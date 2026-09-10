package kira.ditto.data.pi

import kira.ditto.data.PiExtensionLoadOptions
import kira.ditto.data.AetherDiagnosticLogger
import kira.ditto.data.DiagnosticRedactor
import kira.ditto.runtime.AlpineRuntime
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val PiBridgeAssetPath = "pi-bridge/bridge.mjs"
private const val PiBridgeGuestPath = "/root/.aether/pi-bridge/bridge.mjs"
private const val PiBridgeWorkingDirectory = "/root/.aether/pi-bridge"
private const val PiBridgeNodeMinVersion = "22.19.0"
private const val PiBridgeVersion = "2.0.0-alpha.0"
private const val PiAiVersion = "0.84.1"
private const val PiAgentCoreVersion = "0.84.1"
private const val PiCodingAgentVersion = "0.84.1"
private const val PiBridgeRequestTimeoutMillis = 10 * 60 * 1000L
private const val PiBridgeOAuthTimeoutMillis = 15 * 60 * 1000L
private const val PiBridgePingTimeoutMillis = 15_000L
private const val CancelledRequestRetentionMillis = 5 * 60 * 1000L

private data class PendingPiBridgeRequest(
    val response: CompletableDeferred<PiBridgeFrame>,
    val processGeneration: Long,
    val eventChannel: Channel<PiBridgeFrame>? = null,
    val eventJob: Job? = null,
)

private data class ActivePiBridgeProcess(
    val process: Process,
    val writer: BufferedWriter,
    val generation: Long,
)

class PiKernelBridge(
    private val alpineRuntime: AlpineRuntime,
    private val diagnosticLogger: AetherDiagnosticLogger = AetherDiagnosticLogger.NoOp,
    private val remoteKimiClient: kira.ditto.data.remote.RemoteKimiWebClient =
        kira.ditto.data.remote.RemoteKimiWebClient(diagnosticLogger),
) {
    private val mutex = Mutex()
    private val pendingRequests = ConcurrentHashMap<String, PendingPiBridgeRequest>()
    private val cancelledRequestIds = ConcurrentHashMap<String, Long>()
    private val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processStateLock = Any()
    private val nextProcessGeneration = AtomicLong(0L)
    @Volatile
    private var activeProcess: ActivePiBridgeProcess? = null

    @Volatile
    private var lastTurnPayload: JSONObject? = null

    suspend fun ping(
        onSetupProgress: (PiCoreSetupUpdate) -> Unit = {},
    ): JSONObject {
        onSetupProgress(PiCoreSetupUpdate(PiCoreSetupPhase.CheckingAlpine))
        val setup = alpineRuntime.inspectSetup()
        if (!setup.isReady) {
            throw PiBridgeException(
                setup.detail.ifBlank { "Initialize Alpine before starting the agent runtime." },
                code = "alpine_not_ready",
            )
        }
        onSetupProgress(PiCoreSetupUpdate(PiCoreSetupPhase.CheckingNode))
        val version = readNodeVersion()
        if (version.isNullOrBlank()) {
            throw PiBridgeException(
                "Node.js is not available inside Alpine.",
                code = "node_missing",
            )
        }
        onSetupProgress(PiCoreSetupUpdate(PiCoreSetupPhase.Ready, output = "Kimi ACP runtime is ready.\n"))
        return JSONObject()
            .put("ok", true)
            .put("node_version", version)
            .put("bridge_version", "kimi-acp")
    }

    suspend fun listProviders(startIfNeeded: Boolean = true): JSONObject =
        request(
            type = "list_providers",
            timeoutMillis = PiBridgePingTimeoutMillis,
            startIfNeeded = startIfNeeded,
        )

    suspend fun loginProvider(
        providerConfigId: String,
        providerId: String,
        authMethod: String,
        oauthFlow: String = "",
        onEvent: suspend (String, JSONObject) -> Unit,
    ): JSONObject =
        request(
            type = "login_provider",
            payload = JSONObject()
                .put("provider_config_id", providerConfigId)
                .put("provider_id", providerId)
                .put("auth_method", authMethod)
                .put("oauth_flow", oauthFlow),
            timeoutMillis = PiBridgeOAuthTimeoutMillis,
            onEvent = onEvent,
            abortOnCancellation = true,
        )

    suspend fun clearProviderCredential(providerConfigId: String): JSONObject =
        request(
            type = "clear_provider_credential",
            payload = JSONObject().put("provider_config_id", providerConfigId),
            timeoutMillis = PiBridgePingTimeoutMillis,
        )

    suspend fun submitAuthPrompt(
        promptId: String,
        value: String,
        cancelled: Boolean = false,
    ): JSONObject =
        request(
            type = "auth_prompt_result",
            payload = JSONObject()
                .put("prompt_id", promptId)
                .put("value", value)
                .put("cancelled", cancelled),
            timeoutMillis = PiBridgePingTimeoutMillis,
            abortOnCancellation = false,
        )

    suspend fun probeRemote(baseUrl: String, token: String): JSONObject =
        remoteKimiClient.probe(baseUrl, token)

    suspend fun listRemoteWorkspaces(baseUrl: String, token: String): List<kira.ditto.data.RemoteWorkspace> =
        remoteKimiClient.listWorkspaces(baseUrl, token)

    suspend fun listRemoteModels(
        baseUrl: String,
        token: String,
    ): kira.ditto.data.RemoteKimiModelCatalog =
        remoteKimiClient.listModels(baseUrl, token)

    suspend fun updateRemoteSessionModel(
        baseUrl: String,
        token: String,
        sessionId: String,
        modelId: String,
    ) {
        remoteKimiClient.updateSessionModel(baseUrl, token, sessionId, modelId)
    }

    suspend fun updateRemotePermission(
        baseUrl: String,
        token: String,
        permissionMode: String,
        kimiSessionId: String = "",
    ) {
        val liveSession = kimiSessionId.trim().takeIf { id ->
            id.isNotBlank() &&
                !id.startsWith("remote-pending-") &&
                !id.startsWith("aether-")
        }
        if (liveSession != null) {
            remoteKimiClient.updateSessionAgentConfig(
                baseUrl = baseUrl,
                token = token,
                sessionId = liveSession,
                permissionMode = permissionMode,
            )
        }
        remoteKimiClient.updatePermissionConfig(baseUrl, token, permissionMode)
    }

    suspend fun updateRemoteGoalControl(
        baseUrl: String,
        token: String,
        kimiSessionId: String,
        action: String,
    ) {
        val liveSession = kimiSessionId.trim().takeIf { id ->
            id.isNotBlank() &&
                !id.startsWith("remote-pending-") &&
                !id.startsWith("aether-")
        } ?: return
        remoteKimiClient.updateSessionAgentConfig(
            baseUrl = baseUrl,
            token = token,
            sessionId = liveSession,
            goalControl = action,
        )
    }

    suspend fun completeOnce(
        payload: JSONObject,
        onEvent: (suspend (String, JSONObject) -> Unit)? = null,
    ): JSONObject = alpineRuntime.runKimiTurn(
        payload = JSONObject(payload.toString()).apply {
            if (optString("session_id").isBlank()) put("session_id", "completion-${UUID.randomUUID()}")
            if (optString("workspace_directory").isBlank()) put("workspace_directory", alpineRuntime.workspaceRoot)
        },
        onEvent = onEvent ?: { _, _ -> },
    )

    suspend fun runTurn(
        payload: JSONObject,
        onEvent: suspend (String, JSONObject) -> Unit,
    ): JSONObject {
        lastTurnPayload = JSONObject(payload.toString())
        return alpineRuntime.runKimiTurn(payload, onEvent)
    }

    suspend fun ensureKimiAcpReady(): Boolean = alpineRuntime.ensureKimiAcpReady()

    fun abortActiveTurn() {
        remoteKimiClient.abortActiveTurn()
        alpineRuntime.abortActiveKimiTurn()
    }

    fun setIdleCronPromptHandler(listener: ((aetherSessionId: String, promptText: String) -> Unit)?) {
        alpineRuntime.setIdleCronPromptHandler(listener)
    }

    suspend fun steer(
        sessionId: String,
        message: JSONObject,
    ): JSONObject {
        val content = message.optJSONArray("content") ?: org.json.JSONArray().apply {
            val text = message.optString("text")
            if (text.isNotBlank()) {
                put(JSONObject().put("type", "text").put("text", text))
            }
        }
        val prompt = kira.ditto.data.kimi.KimiAcpProtocol.promptBlocksFromUserContent(content)
        if (prompt.length() == 0) {
            return JSONObject().put("accepted", false).put("session_id", sessionId)
        }
        alpineRuntime.enqueueSteerPrompt(prompt)
        alpineRuntime.abortActiveKimiTurn()
        return JSONObject().put("accepted", true).put("session_id", sessionId)
    }

    suspend fun followUp(
        sessionId: String,
        message: JSONObject,
        onEvent: suspend (String, JSONObject) -> Unit,
    ): JSONObject {
        val base = lastTurnPayload
            ?: error("No previous Kimi turn to follow up.")
        val payload = JSONObject(base.toString()).apply {
            put("session_id", sessionId)
            put("messages", org.json.JSONArray().put(message))
            if (optString("workspace_directory").isBlank()) {
                put("workspace_directory", alpineRuntime.workspaceRoot)
            }
        }
        return runTurn(payload, onEvent)
    }

    suspend fun getSessionState(sessionId: String): JSONObject =
        request(
            type = "get_session_state",
            payload = JSONObject().put("session_id", sessionId),
            timeoutMillis = PiBridgePingTimeoutMillis,
            abortOnCancellation = false,
        )

    suspend fun navigateSession(
        sessionId: String,
        entryId: String,
        reset: Boolean = false,
        summarize: Boolean = false,
        customInstructions: String = "",
        sessionPayload: JSONObject = JSONObject(),
    ): JSONObject = request(
        type = "navigate_session",
        payload = JSONObject(sessionPayload.toString()).apply {
            put("session_id", sessionId)
            put("entry_id", entryId)
            put("reset", reset)
            put("summarize", summarize)
            if (customInstructions.isNotBlank()) put("custom_instructions", customInstructions)
        },
        timeoutMillis = null,
        abortOnCancellation = false,
    )

    suspend fun reloadSession(sessionId: String): JSONObject = request(
        type = "reload_session",
        payload = JSONObject().put("session_id", sessionId),
        timeoutMillis = PiBridgePingTimeoutMillis,
        abortOnCancellation = false,
    )

    suspend fun exportSessionJsonl(sessionId: String): JSONObject = request(
        type = "export_session_jsonl",
        payload = JSONObject().put("session_id", sessionId),
        timeoutMillis = PiBridgePingTimeoutMillis,
        abortOnCancellation = false,
    )

    suspend fun importSessionJsonl(sessionId: String, jsonl: String): JSONObject = request(
        type = "import_session_jsonl",
        payload = JSONObject().put("session_id", sessionId).put("jsonl", jsonl),
        timeoutMillis = PiBridgePingTimeoutMillis,
        abortOnCancellation = false,
    )

    suspend fun closeSession(
        sessionId: String,
        sessionFile: String = "",
        deleteFile: Boolean = false,
    ): JSONObject =
        request(
            type = "close_session",
            payload = JSONObject()
                .put("session_id", sessionId)
                .put("session_file", sessionFile)
                .put("delete_file", deleteFile),
            timeoutMillis = PiBridgePingTimeoutMillis,
            abortOnCancellation = false,
            startIfNeeded = deleteFile,
        )

    suspend fun listExtensions(sessionId: String): JSONObject =
        request(
            type = "list_extensions",
            payload = JSONObject().put("session_id", sessionId),
            timeoutMillis = PiBridgePingTimeoutMillis,
            abortOnCancellation = false,
        )

    suspend fun reloadExtensions(sessionId: String): JSONObject =
        request(
            type = "reload_extensions",
            payload = JSONObject().put("session_id", sessionId),
            timeoutMillis = PiBridgeRequestTimeoutMillis,
            abortOnCancellation = false,
        )

    suspend fun invokeExtensionCommand(
        sessionId: String,
        command: String,
        args: String = "",
    ): JSONObject =
        request(
            type = "invoke_extension_command",
            payload = JSONObject()
                .put("session_id", sessionId)
                .put("command", command)
                .put("args", args),
            timeoutMillis = PiBridgeRequestTimeoutMillis,
            abortOnCancellation = false,
        )

    suspend fun listExtensionPackages(): JSONObject =
        request(
            type = "list_extension_packages",
            timeoutMillis = PiBridgeRequestTimeoutMillis,
            abortOnCancellation = false,
        )

    suspend fun listDiscoveredSkills(
        workspaceDirectory: String = alpineRuntime.workspaceRoot,
    ): JSONObject = request(
        type = "list_discovered_skills",
        payload = JSONObject()
            .put("workspace_directory", workspaceDirectory)
            .put("workspace_trusted", true),
        timeoutMillis = PiBridgeRequestTimeoutMillis,
        abortOnCancellation = false,
    )

    suspend fun installExtensionPackage(
        source: String,
        loadOptions: PiExtensionLoadOptions = PiExtensionLoadOptions(),
    ): JSONObject =
        request(
            type = "install_extension_package",
            payload = extensionLoadOptionsPayload(loadOptions).put("source", source),
            timeoutMillis = PiBridgeRequestTimeoutMillis,
            abortOnCancellation = false,
        )

    suspend fun removeExtensionPackage(
        source: String,
        loadOptions: PiExtensionLoadOptions = PiExtensionLoadOptions(),
    ): JSONObject =
        request(
            type = "remove_extension_package",
            payload = extensionLoadOptionsPayload(loadOptions).put("source", source),
            timeoutMillis = PiBridgeRequestTimeoutMillis,
            abortOnCancellation = false,
        )

    suspend fun updateExtensionPackage(
        source: String,
        loadOptions: PiExtensionLoadOptions = PiExtensionLoadOptions(),
    ): JSONObject =
        request(
            type = "update_extension_package",
            payload = extensionLoadOptionsPayload(loadOptions).put("source", source),
            timeoutMillis = PiBridgeRequestTimeoutMillis,
            abortOnCancellation = false,
        )

    suspend fun reloadAllExtensions(
        loadOptions: PiExtensionLoadOptions = PiExtensionLoadOptions(),
    ): JSONObject =
        request(
            type = "reload_all_extensions",
            payload = extensionLoadOptionsPayload(loadOptions),
            timeoutMillis = PiBridgeRequestTimeoutMillis,
            abortOnCancellation = false,
        )

    suspend fun reloadAetherExtensions(
        context: JSONObject,
        loadOptions: PiExtensionLoadOptions = PiExtensionLoadOptions(),
        onEvent: suspend (String, JSONObject) -> Unit,
    ): JSONObject =
        request(
            type = "reload_aether_extensions",
            payload = extensionLoadOptionsPayload(loadOptions).put("context", context),
            timeoutMillis = PiBridgeRequestTimeoutMillis,
            onEvent = onEvent,
            abortOnCancellation = false,
        )

    suspend fun getAetherExtensions(
        context: JSONObject,
        loadOptions: PiExtensionLoadOptions = PiExtensionLoadOptions(),
        onEvent: suspend (String, JSONObject) -> Unit,
    ): JSONObject =
        request(
            type = "get_aether_extensions",
            payload = extensionLoadOptionsPayload(loadOptions).put("context", context),
            timeoutMillis = PiBridgeRequestTimeoutMillis,
            onEvent = onEvent,
            abortOnCancellation = false,
        )

    suspend fun invokeAetherExtensionAction(
        extensionId: String,
        action: String,
        args: JSONObject,
        context: JSONObject,
        onEvent: suspend (String, JSONObject) -> Unit,
    ): JSONObject =
        request(
            type = "invoke_aether_extension_action",
            payload = JSONObject()
                .put("extension_id", extensionId)
                .put("action", action)
                .put("args", args)
                .put("context", context),
            timeoutMillis = PiBridgeRequestTimeoutMillis,
            onEvent = onEvent,
            abortOnCancellation = false,
        )

    suspend fun dispatchAetherExtensionEvent(
        event: String,
        data: JSONObject,
        context: JSONObject,
        onEvent: suspend (String, JSONObject) -> Unit,
    ): JSONObject =
        request(
            type = "dispatch_aether_extension_event",
            payload = JSONObject()
                .put("event", event)
                .put("data", data)
                .put("context", context),
            timeoutMillis = PiBridgeRequestTimeoutMillis,
            onEvent = onEvent,
            abortOnCancellation = false,
        )

    suspend fun subscribeAetherExtensions(
        onEvent: suspend (String, JSONObject) -> Unit,
    ) {
        request(
            type = "subscribe_aether_extensions",
            timeoutMillis = null,
            onEvent = onEvent,
            abortOnCancellation = false,
        )
    }

    suspend fun sendAetherHostResult(
        callId: String,
        result: JSONObject = JSONObject(),
        error: String = "",
    ): JSONObject =
        request(
            type = "aether_host_result",
            payload = JSONObject()
                .put("call_id", callId)
                .put("ok", error.isBlank())
                .put("result", result)
                .put("error", error),
            timeoutMillis = PiBridgePingTimeoutMillis,
            abortOnCancellation = false,
        )

    private fun extensionLoadOptionsPayload(
        loadOptions: PiExtensionLoadOptions,
    ): JSONObject = JSONObject().apply {
        put("disabled_extension_paths", org.json.JSONArray(loadOptions.disabledExtensionPaths.toList()))
        put("disabled_package_sources", org.json.JSONArray(loadOptions.disabledPackageSources.toList()))
    }

    suspend fun sendHostToolResult(payload: JSONObject) {
        request(
            type = "host_tool_result",
            payload = payload,
            timeoutMillis = PiBridgePingTimeoutMillis,
            abortOnCancellation = false,
        )
    }

    suspend fun sendHostToolProgress(payload: JSONObject) {
        request(
            type = "host_tool_progress",
            payload = payload,
            timeoutMillis = PiBridgePingTimeoutMillis,
            abortOnCancellation = false,
        )
    }

    suspend fun sendRuntimeOperationChunk(payload: JSONObject) {
        request(
            type = "runtime_op_chunk",
            payload = payload,
            timeoutMillis = PiBridgePingTimeoutMillis,
            abortOnCancellation = false,
        )
    }

    suspend fun sendRuntimeOperationResult(payload: JSONObject) {
        request(
            type = "runtime_op_result",
            payload = payload,
            timeoutMillis = PiBridgePingTimeoutMillis,
            abortOnCancellation = false,
        )
    }

    suspend fun sendRuntimeOperationCancel(payload: JSONObject) {
        request(
            type = "runtime_op_cancel",
            payload = payload,
            timeoutMillis = PiBridgePingTimeoutMillis,
            abortOnCancellation = false,
        )
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        mutex.withLock {
            eventScope.coroutineContext.cancelChildren()
            pendingRequests.values.forEach { pending ->
                pending.response.completeExceptionally(PiBridgeException("Pi bridge stopped."))
                pending.eventChannel?.close()
            }
            pendingRequests.clear()
            cancelledRequestIds.clear()
            val stoppedProcess = synchronized(processStateLock) {
                activeProcess.also { activeProcess = null }
            }
            runCatching { stoppedProcess?.writer?.close() }
            runCatching { stoppedProcess?.process?.destroy() }
        }
    }

    private suspend fun request(
        type: String,
        payload: JSONObject = JSONObject(),
        timeoutMillis: Long?,
        onEvent: (suspend (String, JSONObject) -> Unit)? = null,
        abortOnCancellation: Boolean = false,
        onSetupProgress: (PiCoreSetupUpdate) -> Unit = {},
        startIfNeeded: Boolean = true,
    ): JSONObject {
        throw PiBridgeException(
            "The Pi JSON-RPC bridge has been removed. Chat uses Kimi ACP.",
            code = "pi_bridge_removed",
        )
    }

    private suspend fun ensureStartedLocked(
        onSetupProgress: (PiCoreSetupUpdate) -> Unit = {},
    ): ActivePiBridgeProcess {
        mutex.withLock {
            diagnosticLogger.event(
                category = "pi_bridge",
                event = "ensure_started_locked_enter",
                details = mapOf(
                    "current_process_alive" to (currentLiveProcess() != null),
                ),
            )
            currentLiveProcess()?.let {
                diagnosticLogger.event(
                    category = "pi_bridge",
                    event = "ensure_started_locked_reuse",
                    details = mapOf("process_generation" to it.generation),
                )
                return it
            }
            val staleProcess = synchronized(processStateLock) {
                activeProcess.also { activeProcess = null }
            }
            runCatching { staleProcess?.writer?.close() }
            runCatching { staleProcess?.process?.destroy() }

            diagnosticLogger.event(
                category = "pi_bridge",
                event = "ensure_node_available_start",
            )
            ensureNodeAvailable(onSetupProgress)
            diagnosticLogger.event(
                category = "pi_bridge",
                event = "ensure_node_available_end",
            )
            onSetupProgress(PiCoreSetupUpdate(PiCoreSetupPhase.PreparingBridge))
            alpineRuntime.installAsset(
                assetPath = PiBridgeAssetPath,
                guestPath = PiBridgeGuestPath,
                executable = false,
            )
            alpineRuntime.installPreinstalledExtensions()
            onSetupProgress(PiCoreSetupUpdate(PiCoreSetupPhase.StartingBridge))
            diagnosticLogger.event(
                category = "pi_bridge",
                event = "start_managed_process_begin",
                details = mapOf(
                    "command" to "node ${shellQuote(PiBridgeGuestPath)}",
                    "working_directory" to PiBridgeWorkingDirectory,
                ),
            )
            val started = alpineRuntime.startManagedProcess(
                command = "node ${shellQuote(PiBridgeGuestPath)}",
                workingDirectory = PiBridgeWorkingDirectory,
            )
            diagnosticLogger.event(
                category = "pi_bridge",
                event = "start_managed_process_end",
                details = mapOf(
                    "process_alive" to started.isAlive,
                ),
            )
            val startedProcess = ActivePiBridgeProcess(
                process = started,
                writer = BufferedWriter(OutputStreamWriter(started.outputStream, Charsets.UTF_8)),
                generation = nextProcessGeneration.incrementAndGet(),
            )
            synchronized(processStateLock) {
                activeProcess = startedProcess
            }
            startStdoutReader(startedProcess)
            startStderrReader(startedProcess)
            diagnosticLogger.event(
                category = "pi_bridge",
                event = "process_started",
                details = mapOf(
                    "guest_path" to PiBridgeGuestPath,
                    "bridge_version" to PiBridgeVersion,
                    "pi_ai_version" to PiAiVersion,
                    "pi_agent_core_version" to PiAgentCoreVersion,
                    "pi_coding_agent_version" to PiCodingAgentVersion,
                    "node_version" to (readNodeVersion() ?: "unknown"),
                    "process_generation" to startedProcess.generation,
                ),
            )
            return startedProcess
        }
    }

    private fun currentLiveProcess(): ActivePiBridgeProcess? =
        synchronized(processStateLock) {
            activeProcess?.takeIf { it.process.isAlive }
        }

    private suspend fun ensureNodeAvailable(
        onSetupProgress: (PiCoreSetupUpdate) -> Unit,
    ) {
        onSetupProgress(PiCoreSetupUpdate(PiCoreSetupPhase.CheckingAlpine))
        // Require explicit Alpine initialize; never download/install here.
        val setup = alpineRuntime.inspectSetup()
        diagnosticLogger.event(
            category = "pi_bridge",
            event = "alpine_setup_inspected",
            details = mapOf(
                "is_ready" to setup.isReady,
                "detail" to setup.detail,
            ),
        )
        if (!setup.isReady) {
            throw PiBridgeException(
                setup.detail.ifBlank {
                    "Initialize Alpine before starting the agent runtime."
                },
                code = "alpine_not_ready",
            )
        }
        onSetupProgress(PiCoreSetupUpdate(PiCoreSetupPhase.CheckingNode))
        val version = readNodeVersion()
        diagnosticLogger.event(
            category = "pi_bridge",
            event = "node_version_checked",
            details = mapOf(
                "version" to version.orEmpty(),
                "min_version" to PiBridgeNodeMinVersion,
            ),
        )
        if (version == null || compareSemver(version, PiBridgeNodeMinVersion) < 0) {
            onSetupProgress(PiCoreSetupUpdate(PiCoreSetupPhase.InstallingNode))
            diagnosticLogger.event(
                category = "pi_bridge",
                event = "node_profile_install_start",
                level = "warn",
                details = mapOf(
                    "current_version" to version.orEmpty(),
                    "required_version" to PiBridgeNodeMinVersion,
                ),
            )
            val installState = alpineRuntime.installPackageProfile("node") { progress ->
                onSetupProgress(
                    PiCoreSetupUpdate(
                        phase = PiCoreSetupPhase.InstallingNode,
                        activity = PiCoreSetupActivity.Downloading,
                        bytesPerSecond = progress.bytesPerSecond,
                        output = progress.output,
                    )
                )
            }
            if (!installState.isReady) {
                throw PiBridgeException(
                    installState.detail.ifBlank { "Failed to install Node.js inside Alpine." },
                    code = "node_install_failed",
                )
            }
            val installedVersion = readNodeVersion()
            if (installedVersion == null || compareSemver(installedVersion, PiBridgeNodeMinVersion) < 0) {
                throw PiBridgeException(
                    "Pi bridge requires Alpine node >= $PiBridgeNodeMinVersion, found ${installedVersion ?: "none"}.",
                    code = "node_version_too_old",
                )
            }
        }

        if (!alpineRuntime.isPackageProfileInstalled("git_search")) {
            diagnosticLogger.event(
                category = "pi_bridge",
                event = "git_search_install_start",
                level = "warn",
            )
            val installState = alpineRuntime.installPackageProfile("git_search") { progress ->
                onSetupProgress(
                    PiCoreSetupUpdate(
                        phase = PiCoreSetupPhase.InstallingNode,
                        activity = PiCoreSetupActivity.Downloading,
                        bytesPerSecond = progress.bytesPerSecond,
                        output = progress.output,
                    )
                )
            }
            if (!installState.isReady) {
                throw PiBridgeException(
                    installState.detail.ifBlank { "Failed to install Pi search tools inside Alpine." },
                    code = "pi_search_tools_install_failed",
                )
            }
        }
    }

    private suspend fun readNodeVersion(): String? {
        val raw = JSONObject(
            alpineRuntime.executeCommand(
                command = "node --version",
                workingDirectory = alpineRuntime.homeDirectory,
                awaitTimeoutMillis = 30_000L,
            )
        )
        if (!raw.optBoolean("ok")) return null
        return raw.optString("stdout")
            .lineSequence()
            .firstOrNull { it.trim().isNotBlank() }
            ?.trim()
            ?.removePrefix("v")
    }

    private fun startStdoutReader(startedProcess: ActivePiBridgeProcess) {
        Thread(
            {
                diagnosticLogger.event(
                    category = "pi_bridge",
                    event = "stdout_reader_started",
                    details = mapOf("process_generation" to startedProcess.generation),
                )
                val parser = PiJsonlParser(
                    onFrame = { frame ->
                        handleFrameFromReader(frame, startedProcess.generation)
                    },
                    onInvalidLine = { line, throwable ->
                        diagnosticLogger.exception(
                            category = "pi_bridge",
                            event = "invalid_stdout_json",
                            throwable = throwable,
                            details = mapOf(
                                "line" to DiagnosticRedactor.sanitizeString(line.take(700)),
                            ),
                        )
                    },
                )
                runCatching {
                    BufferedReader(
                        InputStreamReader(startedProcess.process.inputStream, Charsets.UTF_8)
                    ).useLines { lines ->
                        lines.forEach { line -> parser.accept(line + "\n") }
                    }
                    parser.flush()
                }.onFailure { throwable ->
                    diagnosticLogger.exception(
                        category = "pi_bridge",
                        event = "stdout_reader_failed",
                        throwable = throwable,
                        details = mapOf("process_generation" to startedProcess.generation),
                    )
                }
                diagnosticLogger.event(
                    category = "pi_bridge",
                    event = "stdout_reader_exiting",
                    level = "warn",
                    details = mapOf("process_generation" to startedProcess.generation),
                )
                eventScope.launch {
                    failPendingRequests(
                        exitedProcess = startedProcess,
                        message = "Pi bridge process exited.",
                    )
                }
            },
            "aether-pi-bridge-stdout-${startedProcess.generation}",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun startStderrReader(startedProcess: ActivePiBridgeProcess) {
        Thread(
            {
                runCatching {
                    BufferedReader(
                        InputStreamReader(startedProcess.process.errorStream, Charsets.UTF_8)
                    ).useLines { lines ->
                        lines.forEach { line ->
                            diagnosticLogger.event(
                                category = "pi_bridge",
                                event = "stderr",
                                level = "warn",
                                details = mapOf(
                                    "line" to DiagnosticRedactor.sanitizeString(line),
                                    "process_generation" to startedProcess.generation,
                                ),
                            )
                        }
                    }
                }
            },
            "aether-pi-bridge-stderr-${startedProcess.generation}",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun handleFrameFromReader(
        frame: PiBridgeFrame,
        processGeneration: Long,
    ) {
        diagnosticLogger.event(
            category = "pi_bridge",
            event = "frame_received",
            requestId = frame.id,
            details = mapOf(
                "frame_type" to frame.type,
                "frame_event" to frame.event,
                "process_generation" to processGeneration,
                "has_pending_request" to (pendingRequests[frame.id] != null),
            ),
        )
        val pending = pendingRequests[frame.id]
        if (pending != null && pending.processGeneration != processGeneration) {
            diagnosticLogger.event(
                category = "pi_bridge",
                event = "frame_generation_mismatch",
                level = "warn",
                requestId = frame.id,
                details = mapOf(
                    "frame_generation" to processGeneration,
                    "request_generation" to pending.processGeneration,
                ),
            )
            return
        }
        when {
            pending != null && pending.eventChannel != null -> {
                if (pending.eventChannel.trySend(frame).isFailure) {
                    diagnosticLogger.event(
                        category = "pi_bridge",
                        event = "event_channel_send_failed",
                        level = "warn",
                        requestId = frame.id,
                        details = mapOf(
                            "frame_type" to frame.type,
                            "frame_event" to frame.event,
                        ),
                    )
                    pending.response.completeExceptionally(
                        PiBridgeException("Pi bridge event queue was closed.", code = "event_queue_closed")
                    )
                }
            }

            pending != null && (frame.type == "response" || frame.type == "error") ->
                pending.response.complete(frame)

            pending != null && frame.type == "event" -> Unit

            pending == null && isRecentlyCancelledRequest(frame.id) -> Unit

            else -> diagnosticLogger.event(
                category = "pi_bridge",
                event = "unknown_frame",
                level = "warn",
                details = mapOf(
                    "frame_type" to frame.type,
                    "request_id" to frame.id,
                ),
            )
        }
    }

    private fun markRequestCancelled(requestId: String) {
        val now = System.currentTimeMillis()
        cancelledRequestIds[requestId] = now
        cancelledRequestIds.forEach { (candidateId, cancelledAt) ->
            if (now - cancelledAt > CancelledRequestRetentionMillis) {
                cancelledRequestIds.remove(candidateId, cancelledAt)
            }
        }
    }

    private fun isRecentlyCancelledRequest(requestId: String): Boolean {
        val cancelledAt = cancelledRequestIds[requestId] ?: return false
        if (System.currentTimeMillis() - cancelledAt <= CancelledRequestRetentionMillis) {
            return true
        }
        cancelledRequestIds.remove(requestId, cancelledAt)
        return false
    }

    private suspend fun failPendingRequests(
        exitedProcess: ActivePiBridgeProcess,
        message: String,
    ) {
        diagnosticLogger.event(
            category = "pi_bridge",
            event = "fail_pending_requests_start",
            level = "warn",
            details = mapOf(
                "message" to message,
                "exited_process_generation" to exitedProcess.generation,
                "pending_count" to pendingRequests.size,
            ),
        )
        mutex.withLock {
            val isCurrentProcess = synchronized(processStateLock) {
                activeProcess?.process === exitedProcess.process
            }
            if (!isCurrentProcess) {
                diagnosticLogger.event(
                    category = "pi_bridge",
                    event = "fail_pending_requests_skip",
                    level = "warn",
                    details = mapOf(
                        "reason" to "not_current_process",
                        "exited_process_generation" to exitedProcess.generation,
                    ),
                )
                return
            }
            pendingRequests.forEach { (requestId, pending) ->
                if (pending.processGeneration != exitedProcess.generation) return@forEach
                if (!pendingRequests.remove(requestId, pending)) return@forEach
                pending.response.completeExceptionally(PiBridgeException(message, code = "bridge_exited"))
                pending.eventChannel?.close()
            }
            synchronized(processStateLock) {
                activeProcess = null
            }
        }
    }

    private fun nextRequestId(type: String): String =
        "${type}-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"

    private fun requestDiagnosticDetails(
        type: String,
        payload: JSONObject,
    ): Map<String, Any?> {
        val modelConfig = payload.optJSONObject("model_config")
        return mapOf(
            "request_type" to type,
            "session_id" to payload.optString("session_id"),
            "provider" to modelConfig?.optString("pi_provider_id").orEmpty(),
            "model" to modelConfig?.optString("model_id").orEmpty(),
        ).filterValues(String::isNotBlank)
    }
}

private fun shellQuote(value: String): String =
    "'" + value.replace("'", "'\"'\"'") + "'"

private fun compareSemver(left: String, right: String): Int {
    val leftParts = left.split('.', '-').mapNotNull { it.toIntOrNull() }
    val rightParts = right.split('.', '-').mapNotNull { it.toIntOrNull() }
    val maxSize = maxOf(leftParts.size, rightParts.size, 3)
    for (index in 0 until maxSize) {
        val leftValue = leftParts.getOrNull(index) ?: 0
        val rightValue = rightParts.getOrNull(index) ?: 0
        if (leftValue != rightValue) return leftValue.compareTo(rightValue)
    }
    return 0
}
