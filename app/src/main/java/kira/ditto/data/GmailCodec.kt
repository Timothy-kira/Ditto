package kira.ditto.data

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal object GmailCodec {
    fun stringArg(arguments: JSONObject, vararg keys: String): String {
        keys.forEach { key ->
            val value = arguments.opt(key) ?: return@forEach
            when (value) {
                is String -> if (value.isNotBlank()) return value.trim()
                JSONObject.NULL -> Unit
                else -> {
                    val text = value.toString().trim()
                    if (text.isNotBlank() && text != "null") return text
                }
            }
        }
        return ""
    }

    fun stringList(arguments: JSONObject, vararg keys: String): List<String> {
        keys.forEach { key ->
            if (!arguments.has(key) || arguments.isNull(key)) return@forEach
            val value = arguments.opt(key) ?: return@forEach
            val items = when (value) {
                is JSONArray -> (0 until value.length()).map { value.optString(it) }
                is String -> value.split(',', ';', '\n')
                JSONObject.NULL -> emptyList()
                else -> listOf(value.toString())
            }.map { it.trim() }.filter { it.isNotBlank() }
            if (items.isNotEmpty()) return items.distinct()
        }
        return emptyList()
    }

    fun intArg(arguments: JSONObject, default: Int, max: Int, vararg keys: String): Int {
        keys.forEach { key ->
            if (!arguments.has(key) || arguments.isNull(key)) return@forEach
            val value = arguments.opt(key) ?: return@forEach
            val parsed = when (value) {
                is Number -> value.toInt()
                is String -> value.trim().toIntOrNull()
                else -> null
            } ?: return@forEach
            return parsed.coerceIn(1, max)
        }
        return default.coerceIn(1, max)
    }

    fun boolArg(arguments: JSONObject, default: Boolean, vararg keys: String): Boolean {
        keys.forEach { key ->
            if (!arguments.has(key) || arguments.isNull(key)) return@forEach
            val value = arguments.opt(key) ?: return@forEach
            return when (value) {
                is Boolean -> value
                is String -> value.equals("true", ignoreCase = true) || value == "1"
                is Number -> value.toInt() != 0
                else -> default
            }
        }
        return default
    }

    fun rfc2822Draft(
        to: List<String>,
        cc: List<String>,
        bcc: List<String>,
        subject: String,
        body: String,
        htmlBody: String,
        inReplyTo: String = "",
        references: String = "",
    ): String {
        val headers = buildString {
            appendLine("MIME-Version: 1.0")
            if (to.isNotEmpty()) appendLine("To: ${to.joinToString(", ")}")
            if (cc.isNotEmpty()) appendLine("Cc: ${cc.joinToString(", ")}")
            if (bcc.isNotEmpty()) appendLine("Bcc: ${bcc.joinToString(", ")}")
            if (subject.isNotBlank()) appendLine("Subject: ${encodeHeader(subject)}")
            if (inReplyTo.isNotBlank()) appendLine("In-Reply-To: $inReplyTo")
            if (references.isNotBlank()) appendLine("References: $references")
        }
        return if (htmlBody.isNotBlank() && body.isNotBlank()) {
            val boundary = "aether_gmail_${System.nanoTime()}"
            headers +
                "Content-Type: multipart/alternative; boundary=\"$boundary\"\r\n\r\n" +
                "--$boundary\r\n" +
                "Content-Type: text/plain; charset=UTF-8\r\n\r\n" +
                body + "\r\n" +
                "--$boundary\r\n" +
                "Content-Type: text/html; charset=UTF-8\r\n\r\n" +
                htmlBody + "\r\n" +
                "--$boundary--\r\n"
        } else if (htmlBody.isNotBlank()) {
            headers + "Content-Type: text/html; charset=UTF-8\r\n\r\n" + htmlBody
        } else {
            headers + "Content-Type: text/plain; charset=UTF-8\r\n\r\n" + body
        }.replace("\n", "\r\n").replace("\r\r\n", "\r\n")
    }

    fun rawUrlSafe(rfc2822: String): String =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(rfc2822.toByteArray(StandardCharsets.UTF_8))

    fun decodeBase64Url(data: String): String {
        if (data.isBlank()) return ""
        return runCatching {
            val padded = data.replace('-', '+').replace('_', '/')
            val withPad = padded + "=".repeat((4 - padded.length % 4) % 4)
            String(Base64.getDecoder().decode(withPad), StandardCharsets.UTF_8)
        }.getOrDefault("")
    }

    fun compactThread(
        thread: JSONObject,
        metadataOnly: Boolean,
        fullBody: Boolean = false,
    ): JSONObject {
        val messagesIn = thread.optJSONArray("messages") ?: JSONArray()
        val messages = JSONArray()
        for (index in 0 until messagesIn.length()) {
            val message = messagesIn.optJSONObject(index) ?: continue
            messages.put(compactMessage(message, fullBody = fullBody, metadataOnly = metadataOnly))
        }
        return JSONObject()
            .put("id", thread.optString("id"))
            .put("messages", messages)
    }

    fun compactMessage(
        message: JSONObject,
        fullBody: Boolean,
        metadataOnly: Boolean = false,
    ): JSONObject {
        val payload = message.optJSONObject("payload") ?: JSONObject()
        val headers = headerMap(payload.optJSONArray("headers") ?: JSONArray())
        val bodies = if (fullBody) decodeBodies(payload) else Pair("", "")
        val out = JSONObject()
            .put("id", message.optString("id"))
            .put("sender", headers["from"].orEmpty())
            .put("toRecipients", splitAddresses(headers["to"].orEmpty()))
            .put("ccRecipients", splitAddresses(headers["cc"].orEmpty()))
            .put("date", isoDate(headers["date"].orEmpty()))
            .put("labelIds", message.optJSONArray("labelIds") ?: JSONArray())
        if (!metadataOnly) {
            out.put("subject", headers["subject"].orEmpty())
            out.put(
                "snippet",
                message.optString("snippet").ifBlank { bodies.first.take(160) },
            )
        }
        if (fullBody) {
            out.put("plaintextBody", bodies.first)
            out.put("htmlBody", bodies.second)
            val attachments = attachmentMetadata(payload)
            out.put("attachments", attachments)
            out.put(
                "attachmentIds",
                JSONArray().apply {
                    for (index in 0 until attachments.length()) {
                        put(attachments.optJSONObject(index)?.optString("id").orEmpty())
                    }
                },
            )
        }
        return out
    }

    fun compactDraft(draft: JSONObject, metadataOnly: Boolean): JSONObject {
        val message = draft.optJSONObject("message") ?: JSONObject()
        val compact = compactMessage(message, fullBody = !metadataOnly, metadataOnly = metadataOnly)
        return JSONObject()
            .put("id", draft.optString("id"))
            .put("threadId", message.optString("threadId"))
            .put("subject", compact.optString("subject"))
            .put("toRecipients", compact.optJSONArray("toRecipients") ?: JSONArray())
            .put("ccRecipients", compact.optJSONArray("ccRecipients") ?: JSONArray())
            .put("date", compact.optString("date"))
            .put("plaintextBody", compact.optString("plaintextBody"))
            .put("htmlBody", compact.optString("htmlBody"))
    }

    fun headerMap(headers: JSONArray): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for (index in 0 until headers.length()) {
            val item = headers.optJSONObject(index) ?: continue
            val name = item.optString("name").trim().lowercase(Locale.US)
            val value = item.optString("value")
            if (name.isBlank() || value.isBlank()) continue
            out[name] = if (out.containsKey(name)) "${out.getValue(name)}, $value" else value
        }
        return out
    }

    fun decodeBodies(payload: JSONObject): Pair<String, String> {
        val plains = mutableListOf<String>()
        val htmls = mutableListOf<String>()
        fun walk(part: JSONObject) {
            val mime = part.optString("mimeType").lowercase(Locale.US).substringBefore(';').trim()
            val data = part.optJSONObject("body")?.optString("data").orEmpty()
            if (data.isNotBlank()) {
                val decoded = decodeBase64Url(data).trim()
                if (decoded.isNotBlank()) {
                    when (mime) {
                        "text/plain" -> plains += decoded
                        "text/html" -> htmls += decoded
                    }
                }
            }
            val nested = part.optJSONArray("parts") ?: return
            for (index in 0 until nested.length()) {
                walk(nested.optJSONObject(index) ?: continue)
            }
        }
        walk(payload)
        return Pair(
            plains.maxByOrNull { it.length }.orEmpty(),
            htmls.maxByOrNull { it.length }.orEmpty(),
        )
    }

    fun attachmentMetadata(payload: JSONObject): JSONArray {
        val out = JSONArray()
        fun walk(part: JSONObject) {
            val filename = part.optString("filename")
            val body = part.optJSONObject("body") ?: JSONObject()
            val attachmentId = body.optString("attachmentId")
            if (filename.isNotBlank() && attachmentId.isNotBlank()) {
                out.put(
                    JSONObject()
                        .put("id", attachmentId)
                        .put("mimeType", part.optString("mimeType"))
                        .put("filename", filename),
                )
            }
            val nested = part.optJSONArray("parts") ?: return
            for (index in 0 until nested.length()) {
                walk(nested.optJSONObject(index) ?: continue)
            }
        }
        walk(payload)
        return out
    }

    fun isoDate(raw: String): String {
        if (raw.isBlank()) return ""
        val parsed = runCatching { OffsetDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME) }
            .getOrNull()
            ?: return raw.take(10)
        return parsed.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate().toString()
    }

    private fun splitAddresses(raw: String): JSONArray = JSONArray().apply {
        raw.split(',').map { it.trim() }.filter { it.isNotBlank() }.forEach(::put)
    }

    private fun encodeHeader(value: String): String {
        if (value.all { it.code < 128 }) return value
        val encoded = Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
        return "=?UTF-8?B?$encoded?="
    }
}
