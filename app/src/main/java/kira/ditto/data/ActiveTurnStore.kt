package kira.ditto.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.activeTurnDataStore by preferencesDataStore(name = "aether_active_turns")

internal class ActiveTurnStore(context: Context) {
    private val app = context.applicationContext

    suspend fun markRunning(sessionId: String) {
        val id = sessionId.trim()
        if (id.isBlank()) return
        app.activeTurnDataStore.edit { prefs ->
            val next = (prefs[RUNNING_SESSION_IDS] ?: emptySet()).toMutableSet()
            next.add(id)
            prefs[RUNNING_SESSION_IDS] = next
        }
    }

    suspend fun clear(sessionId: String) {
        val id = sessionId.trim()
        if (id.isBlank()) return
        app.activeTurnDataStore.edit { prefs ->
            val next = (prefs[RUNNING_SESSION_IDS] ?: emptySet()).toMutableSet()
            next.remove(id)
            prefs[RUNNING_SESSION_IDS] = next
        }
    }

    suspend fun consumeLeftover(): Set<String> {
        val leftover = app.activeTurnDataStore.data.first()[RUNNING_SESSION_IDS].orEmpty()
        if (leftover.isNotEmpty()) {
            app.activeTurnDataStore.edit { it.remove(RUNNING_SESSION_IDS) }
        }
        return leftover
    }

    companion object {
        private val RUNNING_SESSION_IDS = stringSetPreferencesKey("running_session_ids")
    }
}

internal fun processDeathCancellationDetail(reason: String = "process_death"): Map<String, String> =
    mapOf("reason" to reason)

internal fun settleAbandonedTurnMessages(
    messages: List<kira.ditto.ui.ChatMessage>,
    interruptedText: String,
    nowMillis: Long = System.currentTimeMillis(),
): List<kira.ditto.ui.ChatMessage> {
    if (messages.isEmpty()) return messages
    val settled = messages.map { message ->
        if (message.isIncomplete) message.copy(isIncomplete = false) else message
    }
    if (interruptedText.isBlank()) return settled
    val lastUserIndex = settled.indexOfLast { message ->
        message.author == kira.ditto.ui.MessageAuthor.User &&
            message.displayKind == kira.ditto.ui.MessageDisplayKind.Standard
    }
    val tail = if (lastUserIndex >= 0) {
        settled.subList(lastUserIndex + 1, settled.size)
    } else {
        settled
    }
    if (tail.any { it.author == kira.ditto.ui.MessageAuthor.Agent && it.text.isNotBlank() }) {
        return settled
    }
    val groupId = tail.lastOrNull { it.author == kira.ditto.ui.MessageAuthor.Agent }?.responseGroupId
        ?: "agent-group-$nowMillis"
    return settled + kira.ditto.ui.ChatMessage(
        id = "agent-interrupted-$nowMillis",
        author = kira.ditto.ui.MessageAuthor.Agent,
        text = interruptedText,
        createdAtMillis = nowMillis,
        responseGroupId = groupId,
    )
}
