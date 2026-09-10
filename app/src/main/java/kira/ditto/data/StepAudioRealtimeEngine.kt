package kira.ditto.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * StepAudio Realtime engine: WebSocket bidirectional streaming for ASR+TTS.
 *
 * Protocol:
 * - Client sends: input_audio_buffer.append (PCM base64), input_audio_buffer.commit
 * - Server sends: response.audio.delta (PCM base64), response.text.delta, response.done
 */
class StepAudioRealtimeEngine(
    private val option: ProviderModelOption,
) {
    companion object {
        private const val TAG = "AetherRealtime"
        private const val SAMPLE_RATE = 24000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var webSocket: WebSocket? = null
    private val connected = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    private val _audioChunks = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val audioChunks: Flow<ByteArray> = _audioChunks

    private val _textDeltas = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val textDeltas: Flow<String> = _textDeltas

    private val _turnComplete = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val turnComplete: Flow<Unit> = _turnComplete

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errors: Flow<String> = _errors

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket needs no read timeout
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    fun connect() {
        if (connected.get()) return
        closed.set(false)

        val base = option.baseUrl.trim().trimEnd('/')
        val wsBase = base
            .replace("https://", "wss://")
            .replace("http://", "ws://")
        val wsUrl = if (wsBase.endsWith("/v1")) {
            "$wsBase/realtime?model=${option.modelId}"
        } else {
            "$wsBase/v1/realtime?model=${option.modelId}"
        }

        val request = Request.Builder()
            .url(wsUrl)
            .header("Authorization", "Bearer ${option.apiKey.trim()}")
            .build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: okhttp3.Response) {
                connected.set(true)
                Log.i(TAG, "WebSocket connected: $wsUrl")
                // Send session configuration
                val config = JSONObject()
                    .put("type", "session.update")
                    .put("session", JSONObject()
                        .put("modalities", org.json.JSONArray().put("text").put("audio"))
                        .put("voice", "linyang")
                        .put("input_audio_format", "pcm16")
                        .put("output_audio_format", "pcm16")
                        .put("input_audio_transcription", JSONObject().put("model", "whisper-1"))
                        .put("turn_detection", JSONObject()
                            .put("type", "server_vad")
                            .put("threshold", 0.5)
                            .put("prefix_padding_ms", 300)
                            .put("silence_duration_ms", 500)
                        )
                    )
                ws.send(config.toString())
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                // Binary audio data
                _audioChunks.tryEmit(bytes.toByteArray())
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: okhttp3.Response?) {
                Log.w(TAG, "WebSocket failure", t)
                _errors.tryEmit(t.message ?: "WebSocket 连接失败")
                connected.set(false)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: $code $reason")
                connected.set(false)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code $reason")
                connected.set(false)
            }
        })
    }

    /**
     * Send PCM audio chunk (16-bit, mono, 24kHz).
     */
    fun sendAudioChunk(pcm: ByteArray) {
        if (!connected.get()) return
        val encoded = java.util.Base64.getEncoder().encodeToString(pcm)
        val msg = JSONObject()
            .put("type", "input_audio_buffer.append")
            .put("audio", encoded)
            .toString()
        webSocket?.send(msg)
    }

    /**
     * Commit the audio buffer (end of user speech).
     */
    fun commitAudio() {
        if (!connected.get()) return
        webSocket?.send(JSONObject().put("type", "input_audio_buffer.commit").toString())
    }

    /**
     * Send a text message (for text-to-speech-only mode).
     */
    fun sendText(text: String) {
        if (!connected.get()) return
        val msg = JSONObject()
            .put("type", "conversation.item.create")
            .put("item", JSONObject()
                .put("type", "message")
                .put("role", "user")
                .put("content", org.json.JSONArray().put(
                    JSONObject()
                        .put("type", "input_text")
                        .put("text", text)
                ))
            )
            .toString()
        webSocket?.send(msg)
        // Trigger response
        webSocket?.send(JSONObject().put("type", "response.create").toString())
    }

    fun disconnect() {
        closed.set(true)
        connected.set(false)
        webSocket?.close(1000, "client closing")
        webSocket = null
        scope.cancel()
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "response.audio.delta" -> {
                    val delta = json.optString("delta")
                    if (delta.isNotBlank()) {
                        val pcm = java.util.Base64.getDecoder().decode(delta)
                        _audioChunks.tryEmit(pcm)
                    }
                }
                "response.text.delta", "response.audio_transcript.delta" -> {
                    val delta = json.optString("delta")
                    if (delta.isNotBlank()) {
                        _textDeltas.tryEmit(delta)
                    }
                }
                "response.done" -> {
                    _turnComplete.tryEmit(Unit)
                }
                "error" -> {
                    val error = json.optJSONObject("error")
                    val msg = error?.optString("message") ?: "未知错误"
                    _errors.tryEmit(msg)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse message", e)
        }
    }
}
