package kira.ditto.ui

import android.view.Surface
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kira.ditto.data.AgentModeAuthorizationMethod
import kira.ditto.data.AgentModeAuthorizationState
import kira.ditto.data.AgentModeDisplayState
import kira.ditto.data.AppLanguage
import kira.ditto.data.AppSettings
import kira.ditto.data.AppThemeMode
import kira.ditto.data.ChatUsageStatisticsSnapshot
import kira.ditto.data.InstalledPiExtension
import kira.ditto.data.InstalledSkill
import kira.ditto.data.LlmProviderConfig
import kira.ditto.data.LlmUsageTotals
import kira.ditto.data.LocalRuntimeId
import kira.ditto.data.McpServerConfig
import kira.ditto.data.McpServerTestOperation
import kira.ditto.data.ModelCatalogInfo
import kira.ditto.data.PackageProfileState
import kira.ditto.data.PendingSessionInput
import kira.ditto.data.PhoneDeskHandoffState
import kira.ditto.data.PhoneSettlementUiState
import kira.ditto.data.PiExtensionCatalogEntry
import kira.ditto.data.PiPackageDetails
import kira.ditto.data.ProviderAuthMethod
import kira.ditto.data.ProviderModelOption
import kira.ditto.data.RootSetupState
import kira.ditto.data.ScheduledTask
import kira.ditto.data.ScheduledTaskSchedule
import kira.ditto.data.SessionContextUsage
import kira.ditto.data.SessionGoalSnapshot
import kira.ditto.data.SessionPlanEntry
import kira.ditto.data.TermuxEnvironmentVariable
import kira.ditto.data.TurnActivityClock
import kira.ditto.data.kimi.PendingElicitationRequest
import kira.ditto.data.kimi.PendingPermissionRequest
import kira.ditto.data.pi.PiProviderAuthState
import kira.ditto.mod.AetherNativeModState
import kira.ditto.runtime.AlpineSetupProgress
import kira.ditto.runtime.AlpineTerminalLaunchSpec
import kira.ditto.runtime.AndroidAlpineFileManagerRuntime
import kira.ditto.runtime.LocalRuntimeSetupState
import kira.ditto.termux.TermuxSetupState
import org.json.JSONObject

/**
 * Drawer-facing projection of [ChatSession]. Message bodies stay off this object so a
 * streaming token cannot invalidate the session list.
 */
@Immutable
data class ChatSessionSummary(
    val id: String,
    val title: String,
    val preview: String,
    val hasCustomTitle: Boolean = false,
    val messageCount: Int = 0,
    val loadedFromPosition: Int = 0,
    val lastMessageAtMillis: Long? = null,
    val workspaceId: String = kira.ditto.data.chatdb.DefaultWorkspaceId,
)

fun ChatSession.toSummary(): ChatSessionSummary = ChatSessionSummary(
    id = id,
    title = title,
    preview = preview,
    hasCustomTitle = hasCustomTitle,
    messageCount = messageCount,
    loadedFromPosition = loadedFromPosition,
    lastMessageAtMillis = lastMessageAtMillis,
    workspaceId = workspaceId,
)

/**
 * Holds the last value that compared equal, so a new list/data-class instance with the
 * same contents does not invalidate [remember] keys or skippable composables.
 */
@Composable
internal fun <T> rememberStructurallyEqual(value: T): T {
    val holder = remember { mutableStateOf(value) }
    if (holder.value != value) {
        holder.value = value
    }
    return holder.value
}

@Immutable
data class ConversationScreenState(
    val conversationStateKey: String,
    val messages: List<ChatMessage>,
    val hasOlderMessages: Boolean = false,
    val workspaceDirectory: String,
    val pendingToolInvocations: List<ChatToolInvocation>,
    val pendingToolInvocationStateKey: String,
    val pendingResponseBlocks: List<AssistantResponseBlock>,
    val pendingAssistantText: String,
    val pendingStatusText: String,
    val pendingStatusDetail: String,
    val isPreparingWorkspace: Boolean,
    val activeResponseGroupId: String? = null,
    val activeResponseMessageIdPrefix: String? = null,
    val activeTurnStartedAtMillis: Long?,
    val activeTurnInteractionClock: TurnActivityClock? = null,
    val isCompacting: Boolean,
    val pendingInputs: List<PendingSessionInput>,
    val inputValue: String,
    val draftAttachments: List<ChatAttachment>,
    val modelOptions: List<ProviderModelOption>,
    val modelCatalogInfo: Map<String, ModelCatalogInfo>,
    val selectedModelKey: String,
    val reasoningEffort: String,
    val thinkingLevelsByProviderModel: Map<String, List<String>>,
    val thinkingLevelClampsByProviderModel: Map<String, Map<String, String>>,
    val agentSlashCommands: List<SlashCommandSuggestion> = emptyList(),
    val workspaceFileSuggestions: List<FileMentionSuggestion> = emptyList(),
    val pendingPermissionRequests: List<PendingPermissionRequest> = emptyList(),
    val pendingElicitationRequests: List<PendingElicitationRequest> = emptyList(),
    val pendingMcpSecretPrompt: PendingMcpSecretPromptUi? = null,
    val planEntries: List<SessionPlanEntry> = emptyList(),
    val planAnchorMessageId: String? = null,
    val planAnchorGroupId: String? = null,
    val planDocumentMarkdown: String = "",
    val goalSnapshot: SessionGoalSnapshot? = null,
    val acpContextUsage: SessionContextUsage? = null,
    val sessionModeId: String = "",
    val promptDirective: String = "",
    val availableSkills: List<InstalledSkill>,
    val availableMcpServers: List<McpServerConfig>,
    val selectedSkillIds: List<String>,
    val selectedMcpServerIds: List<String>,
    val agentModeAvailable: Boolean,
    val agentModeSelected: Boolean,
    val agentModeDisplayState: AgentModeDisplayState,
    val chromeAvailable: Boolean,
    val chromeSelected: Boolean,
    val chromeDisplayState: AgentModeDisplayState,
    val browserDeskState: kira.ditto.browser.BrowserDeskState = kira.ditto.browser.BrowserDeskState(),
    val allowRootImageRead: Boolean = false,
    val isEditing: Boolean,
    val showMenu: Boolean = true,
    val termuxSetupState: TermuxSetupState,
    val showStarterPromptHint: Boolean,
    val showTermuxSetupNotice: Boolean,
    val phoneDeskHandoff: PhoneDeskHandoffState = PhoneDeskHandoffState(),
    val phoneSettlement: PhoneSettlementUiState = PhoneSettlementUiState(),
    val agentModeReviewingEverMe: Boolean = false,
    val isSending: Boolean,
    val composerInteractive: Boolean = true,
    val asrAvailable: Boolean = false,
    val appSettings: AppSettings = AppSettings(),
    val providerConfigs: List<LlmProviderConfig> = emptyList(),
    val recordAudioGranted: Boolean = false,
    val spotifyOverlay: SpotifyOverlayUi = SpotifyOverlayUi(),
    val ttsPlaybackState: TtsPlaybackState = TtsPlaybackState(),
)

class ConversationScreenActions {
    var onLoadOlderMessages: () -> Unit = {}
    var onGoalControl: (String) -> Unit = {}
    var onSessionModeSelected: (String) -> Unit = {}
    var onSendCommand: (String) -> Unit = {}
    var onSetPromptDirective: (String) -> Unit = {}
    var onInputChanged: (String) -> Unit = {}
    var onModelSelected: (String, (Boolean) -> Unit) -> Unit = { _, _ -> }
    var onModelSelectorOpened: () -> Unit = {}
    var onReasoningEffortSelected: (String) -> Unit = {}
    var onRemoveDraftAttachment: (String) -> Unit = {}
    var onSetSkillSelected: (String, Boolean) -> Unit = { _, _ -> }
    var onSetMcpServerSelected: (String, Boolean) -> Unit = { _, _ -> }
    var onSetAgentModeSelected: (Boolean) -> Unit = {}
    var onSetChromeSelected: (Boolean) -> Unit = {}
    var onCancelEdit: () -> Unit = {}
    var onSend: () -> Unit = {}
    var onSteerPendingInput: (String) -> Unit = {}
    var onMenu: () -> Unit = {}
    var onNewChat: () -> Unit = {}
    var personaChrome: ConversationPersonaChrome? = null
    var onPickImages: () -> Unit = {}
    var onPickFiles: () -> Unit = {}
    var onSaveAttachment: (ChatAttachment) -> Unit = {}
    var onOpenLink: (String) -> Unit = {}
    var onEditMessage: (String) -> Unit = {}
    var onDeleteMessage: (String) -> Unit = {}
    var onRedoAgentMessage: (String) -> Unit = {}
    var onRetryUserMessage: (String) -> Unit = {}
    var onSwitchUserMessageBranch: (String, Int) -> Unit = { _, _ -> }
    var onCopyMessage: (ChatMessage) -> Unit = {}
    var onPlayMessage: (String) -> Unit = {}
    var onRequestTermuxPermission: () -> Unit = {}
    var onOpenAppPermissions: () -> Unit = {}
    var onOpenTermuxSettings: () -> Unit = {}
    var onOpenTermux: () -> Unit = {}
    var onInstallTermux: () -> Unit = {}
    var onRefreshTermuxSetup: () -> Unit = {}
    var onAttachAgentModePreviewSurface: (Surface) -> Unit = {}
    var onDetachAgentModePreviewSurface: (Surface) -> Unit = {}
    var onBindAgentModePreviewSurfaceView: (SurfaceView?) -> Unit = {}
    var onTapAgentModeDisplay: (Int, Int) -> Unit = { _, _ -> }
    var onSwipeAgentModeDisplay: (Int, Int, Int, Int, Int) -> Unit = { _, _, _, _, _ -> }
    var onSuppressAgentModeIme: () -> Unit = {}
    var onFinishAgentModeTeaching: () -> Unit = {}
    var onToggleAgentModeTeaching: () -> Unit = {}
    var onPauseGeneration: () -> Unit = {}
    var onDismissTermuxSetupNotice: () -> Unit = {}
    var onDismissStarterPromptHint: () -> Unit = {}
    var onAnswerPermissionRequest: (String, String) -> Unit = { _, _ -> }
    var onAnswerElicitationRequest: (String, JSONObject?) -> Unit = { _, _ -> }
    var onSubmitMcpSecrets: (Map<String, String>) -> Unit = {}
    var onSkipMcpSecrets: () -> Unit = {}
    var onRequestRecordAudio: () -> Unit = {}
    var onActivateSpotifyOverlay: (String) -> Unit = {}
    var onSetSpotifyOverlayDocked: (Boolean) -> Unit = {}
    var onSetSpotifyOrbMenuOpen: (Boolean) -> Unit = {}
    var onSetSpotifyOrbUnlocked: (Boolean) -> Unit = {}
    var onSetSpotifyOrbOffsetY: (Float) -> Unit = {}
    var onDestroySpotifyOverlay: () -> Unit = {}
}

@Immutable
data class SettingsScreenState(
    val systemPrompt: String,
    val tavilyApiKey: String,
    val tavilyBaseUrl: String,
    val llmInactivityReconnectTimeoutSeconds: Int,
    val keepTasksRunningInBackground: Boolean,
    val notifyOnTaskCompletion: Boolean,
    val autoCleanOldCommandHistory: Boolean,
    val oldCommandHistoryRetentionHours: Int,
    val termuxEnvironmentVariables: List<TermuxEnvironmentVariable>,
    val agentModeAuthorizationEnabled: Boolean,
    val agentModeAuthorizationMethod: AgentModeAuthorizationMethod,
    val agentModeAuthorizationState: AgentModeAuthorizationState,
    val rootSetupState: RootSetupState,
    val rootSetupProgressReturnPage: RootSetupProgressReturnPage?,
    val language: AppLanguage,
    val themeMode: AppThemeMode,
    val defaultChatModelKey: String,
    val defaultTitleModelKey: String,
    val defaultNamingModelKey: String,
    val defaultCompactingModelKey: String,
    val defaultVectorModelKey: String,
    val defaultImageModelKey: String,
    val defaultAsrModelKey: String,
    val defaultTtsModelKey: String,
    val ttsEnabled: Boolean,
    val ttsVoiceId: String,
    val agentModeDisplayState: AgentModeDisplayState,
    val providerConfigs: List<LlmProviderConfig>,
    val usageStatisticsSnapshots: List<ChatUsageStatisticsSnapshot>,
    val usageStatisticsTotals: LlmUsageTotals = LlmUsageTotals(),
    val usageStatisticsRefreshing: Boolean = false,
    val storageUsage: kira.ditto.data.AppStorageUsage = kira.ditto.data.AppStorageUsage(),
    val memoryPages: List<kira.ditto.data.MemoryPage> = emptyList(),
    val storageBusy: Boolean = false,
    val scheduledTasks: List<ScheduledTask>,
    val termuxSetupState: TermuxSetupState,
    val alpineSetupState: LocalRuntimeSetupState,
    val enabledRuntimeIds: Set<LocalRuntimeId>,
    val defaultRuntimeId: LocalRuntimeId?,
    val alpinePackageProfiles: Map<String, PackageProfileState>,
    val alpinePackageInstallProgress: Map<String, AlpineSetupProgress>,
    val alpineFileManagerRuntime: AndroidAlpineFileManagerRuntime,
    val browserPreferences: kira.ditto.data.BrowserPreferences = kira.ditto.data.BrowserPreferences(),
    val developerTermuxReadyOverride: Boolean?,
    val installedSkills: List<InstalledSkill>,
    val installedPiExtensions: List<InstalledPiExtension>,
    val hasLoadedInstalledPiExtensions: Boolean,
    val nativeModState: AetherNativeModState,
    val piExtensionCatalog: List<PiExtensionCatalogEntry>,
    val isLoadingPiExtensions: Boolean,
    val piExtensionCatalogError: String,
    val piExtensionOperationSource: String,
    val selectedPiPackageDetails: PiPackageDetails?,
    val selectedPiPackageSource: String,
    val isLoadingPiPackageDetails: Boolean,
    val piPackageDetailsError: String,
    val mcpServers: List<McpServerConfig>,
    val isFetchingModels: Boolean,
    val providerAuthState: PiProviderAuthState,
    val appUpdate: AppUpdateUiState,
)

class SettingsScreenActions {
    var onRefreshUsageStatistics: () -> Unit = {}
    var onRefreshMemoryPages: () -> Unit = {}
    var onDeleteMemoryPage: (String) -> Unit = {}
    var onClearMemoryPages: () -> Unit = {}
    var onRefreshStorageUsage: () -> Unit = {}
    var onClearAppStorage: (kira.ditto.data.AppStorageClearKind) -> Unit = {}
    var onUpdateCacheCleanupPolicy: (Boolean, Int) -> Unit = { _, _ -> }
    var onSave: (
        String,
        String,
        String,
        Int,
        Boolean,
        Boolean,
        Boolean,
        Int,
        List<TermuxEnvironmentVariable>,
        Boolean,
        AgentModeAuthorizationMethod,
        AppLanguage,
        AppThemeMode,
        String,
        String,
        String,
        String,
        String,
        String,
        String,
        String,
        Boolean,
        String,
    ) -> Unit = { _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> }
    var onUpdateLanguage: (AppLanguage) -> Unit = {}
    var onUpdateThemeMode: (AppThemeMode) -> Unit = {}
    var onUpsertProviderConfig: (LlmProviderConfig) -> Unit = {}
    var onRemoveProviderConfig: (String) -> Unit = {}
    var onSetProviderEnabled: (String, Boolean) -> Unit = { _, _ -> }
    var onFetchModels: (LlmProviderConfig, (List<String>) -> Unit) -> Unit = { _, _ -> }
    var onStartProviderLogin: (String, String, ProviderAuthMethod, String) -> Unit = { _, _, _, _ -> }
    var onSubmitProviderAuthPrompt: (String, String, Boolean) -> Unit = { _, _, _ -> }
    var onClearProviderAuthState: () -> Unit = {}
    var onImportSkillFolder: () -> Unit = {}
    var onImportSkillZip: ((Boolean) -> Unit) -> Unit = {}
    var onInstallSkillUrl: (String, (Boolean) -> Unit) -> Unit = { _, _ -> }
    var onToggleSkillEnabled: (String, Boolean) -> Unit = { _, _ -> }
    var onRemoveSkill: (String) -> Unit = {}
    var onRefreshPiExtensions: () -> Unit = {}
    var onInstallPiExtensionPackage: (String) -> Unit = {}
    var onLoadPiPackageDetails: (PiExtensionCatalogEntry) -> Unit = {}
    var onUpdatePiExtensionPackage: (String) -> Unit = {}
    var onRemovePiExtension: (InstalledPiExtension) -> Unit = {}
    var onSetPiExtensionEnabled: (InstalledPiExtension, Boolean) -> Unit = { _, _ -> }
    var onImportPiExtension: () -> Unit = {}
    var onAllowNativeModsOnNextStart: () -> Unit = {}
    var onDisableNativeModsOnNextStart: () -> Unit = {}
    var onSaveHttpMcpServer: (String?, String, String, String) -> Unit = { _, _, _, _ -> }
    var onSaveStdIoMcpServer: (String?, String, String, String, String, String, LocalRuntimeId?) -> Unit =
        { _, _, _, _, _, _, _ -> }
    var onToggleMcpServerEnabled: (String, Boolean) -> Unit = { _, _ -> }
    var onRemoveMcpServer: (String) -> Unit = {}
    var onTestMcpServer: (String, McpServerTestOperation, (String) -> Unit) -> Unit = { _, _, _ -> }
    var onSaveScheduledTask: (String?, String, String, ScheduledTaskSchedule, Boolean) -> Unit =
        { _, _, _, _, _ -> }
    var onToggleScheduledTaskEnabled: (String, Boolean) -> Unit = { _, _ -> }
    var onRemoveScheduledTask: (String) -> Unit = {}
    var onRefreshScheduledTasks: () -> Unit = {}
    var onRequestTermuxPermission: () -> Unit = {}
    var onImportAppData: () -> Unit = {}
    var onExportAppData: () -> Unit = {}
    var onExportLogs: () -> Unit = {}
    var onOpenAppPermissions: () -> Unit = {}
    var onOpenTermuxSettings: () -> Unit = {}
    var onOpenTermux: () -> Unit = {}
    var onInstallTermux: () -> Unit = {}
    var onRefreshTermuxSetup: () -> Unit = {}
    var onInitializeAlpineRuntime: () -> Unit = {}
    var onResetAlpineRuntime: () -> Unit = {}
    var onRefreshAlpineSetup: () -> Unit = {}
    var onInstallAlpinePackageProfile: (String) -> Unit = {}
    var onCreateAlpineTerminalLaunchSpec: suspend () -> Result<AlpineTerminalLaunchSpec> =
        { Result.failure(IllegalStateException("unset")) }
    var onUpdateBrowserPreferences: (kira.ditto.data.BrowserPreferences) -> Unit = {}
    var onOpenBuiltInBrowser: () -> Unit = {}
    var onClearBrowserData: () -> Unit = {}
    var onDeleteBrowserLogin: (String) -> Unit = {}
    var onListBrowserLogins: () -> List<kira.ditto.browser.SavedBrowserLogin> = { emptyList() }
    var onSetDefaultRuntime: (LocalRuntimeId) -> Unit = {}
    var onRefreshRootSetup: () -> Unit = {}
    var onStartRootSetupFromSettings: (RootSetupProgressReturnPage) -> Unit = {}
    var onDismissRootSetupProgress: () -> Unit = {}
    var onRequestShizukuPermission: () -> Unit = {}
    var onRefreshAgentModeAuthorization: (Boolean, AgentModeAuthorizationMethod) -> Unit = { _, _ -> }
    var onOpenShizuku: () -> Unit = {}
    var onInstallShizuku: () -> Unit = {}
    var onReplayOnboarding: () -> Unit = {}
    var onReplayFollowUpOnboarding: () -> Unit = {}
    var onReplayAlpineSetupPreview: () -> Unit = {}
    var onStopAgentModeDisplay: () -> Unit = {}
    var onRefreshAgentModeDisplays: (AgentModeAuthorizationMethod) -> Unit = {}
    var onOpenWebsite: () -> Unit = {}
    var onOpenGitHub: () -> Unit = {}
    var onOpenPrivacyPolicy: () -> Unit = {}
    var onCheckForUpdates: () -> Unit = {}
    var onForceUpdateCheckForTesting: () -> Unit = {}
    var onSetDeveloperTermuxReadyOverride: (Boolean) -> Unit = {}
    var onDownloadAndInstallUpdate: () -> Unit = {}
    var onBack: () -> Unit = {}
}

@Composable
fun ConversationScreen(
    state: ConversationScreenState,
    actions: ConversationScreenActions,
) {
    ConversationScreen(
        conversationStateKey = state.conversationStateKey,
        messages = state.messages,
        hasOlderMessages = state.hasOlderMessages,
        onLoadOlderMessages = actions.onLoadOlderMessages,
        workspaceDirectory = state.workspaceDirectory,
        pendingToolInvocations = state.pendingToolInvocations,
        pendingToolInvocationStateKey = state.pendingToolInvocationStateKey,
        pendingResponseBlocks = state.pendingResponseBlocks,
        pendingAssistantText = state.pendingAssistantText,
        pendingStatusText = state.pendingStatusText,
        pendingStatusDetail = state.pendingStatusDetail,
        isPreparingWorkspace = state.isPreparingWorkspace,
        activeResponseGroupId = state.activeResponseGroupId,
        activeResponseMessageIdPrefix = state.activeResponseMessageIdPrefix,
        activeTurnStartedAtMillis = state.activeTurnStartedAtMillis,
        activeTurnInteractionClock = state.activeTurnInteractionClock,
        isCompacting = state.isCompacting,
        pendingInputs = state.pendingInputs,
        inputValue = state.inputValue,
        draftAttachments = state.draftAttachments,
        modelOptions = state.modelOptions,
        modelCatalogInfo = state.modelCatalogInfo,
        selectedModelKey = state.selectedModelKey,
        reasoningEffort = state.reasoningEffort,
        thinkingLevelsByProviderModel = state.thinkingLevelsByProviderModel,
        thinkingLevelClampsByProviderModel = state.thinkingLevelClampsByProviderModel,
        agentSlashCommands = state.agentSlashCommands,
        workspaceFileSuggestions = state.workspaceFileSuggestions,
        pendingPermissionRequests = state.pendingPermissionRequests,
        pendingElicitationRequests = state.pendingElicitationRequests,
        pendingMcpSecretPrompt = state.pendingMcpSecretPrompt,
        planEntries = state.planEntries,
        planAnchorMessageId = state.planAnchorMessageId,
        planAnchorGroupId = state.planAnchorGroupId,
        planDocumentMarkdown = state.planDocumentMarkdown,
        goalSnapshot = state.goalSnapshot,
        onGoalControl = actions.onGoalControl,
        acpContextUsage = state.acpContextUsage,
        sessionModeId = state.sessionModeId,
        onSessionModeSelected = actions.onSessionModeSelected,
        onSendCommand = actions.onSendCommand,
        promptDirective = state.promptDirective,
        onSetPromptDirective = actions.onSetPromptDirective,
        availableSkills = state.availableSkills,
        availableMcpServers = state.availableMcpServers,
        selectedSkillIds = state.selectedSkillIds,
        selectedMcpServerIds = state.selectedMcpServerIds,
        agentModeAvailable = state.agentModeAvailable,
        agentModeSelected = state.agentModeSelected,
        agentModeDisplayState = state.agentModeDisplayState,
        chromeAvailable = state.chromeAvailable,
        chromeSelected = state.chromeSelected,
        chromeDisplayState = state.chromeDisplayState,
        browserDeskState = state.browserDeskState,
        allowRootImageRead = state.allowRootImageRead,
        isEditing = state.isEditing,
        showMenu = state.showMenu,
        termuxSetupState = state.termuxSetupState,
        showStarterPromptHint = state.showStarterPromptHint,
        showTermuxSetupNotice = state.showTermuxSetupNotice,
        onInputChanged = actions.onInputChanged,
        onModelSelected = actions.onModelSelected,
        onModelSelectorOpened = actions.onModelSelectorOpened,
        onReasoningEffortSelected = actions.onReasoningEffortSelected,
        onRemoveDraftAttachment = actions.onRemoveDraftAttachment,
        onSetSkillSelected = actions.onSetSkillSelected,
        onSetMcpServerSelected = actions.onSetMcpServerSelected,
        onSetAgentModeSelected = actions.onSetAgentModeSelected,
        onSetChromeSelected = actions.onSetChromeSelected,
        onCancelEdit = actions.onCancelEdit,
        onSend = actions.onSend,
        onSteerPendingInput = actions.onSteerPendingInput,
        onMenu = actions.onMenu,
        onNewChat = actions.onNewChat,
        personaChrome = actions.personaChrome,
        onPickImages = actions.onPickImages,
        onPickFiles = actions.onPickFiles,
        onSaveAttachment = actions.onSaveAttachment,
        onOpenLink = actions.onOpenLink,
        onEditMessage = actions.onEditMessage,
        onDeleteMessage = actions.onDeleteMessage,
        onRedoAgentMessage = actions.onRedoAgentMessage,
        onRetryUserMessage = actions.onRetryUserMessage,
        onSwitchUserMessageBranch = actions.onSwitchUserMessageBranch,
        onCopyMessage = actions.onCopyMessage,
        ttsPlaybackState = state.ttsPlaybackState,
        onRequestTermuxPermission = actions.onRequestTermuxPermission,
        onOpenAppPermissions = actions.onOpenAppPermissions,
        onOpenTermuxSettings = actions.onOpenTermuxSettings,
        onOpenTermux = actions.onOpenTermux,
        onInstallTermux = actions.onInstallTermux,
        onRefreshTermuxSetup = actions.onRefreshTermuxSetup,
        onAttachAgentModePreviewSurface = actions.onAttachAgentModePreviewSurface,
        onDetachAgentModePreviewSurface = actions.onDetachAgentModePreviewSurface,
        onBindAgentModePreviewSurfaceView = actions.onBindAgentModePreviewSurfaceView,
        onTapAgentModeDisplay = actions.onTapAgentModeDisplay,
        onSwipeAgentModeDisplay = actions.onSwipeAgentModeDisplay,
        onSuppressAgentModeIme = actions.onSuppressAgentModeIme,
        onFinishAgentModeTeaching = actions.onFinishAgentModeTeaching,
        onToggleAgentModeTeaching = actions.onToggleAgentModeTeaching,
        phoneDeskHandoff = state.phoneDeskHandoff,
        phoneSettlement = state.phoneSettlement,
        agentModeReviewingEverMe = state.agentModeReviewingEverMe,
        onPauseGeneration = actions.onPauseGeneration,
        onDismissTermuxSetupNotice = actions.onDismissTermuxSetupNotice,
        onDismissStarterPromptHint = actions.onDismissStarterPromptHint,
        onAnswerPermissionRequest = actions.onAnswerPermissionRequest,
        onAnswerElicitationRequest = actions.onAnswerElicitationRequest,
        onSubmitMcpSecrets = actions.onSubmitMcpSecrets,
        onSkipMcpSecrets = actions.onSkipMcpSecrets,
        isSending = state.isSending,
        composerInteractive = state.composerInteractive,
        asrAvailable = state.asrAvailable,
        appSettings = state.appSettings,
        providerConfigs = state.providerConfigs,
        onRequestRecordAudio = actions.onRequestRecordAudio,
        recordAudioGranted = state.recordAudioGranted,
        spotifyOverlay = state.spotifyOverlay,
        onActivateSpotifyOverlay = actions.onActivateSpotifyOverlay,
        onSetSpotifyOverlayDocked = actions.onSetSpotifyOverlayDocked,
        onSetSpotifyOrbMenuOpen = actions.onSetSpotifyOrbMenuOpen,
        onSetSpotifyOrbUnlocked = actions.onSetSpotifyOrbUnlocked,
        onSetSpotifyOrbOffsetY = actions.onSetSpotifyOrbOffsetY,
        onDestroySpotifyOverlay = actions.onDestroySpotifyOverlay,
    )
}

@Composable
fun SettingsScreen(
    state: SettingsScreenState,
    actions: SettingsScreenActions,
) {
    SettingsScreen(
        systemPrompt = state.systemPrompt,
        tavilyApiKey = state.tavilyApiKey,
        tavilyBaseUrl = state.tavilyBaseUrl,
        llmInactivityReconnectTimeoutSeconds = state.llmInactivityReconnectTimeoutSeconds,
        keepTasksRunningInBackground = state.keepTasksRunningInBackground,
        notifyOnTaskCompletion = state.notifyOnTaskCompletion,
        autoCleanOldCommandHistory = state.autoCleanOldCommandHistory,
        oldCommandHistoryRetentionHours = state.oldCommandHistoryRetentionHours,
        termuxEnvironmentVariables = state.termuxEnvironmentVariables,
        agentModeAuthorizationEnabled = state.agentModeAuthorizationEnabled,
        agentModeAuthorizationMethod = state.agentModeAuthorizationMethod,
        agentModeAuthorizationState = state.agentModeAuthorizationState,
        rootSetupState = state.rootSetupState,
        rootSetupProgressReturnPage = state.rootSetupProgressReturnPage,
        language = state.language,
        themeMode = state.themeMode,
        defaultChatModelKey = state.defaultChatModelKey,
        defaultTitleModelKey = state.defaultTitleModelKey,
        defaultNamingModelKey = state.defaultNamingModelKey,
        defaultCompactingModelKey = state.defaultCompactingModelKey,
        defaultVectorModelKey = state.defaultVectorModelKey,
        defaultImageModelKey = state.defaultImageModelKey,
        defaultAsrModelKey = state.defaultAsrModelKey,
        defaultTtsModelKey = state.defaultTtsModelKey,
        ttsEnabled = state.ttsEnabled,
        ttsVoiceId = state.ttsVoiceId,
        agentModeDisplayState = state.agentModeDisplayState,
        providerConfigs = state.providerConfigs,
        usageStatisticsSnapshots = state.usageStatisticsSnapshots,
        usageStatisticsTotals = state.usageStatisticsTotals,
        usageStatisticsRefreshing = state.usageStatisticsRefreshing,
        onRefreshUsageStatistics = actions.onRefreshUsageStatistics,
        storageUsage = state.storageUsage,
        storageBusy = state.storageBusy,
        memoryPages = state.memoryPages,
        onRefreshMemoryPages = actions.onRefreshMemoryPages,
        onDeleteMemoryPage = actions.onDeleteMemoryPage,
        onClearMemoryPages = actions.onClearMemoryPages,
        onRefreshStorageUsage = actions.onRefreshStorageUsage,
        onClearAppStorage = actions.onClearAppStorage,
        onUpdateCacheCleanupPolicy = actions.onUpdateCacheCleanupPolicy,
        scheduledTasks = state.scheduledTasks,
        termuxSetupState = state.termuxSetupState,
        alpineSetupState = state.alpineSetupState,
        enabledRuntimeIds = state.enabledRuntimeIds,
        defaultRuntimeId = state.defaultRuntimeId,
        alpinePackageProfiles = state.alpinePackageProfiles,
        alpinePackageInstallProgress = state.alpinePackageInstallProgress,
        alpineFileManagerRuntime = state.alpineFileManagerRuntime,
        developerTermuxReadyOverride = state.developerTermuxReadyOverride,
        installedSkills = state.installedSkills,
        installedPiExtensions = state.installedPiExtensions,
        hasLoadedInstalledPiExtensions = state.hasLoadedInstalledPiExtensions,
        nativeModState = state.nativeModState,
        piExtensionCatalog = state.piExtensionCatalog,
        isLoadingPiExtensions = state.isLoadingPiExtensions,
        piExtensionCatalogError = state.piExtensionCatalogError,
        piExtensionOperationSource = state.piExtensionOperationSource,
        selectedPiPackageDetails = state.selectedPiPackageDetails,
        selectedPiPackageSource = state.selectedPiPackageSource,
        isLoadingPiPackageDetails = state.isLoadingPiPackageDetails,
        piPackageDetailsError = state.piPackageDetailsError,
        mcpServers = state.mcpServers,
        isFetchingModels = state.isFetchingModels,
        providerAuthState = state.providerAuthState,
        appUpdate = state.appUpdate,
        onSave = actions.onSave,
        onUpdateLanguage = actions.onUpdateLanguage,
        onUpdateThemeMode = actions.onUpdateThemeMode,
        onUpsertProviderConfig = actions.onUpsertProviderConfig,
        onRemoveProviderConfig = actions.onRemoveProviderConfig,
        onSetProviderEnabled = actions.onSetProviderEnabled,
        onFetchModels = actions.onFetchModels,
        onStartProviderLogin = actions.onStartProviderLogin,
        onSubmitProviderAuthPrompt = actions.onSubmitProviderAuthPrompt,
        onClearProviderAuthState = actions.onClearProviderAuthState,
        onImportSkillFolder = actions.onImportSkillFolder,
        onImportSkillZip = actions.onImportSkillZip,
        onInstallSkillUrl = actions.onInstallSkillUrl,
        onToggleSkillEnabled = actions.onToggleSkillEnabled,
        onRemoveSkill = actions.onRemoveSkill,
        onRefreshPiExtensions = actions.onRefreshPiExtensions,
        onInstallPiExtensionPackage = actions.onInstallPiExtensionPackage,
        onLoadPiPackageDetails = actions.onLoadPiPackageDetails,
        onUpdatePiExtensionPackage = actions.onUpdatePiExtensionPackage,
        onRemovePiExtension = actions.onRemovePiExtension,
        onSetPiExtensionEnabled = actions.onSetPiExtensionEnabled,
        onImportPiExtension = actions.onImportPiExtension,
        onAllowNativeModsOnNextStart = actions.onAllowNativeModsOnNextStart,
        onDisableNativeModsOnNextStart = actions.onDisableNativeModsOnNextStart,
        onSaveHttpMcpServer = actions.onSaveHttpMcpServer,
        onSaveStdIoMcpServer = actions.onSaveStdIoMcpServer,
        onToggleMcpServerEnabled = actions.onToggleMcpServerEnabled,
        onRemoveMcpServer = actions.onRemoveMcpServer,
        onTestMcpServer = actions.onTestMcpServer,
        onSaveScheduledTask = actions.onSaveScheduledTask,
        onToggleScheduledTaskEnabled = actions.onToggleScheduledTaskEnabled,
        onRemoveScheduledTask = actions.onRemoveScheduledTask,
        onRefreshScheduledTasks = actions.onRefreshScheduledTasks,
        onRequestTermuxPermission = actions.onRequestTermuxPermission,
        onImportAppData = actions.onImportAppData,
        onExportAppData = actions.onExportAppData,
        onExportLogs = actions.onExportLogs,
        onOpenAppPermissions = actions.onOpenAppPermissions,
        onOpenTermuxSettings = actions.onOpenTermuxSettings,
        onOpenTermux = actions.onOpenTermux,
        onInstallTermux = actions.onInstallTermux,
        onRefreshTermuxSetup = actions.onRefreshTermuxSetup,
        onInitializeAlpineRuntime = actions.onInitializeAlpineRuntime,
        onResetAlpineRuntime = actions.onResetAlpineRuntime,
        onRefreshAlpineSetup = actions.onRefreshAlpineSetup,
        onInstallAlpinePackageProfile = actions.onInstallAlpinePackageProfile,
        onCreateAlpineTerminalLaunchSpec = actions.onCreateAlpineTerminalLaunchSpec,
        browserPreferences = state.browserPreferences,
        onUpdateBrowserPreferences = actions.onUpdateBrowserPreferences,
        onOpenBuiltInBrowser = actions.onOpenBuiltInBrowser,
        onClearBrowserData = actions.onClearBrowserData,
        onDeleteBrowserLogin = actions.onDeleteBrowserLogin,
        onListBrowserLogins = actions.onListBrowserLogins,
        onSetDefaultRuntime = actions.onSetDefaultRuntime,
        onRefreshRootSetup = actions.onRefreshRootSetup,
        onStartRootSetupFromSettings = actions.onStartRootSetupFromSettings,
        onDismissRootSetupProgress = actions.onDismissRootSetupProgress,
        onRequestShizukuPermission = actions.onRequestShizukuPermission,
        onRefreshAgentModeAuthorization = actions.onRefreshAgentModeAuthorization,
        onOpenShizuku = actions.onOpenShizuku,
        onInstallShizuku = actions.onInstallShizuku,
        onReplayOnboarding = actions.onReplayOnboarding,
        onReplayFollowUpOnboarding = actions.onReplayFollowUpOnboarding,
        onReplayAlpineSetupPreview = actions.onReplayAlpineSetupPreview,
        onStopAgentModeDisplay = actions.onStopAgentModeDisplay,
        onRefreshAgentModeDisplays = actions.onRefreshAgentModeDisplays,
        onOpenWebsite = actions.onOpenWebsite,
        onOpenGitHub = actions.onOpenGitHub,
        onOpenPrivacyPolicy = actions.onOpenPrivacyPolicy,
        onCheckForUpdates = actions.onCheckForUpdates,
        onForceUpdateCheckForTesting = actions.onForceUpdateCheckForTesting,
        onSetDeveloperTermuxReadyOverride = actions.onSetDeveloperTermuxReadyOverride,
        onDownloadAndInstallUpdate = actions.onDownloadAndInstallUpdate,
        onBack = actions.onBack,
    )
}
