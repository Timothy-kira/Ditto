package kira.ditto.data

import org.json.JSONArray
import org.json.JSONObject

internal fun extractListenTranscript(json: String): String {
    if (json.isBlank()) return ""
    runCatching { JSONObject(json) }.getOrNull()?.let { obj ->
        val direct = transcriptFromObject(obj)
        if (direct.isNotBlank()) return direct
    }
    val array = runCatching { JSONArray(json) }.getOrNull() ?: return ""
    var best = ""
    for (index in 0 until array.length()) {
        val obj = array.optJSONObject(index) ?: continue
        val text = transcriptFromObject(obj)
        if (text.length >= best.length) best = text
    }
    return best
}

internal fun resolveAgentModeListenBodyText(
    transcript: String,
    listening: Boolean,
    visualOnly: Boolean,
    waitingText: String,
    visualOnlyText: String,
): String = when {
    transcript.isNotBlank() -> transcript
    visualOnly && !listening -> visualOnlyText
    else -> waitingText
}

private fun transcriptFromObject(obj: JSONObject): String {
    val direct = obj.optString("transcript").trim()
    if (direct.isNotBlank()) return direct
    val nested = obj.optJSONObject("structuredContent") ?: return ""
    return nested.optString("transcript").trim()
}
