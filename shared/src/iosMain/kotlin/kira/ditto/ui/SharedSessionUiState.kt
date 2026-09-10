package kira.ditto.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kira.ditto.data.SharedActiveSkillContext
import kira.ditto.data.chatdb.PersistedChatSession
import kira.ditto.data.chatdb.PersistedChatMessage
import kira.ditto.data.chatdb.deriveSharedSessionMetadata
import kira.ditto.data.platformRandomUuid
import kotlinx.coroutines.Job

internal class SharedSessionUiState(
    id: String,
    initialMessages: List<SharedChatMessage> = emptyList(),
    selectedSkillIds: List<String> = emptyList(),
    activeSkills: List<SharedActiveSkillContext> = emptyList(),
    activeMcpServerIds: List<String> = emptyList(),
    selectedModelKey: String = "",
    title: String = "New chat",
    isDraft: Boolean = false,
    isHydrated: Boolean = true,
) {
    val composerKey = "$id:${platformRandomUuid()}"
    var id by mutableStateOf(id)
    var isDraft by mutableStateOf(isDraft)

    /**
     * False for a session restored from the drawer summaries, whose message bodies are still
     * on disk. Such a state must never be written back, or the save would erase the history it
     * has not read yet.
     */
    var isHydrated by mutableStateOf(isHydrated)
    val messages = mutableStateListOf<SharedChatMessage>().apply { addAll(initialMessages) }
    val queuedTurns = mutableStateListOf<SharedPendingTurn>()
    val selectedSkillIds = mutableStateListOf<String>().apply { addAll(selectedSkillIds) }
    val activeSkills = mutableStateListOf<SharedActiveSkillContext>().apply { addAll(activeSkills) }
    val activeMcpServerIds = mutableStateListOf<String>().apply { addAll(activeMcpServerIds) }
    var input by mutableStateOf("")
    var editingMessageId by mutableStateOf("")
    var job by mutableStateOf<Job?>(null)
    var streamingStatus by mutableStateOf("")
    var selectedModelKey by mutableStateOf(selectedModelKey)
    var title by mutableStateOf(title.ifBlank { "New chat" })
    var hasCustomTitle by mutableStateOf(false)
    var hasUnviewedCompletion by mutableStateOf(false)

    val isWorking: Boolean
        get() = job?.isActive == true
}

internal fun SharedSessionUiState.clearComposerDraft() {
    input = ""
    editingMessageId = ""
}

internal fun SharedSessionUiState.retainEnabledSkillSelections(enabledSkillIds: Set<String>): Boolean {
    val selectedChanged = selectedSkillIds.removeAll { it !in enabledSkillIds }
    val activeChanged = activeSkills.removeAll { it.skillId !in enabledSkillIds }
    return selectedChanged || activeChanged
}

internal fun SharedSessionUiState.retainEnabledMcpSelections(enabledMcpServerIds: Set<String>): Boolean {
    val previousSize = activeMcpServerIds.size
    activeMcpServerIds.retainAll(enabledMcpServerIds)
    return activeMcpServerIds.size != previousSize
}

internal fun PersistedChatSession.toSharedSessionUiState(
    isHydrated: Boolean = true,
): SharedSessionUiState =
    SharedSessionUiState(
        id = id,
        initialMessages = toSharedVisibleMessages(),
        selectedSkillIds = selectedSkillIds,
        activeSkills = activeSkills,
        activeMcpServerIds = activeMcpServerIds,
        selectedModelKey = selectedModelKey,
        title = title,
        isHydrated = isHydrated,
    ).also { state -> state.hasCustomTitle = hasCustomTitle }

internal fun PersistedChatSession.toSharedVisibleMessages(): List<SharedChatMessage> =
    messages.map(PersistedChatMessage::toSharedChatMessage).filter { message ->
        message.fromUser || message.hasSharedVisibleAssistantWork()
    }

internal fun SharedSessionUiState.toPersistedSession(): PersistedChatSession {
    val persistedMessages = messages.toPersistedMessages()
    return PersistedChatSession(
        id = id,
        title = title,
        preview = deriveSharedSessionMetadata(persistedMessages).second,
        messages = persistedMessages,
        hasCustomTitle = hasCustomTitle,
        selectedSkillIds = selectedSkillIds.toList(),
        activeSkills = activeSkills.toList(),
        activeMcpServerIds = activeMcpServerIds.toList(),
        selectedModelKey = selectedModelKey,
    )
}
