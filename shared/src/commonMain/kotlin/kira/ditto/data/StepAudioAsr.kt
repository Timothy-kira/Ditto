package kira.ditto.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

private val StepAudioJson = Json { ignoreUnknownKeys = true; isLenient = true }

fun isStepAudioStreamModel(modelId: String): Boolean {
    val n = modelId.trim().lowercase()
    if (n.isEmpty()) return false
    return n.contains("asr-stream") || (n.contains("asr") && n.endsWith("-stream"))
}

fun isStepAudioSseModel(modelId: String): Boolean {
    val n = modelId.trim().lowercase()
    if (n.isEmpty() || isStepAudioStreamModel(modelId)) return false
    return (n.contains("stepaudio") && n.contains("asr")) || n.contains("step-asr")
}

fun stepAudioSseModelId(modelId: String): String {
    val trimmed = modelId.trim()
    return if (trimmed.endsWith("-stream", ignoreCase = true)) {
        trimmed.dropLast("-stream".length)
    } else {
        trimmed
    }
}

fun stepAudioApiRoot(baseUrl: String): String {
    var url = baseUrl.trim()
    val query = url.indexOf('?')
    if (query >= 0) url = url.substring(0, query)
    url = url.trimEnd('/')
    val suffixes = listOf(
        "/v1/realtime/asr/stream",
        "/v1/audio/transcriptions",
        "/v1/audio/asr/sse",
        "/audio/transcriptions",
        "/audio/asr/sse",
        "/realtime/asr/stream",
        "/v1",
    )
    for (suffix in suffixes) {
        if (url.endsWith(suffix, ignoreCase = true)) {
            url = url.dropLast(suffix.length).trimEnd('/')
            break
        }
    }
    return url
}

fun stepAudioStreamUrl(baseUrl: String): String =
    toWebSocketOrigin(stepAudioApiRoot(baseUrl)).trimEnd('/') + "/v1/realtime/asr/stream"

fun stepAudioSseUrl(baseUrl: String): String =
    toHttpOrigin(stepAudioApiRoot(baseUrl)).trimEnd('/') + "/v1/audio/asr/sse"

const val StepAudioTtsModelId = "stepaudio-2.5-tts"
const val StepAudioTtsDefaultVoice = "zixinnansheng"
const val StepAudioTtsMaxInputChars = 1000

fun resolveStepAudioTtsModelId(modelId: String): String {
    val n = modelId.trim().lowercase()
    if (n.contains("stepaudio") && n.contains("tts") && !n.contains("realtime")) return modelId.trim()
    if (n.contains("step-tts")) return modelId.trim()
    return StepAudioTtsModelId
}

fun stepAudioSpeechUrl(baseUrl: String): String =
    toHttpOrigin(stepAudioApiRoot(baseUrl)).trimEnd('/') + "/v1/audio/speech"

fun stepAudioPlanSpeechUrl(baseUrl: String): String? =
    stepAudioPlanApiRoot(baseUrl)?.let { toHttpOrigin(it).trimEnd('/') + "/v1/audio/speech" }

fun stepAudioSpeechCandidateUrls(baseUrl: String): List<String> {
    val derived = stepAudioSpeechUrl(baseUrl)
    val publicRoot = stepAudioPublicApiRoot(baseUrl)
    val publicSpeech = if (publicRoot != stepAudioApiRoot(baseUrl).trimEnd('/')) {
        stepAudioSpeechUrl(publicRoot)
    } else {
        null
    }
    val plan = stepAudioPlanSpeechUrl(baseUrl)
    return buildList {
        if (looksLikeStepFunHost(baseUrl) && plan != null) add(plan)
        if (publicSpeech != null) add(publicSpeech)
        add(derived)
        if (plan != null) add(plan)
    }.distinct()
}

fun looksLikeStepFunHost(baseUrl: String): Boolean {
    val host = stepAudioApiRoot(baseUrl).lowercase()
    return host.contains("stepfun.com") || host.contains("stepfun.ai")
}

fun stepAudioPublicApiRoot(baseUrl: String): String =
    stepAudioApiRoot(baseUrl).trimEnd('/').replace(Regex("(?i)/step_plan"), "")

fun stepAudioStreamCandidateUrls(baseUrl: String): List<String> {
    val derived = stepAudioStreamUrl(baseUrl)
    val publicRoot = stepAudioPublicApiRoot(baseUrl)
    val publicStream = if (publicRoot != stepAudioApiRoot(baseUrl).trimEnd('/')) {
        stepAudioStreamUrl(publicRoot)
    } else {
        null
    }
    val plan = stepAudioPlanStreamUrl(baseUrl)
    return buildList {
        if (publicStream != null) add(publicStream)
        add(derived)
        if (plan != null) add(plan)
    }.distinct()
}

fun stepAudioSseCandidateUrls(baseUrl: String): List<String> {
    val derived = stepAudioSseUrl(baseUrl)
    val plan = stepAudioPlanSseUrl(baseUrl)
    return buildList {
        if (looksLikeStepFunHost(baseUrl) && plan != null) add(plan)
        add(derived)
        if (plan != null) add(plan)
    }.distinct()
}

fun stepAudioPlanApiRoot(baseUrl: String): String? {
    val root = stepAudioApiRoot(baseUrl)
    if (root.contains("/step_plan", ignoreCase = true)) return null
    return root.trimEnd('/') + "/step_plan"
}

fun stepAudioPlanStreamUrl(baseUrl: String): String? =
    stepAudioPlanApiRoot(baseUrl)?.let { toWebSocketOrigin(it).trimEnd('/') + "/v1/realtime/asr/stream" }

fun stepAudioPlanSseUrl(baseUrl: String): String? =
    stepAudioPlanApiRoot(baseUrl)?.let { toHttpOrigin(it).trimEnd('/') + "/v1/audio/asr/sse" }

fun asrHttpStatusFromMessage(message: String): Int? =
    Regex("""was '(\d{3})\b""").find(message)?.groupValues?.get(1)?.toIntOrNull()
        ?: Regex("""HTTP (\d{3})\b""").find(message)?.groupValues?.get(1)?.toIntOrNull()

fun isAsrBillingFailure(httpCode: Int?, message: String?): Boolean {
    if (httpCode == 402) return true
    val text = message.orEmpty()
    return text.contains("402") ||
        text.contains("Payment Required", ignoreCase = true)
}

fun shouldRetryAsrEndpoint(httpCode: Int?): Boolean =
    httpCode == 402 || httpCode == 404 || httpCode == 405 || httpCode == 421

fun websocketAsrErrorMessage(rawMessage: String?, httpCode: Int?): String {
    val status = httpCode ?: asrHttpStatusFromMessage(rawMessage.orEmpty())
    if (isAsrBillingFailure(status, rawMessage)) {
        return "阶跃语音识别暂时连不上套餐接口"
    }
    return when (status) {
        401 -> "阶跃语音识别鉴权失败"
        403 -> "阶跃语音识别没有访问权限"
        404 -> "找不到阶跃语音识别接口"
        else -> {
            val raw = rawMessage?.trim().orEmpty()
            if (raw.contains("Expected HTTP 101", ignoreCase = true)) {
                "连接阶跃语音识别失败"
            } else {
                raw.ifBlank { "连接阶跃 ASR 失败" }
            }
        }
    }
}

fun asrSpeechLanguageTag(language: String?): String {
    val n = language?.trim().orEmpty()
    if (n.isEmpty() || n.equals("zh", ignoreCase = true) || n.startsWith("zh-", ignoreCase = true)) {
        return "zh-CN"
    }
    if (n.equals("en", ignoreCase = true) || n.startsWith("en-", ignoreCase = true)) {
        return "en-US"
    }
    return n
}

private fun toWebSocketOrigin(root: String): String {
    val trimmed = root.trim().trimEnd('/')
    return when {
        trimmed.startsWith("https://", ignoreCase = true) -> "wss://" + trimmed.substring(8)
        trimmed.startsWith("http://", ignoreCase = true) -> "ws://" + trimmed.substring(7)
        trimmed.startsWith("wss://", ignoreCase = true) ||
            trimmed.startsWith("ws://", ignoreCase = true) -> trimmed
        else -> "wss://$trimmed"
    }
}

private fun toHttpOrigin(root: String): String {
    val trimmed = root.trim().trimEnd('/')
    return when {
        trimmed.startsWith("wss://", ignoreCase = true) -> "https://" + trimmed.substring(6)
        trimmed.startsWith("ws://", ignoreCase = true) -> "http://" + trimmed.substring(5)
        trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
        else -> "https://$trimmed"
    }
}

data class StepAudioStreamParse(
    val type: String,
    val replacementText: String? = null,
    val completedTranscript: String? = null,
    val errorMessage: String? = null,
)

fun parseStepAudioStreamEvent(raw: String): StepAudioStreamParse {
    val obj = runCatching { StepAudioJson.parseToJsonElement(raw).jsonObject }.getOrNull()
        ?: return StepAudioStreamParse(type = "")
    val type = obj.string("type").orEmpty()
    val error = readErrorMessage(obj)
    if (type == "error" || (error != null && type.isBlank())) {
        return StepAudioStreamParse(type = type.ifBlank { "error" }, errorMessage = error)
    }
    return when {
        type.endsWith("input_audio_transcription.delta") -> StepAudioStreamParse(
            type = type,
            replacementText = obj.string("text"),
            errorMessage = error,
        )
        type.endsWith("input_audio_transcription.completed") -> StepAudioStreamParse(
            type = type,
            completedTranscript = obj.string("transcript") ?: obj.string("text"),
            errorMessage = error,
        )
        type == "error" -> StepAudioStreamParse(type = type, errorMessage = error)
        else -> StepAudioStreamParse(type = type, errorMessage = error)
    }
}

fun parseStepAudioSseEvent(raw: String): StepAudioStreamParse {
    val obj = runCatching { StepAudioJson.parseToJsonElement(raw).jsonObject }.getOrNull()
        ?: return StepAudioStreamParse(type = "")
    val type = obj.string("type").orEmpty()
    val error = readErrorMessage(obj)
    return when (type) {
        "transcript.text.delta" -> StepAudioStreamParse(
            type = type,
            replacementText = obj.string("delta"),
            errorMessage = error,
        )
        "transcript.text.done" -> StepAudioStreamParse(
            type = type,
            completedTranscript = obj.string("text"),
            errorMessage = error,
        )
        "error" -> StepAudioStreamParse(type = type, errorMessage = error ?: "ASR SSE error")
        else -> StepAudioStreamParse(type = type, errorMessage = error)
    }
}

fun applyStepAudioText(current: String, event: StepAudioStreamParse): String {
    event.completedTranscript?.let { return it }
    event.replacementText?.let { return it }
    return current
}

class StepAudioTranscriptAccumulator {
    private val committed = mutableListOf<String>()
    private var current = ""

    @Synchronized
    fun apply(event: StepAudioStreamParse): String {
        event.replacementText?.let { current = it }
        event.completedTranscript?.let { transcript ->
            val value = transcript.ifBlank { current }
            if (value.isNotBlank()) committed += value
            current = ""
        }
        return display()
    }

    @Synchronized
    fun display(): String = AsrChunkAssembler.joinTranscripts(committed + current)

    @Synchronized
    fun finish(): String {
        if (current.isNotBlank()) {
            committed += current
            current = ""
        }
        return display()
    }
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun readErrorMessage(obj: JsonObject): String? {
    val error = obj["error"]
    val nested = when (error) {
        is JsonObject -> error.string("message")
        is JsonPrimitive -> error.contentOrNull?.takeIf { it.isNotBlank() }
        else -> null
    }
    return nested ?: obj.string("message")
}
