package kira.ditto.data

import org.json.JSONObject

data class LlmTokenUsage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val totalTokens: Long? = null,
    val reasoningTokens: Long? = null,
    val cachedInputTokens: Long? = null,
    val requestCount: Int = 1,
) {
    operator fun plus(other: LlmTokenUsage): LlmTokenUsage = LlmTokenUsage(
        inputTokens = sumNullable(inputTokens, other.inputTokens),
        outputTokens = sumNullable(outputTokens, other.outputTokens),
        totalTokens = sumNullable(totalTokens, other.totalTokens),
        reasoningTokens = sumNullable(reasoningTokens, other.reasoningTokens),
        cachedInputTokens = sumNullable(cachedInputTokens, other.cachedInputTokens),
        requestCount = requestCount + other.requestCount,
    )

    fun withMissingTotalResolved(): LlmTokenUsage {
        val resolvedTotal = when {
            totalTokens != null && totalTokens > 0L -> totalTokens
            else -> sumNullable(inputTokens, outputTokens)
        }
        return if (resolvedTotal == totalTokens) this else copy(totalTokens = resolvedTotal)
    }

    companion object {
        fun sumNullable(first: Long?, second: Long?): Long? = when {
            first == null -> second
            second == null -> first
            else -> first + second
        }
    }
}

fun estimateTokensFromChars(charCount: Int): Long =
    ((charCount.coerceAtLeast(0) + 3) / 4).toLong()

sealed interface LlmContentPart

data class LlmTextPart(
    val text: String,
) : LlmContentPart

data class LlmImagePart(
    val mimeType: String,
    val base64Data: String,
) : LlmContentPart

data class LlmResourceLinkPart(
    val uri: String,
    val name: String = "",
) : LlmContentPart

data class LlmMessage(
    val role: String,
    val contentParts: List<LlmContentPart>,
    val providerPayload: JSONObject? = null,
    val toolCallId: String = "",
    val toolName: String = "",
    val isError: Boolean = false,
)
