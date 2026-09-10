package kira.ditto

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import kira.ditto.data.AgentExtensionsRepository
import kira.ditto.data.AgentModeController
import kira.ditto.data.AgentModeLearningRuntime
import kira.ditto.data.GuiSceneStore
import kira.ditto.data.GuiSignalStore
import kira.ditto.data.GuiTrajectoryStore
import kira.ditto.data.PhoneAppCatalogStore
import kira.ditto.data.PhoneAppFlowExecutor
import kira.ditto.data.PhoneAppFlowMcp
import kira.ditto.data.PhoneSettlementStore
import kira.ditto.data.PhoneSettlementRuntime
import kira.ditto.data.AgentModeSopStore
import kira.ditto.data.AgentSkillManager
import kira.ditto.data.HostHealthSampler
import kira.ditto.data.UpaPluginInstaller
import kira.ditto.data.UpaPluginLibrary
import kira.ditto.data.AetherAppExtensionManager
import kira.ditto.data.AetherModKernel
import kira.ditto.data.AetherDiagnosticLogger
import kira.ditto.data.AetherToolExecutor
import kira.ditto.data.ChatRepository
import kira.ditto.data.UsageRecorder
import kira.ditto.data.PiExtensionManager
import kira.ditto.data.PiExtensionStateRepository
import kira.ditto.data.PersonaRepository
import kira.ditto.data.DigiCrewMesh
import kira.ditto.data.RootSetupController
import kira.ditto.data.RuntimeWorkspaceFileBridge
import kira.ditto.data.ChatStateStore
import kira.ditto.data.ScheduledTask
import kira.ditto.data.ScheduledTaskManager
import kira.ditto.data.ScheduledTaskRepository
import kira.ditto.data.ScheduledTaskScheduler
import kira.ditto.data.defaultSessionId
import kira.ditto.data.isGuiWatchTask
import kira.ditto.data.KimiSessionCronStore
import kira.ditto.data.SessionExecutionManager
import kira.ditto.data.SettingsRepository
import kira.ditto.data.WorkspaceFileBridge
import kira.ditto.data.pi.PiCompletionClient
import kira.ditto.data.pi.PiAgentRunner
import kira.ditto.data.pi.PiKernelBridge
import kira.ditto.data.pi.toPiModelConfig
import kira.ditto.mod.AetherNativeModManager
import kira.ditto.runtime.AlpineRuntime
import kira.ditto.runtime.RuntimeRouter
import kira.ditto.termux.TermuxRuntimeOperations
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class AetherApplication : Application() {
    val runtime: AetherAppRuntime by lazy {
        AetherAppRuntime(this)
    }
    @Volatile
    private var isPostHogInitialized = false

    override fun onCreate() {
        super.onCreate()
        runtime.initialize()
    }

    override fun onTerminate() {
        runtime.shutdownNativeMods()
        super.onTerminate()
    }

    fun initializePostHog() {
        if (isPostHogInitialized || BuildConfig.POSTHOG_API_KEY.isBlank()) return
        synchronized(this) {
            if (isPostHogInitialized || BuildConfig.POSTHOG_API_KEY.isBlank()) return
            val config = PostHogAndroidConfig(
                apiKey = BuildConfig.POSTHOG_API_KEY,
                host = BuildConfig.POSTHOG_HOST,
            ).apply {
                captureApplicationLifecycleEvents = true
                captureDeepLinks = true
                captureScreenViews = true
                debug = BuildConfig.DEBUG
                releaseIdentifier = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
                errorTrackingConfig.autoCapture = true
                errorTrackingConfig.inAppIncludes.addAll(
                    listOf(
                        "kira.ditto",
                        "com.kira.ditto",
                    ),
                )
            }
            PostHogAndroid.setup(this, config)
            isPostHogInitialized = true
        }
    }
}

class AetherAppRuntime(
    private val application: AetherApplication,
) {
    val diagnosticLogger = AetherDiagnosticLogger(application)

    /**
     * How many times one memo may fail to upload before it is given up on.
     *
     * Without a ceiling a memo that the server will never accept - malformed, over a quota, from a
     * revoked agent - would be retried at the end of every turn, forever. Counted in memory rather
     * than in the row because the count is about this process's experience, not about the memo.
     */
    private val browserUploadFailures = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * Send the memos the gate has already approved.
     *
     * Everything that decides *whether* to send happened when the memo was recorded: salience, the
     * host-derived exclusion, the recurrence check, and the content-hash guard against sending the
     * same thing twice. This is only a queue drain, which is the only safe shape when the send
     * cannot be undone - a drain has no judgement to get wrong.
     *
     * Failures leave the row `Pending` so the next turn retries, up to [MaxBrowserUploadAttempts].
     * Failing closed matters more here than usual: a row wrongly marked uploaded is a memory
     * silently lost, while a retry costs one request.
     */
    private suspend fun drainBrowserMemoryUploads() {
        val pending = runCatching { chatRepository.getBrowserTasksAwaitingUpload() }
            .getOrDefault(emptyList())
        if (pending.isEmpty()) return
        pending.forEach { task ->
            if ((browserUploadFailures[task.id] ?: 0) >= MaxBrowserUploadAttempts) {
                runCatching {
                    chatRepository.markBrowserTaskUploaded(task.id, uploaded = false, giveUp = true)
                }
                return@forEach
            }
            val uploaded = runCatching {
                kira.ditto.browser.BrowserMemoryUploader.upload(alpineRuntime, task)
            }.getOrDefault(false)
            if (uploaded) {
                browserUploadFailures.remove(task.id)
            } else {
                browserUploadFailures[task.id] = (browserUploadFailures[task.id] ?: 0) + 1
            }
            runCatching { chatRepository.markBrowserTaskUploaded(task.id, uploaded) }
        }
    }

    private val appScope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.Default +
            CoroutineExceptionHandler { _, throwable ->
                diagnosticLogger.exception(
                    category = "coroutine",
                    event = "uncaught_exception",
                    throwable = throwable,
                )
            }
    )

    // Everything below is constructed on first touch. Building the whole graph inside
    // Application.onCreate cost the main thread a Room builder, several SharedPreferences
    // loads, a handful of OkHttp clients and a pile of mkdirs before the first frame.
    val settingsRepository by lazy { SettingsRepository(application) }
    val piExtensionStateRepository by lazy { PiExtensionStateRepository(application) }
    val modKernel by lazy { AetherModKernel() }
    val chatRepository by lazy { ChatRepository(application) }
    val extensionsRepository by lazy { AgentExtensionsRepository(application) }
    val personaRepository by lazy { PersonaRepository(application) }
    val digiCrewMesh by lazy {
        DigiCrewMesh(
            context = application,
            scope = appScope,
            diagnostic = { event, detail ->
                diagnosticLogger.event(
                    category = "digicrew",
                    event = event,
                    details = mapOf("detail" to detail),
                )
            },
        )
    }
    val scheduledTaskRepository by lazy { ScheduledTaskRepository(application) }
    val alpineRuntime by lazy {
        AlpineRuntime(
            context = application,
            diagnosticLogger = diagnosticLogger,
        )
    }
    val kimiCronStore by lazy { KimiSessionCronStore(alpineRuntime) }
    val browserLoginVault by lazy { kira.ditto.browser.BrowserLoginVault(application) }
    val browserHistoryStore by lazy { kira.ditto.browser.BrowserHistoryStore(application) }
    val piKernelBridge by lazy {
        PiKernelBridge(
            alpineRuntime = alpineRuntime,
            diagnosticLogger = diagnosticLogger,
        )
    }
    val kimiInteractionController by lazy {
        kira.ditto.data.kimi.KimiInteractionController(
            aetherSessionIdFor = alpineRuntime::aetherSessionIdForKimiSession,
        )
    }
    private val nativeModManagerDelegate = lazy {
        AetherNativeModManager(
            context = application,
            application = application,
            alpineRuntime = alpineRuntime,
            piKernelBridge = piKernelBridge,
            kernel = modKernel,
            piExtensionStateRepository = piExtensionStateRepository,
            diagnosticLogger = diagnosticLogger,
        )
    }
    val nativeModManager by nativeModManagerDelegate
    val piCompletionClient by lazy {
        PiCompletionClient(
            bridge = piKernelBridge,
            settingsRepository = settingsRepository,
        )
    }
    val runtimeRouter by lazy {
        RuntimeRouter(
            alpineRuntime = alpineRuntime,
        )
    }
    val rootSetupController by lazy {
        RootSetupController(
            context = application,
            diagnosticLogger = diagnosticLogger,
        )
    }
    val workspaceFileBridge by lazy {
        WorkspaceFileBridge(
            context = application,
            alpineRuntime = alpineRuntime,
        )
    }
    val runtimeWorkspaceFileBridge by lazy {
        RuntimeWorkspaceFileBridge(
            context = application,
            runtimeRouter = runtimeRouter,
            alpineRuntime = alpineRuntime,
            termuxFileBridge = workspaceFileBridge,
        )
    }
    val agentModeController by lazy {
        AgentModeController(
            context = application,
            runtimeWorkspaceFileBridge = runtimeWorkspaceFileBridge,
            diagnosticLogger = diagnosticLogger,
            signalStore = guiSignalStore,
        )
    }
    val shizukuProvisioner by lazy {
        kira.ditto.agentmode.ShizukuProvisioner(
            context = application,
            settingsRepository = settingsRepository,
            agentModeController = agentModeController,
            diagnosticLogger = diagnosticLogger,
        ).also { provisioner ->
            agentModeController.prepareShizuku = { provisioner.ensureReady() }
        }
    }
    val agentModeSopStore by lazy {
        AgentModeSopStore(java.io.File(application.filesDir, "agent-mode-sops"))
    }
    val skillManager by lazy {
        AgentSkillManager(
            context = application,
            extensionsRepository = extensionsRepository,
        )
    }
    val phoneAppCatalogStore by lazy {
        PhoneAppCatalogStore(java.io.File(application.filesDir, "agent-mode-apps"))
    }
    val guiSceneStore by lazy {
        GuiSceneStore(java.io.File(application.filesDir, "agent-mode-scenes"))
    }
    val phoneSettlementStore by lazy {
        PhoneSettlementStore(java.io.File(application.filesDir, "agent-mode-settlements"))
    }
    val guiSignalStore by lazy {
        GuiSignalStore(java.io.File(application.filesDir, "agent-mode-trajectories/signals"))
    }
    val phoneAppFlowExecutor by lazy {
        PhoneAppFlowExecutor(
            catalogStore = phoneAppCatalogStore,
            sopStore = agentModeSopStore,
            agentModeController = agentModeController,
            sceneStore = guiSceneStore,
            signalStore = guiSignalStore,
        )
    }
    val phoneSettlementRuntime by lazy {
        PhoneSettlementRuntime(
            store = phoneSettlementStore,
            catalogStore = phoneAppCatalogStore,
            skillManager = skillManager,
            scope = appScope,
            sopStore = agentModeSopStore,
            replayExecutor = phoneAppFlowExecutor,
            replaySettings = { runBlocking { settingsRepository.settings.first() } },
            replayWorkspaceDirectory = { alpineRuntime.workspaceRoot },
        )
    }
    val guiTrajectoryStore by lazy {
        GuiTrajectoryStore(java.io.File(application.filesDir, "agent-mode-trajectories"))
    }
    val agentModeLearningRuntime by lazy {
        AgentModeLearningRuntime(
            trajectoryStore = guiTrajectoryStore,
            sopStore = agentModeSopStore,
            settlementRuntime = phoneSettlementRuntime,
            sceneStore = guiSceneStore,
            guiStepStoreDir = java.io.File(application.filesDir, "agent-mode-gui-steps"),
            diagnosticLogger = diagnosticLogger,
            isTeachingActive = { agentModeController.isTeachingActive },
            signalStore = guiSignalStore,
        ).also(agentModeController::addUserGestureListener)
    }
    val upaPluginLibrary by lazy { UpaPluginLibrary(application) }
    val upaPluginInstaller by lazy { UpaPluginInstaller(upaPluginLibrary, alpineRuntime) }
    val piExtensionManager by lazy {
        PiExtensionManager(
            context = application,
            alpineRuntime = alpineRuntime,
            piKernelBridge = piKernelBridge,
            skillManager = skillManager,
            stateRepository = piExtensionStateRepository,
        )
    }
    val aetherAppExtensionManager by lazy {
        AetherAppExtensionManager(
            bridge = piKernelBridge,
            scope = appScope,
            diagnosticLogger = diagnosticLogger,
            modKernel = modKernel,
            loadOptionsProvider = piExtensionStateRepository::loadOptions,
        )
    }
    val piAgentRunner by lazy {
        PiAgentRunner(
            bridge = piKernelBridge,
            settingsRepository = settingsRepository,
            piExtensionStateRepository = piExtensionStateRepository,
            appExtensionManager = aetherAppExtensionManager,
            termuxRuntimeOperations = TermuxRuntimeOperations(
                homeDirectory = alpineRuntime.homeDirectory,
                executeCommand = { command, workingDirectory, timeoutMillis ->
                    alpineRuntime.executeCommand(command, workingDirectory, timeoutMillis)
                },
            ),
            diagnosticLogger = diagnosticLogger,
            toolExecutor = AetherToolExecutor(
                runtimeRouter = runtimeRouter,
                agentModeController = agentModeController,
            ),
        )
    }
    val appForegroundTracker = AppForegroundTracker()
    val notificationController by lazy { AetherNotificationController(application) }
    val scheduledTaskScheduler by lazy {
        ScheduledTaskScheduler(
            context = application,
            diagnosticLogger = diagnosticLogger,
        )
    }
    val scheduledTaskManager by lazy {
        ScheduledTaskManager(
            repository = scheduledTaskRepository,
            scheduler = scheduledTaskScheduler,
        )
    }
    val chatStateStore by lazy {
        ChatStateStore(
            scope = appScope,
            chatRepository = chatRepository,
        )
    }
    val usageRecorder by lazy {
        UsageRecorder(
            store = chatRepository.usageStore,
            sessionsRoot = { alpineRuntime.kimiCodeSessionsHostDir() },
            aetherSessionIdForKimi = { alpineRuntime.aetherSessionIdForKimiSession(it) },
        )
    }
    val sessionExecutionManager by lazy {
        SessionExecutionManager(
            application = application,
            scope = appScope,
            settingsRepository = settingsRepository,
            extensionsRepository = extensionsRepository,
            chatStateStore = chatStateStore,
            chatRepository = chatRepository,
            runtimeRouter = runtimeRouter,
            workspaceFileBridge = workspaceFileBridge,
            rootSetupController = rootSetupController,
            agentModeController = agentModeController,
            skillManager = skillManager,
            scheduledTaskManager = scheduledTaskManager,
            notificationController = notificationController,
            appForegroundTracker = appForegroundTracker,
            diagnosticLogger = diagnosticLogger,
            piCompletionClient = piCompletionClient,
            piKernelBridge = piKernelBridge,
            piAgentRunner = piAgentRunner,
            upaPluginLibrary = upaPluginLibrary,
            kimiInteractionController = kimiInteractionController,
            agentModeLearningRuntime = agentModeLearningRuntime,
            usageRecorder = usageRecorder,
            kimiCronStore = kimiCronStore,
        )
    }

    fun shutdownNativeMods() {
        if (nativeModManagerDelegate.isInitialized()) {
            nativeModManager.shutdown()
        }
    }

    private val alpineWiringReady = CompletableDeferred<Unit>()

    /**
     * The single entry point for provisioning the local runtime. It waits until the
     * executors and listeners have been attached, then delegates to the idempotent
     * [AlpineRuntime.initialize] so app startup and the ViewModel share one install.
     */
    suspend fun ensureAlpineInitialized(
        onProgress: (kira.ditto.runtime.AlpineSetupProgress) -> Unit = {},
    ): kira.ditto.runtime.LocalRuntimeSetupState {
        alpineWiringReady.await()
        return alpineRuntime.initialize(onProgress)
    }

    /**
     * Runs on the main thread during [Application.onCreate], so it may only register
     * lifecycle observers (which require the main looper) and hand the rest to [appScope].
     */
    fun initialize() {
        diagnosticLogger.installUncaughtExceptionHandler()
        ProcessLifecycleOwner.get().lifecycle.addObserver(appForegroundTracker)
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    phoneSettlementRuntime.resumePending()
                }

                override fun onStop(owner: LifecycleOwner) {
                    phoneSettlementRuntime.pause()
                }
            },
        )
        appScope.launch { initializeInBackground() }
    }

    private suspend fun initializeInBackground() {
        // The first composition subscribes to these, so build them here in parallel with
        // Activity creation rather than letting the main thread do it inline.
        modKernel
        settingsRepository
        chatStateStore
        aetherAppExtensionManager
        nativeModManager
        diagnosticLogger.event(
            category = "app",
            event = "startup",
            details = mapOf(
                "version_name" to BuildConfig.VERSION_NAME,
                "version_code" to BuildConfig.VERSION_CODE,
                "debug" to BuildConfig.DEBUG,
            ),
        )
        notificationController.ensureChannels()
        // Constructing these registers the Shizuku bridge and the gesture listener that
        // AgentModeController hands out; the lazy graph would otherwise never wire them.
        shizukuProvisioner
        agentModeLearningRuntime
        alpineRuntime.attachUpaPluginLibrary(upaPluginLibrary)
        alpineRuntime.onKimiTurnSettled = { aetherSessionId, kimiSessionId ->
            usageRecorder.harvestKimiSession(aetherSessionId, kimiSessionId)
            kira.ditto.browser.AetherBrowserRuntime.destroyTopicTabs(aetherSessionId)
            drainBrowserMemoryUploads()
        }
        alpineRuntime.isGuiTeachingActive = { agentModeController.isTeachingActive }
        alpineRuntime.attachAgentDisplayExecutor { arguments, learningSessionId ->
            runBlocking {
                if (!shizukuProvisioner.isReady()) {
                    runCatching { shizukuProvisioner.ensureReady() }
                }
                val raw = agentModeController.execute(
                    settings = settingsRepository.settings.first(),
                    workspaceDirectory = alpineRuntime.workspaceRoot,
                    termuxWorkspaceDirectory = alpineRuntime.workspaceRoot,
                    argumentsJson = arguments.toString(),
                )
                agentModeLearningRuntime.recordAgentDisplayCall(
                    sessionId = learningSessionId,
                    arguments = arguments,
                    rawOutput = raw,
                )
            }
        }
        alpineRuntime.attachPhoneAppFlow(
            listTools = { PhoneAppFlowMcp.listToolsResult(phoneAppCatalogStore.loadAll()) },
            callTool = { name, arguments, learningSessionId ->
                runBlocking {
                    runCatching { shizukuProvisioner.ensureReady() }
                    val raw = phoneAppFlowExecutor.invoke(
                        toolName = name,
                        arguments = arguments,
                        settings = settingsRepository.settings.first(),
                        workspaceDirectory = alpineRuntime.workspaceRoot,
                    )
                    agentModeLearningRuntime.recordPhoneAppFlowCall(
                        sessionId = learningSessionId,
                        toolName = name,
                        arguments = arguments,
                        rawOutput = raw,
                    )
                }
            },
        )
        alpineRuntime.attachDeviceCatalogExecutor { arguments ->
            runBlocking {
                agentModeController.listInstalledAppsCatalog(
                    settings = settingsRepository.settings.first(),
                    arguments = arguments,
                    workspaceDirectory = alpineRuntime.workspaceRoot,
                )
            }
        }
        // Attach the spill store before any tool can produce a result to spill. Until it is
        // attached, oversized output falls back to plain truncation rather than failing.
        kira.ditto.data.ToolResultSpill.attach(java.io.File(application.cacheDir, "tool-spills"))
        alpineRuntime.attachWebMcp(
            listTools = { kira.ditto.data.WebMcpMcp.listToolsResult() },
            callTool = { name, arguments, learningSessionId ->
                val settings = runBlocking { settingsRepository.settings.first() }
                kira.ditto.browser.WebMcpHost.execute(
                    context = application,
                    prefs = settings.browserPreferences,
                    vault = browserLoginVault,
                    history = browserHistoryStore,
                    name = name,
                    arguments = arguments,
                    sessionId = learningSessionId,
                )
            },
        )
        // Every shipped server is now always declared, so the enable/disable toggle has to be
        // answered here instead of by omitting the server from the tool list.
        alpineRuntime.attachGmailMcp { name, arguments ->
            kira.ditto.data.McpToolAvailability.gate(kira.ditto.data.GmailMcp.PluginId, "Gmail") {
                kira.ditto.data.GmailMcpHost.execute(application, name, arguments)
            }
        }
        alpineRuntime.attachSpotifyMcp { name, arguments ->
            kira.ditto.data.McpToolAvailability.gate(kira.ditto.data.SpotifyMcp.PluginId, "Spotify") {
                kira.ditto.data.SpotifyMcpHost.execute(application, name, arguments)
            }
        }
        alpineRuntime.attachAmapMcp { name, arguments ->
            kira.ditto.data.McpToolAvailability.gate(kira.ditto.data.AmapMcp.PluginId, "高德地图") {
                kira.ditto.data.AmapMcpHost.execute(application, name, arguments)
            }
        }
        alpineRuntime.attachGithubMcp { name, arguments ->
            kira.ditto.data.McpToolAvailability.gate(kira.ditto.data.GithubMcp.PluginId, "GitHub") {
                kira.ditto.data.GithubMcpHost.execute(application, name, arguments)
            }
        }
        alpineRuntime.attachHuggingFaceMcp { name, arguments ->
            kira.ditto.data.McpToolAvailability.gate(kira.ditto.data.HuggingFaceMcp.PluginId, "Hugging Face") {
                kira.ditto.data.HuggingFaceMcpHost.execute(application, name, arguments)
            }
        }
        kira.ditto.data.warmDeviceCoordinatesAsync(application)
        alpineRuntime.attachGeckoWebSearch(
            search = { query, limit ->
                val settings = runBlocking { settingsRepository.settings.first() }
                kira.ditto.browser.GeckoKimiWeb.search(
                    context = application,
                    prefs = settings.browserPreferences,
                    vault = browserLoginVault,
                    history = browserHistoryStore,
                    query = query,
                    limit = limit,
                )
            },
            fetch = { url ->
                val settings = runBlocking { settingsRepository.settings.first() }
                kira.ditto.browser.BrowserPageReader.readMarkdown(
                    context = application,
                    prefs = settings.browserPreferences,
                    vault = browserLoginVault,
                    history = browserHistoryStore,
                    url = url,
                )
            },
        )
        appScope.launch {
            settingsRepository.settings.collect { settings ->
                kira.ditto.browser.AetherBrowserRuntime.updatePreferences(settings.browserPreferences)
            }
        }
        alpineRuntime.setKimiInteractionHandler(kimiInteractionController)
        alpineRuntime.setAvailableCommandsListener { payload ->
            sessionExecutionManager.replaceAgentSlashCommands(payload)
        }
        alpineRuntime.setPlanDocumentListener { aetherSessionId, path, markdown ->
            sessionExecutionManager.applyPlanDocument(aetherSessionId, path, markdown)
        }
        alpineRuntime.onKimiSessionPrepared = { _, kimiSessionId ->
            val tasks = runBlocking { scheduledTaskManager.snapshot() }
            kimiCronStore.projectIntoSession(kimiSessionId, tasks)
        }
        alpineWiringReady.complete(Unit)
        HostHealthSampler.start(application)
        // Kimi Code is the only agent runtime. The legacy native-mod bootstrap
        // discovers packages through pi-bridge, so it must never run at app
        // startup in the mobile Kimi build.
        appScope.launch {
            // Provision only APK-bundled assets. This makes Kimi Code, Node,
            // EverMe and evercli available before the first chat without a
            // download service or a separate setup action.
            ensureAlpineInitialized()
            val settings = runCatching { settingsRepository.settings.first() }.getOrNull()
            if (
                settings != null &&
                settings.apiKey.isNotBlank() &&
                settings.modelId.isNotBlank()
            ) {
                alpineRuntime.warmKimiEngine(settings.toPiModelConfig().toJson())
            } else {
                alpineRuntime.prewarmKimiProcess()
            }
        }
        appScope.launch {
            settingsRepository.migrateLegacyProvidersToPi()
        }
        appScope.launch {
            if (settingsRepository.settings.first().privacyPolicyAccepted) {
                initializePostHog()
            }
        }
        appScope.launch {
            scheduledTaskManager.rescheduleAll()
        }
        appScope.launch {
            phoneSettlementRuntime.resumePending(reclaimAllRunning = true)
        }
        sessionExecutionManager.settleAbandonedTurnsOnStartup()
    }

    fun initializePostHog() {
        application.initializePostHog()
    }

    fun handleScheduledTaskAlarm(
        taskId: String,
        pendingResult: android.content.BroadcastReceiver.PendingResult,
    ) {
        diagnosticLogger.event(
            category = "scheduled_task",
            event = "alarm_received",
            details = mapOf("task_id" to taskId),
        )
        appScope.launch {
            try {
                val task = scheduledTaskManager.markTriggeredAndScheduleNext(taskId)
                if (task == null) {
                    diagnosticLogger.event(
                        category = "scheduled_task",
                        event = "trigger_missing_task",
                        level = "warn",
                        details = mapOf("task_id" to taskId),
                    )
                    return@launch
                }
                val started = startScheduledTaskFromAlarm(task)
                diagnosticLogger.event(
                    category = "scheduled_task",
                    event = if (started) "trigger_started" else "trigger_skipped",
                    level = if (started) "info" else "warn",
                    details = mapOf("task_id" to taskId),
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun startScheduledTaskFromAlarm(task: ScheduledTask): Boolean {
        return try {
            if (settingsRepository.settings.first().keepTasksRunningInBackground) {
                runCatching {
                    AetherForegroundService.ensureRunning(application)
                }.onFailure { throwable ->
                    diagnosticLogger.exception(
                        category = "scheduled_task",
                        event = "foreground_service_start_failed",
                        throwable = throwable,
                        details = mapOf("task_id" to task.id),
                    )
                }
            }
            if (task.isGuiWatchTask()) {
                sessionExecutionManager.startKimiCronResume(
                    sessionId = task.sessionId.ifBlank { task.defaultSessionId() },
                    promptText = task.prompt,
                )
            } else {
                sessionExecutionManager.wakeOfficialCron(task)
            }
        } catch (throwable: Throwable) {
            diagnosticLogger.exception(
                category = "scheduled_task",
                event = "trigger_start_failed",
                throwable = throwable,
                details = mapOf("task_id" to task.id),
            )
            false
        }
    }

    fun rescheduleScheduledTasks(
        pendingResult: android.content.BroadcastReceiver.PendingResult,
    ) {
        appScope.launch {
            try {
                runCatching { shizukuProvisioner.ensureReady() }
                scheduledTaskManager.rescheduleAll()
            } finally {
                pendingResult.finish()
            }
        }
    }
}

class AppForegroundTracker : DefaultLifecycleObserver {
    private val _isForeground = MutableStateFlow(false)

    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    override fun onStart(owner: LifecycleOwner) {
        _isForeground.value = true
    }

    override fun onStop(owner: LifecycleOwner) {
        _isForeground.value = false
    }
}

val Context.aetherRuntime: AetherAppRuntime
    get() = (applicationContext as AetherApplication).runtime

/**
 * How many failed attempts a memo gets before it is written off.
 *
 * Without a ceiling, a memo the server will never accept - malformed, over quota, from a revoked
 * agent - would be retried at the end of every turn for the life of the install.
 */
private const val MaxBrowserUploadAttempts = 3
