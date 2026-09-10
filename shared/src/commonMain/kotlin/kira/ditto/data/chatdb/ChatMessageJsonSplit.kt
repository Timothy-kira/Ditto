package kira.ditto.data.chatdb

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Splits a serialized chat message into a small "light" row and an out-of-row payload.
 *
 * The split is purely mechanical — a fixed set of top-level keys is lifted out and put
 * back verbatim on read — so both encoders (Android's `org.json` writer and the shared
 * kotlinx one used on iOS) keep working without knowing the storage layout.
 */
object ChatMessageJsonSplit {
    /**
     * Branches that dominate the document size: tool output and diffs, reasoning traces,
     * and attachments carrying inline base64.
     */
    private val PayloadKeys = listOf(
        "toolInvocations",
        "reasoningTrace",
        "attachments",
        "tools",
        "responseBlocks",
    )

    private val json = Json { ignoreUnknownKeys = true }

    data class Split(val lightJson: String, val payloadJson: String?)

    fun split(messageJson: String): Split {
        if (messageJson.isBlank()) return Split(messageJson, null)
        val document = runCatching { json.parseToJsonElement(messageJson).jsonObject }.getOrNull()
            ?: return Split(messageJson, null)
        val payload = document.filterKeys { key -> key in PayloadKeys && document[key] != JsonNull }
        if (payload.isEmpty()) return Split(messageJson, null)
        val light = document.filterKeys { key -> key !in PayloadKeys }
        return Split(
            lightJson = JsonObject(light).toString(),
            payloadJson = JsonObject(payload).toString(),
        )
    }

    fun merge(lightJson: String, payloadJson: String?): String {
        if (payloadJson.isNullOrBlank()) return lightJson
        if (lightJson.isBlank()) return payloadJson
        val light = runCatching { json.parseToJsonElement(lightJson).jsonObject }.getOrNull()
            ?: return lightJson
        val payload = runCatching { json.parseToJsonElement(payloadJson).jsonObject }.getOrNull()
            ?: return lightJson
        return JsonObject(light + payload).toString()
    }
}
