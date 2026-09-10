package kira.ditto.ui

import android.app.Application
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.Surface
import android.view.SurfaceView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.FileOutputStream
import kira.ditto.BuildConfig
import kira.ditto.R
import kira.ditto.aetherRuntime
import kira.ditto.data.ActiveSkillContext
import kira.ditto.data.AetherAnalytics
import kira.ditto.data.AetherModOperationDecision
import kira.ditto.data.AetherModServiceMethod
import kira.ditto.data.AppUpdateManager
import kira.ditto.data.AutomaticModelPurpose
import kira.ditto.data.AgentModeAuthorizationMethod
import kira.ditto.data.AlpineEnvironmentVariable
import kira.ditto.data.AppLanguage
import kira.ditto.data.AppSettings
import kira.ditto.data.AppStorageClearKind
import kira.ditto.data.AppStorageManager
import kira.ditto.data.AppStorageUsage
import kira.ditto.data.KimiSessionCronStore
import kira.ditto.data.KimiCompactReply
import kira.ditto.data.classifyKimiCompactReply
import kira.ditto.data.normalizeReasoningEffort
import kira.ditto.data.normalizeKimiPermissionMode
import kira.ditto.data.normalizeRemoteUiPermissionMode
import kira.ditto.data.AppThemeMode
import kira.ditto.data.CurrentOnboardingVersion
import kira.ditto.data.DiagnosticRedactor
import kira.ditto.data.InstalledSkill
import kira.ditto.data.InstalledPiExtension
import kira.ditto.data.PiExtensionInstallKind
import kira.ditto.data.PiExtensionCatalogEntry
import kira.ditto.data.PiDiscoveredSkillSource
import kira.ditto.data.ProviderModelCatalogClient
import kira.ditto.data.withProbedThinking
import kira.ditto.data.thinkingCatalogKey
import kira.ditto.data.LlmProviderConfig
import kira.ditto.data.StartupRouteCache
import kira.ditto.data.ModelCatalogClient
import kira.ditto.data.LocalRuntimeId
import kira.ditto.data.ProviderModelOption
import kira.ditto.data.PersistedChatState
import kira.ditto.data.PersistedChatWriteIntent
import kira.ditto.data.availableModelOptions
import kira.ditto.data.findModelOption
import kira.ditto.data.formatKnowledgeCitations
import kira.ditto.data.knowledgeCitationsFromSlices
import kira.ditto.data.KnowledgeCitation
import kira.ditto.data.PersonaKnowledgeIndexer
import kira.ditto.data.autoEnableFetchedModels
import kira.ditto.data.HostSecretStore
import kira.ditto.data.AmapPlace
import kira.ditto.data.AmapNavigation
import kira.ditto.data.AmapNavigationToolName
import kira.ditto.data.amapPlaceNavigateInChatUserText
import kira.ditto.data.McpClientManager
import kira.ditto.data.McpServerConfig
import kira.ditto.data.McpServerTestOperation
import kira.ditto.data.applyMcpSecrets
import kira.ditto.data.asLookup
import kira.ditto.data.extractInlineMcpSecrets
import kira.ditto.data.inferMcpSecretSlots
import kira.ditto.data.McpSecretSlot
import kira.ditto.data.missingMcpSecretSlots
import kira.ditto.data.normalizeSelectableModelKey
import kira.ditto.data.normalizeAsrModelKey
import kira.ditto.data.normalizeLlmInactivityReconnectTimeoutSeconds
import kira.ditto.data.normalizeLlmUserAgent
import kira.ditto.data.normalizeOldCommandHistoryRetentionHours
import kira.ditto.data.normalizeTavilyBaseUrl
import kira.ditto.data.OnboardingStarterPrompt
import kira.ditto.data.PackageProfileState
import kira.ditto.data.RootSetupIssue
import kira.ditto.data.RootSetupState
import kira.ditto.data.ScheduledTask
import kira.ditto.data.ScheduledTaskCreator
import kira.ditto.data.ScheduledTaskSchedule
import kira.ditto.data.isGuiWatchTask
import kira.ditto.data.TermuxEnvironmentVariable
import kira.ditto.data.normalizeTermuxEnvironmentVariables
import kira.ditto.data.normalizeAlpineEnvironmentVariables
import kira.ditto.data.SessionFollowUpMode
import kira.ditto.data.SessionExecutionState
import kira.ditto.data.forNonChatUi
import kira.ditto.data.uiFingerprint
import kira.ditto.data.SessionTurnEvent
import kira.ditto.data.SessionTurnOutcome
import kira.ditto.data.SessionTurnRequest
import kira.ditto.data.withRemoteFrom
import kira.ditto.data.parseChatSessions
import kira.ditto.data.parseCustomHeaders
import kira.ditto.data.parseMcpServerConfigs
import kira.ditto.data.parseProviderConfigs
import kira.ditto.data.serializeChatSessions
import kira.ditto.data.serializeMcpServerConfigs
import kira.ditto.data.serializeProviderConfigs
import kira.ditto.data.acpMcpServersFingerprint
import kira.ditto.data.buildAcpMcpServerArray
import kira.ditto.data.PhoneDeskHandoffState
import kira.ditto.data.PhoneDeskPhase
import kira.ditto.data.toJson
import kira.ditto.data.toJsonArray
import kira.ditto.data.withExplicitDefaultChatModel
import kira.ditto.data.ChatUsageStatisticsSnapshot
import kira.ditto.data.LlmUsageRecord
import kira.ditto.data.LlmUsageTotals
import kira.ditto.data.UsageDetailWindowMillis
import kira.ditto.data.LlmMessage
import kira.ditto.data.LlmTextPart
import kira.ditto.data.ProviderAuthMethod
import kira.ditto.data.pi.PiCompletionClient
import kira.ditto.data.pi.PiKernelBridge
import kira.ditto.data.pi.PiCoreSetupActivity
import kira.ditto.data.pi.PiCoreSetupPhase
import kira.ditto.data.pi.PiCoreSetupState
import kira.ditto.data.pi.PiCoreSetupUpdate
import kira.ditto.data.pi.PiProviderAuthState
import kira.ditto.data.pi.toPiCompletionResult
import kira.ditto.data.pi.toProviderPayloadJson
import kira.ditto.data.pi.toPiOAuthPrompt
import kira.ditto.data.pi.toPiProviderEnvironmentVariables
import kira.ditto.data.pi.toPiModelConfig
import kira.ditto.data.pi.toPiThinkingLevel
import kira.ditto.data.kimi.KimiAcpProtocol
import kira.ditto.data.TtsEngineRouter
import kira.ditto.data.TtsStreamMux
import kira.ditto.data.isProviderSetupValid
import kira.ditto.data.isNightlyUpdateNewer
import kira.ditto.data.isVersionNewer
import kira.ditto.data.isOnboardingComplete
import kira.ditto.data.shouldMarkOnboardingCompleted
import kira.ditto.data.shouldLaunchOnboarding
import kira.ditto.data.shouldRevealFollowUpTourCard
import kira.ditto.data.resolveDefaultChatModelKey
import kira.ditto.data.resolveDefaultTitleModelKey
import kira.ditto.data.resolveAutomaticModelKey
import kira.ditto.data.resolveModelSettings
import kira.ditto.data.resolveModelSettingsFromOptions
import kira.ditto.data.resolveStoredOrAutomaticModelKey
import kira.ditto.data.SessionTitleSystemPrompt
import kira.ditto.data.fastestSupportedReasoningEffort
import kira.ditto.data.sanitizeGeneratedSessionTitle
import kira.ditto.data.visibleUserMessageText
import kira.ditto.data.looksLikeHiddenPromptTitle
import kira.ditto.browser.collectBrowserInlineImages
import kira.ditto.data.sessionTitleFits
import kira.ditto.data.sessionTitleRewritePrompt
import kira.ditto.termux.TermuxSetupIssue
import kira.ditto.termux.TermuxSetupState
import kira.ditto.runtime.AlpineSetupProgress
import kira.ditto.runtime.AlpineTerminalLaunchSpec
import kira.ditto.runtime.AlpineSetupActivity
import kira.ditto.runtime.EverMeBindingPhase
import kira.ditto.runtime.EverMeBindingState
import kira.ditto.runtime.LocalRuntimeIssue
import kira.ditto.runtime.LocalRuntimeSetupState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.Base64
import java.util.Locale
import java.util.UUID

private const val FollowUpTourAutoOpenDelayMillis = 2_500L
// Safety valve: if the UI never reports a frame (headless task launch, instant crash),
// deferred startup work still runs rather than being stranded forever.
private const val DeferredStartupTimeoutMillis = 4_000L
// Usage backfill walks every session; entering the statistics page repeatedly must not re-run it.
private const val UsageSnapshotRefreshIntervalMillis = 30_000L
private const val AppUpdateCheckIntervalMillis = 3L * 24L * 60L * 60L * 1000L
private const val UpdateChannelNightly = "nightly"
private const val LogcatReadTimeoutSeconds = 4L
private const val MaxSetupOutputChars = 48_000
private const val MaxInlineImageAttachmentBytes = 5 * 1024 * 1024

internal fun mergeImportedPiExtensions(
    current: List<InstalledPiExtension>,
    imported: List<InstalledPiExtension>,
): List<InstalledPiExtension> =
    (current.filter { it.kind != PiExtensionInstallKind.Imported } + imported)
        .distinctBy(InstalledPiExtension::id)
        .sortedBy { it.name.lowercase(Locale.US) }

class AetherViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val runtime = application.aetherRuntime
    private val diagnosticLogger = runtime.diagnosticLogger
    private val settingsRepository = runtime.settingsRepository
    private val modKernel = runtime.modKernel
    private val chatStateStore = runtime.chatStateStore
    private val extensionsRepository = runtime.extensionsRepository
    private val personaRepository = runtime.personaRepository
    private val knowledgeIndexer = PersonaKnowledgeIndexer(
        context = application.applicationContext,
        personaRepository = personaRepository,
        usageRecorder = runtime.usageRecorder,
    )
    private val sessionPersonaIds = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val digiCrewMesh = runtime.digiCrewMesh
    private val sessionExecutionManager = runtime.sessionExecutionManager
    private val piCompletionClient: PiCompletionClient = runtime.piCompletionClient
    private val piKernelBridge: PiKernelBridge = runtime.piKernelBridge
    private val rootSetupController = runtime.rootSetupController
    private val workspaceFileBridge = runtime.workspaceFileBridge
    private val storageManager by lazy {
        AppStorageManager(
            context = getApplication(),
            alpineRuntime = runtime.alpineRuntime,
            browserHistoryStore = runtime.browserHistoryStore,
        )
    }
    private val kimiCronStore by lazy { KimiSessionCronStore(runtime.alpineRuntime) }
    private val runtimeWorkspaceFileBridge = runtime.runtimeWorkspaceFileBridge
    private val agentModeController = runtime.agentModeController.also { controller ->
        controller.loadProviderConfigs = { _uiState.value.providerConfigs }
    }
    private val skillManager = runtime.skillManager
    private val piExtensionManager = runtime.piExtensionManager
    private val aetherAppExtensionManager = runtime.aetherAppExtensionManager
    private val scheduledTaskManager = runtime.scheduledTaskManager
    private val mcpClientManager = McpClientManager(
        runtimeRouter = runtime.runtimeRouter,
        settings = AppSettings(),
        diagnosticLogger = diagnosticLogger,
        upaPluginLibrary = runtime.upaPluginLibrary,
    )
    private val appUpdateManager = AppUpdateManager(application.applicationContext)
    private var didEvaluateStartupUpdateCheck = false
    private var lastTrackedTermuxDetectedIssue: TermuxSetupIssue? = null
    private var pendingTermuxSetupSource: String? = null
    private var pendingSelectComposerAgentMode = false
    private var pendingHiddenContextText: String = ""
    private var pendingKnowledgeCitations: List<KnowledgeCitation> = emptyList()
    private var pendingNewSessionTitle: String? = null
    private var lastModelCatalogRequestKey: String = ""
    private var didInitializeStartupDraftModel = false
    private var didReceiveInitialChatState = false
    private var didHydrateStartupBrowserDesk = false
    private var lastExecutionUiFingerprint: String? = null
    private val _uiState = MutableStateFlow(AetherUiState())
    private val _transientMessages = MutableSharedFlow<UiText>(extraBufferCapacity = 4)
    private var didSweepOrphanWorkspaces = false
    private var didLoadWorkspaces = false
    private var selectSessionJob: Job? = null
    private var prefetchSessionJob: Job? = null
    private var prefetchedSessionId: String? = null

    // ── TTS playback ────────────────────────────────────────────────────────
    private var ttsRouter: TtsEngineRouter? = null
    private var ttsStreamMux: TtsStreamMux? = null
    private var lastTtsTextSnapshot: String = ""
    private var ttsTurnActive: Boolean = false
    private val _playingMessageId = MutableStateFlow<String?>(null)
    val playingMessageId: StateFlow<String?> = _playingMessageId.asStateFlow()
    private var prefetchedSession: ChatSession? = null
    private var loadOlderMessagesJob: Job? = null
    private var providerAuthJob: Job? = null
    private var everMeBindingJob: Job? = null
    private var extensionSendHookJob: Job? = null
    private var developerAlpineSetupPreviewJob: Job? = null
    private var onboardingRuntimeSetupJob: Job? = null
    private var piExtensionRefreshGeneration: Long = 0
    private var didRefreshAlpineAfterSettingsLoad = false
    private var didPruneAppStorage = false
    private var kimiWarmJob: Job? = null
    private var kimiWorkspaceWarmJob: Job? = null
    private var acpSessionSyncJob: Job? = null
    private var workspaceFileSuggestJob: Job? = null
    private var lastKimiWorkspaceWarmFingerprint: String = ""
    private val kimiWorkspaceWarmGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    private val hostSecretStore by lazy { HostSecretStore(getApplication()) }
    private var deferredMcpSecretTurn: SessionTurnRequest? = null
    private val mcpSecretPromptSkippedSessionIds = mutableSetOf<String>()
    @Volatile
    private var chatSurfaceVisible: Boolean = true

    val uiState: StateFlow<AetherUiState> = _uiState.asStateFlow()
    val transientMessages = _transientMessages.asSharedFlow()

    private val firstFrameRendered = CompletableDeferred<Unit>()
    private var lastUsageSnapshotRefreshMillis = 0L
    private var usageRefreshJob: Job? = null
    private var didResolveRouteFromSettings = false
    private var cachedStartupScreen: AppScreen? = null

    private fun applyCachedStartupRoute() {
        val shouldLaunchOnboarding =
            StartupRouteCache.peekShouldLaunchOnboarding(getApplication()) ?: return
        val screen = if (shouldLaunchOnboarding) AppScreen.Onboarding else AppScreen.Chat
        cachedStartupScreen = screen
        _uiState.update { current ->
            current.copy(currentScreen = screen, isStartupRouteResolved = true)
        }
    }

    /**
     * Work that nothing on the first screen depends on — mesh discovery, model catalogs,
     * root probing, extension scans. Holding it back keeps the startup critical path free
     * of dozens of coroutines competing for the main thread before anything is drawn.
     */
    private fun launchDeferred(block: suspend CoroutineScope.() -> Unit): Job =
        viewModelScope.launch(Dispatchers.Default) {
            withTimeoutOrNull(DeferredStartupTimeoutMillis) { firstFrameRendered.await() }
            block()
        }

    fun notifyFirstFrameRendered() {
        firstFrameRendered.complete(Unit)
    }

    init {
        applyCachedStartupRoute()
        registerCoreModServices()
        refreshTermuxSetup()
        launchDeferred { refreshRootSetup() }
        launchDeferred { refreshImportedPiExtensions() }
        // peekPersistedEverMeBinding() reads a file; it used to do so on the main thread
        // inside the ViewModel constructor.
        viewModelScope.launch(Dispatchers.IO) {
            val binding = runtime.alpineRuntime.peekPersistedEverMeBinding()
            _uiState.update { current -> current.copy(everMeBindingState = binding) }
        }
        launchDeferred {
            val plugins = runCatching { runtime.upaPluginLibrary.list() }.getOrDefault(emptyList())
            _uiState.update { it.copy(installedUpaPlugins = plugins) }
        }
        viewModelScope.launch {
            settingsRepository.initializeLanguageIfNeeded()
            settingsRepository.settings.collect { settings ->
                if (settings.privacyPolicyAccepted) {
                    runtime.initializePostHog()
                }
                val authoritativeScreen = if (settings.shouldLaunchOnboarding()) {
                    AppScreen.Onboarding
                } else {
                    AppScreen.Chat
                }
                if (!didResolveRouteFromSettings) {
                    didResolveRouteFromSettings = true
                    StartupRouteCache.remember(getApplication(), settings.shouldLaunchOnboarding())
                }
                _uiState.update { current ->
                    // The cached route may already have put us on a screen. Only correct it
                    // when the guess was wrong and the user has not navigated since.
                    val shouldAdoptAuthoritativeScreen = !current.isStartupRouteResolved ||
                        (current.currentScreen == cachedStartupScreen && current.currentScreen != authoritativeScreen)
                    if (shouldAdoptAuthoritativeScreen) {
                        current.copy(
                            settings = settings,
                            currentScreen = authoritativeScreen,
                            isStartupRouteResolved = true,
                            isOnboardingReplay = false,
                            onboardingStep = OnboardingStep.Landing,
                            onboardingReturnScreen = AppScreen.Chat,
                        )
                    } else {
                        current.copy(settings = settings, isStartupRouteResolved = true)
                    }
                }
                initializeStartupDraftModelIfReady()
                runtime.alpineRuntime.setEnvironmentVariables(settings.alpineEnvironmentVariables)
                runtime.alpineRuntime.setWebSearchBackend(
                    settings.tavilyApiKey,
                    settings.tavilyBaseUrl,
                )
                runtime.alpineRuntime.setUpaProxyBase(settings.upaProxyBaseUrl)
                runtime.alpineRuntime.setKimiPermissionMode(settings.kimiPermissionMode)
                if (!didRefreshAlpineAfterSettingsLoad) {
                    didRefreshAlpineAfterSettingsLoad = true
                    refreshAlpineSetup(startPiIfReady = false)
                }
                if (!didEvaluateStartupUpdateCheck && settings.privacyPolicyAccepted) {
                    didEvaluateStartupUpdateCheck = true
                    maybeCheckForUpdates(settings)
                }
                agentModeController.refreshAuthorization(settings)
                maybeSweepOrphanWorkspaces()
                maybeLoadWorkspaces()
                if (!didPruneAppStorage) {
                    didPruneAppStorage = true
                    refreshStorageUsage()
                }
            }
        }

        viewModelScope.launch {
            var didReceiveChatState = false
            chatStateStore.state.collect { persisted ->
                if (!didReceiveChatState && persisted.sessions.isEmpty() && persisted.currentSessionId == DraftSessionId) {
                    didReceiveChatState = true
                    didReceiveInitialChatState = true
                    initializeStartupDraftModelIfReady()
                    return@collect
                }
                didReceiveChatState = true
                val hydratedPersisted = hydrateCurrentSessionMessages(persisted)
                _uiState.update { current ->
                    val currentSessionId = hydratedPersisted.currentSessionId.ifBlank { DraftSessionId }
                    val currentExecution = current.sessionExecutionStates[currentSessionId]
                    current.withSessionWindows(
                        sessions = hydratedPersisted.sessions.withMessagesOnlyForSession(currentSessionId),
                        currentSessionId = currentSessionId,
                    ).copy(
                        isSending = currentExecution?.isRunning == true,
                        pendingResponseSessionId = currentExecution?.sessionId,
                        pendingToolInvocations = currentExecution?.pendingToolInvocations.orEmpty(),
                        pendingResponseBlocks = currentExecution?.pendingResponseBlocks.orEmpty(),
                        pendingAssistantText = currentExecution?.pendingAssistantText.orEmpty(),
                        pendingStatusText = currentExecution?.pendingStatusText.orEmpty(),
                        pendingStatusDetail = currentExecution?.pendingStatusDetail.orEmpty(),
                    )
                }
                didReceiveInitialChatState = true
                initializeStartupDraftModelIfReady()
                if (!didHydrateStartupBrowserDesk) {
                    didHydrateStartupBrowserDesk = true
                    val sessionId = hydratedPersisted.currentSessionId.ifBlank { DraftSessionId }
                    if (sessionId != DraftSessionId) {
                        val messages = hydratedPersisted.sessions
                            .firstOrNull { it.id == sessionId }
                            ?.messages
                            .orEmpty()
                        restoreBrowserDeskForSession(sessionId, messages)
                    }
                }
            }
        }

        viewModelScope.launch {
            sessionExecutionManager.executionStates.collect { executionStates ->
                _uiState.update { current -> presentExecutionStates(executionStates, current) }
                // TTS streaming: feed text deltas to TtsStreamMux
                val settings = _uiState.value.settings
                if (settings.ttsEnabled && settings.defaultTtsModelKey.isNotBlank()) {
                    val currentSessionId = _uiState.value.currentSessionId
                    val currentExecution = executionStates[currentSessionId]
                    val currentText = currentExecution?.pendingAssistantText.orEmpty()
                    if (currentExecution?.isRunning == true) {
                        if (!ttsTurnActive) {
                            startTtsStreaming(settings)
                            ttsTurnActive = true
                        }
                        when {
                            currentText.length > lastTtsTextSnapshot.length -> {
                                ttsStreamMux?.feed(currentText.substring(lastTtsTextSnapshot.length))
                                lastTtsTextSnapshot = currentText
                            }
                            currentText.length < lastTtsTextSnapshot.length -> {
                                lastTtsTextSnapshot = currentText
                                if (currentText.isNotEmpty()) ttsStreamMux?.feed(currentText)
                            }
                        }
                    } else if (ttsTurnActive) {
                        ttsStreamMux?.flush()
                        ttsTurnActive = false
                    }
                }
            }
        }

        viewModelScope.launch(Dispatchers.Default) {
            sessionExecutionManager.agentSlashCommands.collect { commands ->
                _uiState.update { current ->
                    current.copy(
                        agentSlashCommands = commands.map { command ->
                            SlashCommandSuggestion(
                                command = "/${command.name}",
                                description = command.description,
                                argumentHint = command.argumentHint,
                            )
                        },
                    )
                }
            }
        }

        viewModelScope.launch(Dispatchers.Default) {
            runtime.kimiInteractionController.pendingPermissions.collect { requests ->
                _uiState.update { current -> current.copy(pendingPermissionRequests = requests) }
            }
        }

        viewModelScope.launch(Dispatchers.Default) {
            runtime.kimiInteractionController.pendingElicitations.collect { requests ->
                _uiState.update { current -> current.copy(pendingElicitationRequests = requests) }
            }
        }

        viewModelScope.launch {
            _uiState
                .map { it.currentScreen to it.currentSessionId }
                .distinctUntilChanged()
                .collect {
                    _uiState.update { current ->
                        presentExecutionStates(sessionExecutionManager.executionStates.value, current)
                    }
                }
        }

        viewModelScope.launch {
            sessionExecutionManager.turnEvents.collect { event ->
                handleTurnEvent(event)
            }
        }

        // Prunes skill/MCP selections across every session on each emission, which is
        // linear in session count and has no business running on the main thread.
        viewModelScope.launch(Dispatchers.Default) {
            extensionsRepository.extensionState.collect { extensionState ->
                var didPruneSelections = false
                val enabledSkillIds = extensionState.installedSkills
                    .filter { it.isEnabled }
                    .map { it.id }
                    .toSet()
                val mcpServers = mergedMcpServers(extensionState.mcpServers)
                if (extensionState.mcpServers.none { it.id == kira.ditto.data.GmailMcp.PluginId }) {
                    extensionsRepository.upsertMcpServer(kira.ditto.data.GmailMcp.mcpServerConfig())
                }
                if (extensionState.mcpServers.none { it.id == kira.ditto.data.SpotifyMcp.PluginId }) {
                    extensionsRepository.upsertMcpServer(kira.ditto.data.SpotifyMcp.mcpServerConfig())
                }
                if (extensionState.mcpServers.none { it.id == kira.ditto.data.AmapMcp.PluginId }) {
                    extensionsRepository.upsertMcpServer(kira.ditto.data.AmapMcp.mcpServerConfig())
                }
                if (extensionState.mcpServers.none { it.id == kira.ditto.data.GithubMcp.PluginId }) {
                    extensionsRepository.upsertMcpServer(kira.ditto.data.GithubMcp.mcpServerConfig())
                }
                if (extensionState.mcpServers.none { it.id == kira.ditto.data.HuggingFaceMcp.PluginId }) {
                    extensionsRepository.upsertMcpServer(kira.ditto.data.HuggingFaceMcp.mcpServerConfig())
                }
                val enabledMcpServerIds = mcpServers
                    .filter { it.isEnabled }
                    .map { it.id }
                    .toSet()
                _uiState.update { current ->
                    val updatedSessions = current.sessions.map { session ->
                        val updatedSelectedSkillIds = session.selectedSkillIds.filter(enabledSkillIds::contains)
                        val updatedActiveSkills = session.activeSkills.filter { activeSkill ->
                            updatedSelectedSkillIds.contains(activeSkill.skillId)
                        }
                        val updatedActiveMcpServerIds = session.activeMcpServerIds.filter { serverId ->
                            enabledMcpServerIds.contains(serverId) &&
                                !kira.ditto.upa.isUpaMcpServerId(serverId)
                        }
                        if (
                            updatedSelectedSkillIds != session.selectedSkillIds ||
                            updatedActiveSkills != session.activeSkills ||
                            updatedActiveMcpServerIds != session.activeMcpServerIds
                        ) {
                            didPruneSelections = true
                            session.copy(
                                selectedSkillIds = updatedSelectedSkillIds,
                                activeSkills = updatedActiveSkills,
                                activeMcpServerIds = updatedActiveMcpServerIds,
                            )
                        } else {
                            session
                        }
                    }
                    val updatedDraftSelectedSkillIds = current.draftSelectedSkillIds.filter(enabledSkillIds::contains)
                    val updatedDraftSelectedMcpServerIds = current.draftSelectedMcpServerIds.filter { serverId ->
                        enabledMcpServerIds.contains(serverId) &&
                            !kira.ditto.upa.isUpaMcpServerId(serverId)
                    }
                    if (
                        updatedDraftSelectedSkillIds != current.draftSelectedSkillIds ||
                        updatedDraftSelectedMcpServerIds != current.draftSelectedMcpServerIds
                    ) {
                        didPruneSelections = true
                    }
                    current.copy(
                        sessions = updatedSessions,
                        draftSelectedSkillIds = updatedDraftSelectedSkillIds,
                        draftSelectedMcpServerIds = updatedDraftSelectedMcpServerIds,
                        installedSkills = extensionState.installedSkills,
                        mcpServers = mcpServers,
                    )
                }
                if (didPruneSelections) {
                    persistPrunedSessionSelections(
                        enabledSkillIds = enabledSkillIds,
                        enabledMcpServerIds = enabledMcpServerIds,
                    )
                }
                prewarmKimiWorkspaceIfPossible()
            }
        }

        launchDeferred {
            personaRepository.ensureNodeId()
            personaRepository.store.collect { store ->
                sessionPersonaIds.entries.removeIf { (_, personaId) ->
                    store.personas.none { it.id == personaId }
                }
                store.personas.forEach { persona ->
                    val sessionId = persona.activeSessionId.trim()
                    if (sessionId.isNotBlank()) {
                        sessionPersonaIds[sessionId] = persona.id
                    }
                }
                _uiState.update { current ->
                    val boundPersonaId = if (current.currentSessionId == DraftSessionId) {
                        current.draftPersonaId.ifBlank { current.activePersonaId }
                    } else {
                        sessionPersonaIds[current.currentSessionId].orEmpty()
                            .ifBlank { current.activePersonaId }
                    }
                    current.copy(
                        personas = store.personas,
                        digiCrews = store.crews,
                        activePersonaId = boundPersonaId,
                    )
                }
                val ownerLabel = _uiState.value.everMeBindingState.email
                    .substringBefore("@")
                    .ifBlank { "Peer ${store.nodeId.take(4)}" }
                digiCrewMesh.bind(
                    nodeId = store.nodeId,
                    ownerLabel = ownerLabel,
                    crews = store.crews,
                    localPersonas = store.personas,
                )
            }
        }
        launchDeferred {
            digiCrewMesh.presence.collect { peers ->
                _uiState.update { it.copy(digiCrewPeers = peers) }
                persistDiscoveredRemotePersonas(peers)
            }
        }
        launchDeferred {
            digiCrewMesh.status.collect { status ->
                _uiState.update { it.copy(digiCrewMeshStatus = status) }
            }
        }
        launchDeferred {
            digiCrewMesh.events.collect { event ->
                if (event is kira.ditto.data.DigiCrewMeshEvent.Chat) {
                    ingestDigiCrewChat(event)
                }
            }
        }

        viewModelScope.launch(Dispatchers.Default) {
            settingsRepository.providerConfigs.collect { configs ->
                _uiState.update { current -> current.copy(providerConfigs = configs) }
                initializeStartupDraftModelIfReady()
                refreshModelCatalogInfo(configs)
            }
        }
        viewModelScope.launch {
            scheduledTaskManager.scheduledTasks.collect { tasks ->
                val merged = withContext(Dispatchers.IO) {
                    val disk = runCatching { kimiCronStore.mergeWithDisk(tasks) }.getOrDefault(tasks)
                    persistDiscoveredCronTasks(stored = tasks, merged = disk)
                    disk
                }
                _uiState.update { current -> current.copy(scheduledTasks = merged) }
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            agentModeController.displayState.collect { displayState ->
                _uiState.update { current ->
                    current.copy(
                        agentModeDisplayState = displayState,
                        phoneDeskHandoff = when {
                            displayState.listening -> current.phoneDeskHandoff.copy(
                                phase = PhoneDeskPhase.Listening,
                            )
                            current.phoneDeskHandoff.phase == PhoneDeskPhase.Listening ->
                                current.phoneDeskHandoff.copy(phase = PhoneDeskPhase.Working)
                            else -> current.phoneDeskHandoff
                        },
                    )
                }
            }
        }
        launchDeferred {
            runtime.phoneSettlementRuntime.uiState.collect { settlement ->
                _uiState.update { current -> current.copy(phoneSettlement = settlement) }
            }
        }
        launchDeferred {
            runtime.agentModeLearningRuntime.reviewingEverMe.collect { reviewing ->
                _uiState.update { current -> current.copy(agentModeReviewingEverMe = reviewing) }
                if (reviewing) {
                    delay(8_000)
                    runtime.agentModeLearningRuntime.clearEverMeReview()
                }
            }
        }
        launchDeferred {
            runtime.agentModeLearningRuntime.liveGuiStepsByToolCall.collect { steps ->
                _uiState.update { current -> current.copy(agentModeLiveGuiStepsByToolCall = steps) }
                val sessionId = _uiState.value.currentSessionId
                if (sessionId.isNotBlank() && steps.isNotEmpty()) {
                    sessionExecutionManager.syncLiveGuiSteps(sessionId, steps)
                }
            }
        }
        launchDeferred {
            kira.ditto.browser.AetherBrowserRuntime.chromeStateFlow.collect { chrome ->
                _uiState.update { current ->
                    current.copy(
                        chromeDisplayState = current.chromeDisplayState.copy(
                            isActive = chrome.isActive,
                            status = chrome.status,
                            latestPreviewPath = chrome.url,
                        ),
                    )
                }
            }
        }
        launchDeferred {
            kira.ditto.browser.BrowserDesk.state.collect { desk ->
                _uiState.update { current -> current.copy(browserDeskState = desk) }
                if (!currentComposerAgentModeSelected()) {
                    rememberBrowserDeskSourcesOnCurrentUser(
                        sources = desk.sources,
                        images = kira.ditto.browser.BrowserTopicGraph.snapshot()
                            .flatMap { tab ->
                                tab.images.map { image ->
                                    BrowserInlineImage(
                                        url = image.url,
                                        alt = image.alt,
                                        topicId = tab.topicId,
                                    )
                                }
                            }
                            .ifEmpty {
                                collectBrowserInlineImages(desk, curatedOnly = true).map { image ->
                                    BrowserInlineImage(url = image.url, alt = image.alt)
                                }
                            },
                    )
                    persistBrowserDeskOntoCurrentSession()
                }
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            agentModeController.authorizationState.collect { authorizationState ->
                _uiState.update { current -> current.copy(agentModeAuthorizationState = authorizationState) }
            }
        }
    }

    private fun refreshModelCatalogInfo(configs: List<LlmProviderConfig>) {
        val options = configs.availableModelOptions()
        val requestKey = options.joinToString("|") { "${it.key}:${it.fullLabel}" }
        if (requestKey == lastModelCatalogRequestKey) return
        lastModelCatalogRequestKey = requestKey
        if (options.isEmpty()) {
            _uiState.update { current -> current.copy(modelCatalogInfo = emptyMap()) }
            return
        }
        viewModelScope.launch {
            if (_uiState.value.modelCatalogInfo.isEmpty()) {
                val cached = settingsRepository.loadModelCatalogCache()
                    .filterKeys(options.mapTo(mutableSetOf(), ProviderModelOption::key)::contains)
                if (cached.isNotEmpty() && requestKey == lastModelCatalogRequestKey) {
                    _uiState.update { current -> current.copy(modelCatalogInfo = cached) }
                }
            }
            val thinkingCacheKeys = options.mapTo(mutableSetOf()) { option ->
                thinkingCatalogKey(option.piProviderId, option.modelId)
            }
            val cachedThinkingLevels = settingsRepository.loadThinkingCatalogCache()
                .filterKeys(thinkingCacheKeys::contains)
            val cachedThinkingLevelMaps = settingsRepository.loadThinkingLevelMapsCache()
                .filterKeys(thinkingCacheKeys::contains)
            val cachedReasoningModels = settingsRepository.loadReasoningModelsCache()
                .filterTo(mutableSetOf(), thinkingCacheKeys::contains)
            if (cachedThinkingLevels.isNotEmpty() && requestKey == lastModelCatalogRequestKey) {
                _uiState.update { current ->
                    current.copy(
                        thinkingLevelsByProviderModel = current.thinkingLevelsByProviderModel + cachedThinkingLevels,
                        thinkingLevelClampsByProviderModel = current.thinkingLevelClampsByProviderModel + cachedThinkingLevelMaps,
                        reasoningModels = current.reasoningModels + cachedReasoningModels,
                    )
                }
            }
            val modelInfo = ModelCatalogClient.fetchModelInfo(options)
            if (modelInfo.isNotEmpty()) {
                settingsRepository.saveModelCatalogCache(modelInfo)
            }
            if (modelInfo.isNotEmpty() && requestKey == lastModelCatalogRequestKey) {
                _uiState.update { current ->
                    current.copy(
                        modelCatalogInfo = current.modelCatalogInfo.filterKeys { it.startsWith("remote:") } +
                            modelInfo,
                    )
                }
            }

            // Populate the effort cache during startup as well as when the model
            // picker is opened. Cached values are already applied above, so an
            // unavailable network never delays the initial picker state.
            val catalogResult = ProviderModelCatalogClient.fetchPublicThinkingCatalog(options)
            var mergedCatalog = catalogResult
            val selectedOption = selectedChatModelOption(_uiState.value, options)
            if (selectedOption != null) {
                val selectedKey = thinkingCatalogKey(selectedOption.piProviderId, selectedOption.modelId)
                val alreadyResolved = selectedKey in mergedCatalog.levelsByProviderModel ||
                    selectedKey in _uiState.value.thinkingLevelsByProviderModel
                if (!alreadyResolved) {
                    mergedCatalog = mergedCatalog.withProbedThinking(
                        selectedKey,
                        ProviderModelCatalogClient.probeProviderThinkingLevels(selectedOption),
                    )
                }
            }
            if (
                mergedCatalog.levelsByProviderModel.isNotEmpty() ||
                mergedCatalog.reasoningModels.isNotEmpty()
            ) {
                settingsRepository.saveThinkingCatalogCache(
                    mergedCatalog.levelsByProviderModel,
                    mergedCatalog.levelMapsByProviderModel,
                    mergedCatalog.reasoningModels,
                )
                if (requestKey == lastModelCatalogRequestKey) {
                    _uiState.update { state ->
                        state.copy(
                            thinkingLevelsByProviderModel =
                                state.thinkingLevelsByProviderModel + mergedCatalog.levelsByProviderModel,
                            thinkingLevelClampsByProviderModel =
                                (state.thinkingLevelClampsByProviderModel -
                                    mergedCatalog.levelsByProviderModel.keys) +
                                    mergedCatalog.levelMapsByProviderModel,
                            reasoningModels =
                                (state.reasoningModels - mergedCatalog.levelsByProviderModel.keys) +
                                    mergedCatalog.reasoningModels,
                        )
                    }
                }
            }
        }
    }

    private fun selectedChatModelOption(
        current: AetherUiState,
        options: List<ProviderModelOption>,
    ): ProviderModelOption? {
        val selectedModelKey = current.sessions
            .firstOrNull { it.id == current.currentSessionId }
            ?.selectedModelKey
            ?.takeIf(String::isNotBlank)
            ?: current.draftSelectedModelKey.takeIf(String::isNotBlank)
            ?: resolveDefaultChatModelKey(current.settings, current.providerConfigs)
        return options.firstOrNull { it.key == selectedModelKey }
    }

    private fun resolveSessionModelSettings(
        state: AetherUiState,
        session: ChatSession?,
        preferredModelKey: String,
    ): AppSettings {
        val options = state.chatModelOptions(session)
        val remoteFallback = session?.remoteMachineId
            ?.takeIf(String::isNotBlank)
            ?.let { state.remoteDefaultModelKeyByMachineId[it].orEmpty() }
            .orEmpty()
        return resolveModelSettingsFromOptions(
            baseSettings = state.settings,
            options = options,
            preferredModelKey = preferredModelKey,
            fallbackModelKey = remoteFallback.ifBlank {
                resolveDefaultChatModelKey(state.settings, state.providerConfigs)
            },
        ).let { resolved ->
            val machineId = session?.remoteMachineId?.takeIf { it.isNotBlank() } ?: return@let resolved
            val remoteMode = state.sessionExecutionStates[session.id]?.agentConfig?.modeId
                ?: state.remotePermissionModeByMachineId[machineId]
            resolved.copy(
                kimiPermissionMode = normalizeRemoteUiPermissionMode(remoteMode),
            )
        }
    }

    fun acceptPrivacyPolicy() {
        viewModelScope.launch {
            settingsRepository.updatePrivacyPolicyAccepted(true)
            runtime.initializePostHog()
        }
    }

    fun refreshTermuxSetup() {
        _uiState.update { current ->
            current.copy(
                termuxSetupState = TermuxSetupState(
                    issue = TermuxSetupIssue.NotInstalled,
                    detail = "Termux App is no longer used. Local tools run in Alpine.",
                ),
            )
        }
    }

    fun refreshRootSetup() {
        viewModelScope.launch {
            val inspectedRootState = rootSetupController.inspect()
            _uiState.update { current ->
                val rootState = if (current.rootSetupState.isReady && inspectedRootState.rootAvailable) {
                    current.rootSetupState.copy(
                        rootAvailable = true,
                        suPath = inspectedRootState.suPath,
                        lastUpdatedMillis = inspectedRootState.lastUpdatedMillis,
                    )
                } else {
                    inspectedRootState
                }
                current.copy(rootSetupState = rootState)
            }
        }
    }

    fun configureLocalAccessWithRoot() {
        val currentRootState = _uiState.value.rootSetupState
        if (currentRootState.isRunning) return
        trackTermuxSetupStarted(source = "root_setup")
        trackPermissionRequested(
            permission = "root_su",
            source = "root_setup",
        )
        _uiState.update { current ->
            current.copy(
                rootSetupState = RootSetupState(
                    issue = RootSetupIssue.Running,
                    detail = "Root access is available. Preparing Root Agent Mode...",
                    rootAvailable = currentRootState.rootAvailable,
                    suPath = currentRootState.suPath,
                    lastUpdatedMillis = System.currentTimeMillis(),
                )
            )
        }
        viewModelScope.launch {
            val rootState = rootSetupController.configureLocalAccess()
            trackPermissionResult(
                permission = "root_su",
                granted = rootState.isReady,
                source = "root_setup",
                result = rootState.issue.name.lowercase(),
            )
            if (rootState.isReady) {
                val settings = _uiState.value.settings
                val updatedSettings = settings.withRuntimeEnabled(LocalRuntimeId.Alpine).copy(
                    alpineSetupCompleted = true,
                )
                settingsRepository.updateSettings(
                    updatedSettings
                )
                agentModeController.refreshAuthorization(updatedSettings)
            }
            _uiState.update { current ->
                current.copy(rootSetupState = rootState)
            }
            emitTransientMessage(
                if (rootState.isReady) {
                    uiString(R.string.message_root_setup_completed)
                } else {
                    uiString(R.string.message_root_setup_failed, rootState.detail.ifBlank { rootState.issue.name })
                }
            )
        }
    }

    fun startRootSetupFromSettings(returnPage: RootSetupProgressReturnPage) {
        _uiState.update { current ->
            current.copy(
                currentScreen = AppScreen.Settings,
                rootSetupProgressReturnPage = returnPage,
            )
        }
        configureLocalAccessWithRoot()
    }

    fun dismissRootSetupProgress() {
        _uiState.update { current ->
            current.copy(rootSetupProgressReturnPage = null)
        }
    }

    fun playOnboardingRuntimeSetup() {
        if (_uiState.value.developerAlpineSetupPreviewState != null) {
            restartDeveloperAlpineSetupPreview()
            return
        }
        onboardingRuntimeSetupJob?.cancel()
        onboardingRuntimeSetupJob = viewModelScope.launch {
            _uiState.update { current ->
                current.copy(
                    piCoreSetupState = PiCoreSetupState(
                        isChecking = true,
                        isReady = false,
                        phase = PiCoreSetupPhase.CheckingAlpine,
                        activity = PiCoreSetupActivity.None,
                        bytesPerSecond = 0L,
                        output = "Starting agent runtime setup...\n",
                    ),
                )
            }
            val minimumAlpineDwell = async { delay(700L) }
            val setupState = withContext(Dispatchers.IO) {
                runtime.alpineRuntime.initialize { progress ->
                    applyPiCoreSetupUpdate(
                        PiCoreSetupUpdate(
                            phase = PiCoreSetupPhase.CheckingAlpine,
                            activity = when (progress.activity) {
                                AlpineSetupActivity.Extracting -> PiCoreSetupActivity.Extracting
                                AlpineSetupActivity.Downloading -> PiCoreSetupActivity.Downloading
                                AlpineSetupActivity.Installing -> PiCoreSetupActivity.None
                                AlpineSetupActivity.None -> PiCoreSetupActivity.None
                            },
                            bytesPerSecond = progress.bytesPerSecond,
                            output = progress.output,
                        )
                    )
                }
            }
            minimumAlpineDwell.await()
            _uiState.update { current -> current.copy(alpineSetupState = setupState) }
            if (!setupState.isReady) {
                _uiState.update { current ->
                    current.copy(
                        piCoreSetupState = current.piCoreSetupState.copy(
                            isChecking = false,
                            isReady = false,
                            phase = PiCoreSetupPhase.Failed,
                            failedAtPhase = PiCoreSetupPhase.CheckingAlpine,
                            detail = setupState.detail.ifBlank {
                                "Initialize Alpine before starting the agent runtime."
                            },
                            activity = PiCoreSetupActivity.None,
                            bytesPerSecond = 0L,
                            output = appendSetupOutput(
                                current.piCoreSetupState.output,
                                "Setup failed: ${setupState.detail}\n",
                            ),
                        ),
                    )
                }
                return@launch
            }
            val storedSettings = _uiState.value.settings
            val settings = storedSettings.copy(
                alpineSetupCompleted = true,
                enabledRuntimeIds = storedSettings.enabledRuntimeIds + LocalRuntimeId.Alpine,
            )
            if (settings != storedSettings) settingsRepository.updateSettings(settings)
            refreshEverMeBinding()
            val remainingPhases = listOf(
                PiCoreSetupPhase.CheckingNode to 700L,
                PiCoreSetupPhase.PreparingBridge to 550L,
                PiCoreSetupPhase.StartingBridge to 550L,
                PiCoreSetupPhase.VerifyingBridge to 700L,
            )
            val pingDeferred = async {
                runCatching {
                    withContext(Dispatchers.IO) {
                        runtime.piKernelBridge.ping()
                    }
                }
            }
            for ((phase, dwell) in remainingPhases) {
                _uiState.update { current ->
                    current.copy(
                        piCoreSetupState = current.piCoreSetupState.copy(
                            isChecking = true,
                            isReady = false,
                            phase = phase,
                            activity = PiCoreSetupActivity.None,
                            bytesPerSecond = 0L,
                            output = appendSetupOutput(
                                current.piCoreSetupState.output,
                                when (phase) {
                                    PiCoreSetupPhase.CheckingNode -> "Checking node --version...\n"
                                    PiCoreSetupPhase.PreparingBridge -> "Preparing the AI engine bridge...\n"
                                    PiCoreSetupPhase.StartingBridge -> "Starting node bridge.mjs...\n"
                                    PiCoreSetupPhase.VerifyingBridge -> "Verifying bridge response...\n"
                                    else -> ""
                                },
                            ),
                        ),
                    )
                }
                delay(dwell)
            }
            pingDeferred.await().fold(
                onSuccess = { payload ->
                    _uiState.update { current ->
                        current.copy(
                            piCoreSetupState = PiCoreSetupState(
                                isChecking = false,
                                isReady = true,
                                phase = PiCoreSetupPhase.Ready,
                                nodeVersion = payload.optString("node_version"),
                                bridgeVersion = payload.optString("bridge_version"),
                                output = appendSetupOutput(
                                    current.piCoreSetupState.output,
                                    "AI engine setup complete.\n",
                                ),
                            ),
                        )
                    }
                    syncPiDiscoveredSkills()
                    warmKimiEngineIfPossible()
                },
                onFailure = { throwable ->
                    if (throwable is CancellationException) throw throwable
                    _uiState.update { current ->
                        current.copy(
                            piCoreSetupState = PiCoreSetupState(
                                phase = PiCoreSetupPhase.Failed,
                                failedAtPhase = current.piCoreSetupState.phase,
                                detail = throwable.userFacingMessage(),
                                output = appendSetupOutput(
                                    current.piCoreSetupState.output,
                                    "Setup failed: ${throwable.userFacingMessage()}\n",
                                ),
                            ),
                        )
                    }
                },
            )
        }
    }

    fun refreshAlpineSetup(startPiIfReady: Boolean = false) {
        viewModelScope.launch {
            val setupState = withContext(Dispatchers.IO) {
                // Shares the single provisioning pass started by AetherAppRuntime instead of
                // installing the rootfs and bundled Kimi Code CLI a second time.
                runtime.ensureAlpineInitialized()
            }
            _uiState.update { current ->
                current.copy(
                    alpineSetupState = setupState,
                    piCoreSetupState = if (setupState.isReady) {
                        current.piCoreSetupState.copy(
                            isChecking = false,
                            activity = PiCoreSetupActivity.None,
                            bytesPerSecond = 0L,
                        )
                    } else {
                        current.piCoreSetupState
                    },
                )
            }
            if (setupState.isReady) {
                val storedSettings = _uiState.value.settings
                val settings = storedSettings.copy(
                    alpineSetupCompleted = true,
                    enabledRuntimeIds = storedSettings.enabledRuntimeIds + LocalRuntimeId.Alpine,
                )
                if (settings != storedSettings) settingsRepository.updateSettings(settings)
                refreshEverMeBinding()
                warmKimiEngineIfPossible()
                syncAcpSessionsIfPossible()
                refreshWorkspaceFileSuggestions()
                val verifiedProfiles = withContext(Dispatchers.IO) {
                    settings.alpinePackageProfiles.mapValues { (profileId, profileState) ->
                        if (
                            profileState.installed &&
                            !runtime.alpineRuntime.isPackageProfileInstalled(profileId)
                        ) {
                            profileState.copy(
                                installed = false,
                                installedAtMillis = 0L,
                                lastError = "",
                            )
                        } else {
                            profileState
                        }
                    }
                }
                if (verifiedProfiles != settings.alpinePackageProfiles) {
                    settingsRepository.updateSettings(
                        settings.copy(alpinePackageProfiles = verifiedProfiles)
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        piCoreSetupState = PiCoreSetupState(
                            detail = "Initialize Alpine before starting the agent runtime.",
                        )
                    )
                }
            }
        }
    }

    private fun warmKimiEngineIfPossible() {
        val snapshot = _uiState.value
        if (!snapshot.alpineSetupState.isReady) return
        if (snapshot.settings.apiKey.isBlank() || snapshot.settings.modelId.isBlank()) return
        if (kimiWarmJob?.isActive == true) return
        kimiWarmJob = viewModelScope.launch(Dispatchers.IO) {
            val modelKey = thinkingCatalogKey(snapshot.settings.piProviderId, snapshot.settings.modelId)
            val thinkingLevelMap = snapshot.thinkingLevelClampsByProviderModel[modelKey].orEmpty()
            val modelConfig = snapshot.settings.toPiModelConfig(
                thinkingLevelMap = thinkingLevelMap,
                isReasoningModel = modelKey in snapshot.reasoningModels,
            ).toJson()
            runtime.alpineRuntime.warmKimiEngine(modelConfig)
            if (kimiWorkspaceWarmJob?.isActive != true) {
                prewarmKimiWorkspaceIfPossible()
            }
        }
    }

    private fun prewarmKimiWorkspaceIfPossible(forceRestart: Boolean = false) {
        val snapshot = _uiState.value
        if (!snapshot.alpineSetupState.isReady) return
        if (snapshot.settings.apiKey.isBlank() || snapshot.settings.modelId.isBlank()) return
        if (!forceRestart && kimiWorkspaceWarmJob?.isActive == true) return
        val generation = if (forceRestart) {
            kimiWorkspaceWarmGeneration.incrementAndGet()
        } else {
            kimiWorkspaceWarmGeneration.get()
        }
        if (forceRestart) {
            kimiWorkspaceWarmJob?.cancel()
        }
        kimiWorkspaceWarmJob = viewModelScope.launch(Dispatchers.IO) {
            val sessionId = withContext(Dispatchers.Main.immediate) {
                snapshot.currentSessionId.takeIf { it != DraftSessionId } ?: ensureDraftWorkspaceId()
            }
            if (generation != kimiWorkspaceWarmGeneration.get()) return@launch
            val selected = if (snapshot.currentSessionId == DraftSessionId) {
                snapshot.draftSelectedMcpServerIds.toSet()
            } else {
                snapshot.sessions.firstOrNull { it.id == snapshot.currentSessionId }
                    ?.activeMcpServerIds
                    .orEmpty()
                    .toSet()
            }
            // Agent mode no longer selects which servers are declared: toggling it used to change
            // the tool list, and through the fingerprint that recreated the ACP session.
            val mcpServers = buildAcpMcpServerArray(
                servers = snapshot.mcpServers,
                selectedIds = selected,
                learningSessionId = sessionId,
                secrets = hostSecretStore.asLookup(),
            )
            val fingerprint = "$sessionId|${acpMcpServersFingerprint(mcpServers)}"
            if (generation != kimiWorkspaceWarmGeneration.get()) return@launch
            if (fingerprint == lastKimiWorkspaceWarmFingerprint) return@launch
            val modelKey = thinkingCatalogKey(snapshot.settings.piProviderId, snapshot.settings.modelId)
            val thinkingLevelMap = snapshot.thinkingLevelClampsByProviderModel[modelKey].orEmpty()
            val modelConfig = snapshot.settings.toPiModelConfig(
                thinkingLevelMap = thinkingLevelMap,
                isReasoningModel = modelKey in snapshot.reasoningModels,
            ).toJson()
            val cwd = workspaceFileBridge.workspaceDirectory(
                snapshot.sessions.firstOrNull { it.id == sessionId }?.workspaceId
                    ?: kira.ditto.data.chatdb.DefaultWorkspaceId,
            )
            runtime.alpineRuntime.prewarmKimiSession(
                aetherSessionId = sessionId,
                cwd = cwd,
                modelConfig = modelConfig,
                mcpServers = mcpServers,
                thinking = snapshot.settings.toPiThinkingLevel(),
                permissionMode = snapshot.settings.kimiPermissionMode,
            )
            if (generation != kimiWorkspaceWarmGeneration.get()) return@launch
            lastKimiWorkspaceWarmFingerprint = fingerprint
        }
    }

    fun refreshEverMeBinding() {
        if (!_uiState.value.alpineSetupState.isReady) return
        everMeBindingJob?.cancel()
        everMeBindingJob = viewModelScope.launch {
            _uiState.update { current ->
                current.copy(
                    everMeBindingState = current.everMeBindingState.copy(
                        phase = EverMeBindingPhase.Starting,
                        detail = "Checking EverMe connection...",
                    )
                )
            }
            val state = withContext(Dispatchers.IO) {
                runtime.alpineRuntime.inspectEverMeBinding()
            }
            _uiState.update { it.copy(everMeBindingState = state) }
        }
    }

    fun startEverMeBinding() {
        everMeBindingJob?.cancel()
        everMeBindingJob = viewModelScope.launch {
            _uiState.update { current ->
                current.copy(
                    everMeBindingState = EverMeBindingState(
                        phase = EverMeBindingPhase.Starting,
                        detail = "Starting secure EverMe login...",
                    )
                )
            }
            val state = withContext(Dispatchers.IO) {
                runtime.alpineRuntime.startEverMeDeviceFlow()
            }
            _uiState.update { it.copy(everMeBindingState = state) }
        }
    }

    fun completeEverMeBinding() {
        val authorization = _uiState.value.everMeBindingState
        if (authorization.deviceCode.isBlank()) {
            startEverMeBinding()
            return
        }
        everMeBindingJob?.cancel()
        everMeBindingJob = viewModelScope.launch {
            var state = authorization.copy(
                phase = EverMeBindingPhase.Installing,
                detail = "Waiting for EverMe authorization...",
            )
            _uiState.update { it.copy(everMeBindingState = state) }
            while (state.expiresAtMillis == 0L || System.currentTimeMillis() < state.expiresAtMillis) {
                val result = withContext(Dispatchers.IO) {
                    runtime.alpineRuntime.pollEverMeDeviceFlow(
                        deviceCode = authorization.deviceCode,
                        verificationUrl = authorization.verificationUrl,
                        userCode = authorization.userCode,
                        expiresAtMillis = authorization.expiresAtMillis,
                    )
                }
                if (result.phase != EverMeBindingPhase.AwaitingApproval) {
                    _uiState.update { it.copy(everMeBindingState = result) }
                    return@launch
                }
                state = result.copy(
                    phase = EverMeBindingPhase.Installing,
                    detail = "Waiting for EverMe authorization...",
                )
                _uiState.update { it.copy(everMeBindingState = state) }
                delay(2_500L)
            }
            _uiState.update { current ->
                current.copy(
                    everMeBindingState = authorization.copy(
                        phase = EverMeBindingPhase.Failed,
                        detail = "EverMe authorization expired. Start login again.",
                    )
                )
            }
        }
    }

    fun initializeAlpineRuntime(makeDefault: Boolean = true) {
        viewModelScope.launch {
            initializeAlpineRuntimeAfterReset(makeDefault)
        }
    }

    fun retryAlpineRuntimeSetup(makeDefault: Boolean = true) {
        viewModelScope.launch {
            _uiState.update { current ->
                current.copy(
                    alpineSetupState = LocalRuntimeSetupState(
                        runtimeId = LocalRuntimeId.Alpine,
                        issue = LocalRuntimeIssue.NotInstalled,
                    ),
                    piCoreSetupState = PiCoreSetupState(
                        isChecking = true,
                        phase = PiCoreSetupPhase.CheckingAlpine,
                        output = "Starting Alpine setup...\n",
                    ),
                )
            }
            val resetFailure = try {
                withContext(Dispatchers.IO) {
                    runtime.alpineRuntime.reset()
                }
                null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                error
            }
            if (resetFailure != null) {
                val detail = resetFailure.userFacingMessage()
                _uiState.update { current ->
                    current.copy(
                        alpineSetupState = LocalRuntimeSetupState(
                            runtimeId = LocalRuntimeId.Alpine,
                            issue = LocalRuntimeIssue.Failed,
                            detail = detail,
                        ),
                        piCoreSetupState = current.piCoreSetupState.copy(
                            isChecking = false,
                            phase = PiCoreSetupPhase.Failed,
                            failedAtPhase = PiCoreSetupPhase.CheckingAlpine,
                            detail = detail,
                            output = appendSetupOutput(
                                current.piCoreSetupState.output,
                                "Setup failed: $detail\n",
                            ),
                        ),
                    )
                }
                emitTransientMessage(UiText.Raw(detail))
                return@launch
            }
            val settings = _uiState.value.settings
            val remainingRuntimeIds = settings.enabledRuntimeIds - LocalRuntimeId.Alpine
            settingsRepository.updateSettings(
                settings.copy(
                    alpineSetupCompleted = false,
                    alpinePackageProfiles = emptyMap(),
                    enabledRuntimeIds = remainingRuntimeIds,
                    defaultRuntimeId = if (settings.defaultRuntimeId == LocalRuntimeId.Alpine) {
                        remainingRuntimeIds.firstOrNull()
                    } else {
                        settings.defaultRuntimeId
                    },
                )
            )
            _uiState.update { current ->
                current.copy(
                    alpinePackageInstallProgress = emptyMap(),
                    draftChromeEnabled = false,
                    sessions = current.sessions.map { it.copy(chromeEnabled = false) },
                )
            }
            chatStateStore.updateAndFlush { persisted ->
                persisted.copy(
                    sessions = persisted.sessions.map { it.copy(chromeEnabled = false) },
                )
            }
            initializeAlpineRuntimeAfterReset(makeDefault)
        }
    }

    private suspend fun initializeAlpineRuntimeAfterReset(makeDefault: Boolean) {
        _uiState.update { current ->
            current.copy(
                piCoreSetupState = PiCoreSetupState(
                    isChecking = true,
                    phase = PiCoreSetupPhase.CheckingAlpine,
                    output = "Starting Alpine setup...\n",
                )
            )
        }
        val setupState = withContext(Dispatchers.IO) {
            runtime.alpineRuntime.initialize { progress ->
                applyPiCoreSetupUpdate(
                    PiCoreSetupUpdate(
                        phase = PiCoreSetupPhase.CheckingAlpine,
                        activity = when (progress.activity) {
                            AlpineSetupActivity.Extracting -> PiCoreSetupActivity.Extracting
                            AlpineSetupActivity.Downloading -> PiCoreSetupActivity.Downloading
                            AlpineSetupActivity.Installing -> PiCoreSetupActivity.None
                            AlpineSetupActivity.None -> PiCoreSetupActivity.None
                        },
                        bytesPerSecond = progress.bytesPerSecond,
                        output = progress.output,
                    )
                )
            }
        }
        if (setupState.isReady) {
            settingsRepository.updateSettings(
                _uiState.value.settings.withRuntimeEnabled(
                    runtimeId = LocalRuntimeId.Alpine,
                    makeDefault = makeDefault,
                )
            )
        }
        _uiState.update { current -> current.copy(alpineSetupState = setupState) }
        if (setupState.isReady) {
            _uiState.update { current ->
                current.copy(
                    piCoreSetupState = PiCoreSetupState(
                        isReady = true,
                        phase = PiCoreSetupPhase.Ready,
                        isChecking = false,
                        activity = PiCoreSetupActivity.None,
                        bytesPerSecond = 0L,
                        bridgeVersion = "Aether Agent",
                        output = appendSetupOutput(
                            current.piCoreSetupState.output,
                            "Aether agent runtime setup complete.\n",
                        ),
                    ),
                )
            }
        } else {
            _uiState.update { current ->
                current.copy(
                    piCoreSetupState = current.piCoreSetupState.copy(
                        isChecking = false,
                        phase = PiCoreSetupPhase.Failed,
                        failedAtPhase = PiCoreSetupPhase.CheckingAlpine,
                        detail = setupState.detail,
                        activity = PiCoreSetupActivity.None,
                        bytesPerSecond = 0L,
                        output = appendSetupOutput(
                            current.piCoreSetupState.output,
                            "Setup failed: ${setupState.detail}\n",
                        ),
                    )
                )
            }
        }
        emitTransientMessage(UiText.Raw(setupState.detail.ifBlank { "Alpine runtime status refreshed." }))
        if (setupState.isReady) {
            warmKimiEngineIfPossible()
            syncAcpSessionsIfPossible()
            refreshWorkspaceFileSuggestions()
        }
    }

    private suspend fun refreshPiCoreSetup() {
        if (_uiState.value.piCoreSetupState.isChecking) return
        _uiState.update {
            it.copy(
                piCoreSetupState = PiCoreSetupState(
                    isChecking = true,
                    phase = PiCoreSetupPhase.CheckingAlpine,
                    output = it.piCoreSetupState.output.ifBlank { "Starting agent runtime setup...\n" },
                )
            )
        }
        runCatching {
            withContext(Dispatchers.IO) {
                runtime.piKernelBridge.ping(::applyPiCoreSetupUpdate)
            }
        }.fold(
            onSuccess = { payload ->
                _uiState.update {
                    it.copy(
                        piCoreSetupState = PiCoreSetupState(
                            isReady = true,
                            phase = PiCoreSetupPhase.Ready,
                            nodeVersion = payload.optString("node_version"),
                            bridgeVersion = payload.optString("bridge_version"),
                            output = appendSetupOutput(
                                it.piCoreSetupState.output,
                                "AI engine setup complete.\n",
                            ),
                        )
                    )
                }
                syncPiDiscoveredSkills()
            },
            onFailure = { throwable ->
                if (throwable is CancellationException) throw throwable
                _uiState.update { current ->
                    current.copy(
                        piCoreSetupState = PiCoreSetupState(
                            phase = PiCoreSetupPhase.Failed,
                            failedAtPhase = current.piCoreSetupState.phase,
                            detail = throwable.userFacingMessage(),
                            output = appendSetupOutput(
                                current.piCoreSetupState.output,
                                "Setup failed: ${throwable.userFacingMessage()}\n",
                            ),
                        )
                    )
                }
            },
        )
    }

    private suspend fun syncPiDiscoveredSkills() {
        runCatching {
            val response = piKernelBridge.listDiscoveredSkills()
            val skills = response.optJSONArray("skills") ?: return@runCatching
            val discovered = buildList {
                for (index in 0 until skills.length()) {
                    val item = skills.optJSONObject(index) ?: continue
                    val guestFilePath = item.optString("file_path").trim()
                    val guestBaseDir = item.optString("base_dir").trim()
                    if (guestFilePath.isBlank() || guestBaseDir.isBlank()) continue
                    val hostFile = runtime.alpineRuntime.resolveWorkspaceHostPath(guestFilePath)?.hostFile
                        ?: runtime.alpineRuntime.resolveGuestPath(guestFilePath)
                    val hostRoot = runtime.alpineRuntime.resolveWorkspaceHostPath(guestBaseDir)?.hostFile
                        ?: runtime.alpineRuntime.resolveGuestPath(guestBaseDir)
                    if (!hostFile.isFile || !hostRoot.isDirectory) continue
                    add(
                        PiDiscoveredSkillSource(
                            guestFilePath = guestFilePath,
                            guestBaseDir = guestBaseDir,
                            hostFile = hostFile,
                            hostRoot = hostRoot,
                        )
                    )
                }
            }
            skillManager.syncPiDiscoveredSkills(discovered).getOrThrow()
        }
    }

    fun resetAlpineRuntime() {
        viewModelScope.launch {
            val setupState = withContext(Dispatchers.IO) {
                runtime.alpineRuntime.reset()
            }
            val settings = _uiState.value.settings
            settingsRepository.updateSettings(
                settings.copy(
                    enabledRuntimeIds = settings.enabledRuntimeIds - LocalRuntimeId.Alpine,
                    defaultRuntimeId = if (settings.defaultRuntimeId == LocalRuntimeId.Alpine) {
                        (settings.enabledRuntimeIds - LocalRuntimeId.Alpine).firstOrNull()
                    } else {
                        settings.defaultRuntimeId
                    },
                    alpineSetupCompleted = false,
                    alpinePackageProfiles = emptyMap(),
                )
            )
            _uiState.update { current ->
                current.copy(
                    alpineSetupState = setupState,
                    alpinePackageInstallProgress = emptyMap(),
                    draftChromeEnabled = false,
                    sessions = current.sessions.map { it.copy(chromeEnabled = false) },
                )
            }
            chatStateStore.updateAndFlush { persisted ->
                persisted.copy(
                    sessions = persisted.sessions.map { it.copy(chromeEnabled = false) },
                )
            }
        }
    }

    fun installAlpinePackageProfile(profileId: String) {
        if (_uiState.value.alpinePackageInstallProgress.containsKey(profileId)) return
        viewModelScope.launch {
            _uiState.update { current ->
                current.copy(
                    alpinePackageInstallProgress = current.alpinePackageInstallProgress +
                        (
                            profileId to AlpineSetupProgress(
                                activity = AlpineSetupActivity.Downloading,
                            )
                            ),
                )
            }
            val installState = try {
                withContext(Dispatchers.IO) {
                    runtime.alpineRuntime.installPackageProfile(profileId) { progress ->
                        _uiState.update { current ->
                            val previous = current.alpinePackageInstallProgress[profileId]
                            val merged = progress.copy(
                                bytesPerSecond = progress.bytesPerSecond.takeIf { it > 0L }
                                    ?: previous?.bytesPerSecond
                                    ?: 0L,
                                progressPercent = progress.progressPercent
                                    ?: previous?.progressPercent,
                            )
                            current.copy(
                                alpinePackageInstallProgress =
                                    current.alpinePackageInstallProgress + (profileId to merged),
                            )
                        }
                    }
                }
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                LocalRuntimeSetupState(
                    runtimeId = LocalRuntimeId.Alpine,
                    issue = LocalRuntimeIssue.Failed,
                    detail = throwable.userFacingMessage(),
                )
            } finally {
                _uiState.update { current ->
                    current.copy(
                        alpinePackageInstallProgress =
                            current.alpinePackageInstallProgress - profileId,
                    )
                }
            }
            val profileState = if (installState.isReady) {
                PackageProfileState(
                    profileId = profileId,
                    installed = true,
                    installedAtMillis = System.currentTimeMillis(),
                    lastError = "",
                )
            } else {
                PackageProfileState(
                    profileId = profileId,
                    installed = false,
                    installedAtMillis = 0L,
                    lastError = installState.detail.ifBlank { "Install failed." },
                )
            }
            val settings = _uiState.value.settings
            settingsRepository.updateSettings(
                settings.copy(
                    alpinePackageProfiles = settings.alpinePackageProfiles + (profileId to profileState),
                )
            )
            val runtimeState = withContext(Dispatchers.IO) {
                runtime.alpineRuntime.inspectSetup()
            }
            _uiState.update { current -> current.copy(alpineSetupState = runtimeState) }
            emitTransientMessage(UiText.Raw(installState.detail.ifBlank { "Alpine package profile updated." }))
        }
    }

    suspend fun createAlpineTerminalLaunchSpec(): Result<AlpineTerminalLaunchSpec> =
        withContext(Dispatchers.IO) {
            runCatching { runtime.alpineRuntime.createTerminalLaunchSpec() }
        }

    fun updateBrowserPreferences(preferences: kira.ditto.data.BrowserPreferences) {
        viewModelScope.launch {
            val settings = _uiState.value.settings
            if (settings.browserPreferences == preferences) return@launch
            settingsRepository.updateSettings(settings.copy(browserPreferences = preferences))
        }
    }

    fun deleteBrowserLogin(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runtime.browserLoginVault.delete(id)
        }
    }

    fun clearBrowserBrowsingData() {
        viewModelScope.launch(Dispatchers.Main) {
            if (kira.ditto.browser.AetherBrowserRuntime.isWarm()) {
                kira.ditto.browser.AetherBrowserRuntime.peek()?.clearCookiesAndCache()
            }
        }
    }

    fun refreshStorageUsage() {
        viewModelScope.launch(Dispatchers.IO) {
            val hours = _uiState.value.settings.oldCommandHistoryRetentionHours
            val usage = runCatching { storageManager.pruneStale(hours) }
                .recoverCatching { storageManager.usage() }
                .getOrDefault(AppStorageUsage())
            _uiState.update { it.copy(storageUsage = usage, storageBusy = false) }
        }
    }

    /**
     * Load the memory pages for the settings screen.
     *
     * On IO: the pages are files inside the Alpine rootfs, and a long conversation can have dozens
     * of them. Reading them on the main thread would stutter the screen that exists to reassure the
     * user that memory is inspectable.
     */
    fun refreshMemoryPages() {
        viewModelScope.launch(Dispatchers.IO) {
            val pages = runCatching { runtime.alpineRuntime.readMemoryPages() }.getOrDefault(emptyList())
            _uiState.update { it.copy(memoryPages = pages) }
        }
    }

    fun forgetMemoryPage(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { runtime.alpineRuntime.deleteMemoryPage(id) }
            val pages = runCatching { runtime.alpineRuntime.readMemoryPages() }.getOrDefault(emptyList())
            _uiState.update { it.copy(memoryPages = pages) }
        }
    }

    fun forgetAllMemoryPages() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { runtime.alpineRuntime.clearMemoryPages() }
            _uiState.update { it.copy(memoryPages = emptyList()) }
        }
    }

    fun clearAppStorage(kind: AppStorageClearKind) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(storageBusy = true) }
            runCatching {
                if (kind == AppStorageClearKind.BrowserCache) {
                    withContext(Dispatchers.Main) {
                        if (kira.ditto.browser.AetherBrowserRuntime.isWarm()) {
                            kira.ditto.browser.AetherBrowserRuntime.peek()?.clearCookiesAndCache()
                        }
                    }
                }
                storageManager.clear(kind)
            }
            val hours = _uiState.value.settings.oldCommandHistoryRetentionHours
            val usage = runCatching { storageManager.usage() }.getOrDefault(AppStorageUsage())
            _uiState.update { it.copy(storageUsage = usage, storageBusy = false) }
        }
    }

    fun updateCacheCleanupPolicy(enabled: Boolean, days: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            storageManager.autoCleanEnabled = enabled
            storageManager.retentionDays = days
            refreshStorageUsage()
        }
    }

    fun listBrowserLogins(): List<kira.ditto.browser.SavedBrowserLogin> =
        runtime.browserLoginVault.list()

    fun openBuiltInBrowser(url: String? = null) {
        kira.ditto.browser.BrowserActivity.launch(getApplication(), url)
    }

    fun setDefaultRuntime(runtimeId: LocalRuntimeId) {
        viewModelScope.launch {
            val settings = _uiState.value.settings
            settingsRepository.updateSettings(
                settings.copy(
                    enabledRuntimeIds = settings.enabledRuntimeIds + runtimeId,
                    defaultRuntimeId = runtimeId,
                )
            )
        }
    }

    fun refreshAgentModeAuthorization() {
        val settings = _uiState.value.settings
        refreshAgentModeAuthorization(
            enabled = settings.agentModeAuthorizationEnabled,
            method = settings.agentModeAuthorizationMethod,
        )
    }

    fun refreshAgentModeAuthorization(
        enabled: Boolean,
        method: AgentModeAuthorizationMethod,
    ) {
        viewModelScope.launch {
            syncAgentModeAuthorization(enabled = enabled, method = method)
        }
    }

    private suspend fun syncAgentModeAuthorization(
        enabled: Boolean,
        method: AgentModeAuthorizationMethod,
    ) {
        agentModeController.refreshAuthorization(
            _uiState.value.settings.copy(
                agentModeAuthorizationEnabled = enabled,
                agentModeAuthorizationMethod = method,
            )
        )
        val authorizationState = agentModeController.authorizationState.value
        _uiState.update { current ->
            current.copy(agentModeAuthorizationState = authorizationState)
        }
    }

    fun requestShizukuPermission() {
        _uiState.update { current ->
            current.copy(agentModeAuthorizationState = agentModeController.requestShizukuPermission())
        }
    }

    fun requestEnableAgentModeShizuku(selectAfterReady: Boolean = false) {
        if (selectAfterReady) {
            pendingSelectComposerAgentMode = true
        }
        if (runtime.shizukuProvisioner.isInstalled()) {
            viewModelScope.launch {
                settingsRepository.enableAgentModeShizuku()
                withContext(Dispatchers.IO) {
                    runtime.shizukuProvisioner.ensureReady()
                }
                syncAgentModeAuthorization(
                    enabled = true,
                    method = AgentModeAuthorizationMethod.Shizuku,
                )
                applyPendingComposerAgentModeSelection()
                if (currentComposerAgentModeSelected()) {
                    startAgentModeDisplay()
                }
            }
            return
        }
        _uiState.update { current -> current.copy(pendingShizukuInstallConsent = true) }
    }

    fun confirmShizukuInstall() {
        _uiState.update { current -> current.copy(pendingShizukuInstallConsent = false) }
        viewModelScope.launch {
            runtime.shizukuProvisioner.enableAndInstall()
            syncAgentModeAuthorization(
                enabled = true,
                method = AgentModeAuthorizationMethod.Shizuku,
            )
            applyPendingComposerAgentModeSelection()
            if (currentComposerAgentModeSelected()) {
                startAgentModeDisplay()
            }
        }
    }

    fun dismissShizukuInstallConsent() {
        pendingSelectComposerAgentMode = false
        _uiState.update { current -> current.copy(pendingShizukuInstallConsent = false) }
        setComposerAgentModeSelected(false)
    }

    private fun applyPendingComposerAgentModeSelection() {
        if (!pendingSelectComposerAgentMode) return
        pendingSelectComposerAgentMode = false
        setComposerAgentModeSelected(true)
    }

    fun installBundledShizuku() {
        requestEnableAgentModeShizuku()
    }

    fun trackTermuxSetupStarted(source: String) {
        pendingTermuxSetupSource = source
        captureAnalyticsEvent(
            event = "termux setup started",
            properties = mapOf(
                "source" to source,
                "current_issue" to _uiState.value.termuxSetupState.issue.name.lowercase(),
                "is_ready" to _uiState.value.termuxSetupState.isReady,
            ),
        )
    }

    fun trackPermissionRequested(
        permission: String,
        source: String,
    ) {
        captureAnalyticsEvent(
            event = "permission requested",
            properties = mapOf(
                "permission" to permission,
                "source" to source,
            ),
        )
    }

    fun trackPermissionResult(
        permission: String,
        granted: Boolean,
        source: String,
        result: String = if (granted) "granted" else "denied",
    ) {
        captureAnalyticsEvent(
            event = "permission result",
            properties = mapOf(
                "permission" to permission,
                "source" to source,
                "granted" to granted,
                "result" to result,
            ),
        )
    }

    fun checkForUpdates() {
        checkForUpdates(manual = true, forceAvailable = false)
    }

    fun forceUpdateCheckForTesting() {
        checkForUpdates(manual = true, forceAvailable = true)
    }

    fun dismissUpdateAvailableDialog() {
        _uiState.update { current ->
            current.copy(
                appUpdate = current.appUpdate.copy(showAvailableDialog = false)
            )
        }
    }

    fun downloadAndInstallUpdate() {
        val release = _uiState.value.appUpdate.availableRelease ?: return
        if (_uiState.value.appUpdate.isDownloading) return

        _uiState.update { current ->
            current.copy(
                appUpdate = current.appUpdate.copy(
                    isDownloading = true,
                    downloadProgress = null,
                )
            )
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    appUpdateManager.downloadApk(release) { progress ->
                        _uiState.update { current ->
                            current.copy(
                                appUpdate = current.appUpdate.copy(downloadProgress = progress)
                            )
                        }
                    }
                }
            }
            result
                .onSuccess { installUri ->
                    _uiState.update { current ->
                        current.copy(
                            appUpdate = current.appUpdate.copy(
                                isDownloading = false,
                                downloadProgress = null,
                                pendingInstallUri = installUri.toString(),
                                showAvailableDialog = false,
                            )
                        )
                    }
                }
                .onFailure { throwable ->
                    _uiState.update { current ->
                        current.copy(
                            appUpdate = current.appUpdate.copy(
                                isDownloading = false,
                                downloadProgress = null,
                            )
                        )
                    }
                    emitTransientMessage(uiString(R.string.message_update_download_failed, throwable.userFacingMessage()))
                }
        }
    }

    fun consumePendingUpdateInstallUri() {
        _uiState.update { current ->
            current.copy(
                appUpdate = current.appUpdate.copy(pendingInstallUri = "")
            )
        }
    }

    fun stopAgentModeDisplay() {
        agentModeController.stopDisplay()
    }

    fun refreshAgentModeDisplays() {
        refreshAgentModeDisplays(_uiState.value.settings.agentModeAuthorizationMethod)
    }

    fun refreshAgentModeDisplays(method: AgentModeAuthorizationMethod) {
        viewModelScope.launch {
            agentModeController.refreshDisplays(
                _uiState.value.settings.copy(agentModeAuthorizationMethod = method)
            )
        }
    }

    fun attachAgentModePreviewSurface(surface: Surface) {
        viewModelScope.launch {
            agentModeController.attachPreviewSurface(_uiState.value.settings, surface)
        }
    }

    fun detachAgentModePreviewSurface(surface: Surface) {
        agentModeController.detachPreviewSurface(surface)
    }

    fun bindAgentModePreviewSurfaceView(view: SurfaceView?) {
        agentModeController.bindPreviewSurfaceView(view)
    }

    fun tapAgentModeDisplay(x: Int, y: Int) {
        viewModelScope.launch {
            runCatching {
                agentModeController.injectUserTap(_uiState.value.settings, x, y)
            }
        }
    }

    fun finishAgentModeTeaching() {
        agentModeController.finishTeaching()
        viewModelScope.launch {
            runtime.agentModeLearningRuntime.commitTeaching(_uiState.value.currentSessionId)
        }
    }

    fun toggleAgentModeTeaching() {
        if (agentModeController.isTeachingActive) {
            finishAgentModeTeaching()
        } else {
            startAgentModeTeaching()
        }
    }

    fun startAgentModeTeaching() {
        val current = _uiState.value
        val session = current.sessions.firstOrNull { it.id == current.currentSessionId }
        val lastUser = session?.messages?.lastOrNull { message ->
            message.author == MessageAuthor.User
        }?.text.orEmpty()
        val goal = lastUser.ifBlank { current.draftInput }.ifBlank { "用户示教" }
        runtime.agentModeLearningRuntime.ensureTurn(current.currentSessionId, goal)
        agentModeController.startUserTeaching()
    }

    fun swipeAgentModeDisplay(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 280) {
        viewModelScope.launch {
            runCatching {
                agentModeController.injectUserSwipe(
                    settings = _uiState.value.settings,
                    x1 = x1,
                    y1 = y1,
                    x2 = x2,
                    y2 = y2,
                    durationMs = durationMs,
                )
            }
        }
    }

    @Volatile
    private var suppressingAgentModeIme = false

    fun suppressAgentModeIme() {
        if (suppressingAgentModeIme) return
        viewModelScope.launch {
            suppressingAgentModeIme = true
            try {
                runCatching {
                    agentModeController.suppressVirtualIme(_uiState.value.settings)
                }
            } finally {
                suppressingAgentModeIme = false
            }
        }
    }

    fun updateDraftInput(value: String) {
        _uiState.update { current ->
            current.copy(
                draftInput = value,
                showStarterPromptHint = if (
                    current.showStarterPromptHint && value != current.draftInput
                ) {
                    false
                } else {
                    current.showStarterPromptHint
                },
            )
        }
        if (value.contains('@')) {
            refreshWorkspaceFileSuggestions()
        }
    }

    fun skipOnboarding() {
        viewModelScope.launch {
            settingsRepository.updateOnboardingSeenVersion(CurrentOnboardingVersion)
            _uiState.update { current ->
                current.copy(
                    currentScreen = AppScreen.Chat,
                    isStartupRouteResolved = true,
                    isOnboardingReplay = false,
                    onboardingStep = OnboardingStep.Landing,
                    onboardingReturnScreen = AppScreen.Chat,
                )
            }
        }
    }

    fun openOnboardingFromSettings() {
        _uiState.update { current ->
            current.copy(
                currentScreen = AppScreen.Onboarding,
                isOnboardingReplay = true,
                onboardingStep = OnboardingStep.Landing,
                onboardingReturnScreen = AppScreen.Settings,
            )
        }
    }

    fun openFollowUpOnboardingFromSettings() {
        _uiState.update { current ->
            current.copy(
                currentScreen = AppScreen.Onboarding,
                isOnboardingReplay = true,
                onboardingStep = OnboardingStep.AgentModeAuthorization,
                onboardingReturnScreen = AppScreen.Settings,
                awaitingFollowUpTour = false,
                showFollowUpTourCard = false,
            )
        }
    }

    fun openDeveloperAlpineSetupPreview() {
        developerAlpineSetupPreviewJob?.cancel()
        _uiState.update { current ->
            current.copy(
                currentScreen = AppScreen.Onboarding,
                isOnboardingReplay = true,
                onboardingStep = OnboardingStep.AlpineSetup,
                onboardingReturnScreen = AppScreen.Settings,
                developerAlpineSetupPreviewState = PiCoreSetupState(),
            )
        }
        restartDeveloperAlpineSetupPreview()
    }

    fun restartDeveloperAlpineSetupPreview() {
        developerAlpineSetupPreviewJob?.cancel()
        _uiState.update { current ->
            if (current.developerAlpineSetupPreviewState == null) {
                current
            } else {
                current.copy(developerAlpineSetupPreviewState = PiCoreSetupState())
            }
        }
        developerAlpineSetupPreviewJob = viewModelScope.launch {
            fun update(
                phase: PiCoreSetupPhase,
                activity: PiCoreSetupActivity = PiCoreSetupActivity.None,
                bytesPerSecond: Long = 0L,
                output: String = "",
            ) {
                _uiState.update { current ->
                    val preview = current.developerAlpineSetupPreviewState ?: return@update current
                    current.copy(
                        developerAlpineSetupPreviewState = preview.copy(
                            isChecking = phase != PiCoreSetupPhase.Ready && phase != PiCoreSetupPhase.Failed,
                            isReady = phase == PiCoreSetupPhase.Ready,
                            phase = phase,
                            activity = activity,
                            bytesPerSecond = bytesPerSecond,
                            output = appendSetupOutput(preview.output, output),
                            nodeVersion = if (phase == PiCoreSetupPhase.Ready) "22.21.1" else preview.nodeVersion,
                            bridgeVersion = if (phase == PiCoreSetupPhase.Ready) "2.0.0-alpha.0" else preview.bridgeVersion,
                        )
                    )
                }
            }

            update(PiCoreSetupPhase.CheckingAlpine, output = "Starting Alpine setup preview...\n")
            delay(700L)
            update(
                phase = PiCoreSetupPhase.CheckingAlpine,
                activity = PiCoreSetupActivity.Extracting,
                bytesPerSecond = 18L * 1024L * 1024L,
                output = "Preparing Alpine runtime files...\n",
            )
            delay(900L)
            val extractionEntries = listOf(
                "bin/busybox",
                "etc/alpine-release",
                "usr/lib/libcrypto.so.3",
                "usr/bin/env",
                "var/lib/apk/world",
            )
            extractionEntries.forEachIndexed { index, path ->
                update(
                    phase = PiCoreSetupPhase.CheckingAlpine,
                    activity = PiCoreSetupActivity.Extracting,
                    bytesPerSecond = (18L + index * 3L) * 1024L * 1024L,
                    output = "Extracting $path\n",
                )
                delay(1_600L)
            }
            update(
                phase = PiCoreSetupPhase.CheckingNode,
                output = "Alpine root filesystem ready.\nChecking node --version...\n",
            )
            delay(1_000L)
            val apkLines = listOf(
                "\$ apk add --no-cache --no-chown nodejs npm",
                "fetch https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/APKINDEX.tar.gz",
                "(1/8) Installing ca-certificates",
                "(2/8) Installing libuv",
                "(7/8) Installing nodejs",
                "(8/8) Installing npm",
                "OK: 94 MiB in 32 packages",
            )
            apkLines.forEachIndexed { index, line ->
                update(
                    phase = PiCoreSetupPhase.InstallingNode,
                    activity = PiCoreSetupActivity.Downloading,
                    bytesPerSecond = (2_400L + index * 370L) * 1024L,
                    output = "$line\n",
                )
                delay(1_400L)
            }
            update(PiCoreSetupPhase.PreparingBridge, output = "Preparing the AI engine bridge...\n")
            delay(900L)
            update(PiCoreSetupPhase.StartingBridge, output = "Starting node bridge.mjs...\n")
            delay(900L)
            update(PiCoreSetupPhase.VerifyingBridge, output = "Verifying bridge response...\n")
            delay(900L)
            update(PiCoreSetupPhase.Ready, output = "AI engine setup complete.\n")
        }
    }

    fun resumeOnboarding() {
        _uiState.update { current ->
            current.copy(
                currentScreen = AppScreen.Onboarding,
                isOnboardingReplay = true,
                onboardingStep = OnboardingStep.ProviderSetup,
                onboardingReturnScreen = AppScreen.Chat,
            )
        }
    }

    fun closeOnboarding() {
        developerAlpineSetupPreviewJob?.cancel()
        developerAlpineSetupPreviewJob = null
        onboardingRuntimeSetupJob?.cancel()
        onboardingRuntimeSetupJob = null
        _uiState.update { current ->
            current.copy(
                currentScreen = current.onboardingReturnScreen,
                isOnboardingReplay = false,
                onboardingStep = OnboardingStep.Landing,
                onboardingReturnScreen = AppScreen.Chat,
                developerAlpineSetupPreviewState = null,
            )
        }
    }

    private fun applyPiCoreSetupUpdate(update: PiCoreSetupUpdate) {
        _uiState.update { current ->
            current.copy(
                piCoreSetupState = current.piCoreSetupState.copy(
                    isChecking = true,
                    phase = update.phase,
                    activity = update.activity,
                    bytesPerSecond = update.bytesPerSecond,
                    output = appendSetupOutput(current.piCoreSetupState.output, update.output),
                )
            )
        }
    }

    private fun appendSetupOutput(
        current: String,
        addition: String,
    ): String {
        if (addition.isEmpty()) return current
        val combined = current + addition
        return if (combined.length <= MaxSetupOutputChars) {
            combined
        } else {
            combined.takeLast(MaxSetupOutputChars)
        }
    }

    fun completeFollowUpOnboarding() {
        captureAnalyticsEvent(
            event = "onboarding follow up completed",
            properties = mapOf(
                "section" to "follow_up",
                "source" to if (_uiState.value.isOnboardingReplay) "replay" else "auto",
                "termux_ready" to _uiState.value.termuxSetupState.isReady,
                "agent_mode_authorized" to _uiState.value.agentModeAuthorizationState.isReady,
                "tavily_configured" to _uiState.value.settings.tavilyApiKey.isNotBlank(),
                "skill_count" to _uiState.value.installedSkills.size,
                "mcp_server_count" to _uiState.value.mcpServers.size,
            ),
        )
        closeOnboarding()
    }

    fun completeOnboardingProviderSetup(config: LlmProviderConfig) {
        viewModelScope.launch {
            val enabledConfig = config.copy(isEnabled = true)
            settingsRepository.upsertProviderConfig(enabledConfig)
            settingsRepository.setProviderEnabled(enabledConfig.id, true)
            val updatedSettings = _uiState.value.settings.withExplicitDefaultChatModel(enabledConfig)
            val defaultModelKey = updatedSettings.defaultChatModelKey
            settingsRepository.updateSettings(updatedSettings)
            settingsRepository.updateOnboardingSeenVersion(CurrentOnboardingVersion)
            _uiState.update { current ->
                current.copy(
                    currentScreen = AppScreen.Chat,
                    isStartupRouteResolved = true,
                    isOnboardingReplay = false,
                    onboardingStep = OnboardingStep.Landing,
                    onboardingReturnScreen = AppScreen.Chat,
                    currentSessionId = DraftSessionId,
                    draftInput = OnboardingStarterPrompt,
                    draftAttachments = emptyList(),
                    draftSelectedModelKey = defaultModelKey,
                    draftSelectedSkillIds = emptyList(),
                    draftSelectedMcpServerIds = emptyList(),
                    draftAgentModeEnabled = false,
                    draftChromeEnabled = false,
                    draftWorkspaceId = null,
                    editingSessionId = null,
                    editingMessageId = null,
                    showStarterPromptHint = true,
                    awaitingFollowUpTour = true,
                    showFollowUpTourCard = false,
                )
            }
            persistCurrentSessionId(DraftSessionId)
            captureAnalyticsEvent(
                event = "onboarding completed",
                properties = mapOf(
                    "section" to "initial",
                    "provider" to kira.ditto.data.PiProviderCatalog
                        .resolve(enabledConfig.piProviderId).displayName,
                    "provider_id" to enabledConfig.id,
                ),
            )
            captureAnalyticsEvent(
                event = "onboarding initial completed",
                properties = mapOf(
                    "section" to "initial",
                    "provider" to kira.ditto.data.PiProviderCatalog
                        .resolve(enabledConfig.piProviderId).displayName,
                    "provider_id" to enabledConfig.id,
                ),
            )
        }
    }

    fun dismissStarterPromptHint() {
        _uiState.update { current -> current.copy(showStarterPromptHint = false) }
    }

    fun activateSpotifyOverlay(invocationId: String) {
        if (invocationId.isBlank()) return
        _uiState.update { current ->
            val overlay = current.spotifyOverlay
            if (overlay.lastPlaybackInvocationId == invocationId && !overlay.active) {
                current
            } else if (overlay.lastPlaybackInvocationId == invocationId && overlay.active) {
                current
            } else {
                current.copy(
                    spotifyOverlay = SpotifyOverlayUi(
                        active = true,
                        docked = false,
                        orbMenuOpen = false,
                        orbUnlocked = if (overlay.active) overlay.orbUnlocked else false,
                        orbOffsetY = if (overlay.active) overlay.orbOffsetY else 0f,
                        lastPlaybackInvocationId = invocationId,
                    ),
                )
            }
        }
    }

    fun setSpotifyOverlayDocked(docked: Boolean) {
        _uiState.update { current ->
            val overlay = current.spotifyOverlay
            if (!overlay.active || overlay.docked == docked) current
            else current.copy(
                spotifyOverlay = overlay.copy(
                    docked = docked,
                    orbMenuOpen = false,
                ),
            )
        }
    }

    fun setSpotifyOrbMenuOpen(open: Boolean) {
        _uiState.update { current ->
            val overlay = current.spotifyOverlay
            if (!overlay.active || overlay.orbMenuOpen == open) current
            else current.copy(spotifyOverlay = overlay.copy(orbMenuOpen = open))
        }
    }

    fun setSpotifyOrbUnlocked(unlocked: Boolean) {
        _uiState.update { current ->
            val overlay = current.spotifyOverlay
            if (!overlay.active) current
            else current.copy(
                spotifyOverlay = overlay.copy(
                    orbUnlocked = unlocked,
                    orbMenuOpen = false,
                ),
            )
        }
    }

    fun setSpotifyOrbOffsetY(offsetY: Float) {
        _uiState.update { current ->
            val overlay = current.spotifyOverlay
            if (!overlay.active || overlay.orbOffsetY == offsetY) current
            else current.copy(spotifyOverlay = overlay.copy(orbOffsetY = offsetY))
        }
    }

    fun destroySpotifyOverlay() {
        _uiState.update { current ->
            val overlay = current.spotifyOverlay
            if (!overlay.active && !overlay.docked && !overlay.orbMenuOpen) current
            else current.copy(
                spotifyOverlay = overlay.copy(
                    active = false,
                    docked = false,
                    orbMenuOpen = false,
                    orbUnlocked = false,
                    orbOffsetY = 0f,
                ),
            )
        }
    }

    fun dismissTermuxSetupNotice() {
        viewModelScope.launch {
            settingsRepository.updateSettings(
                _uiState.value.settings.copy(termuxSetupNoticeDismissed = true)
            )
        }
    }

    fun openFollowUpTour() {
        _uiState.update { current ->
            current.copy(
                currentScreen = AppScreen.Onboarding,
                isOnboardingReplay = true,
                onboardingStep = OnboardingStep.AgentModeAuthorization,
                onboardingReturnScreen = AppScreen.Chat,
                awaitingFollowUpTour = false,
                showFollowUpTourCard = false,
            )
        }
    }

    fun saveOnboardingTavilyApiKey(value: String) {
        // Legacy onboarding callbacks are ignored; Web Tools are no longer part of Aether.
    }

    fun saveOnboardingAgentModeAuthorization(
        enabled: Boolean,
        method: AgentModeAuthorizationMethod,
    ) {
        viewModelScope.launch {
            settingsRepository.updateSettings(
                _uiState.value.settings.copy(
                    agentModeAuthorizationEnabled = enabled,
                    agentModeAuthorizationMethod = method,
                )
            )
        }
    }

    fun appendDraftAttachments(uris: List<Uri>) {
        if (uris.isEmpty()) return

        val targetSessionId = ensureDraftWorkspaceId()

        viewModelScope.launch {
            val pendingAttachments = withContext(Dispatchers.IO) {
                uris
                    .mapNotNull { uri -> buildPendingDraftAttachment(uri) }
                    .distinctBy { it.uri }
                    .toList()
            }
            if (pendingAttachments.isEmpty()) return@launch

            var attachmentsToImport = emptyList<ChatAttachment>()
            _uiState.update { current ->
                val newAttachments = pendingAttachments.filterNot { candidate ->
                    current.draftAttachments.any { existing -> existing.uri == candidate.uri }
                }
                attachmentsToImport = newAttachments
                if (newAttachments.isEmpty()) {
                    current
                } else {
                    current.copy(
                        draftAttachments = current.draftAttachments + newAttachments
                    )
                }
            }

            attachmentsToImport.forEach { attachment ->
                launch(Dispatchers.IO) {
                    importDraftAttachmentToWorkspace(
                        attachment = attachment,
                        sessionId = targetSessionId,
                    )
                }
            }
        }
    }

    fun removeDraftAttachment(attachmentId: String) {
        _uiState.update { current ->
            current.copy(
                draftAttachments = current.draftAttachments.filterNot { it.id == attachmentId }
            )
        }
    }

    fun pauseGeneration() {
        val snapshot = _uiState.value
        val sessionId = sequenceOf(
            snapshot.currentSessionId,
            snapshot.pendingResponseSessionId,
        )
            .filterNotNull()
            .firstOrNull(sessionExecutionManager::isSessionRunning)
            ?: return
        val finalizedSession = sessionExecutionManager.pauseSession(sessionId) ?: return
        sealAndClearBrowserDesk()
        val executionStates = sessionExecutionManager.executionStates.value
        _uiState.update { current -> current.withFinalizedPausedSession(finalizedSession, executionStates) }
    }

    fun openSettings() {
        _uiState.update { it.copy(currentScreen = AppScreen.Settings) }
    }

    // ── TTS playback ────────────────────────────────────────────────────────

    private fun startTtsStreaming(settings: AppSettings, playingId: String = "streaming") {
        stopTtsPlayback()
        val router = ttsRouter ?: TtsEngineRouter { _uiState.value.providerConfigs }.also { ttsRouter = it }
        ttsStreamMux = TtsStreamMux(
            router,
            settings,
            getApplication<Application>().applicationContext,
        ).apply {
            onPlaybackStart = { _playingMessageId.value = playingId }
            onPlaybackEnd = {
                if (_playingMessageId.value == playingId) {
                    _playingMessageId.value = null
                }
            }
            onError = { msg ->
                _transientMessages.tryEmit(UiText.Raw(msg))
                if (_playingMessageId.value == playingId) {
                    _playingMessageId.value = null
                }
            }
        }
        ttsStreamMux?.start()
        lastTtsTextSnapshot = ""
    }

    fun onPlayMessage(messageId: String) {
        val settings = _uiState.value.settings
        if (!settings.ttsEnabled || settings.defaultTtsModelKey.isBlank()) return

        if (_playingMessageId.value == messageId) {
            stopTtsPlayback()
            return
        }

        val message = _uiState.value.sessions
            .flatMap { it.messages }
            .firstOrNull { it.id == messageId }
        val text = message?.text?.trim().orEmpty()
        if (text.isBlank()) return

        startTtsStreaming(settings, playingId = messageId)
        _playingMessageId.value = messageId
        ttsStreamMux?.feed(text)
        ttsStreamMux?.flush()
    }

    fun stopTtsPlayback() {
        ttsStreamMux?.stop()
        ttsStreamMux = null
        _playingMessageId.value = null
        lastTtsTextSnapshot = ""
        ttsTurnActive = false
    }

    fun openPersona() {
        _uiState.update { it.copy(currentScreen = AppScreen.DigiCrew) }
    }

    fun closePersona() {
        val snapshot = _uiState.value
        val leavingPersonaChat = snapshot.activePersonaId.isNotBlank() ||
            snapshot.draftPersonaId.isNotBlank() ||
            sessionPersonaIds[snapshot.currentSessionId].orEmpty().isNotBlank()
        if (leavingPersonaChat) {
            startNewChatNow(enabledDefaultSkillIds(snapshot))
            return
        }
        _uiState.update {
            it.copy(
                currentScreen = AppScreen.Chat,
                personaEditorId = "",
            )
        }
    }

    fun openDigiCrew() {
        _uiState.update { it.copy(currentScreen = AppScreen.DigiCrew) }
    }

    fun openUpaPlugin() {
        _uiState.update { it.copy(currentScreen = AppScreen.UpaPlugin) }
    }

    fun openRemote() {
        refreshLocalRemoteControl()
        _uiState.update { it.copy(currentScreen = AppScreen.Remote) }
    }

    fun connectRemoteMachine(rawUrl: String) {
        val trimmed = rawUrl.trim()
        if (kira.ditto.data.isLegacyLanKimiWebUrl(trimmed)) {
            emitTransientMessage(uiString(R.string.remote_official_legacy))
            return
        }
        val officialUrl = (
            kira.ditto.data.extractOfficialRemoteControlUrl(trimmed)
                ?: trimmed.takeIf { kira.ditto.data.isOfficialRemoteControlUrl(it) }
            )
            ?.let { url ->
                if (url.startsWith("http://", ignoreCase = true) ||
                    url.startsWith("https://", ignoreCase = true)
                ) {
                    url
                } else {
                    "https://$url"
                }
            }
        if (officialUrl.isNullOrBlank()) {
            emitTransientMessage(uiString(R.string.remote_official_invalid))
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isConnectingRemote = true, pendingRemoteSetup = null) }
            val existing = _uiState.value.settings.remoteMachines.firstOrNull { machine ->
                machine.baseUrl.equals(officialUrl, ignoreCase = true)
            }
            val machine = kira.ditto.data.RemoteMachine(
                id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                name = existing?.name?.ifBlank { "Remote Control" } ?: "Remote Control",
                baseUrl = officialUrl,
                token = "",
                cwd = existing?.cwd.orEmpty(),
                lastSeenAtMillis = System.currentTimeMillis(),
            )
            val updatedMachines = _uiState.value.settings.remoteMachines
                .filterNot { it.id == machine.id || it.baseUrl.equals(machine.baseUrl, ignoreCase = true) } +
                machine
            val updatedSettings = _uiState.value.settings.copy(remoteMachines = updatedMachines)
            settingsRepository.updateSettings(updatedSettings)
            _uiState.update {
                it.copy(
                    isConnectingRemote = false,
                    pendingRemoteSetup = null,
                    settings = updatedSettings,
                )
            }
            openBuiltInBrowser(officialUrl)
            emitTransientMessage(uiString(R.string.remote_connect_done, machine.name))
        }
    }

    fun prepareRemoteSession(machineId: String) {
        val machine = _uiState.value.settings.remoteMachines.firstOrNull { it.id == machineId } ?: return
        if (kira.ditto.data.isLegacyLanKimiWebUrl(machine.baseUrl) ||
            (machine.token.isNotBlank() && !kira.ditto.data.isOfficialRemoteControlUrl(machine.baseUrl))
        ) {
            emitTransientMessage(uiString(R.string.remote_official_legacy))
            return
        }
        if (!kira.ditto.data.isOfficialRemoteControlUrl(machine.baseUrl)) {
            emitTransientMessage(uiString(R.string.remote_official_invalid))
            return
        }
        openBuiltInBrowser(machine.baseUrl)
    }

    fun startLocalRemoteControl() {
        if (_uiState.value.localRemoteControlBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(localRemoteControlBusy = true) }
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    runtime.alpineRuntime.startKimiRemoteControl()
                }
            }
            result.onFailure { error ->
                _uiState.update {
                    it.copy(
                        localRemoteControlBusy = false,
                        localRemoteControlRunning = false,
                        localRemoteControlUrl = "",
                    )
                }
                emitTransientMessage(
                    uiString(R.string.remote_local_failed, error.message ?: error.javaClass.simpleName),
                )
                return@launch
            }
            val url = result.getOrDefault("")
            _uiState.update {
                it.copy(
                    localRemoteControlBusy = false,
                    localRemoteControlRunning = runtime.alpineRuntime.isKimiRemoteControlRunning(),
                    localRemoteControlUrl = url,
                )
            }
            if (url.isNotBlank()) {
                openBuiltInBrowser(url)
            }
        }
    }

    fun stopLocalRemoteControl() {
        viewModelScope.launch(Dispatchers.IO) {
            runtime.alpineRuntime.stopKimiRemoteControl()
            _uiState.update {
                it.copy(
                    localRemoteControlBusy = false,
                    localRemoteControlRunning = false,
                    localRemoteControlUrl = "",
                )
            }
        }
    }

    fun refreshLocalRemoteControl() {
        _uiState.update {
            it.copy(
                localRemoteControlRunning = runtime.alpineRuntime.isKimiRemoteControlRunning(),
                localRemoteControlUrl = runtime.alpineRuntime.kimiRemoteControlUrl(),
            )
        }
    }

    fun cancelRemoteSetup() {
        _uiState.update { it.copy(pendingRemoteSetup = null, isConnectingRemote = false) }
    }

    fun confirmRemoteSetup(name: String, cwd: String) {
        val pending = _uiState.value.pendingRemoteSetup ?: return
        val trimmedCwd = cwd.trim()
        if (trimmedCwd.isBlank()) {
            emitTransientMessage(uiString(R.string.remote_cwd_required))
            return
        }
        viewModelScope.launch {
            val displayName = name.trim().ifBlank { pending.suggestedName }
            val existingId = pending.machineId.takeIf { it.isNotBlank() }
            val machine = kira.ditto.data.RemoteMachine(
                id = existingId ?: java.util.UUID.randomUUID().toString(),
                name = displayName,
                baseUrl = pending.baseUrl,
                token = pending.token,
                cwd = trimmedCwd,
                lastSeenAtMillis = System.currentTimeMillis(),
            )
            val updatedMachines = _uiState.value.settings.remoteMachines
                .filterNot { it.id == machine.id || (it.baseUrl == machine.baseUrl && it.token == machine.token) } +
                machine
            val updatedSettings = _uiState.value.settings.copy(remoteMachines = updatedMachines)
            settingsRepository.updateSettings(updatedSettings)
            _uiState.update {
                it.copy(
                    pendingRemoteSetup = null,
                    isConnectingRemote = false,
                    settings = updatedSettings,
                ).withRemoteModelCatalog(
                    machine.id,
                    pending.models,
                    pending.defaultModelId,
                    pending.defaultPermissionMode,
                )
            }
            emitTransientMessage(uiString(R.string.remote_connect_done, machine.name))
            openRemoteSession(machine.id)
        }
    }

    fun removeRemoteMachine(machineId: String) {
        val machine = _uiState.value.settings.remoteMachines.firstOrNull { it.id == machineId } ?: return
        viewModelScope.launch {
            val updatedSettings = _uiState.value.settings.copy(
                remoteMachines = _uiState.value.settings.remoteMachines.filterNot { it.id == machineId },
            )
            settingsRepository.updateSettings(updatedSettings)
            _uiState.update { it.copy(settings = updatedSettings) }
            emitTransientMessage(uiString(R.string.remote_removed, machine.name))
        }
    }

    fun openRemoteSession(machineId: String) {
        val snapshot = _uiState.value
        val machine = snapshot.settings.remoteMachines.firstOrNull { it.id == machineId } ?: return
        val sessionId = java.util.UUID.randomUUID().toString()
        val remoteOptions = snapshot.remoteModelOptionsByMachineId[machine.id].orEmpty()
        val remoteDefaultKey = snapshot.remoteDefaultModelKeyByMachineId[machine.id].orEmpty()
        val selectedModelKey = snapshot.draftSelectedModelKey
            .takeIf { key -> remoteOptions.any { it.key == key } }
            ?: remoteDefaultKey.ifBlank { remoteOptions.firstOrNull()?.key.orEmpty() }
        val permissionMode = snapshot.remotePermissionModeByMachineId[machine.id]
            ?: kira.ditto.data.RemoteDefaultPermissionMode
        val session = createSession(
            id = sessionId,
            messages = emptyList(),
            title = machine.name,
            hasCustomTitle = true,
            selectedModelKey = selectedModelKey,
        ).copy(
            remoteMachineId = machine.id,
            remoteCwd = machine.cwd,
        )
        _uiState.update { current ->
            current.copy(
                currentScreen = AppScreen.Chat,
                currentSessionId = sessionId,
                sessions = listOf(session) + current.sessions.filterNot { it.id == sessionId },
                draftInput = "",
                draftAttachments = emptyList(),
                isConnectingRemote = false,
            )
        }
        sessionExecutionManager.applyAgentModeId(sessionId, permissionMode)
        viewModelScope.launch {
            runCatching {
                runtime.chatRepository.upsertAgentSessionMetadata(
                    chatSessionId = sessionId,
                    piSessionId = "remote-pending-$sessionId",
                    jsonlPath = kira.ditto.data.remoteAgentPath(machine.id, machine.cwd),
                    runtime = kira.ditto.data.RemoteAgentRuntime,
                )
            }
            persistSessionSelection(sessionId, session)
        }
    }

    private fun syncRemoteSessionPermission(session: ChatSession?) {
        val machineId = session?.remoteMachineId?.takeIf { it.isNotBlank() } ?: return
        val knownMode = _uiState.value.remotePermissionModeByMachineId[machineId]
        if (!knownMode.isNullOrBlank()) {
            sessionExecutionManager.applyAgentModeId(session.id, knownMode)
        }
        refreshRemotePermissionCatalog(machineId, session.id)
    }

    private fun refreshRemotePermissionCatalog(machineId: String, applyToSessionId: String) {
        val machine = _uiState.value.settings.remoteMachines.firstOrNull { it.id == machineId } ?: return
        viewModelScope.launch {
            val catalog = runCatching {
                withContext(Dispatchers.IO) {
                    runtime.piKernelBridge.listRemoteModels(machine.baseUrl, machine.token)
                }
            }.getOrNull() ?: return@launch
            _uiState.update { current ->
                current.withRemoteModelCatalog(
                    machine.id,
                    catalog.models,
                    catalog.defaultModelId,
                    catalog.defaultPermissionMode,
                )
            }
            val current = _uiState.value
            if (current.currentSessionId == applyToSessionId) {
                sessionExecutionManager.applyAgentModeId(
                    applyToSessionId,
                    catalog.defaultPermissionMode,
                )
            }
        }
    }

    fun previewUpaPlugin(
        source: String,
        onProgress: (kira.ditto.data.UpaScanProgress) -> Unit,
        onDone: (Result<List<kira.ditto.upa.UpaInstallPreview>>) -> Unit,
    ) {
        viewModelScope.launch {
            onDone(
                runCatching {
                    runtime.upaPluginInstaller.previewAll(source) { progress ->
                        withContext(Dispatchers.Main.immediate) {
                            onProgress(progress)
                        }
                    }
                },
            )
        }
    }

    fun installUpaPlugin(
        preview: kira.ditto.upa.UpaInstallPreview,
        onDone: (Result<kira.ditto.data.InstalledUpaPlugin>) -> Unit,
    ) {
        viewModelScope.launch {
            val result = runCatching { runtime.upaPluginInstaller.install(preview) }
            result.onSuccess { plugin ->
                val mcpServers = mergedMcpServers(
                    _uiState.value.mcpServers.filterNot { kira.ditto.upa.isUpaMcpServerId(it.id) },
                )
                _uiState.update { current ->
                    current.copy(
                        installedUpaPlugins = (current.installedUpaPlugins.filterNot { it.id == plugin.id } + plugin)
                            .sortedBy { it.name.lowercase() },
                        mcpServers = mcpServers,
                    )
                }
                emitTransientMessage(uiString(R.string.upa_plugin_install_done, plugin.inAppName()))
                prewarmKimiWorkspaceIfPossible(forceRestart = true)
            }
            result.onFailure { throwable ->
                emitTransientMessage(
                    uiString(
                        R.string.upa_plugin_install_failed,
                        throwable.message ?: "",
                    ),
                )
            }
            onDone(result)
        }
    }

    fun checkUpaPluginUpdates() {
        checkUpaPluginUpdates(manual = true)
    }

    fun checkUpaPluginUpdates(manual: Boolean) {
        if (_uiState.value.isCheckingUpaPluginUpdates) return
        val installed = _uiState.value.installedUpaPlugins
        if (installed.isEmpty()) {
            if (manual) emitTransientMessage(uiString(R.string.upa_plugin_update_none_installed))
            return
        }
        _uiState.update { it.copy(isCheckingUpaPluginUpdates = true) }
        viewModelScope.launch {
            val result = runCatching { runtime.upaPluginInstaller.checkUpdates(installed) }
            result.onFailure { throwable ->
                if (manual) {
                    emitTransientMessage(
                        uiString(R.string.upa_plugin_update_failed, throwable.message ?: ""),
                    )
                }
            }
            result.onSuccess { outcome ->
                if (outcome.updates.isEmpty()) {
                    if (manual) {
                        if (outcome.errors.isNotEmpty()) {
                            emitTransientMessage(
                                uiString(
                                    R.string.upa_plugin_update_failed,
                                    outcome.errors.joinToString("；"),
                                ),
                            )
                        } else {
                            emitTransientMessage(uiString(R.string.upa_plugin_update_current))
                        }
                    }
                } else {
                    val names = mutableListOf<String>()
                    outcome.updates.forEach { update ->
                        runCatching { runtime.upaPluginInstaller.install(update.preview) }
                            .onSuccess { plugin ->
                                names += plugin.inAppName()
                                _uiState.update { current ->
                                    current.copy(
                                        installedUpaPlugins = (
                                            current.installedUpaPlugins.filterNot { it.id == plugin.id } + plugin
                                            ).sortedBy { it.name.lowercase() },
                                    )
                                }
                            }
                    }
                    val mcpServers = mergedMcpServers(
                        _uiState.value.mcpServers.filterNot { kira.ditto.upa.isUpaMcpServerId(it.id) },
                    )
                    _uiState.update { current -> current.copy(mcpServers = mcpServers) }
                    if (names.isNotEmpty()) {
                        emitTransientMessage(
                            uiString(R.string.upa_plugin_update_done, names.joinToString()),
                        )
                        prewarmKimiWorkspaceIfPossible(forceRestart = true)
                        emitTransientMessage(uiString(R.string.upa_plugin_update_current))
                    }
                }
            }
            _uiState.update { it.copy(isCheckingUpaPluginUpdates = false) }
        }
    }

    fun uninstallUpaPlugin(id: String) {
        viewModelScope.launch {
            runCatching { runtime.upaPluginLibrary.uninstall(id) }
            runCatching { runtime.alpineRuntime.publishKimiMcpConfig() }
            val mcpServers = mergedMcpServers(
                _uiState.value.mcpServers.filterNot { kira.ditto.upa.isUpaMcpServerId(it.id) },
            )
            _uiState.update { current ->
                current.copy(
                    installedUpaPlugins = current.installedUpaPlugins.filterNot { it.id == id },
                    mcpServers = mcpServers,
                    draftSelectedMcpServerIds = current.draftSelectedMcpServerIds.filterNot {
                        it == kira.ditto.upa.upaMcpServerId(id)
                    },
                    sessions = current.sessions.map { session ->
                        session.copy(
                            activeMcpServerIds = session.activeMcpServerIds.filterNot {
                                it == kira.ditto.upa.upaMcpServerId(id)
                            },
                        )
                    },
                )
            }
        }
    }

    fun setChatSurfaceVisible(visible: Boolean) {
        if (chatSurfaceVisible == visible) return
        chatSurfaceVisible = visible
        _uiState.update { current ->
            presentExecutionStates(sessionExecutionManager.executionStates.value, current)
        }
        if (visible) {
            prewarmKimiWorkspaceIfPossible()
        }
    }

    fun onAppForegrounded() {
        prewarmKimiWorkspaceIfPossible()
        viewModelScope.launch {
            runCatching { runtime.shizukuProvisioner.ensureReady() }
            if (currentComposerAgentModeSelected()) {
                syncAgentModeAuthorization(
                    enabled = true,
                    method = AgentModeAuthorizationMethod.Shizuku,
                )
                startAgentModeDisplay()
            } else if (_uiState.value.settings.agentModeAuthorizationEnabled) {
                syncAgentModeAuthorization(
                    enabled = true,
                    method = _uiState.value.settings.agentModeAuthorizationMethod,
                )
            }
        }
    }

    fun savePersona(persona: kira.ditto.data.PersonaProfile) {
        viewModelScope.launch {
            val existing = personaRepository.store.first().personas.firstOrNull { it.id == persona.id }
            val preserved = if (persona.activeSessionId.isBlank()) {
                persona.copy(activeSessionId = existing?.activeSessionId.orEmpty())
            } else {
                persona
            }
            personaRepository.upsertPersona(preserved)
        }
    }

    fun deletePersona(personaId: String) {
        viewModelScope.launch {
            personaRepository.removePersona(personaId)
        }
    }

    fun importPersonaKnowledge(
        persona: kira.ditto.data.PersonaProfile,
        bytes: ByteArray,
        displayName: String,
        mimeType: String,
    ) {
        viewModelScope.launch {
            val importState = PersonaKnowledgeImportProgress(
                personaId = persona.id,
                displayName = displayName,
                progress = 0.04f,
                phase = kira.ditto.data.PersonaKnowledgeImportPhase.Parsing,
            )
            _uiState.update { it.copy(personaKnowledgeImport = importState) }
            try {
                personaRepository.upsertPersona(persona)
                val vectorModel = resolveVectorModelOption()
                runCatching {
                    knowledgeIndexer.importAndIndex(
                        persona.id,
                        bytes,
                        displayName,
                        mimeType,
                        vectorModel,
                    ) { progress, phase ->
                        _uiState.update { current ->
                            current.copy(
                                personaKnowledgeImport = current.personaKnowledgeImport?.copy(
                                    progress = progress,
                                    phase = phase,
                                ) ?: PersonaKnowledgeImportProgress(
                                    personaId = persona.id,
                                    displayName = displayName,
                                    progress = progress,
                                    phase = phase,
                                ),
                            )
                        }
                    }
                }.onSuccess { file ->
                    emitTransientMessage(
                        uiString(R.string.persona_knowledge_indexed, file.sliceCount, file.displayName),
                    )
                }.onFailure { throwable ->
                    emitTransientMessage(
                        uiString(
                            R.string.persona_knowledge_import_failed,
                            throwable.message ?: throwable.javaClass.simpleName,
                        ),
                    )
                }
            } finally {
                _uiState.update { it.copy(personaKnowledgeImport = null) }
            }
        }
    }

    fun startPersonaChat(persona: kira.ditto.data.PersonaProfile) {
        viewModelScope.launch {
            personaRepository.upsertPersona(persona)
            val briefing = buildString {
                if (persona.instructions.isNotBlank()) {
                    append("Persona instructions:\n")
                    append(persona.instructions.trim())
                    append("\n\n")
                }
                if (persona.memoryNotes.isNotBlank()) {
                    append("Persona memory:\n")
                    append(persona.memoryNotes.trim())
                }
            }.trim()
            pendingHiddenContextText = briefing
            pendingNewSessionTitle = persona.name.ifBlank { "Persona" }
            _uiState.update { current ->
                current.copy(
                    currentScreen = AppScreen.Chat,
                    currentSessionId = DraftSessionId,
                    draftInput = "",
                    draftAttachments = emptyList(),
                    draftSelectedSkillIds = persona.skillIds,
                    draftSelectedMcpServerIds = persona.mcpServerIds,
                    draftPersonaId = persona.id,
                    activePersonaId = persona.id,
                    personaEditorId = "",
                    showStarterPromptHint = false,
                )
            }
            persistCurrentSessionId(DraftSessionId)
        }
    }

    fun openPersonaConversation(persona: kira.ditto.data.PersonaProfile) {
        val current = _uiState.value
        if (
            current.currentSessionId == DraftSessionId &&
            current.draftPersonaId == persona.id
        ) {
            _uiState.update {
                it.copy(
                    currentScreen = AppScreen.Chat,
                    activePersonaId = persona.id,
                    personaEditorId = "",
                )
            }
            return
        }
        val existingSessionId = persona.activeSessionId.trim()
            .takeIf { id -> id.isNotBlank() && id != DraftSessionId && current.sessions.any { it.id == id } }
            ?: current.sessions.firstOrNull { session ->
                sessionPersonaIds[session.id] == persona.id
            }?.id
        if (existingSessionId != null) {
            selectSession(existingSessionId)
            return
        }
        startPersonaChat(persona)
    }

    fun openPersonaEditor(personaId: String) {
        if (personaId.isBlank()) return
        _uiState.update {
            it.copy(
                currentScreen = AppScreen.DigiCrew,
                personaEditorId = personaId,
            )
        }
    }

    fun consumePersonaEditorRequest() {
        _uiState.update { it.copy(personaEditorId = "") }
    }

    private fun resolveVectorModelOption(): kira.ditto.data.ProviderModelOption? {
        val snapshot = _uiState.value
        val options = snapshot.providerConfigs.availableModelOptions(
            includeDisabledProviders = true,
            includeDisabledModels = true,
        )
        val option = options.findModelOption(snapshot.settings.defaultVectorModelKey) ?: return null
        if (option.apiKey.isNotBlank() || option.oauthCredentialJson.isNotBlank()) return option
        val fallback = snapshot.settings.apiKey.trim()
        return if (fallback.isBlank()) option else option.copy(apiKey = fallback)
    }

    private suspend fun knowledgeBriefingFor(
        personaId: String,
        query: String,
    ): KnowledgeBriefing {
        if (personaId.isBlank() || query.isBlank()) return KnowledgeBriefing()
        val vectorModel = resolveVectorModelOption()
        return try {
            withTimeout(5_000) {
                val slices = knowledgeIndexer.retrieve(personaId, query, vectorModel)
                KnowledgeBriefing(
                    promptText = formatKnowledgeCitations(slices),
                    citations = knowledgeCitationsFromSlices(slices),
                )
            }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            diagnosticLogger.exception(
                category = "persona",
                event = "knowledge_retrieve_timeout",
                throwable = error,
                level = "warn",
            )
            KnowledgeBriefing()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            diagnosticLogger.exception(
                category = "persona",
                event = "knowledge_retrieve_failed",
                throwable = error,
                level = "warn",
            )
            emitTransientMessage(
                uiString(
                    R.string.persona_knowledge_retrieve_failed,
                    error.message ?: error.javaClass.simpleName,
                ),
            )
            KnowledgeBriefing()
        }
    }

    fun removePersonaKnowledge(personaId: String, fileId: String) {
        viewModelScope.launch {
            personaRepository.removeKnowledgeFile(personaId, fileId)
        }
    }

    fun saveDigiCrew(crew: kira.ditto.data.DigiCrewRoom) {
        viewModelScope.launch {
            personaRepository.upsertCrew(crew)
        }
    }

    fun deleteDigiCrew(crewId: String) {
        viewModelScope.launch {
            personaRepository.removeCrew(crewId)
        }
    }

    fun joinDigiCrewByInvite(raw: String) {
        val parsed = kira.ditto.data.DigiCrewInvite.parse(raw)
        if (parsed == null) {
            emitTransientMessage(uiString(R.string.digicrew_invite_invalid))
            return
        }
        val (roomId, secret) = parsed
        val existing = _uiState.value.digiCrews.firstOrNull { it.id == roomId }
        val crew = (existing ?: kira.ditto.data.DigiCrewRoom(
            id = roomId,
            name = "DigiCrew",
        )).copy(
            roomSecret = secret,
            updatedAtMillis = System.currentTimeMillis(),
        )
        viewModelScope.launch {
            personaRepository.upsertCrew(crew)
            _uiState.update { it.copy(currentScreen = AppScreen.DigiCrew) }
            emitTransientMessage(uiString(R.string.digicrew_joined))
        }
    }

    fun startDigiCrewChat(crew: kira.ditto.data.DigiCrewRoom) {
        viewModelScope.launch {
            personaRepository.upsertCrew(crew)
            val snapshot = _uiState.value
            val members = snapshot.personas.filter { it.id in crew.memberPersonaIds }
            val existingId = crew.activeSessionId.takeIf { id ->
                id.isNotBlank() && snapshot.sessions.any { session -> session.id == id }
            }
            val sessionId = existingId ?: "session-${System.currentTimeMillis()}"
            val now = System.currentTimeMillis()
            val briefing = buildDigiCrewBriefing(
                crew = crew,
                members = members,
                remotePeers = snapshot.digiCrewPeers.filter { it.roomId == crew.id },
            )
            if (existingId == null) {
                val enabledSkillIds = snapshot.installedSkills
                    .filter(InstalledSkill::isEnabled)
                    .map(InstalledSkill::id)
                    .toSet()
                val session = createSession(
                    id = sessionId,
                    messages = listOf(
                        ChatMessage(
                            id = "crew-briefing-$now",
                            author = MessageAuthor.User,
                            text = briefing,
                            createdAtMillis = now,
                            displayKind = MessageDisplayKind.HiddenContext,
                        ),
                    ),
                    title = crew.name.ifBlank { "DigiCrew" },
                    hasCustomTitle = true,
                    selectedModelKey = resolveDefaultChatModelKey(snapshot.settings, snapshot.providerConfigs),
                    selectedSkillIds = members.flatMap { it.skillIds }.distinct().filter(enabledSkillIds::contains),
                    activeMcpServerIds = members.flatMap { it.mcpServerIds }.distinct(),
                )
                _uiState.update { current ->
                    current.copy(
                        sessions = listOf(session) + current.sessions.filterNot { it.id == sessionId },
                        currentSessionId = sessionId,
                        currentScreen = AppScreen.Chat,
                        showStarterPromptHint = false,
                    )
                }
                persistSessionSnapshot(session, currentSessionId = sessionId, moveToFront = true)
            } else {
                _uiState.update {
                    it.copy(
                        currentSessionId = sessionId,
                        currentScreen = AppScreen.Chat,
                    )
                }
                persistCurrentSessionId(sessionId)
            }
            personaRepository.upsertCrew(
                crew.copy(
                    activeSessionId = sessionId,
                    updatedAtMillis = System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun buildDigiCrewBriefing(
        crew: kira.ditto.data.DigiCrewRoom,
        members: List<kira.ditto.data.PersonaProfile>,
        remotePeers: List<kira.ditto.data.DigiCrewPeerPresence>,
    ): String = buildString {
        appendLine("This is a decentralized DigiCrew room. There is no central group-chat or bot-hosting server.")
        appendLine("Crew: ${crew.name.ifBlank { "DigiCrew" }}")
        if (crew.description.isNotBlank()) {
            appendLine("Scene: ${crew.description}")
        }
        appendLine("Speak only as the local members listed below. Do not impersonate remote members; they reply from their own devices.")
        appendLine("Local members hosted on this device:")
        if (members.isEmpty()) {
            appendLine("- (none — you are a human participant)")
        } else {
            members.forEach { persona ->
                appendLine("- ${persona.name}")
                if (persona.instructions.isNotBlank()) {
                    appendLine("  Instructions: ${persona.instructions}")
                }
                if (persona.memoryNotes.isNotBlank()) {
                    appendLine("  Memory: ${persona.memoryNotes}")
                }
            }
        }
        appendLine("Remote personas (stay on their owners' devices):")
        val remote = remotePeers.ifEmpty {
            crew.remotePersonas.map { remote ->
                kira.ditto.data.DigiCrewPeerPresence(
                    peerId = remote.peerId,
                    ownerLabel = remote.ownerLabel,
                    roomId = crew.id,
                    personas = listOf(
                        kira.ditto.data.DigiCrewOfferedPersona(remote.personaId, remote.personaName),
                    ),
                )
            }
        }
        if (remote.isEmpty()) {
            appendLine("- (none discovered yet — they will appear when their device joins)")
        } else {
            remote.forEach { peer ->
                val names = peer.personas.joinToString { it.personaName }.ifBlank { "Persona" }
                appendLine("- $names (owner: ${peer.ownerLabel})")
            }
        }
    }

    private fun persistDiscoveredRemotePersonas(
        peers: List<kira.ditto.data.DigiCrewPeerPresence>,
    ) {
        _uiState.value.digiCrews.forEach { crew ->
            val remotes = peers.filter { it.roomId == crew.id }.flatMap { peer ->
                peer.personas.map { offered ->
                    kira.ditto.data.DigiCrewRemotePersona(
                        peerId = peer.peerId,
                        personaId = offered.personaId,
                        personaName = offered.personaName,
                        ownerLabel = peer.ownerLabel,
                        lastSeenMillis = peer.lastSeenMillis,
                    )
                }
            }
            val previousKeys = crew.remotePersonas.map { Triple(it.peerId, it.personaId, it.personaName) }.toSet()
            val nextKeys = remotes.map { Triple(it.peerId, it.personaId, it.personaName) }.toSet()
            if (nextKeys.isEmpty() && previousKeys.isNotEmpty()) return@forEach
            if (nextKeys != previousKeys) {
                viewModelScope.launch {
                    personaRepository.upsertCrew(
                        crew.copy(
                            remotePersonas = remotes,
                            updatedAtMillis = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }
    }

    private fun ingestDigiCrewChat(event: kira.ditto.data.DigiCrewMeshEvent.Chat) {
        val crew = _uiState.value.digiCrews.firstOrNull { it.id == event.roomId } ?: return
        val sessionId = crew.activeSessionId
        if (sessionId.isBlank()) return
        val message = ChatMessage(
            id = "crew-${event.eventId}",
            author = if (event.kind == "persona") MessageAuthor.Agent else MessageAuthor.User,
            text = "${event.speakerName}: ${event.text}",
            createdAtMillis = System.currentTimeMillis(),
        )
        var updated: ChatSession? = null
        updateSession(sessionId) { session ->
            if (session.messages.any { it.id == message.id }) {
                null
            } else {
                session.withMessages(session.messages + message).also { updated = it }
            }
        }
        if (event.kind == "user" && crew.memberPersonaIds.isNotEmpty()) {
            val session = updated ?: _uiState.value.sessions.firstOrNull { it.id == sessionId } ?: return
            startTurnForExistingSession(session)
        }
    }

    private fun crewForSession(sessionId: String): kira.ditto.data.DigiCrewRoom? =
        _uiState.value.digiCrews.firstOrNull { it.activeSessionId == sessionId }

    private fun startTurnForExistingSession(session: ChatSession) {
        if (sessionExecutionManager.isSessionRunning(session.id)) return
        val current = _uiState.value
        startTurnMaybePromptingSecrets(
            SessionTurnRequest(
                sessionId = session.id,
                settings = resolveSessionModelSettings(
                    current,
                    session,
                    session.selectedModelKey,
                ),
                requestMessages = session.messages,
                selectedSkillIds = session.selectedSkillIds,
                activeSkills = session.activeSkills,
                activeMcpServerIds = session.activeMcpServerIds,
                agentModeEnabled = session.agentModeEnabled,
                chromeEnabled = session.chromeEnabled,
                providerConfigs = current.providerConfigs,
                workspaceId = session.workspaceId,
            ).withRemoteFrom(session, current.settings),
        )
    }

    private fun startTurnMaybePromptingSecrets(request: SessionTurnRequest) {
        val missing = missingMcpSecretSlots(
            servers = _uiState.value.mcpServers,
            selectedIds = request.activeMcpServerIds,
            lookup = hostSecretStore.asLookup(),
        )
        if (missing.isNotEmpty() && request.sessionId !in mcpSecretPromptSkippedSessionIds) {
            deferredMcpSecretTurn = request
            _uiState.update { current ->
                current.copy(pendingMcpSecretPrompt = missing.toPromptUi(request.sessionId, current.mcpServers))
            }
            return
        }
        sessionExecutionManager.startTurn(request)
    }

    fun submitPendingMcpSecrets(values: Map<String, String>) {
        val pending = deferredMcpSecretTurn ?: return
        values.forEach { (id, value) ->
            if (value.isNotBlank()) {
                runCatching { hostSecretStore.put(id, value) }
            }
        }
        deferredMcpSecretTurn = null
        mcpSecretPromptSkippedSessionIds.remove(pending.sessionId)
        _uiState.update { it.copy(pendingMcpSecretPrompt = null) }
        sessionExecutionManager.startTurn(pending)
    }

    fun skipPendingMcpSecrets() {
        val pending = deferredMcpSecretTurn ?: return
        deferredMcpSecretTurn = null
        mcpSecretPromptSkippedSessionIds.add(pending.sessionId)
        _uiState.update { it.copy(pendingMcpSecretPrompt = null) }
        sessionExecutionManager.startTurn(pending)
    }

    private fun List<McpSecretSlot>.toPromptUi(
        sessionId: String,
        servers: List<McpServerConfig>,
    ): PendingMcpSecretPromptUi {
        val byId = servers.associateBy { it.id }
        return PendingMcpSecretPromptUi(
            sessionId = sessionId,
            slots = map { slot ->
                val server = byId[slot.serverId]
                McpSecretSlotUi(
                    id = slot.id,
                    serverId = slot.serverId,
                    serverName = server?.displayName.orEmpty().ifBlank { slot.serverId },
                    kind = slot.kind,
                    injectKey = slot.injectKey,
                    serverHint = mcpSecretHostHint(server),
                )
            },
        )
    }

    private fun mcpSecretHostHint(server: McpServerConfig?): String {
        if (server == null) return ""
        return when (val transport = server.transport) {
            is kira.ditto.data.McpTransportConfig.StreamableHttp -> transport.url
            is kira.ditto.data.McpTransportConfig.StdIo -> transport.command
            is kira.ditto.data.McpTransportConfig.UpaManifest -> transport.pluginId
        }
    }

    fun refreshUsageStatisticsSnapshots(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val state = _uiState.value
        val hasCached = hasUsageStatisticsData(
            snapshots = state.usageStatisticsSnapshots,
            totals = state.usageStatisticsTotals,
        )
        if (!force && hasCached && now - lastUsageSnapshotRefreshMillis < UsageSnapshotRefreshIntervalMillis) {
            return
        }
        if (!force && usageRefreshJob?.isActive == true) return
        usageRefreshJob?.cancel()
        usageRefreshJob = viewModelScope.launch {
            _uiState.update { it.copy(usageStatisticsRefreshing = true) }
            try {
                val cached = withContext(Dispatchers.IO) { loadUsageStatisticsFromSql() }
                applyUsageStatistics(cached.first, cached.second)
                withContext(Dispatchers.IO) {
                    runtime.usageRecorder.ensureMessageBackfill()
                    runtime.usageRecorder.harvestAllSessions()
                }
                val harvested = withContext(Dispatchers.IO) { loadUsageStatisticsFromSql() }
                applyUsageStatistics(harvested.first, harvested.second)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                diagnosticLogger.exception(
                    category = "statistics",
                    event = "usage_refresh_failed",
                    throwable = error,
                    level = "warn",
                )
            } finally {
                _uiState.update { it.copy(usageStatisticsRefreshing = false) }
            }
        }
    }

    private suspend fun loadUsageStatisticsFromSql(): Pair<List<ChatUsageStatisticsSnapshot>, LlmUsageTotals> {
        val recent = runtime.usageRecorder
            .listRecentRecords(System.currentTimeMillis() - UsageDetailWindowMillis)
            .map { record -> record.toUsageSnapshot() }
        return recent to runtime.usageRecorder.usageTotals()
    }

    private fun applyUsageStatistics(
        snapshots: List<ChatUsageStatisticsSnapshot>,
        totals: LlmUsageTotals,
    ) {
        _uiState.update {
            it.copy(
                usageStatisticsSnapshots = snapshots,
                usageStatisticsTotals = totals,
            )
        }
        if (hasUsageStatisticsData(snapshots, totals)) {
            lastUsageSnapshotRefreshMillis = System.currentTimeMillis()
        }
    }

    private fun hasUsageStatisticsData(
        snapshots: List<ChatUsageStatisticsSnapshot>,
        totals: LlmUsageTotals,
    ): Boolean = snapshots.isNotEmpty() || totals.recordCount > 0 || totals.totalTokens > 0L

    private fun LlmUsageRecord.toUsageSnapshot(): ChatUsageStatisticsSnapshot =
        ChatUsageStatisticsSnapshot(
            sessionId = sessionId.orEmpty(),
            source = source,
            modelId = modelId,
            providerId = providerId,
            statistics = ChatUsageStatistics(
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                totalTokens = totalTokens,
                reasoningTokens = reasoningTokens,
                cachedInputTokens = cachedInputTokens,
                requestCount = requestCount,
                tokenUsageSource = usageSource,
                startedAtMillis = startedAtMillis,
                firstTokenAtMillis = firstTokenAtMillis,
                completedAtMillis = completedAtMillis,
            ),
        )

    fun closeSettings() {
        _uiState.update {
            it.copy(
                currentScreen = AppScreen.Chat,
                rootSetupProgressReturnPage = null,
            )
        }
    }

    fun startNewChat() {
        pendingHiddenContextText = ""
        pendingKnowledgeCitations = emptyList()
        pendingNewSessionTitle = null
        val snapshot = _uiState.value
        val defaultSkillIds = enabledDefaultSkillIds(snapshot)
        val operation = "chat.new"
        if (
            !modKernel.operations.hasInterceptors(operation) &&
            "operation:$operation" !in aetherAppExtensionManager.state.value.snapshot.eventNames
        ) {
            startNewChatNow(defaultSkillIds)
            return
        }
        viewModelScope.launch {
            val result = dispatchAetherOperation(
                operation = operation,
                payload = JSONObject().put(
                    "selected_skill_ids",
                    JSONArray(defaultSkillIds),
                ),
            )
            if (result.cancelled) return@launch
            val selectedSkillIds = if (result.payload.has("selected_skill_ids")) {
                result.payload.optJSONArray("selected_skill_ids").toStringList()
            } else {
                defaultSkillIds
            }
            withContext(Dispatchers.Main.immediate) {
                startNewChatNow(selectedSkillIds)
            }
        }
    }

    private fun startNewChatNow(
        selectedSkillIds: List<String>,
    ) {
        sealAndClearBrowserDesk()
        kira.ditto.browser.BrowserDesk.clearReadLedger()
        kira.ditto.browser.BrowserTopicGraph.bindSession(DraftSessionId)
        agentModeController.stopDisplay()
        _uiState.update {
            val defaultModelKey = resolveDefaultChatModelKey(it.settings, it.providerConfigs)
            val enabledSkillIds = it.installedSkills
                .filter(InstalledSkill::isEnabled)
                .map(InstalledSkill::id)
                .toSet()
            it.copy(
                currentScreen = AppScreen.Chat,
                currentSessionId = DraftSessionId,
                draftInput = "",
                draftAttachments = emptyList(),
                draftSelectedModelKey = defaultModelKey,
                draftSelectedSkillIds = selectedSkillIds.filter(enabledSkillIds::contains),
                draftSelectedMcpServerIds = emptyList(),
                draftAgentModeEnabled = false,
                draftChromeEnabled = false,
                draftPromptDirective = "",
                draftPersonaId = "",
                activePersonaId = "",
                draftWorkspaceId = null,
                editingSessionId = null,
                editingMessageId = null,
                unviewedCompletedSessionIds = it.unviewedCompletedSessionIds - DraftSessionId,
                showStarterPromptHint = false,
                phoneDeskHandoff = PhoneDeskHandoffState(),
            )
        }
        persistCurrentSessionId(DraftSessionId)
        captureAnalyticsEvent(event = "conversation started")
    }

    private fun initializeStartupDraftModelIfReady() {
        if (didInitializeStartupDraftModel) return
        _uiState.update { current ->
            val options = current.providerConfigs.availableModelOptions()
            if (
                !current.isStartupRouteResolved ||
                !didReceiveInitialChatState ||
                options.isEmpty()
            ) return@update current
            didInitializeStartupDraftModel = true
            if (current.currentSessionId != DraftSessionId) return@update current
            val defaultModelKey = resolveDefaultChatModelKey(current.settings, current.providerConfigs)
            current.copy(draftSelectedModelKey = defaultModelKey)
        }
    }

    private fun currentConversationModelKey(state: AetherUiState): String {
        val options = state.providerConfigs.availableModelOptions()
        val selectedKey = state.sessions
            .firstOrNull { it.id == state.currentSessionId }
            ?.selectedModelKey
            ?: state.draftSelectedModelKey
        return selectedKey.takeIf { key -> options.any { it.key == key } }
            ?: resolveDefaultChatModelKey(state.settings, state.providerConfigs)
    }

    fun prefetchSession(sessionId: String) {
        if (sessionId == _uiState.value.currentSessionId) return
        prefetchSessionJob?.cancel()
        prefetchSessionJob = viewModelScope.launch {
            prefetchedSessionId = sessionId
            prefetchedSession = loadSessionForSelection(sessionId)
        }
    }

    fun selectSession(sessionId: String) {
        selectSessionJob?.cancel()
        // Crossing into another space is a context boundary. The window is cut here rather than
        // summarised: the conversations in the space being entered are their own history, and
        // carrying the previous space's window across is exactly the leak spaces exist to stop.
        val leavingSpace = _uiState.value.currentWorkspaceId
        val enteringSpace = workspaceIdForSession(sessionId)
        if (leavingSpace != enteringSpace) {
            runtime.alpineRuntime.requestSessionMemoryNewContext()
            _uiState.update { it.copy(currentWorkspaceId = enteringSpace) }
        }
        selectSessionJob = viewModelScope.launch {
            val loadedSession = if (sessionId == prefetchedSessionId) {
                prefetchSessionJob?.join()
                val cached = prefetchedSession
                prefetchedSessionId = null
                prefetchedSession = null
                cached
            } else {
                loadSessionForSelection(sessionId)
            }
            commitSelectedSession(sessionId, loadedSession)
        }
    }

    private suspend fun loadSessionForSelection(sessionId: String): ChatSession? {
        val existingSession = _uiState.value.sessions.firstOrNull { it.id == sessionId }
        val needsHydration = sessionId != DraftSessionId &&
            (existingSession == null ||
                (existingSession.messages.isEmpty() && existingSession.messageCount > 0))
        if (!needsHydration) return null
        return runCatching {
            withContext(Dispatchers.IO) {
                runtime.chatRepository.getSessionWindow(sessionId)
            }
        }.getOrElse { throwable ->
            if (throwable is CancellationException) throw throwable
            diagnosticLogger.exception(
                category = "storage",
                event = "session_selection_hydration_failed",
                throwable = throwable,
                level = "warn",
                sessionId = sessionId,
            )
            null
        }
    }

    private suspend fun commitSelectedSession(sessionId: String, loadedSession: ChatSession?) {
        val previousSessionId = _uiState.value.currentSessionId
        if (previousSessionId != sessionId) {
            sealAndClearBrowserDesk()
            kira.ditto.browser.BrowserDesk.clearReadLedger()
        }
        _uiState.update { current ->
            val sessionsWithMessages = loadedSession?.let { session ->
                replaceOrPrependSession(current.sessions, session)
            } ?: current.sessions
            current.withSessionWindows(
                sessions = sessionsWithMessages.withMessagesOnlyForSession(sessionId),
                currentSessionId = sessionId,
            ).copy(
                currentScreen = AppScreen.Chat,
                draftInput = "",
                draftAttachments = emptyList(),
                draftSelectedModelKey = "",
                draftSelectedSkillIds = emptyList(),
                draftSelectedMcpServerIds = emptyList(),
                draftAgentModeEnabled = false,
                draftChromeEnabled = false,
                draftPersonaId = if (sessionId == DraftSessionId) current.draftPersonaId else "",
                activePersonaId = sessionPersonaIds[sessionId].orEmpty().ifBlank {
                    if (sessionId == DraftSessionId) current.draftPersonaId else ""
                },
                draftWorkspaceId = null,
                editingSessionId = null,
                editingMessageId = null,
                unviewedCompletedSessionIds = current.unviewedCompletedSessionIds - sessionId,
                showStarterPromptHint = false,
            )
        }
        persistSessionSelection(sessionId, loadedSession)
        kira.ditto.browser.BrowserTopicGraph.bindSession(sessionId)
        if (previousSessionId != sessionId) {
            val messages = loadedSession?.messages
                ?: _uiState.value.sessions.firstOrNull { it.id == sessionId }?.messages
                .orEmpty()
            restoreBrowserDeskForSession(sessionId, messages)
        }
        replayImportedAcpSessionIfNeeded(sessionId)
        val selected = loadedSession
            ?: _uiState.value.sessions.firstOrNull { it.id == sessionId }
        syncRemoteSessionPermission(selected)
    }

    fun renameSession(
        sessionId: String,
        title: String,
    ) {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isBlank()) return
        updateSession(sessionId) { session ->
            if (session.title == trimmedTitle && session.hasCustomTitle) {
                null
            } else {
                session.copy(
                    title = trimmedTitle.take(80),
                    hasCustomTitle = true,
                )
            }
        }
    }

    fun deleteSession(sessionId: String) {
        if (sessionExecutionManager.isSessionRunning(sessionId)) {
            emitTransientMessage(uiString(R.string.message_pause_before_deleting_session))
            return
        }

        var didUpdate = false
        _uiState.update { current ->
            val session = current.sessions.firstOrNull { it.id == sessionId } ?: return@update current
            val updatedSessions = current.sessions.filterNot { it.id == sessionId }
            if (updatedSessions.size == current.sessions.size) return@update current
            didUpdate = true
            clearPersonaSessionBinding(sessionId)
            current.copy(
                sessions = updatedSessions,
                currentSessionId = if (current.currentSessionId == sessionId) DraftSessionId else current.currentSessionId,
                draftInput = if (current.editingSessionId == sessionId) "" else current.draftInput,
                draftAttachments = if (current.editingSessionId == sessionId) emptyList() else current.draftAttachments,
                draftWorkspaceId = if (current.editingSessionId == sessionId) null else current.draftWorkspaceId,
                draftPersonaId = if (current.currentSessionId == sessionId) "" else current.draftPersonaId,
                activePersonaId = if (current.currentSessionId == sessionId) "" else current.activePersonaId,
                editingSessionId = if (current.editingSessionId == sessionId) null else current.editingSessionId,
                editingMessageId = if (current.editingSessionId == sessionId) null else current.editingMessageId,
                unviewedCompletedSessionIds = current.unviewedCompletedSessionIds - sessionId,
                showStarterPromptHint = false,
            )
        }
        if (didUpdate) {
            viewModelScope.launch {
                chatStateStore.flush()
                val sharedWorkspaceFilePaths = withContext(Dispatchers.IO) {
                    runCatching {
                        runtime.chatRepository.getUnreferencedWorkspaceFilePathsForDeletedSession(sessionId)
                    }.getOrElse { throwable ->
                        diagnosticLogger.exception(
                            category = "storage",
                            event = "unreferenced_workspace_lookup_failed",
                            throwable = throwable,
                            level = "warn",
                            sessionId = sessionId,
                            details = mapOf("lookup_scope" to "session"),
                        )
                        emptyList()
                    }
                }
                persistDeleteSession(
                    sessionId = sessionId,
                    sharedWorkspaceFilePaths = sharedWorkspaceFilePaths,
                )
                captureAnalyticsEvent(event = "conversation deleted")
            }
        }
    }

    fun forkSession(sessionId: String) {
        if (sessionId.isBlank() || sessionId == DraftSessionId) return
        viewModelScope.launch {
            val source = _uiState.value.sessions.firstOrNull { it.id == sessionId } ?: return@launch
            val hydrated = if (source.messages.isEmpty() && source.messageCount > 0) {
                withContext(Dispatchers.IO) {
                    runtime.chatRepository.getSessionWithMessages(sessionId)
                } ?: source
            } else {
                source
            }
            val forkedKimiId = withContext(Dispatchers.IO) {
                runtime.alpineRuntime.forkAetherKimiSession(sessionId)
            }
            if (forkedKimiId == null && hydrated.messages.isNotEmpty()) {
                emitTransientMessage(uiString(R.string.message_session_fork_failed))
                return@launch
            }
            val newId = UUID.randomUUID().toString()
            val baseTitle = hydrated.title.trim().ifBlank { "新对话" }
            val forkedTitle = if (
                baseTitle.contains("分支") || baseTitle.contains("fork", ignoreCase = true)
            ) {
                baseTitle
            } else {
                "$baseTitle（分支）"
            }.take(80)
            val forked = hydrated.copy(
                id = newId,
                title = forkedTitle,
                hasCustomTitle = true,
                messages = hydrated.messages,
            )
            _uiState.update { current ->
                current.copy(
                    currentScreen = AppScreen.Chat,
                    sessions = listOf(forked) + current.sessions
                        .withMessagesOnlyForSession(newId)
                        .filterNot { it.id == newId },
                    currentSessionId = newId,
                    draftInput = "",
                    draftAttachments = emptyList(),
                    draftSelectedModelKey = "",
                    draftSelectedSkillIds = emptyList(),
                    draftSelectedMcpServerIds = emptyList(),
                    draftAgentModeEnabled = false,
                    draftChromeEnabled = false,
                    draftWorkspaceId = null,
                    editingSessionId = null,
                    editingMessageId = null,
                    showStarterPromptHint = false,
                )
            }
            chatStateStore.update { persisted ->
                persisted.copy(
                    sessions = listOf(forked) + persisted.sessions.filterNot { it.id == newId },
                    currentSessionId = newId,
                )
            }
            if (forkedKimiId != null) {
                withContext(Dispatchers.IO) {
                    runtime.alpineRuntime.bindAetherKimiSession(newId, forkedKimiId)
                    runtime.chatRepository.upsertAgentSessionMetadata(
                        chatSessionId = newId,
                        piSessionId = forkedKimiId,
                        jsonlPath = "acp:$forkedKimiId",
                        runtime = LocalRuntimeId.Alpine.storageValue,
                        migrationVersion = 2,
                    )
                }
            }
            captureAnalyticsEvent(event = "conversation forked")
        }
    }

    fun exportSessionToUri(
        sessionId: String,
        destinationUri: Uri,
    ) {
        viewModelScope.launch {
            val didExport = withContext(Dispatchers.IO) {
                val session = runtime.chatRepository.getSessionWithMessages(sessionId) ?: return@withContext false
                val piSession = runCatching { piKernelBridge.exportSessionJsonl(sessionId) }.getOrNull()
                val piPath = piSession?.optString("exported_path").orEmpty()
                val piJsonl = piPath.takeIf(String::isNotBlank)
                    ?.let { path -> runCatching { java.io.File(path).readText(Charsets.UTF_8) }.getOrNull() }
                writeTextToUri(
                    uri = destinationUri,
                    text = JSONObject().apply {
                        put("schemaVersion", 1)
                        put("exportType", "session")
                        put("exportedAtMillis", System.currentTimeMillis())
                        put("session", session.copy(messages = syncActiveBranches(session.messages)).toJson())
                        put("piSession", JSONObject().apply {
                            put("sessionId", sessionId)
                            put("jsonlPath", piPath)
                            put("jsonl", piJsonl ?: "")
                        })
                    }.toString(2),
                )
            }
            emitTransientMessage(uiString(if (didExport) R.string.message_session_exported else R.string.message_session_export_failed))
        }
    }

    fun exportAllDataToUri(destinationUri: Uri) {
        val snapshot = _uiState.value
        viewModelScope.launch {
            val didExport = withContext(Dispatchers.IO) {
                val sessions = runtime.chatRepository.getSessionsWithMessages()
                writeTextToUri(
                    uri = destinationUri,
                    text = buildFullAppExportJson(snapshot, sessions).toString(2),
                )
            }
            emitTransientMessage(uiString(if (didExport) R.string.message_app_data_exported else R.string.message_app_data_export_failed))
        }
    }

    fun exportLogsToUri(destinationUri: Uri) {
        val snapshot = _uiState.value
        viewModelScope.launch {
            diagnosticLogger.event(
                category = "export",
                event = "diagnostic_export_start",
                details = mapOf(
                    "screen" to snapshot.currentScreen.name,
                    "session_count" to snapshot.sessions.size,
                ),
            )
            val didExport = withContext(Dispatchers.IO) {
                writeTextToUri(
                    uri = destinationUri,
                    text = buildDiagnosticLogText(snapshot),
                )
            }
            diagnosticLogger.event(
                category = "export",
                event = if (didExport) "diagnostic_export_end" else "diagnostic_export_failed",
                level = if (didExport) "info" else "warn",
            )
            emitTransientMessage(uiString(if (didExport) R.string.message_logs_exported else R.string.message_logs_export_failed))
        }
    }

    fun importAllDataFromUri(sourceUri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val rawValue = readTextFromUri(sourceUri)
                    val json = JSONObject(rawValue)
                    val importedSkills = skillManager.importSkillBundles(json.optJSONArray("skillBundles"))
                    json.optJSONObject("extensionArchive")?.let { archive ->
                        piExtensionManager.restoreArchive(archive)
                    }
                    parseFullAppImport(json, importedSkills)
                }
            }
            result
                .onSuccess { imported ->
                    imported.piSessions.forEach { (sessionId, jsonl) ->
                        runCatching {
                            val importedPi = piKernelBridge.importSessionJsonl(sessionId, jsonl)
                            val path = importedPi.optString("session_file")
                            runtime.chatRepository.upsertAgentSessionMetadata(
                                chatSessionId = sessionId,
                                piSessionId = sessionId,
                                jsonlPath = path,
                                runtime = _uiState.value.settings.defaultRuntimeId?.storageValue.orEmpty(),
                                migrationVersion = 2,
                            )
                        }.onFailure { throwable ->
                            diagnosticLogger.exception(
                                category = "pi_bridge",
                                event = "import_session_jsonl_failed",
                                throwable = throwable,
                                level = "warn",
                                sessionId = sessionId,
                            )
                        }
                    }
                    settingsRepository.replaceImportedSettings(
                        settings = imported.settings,
                        providerConfigs = imported.providerConfigs,
                    )
                    extensionsRepository.updateInstalledSkills(imported.installedSkills)
                    extensionsRepository.updateMcpServers(imported.mcpServers)
                    chatStateStore.updateAndFlush(
                        writeIntent = PersistedChatWriteIntent.ReplaceFromImport,
                    ) {
                        it.copy(
                            sessions = imported.sessions,
                            currentSessionId = imported.currentSessionId,
                        )
                    }
                    _uiState.update { current ->
                        current.copy(
                            sessions = imported.sessions,
                            currentSessionId = imported.currentSessionId,
                            draftInput = "",
                            draftAttachments = emptyList(),
                            draftSelectedModelKey = "",
                            draftSelectedSkillIds = emptyList(),
                            draftSelectedMcpServerIds = emptyList(),
                            draftAgentModeEnabled = false,
                            draftChromeEnabled = false,
                            draftWorkspaceId = null,
                            editingSessionId = null,
                            editingMessageId = null,
                            unviewedCompletedSessionIds = emptySet(),
                            mcpServers = imported.mcpServers,
                        )
                    }
                    refreshPiExtensionState(loadCatalog = false)
                    emitTransientMessage(uiString(R.string.message_app_data_imported))
                }
                .onFailure { throwable ->
                    emitTransientMessage(uiString(R.string.message_app_data_import_failed, throwable.userFacingMessage()))
                }
        }
    }

    fun startEditingUserMessage(
        sessionId: String,
        messageId: String,
    ) {
        if (sessionExecutionManager.isSessionRunning(sessionId)) return

        val session = _uiState.value.sessions.firstOrNull { it.id == sessionId } ?: return
        val message = session.messages.firstOrNull {
            it.id == messageId && it.author == MessageAuthor.User
        } ?: return

        _uiState.update {
            it.copy(
                currentScreen = AppScreen.Chat,
                currentSessionId = sessionId,
                draftInput = message.text.visibleUserMessageText(),
                draftAttachments = message.attachments.map(::normalizeDraftAttachmentForEditing),
                draftSelectedModelKey = if (sessionId == DraftSessionId) {
                    it.draftSelectedModelKey.ifBlank {
                        resolveDefaultChatModelKey(it.settings, it.providerConfigs)
                    }
                } else {
                    it.draftSelectedModelKey
                },
                draftWorkspaceId = sessionId,
                editingSessionId = sessionId,
                editingMessageId = messageId,
                showStarterPromptHint = false,
            )
        }
        persistCurrentSessionId(sessionId)
    }

    fun cancelMessageEdit() {
        _uiState.update {
            it.copy(
                draftInput = "",
                draftAttachments = emptyList(),
                draftSelectedModelKey = if (it.currentSessionId == DraftSessionId) {
                    it.draftSelectedModelKey.ifBlank {
                        resolveDefaultChatModelKey(it.settings, it.providerConfigs)
                    }
                } else {
                    it.draftSelectedModelKey
                },
                draftSelectedSkillIds = emptyList(),
                draftSelectedMcpServerIds = emptyList(),
                draftAgentModeEnabled = false,
                draftChromeEnabled = false,
                draftWorkspaceId = null,
                editingSessionId = null,
                editingMessageId = null,
                showStarterPromptHint = false,
            )
        }
    }

    fun deleteMessage(
        sessionId: String,
        messageId: String,
    ) {
        if (sessionExecutionManager.isSessionRunning(sessionId)) return

        var didUpdate = false
        var updatedSessionForPersistence: ChatSession? = null
        var removedSessionForPersistence = false
        var removedMessageIds = emptyList<String>()

        _uiState.update { current ->
            val sessionIndex = current.sessions.indexOfFirst { it.id == sessionId }
            if (sessionIndex < 0) return@update current

            val session = current.sessions[sessionIndex]
            val messageIndex = session.messages.indexOfFirst { it.id == messageId }
            if (messageIndex < 0) return@update current

            val trimFromIndex = session.messages.resolveConversationTrimIndex(messageIndex)
            val trimmedMessages = session.messages.take(trimFromIndex)
            val removedMessages = session.messages.drop(trimFromIndex)
            removedMessageIds = removedMessages.map { it.id }
            val updatedSessions = current.sessions.toMutableList().apply {
                removeAt(sessionIndex)
                if (trimmedMessages.isNotEmpty()) {
                    val updatedSession = session.withMessages(trimmedMessages)
                    updatedSessionForPersistence = updatedSession
                    add(sessionIndex.coerceAtMost(size), updatedSession)
                } else {
                    removedSessionForPersistence = true
                }
            }

            didUpdate = true
            current.copy(
                sessions = updatedSessions,
                currentSessionId = when {
                    trimmedMessages.isEmpty() && current.currentSessionId == sessionId -> DraftSessionId
                    else -> current.currentSessionId
                },
                draftSelectedSkillIds = if (
                    trimmedMessages.isEmpty() && current.currentSessionId == sessionId
                ) {
                    emptyList()
                } else {
                    current.draftSelectedSkillIds
                },
                draftSelectedMcpServerIds = if (
                    trimmedMessages.isEmpty() && current.currentSessionId == sessionId
                ) {
                    emptyList()
                } else {
                    current.draftSelectedMcpServerIds
                },
                draftInput = if (current.editingSessionId == sessionId) "" else current.draftInput,
                draftAttachments = if (current.editingSessionId == sessionId) {
                    emptyList()
                } else {
                    current.draftAttachments
                },
                draftWorkspaceId = if (current.editingSessionId == sessionId) null else current.draftWorkspaceId,
                editingSessionId = if (current.editingSessionId == sessionId) null else current.editingSessionId,
                editingMessageId = if (current.editingSessionId == sessionId) null else current.editingMessageId,
                pendingResponseSessionId = if (current.pendingResponseSessionId == sessionId) null else current.pendingResponseSessionId,
                pendingToolInvocations = if (current.pendingResponseSessionId == sessionId) {
                    emptyList()
                } else {
                    current.pendingToolInvocations
                },
            )
        }

        if (didUpdate) {
            viewModelScope.launch {
                chatStateStore.flush()
                val sharedWorkspaceFilePaths = withContext(Dispatchers.IO) {
                    runCatching {
                        runtime.chatRepository.getUnreferencedWorkspaceFilePathsForDeletedMessages(
                            sessionId = sessionId,
                            messageIds = removedMessageIds,
                        )
                    }.getOrElse { throwable ->
                        diagnosticLogger.exception(
                            category = "storage",
                            event = "unreferenced_workspace_lookup_failed",
                            throwable = throwable,
                            level = "warn",
                            sessionId = sessionId,
                            details = mapOf(
                                "lookup_scope" to "messages",
                                "message_count" to removedMessageIds.size,
                            ),
                        )
                        emptyList()
                    }
                }
                if (removedSessionForPersistence) {
                    persistDeleteSession(
                        sessionId = sessionId,
                        sharedWorkspaceFilePaths = sharedWorkspaceFilePaths,
                    )
                } else {
                    updatedSessionForPersistence?.let { persistSessionSnapshotAndFlush(it) }
                    if (sharedWorkspaceFilePaths.isNotEmpty()) {
                        scheduleSessionRuntimeDataCleanup(
                            sessionIds = emptyList(),
                            sharedWorkspaceFilePaths = sharedWorkspaceFilePaths,
                        )
                    }
                }
            }
        }
    }

    fun redoAgentMessage(
        sessionId: String,
        messageId: String,
    ) {
        if (sessionExecutionManager.isSessionRunning(sessionId)) return

        val snapshot = _uiState.value
        var request: SessionTurnRequest? = null
        var updatedSessionForPersistence: ChatSession? = null
        var piBranchMessageId: String? = null

        _uiState.update { current ->
            val sessionIndex = current.sessions.indexOfFirst { it.id == sessionId }
            if (sessionIndex < 0) return@update current

            val session = current.sessions[sessionIndex]
            val messageIndex = session.messages.indexOfFirst {
                it.id == messageId && it.author == MessageAuthor.Agent
            }
            if (messageIndex < 0) return@update current

            val trimFromIndex = session.messages.resolveConversationTrimIndex(messageIndex)
            val trimmedMessages = session.messages.take(trimFromIndex)
            if (trimmedMessages.lastOrNull()?.author != MessageAuthor.User) {
                return@update current
            }
            piBranchMessageId = trimmedMessages.last().id

            request = SessionTurnRequest(
                sessionId = sessionId,
                settings = resolveSessionModelSettings(
                    snapshot,
                    session,
                    session.selectedModelKey,
                ),
                requestMessages = trimmedMessages,
                selectedSkillIds = session.selectedSkillIds,
                activeSkills = session.activeSkills,
                activeMcpServerIds = session.activeMcpServerIds,
                agentModeEnabled = session.agentModeEnabled,
                chromeEnabled = session.chromeEnabled,
                providerConfigs = snapshot.providerConfigs,
                workspaceId = session.workspaceId,
            ).withRemoteFrom(session, snapshot.settings)
            val updatedSessions = current.sessions.toMutableList().apply {
                removeAt(sessionIndex)
                val updatedSession = session.withMessages(trimmedMessages)
                updatedSessionForPersistence = updatedSession
                add(0, updatedSession)
            }

            current.copy(
                sessions = updatedSessions,
                currentSessionId = sessionId,
                currentScreen = AppScreen.Chat,
                draftInput = "",
                draftAttachments = emptyList(),
                draftWorkspaceId = null,
                editingSessionId = null,
                editingMessageId = null,
            )
        }

        val turnRequest = request ?: return
        updatedSessionForPersistence?.let { session ->
            persistSessionSnapshot(
                session = session,
                currentSessionId = sessionId,
                moveToFront = true,
            )
        }
        viewModelScope.launch {
            navigatePiBranch(sessionId, piBranchMessageId)
            startTurnMaybePromptingSecrets(turnRequest)
        }
    }

    fun retryUserMessage(
        sessionId: String,
        messageId: String,
    ) {
        if (sessionExecutionManager.isSessionRunning(sessionId)) return

        val snapshot = _uiState.value
        var request: SessionTurnRequest? = null
        var updatedSessionForPersistence: ChatSession? = null
        var piBranchMessageId: String? = null

        _uiState.update { current ->
            val sessionIndex = current.sessions.indexOfFirst { it.id == sessionId }
            if (sessionIndex < 0) return@update current

            val session = current.sessions[sessionIndex]
            val userMessage = session.messages.firstOrNull {
                it.id == messageId && it.author == MessageAuthor.User
            } ?: return@update current
            val userMessageIndex = session.messages.indexOfFirst { it.id == messageId }
            piBranchMessageId = session.messages.take(userMessageIndex).lastOrNull()?.id

            val retryMessage = userMessage.copy(
                id = "user-${System.currentTimeMillis()}",
                createdAtMillis = System.currentTimeMillis(),
                branchGroup = null,
            )
            val branchedMessages = createEditedMessageBranch(
                messages = session.messages,
                messageId = messageId,
                replacement = retryMessage,
            ) ?: return@update current
            val updatedSession = session.withMessages(branchedMessages)
            updatedSessionForPersistence = updatedSession
            val updatedSessions = current.sessions.toMutableList().apply {
                removeAt(sessionIndex)
                add(0, updatedSession)
            }

            request = SessionTurnRequest(
                sessionId = sessionId,
                settings = resolveSessionModelSettings(
                    snapshot,
                    updatedSession,
                    updatedSession.selectedModelKey,
                ),
                requestMessages = updatedSession.messages,
                selectedSkillIds = updatedSession.selectedSkillIds,
                activeSkills = updatedSession.activeSkills,
                activeMcpServerIds = updatedSession.activeMcpServerIds,
                agentModeEnabled = updatedSession.agentModeEnabled,
                chromeEnabled = updatedSession.chromeEnabled,
                providerConfigs = snapshot.providerConfigs,
                workspaceId = updatedSession.workspaceId,
            ).withRemoteFrom(updatedSession, snapshot.settings)

            current.copy(
                sessions = updatedSessions,
                currentSessionId = sessionId,
                currentScreen = AppScreen.Chat,
                draftInput = "",
                draftAttachments = emptyList(),
                draftWorkspaceId = null,
                editingSessionId = null,
                editingMessageId = null,
            )
        }

        val turnRequest = request ?: return
        updatedSessionForPersistence?.let { session ->
            persistSessionSnapshot(
                session = session,
                currentSessionId = sessionId,
                moveToFront = true,
            )
        }
        viewModelScope.launch {
            navigatePiBranch(sessionId, piBranchMessageId, resetWhenMissing = true)
            startTurnMaybePromptingSecrets(turnRequest)
        }
    }

    private suspend fun navigatePiBranch(
        sessionId: String,
        aetherMessageId: String?,
        resetWhenMissing: Boolean = false,
    ) {
        // Kimi sessions are resolved lazily by AlpineRuntime.runKimiTurn().
        // Never start the removed Pi bridge while selecting or retrying a chat.
    }

    fun switchUserMessageBranch(
        sessionId: String,
        messageId: String,
        delta: Int,
    ) {
        if (sessionExecutionManager.isSessionRunning(sessionId)) return
        var didUpdate = false
        var updatedSessionForPersistence: ChatSession? = null

        _uiState.update { current ->
            val sessionIndex = current.sessions.indexOfFirst { it.id == sessionId }
            if (sessionIndex < 0) return@update current
            val session = current.sessions[sessionIndex]
            val updatedMessages = switchMessageBranch(
                messages = session.messages,
                messageId = messageId,
                delta = delta,
            ) ?: return@update current
            didUpdate = true
            val updatedSession = session.withMessages(updatedMessages)
            updatedSessionForPersistence = updatedSession
            val updatedSessions = current.sessions.toMutableList().apply {
                set(sessionIndex, updatedSession)
            }
            current.copy(sessions = updatedSessions)
        }

        if (didUpdate) {
            updatedSessionForPersistence?.let(::persistSessionSnapshot)
        }
    }

    fun saveSettings(
        systemPrompt: String,
        tavilyApiKey: String,
        tavilyBaseUrl: String,
        llmInactivityReconnectTimeoutSeconds: Int,
        keepTasksRunningInBackground: Boolean,
        notifyOnTaskCompletion: Boolean,
        autoCleanOldCommandHistory: Boolean,
        oldCommandHistoryRetentionHours: Int,
        termuxEnvironmentVariables: List<TermuxEnvironmentVariable>,
        agentModeAuthorizationEnabled: Boolean,
        agentModeAuthorizationMethod: AgentModeAuthorizationMethod,
        language: AppLanguage,
        themeMode: AppThemeMode,
        defaultChatModelKey: String,
        defaultTitleModelKey: String,
        defaultNamingModelKey: String,
        defaultCompactingModelKey: String,
        defaultVectorModelKey: String,
        defaultImageModelKey: String,
        defaultAsrModelKey: String,
        defaultTtsModelKey: String,
        ttsEnabled: Boolean,
        ttsVoiceId: String,
    ) {
        viewModelScope.launch {
            val currentState = _uiState.value
            val modelOptions = currentState.providerConfigs.availableModelOptions()
            val catalogModelOptions = currentState.providerConfigs.availableModelOptions(
                includeDisabledModels = true,
            )
            val resolvedDefaultChatModelKey = resolveStoredOrAutomaticModelKey(
                modelKey = defaultChatModelKey,
                options = modelOptions,
                purpose = AutomaticModelPurpose.Chat,
            )
            val selectedModelSettings = resolveModelSettings(
                baseSettings = currentState.settings,
                providerConfigs = currentState.providerConfigs,
                preferredModelKey = resolvedDefaultChatModelKey,
                fallbackModelKey = resolvedDefaultChatModelKey,
            )
            settingsRepository.updateSettings(
                currentState.settings.copy(
                    piProviderId = selectedModelSettings.piProviderId,
                    providerConfigId = selectedModelSettings.providerConfigId,
                    providerAuthMethod = selectedModelSettings.providerAuthMethod,
                    apiKey = selectedModelSettings.apiKey,
                    oauthCredentialJson = selectedModelSettings.oauthCredentialJson,
                    providerEnvironmentVariables =
                        selectedModelSettings.providerEnvironmentVariables,
                    baseUrl = selectedModelSettings.baseUrl,
                    modelId = selectedModelSettings.modelId,
                    userAgent = selectedModelSettings.userAgent,
                    customHeaders = selectedModelSettings.customHeaders,
                    systemPrompt = systemPrompt,
                    tavilyApiKey = tavilyApiKey.trim(),
                    tavilyBaseUrl = normalizeTavilyBaseUrl(tavilyBaseUrl),
                    llmInactivityReconnectTimeoutSeconds =
                        normalizeLlmInactivityReconnectTimeoutSeconds(
                            llmInactivityReconnectTimeoutSeconds
                    ),
                    keepTasksRunningInBackground = keepTasksRunningInBackground,
                    notifyOnTaskCompletion = notifyOnTaskCompletion,
                    autoCleanOldCommandHistory = autoCleanOldCommandHistory,
                    oldCommandHistoryRetentionHours = normalizeOldCommandHistoryRetentionHours(
                        oldCommandHistoryRetentionHours
                    ),
                    termuxEnvironmentVariables = normalizeTermuxEnvironmentVariables(termuxEnvironmentVariables),
                    agentModeAuthorizationEnabled = agentModeAuthorizationEnabled,
                    agentModeAuthorizationMethod = agentModeAuthorizationMethod,
                    language = language,
                    themeMode = themeMode,
                    defaultChatModelKey = normalizeSelectableModelKey(defaultChatModelKey, modelOptions),
                    defaultTitleModelKey = normalizeSelectableModelKey(defaultTitleModelKey, modelOptions),
                    defaultNamingModelKey = normalizeSelectableModelKey(defaultNamingModelKey, modelOptions),
                    defaultCompactingModelKey = normalizeSelectableModelKey(defaultCompactingModelKey, modelOptions),
                    defaultVectorModelKey = normalizeSelectableModelKey(defaultVectorModelKey, catalogModelOptions),
                    defaultImageModelKey = normalizeSelectableModelKey(defaultImageModelKey, catalogModelOptions),
                    defaultAsrModelKey = normalizeAsrModelKey(defaultAsrModelKey, catalogModelOptions),
                    defaultTtsModelKey = normalizeSelectableModelKey(defaultTtsModelKey, catalogModelOptions),
                    ttsEnabled = ttsEnabled,
                    ttsVoiceId = ttsVoiceId,
                )
            )
            _uiState.update { current ->
                if (current.currentSessionId != DraftSessionId) return@update current
                if (current.draftSelectedModelKey == resolvedDefaultChatModelKey) current
                else current.copy(draftSelectedModelKey = resolvedDefaultChatModelKey)
            }
        }
    }

    // ── Multi-Provider methods ────────────────────────────────────────────────

    fun updateAppLanguage(language: AppLanguage) {
        viewModelScope.launch {
            settingsRepository.updateLanguage(language)
        }
    }

    fun updateAppThemeMode(themeMode: AppThemeMode) {
        viewModelScope.launch {
            settingsRepository.updateThemeMode(themeMode)
        }
    }

    fun updateUpaRenderUi(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateUpaRenderUi(enabled)
        }
    }

    fun updateUpaPluginRenderUi(pluginId: String, enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateUpaPluginRenderUi(pluginId, enabled)
        }
    }

    fun updateUpaPluginPermission(pluginId: String, wire: String, granted: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateUpaPluginPermission(pluginId, wire, granted)
        }
    }

    fun updateUpaPluginMcpBinding(pluginId: String, serverId: String, selected: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateUpaPluginMcpBinding(pluginId, serverId, selected)
        }
    }

    fun dispatchA2uiUserAction(pluginId: String, boundServerIds: List<String>, name: String) {
        val toolName = name.removePrefix("tool:").removePrefix("mcp:").trim()
        if (toolName.isBlank() || toolName.startsWith("transition:")) return
        viewModelScope.launch {
            val servers = boundServerIds.ifEmpty {
                _uiState.value.settings.upaMcpBindings[pluginId].orEmpty()
            }
            var called = false
            for (serverId in servers) {
                val result = mcpClientManager.callTool(serverId, toolName, org.json.JSONObject())
                if (result.isSuccess) {
                    called = true
                    break
                }
            }
            if (!called) {
                mcpClientManager.callToolByName(toolName, "{}")
            }
        }
    }

    fun upsertProviderConfig(config: LlmProviderConfig) {
        viewModelScope.launch {
            val normalizedConfig = normalizeProviderConfig(config)
            settingsRepository.upsertProviderConfig(normalizedConfig)
            if (
                normalizedConfig.authMethod != ProviderAuthMethod.OAuth ||
                normalizedConfig.oauthCredentialJson.isBlank()
            ) {
                runCatching { runtime.piKernelBridge.clearProviderCredential(normalizedConfig.id) }
            }
            captureAnalyticsEvent(
                event = "provider added",
                properties = mapOf(
                    "provider" to kira.ditto.data.PiProviderCatalog
                        .resolve(config.piProviderId).displayName,
                    "provider_id" to config.id,
                ),
            )
        }
    }

    fun removeProviderConfig(id: String) {
        viewModelScope.launch {
            settingsRepository.removeProviderConfig(id)
            runCatching { runtime.piKernelBridge.clearProviderCredential(id) }
            captureAnalyticsEvent(event = "provider removed")
        }
    }

    fun setProviderEnabled(
        id: String,
        enabled: Boolean,
    ) {
        viewModelScope.launch {
            settingsRepository.setProviderEnabled(id, enabled)
        }
    }

    fun setReasoningEffort(effort: String) {
        viewModelScope.launch {
            settingsRepository.updateSettings(
                _uiState.value.settings.copy(reasoningEffort = normalizeReasoningEffort(effort)),
            )
        }
    }

    fun setCurrentChatModelSelection(modelKey: String) {
        var didUpdate = false
        var sessionIdForPersistence: String? = null
        _uiState.update { current ->
            if (current.currentSessionId == DraftSessionId) {
                if (current.draftSelectedModelKey == modelKey) return@update current
                didUpdate = true
                current.copy(draftSelectedModelKey = modelKey)
            } else {
                val sessionIndex = current.sessions.indexOfFirst { it.id == current.currentSessionId }
                if (sessionIndex < 0) return@update current
                val session = current.sessions[sessionIndex]
                if (session.selectedModelKey == modelKey) return@update current
                val updatedSession = session.copy(selectedModelKey = modelKey)
                val updatedSessions = current.sessions.toMutableList().apply {
                    set(sessionIndex, updatedSession)
                }
                sessionIdForPersistence = current.currentSessionId
                didUpdate = true
                current.copy(sessions = updatedSessions)
            }
        }
        val persistedSessionId = sessionIdForPersistence
        if (didUpdate && persistedSessionId != null) {
            persistSessionMutation(persistedSessionId) { session ->
                if (session.selectedModelKey == modelKey) {
                    null
                } else {
                    session.copy(selectedModelKey = modelKey)
                }
            }
            val snapshot = _uiState.value
            val session = snapshot.sessions.firstOrNull { it.id == persistedSessionId }
            val machine = session?.remoteMachineId
                ?.takeIf(String::isNotBlank)
                ?.let { id -> snapshot.settings.remoteMachines.firstOrNull { it.id == id } }
            val kimiSessionId = session?.remoteKimiSessionId.orEmpty()
            val option = snapshot.chatModelOptions(session).findModelOption(modelKey)
            if (
                machine != null &&
                option != null &&
                kimiSessionId.isNotBlank() &&
                !kimiSessionId.startsWith("remote-pending-") &&
                !kimiSessionId.startsWith("aether-")
            ) {
                viewModelScope.launch {
                    runCatching {
                        runtime.piKernelBridge.updateRemoteSessionModel(
                            machine.baseUrl,
                            machine.token,
                            kimiSessionId,
                            option.modelId,
                        )
                    }
                }
            }
        }
    }

    fun setCurrentChatModelSelectionAndResolveThinkingLevels(
        modelKey: String,
        onResolved: (Boolean) -> Unit,
    ) {
        setCurrentChatModelSelection(modelKey)
        val current = _uiState.value
        val session = current.sessions.firstOrNull { it.id == current.currentSessionId }
        val option = current.chatModelOptions(session).firstOrNull { it.key == modelKey }
            ?: return onResolved(false)
        val cacheKey = thinkingCatalogKey(option.piProviderId, option.modelId)
        current.thinkingLevelsByProviderModel[cacheKey]?.let { levels ->
            onResolved(levels.isNotEmpty())
            return
        }
        if (session?.remoteMachineId?.isNotBlank() == true) {
            onResolved(false)
            return
        }
        viewModelScope.launch {
            val catalogResult = ProviderModelCatalogClient.resolveThinkingCatalog(option)
            if (
                catalogResult.levelsByProviderModel.isNotEmpty() ||
                catalogResult.reasoningModels.isNotEmpty()
            ) {
                settingsRepository.saveThinkingCatalogCache(
                    catalogResult.levelsByProviderModel,
                    catalogResult.levelMapsByProviderModel,
                    catalogResult.reasoningModels,
                )
                _uiState.update { state ->
                    state.copy(
                        thinkingLevelsByProviderModel =
                            state.thinkingLevelsByProviderModel + catalogResult.levelsByProviderModel,
                        thinkingLevelClampsByProviderModel =
                            (state.thinkingLevelClampsByProviderModel -
                                catalogResult.levelsByProviderModel.keys) +
                                catalogResult.levelMapsByProviderModel,
                        reasoningModels =
                            (state.reasoningModels - catalogResult.levelsByProviderModel.keys) +
                                catalogResult.reasoningModels,
                    )
                }
            }
            onResolved(catalogResult.levelsByProviderModel[cacheKey].orEmpty().isNotEmpty())
        }
    }

    fun refreshCurrentChatThinkingLevels() {
        val current = _uiState.value
        val session = current.sessions.firstOrNull { it.id == current.currentSessionId }
        if (session?.remoteMachineId?.isNotBlank() == true) return
        val selectedModelKey = session
            ?.selectedModelKey
            ?.takeIf(String::isNotBlank)
            ?: current.draftSelectedModelKey.takeIf(String::isNotBlank)
            ?: resolveDefaultChatModelKey(current.settings, current.providerConfigs)
        val option = current.providerConfigs.availableModelOptions()
            .firstOrNull { it.key == selectedModelKey }
            ?: return
        viewModelScope.launch {
            val catalogResult = ProviderModelCatalogClient.resolveThinkingCatalog(option)
            if (
                catalogResult.levelsByProviderModel.isEmpty() &&
                catalogResult.reasoningModels.isEmpty()
            ) {
                return@launch
            }
            settingsRepository.saveThinkingCatalogCache(
                catalogResult.levelsByProviderModel,
                catalogResult.levelMapsByProviderModel,
                catalogResult.reasoningModels,
            )
            _uiState.update { state ->
                state.copy(
                    thinkingLevelsByProviderModel =
                        state.thinkingLevelsByProviderModel + catalogResult.levelsByProviderModel,
                    thinkingLevelClampsByProviderModel =
                        (state.thinkingLevelClampsByProviderModel -
                            catalogResult.levelsByProviderModel.keys) +
                            catalogResult.levelMapsByProviderModel,
                    reasoningModels =
                        (state.reasoningModels - catalogResult.levelsByProviderModel.keys) +
                            catalogResult.reasoningModels,
                )
            }
        }
    }

    fun fetchModels(
        config: LlmProviderConfig,
        onComplete: (List<String>) -> Unit,
    ) {
        _uiState.update { it.copy(isFetchingModels = true) }
        viewModelScope.launch {
            val result = ProviderModelCatalogClient.fetchModels(
                config = config,
            )
            _uiState.update { current ->
                current.copy(
                    isFetchingModels = false,
                )
            }
            onComplete(result.models)
            if (result.error != null) {
                _transientMessages.emit(
                    UiText.Resource(R.string.message_fetch_models_failed, listOf(result.error)),
                )
            }
        }
    }

    fun startProviderLogin(
        providerConfigId: String,
        providerId: String,
        authMethod: ProviderAuthMethod,
        oauthFlow: String = "",
    ) {
        val normalizedProviderId = providerId.trim()
        if (normalizedProviderId.isBlank()) return
        if (authMethod == ProviderAuthMethod.Ambient) return
        providerAuthJob?.cancel()
        _uiState.update {
            it.copy(
                providerAuthState = PiProviderAuthState(
                    providerId = normalizedProviderId,
                    authMethod = authMethod,
                    isRunning = true,
                    statusMessage = if (authMethod == ProviderAuthMethod.OAuth) {
                        "Waiting for authorization."
                    } else {
                        "Waiting for credentials."
                    },
                )
            )
        }
        providerAuthJob = viewModelScope.launch {
            runCatching {
                runtime.piKernelBridge.loginProvider(
                    providerConfigId = providerConfigId,
                    providerId = normalizedProviderId,
                    authMethod = authMethod.storageValue,
                    oauthFlow = oauthFlow,
                ) { event, payload ->
                    _uiState.update { current ->
                        if (
                            current.providerAuthState.providerId != normalizedProviderId ||
                            current.providerAuthState.authMethod != authMethod
                        ) {
                            current
                        } else {
                            val state = current.providerAuthState
                            current.copy(
                                providerAuthState = when (event) {
                                    "auth_url" -> state.copy(
                                        authorizationUrl = payload.optString("url"),
                                        statusMessage = payload.optString("instructions")
                                            .ifBlank { "Complete authorization in your browser." },
                                    )

                                    "auth_device_code" -> state.copy(
                                        deviceCode = payload.optString("user_code"),
                                        verificationUrl = payload.optString("verification_uri"),
                                        statusMessage = "Enter the device code in your browser.",
                                    )

                                    "auth_prompt" -> state.copy(
                                        prompt = payload.toPiOAuthPrompt(),
                                        statusMessage = payload.optString("message"),
                                    )

                                    "auth_progress" -> state.copy(
                                        statusMessage = payload.optString("message"),
                                    )

                                    else -> state
                                }
                            )
                        }
                    }
                }
            }.fold(
                onSuccess = { payload ->
                    _uiState.update { current ->
                        if (
                            current.providerAuthState.providerId != normalizedProviderId ||
                            current.providerAuthState.authMethod != authMethod
                        ) {
                            current
                        } else {
                            current.copy(
                                providerAuthState = current.providerAuthState.copy(
                                    isRunning = false,
                                    prompt = null,
                                    apiKey = payload.optString("api_key"),
                                    oauthCredentialJson = payload.optJSONObject("oauth_credential")
                                        ?.toString()
                                        .orEmpty(),
                                    providerEnvironmentVariables =
                                        payload.toPiProviderEnvironmentVariables(),
                                    statusMessage = if (authMethod == ProviderAuthMethod.OAuth) {
                                        "Connected with OAuth."
                                    } else {
                                        "API key configured."
                                    },
                                    errorMessage = "",
                                )
                            )
                        }
                    }
                },
                onFailure = { throwable ->
                    if (throwable is CancellationException) return@fold
                    _uiState.update { current ->
                        if (
                            current.providerAuthState.providerId != normalizedProviderId ||
                            current.providerAuthState.authMethod != authMethod
                        ) {
                            current
                        } else {
                            current.copy(
                                providerAuthState = current.providerAuthState.copy(
                                    isRunning = false,
                                    prompt = null,
                                    errorMessage = throwable.userFacingMessage(),
                                    statusMessage = "",
                                )
                            )
                        }
                    }
                },
            )
        }
    }

    fun submitProviderAuthPrompt(
        promptId: String,
        value: String,
        cancelled: Boolean = false,
    ) {
        viewModelScope.launch {
            runCatching {
                runtime.piKernelBridge.submitAuthPrompt(
                    promptId = promptId,
                    value = value,
                    cancelled = cancelled,
                )
            }.fold(
                onSuccess = {
                    _uiState.update { current ->
                        if (current.providerAuthState.prompt?.id != promptId) {
                            current
                        } else {
                            current.copy(
                                providerAuthState = current.providerAuthState.copy(prompt = null)
                            )
                        }
                    }
                },
                onFailure = { throwable ->
                    _uiState.update { current ->
                        if (current.providerAuthState.prompt?.id != promptId) {
                            current
                        } else {
                            current.copy(
                                providerAuthState = current.providerAuthState.copy(
                                    errorMessage = throwable.userFacingMessage(),
                                )
                            )
                        }
                    }
                },
            )
        }
    }

    fun clearProviderAuthState() {
        providerAuthJob?.cancel()
        providerAuthJob = null
        _uiState.update { it.copy(providerAuthState = PiProviderAuthState()) }
    }

    private fun mergeFetchedModels(
        current: LlmProviderConfig,
        fetchedModels: List<String>,
    ): LlmProviderConfig {
        val normalizedCurrent = normalizeProviderConfig(current)
        val previousModels = normalizedCurrent.cachedModels.toSet()
        val normalizedFetched = fetchedModels
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        val enabledModels = autoEnableFetchedModels(
            fetched = normalizedFetched,
            previouslyEnabled = normalizedCurrent.enabledModelIds,
            previousCached = previousModels,
            preferredModelId = normalizedCurrent.modelId,
        )
        return normalizeProviderConfig(
            normalizedCurrent.copy(
                modelId = when {
                    normalizedCurrent.modelId in enabledModels -> normalizedCurrent.modelId
                    enabledModels.isNotEmpty() -> enabledModels.first()
                    normalizedFetched.isNotEmpty() -> normalizedFetched.first()
                    else -> normalizedCurrent.modelId
                },
                cachedModels = normalizedFetched,
                enabledModelIds = enabledModels,
            )
        )
    }

    fun installSkillFromDirectory(treeUri: Uri) {
        performSkillInstall {
            skillManager.installSkillFromDirectory(treeUri)
        }
    }

    fun installSkillFromZip(
        zipUri: Uri,
        onComplete: (Boolean) -> Unit = {},
    ) {
        performSkillInstall(onComplete = onComplete) {
            skillManager.installSkillFromZipUri(zipUri)
        }
    }

    fun installSkillFromRemote(
        url: String,
        onComplete: (Boolean) -> Unit = {},
    ) {
        val trimmedUrl = url.trim()
        if (trimmedUrl.isBlank()) {
            onComplete(false)
            return
        }
        performSkillInstall(onComplete = onComplete) {
            skillManager.installSkillFromRemote(trimmedUrl)
        }
    }

    fun removeSkill(skillId: String) {
        viewModelScope.launch {
            val result = skillManager.uninstallSkill(skillId)
            if (result.isSuccess) {
                piKernelBridge.reloadAllExtensions(runtime.piExtensionStateRepository.loadOptions())
            }
            captureAnalyticsEvent(
                event = "skill removed",
                properties = mapOf("skill_id" to skillId),
            )
        }
    }

    fun setSkillEnabled(
        skillId: String,
        enabled: Boolean,
    ) {
        viewModelScope.launch {
            extensionsRepository.setSkillEnabled(skillId, enabled)
            piKernelBridge.reloadAllExtensions(runtime.piExtensionStateRepository.loadOptions())
        }
    }

    fun refreshPiExtensions() {
        viewModelScope.launch {
            refreshPiExtensionState(loadCatalog = true)
        }
    }

    private fun refreshImportedPiExtensions() {
        val generation = ++piExtensionRefreshGeneration
        viewModelScope.launch {
            val result = piExtensionManager.listImported()
            if (generation == piExtensionRefreshGeneration) {
                publishImportedPiExtensions(result)
            }
        }
    }

    fun loadPiPackageDetails(entry: PiExtensionCatalogEntry) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    selectedPiPackageSource = entry.source,
                    selectedPiPackageDetails = null,
                    isLoadingPiPackageDetails = true,
                    piPackageDetailsError = "",
                )
            }
            val result = piExtensionManager.fetchPackageDetails(entry)
            _uiState.update { current ->
                if (current.selectedPiPackageSource != entry.source) {
                    current
                } else {
                    current.copy(
                        selectedPiPackageDetails = result.getOrNull(),
                        isLoadingPiPackageDetails = false,
                        piPackageDetailsError = result.exceptionOrNull()?.userFacingMessage().orEmpty(),
                    )
                }
            }
        }
    }

    fun installPiExtensionPackage(source: String) {
        performPiExtensionOperation(source) {
            piExtensionManager.installPackage(source)
        }
    }

    fun updatePiExtensionPackage(source: String) {
        performPiExtensionOperation(source) {
            piExtensionManager.updatePackage(source)
        }
    }

    fun removePiExtension(extension: InstalledPiExtension) {
        performPiExtensionOperation(extension.id) {
            piExtensionManager.remove(extension)
        }
    }

    fun setPiExtensionEnabled(
        extension: InstalledPiExtension,
        enabled: Boolean,
    ) {
        piExtensionRefreshGeneration += 1
        _uiState.update { current ->
            current.copy(
                installedPiExtensions = current.installedPiExtensions.map { installed ->
                    if (installed.id == extension.id) installed.copy(isEnabled = enabled) else installed
                },
            )
        }
        performPiExtensionOperation(extension.id) {
            piExtensionManager.setEnabled(extension, enabled)
        }
    }

    fun importPiExtension(
        uri: Uri,
        onComplete: (Boolean) -> Unit = {},
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(piExtensionOperationSource = "import") }
            val result = piExtensionManager.importFromUri(uri)
            result
                .onSuccess { extension ->
                    emitTransientMessage(
                        uiString(R.string.message_pi_extension_imported, extension.name)
                    )
                }
                .onFailure { throwable ->
                    emitTransientMessage(
                        uiString(
                            R.string.message_pi_extension_operation_failed,
                            throwable.userFacingMessage(),
                        )
                    )
                }
            refreshPiExtensionState(loadCatalog = false)
            _uiState.update { it.copy(piExtensionOperationSource = "") }
            onComplete(result.isSuccess)
        }
    }

    private fun performPiExtensionOperation(
        operationSource: String,
        operation: suspend () -> Result<Unit>,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(piExtensionOperationSource = operationSource) }
            val result = operation()
            result
                .onSuccess {
                    emitTransientMessage(uiString(R.string.message_pi_extension_updated))
                }
                .onFailure { throwable ->
                    emitTransientMessage(
                        uiString(
                            R.string.message_pi_extension_operation_failed,
                            throwable.userFacingMessage(),
                        )
                    )
                }
            refreshPiExtensionState(loadCatalog = false)
            _uiState.update { it.copy(piExtensionOperationSource = "") }
        }
    }

    private suspend fun refreshPiExtensionState(loadCatalog: Boolean) {
        val generation = ++piExtensionRefreshGeneration
        _uiState.update { it.copy(isLoadingPiExtensions = true) }
        val installedResult = piExtensionManager.listInstalled()
        runtime.nativeModManager.refreshDiscovery()
        val catalogResult = if (loadCatalog) {
            piExtensionManager.fetchCatalog()
        } else {
            null
        }
        if (generation != piExtensionRefreshGeneration) return
        _uiState.update { current ->
            current.copy(
                installedPiExtensions = installedResult.getOrDefault(current.installedPiExtensions),
                hasLoadedInstalledPiExtensions = true,
                piExtensionCatalog = catalogResult?.getOrDefault(current.piExtensionCatalog)
                    ?: current.piExtensionCatalog,
                isLoadingPiExtensions = false,
                piExtensionCatalogError = catalogResult?.exceptionOrNull()?.userFacingMessage()
                    ?: if (loadCatalog) "" else current.piExtensionCatalogError,
            )
        }
        installedResult.exceptionOrNull()?.let { throwable ->
            emitTransientMessage(
                uiString(
                    R.string.message_pi_extension_operation_failed,
                    throwable.userFacingMessage(),
                )
            )
        }
    }

    private fun publishImportedPiExtensions(
        result: Result<List<InstalledPiExtension>>,
    ) {
        _uiState.update { current ->
            current.copy(
                installedPiExtensions = result.getOrNull()?.let { imported ->
                    mergeImportedPiExtensions(current.installedPiExtensions, imported)
                } ?: current.installedPiExtensions,
                hasLoadedInstalledPiExtensions = true,
            )
        }
    }

    fun setComposerSkillSelected(
        skillId: String,
        selected: Boolean,
    ) {
        val operation = "skills.selection"
        if (
            !modKernel.operations.hasInterceptors(operation) &&
            "operation:$operation" !in aetherAppExtensionManager.state.value.snapshot.eventNames
        ) {
            setComposerSkillSelectedNow(skillId, selected)
            return
        }
        viewModelScope.launch {
            val result = dispatchAetherOperation(
                operation = operation,
                payload = JSONObject()
                    .put("skill_id", skillId)
                    .put("selected", selected),
            )
            if (result.cancelled) return@launch
            val resolvedSkillId = result.payload.optString("skill_id", skillId)
            val resolvedSelected = result.payload.optBoolean("selected", selected)
            withContext(Dispatchers.Main.immediate) {
                setComposerSkillSelectedNow(resolvedSkillId, resolvedSelected)
            }
        }
    }

    private fun setComposerSkillSelectedNow(
        skillId: String,
        selected: Boolean,
    ) {
        var didUpdate = false
        var sessionIdForPersistence: String? = null
        _uiState.update { current ->
            if (current.currentSessionId == DraftSessionId) {
                val updatedDraftSelection = updateOrderedSelection(
                    current.draftSelectedSkillIds,
                    skillId,
                    selected,
                )
                if (updatedDraftSelection == current.draftSelectedSkillIds) {
                    current
                } else {
                    didUpdate = true
                    current.copy(draftSelectedSkillIds = updatedDraftSelection)
                }
            } else {
                val sessionIndex = current.sessions.indexOfFirst { it.id == current.currentSessionId }
                if (sessionIndex < 0) return@update current
                val updatedSessions = current.sessions.toMutableList()
                val session = updatedSessions.removeAt(sessionIndex)
                val updatedSelectedSkillIds = updateOrderedSelection(
                    session.selectedSkillIds,
                    skillId,
                    selected,
                )
                val updatedActiveSkills = session.activeSkills.filter { activeSkill ->
                    updatedSelectedSkillIds.contains(activeSkill.skillId)
                }
                if (
                    updatedSelectedSkillIds == session.selectedSkillIds &&
                    updatedActiveSkills == session.activeSkills
                ) {
                    updatedSessions.add(sessionIndex, session)
                    current
                } else {
                    didUpdate = true
                    val updatedSession = session.copy(
                        selectedSkillIds = updatedSelectedSkillIds,
                        activeSkills = updatedActiveSkills,
                    )
                    sessionIdForPersistence = current.currentSessionId
                    updatedSessions.add(
                        sessionIndex.coerceAtMost(updatedSessions.size),
                        updatedSession,
                    )
                    current.copy(sessions = updatedSessions)
                }
            }
        }
        val persistedSessionId = sessionIdForPersistence
        if (didUpdate && persistedSessionId != null) {
            persistSessionMutation(persistedSessionId) { session ->
                val selectedSkillIds = updateOrderedSelection(
                    session.selectedSkillIds,
                    skillId,
                    selected,
                )
                val activeSkills = session.activeSkills.filter { activeSkill ->
                    selectedSkillIds.contains(activeSkill.skillId)
                }
                if (
                    selectedSkillIds == session.selectedSkillIds &&
                    activeSkills == session.activeSkills
                ) {
                    null
                } else {
                    session.copy(
                        selectedSkillIds = selectedSkillIds,
                        activeSkills = activeSkills,
                    )
                }
            }
        }
    }

    fun saveStreamableHttpMcpServer(
        serverId: String?,
        displayName: String,
        url: String,
        headersRaw: String,
    ) {
        val trimmedName = displayName.trim()
        val trimmedUrl = url.trim()
        if (trimmedName.isBlank() || trimmedUrl.isBlank()) return
        if (serverId != null && kira.ditto.data.isShippedMcpServerId(serverId)) return
        viewModelScope.launch {
            val existingServer = serverId?.let(::findMcpServerById)
            val now = System.currentTimeMillis()
            val assignedId = existingServer?.id ?: "mcp-$now"
            val drafted = McpServerConfig(
                id = assignedId,
                displayName = trimmedName,
                actionLabel = kira.ditto.data.generateQuickActionLabel(
                    trimmedName,
                    trimmedUrl,
                ),
                transport = kira.ditto.data.McpTransportConfig.StreamableHttp(
                    url = trimmedUrl,
                    headers = parseKeyValueLines(headersRaw),
                ),
                isEnabled = existingServer?.isEnabled ?: true,
                connectTimeoutMillis = existingServer?.connectTimeoutMillis ?: 15_000L,
                requestTimeoutMillis = existingServer?.requestTimeoutMillis ?: 60_000L,
                createdAtMillis = existingServer?.createdAtMillis ?: now,
                updatedAtMillis = now,
            )
            val stored = extractInlineMcpSecrets(drafted) { id, value ->
                runCatching { hostSecretStore.put(id, value) }
            }
            extensionsRepository.upsertMcpServer(stored)
            if (existingServer != null) {
                mcpClientManager.disconnect(existingServer.id)
            }
            captureAnalyticsEvent(
                event = "mcp server added",
                properties = mapOf("transport" to "streamable_http"),
            )
        }
    }

    fun saveStdIoMcpServer(
        serverId: String?,
        displayName: String,
        command: String,
        argumentsRaw: String,
        workingDirectory: String,
        environmentRaw: String,
        runtimeEnvironment: LocalRuntimeId?,
    ) {
        val trimmedName = displayName.trim()
        val trimmedCommand = command.trim()
        if (trimmedName.isBlank() || trimmedCommand.isBlank()) return
        if (serverId != null && kira.ditto.data.isShippedMcpServerId(serverId)) return
        viewModelScope.launch {
            val existingServer = serverId?.let(::findMcpServerById)
            val now = System.currentTimeMillis()
            val assignedId = existingServer?.id ?: "mcp-$now"
            val drafted = McpServerConfig(
                id = assignedId,
                displayName = trimmedName,
                actionLabel = kira.ditto.data.generateQuickActionLabel(
                    trimmedName,
                    trimmedCommand,
                ),
                transport = kira.ditto.data.McpTransportConfig.StdIo(
                    command = trimmedCommand,
                    arguments = parseNonBlankLines(argumentsRaw),
                    workingDirectory = workingDirectory.trim(),
                    environment = parseKeyValueLines(environmentRaw),
                    runtimeEnvironment = runtimeEnvironment,
                ),
                isEnabled = existingServer?.isEnabled ?: true,
                connectTimeoutMillis = existingServer?.connectTimeoutMillis ?: 15_000L,
                requestTimeoutMillis = existingServer?.requestTimeoutMillis ?: 60_000L,
                createdAtMillis = existingServer?.createdAtMillis ?: now,
                updatedAtMillis = now,
            )
            val stored = extractInlineMcpSecrets(drafted) { id, value ->
                runCatching { hostSecretStore.put(id, value) }
            }
            extensionsRepository.upsertMcpServer(stored)
            if (existingServer != null) {
                mcpClientManager.disconnect(existingServer.id)
            }
            captureAnalyticsEvent(
                event = "mcp server added",
                properties = mapOf("transport" to "stdio"),
            )
        }
    }

    fun saveScheduledTask(
        existingTaskId: String?,
        name: String,
        prompt: String,
        schedule: ScheduledTaskSchedule,
        enabled: Boolean,
    ) {
        viewModelScope.launch {
            val existing = existingTaskId
                ?.takeIf(String::isNotBlank)
                ?.let { scheduledTaskManager.findTask(it) }
            val task = (existing ?: ScheduledTask(
                name = name.trim().ifBlank { "Scheduled task" },
                prompt = prompt.trim(),
                schedule = schedule,
                createdBy = ScheduledTaskCreator.User,
                sessionId = "",
            )).copy(
                name = name.trim().ifBlank { "Scheduled task" },
                prompt = prompt.trim(),
                schedule = schedule,
                isEnabled = enabled,
                sessionId = "",
            )
            if (task.prompt.isBlank()) {
                emitTransientMessage(uiString(R.string.message_scheduled_task_prompt_required))
                return@launch
            }
            val synced = syncScheduledTaskToKimiCron(task)
            scheduledTaskManager.upsertTask(synced)
        }
    }

    fun setScheduledTaskEnabled(
        taskId: String,
        enabled: Boolean,
    ) {
        viewModelScope.launch {
            val updated = scheduledTaskManager.setTaskEnabled(taskId, enabled)
            if (updated != null) {
                scheduledTaskManager.upsertTask(syncScheduledTaskToKimiCron(updated))
                return@launch
            }
            val displayed = _uiState.value.scheduledTasks.firstOrNull { it.id == taskId } ?: return@launch
            val mirrored = displayed.copy(isEnabled = enabled)
            if (enabled) {
                scheduledTaskManager.upsertTask(syncScheduledTaskToKimiCron(mirrored))
            } else {
                withContext(Dispatchers.IO) { kimiCronStore.remove(mirrored) }
            }
        }
    }

    fun removeScheduledTask(taskId: String) {
        viewModelScope.launch {
            val task = scheduledTaskManager.findTask(taskId)
                ?: _uiState.value.scheduledTasks.firstOrNull { it.id == taskId }
            if (task != null) {
                withContext(Dispatchers.IO) { kimiCronStore.remove(task) }
            }
            scheduledTaskManager.removeTask(taskId)
        }
    }

    fun refreshScheduledTasksFromDisk() {
        viewModelScope.launch(Dispatchers.IO) {
            val stored = scheduledTaskManager.snapshot()
            val merged = runCatching { kimiCronStore.mergeWithDisk(stored) }.getOrDefault(stored)
            persistDiscoveredCronTasks(stored, merged)
            _uiState.update { current -> current.copy(scheduledTasks = merged) }
        }
    }

    private suspend fun persistDiscoveredCronTasks(
        stored: List<ScheduledTask>,
        merged: List<ScheduledTask>,
    ) {
        val storedIds = stored.map { it.id }.toHashSet()
        merged.filter { task -> task.id !in storedIds }.forEach { extra ->
            runCatching { scheduledTaskManager.upsertTask(extra) }
        }
    }

    private suspend fun syncScheduledTaskToKimiCron(task: ScheduledTask): ScheduledTask {
        if (task.isGuiWatchTask()) return task
        // App-wide AlarmManager is the source of truth. Do not attach jobs to
        // the currently open Kimi conversation — those only fire while that
        // chat's ACP session is alive.
        return task.copy(sessionId = "")
    }

    /**
     * The space a conversation belongs to, defaulting for ids that are not conversations at all
     * (an MCP connectivity probe, a warm-up before the session row exists).
     */
    private fun workspaceIdForSession(sessionId: String): String =
        _uiState.value.sessions.firstOrNull { it.id == sessionId }?.workspaceId
            ?: _uiState.value.sessionSummaries.firstOrNull { it.id == sessionId }?.workspaceId
            ?: _uiState.value.currentWorkspaceId

    /** The space the user is currently in, which new conversations inherit. */
    private fun currentWorkspaceId(): String = _uiState.value.currentWorkspaceId

    private fun maybeLoadWorkspaces() {
        if (didLoadWorkspaces) return
        didLoadWorkspaces = true
        refreshWorkspaces()
    }

    private fun refreshWorkspaces() {
        viewModelScope.launch {
            val rows = runtime.chatRepository.listWorkspaces()
            _uiState.update { current ->
                current.copy(
                    workspaces = rows.map { WorkspaceSummary(id = it.id, name = it.name) },
                )
            }
        }
    }

    fun createWorkspace(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            runtime.chatRepository.upsertWorkspace(
                kira.ditto.data.chatdb.WorkspaceEntity(
                    id = newWorkspaceId(trimmed),
                    name = trimmed,
                    createdAtMillis = System.currentTimeMillis(),
                    sortOrder = System.currentTimeMillis(),
                ),
            )
            refreshWorkspaces()
        }
    }

    fun renameWorkspace(workspaceId: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val existing = runtime.chatRepository.listWorkspaces()
                .firstOrNull { it.id == workspaceId } ?: return@launch
            runtime.chatRepository.upsertWorkspace(existing.copy(name = trimmed))
            refreshWorkspaces()
        }
    }

    /**
     * Delete a space and its files. Its conversations move to the default space rather than
     * disappearing with it - the directory is the space's, the transcript is the user's.
     */
    fun deleteWorkspace(workspaceId: String) {
        if (workspaceId == kira.ditto.data.chatdb.DefaultWorkspaceId) return
        viewModelScope.launch {
            runtime.chatRepository.deleteWorkspace(workspaceId)
            workspaceFileBridge.deleteWorkspaceDirectory(workspaceId)
            refreshWorkspaces()
            if (currentWorkspaceId() == workspaceId) {
                _uiState.update { it.copy(currentWorkspaceId = "") }
                selectWorkspace(kira.ditto.data.chatdb.DefaultWorkspaceId)
            }
        }
    }

    /**
     * Enter a space: show its conversations, and start a fresh context window.
     *
     * The cut is the point of the space boundary. Landing on the space's most recent conversation
     * keeps the drawer feeling like a place you return to rather than a filter you applied.
     */
    fun selectWorkspace(workspaceId: String) {
        if (currentWorkspaceId() == workspaceId) return
        runtime.alpineRuntime.requestSessionMemoryNewContext()
        _uiState.update { it.copy(currentWorkspaceId = workspaceId) }
        val target = _uiState.value.sessionSummaries
            .filter { it.workspaceId == workspaceId }
            .maxByOrNull { it.lastMessageAtMillis ?: 0L }
        if (target != null) {
            selectSession(target.id)
        } else {
            startNewChat()
        }
    }

    /**
     * Space ids double as directory names under `/workspace`, and Kimi Code CLI slugifies the
     * basename when it derives its own workspace key. Keeping the id ASCII-safe here means the
     * guest path and the CLI's registry entry stay readable as the same thing.
     */
    private fun newWorkspaceId(name: String): String {
        val slug = name.lowercase()
            .map { if (it.isLetterOrDigit() && it.code < 128) it else '-' }
            .joinToString("")
            .trim('-')
            .take(24)
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        return if (slug.isEmpty()) "space-$suffix" else "$slug-$suffix"
    }

    /**
     * Drop space directories nothing points at any more.
     *
     * Deleting a space removes its directory directly; this is the sweep for the cases that path
     * cannot cover - a delete attempted while the guest was down, and the files left in the shared
     * root from before spaces existed. One of those leftovers was picked up by a brand-new
     * conversation and answered as if it were the question, which is what started this work.
     */
    private fun maybeSweepOrphanWorkspaces() {
        if (didSweepOrphanWorkspaces) return
        didSweepOrphanWorkspaces = true
        viewModelScope.launch {
            val knownIds = runtime.chatRepository.listWorkspaces().map { it.id }
            workspaceFileBridge.deleteOrphanWorkspaceDirectories(knownIds)
        }
    }

    fun testMcpServer(
        serverId: String,
        operation: McpServerTestOperation,
        onComplete: (String) -> Unit,
    ) {
        val server = findMcpServerById(serverId)
        if (server == null) {
            onComplete("MCP server '$serverId' was not found.")
            return
        }
        viewModelScope.launch {
            val workspaceDirectory =
                workspaceFileBridge.workspaceDirectory(kira.ditto.data.chatdb.DefaultWorkspaceId)
            val result = mcpClientManager.testServer(
                server = applyMcpSecrets(server, hostSecretStore.asLookup()),
                workspaceDirectory = workspaceDirectory,
                operation = operation,
                settings = _uiState.value.settings,
            )
            onComplete(
                result.fold(
                    onSuccess = { output -> formatMcpTestOutput(operation, output) },
                    onFailure = { throwable -> "Test failed: ${throwable.message ?: "Unknown MCP error."}" },
                )
            )
        }
    }

    fun removeMcpServer(serverId: String) {
        if (kira.ditto.data.isShippedMcpServerId(serverId)) return
        if (kira.ditto.upa.isUpaMcpServerId(serverId)) {
            uninstallUpaPlugin(serverId.removePrefix(kira.ditto.upa.UpaMcpServerIdPrefix))
            return
        }
        viewModelScope.launch {
            findMcpServerById(serverId)?.let { server ->
                inferMcpSecretSlots(server).forEach { slot ->
                    runCatching { hostSecretStore.delete(slot.id) }
                }
            }
            extensionsRepository.removeMcpServer(serverId)
            mcpClientManager.disconnect(serverId)
        }
    }

    fun setMcpServerEnabled(
        serverId: String,
        enabled: Boolean,
    ) {
        viewModelScope.launch {
            if (kira.ditto.data.isShippedMcpServerId(serverId)) {
                val persisted = extensionsRepository.extensionState.first().mcpServers
                if (persisted.none { it.id == serverId }) {
                    val stock = kira.ditto.data.shippedMcpServerConfig(serverId) ?: return@launch
                    extensionsRepository.upsertMcpServer(stock.copy(isEnabled = enabled))
                    if (!enabled) mcpClientManager.disconnect(serverId)
                    return@launch
                }
            }
            extensionsRepository.setMcpServerEnabled(serverId, enabled)
            if (!enabled) {
                mcpClientManager.disconnect(serverId)
            }
        }
    }

    fun setComposerMcpServerSelected(
        serverId: String,
        selected: Boolean,
    ) {
        var didUpdate = false
        var sessionIdForPersistence: String? = null
        _uiState.update { current ->
            if (current.currentSessionId == DraftSessionId) {
                val updatedDraftSelection = updateOrderedSelection(
                    current.draftSelectedMcpServerIds,
                    serverId,
                    selected,
                )
                if (updatedDraftSelection == current.draftSelectedMcpServerIds) {
                    current
                } else {
                    didUpdate = true
                    current.copy(draftSelectedMcpServerIds = updatedDraftSelection)
                }
            } else {
                val sessionIndex = current.sessions.indexOfFirst { it.id == current.currentSessionId }
                if (sessionIndex < 0) return@update current
                val updatedSessions = current.sessions.toMutableList()
                val session = updatedSessions.removeAt(sessionIndex)
                val updatedActiveIds = updateOrderedSelection(
                    session.activeMcpServerIds,
                    serverId,
                    selected,
                )
                if (updatedActiveIds == session.activeMcpServerIds) {
                    updatedSessions.add(sessionIndex, session)
                    current
                } else {
                    didUpdate = true
                    val updatedSession = session.copy(activeMcpServerIds = updatedActiveIds)
                    sessionIdForPersistence = current.currentSessionId
                    updatedSessions.add(
                        sessionIndex.coerceAtMost(updatedSessions.size),
                        updatedSession,
                    )
                    current.copy(sessions = updatedSessions)
                }
            }
        }
        val persistedSessionId = sessionIdForPersistence
        if (didUpdate && persistedSessionId != null) {
            persistSessionMutation(persistedSessionId) { session ->
                val activeMcpServerIds = updateOrderedSelection(
                    session.activeMcpServerIds,
                    serverId,
                    selected,
                )
                if (activeMcpServerIds == session.activeMcpServerIds) {
                    null
                } else {
                    session.copy(activeMcpServerIds = activeMcpServerIds)
                }
            }
        }
        if (didUpdate && selected && kira.ditto.data.GmailMcp.isShippedServerId(serverId)) {
            connectGmailMcp()
        }
        if (didUpdate && selected && kira.ditto.data.SpotifyMcp.isShippedServerId(serverId)) {
            connectSpotifyMcp()
        }
        if (didUpdate && selected && kira.ditto.data.AmapMcp.isShippedServerId(serverId)) {
            connectAmapMcp()
        }
        if (didUpdate && selected && kira.ditto.data.GithubMcp.isShippedServerId(serverId)) {
            connectGithubMcp()
        }
        if (didUpdate && selected && kira.ditto.data.HuggingFaceMcp.isShippedServerId(serverId)) {
            connectHuggingFaceMcp()
        }
    }

    fun connectGmailMcp() {
        viewModelScope.launch(Dispatchers.IO) {
            if (kira.ditto.data.GmailAuth.snapshot(hostSecretStore).isConnected) return@launch
            runCatching {
                kira.ditto.data.GmailAuth.connectWithBrowser(getApplication(), hostSecretStore)
            }.onFailure { error ->
                val detail = error.message?.trim().orEmpty()
                emitTransientMessage(
                    if (detail.isNotBlank()) UiText.Raw(detail)
                    else uiString(R.string.settings_gmail_mcp_connect_failed),
                )
            }
        }
    }

    fun connectSpotifyMcp() {
        viewModelScope.launch(Dispatchers.IO) {
            if (kira.ditto.data.SpotifyAuth.snapshot(hostSecretStore).isConnected) return@launch
            runCatching {
                kira.ditto.data.SpotifyAuth.connectWithBrowser(getApplication(), hostSecretStore)
            }.onFailure { error ->
                val detail = error.message?.trim().orEmpty()
                emitTransientMessage(
                    if (detail.isNotBlank()) UiText.Raw(detail)
                    else uiString(R.string.settings_spotify_mcp_connect_failed),
                )
            }
        }
    }

    fun connectAmapMcp() {
        viewModelScope.launch(Dispatchers.IO) {
            if (kira.ditto.data.AmapAuth.snapshot(hostSecretStore).isConnected) return@launch
            runCatching {
                kira.ditto.data.AmapAuth.openKeyConsole(getApplication())
            }.onFailure { error ->
                val detail = error.message?.trim().orEmpty()
                emitTransientMessage(
                    if (detail.isNotBlank()) UiText.Raw(detail)
                    else uiString(R.string.settings_amap_mcp_open_console_failed),
                )
            }
        }
    }

    fun connectGithubMcp() {
        viewModelScope.launch(Dispatchers.IO) {
            if (kira.ditto.data.GithubAuth.snapshot(hostSecretStore).isConnected) return@launch
            runCatching {
                kira.ditto.data.GithubAuth.openConsole(getApplication())
            }.onFailure { error ->
                val detail = error.message?.trim().orEmpty()
                emitTransientMessage(
                    if (detail.isNotBlank()) UiText.Raw(detail)
                    else uiString(R.string.settings_github_mcp_open_console_failed),
                )
            }
        }
    }

    fun connectHuggingFaceMcp() {
        viewModelScope.launch(Dispatchers.IO) {
            if (kira.ditto.data.HuggingFaceAuth.snapshot(hostSecretStore).isConnected) return@launch
            runCatching {
                kira.ditto.data.HuggingFaceAuth.openConsole(getApplication())
            }.onFailure { error ->
                val detail = error.message?.trim().orEmpty()
                emitTransientMessage(
                    if (detail.isNotBlank()) UiText.Raw(detail)
                    else uiString(R.string.settings_huggingface_mcp_open_console_failed),
                )
            }
        }
    }

    /**
     * Attaches or clears a composer directive chip ("goal"/"swarm"/"tower",
     * any other value clears). Swarm stays selected across turns until
     * the user removes the chip; the directive still rides on each sent
     * message.
     */
    fun setComposerPromptDirective(directive: String) {
        val normalized = ComposerPromptDirective.fromStorageValue(directive)?.storageValue.orEmpty()
        _uiState.update { current ->
            if (current.draftPromptDirective == normalized) {
                current
            } else {
                current.copy(draftPromptDirective = normalized)
            }
        }
    }

    fun setComposerAgentModeSelected(selected: Boolean) {
        var didUpdate = false
        var persistedSelected = false
        var sessionIdForPersistence: String? = null
        _uiState.update { current ->
            persistedSelected = selected
            if (current.currentSessionId == DraftSessionId) {
                if (current.draftAgentModeEnabled == selected) {
                    current
                } else {
                    didUpdate = true
                    current.copy(draftAgentModeEnabled = selected)
                }
            } else {
                val sessionIndex = current.sessions.indexOfFirst { it.id == current.currentSessionId }
                if (sessionIndex < 0) return@update current
                val updatedSessions = current.sessions.toMutableList()
                val session = updatedSessions.removeAt(sessionIndex)
                if (session.agentModeEnabled == selected) {
                    updatedSessions.add(sessionIndex, session)
                    current
                } else {
                    didUpdate = true
                    val updatedSession = session.copy(agentModeEnabled = selected)
                    sessionIdForPersistence = current.currentSessionId
                    updatedSessions.add(
                        sessionIndex.coerceAtMost(updatedSessions.size),
                        updatedSession,
                    )
                    current.copy(sessions = updatedSessions)
                }
            }
        }
        val persistedSessionId = sessionIdForPersistence
        if (didUpdate && persistedSessionId != null) {
            persistSessionMutation(persistedSessionId) { session ->
                if (session.agentModeEnabled == selected) {
                    null
                } else {
                    session.copy(agentModeEnabled = selected)
                }
            }
        }
        if (didUpdate) {
            captureAnalyticsEvent(
                event = "agent mode toggled",
                properties = mapOf("enabled" to persistedSelected),
            )
            prewarmKimiWorkspaceIfPossible(forceRestart = true)
        }
        if (selected) {
            sealAndClearBrowserDesk(persistSources = false)
            requestEnableAgentModeShizuku()
        } else if (didUpdate) {
            stopAgentModeDisplay()
        }
    }

    private fun currentComposerAgentModeSelected(): Boolean {
        val snapshot = _uiState.value
        return if (snapshot.currentSessionId == DraftSessionId) {
            snapshot.draftAgentModeEnabled
        } else {
            snapshot.sessions.firstOrNull { it.id == snapshot.currentSessionId }?.agentModeEnabled == true
        }
    }

    private fun startAgentModeDisplay() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { runtime.shizukuProvisioner.ensureReady() }
            val settings = _uiState.value.settings.copy(
                agentModeAuthorizationEnabled = true,
                agentModeAuthorizationMethod = AgentModeAuthorizationMethod.Shizuku,
            )
            runCatching {
                agentModeController.execute(
                    settings = settings,
                    workspaceDirectory = runtime.alpineRuntime.workspaceRoot,
                    termuxWorkspaceDirectory = runtime.alpineRuntime.workspaceRoot,
                    argumentsJson = JSONObject()
                        .put("action", "start")
                        .put("skip_capture", true)
                        .toString(),
                )
            }
        }
    }

    fun setComposerChromeSelected(selected: Boolean) {
        var didUpdate = false
        var sessionIdForPersistence: String? = null
        _uiState.update { current ->
            val resolvedSelected = selected
            if (current.currentSessionId == DraftSessionId) {
                if (current.draftChromeEnabled == resolvedSelected) {
                    current
                } else {
                    didUpdate = true
                    current.copy(draftChromeEnabled = resolvedSelected)
                }
            } else {
                val sessionIndex = current.sessions.indexOfFirst { it.id == current.currentSessionId }
                if (sessionIndex < 0) return@update current
                val session = current.sessions[sessionIndex]
                if (session.chromeEnabled == resolvedSelected) {
                    current
                } else {
                    didUpdate = true
                    sessionIdForPersistence = current.currentSessionId
                    current.copy(
                        sessions = current.sessions.toMutableList().apply {
                            this[sessionIndex] = session.copy(chromeEnabled = resolvedSelected)
                        }
                    )
                }
            }
        }
        sessionIdForPersistence?.takeIf { didUpdate }?.let { sessionId ->
            persistSessionMutation(sessionId) { session ->
                if (session.chromeEnabled == selected) null
                else session.copy(chromeEnabled = selected)
            }
        }
        if (selected && didUpdate) {
            openBuiltInBrowser()
        }
    }

    fun sendCurrentMessage() {
        submitCurrentMessage(SessionFollowUpMode.Queue)
    }

    /**
     * Sends a CLI slash command as a plain prompt, the same way agent slash
     * commands typed in the composer reach the CLI. Only useful for commands
     * the CLI's ACP layer actually implements (compact/status/usage/mcp/
     * tasks/help); anything else is answered with "Unknown ACP command".
     * The command replaces the current draft.
     */
    fun sendCommandPrompt(command: String) {
        updateDraftInput(command)
        sendCurrentMessage()
    }

    private fun compactCurrentSession(instruction: String = "") {
        val snapshot = _uiState.value
        if (!snapshot.editingMessageId.isNullOrBlank()) {
            emitTransientMessage(uiString(R.string.message_finish_editing_before_compacting))
            return
        }
        val sessionId = snapshot.currentSessionId
        if (sessionId.isBlank() || sessionId == DraftSessionId) {
            emitTransientMessage(uiString(R.string.message_no_conversation_to_compact))
            return
        }
        val visibleCount = snapshot.sessions.firstOrNull { it.id == sessionId }
            ?.messages
            .orEmpty()
            .count { it.displayKind == MessageDisplayKind.Standard }
        if (visibleCount < 2) {
            emitTransientMessage(uiString(R.string.message_not_enough_conversation_to_compact))
            return
        }
        if (
            sessionExecutionManager.isSessionRunning(sessionId) ||
            snapshot.compactingSessionId == sessionId
        ) {
            emitTransientMessage(uiString(R.string.message_pause_before_compacting))
            return
        }
        _uiState.update { current ->
            current.copy(
                draftInput = "",
                draftAttachments = emptyList(),
                compactingSessionId = sessionId,
            )
        }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    runtime.alpineRuntime.compactKimiSession(
                        aetherSessionId = sessionId,
                        modelConfig = snapshot.settings.toPiModelConfig().toJson(),
                        workspaceDirectory = workspaceFileBridge.workspaceDirectory(
                            snapshot.sessions.firstOrNull { it.id == sessionId }?.workspaceId
                                ?: kira.ditto.data.chatdb.DefaultWorkspaceId,
                        ),
                        instruction = instruction,
                    )
                }
                val ok = result.optBoolean("ok", true)
                val error = result.optString("error_message")
                val reply = classifyKimiCompactReply(result.optString("assistant_text"))
                when {
                    !ok -> emitTransientMessage(
                        uiString(
                            R.string.message_compaction_failed,
                            error.ifBlank { "compact failed" },
                        ),
                    )
                    reply == KimiCompactReply.Busy -> emitTransientMessage(
                        uiString(R.string.message_pause_before_compacting),
                    )
                    reply == KimiCompactReply.Failed -> emitTransientMessage(
                        uiString(
                            R.string.message_compaction_failed,
                            result.optString("assistant_text").ifBlank { error }.ifBlank { "compact failed" },
                        ),
                    )
                    else -> appendCompactStatus(sessionId)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                emitTransientMessage(
                    uiString(R.string.message_compaction_failed, error.userFacingMessage()),
                )
            } finally {
                _uiState.update { current ->
                    if (current.compactingSessionId == sessionId) {
                        current.copy(compactingSessionId = null)
                    } else {
                        current
                    }
                }
            }
        }
    }

    private fun appendCompactStatus(sessionId: String) {
        val now = System.currentTimeMillis()
        val status = ChatMessage(
            id = "compact-status-$now",
            author = MessageAuthor.Agent,
            text = "",
            createdAtMillis = now,
            assistantActionsHidden = true,
            displayKind = MessageDisplayKind.CompactStatus,
        )
        updateSession(sessionId) { session ->
            session.withMessages(session.messages + status)
        }
    }

    internal fun openAmapNavigationInChat(place: AmapPlace) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val invocationId = "amap-nav-$now"
            val assistantId = "amap-nav-assistant-$now"
            val userMessage = ChatMessage(
                id = "user-$now",
                author = MessageAuthor.User,
                text = amapPlaceNavigateInChatUserText(place),
                createdAtMillis = now,
            )
            val running = ChatToolInvocation(
                id = invocationId,
                toolName = AmapNavigationToolName,
                argumentsJson = JSONObject()
                    .put("name", place.name)
                    .put("id", place.id)
                    .put("lng", place.lng ?: JSONObject.NULL)
                    .put("lat", place.lat ?: JSONObject.NULL)
                    .toString(),
                isRunning = true,
                startedAtMillis = now,
                startedAtUptimeMillis = SystemClock.uptimeMillis(),
            )
            val assistantMessage = ChatMessage(
                id = assistantId,
                author = MessageAuthor.Agent,
                text = "",
                createdAtMillis = now + 1,
                toolInvocations = listOf(running),
                assistantActionsHidden = true,
            )
            val sessionId = appendLocalConversationTurn(userMessage, assistantMessage)
            if (sessionId.isBlank()) return@launch
            val payload = withContext(Dispatchers.IO) {
                AmapNavigation.fetch(getApplication(), place)
            }
            if (payload.error.isNotBlank()) {
                val res = when (payload.error) {
                    "gps" -> R.string.amap_nav_error_gps
                    "destination" -> R.string.amap_nav_error_destination
                    "key" -> R.string.amap_nav_error_key
                    else -> R.string.amap_nav_error_route
                }
                emitTransientMessage(uiString(res))
            }
            val doneAt = System.currentTimeMillis()
            val doneUptime = SystemClock.uptimeMillis()
            updateSession(sessionId) { session ->
                session.withMessages(
                    session.messages.map { message ->
                        if (message.id != assistantId) {
                            message
                        } else {
                            message.copy(
                                toolInvocations = listOf(
                                    running.copy(
                                        outputJson = payload.toCompactJson(),
                                        isRunning = false,
                                        completedAtMillis = doneAt,
                                        completedAtUptimeMillis = doneUptime,
                                    ),
                                ),
                            )
                        }
                    },
                )
            }
        }
    }

    private fun appendLocalConversationTurn(
        userMessage: ChatMessage,
        assistantMessage: ChatMessage,
    ): String {
        val snapshot = _uiState.value
        val targetSessionId = when {
            snapshot.currentSessionId != DraftSessionId -> snapshot.currentSessionId
            !snapshot.draftWorkspaceId.isNullOrBlank() -> snapshot.draftWorkspaceId.orEmpty()
            else -> "session-${System.currentTimeMillis()}"
        }
        if (targetSessionId.isBlank()) return ""
        var persisted: ChatSession? = null
        _uiState.update { current ->
            val sessions = current.sessions.toMutableList()
            val existingIndex = sessions.indexOfFirst { it.id == targetSessionId }
            val updated = if (existingIndex >= 0) {
                val existing = sessions.removeAt(existingIndex)
                existing.withMessages(existing.messages + userMessage + assistantMessage)
            } else {
                createSession(
                    id = targetSessionId,
                    messages = listOf(userMessage, assistantMessage),
                    title = userMessage.text,
                    selectedModelKey = current.draftSelectedModelKey.ifBlank {
                        resolveDefaultChatModelKey(current.settings, current.providerConfigs)
                    },
                    selectedSkillIds = current.draftSelectedSkillIds,
                    activeMcpServerIds = current.draftSelectedMcpServerIds,
                    agentModeEnabled = current.draftAgentModeEnabled,
                    chromeEnabled = current.draftChromeEnabled,
                )
            }
            persisted = updated
            sessions.add(0, updated)
            current.copy(
                sessions = sessions,
                currentSessionId = targetSessionId,
                currentScreen = AppScreen.Chat,
                showStarterPromptHint = false,
            )
        }
        persisted?.let { session ->
            persistSessionSnapshot(
                session = session,
                currentSessionId = targetSessionId,
                moveToFront = true,
            )
        }
        return targetSessionId
    }

    /**
     * Pause / resume / cancel the session goal. Prefers the CLI slash
     * (`/goal pause|resume|cancel`); remote sessions also POST `goal_control`.
     */
    fun sendGoalControl(action: String) {
        val normalized = action.trim().lowercase()
        if (normalized !in setOf("pause", "resume", "cancel")) return
        val sessionId = _uiState.value.currentSessionId
        if (sessionId.isBlank()) return
        sessionExecutionManager.applyGoalControl(sessionId, normalized)
        val snapshot = _uiState.value
        val session = snapshot.sessions.firstOrNull { it.id == sessionId }
        val machine = session?.remoteMachineId
            ?.takeIf { it.isNotBlank() }
            ?.let { id -> snapshot.settings.remoteMachines.firstOrNull { it.id == id } }
        viewModelScope.launch {
            var restOk = false
            if (machine != null) {
                restOk = runCatching {
                    withContext(Dispatchers.IO) {
                        runtime.piKernelBridge.updateRemoteGoalControl(
                            baseUrl = machine.baseUrl,
                            token = machine.token,
                            kimiSessionId = session.remoteKimiSessionId,
                            action = normalized,
                        )
                    }
                }.isSuccess
            }
            if (!restOk) {
                sendCommandPrompt("/goal $normalized")
            }
        }
    }

    fun setDeveloperTermuxReadyOverride(isReady: Boolean) {
        _uiState.update { current ->
            current.copy(developerTermuxReadyOverride = isReady)
        }
    }

    fun queueCurrentMessage() {
        submitCurrentMessage(SessionFollowUpMode.Queue)
    }

    fun steerCurrentMessage() {
        submitCurrentMessage(SessionFollowUpMode.Steer)
    }

    fun steerPendingInput(pendingId: String) {
        val sessionId = _uiState.value.currentSessionId
        if (pendingId.isBlank() || sessionId.isBlank()) return
        sessionExecutionManager.promoteQueuedInputToSteer(sessionId, pendingId)
    }

    /**
     * Answers a pending agent permission request. [optionId] must be one of
     * [PendingPermissionRequest.options]; unknown ids fall back to the safe
     * reject default inside the protocol layer. Stale requestIds are ignored.
     */
    fun answerPermissionRequest(requestId: String, optionId: String) {
        val pending = runtime.kimiInteractionController.pendingPermissions.value
            .firstOrNull { it.requestId == requestId }
        val delivered = runtime.kimiInteractionController.answerPermission(requestId, optionId)
        if (
            delivered &&
            pending != null &&
            kira.ditto.data.isPlanApprovalAnswer(pending, optionId)
        ) {
            val sessionId = pending.sessionId.ifBlank { _uiState.value.currentSessionId }
            sessionExecutionManager.markLongHorizonApproved(sessionId)
        }
    }

    /**
     * Answers a pending elicitation form. [answers] is the content object
     * expected by the ACP elicitation result: questionId -> value (string) for
     * single-select questions, questionId -> JSONArray of values for
     * multi-select questions. Null skips/cancels the form.
     */
    fun answerElicitationRequest(requestId: String, answers: JSONObject?) {
        runtime.kimiInteractionController.answerElicitation(requestId, answers)
    }

    /**
     * Switches the kimi permission mode (default|plan|auto|yolo) for the given
     * chat session. Local chats persist it as the phone default and update Alpine.
     * Remote chats write the computer's kimi web session/profile and global config.
     */
    fun setSessionMode(sessionId: String, modeId: String) {
        val normalized = normalizeKimiPermissionMode(modeId)
        sessionExecutionManager.applyAgentModeId(sessionId, normalized)
        val snapshot = _uiState.value
        val session = snapshot.sessions.firstOrNull { it.id == sessionId }
        val machine = session?.remoteMachineId
            ?.takeIf { it.isNotBlank() }
            ?.let { id -> snapshot.settings.remoteMachines.firstOrNull { it.id == id } }
        if (machine != null) {
            _uiState.update { current ->
                current.copy(
                    remotePermissionModeByMachineId = current.remotePermissionModeByMachineId +
                        (machine.id to normalized),
                )
            }
            viewModelScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        runtime.piKernelBridge.updateRemotePermission(
                            baseUrl = machine.baseUrl,
                            token = machine.token,
                            permissionMode = normalized,
                            kimiSessionId = session.remoteKimiSessionId,
                        )
                    }
                }
            }
            return
        }
        viewModelScope.launch {
            settingsRepository.updateSettings(
                _uiState.value.settings.copy(kimiPermissionMode = normalized),
            )
            if (sessionId.isNotBlank()) {
                runtime.alpineRuntime.setKimiSessionMode(sessionId, normalized)
            }
        }
    }

    private fun registerCoreModServices() {
        modKernel.services.register(
            id = "skills",
            owner = "aether-core",
            description = "Inspect and mutate installed skills, current selection, and new-chat defaults.",
            priority = -10_000,
            methods = listOf(
                AetherModServiceMethod("list", "List installed skills and selection state."),
                AetherModServiceMethod("getSelection", "Read current and default skill selections."),
                AetherModServiceMethod("setSelection", "Replace current or default skill selections.", true),
                AetherModServiceMethod("setSelected", "Toggle one skill in a selection scope.", true),
            ),
            handler = { method, args -> handleSkillsModService(method, args) },
        )
        modKernel.services.register(
            id = "state",
            owner = "aether-core",
            description = "Read the public Aether state tree and apply supported state transactions.",
            priority = -10_000,
            methods = listOf(
                AetherModServiceMethod("get", "Read a value from the public state tree."),
                AetherModServiceMethod("transaction", "Apply an ordered list of state mutations.", true),
            ),
            handler = { method, args -> handleStateModService(method, args) },
        )
    }

    private suspend fun dispatchAetherOperation(
        operation: String,
        payload: JSONObject,
    ): AetherModOperationDecision {
        val hasNativeInterceptors = modKernel.operations.hasInterceptors(operation)
        val eventName = "operation:$operation"
        val hasScriptInterceptors =
            eventName in aetherAppExtensionManager.state.value.snapshot.eventNames
        if (!hasNativeInterceptors && !hasScriptInterceptors) {
            return AetherModOperationDecision(payload = payload)
        }

        val context = buildAetherExtensionHostState(_uiState.value)
        val nativeDecision = if (hasNativeInterceptors) {
            modKernel.operations.intercept(
                operation = operation,
                payload = payload,
                context = context,
            )
        } else {
            AetherModOperationDecision(payload = payload)
        }
        if (nativeDecision.cancelled) return nativeDecision

        if (!hasScriptInterceptors) {
            return nativeDecision
        }
        val scriptDecision = aetherAppExtensionManager.dispatchEvent(
            event = eventName,
            data = nativeDecision.payload,
            context = context,
        ).getOrNull() ?: return AetherModOperationDecision(
            payload = nativeDecision.payload,
            cancelled = true,
            reason = "Aether script operation interceptor failed.",
        )
        return AetherModOperationDecision(
            payload = scriptDecision.payload,
            cancelled = scriptDecision.cancelled,
            reason = scriptDecision.reason,
        )
    }

    private suspend fun handleSkillsModService(
        method: String,
        args: JSONObject,
    ): JSONObject = when (method) {
        "list",
        "getSelection" -> buildSkillsModState(_uiState.value)

        "setSelection" -> {
            val requestedIds = args.optJSONArray("ids").toStringList()
            val scope = args.optString("scope", "current").lowercase()
            val snapshot = _uiState.value
            val enabledIds = snapshot.installedSkills
                .filter(InstalledSkill::isEnabled)
                .map(InstalledSkill::id)
                .toSet()
            val selectedIds = requestedIds.filter(enabledIds::contains).distinct()
            if (scope in setOf("default", "global", "current_and_default", "both")) {
                settingsRepository.updateDefaultSelectedSkillIds(selectedIds)
            }
            if (scope !in setOf("default", "global")) {
                withContext(Dispatchers.Main.immediate) {
                    setCurrentSkillSelectionNow(
                        selectedSkillIds = selectedIds,
                        sessionId = args.optString("session_id").ifBlank { null },
                    )
                }
            }
            buildSkillsModState(_uiState.value).put("updated", true)
        }

        "setSelected" -> {
            val skillId = args.optString("skill_id").trim()
            require(skillId.isNotBlank()) { "skill_id is required." }
            val scope = args.optString("scope", "current").lowercase()
            val selected = args.optBoolean("selected", true)
            val snapshot = _uiState.value
            if (scope in setOf("current_and_default", "both")) {
                val currentIds = updateOrderedSelection(
                    currentSelectedSkillIds(snapshot),
                    skillId,
                    selected,
                )
                val defaultIds = updateOrderedSelection(
                    snapshot.settings.defaultSelectedSkillIds,
                    skillId,
                    selected,
                )
                handleSkillsModService(
                    method = "setSelection",
                    args = JSONObject()
                        .put("ids", JSONArray(defaultIds))
                        .put("scope", "default"),
                )
                handleSkillsModService(
                    method = "setSelection",
                    args = JSONObject()
                        .put("ids", JSONArray(currentIds))
                        .put("scope", "current")
                        .put("session_id", args.optString("session_id")),
                )
            } else {
                val currentIds = if (scope in setOf("default", "global")) {
                    snapshot.settings.defaultSelectedSkillIds
                } else {
                    currentSelectedSkillIds(snapshot)
                }
                val updatedIds = updateOrderedSelection(currentIds, skillId, selected)
                handleSkillsModService(
                    method = "setSelection",
                    args = JSONObject()
                        .put("ids", JSONArray(updatedIds))
                        .put("scope", scope)
                        .put("session_id", args.optString("session_id")),
                )
            }
        }

        else -> error("Unknown skills service method: $method")
    }

    private suspend fun handleStateModService(
        method: String,
        args: JSONObject,
    ): JSONObject = when (method) {
        "get" -> {
            val path = args.optString("path").trim()
            val state = buildAetherExtensionHostState(_uiState.value)
            JSONObject()
                .put("path", path)
                .put("value", jsonValueAtPath(state, path))
        }

        "transaction" -> {
            val operations = args.optJSONArray("operations") ?: JSONArray()
            for (index in 0 until operations.length()) {
                val operation = operations.optJSONObject(index) ?: continue
                applyModStateOperation(operation)
            }
            JSONObject()
                .put("applied", operations.length())
                .put("state", buildAetherExtensionHostState(_uiState.value))
        }

        else -> error("Unknown state service method: $method")
    }

    private suspend fun applyModStateOperation(
        operation: JSONObject,
    ) {
        val op = operation.optString("op", "set").lowercase()
        val path = normalizeModStatePath(operation.optString("path"))
        val value = if (op == "remove") JSONObject.NULL else operation.opt("value")
        when (path) {
            "draft_input" -> withContext(Dispatchers.Main.immediate) {
                updateDraftInput(if (value == JSONObject.NULL) "" else value?.toString().orEmpty())
            }

            "selected_skill_ids" -> withContext(Dispatchers.Main.immediate) {
                setCurrentSkillSelectionNow(
                    selectedSkillIds = (value as? JSONArray).toStringList(),
                )
            }

            "default_skill_ids" -> {
                val enabledIds = _uiState.value.installedSkills
                    .filter(InstalledSkill::isEnabled)
                    .map(InstalledSkill::id)
                    .toSet()
                settingsRepository.updateDefaultSelectedSkillIds(
                    (value as? JSONArray).toStringList().filter(enabledIds::contains)
                )
            }

            "agent_mode_enabled" -> withContext(Dispatchers.Main.immediate) {
                setComposerAgentModeSelected(value != JSONObject.NULL && value == true)
            }

            "selected_model_key" -> {
                val modelKey = if (value == JSONObject.NULL) "" else value?.toString().orEmpty()
                require(modelKey.isNotBlank()) { "selected_model_key cannot be empty." }
                withContext(Dispatchers.Main.immediate) {
                    setCurrentChatModelSelectionAndResolveThinkingLevels(modelKey) {}
                }
            }

            "screen" -> withContext(Dispatchers.Main.immediate) {
                when (value?.toString()?.lowercase()) {
                    "settings" -> openSettings()
                    "chat" -> closeSettings()
                    else -> error("Unsupported screen state value: $value")
                }
            }

            else -> error("Unsupported public Aether state path: ${operation.optString("path")}")
        }
    }

    suspend fun handleAetherExtensionHostCall(
        method: String,
        args: JSONObject,
    ): JSONObject = when (method) {
        "kernel.listServices" -> modKernel.services.listJson()

        "kernel.describeService" -> modKernel.services.describeJson(
            args.optString("service")
        )

        "service.invoke" -> modKernel.services.invoke(
            id = args.optString("service"),
            method = args.optString("method"),
            args = args.optJSONObject("args") ?: JSONObject(),
        )

        "state.get" -> handleStateModService("get", args)

        "state.transaction" -> handleStateModService("transaction", args)

        "app.getState" -> buildAetherExtensionHostState(_uiState.value)

        "app.setDraftInput" -> {
            withContext(Dispatchers.Main.immediate) {
                updateDraftInput(args.optString("text"))
            }
            JSONObject().put("updated", true)
        }

        "app.appendDraftInput" -> {
            withContext(Dispatchers.Main.immediate) {
                val current = _uiState.value.draftInput
                updateDraftInput(current + args.optString("text"))
            }
            JSONObject().put("updated", true)
        }

        "app.sendMessage" -> {
            withContext(Dispatchers.Main.immediate) {
                if (args.has("text")) {
                    updateDraftInput(args.optString("text"))
                }
                when (args.optString("mode").lowercase()) {
                    "steer" -> steerCurrentMessage()
                    "queue" -> queueCurrentMessage()
                    else -> sendCurrentMessage()
                }
            }
            JSONObject().put("submitted", true)
        }

        "app.appendCustomMessage" -> {
            val type = args.optString("type").trim()
            require(type.isNotBlank()) { "Custom messages require a type." }
            val text = args.optString("text")
            val payload = args.optJSONObject("payload") ?: JSONObject()
            val sessionId = _uiState.value.currentSessionId
            val message = ChatMessage(
                id = "aether-custom-${UUID.randomUUID()}",
                author = MessageAuthor.Agent,
                text = text,
                createdAtMillis = System.currentTimeMillis(),
                assistantActionsHidden = true,
                providerPayloadJson = JSONObject()
                    .put("aether_custom_type", type)
                    .put("aether_custom_payload", payload)
                    .toString(),
            )
            withContext(Dispatchers.Main.immediate) {
                updateSession(sessionId) { session ->
                    session.copy(messages = session.messages + message, preview = text)
                }
            }
            JSONObject().put("appended", true).put("type", type)
        }

        "app.newChat" -> {
            withContext(Dispatchers.Main.immediate) {
                startNewChat()
            }
            JSONObject().put("opened", "chat")
        }

        "app.selectSession" -> {
            val sessionId = args.optString("session_id").trim()
            require(sessionId.isNotBlank()) { "session_id is required." }
            withContext(Dispatchers.Main.immediate) {
                selectSession(sessionId)
            }
            JSONObject().put("selected", sessionId)
        }

        "app.openScreen" -> {
            val screen = args.optString("screen").lowercase()
            withContext(Dispatchers.Main.immediate) {
                when (screen) {
                    "settings" -> openSettings()
                    "chat" -> closeSettings()
                    else -> error("Unknown Aether screen: $screen")
                }
            }
            JSONObject().put("opened", screen)
        }

        "app.pauseGeneration" -> {
            withContext(Dispatchers.Main.immediate) {
                pauseGeneration()
            }
            JSONObject().put("paused", true)
        }

        "app.setReasoningEffort" -> {
            val effort = normalizeReasoningEffort(args.optString("effort"))
            withContext(Dispatchers.Main.immediate) {
                setReasoningEffort(effort)
            }
            JSONObject().put("reasoning_effort", effort)
        }

        "app.setAgentMode" -> {
            val enabled = args.optBoolean("enabled")
            withContext(Dispatchers.Main.immediate) {
                setComposerAgentModeSelected(enabled)
            }
            JSONObject().put("enabled", enabled)
        }

        "app.setModel" -> {
            val modelKey = args.optString("model_key").trim()
            require(modelKey.isNotBlank()) { "model_key is required." }
            withContext(Dispatchers.Main.immediate) {
                setCurrentChatModelSelectionAndResolveThinkingLevels(modelKey) {}
            }
            JSONObject().put("model_key", modelKey)
        }

        "app.notify" -> {
            emitTransientMessage(UiText.Raw(args.optString("message")))
            JSONObject().put("notified", true)
        }

        "settings.get" -> JSONObject()
            .put("settings", _uiState.value.settings.toJson())
            .put(
                "provider_configs",
                JSONArray(serializeProviderConfigs(_uiState.value.providerConfigs)),
            )

        "settings.patch" -> {
            val current = _uiState.value.settings
            var updated = current
            if (args.has("system_prompt")) {
                updated = updated.copy(systemPrompt = args.optString("system_prompt"))
            }
            if (args.has("reasoning_effort")) {
                updated = updated.copy(
                    reasoningEffort = normalizeReasoningEffort(args.optString("reasoning_effort"))
                )
            }
            if (args.has("keep_tasks_running_in_background")) {
                updated = updated.copy(
                    keepTasksRunningInBackground = args.optBoolean("keep_tasks_running_in_background")
                )
            }
            if (args.has("notify_on_task_completion")) {
                updated = updated.copy(
                    notifyOnTaskCompletion = args.optBoolean("notify_on_task_completion")
                )
            }
            if (args.has("theme")) {
                updated = updated.copy(themeMode = AppThemeMode.fromStorage(args.optString("theme")))
            }
            if (args.has("language")) {
                updated = updated.copy(
                    language = AppLanguage.fromStorage(args.optString("language"), updated.language)
                )
            }
            if (args.has("workspace_mode")) {
                updated = updated.copy(
                )
            }
            if (args.has("default_skill_ids")) {
                val enabledIds = _uiState.value.installedSkills
                    .filter(InstalledSkill::isEnabled)
                    .map(InstalledSkill::id)
                    .toSet()
                updated = updated.copy(
                    defaultSelectedSkillIds = args.optJSONArray("default_skill_ids")
                        .toStringList()
                        .filter(enabledIds::contains),
                )
            }
            if (args.has("tavily_api_key")) {
                updated = updated.copy(tavilyApiKey = args.optString("tavily_api_key"))
            }
            if (args.has("tavily_base_url")) {
                updated = updated.copy(
                    tavilyBaseUrl = normalizeTavilyBaseUrl(args.optString("tavily_base_url"))
                )
            }
            settingsRepository.updateSettings(updated)
            JSONObject().put("settings", updated.toJson())
        }

        "runtime.execute" -> {
            val settings = _uiState.value.settings
            val environment = args.optString("environment").ifBlank { null }
            val selectedRuntime = runtime.runtimeRouter.runtimeFor(settings, environment)
                ?: error("No configured runtime matched ${environment ?: "the default runtime"}.")
            val command = args.optString("command")
            require(command.isNotBlank()) { "command is required." }
            val output = selectedRuntime.executeCommand(
                command = command,
                workingDirectory = args.optString("working_directory")
                    .ifBlank { selectedRuntime.homeDirectory },
                awaitTimeoutMillis = args.optLong("timeout_ms", 60_000L)
                    .coerceIn(1_000L, 10 * 60_000L),
            )
            JSONObject()
                .put("runtime", selectedRuntime.id.storageValue)
                .put("output", output)
        }

        else -> error("Unsupported Aether extension host method: $method")
    }

    private fun currentSelectedSkillIds(
        snapshot: AetherUiState,
    ): List<String> =
        snapshot.sessions
            .firstOrNull { it.id == snapshot.currentSessionId }
            ?.selectedSkillIds
            ?: snapshot.draftSelectedSkillIds

    private fun enabledDefaultSkillIds(
        snapshot: AetherUiState,
    ): List<String> {
        val enabledIds = snapshot.installedSkills
            .filter(InstalledSkill::isEnabled)
            .map(InstalledSkill::id)
            .toSet()
        return snapshot.settings.defaultSelectedSkillIds.filter(enabledIds::contains)
    }

    private fun setCurrentSkillSelectionNow(
        selectedSkillIds: List<String>,
        sessionId: String? = null,
    ) {
        val enabledIds = _uiState.value.installedSkills
            .filter(InstalledSkill::isEnabled)
            .map(InstalledSkill::id)
            .toSet()
        val normalizedIds = selectedSkillIds.filter(enabledIds::contains).distinct()
        val targetSessionId = sessionId ?: _uiState.value.currentSessionId
        if (targetSessionId == DraftSessionId) {
            _uiState.update { current ->
                current.copy(draftSelectedSkillIds = normalizedIds)
            }
        } else {
            setSessionSelectedSkillIds(targetSessionId, normalizedIds)
        }
    }

    private fun buildSkillsModState(
        snapshot: AetherUiState,
    ): JSONObject {
        val selectedIds = currentSelectedSkillIds(snapshot)
        return JSONObject().apply {
            put("session_id", snapshot.currentSessionId)
            put("selected_skill_ids", JSONArray(selectedIds))
            put("default_skill_ids", JSONArray(snapshot.settings.defaultSelectedSkillIds))
            put(
                "skills",
                JSONArray().apply {
                    snapshot.installedSkills.forEach { skill ->
                        put(
                            JSONObject().apply {
                                put("id", skill.id)
                                put("name", skill.name)
                                put("description", skill.description)
                                put("action_label", skill.actionLabel)
                                put("license", skill.license)
                                put("compatibility", skill.compatibility)
                                put("allowed_tools", JSONArray(skill.allowedTools))
                                put("enabled", skill.isEnabled)
                                put("selected", skill.id in selectedIds)
                                put(
                                    "default_selected",
                                    skill.id in snapshot.settings.defaultSelectedSkillIds,
                                )
                            }
                        )
                    }
                },
            )
        }
    }

    private fun normalizeModStatePath(
        rawPath: String,
    ): String = when (
        rawPath.trim().trim('/').replace('/', '.').lowercase()
    ) {
        "draft_input",
        "chat.draftinput",
        "chat.draft_input" -> "draft_input"

        "selected_skill_ids",
        "chat.selectedskillids",
        "chat.selected_skill_ids" -> "selected_skill_ids"

        "default_skill_ids",
        "chat.defaultskillids",
        "chat.default_skill_ids" -> "default_skill_ids"

        "agent_mode_enabled",
        "chat.agentmodeenabled",
        "chat.agent_mode_enabled" -> "agent_mode_enabled"

        "selected_model_key",
        "chat.selectedmodelkey",
        "chat.selected_model_key" -> "selected_model_key"

        "screen",
        "app.screen" -> "screen"

        else -> rawPath.trim()
    }

    private fun jsonValueAtPath(
        root: Any?,
        rawPath: String,
    ): Any? {
        val path = rawPath.trim().trim('/').replace('/', '.')
        if (path.isBlank()) return root
        var current: Any? = root
        path.split('.').filter(String::isNotBlank).forEach { segment ->
            current = when (val value = current) {
                is JSONObject -> value.opt(segment)
                is JSONArray -> segment.toIntOrNull()?.let(value::opt)
                else -> JSONObject.NULL
            }
        }
        return current ?: JSONObject.NULL
    }

    private fun buildAetherExtensionHostState(
        snapshot: AetherUiState,
    ): JSONObject {
        val activeSession = snapshot.sessions.firstOrNull { it.id == snapshot.currentSessionId }
        val execution = snapshot.sessionExecutionStates[snapshot.currentSessionId]
        val selectedSkillIds = currentSelectedSkillIds(snapshot)
        return JSONObject().apply {
            put("screen", snapshot.currentScreen.name.lowercase())
            put("session_id", snapshot.currentSessionId)
            put("draft_input", snapshot.draftInput)
            put("is_running", execution?.isRunning == true)
            put("is_editing", snapshot.editingMessageId != null)
            put("selected_model_key", activeSession?.selectedModelKey ?: snapshot.draftSelectedModelKey)
            put("agent_mode_enabled", activeSession?.agentModeEnabled ?: snapshot.draftAgentModeEnabled)
            put("selected_skill_ids", JSONArray(selectedSkillIds))
            put("default_skill_ids", JSONArray(snapshot.settings.defaultSelectedSkillIds))
            put("skills", buildSkillsModState(snapshot).optJSONArray("skills"))
            put("settings", snapshot.settings.toJson())
            put("provider_configs", JSONArray(serializeProviderConfigs(snapshot.providerConfigs)))
            put("sessions", JSONArray(serializeChatSessions(snapshot.sessions)))
            put(
                "installed_extensions",
                JSONArray().apply {
                    snapshot.installedPiExtensions.forEach { extension ->
                        put(
                            JSONObject().apply {
                                put("id", extension.id)
                                put("name", extension.name)
                                put("source", extension.source)
                                put("pi_extension_count", extension.extensionCount)
                                put("aether_extension_count", extension.aetherExtensionCount)
                                put("native_entrypoint_count", extension.nativeEntrypointCount)
                            }
                        )
                    }
                },
            )
        }
    }

    private fun submitCurrentMessage(
        runningFollowUpMode: SessionFollowUpMode,
    ) {
        if (extensionSendHookJob?.isActive == true) return
        extensionSendHookJob = viewModelScope.launch {
            val snapshot = _uiState.value
            if ("before_send" in aetherAppExtensionManager.state.value.snapshot.eventNames) {
                val eventData = JSONObject().apply {
                    put("text", snapshot.draftInput)
                    put("mode", runningFollowUpMode.name.lowercase())
                    put("session_id", snapshot.currentSessionId)
                    put(
                        "attachments",
                        JSONArray().apply {
                            snapshot.draftAttachments.forEach { attachment ->
                                put(
                                    JSONObject().apply {
                                        put("id", attachment.id)
                                        put("name", attachment.name)
                                        put("mime_type", attachment.mimeType)
                                        put("workspace_path", attachment.workspacePath)
                                    },
                                )
                            }
                        },
                    )
                }
                val eventResult = aetherAppExtensionManager.dispatchEvent(
                    event = "before_send",
                    data = eventData,
                    context = buildAetherExtensionHostState(snapshot),
                ).getOrNull()
                if (eventResult?.cancelled == true) {
                    eventResult.reason.takeIf(String::isNotBlank)?.let { reason ->
                        emitTransientMessage(UiText.Raw(reason))
                    }
                    return@launch
                }
                val transformedText = eventResult?.payload?.optString("text", snapshot.draftInput)
                    ?: snapshot.draftInput
                if (transformedText != _uiState.value.draftInput) {
                    _uiState.update { current -> current.copy(draftInput = transformedText) }
                }
            }
            submitCurrentMessageNow(runningFollowUpMode)
        }
    }

    private suspend fun submitCurrentMessageNow(
        runningFollowUpMode: SessionFollowUpMode,
    ) {
        val snapshot = _uiState.value
        val content = snapshot.draftInput.visibleUserMessageText()
        val attachments = snapshot.draftAttachments

        if (content.isEmpty() && attachments.isEmpty()) return
        if (attachments.any { it.workspaceState != AttachmentWorkspaceState.Ready }) return
        if (attachments.isEmpty() && isCompactSlashCommand(content)) {
            _uiState.update { current ->
                current.copy(draftInput = "", draftAttachments = emptyList())
            }
            compactCurrentSession(compactSlashInstruction(content))
            return
        }
        if (!_uiState.value.compactingSessionId.isNullOrBlank()) {
            emitTransientMessage(uiString(R.string.message_pause_before_compacting))
            return
        }
        if (kira.ditto.browser.looksLikeBrowserPageResume(content)) {
            persistBrowserDeskOntoCurrentSession()
        } else {
            sealAndClearBrowserDesk(persistSources = !currentComposerAgentModeSelected())
        }

        val targetSessionId = snapshot.editingSessionId ?: when {
            snapshot.currentSessionId != DraftSessionId -> snapshot.currentSessionId
            !snapshot.draftWorkspaceId.isNullOrBlank() -> snapshot.draftWorkspaceId.orEmpty()
            else -> "session-${System.currentTimeMillis()}"
        }

        val now = System.currentTimeMillis()
        val userMessage = ChatMessage(
            id = "user-$now",
            author = MessageAuthor.User,
            text = content,
            createdAtMillis = now,
            attachments = attachments,
            promptDirective = ComposerPromptDirective
                .fromStorageValue(snapshot.draftPromptDirective)
                ?.storageValue
                .orEmpty(),
            agentModeEnabled = if (snapshot.currentSessionId == DraftSessionId) {
                snapshot.draftAgentModeEnabled
            } else {
                snapshot.sessions.firstOrNull { it.id == snapshot.currentSessionId }
                    ?.agentModeEnabled
                    ?: snapshot.draftAgentModeEnabled
            },
            knowledgeCitations = pendingKnowledgeCitations,
        )
        pendingKnowledgeCitations = emptyList()
        kira.ditto.browser.BrowserDesk.configureLookupLoop(
            deepSearch = kira.ditto.data.looksLikeDeepWebSearch(content),
            operateUrl = kira.ditto.browser.browserActOperateUrlFromUserText(content),
            lookupThenOperate = kira.ditto.browser.looksLikeBrowserLookupThenOperate(content),
            keepLivePage = kira.ditto.browser.looksLikeBrowserPageResume(content) ||
                kira.ditto.browser.browserActOperateUrlFromUserText(content).isNotBlank(),
        )
        crewForSession(targetSessionId)?.let { crew ->
            if (content.isNotBlank()) {
                digiCrewMesh.publishUserChat(crew.id, content)
            }
        }

        if (snapshot.editingSessionId != null && sessionExecutionManager.isSessionRunning(targetSessionId)) {
            emitTransientMessage(uiString(R.string.message_pause_before_editing_message))
            return
        }

        if (sessionExecutionManager.isSessionRunning(targetSessionId)) {
            val personaIdForKnowledge = sessionPersonaIds[targetSessionId].orEmpty()
                .ifBlank { snapshot.activePersonaId }
                .ifBlank { snapshot.draftPersonaId }
            _uiState.update { current ->
                current.copy(
                    currentScreen = AppScreen.Chat,
                    draftInput = "",
                    draftAttachments = emptyList(),
                    draftWorkspaceId = null,
                    editingSessionId = null,
                    editingMessageId = null,
                    showStarterPromptHint = false,
                )
            }
            val briefing = if (personaIdForKnowledge.isNotBlank()) {
                knowledgeBriefingFor(personaIdForKnowledge, content)
            } else {
                KnowledgeBriefing()
            }
            val hidden = consumePendingHiddenContextMessage(now)
            val outbound = userMessage.copy(
                hiddenPromptPrefix = listOf(hidden?.text.orEmpty(), briefing.promptText)
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n"),
                knowledgeCitations = briefing.citations,
            )
            if (!sessionExecutionManager.submitFollowUp(targetSessionId, outbound, runningFollowUpMode)) {
                _uiState.update { current ->
                    current.copy(
                        draftInput = snapshot.draftInput,
                        draftAttachments = snapshot.draftAttachments,
                        draftPromptDirective = snapshot.draftPromptDirective,
                    )
                }
                emitTransientMessage(uiString(R.string.message_session_no_longer_running))
                return
            }
            pendingKnowledgeCitations = emptyList()
            sessionPersonaIds[targetSessionId]?.takeIf { it.isNotBlank() }?.let { personaId ->
                persistPersonaSessionBinding(targetSessionId, personaId)
            }
            buildAnalyticsTurnRequest(
                snapshot = snapshot,
                sessionId = targetSessionId,
                userMessage = userMessage,
            )?.let { turnRequest ->
                captureMessageSent(
                    request = turnRequest,
                    attachments = attachments,
                    isEdit = false,
                    submissionType = runningFollowUpMode.name.lowercase(),
                )
            }
            _uiState.update { current ->
                current.copy(
                    currentScreen = AppScreen.Chat,
                    draftInput = "",
                    draftAttachments = emptyList(),
                    draftWorkspaceId = null,
                    editingSessionId = null,
                    editingMessageId = null,
                    showStarterPromptHint = false,
                )
            }
            if ("message_sent" in aetherAppExtensionManager.state.value.snapshot.eventNames) {
                aetherAppExtensionManager.emitEvent(
                    event = "message_sent",
                    data = JSONObject()
                        .put("session_id", targetSessionId)
                        .put("message_id", userMessage.id)
                        .put("text", userMessage.text)
                        .put("mode", runningFollowUpMode.name.lowercase()),
                    context = buildAetherExtensionHostState(_uiState.value),
                )
            }
            return
        }

        var request: SessionTurnRequest? = null
        var requestMessages: List<ChatMessage> = emptyList()
        var requestSelectedSkillIds: List<String> = emptyList()
        var requestActiveSkills: List<ActiveSkillContext> = emptyList()
        var requestActiveMcpServerIds: List<String> = emptyList()
        var requestAgentModeEnabled = false
        var requestChromeEnabled = false
        var requestModelKey = ""
        var shouldGenerateSessionTitle = false
        var sessionForPersistence: ChatSession? = null
        var editedMessagePredecessorId: String? = null
        var isEditingExistingMessage = false

        _uiState.update { current ->
            val personaId = current.draftPersonaId.ifBlank { sessionPersonaIds[targetSessionId].orEmpty() }
                .ifBlank { current.activePersonaId }
            if (personaId.isNotBlank()) {
                sessionPersonaIds[targetSessionId] = personaId
            }
            val updatedSessions = current.sessions.toMutableList()
            if (
                current.editingSessionId != null &&
                current.editingMessageId != null
            ) {
                val editingSessionIndex = updatedSessions.indexOfFirst {
                    it.id == current.editingSessionId
                }
                if (editingSessionIndex >= 0) {
                    val editingSession = updatedSessions.removeAt(editingSessionIndex)
                    val editingMessageIndex = editingSession.messages.indexOfFirst {
                        it.id == current.editingMessageId && it.author == MessageAuthor.User
                    }
                    if (editingMessageIndex >= 0) {
                        isEditingExistingMessage = true
                        editedMessagePredecessorId = editingSession.messages
                            .take(editingMessageIndex)
                            .lastOrNull()
                            ?.id
                        val branchedMessages = createEditedMessageBranch(
                            messages = editingSession.messages,
                            messageId = current.editingMessageId,
                            replacement = userMessage,
                        ) ?: (editingSession.messages.take(editingMessageIndex) + userMessage)
                        val updated = editingSession.withMessages(branchedMessages)
                        sessionForPersistence = updated
                        updatedSessions.add(0, updated)
                        requestMessages = updated.messages
                        requestSelectedSkillIds = updated.selectedSkillIds
                        requestActiveSkills = updated.activeSkills
                        requestActiveMcpServerIds = updated.activeMcpServerIds
                        requestAgentModeEnabled = updated.agentModeEnabled
                        requestChromeEnabled = updated.chromeEnabled
                        requestModelKey = updated.selectedModelKey
                    } else {
                        updatedSessions.add(editingSessionIndex, editingSession)
                    }
                }
            }

            if (requestMessages.isEmpty()) {
                val existingIndex = updatedSessions.indexOfFirst { it.id == targetSessionId }
                if (existingIndex >= 0) {
                    val existing = updatedSessions.removeAt(existingIndex)
                    val hidden = consumePendingHiddenContextMessage(now)
                    val updated = existing.withMessages(existing.messages + listOfNotNull(hidden) + userMessage)
                    sessionForPersistence = updated
                    updatedSessions.add(0, updated)
                    requestMessages = updated.messages
                    requestSelectedSkillIds = updated.selectedSkillIds
                    requestActiveSkills = updated.activeSkills
                    requestActiveMcpServerIds = updated.activeMcpServerIds
                    requestAgentModeEnabled = updated.agentModeEnabled
                    requestChromeEnabled = updated.chromeEnabled
                    requestModelKey = updated.selectedModelKey
                } else {
                    val newSession = createSession(
                        id = targetSessionId,
                        messages = listOfNotNull(
                            consumePendingHiddenContextMessage(now),
                            userMessage,
                        ),
                        title = pendingNewSessionTitle ?: "New chat",
                        hasCustomTitle = pendingNewSessionTitle != null,
                        selectedModelKey = current.draftSelectedModelKey.ifBlank {
                            resolveDefaultChatModelKey(current.settings, current.providerConfigs)
                        },
                        selectedSkillIds = current.draftSelectedSkillIds,
                        activeMcpServerIds = current.draftSelectedMcpServerIds,
                        agentModeEnabled = current.draftAgentModeEnabled,
                        chromeEnabled = current.draftChromeEnabled,
                    )
                    shouldGenerateSessionTitle = pendingNewSessionTitle.isNullOrBlank()
                    sessionForPersistence = newSession
                    updatedSessions.add(0, newSession)
                    requestMessages = newSession.messages
                    requestSelectedSkillIds = newSession.selectedSkillIds
                    requestActiveSkills = newSession.activeSkills
                    requestActiveMcpServerIds = newSession.activeMcpServerIds
                    requestAgentModeEnabled = newSession.agentModeEnabled
                    requestChromeEnabled = newSession.chromeEnabled
                    requestModelKey = newSession.selectedModelKey
                }
            }

            request = SessionTurnRequest(
                sessionId = targetSessionId,
                settings = resolveSessionModelSettings(
                    current,
                    sessionForPersistence ?: current.sessions.firstOrNull { it.id == targetSessionId },
                    requestModelKey,
                ),
                requestMessages = requestMessages,
                selectedSkillIds = requestSelectedSkillIds,
                activeSkills = requestActiveSkills,
                activeMcpServerIds = requestActiveMcpServerIds,
                agentModeEnabled = requestAgentModeEnabled,
                chromeEnabled = requestChromeEnabled,
                providerConfigs = current.providerConfigs,
                workspaceId = sessionForPersistence?.workspaceId
                    ?: workspaceIdForSession(targetSessionId),
            ).withRemoteFrom(
                sessionForPersistence ?: ChatSession(
                    id = targetSessionId,
                    title = "",
                    preview = "",
                    messages = requestMessages,
                ),
                current.settings,
            )

            current.copy(
                sessions = updatedSessions,
                currentSessionId = targetSessionId,
                draftInput = "",
                draftAttachments = emptyList(),
                draftSelectedModelKey = "",
                draftSelectedSkillIds = emptyList(),
                draftSelectedMcpServerIds = emptyList(),
                draftAgentModeEnabled = false,
                draftChromeEnabled = false,
                draftPersonaId = "",
                activePersonaId = sessionPersonaIds[targetSessionId].orEmpty(),
                draftWorkspaceId = null,
                editingSessionId = null,
                editingMessageId = null,
                currentScreen = AppScreen.Chat,
                showStarterPromptHint = false,
            )
        }

        var turnRequest = request ?: return
        val personaIdForKnowledge = sessionPersonaIds[targetSessionId].orEmpty()
            .ifBlank { snapshot.activePersonaId }
            .ifBlank { snapshot.draftPersonaId }
        if (personaIdForKnowledge.isNotBlank()) {
            val briefing = knowledgeBriefingFor(personaIdForKnowledge, content)
            if (briefing.promptText.isNotBlank() || briefing.citations.isNotEmpty()) {
                val patchedUser = userMessage.copy(
                    knowledgeCitations = briefing.citations,
                    hiddenPromptPrefix = briefing.promptText,
                )
                _uiState.update { current ->
                    val sessions = current.sessions.toMutableList()
                    val index = sessions.indexOfFirst { it.id == targetSessionId }
                    if (index < 0) return@update current
                    val updated = sessions[index].withMessages(
                        sessions[index].messages.map { message ->
                            if (message.id == patchedUser.id) patchedUser else message
                        },
                    )
                    sessionForPersistence = updated
                    sessions[index] = updated
                    current.copy(sessions = sessions)
                }
                turnRequest = turnRequest.copy(
                    requestMessages = turnRequest.requestMessages.map { message ->
                        if (message.id == patchedUser.id) patchedUser else message
                    },
                )
            }
        }
        sessionForPersistence?.let { session ->
            persistSessionSnapshot(
                session = session,
                currentSessionId = targetSessionId,
                moveToFront = true,
            )
        }
        sessionPersonaIds[targetSessionId]?.takeIf { it.isNotBlank() }?.let { personaId ->
            persistPersonaSessionBinding(targetSessionId, personaId)
        }
        captureMessageSent(
            request = turnRequest,
            attachments = attachments,
            isEdit = snapshot.editingSessionId != null,
            submissionType = "new_turn",
        )
        if (isEditingExistingMessage) {
            viewModelScope.launch {
                navigatePiBranch(
                    sessionId = targetSessionId,
                    aetherMessageId = editedMessagePredecessorId,
                    resetWhenMissing = true,
                )
                startTurnMaybePromptingSecrets(turnRequest)
            }
        } else {
            startTurnMaybePromptingSecrets(turnRequest)
        }
        if (shouldGenerateSessionTitle) {
            generateSessionTitle(
                sessionId = targetSessionId,
                seedMessage = userMessage,
                settings = turnRequest.settings,
            )
        }
        if ("message_sent" in aetherAppExtensionManager.state.value.snapshot.eventNames) {
            aetherAppExtensionManager.emitEvent(
                event = "message_sent",
                data = JSONObject()
                    .put("session_id", targetSessionId)
                    .put("message_id", userMessage.id)
                    .put("text", userMessage.text)
                    .put("mode", "new_turn"),
                context = buildAetherExtensionHostState(_uiState.value),
            )
        }
    }

    private fun handleTurnEvent(
        event: SessionTurnEvent,
    ) {
        captureTurnCompleted(event)
        val isSuccessfulAssistantReply = event.outcome == SessionTurnOutcome.Success
        if (isSuccessfulAssistantReply) {
            settleBrowserCitationsForTurn(event.sessionId)
        }
        if (
            shouldMarkOnboardingCompleted(
                settings = _uiState.value.settings,
                isSuccessfulAssistantReply = isSuccessfulAssistantReply,
            )
        ) {
            viewModelScope.launch {
                settingsRepository.updateOnboardingCompletedVersion(CurrentOnboardingVersion)
            }
        }
        if (
            shouldRevealFollowUpTourCard(
                isAwaitingFollowUpTour = _uiState.value.awaitingFollowUpTour,
                isSuccessfulAssistantReply = isSuccessfulAssistantReply,
            )
        ) {
            scheduleFollowUpTourAfterFirstReply()
        }
        _uiState.update { current ->
            val unviewedCompletedSessionIds = when {
                event.sessionId == current.currentSessionId -> current.unviewedCompletedSessionIds - event.sessionId
                event.outcome != SessionTurnOutcome.Neutral -> current.unviewedCompletedSessionIds + event.sessionId
                else -> current.unviewedCompletedSessionIds
            }
            current.copy(unviewedCompletedSessionIds = unviewedCompletedSessionIds)
        }
        if ("turn_complete" in aetherAppExtensionManager.state.value.snapshot.eventNames) {
            aetherAppExtensionManager.emitEvent(
                event = "turn_complete",
                data = JSONObject()
                    .put("session_id", event.sessionId)
                    .put("outcome", event.outcome.name.lowercase()),
                context = buildAetherExtensionHostState(_uiState.value),
            )
        }
        maybeStartLongHorizonHandoff(event)
        if (isSuccessfulAssistantReply) {
            val crew = crewForSession(event.sessionId)
            if (crew != null) {
                val session = _uiState.value.sessions.firstOrNull { it.id == event.sessionId } ?: return
                val lastAgent = session.messages.lastOrNull {
                    it.author == MessageAuthor.Agent &&
                        it.displayKind == MessageDisplayKind.Standard &&
                        !it.id.startsWith("crew-")
                } ?: return
                val speaker = _uiState.value.personas
                    .filter { it.id in crew.memberPersonaIds }
                    .joinToString("/") { it.name }
                    .ifBlank { "Persona" }
                digiCrewMesh.publishPersonaChat(crew.id, speaker, lastAgent.text)
            }
        }
    }

    private fun maybeStartLongHorizonHandoff(event: SessionTurnEvent) {
        if (event.outcome == SessionTurnOutcome.Neutral) return
        if (!sessionExecutionManager.consumeLongHorizonHandoff(event.sessionId)) return
        viewModelScope.launch {
            enableAgentModeForSession(event.sessionId)
            delay(350)
            runCatching { sessionExecutionManager.startLongHorizonExecute(event.sessionId) }
        }
    }

    private fun enableAgentModeForSession(sessionId: String) {
        if (sessionId.isBlank()) return
        if (sessionId == _uiState.value.currentSessionId || sessionId == DraftSessionId) {
            setComposerAgentModeSelected(true)
            return
        }
        persistSessionMutation(sessionId) { session ->
            if (session.agentModeEnabled) null else session.copy(agentModeEnabled = true)
        }
        requestEnableAgentModeShizuku()
        prewarmKimiWorkspaceIfPossible(forceRestart = true)
    }

    private suspend fun buildPendingDraftAttachment(
        uri: Uri,
    ): ChatAttachment? {
        val metadata = readAttachmentMetadata(uri) ?: return null
        return ChatAttachment(
            id = "attachment-${System.currentTimeMillis()}-${uri.hashCode()}",
            uri = uri.toString(),
            name = metadata.displayName,
            mimeType = metadata.mimeType,
            sizeBytes = metadata.sizeBytes,
            kind = metadata.kind,
            workspaceState = AttachmentWorkspaceState.Pending,
        )
    }

    private suspend fun importDraftAttachmentToWorkspace(
        attachment: ChatAttachment,
        sessionId: String,
    ) {
        val importResult = runtimeWorkspaceFileBridge.importAttachmentToWorkspace(
            settings = _uiState.value.settings,
            sourceUri = Uri.parse(attachment.uri),
            sessionId = sessionId,
            attachmentId = attachment.id,
            displayName = attachment.name,
            workspaceId = workspaceIdForSession(sessionId),
            onProgress = { progress ->
                _uiState.update { current ->
                    val attachmentIndex = current.draftAttachments.indexOfFirst { it.id == attachment.id }
                    if (attachmentIndex < 0) return@update current
                    val existingAttachment = current.draftAttachments[attachmentIndex]
                    current.copy(
                        draftAttachments = current.draftAttachments.toMutableList().apply {
                            set(
                                attachmentIndex,
                                existingAttachment.copy(
                                    workspaceBytesCopied = progress.bytesCopied,
                                    workspaceBytesPerSecond = progress.bytesPerSecond,
                                )
                            )
                        }
                    )
                }
            },
        )

        _uiState.update { current ->
            val attachmentIndex = current.draftAttachments.indexOfFirst { it.id == attachment.id }
            if (attachmentIndex < 0) return@update current

            val existingAttachment = current.draftAttachments[attachmentIndex]
            val updatedAttachment = importResult.fold(
                onSuccess = { importedFile ->
                    val resolvedMimeType = existingAttachment.mimeType.ifBlank {
                        workspaceFileBridge.guessMimeType(importedFile.absolutePath)
                    }
                    existingAttachment.copy(
                        mimeType = resolvedMimeType,
                        sizeBytes = existingAttachment.sizeBytes ?: importedFile.bytesCopied,
                        kind = if (resolvedMimeType.startsWith("image/")) {
                            AttachmentKind.Image
                        } else {
                            AttachmentKind.File
                        },
                        workspacePath = importedFile.absolutePath,
                        workspaceState = AttachmentWorkspaceState.Ready,
                        workspaceError = "",
                        workspaceBytesCopied = importedFile.bytesCopied,
                        workspaceBytesPerSecond = 0L,
                        inlineBase64 = if (
                            resolvedMimeType.startsWith("image/") &&
                            importedFile.inlineBytes.isNotEmpty() &&
                            importedFile.inlineBytes.size <= MaxInlineImageAttachmentBytes
                        ) {
                            Base64.getEncoder().encodeToString(importedFile.inlineBytes)
                        } else {
                            existingAttachment.inlineBase64
                        },
                    )
                },
                onFailure = { throwable ->
                    val inlineImageBase64 = readInlineImageAttachment(existingAttachment)
                    if (inlineImageBase64.isNotBlank()) {
                        existingAttachment.copy(
                            workspacePath = "",
                            workspaceState = AttachmentWorkspaceState.Ready,
                            workspaceError = "",
                            workspaceBytesPerSecond = 0L,
                            inlineBase64 = inlineImageBase64,
                        )
                    } else {
                        existingAttachment.copy(
                            workspaceState = AttachmentWorkspaceState.Failed,
                            workspaceError = throwable.message
                                .orEmpty()
                                .ifBlank { "Couldn't copy this attachment into the workspace." },
                            workspaceBytesPerSecond = 0L,
                        )
                    }
                },
            )

            current.copy(
                draftAttachments = current.draftAttachments.toMutableList().apply {
                    set(attachmentIndex, updatedAttachment)
                }
            )
        }
    }

    private fun readInlineImageAttachment(attachment: ChatAttachment): String {
        if (attachment.kind != AttachmentKind.Image || !attachment.mimeType.startsWith("image/")) return ""
        if (attachment.sizeBytes?.let { it > MaxInlineImageAttachmentBytes } == true) return ""
        return runCatching {
            val resolver = getApplication<Application>().contentResolver
            val bytes = resolver.openInputStream(Uri.parse(attachment.uri))?.use { input ->
                val buffer = ByteArray(MaxInlineImageAttachmentBytes + 1)
                var total = 0
                while (total < buffer.size) {
                    val read = input.read(buffer, total, buffer.size - total)
                    if (read <= 0) break
                    total += read
                }
                require(total <= MaxInlineImageAttachmentBytes) { "Image is too large to inline." }
                buffer.copyOf(total)
            } ?: return ""
            Base64.getEncoder().encodeToString(bytes)
        }.getOrDefault("")
    }

    private fun normalizeDraftAttachmentForEditing(
        attachment: ChatAttachment,
    ): ChatAttachment = if (
        attachment.workspacePath.isNotBlank() ||
        (attachment.kind == AttachmentKind.Image && attachment.inlineBase64.isNotBlank())
    ) {
        attachment.copy(
            workspaceState = AttachmentWorkspaceState.Ready,
            workspaceError = "",
            workspaceBytesPerSecond = 0L,
        )
    } else {
        attachment.copy(
            workspaceState = AttachmentWorkspaceState.Failed,
            workspaceError = "This attachment is missing its workspace copy. Re-upload it before sending.",
        )
    }

    private fun readAttachmentMetadata(
        uri: Uri,
    ): AttachmentMetadata? {
        val resolver = getApplication<Application>().contentResolver
        var mimeType = resolver.getType(uri).orEmpty()
        var displayName = uri.lastPathSegment ?: "Attachment"
        var sizeBytes: Long? = null

        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    displayName = cursor.getString(nameIndex) ?: displayName
                }

                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    sizeBytes = cursor.getLong(sizeIndex)
                }
            }
        }

        if (mimeType.isBlank()) {
            mimeType = workspaceFileBridge.guessMimeType(displayName)
        }

        return AttachmentMetadata(
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            kind = if (mimeType.startsWith("image/")) AttachmentKind.Image else AttachmentKind.File,
        )
    }

    private fun scheduleFollowUpTourAfterFirstReply() {
        _uiState.update { current ->
            current.copy(
                awaitingFollowUpTour = false,
                showFollowUpTourCard = true,
            )
        }
        viewModelScope.launch {
            delay(FollowUpTourAutoOpenDelayMillis)
            _uiState.update { current ->
                if (current.currentScreen != AppScreen.Chat) {
                    current
                } else {
                    current.copy(
                        currentScreen = AppScreen.Onboarding,
                        isOnboardingReplay = true,
                        onboardingStep = OnboardingStep.AgentModeAuthorization,
                        onboardingReturnScreen = AppScreen.Chat,
                        awaitingFollowUpTour = false,
                        showFollowUpTourCard = false,
                    )
                }
            }
        }
    }

    /**
     * Pages one screen of older messages into the active conversation. Called when the
     * transcript is scrolled to the top of its loaded window.
     */
    fun loadOlderMessages() {
        if (loadOlderMessagesJob?.isActive == true) return
        val snapshot = _uiState.value
        val sessionId = snapshot.currentSessionId
        val session = snapshot.sessions.firstOrNull { it.id == sessionId } ?: return
        if (session.loadedFromPosition <= 0) return
        loadOlderMessagesJob = viewModelScope.launch {
            val older = runCatching {
                withContext(Dispatchers.IO) {
                    runtime.chatRepository.loadOlderMessages(
                        sessionId = sessionId,
                        beforePosition = session.loadedFromPosition,
                    )
                }
            }.getOrElse { throwable ->
                if (throwable is CancellationException) throw throwable
                diagnosticLogger.exception(
                    category = "storage",
                    event = "older_messages_load_failed",
                    throwable = throwable,
                    level = "warn",
                    sessionId = sessionId,
                )
                emptyList()
            }
            if (older.isEmpty()) return@launch
            val newFromPosition = (session.loadedFromPosition - older.size).coerceAtLeast(0)
            chatStateStore.update { persisted ->
                val index = persisted.sessions.indexOfFirst { it.id == sessionId }
                if (index < 0) return@update persisted
                val target = persisted.sessions[index]
                // The window may have grown at the tail while the page was loading.
                if (target.loadedFromPosition != session.loadedFromPosition) return@update persisted
                val updated = persisted.sessions.toMutableList()
                updated[index] = target.copy(
                    messages = older + target.messages,
                    loadedFromPosition = newFromPosition,
                )
                persisted.copy(sessions = updated)
            }
        }
    }

    private fun persistCurrentSessionId(sessionId: String) {
        chatStateStore.update { persisted ->
            persisted.copy(
                sessions = persisted.sessions.withMessagesOnlyForSession(sessionId),
                currentSessionId = sessionId,
            )
        }
    }

    private suspend fun hydrateCurrentSessionMessages(persisted: PersistedChatState): PersistedChatState {
        val currentSessionId = persisted.currentSessionId.ifBlank { DraftSessionId }
        if (currentSessionId == DraftSessionId) return persisted
        val currentSession = persisted.sessions.firstOrNull { it.id == currentSessionId } ?: return persisted
        if (currentSession.messages.isNotEmpty() || currentSession.messageCount <= 0) return persisted
        val loadedSession = runCatching {
            withContext(Dispatchers.IO) {
                runtime.chatRepository.getSessionWindow(currentSessionId)
            }
        }.getOrElse { throwable ->
            if (throwable is CancellationException) throw throwable
            diagnosticLogger.exception(
                category = "storage",
                event = "session_hydration_failed",
                throwable = throwable,
                level = "warn",
                sessionId = currentSessionId,
            )
            null
        } ?: return persisted
        return persisted.copy(
            sessions = replaceOrPrependSession(persisted.sessions, loadedSession),
        )
    }

    private fun persistSessionSelection(
        sessionId: String,
        loadedSession: ChatSession?,
    ) {
        if (loadedSession == null) {
            persistCurrentSessionId(sessionId)
            return
        }
        chatStateStore.update { persisted ->
            persisted.copy(
                sessions = replaceOrPrependSession(persisted.sessions, loadedSession)
                    .withMessagesOnlyForSession(sessionId),
                currentSessionId = sessionId,
            )
        }
    }

    private fun replaceOrPrependSession(
        sessions: List<ChatSession>,
        session: ChatSession,
    ): List<ChatSession> = if (sessions.any { it.id == session.id }) {
        sessions.map { existing ->
            if (existing.id == session.id) session else existing
        }
    } else {
        listOf(session) + sessions
    }

    private fun List<ChatSession>.withMessagesOnlyForSession(sessionId: String): List<ChatSession> =
        map { session ->
            if (session.id == sessionId) {
                session
            } else {
                session.copy(
                    messages = emptyList(),
                    messageCount = maxOf(session.messageCount, session.messages.size),
                    lastMessageAtMillis = session.lastMessageAtMillis
                        ?: session.messages.maxOfOrNull { it.createdAtMillis },
                )
            }
        }

    private fun replacePersistedChats(
        sessions: List<ChatSession>,
        currentSessionId: String,
    ) {
        chatStateStore.update { persisted ->
            persisted.copy(
                sessions = sessions,
                currentSessionId = currentSessionId,
            )
        }
    }

    private fun persistSessionSnapshot(
        session: ChatSession,
        currentSessionId: String? = null,
        moveToFront: Boolean = false,
    ) {
        chatStateStore.update { persisted ->
            val currentIndex = persisted.sessions.indexOfFirst { it.id == session.id }
            val updatedSessions = persisted.sessions.toMutableList().apply {
                if (currentIndex >= 0) {
                    removeAt(currentIndex)
                }
                val insertIndex = when {
                    moveToFront -> 0
                    currentIndex >= 0 -> currentIndex.coerceAtMost(size)
                    else -> 0
                }
                add(insertIndex, session)
            }
            persisted.copy(
                sessions = updatedSessions,
                currentSessionId = currentSessionId ?: persisted.currentSessionId,
            )
        }
    }

    private suspend fun persistSessionSnapshotAndFlush(
        session: ChatSession,
        moveToFront: Boolean = false,
        currentSessionId: String? = null,
    ) {
        chatStateStore.updateAndFlush { persisted ->
            val currentIndex = persisted.sessions.indexOfFirst { it.id == session.id }
            val updatedSessions = persisted.sessions.toMutableList().apply {
                if (currentIndex >= 0) {
                    removeAt(currentIndex)
                }
                val insertIndex = when {
                    moveToFront -> 0
                    currentIndex >= 0 -> currentIndex.coerceAtMost(size)
                    else -> 0
                }
                add(insertIndex, session)
            }
            persisted.copy(
                sessions = updatedSessions,
                currentSessionId = currentSessionId ?: persisted.currentSessionId,
            )
        }
    }

    private fun rememberBrowserDeskSourcesOnCurrentUser(
        sources: List<kira.ditto.data.KnowledgeCitation>,
        images: List<BrowserInlineImage> = emptyList(),
    ) {
        if (sources.isEmpty() && images.isEmpty()) return
        val sessionId = _uiState.value.currentSessionId
        if (sessionId.isBlank() || sessionId == DraftSessionId) return
        fun attach(session: ChatSession): ChatSession? {
            val index = session.messages.indexOfLast { message ->
                message.author == MessageAuthor.User &&
                    message.displayKind == MessageDisplayKind.Standard
            }
            if (index < 0) return null
            val message = session.messages[index]
            val nextSources = sources.ifEmpty { message.knowledgeCitations }
            val nextImages = images.ifEmpty { message.browserInlineImages }
            if (message.knowledgeCitations == nextSources &&
                message.browserInlineImages == nextImages
            ) {
                return null
            }
            val updated = session.messages.toMutableList()
            updated[index] = message.copy(
                knowledgeCitations = nextSources,
                browserInlineImages = nextImages,
            )
            return session.withMessages(updated)
        }
        _uiState.update { current ->
            val sessionIndex = current.sessions.indexOfFirst { it.id == sessionId }
            if (sessionIndex < 0) return@update current
            val updatedSession = attach(current.sessions[sessionIndex]) ?: return@update current
            current.copy(
                sessions = current.sessions.toMutableList().apply { set(sessionIndex, updatedSession) },
            )
        }
        persistSessionMutation(sessionId, ::attach)
    }

    /**
     * Mark the turn's citations the moment the turn ends.
     *
     * This used to ride on [sealAndClearBrowserDesk], which sounds like "the turn is over" but is
     * really "the user did the next thing" — its five call sites are pause, new chat, switch
     * session, agent-mode toggle, and sending the next message. So citations were not slow, they
     * were never triggered until the user acted.
     *
     * The three steps are ordered, and the order is the whole point. Once the turn stops running,
     * `browserPreviewUsesLiveDesk` sends the card to the stored message snapshot, so writing the
     * verdict onto the live desk without rewriting the snapshot right after is invisible.
     */
    private fun settleBrowserCitationsForTurn(sessionId: String) {
        if (sessionId != _uiState.value.currentSessionId) return
        if (kira.ditto.browser.BrowserDesk.state.value.previewsByTopic.isEmpty()) return
        // The answer is the evidence. If it has not reached state yet there is nothing to resolve
        // against, so settle nothing and let sealAndClearBrowserDesk's fallback catch it later.
        if (currentTurnAnswerMarkdown().isBlank()) return
        val resolved = resolveBrowserCitationsForFinishedTurn()   // resolve, then applyCitations
        persistBrowserDeskOntoCurrentSession()                    // rewrite the snapshot the card reads
        rememberBrowserDeskSourcesOnCurrentUser(sources = resolved)
    }

    /**
     * The text of the answer this turn just produced, as the reader sees it.
     *
     * The citation resolver reads the answer back to work out which browsed pages it leaned on, so
     * it needs every agent message after the last user message joined in order.
     */
    private fun currentTurnAnswerMarkdown(): String {
        val sessionId = _uiState.value.currentSessionId
        val session = _uiState.value.sessions.firstOrNull { it.id == sessionId } ?: return ""
        val lastUser = session.messages.indexOfLast { message ->
            message.author == MessageAuthor.User && message.displayKind == MessageDisplayKind.Standard
        }
        if (lastUser < 0) return ""
        return session.messages.drop(lastUser + 1)
            .filter { it.author == MessageAuthor.Agent }
            .joinToString("\n\n") { it.text }
            .trim()
    }

    /**
     * Turn "the agent read these pages" into "the answer cited these pages".
     *
     * Runs once the answer is complete, because that answer is the evidence. Until it runs the card
     * shows pages as read, never as cited - which is the honest state, and the reason the card's
     * badges and the bubble's footnotes used to disagree.
     */
    private fun resolveBrowserCitationsForFinishedTurn(): List<kira.ditto.data.KnowledgeCitation> {
        val desk = kira.ditto.browser.BrowserDesk.state.value
        val answer = currentTurnAnswerMarkdown()
        if (answer.isBlank() || desk.previewsByTopic.isEmpty()) return desk.sources
        val citations = kira.ditto.browser.resolveBrowserCitations(
            answerMarkdown = answer,
            topics = desk.previewsByTopic,
        )
        if (citations.isEmpty()) return desk.sources
        kira.ditto.browser.BrowserDesk.applyCitations(citations)
        persistBrowserCitationLedger(citations)
        persistHostDerivedTaskMemo(citations, answer)
        return citations.mapIndexed { index, citation ->
            kira.ditto.data.KnowledgeCitation(
                index = index + 1,
                sourceName = citation.title.ifBlank { kira.ditto.data.markdownSourceHost(citation.url) },
                text = citation.quote.ifBlank { citation.title },
                url = citation.url,
            )
        }
    }

    /**
     * Write the turn's pages, passages and citations into the research ledger.
     *
     * The in-memory page graph is cleared when a turn ends, so without this a citation resolved
     * today would have nothing to point at tomorrow. Fire-and-forget: a ledger write failing must
     * never hold up sealing the desk.
     */
    private fun persistBrowserCitationLedger(citations: List<kira.ditto.browser.BrowserCitation>) {
        val sessionId = _uiState.value.currentSessionId
        if (sessionId.isBlank() || sessionId == DraftSessionId || citations.isEmpty()) return
        val session = _uiState.value.sessions.firstOrNull { it.id == sessionId } ?: return
        val messageId = session.messages.lastOrNull { it.author == MessageAuthor.Agent }?.id ?: return
        val now = System.currentTimeMillis()
        val pages = ArrayList<kira.ditto.data.chatdb.BrowserPageEntity>()
        val passages = ArrayList<kira.ditto.data.chatdb.BrowserPassageEntity>()
        citations.map { it.pageKey }.distinct().forEach { key ->
            val indexed = kira.ditto.browser.BrowserResearchGraph.lookup(key) ?: return@forEach
            pages += kira.ditto.data.chatdb.BrowserPageEntity(
                sessionId = sessionId,
                pageKey = key,
                url = indexed.url,
                canonical = indexed.canonical,
                title = indexed.title,
                contentHash = indexed.contentHash,
                charCount = indexed.text.length,
                indexedAtMillis = indexed.indexedAtMillis,
            )
            indexed.passages.forEach { passage ->
                if (passage.ordinal < 0) return@forEach
                passages += kira.ditto.data.chatdb.BrowserPassageEntity(
                    sessionId = sessionId,
                    pageKey = key,
                    contentHash = indexed.contentHash,
                    ordinal = passage.ordinal,
                    heading = passage.heading,
                    text = passage.text,
                )
            }
        }
        val rows = citations.map { citation ->
            kira.ditto.data.chatdb.BrowserCitationEntity(
                id = "$sessionId:$messageId:${citation.pageKey}",
                sessionId = sessionId,
                messageId = messageId,
                topicId = citation.topicId,
                pageKey = citation.pageKey,
                url = citation.url,
                title = citation.title,
                contentHash = citation.contentHash,
                passageOrdinal = citation.passageOrdinal,
                quote = citation.quote,
                quoteHash = citation.quoteHash,
                confidence = citation.confidence.name,
                kind = citation.kind.name,
                createdAtMillis = now,
            )
        }
        viewModelScope.launch {
            runCatching {
                runtime.chatRepository.recordBrowserCitations(sessionId, messageId, pages, passages, rows)
            }.onFailure { error ->
                android.util.Log.w("AetherViewModel", "browser citation ledger write failed", error)
            }
        }
    }

    /**
     * Leave a memory even when the model wrote none.
     *
     * The subagent is asked for a memo, but it is a model and it will sometimes not write one - a
     * short turn, a format drift, a swarm member that ended early. Rather than lose the turn
     * entirely, the host synthesises one from what it can prove: the pages the answer actually
     * cited. It is marked [MemorySource.HostDerived], which is what keeps it local forever - it
     * records that research happened, not a judgement that it was worth keeping, and only a
     * judgement is worth sending somewhere it cannot be deleted from.
     *
     * A no-op when the model did write one: that row already exists under the same task key.
     */
    private fun persistHostDerivedTaskMemo(
        citations: List<kira.ditto.browser.BrowserCitation>,
        answer: String,
    ) {
        val sessionId = _uiState.value.currentSessionId
        if (sessionId.isBlank() || sessionId == DraftSessionId) return
        val memo = kira.ditto.browser.synthesizeBrowserTaskMemo(citations, answer) ?: return
        val session = _uiState.value.sessions.firstOrNull { it.id == sessionId } ?: return
        val messageId = session.messages.lastOrNull { it.author == MessageAuthor.Agent }?.id ?: return
        val topicId = citations.firstOrNull { it.topicId.isNotBlank() }?.topicId.orEmpty()
        viewModelScope.launch {
            runCatching {
                runtime.chatRepository.recordBrowserTaskMemo(
                    sessionId = sessionId,
                    messageId = "$messageId:host",
                    topicId = topicId,
                    goal = currentTurnUserGoal(),
                    memo = memo,
                )
            }.onFailure { error ->
                android.util.Log.w("AetherViewModel", "host-derived browser memo write failed", error)
            }
        }
    }

    /** The user's own words for this turn - the goal a later recall would search by. */
    private fun currentTurnUserGoal(): String {
        val sessionId = _uiState.value.currentSessionId
        val session = _uiState.value.sessions.firstOrNull { it.id == sessionId } ?: return ""
        return session.messages.lastOrNull { message ->
            message.author == MessageAuthor.User && message.displayKind == MessageDisplayKind.Standard
        }?.text.orEmpty().take(200)
    }

    private fun persistBrowserDeskOntoCurrentSession() {
        val sessionId = _uiState.value.currentSessionId
        if (sessionId.isBlank() || sessionId == DraftSessionId) return
        val desk = kira.ditto.browser.BrowserDesk.state.value
        fun attach(session: ChatSession): ChatSession? {
            val next = attachBrowserDeskPreviewToMessages(session.messages, desk)
            if (next === session.messages) return null
            return session.withMessages(next)
        }
        _uiState.update { current ->
            val sessionIndex = current.sessions.indexOfFirst { it.id == sessionId }
            if (sessionIndex < 0) return@update current
            val updatedSession = attach(current.sessions[sessionIndex]) ?: return@update current
            current.copy(
                sessions = current.sessions.toMutableList().apply { set(sessionIndex, updatedSession) },
            )
        }
        persistSessionMutation(sessionId, ::attach)
    }

    private fun restoreBrowserDeskForSession(sessionId: String, messages: List<ChatMessage>) {
        if (sessionId.isBlank() || sessionId == DraftSessionId) return
        // Always rebuilt from the messages, never restored from a saved copy.
        //
        // A parked copy was a second writer to a state the event log is supposed to own, and it
        // outlived the `TurnRetired` that was meant to clear the previous turn's previews - which
        // is how a turn that never touched the browser ended up wearing the previous turn's card.
        hydrateBrowserDeskFromMessages(messages)
    }

    private fun sealAndClearBrowserDesk(persistSources: Boolean = true) {
        val sessionId = _uiState.value.currentSessionId
        val running = sessionExecutionManager.isSessionRunning(sessionId)
        // Resolve before persisting: it rewrites the desk's hits, and the snapshot written onto the
        // messages has to carry the resolved badges rather than the read-only ones of a moment ago.
        val resolvedSources = if (persistSources && !running) {
            resolveBrowserCitationsForFinishedTurn()
        } else {
            emptyList()
        }
        if (persistSources) {
            persistBrowserDeskOntoCurrentSession()
        }
        if (persistSources && !running) {
            val desk = kira.ditto.browser.BrowserDesk.state.value
            val topicImages = kira.ditto.browser.BrowserTopicGraph.snapshot(sessionId).flatMap { tab ->
                tab.images.map { image ->
                    BrowserInlineImage(url = image.url, alt = image.alt, topicId = tab.topicId)
                }
            }
            rememberBrowserDeskSourcesOnCurrentUser(
                sources = resolvedSources,
                images = topicImages.ifEmpty {
                    collectBrowserInlineImages(desk, curatedOnly = true).map { image ->
                        BrowserInlineImage(url = image.url, alt = image.alt)
                    }
                },
            )
        }
        if (!running) {
            kira.ditto.browser.AetherBrowserRuntime.destroyTopicTabs(sessionId)
        }
        kira.ditto.browser.BrowserDesk.clear()
        if (sessionId.isNotBlank()) {
            kira.ditto.browser.BrowserPageLedger.clearSession(sessionId)
        }
        kira.ditto.browser.BrowserTopicGraph.bindSession(
            if (running) sessionId else "",
        )
    }

    private fun persistSessionMutation(
        sessionId: String,
        transform: (ChatSession) -> ChatSession?,
    ) {
        chatStateStore.update { persisted ->
            val sessionIndex = persisted.sessions.indexOfFirst { it.id == sessionId }
            if (sessionIndex < 0) return@update persisted
            val updatedSession = transform(persisted.sessions[sessionIndex]) ?: return@update persisted
            val updatedSessions = persisted.sessions.toMutableList().apply {
                set(sessionIndex, updatedSession)
            }
            persisted.copy(sessions = updatedSessions)
        }
    }

    private fun refreshWorkspaceFileSuggestions() {
        if (workspaceFileSuggestJob?.isActive == true) return
        workspaceFileSuggestJob = viewModelScope.launch(Dispatchers.IO) {
            val files = runCatching {
                runtime.alpineRuntime.listWorkspaceFileSuggestions("", limit = 80)
            }.getOrDefault(emptyList()).map { entry ->
                val relative = entry.guestPath.removePrefix("/workspace/").trimStart('/')
                FileMentionSuggestion(
                    insertToken = "@$relative",
                    name = entry.name,
                    guestPath = entry.guestPath,
                )
            }
            _uiState.update { current ->
                if (current.workspaceFileSuggestions == files) current
                else current.copy(workspaceFileSuggestions = files)
            }
        }
    }

    private fun syncAcpSessionsIfPossible() {
        if (!_uiState.value.alpineSetupState.isReady) return
        if (acpSessionSyncJob?.isActive == true) return
        acpSessionSyncJob = viewModelScope.launch(Dispatchers.IO) {
            pruneGhostImportedSessions()
            val listed = runCatching {
                runtime.alpineRuntime.listAcpSessions(runtime.alpineRuntime.workspaceRoot)
            }.getOrDefault(emptyList())
            if (listed.isEmpty()) return@launch
            val childKimiIds = listed.mapNotNull { session ->
                session.sessionId.takeIf { it.isNotBlank() && session.isChild }
            }.toSet()
            dropImportedChildSessions(childKimiIds)
            // Do not import unmapped ACP sessions. kimi session/list includes
            // subagent child sessions (often with real task titles and without
            // parent_session_id on the ACP wire), which would otherwise appear
            // as extra chats in the drawer.
        }
    }

    private fun pruneGhostImportedSessions() {
        val ghosts = _uiState.value.sessions.filter { session ->
            session.id != DraftSessionId &&
                session.messages.isEmpty() &&
                session.messageCount <= 0 &&
                (
                    !KimiAcpProtocol.isUsableDisplayText(session.title) ||
                        !runtime.alpineRuntime.mappedKimiSessionId(session.id).isNullOrBlank() ||
                        looksLikeImportedChildSessionTitle(session.title)
                    )
        }
        if (ghosts.isEmpty()) return
        val ghostIds = ghosts.map { it.id }.toSet()
        ghosts.forEach { session ->
            runtime.alpineRuntime.unbindAetherKimiSession(session.id)
        }
        viewModelScope.launch(Dispatchers.Main.immediate) {
            _uiState.update { current ->
                current.copy(
                    sessions = current.sessions.filterNot { it.id in ghostIds },
                    currentSessionId = if (current.currentSessionId in ghostIds) {
                        DraftSessionId
                    } else {
                        current.currentSessionId
                    },
                )
            }
            chatStateStore.update { persisted ->
                persisted.copy(
                    sessions = persisted.sessions.filterNot { it.id in ghostIds },
                    currentSessionId = if (persisted.currentSessionId in ghostIds) {
                        DraftSessionId
                    } else {
                        persisted.currentSessionId
                    },
                )
            }
        }
    }

    private fun dropImportedChildSessions(childKimiIds: Set<String>) {
        val victims = _uiState.value.sessions.filter { session ->
            if (session.id == DraftSessionId) return@filter false
            val kimiId = runtime.alpineRuntime.mappedKimiSessionId(session.id).orEmpty()
            kimiId in childKimiIds || looksLikeImportedChildSessionTitle(session.title)
        }
        if (victims.isEmpty()) return
        val victimIds = victims.map { it.id }.toSet()
        victims.forEach { session ->
            runtime.alpineRuntime.unbindAetherKimiSession(session.id)
        }
        viewModelScope.launch(Dispatchers.Main.immediate) {
            _uiState.update { current ->
                current.copy(
                    sessions = current.sessions.filterNot { it.id in victimIds },
                    currentSessionId = if (current.currentSessionId in victimIds) {
                        DraftSessionId
                    } else {
                        current.currentSessionId
                    },
                )
            }
            chatStateStore.update { persisted ->
                persisted.copy(
                    sessions = persisted.sessions.filterNot { it.id in victimIds },
                    currentSessionId = if (persisted.currentSessionId in victimIds) {
                        DraftSessionId
                    } else {
                        persisted.currentSessionId
                    },
                )
            }
        }
    }

    private fun looksLikeImportedChildSessionTitle(title: String): Boolean {
        val text = title.trim()
        return text.startsWith("Child: ") || text.startsWith("Launching ")
    }

    private fun replayImportedAcpSessionIfNeeded(sessionId: String) {
        if (sessionId.isBlank() || sessionId == DraftSessionId) return
        viewModelScope.launch(Dispatchers.IO) {
            val snapshot = withContext(Dispatchers.Main.immediate) {
                _uiState.value.sessions.firstOrNull { it.id == sessionId }
            } ?: return@launch
            val roomSession = runCatching {
                runtime.chatRepository.getSessionWithMessages(sessionId)
            }.getOrNull()
            if (roomSession != null && roomSession.messages.isNotEmpty()) {
                withContext(Dispatchers.Main.immediate) {
                    updateSession(sessionId) { session ->
                        if (session.messages.isNotEmpty()) null else roomSession
                    }
                }
                return@launch
            }
            if (snapshot.messages.isNotEmpty() || snapshot.messageCount > 0) return@launch
            if (runtime.alpineRuntime.mappedKimiSessionId(sessionId).isNullOrBlank()) return@launch
            val cwd = workspaceFileBridge.workspaceDirectory(workspaceIdForSession(sessionId))
            val turns = runtime.alpineRuntime.loadKimiSessionHistory(sessionId, cwd)
            if (turns.isEmpty()) return@launch
            val now = System.currentTimeMillis()
            val messages = turns.mapIndexedNotNull { index, turn ->
                val text = if (turn.role == "user") {
                    KimiAcpProtocol.visibleUserTextFromReplay(turn.text)
                } else {
                    turn.text.trim()
                }
                if (text.isBlank()) return@mapIndexedNotNull null
                ChatMessage(
                    id = UUID.randomUUID().toString(),
                    author = if (turn.role == "user") MessageAuthor.User else MessageAuthor.Agent,
                    text = text,
                    createdAtMillis = now + index,
                )
            }
            if (messages.isEmpty()) return@launch
            withContext(Dispatchers.Main.immediate) {
                updateSession(sessionId) { session ->
                    if (session.messages.isNotEmpty()) null else session.withMessages(messages)
                }
            }
        }
    }

    private suspend fun persistDeleteSession(
        sessionId: String,
        sharedWorkspaceFilePaths: Collection<String> = emptyList(),
    ) {
        // Drop the ACP session so session/list no longer returns a ghost after
        // the local chat is removed.
        withContext(Dispatchers.IO) {
            runCatching { runtime.alpineRuntime.deleteAetherKimiSession(sessionId) }
        }
        val unreferencedSharedWorkspaceFilePaths = sharedWorkspaceFilePaths
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        chatStateStore.updateAndFlush(
            writeIntent = PersistedChatWriteIntent.DeleteSession,
        ) { persisted ->
            val updatedSessions = persisted.sessions.filterNot { it.id == sessionId }
            persisted.copy(
                sessions = updatedSessions,
                currentSessionId = if (persisted.currentSessionId == sessionId) {
                    DraftSessionId
                } else {
                    persisted.currentSessionId
                },
            )
        }
        scheduleSessionRuntimeDataCleanup(
            sessionIds = listOf(sessionId),
            sharedWorkspaceFilePaths = unreferencedSharedWorkspaceFilePaths,
        )
    }

    private fun scheduleSessionRuntimeDataCleanup(
        sessionIds: Collection<String>,
        sharedWorkspaceFilePaths: Collection<String>,
    ) {
        val cleanupSessionIds = sessionIds
            .filter { it.isNotBlank() && it != DraftSessionId }
            .distinct()
        val sharedWorkspaceFileCount = sharedWorkspaceFilePaths.distinct().size
        if (cleanupSessionIds.isEmpty() && sharedWorkspaceFileCount == 0) return

        viewModelScope.launch {
            workspaceFileBridge.deleteSessionsRuntimeData(
                sessionIds = cleanupSessionIds,
                sharedWorkspaceFilePaths = sharedWorkspaceFilePaths,
            )
                .onSuccess {
                    diagnosticLogger.event(
                        category = "storage",
                        event = "deleted_session_runtime_cleanup_completed",
                        sessionId = cleanupSessionIds.singleOrNull(),
                        details = mapOf(
                            "session_count" to cleanupSessionIds.size,
                            "shared_workspace_file_count" to sharedWorkspaceFileCount,
                        ),
                    )
                }
                .onFailure { throwable ->
                    diagnosticLogger.exception(
                        category = "storage",
                        event = "deleted_session_runtime_cleanup_failed",
                        throwable = throwable,
                        level = "warn",
                        sessionId = cleanupSessionIds.singleOrNull(),
                        details = mapOf(
                            "session_count" to cleanupSessionIds.size,
                            "shared_workspace_file_count" to sharedWorkspaceFileCount,
                        ),
                    )
                }
        }
    }

    private fun persistPrunedSessionSelections(
        enabledSkillIds: Set<String>,
        enabledMcpServerIds: Set<String>,
    ) {
        chatStateStore.update { persisted ->
            persisted.copy(
                sessions = persisted.sessions.map { session ->
                    val selectedSkillIds = session.selectedSkillIds.filter(enabledSkillIds::contains)
                    val activeSkills = session.activeSkills.filter { activeSkill ->
                        selectedSkillIds.contains(activeSkill.skillId)
                    }
                    val activeMcpServerIds = session.activeMcpServerIds.filter { serverId ->
                        enabledMcpServerIds.contains(serverId) &&
                            !kira.ditto.upa.isUpaMcpServerId(serverId)
                    }
                    if (
                        selectedSkillIds == session.selectedSkillIds &&
                        activeSkills == session.activeSkills &&
                        activeMcpServerIds == session.activeMcpServerIds
                    ) {
                        session
                    } else {
                        session.copy(
                            selectedSkillIds = selectedSkillIds,
                            activeSkills = activeSkills,
                            activeMcpServerIds = activeMcpServerIds,
                        )
                    }
                }
            )
        }
    }

    private fun buildAnalyticsTurnRequest(
        snapshot: AetherUiState,
        sessionId: String,
        userMessage: ChatMessage,
    ): SessionTurnRequest? {
        val session = snapshot.sessions.firstOrNull { it.id == sessionId } ?: return null
        return SessionTurnRequest(
            sessionId = sessionId,
            settings = resolveSessionModelSettings(
                snapshot,
                session,
                session.selectedModelKey,
            ),
            requestMessages = session.messages + userMessage,
            selectedSkillIds = session.selectedSkillIds,
            activeSkills = session.activeSkills,
            activeMcpServerIds = session.activeMcpServerIds,
            agentModeEnabled = session.agentModeEnabled,
            chromeEnabled = session.chromeEnabled,
            providerConfigs = snapshot.providerConfigs,
            workspaceId = session.workspaceId,
        ).withRemoteFrom(session, snapshot.settings)
    }

    private fun captureMessageSent(
        request: SessionTurnRequest,
        attachments: List<ChatAttachment>,
        isEdit: Boolean,
        submissionType: String,
    ) {
        val modelProperties = modelUsageProperties(request, source = "message")
        captureAnalyticsEvent(
            event = "message sent",
            properties = mapOf(
                "has_attachments" to attachments.isNotEmpty(),
                "attachment_count" to attachments.size,
                "agent_mode_enabled" to request.agentModeEnabled,
                "skill_count" to request.selectedSkillIds.size,
                "mcp_server_count" to request.activeMcpServerIds.size,
                "is_edit" to isEdit,
                "submission_type" to submissionType,
            ) + modelProperties,
        )
        captureAnalyticsEvent(
            event = "model used",
            properties = modelProperties + mapOf(
                "has_attachments" to attachments.isNotEmpty(),
                "attachment_count" to attachments.size,
                "is_edit" to isEdit,
                "submission_type" to submissionType,
            ),
        )
        captureAgentModeStarted(
            request = request,
            isEdit = isEdit,
            submissionType = submissionType,
        )
    }

    private fun captureTurnCompleted(event: SessionTurnEvent) {
        val tokenProperties = tokenUsageAnalyticsProperties(event)
        captureAnalyticsEvent(
            event = "conversation turn completed",
            properties = mapOf(
                "outcome" to event.outcome.name.lowercase(),
                "tool_call_count" to event.toolCallCount,
                "distinct_tool_count" to event.distinctToolCount,
                "tool_names" to event.toolNames,
                "has_tool_calls" to (event.toolCallCount > 0),
                "duration_millis" to (event.durationMillis ?: 0L),
            ) + tokenProperties,
        )
        if (event.tokenUsage != null) {
            captureAnalyticsEvent(
                event = "tokens used",
                properties = tokenProperties + mapOf(
                    "outcome" to event.outcome.name.lowercase(),
                    "tool_call_count" to event.toolCallCount,
                    "has_tool_calls" to (event.toolCallCount > 0),
                    "duration_millis" to (event.durationMillis ?: 0L),
                ),
            )
        }
    }

    private fun trackTermuxSetupState(
        setupState: TermuxSetupState,
        source: String,
    ) {
        if (!_uiState.value.settings.privacyPolicyAccepted) return
        if (setupState.issue != TermuxSetupIssue.NotInstalled &&
            lastTrackedTermuxDetectedIssue != setupState.issue
        ) {
            lastTrackedTermuxDetectedIssue = setupState.issue
            captureAnalyticsEvent(
                event = "termux detected",
                properties = mapOf(
                    "source" to source,
                    "issue" to setupState.issue.name.lowercase(),
                    "is_ready" to setupState.isReady,
                ),
            )
        }

        val setupSource = pendingTermuxSetupSource ?: return
        if (!setupState.isReady) return
        pendingTermuxSetupSource = null
        captureAnalyticsEvent(
            event = "termux setup completed",
            properties = mapOf(
                "source" to setupSource,
                "detected_source" to source,
                "issue" to setupState.issue.name.lowercase(),
            ),
        )
    }

    private fun captureAgentModeStarted(
        request: SessionTurnRequest,
        isEdit: Boolean,
        submissionType: String,
    ) {
        if (!request.agentModeEnabled) return
        val properties = modelUsageProperties(request, source = "agent_mode") + mapOf(
            "authorization_enabled" to request.settings.agentModeAuthorizationEnabled,
            "authorization_method" to request.settings.agentModeAuthorizationMethod.storageValue,
            "is_edit" to isEdit,
            "submission_type" to submissionType,
        )
        if (request.settings.agentModeAuthorizationEnabled) {
            captureAnalyticsEvent(
                event = "agent mode started",
                properties = properties,
            )
        } else {
            captureAnalyticsEvent(
                event = "agent mode failed",
                properties = properties + mapOf("reason" to "authorization_disabled"),
            )
        }
    }

    private fun modelUsageProperties(
        request: SessionTurnRequest,
        source: String,
    ): Map<String, Any> = mapOf(
        "model" to request.settings.modelId.trim(),
        "provider" to kira.ditto.data.PiProviderCatalog
            .resolve(request.settings.piProviderId).displayName,
        "provider_type" to request.settings.piProviderId,
        "source" to source,
        "agent_mode_enabled" to request.agentModeEnabled,
        "skill_count" to request.selectedSkillIds.size,
        "mcp_server_count" to request.activeMcpServerIds.size,
    )

    private fun tokenUsageAnalyticsProperties(event: SessionTurnEvent): Map<String, Any> {
        val usage = event.tokenUsage
        val totalTokens = usage?.totalTokens ?: 0L
        val inputTokens = usage?.inputTokens ?: 0L
        val outputTokens = usage?.outputTokens ?: 0L
        val inputMessageCount = event.inputMessageCount.coerceAtLeast(0)
        val userMessageCount = event.userMessageCount.coerceAtLeast(0)
        return buildMap {
            put("token_usage_source", event.tokenUsageSource)
            put("has_token_usage", usage != null)
            put("input_message_count", inputMessageCount)
            put("user_message_count", userMessageCount)
            put("llm_request_count", usage?.requestCount ?: 0)
            put("input_tokens", inputTokens)
            put("output_tokens", outputTokens)
            put("total_tokens", totalTokens)
            usage?.reasoningTokens?.let { put("reasoning_tokens", it) }
            usage?.cachedInputTokens?.let { put("cached_input_tokens", it) }
            put(
                "average_tokens_per_input_message",
                if (inputMessageCount > 0) totalTokens.toDouble() / inputMessageCount else 0.0,
            )
            put(
                "average_input_tokens_per_input_message",
                if (inputMessageCount > 0) inputTokens.toDouble() / inputMessageCount else 0.0,
            )
            put(
                "average_tokens_per_user_message",
                if (userMessageCount > 0) totalTokens.toDouble() / userMessageCount else 0.0,
            )
        }
    }

    private fun captureAnalyticsEvent(
        event: String,
        properties: Map<String, Any> = emptyMap(),
    ) {
        AetherAnalytics.capture(event = event, properties = properties)
    }

    private fun normalizeProviderConfig(
        config: LlmProviderConfig,
    ): LlmProviderConfig {
        val definition = kira.ditto.data.PiProviderCatalog.resolve(
            config.piProviderId,
        )
        val manualModels = (config.manualModelIds.ifEmpty { listOf(config.modelId) })
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        val cachedModels = config.cachedModels
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        val availableModels = (cachedModels + manualModels)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        val normalizedModelId = config.modelId.trim()
            .takeIf { it.isNotBlank() && availableModels.contains(it) }
            ?: manualModels.firstOrNull()
            ?: cachedModels.firstOrNull()
            ?: definition.defaultModelId
        val normalizedEnabledModels = config.enabledModelIds
            .map(String::trim)
            .filter { it.isNotEmpty() && availableModels.contains(it) }
            .distinct()
        return config.copy(
            providerId = config.providerId.trim(),
            name = config.name.trim().ifBlank { definition.displayName },
            piProviderId = definition.id,
            baseUrl = config.baseUrl.trim(),
            modelId = normalizedModelId,
            manualModelIds = manualModels,
            userAgent = normalizeLlmUserAgent(config.userAgent),
            customHeaders = config.customHeaders
                .map { header -> header.copy(name = header.name.trim()) }
                .filter { header ->
                    header.name.isNotBlank() &&
                        !header.name.equals("User-Agent", ignoreCase = true)
                }
                .distinctBy { header -> header.name.lowercase() },
            providerEnvironmentVariables = config.providerEnvironmentVariables
                .map { variable -> variable.copy(name = variable.name.trim()) }
                .filter { variable -> variable.name.isNotBlank() }
                .distinctBy { variable -> variable.name.uppercase() },
            cachedModels = cachedModels,
            enabledModelIds = normalizedEnabledModels,
        )
    }

    private fun persistPersonaSessionBinding(sessionId: String, personaId: String) {
        if (sessionId.isBlank() || sessionId == DraftSessionId || personaId.isBlank()) return
        sessionPersonaIds[sessionId] = personaId
        viewModelScope.launch {
            val persona = personaRepository.store.first().personas.firstOrNull { it.id == personaId }
                ?: return@launch
            if (persona.activeSessionId == sessionId) return@launch
            personaRepository.upsertPersona(
                persona.copy(
                    activeSessionId = sessionId,
                    updatedAtMillis = System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun clearPersonaSessionBinding(sessionId: String) {
        val personaId = sessionPersonaIds.remove(sessionId).orEmpty()
        if (personaId.isBlank()) return
        viewModelScope.launch {
            val persona = personaRepository.store.first().personas.firstOrNull { it.id == personaId }
                ?: return@launch
            if (persona.activeSessionId != sessionId) return@launch
            personaRepository.upsertPersona(
                persona.copy(
                    activeSessionId = "",
                    updatedAtMillis = System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun consumePendingHiddenContextMessage(now: Long): ChatMessage? {
        val briefing = pendingHiddenContextText.trim().takeIf(String::isNotBlank) ?: return null
        pendingHiddenContextText = ""
        return ChatMessage(
            id = "persona-briefing-$now",
            author = MessageAuthor.User,
            text = briefing,
            createdAtMillis = now - 1L,
            displayKind = MessageDisplayKind.HiddenContext,
        )
    }

    private fun createSession(
        id: String,
        messages: List<ChatMessage>,
        title: String? = null,
        hasCustomTitle: Boolean = false,
        selectedModelKey: String = "",
        selectedSkillIds: List<String> = emptyList(),
        activeSkills: List<ActiveSkillContext> = emptyList(),
        activeMcpServerIds: List<String> = emptyList(),
        agentModeEnabled: Boolean = false,
        chromeEnabled: Boolean = false,
        /**
         * New conversations open in the space the user is already in, not always the default.
         *
         * Reads the space directly rather than resolving it through the open conversation: a draft
         * has no row in `sessions`, so going via the conversation only works by way of a fallback,
         * and would start returning the wrong space the day drafts gain one.
         */
        workspaceId: String = currentWorkspaceId(),
    ): ChatSession {
        val metadata = deriveSessionMetadata(messages)
        return ChatSession(
            id = id,
            title = title ?: metadata.title,
            preview = metadata.preview,
            hasCustomTitle = hasCustomTitle,
            messages = messages,
            selectedModelKey = selectedModelKey,
            selectedSkillIds = selectedSkillIds,
            activeSkills = activeSkills,
            activeMcpServerIds = activeMcpServerIds,
            agentModeEnabled = agentModeEnabled,
            chromeEnabled = chromeEnabled,
            workspaceId = workspaceId,
        )
    }

    private fun ChatSession.withMessages(messages: List<ChatMessage>): ChatSession {
        val syncedMessages = syncActiveBranches(messages)
        val metadata = deriveSessionMetadata(syncedMessages)
        return copy(
            title = if (hasCustomTitle) title else metadata.title,
            preview = metadata.preview,
            messages = syncedMessages,
            messageCount = syncedMessages.size,
            lastMessageAtMillis = syncedMessages.maxOfOrNull { it.createdAtMillis },
        )
    }

    private suspend fun resolveSelectedActiveSkills(
        selectedSkillIds: List<String>,
        existingActiveSkills: List<ActiveSkillContext>,
    ): List<ActiveSkillContext> {
        if (selectedSkillIds.isEmpty()) return emptyList()
        val installedSkillsById = _uiState.value.installedSkills
            .filter { it.isEnabled }
            .associateBy { it.id }
        return buildList {
            selectedSkillIds.distinct().forEach { skillId ->
                val installedSkill = installedSkillsById[skillId] ?: return@forEach
                val refreshedSkill = skillManager.buildActiveSkillContext(installedSkill)
                    .getOrElse { return@forEach }
                add(refreshedSkill)
            }
        }
    }

    private fun upsertActiveSkillContext(
        activeSkills: List<ActiveSkillContext>,
        activeSkill: ActiveSkillContext,
    ): List<ActiveSkillContext> {
        val existingIndex = activeSkills.indexOfFirst { it.skillId == activeSkill.skillId }
        if (existingIndex < 0) return activeSkills + activeSkill
        return activeSkills.toMutableList().apply {
            set(existingIndex, activeSkill)
        }
    }

    private fun resolveSelectedMcpServers(
        selectedServerIds: List<String>,
    ): List<McpServerConfig> {
        if (selectedServerIds.isEmpty()) return emptyList()
        val enabledServersById = _uiState.value.mcpServers
            .filter { it.isEnabled }
            .associateBy { it.id }
        return selectedServerIds.distinct().mapNotNull(enabledServersById::get)
    }

    private fun setSessionSelectedSkillIds(
        sessionId: String,
        selectedSkillIds: List<String>,
    ) {
        updateSession(sessionId) { session ->
            val activeSkills = session.activeSkills.filter { activeSkill ->
                selectedSkillIds.contains(activeSkill.skillId)
            }
            if (
                session.selectedSkillIds == selectedSkillIds &&
                session.activeSkills == activeSkills
            ) {
                null
            } else {
                session.copy(
                    selectedSkillIds = selectedSkillIds,
                    activeSkills = activeSkills,
                )
            }
        }
    }

    private fun setSessionActiveSkills(
        sessionId: String,
        activeSkills: List<ActiveSkillContext>,
    ) {
        updateSession(sessionId) { session ->
            if (session.activeSkills == activeSkills) {
                null
            } else {
                session.copy(activeSkills = activeSkills)
            }
        }
    }

    private fun setSessionActiveMcpServerIds(
        sessionId: String,
        activeMcpServerIds: List<String>,
    ) {
        updateSession(sessionId) { session ->
            if (session.activeMcpServerIds == activeMcpServerIds) {
                null
            } else {
                session.copy(activeMcpServerIds = activeMcpServerIds)
            }
        }
    }

    private fun updateSession(
        sessionId: String,
        transform: (ChatSession) -> ChatSession?,
    ) {
        var didUpdate = false
        _uiState.update { current ->
            val sessionIndex = current.sessions.indexOfFirst { it.id == sessionId }
            if (sessionIndex < 0) return@update current
            val updatedSessions = current.sessions.toMutableList()
            val session = updatedSessions.removeAt(sessionIndex)
            val updatedSession = transform(session)
            if (updatedSession == null) {
                updatedSessions.add(sessionIndex, session)
                current
            } else {
                didUpdate = true
                updatedSessions.add(
                    sessionIndex.coerceAtMost(updatedSessions.size),
                    updatedSession,
                )
                current.copy(sessions = updatedSessions)
            }
        }
        if (didUpdate) {
            persistSessionMutation(sessionId, transform)
        }
    }

    private suspend fun mergedMcpServers(persisted: List<McpServerConfig>): List<McpServerConfig> {
        return kira.ditto.data.mergeShippedMcpServers(persisted)
    }

    private fun findMcpServerById(serverId: String): McpServerConfig? =
        _uiState.value.mcpServers.firstOrNull { it.id == serverId }

    private fun updateOrderedSelection(
        currentSelection: List<String>,
        id: String,
        selected: Boolean,
    ): List<String> = when {
        selected && currentSelection.contains(id) -> currentSelection
        selected -> currentSelection + id
        else -> currentSelection.filterNot { it == id }
    }

    private fun deriveSessionMetadata(messages: List<ChatMessage>): SessionMetadata {
        val visibleMessages = messages.filter { it.displayKind != MessageDisplayKind.HiddenContext }
        val title = messages
            .firstOrNull { it.author == MessageAuthor.User && it.displayKind == MessageDisplayKind.Standard }
            ?.summaryText()
            .orEmpty()
            .ifBlank { "New chat" }
            .sanitizeGeneratedSessionTitle()

        val preview = visibleMessages
            .lastOrNull()
            ?.summaryText()
            .orEmpty()
            .ifBlank { "No messages yet." }
            .take(96)

        return SessionMetadata(title = title, preview = preview)
    }

    private fun generateSessionTitle(
        sessionId: String,
        seedMessage: ChatMessage,
        settings: AppSettings,
    ) {
                if (!settings.isProviderSetupValid()) {
            return
        }

        val titleInput = buildTitleGenerationInput(seedMessage)
        if (titleInput.isBlank()) return

        viewModelScope.launch {
            while (sessionExecutionManager.isSessionRunning(sessionId)) {
                delay(250)
            }
            val providerConfigs = _uiState.value.providerConfigs
            val titleSettings = resolveModelSettings(
                baseSettings = settings,
                providerConfigs = providerConfigs,
                preferredModelKey = resolveDefaultTitleModelKey(settings, providerConfigs),
                fallbackModelKey = resolveDefaultChatModelKey(settings, providerConfigs),
            )
            val modelKey = thinkingCatalogKey(titleSettings.piProviderId, titleSettings.modelId)
            val thinkingLevelMap = _uiState.value.thinkingLevelClampsByProviderModel[modelKey].orEmpty()
            val isReasoningModel = modelKey in _uiState.value.reasoningModels
            val fastestEffort = if (isReasoningModel) {
                fastestSupportedReasoningEffort(
                    _uiState.value.thinkingLevelsByProviderModel[modelKey].orEmpty(),
                )
            } else {
                "off"
            }
            val titleCallSettings = titleSettings.copy(reasoningEffort = fastestEffort)
            val title = run {
                var draft = ""
                var prompt = titleInput
                repeat(3) { attempt ->
                    val completion = piCompletionClient.completeOnce(
                        settings = titleCallSettings,
                        systemPrompt = SessionTitleSystemPrompt,
                        messages = listOf(
                            LlmMessage(
                                role = "user",
                                contentParts = listOf(LlmTextPart(prompt)),
                            )
                        ),
                        disableReasoning = fastestEffort == "off",
                        thinkingLevelMap = thinkingLevelMap,
                        isReasoningModel = isReasoningModel && fastestEffort != "off",
                    ).getOrNull() ?: return@repeat
                    runtime.usageRecorder.recordTitle(
                        sessionId = sessionId,
                        providerId = titleCallSettings.piProviderId,
                        modelId = titleCallSettings.modelId,
                        result = completion,
                        promptChars = prompt.length,
                    )
                    val raw = completion
                        .assistantText
                        .sanitizeGeneratedSessionTitle()
                        .orEmpty()
                    if (raw.isBlank()) return@repeat
                    draft = raw
                    if (raw.sessionTitleFits()) return@run raw
                    prompt = sessionTitleRewritePrompt(raw, titleInput)
                }
                draft.takeIf { it.sessionTitleFits() }.orEmpty()
            }

            if (title.isBlank() || title.looksLikeHiddenPromptTitle()) return@launch

            updateSession(sessionId) { session ->
                val firstUserMessage = session.messages.firstOrNull { it.author == MessageAuthor.User }
                if (firstUserMessage?.id != seedMessage.id) {
                    null
                } else {
                    session.copy(
                        title = title,
                        hasCustomTitle = true,
                    )
                }
            }
        }
    }

    private fun buildTitleGenerationInput(
        message: ChatMessage,
    ): String = buildString {
        val text = message.text.visibleUserMessageText()
        if (text.isNotBlank()) {
            appendLine("First user message:")
            appendLine(text)
        }
        if (message.attachments.isNotEmpty()) {
            if (isNotEmpty()) appendLine()
            appendLine("Attachments:")
            message.attachments.forEach { attachment ->
                appendLine("- ${attachment.name}")
            }
        }
    }.trim()

    private fun ensureDraftWorkspaceId(): String {
        val snapshot = _uiState.value
        return snapshot.editingSessionId ?: when {
            snapshot.currentSessionId != DraftSessionId -> snapshot.currentSessionId
            !snapshot.draftWorkspaceId.isNullOrBlank() -> snapshot.draftWorkspaceId.orEmpty()
            else -> {
                val generatedId = "session-${System.currentTimeMillis()}"
                _uiState.update { current ->
                    if (current.currentSessionId == DraftSessionId && current.draftWorkspaceId.isNullOrBlank()) {
                        current.copy(draftWorkspaceId = generatedId)
                    } else {
                        current
                    }
                }
                _uiState.value.draftWorkspaceId ?: generatedId
            }
        }
    }

    private fun ChatMessage.summaryText(): String {
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

    private fun List<ChatMessage>.resolveConversationTrimIndex(
        targetIndex: Int,
    ): Int {
        val targetMessage = getOrNull(targetIndex) ?: return targetIndex
        val responseGroupId = targetMessage.responseGroupId
        if (
            targetMessage.author != MessageAuthor.Agent ||
            responseGroupId.isNullOrBlank()
        ) {
            return targetIndex
        }
        if (responseGroupId.isNullOrBlank()) {
            return resolveLegacyAssistantGroupStartIndex(targetIndex)
        }
        val groupStartIndex = indexOfFirst { message ->
            message.author == MessageAuthor.Agent && message.responseGroupId == responseGroupId
        }
        return if (groupStartIndex >= 0) groupStartIndex else targetIndex
    }

    private fun List<ChatMessage>.resolveLegacyAssistantGroupStartIndex(
        targetIndex: Int,
    ): Int {
        val targetMessage = getOrNull(targetIndex) ?: return targetIndex
        if (targetMessage.author != MessageAuthor.Agent) return targetIndex
        var groupStartIndex = targetIndex
        var expectedCreatedAtMillis = targetMessage.createdAtMillis
        while (groupStartIndex > 0) {
            val previous = this[groupStartIndex - 1]
            if (
                previous.author != MessageAuthor.Agent ||
                !previous.responseGroupId.isNullOrBlank() ||
                previous.createdAtMillis != expectedCreatedAtMillis - 1
            ) {
                break
            }
            groupStartIndex -= 1
            expectedCreatedAtMillis = previous.createdAtMillis
        }
        return groupStartIndex
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024f * 1024f))
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024f)
        else -> "$bytes B"
    }

    private fun parseKeyValueLines(rawValue: String): List<kira.ditto.data.McpKeyValue> =
        rawValue.lineSequence()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                val separatorIndex = trimmed.indexOf('=')
                if (separatorIndex <= 0) return@mapNotNull null
                kira.ditto.data.McpKeyValue(
                    key = trimmed.substring(0, separatorIndex).trim(),
                    value = trimmed.substring(separatorIndex + 1).trim(),
                )
            }
            .toList()

    private fun parseNonBlankLines(rawValue: String): List<String> =
        rawValue.lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toList()

    private fun formatMcpTestOutput(
        operation: McpServerTestOperation,
        outputJson: String,
    ): String {
        val json = runCatching { JSONObject(outputJson) }.getOrNull()
            ?: return outputJson.take(4_000)
        val serverInfo = json.optString("server_info").ifBlank { json.optString("server_name") }
        return when (operation) {
            McpServerTestOperation.ListTools -> formatMcpNamedItems(
                title = "Tools",
                serverInfo = serverInfo,
                items = json.optJSONArray("tools"),
                nameKey = "name",
            )

            McpServerTestOperation.ListResources -> formatMcpNamedItems(
                title = "Resources",
                serverInfo = serverInfo,
                items = json.optJSONArray("resources"),
                nameKey = "uri",
            )

            McpServerTestOperation.ListPrompts -> formatMcpNamedItems(
                title = "Prompts",
                serverInfo = serverInfo,
                items = json.optJSONArray("prompts"),
                nameKey = "name",
            )
        }
    }

    private fun formatMcpNamedItems(
        title: String,
        serverInfo: String,
        items: JSONArray?,
        nameKey: String,
    ): String {
        val count = items?.length() ?: 0
        return buildString {
            append(title)
            append(": ")
            append(count)
            if (serverInfo.isNotBlank()) {
                append(" on ")
                append(serverInfo)
            }
            if (count > 0 && items != null) {
                appendLine()
                val visibleCount = minOf(count, 8)
                for (index in 0 until visibleCount) {
                    val item = items.optJSONObject(index) ?: continue
                    val name = item.optString(nameKey)
                        .ifBlank { item.optString("name") }
                    val description = item.optString("description")
                    append("- ")
                    append(name)
                    if (description.isNotBlank()) {
                        append(": ")
                        append(description.take(160))
                    }
                    appendLine()
                }
                if (count > visibleCount) {
                    append("... and ")
                    append(count - visibleCount)
                    append(" more")
                }
            }
        }.trim()
    }

    private fun performSkillInstall(
        onComplete: (Boolean) -> Unit = {},
        installBlock: suspend () -> Result<InstalledSkill>,
    ) {
        viewModelScope.launch {
            val result = installBlock()
            if (result.isSuccess) {
                piKernelBridge.reloadAllExtensions(runtime.piExtensionStateRepository.loadOptions())
            }
            result
                .onSuccess { installedSkill ->
                    emitTransientMessage(uiString(R.string.message_installed_skill, installedSkill.name))
                    captureAnalyticsEvent(
                        event = "skill installed",
                        properties = mapOf(
                            "skill_id" to installedSkill.id,
                            "skill_name" to installedSkill.name,
                        ),
                    )
                }
                .onFailure { throwable ->
                    emitTransientMessage(
                        uiString(R.string.message_install_skill_failed, throwable.userFacingMessage())
                    )
                }
            onComplete(result.isSuccess)
        }
    }

    private fun maybeCheckForUpdates(settings: AppSettings) {
        if (!settings.privacyPolicyAccepted) return
        val lastCheck = settings.lastUpdateCheckAtMillis
        if (lastCheck > 0L && System.currentTimeMillis() - lastCheck < AppUpdateCheckIntervalMillis) {
            return
        }
        checkForUpdates(manual = false, forceAvailable = false)
    }

    private fun checkForUpdates(
        manual: Boolean,
        forceAvailable: Boolean,
    ) {
        if (_uiState.value.appUpdate.isChecking) return

        _uiState.update { current ->
            current.copy(
                appUpdate = current.appUpdate.copy(
                    isChecking = true,
                    showAvailableDialog = if (manual) false else current.appUpdate.showAvailableDialog,
                )
            )
        }
        viewModelScope.launch {
            val checkedAtMillis = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (BuildConfig.UPDATE_CHANNEL == UpdateChannelNightly) {
                        appUpdateManager.fetchLatestNightly()
                    } else {
                        appUpdateManager.fetchLatestRelease()
                    }
                }
            }
            settingsRepository.updateLastUpdateCheckAtMillis(checkedAtMillis)

            result
                .onSuccess { release ->
                    val hasUpdate = forceAvailable || if (BuildConfig.UPDATE_CHANNEL == UpdateChannelNightly) {
                        isNightlyUpdateNewer(
                            remoteVersion = release.versionName,
                            currentVersion = BuildConfig.VERSION_NAME,
                        )
                    } else {
                        isVersionNewer(
                            remoteVersion = release.versionName,
                            currentVersion = BuildConfig.VERSION_NAME,
                        )
                    }
                    _uiState.update { current ->
                        current.copy(
                            appUpdate = current.appUpdate.copy(
                                isChecking = false,
                                availableRelease = if (hasUpdate) release else current.appUpdate.availableRelease,
                                showAvailableDialog = hasUpdate,
                            )
                        )
                    }
                    if (!hasUpdate && manual) {
                        emitTransientMessage(uiString(R.string.message_aether_up_to_date))
                    }
                }
                .onFailure { throwable ->
                    _uiState.update { current ->
                        current.copy(
                            appUpdate = current.appUpdate.copy(isChecking = false)
                        )
                    }
                    if (manual) {
                        emitTransientMessage(uiString(R.string.message_update_check_failed, throwable.userFacingMessage()))
                    }
                }
        }
    }

    private fun writeTextToUri(
        uri: Uri,
        text: String,
    ): Boolean = runCatching {
        val resolver = getApplication<Application>().contentResolver
        // Open with "rwt" so the destination is truncated before writing. The default "w"
        // mode only truncates when the document provider declares truncation support, and
        // providers that ignore mode entirely (or stream via FUSE) can otherwise leave the
        // exported file at 0 bytes.
        resolver.openOutputStream(uri, "rwt")?.use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.flush()
            (output as? FileOutputStream)?.fd?.sync()
        } ?: return false
        true
    }.getOrDefault(false)

    private fun readTextFromUri(uri: Uri): String =
        getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        } ?: error("Unable to read the selected file.")

    private fun buildDiagnosticLogText(snapshot: AetherUiState): String = buildString {
        val logcat = readLogcatDump()
        val diagnosticEvents = diagnosticLogger.readEventsText()
        val lastCrash = diagnosticLogger.readLastCrashText()
        appendLine("Aether diagnostic log")
        appendLine("generatedAtMillis=${System.currentTimeMillis()}")
        appendLine("versionName=${BuildConfig.VERSION_NAME}")
        appendLine("versionCode=${BuildConfig.VERSION_CODE}")
        appendLine("debug=${BuildConfig.DEBUG}")
        appendLine("screen=${snapshot.currentScreen}")
        appendLine("currentSessionId=${snapshot.currentSessionId}")
        appendLine("sessionCount=${snapshot.sessions.size}")
        appendLine("runningSessionCount=${snapshot.sessionExecutionStates.values.count { it.isRunning }}")
        appendLine("piProviderId=${snapshot.settings.piProviderId}")
        appendLine("providerConfigCount=${snapshot.providerConfigs.size}")
        appendLine("skillCount=${snapshot.installedSkills.size}")
        appendLine("mcpServerCount=${snapshot.mcpServers.size}")
        appendLine("termuxReady=${snapshot.termuxSetupState.isReady}")
        appendLine("rootReady=${snapshot.rootSetupState.isReady}")
        appendLine("agentModeAuthorized=${snapshot.agentModeAuthorizationState.isReady}")
        appendLine()
        appendLine("settingsSummary:")
        appendLine(buildSettingsDiagnosticSummary(snapshot).toString(2))
        appendLine()
        appendLine("providerConfigsSummary:")
        appendLine(buildProviderConfigsDiagnosticSummary(snapshot).toString(2))
        appendLine()
        appendLine("mcpServersSummary:")
        appendLine(buildMcpServersDiagnosticSummary(snapshot).toString(2))
        appendLine()
        appendLine("sessionsSummary:")
        appendLine(buildSessionsDiagnosticSummary(snapshot).toString(2))
        appendLine()
        appendLine("lastCrash:")
        appendLine(lastCrash.ifBlank { "No crash breadcrumb recorded." })
        appendLine()
        appendLine("diagnosticEventsJsonl:")
        appendLine(diagnosticEvents.ifBlank { "No diagnostic events recorded." })
        appendLine()
        appendLine("logcatReadStatus:")
        appendLine(logcat.toJson().toString(2))
        appendLine()
        appendLine("logcat:")
        append(logcat.output.ifBlank { logcat.message.ifBlank { "No logcat output." } })
    }

    private fun buildSettingsDiagnosticSummary(snapshot: AetherUiState): JSONObject =
        JSONObject().apply {
            put("piProviderId", snapshot.settings.piProviderId)
            put("modelId", snapshot.settings.modelId)
            put("baseUrl", DiagnosticRedactor.sanitizedBaseUrl(snapshot.settings.baseUrl))
            put("defaultChatModelKey", snapshot.settings.defaultChatModelKey)
            put("defaultTitleModelKey", snapshot.settings.defaultTitleModelKey)
            put("defaultNamingModelKey", snapshot.settings.defaultNamingModelKey)
            put("llmInactivityReconnectTimeoutSeconds", snapshot.settings.llmInactivityReconnectTimeoutSeconds)
            put("keepTasksRunningInBackground", snapshot.settings.keepTasksRunningInBackground)
            put("notifyOnTaskCompletion", snapshot.settings.notifyOnTaskCompletion)
            put("termuxSetupCompleted", snapshot.settings.termuxSetupCompleted)
            put("termuxSetupNoticeDismissed", snapshot.settings.termuxSetupNoticeDismissed)
            put("privacyPolicyAccepted", snapshot.settings.privacyPolicyAccepted)
            put("termux", JSONObject().apply {
                put("issue", snapshot.termuxSetupState.issue.name)
                put("isReady", snapshot.termuxSetupState.isReady)
                put("detail", snapshot.termuxSetupState.detail)
                put("previouslyConfigured", snapshot.termuxSetupState.previouslyConfigured)
            })
            put("root", JSONObject().apply {
                put("issue", snapshot.rootSetupState.issue.name)
                put("isReady", snapshot.rootSetupState.isReady)
                put("detail", snapshot.rootSetupState.detail)
                put("rootAvailable", snapshot.rootSetupState.rootAvailable)
                put("lastUpdatedMillis", snapshot.rootSetupState.lastUpdatedMillis)
            })
            put("agentMode", JSONObject().apply {
                put("authorizationEnabled", snapshot.settings.agentModeAuthorizationEnabled)
                put("authorizationMethod", snapshot.settings.agentModeAuthorizationMethod.storageValue)
                put("authorizationIssue", snapshot.agentModeAuthorizationState.issue.name)
                put("authorizationReady", snapshot.agentModeAuthorizationState.isReady)
                put("authorizationDetail", snapshot.agentModeAuthorizationState.detail)
                put("displayActive", snapshot.agentModeDisplayState.isActive)
                put("displayId", snapshot.agentModeDisplayState.displayId ?: JSONObject.NULL)
                put("displayStatus", snapshot.agentModeDisplayState.status)
                put("livePreviewActive", snapshot.agentModeDisplayState.isLivePreviewActive)
                put("lastUpdatedMillis", snapshot.agentModeDisplayState.lastUpdatedMillis)
            })
        }

    private fun buildProviderConfigsDiagnosticSummary(snapshot: AetherUiState): JSONArray =
        JSONArray().apply {
            snapshot.providerConfigs.forEach { config ->
                put(
                    JSONObject().apply {
                        put("id", config.id)
                        put("name", config.name)
                        put("piProviderId", config.piProviderId)
                        put("baseUrl", DiagnosticRedactor.sanitizedBaseUrl(config.baseUrl))
                        put("modelId", config.modelId)
                        put("cachedModelCount", config.cachedModels.size)
                        put("enabledModelCount", config.enabledModelIds.size)
                        put("isEnabled", config.isEnabled)
                    }
                )
            }
        }

    private fun buildMcpServersDiagnosticSummary(snapshot: AetherUiState): JSONArray =
        JSONArray().apply {
            snapshot.mcpServers.forEach { server ->
                put(
                    JSONObject().apply {
                        put("id", server.id)
                        put("displayName", server.displayName)
                        put("isEnabled", server.isEnabled)
                        put("transportType", server.transport.transportType.storageValue)
                        put("connectTimeoutMillis", server.connectTimeoutMillis)
                        put("requestTimeoutMillis", server.requestTimeoutMillis)
                        when (val transport = server.transport) {
                            is kira.ditto.data.McpTransportConfig.StdIo -> {
                                put("commandSummary", transport.command.lineSequence().firstOrNull().orEmpty().take(160))
                                put("argumentCount", transport.arguments.size)
                                put("workingDirectory", transport.workingDirectory)
                                put("environmentKeyCount", transport.environment.size)
                            }

                            is kira.ditto.data.McpTransportConfig.StreamableHttp -> {
                                put("url", DiagnosticRedactor.sanitizedBaseUrl(transport.url))
                                put("headerKeyCount", transport.headers.size)
                            }

                            is kira.ditto.data.McpTransportConfig.UpaManifest -> {
                                put("pluginId", transport.pluginId)
                            }
                        }
                    }
                )
            }
        }

    private fun buildSessionsDiagnosticSummary(snapshot: AetherUiState): JSONObject =
        JSONObject().apply {
            put("currentSessionId", snapshot.currentSessionId)
            put("sessionCount", snapshot.sessions.size)
            put(
                "runningSessions",
                JSONArray().apply {
                    snapshot.sessionExecutionStates.values
                        .filter { it.isRunning }
                        .forEach { state ->
                            put(
                                JSONObject().apply {
                                    put("sessionId", state.sessionId)
                                    put("pendingToolCount", state.pendingToolInvocations.size)
                                    put("pendingInputCount", state.pendingInputs.size)
                                    put("activeTurnStartedAtMillis", state.activeTurnStartedAtMillis ?: JSONObject.NULL)
                                    put("pendingStatusText", state.pendingStatusText)
                                    put("pendingStatusDetail", state.pendingStatusDetail)
                                }
                            )
                        }
                },
            )
            put(
                "recentSessions",
                JSONArray().apply {
                    snapshot.sessions
                        .sortedByDescending { session -> session.lastMessageAtMillis ?: 0L }
                        .take(12)
                        .forEach { session ->
                            put(
                                JSONObject().apply {
                                    put("id", session.id)
                                    put("title", session.title)
                                    put("messageCount", session.messageCount)
                                    put("lastMessageAtMillis", session.lastMessageAtMillis ?: 0L)
                                    put("selectedModelKey", session.selectedModelKey)
                                    put("selectedSkillCount", session.selectedSkillIds.size)
                                    put("activeMcpServerCount", session.activeMcpServerIds.size)
                                    put("agentModeEnabled", session.agentModeEnabled)
                                }
                            )
                        }
                },
            )
        }

    private fun readLogcatDump(): LogcatDump {
        val pid = android.os.Process.myPid().toString()
        val commands = listOf(
            listOf("logcat", "-d", "-v", "threadtime", "-t", "4000", "--pid", pid),
            listOf("logcat", "-d", "-v", "threadtime", "-t", "8000"),
        )

        commands.forEach { command ->
            val dump = runCatching { runLogcatCommand(command) }.getOrElse { throwable ->
                LogcatDump(
                    command = command,
                    success = false,
                    exitCode = null,
                    output = "",
                    message = throwable.message ?: "Unable to run logcat command.",
                )
            }
            if (dump.success && dump.output.isNotBlank()) {
                return dump
            }
        }

        return LogcatDump(
            command = emptyList(),
            success = false,
            exitCode = null,
            output = "",
            message = "Unable to read logcat output.",
        )
    }

    private fun runLogcatCommand(command: List<String>): LogcatDump {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val executor = Executors.newSingleThreadExecutor()
        val outputFuture = executor.submit<String> {
            process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                reader.readText()
            }
        }
        return try {
            if (!process.waitFor(LogcatReadTimeoutSeconds, TimeUnit.SECONDS)) {
                process.destroy()
                LogcatDump(
                    command = command,
                    success = false,
                    exitCode = null,
                    output = runCatching { outputFuture.get(500, TimeUnit.MILLISECONDS) }.getOrDefault(""),
                    message = "Logcat command timed out.",
                )
            } else {
                val output = outputFuture.get(1, TimeUnit.SECONDS)
                val exitCode = process.exitValue()
                LogcatDump(
                    command = command,
                    success = exitCode == 0 && output.isNotBlank(),
                    exitCode = exitCode,
                    output = output,
                    message = when {
                        exitCode != 0 -> "Logcat command exited with code $exitCode."
                        output.isBlank() -> "Logcat command returned no output."
                        else -> ""
                    },
                )
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private suspend fun buildFullAppExportJson(
        snapshot: AetherUiState,
        sessions: List<ChatSession>,
    ): JSONObject {
        val piSessions = JSONArray()
        sessions.forEach { session ->
            runCatching { piKernelBridge.exportSessionJsonl(session.id) }
                .getOrNull()
                ?.let { exported ->
                    val path = exported.optString("exported_path")
                    val jsonl = path.takeIf(String::isNotBlank)
                        ?.let { filePath -> runCatching { java.io.File(filePath).readText(Charsets.UTF_8) }.getOrNull() }
                    piSessions.put(JSONObject().apply {
                        put("sessionId", session.id)
                        put("jsonlPath", path)
                        put("jsonl", jsonl ?: "")
                    })
                }
        }
        return JSONObject().apply {
            put("schemaVersion", 3)
            put("exportType", "app")
            put("exportedAtMillis", System.currentTimeMillis())
            put("settings", snapshot.settings.toJson())
            put("providerConfigs", JSONArray(serializeProviderConfigs(snapshot.providerConfigs)))
            put("sessions", JSONArray(serializeChatSessions(sessions.map { it.copy(activeSkills = emptyList()) })))
            put("currentSessionId", snapshot.currentSessionId)
            put("skillBundles", skillManager.exportSkillBundles(snapshot.installedSkills))
            put("mcpServers", JSONArray(serializeMcpServerConfigs(snapshot.mcpServers)))
            put("piSessions", piSessions)
            put("extensionArchive", piExtensionManager.exportArchive())
        }
    }

    private fun parseFullAppImport(
        json: JSONObject,
        installedSkills: List<InstalledSkill>,
    ): ImportedAppData {
        val mcpServers = parseMcpServerConfigs(json.optJSONArray("mcpServers")?.toString().orEmpty())
        val sessions = sanitizeImportedSessions(
            sessions = parseChatSessions(json.optJSONArray("sessions")?.toString().orEmpty()),
            installedSkillIds = installedSkills.map { it.id }.toSet(),
            mcpServerIds = mcpServers.map(McpServerConfig::id).toSet(),
        )
        val piSessions = buildMap {
            val entries = json.optJSONArray("piSessions") ?: JSONArray()
            for (index in 0 until entries.length()) {
                val item = entries.optJSONObject(index) ?: continue
                val id = item.optString("sessionId").trim()
                val jsonl = item.optString("jsonl")
                if (id.isNotBlank() && jsonl.isNotBlank()) put(id, jsonl)
            }
        }
        return ImportedAppData(
            settings = parseImportedSettings(json.optJSONObject("settings")),
            providerConfigs = parseProviderConfigs(json.optJSONArray("providerConfigs")?.toString().orEmpty()),
            sessions = sessions,
            currentSessionId = json.optString("currentSessionId")
                .takeIf { id -> id == DraftSessionId || sessions.any { it.id == id } }
                ?: DraftSessionId,
            installedSkills = installedSkills,
            mcpServers = mcpServers,
            piSessions = piSessions,
        )
    }

    private fun sanitizeImportedSessions(
        sessions: List<ChatSession>,
        installedSkillIds: Set<String>,
        mcpServerIds: Set<String>,
    ): List<ChatSession> =
        sessions.map { session ->
            session.copy(
                selectedSkillIds = session.selectedSkillIds.filter(installedSkillIds::contains),
                activeSkills = emptyList(),
                activeMcpServerIds = session.activeMcpServerIds.filter(mcpServerIds::contains),
            )
        }

    private fun AppSettings.toJson(): JSONObject = JSONObject().apply {
        put("piProviderId", piProviderId)
        put("providerConfigId", providerConfigId)
        put("providerAuthMethod", providerAuthMethod.storageValue)
        put("apiKey", apiKey)
        put("oauthCredentialJson", oauthCredentialJson)
        put(
            "providerEnvironmentVariables",
            JSONArray().apply {
                providerEnvironmentVariables.forEach { variable ->
                    put(
                        JSONObject()
                            .put("name", variable.name)
                            .put("value", variable.value)
                    )
                }
            },
        )
        put("baseUrl", baseUrl)
        put("modelId", modelId)
        put("userAgent", normalizeLlmUserAgent(userAgent))
        put("customHeaders", customHeaders.toJsonArray())
        put("reasoningEffort", reasoningEffort)
        put("systemPrompt", systemPrompt)
        put("llmInactivityReconnectTimeoutSeconds", llmInactivityReconnectTimeoutSeconds)
        put("keepTasksRunningInBackground", keepTasksRunningInBackground)
        put("notifyOnTaskCompletion", notifyOnTaskCompletion)
        put("termuxSetupCompleted", termuxSetupCompleted)
        put("termuxSetupNoticeDismissed", termuxSetupNoticeDismissed)
        put(
            "termuxEnvironmentVariables",
            JSONArray().apply {
                termuxEnvironmentVariables.forEach { variable ->
                    put(
                        JSONObject().apply {
                            put("name", variable.name)
                            put("value", variable.value)
                        }
                    )
                }
            },
        )
        put("enabledRuntimeIds", JSONArray().apply { enabledRuntimeIds.forEach { put(it.storageValue) } })
        put("defaultRuntimeId", defaultRuntimeId?.storageValue ?: JSONObject.NULL)
        put("alpineSetupCompleted", alpineSetupCompleted)
        put(
            "alpinePackageProfiles",
            JSONArray().apply {
                alpinePackageProfiles.values.forEach { profile ->
                    put(
                        JSONObject().apply {
                            put("profileId", profile.profileId)
                            put("installed", profile.installed)
                            put("installedAtMillis", profile.installedAtMillis)
                            put("lastError", profile.lastError)
                        }
                    )
                }
            },
        )
        put(
            "alpineEnvironmentVariables",
            JSONArray().apply {
                alpineEnvironmentVariables.forEach { variable ->
                    put(JSONObject().put("name", variable.name).put("value", variable.value))
                }
            },
        )
        put("autoCleanOldCommandHistory", autoCleanOldCommandHistory)
        put("oldCommandHistoryRetentionHours", oldCommandHistoryRetentionHours)
        put("agentModeAuthorizationEnabled", agentModeAuthorizationEnabled)
        put("agentModeAuthorizationMethod", agentModeAuthorizationMethod.storageValue)
        put("language", language.storageValue)
        put("themeMode", themeMode.storageValue)
        put("defaultChatModelKey", defaultChatModelKey)
        put("defaultTitleModelKey", defaultTitleModelKey)
        put("defaultNamingModelKey", defaultNamingModelKey)
        put("defaultCompactingModelKey", defaultCompactingModelKey)
        put("defaultVectorModelKey", defaultVectorModelKey)
        put("defaultImageModelKey", defaultImageModelKey)
        put("defaultAsrModelKey", defaultAsrModelKey)
        put("defaultTtsModelKey", defaultTtsModelKey)
        put("ttsEnabled", ttsEnabled)
        put("ttsVoiceId", ttsVoiceId)
        put("defaultSelectedSkillIds", JSONArray(defaultSelectedSkillIds))
        put("onboardingSeenVersion", onboardingSeenVersion)
        put("onboardingCompletedVersion", onboardingCompletedVersion)
        put("privacyPolicyAccepted", privacyPolicyAccepted)
        put("lastUpdateCheckAtMillis", lastUpdateCheckAtMillis)
    }

    private fun parseImportedSettings(json: JSONObject?): AppSettings {
        if (json == null) return AppSettings()
        val defaults = AppSettings()
        val importedBaseUrl = json.optString("baseUrl", defaults.baseUrl)
        val importedPiProviderId = json.optString("piProviderId").trim().ifBlank {
            kira.ditto.data.inferLegacyPiProviderId(
                json.optString("provider"),
                importedBaseUrl,
            )
        }
        return AppSettings(
            piProviderId = importedPiProviderId,
            providerConfigId = json.optString("providerConfigId"),
            providerAuthMethod = kira.ditto.data.ProviderAuthMethod.fromStorage(
                json.optString("providerAuthMethod"),
            ),
            apiKey = json.optString("apiKey", defaults.apiKey),
            oauthCredentialJson = json.optString(
                "oauthCredentialJson",
                defaults.oauthCredentialJson,
            ),
            providerEnvironmentVariables = kira.ditto.data
                .parseProviderEnvironmentVariables(
                    json.optJSONArray("providerEnvironmentVariables"),
                ),
            baseUrl = importedBaseUrl,
            modelId = json.optString("modelId", defaults.modelId),
            userAgent = normalizeLlmUserAgent(json.optString("userAgent", defaults.userAgent)),
            customHeaders = parseCustomHeaders(json.optJSONArray("customHeaders")),
            reasoningEffort = normalizeReasoningEffort(
                json.optString("reasoningEffort", defaults.reasoningEffort),
            ),
            systemPrompt = json.optString("systemPrompt", defaults.systemPrompt),
            llmInactivityReconnectTimeoutSeconds = normalizeLlmInactivityReconnectTimeoutSeconds(
                json.optInt(
                    "llmInactivityReconnectTimeoutSeconds",
                    defaults.llmInactivityReconnectTimeoutSeconds,
                )
            ),
            keepTasksRunningInBackground = json.optBoolean(
                "keepTasksRunningInBackground",
                defaults.keepTasksRunningInBackground,
            ),
            notifyOnTaskCompletion = json.optBoolean(
                "notifyOnTaskCompletion",
                defaults.notifyOnTaskCompletion,
            ),
            termuxSetupCompleted = json.optBoolean(
                "termuxSetupCompleted",
                defaults.termuxSetupCompleted,
            ),
            termuxSetupNoticeDismissed = json.optBoolean(
                "termuxSetupNoticeDismissed",
                defaults.termuxSetupNoticeDismissed,
            ),
            termuxEnvironmentVariables = parseImportedTermuxEnvironmentVariables(
                json.optJSONArray("termuxEnvironmentVariables")
            ),
            autoCleanOldCommandHistory = json.optBoolean(
                "autoCleanOldCommandHistory",
                defaults.autoCleanOldCommandHistory,
            ),
            oldCommandHistoryRetentionHours = normalizeOldCommandHistoryRetentionHours(
                json.optInt(
                    "oldCommandHistoryRetentionHours",
                    defaults.oldCommandHistoryRetentionHours,
                )
            ),
            enabledRuntimeIds = parseImportedRuntimeIds(json.optJSONArray("enabledRuntimeIds")),
            defaultRuntimeId = LocalRuntimeId.fromStorage(json.optString("defaultRuntimeId")),
            alpineSetupCompleted = json.optBoolean(
                "alpineSetupCompleted",
                defaults.alpineSetupCompleted,
            ),
            alpinePackageProfiles = parseImportedPackageProfileStates(
                json.optJSONArray("alpinePackageProfiles")
            ),
            alpineEnvironmentVariables = parseImportedAlpineEnvironmentVariables(
                json.optJSONArray("alpineEnvironmentVariables")
            ),
            agentModeAuthorizationEnabled = json.optBoolean(
                "agentModeAuthorizationEnabled",
                defaults.agentModeAuthorizationEnabled,
            ),
            agentModeAuthorizationMethod = AgentModeAuthorizationMethod.fromStorage(
                json.optString("agentModeAuthorizationMethod"),
                defaults.agentModeAuthorizationMethod,
            ),
            language = AppLanguage.fromStorage(
                json.optString("language"),
                defaults.language,
            ),
            themeMode = AppThemeMode.fromStorage(json.optString("themeMode")),
            defaultChatModelKey = json.optString("defaultChatModelKey", defaults.defaultChatModelKey),
            defaultTitleModelKey = json.optString("defaultTitleModelKey", defaults.defaultTitleModelKey),
            defaultNamingModelKey = json.optString("defaultNamingModelKey", defaults.defaultNamingModelKey),
            defaultCompactingModelKey = json.optString(
                "defaultCompactingModelKey",
                defaults.defaultCompactingModelKey,
            ),
            defaultVectorModelKey = json.optString(
                "defaultVectorModelKey",
                defaults.defaultVectorModelKey,
            ),
            defaultImageModelKey = json.optString(
                "defaultImageModelKey",
                defaults.defaultImageModelKey,
            ),
            defaultAsrModelKey = json.optString(
                "defaultAsrModelKey",
                defaults.defaultAsrModelKey,
            ),
            defaultTtsModelKey = json.optString(
                "defaultTtsModelKey",
                defaults.defaultTtsModelKey,
            ),
            ttsEnabled = json.optBoolean("ttsEnabled", defaults.ttsEnabled),
            ttsVoiceId = json.optString("ttsVoiceId", defaults.ttsVoiceId),
            defaultSelectedSkillIds = json.optJSONArray("defaultSelectedSkillIds").toStringList(),
            onboardingSeenVersion = json.optInt("onboardingSeenVersion", defaults.onboardingSeenVersion),
            onboardingCompletedVersion = json.optInt(
                "onboardingCompletedVersion",
                defaults.onboardingCompletedVersion,
            ),
            privacyPolicyAccepted = json.optBoolean(
                "privacyPolicyAccepted",
                defaults.privacyPolicyAccepted,
            ),
            lastUpdateCheckAtMillis = json.optLong(
                "lastUpdateCheckAtMillis",
                defaults.lastUpdateCheckAtMillis,
            ),
        )
    }

    private fun parseImportedStringArray(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val value = array.optString(index).trim()
                if (value.isNotEmpty()) {
                    add(value)
                }
            }
        }.distinct()
    }

    private fun parseImportedTermuxEnvironmentVariables(
        array: JSONArray?,
    ): List<TermuxEnvironmentVariable> {
        if (array == null) return emptyList()
        return normalizeTermuxEnvironmentVariables(
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        TermuxEnvironmentVariable(
                            name = item.optString("name"),
                            value = item.optString("value"),
                        )
                    )
                }
            }
        )
    }

    private fun parseImportedAlpineEnvironmentVariables(
        array: JSONArray?,
    ): List<AlpineEnvironmentVariable> {
        if (array == null) return emptyList()
        return normalizeAlpineEnvironmentVariables(
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        AlpineEnvironmentVariable(
                            name = item.optString("name"),
                            value = item.optString("value"),
                        )
                    )
                }
            }
        )
    }

    private fun parseImportedRuntimeIds(array: JSONArray?): Set<LocalRuntimeId> {
        if (array == null) return emptySet()
        return buildSet {
            for (index in 0 until array.length()) {
                LocalRuntimeId.fromStorage(array.optString(index))?.let(::add)
            }
        }
    }

    private fun parseImportedPackageProfileStates(
        array: JSONArray?,
    ): Map<String, PackageProfileState> {
        if (array == null) return emptyMap()
        return buildMap {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val profileId = item.optString("profileId").trim()
                if (profileId.isBlank()) continue
                put(
                    profileId,
                    PackageProfileState(
                        profileId = profileId,
                        installed = item.optBoolean("installed", false),
                        installedAtMillis = item.optLong("installedAtMillis", 0L),
                        lastError = item.optString("lastError"),
                    )
                )
            }
        }
    }

    private fun emitTransientMessage(message: UiText) {
        _transientMessages.tryEmit(message)
    }

    private fun uiString(resId: Int, vararg formatArgs: Any): UiText =
        UiText.Resource(resId, formatArgs.toList())

    private fun Throwable.userFacingMessage(): String =
        message?.trim().takeUnless { it.isNullOrBlank() } ?: javaClass.simpleName

    private data class SessionMetadata(
        val title: String,
        val preview: String,
    )

    private data class AttachmentMetadata(
        val displayName: String,
        val mimeType: String,
        val sizeBytes: Long?,
        val kind: AttachmentKind,
    )

    private data class LogcatDump(
        val command: List<String>,
        val success: Boolean,
        val exitCode: Int?,
        val output: String,
        val message: String,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("command", command.joinToString(" "))
            put("success", success)
            put("exitCode", exitCode ?: JSONObject.NULL)
            put("lineCount", output.lineSequence().count())
            put("message", message)
        }
    }

    private data class ImportedAppData(
        val settings: AppSettings,
        val providerConfigs: List<LlmProviderConfig>,
        val sessions: List<ChatSession>,
        val currentSessionId: String,
        val installedSkills: List<InstalledSkill>,
        val mcpServers: List<McpServerConfig>,
        val piSessions: Map<String, String> = emptyMap(),
    )

    private fun presentExecutionStates(
        executionStates: Map<String, SessionExecutionState>,
        current: AetherUiState,
    ): AetherUiState {
        val includeStreaming = chatSurfaceVisible
        val currentExecution = executionStates[current.currentSessionId]
        val presented = executionStates.mapValues { (id, state) ->
            if (includeStreaming && id == current.currentSessionId) {
                state
            } else {
                state.forNonChatUi()
            }
        }
        val fingerprint = buildString {
            append(current.currentScreen.name)
            append('|')
            append(current.currentSessionId)
            append('|')
            presented.forEach { (id, state) ->
                append(id)
                append('=')
                append(state.uiFingerprint(includeStreaming && id == current.currentSessionId))
                append(';')
            }
        }
        if (fingerprint == lastExecutionUiFingerprint) return current
        lastExecutionUiFingerprint = fingerprint
        return current.copy(
            sessionExecutionStates = presented,
            isSending = currentExecution?.isRunning == true,
            pendingResponseSessionId = currentExecution?.sessionId,
            pendingToolInvocations = currentExecution?.pendingToolInvocations.orEmpty(),
            pendingResponseBlocks = if (includeStreaming) {
                currentExecution?.pendingResponseBlocks.orEmpty()
            } else {
                emptyList()
            },
            pendingAssistantText = if (includeStreaming) {
                currentExecution?.pendingAssistantText.orEmpty()
            } else {
                ""
            },
            pendingStatusText = currentExecution?.pendingStatusText.orEmpty(),
            pendingStatusDetail = if (includeStreaming) {
                currentExecution?.pendingStatusDetail.orEmpty()
            } else {
                ""
            },
        )
    }
}

internal fun AetherUiState.withFinalizedPausedSession(
    finalizedSession: ChatSession,
    executionStates: Map<String, SessionExecutionState>,
): AetherUiState {
    val currentExecution = executionStates[currentSessionId]
    val updatedSessions = if (sessions.any { it.id == finalizedSession.id }) {
        sessions.map { if (it.id == finalizedSession.id) finalizedSession else it }
    } else {
        listOf(finalizedSession) + sessions
    }
    return copy(
        sessions = updatedSessions,
        sessionExecutionStates = executionStates,
        isSending = currentExecution?.isRunning == true,
        pendingResponseSessionId = currentExecution?.sessionId,
        pendingToolInvocations = currentExecution?.pendingToolInvocations.orEmpty(),
        pendingResponseBlocks = currentExecution?.pendingResponseBlocks.orEmpty(),
        pendingAssistantText = currentExecution?.pendingAssistantText.orEmpty(),
        pendingStatusText = currentExecution?.pendingStatusText.orEmpty(),
        pendingStatusDetail = currentExecution?.pendingStatusDetail.orEmpty(),
    )
}

private fun AppSettings.withRuntimeEnabled(
    runtimeId: LocalRuntimeId,
    makeDefault: Boolean = defaultRuntimeId == null,
): AppSettings {
    val enabled = enabledRuntimeIds + runtimeId
    return copy(
        termuxSetupCompleted = termuxSetupCompleted || runtimeId == LocalRuntimeId.Termux,
        alpineSetupCompleted = alpineSetupCompleted || runtimeId == LocalRuntimeId.Alpine,
        enabledRuntimeIds = enabled,
        defaultRuntimeId = if (makeDefault) runtimeId else defaultRuntimeId,
    )
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
        }
    }.distinct()
}

private data class KnowledgeBriefing(
    val promptText: String = "",
    val citations: List<KnowledgeCitation> = emptyList(),
)
