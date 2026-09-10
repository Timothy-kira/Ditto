package kira.ditto.data

const val ThinkingEffortProbeSentinel = "__unsupported_effort__"

val KnownThinkingEffortValues = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")

enum class ThinkingProbeStyle {
    OpenAiCompletions,
    AnthropicMessages,
}

data class ThinkingProbeResult(
    val levels: List<String> = emptyList(),
    val levelMap: Map<String, String> = emptyMap(),
    val reasoningSupported: Boolean = false,
    val resolved: Boolean = false,
    val parameterUnsupported: Boolean = false,
    val ignored: Boolean = false,
)

private val QuotedEffortRegex = Regex(
    """['"`“”](none|off|minimal|low|medium|high|xhigh|max)['"`“”]""",
    RegexOption.IGNORE_CASE,
)

private val BareEffortRegex = Regex(
    """\b(none|off|minimal|low|medium|high|xhigh|max)\b""",
    RegexOption.IGNORE_CASE,
)

private val EnumContextRegex = Regex(
    """(one of|supported|allowed|valid|must be|expected|enum|choose from|values?\s*(are|:))""",
    RegexOption.IGNORE_CASE,
)

private val UnsupportedParameterMarkers = listOf(
    "unknown",
    "unexpected",
    "unrecognized",
    "extra field",
    "not support",
    "unsupported",
    "invalid parameter",
    "does not support",
    "not a valid parameter",
    "unrecognized request argument",
    "unknown argument",
    "unknown field",
    "unexpected keyword",
)

fun normalizeThinkingEffortToken(raw: String): String =
    when (val token = raw.trim().lowercase()) {
        "none" -> "off"
        else -> token
    }

fun parseAllowedThinkingLevels(errorBody: String): List<String> {
    if (errorBody.isBlank()) return emptyList()
    val quoted = QuotedEffortRegex.findAll(errorBody)
        .map { normalizeThinkingEffortToken(it.groupValues[1]) }
        .filter { it in KnownThinkingEffortValues }
        .toSet()
    val tokens = if (quoted.isNotEmpty()) {
        quoted
    } else if (EnumContextRegex.containsMatchIn(errorBody)) {
        BareEffortRegex.findAll(errorBody)
            .map { normalizeThinkingEffortToken(it.groupValues[1]) }
            .filter { it in KnownThinkingEffortValues }
            .toSet()
    } else {
        emptySet()
    }
    return KnownThinkingEffortValues.filter { it in tokens }
}

fun thinkingProbeParameterUnsupported(status: Int, body: String): Boolean {
    if (status !in 400..499) return false
    if (parseAllowedThinkingLevels(body).isNotEmpty()) return false
    val text = body.lowercase()
    return UnsupportedParameterMarkers.any { marker -> marker in text }
}

fun interpretThinkingProbeResponse(status: Int, body: String): ThinkingProbeResult {
    val levels = parseAllowedThinkingLevels(body)
    if (levels.isNotEmpty()) {
        return ThinkingProbeResult(
            levels = levels,
            levelMap = if ("off" in levels) mapOf("off" to "none") else emptyMap(),
            reasoningSupported = true,
            resolved = true,
        )
    }
    if (status in 200..299) {
        return ThinkingProbeResult(ignored = true)
    }
    if (status == 401 || status == 403) {
        return ThinkingProbeResult()
    }
    if (thinkingProbeParameterUnsupported(status, body)) {
        return ThinkingProbeResult(parameterUnsupported = true)
    }
    return ThinkingProbeResult()
}

fun reduceThinkingProbeResults(results: List<ThinkingProbeResult>): ThinkingProbeResult {
    results.firstOrNull { it.levels.isNotEmpty() }?.let { return it }
    if (results.any { !it.resolved && !it.parameterUnsupported && !it.ignored }) {
        return ThinkingProbeResult()
    }
    if (results.any { it.parameterUnsupported || it.ignored }) {
        return ThinkingProbeResult(resolved = true)
    }
    return ThinkingProbeResult()
}

fun thinkingProbeStyles(piProviderId: String, modelId: String = ""): List<ThinkingProbeStyle> {
    val api = PiProviderCatalog.messageApiFor(piProviderId, modelId)
    return when {
        api == "anthropic-messages" ||
            piProviderId == "anthropic" ||
            piProviderId.startsWith("minimax") -> listOf(
            ThinkingProbeStyle.AnthropicMessages,
            ThinkingProbeStyle.OpenAiCompletions,
        )
        else -> listOf(
            ThinkingProbeStyle.OpenAiCompletions,
            ThinkingProbeStyle.AnthropicMessages,
        )
    }
}

fun thinkingProbeUrl(
    baseUrl: String,
    piProviderId: String,
    modelId: String,
    style: ThinkingProbeStyle,
): String {
    val normalized = normalizeThinkingProbeBaseUrl(piProviderId, modelId, baseUrl)
    return when (style) {
        ThinkingProbeStyle.OpenAiCompletions -> openAiCompletionsProbeUrl(normalized, piProviderId)
        ThinkingProbeStyle.AnthropicMessages -> anthropicMessagesProbeUrl(normalized, piProviderId, modelId)
    }
}

fun thinkingProbeRequestBody(modelId: String, style: ThinkingProbeStyle): String {
    val model = jsonEscape(modelId.trim().ifBlank { "unknown" })
    return when (style) {
        ThinkingProbeStyle.OpenAiCompletions ->
            """{"model":"$model","max_tokens":1,"messages":[{"role":"user","content":"ping"}],"reasoning_effort":"$ThinkingEffortProbeSentinel"}"""
        ThinkingProbeStyle.AnthropicMessages ->
            """{"model":"$model","max_tokens":1,"messages":[{"role":"user","content":"ping"}],"output_config":{"effort":"$ThinkingEffortProbeSentinel"}}"""
    }
}

internal fun normalizeThinkingProbeBaseUrl(
    providerId: String,
    modelId: String,
    baseUrl: String,
): String {
    val normalized = baseUrl.trim().trimEnd('/')
    if (!providerId.equals("stepfun", ignoreCase = true)) return normalized
    if (!normalized.startsWith("https://api.stepfun.com", ignoreCase = true) &&
        !normalized.startsWith("https://api.stepfun.ai", ignoreCase = true)
    ) {
        return normalized
    }
    val usesStepPlan = normalized.contains("/step_plan", ignoreCase = true) ||
        stepfunUsesStepPlan(modelId)
    return if (usesStepPlan) {
        if (normalized.startsWith("https://api.stepfun.ai", ignoreCase = true)) {
            "https://api.stepfun.ai/step_plan"
        } else {
            "https://api.stepfun.com/step_plan"
        }
    } else if (normalized.startsWith("https://api.stepfun.ai", ignoreCase = true)) {
        "https://api.stepfun.ai"
    } else {
        "https://api.stepfun.com"
    }
}

private fun openAiCompletionsProbeUrl(normalized: String, piProviderId: String): String {
    if (normalized.endsWith("/chat/completions")) return normalized
    if (normalized.endsWith("/responses")) {
        return normalized.removeSuffix("/responses") + "/chat/completions"
    }
    if (piProviderId.equals("stepfun", ignoreCase = true)) {
        return if (normalized.contains("/step_plan", ignoreCase = true)) {
            "$normalized/v1/chat/completions"
        } else {
            "$normalized/v1/chat/completions"
        }
    }
    return "$normalized/chat/completions"
}

private fun jsonEscape(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

private fun anthropicMessagesProbeUrl(normalized: String, piProviderId: String, modelId: String): String {
    if (normalized.endsWith("/messages")) return normalized
    if (piProviderId.equals("stepfun", ignoreCase = true)) {
        val root = if (normalized.contains("/step_plan", ignoreCase = true) || stepfunUsesStepPlan(modelId)) {
            if (normalized.contains("/step_plan", ignoreCase = true)) {
                normalized
            } else if (normalized.startsWith("https://api.stepfun.ai", ignoreCase = true)) {
                "https://api.stepfun.ai/step_plan"
            } else {
                "https://api.stepfun.com/step_plan"
            }
        } else {
            normalized
        }
        return "$root/v1/messages"
    }
    if (normalized.endsWith("/v1")) return "$normalized/messages"
    if (normalized.endsWith("/anthropic")) return "$normalized/v1/messages"
    return "$normalized/v1/messages"
}
