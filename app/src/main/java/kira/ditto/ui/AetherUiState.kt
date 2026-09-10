package kira.ditto.ui

import androidx.compose.runtime.Immutable
import kira.ditto.data.ActiveSkillContext
import kira.ditto.data.KnowledgeCitation
import kira.ditto.data.AgentModeAuthorizationState
import kira.ditto.data.AgentModeDisplayState
import kira.ditto.data.LlmUsageTotals
import kira.ditto.data.PhoneDeskHandoffState
import kira.ditto.data.PhoneSettlementUiState
import kira.ditto.data.AppSettings
import kira.ditto.data.AppUpdateRelease
import kira.ditto.data.ChatUsageStatisticsSnapshot
import kira.ditto.data.InstalledSkill
import kira.ditto.data.LlmProviderConfig
import kira.ditto.data.ModelCatalogInfo
import kira.ditto.data.RemoteKimiModel
import kira.ditto.data.availableModelOptions
import kira.ditto.data.normalizeRemoteUiPermissionMode
import kira.ditto.data.thinkingCatalogKey
import kira.ditto.data.toProviderModelOptions
import kira.ditto.data.McpServerConfig
import kira.ditto.data.InstalledPiExtension
import kira.ditto.data.PiExtensionCatalogEntry
import kira.ditto.data.PiPackageDetails
import kira.ditto.data.RootSetupState
import kira.ditto.data.ScheduledTask
import kira.ditto.data.SessionExecutionState
import kira.ditto.data.pi.PiCoreSetupState
import kira.ditto.data.pi.PiProviderAuthState
import kira.ditto.runtime.AlpineSetupProgress
import kira.ditto.runtime.EverMeBindingState
import kira.ditto.runtime.LocalRuntimeSetupState
import kira.ditto.termux.TermuxSetupState

internal const val DraftSessionId = "draft"

enum class AppScreen {
    Onboarding,
    Chat,
    Settings,
    DigiCrew,
    UpaPlugin,
    Remote,
}

data class PersonaKnowledgeImportProgress(
    val personaId: String,
    val displayName: String,
    val fileId: String = "",
    val progress: Float = 0f,
    val phase: kira.ditto.data.PersonaKnowledgeImportPhase = kira.ditto.data.PersonaKnowledgeImportPhase.Parsing,
)

enum class OnboardingStep {
    Landing,
    EverMeSetup,
    ProviderSetup,
    TermuxSetup,
    LocalRuntimeChoice,
    AlpineSetup,
    AgentModeAuthorization,
    TavilySetup,
}

enum class RootSetupProgressReturnPage {
    Termux,
    AgentMode,
}

enum class MessageAuthor {
    User,
    Agent,
}

enum class MessageDisplayKind {
    Standard,
    HiddenContext,
    CompactStatus,
}

enum class AttachmentKind {
    Image,
    File;

    companion object {
        fun fromStored(
            value: String,
            mimeType: String,
        ): AttachmentKind = when {
            value == Image.name -> Image
            value == File.name -> File
            mimeType.startsWith("image/") -> Image
            else -> File
        }
    }
}

enum class AttachmentWorkspaceState {
    Pending,
    Ready,
    Failed,
}

@Immutable
data class ChatAttachment(
    val id: String,
    val uri: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long?,
    val kind: AttachmentKind,
    val workspacePath: String = "",
    val workspaceState: AttachmentWorkspaceState = AttachmentWorkspaceState.Ready,
    val workspaceError: String = "",
    val workspaceBytesCopied: Long = 0L,
    val workspaceBytesPerSecond: Long = 0L,
    val inlineBase64: String = "",
)

@Immutable
data class ToolCallDiff(
    val path: String,
    val oldText: String,
    val newText: String,
)

@Immutable
data class ChatToolInvocation(
    val id: String,
    val toolName: String,
    val argumentsJson: String,
    val outputJson: String = "",
    val isRunning: Boolean = false,
    val startedAtUptimeMillis: Long = 0L,
    val completedAtUptimeMillis: Long? = null,
    val startedAtMillis: Long = 0L,
    val completedAtMillis: Long? = null,
    val timelineOrder: Long = 0L,
    val toolKind: String = "",
    val diffs: List<ToolCallDiff> = emptyList(),
    val guiStepsJson: String = "",
)

@Immutable
data class ChatUsageStatistics(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val totalTokens: Long? = null,
    val reasoningTokens: Long? = null,
    val cachedInputTokens: Long? = null,
    val requestCount: Int = 1,
    val tokenUsageSource: String = "unavailable",
    val startedAtMillis: Long = 0L,
    val firstTokenAtMillis: Long? = null,
    val completedAtMillis: Long = 0L,
) {
    val firstTokenLatencyMillis: Long?
        get() = firstTokenAtMillis?.let { firstToken ->
            if (startedAtMillis > 0L) (firstToken - startedAtMillis).coerceAtLeast(0L) else null
        }

    val outputTokensPerSecond: Double?
        get() {
            val output = outputTokens ?: return null
            val outputStartedAt = firstTokenAtMillis ?: startedAtMillis.takeIf { it > 0L } ?: return null
            if (completedAtMillis <= outputStartedAt) return null
            val seconds = (completedAtMillis - outputStartedAt) / 1000.0
            if (seconds <= 0.0) return null
            return output / seconds
        }
}

data class ReasoningSummaryChunk(
    val id: String,
    val title: String = "",
    val detail: String = "",
    val rawText: String = "",
    val isPending: Boolean = false,
    val createdAtMillis: Long = 0L,
    val timelineOrder: Long = 0L,
)

@Immutable
data class ReasoningTrace(
    val id: String,
    val rawText: String = "",
    val chunks: List<ReasoningSummaryChunk> = emptyList(),
    val toolInvocations: List<ChatToolInvocation> = emptyList(),
    val latestStatusText: String = "",
    val startedAtMillis: Long = 0L,
    val completedAtMillis: Long? = null,
) {
    val hasSummary: Boolean
        get() = chunks.any { it.title.isNotBlank() || it.detail.isNotBlank() }

    val hasTimelineContent: Boolean
        get() = chunks.isNotEmpty() || toolInvocations.isNotEmpty()
}

sealed interface AssistantResponseBlock {
    val id: String

    data class Text(
        override val id: String,
        val text: String,
    ) : AssistantResponseBlock

    data class ToolGroup(
        override val id: String,
        val toolInvocations: List<ChatToolInvocation>,
    ) : AssistantResponseBlock

    data class Reasoning(
        override val id: String,
        val trace: ReasoningTrace,
    ) : AssistantResponseBlock

    data class Status(
        override val id: String,
        val text: String,
        val detail: String = "",
    ) : AssistantResponseBlock
}

internal fun completeAssistantBlocksForSteer(
    blocks: List<AssistantResponseBlock>,
    nowMillis: Long,
): List<AssistantResponseBlock> = blocks.map { block ->
    when (block) {
        is AssistantResponseBlock.Reasoning -> block.copy(
            trace = block.trace.copy(
                completedAtMillis = block.trace.completedAtMillis ?: nowMillis,
                toolInvocations = block.trace.toolInvocations.map { it.frozenAt(nowMillis) },
            ),
        )
        is AssistantResponseBlock.ToolGroup -> block.copy(
            toolInvocations = block.toolInvocations.map { it.frozenAt(nowMillis) },
        )
        is AssistantResponseBlock.Text,
        is AssistantResponseBlock.Status -> block
    }
}

private fun ChatToolInvocation.frozenAt(nowMillis: Long): ChatToolInvocation {
    if (!isRunning) return this
    return copy(
        isRunning = false,
        completedAtMillis = completedAtMillis ?: nowMillis,
        completedAtUptimeMillis = completedAtUptimeMillis ?: nowMillis,
    )
}

internal fun List<AssistantResponseBlock>.sanitizedForReasoningOff(): List<AssistantResponseBlock> {
    val finalText = filterIsInstance<AssistantResponseBlock.Text>()
        .lastOrNull { it.text.isNotBlank() }
    val workBlocks = flatMap { block ->
        when (block) {
            is AssistantResponseBlock.Text -> emptyList()
            is AssistantResponseBlock.Reasoning -> block.trace.toolInvocations
                .takeIf { it.isNotEmpty() }
                ?.let { tools ->
                    listOf(AssistantResponseBlock.ToolGroup(block.id, tools))
                }
                .orEmpty()
            is AssistantResponseBlock.Status,
            is AssistantResponseBlock.ToolGroup -> listOf(block)
        }
    }
    return workBlocks + listOfNotNull(finalText)
}

/**
 * Transcript entries are only ever replaced, never mutated in place, so Compose can treat
 * them as stable and skip items whose message instance is unchanged. Without this the
 * `List` properties make the whole class unstable and every item recomposes on any list
 * change.
 */
@Immutable
data class ChatMessage(
    val id: String,
    val author: MessageAuthor,
    val text: String,
    val createdAtMillis: Long = 0L,
    val attachments: List<ChatAttachment> = emptyList(),
    val toolInvocations: List<ChatToolInvocation> = emptyList(),
    val thoughtDurationMillis: Long? = null,
    val reasoningTrace: ReasoningTrace? = null,
    val branchGroup: ChatBranchGroup? = null,
    val responseGroupId: String? = null,
    val assistantActionsHidden: Boolean = false,
    val isIncomplete: Boolean = false,
    val statusText: String = "",
    val statusDetail: String = "",
    val providerPayloadJson: String = "",
    val displayKind: MessageDisplayKind = MessageDisplayKind.Standard,
    val usageStatistics: ChatUsageStatistics? = null,
    /**
     * One-shot composer directive ("goal"/"swarm"/"tower", see [ComposerPromptDirective]).
     * Prepended as hidden instructions to the prompt sent to the agent for this
     * message; the bubble keeps showing only [text]. Intentionally in-memory
     * only (not serialized): the directive applies to the turn the message
     * submits, it must not be replayed from restored history.
     */
    val promptDirective: String = "",
    /**
     * Hidden Persona / RAG briefing prepended to the agent prompt for this
     * user turn. Used when the session is already running so the briefing
     * cannot be queued as a separate follow-up. The bubble keeps showing only
     * [text]. In-memory only, same as [promptDirective].
     */
    val hiddenPromptPrefix: String = "",
    /**
     * Composer Agent Mode was on when this user turn was sent. In-memory only,
     * same as [promptDirective]: it labels the bubble for the current session
     * and is not replayed from restored history.
     */
    val agentModeEnabled: Boolean = false,
    /**
     * Persona knowledge slices retrieved for this user turn. Persisted so
     * assistant citation markers stay tappable after restore. The bubble
     * still shows only [text].
     */
    val knowledgeCitations: List<KnowledgeCitation> = emptyList(),
    /**
     * Photos fetched for this user turn. Persisted so later answers do not
     * inherit the latest search carousel.
     */
    val browserInlineImages: List<BrowserInlineImage> = emptyList(),
    /**
     * What the browser found on this turn, keyed by research topic.
     *
     * The card used to reconstruct this from the tool outputs, which works only for the webmcp
     * tools that return JSON. A punched-through WebSearch comes back as the agent's rendered text,
     * so the card rebuilt a preview holding the query and no hits — a lone search box. Attaching it
     * to the tool output instead does not survive either: the ACP result arrives afterwards and
     * replaces `outputJson` wholesale. It lives on the message, where nothing overwrites it, and is
     * the single thing the card reads once the live desk is gone.
     */
    val browserPreviewsByTopic: Map<String, kira.ditto.browser.BrowserDeskPreview> = emptyMap(),
)

fun precedingKnowledgeCitations(
    messages: List<ChatMessage>,
    beforeMessageId: String? = null,
    fromIndex: Int? = null,
): List<KnowledgeCitation> {
    val start = when {
        fromIndex != null -> fromIndex
        !beforeMessageId.isNullOrBlank() -> {
            val index = messages.indexOfFirst { it.id == beforeMessageId }
            if (index >= 0) index else messages.size
        }
        else -> messages.size
    }
    for (index in start - 1 downTo 0) {
        val message = messages[index]
        if (message.author == MessageAuthor.User && message.displayKind == MessageDisplayKind.Standard) {
            return message.knowledgeCitations
        }
    }
    return emptyList()
}

/**
 * Precomputes [precedingKnowledgeCitations] for every message in one pass.
 *
 * Called per item, that helper rescans the transcript backwards, so a long conversation
 * paid O(messages) inside every visible list item on every scroll frame.
 */
fun knowledgeCitationsByMessageId(
    messages: List<ChatMessage>,
): Map<String, List<KnowledgeCitation>> {
    val citations = HashMap<String, List<KnowledgeCitation>>(messages.size)
    var mostRecent: List<KnowledgeCitation> = emptyList()
    messages.forEach { message ->
        citations[message.id] = mostRecent
        if (message.author == MessageAuthor.User && message.displayKind == MessageDisplayKind.Standard) {
            mostRecent = message.knowledgeCitations
        }
    }
    return citations
}

fun browserImagesByMessageId(
    messages: List<ChatMessage>,
): Map<String, List<BrowserInlineImage>> {
    val images = HashMap<String, List<BrowserInlineImage>>(messages.size)
    var mostRecent: List<BrowserInlineImage> = emptyList()
    messages.forEach { message ->
        images[message.id] = mostRecent
        if (message.author == MessageAuthor.User && message.displayKind == MessageDisplayKind.Standard) {
            mostRecent = message.browserInlineImages
        }
    }
    return images
}

@Immutable
data class ChatSession(
    val id: String,
    val title: String,
    val preview: String,
    val hasCustomTitle: Boolean = false,
    /**
     * The hydrated window of the conversation, not necessarily the whole thing. It starts
     * at [loadedFromPosition]; anything before that is still on disk and is paged in when
     * the user scrolls back.
     */
    val messages: List<ChatMessage>,
    val messageCount: Int = messages.size,
    /** Stored position of [messages]`[0]`. Zero once the whole conversation is loaded. */
    val loadedFromPosition: Int = 0,
    val lastMessageAtMillis: Long? = messages.maxOfOrNull { it.createdAtMillis },
    val selectedSkillIds: List<String> = emptyList(),
    val activeSkills: List<ActiveSkillContext> = emptyList(),
    val activeMcpServerIds: List<String> = emptyList(),
    val agentModeEnabled: Boolean = false,
    val chromeEnabled: Boolean = false,
    val selectedModelKey: String = "",
    val remoteMachineId: String = "",
    val remoteKimiSessionId: String = "",
    val remoteCwd: String = "",
    /** The space this conversation lives in; decides which directory the agent runs in. */
    val workspaceId: String = kira.ditto.data.chatdb.DefaultWorkspaceId,
)

data class AppUpdateUiState(
    val isChecking: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Float? = null,
    val availableRelease: AppUpdateRelease? = null,
    val showAvailableDialog: Boolean = false,
    val pendingInstallUri: String = "",
)

data class McpSecretSlotUi(
    val id: String,
    val serverId: String,
    val serverName: String,
    val kind: kira.ditto.data.McpSecretKind,
    val injectKey: String,
    val serverHint: String = "",
)

data class PendingMcpSecretPromptUi(
    val sessionId: String,
    val slots: List<McpSecretSlotUi>,
)

/** A space as the drawer shows it. */
data class WorkspaceSummary(
    val id: String,
    val name: String,
)

data class AetherUiState(
    val currentScreen: AppScreen = AppScreen.Chat,
    val isStartupRouteResolved: Boolean = false,
    val isOnboardingReplay: Boolean = false,
    val onboardingStep: OnboardingStep = OnboardingStep.Landing,
    val onboardingReturnScreen: AppScreen = AppScreen.Chat,
    val sessions: List<ChatSession> = emptyList(),
    /**
     * Drawer projection of [sessions] without message bodies. Kept as a stored snapshot so
     * streaming the active transcript does not rebuild the session list.
     */
    val sessionSummaries: List<ChatSessionSummary> = emptyList(),
    /** Hydrated window of the open conversation; same object as the active [ChatSession.messages]. */
    val activeSessionMessages: List<ChatMessage> = emptyList(),
    /** Detailed records for the recent window only; see [usageStatisticsTotals]. */
    val usageStatisticsSnapshots: List<ChatUsageStatisticsSnapshot> = emptyList(),
    val usageStatisticsTotals: LlmUsageTotals = LlmUsageTotals(),
    val usageStatisticsRefreshing: Boolean = false,
    val currentSessionId: String = DraftSessionId,
    /** The spaces the user has, in display order. Always contains the default one. */
    val workspaces: List<WorkspaceSummary> = emptyList(),
    /**
     * The space being shown. Tracked explicitly rather than derived from the open conversation,
     * because a space with no conversations yet still has to be somewhere the user can stand.
     *
     * Unrelated to [draftWorkspaceId], which despite the name holds a session id.
     */
    val currentWorkspaceId: String = kira.ditto.data.chatdb.DefaultWorkspaceId,
    val draftInput: String = "",
    val draftAttachments: List<ChatAttachment> = emptyList(),
    val draftSelectedModelKey: String = "",
    val draftSelectedSkillIds: List<String> = emptyList(),
    val draftSelectedMcpServerIds: List<String> = emptyList(),
    val draftAgentModeEnabled: Boolean = false,
    val draftChromeEnabled: Boolean = false,
    /** Pending one-shot composer chip: "goal"/"swarm"/"tower"/"" (see [ComposerPromptDirective]). */
    val draftPromptDirective: String = "",
    val draftPersonaId: String = "",
    val activePersonaId: String = "",
    val personaEditorId: String = "",
    val draftWorkspaceId: String? = null,
    val editingSessionId: String? = null,
    val editingMessageId: String? = null,
    val settings: AppSettings = AppSettings(),
    val isSending: Boolean = false,
    val pendingResponseSessionId: String? = null,
    val pendingToolInvocations: List<ChatToolInvocation> = emptyList(),
    val pendingResponseBlocks: List<AssistantResponseBlock> = emptyList(),
    val pendingAssistantText: String = "",
    val pendingStatusText: String = "",
    val pendingStatusDetail: String = "",
    val compactingSessionId: String? = null,
    val isConnectingRemote: Boolean = false,
    val pendingRemoteSetup: kira.ditto.data.PendingRemoteSetup? = null,
    val sessionExecutionStates: Map<String, SessionExecutionState> = emptyMap(),
    val agentSlashCommands: List<SlashCommandSuggestion> = emptyList(),
    val workspaceFileSuggestions: List<FileMentionSuggestion> = emptyList(),
    val pendingPermissionRequests: List<kira.ditto.data.kimi.PendingPermissionRequest> = emptyList(),
    val pendingElicitationRequests: List<kira.ditto.data.kimi.PendingElicitationRequest> = emptyList(),
    val pendingMcpSecretPrompt: PendingMcpSecretPromptUi? = null,
    val unviewedCompletedSessionIds: Set<String> = emptySet(),
    val termuxSetupState: TermuxSetupState = TermuxSetupState(),
    val alpineSetupState: LocalRuntimeSetupState = LocalRuntimeSetupState(
        runtimeId = kira.ditto.data.LocalRuntimeId.Alpine,
    ),
    val alpinePackageInstallProgress: Map<String, AlpineSetupProgress> = emptyMap(),
    val everMeBindingState: EverMeBindingState = EverMeBindingState(),
    val developerTermuxReadyOverride: Boolean? = null,
    val rootSetupState: RootSetupState = RootSetupState(),
    val rootSetupProgressReturnPage: RootSetupProgressReturnPage? = null,
    val installedSkills: List<InstalledSkill> = emptyList(),
    val installedUpaPlugins: List<kira.ditto.data.InstalledUpaPlugin> = emptyList(),
    val isCheckingUpaPluginUpdates: Boolean = false,
    val installedPiExtensions: List<InstalledPiExtension> = emptyList(),
    val hasLoadedInstalledPiExtensions: Boolean = false,
    val piExtensionCatalog: List<PiExtensionCatalogEntry> = emptyList(),
    val isLoadingPiExtensions: Boolean = false,
    val piExtensionCatalogError: String = "",
    val piExtensionOperationSource: String = "",
    val selectedPiPackageDetails: PiPackageDetails? = null,
    val selectedPiPackageSource: String = "",
    val isLoadingPiPackageDetails: Boolean = false,
    val piPackageDetailsError: String = "",
    val mcpServers: List<McpServerConfig> = emptyList(),
    val personas: List<kira.ditto.data.PersonaProfile> = emptyList(),
    val digiCrews: List<kira.ditto.data.DigiCrewRoom> = emptyList(),
    val digiCrewPeers: List<kira.ditto.data.DigiCrewPeerPresence> = emptyList(),
    val digiCrewMeshStatus: String = "",
    val scheduledTasks: List<ScheduledTask> = emptyList(),
    val providerConfigs: List<LlmProviderConfig> = emptyList(),
    val remoteModelOptionsByMachineId: Map<String, List<kira.ditto.data.ProviderModelOption>> = emptyMap(),
    val remoteDefaultModelKeyByMachineId: Map<String, String> = emptyMap(),
    val remotePermissionModeByMachineId: Map<String, String> = emptyMap(),
    val modelCatalogInfo: Map<String, kira.ditto.data.ModelCatalogInfo> = emptyMap(),
    val thinkingLevelsByProviderModel: Map<String, List<String>> = emptyMap(),
    val thinkingLevelClampsByProviderModel: Map<String, Map<String, String>> = emptyMap(),
    val reasoningModels: Set<String> = emptySet(),
    val isFetchingModels: Boolean = false,
    val providerAuthState: PiProviderAuthState = PiProviderAuthState(),
    val piCoreSetupState: PiCoreSetupState = PiCoreSetupState(),
    val developerAlpineSetupPreviewState: PiCoreSetupState? = null,
    val personaKnowledgeImport: PersonaKnowledgeImportProgress? = null,
    val showStarterPromptHint: Boolean = false,
    val awaitingFollowUpTour: Boolean = false,
    val showFollowUpTourCard: Boolean = false,
    val agentModeDisplayState: AgentModeDisplayState = AgentModeDisplayState(),
    val phoneDeskHandoff: PhoneDeskHandoffState = PhoneDeskHandoffState(),
    val phoneSettlement: PhoneSettlementUiState = PhoneSettlementUiState(),
    val agentModeReviewingEverMe: Boolean = false,
    val agentModeLiveGuiStepsByToolCall: Map<String, List<kira.ditto.data.PhoneGuiStepUi>> = emptyMap(),
    val chromeDisplayState: AgentModeDisplayState = AgentModeDisplayState(),
    val browserDeskState: kira.ditto.browser.BrowserDeskState = kira.ditto.browser.BrowserDeskState(),
    val agentModeAuthorizationState: AgentModeAuthorizationState = AgentModeAuthorizationState(),
    val pendingShizukuInstallConsent: Boolean = false,
    val appUpdate: AppUpdateUiState = AppUpdateUiState(),
    val storageUsage: kira.ditto.data.AppStorageUsage = kira.ditto.data.AppStorageUsage(),
    val memoryPages: List<kira.ditto.data.MemoryPage> = emptyList(),
    val storageBusy: Boolean = false,
    val localRemoteControlRunning: Boolean = false,
    val localRemoteControlUrl: String = "",
    val localRemoteControlBusy: Boolean = false,
    val spotifyOverlay: SpotifyOverlayUi = SpotifyOverlayUi(),
)

@Immutable
data class SpotifyOverlayUi(
    val active: Boolean = false,
    val docked: Boolean = false,
    val orbMenuOpen: Boolean = false,
    val orbUnlocked: Boolean = false,
    val orbOffsetY: Float = 0f,
    val lastPlaybackInvocationId: String = "",
)

fun AetherUiState.chatModelOptions(session: ChatSession?): List<kira.ditto.data.ProviderModelOption> {
    val machineId = session?.remoteMachineId.orEmpty()
    if (machineId.isNotBlank()) {
        return remoteModelOptionsByMachineId[machineId].orEmpty()
    }
    return providerConfigs.availableModelOptions()
}

fun AetherUiState.withRemoteModelCatalog(
    machineId: String,
    models: List<RemoteKimiModel>,
    defaultModelId: String,
    defaultPermissionMode: String = "",
): AetherUiState {
    if (machineId.isBlank()) return this
    val permissionMode = normalizeRemoteUiPermissionMode(defaultPermissionMode)
    val options = models.toProviderModelOptions(machineId)
    val defaultKey = options.firstOrNull { it.modelId.equals(defaultModelId, ignoreCase = true) }?.key
        ?: options.firstOrNull()?.key.orEmpty()
    val catalogInfo = options.associate { option ->
        val model = models.first { it.id == option.modelId }
        option.key to ModelCatalogInfo(
            displayName = model.displayName.ifBlank { model.id },
            labId = "kimi-code",
            labName = "Kimi Code",
            labLogoUrl = "",
            contextWindow = model.maxContextSize.takeIf { it > 0 },
        )
    }
    val thinking = buildMap {
        options.forEach { option ->
            val model = models.first { it.id == option.modelId }
            if (model.supportEfforts.isNotEmpty()) {
                put(thinkingCatalogKey(option.piProviderId, option.modelId), model.supportEfforts)
            }
        }
    }
    val reasoning = options.mapNotNull { option ->
        val model = models.first { it.id == option.modelId }
        thinkingCatalogKey(option.piProviderId, option.modelId).takeIf {
            model.capabilities.any { capability -> capability.contains("thinking", ignoreCase = true) }
        }
    }.toSet()
    return copy(
        remoteModelOptionsByMachineId = remoteModelOptionsByMachineId + (machineId to options),
        remoteDefaultModelKeyByMachineId = remoteDefaultModelKeyByMachineId + (machineId to defaultKey),
        remotePermissionModeByMachineId = remotePermissionModeByMachineId + (machineId to permissionMode),
        modelCatalogInfo = modelCatalogInfo + catalogInfo,
        thinkingLevelsByProviderModel = thinkingLevelsByProviderModel + thinking,
        reasoningModels = reasoningModels + reasoning,
    )
}

internal fun AetherUiState.withSessionWindows(
    sessions: List<ChatSession> = this.sessions,
    currentSessionId: String = this.currentSessionId,
): AetherUiState {
    val activeMessages = sessions.firstOrNull { it.id == currentSessionId }?.messages.orEmpty()
    val summaries = sessions.map { it.toSummary() }
    return copy(
        sessions = sessions,
        currentSessionId = currentSessionId,
        sessionSummaries = if (summaries == sessionSummaries) sessionSummaries else summaries,
        activeSessionMessages = if (activeMessages === this.activeSessionMessages ||
            activeMessages == this.activeSessionMessages
        ) {
            this.activeSessionMessages
        } else {
            activeMessages
        },
    )
}
