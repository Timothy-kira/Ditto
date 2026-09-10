package kira.ditto.data.remote

import kira.ditto.data.AetherDiagnosticLogger
import kira.ditto.data.kimi.KimiAcpProtocol
import kira.ditto.data.normalizeRemoteBaseUrl
import kira.ditto.data.remotePermissionWireFromUi
import kira.ditto.data.remoteWebSocketUrl
import kira.ditto.data.uiPermissionModeFromRemote
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

private const val JsonMedia = "application/json; charset=utf-8"
private const val ConnectTimeoutMillis = 12_000L
private const val HelloAckTimeoutMillis = 5_000L
private const val CatchUpPollMillis = 750L
private const val TurnTimeoutMillis = 10 * 60 * 1000L
private const val HelloFrameId = "hello"
private const val SubscribeFrameId = "sub"

private data class RemoteSocketEvent(
    val name: String,
    val payload: JSONObject,
)

class RemoteKimiWebClient(
    private val diagnosticLogger: AetherDiagnosticLogger = AetherDiagnosticLogger.NoOp,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build(),
) {
    @Volatile
    private var activeSocket: WebSocket? = null
    @Volatile
    private var activeSessionId: String? = null
    @Volatile
    private var activeBaseUrl: String? = null
    @Volatile
    private var activeToken: String? = null

    suspend fun probe(baseUrl: String, token: String): JSONObject = withContext(Dispatchers.IO) {
        val origin = normalizeRemoteBaseUrl(baseUrl) ?: error("Remote address is invalid.")
        apiGet(origin, token, "/api/v1/meta")
    }

    suspend fun listWorkspaces(
        baseUrl: String,
        token: String,
    ): List<kira.ditto.data.RemoteWorkspace> = withContext(Dispatchers.IO) {
        val origin = normalizeRemoteBaseUrl(baseUrl) ?: error("Remote address is invalid.")
        parseRemoteWorkspaces(apiGet(origin, token, "/api/v1/workspaces"))
    }

    suspend fun listModels(
        baseUrl: String,
        token: String,
    ): kira.ditto.data.RemoteKimiModelCatalog = withContext(Dispatchers.IO) {
        val origin = normalizeRemoteBaseUrl(baseUrl) ?: error("Remote address is invalid.")
        val models = parseRemoteModels(apiGet(origin, token, "/api/v1/models"))
        val config = runCatching {
            apiGet(origin, token, "/api/v1/config")
        }.getOrDefault(JSONObject())
        val defaultModelId = config.optString("default_model").ifBlank {
            models.firstOrNull()?.id.orEmpty()
        }
        kira.ditto.data.RemoteKimiModelCatalog(
            models = models,
            defaultModelId = defaultModelId,
            defaultPermissionMode = parseRemotePermissionMode(config),
        )
    }

    suspend fun updateSessionModel(
        baseUrl: String,
        token: String,
        sessionId: String,
        modelId: String,
    ) = updateSessionAgentConfig(
        baseUrl = baseUrl,
        token = token,
        sessionId = sessionId,
        modelId = modelId,
    )

    suspend fun updateSessionAgentConfig(
        baseUrl: String,
        token: String,
        sessionId: String,
        modelId: String? = null,
        permissionMode: String? = null,
        goalControl: String? = null,
    ) = withContext(Dispatchers.IO) {
        val origin = normalizeRemoteBaseUrl(baseUrl) ?: error("Remote address is invalid.")
        check(sessionId.isNotBlank()) { "Remote session is missing." }
        val agent = JSONObject()
        modelId?.trim()?.takeIf { it.isNotBlank() }?.let { agent.put("model", it) }
        permissionMode?.trim()?.takeIf { it.isNotBlank() }?.let {
            applyRemotePermissionToAgentConfig(agent, it)
        }
        goalControl?.trim()?.takeIf { it.isNotBlank() }?.let { agent.put("goal_control", it) }
        check(agent.length() > 0) { "Remote session profile is empty." }
        apiPost(
            origin,
            token,
            "/api/v1/sessions/$sessionId/profile",
            JSONObject().put("agent_config", agent),
        )
    }

    suspend fun updatePermissionConfig(
        baseUrl: String,
        token: String,
        permissionMode: String,
    ) = withContext(Dispatchers.IO) {
        val origin = normalizeRemoteBaseUrl(baseUrl) ?: error("Remote address is invalid.")
        apiPost(origin, token, "/api/v1/config", remoteGlobalConfigForPermission(permissionMode))
    }

    fun abortActiveTurn() {
        val sessionId = activeSessionId
        val origin = activeBaseUrl
        val token = activeToken
        activeSocket?.close(1000, "abort")
        activeSocket = null
        if (sessionId.isNullOrBlank() || origin.isNullOrBlank() || token.isNullOrBlank()) return
        runCatching {
            httpClient.newCall(
                authorizedRequest(origin, token, "/api/v1/sessions/$sessionId:abort")
                    .post("{}".toRequestBody(JsonMedia.toMediaType()))
                    .build(),
            ).execute().close()
        }
    }

    suspend fun runTurn(
        payload: JSONObject,
        onEvent: suspend (String, JSONObject) -> Unit,
    ): JSONObject = withContext(Dispatchers.IO) {
        val origin = normalizeRemoteBaseUrl(payload.optString("remote_base_url"))
            ?: error("Remote machine address is missing.")
        val token = payload.optString("remote_token").trim()
        check(token.isNotBlank()) { "Remote machine token is missing." }
        val cwd = payload.optString("remote_cwd").ifBlank {
            payload.optString("workspace_directory")
        }
        var kimiSessionId = payload.optString("remote_kimi_session_id").trim()
        if (
            kimiSessionId.isBlank() ||
            kimiSessionId.startsWith("remote-pending-") ||
            kimiSessionId.startsWith("aether-")
        ) {
            kimiSessionId = createSession(origin, token, cwd, payload)
        } else {
            applySessionProfile(origin, token, kimiSessionId, payload)
        }

        val prompt = KimiAcpProtocol.turnUserContent(payload.optJSONArray("messages"))
        check(prompt.length() > 0) { "Remote prompt is empty." }

        val events = Channel<RemoteSocketEvent>(Channel.UNLIMITED)
        val helloReceived = AtomicBoolean(false)
        val helloAcked = AtomicBoolean(false)
        val subscribed = AtomicBoolean(false)
        val connectFailed = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (frame.optString("type")) {
                    "server_hello" -> {
                        helloReceived.set(true)
                        return
                    }
                    "ping" -> {
                        webSocket.send(
                            JSONObject()
                                .put("type", "pong")
                                .put(
                                    "payload",
                                    JSONObject().put(
                                        "nonce",
                                        frame.optJSONObject("payload")?.opt("nonce"),
                                    ),
                                )
                                .toString(),
                        )
                        return
                    }
                    "ack" -> {
                        val id = frame.optString("id")
                        val failed = frame.optInt("code", 0) != 0
                        val message = frame.optString("msg").ifBlank { "Remote handshake failed." }
                        when (id) {
                            HelloFrameId -> if (failed) connectFailed.set(message) else helloAcked.set(true)
                            SubscribeFrameId -> if (failed) connectFailed.set(message) else subscribed.set(true)
                        }
                        return
                    }
                    "error" -> {
                        val payload = frame.optJSONObject("payload") ?: JSONObject()
                        val message = payload.optString("msg").ifBlank {
                            payload.optString("message").ifBlank { "Remote WebSocket error." }
                        }
                        if (frame.optString("session_id").isBlank()) {
                            connectFailed.compareAndSet(null, message)
                            events.trySend(
                                RemoteSocketEvent("turn_failed", JSONObject().put("error", message)),
                            )
                        }
                        return
                    }
                }
                val mapped = mapRemoteEvent(frame)
                if (mapped == null) {
                    val rawType = frame.optString("type")
                    if (rawType.isNotBlank() && !isIgnorableRemoteFrame(rawType)) {
                        diagnosticLogger.event(
                            category = "remote_kimi",
                            event = "unmapped_ws_frame",
                            level = "debug",
                            details = mapOf("type" to rawType, "session_id" to kimiSessionId),
                        )
                    }
                    return
                }
                if (mapped.first == "turn_failed") {
                    connectFailed.compareAndSet(null, mapped.second.optString("error"))
                }
                events.trySend(RemoteSocketEvent(mapped.first, mapped.second))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connectFailed.compareAndSet(null, t.message ?: t.javaClass.simpleName)
                events.trySend(
                    RemoteSocketEvent(
                        "socket_closed",
                        JSONObject().put("error", t.message ?: t.javaClass.simpleName),
                    ),
                )
                events.close()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                events.trySend(
                    RemoteSocketEvent(
                        "socket_closed",
                        JSONObject().put("code", code).put("reason", reason),
                    ),
                )
                events.close()
            }
        }

        val request = Request.Builder()
            .url(remoteWebSocketUrl(origin))
            .header("Authorization", "Bearer $token")
            .header("Sec-WebSocket-Protocol", "kimi-code.bearer.$token")
            .build()
        val socket = httpClient.newWebSocket(request, listener)
        activeSocket = socket
        activeSessionId = kimiSessionId
        activeBaseUrl = origin
        activeToken = token

        val streamedAssistant = StringBuilder()
        val streamedReasoning = StringBuilder()
        try {
            val wsReady = runCatching {
                handshakeRemoteSocket(
                    socket = socket,
                    sessionId = kimiSessionId,
                    helloReceived = helloReceived,
                    helloAcked = helloAcked,
                    subscribed = subscribed,
                    connectFailed = connectFailed,
                )
            }.onFailure { error ->
                diagnosticLogger.event(
                    category = "remote_kimi",
                    event = "ws_handshake_failed",
                    level = "warn",
                    details = mapOf(
                        "session_id" to kimiSessionId,
                        "error" to (error.message ?: error.javaClass.simpleName),
                    ),
                )
            }.isSuccess
            onEvent("assistant_request_start", JSONObject())
            val promptResult = postPrompt(origin, token, kimiSessionId, prompt, payload)
            val promptId = promptResult.optString("prompt_id")
            val promptCreatedAt = promptResult.optString("created_at")
            val sawBusy = AtomicBoolean(false)
            val notifiedInteraction = AtomicBoolean(false)
            val promptStartedAt = System.currentTimeMillis()
            var stopReason = "end_turn"
            var socketDied = !wsReady
            withTimeout(TurnTimeoutMillis) {
                while (true) {
                    val event = withTimeoutOrNull(CatchUpPollMillis) {
                        events.receiveCatching().getOrNull()
                    }
                    if (event == null) {
                        val caught = catchUpRemoteTurn(
                            origin = origin,
                            token = token,
                            sessionId = kimiSessionId,
                            streamedAssistant = streamedAssistant,
                            streamedReasoning = streamedReasoning,
                            promptId = promptId,
                            promptCreatedAt = promptCreatedAt,
                            promptStartedAtMillis = promptStartedAt,
                            sawBusy = sawBusy,
                            notifiedInteraction = notifiedInteraction,
                            onEvent = onEvent,
                        )
                        if (caught.finished) {
                            stopReason = caught.stopReason
                            break
                        }
                        continue
                    }
                    when (event.name) {
                        "assistant_text_delta" -> {
                            event.payload.optString("delta").takeIf(String::isNotEmpty)
                                ?.let(streamedAssistant::append)
                            onEvent(event.name, event.payload)
                        }
                        "assistant_reasoning_delta" -> {
                            event.payload.optString("delta").takeIf(String::isNotEmpty)
                                ?.let(streamedReasoning::append)
                            onEvent(event.name, event.payload)
                        }
                        "socket_closed" -> socketDied = true
                        "turn_ended" -> {
                            if (streamedAssistant.isEmpty()) {
                                catchUpRemoteTurn(
                                    origin = origin,
                                    token = token,
                                    sessionId = kimiSessionId,
                                    streamedAssistant = streamedAssistant,
                                    streamedReasoning = streamedReasoning,
                                    promptId = promptId,
                                    promptCreatedAt = promptCreatedAt,
                                    promptStartedAtMillis = promptStartedAt,
                                    sawBusy = sawBusy,
                                    notifiedInteraction = notifiedInteraction,
                                    onEvent = onEvent,
                                )
                            }
                            stopReason = event.payload.optString("stop_reason", "end_turn")
                            break
                        }
                        "turn_failed" ->
                            error(event.payload.optString("error").ifBlank { "Remote turn failed." })
                        else -> onEvent(event.name, event.payload)
                    }
                }
            }
            diagnosticLogger.event(
                category = "remote_kimi",
                event = "turn_complete",
                details = mapOf(
                    "session_id" to kimiSessionId,
                    "assistant_chars" to streamedAssistant.length,
                    "socket_died" to socketDied,
                ),
            )
            JSONObject().apply {
                put("ok", true)
                put("assistant_text", streamedAssistant.toString())
                put("reasoning_text", streamedReasoning.toString())
                put("assistant_message", JSONObject())
                put("stop_reason", KimiAcpProtocol.mapStopReason(stopReason))
                put("session_id", kimiSessionId)
                put("response_id", kimiSessionId)
                put("runtime", "remote")
                put("cwd", cwd)
            }
        } catch (error: CancellationException) {
            abortActiveTurn()
            throw error
        } finally {
            events.close()
            socket.close(1000, "turn-complete")
            if (activeSocket === socket) {
                activeSocket = null
                activeSessionId = null
                activeBaseUrl = null
                activeToken = null
            }
        }
    }

    private fun createSession(origin: String, token: String, cwd: String, payload: JSONObject): String {
        val body = JSONObject().put(
            "metadata",
            JSONObject().put("cwd", cwd.ifBlank { "." }),
        )
        val agent = JSONObject()
        val modelId = payloadModelId(payload)
        if (modelId.isNotBlank()) {
            agent.put("model", modelId)
        }
        payload.optString("permission_mode").trim().takeIf { it.isNotBlank() }?.let { uiMode ->
            applyRemotePermissionToAgentConfig(agent, uiMode)
        }
        if (agent.length() > 0) {
            body.put("agent_config", agent)
        }
        val data = apiPost(origin, token, "/api/v1/sessions", body)
        val id = data.optString("id").ifBlank {
            data.optJSONObject("session")?.optString("id").orEmpty()
        }.ifBlank {
            data.optString("session_id")
        }
        check(id.isNotBlank()) { "Remote kimi web did not return a session id." }
        applySessionProfile(origin, token, id, payload)
        return id
    }

    private fun applySessionProfile(
        origin: String,
        token: String,
        sessionId: String,
        payload: JSONObject,
    ) {
        val agent = JSONObject()
        val modelId = payloadModelId(payload)
        if (modelId.isNotBlank()) agent.put("model", modelId)
        payload.optString("permission_mode").trim().takeIf { it.isNotBlank() }?.let { uiMode ->
            applyRemotePermissionToAgentConfig(agent, uiMode)
        }
        if (agent.length() == 0) return
        runCatching {
            apiPost(
                origin,
                token,
                "/api/v1/sessions/$sessionId/profile",
                JSONObject().put("agent_config", agent),
            )
        }
    }

    private suspend fun handshakeRemoteSocket(
        socket: WebSocket,
        sessionId: String,
        helloReceived: AtomicBoolean,
        helloAcked: AtomicBoolean,
        subscribed: AtomicBoolean,
        connectFailed: java.util.concurrent.atomic.AtomicReference<String?>,
    ) {
        withTimeout(ConnectTimeoutMillis) {
            while (!helloReceived.get()) {
                connectFailed.get()?.let { error(it) }
                delay(40)
            }
        }
        socket.send(
            JSONObject()
                .put("type", "client_hello")
                .put("id", HelloFrameId)
                .put(
                    "payload",
                    JSONObject()
                        .put("client_id", "ditto-android")
                        .put("subscriptions", JSONArray().put(sessionId)),
                )
                .toString(),
        )
        withTimeout(HelloAckTimeoutMillis) {
            val waitStart = System.nanoTime()
            while (!helloAcked.get()) {
                connectFailed.get()?.let { error(it) }
                if ((System.nanoTime() - waitStart) / 1_000_000L >= 2_000L) break
                delay(40)
            }
        }
        socket.send(
            JSONObject()
                .put("type", "subscribe")
                .put("id", SubscribeFrameId)
                .put(
                    "payload",
                    JSONObject()
                        .put("session_ids", JSONArray().put(sessionId))
                        .put(
                            "cursors",
                            JSONObject().put(sessionId, JSONObject().put("seq", 0)),
                        ),
                )
                .toString(),
        )
        withTimeout(ConnectTimeoutMillis) {
            while (!subscribed.get()) {
                connectFailed.get()?.let { error(it) }
                delay(40)
            }
        }
    }

    private suspend fun catchUpRemoteTurn(
        origin: String,
        token: String,
        sessionId: String,
        streamedAssistant: StringBuilder,
        streamedReasoning: StringBuilder,
        promptId: String,
        promptCreatedAt: String,
        promptStartedAtMillis: Long,
        sawBusy: AtomicBoolean,
        notifiedInteraction: AtomicBoolean,
        onEvent: suspend (String, JSONObject) -> Unit,
    ): RemoteCatchUpResult {
        val session = runCatching {
            apiGet(origin, token, "/api/v1/sessions/${urlEncode(sessionId)}")
        }.getOrNull() ?: return RemoteCatchUpResult(finished = false, stopReason = "end_turn")
        val pending = session.optString("pending_interaction").ifBlank { "none" }
        if (pending.equals("approval", ignoreCase = true) || pending.equals("question", ignoreCase = true)) {
            sawBusy.set(true)
            if (notifiedInteraction.compareAndSet(false, true)) {
                onEvent(
                    "assistant_retry",
                    JSONObject()
                        .put("error_message", "Waiting for permission on the computer. Approve it in Kimi Code.")
                        .put("attempt", 1)
                        .put("max_attempts", 1),
                )
            }
            return RemoteCatchUpResult(finished = false, stopReason = "end_turn")
        }
        val busy = session.optBoolean("busy") || session.optBoolean("main_turn_active")
        if (busy) {
            sawBusy.set(true)
            return RemoteCatchUpResult(finished = false, stopReason = "end_turn")
        }
        val waited = System.currentTimeMillis() - promptStartedAtMillis
        if (!sawBusy.get() && waited < 15_000L) {
            return RemoteCatchUpResult(finished = false, stopReason = "end_turn")
        }

        val messages = runCatching {
            apiGet(
                origin,
                token,
                "/api/v1/sessions/${urlEncode(sessionId)}/messages?page_size=20",
            )
        }.getOrDefault(JSONObject())
        val caught = extractLatestAssistantMessage(messages, promptId, promptCreatedAt)
        if (caught.text.isNotEmpty() && streamedAssistant.isEmpty()) {
            streamedAssistant.append(caught.text)
            onEvent("assistant_text_delta", JSONObject().put("delta", caught.text))
        }
        if (caught.thinking.isNotEmpty() && streamedReasoning.isEmpty()) {
            streamedReasoning.append(caught.thinking)
            onEvent("assistant_reasoning_delta", JSONObject().put("delta", caught.thinking))
        }
        val reason = session.optString("last_turn_reason").ifBlank { "completed" }
        if (reason.equals("failed", ignoreCase = true) && streamedAssistant.isEmpty()) {
            error(session.optString("last_error").ifBlank { "Remote turn failed." })
        }
        return RemoteCatchUpResult(
            finished = true,
            stopReason = when (reason.lowercase()) {
                "cancelled" -> "cancelled"
                "failed" -> "error"
                else -> "end_turn"
            },
        )
    }

    private fun postPrompt(
        origin: String,
        token: String,
        sessionId: String,
        prompt: JSONArray,
        payload: JSONObject,
    ): JSONObject {
        val body = JSONObject().put("content", prompt)
        payloadModelId(payload).takeIf { it.isNotBlank() }?.let { body.put("model", it) }
        payload.optString("permission_mode").trim().takeIf { it.isNotBlank() }?.let { uiMode ->
            body.put("permission_mode", remotePermissionWireFromUi(uiMode).permissionMode)
        }
        return apiPost(origin, token, "/api/v1/sessions/$sessionId/prompts", body)
    }

    private fun apiGet(origin: String, token: String, path: String): JSONObject {
        httpClient.newCall(authorizedRequest(origin, token, path).get().build()).execute().use { response ->
            return unwrapEnvelope(response)
        }
    }

    private fun apiPost(origin: String, token: String, path: String, body: JSONObject): JSONObject {
        val request = authorizedRequest(origin, token, path)
            .post(body.toString().toRequestBody(JsonMedia.toMediaType()))
            .build()
        httpClient.newCall(request).execute().use { response ->
            return unwrapEnvelope(response)
        }
    }

    private fun authorizedRequest(origin: String, token: String, path: String): Request.Builder =
        Request.Builder()
            .url(origin.trimEnd('/') + path)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")

    private fun unwrapEnvelope(response: Response): JSONObject {
        val raw = response.body?.string().orEmpty()
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: JSONObject().put("raw", raw)
        if (!response.isSuccessful) {
            error(json.optString("msg").ifBlank { "Remote HTTP ${response.code}" })
        }
        if (json.has("code") && json.optInt("code") != 0) {
            error(json.optString("msg").ifBlank { "Remote error ${json.optInt("code")}" })
        }
        return json.optJSONObject("data") ?: json
    }
}

private data class RemoteCatchUpResult(
    val finished: Boolean,
    val stopReason: String,
)

internal fun mapRemoteEvent(frame: JSONObject): Pair<String, JSONObject>? {
    val event = frame.optJSONObject("data") ?: frame
    val type = normalizeRemoteEventType(
        event.optString("type").ifBlank { event.optString("event") },
    )
    val payload = event.optJSONObject("payload") ?: event
    val delta = extractRemoteDelta(payload)
    return when {
        type == "hello" || type == "server.hello" || type == "server_hello" || type == "ack" -> null
        type == "turn.started" || type == "turn_started" ->
            "assistant_request_start" to JSONObject()
        type == "assistant.delta" || type == "assistant_delta" || type == "text.delta" ->
            "assistant_text_delta" to JSONObject().put("delta", delta)
        type == "thinking.delta" || type == "assistant.thinking.delta" || type == "reasoning.delta" ->
            "assistant_reasoning_delta" to JSONObject().put("delta", delta)
        type.startsWith("tool.call") && (type.endsWith("started") || type.endsWith("start")) ->
            "tool_call_start" to toolPayload(payload, running = true)
        type == "tool.call.delta" || type == "tool_call_delta" ->
            "tool_call_delta" to toolPayload(payload, running = true)
        type.contains("tool") && (type.endsWith("result") || type.endsWith("completed") || type.endsWith("end")) ->
            "tool_call_end" to toolPayload(payload, running = false)
        type == "turn.ended" || type == "turn_ended" ->
            "turn_ended" to JSONObject().put("stop_reason", payload.optString("stop_reason", "end_turn"))
        type.contains("error") || type == "turn.failed" ->
            "turn_failed" to JSONObject().put("error", payload.optString("message").ifBlank { payload.toString() })
        type.contains("permission") || type.contains("approval") || type.contains("question") ->
            "assistant_retry" to JSONObject()
                .put("error_message", "Waiting for permission on the computer. Approve it in Kimi Code.")
                .put("attempt", 1)
                .put("max_attempts", 1)
        else -> null
    }
}

internal fun normalizeRemoteEventType(raw: String): String =
    raw.trim().lowercase().removePrefix("event.")

internal fun isIgnorableRemoteFrame(type: String): Boolean {
    val normalized = normalizeRemoteEventType(type)
    return normalized in setOf(
        "hello",
        "server.hello",
        "server_hello",
        "ack",
        "ping",
        "pong",
        "session.meta.updated",
        "session.created",
        "session.archived",
        "session.work_changed",
        "session.status_changed",
        "config.changed",
    ) || normalized.startsWith("workspace.")
}

internal fun extractLatestAssistantMessage(
    data: JSONObject,
    promptId: String = "",
    sinceCreatedAt: String = "",
): RemoteAssistantCatchUp {
    val items = data.optJSONArray("items") ?: JSONArray()
    for (index in 0 until items.length()) {
        val message = items.optJSONObject(index) ?: continue
        if (message.optString("role") != "assistant") continue
        if (!assistantMessageBelongsToPrompt(message, promptId, sinceCreatedAt)) continue
        return extractAssistantContent(message.optJSONArray("content"))
    }
    return RemoteAssistantCatchUp()
}

internal fun assistantMessageBelongsToPrompt(
    message: JSONObject,
    promptId: String,
    sinceCreatedAt: String,
): Boolean {
    if (promptId.isNotBlank() && message.optString("prompt_id") == promptId) return true
    if (sinceCreatedAt.isNotBlank()) {
        val createdAt = message.optString("created_at")
        if (createdAt.isNotBlank() && createdAt >= sinceCreatedAt) return true
    }
    return promptId.isBlank() && sinceCreatedAt.isBlank()
}

internal data class RemoteAssistantCatchUp(
    val text: String = "",
    val thinking: String = "",
)

private fun extractAssistantContent(content: JSONArray?): RemoteAssistantCatchUp {
    if (content == null) return RemoteAssistantCatchUp()
    val text = StringBuilder()
    val thinking = StringBuilder()
    for (index in 0 until content.length()) {
        val part = content.optJSONObject(index) ?: continue
        when (part.optString("type").lowercase()) {
            "text" -> text.append(part.optString("text"))
            "thinking" -> thinking.append(
                part.optString("thinking").ifBlank { part.optString("text") },
            )
        }
    }
    return RemoteAssistantCatchUp(text = text.toString(), thinking = thinking.toString())
}

private fun urlEncode(value: String): String =
    URLEncoder.encode(value, Charsets.UTF_8.name())

private fun extractRemoteDelta(payload: JSONObject): String {
    val direct = payload.optString("delta").ifBlank { payload.optString("text") }
    if (direct.isNotBlank()) return direct
    val content = payload.opt("content")
    return when (content) {
        is String -> content
        is JSONObject -> content.optString("text").ifBlank { content.optString("delta") }
        else -> ""
    }
}

internal fun parseRemotePermissionMode(config: JSONObject): String {
    val plan = config.optBoolean("default_plan_mode") || config.optBoolean("plan_mode")
    val permission = config.optString("default_permission_mode").ifBlank {
        config.optString("permission_mode")
    }
    val yolo = config.optBoolean("yolo") && permission.isBlank()
    return uiPermissionModeFromRemote(
        permissionMode = permission,
        planMode = plan,
        yolo = yolo,
    )
}

internal fun applyRemotePermissionToAgentConfig(agent: JSONObject, uiMode: String) {
    val wire = remotePermissionWireFromUi(uiMode)
    agent.put("permission_mode", wire.permissionMode)
    agent.put("plan_mode", wire.planMode)
}

internal fun remoteGlobalConfigForPermission(uiMode: String): JSONObject {
    val wire = remotePermissionWireFromUi(uiMode)
    return JSONObject().apply {
        put("default_plan_mode", wire.planMode)
        put("plan_mode", wire.planMode)
        if (uiMode.trim().equals("plan", ignoreCase = true)) {
            return@apply
        }
        put("default_permission_mode", wire.permissionMode)
        put("yolo", wire.yolo)
    }
}

internal fun parseRemoteModels(data: JSONObject): List<kira.ditto.data.RemoteKimiModel> {
    val items = data.optJSONArray("items") ?: JSONArray()
    return buildList {
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val id = item.optString("model").trim()
            if (id.isBlank()) continue
            val efforts = item.optJSONArray("support_efforts") ?: JSONArray()
            val capabilities = item.optJSONArray("capabilities") ?: JSONArray()
            add(
                kira.ditto.data.RemoteKimiModel(
                    id = id,
                    provider = item.optString("provider").trim(),
                    displayName = item.optString("display_name").trim().ifBlank { id },
                    maxContextSize = item.optLong("max_context_size"),
                    capabilities = (0 until capabilities.length()).map { capabilities.optString(it) },
                    supportEfforts = (0 until efforts.length()).map { efforts.optString(it) },
                    defaultEffort = item.optString("default_effort").trim(),
                ),
            )
        }
    }
}

private fun payloadModelId(payload: JSONObject): String {
    val fromConfig = payload.optJSONObject("model_config")?.optString("model_id").orEmpty().trim()
    return fromConfig.ifBlank { payload.optString("model").trim() }
}

internal fun parseRemoteWorkspaces(data: JSONObject): List<kira.ditto.data.RemoteWorkspace> {
    val items = data.optJSONArray("items") ?: JSONArray()
    return buildList {
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val root = item.optString("root").trim()
            if (root.isBlank()) continue
            val name = item.optString("name").trim().ifBlank {
                root.substringAfterLast('\\').substringAfterLast('/').ifBlank { root }
            }
            add(
                kira.ditto.data.RemoteWorkspace(
                    id = item.optString("id"),
                    name = name,
                    root = root,
                    lastOpenedAt = item.optString("last_opened_at"),
                ),
            )
        }
    }.sortedByDescending { it.lastOpenedAt }
}

private fun toolPayload(payload: JSONObject, running: Boolean): JSONObject {
    val id = payload.optString("id").ifBlank {
        payload.optString("tool_call_id").ifBlank { payload.optString("call_id") }
    }
    val name = payload.optString("name").ifBlank {
        payload.optString("tool").ifBlank { payload.optString("tool_name") }
    }
    val args = payload.opt("arguments") ?: payload.opt("input") ?: JSONObject()
    val output = payload.opt("result") ?: payload.opt("output")
    return JSONObject()
        .put("id", id.ifBlank { "remote-tool" })
        .put("tool_call_id", id.ifBlank { "remote-tool" })
        .put("name", name.ifBlank { "tool" })
        .put("arguments", if (args is JSONObject || args is JSONArray) args else JSONObject().put("value", args))
        .put("is_error", payload.optBoolean("is_error") || payload.optBoolean("error"))
        .apply {
            if (!running && output != null) put("output", output)
        }
}
