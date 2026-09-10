package kira.ditto.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal object JsonRest {
    fun get(url: String, token: String, query: JSONObject = JSONObject(), headers: Map<String, String> = emptyMap()): JSONObject =
        request("GET", url, token, query, body = null, headers = headers)

    fun request(
        verb: String,
        url: String,
        token: String,
        query: JSONObject = JSONObject(),
        body: JSONObject? = null,
        headers: Map<String, String> = emptyMap(),
        accept: String = "application/json",
    ): JSONObject {
        val full = buildUrl(url, query)
        val connection = (URL(full).openConnection() as HttpURLConnection).apply {
            requestMethod = verb
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "Aether-MCP")
            if (token.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer $token")
            }
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
            if (body != null && verb != "GET") {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                outputStream.use { stream ->
                    stream.write(body.toString().toByteArray(StandardCharsets.UTF_8))
                }
            }
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream ?: connection.inputStream
        }
        val raw = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val message = runCatching { JSONObject(raw).optString("message") }.getOrNull()
                ?.ifBlank { null }
                ?: runCatching { JSONObject(raw).optString("error") }.getOrNull()
                ?: raw.ifBlank { "HTTP $code" }
            error(message)
        }
        if (raw.isBlank()) return JSONObject()
        runCatching { JSONObject(raw) }.getOrNull()?.let { return it }
        runCatching { JSONArray(raw) }.getOrNull()?.let { return JSONObject().put("items", it) }
        return JSONObject().put("raw", raw)
    }

    fun paramsObject(arguments: JSONObject): JSONObject {
        val params = arguments.optJSONObject("params")
        if (params != null) return params
        val copy = JSONObject(arguments.toString())
        copy.remove("method")
        return copy
    }

    fun need(params: JSONObject, key: String): String {
        val value = params.optString(key).trim()
            .ifBlank { params.optString(key.replace('_', '-')).trim() }
        require(value.isNotBlank()) { "$key is required" }
        return value
    }

    fun opt(params: JSONObject, vararg keys: String): String {
        keys.forEach { key ->
            val value = params.optString(key).trim()
            if (value.isNotBlank()) return value
        }
        return ""
    }

    fun without(params: JSONObject, vararg keys: String): JSONObject {
        val copy = JSONObject(params.toString())
        keys.forEach { copy.remove(it) }
        return copy
    }

    fun errorJson(code: String, message: String): String = JSONObject()
        .put("ok", false)
        .put("code", code)
        .put("reason", message)
        .toString()

    fun missingToken(service: String, hint: String): String = JSONObject()
        .put("ok", false)
        .put("code", "input_required")
        .put("reason", "$service token missing. $hint")
        .toString()

    private fun buildUrl(base: String, query: JSONObject): String {
        if (query.length() == 0) return base
        val parts = mutableListOf<String>()
        val keys = query.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = query.opt(key) ?: continue
            if (value == JSONObject.NULL) continue
            val text = when (value) {
                is JSONArray -> (0 until value.length()).joinToString(",") { value.optString(it) }
                else -> value.toString()
            }
            if (text.isBlank()) continue
            parts += "${enc(key)}=${enc(text)}"
        }
        if (parts.isEmpty()) return base
        val joiner = if ('?' in base) "&" else "?"
        return base + joiner + parts.joinToString("&")
    }

    private fun enc(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
}
