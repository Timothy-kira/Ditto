package kira.ditto.data.pi

import kira.ditto.data.ActiveSkillContext
import kira.ditto.data.AetherAgentTurnResult
import kira.ditto.data.AetherDiagnosticLogger
import kira.ditto.data.AetherAppExtensionManager
import kira.ditto.data.AetherSelfManagementTool
import kira.ditto.data.AetherToolExecutor
import kira.ditto.data.AgentToolEvent
import kira.ditto.data.AppSettings
import kira.ditto.data.LlmMessage
import kira.ditto.data.LlmTextPart
import kira.ditto.data.LocalRuntimeId
import kira.ditto.data.PiExtensionStateRepository
import kira.ditto.data.StreamingStatus
import kira.ditto.data.SettingsRepository
import kira.ditto.data.toolDiffsJson
import kira.ditto.termux.TermuxRuntimeOperations
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private const val InjectedMessagePollIntervalMillis = 150L
private const val AetherExtensionGuestDirectory = "/root/.aether/extensions"

class PiAgentRunner(
    private val bridge: PiKernelBridge,
    private val toolExecutor: AetherToolExecutor? = null,
    private val settingsRepository: SettingsRepository? = null,
    private val piExtensionStateRepository: PiExtensionStateRepository? = null,
    private val appExtensionManager: AetherAppExtensionManager? = null,
    private val termuxRuntimeOperations: TermuxRuntimeOperations? = null,
    private val diagnosticLogger: AetherDiagnosticLogger = AetherDiagnosticLogger.NoOp,
) {
    suspend fun runTurn(
        settings: AppSettings,
        messages: List<LlmMessage>,
        workspaceDirectory: String,
        termuxWorkspaceDirectory: String,
        runtimeId: LocalRuntimeId = LocalRuntimeId.Alpine,
        skillPaths: List<String> = emptyList(),
        activeSkills: List<ActiveSkillContext> = emptyList(),
        selfManagementTool: AetherSelfManagementTool? = null,
        agentModeEnabled: Boolean = false,
        chromeEnabled: Boolean = false,
        mcpServers: JSONArray = JSONArray(),
        sessionId: String = "",
        sessionFile: String = "",
        thinkingLevelMap: Map<String, String> = emptyMap(),
        isReasoningModel: Boolean = true,
        onToolEvent: suspend (AgentToolEvent) -> Unit = {},
        onToolProgress: (suspend (AgentToolEvent) -> Unit)? = null,
        onAssistantTextDelta: suspend (String) -> Unit = {},
        onAssistantReasoningDelta: suspend (String) -> Unit = {},
        onAssistantReasoningSummaryDelta: suspend (String) -> Unit = {},
        onAssistantTextReset: suspend () -> Unit = {},
        onAssistantRequestStarted: suspend () -> Unit = {},
        onAssistantResponseReset: suspend () -> Unit = {},
        onStreamingStatus: suspend (StreamingStatus?) -> Unit = {},
        onSessionEvent: suspend (String, JSONObject) -> Unit = { _, _ -> },
        pollInjectedUserMessages: suspend () -> List<LlmMessage> = { emptyList() },
        remoteMachineId: String = "",
        remoteBaseUrl: String = "",
        remoteToken: String = "",
        remoteKimiSessionId: String = "",
        remoteCwd: String = "",
    ): Result<AetherAgentTurnResult> {
        onStreamingStatus(null)
        diagnosticLogger.event(
            category = "pi_agent",
            event = "run_turn_start",
            sessionId = sessionId,
            details = mapOf(
                "session_file" to sessionFile,
                "runtime_id" to runtimeId.storageValue,
                "workspace_directory" to workspaceDirectory,
                "message_count" to messages.size,
            ),
        )
        return try {
            runCatchingPreservingCancellation {
                val resolvedSessionId = sessionId.ifBlank {
                    "aether-session-${System.currentTimeMillis()}"
                }
                var currentRuntimeId = runtimeId
                val appendedPiEntryIds = ConcurrentLinkedQueue<String>()
                val prompt = { "" }
                val payload = JSONObject().apply {
                    val extensionLoadOptions = piExtensionStateRepository?.loadOptions()
                    put("model_config", settings.toPiModelConfig(thinkingLevelMap, isReasoningModel).toJson())
                    put("session_id", resolvedSessionId)
                    if (sessionFile.isNotBlank()) put("session_file", sessionFile)
                    put(
                        "messages",
                        messages.withSelectedSkillCommand(activeSkills.firstOrNull()?.name).toPiJson(),
                    )
                    put(
                        "skill_paths",
                        JSONArray(skillPaths),
                    )
                    put("workspace_directory", workspaceDirectory)
                    put("workspace_trusted", true)
                    put("termux_workspace_directory", termuxWorkspaceDirectory)
                    // Pi extensions are installed in Alpine's guest home even
                    // when the selected chat runtime is Termux.
                    put("extension_paths", JSONArray().put(AetherExtensionGuestDirectory))
                    put("runtime", runtimeId.storageValue)
                    put("platform", "android")
                    put("chrome_enabled", chromeEnabled)
                    put("mcp_servers", mcpServers)
                    put("reasoning", settings.toPiThinkingLevel())
                    put("system_prompt", settings.systemPrompt)
                    put("agent_mode_enabled", agentModeEnabled)
                    if (remoteBaseUrl.isNotBlank()) {
                        put("remote_machine_id", remoteMachineId)
                        put("remote_base_url", remoteBaseUrl)
                        put("remote_token", remoteToken)
                        put("remote_kimi_session_id", remoteKimiSessionId)
                        put("remote_cwd", remoteCwd)
                        put("permission_mode", settings.kimiPermissionMode)
                    }
                    put(
                        "disabled_extension_paths",
                        JSONArray(extensionLoadOptions?.disabledExtensionPaths?.toList().orEmpty()),
                    )
                    put(
                        "disabled_package_sources",
                        JSONArray(extensionLoadOptions?.disabledPackageSources?.toList().orEmpty()),
                    )
                    put(
                        "host_tools",
                        AetherToolExecutor.hostToolDefinitions(
                            selfManagementTool = selfManagementTool,
                            agentModeEnabled = agentModeEnabled,
                        ),
                    )
                }

                coroutineScope {
                    val parallelHostToolJobs = ConcurrentHashMap<String, Job>()
                    val runtimeOperationJobs = ConcurrentHashMap<String, Job>()
                    val pendingRuntimeOperationRequests = ConcurrentHashMap<String, JSONObject>()
                    val pendingRuntimeOperationChunks =
                        ConcurrentHashMap<String, ConcurrentHashMap<Int, ByteArray>>()
                    val handledHostToolRequestIds = ConcurrentHashMap.newKeySet<String>()
                    val sequentialHostToolRequests = Channel<JSONObject>(Channel.UNLIMITED)
                    val sequentialHostToolWorker = launch {
                        for (requestPayload in sequentialHostToolRequests) {
                            handleHostToolRequest(
                                payload = requestPayload,
                                sessionId = resolvedSessionId,
                                settings = settings,
                                workspaceDirectory = workspaceDirectory,
                                termuxWorkspaceDirectory = termuxWorkspaceDirectory,
                                selfManagementTool = selfManagementTool,
                                agentModeEnabled = agentModeEnabled,
                                currentRuntimeId = { currentRuntimeId },
                                onRuntimeChanged = { currentRuntimeId = it },
                                updatedSystemPrompt = prompt,
                            )
                        }
                    }

                    suspend fun dispatchHostToolRequest(eventPayload: JSONObject) {
                        val toolRequestId = eventPayload.optString("tool_request_id").trim()
                        if (toolRequestId.isBlank()) {
                            logMalformedHostToolRequest(eventPayload, resolvedSessionId)
                            return
                        }
                        if (!handledHostToolRequestIds.add(toolRequestId)) return
                        val requestPayload = JSONObject(eventPayload.toString())
                        if (eventPayload.optString("execution_mode") == "sequential") {
                            sequentialHostToolRequests.send(requestPayload)
                            return
                        }
                        val job = launch(start = CoroutineStart.LAZY) {
                            try {
                                handleHostToolRequest(
                                    payload = requestPayload,
                                    sessionId = resolvedSessionId,
                                    settings = settings,
                                    workspaceDirectory = workspaceDirectory,
                                    termuxWorkspaceDirectory = termuxWorkspaceDirectory,
                                    selfManagementTool = selfManagementTool,
                                    agentModeEnabled = agentModeEnabled,
                                    currentRuntimeId = { currentRuntimeId },
                                    onRuntimeChanged = { currentRuntimeId = it },
                                    updatedSystemPrompt = prompt,
                                )
                            } finally {
                                parallelHostToolJobs.remove(toolRequestId)
                            }
                        }
                        parallelHostToolJobs[toolRequestId] = job
                        job.start()
                    }

                    suspend fun startRuntimeOperation(
                        eventPayload: JSONObject,
                        inputData: ByteArray? = null,
                    ) {
                        val operationId = eventPayload.optString("operation_id").trim()
                        if (operationId.isBlank() || runtimeOperationJobs.containsKey(operationId)) return
                        val operationRuntime = eventPayload.optString("runtime").trim()
                        val kind = eventPayload.optString("kind").trim()
                        val operationPayload = eventPayload.optJSONObject("payload") ?: JSONObject()
                        val job = launch(start = CoroutineStart.LAZY) {
                            val result = runCatching {
                                check(
                                    operationRuntime == LocalRuntimeId.Alpine.storageValue ||
                                        operationRuntime == LocalRuntimeId.Termux.storageValue,
                                ) {
                                    "Unsupported host runtime operation: $operationRuntime"
                                }
                                val operations = termuxRuntimeOperations
                                    ?: error("Termux runtime operations are unavailable.")
                                operations.execute(kind, operationPayload, inputData) { sequence, bytes ->
                                    bridge.sendRuntimeOperationChunk(
                                        JSONObject().apply {
                                            put("operation_id", operationId)
                                            put("session_id", resolvedSessionId)
                                            put("runtime", operationRuntime)
                                            put("direction", "output")
                                            put("sequence", sequence)
                                            put("data_base64", Base64.getEncoder().encodeToString(bytes))
                                        }
                                    )
                                }
                            }
                            if (result.isSuccess) {
                                bridge.sendRuntimeOperationResult(
                                    JSONObject().apply {
                                        put("operation_id", operationId)
                                        put("session_id", resolvedSessionId)
                                        put("runtime", operationRuntime)
                                        put("ok", true)
                                        put("result", result.getOrThrow())
                                    }
                                )
                            } else if (result.exceptionOrNull() !is CancellationException) {
                                bridge.sendRuntimeOperationResult(
                                    JSONObject().apply {
                                        put("operation_id", operationId)
                                        put("session_id", resolvedSessionId)
                                        put("runtime", operationRuntime)
                                        put("ok", false)
                                        put("error", result.exceptionOrNull()?.message ?: "Runtime operation failed.")
                                    }
                                )
                            }
                        }
                        job.invokeOnCompletion { runtimeOperationJobs.remove(operationId) }
                        runtimeOperationJobs[operationId] = job
                        job.start()
                    }

                    suspend fun dispatchRuntimeOperation(eventPayload: JSONObject) {
                        val operationId = eventPayload.optString("operation_id").trim()
                        if (operationId.isBlank()) return
                        val inputChunkCount = eventPayload.optInt("input_chunk_count").coerceAtLeast(0)
                        if (inputChunkCount == 0) {
                            startRuntimeOperation(eventPayload)
                            return
                        }
                        pendingRuntimeOperationRequests[operationId] = JSONObject(eventPayload.toString())
                        pendingRuntimeOperationChunks[operationId] = ConcurrentHashMap()
                    }

                    suspend fun dispatchRuntimeOperationInputChunk(eventPayload: JSONObject) {
                        if (eventPayload.optString("direction") != "input") return
                        val operationId = eventPayload.optString("operation_id").trim()
                        val requestPayload = pendingRuntimeOperationRequests[operationId] ?: return
                        val sequence = eventPayload.optInt("sequence", -1)
                        if (sequence < 0) return
                        val chunk = runCatching {
                            Base64.getDecoder().decode(eventPayload.optString("data_base64"))
                        }.getOrNull() ?: return
                        val chunks = pendingRuntimeOperationChunks[operationId] ?: return
                        chunks.putIfAbsent(sequence, chunk)
                        val expected = requestPayload.optInt("input_chunk_count").coerceAtLeast(1)
                        if (chunks.size < expected) return
                        val ordered = (0 until expected).map { chunks[it] ?: return }
                        val byteCount = ordered.sumOf(ByteArray::size)
                        val inputData = ByteArray(byteCount)
                        var offset = 0
                        ordered.forEach { bytes ->
                            bytes.copyInto(inputData, offset)
                            offset += bytes.size
                        }
                        pendingRuntimeOperationRequests.remove(operationId)
                        pendingRuntimeOperationChunks.remove(operationId)
                        startRuntimeOperation(requestPayload, inputData)
                    }

                    val eventHandler: suspend (String, JSONObject) -> Unit = { event, eventPayload ->
                        when (event) {
                            "assistant_text_delta" ->
                                onAssistantTextDelta(eventPayload.optString("delta"))

                            "assistant_reasoning_delta" -> {
                                val delta = eventPayload.optString("delta")
                                onAssistantReasoningDelta(delta)
                                if (eventPayload.optString("kind") == "summary") {
                                    onAssistantReasoningSummaryDelta(delta)
                                }
                            }

                            "assistant_request_start" -> onAssistantRequestStarted()

                            "assistant_stream_reset" -> onAssistantResponseReset()

                            "assistant_retry" ->
                                onStreamingStatus(reconnectStreamingStatus(eventPayload))

                            "tool_call_start" -> {
                                onAssistantTextReset()
                                onToolEvent(eventPayload.toToolEvent(isRunning = true))
                            }

                            "tool_call_delta" -> {
                                val toolEvent = eventPayload.toToolEvent(isRunning = true)
                                if (toolEvent.outputJson == null) {
                                    onToolEvent(toolEvent)
                                } else {
                                    (onToolProgress ?: onToolEvent)(toolEvent)
                                }
                            }

                            "tool_call_end" ->
                                onToolEvent(eventPayload.toToolEvent(isRunning = false))

                            "plan_update",
                            "usage_update",
                            "current_mode_update",
                            "config_option_update",
                            "session_info_update",
                            "available_commands_update",
                            "user_message_chunk" -> onSessionEvent(event, eventPayload)

                            "unknown_session_update" -> diagnosticLogger.event(
                                category = "pi_agent",
                                event = "unknown_session_update",
                                level = "debug",
                                sessionId = resolvedSessionId,
                                details = mapOf(
                                    "kind" to eventPayload.optString("kind"),
                                ),
                            )

                            "host_tool_request" -> dispatchHostToolRequest(eventPayload)

                            "aether_host_call" -> dispatchAetherHostCall(eventPayload, resolvedSessionId)

                            "runtime_op_request" -> dispatchRuntimeOperation(eventPayload)

                            "runtime_op_chunk" -> dispatchRuntimeOperationInputChunk(eventPayload)

                            "runtime_op_cancel" -> {
                                val operationId = eventPayload.optString("operation_id").trim()
                                pendingRuntimeOperationRequests.remove(operationId)
                                pendingRuntimeOperationChunks.remove(operationId)
                                runtimeOperationJobs.remove(operationId)?.cancel()
                            }

                            "session_entry_appended" -> {
                                val entryId = eventPayload.optJSONObject("entry")?.optString("id").orEmpty()
                                if (entryId.isNotBlank()) appendedPiEntryIds += entryId
                            }

                            "assistant_error" -> onStreamingStatus(
                                StreamingStatus(
                                    text = "Agent engine error",
                                    detail = eventPayload.optString("error_message"),
                                )
                            )
                        }
                    }

                    val deferredInjectedMessages = ConcurrentLinkedQueue<LlmMessage>()
                    suspend fun forwardInjectedMessages() {
                        pollInjectedUserMessages().forEach { message ->
                            val accepted = runCatching {
                                bridge.steer(resolvedSessionId, message.toPiJson())
                                    .optBoolean("accepted")
                            }.getOrElse { throwable ->
                                if (throwable is CancellationException) throw throwable
                                false
                            }
                            if (!accepted) {
                                deferredInjectedMessages += message
                            }
                        }
                    }

                    val pollingJob = launch {
                        while (isActive) {
                            forwardInjectedMessages()
                            delay(InjectedMessagePollIntervalMillis)
                        }
                    }
                    try {
                        diagnosticLogger.event(
                            category = "pi_agent",
                            event = "bridge_run_turn_start",
                            sessionId = resolvedSessionId,
                            details = mapOf(
                                "payload_session_id" to payload.optString("session_id"),
                                "payload_session_file" to payload.optString("session_file"),
                            ),
                        )
                        var response = bridge.runTurn(payload, eventHandler)
                        diagnosticLogger.event(
                            category = "pi_agent",
                            event = "bridge_run_turn_end",
                            sessionId = resolvedSessionId,
                            details = mapOf(
                                "response_ok" to response.optBoolean("ok"),
                                "response_session_id" to response.optString("session_id"),
                            ),
                        )
                        forwardInjectedMessages()
                        while (true) {
                            val injected = deferredInjectedMessages.poll() ?: break
                            response = bridge.followUp(
                                sessionId = resolvedSessionId,
                                message = injected.toPiJson(),
                                onEvent = eventHandler,
                            )
                        }

                        val completion = response.toPiCompletionResult()
                        if (
                            settings.providerConfigId.isNotBlank() &&
                            completion.updatedOauthCredentialJson.isNotBlank()
                        ) {
                            settingsRepository?.updateProviderOAuthCredential(
                                settings.providerConfigId,
                                completion.updatedOauthCredentialJson,
                            )
                        }
                        if (completion.errorMessage.isNotBlank()) {
                            error(completion.errorMessage)
                        }
                        AetherAgentTurnResult(
                            assistantText = resolvedAssistantText(
                                assistantText = completion.assistantText,
                                reasoningText = completion.reasoningText,
                                stopReason = completion.stopReason,
                            ),
                            reasoningText = completion.reasoningText,
                            tokenUsage = completion.usage,
                            providerPayloadJson = completion.toProviderPayloadJson(),
                            piSessionId = completion.sessionId,
                            piSessionFile = completion.sessionFile,
                            piSessionLeafId = completion.sessionLeafId,
                            runtime = completion.runtime,
                            cwd = completion.cwd,
                            piEntryIds = appendedPiEntryIds.toList(),
                        )
                    } finally {
                        pollingJob.cancelAndJoin()
                        sequentialHostToolRequests.close()
                        sequentialHostToolWorker.cancelAndJoin()
                        parallelHostToolJobs.values.toList().forEach { job ->
                            job.cancelAndJoin()
                        }
                        runtimeOperationJobs.values.toList().forEach { job ->
                            job.cancelAndJoin()
                        }
                        pendingRuntimeOperationRequests.clear()
                        pendingRuntimeOperationChunks.clear()
                    }
                }
            }
        } finally {
            diagnosticLogger.event(
                category = "pi_agent",
                event = "run_turn_end",
                sessionId = sessionId,
            )
            onStreamingStatus(null)
        }
    }

    private suspend fun handleHostToolRequest(
        payload: JSONObject,
        sessionId: String,
        settings: AppSettings,
        workspaceDirectory: String,
        termuxWorkspaceDirectory: String,
        selfManagementTool: AetherSelfManagementTool?,
        agentModeEnabled: Boolean,
        currentRuntimeId: () -> LocalRuntimeId,
        onRuntimeChanged: suspend (LocalRuntimeId) -> Unit,
        updatedSystemPrompt: () -> String,
    ) {
        val toolRequestId = payload.optString("tool_request_id").trim()
        val toolCallId = payload.optString("tool_call_id").trim()
        val toolName = payload.optString("tool_name").trim()
        if (toolRequestId.isBlank()) {
            logMalformedHostToolRequest(payload, sessionId)
            return
        }

        val argumentsJson = payload.argumentsJson()
        val executor = toolExecutor
        if (executor == null || !AetherToolExecutor.supports(toolName)) {
            bridge.sendHostToolResult(
                hostToolPayload(
                    sessionId = sessionId,
                    toolRequestId = toolRequestId,
                    toolCallId = toolCallId,
                    toolName = toolName.ifBlank { "unknown" },
                    argumentsJson = argumentsJson,
                    rawOutput = JSONObject().apply {
                        put("ok", false)
                        put("errmsg", "Host tool '$toolName' is not available.")
                    }.toString(),
                    isError = true,
                )
            )
            return
        }

        val result = runCatching {
            executor.execute(
                settings = settings,
                workspaceDirectory = workspaceDirectory,
                termuxWorkspaceDirectory = termuxWorkspaceDirectory,
                toolName = toolName,
                argumentsJson = argumentsJson,
                selfManagementTool = selfManagementTool,
                agentModeEnabled = agentModeEnabled,
                currentRuntimeId = currentRuntimeId(),
                onRuntimeChanged = onRuntimeChanged,
                onProgress = { progress ->
                    bridge.sendHostToolProgress(
                        hostToolPayload(
                            sessionId = sessionId,
                            toolRequestId = toolRequestId,
                            toolCallId = toolCallId,
                            toolName = toolName,
                            argumentsJson = argumentsJson,
                            rawOutput = progress,
                            isError = !AetherToolExecutor.inferToolOutputOk(
                                AetherToolExecutor.sanitizeToolOutputForConversation(toolName, progress),
                            ),
                        )
                    )
                },
            )
        }

        val responsePayload = result.fold(
            onSuccess = { executionResult ->
                hostToolPayload(
                    sessionId = sessionId,
                    toolRequestId = toolRequestId,
                    toolCallId = toolCallId,
                    toolName = toolName,
                    argumentsJson = argumentsJson,
                    rawOutput = executionResult.rawOutput,
                    visibleOutput = executionResult.visibleOutput,
                    isError = executionResult.isError,
                    systemPrompt = updatedSystemPrompt(),
                )
            },
            onFailure = { throwable ->
                if (throwable is CancellationException) throw throwable
                hostToolPayload(
                    sessionId = sessionId,
                    toolRequestId = toolRequestId,
                    toolCallId = toolCallId,
                    toolName = toolName,
                    argumentsJson = argumentsJson,
                    rawOutput = JSONObject().apply {
                        put("ok", false)
                        put("errmsg", throwable.message ?: "Tool execution failed.")
                    }.toString(),
                    isError = true,
                )
            },
        )
        bridge.sendHostToolResult(responsePayload)
    }

    private suspend fun dispatchAetherHostCall(
        payload: JSONObject,
        sessionId: String,
    ) {
        val method = payload.optString("method").trim()
        if (method != "aether_chrome_execute") {
            appExtensionManager?.handleAgentBridgeEvent("aether_host_call", payload)
                ?: bridge.sendAetherHostResult(
                    callId = payload.optString("call_id"),
                    error = "The Aether UI host is unavailable.",
                )
            return
        }
        val callId = payload.optString("call_id").trim()
        bridge.sendAetherHostResult(
            callId = callId,
            result = JSONObject()
                .put("ok", false)
                .put("code", "setup_required")
                .put("errmsg", "Use the webmcp MCP tools instead of aether_chrome_execute."),
        )
    }

    private fun logMalformedHostToolRequest(
        payload: JSONObject,
        sessionId: String,
    ) {
        diagnosticLogger.event(
            category = "pi_agent",
            event = "malformed_host_tool_request",
            level = "warn",
            sessionId = sessionId,
            details = mapOf(
                "tool_call_id" to payload.optString("tool_call_id").trim(),
                "tool_name" to payload.optString("tool_name").trim(),
                "reason" to "Missing tool_request_id.",
            ),
        )
    }
}

internal fun reconnectStreamingStatus(payload: JSONObject): StreamingStatus {
    val delayMillis = payload.optInt("delay_ms").coerceAtLeast(0)
    return StreamingStatus(
        text =
            "Reconnecting... ${payload.optInt("attempt")}/${payload.optInt("max_attempts")}",
        detail = buildString {
            append(payload.optString("error_message"))
            if (delayMillis > 0) {
                if (isNotEmpty()) append('\n')
                append("Retrying in ")
                if (delayMillis % 1_000 == 0) {
                    append(delayMillis / 1_000).append('s')
                } else {
                    append(delayMillis).append("ms")
                }
            }
        },
    )
}

private fun JSONObject.toToolEvent(isRunning: Boolean): AgentToolEvent =
    AgentToolEvent(
        id = optString("id").ifBlank { "pi-tool-${optInt("content_index", 0)}" },
        // Keep blank when absent: the execution layer preserves the name from
        // the tool_call start event (ACP updates omit the title entirely).
        name = optString("name"),
        argumentsJson = argumentsJson(),
        outputJson = outputJson(),
        isRunning = isRunning,
        kind = optString("kind"),
        status = optString("status"),
        diffsJson = toolDiffsJson(this),
    )

private fun JSONObject.argumentsJson(): String {
    val explicit = optString("arguments_json").trim()
    if (explicit.isNotBlank()) return explicit
    return when (val arguments = opt("arguments")) {
        is JSONObject -> arguments.toString()
        is JSONArray -> arguments.toString()
        is String -> arguments.ifBlank { "{}" }
        null,
        JSONObject.NULL -> optString("delta").ifBlank { "{}" }
        else -> JSONObject.wrap(arguments)?.toString() ?: "{}"
    }
}

private fun JSONObject.outputJson(): String? {
    val explicit = optString("output_json")
    if (explicit.isNotBlank()) return explicit
    return when (val output = opt("output")) {
        is JSONObject -> output.toString()
        is JSONArray -> output.toString()
        is String -> output.takeIf { it.isNotBlank() }
        else -> null
    }
}

private fun hostToolPayload(
    sessionId: String,
    toolRequestId: String,
    toolCallId: String,
    toolName: String,
    argumentsJson: String,
    rawOutput: String,
    visibleOutput: String = AetherToolExecutor.sanitizeToolOutputForConversation(toolName, rawOutput),
    isError: Boolean,
    systemPrompt: String = "",
): JSONObject = JSONObject().apply {
    put("session_id", sessionId)
    put("tool_request_id", toolRequestId)
    put("tool_call_id", toolCallId)
    put("tool_name", toolName)
    put("arguments_json", argumentsJson)
    put("output_json", visibleOutput)
    put("raw_output_json", rawOutput)
    put("is_error", isError)
    if (systemPrompt.isNotBlank()) put("system_prompt", systemPrompt)
    put(
        "content",
        JSONArray().apply {
            put(
                JSONObject().apply {
                    put("type", "text")
                    put("text", visibleOutput)
                }
            )
            if (toolName == "chrome" || toolName == "browser") {
                val parsed = runCatching { JSONObject(rawOutput) }.getOrNull()
                val imageData = parsed?.optString("screenshot_base64").orEmpty()
                if (parsed?.optBoolean("ok") == true && imageData.isNotBlank()) {
                    put(
                        JSONObject().apply {
                            put("type", "image")
                            put(
                                "mime_type",
                                parsed.optString("screenshot_mime_type").ifBlank { "image/png" },
                            )
                            put("data", imageData)
                        }
                    )
                }
            }
        },
    )
    put(
        "details",
        JSONObject().apply {
            put("tool_request_id", toolRequestId)
            put("tool_call_id", toolCallId)
            put("tool_name", toolName)
            put("arguments_json", argumentsJson)
            put("output_json", visibleOutput)
            put("is_error", isError)
        },
    )
}

private inline fun <T> runCatchingPreservingCancellation(
    block: () -> T,
): Result<T> = try {
    Result.success(block())
} catch (cancellationException: CancellationException) {
    throw cancellationException
} catch (throwable: Throwable) {
    Result.failure(throwable)
}

private fun List<LlmMessage>.withSelectedSkillCommand(skillName: String?): List<LlmMessage> {
    val name = skillName?.trim().orEmpty()
    if (name.isBlank()) return this
    val userIndex = indexOfLast { it.role == "user" }
    if (userIndex < 0) return this
    val user = get(userIndex)
    val textIndex = user.contentParts.indexOfFirst { it is LlmTextPart }
    val command = "/skill:$name"
    val updatedParts = if (textIndex >= 0) {
        user.contentParts.toMutableList().apply {
            val text = this[textIndex] as LlmTextPart
            this[textIndex] = text.copy(text = "$command ${text.text}")
        }
    } else {
        listOf(LlmTextPart(command)) + user.contentParts
    }
    return toMutableList().apply { this[userIndex] = user.copy(contentParts = updatedParts) }
}
