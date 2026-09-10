package kira.ditto.data

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
 * OpenAI-compatible TTS engine: POST {baseUrl}/audio/speech
 * Supports streaming PCM response.
 */
internal object OpenAiTtsEngine {
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
        val base = option.baseUrl.trim().trimEnd('/')
        if (base.isBlank() || option.apiKey.isBlank()) {
            throw TtsException("TTS 未配置地址或密钥")
        }
        val url = if (base.endsWith("/audio/speech")) {
            base
        } else if (base.endsWith("/v1")) {
            "$base/audio/speech"
        } else {
            "$base/v1/audio/speech"
        }
        val payload = JSONObject()
            .put("model", option.modelId)
            .put("input", text)
            .put("voice", voice)
            .put("response_format", "pcm")
            .toString()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${option.apiKey.trim()}")
            .applyAetherLlmHeaders(option.userAgent, option.customHeaders)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                throw TtsException(cloudTtsErrorMessage(body, response.code), response.code)
            }
            val contentType = response.header("Content-Type").orEmpty()
            if (contentType.contains("json", ignoreCase = true)) {
                val body = response.body?.string().orEmpty()
                throw TtsException(cloudTtsErrorMessage(body, response.code), response.code)
            }
            val body = response.body ?: throw TtsException("TTS 空响应")
            val buffer = ByteArray(8192)
            body.byteStream().use { stream ->
                while (true) {
                    val read = stream.read(buffer)
                    if (read == -1) break
                    emit(buffer.copyOf(read))
                }
            }
        }
    }.flowOn(Dispatchers.IO)
}

internal fun cloudTtsErrorMessage(body: String, code: Int): String {
    val parsed = runCatching {
        val obj = JSONObject(body)
        obj.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
            ?: obj.optString("message").takeIf { it.isNotBlank() }
    }.getOrNull()
    if (isTtsQuotaOrBilling(code, parsed)) {
        return "TTS 额度不足，或当前套餐不含语音合成。请检查该密钥的计费与套餐。"
    }
    return parsed ?: "TTS HTTP $code"
}

internal fun isTtsQuotaOrBilling(code: Int, message: String?): Boolean {
    if (code == 402 || code == 429) return true
    val text = message.orEmpty()
    return text.contains("quota", ignoreCase = true) ||
        text.contains("billing", ignoreCase = true) ||
        text.contains("insufficient", ignoreCase = true) ||
        text.contains("Payment Required", ignoreCase = true)
}

internal fun shouldRetryTtsEndpoint(httpCode: Int, message: String?): Boolean =
    httpCode == 402 ||
        httpCode == 404 ||
        httpCode == 405 ||
        httpCode == 421 ||
        httpCode == 429 ||
        (httpCode == 400 && message.orEmpty().contains("quota", ignoreCase = true))
