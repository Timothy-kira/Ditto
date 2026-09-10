package kira.ditto.data.chatdb

import kira.ditto.data.platformCurrentTimeMillis
import okio.ByteString.Companion.encodeUtf8

/**
 * Same payload Node `JSON.stringify({ role, content })` produces for a two-string object,
 * so the sidecar hash and this table agree on `aether_hash`.
 */
fun spokenPayloadJson(role: String, content: String): String {
    val normalized = content.replace("\r\n", "\n").trim()
    return buildString {
        append("{\"role\":")
        appendJsonString(role)
        append(",\"content\":")
        appendJsonString(normalized)
        append('}')
    }
}

fun spokenTurnHash(role: String, content: String): String =
    spokenPayloadJson(role, content).encodeUtf8().sha256().hex()

fun spokenRoleFromAuthor(author: String): String? = when (author) {
    "User", "user" -> "user"
    "Agent", "assistant" -> "assistant"
    else -> null
}

fun encodeMessageIdsJson(ids: Collection<String>): String =
    ids.joinToString(prefix = "[", postfix = "]") { id ->
        buildString { appendJsonString(id) }
    }

fun decodeMessageIdsJson(json: String): List<String> {
    val body = json.trim()
    if (body.length < 2 || body.first() != '[' || body.last() != ']') return emptyList()
    val inner = body.substring(1, body.lastIndex).trim()
    if (inner.isEmpty()) return emptyList()
    return inner.split(',').mapNotNull { token ->
        val value = token.trim()
        if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            value.substring(1, value.lastIndex)
        } else {
            null
        }
    }
}

private fun StringBuilder.appendJsonString(value: String) {
    append('"')
    for (char in value) {
        when (char) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (char.code < 0x20) {
                append("\\u")
                append(char.code.toString(16).padStart(4, '0'))
            } else {
                append(char)
            }
        }
    }
    append('"')
}

internal suspend fun ChatHistoryDao.upsertSpokenOriginalPointers(messages: List<ChatMessageEntity>) {
    if (messages.isEmpty()) return
    val now = platformCurrentTimeMillis()
    for (message in messages) {
        val role = spokenRoleFromAuthor(message.author) ?: continue
        val content = message.text
        if (content.isBlank()) continue
        val hash = spokenTurnHash(role, content)
        val existing = getMemoryOriginalPointer(hash)
        val ids = decodeMessageIdsJson(existing?.messageIdsJson.orEmpty()).toMutableSet()
        ids += message.id
        upsertMemoryOriginalPointer(
            MemoryOriginalPointerEntity(
                hash = hash,
                sessionId = message.sessionId,
                messageIdsJson = encodeMessageIdsJson(ids),
                createdAtMillis = existing?.createdAtMillis ?: now,
            ),
        )
    }
}
