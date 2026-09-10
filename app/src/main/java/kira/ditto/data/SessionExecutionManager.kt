package kira.ditto.data

import kira.ditto.browser.BrowserTaskMemoHeader
import kira.ditto.browser.parseBrowserTaskMemo
import kira.ditto.browser.stripBrowserTaskMemo
import android.app.Application
import android.os.SystemClock
import kira.ditto.R
import kira.ditto.AetherForegroundService
import kira.ditto.AetherNotificationController
import kira.ditto.AppForegroundTracker
import kira.ditto.runtime.RuntimeRouter
import kira.ditto.runtime.RuntimeShellTool
import kira.ditto.data.chatdb.ChatAgentSessionEntity
import kira.ditto.data.pi.PiAgentRunner
import kira.ditto.data.pi.PiCompletionClient
import kira.ditto.data.RemoteAgentRuntime
import kira.ditto.data.isRemoteAgentPath
import kira.ditto.data.parseRemoteMachineIdFromAgentPath
import kira.ditto.data.remoteAgentPath
import kira.ditto.data.pi.PiKernelBridge
import kira.ditto.data.kimi.KimiInteractionController
import kira.ditto.ui.AttachmentKind
import kira.ditto.ui.AssistantResponseBlock
import kira.ditto.ui.ChatAttachment
import kira.ditto.ui.ChatMessage
import kira.ditto.ui.ChatSession
import kira.ditto.ui.ChatToolInvocation
import kira.ditto.ui.ChatUsageStatistics
import kira.ditto.ui.MessageDisplayKind
import kira.ditto.ui.MessageAuthor
import kira.ditto.ui.ReasoningSummaryChunk
import kira.ditto.ui.ReasoningTrace
import kira.ditto.ui.completeAssistantBlocksForSteer
import kira.ditto.ui.composerDirectivePromptText
import kira.ditto.ui.lastPunchedBrowserTopicId
import kira.ditto.ui.pendingHasNativeBrowserSubagent
import kira.ditto.ui.pendingHasPunchedBrowserAgent
import kira.ditto.ui.punchThroughWebToBrowserAgent
import kira.ditto.ui.sanitizedForReasoningOff
import kira.ditto.ui.syncActiveBranches
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private const val ReasoningInitialSummaryTokenThreshold = 100
private const val ReasoningTimedSummaryIntervalMillis = 5_000L
private const val AssistantCheckpointIntervalMillis = 3_000L
private const val StreamUiCoalesceMillis = 48L
private const val ReasoningRawTextWindowChars = 32_768

private data class AgentModeTurnPrep(
    val mirroredSkillPaths: List<String>,
    val agentSessionEntity: ChatAgentSessionEntity?,
    val thinkingCaches: Pair<Map<String, Map<String, String>>, Set<String>>,
    val preopen: AgentModePreopenResult?,
)
private const val ReasoningSummaryMaxInputChars = 8_000
private const val ReasoningSummaryTitleMaxChars = 120
private const val ReasoningSummaryDetailMaxChars = 520
private const val ReasoningSummarySystemPrompt =
    "You write concise user-visible progress summaries for assistant reasoning. Use a consistent first-person planning style, and never quote long private reasoning verbatim."

internal fun completedReconnectStatus(status: String): String {
    val match = Regex("^Reconnecting\\.\\.\\.\\s*(.*)$", RegexOption.IGNORE_CASE).matchEntire(status.trim())
        ?: return status
    return "Reconnected" + match.groupValues[1].takeIf(String::isNotBlank)?.let { " $it" }.orEmpty()
}

internal fun completePendingReconnectBlocks(
    blocks: List<AssistantResponseBlock>,
): List<AssistantResponseBlock> = blocks.map { block ->
    if (block is AssistantResponseBlock.Status &&
        block.text.startsWith("Reconnecting", ignoreCase = true)
    ) {
        block.copy(text = completedReconnectStatus(block.text))
    } else {
        block
    }
}

enum class SessionFollowUpMode {
    Queue,
    Steer,
}

enum class SessionTurnOutcome {
    Success,
    ValidationError,
    Failure,
    Neutral,
}

data class PendingSessionInput(
    val id: String,
    val mode: SessionFollowUpMode,
    val preview: String,
    val attachmentCount: Int,
)

/**
 * Accumulates the wall-clock time an active turn spent waiting on unanswered
 * user interactions (permission / elicitation prompts). [pause] stamps an
 * anchor, [resume] folds the anchored segment into [pausedMillis]; the
 * user-visible "thinking" duration is the turn's wall clock minus everything
 * the clock absorbed, so the timer freezes while the agent waits for the user
 * and continues afterwards instead of stopping the whole turn clock.
 */
data class TurnActivityClock(
    val pausedMillis: Long = 0L,
    val pauseStartedAtMillis: Long? = null,
) {
    val isPaused: Boolean
        get() = pauseStartedAtMillis != null

    fun pause(nowMillis: Long): TurnActivityClock =
        if (pauseStartedAtMillis != null) this else copy(pauseStartedAtMillis = nowMillis)

    fun resume(nowMillis: Long): TurnActivityClock {
        val anchor = pauseStartedAtMillis ?: return this
        return copy(
            pausedMillis = pausedMillis + (nowMillis - anchor).coerceAtLeast(0L),
            pauseStartedAtMillis = null,
        )
    }

    /** Turn-active wall time at [nowMillis], excluding every paused segment. */
    fun activeElapsedMillis(startedAtMillis: Long, nowMillis: Long): Long {
        val ongoingPause = pauseStartedAtMillis?.let { (nowMillis - it).coerceAtLeast(0L) } ?: 0L
        return (nowMillis - startedAtMillis - pausedMillis - ongoingPause).coerceAtLeast(0L)
    }
}

data class SessionExecutionState(
    val sessionId: String,
    val isRunning: Boolean = false,
    val isPreparingWorkspace: Boolean = false,
    val pendingToolInvocations: List<ChatToolInvocation> = emptyList(),
    val pendingResponseBlocks: List<AssistantResponseBlock> = emptyList(),
    val pendingAssistantText: String = "",
    val pendingStatusText: String = "",
    val pendingStatusDetail: String = "",
    val pendingInputs: List<PendingSessionInput> = emptyList(),
    val activeResponseGroupId: String? = null,
    val activeResponseMessageIdPrefix: String? = null,
    val activeTurnStartedAtMillis: Long? = null,
    /** Waiting-on-user time absorbed for the active turn (see [TurnActivityClock]). */
    val activeTurnInteractionClock: TurnActivityClock = TurnActivityClock(),
    /** Latest full-replacement todo plan from the agent (plan_update). */
    val planEntries: List<SessionPlanEntry> = emptyList(),
    /** Detailed plan markdown written by Plan mode (agents/main/plans markdown). */
    val planDocumentMarkdown: String = "",
    /** Guest path of [planDocumentMarkdown], for `/plan view`. */
    val planDocumentPath: String = "",
    /** Message that created/replaced this plan; status ticks keep this pin. */
    val planAnchorMessageId: String? = null,
    /** Assistant response group that created/replaced this plan. */
    val planAnchorGroupId: String? = null,
    /** Task-title fingerprint used to tell a rewrite from a status tick. */
    val planContentFingerprint: String = "",
    /** Latest context-window usage (usage_update, or final prompt usage). */
    val contextUsage: SessionContextUsage? = null,
    /** Latest goal state reduced from goal tool calls (CreateGoal/UpdateGoal/SetGoalBudget). */
    val goalSnapshot: SessionGoalSnapshot? = null,
    /** Agent-reported mode/model/thinking; agent replay is authoritative. */
    val agentConfig: SessionAgentConfig = SessionAgentConfig(),
    /** Replayed user message text (user_message_chunk during session/load). */
    val replayedUserText: String = "",
    /** User approved ExitPlanMode for a multi-app course; desk lead uses long-horizon relay. */
    val longHorizonApproved: Boolean = false,
)

private enum class ExecutionPublish {
    Immediate,
    Coalesced,
}

internal fun streamCoalesceDelayMillis(
    previous: SessionExecutionState?,
    next: SessionExecutionState,
): Long {
    val previousLength = previous?.pendingAssistantText?.length ?: 0
    val nextLength = next.pendingAssistantText.length
    if (previousLength == 0 && nextLength > 0) return 0L
    return StreamUiCoalesceMillis
}

fun SessionExecutionState.forNonChatUi(): SessionExecutionState {
    if (
        pendingResponseBlocks.isEmpty() &&
        pendingAssistantText.isEmpty() &&
        pendingStatusDetail.isEmpty()
    ) {
        return this
    }
    return copy(
        pendingResponseBlocks = emptyList(),
        pendingAssistantText = "",
        pendingStatusDetail = "",
    )
}

fun SessionExecutionState.uiFingerprint(includeStreaming: Boolean): String = buildString {
    append(isRunning)
    append('|')
    append(isPreparingWorkspace)
    append('|')
    append(activeResponseGroupId)
    append('|')
    append(activeResponseMessageIdPrefix)
    append('|')
    pendingInputs.forEach { pending ->
        append(pending.id)
        append(':')
        append(pending.mode)
        append(',')
    }
    append('|')
    append(pendingStatusText)
    append('|')
    append(agentConfig.modeId)
    append('|')
    append(agentConfig.modelId)
    append('|')
    append(agentConfig.thinkingLevel)
    append('|')
    append(contextUsage?.usedTokens ?: -1L)
    append('/')
    append(contextUsage?.windowTokens ?: -1L)
    append('|')
    goalSnapshot?.let { goal ->
        append(goal.objective)
        append(':')
        append(goal.status)
        append(':')
        append(goal.tokensUsed)
        append('/')
        append(goal.tokenBudget ?: -1L)
        append(':')
        append(goal.turnsUsed)
        append('/')
        append(goal.turnBudget ?: -1L)
        append(':')
        append(goal.wallClockMs)
        append('/')
        append(goal.wallClockBudgetMs ?: -1L)
    }
    append('|')
    planEntries.forEach { entry ->
        append(entry.content)
        append(':')
        append(entry.status)
        append(',')
    }
    append('|')
    append(planAnchorMessageId.orEmpty())
    append('/')
    append(planAnchorGroupId.orEmpty())
    append('|')
    append(planDocumentPath)
    append(':')
    append(planDocumentMarkdown.length)
    append('|')
    append(replayedUserText.length)
    append('|')
    pendingToolInvocations.forEach { invocation ->
        append(invocation.id)
        append(':')
        append(invocation.isRunning)
        append(':')
        append(invocation.toolName)
        append(',')
    }
    if (!includeStreaming) return@buildString
    append('|')
    append(pendingAssistantText.length)
    append('|')
    pendingResponseBlocks.forEach { block ->
        when (block) {
            is AssistantResponseBlock.Text -> {
                append(block.id)
                append(":t:")
                append(block.text.length)
            }
            is AssistantResponseBlock.Reasoning -> {
                append(block.id)
                append(":r:")
                append(block.trace.rawText.length)
                append(':')
                append(block.trace.latestStatusText.length)
                append(':')
                append(block.trace.completedAtMillis ?: 0L)
            }
            is AssistantResponseBlock.ToolGroup -> {
                append(block.id)
                append(":g:")
                block.toolInvocations.forEach { invocation ->
                    append(invocation.id)
                    append(':')
                    append(invocation.isRunning)
                    append(',')
                }
            }
            is AssistantResponseBlock.Status -> {
                append(block.id)
                append(":s:")
                append(block.text)
            }
        }
        append(';')
    }
}

data class SessionTurnRequest(
    val sessionId: String,
    val settings: AppSettings,
    val requestMessages: List<ChatMessage>,
    val selectedSkillIds: List<String>,
    val activeSkills: List<ActiveSkillContext>,
    val activeMcpServerIds: List<String>,
    val agentModeEnabled: Boolean,
    val chromeEnabled: Boolean,
    val providerConfigs: List<LlmProviderConfig> = emptyList(),
    val remoteMachineId: String = "",
    val remoteBaseUrl: String = "",
    val remoteToken: String = "",
    val remoteKimiSessionId: String = "",
    val remoteCwd: String = "",
    /** The space this conversation belongs to; decides the agent's working directory. */
    val workspaceId: String = kira.ditto.data.chatdb.DefaultWorkspaceId,
)

internal fun SessionTurnRequest.withRemoteFrom(
    @Suppress("UNUSED_PARAMETER") session: ChatSession,
    @Suppress("UNUSED_PARAMETER") settings: AppSettings = this.settings,
): SessionTurnRequest = this

data class SessionTurnEvent(
    val sessionId: String,
    val outcome: SessionTurnOutcome,
    val toolCallCount: Int = 0,
    val distinctToolCount: Int = 0,
    val toolNames: List<String> = emptyList(),
    val durationMillis: Long? = null,
    val tokenUsage: LlmTokenUsage? = null,
    val tokenUsageSource: String = "unavailable",
    val inputMessageCount: Int = 0,
    val userMessageCount: Int = 0,
)

private enum class ReasoningCompletionTrigger {
    BodyStarted,
    TurnFinished,
    SteerAccepted,
}

private data class ReasoningSummary(
    val title: String,
    val detail: String,
)

private data class AssistantResponseIdentity(
    val responseGroupId: String,
    val messageIdPrefix: String,
    val createdAtMillis: Long,
    val checkpointFromPosition: Int,
) {
    fun messageIdFor(blockId: String): String = "$messageIdPrefix-$blockId"
}

private data class ProviderRequestCheckpoint(
    val pendingToolInvocations: List<ChatToolInvocation>,
    val pendingResponseBlocks: List<AssistantResponseBlock>,
    val pendingAssistantText: String,
    val activeReasoningBlockId: String?,
    val activeDirectReasoningSummaryChunkId: String?,
    val reasoningFirstSummarySubmitted: Boolean,
    val reasoningLastSubmittedCharIndex: Int,
    val reasoningLastTimedSummaryAtMillis: Long,
)

class SessionExecutionManager(
    private val application: Application,
    private val scope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val extensionsRepository: AgentExtensionsRepository,
    private val chatStateStore: ChatStateStore,
    private val chatRepository: ChatRepository,
    private val runtimeRouter: RuntimeRouter,
    private val workspaceFileBridge: WorkspaceFileBridge,
    private val rootSetupController: RootSetupController,
    private val agentModeController: AgentModeController,
    private val skillManager: AgentSkillManager,
    private val scheduledTaskManager: ScheduledTaskManager,
    private val notificationController: AetherNotificationController,
    private val appForegroundTracker: AppForegroundTracker,
    private val diagnosticLogger: AetherDiagnosticLogger = AetherDiagnosticLogger.NoOp,
    private val piCompletionClient: PiCompletionClient? = null,
    private val piKernelBridge: PiKernelBridge,
    private val piAgentRunner: PiAgentRunner,
    @Suppress("unused")
    private val upaPluginLibrary: UpaPluginLibrary,
    private val kimiInteractionController: KimiInteractionController? = null,
    private val agentModeLearningRuntime: AgentModeLearningRuntime? = null,
    private val usageRecorder: UsageRecorder? = null,
    private val kimiCronStore: KimiSessionCronStore? = null,
) {
    private val activeTurnStore = ActiveTurnStore(application)
    private val skillRuntimeMirror = SkillRuntimeMirror(runtimeRouter)
    private val currentSettings = MutableStateFlow(AppSettings())
    private val currentProviderConfigs = MutableStateFlow<List<LlmProviderConfig>>(emptyList())
    private val currentExtensionsState = MutableStateFlow(AgentExtensionsState())
    private val _executionStates = MutableStateFlow<Map<String, SessionExecutionState>>(emptyMap())
    private val unpublishedExecutionStates = mutableMapOf<String, SessionExecutionState>()
    private val executionPublishLock = Any()
    private var coalescedPublishJob: Job? = null
    private val _turnEvents = MutableSharedFlow<SessionTurnEvent>(extraBufferCapacity = 8)
    private val _agentSlashCommands = MutableStateFlow<List<AgentSlashCommand>>(emptyList())
    private val executionHandles = ConcurrentHashMap<String, SessionExecutionHandle>()
    private val longHorizonApprovedIds = ConcurrentHashMap<String, Boolean>()
    private val longHorizonHandoffPending = ConcurrentHashMap<String, Boolean>()
    private val lastAgentModeTurnSettledAtMillis = AtomicLong(0L)
    /**
     * What we know about operating the sites this session has touched, rendered once per turn.
     *
     * Cached rather than queried inline because the lead is built synchronously, and refreshed at
     * the start of a turn rather than per message so that every message renders identically on
     * every rebuild - the rule the date line had to learn the hard way. A dossier that changed
     * shape mid-history would move the point where the prompt prefix diverges and throw away the
     * cache behind it.
     */
    private val browserDossierBySession = ConcurrentHashMap<String, String>()

    private val queuedTurnRequestBuilder = QueuedTurnRequestBuilder(chatStateStore)
    private val mcpSecretStore by lazy { HostSecretStore(application) }

    val executionStates: StateFlow<Map<String, SessionExecutionState>> = _executionStates.asStateFlow()
    val turnEvents = _turnEvents.asSharedFlow()

    /**
     * Latest slash command set advertised by the agent (available_commands_update).
     * The CLI sends the full set on every update, so each event replaces the list.
     */
    val agentSlashCommands: StateFlow<List<AgentSlashCommand>> = _agentSlashCommands.asStateFlow()

    init {
        scope.launch {
            settingsRepository.settings.collect { settings ->
                currentSettings.value = settings
                if (
                    settings.keepTasksRunningInBackground &&
                    _executionStates.value.values.any { it.isRunning }
                ) {
                    ensureForegroundServiceRunning()
                }
            }
        }
        scope.launch {
            extensionsRepository.extensionState.collect { state ->
                currentExtensionsState.value = state
                // The tool list no longer carries enablement, so the call-time gate has to. Pushed
                // rather than pulled: a tool call arrives on the MCP transport thread with no scope
                // to collect this flow from, and blocking there would stall the call behind a
                // DataStore read.
                McpToolAvailability.setDisabledServerIds(
                    mergeShippedMcpServers(state.mcpServers)
                        .filterNot { it.isEnabled }
                        .mapTo(mutableSetOf()) { it.id },
                )
            }
        }
        scope.launch {
            settingsRepository.providerConfigs.collect { currentProviderConfigs.value = it }
        }
        if (kimiInteractionController != null) {
            scope.launch {
                combine(
                    kimiInteractionController.pendingPermissions,
                    kimiInteractionController.pendingElicitations,
                ) { permissions, elicitations ->
                    buildSet {
                        permissions.forEach { if (it.sessionId.isNotBlank()) add(it.sessionId) }
                        elicitations.forEach { if (it.sessionId.isNotBlank()) add(it.sessionId) }
                    }
                }.collect(::setInteractionWaitingSessions)
            }
        }
        piKernelBridge.setIdleCronPromptHandler { _, promptText ->
            scope.launch {
                val tasks = scheduledTaskManager.snapshot()
                if (kimiCronStore?.shouldSuppressIdleCronFire(promptText, tasks) == true) {
                    return@launch
                }
                if (shouldDeferIdleCronForGui()) {
                    delay(15_000L)
                    if (shouldDeferIdleCronForGui()) return@launch
                }
                val spec = officialIdleCronResume()
                startKimiCronResume(
                    sessionId = spec.sessionId,
                    promptText = promptText,
                    visibleText = "定时任务",
                    createIfMissing = true,
                    title = "定时任务",
                    adoptAsCurrentSession = spec.adoptAsCurrentSession,
                    enableAgentMode = spec.agentModeEnabled,
                )
            }
        }
    }

    /**
     * Pauses the active-turn clock of every session waiting on an unanswered
     * user interaction (permission / elicitation) and resumes every other
     * session. Driven by [KimiInteractionController]'s pending flows; waiting
     * starts/ends stamp anchors on the session's [TurnActivityClock] so the
     * user-visible thinking timer freezes during the wait and continues after.
     */
    fun setInteractionWaitingSessions(waitingSessionIds: Set<String>) {
        val now = System.currentTimeMillis()
        _executionStates.value.values.forEach { state ->
            if (state.activeTurnStartedAtMillis == null) return@forEach
            val waiting = state.sessionId in waitingSessionIds
            val clock = state.activeTurnInteractionClock
            if (waiting == clock.isPaused) return@forEach
            updateExecutionState(state.sessionId, ExecutionPublish.Coalesced) { current ->
                current.copy(
                    activeTurnInteractionClock = if (waiting) {
                        current.activeTurnInteractionClock.pause(now)
                    } else {
                        current.activeTurnInteractionClock.resume(now)
                    },
                )
            }
        }
    }

    fun isSessionRunning(sessionId: String): Boolean =
        _executionStates.value[sessionId]?.isRunning == true

    private fun shouldDeferIdleCronForGui(): Boolean = shouldDeferIdleCronResume(
        runningSessionIds = _executionStates.value.filter { it.value.isRunning }.keys,
        displayActive = agentModeController.displayState.value.isActive,
        lastAgentModeTurnSettledAtMillis = this.lastAgentModeTurnSettledAtMillis.get(),
        nowMillis = System.currentTimeMillis(),
    )

    fun settleAbandonedTurnsOnStartup() {
        scope.launch(Dispatchers.IO) {
            val leftover = runCatching { activeTurnStore.consumeLeftover() }.getOrDefault(emptySet())
            leftover.forEach { sessionId ->
                if (isSessionRunning(sessionId)) return@forEach
                diagnosticLogger.event(
                    category = "session",
                    event = "turn_cancelled",
                    level = "warn",
                    sessionId = sessionId,
                    details = processDeathCancellationDetail(),
                )
                chatStateStore.update { persisted ->
                    val sessionIndex = persisted.sessions.indexOfFirst { it.id == sessionId }
                    if (sessionIndex < 0) return@update persisted
                    val sessions = persisted.sessions.toMutableList()
                    val session = sessions[sessionIndex]
                    val messages = settleAbandonedTurnMessages(
                        messages = session.messages,
                        interruptedText = application.getString(
                            R.string.chat_turn_interrupted_process_death,
                        ),
                    )
                    sessions[sessionIndex] = session.withDerivedMessages(messages)
                    persisted.copy(sessions = sessions)
                }
                updateExecutionState(sessionId) {
                    it.copy(
                        sessionId = sessionId,
                        isRunning = false,
                        pendingToolInvocations = emptyList(),
                        pendingResponseBlocks = emptyList(),
                        pendingAssistantText = "",
                        pendingStatusText = "",
                        pendingStatusDetail = "",
                        activeTurnStartedAtMillis = null,
                    )
                }
                kira.ditto.browser.AetherBrowserRuntime.destroyTopicTabs(sessionId)
            }
            runCatching { chatStateStore.flush() }
        }
    }

    /** Stores the Plan-mode markdown document written under kimi-code sessions. */
    fun applyPlanDocument(sessionId: String, path: String, markdown: String) {
        if (sessionId.isBlank()) return
        updateExecutionState(sessionId) { current ->
            val needsAnchor = current.planAnchorMessageId.isNullOrBlank()
            val (messageId, groupId) = if (needsAnchor) {
                resolveSessionPlanAnchor(
                    activeResponseGroupId = current.activeResponseGroupId,
                    messages = chatStateStore.state.value.sessions
                        .firstOrNull { it.id == sessionId }
                        ?.messages
                        .orEmpty(),
                )
            } else {
                current.planAnchorMessageId to current.planAnchorGroupId
            }
            current.copy(
                planDocumentPath = path,
                planDocumentMarkdown = markdown,
                planAnchorMessageId = messageId ?: current.planAnchorMessageId,
                planAnchorGroupId = groupId ?: current.planAnchorGroupId,
            )
        }
    }

    /** Optimistic local projection of `/goal pause|resume|cancel`. */
    fun applyGoalControl(sessionId: String, action: String) {
        if (sessionId.isBlank()) return
        val normalized = action.trim().lowercase()
        updateExecutionState(sessionId) { current ->
            val goal = current.goalSnapshot ?: return@updateExecutionState current
            when (normalized) {
                "pause" -> current.copy(
                    goalSnapshot = goal.copy(status = "paused", updatedAtMillis = System.currentTimeMillis()),
                )
                "resume" -> current.copy(
                    goalSnapshot = goal.copy(status = "active", updatedAtMillis = System.currentTimeMillis()),
                )
                "cancel" -> current.copy(goalSnapshot = null)
                else -> current
            }
        }
    }

    /** Optimistically records the composer-selected ACP/permission mode. */
    fun applyAgentModeId(sessionId: String, modeId: String) {
        if (sessionId.isBlank()) return
        val normalized = normalizeKimiPermissionMode(modeId)
        updateExecutionState(sessionId) { current ->
            current.copy(agentConfig = current.agentConfig.copy(modeId = normalized))
        }
    }

    fun startTurn(request: SessionTurnRequest) {
        val handle = SessionExecutionHandle(sessionId = request.sessionId)
        handle.replaceRetainedMessages(request.requestMessages)
        if (executionHandles.putIfAbsent(request.sessionId, handle) != null) return

        val validationError = validateRequest(request)
        if (validationError != null) {
            diagnosticLogger.event(
                category = "session",
                event = "turn_validation_failed",
                level = "warn",
                sessionId = request.sessionId,
                details = mapOf("message" to validationError),
            )
            executionHandles.remove(request.sessionId, handle)
            val completion = appendAgentMessage(
                sessionId = request.sessionId,
                blocks = listOf(
                    AssistantResponseBlock.Text(
                        id = "agent-validation-${System.currentTimeMillis()}",
                        text = validationError,
                    )
                ),
                thoughtDurationMillis = null,
                outcome = SessionTurnOutcome.ValidationError,
                baseMessages = request.requestMessages,
            )
            _turnEvents.tryEmit(completion.toTurnEvent(request.sessionId))
            return
        }

        updateExecutionState(request.sessionId) {
            it.copy(
                sessionId = request.sessionId,
                isRunning = true,
                isPreparingWorkspace = true,
                pendingToolInvocations = emptyList(),
                pendingResponseBlocks = emptyList(),
                pendingAssistantText = "",
                pendingStatusText = "",
                pendingStatusDetail = "",
                activeResponseGroupId = null,
                activeResponseMessageIdPrefix = null,
                activeTurnStartedAtMillis = null,
                activeTurnInteractionClock = TurnActivityClock(),
            )
        }
        diagnosticLogger.event(
            category = "session",
            event = "turn_queued",
            sessionId = request.sessionId,
            details = mapOf(
                "provider" to request.settings.piProviderId,
                "model" to request.settings.modelId,
                "base_url" to DiagnosticRedactor.sanitizedBaseUrl(request.settings.baseUrl),
                "request_message_count" to request.requestMessages.size,
                "selected_skill_count" to request.selectedSkillIds.size,
                "active_mcp_server_count" to request.activeMcpServerIds.size,
                "agent_mode_enabled" to request.agentModeEnabled,
            ),
        )

        handle.job = scope.launch {
            runCatching { activeTurnStore.markRunning(request.sessionId) }
            runSession(
                handle = handle,
                initialRequest = request,
            )
        }
    }

    fun submitFollowUp(
        sessionId: String,
        message: ChatMessage,
        mode: SessionFollowUpMode,
    ): Boolean {
        val handle = executionHandles[sessionId] ?: return false
        val pending = PendingEnvelope(
            id = "pending-${System.currentTimeMillis()}-${message.id}",
            mode = mode,
            message = message,
        )
        synchronized(handle.lock) {
            when (mode) {
                SessionFollowUpMode.Queue -> handle.queuedInputs += pending
                SessionFollowUpMode.Steer -> handle.steerInputs += pending
            }
        }
        if (mode == SessionFollowUpMode.Steer) {
            commitSteerUi(handle, listOf(pending))
        } else {
            updateExecutionState(sessionId) { current ->
                current.copy(
                    pendingInputs = current.pendingInputs + pending.toUiState()
                )
            }
        }
        return true
    }

    fun promoteQueuedInputToSteer(sessionId: String, pendingId: String): Boolean {
        val handle = executionHandles[sessionId] ?: return false
        val moved = synchronized(handle.lock) {
            val index = handle.queuedInputs.indexOfFirst { it.id == pendingId }
            if (index < 0) return@synchronized null
            val entry = handle.queuedInputs.removeAt(index)
                .copy(mode = SessionFollowUpMode.Steer)
            handle.steerInputs += entry
            entry
        } ?: return false
        commitSteerUi(handle, listOf(moved))
        return true
    }

    fun pauseSession(sessionId: String): ChatSession? {
        val handle = executionHandles[sessionId] ?: return null
        if (handle.pauseRequested) return null
        handle.pauseRequested = true
        val snapshot = _executionStates.value[sessionId]
        val runningRunIds = snapshot?.pendingToolInvocations?.let(::extractActiveManagedRunIds).orEmpty()
        val completion = finalizePausedTurn(
            handle = handle,
            snapshot = snapshot ?: SessionExecutionState(sessionId = sessionId),
        )
        deactivateAssistantCheckpoint(handle, handle.activeResponseIdentity)
        scope.launch(Dispatchers.IO) {
            runCatching { chatStateStore.flush() }
        }
        handle.pauseFinalized = true
        executionHandles.remove(sessionId, handle)
        updateExecutionState(sessionId) {
            it.copy(
                sessionId = sessionId,
                isRunning = false,
                pendingToolInvocations = emptyList(),
                pendingResponseBlocks = emptyList(),
                pendingAssistantText = "",
                pendingStatusText = "",
                pendingStatusDetail = "",
                pendingInputs = emptyList(),
                activeResponseGroupId = null,
                activeResponseMessageIdPrefix = null,
                activeTurnStartedAtMillis = null,
                activeTurnInteractionClock = TurnActivityClock(),
            )
        }
        _turnEvents.tryEmit(completion.toTurnEvent(sessionId))
        handle.job?.cancel(CancellationException("Paused by user."))
        scope.launch(Dispatchers.IO) {
            runCatching { activeTurnStore.clear(sessionId) }
            runCatching { piKernelBridge.abortActiveTurn() }
        }
        if (runningRunIds.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                val shellTool = RuntimeShellTool(runtimeRouter)
                runningRunIds.forEach { runId ->
                    runCatching { shellTool.killByRunId(runId) }
                }
            }
        }
        return chatStateStore.state.value.sessions.firstOrNull { it.id == sessionId }
    }

    suspend fun startScheduledTask(task: ScheduledTask): Boolean {
        if (!task.isEnabled || task.prompt.isBlank()) return false
        val settings = settingsRepository.settings.first()
        val providerConfigs = settingsRepository.providerConfigs.first()
        val sessionId = if (task.isGuiWatchTask()) {
            task.defaultSessionId()
        } else {
            AppScheduledSessionId
        }
        val now = System.currentTimeMillis()
        val userMessage = ChatMessage(
            id = "scheduled-${task.id.take(8)}-$now",
            author = MessageAuthor.User,
            text = task.prompt,
            createdAtMillis = now,
        )

        if (isSessionRunning(sessionId)) {
            return submitFollowUp(
                sessionId = sessionId,
                message = userMessage,
                mode = SessionFollowUpMode.Queue,
            )
        }

        var selectedModelKey = ""
        var requestMessages: List<ChatMessage> = emptyList()
        var selectedSkillIds: List<String> = emptyList()
        var activeSkills: List<ActiveSkillContext> = emptyList()
        var activeMcpServerIds: List<String> = emptyList()
        var agentModeEnabled = false
        var chromeEnabled = false
        var remoteSession = ChatSession(id = sessionId, title = "", preview = "", messages = emptyList())

        val persistedSessionWithMessages = chatRepository.getSessionWithMessages(sessionId)

        chatStateStore.updateAndFlush { persisted ->
            val updatedSessions = persisted.sessions.toMutableList()
            val existingIndex = updatedSessions.indexOfFirst { it.id == sessionId }
            val updatedSession = if (existingIndex >= 0) {
                val existing = updatedSessions.removeAt(existingIndex)
                val baseMessages = existing.messages.ifEmpty {
                    persistedSessionWithMessages?.messages.orEmpty()
                }
                existing.withDerivedMessages(baseMessages + userMessage)
            } else {
                ChatSession(
                    id = sessionId,
                    title = task.name.ifBlank { "Scheduled task" },
                    preview = userMessage.summaryText(),
                    hasCustomTitle = true,
                    messages = listOf(userMessage),
                    selectedModelKey = resolveDefaultChatModelKey(settings, providerConfigs),
                )
            }
            selectedModelKey = updatedSession.selectedModelKey
            requestMessages = updatedSession.messages
            selectedSkillIds = updatedSession.selectedSkillIds
            activeSkills = updatedSession.activeSkills
            activeMcpServerIds = updatedSession.activeMcpServerIds
            agentModeEnabled = updatedSession.agentModeEnabled
            chromeEnabled = updatedSession.chromeEnabled
            remoteSession = updatedSession
            updatedSessions.add(0, updatedSession)
            persisted.copy(
                sessions = updatedSessions,
                currentSessionId = sessionId,
            )
        }

        startTurn(
            SessionTurnRequest(
                sessionId = sessionId,
                settings = resolveModelSettings(
                    baseSettings = settings,
                    providerConfigs = providerConfigs,
                    preferredModelKey = selectedModelKey,
                    fallbackModelKey = resolveDefaultChatModelKey(settings, providerConfigs),
                ),
                requestMessages = requestMessages,
                selectedSkillIds = selectedSkillIds,
                activeSkills = activeSkills,
                activeMcpServerIds = activeMcpServerIds,
                agentModeEnabled = agentModeEnabled,
                chromeEnabled = chromeEnabled,
                providerConfigs = providerConfigs,
            ).withRemoteFrom(remoteSession, settings),
        )
        return true
    }

    suspend fun wakeOfficialCron(task: ScheduledTask): Boolean {
        if (!task.isEnabled || task.prompt.isBlank()) return false
        val sessionId = if (task.isGuiWatchTask()) {
            task.sessionId
                .takeIf { it.isNotBlank() && it != "draft" }
                ?: task.defaultSessionId()
        } else {
            AppScheduledSessionId
        }
        runCatching { piKernelBridge.ensureKimiAcpReady() }
        return startKimiCronResume(
            sessionId = sessionId,
            promptText = task.prompt,
            visibleText = task.name.ifBlank { "定时任务" },
            createIfMissing = true,
            title = task.name,
            adoptAsCurrentSession = task.isGuiWatchTask(),
            enableAgentMode = task.isGuiWatchTask(),
        )
    }

    suspend fun startKimiCronResume(
        sessionId: String,
        promptText: String,
        visibleText: String = "正在检查课程进度",
        createIfMissing: Boolean = false,
        title: String = "",
        adoptAsCurrentSession: Boolean = true,
        enableAgentMode: Boolean = false,
    ): Boolean {
        if (sessionId.isBlank()) return false
        val settings = settingsRepository.settings.first()
        val providerConfigs = settingsRepository.providerConfigs.first()
        val cronPrompt = promptText.trim().ifBlank {
            GuiWatchResumePrompt
        }
        val now = System.currentTimeMillis()
        val userMessage = ChatMessage(
            id = "kimi-cron-$now",
            author = MessageAuthor.User,
            text = visibleText.trim().ifBlank { "正在检查课程进度" },
            createdAtMillis = now,
            hiddenPromptPrefix = cronPrompt,
            agentModeEnabled = enableAgentMode,
        )
        if (isSessionRunning(sessionId)) {
            return submitFollowUp(
                sessionId = sessionId,
                message = userMessage,
                mode = SessionFollowUpMode.Queue,
            )
        }
        var selectedModelKey = ""
        var requestMessages: List<ChatMessage> = emptyList()
        var selectedSkillIds: List<String> = emptyList()
        var activeSkills: List<ActiveSkillContext> = emptyList()
        var activeMcpServerIds: List<String> = emptyList()
        var agentModeEnabled = enableAgentMode
        var chromeEnabled = false
        var remoteSession = ChatSession(id = sessionId, title = "", preview = "", messages = emptyList())
        val persistedSessionWithMessages = chatRepository.getSessionWithMessages(sessionId)
        chatStateStore.updateAndFlush { persisted ->
            val updatedSessions = persisted.sessions.toMutableList()
            val existingIndex = updatedSessions.indexOfFirst { it.id == sessionId }
            val updatedSession = if (existingIndex >= 0) {
                val existing = updatedSessions.removeAt(existingIndex)
                val baseMessages = existing.messages.ifEmpty {
                    persistedSessionWithMessages?.messages.orEmpty()
                }
                existing.withDerivedMessages(baseMessages + userMessage)
            } else if (createIfMissing) {
                ChatSession(
                    id = sessionId,
                    title = title.ifBlank { "Scheduled task" },
                    preview = userMessage.summaryText(),
                    hasCustomTitle = true,
                    messages = listOf(userMessage),
                    selectedModelKey = resolveDefaultChatModelKey(settings, providerConfigs),
                    agentModeEnabled = enableAgentMode,
                )
            } else {
                return@updateAndFlush persisted
            }
            selectedModelKey = updatedSession.selectedModelKey
            requestMessages = updatedSession.messages
            selectedSkillIds = updatedSession.selectedSkillIds
            activeSkills = updatedSession.activeSkills
            activeMcpServerIds = updatedSession.activeMcpServerIds
            agentModeEnabled = enableAgentMode
            chromeEnabled = updatedSession.chromeEnabled
            remoteSession = updatedSession
            updatedSessions.add(0, updatedSession)
            persisted.copy(
                sessions = updatedSessions,
                currentSessionId = if (adoptAsCurrentSession) {
                    sessionId
                } else {
                    persisted.currentSessionId
                },
            )
        }
        if (requestMessages.isEmpty()) return false
        diagnosticLogger.event(
            category = "kimi_acp",
            event = "idle_cron_resume",
            sessionId = sessionId,
            details = mapOf("prompt_length" to cronPrompt.length),
        )
        startTurn(
            SessionTurnRequest(
                sessionId = sessionId,
                settings = resolveModelSettings(
                    baseSettings = settings,
                    providerConfigs = providerConfigs,
                    preferredModelKey = selectedModelKey,
                    fallbackModelKey = resolveDefaultChatModelKey(settings, providerConfigs),
                ),
                requestMessages = requestMessages,
                selectedSkillIds = selectedSkillIds,
                activeSkills = activeSkills,
                activeMcpServerIds = activeMcpServerIds,
                agentModeEnabled = agentModeEnabled,
                chromeEnabled = chromeEnabled,
                providerConfigs = providerConfigs,
            ).withRemoteFrom(remoteSession, settings),
        )
        return true
    }

    /**
     * Take the research memo off a finished browser subagent and put it in the ledger.
     *
     * The sibling of the phone agent's `observeNativeAgentEvent`, and deliberately a separate
     * branch rather than a condition added to that one: that runtime only exists in agent mode and
     * only wakes for a live GUI attempt, while browser research is something ordinary chat does.
     *
     * Splitting the work this way - the model writes the summary, the host decides what happens to
     * it - is the only division that survives the fact that the upload cannot be undone. Judging
     * what a page said is a language problem, so the model does it. Judging whether a judgement is
     * worth keeping forever has to be deterministic code, because a model that inflates its own
     * salience once has permanently written something the user cannot delete.
     *
     * @return the event with the memo block removed, or the event unchanged
     */
    private suspend fun captureBrowserTaskMemo(
        sessionId: String,
        event: AgentToolEvent,
    ): AgentToolEvent {
        val output = event.outputJson.orEmpty()
        if (event.isRunning == true || output.isBlank()) return event
        if (BrowserTaskMemoHeader !in output) return event
        val arguments = runCatching { JSONObject(event.argumentsJson) }.getOrNull()
        if (!isBrowserSubagentEvent(event.name, arguments)) return event
        val memo = parseBrowserTaskMemo(output) ?: return event
        val topicId = arguments?.optString("topic_id").orEmpty()
        val goal = arguments?.optString("prompt").orEmpty()
            .ifBlank { arguments?.optString("description").orEmpty() }
        runCatching {
            chatRepository.recordBrowserTaskMemo(
                sessionId = sessionId,
                messageId = event.id,
                topicId = topicId,
                goal = goal,
                memo = memo,
            )
        }.onFailure { error ->
            android.util.Log.w("SessionExecutionManager", "browser task memo write failed", error)
        }
        val stripped = stripBrowserTaskMemo(output)
        return if (stripped == output) event else event.copy(outputJson = stripped)
    }

    /**
     * Load the site knowledge this session may need, once per turn.
     *
     * Scoped to hosts this session has already touched, because that is the set the next step is
     * most likely to touch again - and because a dossier of every site ever visited would be both
     * useless and a standing prefix that grows without bound. Only facts confirmed more than once
     * are included: a single sighting of "needs a login" is as likely to have been a signed-out
     * session as a property of the site.
     */
    private suspend fun refreshBrowserDossier(sessionId: String) {
        if (sessionId.isBlank()) return
        val origins = runCatching {
            kira.ditto.browser.BrowserTopicGraph.snapshot(sessionId)
                .flatMap { tab -> tab.hits.map { kira.ditto.browser.originOf(it.url) } }
                .filter { it.isNotBlank() }
                .distinct()
                .take(12)
        }.getOrDefault(emptyList())
        if (origins.isEmpty()) {
            browserDossierBySession.remove(sessionId)
            return
        }
        val known = runCatching { chatRepository.getBrowserOriginDossier(origins) }
            .getOrDefault(emptyList())
            .filter { it.confirmCount >= 2 && it.facts.isNotBlank() }
        if (known.isEmpty()) {
            browserDossierBySession.remove(sessionId)
            return
        }
        browserDossierBySession[sessionId] = buildString {
            append("已知站点情况（来自以往访问，供你决定是否值得再探）：")
            known.take(6).forEach { origin ->
                appendLine()
                append("- ").append(origin.origin).append("：").append(origin.facts)
            }
        }
    }

    private fun isBrowserSubagentEvent(toolName: String, arguments: JSONObject?): Boolean {
        val profile = arguments?.optString("subagent_type")?.trim().orEmpty()
        if (profile.equals(KimiBrowserSubagentProfileName, ignoreCase = true)) return true
        val title = toolName.lowercase()
        return title.contains("browser agent") || title.contains("websearch")
    }

    private suspend fun applyGuiWatchSchedule(action: GuiWatchScheduleAction) {
        val sessionId = action.sessionId.trim()
        if (sessionId.isBlank()) return
        val taskId = guiWatchTaskId(sessionId)
        val interval = action.intervalMillis
        if (interval == null) {
            scheduledTaskManager.removeTask(taskId)
            diagnosticLogger.event(
                category = "scheduled_task",
                event = "gui_watch_cancelled",
                sessionId = sessionId,
                details = mapOf("task_id" to taskId),
            )
            return
        }
        val existing = scheduledTaskManager.findTask(taskId)
        val task = buildGuiWatchScheduledTask(
            sessionId = sessionId,
            intervalMillis = interval,
            existing = existing,
        )
        scheduledTaskManager.upsertTask(task)
        diagnosticLogger.event(
            category = "scheduled_task",
            event = "gui_watch_scheduled",
            sessionId = sessionId,
            details = mapOf(
                "task_id" to task.id,
                "interval_ms" to task.schedule.let { schedule ->
                    (schedule as? ScheduledTaskSchedule.Interval)?.intervalMillis ?: interval
                },
            ),
        )
    }

    fun markLongHorizonApproved(sessionId: String) {
        if (sessionId.isBlank()) return
        longHorizonApprovedIds[sessionId] = true
        longHorizonHandoffPending[sessionId] = true
        updateExecutionState(sessionId) { current ->
            current.copy(sessionId = sessionId, longHorizonApproved = true)
        }
    }

    fun consumeLongHorizonHandoff(sessionId: String): Boolean =
        sessionId.isNotBlank() && longHorizonHandoffPending.remove(sessionId) == true

    fun isLongHorizonApproved(sessionId: String): Boolean =
        longHorizonApprovedIds[sessionId] == true ||
            _executionStates.value[sessionId]?.longHorizonApproved == true

    suspend fun startLongHorizonExecute(sessionId: String): Boolean {
        if (sessionId.isBlank()) return false
        longHorizonApprovedIds[sessionId] = true
        val settings = settingsRepository.settings.first()
        val providerConfigs = settingsRepository.providerConfigs.first()
        val now = System.currentTimeMillis()
        val userMessage = ChatMessage(
            id = "long-horizon-$now",
            author = MessageAuthor.User,
            text = LongHorizonExecuteUserVisibleText,
            createdAtMillis = now,
            hiddenPromptPrefix = LongHorizonExecuteHiddenPrompt,
            agentModeEnabled = true,
        )
        if (isSessionRunning(sessionId)) {
            return submitFollowUp(
                sessionId = sessionId,
                message = userMessage,
                mode = SessionFollowUpMode.Queue,
            )
        }
        var selectedModelKey = ""
        var requestMessages: List<ChatMessage> = emptyList()
        var selectedSkillIds: List<String> = emptyList()
        var activeSkills: List<ActiveSkillContext> = emptyList()
        var activeMcpServerIds: List<String> = emptyList()
        var chromeEnabled = false
        var remoteSession = ChatSession(id = sessionId, title = "", preview = "", messages = emptyList())
        val persistedSessionWithMessages = chatRepository.getSessionWithMessages(sessionId)
        chatStateStore.updateAndFlush { persisted ->
            val updatedSessions = persisted.sessions.toMutableList()
            val existingIndex = updatedSessions.indexOfFirst { it.id == sessionId }
            if (existingIndex < 0) return@updateAndFlush persisted
            val existing = updatedSessions.removeAt(existingIndex)
            val baseMessages = existing.messages.ifEmpty {
                persistedSessionWithMessages?.messages.orEmpty()
            }
            val updatedSession = existing
                .copy(agentModeEnabled = true)
                .withDerivedMessages(baseMessages + userMessage)
            selectedModelKey = updatedSession.selectedModelKey
            requestMessages = updatedSession.messages
            selectedSkillIds = updatedSession.selectedSkillIds
            activeSkills = updatedSession.activeSkills
            activeMcpServerIds = updatedSession.activeMcpServerIds
            chromeEnabled = updatedSession.chromeEnabled
            remoteSession = updatedSession
            updatedSessions.add(0, updatedSession)
            persisted.copy(sessions = updatedSessions)
        }
        if (requestMessages.isEmpty()) return false
        diagnosticLogger.event(
            category = "session",
            event = "long_horizon_execute",
            sessionId = sessionId,
        )
        startTurn(
            SessionTurnRequest(
                sessionId = sessionId,
                settings = resolveModelSettings(
                    baseSettings = settings,
                    providerConfigs = providerConfigs,
                    preferredModelKey = selectedModelKey,
                    fallbackModelKey = resolveDefaultChatModelKey(settings, providerConfigs),
                ),
                requestMessages = requestMessages,
                selectedSkillIds = selectedSkillIds,
                activeSkills = activeSkills,
                activeMcpServerIds = activeMcpServerIds,
                agentModeEnabled = true,
                chromeEnabled = chromeEnabled,
                providerConfigs = providerConfigs,
            ).withRemoteFrom(remoteSession, settings),
        )
        return true
    }

    private suspend fun runSession(
        handle: SessionExecutionHandle,
        initialRequest: SessionTurnRequest,
    ) {
        var nextRequest: SessionTurnRequest? = initialRequest
        var lastCompletion: CompletionSummary? = null

        try {
            while (nextRequest != null && !handle.pauseRequested) {
                lastCompletion = executeTurn(
                    handle = handle,
                    request = nextRequest,
                )
                if (handle.pauseRequested) break
                promoteRemainingSteersToQueue(handle)
                if (handle.pauseRequested) break

                val nextQueued = pollNextQueuedInput(handle) ?: break
                nextRequest = buildQueuedTurnRequest(
                    handle = handle,
                    queuedInput = nextQueued.message,
                )
            }
        } finally {
            deactivateAssistantCheckpoint(handle, handle.activeResponseIdentity)
            clearPendingInputs(handle)
            runCatching { activeTurnStore.clear(handle.sessionId) }
            if (executionHandles.remove(handle.sessionId, handle)) {
                updateExecutionState(handle.sessionId) {
                    it.copy(
                        sessionId = handle.sessionId,
                        isRunning = false,
                        pendingToolInvocations = emptyList(),
                        pendingResponseBlocks = emptyList(),
                        pendingAssistantText = "",
                        pendingStatusText = "",
                        pendingStatusDetail = "",
                        pendingInputs = emptyList(),
                        activeResponseGroupId = null,
                        activeResponseMessageIdPrefix = null,
                        activeTurnStartedAtMillis = null,
                        activeTurnInteractionClock = TurnActivityClock(),
                        replayedUserText = "",
                    )
                }
            }

            if (
                !handle.pauseRequested &&
                lastCompletion != null &&
                currentSettings.value.notifyOnTaskCompletion &&
                !appForegroundTracker.isForeground.value
            ) {
                notificationController.notifyCompletion(
                    sessionId = handle.sessionId,
                    sessionTitle = lastCompletion.sessionTitle,
                    summary = lastCompletion.summary,
                    failed = lastCompletion.outcome == SessionTurnOutcome.Failure,
                )
            }
        }
    }

    private suspend fun executeTurn(
        handle: SessionExecutionHandle,
        request: SessionTurnRequest,
    ): CompletionSummary {
        val turnStartedAtMillis = System.currentTimeMillis()
        val turnId = "turn-$turnStartedAtMillis"
        kira.ditto.browser.BrowserTopicGraph.bindSession(handle.sessionId)
        if (request.agentModeEnabled && request.remoteMachineId.isBlank()) {
            agentModeLearningRuntime?.beginTurn(
                sessionId = handle.sessionId,
                turnId = turnId,
                goal = request.requestMessages
                    .lastOrNull { it.author == MessageAuthor.User }
                    ?.text
                    .orEmpty(),
            )
        }
        val responseIdentity = activateAssistantResponse(handle)
        updateExecutionState(handle.sessionId) { current ->
            current.copy(
                activeResponseGroupId = responseIdentity.responseGroupId,
                activeResponseMessageIdPrefix = responseIdentity.messageIdPrefix,
                isPreparingWorkspace = true,
                pendingToolInvocations = emptyList(),
                pendingResponseBlocks = emptyList(),
                pendingAssistantText = "",
                pendingStatusText = "",
                pendingStatusDetail = "",
                activeTurnStartedAtMillis = null,
                activeTurnInteractionClock = TurnActivityClock(),
            )
        }
        var firstAssistantTokenAtMillis: Long? = null
        var thinkingStartedAtMillis: Long? = null
        fun markThinkingStarted() {
            if (thinkingStartedAtMillis != null) return
            val startedAt = System.currentTimeMillis()
            thinkingStartedAtMillis = startedAt
            updateExecutionState(handle.sessionId) { current ->
                current.copy(
                    isPreparingWorkspace = false,
                    activeTurnStartedAtMillis = current.activeTurnStartedAtMillis ?: startedAt,
                )
            }
        }
        fun markFirstStreamEvent() {
            if (firstAssistantTokenAtMillis != null) return
            firstAssistantTokenAtMillis = System.currentTimeMillis()
        }
        diagnosticLogger.event(
            category = "session",
            event = "turn_start",
            sessionId = handle.sessionId,
            turnId = turnId,
            details = mapOf(
                "session_title" to resolveSessionTitle(handle.sessionId),
                "provider" to request.settings.piProviderId,
                "model" to request.settings.modelId,
                "base_url" to DiagnosticRedactor.sanitizedBaseUrl(request.settings.baseUrl),
            ),
        )
        val selfManagementTool = AetherSelfManagementTool(
            settingsRepository = settingsRepository,
            extensionsRepository = extensionsRepository,
            skillManager = skillManager,
            rootSetupController = rootSetupController,
            agentModeController = agentModeController,
            scheduledTaskManager = scheduledTaskManager,
            piKernelBridge = piKernelBridge,
            sessionId = handle.sessionId,
            diagnosticLogger = diagnosticLogger,
        )

        updateExecutionState(handle.sessionId) {
            it.copy(
                sessionId = handle.sessionId,
                isRunning = true,
                isPreparingWorkspace = true,
                pendingToolInvocations = emptyList(),
                pendingResponseBlocks = emptyList(),
                pendingAssistantText = "",
                pendingStatusText = "",
                pendingStatusDetail = "",
                activeTurnStartedAtMillis = null,
                activeTurnInteractionClock = TurnActivityClock(),
            )
        }

        return try {
            val resolvedAvailableSkills = currentExtensionsState.value.installedSkills
                .filter { it.isEnabled }
                .sortedBy { it.name.lowercase() }
            val explicitlySelectedSkills = resolveSelectedActiveSkills(
                selectedSkillIds = request.selectedSkillIds,
            )
            val resolvedSkillSelection = resolveTurnSkillSelection(
                explicitActiveSkills = explicitlySelectedSkills,
                implicitActiveSkills = emptyList(),
            )
            val resolvedActiveSkills = resolvedSkillSelection.activeSkills
            updateSessionSelections(
                sessionId = handle.sessionId,
                selectedSkillIds = emptyList(),
                activeSkills = emptyList(),
                activeMcpServerIds = emptyList(),
            )

            val workspaceDirectory = if (request.remoteMachineId.isNotBlank()) {
                request.remoteCwd.ifBlank { "." }
            } else {
                workspaceFileBridge.workspaceDirectory(request.workspaceId)
            }
            val (mirroredSkillPaths, agentSessionEntity, thinkingCaches, preopen) = coroutineScope {
                val skillsDeferred = async {
                    if (request.remoteMachineId.isNotBlank()) {
                        emptyList()
                    } else {
                        skillRuntimeMirror.sync(resolvedAvailableSkills)
                    }
                }
                val metadataDeferred = async {
                    chatRepository.getAgentSessionMetadata(handle.sessionId)
                }
                val cachesDeferred = async {
                    settingsRepository.loadThinkingLevelMapsCache() to
                        settingsRepository.loadReasoningModelsCache()
                }
                val preopenDeferred = async {
                    if (request.agentModeEnabled && request.remoteMachineId.isBlank()) {
                        val goal = request.requestMessages
                            .lastOrNull { it.author == MessageAuthor.User }
                            ?.text
                            .orEmpty()
                        agentModeController.preopenForGoal(request.settings, goal)
                    } else {
                        null
                    }
                }
                AgentModeTurnPrep(
                    skillsDeferred.await(),
                    metadataDeferred.await(),
                    cachesDeferred.await(),
                    preopenDeferred.await(),
                )
            }
            val isRemoteTurn = request.remoteMachineId.isNotBlank() ||
                isRemoteAgentPath(agentSessionEntity?.jsonlPath.orEmpty())
            val agentSessionMetadata = agentSessionEntity?.takeIf { metadata ->
                isRemoteAgentPath(metadata.jsonlPath) ||
                    validateAgentSessionFile(metadata.piSessionId, metadata.jsonlPath)
            }
            val (cachedThinkingLevelMaps, cachedReasoningModels) = thinkingCaches
            val activeRuntimeId = if (isRemoteTurn) {
                LocalRuntimeId.Alpine
            } else {
                LocalRuntimeId.fromStorage(agentSessionMetadata?.runtime)
                    ?: runtimeRouter.runtimeFor(request.settings, null)?.id
                    ?: request.settings.defaultRuntimeId
                    ?: LocalRuntimeId.Alpine
            }
            val runtimeWorkspaceDirectory = if (isRemoteTurn) {
                request.remoteCwd.ifBlank { "." }
            } else {
                runtimeRouter.runtimeWorkspaceDirectory(
                    settings = request.settings,
                    termuxWorkspaceDirectory = workspaceDirectory,
                )
            }
            val reasoningTraceToolRoutingEnabled = request.settings.supportsVisibleReasoningTrace()
            var providerRequestCheckpoint: ProviderRequestCheckpoint? = null
            var sawStreamingUsageUpdate = false
            val emitToolEvent: suspend (AgentToolEvent) -> Unit = { rawEvent ->
                if (!handle.pauseRequested) {
                    markFirstStreamEvent()
                    // Distil first, and outside the agent-mode branch: browser research happens in
                    // ordinary chat, where the phone-learning runtime is not even constructed. The
                    // memo is stripped from the event before anything downstream sees it, so the
                    // block reaches the ledger and never the reader.
                    val event = captureBrowserTaskMemo(handle.sessionId, rawEvent)
                    if (request.agentModeEnabled) {
                        val decorated = agentModeLearningRuntime?.observeNativeAgentEvent(
                            handle.sessionId,
                            event,
                        )
                        agentModeLearningRuntime?.consumeWatchSchedule()?.let { action ->
                            scope.launch { applyGuiWatchSchedule(action) }
                        }
                        val forwarded = if (!decorated.isNullOrBlank()) {
                            event.copy(outputJson = decorated)
                        } else {
                            event
                        }
                        handleToolEvent(
                            handle = handle,
                            event = forwarded,
                            reasoningTraceToolRoutingEnabled = reasoningTraceToolRoutingEnabled,
                        )
                    } else {
                        handleToolEvent(
                            handle = handle,
                            event = event,
                            reasoningTraceToolRoutingEnabled = reasoningTraceToolRoutingEnabled,
                        )
                    }
                }
            }
            // Agent Mode display (and a unique named app) is started in
            // parallel with workspace prep so the phone subagent wakes up
            // already looking at the target app.

            diagnosticLogger.event(
                category = "session",
                event = "pi_agent_runner_start",
                sessionId = handle.sessionId,
                turnId = turnId,
                details = mapOf(
                    "runtime_id" to activeRuntimeId.storageValue,
                    "workspace_directory" to runtimeWorkspaceDirectory,
                    "session_file" to agentSessionMetadata?.jsonlPath.orEmpty(),
                    "message_count" to request.requestMessages.size,
                ),
            )
            val modelKey = thinkingCatalogKey(request.settings.piProviderId, request.settings.modelId)
            val thinkingLevelMap = cachedThinkingLevelMaps[modelKey].orEmpty()
            val isReasoningModel = modelKey in cachedReasoningModels
            refreshBrowserDossier(handle.sessionId)
            val result = piAgentRunner.runTurn(
                settings = request.settings,
                messages = buildRequestMessages(
                    messages = request.requestMessages,
                    settings = request.settings,
                    sessionId = handle.sessionId,
                    agentModeEnabled = request.agentModeEnabled,
                    preopen = preopen,
                ),
                workspaceDirectory = runtimeWorkspaceDirectory,
                termuxWorkspaceDirectory = workspaceDirectory,
                skillPaths = mirroredSkillPaths,
                activeSkills = resolvedActiveSkills,
                selfManagementTool = selfManagementTool,
                agentModeEnabled = request.agentModeEnabled,
                chromeEnabled = request.chromeEnabled,
                mcpServers = if (isRemoteTurn) JSONArray() else acpMcpServersFor(request),
                sessionId = handle.sessionId,
                sessionFile = agentSessionMetadata?.jsonlPath.orEmpty(),
                runtimeId = activeRuntimeId,
                thinkingLevelMap = thinkingLevelMap,
                isReasoningModel = isReasoningModel,
                remoteMachineId = request.remoteMachineId.ifBlank {
                    parseRemoteMachineIdFromAgentPath(agentSessionMetadata?.jsonlPath.orEmpty())
                },
                remoteBaseUrl = request.remoteBaseUrl,
                remoteToken = request.remoteToken,
                remoteKimiSessionId = request.remoteKimiSessionId.ifBlank {
                    agentSessionMetadata?.piSessionId.orEmpty()
                        .takeIf { !it.startsWith("remote-pending-") }
                        .orEmpty()
                },
                remoteCwd = request.remoteCwd,
                onToolEvent = emitToolEvent,
                onToolProgress = emitToolEvent,
                onAssistantReasoningDelta = { delta ->
                    if (handle.pauseRequested) return@runTurn
                    if (delta.isEmpty()) return@runTurn
                    markFirstStreamEvent()
                    markThinkingStarted()
                    handle.finishDirectReasoningSummaryChunk()
                    appendReasoningDelta(
                        handle = handle,
                        delta = delta,
                    )
                },
                onAssistantReasoningSummaryDelta = { delta ->
                    if (handle.pauseRequested) return@runTurn
                    if (delta.isEmpty()) return@runTurn
                    markFirstStreamEvent()
                    appendDirectReasoningSummaryDelta(
                        handle = handle,
                        delta = delta,
                    )
                },
                onAssistantTextDelta = { delta ->
                    if (handle.pauseRequested) return@runTurn
                    if (delta.isEmpty()) return@runTurn
                    markFirstStreamEvent()
                    markThinkingStarted()
                    handle.finishDirectReasoningSummaryChunk()
                    completeActiveReasoning(
                        handle = handle,
                        trigger = ReasoningCompletionTrigger.BodyStarted,
                    )
                    updateExecutionState(
                        handle.sessionId,
                        ExecutionPublish.Coalesced,
                    ) { current ->
                        val pendingResponseBlocks = appendAssistantResponseText(
                            handle = handle,
                            blocks = completePendingReconnectBlocks(current.pendingResponseBlocks),
                            delta = delta,
                        ) { handle.nextPendingBlockId("pending-text") }
                        current.copy(
                            pendingStatusText = "",
                            pendingStatusDetail = "",
                            pendingAssistantText = pendingTrailingAssistantText(pendingResponseBlocks),
                            pendingResponseBlocks = pendingResponseBlocks,
                        )
                    }
                },
                onAssistantTextReset = {
                    if (handle.pauseRequested) return@runTurn
                    updateExecutionState(handle.sessionId) { current ->
                        if (current.pendingAssistantText.isEmpty()) {
                            current
                        } else {
                            current.copy(pendingAssistantText = "")
                        }
                    }
                },
                onAssistantRequestStarted = {
                    if (!handle.pauseRequested) {
                        markThinkingStarted()
                        val current = _executionStates.value[handle.sessionId]
                            ?: SessionExecutionState(sessionId = handle.sessionId)
                        providerRequestCheckpoint = handle.providerRequestCheckpoint(current)
                    }
                },
                onAssistantResponseReset = {
                    if (!handle.pauseRequested) {
                        providerRequestCheckpoint?.let { checkpoint ->
                            handle.restoreProviderRequestCheckpoint(checkpoint)
                            updateExecutionState(handle.sessionId) { current ->
                                current.copy(
                                    pendingToolInvocations = checkpoint.pendingToolInvocations,
                                    pendingResponseBlocks = checkpoint.pendingResponseBlocks,
                                    pendingAssistantText = checkpoint.pendingAssistantText,
                                )
                            }
                        }
                    }
                },
                onStreamingStatus = { status ->
                    if (handle.pauseRequested) return@runTurn
                    if (status?.text?.startsWith("Reconnecting", ignoreCase = true) == true) {
                        completeActiveReasoning(
                            handle = handle,
                            trigger = ReasoningCompletionTrigger.BodyStarted,
                        )
                    }
                    updateExecutionState(handle.sessionId) { current ->
                        val text = status?.text.orEmpty()
                        if (text.startsWith("Reconnecting", ignoreCase = true)) {
                            val last = current.pendingResponseBlocks.lastOrNull()
                            val blocks = if (last is AssistantResponseBlock.Status &&
                                last.text.startsWith("Reconnecting", ignoreCase = true)
                            ) {
                                current.pendingResponseBlocks.dropLast(1) + last.copy(
                                    text = text,
                                    detail = status?.detail.orEmpty(),
                                )
                            } else {
                                current.pendingResponseBlocks + AssistantResponseBlock.Status(
                                    id = handle.nextPendingBlockId("pending-status"),
                                    text = text,
                                    detail = status?.detail.orEmpty(),
                                )
                            }
                            current.copy(
                                pendingResponseBlocks = blocks,
                                pendingStatusText = "",
                                pendingStatusDetail = "",
                            )
                        } else {
                            current.copy(
                                pendingResponseBlocks = completePendingReconnectBlocks(current.pendingResponseBlocks),
                                pendingStatusText = text,
                                pendingStatusDetail = status?.detail.orEmpty(),
                            )
                        }
                    }
                },
                onSessionEvent = onSessionEvent@{ name, eventPayload ->
                    if (handle.pauseRequested) return@onSessionEvent
                    if (name == "usage_update" && parseSessionContextUsage(eventPayload) != null) {
                        sawStreamingUsageUpdate = true
                    }
                    handleKimiSessionEvent(handle, name, eventPayload)
                },
                pollInjectedUserMessages = {
                    if (handle.pauseRequested) return@runTurn emptyList()
                    val drained = drainSteerInputs(handle)
                    commitSteerUi(handle, drained)
                    drained.map { buildSteerRequestMessage(it.message, request.settings) }
                },
            )
            diagnosticLogger.event(
                category = "session",
                event = "pi_agent_runner_end",
                sessionId = handle.sessionId,
                turnId = turnId,
                details = mapOf(
                    "success" to result.isSuccess,
                    "error" to (result.exceptionOrNull()?.message ?: ""),
                ),
            )
            if (handle.pauseRequested) {
                return finalizePausedTurn(
                    handle = handle,
                    snapshot = _executionStates.value[handle.sessionId] ?: SessionExecutionState(sessionId = handle.sessionId),
                )
            }

            completeActiveReasoning(
                handle = handle,
                trigger = ReasoningCompletionTrigger.TurnFinished,
            )
            val turnClock = _executionStates.value[handle.sessionId]?.activeTurnInteractionClock
            val thoughtDurationMillis = thinkingStartedAtMillis?.let { startedAt ->
                turnClock?.activeElapsedMillis(startedAt, System.currentTimeMillis())
                    ?: (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
            }
            val turnCompletedAtMillis = System.currentTimeMillis()
            val estimatedTokenUsage = estimateRequestTokenUsage(request)
            val completion: CompletionSummary = result.fold(
                onSuccess = { turnResult ->
                    if (request.agentModeEnabled) {
                        agentModeLearningRuntime?.completeTurn(
                            sessionId = handle.sessionId,
                            outerTurnSucceeded = true,
                            summary = turnResult.assistantText,
                        )
                    }
                    val estimatedOutputTokens = approximateReasoningTokenCount(turnResult.assistantText).toLong()
                    val estimatedReasoningTokens = approximateReasoningTokenCount(
                        turnResult.reasoningText,
                    ).toLong().takeIf { it > 0L }
                    val resolvedTokenUsage = turnResult.tokenUsage?.let { usage ->
                        usage.copy(
                            outputTokens = usage.outputTokens ?: estimatedOutputTokens.takeIf { it > 0L },
                            reasoningTokens = usage.reasoningTokens ?: estimatedReasoningTokens,
                        ).withMissingTotalResolved()
                    } ?: estimatedTokenUsage.copy(
                        outputTokens = estimatedOutputTokens.takeIf { it > 0L },
                        reasoningTokens = estimatedReasoningTokens,
                        totalTokens = (estimatedTokenUsage.inputTokens ?: 0L) +
                            estimatedOutputTokens +
                            (estimatedReasoningTokens ?: 0L),
                    )
                    // `contextUsage` accepts `usage_update` and nothing else.
                    //
                    // This used to fall back to `resolvedTokenUsage.totalTokens` when no streaming
                    // update arrived, which is a different quantity in the same field: the CLI's
                    // `usage_update` reports `agent.getContext().tokenCount` - how full the window
                    // is - while `totalTokens` is one turn's billing (inputOther + cacheCreation +
                    // cacheRead + output). They are not comparable, and once folding shipped they
                    // stopped even being close: a folded prompt bills ~5.5k per turn while the
                    // context keeps growing, so the capsule read low exactly when it mattered. On
                    // turns with no usage at all it was `text.length / 4`, a third quantity again.
                    //
                    // Nothing replaces it here. A turn with no usage_update leaves the previous
                    // occupancy on screen (`lastKnownContextUsage` in ConversationUi), which is
                    // stale but true, rather than substituting a number that is fresh and wrong.
                    // The billing figure is still recorded for the usage panel; it just no longer
                    // impersonates occupancy.
                    val userText = request.requestMessages.lastOrNull { message ->
                        message.author == MessageAuthor.User &&
                            message.displayKind == MessageDisplayKind.Standard
                    }?.text.orEmpty()
                    val sealedText = kira.ditto.browser.ensureBrowserResumeAnswer(
                        markdown = kira.ditto.browser.ensureBrowserActAnswer(
                            markdown = turnResult.assistantText,
                            userText = userText,
                            officialUrl = kira.ditto.browser.BrowserDesk.officialActUrl(userText),
                        ),
                        userTakeoverReason = kira.ditto.browser.BrowserDesk.state.value.userTakeoverReason,
                    )
                    val turnStopReason = runCatching {
                        JSONObject(turnResult.providerPayloadJson).optString("stopReason")
                    }.getOrDefault("")
                    val responseBlocks = currentAssistantResponseBlocks(handle.sessionId).let { blocks ->
                        val prepared = if (request.settings.reasoningEffort == "off") {
                            blocks.sanitizedForReasoningOff()
                        } else {
                            blocks
                        }
                        val execution = _executionStates.value[handle.sessionId]
                        val planMode = execution?.agentConfig?.modeId?.equals("plan", ignoreCase = true) == true ||
                            request.settings.kimiPermissionMode.equals("plan", ignoreCase = true)
                        val hasPlanEntries = execution?.planEntries.orEmpty().isNotEmpty()
                        // Say what actually happened. The old text always blamed context size and
                        // told the user to compact, which measured wrong every time it fired: two
                        // turns had 15 and 612 input tokens, and a third was cancelled by the user.
                        // Compaction is also automatic now, so the advice was stale on top of that.
                        val emptyReplyText = when {
                            planMode && hasPlanEntries ->
                                application.getString(R.string.chat_empty_model_reply_plan)
                            turnStopReason == "cancelled" ->
                                application.getString(R.string.chat_reply_cancelled)
                            turnResult.reasoningText.isNotBlank() ->
                                application.getString(R.string.chat_empty_model_reply_thinking_only)
                            else -> application.getString(R.string.chat_empty_model_reply)
                        }
                        ensureVisibleAssistantReply(
                            blocks = ensureAssistantResponseFinalText(
                                blocks = prepared,
                                finalText = sealedText,
                            ) { handle.nextPendingBlockId("agent-text") },
                            emptyReplyText = emptyReplyText,
                        )
                    }
                    val visibleThoughtDurationMillis = thoughtDurationMillis.takeIf {
                        request.settings.reasoningEffort != "off"
                    }
                    diagnosticLogger.event(
                        category = "session",
                        event = "turn_model_success",
                        sessionId = handle.sessionId,
                        turnId = turnId,
                        details = mapOf(
                            "reply_chars" to sealedText.length,
                            "duration_millis" to thoughtDurationMillis,
                        ),
                    )
                    // A turn that ends with nothing to show has several unrelated causes - a
                    // cancel, a contradictory prompt the model argued with instead of answering,
                    // a tool call that never produced text. Nothing on disk told them apart, so
                    // the placeholder was the only evidence and it named the wrong one.
                    if (sealedText.isBlank()) {
                        diagnosticLogger.event(
                            category = "session",
                            event = "turn_empty_reply",
                            sessionId = handle.sessionId,
                            turnId = turnId,
                            details = mapOf(
                                "stop_reason" to turnStopReason,
                                "reasoning_chars" to turnResult.reasoningText.length,
                                "input_tokens" to (resolvedTokenUsage.inputTokens ?: 0L),
                                "duration_millis" to thoughtDurationMillis,
                            ),
                        )
                    }
                    appendAgentMessage(
                        sessionId = handle.sessionId,
                        blocks = responseBlocks,
                        thoughtDurationMillis = visibleThoughtDurationMillis,
                        outcome = if (turnResult.assistantText.isBlank() && sealedText.isBlank()) {
                            SessionTurnOutcome.Failure
                        } else {
                            SessionTurnOutcome.Success
                        },
                        tokenUsage = resolvedTokenUsage,
                        tokenUsageSource = if (turnResult.tokenUsage != null) "api" else "estimated",
                        turnStartedAtMillis = turnStartedAtMillis,
                        firstTokenAtMillis = firstAssistantTokenAtMillis,
                        turnCompletedAtMillis = turnCompletedAtMillis,
                        inputMessageCount = request.requestMessages.size,
                        userMessageCount = request.requestMessages.count { it.author == MessageAuthor.User },
                        providerPayloadJson = turnResult.providerPayloadJson,
                        handle = handle,
                    ).copy(
                        piSessionId = turnResult.piSessionId,
                        piSessionFile = turnResult.piSessionFile,
                        piRuntime = turnResult.runtime,
                        piEntryIds = turnResult.piEntryIds,
                    )
                },
                onFailure = { throwable ->
                    if (request.agentModeEnabled) {
                        agentModeLearningRuntime?.completeTurn(
                            sessionId = handle.sessionId,
                            outerTurnSucceeded = false,
                            summary = throwable.message.orEmpty(),
                        )
                    }
                    val responseBlocks = currentAssistantResponseBlocks(handle.sessionId).let { blocks ->
                        if (request.settings.reasoningEffort == "off") {
                            blocks.sanitizedForReasoningOff()
                        } else {
                            blocks
                        }
                    }
                    val visibleThoughtDurationMillis = thoughtDurationMillis.takeIf {
                        request.settings.reasoningEffort != "off"
                    }
                    diagnosticLogger.exception(
                        category = "session",
                        event = "turn_model_failed",
                        sessionId = handle.sessionId,
                        turnId = turnId,
                        throwable = throwable,
                        details = mapOf("duration_millis" to thoughtDurationMillis),
                    )
                    appendAgentMessage(
                        sessionId = handle.sessionId,
                        blocks = appendAssistantResponseText(
                            handle = handle,
                            blocks = responseBlocks,
                            delta = buildString {
                                if (responseBlocks.lastOrNull() is AssistantResponseBlock.Text) {
                                    append("\n\n")
                                }
                                append("Request failed: ${formatFailureMessage(throwable)}")
                            },
                        ) { handle.nextPendingBlockId("agent-text") },
                        thoughtDurationMillis = visibleThoughtDurationMillis,
                        outcome = SessionTurnOutcome.Failure,
                        tokenUsage = estimatedTokenUsage,
                        tokenUsageSource = "estimated",
                        turnStartedAtMillis = turnStartedAtMillis,
                        firstTokenAtMillis = firstAssistantTokenAtMillis,
                        turnCompletedAtMillis = System.currentTimeMillis(),
                        inputMessageCount = request.requestMessages.size,
                        userMessageCount = request.requestMessages.count { it.author == MessageAuthor.User },
                        handle = handle,
                    )
                },
            )
            usageRecorder?.recordTurn(
                sessionId = handle.sessionId,
                providerId = request.settings.piProviderId,
                modelId = request.settings.modelId,
                usage = completion.tokenUsage,
                usageSource = completion.tokenUsageSource,
                startedAtMillis = turnStartedAtMillis,
                completedAtMillis = System.currentTimeMillis(),
                firstTokenAtMillis = firstAssistantTokenAtMillis,
                messageId = completion.appendedMessageIds.lastOrNull(),
            )
            chatStateStore.flush()
            if (request.remoteMachineId.isNotBlank() && completion.piSessionId.isNotBlank()) {
                chatRepository.upsertAgentSessionMetadata(
                    chatSessionId = handle.sessionId,
                    piSessionId = completion.piSessionId,
                    jsonlPath = remoteAgentPath(request.remoteMachineId, request.remoteCwd),
                    runtime = RemoteAgentRuntime,
                )
                chatStateStore.update { persisted ->
                    val sessionIndex = persisted.sessions.indexOfFirst { it.id == handle.sessionId }
                    if (sessionIndex < 0) return@update persisted
                    val sessions = persisted.sessions.toMutableList()
                    val session = sessions[sessionIndex]
                    if (session.remoteKimiSessionId == completion.piSessionId) return@update persisted
                    sessions[sessionIndex] = session.copy(remoteKimiSessionId = completion.piSessionId)
                    persisted.copy(sessions = sessions)
                }
            } else if (completion.piSessionId.isNotBlank() && completion.piSessionFile.isNotBlank()) {
                chatRepository.upsertAgentSessionMetadata(
                    chatSessionId = handle.sessionId,
                    piSessionId = completion.piSessionId,
                    jsonlPath = completion.piSessionFile,
                    runtime = completion.piRuntime,
                )
                chatRepository.upsertAgentMessageRefs(
                    chatSessionId = handle.sessionId,
                    aetherMessageIds = completion.appendedMessageIds,
                    piEntryIds = completion.piEntryIds,
                )
            }
            _turnEvents.tryEmit(completion.toTurnEvent(handle.sessionId))
            diagnosticLogger.event(
                category = "session",
                event = "turn_end",
                sessionId = handle.sessionId,
                turnId = turnId,
                level = if (completion.outcome == SessionTurnOutcome.Failure) "warn" else "info",
                details = mapOf(
                    "outcome" to completion.outcome.name,
                    "tool_call_count" to completion.toolCallCount,
                    "distinct_tool_count" to completion.distinctToolCount,
                    "duration_millis" to completion.durationMillis,
                ),
            )
            completion
        } catch (_: CancellationException) {
            if (request.agentModeEnabled) {
                agentModeController.abortInFlightCapture()
                agentModeLearningRuntime?.cancelTurn(handle.sessionId)
            }
            diagnosticLogger.event(
                category = "session",
                event = "turn_cancelled",
                level = "warn",
                sessionId = handle.sessionId,
                turnId = turnId,
                details = mapOf("pause_finalized" to handle.pauseFinalized),
            )
            clearPendingInputs(handle)
            val completion = if (handle.pauseFinalized) {
                CompletionSummary(
                    sessionTitle = resolveSessionTitle(handle.sessionId),
                    summary = "",
                    outcome = SessionTurnOutcome.Neutral,
                    toolCallCount = 0,
                    distinctToolCount = 0,
                    toolNames = emptyList(),
                    durationMillis = null,
                )
            } else {
                finalizePausedTurn(
                    handle = handle,
                    snapshot = _executionStates.value[handle.sessionId] ?: SessionExecutionState(sessionId = handle.sessionId),
                )
            }
            if (!handle.pauseFinalized) {
                chatStateStore.flush()
                handle.pauseFinalized = true
                _turnEvents.tryEmit(completion.toTurnEvent(handle.sessionId))
            }
            completion
        } catch (failure: Throwable) {
            if (request.agentModeEnabled) {
                agentModeLearningRuntime?.completeTurn(
                    sessionId = handle.sessionId,
                    outerTurnSucceeded = false,
                    summary = failure.message.orEmpty(),
                )
            }
            diagnosticLogger.exception(
                category = "session",
                event = "turn_setup_failed",
                sessionId = handle.sessionId,
                turnId = turnId,
                throwable = failure,
            )
            val completion = appendAgentMessage(
                sessionId = handle.sessionId,
                blocks = listOf(
                    AssistantResponseBlock.Text(
                        id = handle.nextPendingBlockId("agent-text"),
                        text = "Request failed: ${formatFailureMessage(failure)}",
                    )
                ),
                thoughtDurationMillis = null,
                outcome = SessionTurnOutcome.Failure,
                turnStartedAtMillis = turnStartedAtMillis,
                firstTokenAtMillis = firstAssistantTokenAtMillis,
                turnCompletedAtMillis = System.currentTimeMillis(),
                inputMessageCount = request.requestMessages.size,
                userMessageCount = request.requestMessages.count { it.author == MessageAuthor.User },
                handle = handle,
            )
            chatStateStore.flush()
            _turnEvents.tryEmit(completion.toTurnEvent(handle.sessionId))
            completion
        } finally {
            if (request.agentModeEnabled) {
                lastAgentModeTurnSettledAtMillis.set(System.currentTimeMillis())
                agentModeController.abortInFlightCapture()
                agentModeLearningRuntime?.cancelTurn(
                    sessionId = handle.sessionId,
                    summary = "Agent Mode turn ended without a verified phone-agent report.",
                )
            }
            if (executionHandles[handle.sessionId] === handle) {
                updateExecutionState(handle.sessionId) { current ->
                    current.copy(
                        pendingToolInvocations = emptyList(),
                        pendingResponseBlocks = emptyList(),
                        pendingAssistantText = "",
                        isPreparingWorkspace = false,
                        activeResponseGroupId = null,
                        activeResponseMessageIdPrefix = null,
                        activeTurnStartedAtMillis = null,
                        activeTurnInteractionClock = TurnActivityClock(),
                    )
                }
            }
        }
    }

    private suspend fun validateAgentSessionFile(
        expectedSessionId: String,
        sessionFile: String,
    ): Boolean {
        if (expectedSessionId.isBlank() || sessionFile.isBlank()) return false
        if (isRemoteAgentPath(sessionFile)) return true
        val alpine = runtimeRouter.runtimeById(LocalRuntimeId.Alpine)
        val hostFile = if (alpine is kira.ditto.runtime.AlpineRuntime) {
            alpine.resolveAcpHostFile(sessionFile)
        } else {
            java.io.File(sessionFile)
        }
        if (!hostFile.isFile) return false
        val header = runCatching {
            JSONObject(hostFile.useLines { it.firstOrNull().orEmpty() })
        }.getOrNull() ?: return false
        return header.optString("type") == "session" && header.optString("id") == expectedSessionId
    }

    private suspend fun acpMcpServersFor(request: SessionTurnRequest): JSONArray {
        return buildAcpMcpServerArray(
            servers = mergeShippedMcpServers(currentExtensionsState.value.mcpServers),
            selectedIds = request.activeMcpServerIds,
            learningSessionId = request.sessionId,
            secrets = mcpSecretStore.asLookup(),
        )
    }

    private fun buildQueuedTurnRequest(
        handle: SessionExecutionHandle,
        queuedInput: ChatMessage,
    ): SessionTurnRequest? {
        val request = queuedTurnRequestBuilder.build(
            sessionId = handle.sessionId,
            queuedInput = queuedInput,
            baseMessages = handle.retainedMessagesSnapshot(),
            baseSettings = currentSettings.value,
            providerConfigs = currentProviderConfigs.value,
        )
        request?.let { handle.replaceRetainedMessages(it.requestMessages) }
        return request
    }

    private fun updateSessionSelections(
        sessionId: String,
        selectedSkillIds: List<String>,
        activeSkills: List<ActiveSkillContext>,
        activeMcpServerIds: List<String>,
    ) {
        chatStateStore.update { persisted ->
            val sessionIndex = persisted.sessions.indexOfFirst { it.id == sessionId }
            if (sessionIndex < 0) return@update persisted
            val updatedSessions = persisted.sessions.toMutableList()
            val session = updatedSessions.removeAt(sessionIndex)
            updatedSessions.add(
                sessionIndex.coerceAtMost(updatedSessions.size),
                session.copy(
                    selectedSkillIds = selectedSkillIds,
                    activeSkills = activeSkills,
                    activeMcpServerIds = activeMcpServerIds,
                ),
            )
            persisted.copy(sessions = updatedSessions)
        }
    }

    private suspend fun resolveSelectedActiveSkills(
        selectedSkillIds: List<String>,
    ): List<ActiveSkillContext> {
        if (selectedSkillIds.isEmpty()) return emptyList()
        val installedSkillsById = currentExtensionsState.value.installedSkills
            .filter { it.isEnabled }
            .associateBy { it.id }
        return buildList {
            selectedSkillIds.distinct().forEach { skillId ->
                val installedSkill = installedSkillsById[skillId] ?: return@forEach
                val activeSkill = skillManager.buildActiveSkillContext(installedSkill)
                    .getOrElse { return@forEach }
                add(activeSkill)
            }
        }
    }

    private fun resolveTurnSkillSelection(
        explicitActiveSkills: List<ActiveSkillContext>,
        implicitActiveSkills: List<ActiveSkillContext>,
    ): TurnSkillSelection = resolveTurnSkillSelectionForTest(
        explicitActiveSkills = explicitActiveSkills,
        implicitActiveSkills = implicitActiveSkills,
    )

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

    private fun appendAgentMessage(
        sessionId: String,
        blocks: List<AssistantResponseBlock>,
        thoughtDurationMillis: Long?,
        outcome: SessionTurnOutcome,
        tokenUsage: LlmTokenUsage? = null,
        tokenUsageSource: String = "unavailable",
        turnStartedAtMillis: Long? = null,
        firstTokenAtMillis: Long? = null,
        turnCompletedAtMillis: Long? = null,
        inputMessageCount: Int = 0,
        userMessageCount: Int = 0,
        providerPayloadJson: String = "",
        statusText: String = "",
        statusDetail: String = "",
        handle: SessionExecutionHandle? = null,
        baseMessages: List<ChatMessage> = handle?.retainedMessagesSnapshot().orEmpty(),
    ): CompletionSummary {
        var sessionTitle = resolveSessionTitle(sessionId)
        var replySummary = blocks.lastOrNull()
            ?.let(::assistantResponseBlockSummaryText)
            .orEmpty()

        val normalizedBlocks = normalizeAssistantResponseBlocks(blocks)
        val responseIdentity = handle?.activeResponseIdentity
        val appendedMessages = assistantMessagesForBlocks(
            normalizedBlocks = normalizedBlocks,
            thoughtDurationMillis = thoughtDurationMillis,
            assistantActionsHidden = false,
            isIncomplete = false,
            responseIdentity = responseIdentity,
            messageCreatedAtMillis = turnCompletedAtMillis,
            usageStatistics = buildChatUsageStatistics(
                tokenUsage = tokenUsage,
                tokenUsageSource = tokenUsageSource,
                turnStartedAtMillis = turnStartedAtMillis,
                firstTokenAtMillis = firstTokenAtMillis,
                turnCompletedAtMillis = turnCompletedAtMillis,
            ),
            providerPayloadJson = providerPayloadJson,
            statusText = statusText,
            statusDetail = statusDetail,
        )

        deactivateAssistantCheckpoint(handle, responseIdentity)
        chatStateStore.update { persisted ->
            val sessionIndex = persisted.sessions.indexOfFirst { it.id == sessionId }
            if (sessionIndex < 0) return@update persisted

            val updatedSessions = persisted.sessions.toMutableList()
            val session = updatedSessions.removeAt(sessionIndex)
            val currentMessages = session.messages.ifEmpty { baseMessages }
            val messagesWithoutCheckpoint = responseIdentity?.let { identity ->
                currentMessages.filterNot { it.responseGroupId == identity.responseGroupId }
            } ?: currentMessages
            val updatedSession = session.withDerivedMessages(
                messagesWithoutCheckpoint + appendedMessages
            )
            handle?.replaceRetainedMessages(updatedSession.messages)
            sessionTitle = updatedSession.title
            replySummary = updatedSession.preview
            updatedSessions.add(0, updatedSession)
            persisted.copy(sessions = updatedSessions)
        }

        val toolInvocations = normalizedBlocks.toolInvocations()
        val toolNames = toolInvocations.map { it.toolName }.distinct()
        return CompletionSummary(
            sessionTitle = sessionTitle,
            summary = replySummary,
            outcome = outcome,
            toolCallCount = toolInvocations.size,
            distinctToolCount = toolNames.size,
            toolNames = toolNames,
            durationMillis = thoughtDurationMillis,
            tokenUsage = tokenUsage,
            tokenUsageSource = tokenUsageSource,
            inputMessageCount = inputMessageCount,
            userMessageCount = userMessageCount,
            appendedMessageIds = appendedMessages.map(ChatMessage::id),
        )
    }

    private suspend fun handleToolEvent(
        handle: SessionExecutionHandle,
        event: AgentToolEvent,
        reasoningTraceToolRoutingEnabled: Boolean,
    ) {
        val nowUptime = SystemClock.uptimeMillis()
        val nowMillis = System.currentTimeMillis()
        val existingInvocation = _executionStates.value[handle.sessionId]
            ?.pendingToolInvocations
            ?.firstOrNull { it.id == event.id }
        val pendingTools = _executionStates.value[handle.sessionId]
            ?.pendingToolInvocations
            .orEmpty()
        val incomingName = preferStableOfficialCronToolName(
            incoming = event.name,
            existing = existingInvocation?.toolName,
        ).ifBlank { existingInvocation?.toolName.orEmpty() }
        val incomingArgs = event.argumentsJson
            .takeIf { it.isNotBlank() && it != "{}" }
            ?: existingInvocation?.argumentsJson?.takeIf { it.isNotBlank() }
            ?: event.argumentsJson
        val incomingKind = event.kind.ifBlank { existingInvocation?.toolKind.orEmpty() }
        val punchedWeb = punchThroughWebToBrowserAgent(
            toolName = incomingName,
            argumentsJson = incomingArgs,
            toolKind = incomingKind,
            skipBecauseNativeBrowserChild = pendingHasNativeBrowserSubagent(
                invocations = pendingTools,
                exceptId = event.id,
            ) || pendingHasPunchedBrowserAgent(
                invocations = pendingTools,
                exceptId = event.id,
            ),
            topicIdHint = lastPunchedBrowserTopicId(pendingTools),
            existingToolName = existingInvocation?.toolName.orEmpty(),
            existingArgumentsJson = existingInvocation?.argumentsJson.orEmpty(),
            existingToolKind = existingInvocation?.toolKind.orEmpty(),
            operateUrl = kira.ditto.browser.BrowserDesk.operateUrlThisTurn(),
            lookupThenOperate = kira.ditto.browser.BrowserDesk.isLookupThenOperateThisTurn(),
        )
        var invocation = ChatToolInvocation(
            id = event.id,
            // Completion/delta events may carry no title or arguments at all
            // (ACP tool_call_update omits them); keep the start event's values
            // so subagent launches stay parseable after they finish.
            toolName = punchedWeb.toolName,
            argumentsJson = punchedWeb.argumentsJson,
            outputJson = event.outputJson.orEmpty(),
            isRunning = event.isRunning ?: (event.outputJson == null),
            startedAtUptimeMillis = existingInvocation?.startedAtUptimeMillis ?: nowUptime,
            completedAtUptimeMillis = if (event.isRunning == true || event.outputJson == null) {
                null
            } else {
                existingInvocation?.completedAtUptimeMillis ?: nowUptime
            },
            startedAtMillis = existingInvocation?.startedAtMillis ?: nowMillis,
            completedAtMillis = if (event.isRunning == true || event.outputJson == null) {
                null
            } else {
                existingInvocation?.completedAtMillis ?: nowMillis
            },
            timelineOrder = existingInvocation?.timelineOrder ?: 0L,
            toolKind = punchedWeb.toolKind,
            diffs = event.diffsJson?.let(::parseToolDiffs) ?: existingInvocation?.diffs.orEmpty(),
            guiStepsJson = agentModeLearningRuntime?.guiStepsJsonForToolCall(event.id)
                .orEmpty()
                .ifBlank { existingInvocation?.guiStepsJson.orEmpty() },
        )
        if (event.outputJson == null) {
            flushActiveReasoningSummary(
                handle = handle,
            )
            handle.finishDirectReasoningSummaryChunk()
        }
        // Goal tool calls carry no dedicated ACP event; fold their results into
        // the session goal snapshot once the call completes.
        val goalToolKind = if (event.outputJson != null && event.isRunning != true) {
            classifyGoalToolCall(invocation.toolName)
        } else {
            null
        }
        val currentBlocks = _executionStates.value[handle.sessionId]?.pendingResponseBlocks.orEmpty()
        val shouldRouteToolIntoReasoning =
            handle.activeReasoningBlockId != null ||
                reasoningTraceToolRoutingEnabled ||
                currentBlocks.any { it is AssistantResponseBlock.Reasoning }
        var reasoningBlockId = handle.activeReasoningBlockId
        if (reasoningBlockId == null && shouldRouteToolIntoReasoning) {
            reasoningBlockId = handle.nextPendingBlockId("pending-reasoning")
            handle.startReasoningBlock(reasoningBlockId, nowMillis)
        }
        if (shouldRouteToolIntoReasoning && invocation.timelineOrder <= 0L) {
            invocation = invocation.copy(timelineOrder = handle.nextReasoningTimelineOrder())
        }
        val todoEntries = parseSessionPlanEntriesFromTool(
            toolName = invocation.toolName,
            toolKind = invocation.toolKind,
            argumentsJson = invocation.argumentsJson,
            outputJson = invocation.outputJson.takeIf { it.isNotBlank() },
        )
        updateExecutionState(handle.sessionId) { current ->
            val targetReasoningBlockId = reasoningBlockId
            val currentBlocks = completePendingReconnectBlocks(current.pendingResponseBlocks)
            val pendingToolInvocations = upsertToolInvocation(
                current.pendingToolInvocations,
                invocation,
            )
            val blocksWithReasoningTrace = if (
                targetReasoningBlockId != null &&
                currentBlocks.none { it is AssistantResponseBlock.Reasoning && it.id == targetReasoningBlockId }
            ) {
                currentBlocks + AssistantResponseBlock.Reasoning(
                    id = targetReasoningBlockId,
                    trace = ReasoningTrace(
                        id = targetReasoningBlockId,
                        latestStatusText = formatReasoningToolStatus(invocation),
                        startedAtMillis = nowMillis,
                    ),
                )
            } else {
                currentBlocks
            }
            val pendingResponseBlocks = upsertAssistantResponseToolInvocation(
                blocks = blocksWithReasoningTrace,
                toolInvocation = invocation,
                reasoningBlockId = targetReasoningBlockId,
            ) { handle.nextPendingBlockId("pending-tools") }
            val planned = todoEntries?.let { current.withPlanEntries(it) } ?: current
            planned.copy(
                pendingToolInvocations = pendingToolInvocations,
                pendingResponseBlocks = pendingResponseBlocks,
                goalSnapshot = goalToolKind?.let { kind ->
                    reduceGoalToolResult(
                        current = planned.goalSnapshot,
                        kind = kind,
                        argumentsJson = invocation.argumentsJson,
                        outputJson = invocation.outputJson,
                        nowMillis = nowMillis,
                    )
                } ?: planned.goalSnapshot,
            )
        }
        if (event.outputJson == null && kira.ditto.browser.looksLikeBrowserSwarm(invocation.toolName, invocation.argumentsJson)) {
            kira.ditto.browser.BrowserTopicGraph.bindSession(handle.sessionId)
            kira.ditto.browser.BrowserTopicGraph.noteBrowserSwarm(
                sessionId = handle.sessionId,
                toolName = invocation.toolName,
                argumentsJson = invocation.argumentsJson,
            )
            kira.ditto.browser.AetherBrowserRuntime.preopenTopicWorkspaces(handle.sessionId)
        }
        if (event.isRunning != true) {
            scope.launch(Dispatchers.IO) {
                runCatching {
                    ingestOfficialCronTool(
                        scheduledTaskManager = scheduledTaskManager,
                        kimiCronStore = kimiCronStore,
                        toolName = invocation.toolName,
                        argumentsJson = invocation.argumentsJson,
                        outputJson = invocation.outputJson,
                    )
                }
            }
        }
    }

    fun syncLiveGuiSteps(sessionId: String, stepsByToolCall: Map<String, List<PhoneGuiStepUi>>) {
        if (sessionId.isBlank() || stepsByToolCall.isEmpty()) return
        val encoded = stepsByToolCall.mapValues { (_, steps) -> encodePhoneGuiSteps(steps) }
            .filterValues { it.isNotBlank() }
        if (encoded.isEmpty()) return
        if (_executionStates.value.containsKey(sessionId)) {
            updateExecutionState(sessionId) { current ->
                val tools = current.pendingToolInvocations.map { it.withGuiSteps(encoded) }
                val blocks = current.pendingResponseBlocks.map { it.withGuiSteps(encoded) }
                if (tools == current.pendingToolInvocations && blocks == current.pendingResponseBlocks) {
                    current
                } else {
                    current.copy(
                        pendingToolInvocations = tools,
                        pendingResponseBlocks = blocks,
                    )
                }
            }
        }
        chatStateStore.update { persisted ->
            val sessionIndex = persisted.sessions.indexOfFirst { it.id == sessionId }
            if (sessionIndex < 0) return@update persisted
            val session = persisted.sessions[sessionIndex]
            // GUI steps arrive several times a second; identity comparison keeps the
            // no-op case off the persistence queue without a deep equals on every message.
            var touched = false
            val messages = session.messages.map { message ->
                message.withGuiSteps(encoded).also { patched ->
                    if (patched !== message) touched = true
                }
            }
            if (!touched) return@update persisted
            val updatedSessions = persisted.sessions.toMutableList()
            updatedSessions[sessionIndex] = session.withDerivedMessages(messages)
            persisted.copy(sessions = updatedSessions)
        }
    }

    private fun SessionExecutionState.withPlanEntries(
        entries: List<SessionPlanEntry>,
    ): SessionExecutionState {
        if (entries.isEmpty() && planDocumentMarkdown.isNotBlank()) {
            return copy(planEntries = emptyList(), planContentFingerprint = "")
        }
        val placement = nextSessionPlanPlacement(
            previousEntries = planEntries,
            previousFingerprint = planContentFingerprint,
            previousMessageId = planAnchorMessageId,
            previousGroupId = planAnchorGroupId,
            nextEntries = entries,
            activeResponseGroupId = activeResponseGroupId,
            messages = chatStateStore.state.value.sessions
                .firstOrNull { it.id == sessionId }
                ?.messages
                .orEmpty(),
        )
        return copy(
            planEntries = placement.entries,
            planAnchorMessageId = placement.messageId,
            planAnchorGroupId = placement.groupId,
            planContentFingerprint = placement.contentFingerprint,
        )
    }

    /**
     * Reduces ACP session-level events (plan/usage/mode/config/title/commands/
     * replayed user chunks) into execution state or the shared agent command
     * flow. Streaming payloads already flow through [handleToolEvent] and the
     * text callbacks; this handler owns everything else the CLI broadcasts.
     */
    private fun handleKimiSessionEvent(
        handle: SessionExecutionHandle,
        name: String,
        payload: JSONObject,
    ) {
        when (name) {
            // The CLI sends the full plan projection on every update: replace.
            "plan_update" -> {
                val entries = parseSessionPlanEntries(payload)
                updateExecutionState(handle.sessionId, ExecutionPublish.Coalesced) { current ->
                    current.withPlanEntries(entries)
                }
            }

            "usage_update" -> {
                val usage = parseSessionContextUsage(payload) ?: return
                updateExecutionState(handle.sessionId, ExecutionPublish.Coalesced) { current ->
                    current.copy(contextUsage = mergeSessionContextUsage(current.contextUsage, usage))
                }
            }

            "current_mode_update" -> {
                val modeId = parseCurrentModeId(payload) ?: return
                updateExecutionState(handle.sessionId) { current ->
                    current.copy(agentConfig = current.agentConfig.copy(modeId = modeId))
                }
            }

            "config_option_update" -> {
                val update = payload.optJSONObject("update") ?: payload
                updateExecutionState(handle.sessionId) { current ->
                    current.copy(
                        agentConfig = current.agentConfig.applyConfigOptionUpdate(update),
                    )
                }
            }

            "session_info_update" -> applySessionInfoTitle(
                sessionId = handle.sessionId,
                title = parseSessionInfoTitle(payload),
            )

            "available_commands_update" -> {
                _agentSlashCommands.value = parseAgentSlashCommands(payload)
            }

            // History replay (session/load) re-emits user turns; never drop them.
            "user_message_chunk" -> {
                val delta = payload.optString("delta")
                if (delta.isEmpty()) return
                updateExecutionState(handle.sessionId, ExecutionPublish.Coalesced) { current ->
                    current.copy(replayedUserText = current.replayedUserText + delta)
                }
            }
        }
    }

    /**
     * Writes the agent-reported session title back into the persisted chat
     * state (batched through ChatStateStore's Room persistence queue). A null
     * title clears the agent-set title and reverts to the derived one.
     */
    private fun applySessionInfoTitle(sessionId: String, title: String?) {
        chatStateStore.update { persisted ->
            val sessionIndex = persisted.sessions.indexOfFirst { it.id == sessionId }
            if (sessionIndex < 0) return@update persisted
            val session = persisted.sessions[sessionIndex]
            val updatedSession = if (title != null) {
                val sanitized = title.visibleUserMessageText().sanitizeGeneratedSessionTitle()
                if (sanitized.isBlank() || sanitized.looksLikeHiddenPromptTitle()) {
                    if (session.title.looksLikeHiddenPromptTitle()) {
                        val cleared = session.copy(hasCustomTitle = false)
                            .withDerivedMessages(session.messages)
                        val updatedSessions = persisted.sessions.toMutableList()
                        updatedSessions[sessionIndex] = cleared
                        return@update persisted.copy(sessions = updatedSessions)
                    }
                    return@update persisted
                }
                if (session.title == sanitized && session.hasCustomTitle) {
                    return@update persisted
                }
                session.copy(title = sanitized, hasCustomTitle = true)
            } else {
                if (!session.hasCustomTitle) return@update persisted
                session.copy(hasCustomTitle = false).withDerivedMessages(session.messages)
            }
            val updatedSessions = persisted.sessions.toMutableList()
            updatedSessions[sessionIndex] = updatedSession
            persisted.copy(sessions = updatedSessions)
        }
    }

    private fun activateAssistantResponse(handle: SessionExecutionHandle): AssistantResponseIdentity {
        val startedAtMillis = System.currentTimeMillis()
        val turnId = "turn-$startedAtMillis-${UUID.randomUUID().toString().take(8)}"
        return AssistantResponseIdentity(
            responseGroupId = "agent-group-$turnId",
            messageIdPrefix = "agent-$turnId",
            createdAtMillis = startedAtMillis,
            checkpointFromPosition = syncActiveBranches(handle.retainedMessagesSnapshot()).size,
        ).also { identity ->
            synchronized(handle.lock) {
                handle.assistantCheckpointJob?.cancel()
                handle.assistantCheckpointJob = null
                handle.lastAssistantCheckpointUptimeMillis = null
                handle.activeResponseIdentity = identity
            }
        }
    }

    private fun deactivateAssistantCheckpoint(
        handle: SessionExecutionHandle?,
        identity: AssistantResponseIdentity?,
    ) {
        if (handle == null || identity == null) return
        synchronized(handle.lock) {
            if (handle.activeResponseIdentity != identity) return
            handle.activeResponseIdentity = null
            handle.assistantCheckpointJob?.cancel()
            handle.assistantCheckpointJob = null
        }
    }

    private fun assistantMessagesForBlocks(
        normalizedBlocks: List<AssistantResponseBlock>,
        thoughtDurationMillis: Long?,
        assistantActionsHidden: Boolean,
        isIncomplete: Boolean = false,
        responseIdentity: AssistantResponseIdentity? = null,
        messageCreatedAtMillis: Long? = null,
        usageStatistics: ChatUsageStatistics? = null,
        providerPayloadJson: String = "",
        statusText: String = "",
        statusDetail: String = "",
    ): List<ChatMessage> {
        val messageTimestamp = if (isIncomplete) {
            responseIdentity?.createdAtMillis ?: messageCreatedAtMillis ?: System.currentTimeMillis()
        } else {
            messageCreatedAtMillis ?: System.currentTimeMillis()
        }
        val responseGroupId = responseIdentity?.responseGroupId ?: "agent-group-$messageTimestamp"
        return normalizedBlocks.mapIndexedNotNull { index, block ->
            when (block) {
                is AssistantResponseBlock.Text -> {
                    if (block.text.isBlank()) {
                        null
                    } else {
                        ChatMessage(
                            id = responseIdentity?.messageIdFor(block.id) ?: "agent-${messageTimestamp + index}",
                            author = MessageAuthor.Agent,
                            text = block.text,
                            createdAtMillis = messageTimestamp + index,
                            responseGroupId = responseGroupId,
                            assistantActionsHidden = assistantActionsHidden,
                            isIncomplete = isIncomplete,
                        )
                    }
                }

                is AssistantResponseBlock.ToolGroup -> {
                    if (block.toolInvocations.isEmpty()) {
                        null
                    } else {
                        ChatMessage(
                            id = responseIdentity?.messageIdFor(block.id) ?: "agent-${messageTimestamp + index}",
                            author = MessageAuthor.Agent,
                            text = "",
                            createdAtMillis = messageTimestamp + index,
                            toolInvocations = block.toolInvocations,
                            responseGroupId = responseGroupId,
                            assistantActionsHidden = assistantActionsHidden,
                            isIncomplete = isIncomplete,
                        )
                    }
                }

                is AssistantResponseBlock.Reasoning -> {
                    ChatMessage(
                        id = responseIdentity?.messageIdFor(block.id) ?: "agent-${messageTimestamp + index}",
                        author = MessageAuthor.Agent,
                        text = "",
                        createdAtMillis = messageTimestamp + index,
                        toolInvocations = block.trace.toolInvocations,
                        reasoningTrace = block.trace,
                        responseGroupId = responseGroupId,
                        assistantActionsHidden = assistantActionsHidden,
                        isIncomplete = isIncomplete,
                    )
                }

                is AssistantResponseBlock.Status -> {
                    if (block.text.isBlank()) null else ChatMessage(
                        id = responseIdentity?.messageIdFor(block.id) ?: "agent-${messageTimestamp + index}",
                        author = MessageAuthor.Agent,
                        text = "",
                        createdAtMillis = messageTimestamp + index,
                        responseGroupId = responseGroupId,
                        assistantActionsHidden = assistantActionsHidden,
                        isIncomplete = isIncomplete,
                        statusText = block.text,
                        statusDetail = block.detail,
                    )
                }
            }
        }.let { messages ->
            if (messages.isEmpty()) {
                if (statusText.isBlank()) {
                    emptyList()
                } else {
                    listOf(
                        ChatMessage(
                            id = responseIdentity?.messageIdFor("status") ?: "agent-$messageTimestamp",
                            author = MessageAuthor.Agent,
                            text = "",
                            createdAtMillis = messageTimestamp,
                            responseGroupId = responseGroupId,
                            assistantActionsHidden = assistantActionsHidden,
                            isIncomplete = isIncomplete,
                            statusText = statusText,
                            statusDetail = statusDetail,
                        )
                    )
                }
            } else {
                messages.toMutableList().apply {
                    if (none { it.reasoningTrace != null }) {
                        val lastIndex = lastIndex
                        set(
                            lastIndex,
                            get(lastIndex).copy(
                                thoughtDurationMillis = thoughtDurationMillis,
                                usageStatistics = usageStatistics,
                                providerPayloadJson = providerPayloadJson,
                                statusText = statusText.ifBlank { get(lastIndex).statusText },
                                statusDetail = statusDetail.ifBlank { get(lastIndex).statusDetail },
                            ),
                        )
                    } else {
                        val lastIndex = lastIndex
                        set(
                            lastIndex,
                            get(lastIndex).copy(
                                usageStatistics = usageStatistics,
                                providerPayloadJson = providerPayloadJson,
                                statusText = statusText.ifBlank { get(lastIndex).statusText },
                                statusDetail = statusDetail.ifBlank { get(lastIndex).statusDetail },
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun buildChatUsageStatistics(
        tokenUsage: LlmTokenUsage?,
        tokenUsageSource: String,
        turnStartedAtMillis: Long?,
        firstTokenAtMillis: Long?,
        turnCompletedAtMillis: Long?,
    ): ChatUsageStatistics? {
        if (
            tokenUsage == null &&
            turnStartedAtMillis == null &&
            firstTokenAtMillis == null &&
            turnCompletedAtMillis == null
        ) {
            return null
        }
        return ChatUsageStatistics(
            inputTokens = tokenUsage?.inputTokens,
            outputTokens = tokenUsage?.outputTokens,
            totalTokens = tokenUsage?.withMissingTotalResolved()?.totalTokens,
            reasoningTokens = tokenUsage?.reasoningTokens,
            cachedInputTokens = tokenUsage?.cachedInputTokens,
            requestCount = tokenUsage?.requestCount ?: 1,
            tokenUsageSource = tokenUsageSource,
            startedAtMillis = turnStartedAtMillis ?: 0L,
            firstTokenAtMillis = firstTokenAtMillis,
            completedAtMillis = turnCompletedAtMillis ?: 0L,
        )
    }

    private fun currentAssistantResponseBlocks(sessionId: String): List<AssistantResponseBlock> =
        _executionStates.value[sessionId]?.pendingResponseBlocks.orEmpty()

    private fun finalizePausedTurn(
        handle: SessionExecutionHandle,
        snapshot: SessionExecutionState,
    ): CompletionSummary {
        val finalizedToolInvocations = finalizeInterruptedToolInvocations(snapshot.pendingToolInvocations)
        val finalizedResponseBlocks = finalizeInterruptedAssistantResponseBlocks(snapshot.pendingResponseBlocks)
        val thoughtDurationMillis = snapshot.activeTurnStartedAtMillis
            ?.let { startedAt ->
                snapshot.activeTurnInteractionClock.activeElapsedMillis(startedAt, System.currentTimeMillis())
            }
        val blocks = finalizedResponseBlocks.ifEmpty {
            buildList {
                if (snapshot.pendingAssistantText.isNotBlank()) {
                    add(
                        AssistantResponseBlock.Text(
                            id = handle.nextPendingBlockId("agent-text"),
                            text = snapshot.pendingAssistantText,
                        )
                    )
                }
                if (finalizedToolInvocations.isNotEmpty()) {
                    add(
                        AssistantResponseBlock.ToolGroup(
                            id = handle.nextPendingBlockId("agent-tools"),
                            toolInvocations = finalizedToolInvocations,
                        )
                    )
                }
            }
        }
        return if (blocks.isEmpty() && snapshot.pendingStatusText.isBlank()) {
            CompletionSummary(
                sessionTitle = resolveSessionTitle(handle.sessionId),
                summary = "",
                outcome = SessionTurnOutcome.Neutral,
                toolCallCount = 0,
                distinctToolCount = 0,
                toolNames = emptyList(),
                durationMillis = thoughtDurationMillis,
            )
        } else {
            appendAgentMessage(
                sessionId = handle.sessionId,
                blocks = blocks,
                thoughtDurationMillis = thoughtDurationMillis,
                outcome = SessionTurnOutcome.Neutral,
                statusText = completedReconnectStatus(snapshot.pendingStatusText),
                statusDetail = snapshot.pendingStatusDetail,
                handle = handle,
            )
        }
    }

    private fun commitSteerUi(
        handle: SessionExecutionHandle,
        drained: List<PendingEnvelope>,
    ) {
        if (drained.isEmpty()) return
        val fresh = synchronized(handle.lock) {
            drained.filter { envelope -> handle.committedSteerIds.add(envelope.id) }
        }
        if (fresh.isEmpty()) return
        appendSteerInterruptionMessages(handle, fresh)
        val committedIds = fresh.map { it.id }.toSet()
        updateExecutionState(handle.sessionId) { current ->
            current.copy(
                pendingInputs = current.pendingInputs.filterNot { committedIds.contains(it.id) },
            )
        }
    }

    private fun appendSteerInterruptionMessages(
        handle: SessionExecutionHandle,
        drained: List<PendingEnvelope>,
    ) {
        if (drained.isEmpty()) return
        updateExecutionState(handle.sessionId) { it }
        completeActiveReasoning(
            handle = handle,
            trigger = ReasoningCompletionTrigger.SteerAccepted,
        )
        val nowMillis = System.currentTimeMillis()
        val snapshot = _executionStates.value[handle.sessionId]
            ?: SessionExecutionState(sessionId = handle.sessionId)
        val pendingBlocks = completeAssistantBlocksForSteer(
            blocks = snapshot.pendingResponseBlocks.ifEmpty {
                buildList {
                    if (snapshot.pendingAssistantText.isNotBlank()) {
                        add(
                            AssistantResponseBlock.Text(
                                id = handle.nextPendingBlockId("agent-text"),
                                text = snapshot.pendingAssistantText,
                            )
                        )
                    }
                    if (snapshot.pendingToolInvocations.isNotEmpty()) {
                        add(
                            AssistantResponseBlock.ToolGroup(
                                id = handle.nextPendingBlockId("agent-tools"),
                                toolInvocations = snapshot.pendingToolInvocations,
                            )
                        )
                    }
                }
            },
            nowMillis = nowMillis,
        )
        val responseIdentity = handle.activeResponseIdentity
        val interruptedAssistantMessages = assistantMessagesForBlocks(
            normalizedBlocks = normalizeAssistantResponseBlocks(pendingBlocks),
            thoughtDurationMillis = null,
            assistantActionsHidden = true,
            responseIdentity = responseIdentity,
        )
        val userMessages = drained.map { it.message }
        deactivateAssistantCheckpoint(handle, responseIdentity)
        chatStateStore.update { persisted ->
            val sessionIndex = persisted.sessions.indexOfFirst { it.id == handle.sessionId }
            if (sessionIndex < 0) return@update persisted

            val updatedSessions = persisted.sessions.toMutableList()
            val session = updatedSessions.removeAt(sessionIndex)
            val currentMessages = session.messages.ifEmpty { handle.retainedMessagesSnapshot() }
            val messagesWithoutCheckpoint = responseIdentity?.let { identity ->
                currentMessages.filterNot { it.responseGroupId == identity.responseGroupId }
            } ?: currentMessages
            val updatedSession = session.withDerivedMessages(
                syncActiveBranches(messagesWithoutCheckpoint + interruptedAssistantMessages + userMessages)
            )
            handle.replaceRetainedMessages(updatedSession.messages)
            updatedSessions.add(
                0,
                updatedSession,
            )
            persisted.copy(sessions = updatedSessions)
        }
        handle.finishReasoningBlock()
        updateExecutionState(handle.sessionId) { current ->
            current.copy(
                pendingToolInvocations = emptyList(),
                pendingResponseBlocks = emptyList(),
                pendingAssistantText = "",
                pendingStatusText = "",
                pendingStatusDetail = "",
            )
        }
        val continuedResponseIdentity = activateAssistantResponse(handle)
        updateExecutionState(handle.sessionId) { current ->
            current.copy(
                activeResponseGroupId = continuedResponseIdentity.responseGroupId,
                activeResponseMessageIdPrefix = continuedResponseIdentity.messageIdPrefix,
            )
        }
    }

    private fun drainSteerInputs(
        handle: SessionExecutionHandle,
    ): List<PendingEnvelope> {
        val drained = synchronized(handle.lock) {
            buildList {
                while (handle.steerInputs.isNotEmpty()) {
                    add(handle.steerInputs.removeFirst())
                }
            }
        }
        if (drained.isEmpty()) return emptyList()

        val drainedIds = drained.map { it.id }.toSet()
        updateExecutionState(handle.sessionId) { current ->
            current.copy(
                pendingInputs = current.pendingInputs.filterNot { drainedIds.contains(it.id) }
            )
        }
        return drained
    }

    private fun promoteRemainingSteersToQueue(
        handle: SessionExecutionHandle,
    ) {
        val movedIds = synchronized(handle.lock) {
            if (handle.steerInputs.isEmpty()) {
                emptyList()
            } else {
                buildList {
                    while (handle.steerInputs.isNotEmpty()) {
                        val entry = handle.steerInputs.removeFirst()
                        handle.queuedInputs.addFirst(entry.copy(mode = SessionFollowUpMode.Queue))
                        add(entry.id)
                    }
                }
            }
        }
        if (movedIds.isEmpty()) return

        updateExecutionState(handle.sessionId) { current ->
            current.copy(
                pendingInputs = current.pendingInputs.map { pending ->
                    if (movedIds.contains(pending.id)) {
                        pending.copy(mode = SessionFollowUpMode.Queue)
                    } else {
                        pending
                    }
                }
            )
        }
    }

    private fun pollNextQueuedInput(
        handle: SessionExecutionHandle,
    ): PendingEnvelope? {
        val next = synchronized(handle.lock) {
            if (handle.queuedInputs.isEmpty()) {
                null
            } else {
                handle.queuedInputs.removeFirst()
            }
        } ?: return null

        updateExecutionState(handle.sessionId) { current ->
            current.copy(
                pendingInputs = current.pendingInputs.filterNot { it.id == next.id }
            )
        }
        return next
    }

    private fun clearPendingInputs(
        handle: SessionExecutionHandle,
    ) {
        synchronized(handle.lock) {
            handle.queuedInputs.clear()
            handle.steerInputs.clear()
        }
        if (executionHandles[handle.sessionId] !== handle) return
        updateExecutionState(handle.sessionId) { current ->
            current.copy(pendingInputs = emptyList())
        }
    }

    private fun updateExecutionState(
        sessionId: String,
        publish: ExecutionPublish = ExecutionPublish.Immediate,
        transform: (SessionExecutionState) -> SessionExecutionState,
    ) {
        val changedSessionId = synchronized(executionPublishLock) {
            val current = unpublishedExecutionStates[sessionId]
                ?: _executionStates.value[sessionId]
                ?: SessionExecutionState(sessionId = sessionId)
            unpublishedExecutionStates[sessionId] = transform(current)
            if (publish == ExecutionPublish.Immediate) {
                coalescedPublishJob?.cancel()
                coalescedPublishJob = null
                flushUnpublishedExecutionStatesLocked()
                sessionId
            } else {
                val pending = unpublishedExecutionStates[sessionId] ?: current
                val published = _executionStates.value[sessionId]
                val delayMillis = streamCoalesceDelayMillis(published, pending)
                if (delayMillis <= 0L) {
                    coalescedPublishJob?.cancel()
                    coalescedPublishJob = null
                    flushUnpublishedExecutionStatesLocked()
                    sessionId
                } else if (coalescedPublishJob?.isActive != true) {
                    coalescedPublishJob = scope.launch {
                        delay(delayMillis)
                        val flushed = synchronized(executionPublishLock) {
                            coalescedPublishJob = null
                            flushUnpublishedExecutionStatesLocked()
                        }
                        flushed.forEach(::afterExecutionPublished)
                    }
                    null
                } else {
                    null
                }
            }
        }
        if (changedSessionId != null) {
            afterExecutionPublished(changedSessionId)
        }
    }

    private fun flushUnpublishedExecutionStatesLocked(): Set<String> {
        if (unpublishedExecutionStates.isEmpty()) return emptySet()
        val changed = unpublishedExecutionStates.keys.toSet()
        _executionStates.update { states ->
            states.toMutableMap().apply { putAll(unpublishedExecutionStates) }
        }
        unpublishedExecutionStates.clear()
        return changed
    }

    private fun afterExecutionPublished(sessionId: String) {
        requestAssistantCheckpoint(sessionId)
        if (
            currentSettings.value.keepTasksRunningInBackground &&
            _executionStates.value.values.any { it.isRunning }
        ) {
            ensureForegroundServiceRunning()
        }
    }

    private fun requestAssistantCheckpoint(sessionId: String) {
        val handle = executionHandles[sessionId] ?: return
        val identity = handle.activeResponseIdentity ?: return
        val blocks = _executionStates.value[sessionId]?.pendingResponseBlocks.orEmpty()
        if (blocks.isEmpty()) return

        val nowUptimeMillis = SystemClock.uptimeMillis()
        var persistNow = false
        synchronized(handle.lock) {
            if (handle.activeResponseIdentity != identity) return
            val remainingMillis = handle.lastAssistantCheckpointUptimeMillis?.let { lastCheckpointUptimeMillis ->
                AssistantCheckpointIntervalMillis - (nowUptimeMillis - lastCheckpointUptimeMillis)
            } ?: 0L
            if (remainingMillis <= 0L) {
                handle.lastAssistantCheckpointUptimeMillis = nowUptimeMillis
                handle.assistantCheckpointJob?.cancel()
                handle.assistantCheckpointJob = null
                persistNow = true
            } else if (handle.assistantCheckpointJob?.isActive != true) {
                handle.assistantCheckpointJob = scope.launch {
                    delay(remainingMillis)
                    persistAssistantCheckpoint(handle, identity)
                }
            }
        }
        if (persistNow) {
            persistAssistantCheckpoint(handle, identity)
        }
    }

    private fun persistAssistantCheckpoint(
        handle: SessionExecutionHandle,
        identity: AssistantResponseIdentity,
    ) {
        val blocks = _executionStates.value[handle.sessionId]?.pendingResponseBlocks.orEmpty()
        if (blocks.isEmpty() || handle.activeResponseIdentity != identity) return
        synchronized(handle.lock) {
            if (handle.activeResponseIdentity != identity) return
            handle.lastAssistantCheckpointUptimeMillis = SystemClock.uptimeMillis()
            handle.assistantCheckpointJob = null
        }
        val checkpointMessages = assistantMessagesForBlocks(
            normalizedBlocks = normalizeAssistantResponseBlocks(blocks),
            thoughtDurationMillis = null,
            assistantActionsHidden = false,
            isIncomplete = true,
            responseIdentity = identity,
        )
        if (checkpointMessages.isEmpty()) return

        chatStateStore.updateAssistantCheckpoint(
            checkpoint = AssistantResponseCheckpoint(
                target = AssistantResponseCheckpointTarget(
                    sessionId = handle.sessionId,
                    responseGroupId = identity.responseGroupId,
                ),
                fromPosition = identity.checkpointFromPosition,
                messages = checkpointMessages,
            ),
            // Ignore checkpoints that race with response completion.
            shouldPersist = { handle.activeResponseIdentity == identity },
        )
    }

    private fun ensureForegroundServiceRunning() {
        try {
            AetherForegroundService.ensureRunning(application)
        } catch (throwable: Throwable) {
            diagnosticLogger.exception(
                category = "session",
                event = "foreground_service_start_failed",
                throwable = throwable,
            )
        }
    }

    private fun validateSettings(settings: AppSettings): String? = when {
        !settings.isProviderSetupValid() ->
            "The selected provider is not fully configured."

        else -> null
    }

    private fun validateRequest(request: SessionTurnRequest): String? = when {
        request.agentModeEnabled && !request.settings.agentModeAuthorizationEnabled ->
            "Agent Mode is selected, but authorization is disabled. Enable it in Settings > Agent Mode first."

        request.agentModeEnabled && !agentModeController.authorizationState.value.isReady ->
            agentModeController.authorizationState.value.detail.ifBlank {
                "Agent Mode is selected, but its authorization service is not ready."
            }

        request.remoteMachineId.isNotBlank() && request.remoteBaseUrl.isBlank() ->
            "That remote machine is missing. Add it again from Remote."

        request.remoteMachineId.isNotBlank() -> null

        else -> validateSettings(request.settings)
    }

    private fun resolveSessionTitle(sessionId: String): String =
        chatStateStore.state.value.sessions.firstOrNull { it.id == sessionId }?.title.orEmpty()

    private fun buildRequestMessages(
        messages: List<ChatMessage>,
        settings: AppSettings,
        sessionId: String = "",
        agentModeEnabled: Boolean = false,
        preopen: AgentModePreopenResult? = null,
    ): List<LlmMessage> {
        val visible = messages.filter { it.displayKind != MessageDisplayKind.CompactStatus }
        val lastUserId = visible.lastOrNull { it.author == MessageAuthor.User }?.id
        val execution = _executionStates.value[sessionId]
        val planMode = execution?.agentConfig?.modeId?.equals("plan", ignoreCase = true) == true ||
            settings.kimiPermissionMode.equals("plan", ignoreCase = true)
        val hasOpenTodos = execution?.planEntries.orEmpty().any { entry ->
            sessionPlanEntryIsOpen(entry.status)
        }
        val kind = resolveDeskLeadKind(
            agentModeEnabled = agentModeEnabled,
            planMode = planMode,
            longHorizonApproved = isLongHorizonApproved(sessionId) || execution?.longHorizonApproved == true,
            hasOpenTodos = hasOpenTodos,
        )
        // The date is stamped on day boundaries, from each message's own timestamp — never on
        // "whichever message is last right now". The whole history is rebuilt every turn and the
        // provider caches by prefix, so a line that sits on the last user message this turn and is
        // gone from that same message next turn moves the point where the prefix diverges from the
        // tail into the middle of the conversation: every turn a near-total cache miss. Keying off
        // createdAtMillis makes each message render identically on every rebuild, and it is also
        // more truthful — a question asked yesterday was asked on yesterday's date.
        var lastStampedDate: String? = null
        // The lead is no longer folded into the last user message.
        //
        // It used to be, and that made the history itself unstable: the lead rode on whichever
        // message was last *this* turn and was gone from that same message next turn, so the
        // provider's prefix cache diverged at the tail of the history every single turn. Rendering
        // every historical message identically on every rebuild - the rule the date line already
        // follows - and appending the lead as its own trailing message keeps the whole history a
        // byte-stable prefix, with the only new content at the end where it belongs.
        val history = visible.flatMap { message ->
            val stampDate = if (message.author == MessageAuthor.User) {
                val day = messageLocalDate(message.createdAtMillis)
                (day != lastStampedDate).also { lastStampedDate = day }
            } else {
                false
            }
            buildRequestMessagesForChatMessage(
                message = message,
                settings = settings,
                stampDate = stampDate,
            )
        }
        if (kind == DeskLeadKind.None) return history
        // The static rules ride on every lead, not just the first of a session.
        //
        // They used to be sent once, on the turn where the session's lead kind was first seen, on
        // the reasoning that they were in the context from then on. The lead is a synthetic
        // trailing message that is never stored, so `history` - rebuilt from the database every
        // turn - never contained them: from the second turn on the model was working without its
        // standing rules, with nothing to resend them. Folding makes the same assumption false a
        // second way, by design: what is in the window now leaves it later.
        //
        // Resending costs nothing that matters. The lead is the last message in the request, so a
        // stable block of rules extends the cached prefix rather than breaking it.
        val lead = buildDeskLeadMessage(
            kind = kind,
            task = visible.lastOrNull { it.id == lastUserId }?.text.orEmpty(),
            preopen = preopen.takeIf { agentModeEnabled },
            includeStaticRules = true,
            sessionId = sessionId,
        ) ?: return history
        return history + lead
    }

    /**
     * The turn's standing instructions, as a message of their own.
     *
     * Marked as coming from the environment rather than from the user, because that is what it is -
     * the same shape the session-memory ledger uses when it injects the frozen-node table. A model
     * that reads it as the user's own words would treat "prefer the official source" as this turn's
     * request instead of a standing rule.
     */
    private fun buildDeskLeadMessage(
        kind: DeskLeadKind,
        task: String,
        preopen: AgentModePreopenResult?,
        includeStaticRules: Boolean,
        sessionId: String,
    ): LlmMessage? {
        if (kind == DeskLeadKind.None) return null
        val scenes = if (kind == DeskLeadKind.Research) {
            emptyList()
        } else {
            agentModeLearningRuntime?.recallScenes(
                task = task,
                packageName = preopen?.packageName.orEmpty(),
            ).orEmpty()
        }
        // Research used to be excluded from recall outright, which read as "the browser has nothing
        // to remember". It has - just not the phone agent's scene cards. What the browser
        // accumulates is knowledge about sites: which need a login, which hide their body behind a
        // widget, which are usually unreachable. That is what gets injected here instead.
        val everMeFact = if (kind == DeskLeadKind.Research) {
            browserDossierBySession[sessionId].orEmpty()
        } else {
            agentModeLearningRuntime?.everMeCommitPrompt().orEmpty()
        }
        val reminder = agentModeLeadReminderText(
            preopen = preopen,
            recalledScenes = scenes,
            everMeCommitFact = everMeFact,
            kind = kind,
            includeStaticRules = includeStaticRules,
        )
        if (reminder.isBlank()) return null
        // Only the phone runtime's own fact is consumable; the browser dossier is standing
        // knowledge that should be there again next turn.
        if (everMeFact.isNotBlank() && kind != DeskLeadKind.Research) {
            agentModeLearningRuntime?.consumeEverMeCommitPrompt()
        }
        return LlmMessage(
            role = "user",
            contentParts = listOf(LlmTextPart(reminder)),
        )
    }

    private fun buildRequestMessagesForChatMessage(
        message: ChatMessage,
        settings: AppSettings,
        stampDate: Boolean = false,
    ): List<LlmMessage> {
        if (message.author != MessageAuthor.Agent || message.toolInvocations.isEmpty()) {
            return listOf(
                buildRequestMessage(message = message, settings = settings, stampDate = stampDate),
            )
        }
        val assistant = buildRequestMessage(message = message, settings = settings, stampDate = stampDate)
        val existingPayload = assistant.providerPayload
        val providerPayload = existingPayload ?: JSONObject().apply {
            put("piAssistantMessage", JSONObject().apply {
                put("role", "assistant")
                put("api", "aether")
                put("provider", settings.piProviderId.ifBlank { "aether" })
                put("model", settings.modelId.ifBlank { "unknown" })
                put("content", JSONArray().apply {
                    if (message.text.isNotBlank()) {
                        put(JSONObject().put("type", "text").put("text", message.text))
                    }
                    message.toolInvocations.forEach { invocation ->
                        put(JSONObject().apply {
                            put("type", "toolCall")
                            put("id", invocation.id)
                            put("name", invocation.toolName)
                            put("arguments", parseJsonObject(invocation.argumentsJson) ?: JSONObject())
                        })
                    }
                })
                put("stopReason", "toolUse")
                put("timestamp", message.createdAtMillis ?: System.currentTimeMillis())
            })
        }
        val assistantWithPayload = assistant.copy(providerPayload = providerPayload)
        val results = message.toolInvocations.map { invocation ->
            LlmMessage(
                role = "toolResult",
                contentParts = listOf(LlmTextPart(invocation.outputJson)),
                toolCallId = invocation.id,
                toolName = invocation.toolName,
            )
        }
        return listOf(assistantWithPayload) + results
    }

    /** The local calendar day a message was sent on, used to decide where to stamp the date. */
    private fun messageLocalDate(millis: Long): String =
        java.time.Instant.ofEpochMilli(millis)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
            .toString()

    /**
     * The date a message was sent on, for a model that would otherwise date "latest" from its
     * training data.
     *
     * Derived from the message's own timestamp, never from `now()`: the history is rebuilt on every
     * turn, so a line that changes between rebuilds breaks the provider's prefix cache for
     * everything after it.
     *
     * Must stay on one line and keep [EnvironmentLeadMarker] as its first characters:
     * `visibleUserMessageText` strips exactly that line back out, because the ACP replay returns
     * this text as the user's own message and it would otherwise become the bubble and the title.
     */
    private fun datePromptLine(millis: Long): String {
        val day = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault())
        val weekday = day.dayOfWeek.getDisplayName(
            java.time.format.TextStyle.FULL,
            java.util.Locale.SIMPLIFIED_CHINESE,
        )
        return "$EnvironmentLeadMarker 今天是 ${day.toLocalDate()}（$weekday）。" +
            "凡是\"最新/近期/今年\"之类的问题都以这个日期为准；" +
            "不要凭记忆假设年份，也不要把年份或\"最新\"塞进搜索词——直接搜用户说的主题。"
    }

    private fun buildRequestMessage(
        message: ChatMessage,
        settings: AppSettings,
        stampDate: Boolean = false,
    ): LlmMessage {
        val parts = mutableListOf<LlmContentPart>()
        // A one-shot composer directive (goal/swarm chip) prepends its hidden
        // instructions to the prompt that reaches the agent; the user bubble
        // keeps showing only message.text.
        val directedText = if (message.author == MessageAuthor.User && message.promptDirective.isNotBlank()) {
            composerDirectivePromptText(message.promptDirective, message.text)
        } else {
            message.text
        }
        val messageText = buildString {
            // The model has no clock. Asked for "最新科技新闻" it reached for the newest year it had
            // ever seen in training and searched 2025年9月 — twice — before a search result happened
            // to mention 2026 and it corrected itself. One line removes the whole class of error.
            // Stamped on the first user message of each calendar day, so a session that runs past
            // midnight still learns the new date without every turn rewriting its own history.
            if (stampDate && message.author == MessageAuthor.User) {
                append(datePromptLine(message.createdAtMillis))
                append("\n\n")
            }
            if (message.author == MessageAuthor.User && message.hiddenPromptPrefix.isNotBlank()) {
                append(message.hiddenPromptPrefix.trim())
                append("\n\n")
            }
            append(directedText)
        }
        if (messageText.isNotBlank()) {
            parts += LlmTextPart(messageText)
        }
        message.attachments.forEach { attachment ->
            parts += buildWorkspaceAttachmentParts(attachment, settings)
        }
        if (parts.isEmpty()) {
            parts += LlmTextPart("[Empty message]")
        }
        return LlmMessage(
            role = if (message.author == MessageAuthor.User) "user" else "assistant",
            contentParts = parts,
            providerPayload = parseJsonObject(message.providerPayloadJson),
        )
    }

    private fun buildSteerRequestMessage(
        message: ChatMessage,
        settings: AppSettings,
    ): LlmMessage {
        val steerText = buildString {
            append(
                "The user sent this while you were already working. Treat it as supplemental context for the current task. " +
                    "Continue the ongoing work, do not restart just to acknowledge it, and only change course if the new note requires it."
            )
            if (message.text.isNotBlank()) {
                append("\n\nSupplemental user note:\n")
                append(message.text)
            } else if (message.attachments.isNotEmpty()) {
                append("\n\nThe user also attached additional files for the current task.")
            }
        }
        return buildRequestMessage(message.copy(text = steerText), settings)
    }

    private fun buildWorkspaceAttachmentParts(
        attachment: ChatAttachment,
        settings: AppSettings,
    ): List<LlmContentPart> {
        if (attachment.workspacePath.isBlank()) {
            if (
                attachment.kind == AttachmentKind.Image &&
                attachment.mimeType.startsWith("image/") &&
                attachment.inlineBase64.isNotBlank()
            ) {
                return listOf(
                    LlmTextPart(
                        "Visual attachment:\n" +
                            "Name: ${attachment.name}\n" +
                            "Type: ${attachment.mimeType}\n" +
                            "This image is attached directly to the model request and has no workspace copy."
                    ),
                    LlmImagePart(
                        mimeType = attachment.mimeType,
                        base64Data = attachment.inlineBase64,
                    ),
                )
            }
            return listOf(LlmTextPart(
                "Attached file '${attachment.name}' is missing a workspace path. Ask the user to re-upload it if you need to inspect the file."
            ))
        }

        val canInlineImage = canInlineWorkspaceImageAttachment(attachment, settings)
        val accessHint = if (isWorkspaceImageAttachment(attachment)) {
            if (canInlineImage) {
                "This image was copied into the workspace and is also inserted into this model request when local bytes are available. Use the native read tool on this path when you need to inspect it."
            } else {
                "This image was copied into the workspace. Call the native read tool on this exact path before answering questions about the image."
            }
        } else {
            "Inspect this file through read, grep, find, ls, or bash inside the workspace instead of assuming its contents."
        }

        val metadataPart = LlmTextPart(
            buildString {
                append("Workspace attachment:\n")
                append("Name: ${attachment.name}\n")
                append("Type: ${attachment.mimeType.ifBlank { "unknown" }}\n")
                attachment.sizeBytes?.let { append("Size: ${formatBytes(it)}\n") }
                append("Path: ${attachment.workspacePath}\n")
                append("This file was uploaded in the current session.\n")
                append(accessHint)
            }
        )
        val resourceLinkPart = LlmResourceLinkPart(
            uri = "file://${attachment.workspacePath}",
            name = attachment.name,
        )
        val imagePart = attachment.takeIf {
            canInlineImage
        }?.let {
            LlmImagePart(
                mimeType = it.mimeType,
                base64Data = it.inlineBase64,
            )
        }
        return listOfNotNull(metadataPart, resourceLinkPart, imagePart)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024f * 1024f))
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024f)
        else -> "$bytes B"
    }

    private fun formatReasoningDurationLabel(trace: ReasoningTrace): String {
        val startedAt = trace.startedAtMillis.takeIf { it > 0L } ?: return "0s"
        val endedAt = trace.completedAtMillis ?: System.currentTimeMillis()
        val totalSeconds = ((endedAt - startedAt).coerceAtLeast(0L) + 500L) / 1000L
        return "${totalSeconds.coerceAtLeast(0L)}s"
    }

    private fun formatFailureMessage(throwable: Throwable): String {
        val message = throwable.message?.trim().orEmpty()
        if (message.isBlank()) return throwable.javaClass.simpleName.ifBlank { "Unknown error" }
        return explainProviderRefusal(message) ?: message
    }

    /**
     * Turn a provider refusal into something a person can act on.
     *
     * The model provider runs its own content filter and answers HTTP 451 with a JSON blob. Nothing
     * on this side can retry past it — the request never reached the model — so dumping
     * `{"error":{"message":"The content you provided or machine outputted is blocked."...}}` into the
     * chat reads like an Aether crash when it is a refusal. Note "or machine outputted": a search
     * about a sensitive subject can come back clean and still be blocked once the fetched pages are
     * sent back up, which is why this can land mid-turn after the browser card has already filled in.
     */
    private fun explainProviderRefusal(raw: String): String? {
        val normalized = raw.lowercase()
        val refused = normalized.contains("censorship_blocked") ||
            (normalized.contains("451") && normalized.contains("blocked"))
        if (!refused) return null
        return "The model provider blocked this turn under its own content policy (HTTP 451). " +
            "It is not an Aether or network error, and retrying the same wording will be blocked " +
            "again — the request never reached the model. Web pages fetched during the turn count " +
            "as content too, so a sensitive subject can be refused after the search succeeded. " +
            "Try a narrower question, or switch models for this topic."
    }

    private fun AppSettings.supportsVisibleReasoningTrace(): Boolean {
        val provider = piProviderId.lowercase()
        val model = modelId.lowercase()
        val base = baseUrl.lowercase()
        return base.contains("deepseek") ||
            base.contains("openrouter") ||
            model.contains("deepseek") ||
            model.contains("openrouter") ||
            provider == "stepfun" ||
            base.contains("api.stepfun.com") ||
            base.contains("api.stepfun.ai")
    }

    private fun formatReasoningToolStatus(invocation: ChatToolInvocation): String {
        val arguments = parseJsonObject(invocation.argumentsJson)
        return when (invocation.toolName.lowercase()) {
            "bash" -> if (invocation.isRunning) "Executing bash command" else "Executed bash command"
            "read" -> if (invocation.isRunning) "Reading file" else "Read file"
            "edit" -> if (invocation.isRunning) "Editing file" else "Edited file"
            "write" -> if (invocation.isRunning) "Writing file" else "Wrote file"
            "grep" -> if (invocation.isRunning) "Searching files" else "Searched files"
            "find" -> if (invocation.isRunning) "Finding files" else "Found files"
            "ls" -> if (invocation.isRunning) "Listing files" else "Listed files"
            "aether_config_get" -> formatReasoningToolAction(
                isRunning = invocation.isRunning,
                runningVerb = "Reading",
                completedVerb = "Read",
                subject = formatAetherReasoningCategories(arguments),
                fallback = "Aether settings",
            )

            "aether_config_set" -> formatReasoningToolAction(
                isRunning = invocation.isRunning,
                runningVerb = "Updating",
                completedVerb = "Updated",
                subject = arguments?.optString("category").orEmpty(),
                fallback = "Aether settings",
            )

            "aether_skill_manage" -> formatReasoningToolAction(
                isRunning = invocation.isRunning,
                runningVerb = aetherSkillReasoningVerb(arguments, running = true),
                completedVerb = aetherSkillReasoningVerb(arguments, running = false),
                subject = arguments?.optString("skill_id").orEmpty()
                    .ifBlank { arguments?.optString("skillId").orEmpty() }
                    .ifBlank { arguments?.optString("url").orEmpty() },
                fallback = "Agent Skills",
            )

            "aether_mcp_manage" -> formatReasoningToolAction(
                isRunning = invocation.isRunning,
                runningVerb = aetherMcpReasoningVerb(arguments, running = true),
                completedVerb = aetherMcpReasoningVerb(arguments, running = false),
                subject = arguments?.optString("server_id").orEmpty()
                    .ifBlank { arguments?.optString("serverId").orEmpty() }
                    .ifBlank { arguments?.optString("display_name").orEmpty() }
                    .ifBlank { arguments?.optString("displayName").orEmpty() },
                fallback = "MCP servers",
            )

            "aether_termux_manage" -> when (arguments?.optString("action").orEmpty().lowercase()) {
                "configure_root_access" -> if (invocation.isRunning) "Configuring Termux root access" else "Configured Termux root access"
                "inspect_root_setup" -> if (invocation.isRunning) "Checking Root setup" else "Checked Root setup"
                else -> if (invocation.isRunning) "Checking Termux setup" else "Checked Termux setup"
            }

            "aether_agent_mode_manage" -> when (arguments?.optString("action").orEmpty().lowercase()) {
                "set_authorization" -> if (invocation.isRunning) "Updating Agent Mode authorization" else "Updated Agent Mode authorization"
                "request_shizuku_permission" -> if (invocation.isRunning) "Requesting Shizuku permission" else "Requested Shizuku permission"
                "stop_display" -> if (invocation.isRunning) "Stopping Agent Mode display" else "Stopped Agent Mode display"
                "refresh_displays" -> if (invocation.isRunning) "Refreshing Agent Mode displays" else "Refreshed Agent Mode displays"
                else -> if (invocation.isRunning) "Checking Agent Mode authorization" else "Checked Agent Mode authorization"
            }

            "aether_developer_manage" -> if (invocation.isRunning) "Reading Aether diagnostics" else "Read Aether diagnostics"
            else -> when {
                isAgentModeDisplayToolName(invocation.toolName) ->
                    if (invocation.isRunning) "Starting computer" else "Computer ready"
                invocation.isRunning -> "Using ${invocation.toolName}"
                else -> "Used ${invocation.toolName}"
            }
        }
    }

    private fun formatAetherReasoningCategories(arguments: JSONObject?): String {
        val categories = arguments?.optJSONArray("categories") ?: return ""
        return buildList {
            for (index in 0 until categories.length()) {
                val value = categories.optString(index).trim()
                if (value.isNotBlank()) add(value)
            }
        }.joinToString(", ")
    }

    private fun aetherSkillReasoningVerb(
        arguments: JSONObject?,
        running: Boolean,
    ): String = when (arguments?.optString("action").orEmpty().lowercase()) {
        "install_remote" -> if (running) "Installing" else "Installed"
        "remove" -> if (running) "Removing" else "Removed"
        "set_enabled" -> if (running) "Updating" else "Updated"
        else -> if (running) "Reading" else "Read"
    }

    private fun aetherMcpReasoningVerb(
        arguments: JSONObject?,
        running: Boolean,
    ): String = when (arguments?.optString("action").orEmpty().lowercase()) {
        "upsert_streamable_http", "upsert_stdio" -> if (running) "Saving" else "Saved"
        "remove" -> if (running) "Removing" else "Removed"
        "set_enabled" -> if (running) "Updating" else "Updated"
        else -> if (running) "Reading" else "Read"
    }

    private fun formatReasoningToolAction(
        isRunning: Boolean,
        runningVerb: String,
        completedVerb: String,
        subject: String,
        fallback: String,
    ): String {
        val action = if (isRunning) runningVerb else completedVerb
        val normalizedSubject = subject.trim().take(96)
        return if (normalizedSubject.isBlank()) {
            "$action $fallback"
        } else {
            "$action $normalizedSubject"
        }
    }

    private fun ChatToolInvocation.withGuiSteps(encoded: Map<String, String>): ChatToolInvocation {
        val json = encoded[id] ?: return this
        return if (guiStepsJson == json) this else copy(guiStepsJson = json)
    }

    private fun AssistantResponseBlock.withGuiSteps(encoded: Map<String, String>): AssistantResponseBlock = when (this) {
        is AssistantResponseBlock.ToolGroup -> {
            val tools = toolInvocations.map { it.withGuiSteps(encoded) }
            if (tools == toolInvocations) this else copy(toolInvocations = tools)
        }
        is AssistantResponseBlock.Reasoning -> {
            val tools = trace.toolInvocations.map { it.withGuiSteps(encoded) }
            if (tools == trace.toolInvocations) this else copy(trace = trace.copy(toolInvocations = tools))
        }
        else -> this
    }

    private fun ChatMessage.withGuiSteps(encoded: Map<String, String>): ChatMessage {
        val tools = toolInvocations.map { it.withGuiSteps(encoded) }
        val trace = reasoningTrace?.let { current ->
            val patched = current.toolInvocations.map { it.withGuiSteps(encoded) }
            if (patched == current.toolInvocations) current else current.copy(toolInvocations = patched)
        }
        if (tools == toolInvocations && trace == reasoningTrace) return this
        return copy(toolInvocations = tools, reasoningTrace = trace)
    }

    private fun upsertToolInvocation(
        invocations: List<ChatToolInvocation>,
        toolInvocation: ChatToolInvocation,
    ): List<ChatToolInvocation> {
        val nowUptime = SystemClock.uptimeMillis()
        val nowMillis = System.currentTimeMillis()
        val existingIndex = invocations.indexOfFirst { it.id == toolInvocation.id }
        val normalized = if (existingIndex < 0) {
            toolInvocation.copy(
                startedAtUptimeMillis = toolInvocation.startedAtUptimeMillis.takeIf { it > 0L } ?: nowUptime,
                completedAtUptimeMillis = if (toolInvocation.isRunning) {
                    null
                } else {
                    toolInvocation.completedAtUptimeMillis ?: nowUptime
                },
                startedAtMillis = toolInvocation.startedAtMillis.takeIf { it > 0L } ?: nowMillis,
                completedAtMillis = if (toolInvocation.isRunning) {
                    null
                } else {
                    toolInvocation.completedAtMillis ?: nowMillis
                },
            )
        } else {
            val existing = invocations[existingIndex]
            toolInvocation.copy(
                startedAtUptimeMillis = existing.startedAtUptimeMillis
                    .takeIf { it > 0L }
                    ?: toolInvocation.startedAtUptimeMillis.takeIf { it > 0L }
                    ?: nowUptime,
                completedAtUptimeMillis = if (toolInvocation.isRunning) {
                    null
                } else {
                    toolInvocation.completedAtUptimeMillis
                        ?: existing.completedAtUptimeMillis
                        ?: nowUptime
                },
                startedAtMillis = existing.startedAtMillis
                    .takeIf { it > 0L }
                    ?: toolInvocation.startedAtMillis.takeIf { it > 0L }
                    ?: nowMillis,
                completedAtMillis = if (toolInvocation.isRunning) {
                    null
                } else {
                    toolInvocation.completedAtMillis
                        ?: existing.completedAtMillis
                        ?: nowMillis
                },
                timelineOrder = existing.timelineOrder
                    .takeIf { it > 0L }
                    ?: toolInvocation.timelineOrder,
                guiStepsJson = toolInvocation.guiStepsJson.ifBlank { existing.guiStepsJson },
            )
        }
        return if (existingIndex < 0) {
            invocations + normalized
        } else {
            invocations.toMutableList().apply { set(existingIndex, normalized) }
        }
    }

    private fun appendAssistantResponseText(
        handle: SessionExecutionHandle,
        blocks: List<AssistantResponseBlock>,
        delta: String,
        newBlockId: () -> String,
    ): List<AssistantResponseBlock> {
        if (delta.isEmpty()) return blocks
        val lastBlock = blocks.lastOrNull()
        return if (lastBlock is AssistantResponseBlock.Text) {
            val builder = handle.assistantTextBuilders.getOrPut(lastBlock.id) {
                StringBuilder(lastBlock.text)
            }
            builder.append(delta)
            blocks.toMutableList().apply {
                set(lastIndex, lastBlock.copy(text = builder.toString()))
            }
        } else {
            val blockId = newBlockId()
            handle.assistantTextBuilders[blockId] = StringBuilder(delta)
            blocks + AssistantResponseBlock.Text(
                id = blockId,
                text = delta,
            )
        }
    }

    private fun appendReasoningDelta(
        handle: SessionExecutionHandle,
        delta: String,
    ) {
        val now = System.currentTimeMillis()
        var activeBlockId = handle.activeReasoningBlockId
        updateExecutionState(handle.sessionId, ExecutionPublish.Coalesced) { current ->
            val blocks = completePendingReconnectBlocks(current.pendingResponseBlocks).toMutableList()
            val activeIndex = activeBlockId?.let { id ->
                blocks.indexOfFirst { it is AssistantResponseBlock.Reasoning && it.id == id }
            } ?: -1
            if (activeIndex >= 0) {
                val block = blocks[activeIndex] as AssistantResponseBlock.Reasoning
                val builder = handle.reasoningTextBuilders.getOrPut(block.id) {
                    StringBuilder(block.trace.rawText)
                }
                builder.append(delta)
                if (builder.length > ReasoningRawTextWindowChars) {
                    builder.delete(0, builder.length - ReasoningRawTextWindowChars)
                }
                blocks[activeIndex] = block.copy(
                    trace = block.trace.copy(rawText = builder.toString()),
                )
            } else {
                val blockId = handle.nextPendingBlockId("pending-reasoning")
                activeBlockId = blockId
                handle.startReasoningBlock(blockId, now)
                val builder = StringBuilder(delta)
                if (builder.length > ReasoningRawTextWindowChars) {
                    builder.delete(0, builder.length - ReasoningRawTextWindowChars)
                }
                handle.reasoningTextBuilders[blockId] = builder
                blocks += AssistantResponseBlock.Reasoning(
                    id = blockId,
                    trace = ReasoningTrace(
                        id = blockId,
                        rawText = builder.toString(),
                        startedAtMillis = now,
                    ),
                )
            }
            current.copy(
                pendingStatusText = "",
                pendingStatusDetail = "",
                pendingResponseBlocks = blocks,
            )
        }

        val blockId = activeBlockId ?: return
        val trace = currentReasoningTrace(handle.sessionId, blockId) ?: return
        maybeSubmitReasoningSummary(
            handle = handle,
            trace = trace,
            forceRemaining = false,
        )
    }

    private fun appendDirectReasoningSummaryDelta(
        handle: SessionExecutionHandle,
        delta: String,
    ) {
        val now = System.currentTimeMillis()
        var activeBlockId = handle.activeReasoningBlockId
        updateExecutionState(handle.sessionId, ExecutionPublish.Coalesced) { current ->
            val blocks = current.pendingResponseBlocks.toMutableList()
            val activeIndex = activeBlockId?.let { id ->
                blocks.indexOfFirst { it is AssistantResponseBlock.Reasoning && it.id == id }
            } ?: -1
            val blockIndex = if (activeIndex >= 0) {
                activeIndex
            } else {
                val blockId = handle.nextPendingBlockId("pending-reasoning")
                activeBlockId = blockId
                handle.startReasoningBlock(blockId, now)
                blocks += AssistantResponseBlock.Reasoning(
                    id = blockId,
                    trace = ReasoningTrace(
                        id = blockId,
                        startedAtMillis = now,
                    ),
                )
                blocks.lastIndex
            }

            val block = blocks[blockIndex] as AssistantResponseBlock.Reasoning
            val chunkId = handle.activeDirectReasoningSummaryChunkId
                ?.takeIf { id -> block.trace.chunks.any { it.id == id } }
                ?: handle.nextReasoningChunkId(block.id).also { newChunkId ->
                    handle.activeDirectReasoningSummaryChunkId = newChunkId
                }
            val existingChunk = block.trace.chunks.firstOrNull { it.id == chunkId }
            val updatedDetail = existingChunk?.detail.orEmpty() + delta
            val updatedChunks = if (existingChunk == null) {
                block.trace.chunks + ReasoningSummaryChunk(
                    id = chunkId,
                    title = "Reasoning",
                    detail = updatedDetail,
                    isPending = false,
                    createdAtMillis = now,
                    timelineOrder = handle.nextReasoningTimelineOrder(),
                )
            } else {
                block.trace.chunks.map { chunk ->
                    if (chunk.id == chunkId) {
                        chunk.copy(
                            detail = updatedDetail,
                            isPending = false,
                        )
                    } else {
                        chunk
                    }
                }
            }
            blocks[blockIndex] = block.copy(
                trace = block.trace.copy(
                    chunks = updatedChunks,
                    latestStatusText = updatedDetail,
                )
            )
            current.copy(
                pendingStatusText = "",
                pendingStatusDetail = "",
                pendingResponseBlocks = blocks,
            )
        }
    }

    private fun completeActiveReasoning(
        handle: SessionExecutionHandle,
        trigger: ReasoningCompletionTrigger,
    ) {
        val blockId = handle.activeReasoningBlockId ?: return
        val now = System.currentTimeMillis()
        val trace = currentReasoningTrace(handle.sessionId, blockId) ?: run {
            handle.finishReasoningBlock()
            return
        }
        if (trace.rawText.isNotBlank()) {
            maybeSubmitReasoningSummary(
                handle = handle,
                trace = trace,
                forceRemaining = true,
            )
        }
        updateExecutionState(handle.sessionId) { current ->
            current.copy(
                pendingResponseBlocks = current.pendingResponseBlocks.map { block ->
                    if (block is AssistantResponseBlock.Reasoning && block.id == blockId) {
                        block.copy(
                            trace = block.trace.copy(
                                completedAtMillis = block.trace.completedAtMillis ?: now,
                            )
                        )
                    } else {
                        block
                    }
                }
            )
        }
        handle.finishReasoningBlock()
    }

    private fun flushActiveReasoningSummary(
        handle: SessionExecutionHandle,
    ) {
        val blockId = handle.activeReasoningBlockId ?: return
        val trace = currentReasoningTrace(handle.sessionId, blockId) ?: return
        if (trace.rawText.isBlank()) return
        maybeSubmitReasoningSummary(
            handle = handle,
            trace = trace,
            forceRemaining = true,
        )
    }

    @Suppress("UNUSED_PARAMETER")
    private fun maybeSubmitReasoningSummary(
        handle: SessionExecutionHandle,
        trace: ReasoningTrace,
        forceRemaining: Boolean,
    ) {
        // A second completeOnce() shares the ACP process with the live turn
        // and restarts it when the title model differs. Show raw reasoning.
    }

    fun replaceAgentSlashCommands(payload: JSONObject) {
        val parsed = parseAgentSlashCommands(payload)
        if (parsed.isNotEmpty()) {
            _agentSlashCommands.value = parsed
        }
    }

    private fun submitReasoningSummary(
        handle: SessionExecutionHandle,
        blockId: String,
        rawText: String,
    ) {
        val trimmed = rawText.trim()
        if (trimmed.isBlank()) return
        val chunkId = handle.nextReasoningChunkId(blockId)
        val createdAtMillis = System.currentTimeMillis()
        val timelineOrder = handle.nextReasoningTimelineOrder()
        updateReasoningTrace(handle.sessionId, blockId) { trace ->
            trace.copy(
                chunks = trace.chunks + ReasoningSummaryChunk(
                    id = chunkId,
                    rawText = trimmed,
                    isPending = true,
                    createdAtMillis = createdAtMillis,
                    timelineOrder = timelineOrder,
                )
            )
        }

        scope.launch(Dispatchers.IO) {
            val summary = summarizeReasoningChunk(trimmed) ?: fallbackReasoningSummary(trimmed)
            updateReasoningTrace(handle.sessionId, blockId) { trace ->
                val latestStatusText = summary.detail.ifBlank { summary.title }
                trace.copy(
                    chunks = trace.chunks.map { chunk ->
                        if (chunk.id != chunkId) {
                            chunk
                        } else {
                            chunk.copy(
                                title = summary.title,
                                detail = summary.detail,
                                isPending = false,
                            )
                        }
                    },
                    latestStatusText = latestStatusText,
                )
            }
        }
    }

    private suspend fun summarizeReasoningChunk(
        rawText: String,
    ): ReasoningSummary? {
        val settings = currentSettings.value
        val providerConfigs = currentProviderConfigs.value
        val titleSettings = resolveModelSettings(
            baseSettings = settings,
            providerConfigs = providerConfigs,
            preferredModelKey = resolveDefaultTitleModelKey(settings, providerConfigs),
            fallbackModelKey = resolveDefaultChatModelKey(settings, providerConfigs),
        )
        if (!titleSettings.isProviderSetupValid()) {
            return null
        }
        val modelKey = thinkingCatalogKey(titleSettings.piProviderId, titleSettings.modelId)
        val thinkingLevelMap = settingsRepository.loadThinkingLevelMapsCache()[modelKey].orEmpty()
        val isReasoningModel = settingsRepository.loadThinkingCatalogCache()[modelKey]
            .orEmpty().isNotEmpty()
        val prompt = buildString {
            appendLine("Summarize this assistant reasoning excerpt for a user-visible thinking timeline.")
            appendLine("Return exactly two short paragraphs: first a concise title, then one detail paragraph.")
            appendLine("Title style: a short gerund or noun phrase about the purpose or outcome, without 'I', 'The assistant', or a tool-action headline.")
            appendLine("Detail style: natural first-person planning language. 'I need to...', 'I should...', 'I will...', and 'I am...' are all acceptable when they fit.")
            appendLine("Never write from a third-person assistant perspective such as 'The assistant is...' or 'The model is...'.")
            appendLine("Do not mention that this is a summary, do not add bullets, and do not invent context.")
            appendLine()
            appendLine("Use this style:")
            appendLine("Providing accurate and properly cited documentation")
            appendLine()
            appendLine("I need to make sure I include citations for all factual information, especially from official docs, since I haven't performed any live API tests. It's essential to clarify that my info is based on public documentation and mention the safety of returning raw reasoning in OpenRouter. I should avoid long CoT examples.")
            appendLine()
            appendLine("Reasoning excerpt:")
            append(rawText.take(ReasoningSummaryMaxInputChars))
        }
        val result = piCompletionClient?.completeOnce(
            settings = titleSettings,
            systemPrompt = ReasoningSummarySystemPrompt,
            messages = listOf(
                LlmMessage(
                    role = "user",
                    contentParts = listOf(LlmTextPart(prompt)),
                )
            ),
            disableReasoning = true,
            thinkingLevelMap = thinkingLevelMap,
            isReasoningModel = isReasoningModel,
        )?.getOrNull()?.assistantText?.trim().orEmpty()
        return parseReasoningSummary(result)
    }

    private fun fallbackReasoningSummary(rawText: String): ReasoningSummary {
        val compact = rawText
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
        return ReasoningSummary(
            title = "Thinking through the next step",
            detail = compact
                .take(ReasoningSummaryDetailMaxChars)
                .ifBlank { "Preparing the next action." },
        )
    }

    private fun parseReasoningSummary(text: String): ReasoningSummary? {
        val lines = text.lines().map(String::trim).filter(String::isNotBlank)
        if (lines.isEmpty()) return null
        val title = lines.first().trim('"').take(ReasoningSummaryTitleMaxChars)
        val detail = lines.drop(1)
            .joinToString(" ")
            .trim()
            .ifBlank { title }
            .take(ReasoningSummaryDetailMaxChars)
        return ReasoningSummary(title = title, detail = detail)
    }

    private fun currentReasoningTrace(
        sessionId: String,
        blockId: String,
    ): ReasoningTrace? = _executionStates.value[sessionId]
        ?.pendingResponseBlocks
        ?.firstOrNull { it is AssistantResponseBlock.Reasoning && it.id == blockId }
        ?.let { (it as AssistantResponseBlock.Reasoning).trace }

    private fun updateReasoningTrace(
        sessionId: String,
        blockId: String,
        transform: (ReasoningTrace) -> ReasoningTrace,
    ) {
        updateExecutionState(sessionId) { current ->
            current.copy(
                pendingResponseBlocks = current.pendingResponseBlocks.map { block ->
                    if (block is AssistantResponseBlock.Reasoning && block.id == blockId) {
                        block.copy(trace = transform(block.trace))
                    } else {
                        block
                    }
                }
            )
        }
        updatePersistedReasoningTrace(
            sessionId = sessionId,
            blockId = blockId,
            transform = transform,
        )
    }

    private fun updatePersistedReasoningTrace(
        sessionId: String,
        blockId: String,
        transform: (ReasoningTrace) -> ReasoningTrace,
    ) {
        val hasPersistedTrace = chatStateStore.state.value.sessions.any { session ->
            session.id == sessionId && session.messages.any { it.reasoningTrace?.id == blockId }
        }
        if (!hasPersistedTrace) return

        chatStateStore.update { persisted ->
            val sessionIndex = persisted.sessions.indexOfFirst { it.id == sessionId }
            if (sessionIndex < 0) return@update persisted

            val session = persisted.sessions[sessionIndex]
            var changed = false
            val updatedMessages = session.messages.map { message ->
                val trace = message.reasoningTrace
                if (trace == null || trace.id != blockId) {
                    message
                } else {
                    val updatedTrace = transform(trace)
                    if (updatedTrace == trace) {
                        message
                    } else {
                        changed = true
                        message.copy(
                            reasoningTrace = updatedTrace,
                            toolInvocations = updatedTrace.toolInvocations,
                        )
                    }
                }
            }
            if (!changed) return@update persisted

            val updatedSessions = persisted.sessions.toMutableList()
            updatedSessions[sessionIndex] = session.withDerivedMessages(updatedMessages)
            persisted.copy(sessions = updatedSessions)
        }
    }

    private fun upsertAssistantResponseToolInvocation(
        blocks: List<AssistantResponseBlock>,
        toolInvocation: ChatToolInvocation,
        reasoningBlockId: String?,
        newBlockId: () -> String,
    ): List<AssistantResponseBlock> {
        val existingReasoningIndex = blocks.indexOfFirst { block ->
            block is AssistantResponseBlock.Reasoning &&
                block.trace.toolInvocations.any { it.id == toolInvocation.id }
        }
        if (existingReasoningIndex >= 0) {
            val reasoningBlock = blocks[existingReasoningIndex] as AssistantResponseBlock.Reasoning
            return blocks.toMutableList().apply {
                set(
                    existingReasoningIndex,
                    reasoningBlock.copy(
                        trace = reasoningBlock.trace.copy(
                            toolInvocations = upsertToolInvocation(
                                reasoningBlock.trace.toolInvocations,
                                toolInvocation,
                            ),
                            latestStatusText = formatReasoningToolStatus(toolInvocation),
                        ),
                    )
                )
            }
        }

        val reasoningIndex = reasoningBlockId?.let { id ->
            blocks.indexOfFirst { it is AssistantResponseBlock.Reasoning && it.id == id }
        } ?: -1
        if (reasoningIndex >= 0) {
            val reasoningBlock = blocks[reasoningIndex] as AssistantResponseBlock.Reasoning
            return blocks.toMutableList().apply {
                set(
                    reasoningIndex,
                    reasoningBlock.copy(
                        trace = reasoningBlock.trace.copy(
                            toolInvocations = upsertToolInvocation(
                                reasoningBlock.trace.toolInvocations,
                                toolInvocation,
                            ),
                            latestStatusText = formatReasoningToolStatus(toolInvocation),
                        ),
                    )
                )
            }
        }

        val existingIndex = blocks.indexOfFirst { block ->
            block is AssistantResponseBlock.ToolGroup &&
                block.toolInvocations.any { it.id == toolInvocation.id }
        }
        if (existingIndex >= 0) {
            val toolBlock = blocks[existingIndex] as AssistantResponseBlock.ToolGroup
            return blocks.toMutableList().apply {
                set(
                    existingIndex,
                    toolBlock.copy(
                        toolInvocations = upsertToolInvocation(toolBlock.toolInvocations, toolInvocation),
                    ),
                )
            }
        }

        val lastBlock = blocks.lastOrNull()
        return if (lastBlock is AssistantResponseBlock.ToolGroup) {
            blocks.toMutableList().apply {
                set(
                    lastIndex,
                    lastBlock.copy(
                        toolInvocations = upsertToolInvocation(lastBlock.toolInvocations, toolInvocation),
                    ),
                )
            }
        } else {
            blocks + AssistantResponseBlock.ToolGroup(
                id = newBlockId(),
                toolInvocations = upsertToolInvocation(emptyList(), toolInvocation),
            )
        }
    }

    private fun pendingTrailingAssistantText(
        blocks: List<AssistantResponseBlock>,
    ): String = (blocks.lastOrNull() as? AssistantResponseBlock.Text)?.text.orEmpty()

    private fun ensureVisibleAssistantReply(
        blocks: List<AssistantResponseBlock>,
        emptyReplyText: String,
        newBlockId: () -> String = { "empty-reply-${System.currentTimeMillis()}" },
    ): List<AssistantResponseBlock> {
        val normalized = normalizeAssistantResponseBlocks(blocks)
        if (normalized.any(::assistantResponseBlockIsVisible)) return normalized
        return normalized + AssistantResponseBlock.Text(
            id = newBlockId(),
            text = emptyReplyText,
        )
    }

    private fun assistantResponseBlockIsVisible(block: AssistantResponseBlock): Boolean = when (block) {
        is AssistantResponseBlock.Text -> block.text.isNotBlank()
        is AssistantResponseBlock.ToolGroup -> false
        is AssistantResponseBlock.Reasoning -> false
        is AssistantResponseBlock.Status -> block.text.isNotBlank()
    }

    private fun ensureAssistantResponseFinalText(
        blocks: List<AssistantResponseBlock>,
        finalText: String,
        newBlockId: () -> String,
    ): List<AssistantResponseBlock> {
        if (finalText.isBlank()) return normalizeAssistantResponseBlocks(blocks)
        val normalized = normalizeAssistantResponseBlocks(blocks)
        val lastTextIndex = normalized.indexOfLast { it is AssistantResponseBlock.Text }
        if (lastTextIndex < 0) {
            return normalized + AssistantResponseBlock.Text(
                id = newBlockId(),
                text = finalText,
            )
        }
        val lastTextBlock = normalized[lastTextIndex] as AssistantResponseBlock.Text
        if (lastTextBlock.text == finalText) return normalized
        return normalized.toMutableList().apply {
            set(lastTextIndex, lastTextBlock.copy(text = finalText))
        }
    }

    private fun normalizeAssistantResponseBlocks(
        blocks: List<AssistantResponseBlock>,
    ): List<AssistantResponseBlock> = buildList {
        blocks.forEach { block ->
            when (block) {
                is AssistantResponseBlock.Text -> {
                    if (block.text.isBlank()) return@forEach
                    val previous = lastOrNull()
                    if (previous is AssistantResponseBlock.Text) {
                        removeAt(lastIndex)
                        add(previous.copy(text = previous.text + block.text))
                    } else {
                        add(block)
                    }
                }

                is AssistantResponseBlock.ToolGroup -> {
                    if (block.toolInvocations.isEmpty()) return@forEach
                    val previous = lastOrNull()
                    if (previous is AssistantResponseBlock.ToolGroup) {
                        removeAt(lastIndex)
                        add(
                            previous.copy(
                                toolInvocations = previous.toolInvocations + block.toolInvocations,
                            ),
                        )
                    } else {
                        add(block)
                    }
                }

                is AssistantResponseBlock.Reasoning -> {
                    if (
                        block.trace.rawText.isBlank() &&
                        block.trace.chunks.isEmpty() &&
                        block.trace.toolInvocations.isEmpty()
                    ) {
                        return@forEach
                    }
                    add(block)
                }

                is AssistantResponseBlock.Status -> if (block.text.isNotBlank()) add(block)
            }
        }
    }

    private fun List<AssistantResponseBlock>.toolInvocations(): List<ChatToolInvocation> =
        flatMap { block ->
            when (block) {
                is AssistantResponseBlock.ToolGroup -> block.toolInvocations
                is AssistantResponseBlock.Text -> emptyList()
                is AssistantResponseBlock.Reasoning -> block.trace.toolInvocations
                is AssistantResponseBlock.Status -> emptyList()
            }
        }.distinctBy { it.id }

    private fun finalizeInterruptedAssistantResponseBlocks(
        blocks: List<AssistantResponseBlock>,
    ): List<AssistantResponseBlock> = normalizeAssistantResponseBlocks(
        blocks.map { block ->
            when (block) {
                is AssistantResponseBlock.Text -> block
                is AssistantResponseBlock.Reasoning -> block.copy(
                    trace = block.trace.copy(
                        toolInvocations = finalizeInterruptedToolInvocations(block.trace.toolInvocations),
                        completedAtMillis = block.trace.completedAtMillis ?: System.currentTimeMillis(),
                    )
                )
                is AssistantResponseBlock.ToolGroup -> block.copy(
                    toolInvocations = finalizeInterruptedToolInvocations(block.toolInvocations),
                )
                is AssistantResponseBlock.Status -> block.copy(
                    text = completedReconnectStatus(block.text),
                )
            }
        }
    )

    private fun assistantResponseBlockSummaryText(
        block: AssistantResponseBlock,
    ): String = when (block) {
        is AssistantResponseBlock.Text -> block.text.trim()
        is AssistantResponseBlock.ToolGroup -> ChatMessage(
            id = block.id,
            author = MessageAuthor.Agent,
            text = "",
            toolInvocations = block.toolInvocations,
        ).summaryText()
        is AssistantResponseBlock.Reasoning -> block.trace.chunks.lastOrNull { chunk ->
            chunk.detail.isNotBlank() || chunk.title.isNotBlank()
        }?.let { chunk ->
            chunk.detail.ifBlank { chunk.title }
        } ?: "Thought for ${formatReasoningDurationLabel(block.trace)}"
        is AssistantResponseBlock.Status -> block.text
    }

    private fun finalizeInterruptedToolInvocations(
        invocations: List<ChatToolInvocation>,
    ): List<ChatToolInvocation> = invocations.map { invocation ->
        if (!isInterruptedToolInvocation(invocation)) {
            invocation
        } else {
            invocation.copy(
                isRunning = false,
                outputJson = buildInterruptedToolOutput(invocation),
                completedAtUptimeMillis = invocation.completedAtUptimeMillis ?: SystemClock.uptimeMillis(),
                completedAtMillis = invocation.completedAtMillis ?: System.currentTimeMillis(),
            )
        }
    }

    private fun isInterruptedToolInvocation(invocation: ChatToolInvocation): Boolean {
        if (invocation.isRunning) return true
        if (invocation.toolName.lowercase() != "bash") return false
        val output = parseJsonObject(invocation.outputJson) ?: return false
        return output.optString("status") == "running" || output.optString("status") == "launching"
    }

    private fun buildInterruptedToolOutput(invocation: ChatToolInvocation): String {
        val output = parseJsonObject(invocation.outputJson) ?: JSONObject()
        output.put("ok", false)
        output.put("status", "cancelled")
        output.put("running", false)
        output.put("completed", true)
        if (!output.has("stdout")) output.put("stdout", "")
        if (!output.has("stderr")) output.put("stderr", "")
        if (!output.has("exit_code")) output.put("exit_code", 143)
        if (!output.has("err")) output.put("err", -1)
        output.put("errmsg", "Stopped by user.")
        return output.toString()
    }

    private fun extractActiveManagedRunIds(
        invocations: List<ChatToolInvocation>,
    ): List<String> = invocations.mapNotNull { invocation ->
        if (invocation.toolName.lowercase() != "bash") return@mapNotNull null
        val output = parseJsonObject(invocation.outputJson) ?: return@mapNotNull null
        val status = output.optString("status")
        if (status != "running" && status != "launching") {
            return@mapNotNull null
        }
        output.optString("run_id").trim().ifBlank { null }
    }.distinct()

    private fun parseJsonObject(rawValue: String): JSONObject? =
        if (rawValue.isBlank()) null else runCatching { JSONObject(rawValue) }.getOrNull()

    private fun approximateReasoningTokenCount(text: String): Int {
        var count = 0
        var inToken = false
        text.forEach { char ->
            when {
                char.isWhitespace() -> inToken = false
                isCjkReasoningChar(char) -> {
                    count += 1
                    inToken = false
                }
                !inToken -> {
                    count += 1
                    inToken = true
                }
            }
        }
        return count
    }

    private fun estimateRequestTokenUsage(request: SessionTurnRequest): LlmTokenUsage {
        val inputTokens = estimateChatMessagesTokens(request.requestMessages).toLong()
        return LlmTokenUsage(
            inputTokens = inputTokens,
            totalTokens = inputTokens,
            requestCount = 1,
        )
    }

    private fun estimateChatMessagesTokens(messages: List<ChatMessage>): Int =
        messages.sumOf { message ->
            approximateReasoningTokenCount(message.text) +
                message.attachments.sumOf(::estimateAttachmentTokens)
        }

    private fun estimateAttachmentTokens(attachment: ChatAttachment): Int =
        approximateReasoningTokenCount(attachment.name) +
            when (attachment.kind) {
                AttachmentKind.Image -> 85
                AttachmentKind.File -> {
                    val sizeBytes = attachment.sizeBytes ?: 0L
                    if (sizeBytes > 0L) {
                        (sizeBytes / 4L).coerceAtMost(16_000L).toInt()
                    } else {
                        0
                    }
                }
            }

    private fun takeApproximateReasoningTokens(
        text: String,
        maxTokens: Int,
    ): String {
        if (maxTokens <= 0) return ""
        var count = 0
        var inToken = false
        for (index in text.indices) {
            val char = text[index]
            when {
                char.isWhitespace() -> inToken = false
                isCjkReasoningChar(char) -> {
                    count += 1
                    inToken = false
                }
                !inToken -> {
                    count += 1
                    inToken = true
                }
            }
            if (count >= maxTokens) {
                return text.substring(0, index + 1)
            }
        }
        return text
    }

    private fun isCjkReasoningChar(char: Char): Boolean {
        val block = Character.UnicodeBlock.of(char)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
            block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
            block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA ||
            block == Character.UnicodeBlock.HANGUL_SYLLABLES
    }

    private data class PendingEnvelope(
        val id: String,
        val mode: SessionFollowUpMode,
        val message: ChatMessage,
    ) {
        fun toUiState(): PendingSessionInput = PendingSessionInput(
            id = id,
            mode = mode,
            preview = message.summaryText().take(72),
            attachmentCount = message.attachments.size,
        )
    }

    private data class CompletionSummary(
        val sessionTitle: String,
        val summary: String,
        val outcome: SessionTurnOutcome,
        val toolCallCount: Int,
        val distinctToolCount: Int,
        val toolNames: List<String>,
        val durationMillis: Long?,
        val tokenUsage: LlmTokenUsage? = null,
        val tokenUsageSource: String = "unavailable",
        val inputMessageCount: Int = 0,
        val userMessageCount: Int = 0,
        val appendedMessageIds: List<String> = emptyList(),
        val piSessionId: String = "",
        val piSessionFile: String = "",
        val piRuntime: String = "",
        val piEntryIds: List<String> = emptyList(),
    ) {
        fun toTurnEvent(sessionId: String): SessionTurnEvent = SessionTurnEvent(
            sessionId = sessionId,
            outcome = outcome,
            toolCallCount = toolCallCount,
            distinctToolCount = distinctToolCount,
            toolNames = toolNames,
            durationMillis = durationMillis,
            tokenUsage = tokenUsage,
            tokenUsageSource = tokenUsageSource,
            inputMessageCount = inputMessageCount,
            userMessageCount = userMessageCount,
        )
    }

    private class SessionExecutionHandle(
        val sessionId: String,
    ) {
        val lock = Any()
        val queuedInputs = ArrayDeque<PendingEnvelope>()
        val steerInputs = ArrayDeque<PendingEnvelope>()
        val committedSteerIds = mutableSetOf<String>()
        private var retainedMessages: List<ChatMessage> = emptyList()
        private var pendingBlockCounter: Long = 0
        private var reasoningChunkCounter: Long = 0
        private var reasoningTimelineCounter: Long = 0
        var activeReasoningBlockId: String? = null
        var activeDirectReasoningSummaryChunkId: String? = null
        var reasoningFirstSummarySubmitted: Boolean = false
        var reasoningLastSubmittedCharIndex: Int = 0
        var reasoningLastTimedSummaryAtMillis: Long = 0L
        @Volatile
        var activeResponseIdentity: AssistantResponseIdentity? = null
        var lastAssistantCheckpointUptimeMillis: Long? = null
        var assistantCheckpointJob: Job? = null
        val assistantTextBuilders = mutableMapOf<String, StringBuilder>()
        val reasoningTextBuilders = mutableMapOf<String, StringBuilder>()

        @Volatile
        var pauseRequested: Boolean = false

        @Volatile
        var pauseFinalized: Boolean = false

        @Volatile
        var job: Job? = null

        fun nextPendingBlockId(prefix: String): String {
            val nextId = pendingBlockCounter
            pendingBlockCounter += 1
            return "$prefix-$sessionId-$nextId"
        }

        fun nextReasoningChunkId(blockId: String): String {
            val nextId = reasoningChunkCounter
            reasoningChunkCounter += 1
            return "$blockId-summary-$nextId"
        }

        fun nextReasoningTimelineOrder(): Long {
            reasoningTimelineCounter += 1
            return reasoningTimelineCounter
        }

        fun startReasoningBlock(blockId: String, nowMillis: Long) {
            activeReasoningBlockId = blockId
            activeDirectReasoningSummaryChunkId = null
            reasoningFirstSummarySubmitted = false
            reasoningLastSubmittedCharIndex = 0
            reasoningLastTimedSummaryAtMillis = nowMillis
        }

        fun finishDirectReasoningSummaryChunk() {
            activeDirectReasoningSummaryChunkId = null
        }

        fun finishReasoningBlock() {
            activeReasoningBlockId?.let { reasoningTextBuilders.remove(it) }
            activeReasoningBlockId = null
            activeDirectReasoningSummaryChunkId = null
            reasoningFirstSummarySubmitted = false
            reasoningLastSubmittedCharIndex = 0
            reasoningLastTimedSummaryAtMillis = 0L
        }

        fun providerRequestCheckpoint(state: SessionExecutionState): ProviderRequestCheckpoint =
            synchronized(lock) {
                ProviderRequestCheckpoint(
                    pendingToolInvocations = state.pendingToolInvocations,
                    pendingResponseBlocks = state.pendingResponseBlocks,
                    pendingAssistantText = state.pendingAssistantText,
                    activeReasoningBlockId = activeReasoningBlockId,
                    activeDirectReasoningSummaryChunkId = activeDirectReasoningSummaryChunkId,
                    reasoningFirstSummarySubmitted = reasoningFirstSummarySubmitted,
                    reasoningLastSubmittedCharIndex = reasoningLastSubmittedCharIndex,
                    reasoningLastTimedSummaryAtMillis = reasoningLastTimedSummaryAtMillis,
                )
            }

        fun restoreProviderRequestCheckpoint(checkpoint: ProviderRequestCheckpoint) {
            synchronized(lock) {
                activeReasoningBlockId = checkpoint.activeReasoningBlockId
                activeDirectReasoningSummaryChunkId = checkpoint.activeDirectReasoningSummaryChunkId
                reasoningFirstSummarySubmitted = checkpoint.reasoningFirstSummarySubmitted
                reasoningLastSubmittedCharIndex = checkpoint.reasoningLastSubmittedCharIndex
                reasoningLastTimedSummaryAtMillis = checkpoint.reasoningLastTimedSummaryAtMillis
            }
        }

        fun retainedMessagesSnapshot(): List<ChatMessage> = synchronized(lock) {
            retainedMessages
        }

        fun replaceRetainedMessages(messages: List<ChatMessage>) {
            synchronized(lock) {
                retainedMessages = messages
            }
        }
    }
}

internal data class TurnSkillSelection(
    val selectedSkillIds: List<String>,
    val activeSkills: List<ActiveSkillContext>,
)

internal fun resolveTurnSkillSelectionForTest(
    explicitActiveSkills: List<ActiveSkillContext>,
    implicitActiveSkills: List<ActiveSkillContext>,
): TurnSkillSelection {
    val normalizedExplicitSkills = explicitActiveSkills.distinctBy { it.skillId }
    return TurnSkillSelection(
        selectedSkillIds = normalizedExplicitSkills.map { it.skillId },
        activeSkills = (normalizedExplicitSkills + implicitActiveSkills).distinctBy { it.skillId },
    )
}

internal fun shouldInlineWorkspaceImageAttachment(
    attachment: ChatAttachment,
    settings: AppSettings,
): Boolean =
    isWorkspaceImageAttachment(attachment) &&
        settings.modelCapabilities().supportsInlineImageWithTools

private fun canInlineWorkspaceImageAttachment(
    attachment: ChatAttachment,
    settings: AppSettings,
): Boolean =
    shouldInlineWorkspaceImageAttachment(attachment, settings) &&
        attachment.inlineBase64.isNotBlank()

private fun isWorkspaceImageAttachment(attachment: ChatAttachment): Boolean =
    attachment.kind == AttachmentKind.Image &&
        attachment.mimeType.startsWith("image/")
