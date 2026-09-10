package kira.ditto.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * StepFun TTS: POST {root}/v1/audio/speech
 * Current model is stepaudio-2.5-tts. Tries Step Plan then public endpoints.
 */
internal object StepAudioTtsEngine {
    private const val TAG = "AetherTts"
    private val httpClient = AetherHttp.derive {
        connectTimeout(15, TimeUnit.SECONDS)
        readTimeout(60, TimeUnit.SECONDS)
        writeTimeout(30, TimeUnit.SECONDS)
    }

    fun synthesizeStream(
        option: ProviderModelOption,
        text: String,
        voice: String,
    ): Flow<ByteArray> = flow {
        if (option.apiKey.isBlank()) {
            throw TtsException("阶跃 TTS 未配置密钥")
        }
        val modelId = resolveStepAudioTtsModelId(option.modelId)
        val clipped = text.take(StepAudioTtsMaxInputChars)
        val resolvedVoice = voice.ifBlank { StepAudioTtsDefaultVoice }
        val payload = JSONObject()
            .put("model", modelId)
            .put("input", clipped)
            .put("voice", resolvedVoice)
            .put("response_format", "pcm")
            .put("sample_rate", 24000)
            .toString()
        val urls = stepAudioSpeechCandidateUrls(option.baseUrl)
        if (urls.isEmpty()) {
            throw TtsException("阶跃 TTS 未配置地址")
        }
        var lastError: TtsException? = null
        for (url in urls) {
            Log.i(TAG, "step tts POST $url model=$modelId voice=$resolvedVoice chars=${clipped.length}")
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${option.apiKey.trim()}")
                .applyAetherLlmHeaders(option.userAgent, option.customHeaders)
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                response.close()
                val error = TtsException(cloudTtsErrorMessage(body, response.code), response.code)
                lastError = error
                if (!shouldRetryTtsEndpoint(error.httpCode, error.message)) throw error
                continue
            }
            val contentType = response.header("Content-Type").orEmpty()
            if (contentType.contains("json", ignoreCase = true)) {
                val body = response.body?.string().orEmpty()
                response.close()
                throw TtsException(cloudTtsErrorMessage(body, response.code), response.code)
            }
            response.use { success ->
                val body = success.body ?: throw TtsException("阶跃 TTS 空响应")
                val buffer = ByteArray(8192)
                body.byteStream().use { stream ->
                    while (true) {
                        val read = stream.read(buffer)
                        if (read == -1) break
                        emit(buffer.copyOf(read))
                    }
                }
            }
            return@flow
        }
        throw lastError ?: TtsException("阶跃 TTS 请求失败")
    }.flowOn(Dispatchers.IO)
}
