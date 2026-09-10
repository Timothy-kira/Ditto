package kira.ditto.data

import kira.ditto.ui.ChatMessage
import kira.ditto.ui.ChatSession
import kira.ditto.ui.ChatSessionSummary
import kira.ditto.ui.MessageDisplayKind
import kira.ditto.ui.MessageAuthor
import kira.ditto.ui.syncActiveBranches

internal fun ChatSession.usableTitle(fallback: String = "New chat"): String {
    if (title.isNotBlank() && !title.looksLikeHiddenPromptTitle()) return title
    return deriveSessionMetadata(messages).first.ifBlank { fallback }
}

internal fun ChatSessionSummary.usableTitle(fallback: String = "New chat"): String =
    title.ifBlank { fallback }

internal fun ChatSession.withDerivedMessages(
    messages: List<ChatMessage>,
): ChatSession {
    val metadata = deriveSessionMetadata(messages)
    // A windowed session does not contain the opening message, so the derived title would
    // be wrong; only the preview (taken from the newest message) stays meaningful.
    val isWindowed = loadedFromPosition > 0
    return copy(
        title = if (isWindowed || (hasCustomTitle && !title.looksLikeHiddenPromptTitle())) {
            title
        } else {
            metadata.first
        },
        preview = metadata.second,
        messages = syncActiveBranches(messages),
        messageCount = loadedFromPosition + messages.size,
        lastMessageAtMillis = messages.maxOfOrNull { it.createdAtMillis },
    )
}

private fun deriveSessionMetadata(messages: List<ChatMessage>): Pair<String, String> {
    val visibleMessages = messages.filter { it.displayKind != MessageDisplayKind.HiddenContext }
    val title = messages
        .firstOrNull { message ->
            message.author == MessageAuthor.User &&
                message.displayKind == MessageDisplayKind.Standard &&
                !message.summaryText().looksLikeHiddenPromptTitle()
        }
        ?.summaryText()
        .orEmpty()
        .ifBlank { "New chat" }
        .take(36)
    val preview = visibleMessages
        .lastOrNull()
        ?.summaryText()
        .orEmpty()
        .ifBlank { "No messages yet." }
        .take(96)
    return title to preview
}

internal fun ChatMessage.summaryText(): String {
    if (displayKind == MessageDisplayKind.CompactStatus) return text.ifBlank { "Context compacted" }
    val textSummary = text.visibleUserMessageText()
    if (textSummary.isNotBlank()) return textSummary
    reasoningTrace?.let { trace ->
        trace.chunks.lastOrNull { it.detail.isNotBlank() || it.title.isNotBlank() }?.let { chunk ->
            return chunk.detail.ifBlank { chunk.title }
        }
        return if (trace.toolInvocations.isNotEmpty()) {
            "Thought and used ${trace.toolInvocations.size} tools"
        } else {
            "Thought"
        }
    }
    if (toolInvocations.isNotEmpty()) {
        return if (toolInvocations.size == 1) {
            when (toolInvocations.first().toolName.lowercase()) {
                "bash" -> "Ran bash command"
                else -> "Used ${toolInvocations.first().toolName}"
            }
        } else {
            "Used ${toolInvocations.size} tools"
        }
    }
    if (attachments.isEmpty()) return "Empty message"
    if (attachments.size == 1) return attachments.first().name
    return "${attachments.size} attachments"
}
