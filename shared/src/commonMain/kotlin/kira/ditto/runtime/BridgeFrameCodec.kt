package kira.ditto.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class BridgeFrameCodec(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) {
    private var pending = ByteArray(0)
    private var pendingLength = 0

    fun append(bytes: ByteArray): List<JsonObject> {
        if (bytes.isEmpty()) return emptyList()
        ensureCapacity(pendingLength + bytes.size)
        bytes.copyInto(pending, destinationOffset = pendingLength)
        pendingLength += bytes.size
        val frames = mutableListOf<JsonObject>()
        var start = 0
        var index = 0
        while (index < pendingLength) {
            if (pending[index] != '\n'.code.toByte()) {
                index++
                continue
            }
            val line = pending.decodeToString(start, index).trim()
            if (line.isNotEmpty()) {
                frames += json.parseToJsonElement(line).jsonObject
            }
            start = index + 1
            index++
        }
        if (start > 0) {
            val remaining = pendingLength - start
            if (remaining > 0) {
                pending.copyInto(pending, destinationOffset = 0, startIndex = start, endIndex = pendingLength)
            }
            pendingLength = remaining
        }
        return frames
    }

    fun encode(frame: JsonObject): ByteArray =
        (json.encodeToString(JsonObject.serializer(), frame) + "\n").encodeToByteArray()

    private fun ensureCapacity(min: Int) {
        if (pending.size >= min) return
        var cap = pending.size.coerceAtLeast(256)
        while (cap < min) cap *= 2
        pending = pending.copyOf(cap)
    }
}
