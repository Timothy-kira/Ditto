package kira.ditto.ui

import androidx.compose.runtime.compositionLocalOf

/**
 * True while the conversation is covered by another surface (currently the session
 * drawer). Consumers stop work whose result nobody can see: infinite shimmer
 * animations, the one-second elapsed-time ticker, and background markdown parsing.
 */
val LocalChatActivityPaused = compositionLocalOf { false }

/** True while the conversation list is being dragged or flung. */
val LocalConversationScrolling = compositionLocalOf { false }

/** Markdown sources that have already been fully composed in this conversation. */
class ConversationMarkdownComposedRegistry {
    private val keys = HashSet<String>()

    fun contains(markdown: String): Boolean = synchronized(keys) { markdown in keys }

    fun add(markdown: String) {
        if (markdown.isBlank()) return
        synchronized(keys) { keys.add(markdown) }
    }
}

val LocalConversationMarkdownComposed = compositionLocalOf { ConversationMarkdownComposedRegistry() }
