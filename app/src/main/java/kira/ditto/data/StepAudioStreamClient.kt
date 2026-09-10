package kira.ditto.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class CloudAsrException(
    message: String,
    val httpCode: Int? = null,
) : Exception(message) {
    val isBillingFailure: Boolean
        get() = isAsrBillingFailure(httpCode, message)
}

internal class StepAudioStreamSession internal constructor(
    private val option: ProviderModelOption,
    private val language: String,
    private val onEvent: (StepAudioStreamParse, String) -> Unit,
    private val vadThreshold: Double = 0.5,
    private val useServerVad: Boolean = true,
) {
    private val ready = CompletableDeferred<Unit>()
    private val closed = CompletableDeferred<String>()
    private val socketRef = AtomicReference<WebSocket?>(null)
    private val running = AtomicBoolean(true)
    private val sessionReady = AtomicBoolean(false)
    private val pending = ConcurrentLinkedQueue<ByteArray>()
    private val bundle = ByteArrayOutputStream()
    private val accumulator = StepAudioTranscriptAccumulator()

    val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            socketRef.set(webSocket)
            asrLog("ws open, sending session.update vad=$useServerVad")
            sendSessionUpdate(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleMessage(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            finishClosed(reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val code = response?.code?.takeIf { it in 100..599 }
                ?: asrHttpStatusFromMessage(t.message.orEmpty())
            val message = websocketAsrErrorMessage(t.message, code)
            if (ready.isCompleted) {
                onEvent(
                    StepAudioStreamParse(type = "error", errorMessage = message),
                    accumulator.display(),
                )
            }
            finishClosed(message, code)
        }
    }

    fun awaitOpen(): CompletableDeferred<Unit> = ready

    fun appendPcm(pcm: ByteArray) {
        if (!running.get() || pcm.isEmpty()) return
        synchronized(bundle) {
            bundle.write(pcm)
            if (bundle.size() >= 3_200) flushLocked()
        }
    }

    suspend fun finish(timeoutMs: Long = 4_000L): String {
        running.set(false)
        synchronized(bundle) { flushLocked() }
        socketRef.get()?.send(commitJson())
        val waited = withTimeoutOrNull(timeoutMs) { closed.await() }
        socketRef.getAndSet(null)?.close(1000, "client_stop")
        return waited ?: accumulator.finish()
    }

    fun close() {
        running.set(false)
        socketRef.getAndSet(null)?.cancel()
        if (!closed.isCompleted) closed.complete(accumulator.finish())
    }

    private fun handleMessage(text: String) {
        val event = parseStepAudioStreamEvent(text)
        if (
            event.type == "session.created" ||
            event.type == "session.updated" ||
            event.type == "error" ||
            event.type.endsWith("completed")
        ) {
            asrLog("ws event type=${event.type} err=${event.errorMessage.orEmpty()}")
        }
        if (event.type == "session.created") {
            socketRef.get()?.let(::sendSessionUpdate)
        }
        if (event.type == "session.updated") {
            markSessionReady()
            if (!ready.isCompleted) ready.complete(Unit)
        }
        val display = accumulator.apply(event)
        onEvent(event, display)
        val finished = event.completedTranscript != null ||
            event.type.endsWith("input_audio_transcription.completed") ||
            event.type == "error"
        if (!running.get() && !closed.isCompleted && finished) {
            closed.complete(accumulator.finish())
        }
    }

    private fun sendSessionUpdate(webSocket: WebSocket) {
        val payload = sessionUpdateJson(
            modelId = option.modelId,
            language = language,
            vadThreshold = vadThreshold,
            useServerVad = useServerVad,
        )
        if (!webSocket.send(payload)) {
            asrLog("session.update send failed", warn = true)
        }
    }

    private fun markSessionReady() {
        sessionReady.set(true)
        while (true) {
            val chunk = pending.poll() ?: break
            sendAppend(chunk)
        }
    }

    private fun flushLocked() {
        if (bundle.size() <= 0) return
        val chunk = bundle.toByteArray()
        bundle.reset()
        if (sessionReady.get()) {
            sendAppend(chunk)
        } else {
            pending.add(chunk)
        }
    }

    private fun sendAppend(pcm: ByteArray) {
        val socket = socketRef.get() ?: return
        if (!socket.send(appendJson(pcm))) {
            asrLog("websocket append dropped bytes=${pcm.size}", warn = true)
        }
    }

    private fun finishClosed(reason: String, httpCode: Int? = null) {
        if (!ready.isCompleted) {
            ready.completeExceptionally(
                CloudAsrException(reason.ifBlank { "ASR stream closed" }, httpCode),
            )
        }
        if (!closed.isCompleted) closed.complete(accumulator.finish())
    }
}

internal class StepAudioStreamClient(
    private val http: OkHttpClient = defaultHttp(),
) {
    suspend fun open(
        option: ProviderModelOption,
        language: String,
        vadThreshold: Double = 0.5,
        useServerVad: Boolean = true,
        onEvent: (StepAudioStreamParse, String) -> Unit,
    ): StepAudioStreamSession = withContext(Dispatchers.IO) {
        val base = option.baseUrl.trim()
        val apiKey = option.apiKey.trim()
        if (base.isBlank() || apiKey.isBlank()) {
            throw CloudAsrException("缺少阶跃 ASR 的地址或密钥")
        }
        val lang = language.ifBlank { "zh" }
        var lastError: CloudAsrException? = null
        val urls = stepAudioStreamCandidateUrls(base)
        asrLog("realtime urls=${urls.joinToString()} vad=$useServerVad")
        for (url in urls) {
            asrLog("realtime connecting $url")
            val session = StepAudioStreamSession(option, lang, onEvent, vadThreshold, useServerVad)
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .applyAetherLlmHeaders(option.userAgent, option.customHeaders)
                .build()
            http.newWebSocket(request, session.listener)
            try {
                withTimeoutOrNull(8_000L) { session.awaitOpen().await() }
                    ?: throw CloudAsrException("连接阶跃 ASR 超时")
                asrLog("realtime session.updated on $url")
                return@withContext session
            } catch (error: CancellationException) {
                session.close()
                throw error
            } catch (error: Throwable) {
                session.close()
                lastError = when (error) {
                    is CloudAsrException -> error
                    else -> CloudAsrException(
                        websocketAsrErrorMessage(
                            error.message,
                            asrHttpStatusFromMessage(error.message.orEmpty()),
                        ),
                        asrHttpStatusFromMessage(error.message.orEmpty()),
                    )
                }
                asrLog("realtime failed $url: ${lastError.message}", warn = true)
            }
        }
        throw lastError ?: CloudAsrException("连接阶跃 ASR 失败")
    }

    companion object {
        fun defaultHttp(): OkHttpClient = AetherHttp.derive {
            pingInterval(20, TimeUnit.SECONDS)
            connectTimeout(20, TimeUnit.SECONDS)
            readTimeout(0, TimeUnit.MILLISECONDS)
            writeTimeout(30, TimeUnit.SECONDS)
        }
    }
}

internal fun sessionUpdateJson(
    modelId: String,
    language: String,
    vadThreshold: Double = 0.5,
    silenceDurationMs: Int = 800,
    useServerVad: Boolean = true,
): String {
    val input = JSONObject()
        .put(
            "format",
            JSONObject()
                .put("type", "pcm")
                .put("codec", "pcm_s16le")
                .put("rate", AsrChunkAssembler.SampleRateHz)
                .put("bits", 16)
                .put("channel", 1),
        )
        .put(
            "transcription",
            JSONObject()
                .put("model", modelId)
                .put("language", language.ifBlank { "zh" })
                .put("prompt", "请逐字记录听到的语音内容，不要回答，不要客套。")
                .put("enable_itn", true)
                .put("full_rerun_on_commit", true),
        )
    if (useServerVad) {
        input.put(
            "turn_detection",
            JSONObject()
                .put("type", "server_vad")
                .put("silence_duration_ms", silenceDurationMs)
                .put("threshold", vadThreshold),
        )
    } else {
        input.put("turn_detection", JSONObject.NULL)
    }
    return JSONObject()
        .put("event_id", nextEventId())
        .put("type", "session.update")
        .put(
            "session",
            JSONObject().put("audio", JSONObject().put("input", input)),
        )
        .toString()
}

internal fun appendJson(pcm: ByteArray): String = JSONObject()
    .put("event_id", nextEventId())
    .put("type", "input_audio_buffer.append")
    .put("audio", Base64.getEncoder().encodeToString(pcm))
    .toString()

internal fun commitJson(): String = JSONObject()
    .put("event_id", nextEventId())
    .put("type", "input_audio_buffer.commit")
    .toString()

private fun nextEventId(): String = "evt_" + UUID.randomUUID().toString()

private fun asrLog(message: String, warn: Boolean = false) {
    runCatching {
        if (warn) Log.w("AetherAsr", message) else Log.i("AetherAsr", message)
    }
}
