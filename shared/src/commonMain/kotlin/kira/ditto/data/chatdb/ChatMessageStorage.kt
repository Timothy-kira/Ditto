package kira.ditto.data.chatdb

/**
 * Storage-level read/write helpers shared by the Android repository and the iOS store, so
 * both stacks agree on how a message is split between [ChatMessageEntity] and
 * [ChatMessagePayloadEntity] and on how rows are fingerprinted.
 */

/** Large columns are read in slices so a single huge message never allocates twice. */
private const val JsonChunkSize = 64 * 1024

/**
 * Prepares a message whose [ChatMessageEntity.messageJson] still holds the full document:
 * lifts the heavy branches into a payload row and stamps both fingerprints.
 */
fun ChatMessageEntity.splitForStorage(): Pair<ChatMessageEntity, ChatMessagePayloadEntity?> {
    val split = ChatMessageJsonSplit.split(messageJson)
    val payloadHash = split.payloadJson?.let(::chatContentHash).orEmpty()
    val lightEntity = copy(
        messageJson = split.lightJson,
        contentHash = chatContentHash(split.lightJson),
        payloadHash = payloadHash,
    )
    val payloadEntity = split.payloadJson?.let { payloadJson ->
        ChatMessagePayloadEntity(
            sessionId = sessionId,
            messageId = id,
            payloadJson = payloadJson,
        )
    }
    return lightEntity to payloadEntity
}

/**
 * Writes [messages] (each carrying a full document) as light rows plus payload rows.
 * Payload rows for messages that no longer have one are dropped so a message that loses
 * its tool output does not keep the old blob alive.
 */
suspend fun ChatHistoryDao.upsertMessagesWithPayloads(messages: List<ChatMessageEntity>) {
    if (messages.isEmpty()) return
    val light = ArrayList<ChatMessageEntity>(messages.size)
    val payloads = ArrayList<ChatMessagePayloadEntity>()
    val withoutPayload = ArrayList<String>()
    messages.forEach { message ->
        val (lightEntity, payloadEntity) = message.splitForStorage()
        light += lightEntity
        if (payloadEntity != null) payloads += payloadEntity else withoutPayload += lightEntity.id
    }
    upsertMessages(light)
    upsertSpokenOriginalPointers(light)
    if (payloads.isNotEmpty()) upsertMessagePayloads(payloads)
    if (withoutPayload.isNotEmpty()) {
        deleteMessagePayloads(messages.first().sessionId, withoutPayload)
    }
}

/** Reassembles the full document for a message that was stored split. */
suspend fun ChatHistoryDao.readFullMessageJson(
    sessionId: String,
    messageId: String,
    hasPayload: Boolean,
): String? {
    val lightJson = readChunkedColumn(
        length = getMessageJsonLength(sessionId, messageId),
    ) { start, length -> getMessageJsonChunk(sessionId, messageId, start, length) } ?: return null
    if (!hasPayload) return lightJson
    val payloadJson = readChunkedColumn(
        length = getMessagePayloadLength(sessionId, messageId),
    ) { start, length -> getMessagePayloadChunk(sessionId, messageId, start, length) }
    return ChatMessageJsonSplit.merge(lightJson, payloadJson)
}

private suspend inline fun readChunkedColumn(
    length: Int?,
    readChunk: (start: Int, length: Int) -> String?,
): String? {
    val total = length ?: return null
    if (total <= 0) return ""
    val builder = StringBuilder(total)
    // SQLite substr() is 1-based.
    var start = 1
    while (start <= total) {
        val requested = minOf(JsonChunkSize, total - start + 1)
        val chunk = readChunk(start, requested) ?: return null
        if (chunk.isEmpty()) return null
        builder.append(chunk)
        start += requested
    }
    return builder.toString()
}
