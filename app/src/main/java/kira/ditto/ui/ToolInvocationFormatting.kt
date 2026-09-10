package kira.ditto.ui

import org.json.JSONArray
import org.json.JSONObject

internal fun toolInvocationResultText(
    output: JSONObject?,
    rawOutput: String,
    noOutputLabel: String,
): String {
    if (output == null) {
        return rawOutput.trim().ifBlank { noOutputLabel }
    }
    mcpToolResultText(output)?.let { return it }
    listOf("text", "result", "message", "output", "value").forEach { key ->
        output.optString(key).trim().takeIf { it.isNotBlank() }?.let { return it }
    }
    output.optJSONObject("structuredContent")?.let { structured ->
        prettyJson(structured).takeIf { it.isNotBlank() }?.let { return it }
    }
    return rawOutput.trim().ifBlank { prettyJson(output).ifBlank { noOutputLabel } }
}

internal fun mcpToolResultText(output: JSONObject): String? {
    val texts = extractMcpContentTexts(output.opt("content"))
        .ifEmpty { extractMcpContentTexts(output.optJSONObject("structuredContent")?.opt("content")) }
    return texts.joinToString("\n").trim().takeIf { it.isNotBlank() }
}

private fun extractMcpContentTexts(content: Any?): List<String> {
    val array = when (content) {
        is JSONArray -> content
        is JSONObject -> JSONArray().put(content)
        is String -> return listOf(content.trim()).filter { it.isNotBlank() }
        else -> return emptyList()
    }
    return buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index)
            if (item == null) {
                array.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
                continue
            }
            val nested = item.optJSONObject("content")
            val text = when (item.optString("type")) {
                "text", "" -> item.optString("text").trim()
                "content" -> nested?.optString("text").orEmpty().trim()
                    .ifBlank { item.optString("text").trim() }
                else -> item.optString("text").trim()
            }
            if (text.isNotBlank()) add(text)
        }
    }
}

private fun prettyJson(value: JSONObject): String =
    runCatching { value.toString(2) }.getOrDefault(value.toString()).trim()
