package kira.ditto.data.kimi

import kira.ditto.data.AetherDiagnosticLogger
import kira.ditto.runtime.AlpineProcessHandle
import kira.ditto.runtime.AlpineRuntime
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.InterruptedIOException
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

private const val InitializeTimeoutMillis = 90_000L
private const val EnsureStartedTimeoutMillis = 60_000L
private const val ConfigTimeoutMillis = 20_000L
private const val SessionTimeoutMillis = 30_000L
private const val PromptTimeoutMillis = 10 * 60_000L
private const val PromptCeilingTimeoutMillis = 60 * 60_000L
private const val DefaultInteractionTimeoutMillis = 5 * 60_000L
private const val DefaultTerminalOutputLimit = 4 * 1024 * 1024

private data class PendingAcpRequest(
    val response: CompletableDeferred<JSONObject>,
    val generation: Long,
    val method: String,
)

/** An agent->client request (permission / elicitation) waiting on the UI layer. */
private data class PendingAcpInteraction(
    val id: Any,
    val method: String,
    val generation: Long,
    val job: kotlinx.coroutines.Job,
)

private data class ActiveAcpProcess(
    val process: Process,
    val writer: BufferedWriter,
    val generation: Long,
)

/**
 * A stream reader failure counts as expected teardown — log quietly and exit
 * the loop — when the process is already dead, the reader was cancelled, or the
 * process generation was replaced by invalidate()/ensureStarted(). Anything
 * else is a real error and must go through error handling (but still never
 * escape the reader coroutine).
 */
internal enum class KimiAcpActivityTimeoutVerdict {
    Continue,
    Idle,
    Ceiling,
}

internal fun kimiAcpActivityTimeoutVerdict(
    nowMillis: Long,
    startedMillis: Long,
    lastActivityMillis: Long,
    idleMillis: Long,
    ceilingMillis: Long,
    busy: Boolean,
): KimiAcpActivityTimeoutVerdict {
    if (nowMillis - startedMillis >= ceilingMillis) return KimiAcpActivityTimeoutVerdict.Ceiling
    val last = if (busy) nowMillis else lastActivityMillis
    if (nowMillis - last >= idleMillis) return KimiAcpActivityTimeoutVerdict.Idle
    return KimiAcpActivityTimeoutVerdict.Continue
}

internal fun isExpectedReaderTeardown(
    processAlive: Boolean,
    coroutineActive: Boolean,
    currentGeneration: Long?,
    readerGeneration: Long,
): Boolean = !processAlive || !coroutineActive || currentGeneration != readerGeneration

internal fun formatKimiAcpRpcError(error: JSONObject): String {
    val message = error.optString("message").trim().ifBlank { "Kimi ACP request failed." }
    val data = error.opt("data") ?: return message
    val detail = when (data) {
        is JSONObject -> sequenceOf("details", "detail", "message", "error")
            .map { data.opt(it) }
            .map { value ->
                when (value) {
                    null -> ""
                    JSONObject.NULL -> ""
                    is String -> value.trim()
                    else -> value.toString().trim()
                }
            }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        is String -> data.trim()
        JSONObject.NULL -> ""
        else -> data.toString().trim()
    }.take(400)
    if (detail.isBlank() || message.contains(detail)) return message
    return "$message: $detail"
}

class KimiAcpClient(
    private val alpineRuntime: AlpineRuntime,
    private val diagnosticLogger: AetherDiagnosticLogger = AetherDiagnosticLogger.NoOp,
    private val clientVersion: String = "0.1.0",
    private val interactionTimeoutMillis: Long = DefaultInteractionTimeoutMillis,
) {
    private val mutex = Mutex()
    private val pendingRequests = ConcurrentHashMap<String, PendingAcpRequest>()
    private val pendingInteractions = ConcurrentHashMap<String, PendingAcpInteraction>()
    private val sessionListeners = ConcurrentHashMap<String, Channel<JSONObject>>()
    private val terminals = ConcurrentHashMap<String, AcpTerminalSession>()
    // Backstop: reader/interaction coroutines must never take the app process
    // down. Anything that still escapes a child job is logged, not crashed.
    private val eventExceptionHandler = CoroutineExceptionHandler { _, error ->
        runCatching {
            diagnosticLogger.event(
                category = "kimi_acp",
                event = "event_scope_uncaught",
                level = "error",
                details = mapOf("message" to (error.message ?: error.javaClass.simpleName)),
            )
        }
    }
    private val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + eventExceptionHandler)
    private val jsonParseDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val processStateLock = Any()
    private val nextProcessGeneration = AtomicLong(0L)
    private val nextRequestId = AtomicLong(1L)
    private val fileBridge = KimiAcpFileBridge(resolveHostFile = alpineRuntime::resolveAcpHostFile)

    /** Set by the UI layer; null means every interaction falls back to the safe default. */
    @Volatile
    var interactionHandler: KimiAcpInteractionHandler? = null

    /**
     * Receives every available_commands_update, including those emitted during
     * session/new before a prompt listener exists.
     */
    @Volatile
    var onAvailableCommandsUpdate: ((JSONObject) -> Unit)? = null

    /**
     * kimi-code Plan mode writes the detailed plan under
     * ~/.kimi-code/sessions/.../agents/main/plans. Host UI stores the
     * markdown as the plan document (TodoList stays the progress checklist).
     */
    @Volatile
    var onPlanDocumentWrite: ((kimiSessionId: String, path: String, markdown: String) -> Unit)? = null

    fun hasActivePrompt(): Boolean = activeSessionId != null

    fun hasPendingInteraction(): Boolean = pendingInteractions.isNotEmpty()

    fun hasInFlightTools(): Boolean = inFlightToolIds.isNotEmpty()

    /** Agent is waiting on a live terminal session (e.g. wait_for_exit). */
    fun hasActiveTerminals(): Boolean = terminals.values.any { it.isActive }

    /** Invoked on every inbound ACP frame; used by the turn watchdog. */
    @Volatile
    var activityListener: (() -> Unit)? = null

    private val lastIncomingAt = AtomicLong(0L)

    /** Nested MCP / GUI work that never appears as a parent ACP frame. */
    fun noteActivity() {
        lastIncomingAt.set(System.currentTimeMillis())
        activityListener?.invoke()
    }

    /**
     * CronCreate injects a prompt into the live Kimi session. ACP only has a
     * listener during session/prompt, so idle session/update would otherwise
     * never reach chat. Buffer those frames and ask the host to start a turn.
     */
    @Volatile
    var onIdleCronPrompt: ((kimiSessionId: String, promptText: String) -> Unit)? = null

    private val idleUpdateBuffer = ConcurrentHashMap<String, ConcurrentLinkedQueue<JSONObject>>()
    private val idleCronNotified = ConcurrentHashMap<String, Boolean>()

    fun hasBufferedIdleTurn(sessionId: String): Boolean =
        idleUpdateBuffer[sessionId]?.any(KimiAcpProtocol::isBufferedIdleTurnUpdate) == true

    fun discardIdleUpdates(sessionId: String) {
        idleUpdateBuffer.remove(sessionId)
        idleCronNotified.remove(sessionId)
    }

    private val interactionResolver = KimiAcpInteractionResolver(
        timeoutMillis = interactionTimeoutMillis,
        handlerProvider = { interactionHandler },
        log = { event, message ->
            diagnosticLogger.event(
                category = "kimi_acp",
                event = event,
                level = "warn",
                details = mapOf("message" to message),
            )
        },
    )
    @Volatile
    private var activeProcess: ActiveAcpProcess? = null
    @Volatile
    private var initialized = false
    @Volatile
    private var boundSessionId: String? = null
    @Volatile
    private var activeSessionId: String? = null
    private val inFlightToolIds = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    var sessionNeedsRecreate: Boolean = false
        private set

    suspend fun ensureReady() {
        ensureStarted()
    }

    fun invalidate(reason: String = "Kimi ACP process restarted.") {
        sessionNeedsRecreate = true
        boundSessionId = null
        activeSessionId = null
        val stale = synchronized(processStateLock) {
            activeProcess.also {
                activeProcess = null
                initialized = false
            }
        }
        inFlightToolIds.clear()
        failPending(reason)
        failInteractions(reason)
        releaseAllTerminals()
        runCatching { stale?.writer?.close() }
        runCatching { stale?.process?.destroy() }
        if (stale?.process != null && !stale.process.waitFor(800, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            runCatching { stale.process.destroyForcibly() }
        }
    }

    fun abortActiveTurn() {
        val sessionId = activeSessionId
        if (sessionId != null) {
            runCatching { notify("session/cancel", JSONObject().put("sessionId", sessionId)) }
        }
        failInteractions("Turn cancelled.")
        pendingRequests.entries.removeIf { (_, pending) ->
            if (pending.method != "session/prompt") return@removeIf false
            pending.response.completeExceptionally(CancellationException("Turn cancelled."))
            true
        }
    }

    fun isBoundSession(sessionId: String): Boolean =
        sessionId.isNotBlank() &&
            boundSessionId == sessionId &&
            initialized &&
            !sessionNeedsRecreate &&
            currentLiveProcess() != null

    private fun rememberBoundSession(sessionId: String) {
        if (sessionId.isNotBlank()) boundSessionId = sessionId
    }

    fun markSessionRecreated() {
        sessionNeedsRecreate = false
    }

    suspend fun createSession(
        cwd: String,
        mcpServers: JSONArray = JSONArray(),
        additionalDirectories: JSONArray = JSONArray(),
    ): JSONObject {
        alpineRuntime.ensureKimiAcpSessionDirectories()
        val extraDirectories = mergedAdditionalDirectories(additionalDirectories)
        val result = request(
            method = "session/new",
            params = JSONObject()
                .put("cwd", cwd)
                .put("mcpServers", mcpServers)
                .put("additionalDirectories", extraDirectories),
            timeoutMillis = SessionTimeoutMillis,
        )
        registerFsSession(
            sessionId = result.optString("sessionId"),
            cwd = cwd,
            extraDirectories = extraDirectories,
        )
        rememberBoundSession(result.optString("sessionId"))
        return result
    }

    suspend fun resumeSession(
        sessionId: String,
        cwd: String,
        mcpServers: JSONArray = JSONArray(),
        additionalDirectories: JSONArray = JSONArray(),
    ): JSONObject {
        alpineRuntime.ensureKimiAcpSessionDirectories()
        val extraDirectories = mergedAdditionalDirectories(additionalDirectories)
        val result = request(
            method = "session/resume",
            params = JSONObject()
                .put("sessionId", sessionId)
                .put("cwd", cwd)
                .put("mcpServers", mcpServers)
                .put("additionalDirectories", extraDirectories),
            timeoutMillis = SessionTimeoutMillis,
        )
        registerFsSession(sessionId = sessionId, cwd = cwd, extraDirectories = extraDirectories)
        rememberBoundSession(sessionId)
        return result
    }

    /** Restores a session and replays its history via session/update notifications. */
    suspend fun loadSession(
        sessionId: String,
        cwd: String,
        mcpServers: JSONArray = JSONArray(),
        additionalDirectories: JSONArray = JSONArray(),
        onEvent: (suspend (String, JSONObject) -> Unit)? = null,
    ): JSONObject {
        val load = suspend {
            alpineRuntime.ensureKimiAcpSessionDirectories()
            val extraDirectories = mergedAdditionalDirectories(additionalDirectories)
            val result = request(
                method = "session/load",
                params = JSONObject()
                    .put("sessionId", sessionId)
                    .put("cwd", cwd)
                    .put("mcpServers", mcpServers)
                    .put("additionalDirectories", extraDirectories),
                timeoutMillis = if (onEvent != null) PromptTimeoutMillis else SessionTimeoutMillis,
            )
            registerFsSession(sessionId = sessionId, cwd = cwd, extraDirectories = extraDirectories)
            rememberBoundSession(sessionId)
            result
        }
        return if (onEvent != null) {
            withSessionUpdates(sessionId, onEvent, load)
        } else {
            load()
        }
    }

    /** Forks a session; the fork inherits the workspace (and fs roots when cwd is omitted). */
    suspend fun forkSession(
        sessionId: String,
        cwd: String? = null,
        mcpServers: JSONArray = JSONArray(),
    ): JSONObject {
        val params = JSONObject().put("sessionId", sessionId)
        cwd?.let { params.put("cwd", it) }
        params.put("mcpServers", mcpServers)
        val result = request(
            method = "session/fork",
            params = params,
            timeoutMillis = SessionTimeoutMillis,
        )
        val forkedId = result.optString("sessionId")
        if (forkedId.isNotBlank()) {
            if (cwd != null) {
                registerFsSession(
                    sessionId = forkedId,
                    cwd = cwd,
                    extraDirectories = mergedAdditionalDirectories(JSONArray()),
                )
            } else {
                fileBridge.copySessionRoots(sessionId, forkedId)
            }
        }
        return result
    }

    /** Lists sessions, optionally filtered by cwd; pass nextCursor for pagination. */
    suspend fun listSessions(
        cwd: String? = null,
        cursor: String? = null,
    ): JSONObject {
        val params = JSONObject()
        cwd?.let { params.put("cwd", it) }
        cursor?.let { params.put("cursor", it) }
        return request(
            method = "session/list",
            params = params,
            timeoutMillis = SessionTimeoutMillis,
        )
    }

    suspend fun closeSession(sessionId: String): JSONObject {
        val result = request(
            method = "session/close",
            params = JSONObject().put("sessionId", sessionId),
            timeoutMillis = SessionTimeoutMillis,
        )
        fileBridge.unregisterSession(sessionId)
        return result
    }

    suspend fun deleteSession(sessionId: String): JSONObject {
        val result = request(
            method = "session/delete",
            params = JSONObject().put("sessionId", sessionId),
            timeoutMillis = SessionTimeoutMillis,
        )
        fileBridge.unregisterSession(sessionId)
        return result
    }

    suspend fun setMode(sessionId: String, modeId: String): JSONObject = request(
        method = "session/set_mode",
        params = JSONObject()
            .put("sessionId", sessionId)
            .put("modeId", modeId),
        timeoutMillis = ConfigTimeoutMillis,
    )

    /** Experimental: switches the model of a live session. */
    suspend fun setModel(sessionId: String, modelId: String): JSONObject = request(
        method = "session/set_model",
        params = JSONObject()
            .put("sessionId", sessionId)
            .put("modelId", modelId),
        timeoutMillis = ConfigTimeoutMillis,
    )

    suspend fun setConfigOption(
        sessionId: String,
        configId: String,
        value: String,
    ): JSONObject = request(
        method = "session/set_config_option",
        params = JSONObject()
            .put("sessionId", sessionId)
            .put("configId", configId)
            .put("value", value),
        timeoutMillis = ConfigTimeoutMillis,
    )

    suspend fun prompt(
        sessionId: String,
        prompt: JSONArray,
        onEvent: suspend (String, JSONObject) -> Unit,
    ): JSONObject = withSessionUpdates(sessionId, onEvent) {
        requestWithActivityTimeout(
            method = "session/prompt",
            params = JSONObject()
                .put("sessionId", sessionId)
                .put("prompt", prompt),
            idleMillis = PromptTimeoutMillis,
            ceilingMillis = PromptCeilingTimeoutMillis,
        )
    }

    /**
     * Attach to an already-running idle cron turn (Kimi steered internally)
     * and return when the session is quiet again.
     */
    suspend fun observeUntilIdle(
        sessionId: String,
        onEvent: suspend (String, JSONObject) -> Unit,
        idleMillis: Long = 5_000L,
    ): JSONObject {
        val lastEventAt = AtomicLong(System.currentTimeMillis())
        return withSessionUpdates(
            sessionId = sessionId,
            onEvent = { name, payload ->
                lastEventAt.set(System.currentTimeMillis())
                onEvent(name, payload)
            },
        ) {
            val started = System.currentTimeMillis()
            while (true) {
                delay(250)
                if (hasPendingInteraction() || hasInFlightTools() || hasActiveTerminals()) {
                    lastEventAt.set(System.currentTimeMillis())
                    continue
                }
                if (System.currentTimeMillis() - lastEventAt.get() >= idleMillis) break
                if (System.currentTimeMillis() - started > PromptTimeoutMillis) break
            }
            JSONObject().put("stopReason", "end_turn")
        }
    }

    private suspend fun withSessionUpdates(
        sessionId: String,
        onEvent: suspend (String, JSONObject) -> Unit,
        block: suspend () -> JSONObject,
    ): JSONObject = withContext(Dispatchers.IO) {
        ensureStarted()
        activeSessionId = sessionId
        inFlightToolIds.clear()
        idleCronNotified.remove(sessionId)
        val updates = Channel<JSONObject>(Channel.UNLIMITED)
        sessionListeners[sessionId] = updates
        drainIdleBuffer(sessionId, updates)
        try {
            val collector = eventScope.launch {
                for (update in updates) {
                    KimiAcpProtocol.mapSessionUpdate(update)?.let { mapped ->
                        onEvent(mapped.name, mapped.payload)
                    }
                    KimiAcpProtocol.planUpdateFromToolCall(update)?.let { mapped ->
                        onEvent(mapped.name, mapped.payload)
                    }
                }
            }
            val result = try {
                block()
            } catch (cancelled: CancellationException) {
                runCatching { notify("session/cancel", JSONObject().put("sessionId", sessionId)) }
                collector.cancel()
                throw cancelled
            }
            updates.close()
            collector.join()
            result
        } finally {
            sessionListeners.remove(sessionId, updates)
            if (activeSessionId == sessionId) activeSessionId = null
            inFlightToolIds.clear()
        }
    }

    private fun drainIdleBuffer(sessionId: String, updates: Channel<JSONObject>) {
        val buffered = idleUpdateBuffer.remove(sessionId) ?: return
        buffered.forEach { updates.trySend(it) }
    }

    private fun rememberIdleUpdate(sessionId: String, update: JSONObject) {
        if (sessionId.isBlank()) return
        idleUpdateBuffer.getOrPut(sessionId) { ConcurrentLinkedQueue() }.add(update)
        if (!KimiAcpProtocol.isIdleTurnSignal(update)) return
        if (idleCronNotified.putIfAbsent(sessionId, true) != null) return
        val prompt = extractIdleCronPrompt(sessionId)
        onIdleCronPrompt?.invoke(sessionId, prompt)
    }

    private fun extractIdleCronPrompt(sessionId: String): String {
        val buffered = idleUpdateBuffer[sessionId] ?: return ""
        val chunks = buffered.mapNotNull { update ->
            if (update.optString("sessionUpdate") != "user_message_chunk") return@mapNotNull null
            KimiAcpProtocol.sessionUpdateText(update).takeIf { it.isNotBlank() }
        }
        return chunks.firstOrNull { text ->
            text.contains("<cron-fire", ignoreCase = true) || text.contains("cron-fire", ignoreCase = true)
        } ?: chunks.joinToString("")
    }

    private suspend fun ensureStarted(): ActiveAcpProcess {
        currentLiveProcess()?.takeIf { initialized }?.let { return it }
        val acquired = withTimeoutOrNull(EnsureStartedTimeoutMillis) {
            mutex.lock()
            true
        } ?: false
        if (!acquired) {
            throw IllegalStateException(
                "Kimi 进程启动失败：${EnsureStartedTimeoutMillis / 1000}s 内未能获得启动锁。",
            )
        }
        try {
            currentLiveProcess()?.takeIf { initialized }?.let { return it }
            return coroutineScope {
                val watchdog = launch {
                    delay(EnsureStartedTimeoutMillis)
                    diagnosticLogger.event(
                        category = "kimi_acp",
                        event = "ensure_started_watchdog",
                        level = "error",
                        details = mapOf("timeout_ms" to EnsureStartedTimeoutMillis),
                    )
                    invalidate(
                        "Kimi ACP process did not become ready within ${EnsureStartedTimeoutMillis / 1000}s.",
                    )
                }
                try {
                    withTimeout(EnsureStartedTimeoutMillis) {
                        ensureStartedLocked()
                    }
                } catch (timeout: TimeoutCancellationException) {
                    invalidate(
                        "Kimi ACP process did not become ready within ${EnsureStartedTimeoutMillis / 1000}s.",
                    )
                    throw IllegalStateException(
                        "Kimi 进程启动失败：${EnsureStartedTimeoutMillis / 1000}s 内未完成握手。",
                        timeout,
                    )
                } catch (failure: Throwable) {
                    if (failure is CancellationException) throw failure
                    throw IllegalStateException(
                        "Kimi 进程启动失败：${failure.message ?: failure.javaClass.simpleName}",
                        failure,
                    )
                } finally {
                    watchdog.cancel()
                }
            }
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun ensureStartedLocked(): ActiveAcpProcess {
        currentLiveProcess()?.takeIf { initialized }?.let { return it }
        val stale = synchronized(processStateLock) {
            activeProcess.also { activeProcess = null }
        }
        initialized = false
        runCatching { stale?.writer?.close() }
        runCatching { stale?.process?.destroy() }

        val ensureStartedAt = System.currentTimeMillis()
        fun stage(name: String) {
            diagnosticLogger.event(
                category = "kimi_acp",
                event = "ensure_started_stage",
                details = mapOf(
                    "stage" to name,
                    "elapsed_ms" to (System.currentTimeMillis() - ensureStartedAt),
                ),
            )
        }
        stage("entered")
        alpineRuntime.ensureWebSearchGateway()
        stage("web_search_gateway_ready")
        alpineRuntime.ensureUpaMcpGateway()
        stage("upa_mcp_gateway_ready")
        val process = alpineRuntime.startManagedProcess(
            command = alpineRuntime.kimiAcpLaunchCommand(),
            workingDirectory = alpineRuntime.workspaceRoot,
            redirectErrorStream = false,
        )
        stage("process_spawned")
        val started = ActiveAcpProcess(
            process = process,
            writer = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8)),
            generation = nextProcessGeneration.incrementAndGet(),
        )
        synchronized(processStateLock) {
            activeProcess = started
        }
        startStdoutReader(started)
        startStderrReader(started)
        stage("io_readers_started")
        try {
            initializeHandshake()
        } catch (cancelled: CancellationException) {
            teardownFailedStart(started)
            if (cancelled is TimeoutCancellationException) {
                throw IllegalStateException(
                    "Kimi 进程启动失败：握手超时。",
                    cancelled,
                )
            }
            throw cancelled
        } catch (failure: Throwable) {
            // Never leave a half-started zombie behind: with initialized=false
            // the process would linger until the next ensureStarted, and every
            // retry would burn a full spawn + handshake timeout.
            teardownFailedStart(started)
            throw IllegalStateException(
                "Kimi ACP initialize handshake failed: ${failure.message ?: failure.javaClass.simpleName}",
                failure,
            )
        }
        initialized = true
        stage("handshake_done")
        diagnosticLogger.event(
            category = "kimi_acp",
            event = "process_started",
            details = mapOf("process_generation" to started.generation),
        )
        return started
    }

    private fun teardownFailedStart(started: ActiveAcpProcess) {
        synchronized(processStateLock) {
            if (activeProcess?.generation == started.generation) {
                activeProcess = null
            }
        }
        failPending("Kimi ACP initialize handshake failed.", started.generation)
        runCatching { started.writer.close() }
        runCatching { started.process.destroy() }
    }

    private suspend fun initializeHandshake() {
        val result = request(
            method = "initialize",
            params = JSONObject()
                .put("protocolVersion", KimiAcpProtocol.PROTOCOL_VERSION)
                .put(
                    "clientInfo",
                    JSONObject()
                        .put("name", KimiAcpProtocol.CLIENT_NAME)
                        .put("version", clientVersion),
                )
                .put("clientCapabilities", KimiAcpProtocol.clientCapabilities()),
            timeoutMillis = InitializeTimeoutMillis,
        )
        val negotiated = result.optInt("protocolVersion", KimiAcpProtocol.PROTOCOL_VERSION)
        if (negotiated != KimiAcpProtocol.PROTOCOL_VERSION) {
            error("Kimi ACP protocol mismatch: $negotiated")
        }
        val authMethods = result.optJSONArray("authMethods")
        diagnosticLogger.event(
            category = "kimi_acp",
            event = "initialized",
            details = mapOf(
                "auth_method_count" to (authMethods?.length() ?: 0),
                "auth_methods" to authMethodIds(authMethods).joinToString(","),
            ),
        )
        // kimi acp advertises terminal OAuth `login` even when config.toml already
        // has an API-key provider. Calling it on Alpine has no TTY and leaves the
        // session gate thinking no provider is configured. Skip it; session/new
        // authenticates from the provider api_key instead.
    }

    private suspend fun requestWithActivityTimeout(
        method: String,
        params: JSONObject,
        idleMillis: Long,
        ceilingMillis: Long,
    ): JSONObject {
        val process = currentLiveProcess() ?: ensureStarted()
        val id = nextRequestId.getAndIncrement().toString()
        val pending = PendingAcpRequest(
            response = CompletableDeferred(),
            generation = process.generation,
            method = method,
        )
        pendingRequests[id] = pending
        val started = System.currentTimeMillis()
        lastIncomingAt.set(started)
        try {
            writeFrameTo(
                process,
                JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", id.toLongOrNull() ?: id)
                    .put("method", method)
                    .put("params", params),
            )
            while (true) {
                if (pending.response.isCompleted) return pending.response.await()
                val now = System.currentTimeMillis()
                val busy = hasPendingInteraction() || hasInFlightTools() || hasActiveTerminals()
                when (
                    kimiAcpActivityTimeoutVerdict(
                        nowMillis = now,
                        startedMillis = started,
                        lastActivityMillis = lastIncomingAt.get(),
                        idleMillis = idleMillis,
                        ceilingMillis = ceilingMillis,
                        busy = busy,
                    )
                ) {
                    KimiAcpActivityTimeoutVerdict.Ceiling -> {
                        pendingRequests.remove(id)
                        invalidate()
                        throw IllegalStateException(
                            "Kimi ACP request reached ${ceilingMillis / 1000}s ceiling: $method",
                        )
                    }
                    KimiAcpActivityTimeoutVerdict.Idle -> {
                        pendingRequests.remove(id)
                        invalidate()
                        throw IllegalStateException(
                            "Kimi ACP request idle timed out after ${idleMillis / 1000}s: $method",
                        )
                    }
                    KimiAcpActivityTimeoutVerdict.Continue -> {
                        val completed = withTimeoutOrNull(250L) { pending.response.await() }
                        if (completed != null) return completed
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            pendingRequests.remove(id)
            throw cancelled
        } catch (error: Throwable) {
            pendingRequests.remove(id)
            throw error
        }
    }

    private suspend fun request(
        method: String,
        params: JSONObject,
        timeoutMillis: Long,
    ): JSONObject {
        val process = currentLiveProcess() ?: ensureStarted()
        val id = nextRequestId.getAndIncrement().toString()
        val pending = PendingAcpRequest(
            response = CompletableDeferred(),
            generation = process.generation,
            method = method,
        )
        pendingRequests[id] = pending
        try {
            // The frame write must live inside the timeout and target the
            // captured process: previously the blocking write ran before the
            // timeout started (so a wedged pipe meant the timeout never
            // began), and it wrote to whatever process was current at write
            // time (so a mid-request process swap dropped the response as a
            // generation mismatch and the caller waited out the full timeout).
            return withTimeout(timeoutMillis) {
                writeFrameTo(
                    process,
                    JSONObject()
                        .put("jsonrpc", "2.0")
                        .put("id", id.toLongOrNull() ?: id)
                        .put("method", method)
                        .put("params", params),
                )
                pending.response.await()
            }
        } catch (timeout: TimeoutCancellationException) {
            pendingRequests.remove(id)
            // A wedged process (blocked write, dead stdout) must not be
            // reused; tear it down so the next request spawns a fresh one.
            invalidate()
            throw IllegalStateException(
                "Kimi ACP request timed out after ${timeoutMillis / 1000}s: $method",
                timeout,
            )
        } catch (cancelled: CancellationException) {
            pendingRequests.remove(id)
            throw cancelled
        } catch (error: Throwable) {
            pendingRequests.remove(id)
            throw error
        }
    }

    private fun notify(method: String, params: JSONObject) {
        writeFrame(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("method", method)
                .put("params", params),
        )
    }

    private fun writeFrame(payload: JSONObject) {
        writeFrameBlocking(currentLiveProcess() ?: error("Kimi ACP process is not running."), payload)
    }

    private suspend fun writeFrameTo(process: ActiveAcpProcess, payload: JSONObject) {
        runInterruptible(Dispatchers.IO) {
            writeFrameBlocking(process, payload)
        }
    }

    private fun writeFrameBlocking(process: ActiveAcpProcess, payload: JSONObject) {
        synchronized(process.writer) {
            process.writer.write(payload.toString())
            process.writer.write("\n")
            process.writer.flush()
        }
    }

    private fun currentLiveProcess(): ActiveAcpProcess? =
        synchronized(processStateLock) {
            activeProcess?.takeIf { it.process.isAlive }
        }

    private fun startStdoutReader(startedProcess: ActiveAcpProcess) {
        eventScope.launch {
            // Bounded capacity here back-pressured the reader thread: a slow
            // parser (large session/load replays) would fill the pipe and
            // block node from writing stdout, faking a deadlock. Parsing is
            // cheaper than reading; never throttle the pipe.
            val lines = Channel<String>(capacity = Channel.UNLIMITED)
            val parser = launch(jsonParseDispatcher) {
                for (line in lines) {
                    val message = runCatching { JSONObject(line) }.getOrElse {
                        diagnosticLogger.event(
                            category = "kimi_acp",
                            event = "invalid_stdout_json",
                            level = "warn",
                            details = mapOf("line" to line.take(500)),
                        )
                        continue
                    }
                    handleIncoming(message, startedProcess.generation)
                }
            }
            try {
                BufferedReader(
                    InputStreamReader(startedProcess.process.inputStream, Charsets.UTF_8),
                ).use { reader ->
                    while (isActive) {
                        val line = try {
                            reader.readLine()
                        } catch (interrupted: InterruptedIOException) {
                            // Thrown when invalidate()/process teardown interrupts
                            // the blocking read. Expected on restart: exit quietly.
                            if (isExpectedReaderTeardown(startedProcess, coroutineActive = isActive)) break
                            throw interrupted
                        } catch (streamFailure: IOException) {
                            // Stream closed because the process died or was swapped.
                            if (isExpectedReaderTeardown(startedProcess, coroutineActive = isActive)) break
                            throw streamFailure
                        } ?: break
                        if (line.isBlank()) continue
                        lines.send(line)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // A genuine reader failure. The finally block below fails pending
                // requests for the current generation; the exception itself must
                // never escape this coroutine (it would crash the app process).
                diagnosticLogger.event(
                    category = "kimi_acp",
                    event = "stdout_reader_failed",
                    level = "error",
                    details = mapOf(
                        "message" to (error.message ?: error.javaClass.simpleName),
                        "process_generation" to startedProcess.generation,
                    ),
                )
            } finally {
                lines.close()
                parser.join()
                if (synchronized(processStateLock) { activeProcess?.generation == startedProcess.generation }) {
                    initialized = false
                    // Mirror invalidate(): a dead process must also drop
                    // pending UI interactions and terminal sessions, or they
                    // leak until their own long timeouts fire.
                    failPending("Kimi ACP process exited.", startedProcess.generation)
                    failInteractions("Kimi ACP process exited.")
                    releaseAllTerminals()
                }
            }
        }
    }

    /**
     * A read failure is expected teardown (quiet exit) when the process is dead,
     * the coroutine was cancelled, or the process generation was replaced by
     * invalidate()/ensureStarted(). Anything else is a real error.
     */
    private fun isExpectedReaderTeardown(
        startedProcess: ActiveAcpProcess,
        coroutineActive: Boolean,
    ): Boolean = isExpectedReaderTeardown(
        processAlive = startedProcess.process.isAlive,
        coroutineActive = coroutineActive,
        currentGeneration = currentGeneration(),
        readerGeneration = startedProcess.generation,
    )

    private fun startStderrReader(startedProcess: ActiveAcpProcess) {
        Thread(
            {
                val failure = runCatching {
                    BufferedReader(
                        InputStreamReader(startedProcess.process.errorStream, Charsets.UTF_8),
                    ).useLines { lines ->
                        lines.forEach { line ->
                            if (line.isBlank()) return@forEach
                            diagnosticLogger.event(
                                category = "kimi_acp",
                                event = "stderr",
                                level = "warn",
                                details = mapOf(
                                    "line" to line.take(700),
                                    "process_generation" to startedProcess.generation,
                                ),
                            )
                        }
                    }
                }.exceptionOrNull()
                if (failure != null && !isExpectedReaderTeardown(startedProcess, coroutineActive = true)) {
                    diagnosticLogger.event(
                        category = "kimi_acp",
                        event = "stderr_reader_failed",
                        level = "warn",
                        details = mapOf(
                            "message" to (failure.message ?: failure.javaClass.simpleName),
                            "process_generation" to startedProcess.generation,
                        ),
                    )
                }
            },
            "aether-kimi-acp-stderr-${startedProcess.generation}",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun handleIncoming(message: JSONObject, generation: Long) {
        lastIncomingAt.set(System.currentTimeMillis())
        activityListener?.invoke()
        if (message.has("id") && (message.has("result") || message.has("error"))) {
            val id = message.opt("id")?.toString().orEmpty()
            val pending = pendingRequests.remove(id) ?: return
            if (pending.generation != generation) {
                pending.response.completeExceptionally(
                    IllegalStateException(
                        "Kimi ACP process generation changed during ${pending.method}.",
                    ),
                )
                return
            }
            if (message.has("error")) {
                val error = message.optJSONObject("error") ?: JSONObject()
                val formatted = formatKimiAcpRpcError(error)
                diagnosticLogger.event(
                    category = "kimi_acp",
                    event = "rpc_error",
                    level = "error",
                    details = mapOf(
                        "method" to pending.method,
                        "code" to error.optInt("code"),
                        "message" to formatted,
                    ),
                )
                pending.response.completeExceptionally(IllegalStateException(formatted))
            } else {
                val result = message.opt("result")
                pending.response.complete(
                    when (result) {
                        is JSONObject -> result
                        null -> JSONObject()
                        else -> if (result == JSONObject.NULL) JSONObject()
                        else JSONObject().put("value", result)
                    },
                )
            }
            return
        }
        val method = message.optString("method")
        when (method) {
            "session/update" -> {
                val params = message.optJSONObject("params") ?: return
                val sessionId = params.optString("sessionId")
                val update = params.optJSONObject("update") ?: return
                KimiAcpProtocol.applyToolCallFlight(update, inFlightToolIds)
                val listener = sessionListeners[sessionId]
                if (listener != null) {
                    listener.trySend(update)
                } else {
                    rememberIdleUpdate(sessionId, update)
                }
                if (update.optString("sessionUpdate") == "available_commands_update") {
                    KimiAcpProtocol.mapSessionUpdate(update)?.payload?.let { payload ->
                        onAvailableCommandsUpdate?.invoke(payload)
                    }
                }
            }
            "session/request_permission" -> handleInteraction(message, generation) { params ->
                interactionResolver.resolvePermission(KimiAcpProtocol.parsePermissionRequest(params))
            }
            "elicitation/create" -> handleInteraction(message, generation) { params ->
                interactionResolver.resolveElicitation(KimiAcpProtocol.parseElicitationRequest(params))
            }
            "fs/read_text_file" -> replyToAgent(message) { params ->
                fileBridge.readTextFile(
                    sessionId = params.optString("sessionId"),
                    path = params.optString("path"),
                    line = if (params.has("line")) params.optInt("line") else null,
                    limit = if (params.has("limit")) params.optInt("limit") else null,
                )
            }
            "fs/write_text_file" -> replyToAgent(message) { params ->
                val path = params.optString("path")
                val content = params.optString("content")
                val sessionId = params.optString("sessionId")
                val result = fileBridge.writeTextFile(
                    sessionId = sessionId,
                    path = path,
                    content = content,
                )
                if (isKimiSessionPlanDocumentPath(path) && sessionId.isNotBlank()) {
                    onPlanDocumentWrite?.invoke(sessionId, path, content)
                }
                result
            }
            "terminal/create" -> handleTerminalCreate(message)
            "terminal/output" -> replyToAgent(message) { params ->
                terminalSession(params).outputPayload()
            }
            "terminal/wait_for_exit" -> handleTerminalWaitForExit(message)
            "terminal/kill" -> replyToAgent(message) { params ->
                terminalSession(params).kill()
                JSONObject()
            }
            "terminal/release" -> replyToAgent(message) { params ->
                terminals.remove(params.optString("terminalId"))?.release()
                JSONObject()
            }
            else -> {
                // Every JSON-RPC request must get an answer; never let the agent hang.
                val id = message.opt("id")
                if (id != null && method.isNotBlank()) {
                    diagnosticLogger.event(
                        category = "kimi_acp",
                        event = "unknown_agent_method",
                        level = "warn",
                        details = mapOf("method" to method),
                    )
                    writeError(id, -32601, "Method not found: $method")
                } else if (method.isNotBlank()) {
                    diagnosticLogger.event(
                        category = "kimi_acp",
                        event = "unknown_agent_notification",
                        details = mapOf("method" to method),
                    )
                }
            }
        }
    }

    /**
     * Registers an agent->client interaction request, waits for the UI layer with
     * a bounded timeout (safe defaults inside the resolver), and always writes a
     * JSON-RPC response. Tracked in pendingInteractions so process restarts and
     * turn aborts can answer/cancel every pending request without leaking.
     */
    private fun handleInteraction(
        message: JSONObject,
        generation: Long,
        resolve: suspend (JSONObject) -> JSONObject,
    ) {
        val id = message.opt("id") ?: return
        val key = id.toString()
        val method = message.optString("method")
        val params = message.optJSONObject("params") ?: JSONObject()
        val job = eventScope.launch {
            try {
                val result = resolve(params)
                if (currentGeneration() == generation) {
                    runCatching { writeResult(id, result) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (currentGeneration() == generation) {
                    runCatching {
                        writeError(id, -32603, error.message ?: "Interaction handling failed.")
                    }
                }
            } finally {
                pendingInteractions.remove(key)
            }
        }
        pendingInteractions[key] = PendingAcpInteraction(id, method, generation, job)
    }

    private fun currentGeneration(): Long? =
        synchronized(processStateLock) { activeProcess?.generation }

    private fun failInteractions(message: String) {
        val pending = pendingInteractions.values.toList()
        pendingInteractions.clear()
        pending.forEach { interaction ->
            runCatching { writeError(interaction.id, -32603, message) }
            interaction.job.cancel()
        }
    }

    private fun handleTerminalCreate(message: JSONObject) {
        val id = message.opt("id") ?: return
        eventScope.launch {
            runCatching {
                val params = message.optJSONObject("params") ?: JSONObject()
                val command = params.optString("command")
                check(command.isNotBlank()) { "terminal command is missing." }
                val args = jsonStringList(params.optJSONArray("args"))
                val cwd = params.optString("cwd").ifBlank { alpineRuntime.workspaceRoot }
                val extraEnv = jsonEnv(params.optJSONArray("env"))
                val outputByteLimit = params.optInt("outputByteLimit", DefaultTerminalOutputLimit)
                    .coerceIn(16 * 1024, 8 * 1024 * 1024)
                val process = alpineRuntime.startAcpTerminalProcess(
                    command = command,
                    args = args,
                    workingDirectory = cwd,
                    extraEnvironment = extraEnv,
                )
                val terminal = AcpTerminalSession(
                    id = UUID.randomUUID().toString(),
                    process = process,
                    outputByteLimit = outputByteLimit,
                )
                terminals[terminal.id] = terminal
                writeResult(id, JSONObject().put("terminalId", terminal.id))
            }.onFailure { error ->
                writeError(id, -32603, error.message ?: "Unable to create terminal.")
            }
        }
    }

    private fun handleTerminalWaitForExit(message: JSONObject) {
        val id = message.opt("id") ?: return
        eventScope.launch {
            runCatching {
                val params = message.optJSONObject("params") ?: JSONObject()
                val status = terminalSession(params).waitForExit()
                writeResult(id, status)
            }.onFailure { error ->
                writeError(id, -32603, error.message ?: "Unable to wait for terminal.")
            }
        }
    }

    private fun replyToAgent(
        message: JSONObject,
        build: (JSONObject) -> JSONObject,
    ) {
        val id = message.opt("id") ?: return
        eventScope.launch {
            runCatching {
                val params = message.optJSONObject("params") ?: JSONObject()
                writeResult(id, build(params))
            }.onFailure { error ->
                writeError(id, -32603, error.message ?: "Agent request failed.")
            }
        }
    }

    private fun terminalSession(params: JSONObject): AcpTerminalSession {
        val terminalId = params.optString("terminalId")
        return terminals[terminalId] ?: error("Unknown terminal $terminalId")
    }

    private fun writeResult(id: Any, result: JSONObject) {
        writeFrame(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("result", result),
        )
    }

    private fun writeError(id: Any, code: Int, message: String) {
        writeFrame(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put(
                    "error",
                    JSONObject()
                        .put("code", code)
                        .put("message", message),
                ),
        )
    }

    private fun releaseAllTerminals() {
        terminals.keys.toList().forEach { key ->
            terminals.remove(key)?.release()
        }
    }

    private fun mergedAdditionalDirectories(additional: JSONArray): JSONArray {
        val merged = JSONArray()
        mergeKimiAcpAdditionalDirectories(jsonStringList(additional)).forEach(merged::put)
        return merged
    }

    private fun registerFsSession(
        sessionId: String,
        cwd: String,
        extraDirectories: JSONArray,
    ) {
        fileBridge.registerSession(
            sessionId = sessionId,
            cwd = cwd,
            additionalDirectories = jsonStringList(extraDirectories),
        )
    }

    private fun jsonStringList(values: JSONArray?): List<String> {
        if (values == null) return emptyList()
        return buildList {
            for (index in 0 until values.length()) {
                add(values.optString(index))
            }
        }
    }

    private fun jsonEnv(values: JSONArray?): Map<String, String> {
        if (values == null) return emptyMap()
        return buildMap {
            for (index in 0 until values.length()) {
                val item = values.optJSONObject(index) ?: continue
                val name = item.optString("name")
                if (name.isNotBlank()) put(name, item.optString("value"))
            }
        }
    }

    private fun failPending(message: String, generation: Long? = null) {
        pendingRequests.entries.removeIf { (_, pending) ->
            if (generation != null && pending.generation != generation) return@removeIf false
            pending.response.completeExceptionally(IllegalStateException(message))
            true
        }
    }

    private fun authMethodIds(authMethods: JSONArray?): List<String> {
        if (authMethods == null) return emptyList()
        return buildList {
            for (index in 0 until authMethods.length()) {
                val method = authMethods.optJSONObject(index) ?: continue
                method.optString("id").ifBlank { method.optString("methodId") }
                    .takeIf(String::isNotBlank)
                    ?.let(::add)
            }
        }
    }
}

private class AcpTerminalSession(
    val id: String,
    private val process: AlpineProcessHandle,
    private val outputByteLimit: Int,
) {
    private val output = ByteArrayOutputStream()
    private val outputLock = Any()
    private val exit = CompletableDeferred<Int>()
    @Volatile
    private var truncated = false
    @Volatile
    private var exitCode: Int? = null
    val isActive: Boolean get() = exitCode == null
    @Volatile
    private var cachedOutput = ""
    @Volatile
    private var outputDirty = true

    init {
        Thread(
            {
                runCatching {
                    process.inputStream.use { input ->
                        val buffer = ByteArray(8 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            synchronized(outputLock) {
                                val remaining = outputByteLimit - output.size()
                                if (remaining <= 0) {
                                    truncated = true
                                } else if (read > remaining) {
                                    output.write(buffer, 0, remaining)
                                    truncated = true
                                    outputDirty = true
                                } else {
                                    output.write(buffer, 0, read)
                                    outputDirty = true
                                }
                            }
                        }
                    }
                }
                val code = runCatching { process.waitFor() }.getOrDefault(-1)
                exitCode = code
                if (!exit.isCompleted) exit.complete(code)
            },
            "aether-acp-terminal-$id",
        ).apply {
            isDaemon = true
            start()
        }
    }

    fun outputPayload(): JSONObject {
        val finished = exitCode
        return JSONObject()
            .put("output", currentOutput())
            .put("truncated", truncated)
            .apply {
                if (finished != null) {
                    put(
                        "exitStatus",
                        JSONObject()
                            .put("exitCode", finished.coerceAtLeast(0)),
                    )
                }
            }
    }

    suspend fun waitForExit(): JSONObject {
        val code = exit.await().coerceAtLeast(0)
        return JSONObject()
            .put("exitCode", code)
    }

    fun kill() {
        runCatching { process.destroy() }
        if (!process.waitFor(800, TimeUnit.MILLISECONDS)) {
            runCatching { process.destroyForcibly() }
        }
    }

    fun release() {
        kill()
    }

    private fun currentOutput(): String = synchronized(outputLock) {
        if (!outputDirty) return cachedOutput
        cachedOutput = output.toByteArray().toString(Charsets.UTF_8)
        outputDirty = false
        cachedOutput
    }
}
