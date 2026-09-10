package kira.ditto.ui

import org.json.JSONArray
import org.json.JSONObject

internal fun looksLikeUpaChatOutput(raw: String): Boolean =
    parseUpaToolOutput(raw)?.optJSONObject("_upa") != null

internal fun parseUpaToolOutput(raw: String): JSONObject? {
    val json = parseLooseJsonObject(raw) ?: return null
    json.optJSONObject("_upa")?.let { return json }
    json.optJSONObject("structuredContent")?.optJSONObject("_upa")?.let {
        return json.optJSONObject("structuredContent")
    }
    val content = json.optJSONArray("content") ?: return null
    for (index in 0 until content.length()) {
        val text = content.optJSONObject(index)?.optString("text").orEmpty()
        if (!text.contains("_upa")) continue
        val nested = parseLooseJsonObject(text) ?: continue
        if (nested.optJSONObject("_upa") != null) return nested
    }
    return null
}

internal fun jsonStringOrArray(json: JSONObject, key: String): String {
    val value = json.opt(key) ?: return ""
    return when (value) {
        JSONObject.NULL -> ""
        is JSONArray -> value.toString()
        is JSONObject -> value.toString()
        else -> value.toString().takeIf { it.isNotBlank() && it != "null" }.orEmpty()
    }
}

internal fun upaFieldsMap(fieldsObject: JSONObject?): Map<String, String> {
    stringifyUpaArrayFields(fieldsObject)
    if (fieldsObject == null || fieldsObject.length() == 0) return emptyMap()
    return fieldsObject.keys().asSequence()
        .associateWith { key -> jsonStringOrArray(fieldsObject, key) }
        .filterValues { it.isNotBlank() }
}

private fun stringifyUpaArrayFields(fields: JSONObject?) {
    if (fields == null) return
    fields.keys().asSequence().toList().forEach { key ->
        val value = fields.opt(key)
        if (value is JSONArray) fields.put(key, value.toString())
    }
}

internal fun parseLooseJsonObject(raw: String): JSONObject? {
    val text = extractFirstCompleteJsonObject(raw) ?: return null
    return runCatching { JSONObject(text) }.getOrNull()
}

internal fun extractFirstCompleteJsonObject(raw: String): String? {
    val start = raw.indexOf('{')
    if (start < 0) return null
    return extractBalancedJson(raw, start)
}

internal fun extractBalancedJson(raw: String, start: Int): String? {
    val opener = raw.getOrNull(start) ?: return null
    if (opener != '{' && opener != '[') return null
    val stack = ArrayDeque<Char>()
    var inString = false
    var escape = false
    for (index in start until raw.length) {
        val char = raw[index]
        if (inString) {
            when {
                escape -> escape = false
                char == '\\' -> escape = true
                char == '"' -> inString = false
            }
            continue
        }
        when (char) {
            '"' -> inString = true
            '{', '[' -> stack.addLast(char)
            '}' -> {
                if (stack.lastOrNull() != '{') return null
                stack.removeLast()
                if (stack.isEmpty()) return raw.substring(start, index + 1)
            }
            ']' -> {
                if (stack.lastOrNull() != '[') return null
                stack.removeLast()
                if (stack.isEmpty()) return raw.substring(start, index + 1)
            }
        }
    }
    return null
}

internal fun extractJsonArrayForKey(raw: String, key: String): JSONArray? {
    val start = indexOfJsonValue(raw, key) ?: return null
    return when (raw.getOrNull(start)) {
        '[' -> extractBalancedJson(raw, start)?.let { runCatching { JSONArray(it) }.getOrNull() }
            ?: extractJsonArrayPrefix(raw, start)
        '"' -> {
            val literal = extractJsonStringLiteral(raw, start, allowUnterminated = true) ?: return null
            val trimmed = literal.trim()
            if (!trimmed.startsWith("[")) return null
            runCatching { JSONArray(trimmed) }.getOrNull()
                ?: extractJsonArrayPrefix(trimmed, 0)
        }
        else -> null
    }
}

internal fun extractJsonArrayPrefix(raw: String, start: Int): JSONArray? {
    if (raw.getOrNull(start) != '[') return null
    val result = JSONArray()
    var index = start + 1
    while (index < raw.length) {
        while (index < raw.length && (raw[index].isWhitespace() || raw[index] == ',')) {
            index++
        }
        if (index >= raw.length || raw[index] == ']') break
        if (raw[index] != '{') break
        val obj = extractBalancedJson(raw, index) ?: break
        runCatching { JSONObject(obj) }.getOrNull()?.let(result::put)
        index += obj.length
    }
    return result.takeIf { it.length() > 0 }
}

internal fun extractJsonStringForKey(raw: String, key: String): String? {
    val start = indexOfJsonValue(raw, key) ?: return null
    return extractJsonStringLiteral(raw, start, allowUnterminated = false)
        ?.takeIf { it.isNotBlank() }
}

internal fun indexOfJsonValue(raw: String, key: String): Int? {
    val needle = "\"$key\""
    var from = 0
    while (from < raw.length) {
        val at = raw.indexOf(needle, from)
        if (at < 0) return null
        var index = at + needle.length
        while (index < raw.length && raw[index].isWhitespace()) index++
        if (index < raw.length && raw[index] == ':') {
            index++
            while (index < raw.length && raw[index].isWhitespace()) index++
            if (index < raw.length) return index
        }
        from = at + 1
    }
    return null
}

internal fun extractJsonStringLiteral(
    raw: String,
    start: Int,
    allowUnterminated: Boolean,
): String? {
    if (raw.getOrNull(start) != '"') return null
    val out = StringBuilder()
    var index = start + 1
    var escape = false
    while (index < raw.length) {
        val char = raw[index]
        if (escape) {
            when (char) {
                '"', '\\', '/' -> out.append(char)
                'b' -> out.append('\b')
                'f' -> out.append('\u000c')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'u' -> {
                    val hexEnd = (index + 5).coerceAtMost(raw.length)
                    val hex = raw.substring(index + 1, hexEnd)
                    if (hex.length == 4) {
                        out.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                        index += 4
                    } else if (!allowUnterminated) {
                        return null
                    }
                }
                else -> out.append(char)
            }
            escape = false
        } else when (char) {
            '\\' -> escape = true
            '"' -> return out.toString()
            else -> out.append(char)
        }
        index++
    }
    return if (allowUnterminated && out.isNotEmpty()) out.toString() else null
}
