package kira.ditto.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit

internal object CloudAsrEngine {
    private val httpClient: OkHttpClient = AetherHttp.derive {
        connectTimeout(20, TimeUnit.SECONDS)
        readTimeout(90, TimeUnit.SECONDS)
        writeTimeout(90, TimeUnit.SECONDS)
    }

    suspend fun transcribe(
        option: ProviderModelOption,
        pcm: ByteArray,
        language: String?,
    ): String = withContext(Dispatchers.IO) {
        if (pcm.isEmpty()) return@withContext ""
        if (isStepAudioSseModel(option.modelId) || isStepAudioStreamModel(option.modelId)) {
            transcribeSse(option, pcm, language)
        } else {
            transcribeOpenAi(option, AsrWav.pcm16leMonoToWav(pcm), language)
        }
    }

    private fun transcribeSse(
        option: ProviderModelOption,
        pcm: ByteArray,
        language: String?,
    ): String {
        val base = option.baseUrl.trim()
        val apiKey = option.apiKey.trim()
        if (base.isBlank() || apiKey.isBlank()) {
            throw CloudAsrException("缺少阶跃 ASR 的地址或密钥")
        }
        val payload = JSONObject()
            .put(
                "audio",
                JSONObject()
                    .put("data", Base64.getEncoder().encodeToString(pcm))
                    .put(
                        "input",
                        JSONObject()
                            .put(
                                "transcription",
                                JSONObject()
                                    .put("model", stepAudioSseModelId(option.modelId))
                                    .put("language", language?.takeIf { it.isNotBlank() } ?: "zh")
                                    .put("enable_itn", true),
                            )
                            .put(
                                "format",
                                JSONObject()
                                    .put("type", "pcm")
                                    .put("codec", "pcm_s16le")
                                    .put("rate", AsrChunkAssembler.SampleRateHz)
                                    .put("bits", 16)
                                    .put("channel", 1),
                            ),
                    ),
            )
        val payloadJson = payload.toString()
        var lastError: CloudAsrException? = null
        for (url in stepAudioSseCandidateUrls(base)) {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "text/event-stream")
                .applyAetherLlmHeaders(option.userAgent, option.customHeaders)
                .post(payloadJson.toRequestBody("application/json".toMediaType()))
                .build()
            val transcript = try {
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body ?: throw CloudAsrException("ASR 空响应")
                    if (!response.isSuccessful) {
                        throw CloudAsrException(
                            cloudAsrErrorMessage(body.string(), response.code),
                            response.code,
                        )
                    }
                    readSseTranscript(body)
                }
            } catch (error: CloudAsrException) {
                lastError = error
                if (!shouldRetryAsrEndpoint(error.httpCode)) throw error
                null
            }
            if (transcript != null) return transcript
        }
        throw lastError ?: CloudAsrException("阶跃 ASR 请求失败")
    }

    private fun transcribeOpenAi(
        option: ProviderModelOption,
        wavBytes: ByteArray,
        language: String?,
    ): String {
        val base = option.baseUrl.trim().trimEnd('/')
        if (base.isBlank() || option.apiKey.isBlank()) {
            throw CloudAsrException("缺少云端 ASR 的地址或密钥")
        }
        val url = if (base.endsWith("/audio/transcriptions")) {
            base
        } else if (base.endsWith("/v1")) {
            "$base/audio/transcriptions"
        } else {
            "$base/v1/audio/transcriptions"
        }
        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "chunk.wav",
                wavBytes.toRequestBody("audio/wav".toMediaType()),
            )
            .addFormDataPart("model", option.modelId)
        if (!language.isNullOrBlank()) {
            bodyBuilder.addFormDataPart("language", language)
        }
        bodyBuilder.addFormDataPart("prompt", openAiAsrCorrectionPrompt(language))
        bodyBuilder.addFormDataPart("temperature", "0")
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${option.apiKey.trim()}")
            .applyAetherLlmHeaders(option.userAgent, option.customHeaders)
            .post(bodyBuilder.build())
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw CloudAsrException(cloudAsrErrorMessage(body, response.code), response.code)
            }
            val parsed = parseCloudAsrTranscript(body)
            android.util.Log.i(
                "AetherAsr",
                "openai asr bytes=${wavBytes.size} text=${parsed.length} body=${body.take(80)}",
            )
            if (parsed.isBlank()) {
                throw CloudAsrException(
                    "ASR empty transcript: ${body.take(180)}",
                    response.code,
                )
            }
            return parsed
        }
    }
}

internal fun openAiAsrCorrectionPrompt(language: String?): String {
    val lang = language?.trim().orEmpty()
    return if (lang.isBlank() || lang.startsWith("zh", ignoreCase = true)) {
        "请转写成干净的中文指令：纠正错别字，去掉口头禅和同一句话的重复，不要编造没听到的内容。"
    } else {
        "Transcribe accurately. Fix misspellings, drop filler and repeated phrases, and do not invent words."
    }
}

internal fun cloudAsrErrorMessage(body: String, code: Int): String {
    val parsed = runCatching {
        val obj = JSONObject(body)
        obj.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
            ?: obj.optString("message").takeIf { it.isNotBlank() }
            ?: obj.optString("errmsg").takeIf { it.isNotBlank() }
    }.getOrNull()
    if (code == 402) {
        return parsed ?: "阶跃语音识别暂时连不上套餐接口"
    }
    return parsed ?: "ASR HTTP $code"
}

internal fun parseCloudAsrTranscript(body: String): String {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) return ""
    val obj = runCatching { JSONObject(trimmed) }.getOrNull()
    if (obj == null) {
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return ""
        return trimmed
    }
    obj.optString("text").trim().takeIf { it.isNotEmpty() }?.let { return it }
    obj.optJSONObject("result")?.optString("text")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    obj.optJSONArray("results")?.optJSONObject(0)?.optString("text")?.trim()
        ?.takeIf { it.isNotEmpty() }?.let { return it }
    obj.optJSONObject("data")?.optString("text")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    return ""
}

private fun readSseTranscript(body: ResponseBody): String {
    var assembled = ""
    var done = ""
    body.source().use { source ->
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            val data = sseData(line) ?: continue
            val event = parseStepAudioSseEvent(data)
            val errorMessage = event.errorMessage
            if (!errorMessage.isNullOrBlank()) {
                throw CloudAsrException(errorMessage)
            }
            event.replacementText?.let { assembled += it }
            event.completedTranscript?.let { done = it }
        }
    }
    return done.ifBlank { assembled }.trim()
}

private fun sseData(line: String): String? {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || trimmed.startsWith(":")) return null
    if (trimmed.startsWith("data:", ignoreCase = true)) {
        val data = trimmed.substring(5).trim()
        if (data.isEmpty() || data == "[DONE]") return null
        return data
    }
    if (trimmed.startsWith("{")) return trimmed
    return null
}
