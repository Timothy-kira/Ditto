package kira.ditto.ui

import android.graphics.BitmapFactory
import android.graphics.Outline
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewOutlineProvider
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import android.widget.Toast
import kira.ditto.R
import kira.ditto.agentmode.AgentModeImeGuard
import kira.ditto.data.InstalledSkill
import kira.ditto.data.AppLanguage
import kira.ditto.data.AgentModeDisplayState
import kira.ditto.ui.TtsPlaybackState
import kira.ditto.ui.LocalTtsPlayback
import kira.ditto.data.AppSettings
import kira.ditto.data.AsrEngineRouter
import kira.ditto.data.composerAsrVisualLevel
import kira.ditto.data.ComposerAsrSession
import kira.ditto.data.LlmProviderConfig
import kira.ditto.data.PhoneDeskHandoffState
import kira.ditto.data.PhoneSettlementUiState
import kira.ditto.data.isAgentModeDisplayToolName
import kira.ditto.data.DeviceCatalogMcp
import kira.ditto.data.GmailMcp
import kira.ditto.data.EverMeTools
import kira.ditto.data.SessionNoteTools
import kira.ditto.data.injectAmapPlaceMarkup
import kira.ditto.data.SpotifyMcp
import kira.ditto.data.GithubMcp
import kira.ditto.data.HuggingFaceMcp
import kira.ditto.data.McpServerConfig
import kira.ditto.data.ModelCatalogInfo
import kira.ditto.data.PendingSessionInput
import kira.ditto.data.ProviderModelOption
import kira.ditto.data.SessionContextUsage
import kira.ditto.data.SessionExecutionState
import kira.ditto.data.SessionFollowUpMode
import kira.ditto.data.SessionGoalSnapshot
import kira.ditto.data.SessionPlanEntry
import kira.ditto.data.DefaultKimiPermissionMode
import kira.ditto.data.SupportedKimiPermissionModes
import kira.ditto.data.TurnActivityClock
import kira.ditto.data.sessionPlanEntryIsCompleted
import kira.ditto.data.sessionPlanEntryIsOpen
import kira.ditto.data.chatMessageHostsSessionPlan
import kira.ditto.data.sessionPlanHostKey
import kira.ditto.data.kimi.PendingElicitationRequest
import kira.ditto.data.kimi.PendingPermissionRequest
import kira.ditto.data.quickActionLabel
import kira.ditto.data.thinkingCatalogKey
import kira.ditto.data.browserModeTrailingCitations
import kira.ditto.browser.BrowserDesk
import kira.ditto.browser.BrowserLoginContinueUserText
import kira.ditto.browser.BrowserVerifyContinueUserText
import kira.ditto.browser.collectBrowserInlineImages
import kira.ditto.browser.sanitizeBrowserDeskPreviewForCard
import kira.ditto.data.visibleUserMessageText
import kira.ditto.termux.TermuxSetupState
import kira.ditto.ui.theme.AetherBackground
import kira.ditto.ui.theme.AetherBackgroundGradientTop
import kira.ditto.ui.theme.AetherComposerChipBackground
import kira.ditto.ui.theme.AetherComposerChipForeground
import kira.ditto.ui.theme.AetherMessageBubble
import kira.ditto.ui.theme.AetherOnPrimaryContainer
import kira.ditto.ui.theme.AetherOnSurface
import kira.ditto.ui.theme.AetherOnSurfaceVariant
import kira.ditto.ui.theme.AetherPrimary
import kira.ditto.ui.theme.AetherScrim
import kira.ditto.ui.theme.AetherSurface
import kira.ditto.ui.theme.AetherSurfaceHigh
import kira.ditto.ui.theme.AetherSurfaceHigher
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import org.json.JSONObject
import java.util.Locale

private const val ConversationNestedPrefetchItemCount = 2

/** Items left before the oldest loaded message when the next page is requested. */
private const val OlderMessagesPrefetchDistance = 4

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun conversationLazyListState(
    firstVisibleItemIndex: Int = 0,
    firstVisibleItemScrollOffset: Int = 0,
): LazyListState = LazyListState(
    firstVisibleItemIndex = firstVisibleItemIndex,
    firstVisibleItemScrollOffset = firstVisibleItemScrollOffset,
    prefetchStrategy = LazyListPrefetchStrategy(ConversationNestedPrefetchItemCount),
)

private const val AssistantMarkdownSplitMinChars = 4_000
private const val AssistantMarkdownSplitMinBlocks = 3

private sealed interface ConversationListItem {
    val key: String

    data class Message(
        val message: ChatMessage,
    ) : ConversationListItem {
        override val key: String = message.id
    }

    data class AssistantGroup(
        val messages: List<ChatMessage>,
        val omitBodyMarkdown: Boolean = false,
    ) : ConversationListItem {
        override val key: String = messages.firstOrNull()?.responseGroupId
            ?: messages.firstOrNull()?.id
            ?: "assistant-group"
    }

    data class AssistantMarkdownSlice(
        val groupKey: String,
        val blockIndex: Int,
        val block: MarkdownBlock,
        val isLast: Boolean,
        val messages: List<ChatMessage>,
    ) : ConversationListItem {
        override val key: String = "$groupKey-md-$blockIndex"
    }

    data class CompactStatus(
        val message: ChatMessage,
    ) : ConversationListItem {
        override val key: String = message.id
    }

    fun prefetchMarkdownSource(): String? = when (this) {
        is Message -> message.text.takeIf { it.isNotBlank() }
        is AssistantGroup -> messages.joinToString("\n\n") { it.text }.trim()
            .takeIf { it.isNotBlank() }
        else -> null
    }

    fun prefetchGmailInvocations(): List<ChatToolInvocation> = when (this) {
        is Message -> collectGmailWarmInvocations(
            message.toolInvocations + message.reasoningTrace?.toolInvocations.orEmpty(),
        )
        is AssistantGroup -> collectMessageGmailWarmInvocations(messages)
        is AssistantMarkdownSlice -> collectMessageGmailWarmInvocations(messages)
        else -> emptyList()
    }
}

private fun conversationPrefetchMarkdown(
    items: List<ConversationListItem>,
    visibleKeys: List<Any>,
): List<String> {
    val reversed = items.asReversed()
    val keySet = visibleKeys.map { it.toString() }.toSet()
    val found = reversed.indices.filter { reversed[it].key in keySet }
    if (found.isEmpty()) return emptyList()
    return found.flatMap { index -> listOf(index - 1, index, index + 1, index + 2) }
        .filter { it in reversed.indices }
        .distinct()
        .mapNotNull { reversed[it].prefetchMarkdownSource() }
        .distinct()
}

private fun conversationPrefetchGmail(
    items: List<ConversationListItem>,
    visibleKeys: List<Any>,
): List<ChatToolInvocation> {
    val reversed = items.asReversed()
    val keySet = visibleKeys.map { it.toString() }.toSet()
    val found = reversed.indices.filter { reversed[it].key in keySet }
    if (found.isEmpty()) return emptyList()
    return found.flatMap { index -> listOf(index - 1, index, index + 1, index + 2) }
        .filter { it in reversed.indices }
        .flatMap { reversed[it].prefetchGmailInvocations() }
        .distinctBy(ChatToolInvocation::id)
}

private val ConversationTopFadeHeight = 42.dp
private val ComposerCardShape = RoundedCornerShape(26.dp)
private val ComposerFocusedCardShape = RoundedCornerShape(28.dp)
private const val ComposerAsrBarCount = 12
private val ComposerSubmitButtonSize = 38.dp
private val ComposerAsrCapsuleWidth = 108.dp
private const val ComposerAsrStopGuardMillis = 550L
private const val ComposerAsrEmptyToastMinMillis = 1_000L
private const val ComposerPopupActionDelayMillis = 240L

internal fun composerAsrEmptyMessageRes(
    hadVisualVoice: Boolean,
    listenedForMillis: Long,
): Int = when {
    !hadVisualVoice -> R.string.composer_asr_empty_no_voice
    listenedForMillis < 1_800L -> R.string.composer_asr_empty_too_short
    else -> R.string.composer_asr_empty
}
private const val MinimumWallClockMillis = 946_684_800_000L
private val ChatGptControlShadow = Color(0x14000000)
private val ChatGptComposerShadow = Color(0x18000000)
private val ChatGptPurple = Color(0xFF9B5CFF)
private val ChatGptMotionEasing = CubicBezierEasing(0.22f, 0.84f, 0.18f, 1f)

internal fun ChatUsageStatistics?.effectiveContextTokens(): Long {
    if (this == null) return 0L
    val total = totalTokens ?: 0L
    if (total > 0L) return total
    return (inputTokens ?: 0L) + (outputTokens ?: 0L) + (reasoningTokens ?: 0L)
}

internal fun estimateConversationContextTokens(messages: List<ChatMessage>): Long =
    messages.sumOf { message -> (message.text.length + 3L) / 4L }

internal enum class PendingGenerationIndicator {
    None,
    WorkspaceSetup,
    Thinking,
    Status,
}

internal fun isToolProgressStatusText(text: String): Boolean {
    val trimmed = text.trim()
    return trimmed.startsWith("Using ", ignoreCase = true) ||
        trimmed.startsWith("Used ", ignoreCase = true)
}

internal fun pendingGenerationIndicator(
    isSending: Boolean,
    pendingAssistantText: String,
    pendingStatusText: String,
    hasVisiblePendingReasoning: Boolean = false,
    hasVisiblePendingWork: Boolean = false,
    lastVisibleMessageAuthor: MessageAuthor? = null,
    isPreparingWorkspace: Boolean = false,
    hideSetupAndThinking: Boolean = false,
): PendingGenerationIndicator = when {
    !isSending -> PendingGenerationIndicator.None
    pendingStatusText.isNotBlank() && !isToolProgressStatusText(pendingStatusText) ->
        PendingGenerationIndicator.Status
    hideSetupAndThinking -> PendingGenerationIndicator.None
    isPreparingWorkspace -> PendingGenerationIndicator.WorkspaceSetup
    hasVisiblePendingReasoning -> PendingGenerationIndicator.None
    hasVisiblePendingWork -> PendingGenerationIndicator.None
    lastVisibleMessageAuthor == MessageAuthor.Agent -> PendingGenerationIndicator.None
    pendingAssistantText.isBlank() -> PendingGenerationIndicator.Thinking
    else -> PendingGenerationIndicator.None
}

internal fun agentModeConversationDockVisible(
    agentModeSelected: Boolean,
    hasPinnedSubagents: Boolean,
): Boolean = agentModeSelected || hasPinnedSubagents

internal fun agentModeVirtualDeskVisible(agentModeSelected: Boolean): Boolean = agentModeSelected

internal fun agentModePhonePreviewVisible(
    deskBusy: Boolean,
    displayActive: Boolean,
    hasPreviewBitmap: Boolean,
): Boolean = displayActive || (!deskBusy && hasPreviewBitmap)

internal fun agentModeComputerShouldAutoExpand(phoneSubagentStarted: Boolean): Boolean =
    phoneSubagentStarted

internal fun agentModePreviewUserInputEnabled(teachingActive: Boolean): Boolean = teachingActive

internal fun agentModeShouldIsolateIme(
    displayActive: Boolean,
    previewExpanded: Boolean,
    composerFocused: Boolean,
): Boolean = displayActive && (previewExpanded || !composerFocused)

internal fun currentTurnPhoneSubagentStarted(
    pendingBlocks: List<AssistantResponseBlock>,
    pendingTools: List<ChatToolInvocation>,
    messages: List<ChatMessage>,
): Boolean = currentTurnSubagentStarted(
    pendingBlocks = pendingBlocks,
    pendingTools = pendingTools,
    messages = messages,
    predicate = ChatToolInvocation::isPhoneSubagent,
)

internal fun currentTurnBrowserSubagentStarted(
    pendingBlocks: List<AssistantResponseBlock>,
    pendingTools: List<ChatToolInvocation>,
    messages: List<ChatMessage>,
): Boolean = currentTurnSubagentStarted(
    pendingBlocks = pendingBlocks,
    pendingTools = pendingTools,
    messages = messages,
    predicate = ChatToolInvocation::isBrowserSubagent,
)

private fun currentTurnSubagentStarted(
    pendingBlocks: List<AssistantResponseBlock>,
    pendingTools: List<ChatToolInvocation>,
    messages: List<ChatMessage>,
    predicate: (ChatToolInvocation) -> Boolean,
): Boolean {
    val pendingMatch = (pendingTools + pendingBlocks.flatMap { block ->
        when (block) {
            is AssistantResponseBlock.ToolGroup -> block.toolInvocations
            is AssistantResponseBlock.Reasoning -> block.trace.toolInvocations
            else -> emptyList()
        }
    }).any(predicate)
    if (pendingMatch) return true
    val lastUserIndex = messages.indexOfLast { message -> message.author == MessageAuthor.User }
    if (lastUserIndex < 0) return false
    return messages.drop(lastUserIndex + 1).any { message ->
        message.author == MessageAuthor.Agent &&
            (message.toolInvocations + message.reasoningTrace?.toolInvocations.orEmpty())
                .any(predicate)
    }
}

internal enum class ComposerAgentModeChipState {
    AgentMode,
    Takeover,
    EndTeaching,
}

internal fun composerAgentModeChipState(
    teachingActive: Boolean,
    previewHasContent: Boolean,
): ComposerAgentModeChipState = when {
    teachingActive -> ComposerAgentModeChipState.EndTeaching
    previewHasContent -> ComposerAgentModeChipState.Takeover
    else -> ComposerAgentModeChipState.AgentMode
}

internal fun agentModePreviewHasContent(
    displayActive: Boolean,
    previewPath: String,
): Boolean = displayActive || previewPath.isNotBlank()

internal fun shouldRenderPendingGenerationBlock(
    isSending: Boolean,
    pendingResponseBlocks: List<AssistantResponseBlock>,
    pendingToolInvocations: List<ChatToolInvocation>,
    pendingStatusText: String,
    lastVisibleAgentText: String?,
): Boolean {
    val hasPendingContent = pendingResponseBlocks.isNotEmpty() ||
        pendingToolInvocations.isNotEmpty() ||
        isSending
    if (!hasPendingContent) return false

    val pendingText = pendingResponseBlocks.visibleText().trim()
    val lastAgentText = lastVisibleAgentText?.trim().orEmpty()
    val isCommittedTextEcho = pendingText.isNotBlank() &&
        lastAgentText.isNotBlank() &&
        pendingText == lastAgentText &&
        pendingToolInvocations.isEmpty() &&
        pendingStatusText.isBlank()
    return !isCommittedTextEcho
}

internal fun hasVisibleReasoningStatus(trace: ReasoningTrace): Boolean =
    trace.latestStatusText.isNotBlank() ||
        trace.rawText.isNotBlank() ||
        trace.hasTimelineContent ||
        trace.completedAtMillis != null

private fun topOverlayBodyGradient(): Brush = Brush.verticalGradient(
    colorStops = arrayOf(
        0.0f to AetherBackground.copy(alpha = 0.98f),
        0.28f to AetherBackground.copy(alpha = 0.92f),
        0.58f to AetherBackground.copy(alpha = 0.52f),
        0.82f to AetherBackground.copy(alpha = 0.18f),
        1.0f to Color.Transparent,
    )
)

private fun topOverlayTailGradient(): Brush = Brush.verticalGradient(
    colorStops = arrayOf(
        0.0f to AetherBackground.copy(alpha = 0.10f),
        0.42f to AetherBackground.copy(alpha = 0.04f),
        1.0f to Color.Transparent,
    )
)

data class ConversationPersonaChrome(
    val name: String,
    val onBack: () -> Unit,
    val onEdit: () -> Unit,
    val onNewChat: () -> Unit,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationScreen(
    conversationStateKey: String,
    messages: List<ChatMessage>,
    hasOlderMessages: Boolean = false,
    onLoadOlderMessages: () -> Unit = {},
    workspaceDirectory: String,
    pendingToolInvocations: List<ChatToolInvocation>,
    pendingToolInvocationStateKey: String,
    pendingResponseBlocks: List<AssistantResponseBlock>,
    pendingAssistantText: String,
    pendingStatusText: String,
    pendingStatusDetail: String,
    isPreparingWorkspace: Boolean,
    activeResponseGroupId: String? = null,
    activeResponseMessageIdPrefix: String? = null,
    activeTurnStartedAtMillis: Long?,
    activeTurnInteractionClock: TurnActivityClock? = null,
    isCompacting: Boolean,
    pendingInputs: List<PendingSessionInput>,
    inputValue: String,
    draftAttachments: List<ChatAttachment>,
    modelOptions: List<ProviderModelOption>,
    modelCatalogInfo: Map<String, ModelCatalogInfo>,
    selectedModelKey: String,
    reasoningEffort: String,
    thinkingLevelsByProviderModel: Map<String, List<String>>,
    thinkingLevelClampsByProviderModel: Map<String, Map<String, String>>,
    agentSlashCommands: List<SlashCommandSuggestion> = emptyList(),
    workspaceFileSuggestions: List<FileMentionSuggestion> = emptyList(),
    pendingPermissionRequests: List<PendingPermissionRequest> = emptyList(),
    pendingElicitationRequests: List<PendingElicitationRequest> = emptyList(),
    pendingMcpSecretPrompt: PendingMcpSecretPromptUi? = null,
    planEntries: List<SessionPlanEntry> = emptyList(),
    planAnchorMessageId: String? = null,
    planAnchorGroupId: String? = null,
    planDocumentMarkdown: String = "",
    goalSnapshot: SessionGoalSnapshot? = null,
    onGoalControl: (String) -> Unit = {},
    acpContextUsage: SessionContextUsage? = null,
    sessionModeId: String = "",
    onSessionModeSelected: (String) -> Unit = {},
    onSendCommand: (String) -> Unit = {},
    promptDirective: String = "",
    onSetPromptDirective: (String) -> Unit = {},
    availableSkills: List<InstalledSkill>,
    availableMcpServers: List<McpServerConfig>,
    selectedSkillIds: List<String>,
    selectedMcpServerIds: List<String>,
    agentModeAvailable: Boolean,
    agentModeSelected: Boolean,
    agentModeDisplayState: AgentModeDisplayState,
    chromeAvailable: Boolean,
    chromeSelected: Boolean,
    chromeDisplayState: AgentModeDisplayState,
    browserDeskState: kira.ditto.browser.BrowserDeskState = kira.ditto.browser.BrowserDeskState(),
    allowRootImageRead: Boolean = false,
    isEditing: Boolean,
    showMenu: Boolean = true,
    termuxSetupState: TermuxSetupState,
    showStarterPromptHint: Boolean,
    showTermuxSetupNotice: Boolean,
    onInputChanged: (String) -> Unit,
    onModelSelected: (String, (Boolean) -> Unit) -> Unit,
    onModelSelectorOpened: () -> Unit,
    onReasoningEffortSelected: (String) -> Unit,
    onRemoveDraftAttachment: (String) -> Unit,
    onSetSkillSelected: (String, Boolean) -> Unit,
    onSetMcpServerSelected: (String, Boolean) -> Unit,
    onSetAgentModeSelected: (Boolean) -> Unit,
    onSetChromeSelected: (Boolean) -> Unit,
    onCancelEdit: () -> Unit,
    onSend: () -> Unit,
    onSteerPendingInput: (String) -> Unit,
    onMenu: () -> Unit,
    onNewChat: () -> Unit,
    personaChrome: ConversationPersonaChrome? = null,
    onPickImages: () -> Unit,
    onPickFiles: () -> Unit,
    onSaveAttachment: (ChatAttachment) -> Unit,
    onOpenLink: (String) -> Unit,
    onEditMessage: (String) -> Unit,
    onDeleteMessage: (String) -> Unit,
    onRedoAgentMessage: (String) -> Unit,
    onRetryUserMessage: (String) -> Unit,
    onSwitchUserMessageBranch: (String, Int) -> Unit,
    onCopyMessage: (ChatMessage) -> Unit,
    onRequestTermuxPermission: () -> Unit,
    onOpenAppPermissions: () -> Unit,
    onOpenTermuxSettings: () -> Unit,
    onOpenTermux: () -> Unit,
    onInstallTermux: () -> Unit,
    onRefreshTermuxSetup: () -> Unit,
    onAttachAgentModePreviewSurface: (Surface) -> Unit,
    onDetachAgentModePreviewSurface: (Surface) -> Unit,
    onBindAgentModePreviewSurfaceView: (SurfaceView?) -> Unit = {},
    onTapAgentModeDisplay: (Int, Int) -> Unit = { _, _ -> },
    onSwipeAgentModeDisplay: (Int, Int, Int, Int, Int) -> Unit = { _, _, _, _, _ -> },
    onSuppressAgentModeIme: () -> Unit = {},
    onFinishAgentModeTeaching: () -> Unit = {},
    onToggleAgentModeTeaching: () -> Unit = {},
    phoneDeskHandoff: PhoneDeskHandoffState = PhoneDeskHandoffState(),
    phoneSettlement: PhoneSettlementUiState = PhoneSettlementUiState(),
    agentModeReviewingEverMe: Boolean = false,
    onPauseGeneration: () -> Unit,
    onDismissTermuxSetupNotice: () -> Unit,
    onDismissStarterPromptHint: () -> Unit,
    onAnswerPermissionRequest: (String, String) -> Unit = { _, _ -> },
    onAnswerElicitationRequest: (String, JSONObject?) -> Unit = { _, _ -> },
    onSubmitMcpSecrets: (Map<String, String>) -> Unit = {},
    onSkipMcpSecrets: () -> Unit = {},
    isSending: Boolean,
    composerInteractive: Boolean = true,
    asrAvailable: Boolean = false,
    appSettings: AppSettings = AppSettings(),
    providerConfigs: List<LlmProviderConfig> = emptyList(),
    ttsPlaybackState: TtsPlaybackState = TtsPlaybackState(),
    onRequestRecordAudio: () -> Unit = {},
    recordAudioGranted: Boolean = false,
    spotifyOverlay: SpotifyOverlayUi = SpotifyOverlayUi(),
    onActivateSpotifyOverlay: (String) -> Unit = {},
    onSetSpotifyOverlayDocked: (Boolean) -> Unit = {},
    onSetSpotifyOrbMenuOpen: (Boolean) -> Unit = {},
    onSetSpotifyOrbUnlocked: (Boolean) -> Unit = {},
    onSetSpotifyOrbOffsetY: (Float) -> Unit = {},
    onDestroySpotifyOverlay: () -> Unit = {},
) {
    // Transcript items are expensive to compose, so give the list a prefetch strategy that
    // also warms nested lazy content (diff viewers) instead of leaving it to the frame
    // that first reveals the card.
    val listState = rememberSaveable(
        conversationStateKey,
        saver = listSaver(
            save = { listOf(it.firstVisibleItemIndex, it.firstVisibleItemScrollOffset) },
            restore = { restored ->
                conversationLazyListState(
                    firstVisibleItemIndex = restored.getOrElse(0) { 0 },
                    firstVisibleItemScrollOffset = restored.getOrElse(1) { 0 },
                )
            },
        ),
    ) {
        conversationLazyListState()
    }
    val conversationMarkdownComposed = remember(conversationStateKey) {
        ConversationMarkdownComposedRegistry()
    }
    val conversationScope = rememberCoroutineScope()
    val pendingStreamingGroup = remember(
        pendingResponseBlocks,
        activeResponseGroupId,
        activeResponseMessageIdPrefix,
        chromeSelected,
    ) {
        pendingAssistantGroup(
            blocks = pendingResponseBlocks,
            responseGroupId = activeResponseGroupId,
            messageIdPrefix = activeResponseMessageIdPrefix,
            chromeSelected = chromeSelected,
        )
    }
    val baseConversationItems = remember(messages, pendingStreamingGroup) {
        val base = buildConversationListItems(messages)
        if (pendingStreamingGroup != null && base.none { it.key == pendingStreamingGroup.key }) {
            base + pendingStreamingGroup
        } else {
            base
        }
    }
    val browserDeskInvocations = remember(
        pendingResponseBlocks,
        pendingToolInvocations,
        messages,
        agentModeSelected,
    ) {
        stickySubagentInvocations(
            pendingBlocks = pendingResponseBlocks,
            pendingTools = pendingToolInvocations,
            messages = messages,
        ).filter { invocation ->
            !isBuiltinKimiFetchTool(invocation.toolName) &&
                (
                    invocation.isBrowserSubagent() ||
                        (!agentModeSelected && invocation.isImageSubagent())
                    )
        }.withoutRedundantFetchAgents()
            .let(::coalesceParallelBrowserAgents)
    }
    val retainBrowserDesk = browserDeskRetainDuringTurn(
        isSending = isSending,
        hasBrowserWork = browserDeskInvocations.isNotEmpty() ||
            browserDeskState.activities.isNotEmpty(),
    )
    val browserModeDeskVisible = !agentModeSelected &&
        browserDeskDockVisible(
            invocations = browserDeskInvocations,
            deskState = browserDeskState,
            keepCompletedPreview = retainBrowserDesk,
        )
    val browserPreviewIdentityKey = remember(conversationStateKey, messages) {
        agentModeCrewIdentityKey(
            conversationStateKey = conversationStateKey,
            lastUserMessageId = messages.lastOrNull { message ->
                message.author == MessageAuthor.User
            }?.id.orEmpty(),
        )
    }
    val lastUserMessageIndex = remember(messages) {
        messages.indexOfLast { message ->
            message.author == MessageAuthor.User && message.displayKind == MessageDisplayKind.Standard
        }
    }
    val currentTurnAgentMessageIds = remember(messages, lastUserMessageIndex) {
        if (lastUserMessageIndex < 0) {
            emptySet()
        } else {
            messages.drop(lastUserMessageIndex + 1)
                .filter { message -> message.author == MessageAuthor.Agent }
                .mapTo(hashSetOf()) { it.id }
        }
    }
    /**
     * Did the turn in progress touch the browser at all?
     *
     * Read from the running turn's own tool invocations rather than from the desk, because the desk
     * survives the turn that filled it. `lastBrowserPreviewMessageIds` only fills once an
     * invocation is committed to a message, which is late in the turn, so the pending invocations
     * are what make the card appear from the first browser call instead of at the end.
     */
    val currentTurnBrowserWork = remember(pendingToolInvocations, messages, currentTurnAgentMessageIds) {
        pendingToolInvocations.any { it.isBrowserDeskSubagent() } ||
            messages.any { message ->
                message.id in currentTurnAgentMessageIds &&
                    (message.toolInvocations + message.reasoningTrace?.toolInvocations.orEmpty())
                        .any { it.isBrowserDeskSubagent() }
            }
    }
    val lastBrowserPreviewMessageIds = remember(baseConversationItems, currentTurnAgentMessageIds) {
        var ids = emptySet<String>()
        baseConversationItems.forEach { item ->
            when (item) {
                is ConversationListItem.AssistantGroup -> {
                    val hasBrowser = item.messages.any { message ->
                        (message.toolInvocations + message.reasoningTrace?.toolInvocations.orEmpty())
                            .any { it.isBrowserDeskSubagent() }
                    }
                    if (hasBrowser) ids = item.messages.mapTo(hashSetOf()) { it.id }
                }
                is ConversationListItem.Message -> {
                    val message = item.message
                    val hasBrowser = message.author == MessageAuthor.Agent &&
                        (message.toolInvocations + message.reasoningTrace?.toolInvocations.orEmpty())
                            .any { it.isBrowserDeskSubagent() }
                    if (hasBrowser) ids = setOf(message.id)
                }
                else -> Unit
            }
        }
        browserPreviewLiveMessageIds(ids, currentTurnAgentMessageIds)
    }
    val attachBrowserPreviewToPending = browserModeDeskVisible &&
        pendingStreamingGroup == null &&
        (pendingResponseBlocks.isNotEmpty() || pendingToolInvocations.isNotEmpty() || isSending)
    val liveBrowserImages = remember(browserDeskState, agentModeSelected) {
        if (agentModeSelected) {
            emptyList()
        } else {
            val fromTopics = kira.ditto.browser.BrowserTopicGraph.snapshot().flatMap { tab ->
                tab.images.map { image ->
                    BrowserInlineImage(url = image.url, alt = image.alt, topicId = tab.topicId)
                }
            }
            fromTopics.ifEmpty {
                collectBrowserInlineImages(browserDeskState, curatedOnly = true).map { image ->
                    BrowserInlineImage(url = image.url, alt = image.alt)
                }
            }
        }
    }
    val knowledgeCitationsByMessage = remember(messages) { knowledgeCitationsByMessageId(messages) }
    val browserImagesByMessage = remember(messages) { browserImagesByMessageId(messages) }
    val imagesByGroupKey = remember(
        baseConversationItems,
        liveBrowserImages,
        browserImagesByMessage,
        currentTurnAgentMessageIds,
        agentModeSelected,
    ) {
        buildMap {
            baseConversationItems.forEach { item ->
                val group = item as? ConversationListItem.AssistantGroup ?: return@forEach
                put(
                    group.key,
                    browserImagesForMessage(
                        messageId = group.messages.last().id,
                        currentTurnIds = currentTurnAgentMessageIds,
                        liveImages = liveBrowserImages,
                        imagesByMessage = browserImagesByMessage,
                        agentMode = agentModeSelected,
                    ),
                )
            }
        }
    }
    var parsedLongAssistantMarkdown by remember(conversationStateKey) {
        mutableStateOf<Map<String, List<MarkdownBlock>>>(emptyMap())
    }
    LaunchedEffect(
        baseConversationItems,
        pendingStreamingGroup?.key,
        imagesByGroupKey,
        agentModeSelected,
    ) {
        val streamingKey = pendingStreamingGroup?.key
        val needed = baseConversationItems.mapNotNull { item ->
            val group = item as? ConversationListItem.AssistantGroup ?: return@mapNotNull null
            if (group.key == streamingKey) return@mapNotNull null
            val raw = assistantGroupFinalBodyMarkdown(group.messages)
            if (raw.length < AssistantMarkdownSplitMinChars) return@mapNotNull null
            val decorated = attachTopicImages(
                markdown = injectAmapPlaceMarkup(
                    normalizeMarkdownSource(raw),
                    amapPlacesFromMessages(group.messages),
                ),
                bundles = topicBundlesFromImages(imagesByGroupKey[group.key].orEmpty()),
            )
            group.key to decorated
        }
        if (needed.isEmpty()) return@LaunchedEffect
        val parsed = withContext(Dispatchers.Default) {
            needed.associate { (key, markdown) ->
                key to MarkdownBlockCache.getOrParse(markdown)
            }
        }
        parsedLongAssistantMarkdown = parsedLongAssistantMarkdown + parsed
    }
    val conversationItems = remember(
        baseConversationItems,
        parsedLongAssistantMarkdown,
        pendingStreamingGroup?.key,
        imagesByGroupKey,
        agentModeSelected,
    ) {
        expandLongAssistantMarkdown(
            items = baseConversationItems,
            parsedBlocks = parsedLongAssistantMarkdown,
            streamingGroupKey = pendingStreamingGroup?.key,
            imagesByGroupKey = imagesByGroupKey,
            requireTopicMatch = !agentModeSelected,
        )
    }
    val sessionTotalTokens = remember(messages) {
        messages.lastOrNull { message ->
            message.usageStatistics.effectiveContextTokens() > 0L
        }?.usageStatistics.effectiveContextTokens().takeIf { it > 0L }
    }
    val liveBrowserCitations = remember(
        agentModeSelected,
        browserDeskState.sources,
        messages,
        lastUserMessageIndex,
    ) {
        val preceding = if (lastUserMessageIndex >= 0) {
            messages[lastUserMessageIndex].knowledgeCitations
        } else {
            emptyList()
        }
        browserModeTrailingCitations(
            agentMode = agentModeSelected,
            deskSources = browserDeskState.sources,
            preceding = preceding,
        )
    }
    val rawContextUsageFraction = remember(
        messages,
        pendingAssistantText,
        selectedModelKey,
        modelCatalogInfo,
        acpContextUsage,
    ) {
        val catalogWindow = modelCatalogInfo[selectedModelKey]?.contextWindow
        val latestTurnTokens = messages.lastOrNull { message ->
            message.usageStatistics.effectiveContextTokens() > 0L
        }?.usageStatistics.effectiveContextTokens().takeIf { it > 0L }
            ?: estimateConversationContextTokens(messages).takeIf { it > 0L }
        capsuleContextUsageFraction(
            acpUsedTokens = acpContextUsage?.usedTokens,
            acpWindowTokens = acpContextUsage?.windowTokens,
            catalogWindow = catalogWindow,
            localUsedTokens = conversationUsedContextTokens(latestTurnTokens, pendingAssistantText),
            sessionRunning = isSending,
            // Only the text streamed so far this turn. Passing the whole local estimate here would
            // add the previous turn's billing total to a live occupancy figure - two quantities, one
            // number, which is the bug this parameter exists to end.
            pendingTokens = (pendingAssistantText.length + 3L) / 4L,
        )
    }
    var lastKnownContextUsage by rememberSaveable(conversationStateKey) { mutableStateOf(0f) }
    LaunchedEffect(conversationStateKey, rawContextUsageFraction) {
        if (rawContextUsageFraction > 0.004f) {
            lastKnownContextUsage = rawContextUsageFraction
        }
    }
    val contextUsageFraction = if (rawContextUsageFraction > 0.004f) {
        rawContextUsageFraction
    } else {
        lastKnownContextUsage
    }
    val compactSuggestion = remember(messages, contextUsageFraction) {
        if (messages.count { it.displayKind == MessageDisplayKind.Standard } < 2) {
            CompactCommandSuggestion(percent = null)
        } else {
            CompactCommandSuggestion(
                percent = (contextUsageFraction * 100f).toInt().coerceIn(1, 100),
            )
        }
    }
    val compactSuggestionText = compactSuggestion.percent?.let { percent ->
        stringResource(R.string.chat_compact_thread_context_percent, percent)
    } ?: stringResource(R.string.chat_compact_thread_context)
    val lastVisibleMessageAuthor = remember(messages) {
        messages.lastOrNull { message ->
            message.displayKind == MessageDisplayKind.Standard
        }?.author
    }
    val lastVisibleAgentText = remember(messages) {
        messages.lastOrNull { message ->
            message.displayKind == MessageDisplayKind.Standard &&
                message.author == MessageAuthor.Agent
        }?.text
    }
    var previewAttachment by remember { mutableStateOf<ChatAttachment?>(null) }
    var shouldAutoFollow by rememberSaveable(conversationStateKey) { mutableStateOf(true) }
    var topBarBodyHeightPx by remember { mutableIntStateOf(0) }
    var composerBodyHeightPx by remember { mutableIntStateOf(0) }
    var composerFocused by remember { mutableStateOf(false) }
    var agentModePreviewExpanded by remember { mutableStateOf(false) }
    var spotifyTimelineVisible by remember { mutableStateOf(false) }
    var spotifyComposerTopInWindow by remember { mutableStateOf(Float.POSITIVE_INFINITY) }
    var spotifyDockedHeightPx by remember { mutableIntStateOf(0) }
    val latestSpotifyPlaybackId = remember(
        messages,
        pendingToolInvocations,
        pendingResponseBlocks,
    ) {
        latestConversationSpotifyPlaybackId(
            messages = messages,
            pendingToolInvocations = pendingToolInvocations,
            pendingResponseBlocks = pendingResponseBlocks,
        )
    }
    val spotifyOverlayHost = SpotifyOverlayHost(
        overlay = spotifyOverlay,
        viewportBottom = spotifyComposerTopInWindow,
        timelineVisible = spotifyTimelineVisible,
        onActivate = onActivateSpotifyOverlay,
        onTimelineVisible = { visible -> spotifyTimelineVisible = visible },
        onSetDocked = onSetSpotifyOverlayDocked,
        onSetOrbMenuOpen = onSetSpotifyOrbMenuOpen,
        onSetOrbUnlocked = onSetSpotifyOrbUnlocked,
        onSetOrbOffsetY = onSetSpotifyOrbOffsetY,
        onDestroy = onDestroySpotifyOverlay,
        onDockedCardHeightPx = { spotifyDockedHeightPx = it },
    )
    DisposableEffect(conversationStateKey) {
        spotifyTimelineVisible = false
        onDispose { }
    }
    val conversationContext = LocalContext.current
    LaunchedEffect(agentModeDisplayState.isActive, composerFocused, agentModePreviewExpanded) {
        AgentModeImeGuard.apply(
            context = conversationContext,
            isolateIme = agentModeShouldIsolateIme(
                displayActive = agentModeDisplayState.isActive,
                previewExpanded = agentModePreviewExpanded,
                composerFocused = composerFocused,
            ),
        )
    }
    LaunchedEffect(agentModeDisplayState.isActive, agentModePreviewExpanded, composerFocused) {
        if (!agentModeDisplayState.isActive || !agentModePreviewExpanded || composerFocused) {
            return@LaunchedEffect
        }
        while (true) {
            onSuppressAgentModeIme()
            delay(200)
        }
    }
    val density = LocalDensity.current
    val fallbackTopBarBodyHeight = with(density) {
        WindowInsets.statusBars.getTop(this).toDp() + 68.dp
    }
    val topBarBodyHeight = with(density) {
        if (topBarBodyHeightPx > 0) topBarBodyHeightPx.toDp() else fallbackTopBarBodyHeight
    }
    val composerBodyHeight = with(density) {
        if (composerBodyHeightPx > 0) composerBodyHeightPx.toDp() else 112.dp
    }
    val spotifyDockedOverlap = with(density) {
        if (spotifyOverlay.docked && !spotifyTimelineVisible && spotifyDockedHeightPx > 0) {
            spotifyDockedHeightPx.toDp()
        } else {
            0.dp
        }
    }
    val imeBottom = with(density) {
        WindowInsets.ime.getBottom(this).toDp()
    }
    val animatedImeBottom by animateDpAsState(
        targetValue = imeBottom,
        animationSpec = tween(durationMillis = 260, easing = ChatGptMotionEasing),
        label = "conversation_empty_ime_bottom",
    )
    val animatedImeBottomPx = with(density) { animatedImeBottom.roundToPx() }
    val conversationScrollConnection = remember(listState) {
        object : NestedScrollConnection {
            private var leftEndDuringGesture = false

            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    shouldAutoFollow = false
                    if (!listState.isAtConversationEnd(thresholdPx = 48, reverseLayout = true)) {
                        leftEndDuringGesture = true
                    }
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (
                    source == NestedScrollSource.UserInput &&
                    leftEndDuringGesture &&
                    listState.isAtConversationEnd(thresholdPx = 8, reverseLayout = true)
                ) {
                    shouldAutoFollow = true
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                val atEnd = listState.isAtConversationEnd(thresholdPx = 8, reverseLayout = true)
                if (leftEndDuringGesture && atEnd) {
                    shouldAutoFollow = true
                }
                leftEndDuringGesture = false
                return Velocity.Zero
            }
        }
    }

    // 正文输出不再自动向下跟随；用户手动上滑离开底部后也不再被拽回。
    // （只保留历史/导航的显式滚动。）

    LaunchedEffect(listState, conversationItems) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.key } }
            .distinctUntilChanged()
            .collect { visibleKeys ->
                val texts = conversationPrefetchMarkdown(conversationItems, visibleKeys)
                val gmailInvocations = conversationPrefetchGmail(conversationItems, visibleKeys)
                if (texts.isEmpty() && gmailInvocations.isEmpty()) return@collect
                withContext(Dispatchers.Default) {
                    texts.forEach { source ->
                        MarkdownBlockCache.getOrParse(normalizeMarkdownSource(source))
                    }
                }
                if (gmailInvocations.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        GmailCardCache.prefetch(gmailInvocations)
                    }
                }
            }
    }

    // The list is reversed, so the oldest loaded message sits at the highest index:
    // approaching it means the user is scrolling back into history.
    LaunchedEffect(listState, hasOlderMessages) {
        if (!hasOlderMessages) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - OlderMessagesPrefetchDistance
        }
            .distinctUntilChanged()
            .collect { nearOldest -> if (nearOldest) onLoadOlderMessages() }
    }

    val showPendingGeneration = shouldRenderPendingGenerationBlock(
        isSending = isSending,
        pendingResponseBlocks = pendingResponseBlocks,
        pendingToolInvocations = pendingToolInvocations,
        pendingStatusText = pendingStatusText,
        lastVisibleAgentText = lastVisibleAgentText,
    )
    val conversationPrefixCount = conversationLazyPrefixCount(
        pendingInputCount = pendingInputs.size,
        showPendingGeneration = showPendingGeneration,
        isCompacting = isCompacting,
    )
    val planHostItemKey = remember(
        conversationItems,
        planEntries,
        planDocumentMarkdown,
        planAnchorMessageId,
        planAnchorGroupId,
    ) {
        if (planEntries.isEmpty() && planDocumentMarkdown.isBlank()) {
            null
        } else {
            conversationPlanHostItemKey(
                items = conversationItems,
                planAnchorMessageId = planAnchorMessageId,
                planAnchorGroupId = planAnchorGroupId,
            )
        }
    }
    val promptTicks = remember(conversationItems) {
        conversationItems.mapIndexedNotNull { index, item ->
            val message = (item as? ConversationListItem.Message)?.message
                ?: return@mapIndexedNotNull null
            if (message.author != MessageAuthor.User) return@mapIndexedNotNull null
            ConversationPromptTick(
                messageId = message.id,
                preview = conversationPromptPreview(message.text),
                itemIndex = index,
            )
        }
    }

    val spotifyHazeState = remember { HazeState() }
    CompositionLocalProvider(
        LocalSpotifyOverlayHost provides spotifyOverlayHost,
        LocalLatestSpotifyPlaybackId provides latestSpotifyPlaybackId,
        LocalSpotifyHazeState provides spotifyHazeState,
        LocalAmapPlaceAsk provides onSendCommand,
        LocalTtsPlayback provides ttsPlaybackState,
    ) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = AetherBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(state = spotifyHazeState),
            ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(AetherBackgroundGradientTop, AetherBackground, AetherSurface)
                        )
                    ),
            )
            AnimatedContent(
                targetState = conversationStateKey,
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds(),
                contentAlignment = Alignment.TopStart,
                transitionSpec = {
                    val enter = slideInHorizontally(
                        animationSpec = tween(280, easing = ChatGptMotionEasing),
                        initialOffsetX = { -it / 3 },
                    ) + fadeIn(tween(280, easing = ChatGptMotionEasing))
                    (enter togetherWith ExitTransition.None using
                        SizeTransform(clip = false) { _, _ -> snap() }).apply {
                        targetContentZIndex = 1f
                    }
                },
                label = "conversation_session_transition",
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
            if (messages.isEmpty()) {
                AetherExtensionSlot(
                    slot = AetherExtensionSlotChatEmpty,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(
                            start = 20.dp,
                            end = 20.dp,
                            top = topBarBodyHeight + 12.dp,
                        ),
                )
            } else {
                val composeView = LocalView.current
                DisposableEffect(composeView) {
                    val previousVertical = composeView.isVerticalScrollBarEnabled
                    val previousHorizontal = composeView.isHorizontalScrollBarEnabled
                    composeView.isVerticalScrollBarEnabled = false
                    composeView.isHorizontalScrollBarEnabled = false
                    onDispose {
                        composeView.isVerticalScrollBarEnabled = previousVertical
                        composeView.isHorizontalScrollBarEnabled = previousHorizontal
                    }
                }
                CompositionLocalProvider(
                    LocalConversationScrolling provides listState.isScrollInProgress,
                    LocalConversationMarkdownComposed provides conversationMarkdownComposed,
                    LocalBrowserConversationChrome provides BrowserConversationChrome(
                        browserMode = !agentModeSelected,
                        deskVisible = browserModeDeskVisible,
                    ),
                    LocalBrowserPreviewHost provides BrowserPreviewHost(
                        visible = browserModeDeskVisible,
                        attachToPending = attachBrowserPreviewToPending,
                        identityKey = browserPreviewIdentityKey,
                        deskState = browserDeskState,
                        invocations = browserDeskInvocations,
                        sessionRunning = retainBrowserDesk || isSending,
                        messageIds = lastBrowserPreviewMessageIds,
                        currentTurnMessageIds = currentTurnAgentMessageIds,
                        currentTurnBrowserWork = currentTurnBrowserWork,
                        workspaceDirectory = workspaceDirectory,
                        allowRootImageRead = allowRootImageRead,
                        onOpenLink = onOpenLink,
                        onVerifyContinue = {
                            val reason = BrowserDesk.state.value.userTakeoverReason
                            // If a tool call is parked on the takeover gate, releasing it resumes
                            // the task in place. Sending a message as well would start the whole
                            // task over on top of the one that just continued.
                            if (!BrowserDesk.completeUserTakeover()) {
                                onSendCommand(
                                    if (reason == "login") {
                                        BrowserLoginContinueUserText
                                    } else {
                                        BrowserVerifyContinueUserText
                                    },
                                )
                            }
                        },
                    ),
                ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(conversationScrollConnection),
                    reverseLayout = true,
                    contentPadding = PaddingValues(
                        start = 20.dp,
                        end = 20.dp,
                        top = topBarBodyHeight + 10.dp,
                        bottom = (composerBodyHeight - spotifyDockedOverlap).coerceAtLeast(0.dp) +
                            animatedImeBottom + 28.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    item(key = "conversation-bottom-anchor", contentType = "anchor") {
                        Spacer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                        )
                    }
                    item(key = "aether-extension-chat-list-end", contentType = "slot") {
                        AetherExtensionSlot(AetherExtensionSlotChatListEnd)
                    }
                    items(pendingInputs.asReversed(), key = { it.id }, contentType = { "pending-input" }) { pendingInput ->
                        PendingSessionInputBubble(
                            pendingInput = pendingInput,
                            onSendNow = { onSteerPendingInput(pendingInput.id) },
                        )
                    }
                    if (showPendingGeneration) {
                        item(
                            key = "pending-generation-${activeResponseGroupId ?: "block"}",
                            contentType = "pending",
                        ) {
                            val indicator = pendingGenerationIndicator(
                                isSending = isSending,
                                pendingAssistantText = pendingAssistantText,
                                pendingStatusText = pendingStatusText,
                                hasVisiblePendingWork = pendingResponseBlocks.hasVisiblePendingWork() ||
                                    pendingToolInvocations.isNotEmpty(),
                                lastVisibleMessageAuthor = lastVisibleMessageAuthor,
                                hasVisiblePendingReasoning = pendingResponseBlocks.any {
                                    it is AssistantResponseBlock.Reasoning &&
                                        hasVisibleReasoningStatus(it.trace)
                                },
                                isPreparingWorkspace = isPreparingWorkspace,
                            )
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                if (pendingStreamingGroup == null) {
                                CompositionLocalProvider(
                                    LocalBrowserInlineImages provides liveBrowserImages,
                                ) {
                                PendingAssistantTimeline(
                                    blocks = pendingResponseBlocks,
                                    workspaceDirectory = workspaceDirectory,
                                    allowRootImageRead = allowRootImageRead,
                                    onOpenLink = onOpenLink,
                                    knowledgeCitations = liveBrowserCitations,
                                    pendingToolInvocationStateKey = pendingToolInvocationStateKey,
                                    pendingToolInvocations = pendingToolInvocations,
                                    activeTurnStartedAtMillis = activeTurnStartedAtMillis,
                                    activeTurnInteractionClock = activeTurnInteractionClock,
                                    agentModeSelected = agentModeSelected,
                                    hideBrowserDeskSubagents = agentModeSelected || browserModeDeskVisible,
                                    agentModeDisplayState = agentModeDisplayState,
                                    chromeSelected = chromeSelected,
                                    chromeDisplayState = chromeDisplayState,
                                    onAttachAgentModePreviewSurface = onAttachAgentModePreviewSurface,
                                    onDetachAgentModePreviewSurface = onDetachAgentModePreviewSurface,
                                )
                                }
                                }
                                when (indicator) {
                                    PendingGenerationIndicator.WorkspaceSetup -> {
                                        ConversationThinkingIndicator(
                                            text = stringResource(R.string.chat_setting_up_workspace),
                                        )
                                    }

                                    PendingGenerationIndicator.Thinking -> {
                                        val subagentRunning = pendingToolInvocations.any {
                                            it.isRunning && it.isCollaborationCapsule()
                                        } || pendingResponseBlocks.any { block ->
                                            when (block) {
                                                is AssistantResponseBlock.Reasoning -> block.trace.toolInvocations
                                                is AssistantResponseBlock.ToolGroup -> block.toolInvocations
                                                else -> emptyList()
                                            }.any { it.isRunning && it.isCollaborationCapsule() }
                                        }
                                        ConversationThinkingIndicator(
                                            text = stringResource(
                                                if (subagentRunning) {
                                                    R.string.chat_team_collaborating
                                                } else {
                                                    R.string.chat_thinking
                                                },
                                            ),
                                        )
                                    }

                                    PendingGenerationIndicator.Status -> {
                                        ReconnectingStatusCard(
                                            text = pendingStatusText,
                                            detail = pendingStatusDetail,
                                            isRunning = true,
                                            modifier = Modifier.padding(top = 6.dp),
                                        )
                                    }

                                    PendingGenerationIndicator.None -> Unit
                                }
                            }
                        }
                    }
                    if (isCompacting) {
                        item(key = "compact-running-status", contentType = "compact") {
                            CompactStatusDivider(
                                text = stringResource(R.string.chat_compacting_context),
                                isRunning = true,
                            )
                        }
                    }
                    items(
                        conversationItems.asReversed(),
                        key = { it.key },
                        contentType = { item ->
                            when (item) {
                                is ConversationListItem.Message -> "message"
                                is ConversationListItem.AssistantGroup -> "assistant-group"
                                is ConversationListItem.AssistantMarkdownSlice ->
                                    markdownSliceContentType(item.block)
                                is ConversationListItem.CompactStatus -> "compact"
                            }
                        },
                    ) { item ->
                        val showPlan = item.key == planHostItemKey
                        when (item) {
                            is ConversationListItem.Message -> {
                                val message = item.message
                                val messageImages = if (message.author == MessageAuthor.Agent) {
                                    browserImagesForMessage(
                                        messageId = message.id,
                                        currentTurnIds = currentTurnAgentMessageIds,
                                        liveImages = liveBrowserImages,
                                        imagesByMessage = browserImagesByMessage,
                                        agentMode = agentModeSelected,
                                    )
                                } else {
                                    emptyList()
                                }
                                ConversationItemWithOptionalPlan(
                                    showPlan = showPlan,
                                    planEntries = planEntries,
                                    planDocumentMarkdown = planDocumentMarkdown,
                                ) {
                                    CompositionLocalProvider(
                                        LocalBrowserInlineImages provides messageImages,
                                    ) {
                                    ConversationMessageBubble(
                                        message = message,
                                        actionsEnabled = !isSending,
                                        workspaceDirectory = workspaceDirectory,
                                        allowRootImageRead = allowRootImageRead,
                                        onOpenAttachment = { previewAttachment = it },
                                        onOpenLink = onOpenLink,
                                        onEdit = { onEditMessage(message.id) },
                                        onDelete = { onDeleteMessage(message.id) },
                                        onCopy = {
                                            onCopyMessage(
                                                message.copy(text = message.text.visibleUserMessageText()),
                                            )
                                        },
                                        onRedo = { onRedoAgentMessage(message.id) },
                                        onRetry = { onRetryUserMessage(message.id) },
                                        onSwitchBranch = { delta -> onSwitchUserMessageBranch(message.id, delta) },
                                        sessionTotalTokens = sessionTotalTokens,
                                        knowledgeCitations = knowledgeCitationsByMessage[message.id].orEmpty(),
                                    )
                                    }
                                }
                            }

                            is ConversationListItem.AssistantGroup -> {
                                val lastMessage = item.messages.last()
                                ConversationItemWithOptionalPlan(
                                    showPlan = showPlan,
                                    planEntries = planEntries,
                                    planDocumentMarkdown = planDocumentMarkdown,
                                ) {
                                    CompositionLocalProvider(
                                        LocalBrowserInlineImages provides browserImagesForMessage(
                                            messageId = lastMessage.id,
                                            currentTurnIds = currentTurnAgentMessageIds,
                                            liveImages = liveBrowserImages,
                                            imagesByMessage = browserImagesByMessage,
                                            agentMode = agentModeSelected,
                                        ),
                                    ) {
                                    ConversationAssistantGroupBubble(
                                        messages = item.messages,
                                        actionsEnabled = !isSending,
                                        workspaceDirectory = workspaceDirectory,
                                        allowRootImageRead = allowRootImageRead,
                                        onOpenAttachment = { previewAttachment = it },
                                        onOpenLink = onOpenLink,
                                        onCopy = {
                                            onCopyMessage(
                                                lastMessage.copy(
                                                    text = item.messages.joinToString("\n\n") { message -> message.text }
                                                        .trim(),
                                                )
                                            )
                                        },
                                        onRedo = { onRedoAgentMessage(lastMessage.id) },
                                        onDelete = { onDeleteMessage(lastMessage.id) },
                                        sessionTotalTokens = sessionTotalTokens,
                                        knowledgeCitations = if (lastMessage.id in currentTurnAgentMessageIds) {
                                            liveBrowserCitations
                                        } else {
                                            knowledgeCitationsByMessage[lastMessage.id].orEmpty()
                                        },
                                        isStreaming = isSending &&
                                            activeResponseGroupId != null &&
                                            item.messages.any { it.responseGroupId == activeResponseGroupId },
                                        omitBodyMarkdown = item.omitBodyMarkdown,
                                    )
                                    }
                                }
                            }

                            is ConversationListItem.AssistantMarkdownSlice -> {
                                val lastMessage = item.messages.last()
                                CompositionLocalProvider(
                                    LocalBrowserInlineImages provides browserImagesForMessage(
                                        messageId = lastMessage.id,
                                        currentTurnIds = currentTurnAgentMessageIds,
                                        liveImages = liveBrowserImages,
                                        imagesByMessage = browserImagesByMessage,
                                        agentMode = agentModeSelected,
                                    ),
                                ) {
                                ConversationAssistantMarkdownSlice(
                                    block = item.block,
                                    isLast = item.isLast,
                                    actionsEnabled = !isSending,
                                    workspaceDirectory = workspaceDirectory,
                                    allowRootImageRead = allowRootImageRead,
                                    onOpenLink = onOpenLink,
                                    messageId = lastMessage.id,
                                    knowledgeCitations = if (lastMessage.id in currentTurnAgentMessageIds) {
                                        liveBrowserCitations
                                    } else {
                                        knowledgeCitationsByMessage[lastMessage.id].orEmpty()
                                    },
                                    onCopy = {
                                        onCopyMessage(
                                            lastMessage.copy(
                                                text = item.messages.joinToString("\n\n") { message -> message.text }
                                                    .trim(),
                                            )
                                        )
                                    },
                                    onRedo = { onRedoAgentMessage(lastMessage.id) },
                                    onDelete = { onDeleteMessage(lastMessage.id) },
                                    showActions = item.isLast &&
                                        item.messages.none { it.assistantActionsHidden },
                                    amapPlaces = amapPlacesFromMessages(item.messages),
                                )
                                }
                            }

                            is ConversationListItem.CompactStatus -> {
                                CompactStatusDivider(text = item.message.text.ifBlank { stringResource(R.string.chat_context_compacted) })
                            }
                        }
                    }
                    item(key = "aether-extension-chat-list-start", contentType = "slot") {
                        AetherExtensionSlot(AetherExtensionSlotChatListStart)
                    }
                }
                }
            }
                }
            }

            }

            if (shouldShowConversationIndexRail(promptTicks)) {
                ConversationIndexRail(
                    ticks = promptTicks,
                    listState = listState,
                    prefixCount = conversationPrefixCount,
                    conversationItemCount = conversationItems.size,
                    onNavigate = { tick ->
                        shouldAutoFollow = false
                        conversationScope.launch {
                            listState.animateScrollToItem(
                                conversationLazyIndexForItem(
                                    prefixCount = conversationPrefixCount,
                                    conversationItemCount = conversationItems.size,
                                    itemIndex = tick.itemIndex,
                                ),
                            )
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(
                            top = topBarBodyHeight + 8.dp,
                            bottom = composerBodyHeight + animatedImeBottom + 12.dp,
                            end = 6.dp,
                        ),
                )
            }

            SpotifyNowPlayingOverlay(
                composerBottomPadding = composerBodyHeight + animatedImeBottom + 16.dp,
            )

            ConversationTopOverlay(
                modifier = Modifier.align(Alignment.TopCenter),
                onBodyHeightChanged = { topBarBodyHeightPx = it },
                modelOptions = modelOptions,
                modelCatalogInfo = modelCatalogInfo,
                selectedModelKey = selectedModelKey,
                reasoningEffort = reasoningEffort,
                thinkingLevelsByProviderModel = thinkingLevelsByProviderModel,
                thinkingLevelClampsByProviderModel = thinkingLevelClampsByProviderModel,
                contextUsageFraction = contextUsageFraction,
                isCompacting = isCompacting,
                showMenu = showMenu,
                onMenu = onMenu,
                onModelSelected = onModelSelected,
                onModelSelectorOpened = onModelSelectorOpened,
                onReasoningEffortSelected = onReasoningEffortSelected,
                onNewChat = onNewChat,
                sessionModeId = sessionModeId,
                onPermissionModeSelected = onSessionModeSelected,
                personaChrome = personaChrome,
                planEntries = planEntries,
                planDocumentMarkdown = planDocumentMarkdown,
            )

            ConversationComposerOverlay(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onGloballyPositioned { coordinates ->
                        spotifyComposerTopInWindow = coordinates.boundsInWindow().top
                    },
                conversationStateKey = conversationStateKey,
                onBodyHeightChanged = { composerBodyHeightPx = it },
                value = inputValue,
                attachments = draftAttachments,
                availableSkills = availableSkills,
                availableMcpServers = availableMcpServers,
                selectedSkillIds = selectedSkillIds,
                selectedMcpServerIds = selectedMcpServerIds,
                agentModeAvailable = agentModeAvailable,
                agentModeSelected = agentModeSelected,
                chromeAvailable = chromeAvailable,
                chromeSelected = chromeSelected,
                isEditing = isEditing,
                termuxSetupState = termuxSetupState,
                isSending = isSending,
                showStarterPromptHint = showStarterPromptHint,
                showTermuxSetupNotice = showTermuxSetupNotice,
                compactSuggestionText = compactSuggestionText,
                agentSlashCommands = agentSlashCommands,
                workspaceFileSuggestions = workspaceFileSuggestions,
                sessionModeId = sessionModeId,
                onSessionModeSelected = onSessionModeSelected,
                onSendCommand = onSendCommand,
                promptDirective = promptDirective,
                onSetPromptDirective = onSetPromptDirective,
                pendingPermissionRequests = pendingPermissionRequests,
                onAnswerPermissionRequest = onAnswerPermissionRequest,
                pendingElicitationRequests = pendingElicitationRequests,
                onAnswerElicitationRequest = onAnswerElicitationRequest,
                pendingMcpSecretPrompt = pendingMcpSecretPrompt,
                onSubmitMcpSecrets = onSubmitMcpSecrets,
                onSkipMcpSecrets = onSkipMcpSecrets,
                onValueChange = onInputChanged,
                onRemoveAttachment = onRemoveDraftAttachment,
                onSetSkillSelected = onSetSkillSelected,
                onSetMcpServerSelected = onSetMcpServerSelected,
                onSetAgentModeSelected = onSetAgentModeSelected,
                onToggleAgentModeTeaching = onToggleAgentModeTeaching,
                agentModeTeachingActive = agentModeDisplayState.teachingActive,
                agentModePreviewHasContent = agentModePreviewHasContent(
                    displayActive = agentModeDisplayState.isActive,
                    previewPath = agentModeDisplayState.latestPreviewPath,
                ),
                onSetChromeSelected = onSetChromeSelected,
                onCancelEdit = onCancelEdit,
                onPickImages = onPickImages,
                onPickFiles = onPickFiles,
                onRequestTermuxPermission = onRequestTermuxPermission,
                onOpenAppPermissions = onOpenAppPermissions,
                onOpenTermuxSettings = onOpenTermuxSettings,
                onOpenTermux = onOpenTermux,
                onInstallTermux = onInstallTermux,
                onRefreshTermuxSetup = onRefreshTermuxSetup,
                onPauseGeneration = onPauseGeneration,
                onDismissTermuxSetupNotice = onDismissTermuxSetupNotice,
                onDismissStarterPromptHint = onDismissStarterPromptHint,
                onFocusChanged = { composerFocused = it },
                composerInteractive = composerInteractive,
                asrAvailable = asrAvailable,
                appSettings = appSettings,
                providerConfigs = providerConfigs,
                onRequestRecordAudio = onRequestRecordAudio,
                recordAudioGranted = recordAudioGranted,
                stationContent = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                    AgentModeConversationDock(
                        identityKey = agentModeCrewIdentityKey(
                            conversationStateKey = conversationStateKey,
                            lastUserMessageId = messages.lastOrNull { message ->
                                message.author == MessageAuthor.User
                            }?.id.orEmpty(),
                        ),
                        agentModeSelected = agentModeSelected,
                        isSending = isSending,
                        displayState = agentModeDisplayState,
                        phoneDeskHandoff = phoneDeskHandoff,
                        phoneSettlement = phoneSettlement,
                        lastUserMessageText = messages.lastOrNull { message ->
                            message.author == MessageAuthor.User
                        }?.text.orEmpty(),
                        phoneSubagentInvocations = stickySubagentInvocations(
                            pendingBlocks = pendingResponseBlocks,
                            pendingTools = pendingToolInvocations,
                            messages = messages,
                        ).filter { it.isPhoneSubagent() },
                        phoneSubagentStarted = currentTurnPhoneSubagentStarted(
                            pendingBlocks = pendingResponseBlocks,
                            pendingTools = pendingToolInvocations,
                            messages = messages,
                        ),
                        reviewingEverMe = agentModeReviewingEverMe,
                        collapsePreview = agentModeSelected && composerFocused,
                        pendingAssistantText = pendingAssistantText,
                        onPreviewExpandedChange = { agentModePreviewExpanded = it },
                        transcript = "",
                        workspaceDirectory = workspaceDirectory,
                        allowRootImageRead = allowRootImageRead,
                        onOpenLink = onOpenLink,
                        onAttachSurface = onAttachAgentModePreviewSurface,
                        onDetachSurface = onDetachAgentModePreviewSurface,
                        onBindSurfaceView = onBindAgentModePreviewSurfaceView,
                        onTap = onTapAgentModeDisplay,
                        onSwipe = onSwipeAgentModeDisplay,
                        onFinishTeaching = onFinishAgentModeTeaching,
                    )
                    SpotifyDockedNowPlayingCard()
                    if (goalSnapshot != null) {
                        GoalSupervisionBar(
                            snapshot = goalSnapshot,
                            supervisorName = selectedModelSupervisorName(
                                options = modelOptions,
                                modelCatalogInfo = modelCatalogInfo,
                                selectedModelKey = selectedModelKey,
                            ),
                            onGoalControl = onGoalControl,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                    }
                },
                onSend = onSend,
            )

            previewAttachment?.let { attachment ->
                AttachmentPreviewDialog(
                    attachment = attachment,
                    onDismiss = { previewAttachment = null },
                    onSave = { onSaveAttachment(attachment) },
                )
            }
        }
    }
    }
}

@Composable
private fun ConversationTopOverlay(
    modifier: Modifier = Modifier,
    onBodyHeightChanged: (Int) -> Unit,
    modelOptions: List<ProviderModelOption>,
    modelCatalogInfo: Map<String, ModelCatalogInfo>,
    selectedModelKey: String,
    reasoningEffort: String,
    thinkingLevelsByProviderModel: Map<String, List<String>>,
    thinkingLevelClampsByProviderModel: Map<String, Map<String, String>>,
    contextUsageFraction: Float,
    isCompacting: Boolean,
    showMenu: Boolean,
    onMenu: () -> Unit,
    onModelSelected: (String, (Boolean) -> Unit) -> Unit,
    onModelSelectorOpened: () -> Unit,
    onReasoningEffortSelected: (String) -> Unit,
    onNewChat: () -> Unit,
    sessionModeId: String = "",
    onPermissionModeSelected: (String) -> Unit = {},
    personaChrome: ConversationPersonaChrome? = null,
    planEntries: List<SessionPlanEntry> = emptyList(),
    planDocumentMarkdown: String = "",
) {
    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(topOverlayBodyGradient())
                .onSizeChanged { onBodyHeightChanged(it.height) },
        ) {
            Column {
                ConversationTopBar(
                    modelOptions = modelOptions,
                    modelCatalogInfo = modelCatalogInfo,
                    selectedModelKey = selectedModelKey,
                    reasoningEffort = reasoningEffort,
                    thinkingLevelsByProviderModel = thinkingLevelsByProviderModel,
                    thinkingLevelClampsByProviderModel = thinkingLevelClampsByProviderModel,
                    contextUsageFraction = contextUsageFraction,
                    isCompacting = isCompacting,
                    showMenu = showMenu,
                    onMenu = onMenu,
                    onModelSelected = onModelSelected,
                    onModelSelectorOpened = onModelSelectorOpened,
                    onReasoningEffortSelected = onReasoningEffortSelected,
                    onNewChat = onNewChat,
                    sessionModeId = sessionModeId,
                    onPermissionModeSelected = onPermissionModeSelected,
                    personaChrome = personaChrome,
                    planEntries = planEntries,
                    planDocumentMarkdown = planDocumentMarkdown,
                )
                AetherExtensionSlot(
                    slot = AetherExtensionSlotChatTop,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(ConversationTopFadeHeight)
                .background(topOverlayTailGradient())
        )
    }
}

@Composable
private fun ConversationTopBar(
    modifier: Modifier = Modifier,
    modelOptions: List<ProviderModelOption>,
    modelCatalogInfo: Map<String, ModelCatalogInfo>,
    selectedModelKey: String,
    reasoningEffort: String,
    thinkingLevelsByProviderModel: Map<String, List<String>>,
    thinkingLevelClampsByProviderModel: Map<String, Map<String, String>>,
    contextUsageFraction: Float,
    isCompacting: Boolean,
    showMenu: Boolean,
    onMenu: () -> Unit,
    onModelSelected: (String, (Boolean) -> Unit) -> Unit,
    onModelSelectorOpened: () -> Unit,
    onReasoningEffortSelected: (String) -> Unit,
    onNewChat: () -> Unit,
    sessionModeId: String = "",
    onPermissionModeSelected: (String) -> Unit = {},
    personaChrome: ConversationPersonaChrome? = null,
    planEntries: List<SessionPlanEntry> = emptyList(),
    planDocumentMarkdown: String = "",
) {
    var personaMenuOpen by remember { mutableStateOf(false) }
    AetherConversationTopBarFrame(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding(),
        menuDescription = stringResource(
            if (personaChrome != null) R.string.common_back else R.string.common_menu,
        ),
        newChatDescription = stringResource(
            if (personaChrome != null) R.string.common_more else R.string.common_new_chat,
        ),
        onMenu = personaChrome?.onBack ?: onMenu,
        onNewChat = onNewChat,
        showMenu = showMenu,
        leadingIcon = if (personaChrome != null) {
            Icons.AutoMirrored.Rounded.ArrowBack
        } else {
            Icons.Rounded.Menu
        },
        trailingIcon = if (personaChrome != null) {
            Icons.Rounded.MoreVert
        } else {
            LucideIcons.SquarePen
        },
        trailingContent = personaChrome?.let { chrome ->
            {
                Box {
                    HeaderCircleButton(
                        icon = Icons.Rounded.MoreVert,
                        contentDescription = stringResource(R.string.common_more),
                        onClick = { personaMenuOpen = true },
                        size = 38.dp,
                        iconSize = 19.dp,
                        containerColor = AetherSurface.copy(alpha = 0.96f),
                    )
                    DropdownMenu(
                        expanded = personaMenuOpen,
                        onDismissRequest = { personaMenuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.persona_overflow_edit)) },
                            onClick = {
                                personaMenuOpen = false
                                chrome.onEdit()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.persona_overflow_new_chat)) },
                            onClick = {
                                personaMenuOpen = false
                                chrome.onNewChat()
                            },
                        )
                    }
                }
            }
        },
    ) {
        ConversationModelSelector(
            options = modelOptions,
            modelCatalogInfo = modelCatalogInfo,
            selectedModelKey = selectedModelKey,
            reasoningEffort = reasoningEffort,
            thinkingLevelsByProviderModel = thinkingLevelsByProviderModel,
            thinkingLevelClampsByProviderModel = thinkingLevelClampsByProviderModel,
            contextUsageFraction = contextUsageFraction,
            isCompacting = isCompacting,
            onSelected = onModelSelected,
            onOpened = onModelSelectorOpened,
            onReasoningEffortSelected = onReasoningEffortSelected,
            sessionModeId = sessionModeId,
            onPermissionModeSelected = onPermissionModeSelected,
            planEntries = planEntries,
            planDocumentMarkdown = planDocumentMarkdown,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ConversationModelSelector(
    options: List<ProviderModelOption>,
    modelCatalogInfo: Map<String, ModelCatalogInfo>,
    selectedModelKey: String,
    reasoningEffort: String,
    thinkingLevelsByProviderModel: Map<String, List<String>>,
    thinkingLevelClampsByProviderModel: Map<String, Map<String, String>>,
    contextUsageFraction: Float,
    isCompacting: Boolean,
    onSelected: (String, (Boolean) -> Unit) -> Unit,
    onOpened: () -> Unit,
    onReasoningEffortSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    sessionModeId: String = "",
    onPermissionModeSelected: (String) -> Unit = {},
    planEntries: List<SessionPlanEntry> = emptyList(),
    planDocumentMarkdown: String = "",
) {
    var expanded by remember { mutableStateOf(false) }
    var showingReasoningEffort by remember { mutableStateOf(false) }
    var showingPermissionMode by remember { mutableStateOf(false) }
    var showingTodos by remember { mutableStateOf(false) }
    // Keyed on the live selection: when the session, the provider list, or the agent changes
    // the model, the menu highlight follows instead of waiting for the capsule to be tapped.
    var menuSelectedModelKey by remember(selectedModelKey) { mutableStateOf(selectedModelKey) }
    var anchorHeightPx by remember { mutableIntStateOf(0) }
    val menuVisibility = remember { MutableTransitionState(false) }
    menuVisibility.targetState = expanded
    val density = LocalDensity.current
    val menuWidth = 242.dp
    // While the agent has open todos the capsule switches from the model card
    // to the todo card; the user can flip back to the model card from the
    // todo popup, and back to todos from the model menu.
    val todoActive = planEntries.any { sessionPlanEntryIsOpen(it.status) }
    var capsuleShowsModel by remember { mutableStateOf(false) }
    LaunchedEffect(todoActive) {
        if (!todoActive) capsuleShowsModel = false
    }
    val showTodoCapsule = todoActive && !capsuleShowsModel
    val selectedOption = options.firstOrNull { it.key == menuSelectedModelKey } ?: options.firstOrNull()
    val supportedThinkingLevels = selectedOption?.let { option ->
        thinkingLevelsByProviderModel[thinkingCatalogKey(option.piProviderId, option.modelId)]
    }.orEmpty()
    val effectiveReasoningEffort = selectedOption?.let { option ->
        thinkingLevelClampsByProviderModel[thinkingCatalogKey(option.piProviderId, option.modelId)]
            ?.get(reasoningEffort)
    } ?: reasoningEffort
    val fallbackLabel = stringResource(R.string.chat_select_model)
    // Two different questions, so two different values. The capsule answers "which model is in
    // effect right now" and must track [selectedModelKey]; the menu answers "which row is
    // highlighted" and is allowed to lag while the menu is open.
    val displayedOption = options.firstOrNull { it.key == selectedModelKey } ?: selectedOption
    val selectedDisplay = remember(displayedOption, modelCatalogInfo) {
        displayedOption?.let { option ->
            formatSelectedModelDisplayName(
                rawName = modelCatalogInfo[option.key]?.displayName ?: option.modelId,
            )
        }
    }
    val menuDisplay = remember(selectedOption, modelCatalogInfo) {
        selectedOption?.let { option ->
            formatSelectedModelDisplayName(
                rawName = modelCatalogInfo[option.key]?.displayName ?: option.modelId,
            )
        }
    }
    val selectedModelName = menuDisplay
        ?.let { display -> listOf(display.primary, display.secondary).filter(String::isNotBlank) }
        ?.joinToString(" ")
        ?.takeIf(String::isNotBlank)
        ?: selectedOption?.chatLabel
        ?: fallbackLabel

    Box(
        modifier = modifier,
        contentAlignment = Alignment.CenterStart,
    ) {
        AetherCapsuleSurface(
            modifier = Modifier.height(38.dp)
                .onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInWindow()
                    anchorHeightPx = bounds.height.toInt()
                },
        ) {
            Row(
                modifier = Modifier.height(38.dp)
                    .clickable(enabled = options.isNotEmpty() || todoActive) {
                        onOpened()
                        menuSelectedModelKey = selectedModelKey
                        showingReasoningEffort = false
                        showingPermissionMode = false
                        showingTodos = showTodoCapsule
                        expanded = true
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnimatedContent(
                    targetState = showTodoCapsule,
                    transitionSpec = {
                        (
                            fadeIn(tween(200, delayMillis = 40, easing = ChatGptMotionEasing)) +
                                scaleIn(initialScale = 0.92f, animationSpec = tween(240, easing = ChatGptMotionEasing))
                            ).togetherWith(
                            fadeOut(tween(140, easing = ChatGptMotionEasing)) +
                                scaleOut(targetScale = 0.94f, animationSpec = tween(180, easing = ChatGptMotionEasing))
                            ).using(
                            SizeTransform(clip = false) { _, _ ->
                                tween(durationMillis = 280, easing = ChatGptMotionEasing)
                            }
                        )
                    },
                    label = "conversation-capsule-mode",
                ) { todoMode ->
                    if (todoMode) {
                        TodoCapsuleContent(entries = planEntries)
                    } else if (selectedDisplay != null) {
                        SelectedModelDisplay(
                            displayName = selectedDisplay,
                            modifier = Modifier
                                .widthIn(max = 240.dp)
                                .padding(horizontal = 17.dp),
                        )
                    } else {
                        Text(
                            text = fallbackLabel,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Normal),
                            color = AetherOnSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .widthIn(max = 220.dp)
                                .padding(horizontal = 17.dp),
                        )
                    }
                }
            }
            ModelContextUsageEdge(
                usageFraction = contextUsageFraction,
                isCompacting = isCompacting,
                modifier = Modifier.matchParentSize(),
            )
        }

        if (menuVisibility.currentState || menuVisibility.targetState) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, anchorHeightPx + with(density) { 10.dp.roundToPx() }),
                onDismissRequest = { expanded = false },
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true,
                ),
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visibleState = menuVisibility,
                    enter = fadeIn() + scaleIn(
                        initialScale = 0.96f,
                        transformOrigin = TransformOrigin(0.5f, 0f),
                    ) + slideInVertically(initialOffsetY = { -it / 10 }),
                    exit = fadeOut() + scaleOut(
                        targetScale = 0.98f,
                        transformOrigin = TransformOrigin(0.5f, 0f),
                    ) + slideOutVertically(targetOffsetY = { -it / 12 }),
                ) {
                    AnimatedContent(
                        targetState = when {
                            showingTodos -> ConversationMenuPage.Todos
                            showingPermissionMode -> ConversationMenuPage.PermissionMode
                            showingReasoningEffort && supportedThinkingLevels.isNotEmpty() ->
                                ConversationMenuPage.Reasoning
                            else -> ConversationMenuPage.Models
                        },
                        transitionSpec = {
                            val enteringOffset: (Int) -> Int = { width ->
                                if (targetState != ConversationMenuPage.Models) width / 10 else -width / 10
                            }
                            val exitingOffset: (Int) -> Int = { width ->
                                if (targetState != ConversationMenuPage.Models) -width / 10 else width / 10
                            }
                            (
                                fadeIn(
                                    animationSpec = tween(
                                        durationMillis = 220,
                                        delayMillis = 60,
                                        easing = ChatGptMotionEasing,
                                    ),
                                ) + slideInHorizontally(
                                    animationSpec = tween(
                                        durationMillis = 340,
                                        easing = ChatGptMotionEasing,
                                    ),
                                    initialOffsetX = enteringOffset,
                                )
                            ).togetherWith(
                                fadeOut(
                                    animationSpec = tween(
                                        durationMillis = 150,
                                        easing = ChatGptMotionEasing,
                                    ),
                                ) + slideOutHorizontally(
                                    animationSpec = tween(
                                        durationMillis = 280,
                                        easing = ChatGptMotionEasing,
                                    ),
                                    targetOffsetX = exitingOffset,
                                )
                            ).using(
                                SizeTransform(clip = false) { _, _ ->
                                    tween(
                                        durationMillis = 360,
                                        easing = ChatGptMotionEasing,
                                    )
                                }
                            )
                        },
                        modifier = Modifier
                            .width(menuWidth)
                            .shadow(14.dp, RoundedCornerShape(22.dp), ambientColor = AetherScrim, spotColor = AetherScrim)
                            .clip(RoundedCornerShape(22.dp))
                            .background(AetherSurface)
                            .padding(vertical = 5.dp),
                        label = "conversation-model-menu-mode",
                    ) { page ->
                        when (page) {
                            ConversationMenuPage.Todos -> {
                                ConversationTodoMenu(
                                    entries = planEntries,
                                    planDocumentMarkdown = planDocumentMarkdown,
                                    onBackToModel = {
                                        capsuleShowsModel = true
                                        showingTodos = false
                                        expanded = false
                                    },
                                )
                            }

                            ConversationMenuPage.PermissionMode -> {
                                ConversationPermissionModeMenu(
                                    selectedModeId = sessionModeId,
                                    selectedModelName = selectedModelName,
                                    onBack = { showingPermissionMode = false },
                                    onSelected = { modeId ->
                                        onPermissionModeSelected(modeId)
                                        expanded = false
                                    },
                                )
                            }

                            ConversationMenuPage.Reasoning -> {
                                ConversationReasoningEffortMenu(
                                    efforts = supportedThinkingLevels,
                                    selectedEffort = effectiveReasoningEffort,
                                    selectedModelName = selectedModelName,
                                    onBack = { showingReasoningEffort = false },
                                    onSelected = { effort ->
                                        onReasoningEffortSelected(effort)
                                        expanded = false
                                    },
                                )
                            }

                            ConversationMenuPage.Models -> {
                                ConversationModelListMenu(
                                    options = options,
                                    selectedOption = selectedOption,
                                    reasoningEffort = effectiveReasoningEffort,
                                    showReasoningEffort = supportedThinkingLevels.isNotEmpty(),
                                    onReasoningEffortClick = { showingReasoningEffort = true },
                                    permissionModeId = sessionModeId,
                                    onPermissionModeClick = { showingPermissionMode = true },
                                    openTodoCount = planEntries.count { sessionPlanEntryIsOpen(it.status) },
                                    onShowTodos = { showingTodos = true },
                                    onSelected = { option ->
                                        menuSelectedModelKey = option.key
                                        onSelected(option.key) { hasThinkingLevels ->
                                            if (expanded && menuSelectedModelKey == option.key) {
                                                if (hasThinkingLevels) {
                                                    showingReasoningEffort = true
                                                } else {
                                                    expanded = false
                                                }
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun reasoningEffortLabel(effort: String): String = when (effort.trim().lowercase()) {
    "off" -> stringResource(R.string.chat_reasoning_effort_off)
    "minimal" -> stringResource(R.string.chat_reasoning_effort_minimal)
    "low" -> stringResource(R.string.chat_reasoning_effort_low)
    "medium" -> stringResource(R.string.chat_reasoning_effort_medium)
    "high" -> stringResource(R.string.chat_reasoning_effort_high)
    "xhigh" -> stringResource(R.string.chat_reasoning_effort_xhigh)
    "max" -> stringResource(R.string.chat_reasoning_effort_max)
    else -> effort.trim().replaceFirstChar { it.titlecase() }
}

@Composable
private fun permissionModeLabel(modeId: String): String = when (modeId.trim().lowercase()) {
    "default" -> stringResource(R.string.chat_permission_mode_default)
    "plan" -> stringResource(R.string.chat_permission_mode_plan)
    "auto" -> stringResource(R.string.chat_permission_mode_auto)
    "yolo" -> stringResource(R.string.chat_permission_mode_yolo)
    else -> stringResource(R.string.chat_permission_mode_default)
}

@Composable
private fun permissionModeDescription(modeId: String): String = when (modeId.trim().lowercase()) {
    "default" -> stringResource(R.string.chat_permission_mode_default_desc)
    "plan" -> stringResource(R.string.chat_permission_mode_plan_desc)
    "auto" -> stringResource(R.string.chat_permission_mode_auto_desc)
    "yolo" -> stringResource(R.string.chat_permission_mode_yolo_desc)
    else -> stringResource(R.string.chat_permission_mode_default_desc)
}

/**
 * Capsule face while the agent has open todos: only the completed count.
 * Task text and status circles live in the expanded todo menu.
 */
@Composable
private fun TodoCapsuleContent(
    entries: List<SessionPlanEntry>,
    modifier: Modifier = Modifier,
) {
    val completedCount = entries.count { sessionPlanEntryIsCompleted(it.status) }
    Text(
        text = stringResource(R.string.chat_plan_progress, completedCount, entries.size),
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Normal),
        color = AetherOnSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(horizontal = 17.dp),
    )
}

/** Todo popup page: the full plan list plus a way back to the model card. */
@Composable
private fun ConversationTodoMenu(
    entries: List<SessionPlanEntry>,
    planDocumentMarkdown: String = "",
    onBackToModel: () -> Unit,
) {
    val completedCount = entries.count { sessionPlanEntryIsCompleted(it.status) }
    Column(
        horizontalAlignment = Alignment.Start,
    ) {
        if (planDocumentMarkdown.isNotBlank()) {
            Text(
                text = stringResource(R.string.chat_plan_document_title),
                style = MaterialTheme.typography.labelLarge,
                color = AetherOnSurface,
                modifier = Modifier.padding(start = 19.dp, end = 14.dp, top = 12.dp, bottom = 4.dp),
            )
            Column(
                modifier = Modifier
                    .heightIn(max = 180.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                MarkdownContent(markdown = planDocumentMarkdown)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 19.dp, end = 14.dp, top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.List,
                contentDescription = null,
                tint = AetherOnSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = stringResource(R.string.chat_todo_progress_title),
                style = MaterialTheme.typography.labelLarge,
                color = AetherOnSurface,
                modifier = Modifier.weight(1f),
            )
            if (entries.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.chat_plan_progress, completedCount, entries.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = AetherOnSurfaceVariant,
                )
            }
        }
        Column(
            modifier = Modifier
                .heightIn(max = 280.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.Start,
        ) {
            entries.forEach { entry ->
                ConversationTodoMenuRow(entry)
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            color = AetherOnSurface.copy(alpha = 0.08f),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 5.dp, vertical = 1.dp)
                .clip(RoundedCornerShape(15.dp))
                .clickable(onClick = onBackToModel)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = null,
                tint = AetherOnSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = stringResource(R.string.chat_todo_back_to_model),
                style = MaterialTheme.typography.bodyMedium,
                color = AetherOnSurface,
            )
        }
    }
}

@Composable
private fun ConversationTodoMenuRow(entry: SessionPlanEntry) {
    val completed = sessionPlanEntryIsCompleted(entry.status)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        when {
            completed -> Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = AetherOnSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
            else -> Icon(
                imageVector = Icons.Rounded.RadioButtonUnchecked,
                contentDescription = null,
                tint = AetherOnSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(15.dp),
            )
        }
        Text(
            text = entry.content,
            style = MaterialTheme.typography.bodySmall,
            color = if (completed) AetherOnSurfaceVariant else AetherOnSurface,
            textDecoration = if (completed) TextDecoration.LineThrough else null,
            modifier = if (completed) Modifier.alpha(0.62f) else Modifier,
        )
    }
}

private enum class ConversationMenuPage {
    Models,
    Reasoning,
    PermissionMode,
    Todos,
}

@Composable
private fun ConversationMenuModeEntry(
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 19.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = AetherOnSurface,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = AetherOnSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ConversationReasoningEffortMenu(
    efforts: List<String>,
    selectedEffort: String,
    selectedModelName: String,
    onBack: () -> Unit,
    onSelected: (String) -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.Start,
    ) {
        ConversationMenuModeEntry(
            title = stringResource(R.string.chat_model),
            value = selectedModelName,
            onClick = onBack,
        )
        efforts.forEach { effort ->
            val selected = effort == selectedEffort
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 5.dp, vertical = 1.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(
                        if (selected) AetherOnSurface.copy(alpha = 0.06f) else Color.Transparent,
                    )
                    .clickable { onSelected(effort) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = reasoningEffortLabel(effort),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AetherOnSurface,
                    modifier = Modifier.weight(1f),
                )
                if (selected) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = AetherOnSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationPermissionModeMenu(
    selectedModeId: String,
    selectedModelName: String,
    onBack: () -> Unit,
    onSelected: (String) -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.Start,
    ) {
        ConversationMenuModeEntry(
            title = stringResource(R.string.chat_model),
            value = selectedModelName,
            onClick = onBack,
        )
        SupportedKimiPermissionModes.forEach { modeId ->
            val selected = modeId == selectedModeId.trim().lowercase()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 5.dp, vertical = 1.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(
                        if (selected) AetherOnSurface.copy(alpha = 0.06f) else Color.Transparent,
                    )
                    .clickable { onSelected(modeId) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = permissionModeLabel(modeId),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AetherOnSurface,
                    )
                    Text(
                        text = permissionModeDescription(modeId),
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherOnSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (selected) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = AetherOnSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationModelListMenu(
    options: List<ProviderModelOption>,
    selectedOption: ProviderModelOption?,
    reasoningEffort: String,
    showReasoningEffort: Boolean,
    onReasoningEffortClick: () -> Unit,
    permissionModeId: String = "",
    onPermissionModeClick: () -> Unit = {},
    openTodoCount: Int = 0,
    onShowTodos: () -> Unit = {},
    onSelected: (ProviderModelOption) -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.Start,
    ) {
        ConversationMenuModeEntry(
            title = stringResource(R.string.chat_permission_mode),
            value = permissionModeLabel(permissionModeId),
            onClick = onPermissionModeClick,
        )
        if (showReasoningEffort) {
            ConversationMenuModeEntry(
                title = stringResource(R.string.chat_reasoning_effort),
                value = reasoningEffortLabel(reasoningEffort),
                onClick = onReasoningEffortClick,
            )
        }
        if (openTodoCount > 0) {
            ConversationMenuModeEntry(
                title = stringResource(R.string.chat_todo_view),
                value = stringResource(R.string.chat_todo_open_count, openTodoCount),
                onClick = onShowTodos,
            )
        }
        Column(
            modifier = Modifier
                .heightIn(max = 312.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.Start,
        ) {
            options.forEach { option ->
                ConversationModelMenuRow(
                    label = option.chatLabel,
                    selected = option.key == selectedOption?.key,
                    onClick = { onSelected(option) },
                )
            }
        }
    }
}

@Composable
private fun ConversationModelMenuRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 5.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(if (selected) AetherOnSurface.copy(alpha = 0.06f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Normal),
            color = AetherOnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = AetherOnSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun SelectedModelDisplay(
    displayName: SelectedModelDisplayName,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = displayName.primary,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Normal),
            color = AetherOnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (displayName.secondary.isNotBlank()) {
            Text(
                text = displayName.secondary,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Normal),
                color = AetherOnSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        displayName.icon?.let { icon ->
            Icon(
                imageVector = when (icon) {
                    SelectedModelDisplayIcon.Fast -> LucideIcons.Zap
                    SelectedModelDisplayIcon.Reasoning -> LucideIcons.Brain
                },
                contentDescription = null,
                tint = AetherOnSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

internal data class SelectedModelDisplayName(
    val primary: String,
    val secondary: String,
    val icon: SelectedModelDisplayIcon? = null,
)

internal enum class SelectedModelDisplayIcon {
    Fast,
    Reasoning,
}

internal fun selectedModelSupervisorName(
    options: List<ProviderModelOption>,
    modelCatalogInfo: Map<String, ModelCatalogInfo>,
    selectedModelKey: String,
): String {
    val option = options.firstOrNull { it.key == selectedModelKey } ?: options.firstOrNull()
        ?: return ""
    val display = formatSelectedModelDisplayName(
        rawName = modelCatalogInfo[option.key]?.displayName ?: option.modelId,
    )
    return listOf(display.primary, display.secondary)
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .ifBlank { option.chatLabel }
}

internal fun formatSelectedModelDisplayName(rawName: String): SelectedModelDisplayName {
    val normalizedTokens = rawName
        .trim()
        .substringAfterLast('/')
        .replace(Regex("[()]"), " ")
        .split(Regex("[\\s_-]+"))
        .mapNotNull { token ->
            token.trim().takeIf { it.isNotEmpty() }
        }
    val icon = when {
        normalizedTokens.any { it.equals("reasoning", ignoreCase = true) || it.equals("thinking", ignoreCase = true) } ->
            SelectedModelDisplayIcon.Reasoning
        normalizedTokens.any { token ->
            token.equals("ultraspeed", ignoreCase = true) ||
                token.equals("fast", ignoreCase = true) ||
                token.equals("spark", ignoreCase = true) ||
                token.equals("highspeed", ignoreCase = true)
        } -> SelectedModelDisplayIcon.Fast
        else -> null
    }
    val visibleTokens = normalizedTokens
        .filterNot { token ->
            token.equals("preview", ignoreCase = true) ||
                token.equals("reasoning", ignoreCase = true) ||
                token.equals("thinking", ignoreCase = true) ||
                token.equals("ultraspeed", ignoreCase = true) ||
                token.equals("fast", ignoreCase = true) ||
                token.equals("spark", ignoreCase = true) ||
                token.equals("highspeed", ignoreCase = true)
        }
        .let { tokens ->
            val isLong = tokens.joinToString(" ").length > 28 || tokens.size > 4
            if (!isLong) {
                tokens
            } else {
                tokens.filterNot { token -> token.matches(Regex("\\d+b", RegexOption.IGNORE_CASE)) || token.matches(Regex("a\\d+b", RegexOption.IGNORE_CASE)) }
            }
        }
    if (visibleTokens.isEmpty()) {
        return SelectedModelDisplayName(primary = rawName.trim().ifBlank { "" }, secondary = "", icon = icon)
    }
    val splitFirst = splitTrailingNumber(visibleTokens.first())
    val primary = titleModelToken(splitFirst.first)
    val secondaryTokens = buildList {
        splitFirst.second?.takeIf { it.isNotBlank() }?.let(::add)
        addAll(visibleTokens.drop(1))
    }
    return SelectedModelDisplayName(
        primary = primary,
        secondary = secondaryTokens.joinToString(" ") { titleModelToken(it) },
        icon = icon,
    )
}

private fun splitTrailingNumber(token: String): Pair<String, String?> {
    val match = Regex("^([A-Za-z]+)(\\d[\\w.]*)$").matchEntire(token) ?: return token to null
    return match.groupValues[1] to match.groupValues[2]
}

private fun titleModelToken(token: String): String {
    if (token.isBlank()) return token
    val uppercaseToken = token.uppercase()
    if (uppercaseToken == token && token.any(Char::isLetter)) return token
    if (token.equals("gpt", ignoreCase = true) || token.equals("glm", ignoreCase = true)) {
        return token.uppercase()
    }
    if (token.startsWith("v") && token.drop(1).firstOrNull()?.isDigit() == true) {
        return "v" + token.drop(1)
    }
    if (token.first().isDigit()) return token
    return token.replaceFirstChar { char ->
        if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString()
    }
}

@Composable
private fun ConversationEmptyState(
    modifier: Modifier = Modifier,
    inputFocused: Boolean,
    onStarterPromptSelected: (String) -> Unit,
) {
    AetherConversationEmptyState(
        modifier = modifier,
        welcomeLabel = stringResource(R.string.chat_welcome_help),
        analyzeImageLabel = stringResource(R.string.chat_analyze_image_chip),
        codeLabel = stringResource(R.string.chat_code_chip),
        helpWriteLabel = stringResource(R.string.chat_help_me_write_chip),
        summarizeFileLabel = stringResource(R.string.chat_summarize_file_chip),
        inputFocused = inputFocused,
        onStarterPromptSelected = onStarterPromptSelected,
    )
}

@Composable
private fun ConversationThinkingIndicator(
    text: String = stringResource(R.string.chat_thinking),
) {
    ShimmerStatusText(
        text = text,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun RunningWorkStatusHeader(
    startedAtMillis: Long?,
    fallbackKey: Any?,
    activeTurnInteractionClock: TurnActivityClock?,
) {
    val fallbackStartedRealtimeMillis = remember(fallbackKey) {
        SystemClock.elapsedRealtime()
    }
    val paused = LocalChatActivityPaused.current
    val durationMillis by produceState(
        initialValue = runningWorkDurationMillis(
            startedAtMillis = startedAtMillis,
            fallbackStartedRealtimeMillis = fallbackStartedRealtimeMillis,
            interactionPausedMillis = activeTurnInteractionClock?.pausedMillis ?: 0L,
            interactionPauseStartedAtMillis = activeTurnInteractionClock?.pauseStartedAtMillis,
        ),
        startedAtMillis,
        fallbackStartedRealtimeMillis,
        activeTurnInteractionClock,
        paused,
    ) {
        while (!paused) {
            value = runningWorkDurationMillis(
                startedAtMillis = startedAtMillis,
                fallbackStartedRealtimeMillis = fallbackStartedRealtimeMillis,
                interactionPausedMillis = activeTurnInteractionClock?.pausedMillis ?: 0L,
                interactionPauseStartedAtMillis = activeTurnInteractionClock?.pauseStartedAtMillis,
            )
            kotlinx.coroutines.delay(1_000L)
        }
    }
    AgentWorkingStatusHeader(
        title = formatWorkedSummaryTitle(durationMillis),
    )
}

@Composable
private fun PendingAssistantTimeline(
    blocks: List<AssistantResponseBlock>,
    workspaceDirectory: String,
    allowRootImageRead: Boolean,
    onOpenLink: (String) -> Unit,
    knowledgeCitations: List<kira.ditto.data.KnowledgeCitation> = emptyList(),
    pendingToolInvocationStateKey: String,
    pendingToolInvocations: List<ChatToolInvocation>,
    activeTurnStartedAtMillis: Long?,
    activeTurnInteractionClock: TurnActivityClock? = null,
    agentModeSelected: Boolean,
    hideBrowserDeskSubagents: Boolean,
    agentModeDisplayState: AgentModeDisplayState,
    chromeSelected: Boolean,
    chromeDisplayState: AgentModeDisplayState,
    onAttachAgentModePreviewSurface: (Surface) -> Unit,
    onDetachAgentModePreviewSurface: (Surface) -> Unit,
) {
    val visiblePendingInvocations = pendingToolInvocations.filterNot {
        it.isAgentModeDisplayInvocation() ||
            it.isChromeDisplayInvocation()
    }
    val pendingCapsuleInvocations = collectToolCollaborationInvocations(
        visiblePendingInvocations + blocks.flatMap { block ->
            when (block) {
                is AssistantResponseBlock.ToolGroup -> block.toolInvocations
                is AssistantResponseBlock.Reasoning -> block.trace.toolInvocations
                else -> emptyList()
            }
        },
        hideBrowserSwarmUi = hideBrowserDeskSubagents && !agentModeSelected,
    )
    val pendingCapsuleIds = pendingCapsuleInvocations.mapTo(hashSetOf(), ChatToolInvocation::id)
    val pendingSpotifyInvocations = collectSpotifyPlaybackInvocations(
        visiblePendingInvocations + blocks.flatMap { block ->
            when (block) {
                is AssistantResponseBlock.ToolGroup -> block.toolInvocations
                is AssistantResponseBlock.Reasoning -> block.trace.toolInvocations
                else -> emptyList()
            }
        },
    )
    val pendingRelatedInvocations = visiblePendingInvocations + blocks.flatMap { block ->
        when (block) {
            is AssistantResponseBlock.ToolGroup -> block.toolInvocations
            is AssistantResponseBlock.Reasoning -> block.trace.toolInvocations
            else -> emptyList()
        }
    }
    val pendingSpotifyIds = pendingSpotifyInvocations.mapTo(hashSetOf(), ChatToolInvocation::id)
    val pendingGmailSearchInvocations = collectGmailLiveInvocations(pendingRelatedInvocations)
    val pendingGmailSearchIds = pendingGmailSearchInvocations.mapTo(hashSetOf(), ChatToolInvocation::id)
    val pendingAmapNavInvocations = collectAmapNavigationInvocations(pendingRelatedInvocations)
    val pendingAmapNavIds = pendingAmapNavInvocations.mapTo(hashSetOf(), ChatToolInvocation::id)
    val remainingPendingInvocations = visiblePendingInvocations.filterNot {
        it.id in pendingCapsuleIds ||
            it.id in pendingSpotifyIds ||
            it.id in pendingGmailSearchIds ||
            it.id in pendingAmapNavIds ||
            it.isBrowserDeskSingle() ||
            it.isSilentBrowserHostTool(pendingRelatedInvocations)
    }
    if (blocks.isEmpty()) {
        if (visiblePendingInvocations.isNotEmpty() || pendingCapsuleInvocations.isNotEmpty()) {
            val pendingToolsStartedAtMillis = visiblePendingInvocations
                .mapNotNull { it.startedAtMillis.takeIf { timestamp -> timestamp > 0L } }
                .plus(listOfNotNull(activeTurnStartedAtMillis?.takeIf { it > 0L }))
                .filter { it >= MinimumWallClockMillis }
                .minOrNull()
            RunningWorkStatusHeader(
                startedAtMillis = pendingToolsStartedAtMillis,
                fallbackKey = pendingToolInvocationStateKey,
                activeTurnInteractionClock = activeTurnInteractionClock,
            )
            CollaborationCapsuleStack(invocations = pendingCapsuleInvocations)
            SpotifyPlaybackCardStack(invocations = pendingSpotifyInvocations)
            GmailSearchCardStack(
                invocations = pendingGmailSearchInvocations,
                relatedInvocations = pendingRelatedInvocations,
            )
            BrowserPreviewCardHost(placement = BrowserPreviewPlacement.Pending)
            AmapNavigationCardStack(invocations = pendingAmapNavInvocations)
            if (remainingPendingInvocations.isNotEmpty()) {
                ToolInvocationList(
                    toolInvocations = remainingPendingInvocations,
                    stateKey = pendingToolInvocationStateKey,
                    autoExpand = true,
                )
            }
        }
        return
    }

    val workStartedAtMillis = listOfNotNull(
        blocks.workStartedAtMillis(),
        activeTurnStartedAtMillis?.takeIf { it >= MinimumWallClockMillis },
    ).minOrNull()
    val shouldShowWorkingDisclosure = blocks.any { block ->
        when (block) {
            is AssistantResponseBlock.Text -> block.text.isNotBlank()
            is AssistantResponseBlock.ToolGroup -> block.toolInvocations.isNotEmpty()
            is AssistantResponseBlock.Reasoning -> hasVisibleReasoningStatus(block.trace)
            is AssistantResponseBlock.Status -> block.text.isNotBlank()
        }
    }

    if (shouldShowWorkingDisclosure) {
        RunningWorkStatusHeader(
            startedAtMillis = workStartedAtMillis,
            fallbackKey = activeTurnStartedAtMillis,
            activeTurnInteractionClock = activeTurnInteractionClock,
        )
        CollaborationCapsuleStack(invocations = pendingCapsuleInvocations)
        SpotifyPlaybackCardStack(invocations = pendingSpotifyInvocations)
        GmailSearchCardStack(
            invocations = pendingGmailSearchInvocations,
            relatedInvocations = pendingRelatedInvocations,
        )
        BrowserPreviewCardHost(placement = BrowserPreviewPlacement.Pending)
        AmapNavigationCardStack(invocations = pendingAmapNavInvocations)
        blocks.forEachIndexed { index, block ->
            PendingAssistantTimelineBlock(
                block = block,
                index = index,
                isLastBlock = index == blocks.lastIndex,
                agentModePreviewVisible = false,
                workspaceDirectory = workspaceDirectory,
                allowRootImageRead = allowRootImageRead,
                onOpenLink = onOpenLink,
                knowledgeCitations = knowledgeCitations,
                pendingToolInvocationStateKey = pendingToolInvocationStateKey,
                relatedInvocations = pendingRelatedInvocations,
                agentModeSelected = agentModeSelected,
                hideBrowserDeskSubagents = hideBrowserDeskSubagents,
                agentModeDisplayState = agentModeDisplayState,
                onAttachAgentModePreviewSurface = onAttachAgentModePreviewSurface,
                onDetachAgentModePreviewSurface = onDetachAgentModePreviewSurface,
            )
        }
    }
}

@Composable
private fun PendingAssistantTimelineBlock(
    block: AssistantResponseBlock,
    @Suppress("UNUSED_PARAMETER") index: Int,
    isLastBlock: Boolean,
    agentModePreviewVisible: Boolean,
    workspaceDirectory: String,
    allowRootImageRead: Boolean,
    onOpenLink: (String) -> Unit,
    knowledgeCitations: List<kira.ditto.data.KnowledgeCitation> = emptyList(),
    pendingToolInvocationStateKey: String,
    relatedInvocations: List<ChatToolInvocation> = emptyList(),
    @Suppress("UNUSED_PARAMETER") agentModeSelected: Boolean,
    hideBrowserDeskSubagents: Boolean,
    @Suppress("UNUSED_PARAMETER") agentModeDisplayState: AgentModeDisplayState,
    @Suppress("UNUSED_PARAMETER") onAttachAgentModePreviewSurface: (Surface) -> Unit,
    @Suppress("UNUSED_PARAMETER") onDetachAgentModePreviewSurface: (Surface) -> Unit,
) {
    when (block) {
        is AssistantResponseBlock.Text -> {
            if (!agentModePreviewVisible) {
                val visible = assistantMarkdownForGmailCards(block.text, relatedInvocations)
                if (visible.isNotBlank()) {
                    CompositionLocalProvider(
                        LocalAmapPlaces provides amapPlacesFromInvocations(relatedInvocations),
                    ) {
                    PendingAssistantResponseBlock(
                        text = visible,
                        workspaceDirectory = workspaceDirectory,
                        allowRootImageRead = allowRootImageRead,
                        onOpenLink = onOpenLink,
                        knowledgeCitations = knowledgeCitations,
                    )
                    }
                }
            }
        }

        is AssistantResponseBlock.ToolGroup -> {
            val visibleInvocations = block.toolInvocations.filterNot {
                it.isAgentModeDisplayInvocation() ||
                    it.isChromeDisplayInvocation() ||
                    it.isCollaborationCapsule() ||
                    it.isSpotifyPlaybackCard() ||
                    it.isGmailLiveCard() ||
                    it.isAmapNavigationCard() ||
                    it.isSilentBrowserHostTool(relatedInvocations) ||
                    (hideBrowserDeskSubagents && it.isBrowserDeskSingle())
            }
            if (visibleInvocations.isNotEmpty()) {
                ToolInvocationList(
                    toolInvocations = visibleInvocations,
                    stateKey = "$pendingToolInvocationStateKey-${block.id}",
                    autoExpand = isLastBlock,
                )
            }
        }

        is AssistantResponseBlock.Reasoning -> {
            if (hasVisibleReasoningStatus(block.trace)) {
                ReasoningTraceStatus(
                    trace = block.trace,
                    excludedToolInvocationIds = block.trace.toolInvocations
                        .filter {
                            it.isAgentModeDisplayInvocation() ||
                                it.isCollaborationCapsule() ||
                                it.isSpotifyPlaybackCard() ||
                                it.isGmailLiveCard() ||
                                it.isAmapNavigationCard() ||
                                it.isSilentBrowserHostTool(relatedInvocations) ||
                                // Same rule as the ToolGroup branch above. Leaving it off here let a
                                // browser agent that happened to land inside a reasoning trace show
                                // up as a plain subagent line while its own card sat right below.
                                (hideBrowserDeskSubagents && it.isBrowserDeskSingle())
                        }
                        .mapTo(hashSetOf()) { it.id },
                    onOpenLink = onOpenLink,
                )
            }
        }

        is AssistantResponseBlock.Status -> ReconnectingStatusCard(
            text = block.text,
            detail = block.detail,
        )
    }
}

private fun AssistantResponseBlock.agentModeToolInvocations(): List<ChatToolInvocation> = when (this) {
    is AssistantResponseBlock.ToolGroup -> toolInvocations.filter { it.isAgentModeDisplayInvocation() }
    is AssistantResponseBlock.Reasoning -> trace.toolInvocations.filter { it.isAgentModeDisplayInvocation() }
    is AssistantResponseBlock.Text -> emptyList()
    is AssistantResponseBlock.Status -> emptyList()
}

private fun AssistantResponseBlock.chromeToolInvocations(): List<ChatToolInvocation> = when (this) {
    is AssistantResponseBlock.ToolGroup -> toolInvocations.filter { it.isChromeDisplayInvocation() }
    is AssistantResponseBlock.Reasoning -> trace.toolInvocations.filter { it.isChromeDisplayInvocation() }
    is AssistantResponseBlock.Text -> emptyList()
    is AssistantResponseBlock.Status -> emptyList()
}

private fun ChatToolInvocation.isChromeDisplayInvocation(): Boolean {
    if (isSubagentLaunch()) return false
    val n = toolName.trim().lowercase().replace('-', '_')
    return n == "chrome" ||
        n == "browser" ||
        n.contains("webmcp") ||
        n.contains("tabs_") ||
        n.contains("page_") ||
        n.contains("passwords") ||
        n.contains("history_search") ||
        n.contains("http_fetch") ||
        n.contains("search_web") ||
        n.contains("resources_list") ||
        n.contains("network_recent")
}

private fun List<AssistantResponseBlock>.firstAgentModeBlockIndex(): Int =
    indexOfFirst { it.agentModeToolInvocations().isNotEmpty() }

private fun List<AssistantResponseBlock>.lastTextBlockAfterAgentMode(): String? {
    val firstAgentModeBlockIndex = firstAgentModeBlockIndex()
    if (firstAgentModeBlockIndex < 0) {
        return null
    }
    return drop(firstAgentModeBlockIndex + 1)
        .filterIsInstance<AssistantResponseBlock.Text>()
        .lastOrNull { it.text.isNotBlank() }
        ?.text
}

private fun List<AssistantResponseBlock>.latestReasoningStatusAfterTool(
    predicate: (ChatToolInvocation) -> Boolean,
): String {
    val firstToolBlock = indexOfFirst { block ->
        when (block) {
            is AssistantResponseBlock.ToolGroup -> block.toolInvocations.any(predicate)
            is AssistantResponseBlock.Reasoning -> block.trace.toolInvocations.any(predicate)
            is AssistantResponseBlock.Text -> false
            is AssistantResponseBlock.Status -> false
        }
    }
    if (firstToolBlock < 0) return ""
    return drop(firstToolBlock).asReversed().firstNotNullOfOrNull { block ->
        val trace = (block as? AssistantResponseBlock.Reasoning)?.trace ?: return@firstNotNullOfOrNull null
        trace.latestStatusText.ifBlank {
            trace.chunks.lastOrNull { it.detail.isNotBlank() || it.title.isNotBlank() }
                ?.let { it.detail.ifBlank(it::title) }
                .orEmpty()
        }.takeIf(String::isNotBlank)
    }.orEmpty()
}

private fun List<AssistantResponseBlock>.visibleText(): String =
    filterIsInstance<AssistantResponseBlock.Text>()
        .joinToString("\n\n") { it.text }

private fun List<AssistantResponseBlock>.hasVisiblePendingWork(): Boolean =
    any { block ->
        when (block) {
            is AssistantResponseBlock.Text -> block.text.isNotBlank()
            is AssistantResponseBlock.ToolGroup -> block.toolInvocations.isNotEmpty()
            is AssistantResponseBlock.Reasoning -> hasVisibleReasoningStatus(block.trace)
            is AssistantResponseBlock.Status -> block.text.isNotBlank()
        }
    }

private fun List<AssistantResponseBlock>.workStartedAtMillis(): Long? =
    flatMap { block ->
        when (block) {
            is AssistantResponseBlock.Text -> emptyList()
            is AssistantResponseBlock.Status -> emptyList()
            is AssistantResponseBlock.ToolGroup -> block.toolInvocations.mapNotNull {
                it.startedAtMillis.takeIf { timestamp -> timestamp > 0L }
            }
            is AssistantResponseBlock.Reasoning -> buildList {
                block.trace.startedAtMillis.takeIf { it > 0L }?.let(::add)
                block.trace.chunks.forEach { chunk ->
                    chunk.createdAtMillis.takeIf { it > 0L }?.let(::add)
                }
                block.trace.toolInvocations.forEach { invocation ->
                    invocation.startedAtMillis.takeIf { it > 0L }?.let(::add)
                }
            }
        }
    }
        .filter { it >= MinimumWallClockMillis }
        .minOrNull()

internal fun runningWorkDurationMillis(
    startedAtMillis: Long?,
    fallbackStartedRealtimeMillis: Long,
    nowMillis: Long = System.currentTimeMillis(),
    nowRealtimeMillis: Long = SystemClock.elapsedRealtime(),
    interactionPausedMillis: Long = 0L,
    interactionPauseStartedAtMillis: Long? = null,
): Long {
    // Time spent waiting on unanswered user interactions does not count as
    // active work; while paused the duration stays frozen.
    val pausedTotalMillis = interactionPausedMillis +
        (interactionPauseStartedAtMillis?.let { (nowMillis - it).coerceAtLeast(0L) } ?: 0L)
    return if (startedAtMillis != null) {
        (nowMillis - startedAtMillis - pausedTotalMillis).coerceAtLeast(1_000L)
    } else {
        (nowRealtimeMillis - fallbackStartedRealtimeMillis).coerceAtLeast(1_000L)
    }
}

/**
 * The browser's answer surface streams the same blocks this conversation does, so it reuses the
 * same block-to-message mapping instead of growing a second, drifting copy.
 */
internal fun assistantMessagesForPendingBlocks(
    blocks: List<AssistantResponseBlock>,
    responseGroupId: String?,
    messageIdPrefix: String?,
): List<ChatMessage> = pendingAssistantGroup(
    blocks = blocks,
    responseGroupId = responseGroupId,
    messageIdPrefix = messageIdPrefix,
    chromeSelected = false,
)?.messages.orEmpty()

private fun pendingAssistantGroup(
    blocks: List<AssistantResponseBlock>,
    responseGroupId: String?,
    messageIdPrefix: String?,
    chromeSelected: Boolean,
): ConversationListItem.AssistantGroup? {
    if (blocks.isEmpty() || responseGroupId.isNullOrBlank() || messageIdPrefix.isNullOrBlank()) {
        return null
    }
    val messages = blocks.mapIndexedNotNull { index, block ->
        val id = "$messageIdPrefix-${block.id}"
        when (block) {
            is AssistantResponseBlock.Text -> {
                if (block.text.isBlank()) {
                    null
                } else {
                    ChatMessage(
                        id = id,
                        author = MessageAuthor.Agent,
                        text = block.text,
                        createdAtMillis = index.toLong(),
                        responseGroupId = responseGroupId,
                        isIncomplete = true,
                    )
                }
            }
            is AssistantResponseBlock.ToolGroup -> {
                if (block.toolInvocations.isEmpty()) {
                    null
                } else {
                    ChatMessage(
                        id = id,
                        author = MessageAuthor.Agent,
                        text = "",
                        createdAtMillis = index.toLong(),
                        toolInvocations = block.toolInvocations,
                        responseGroupId = responseGroupId,
                        isIncomplete = true,
                    )
                }
            }
            is AssistantResponseBlock.Reasoning -> ChatMessage(
                id = id,
                author = MessageAuthor.Agent,
                text = "",
                createdAtMillis = index.toLong(),
                toolInvocations = block.trace.toolInvocations,
                reasoningTrace = block.trace,
                responseGroupId = responseGroupId,
                isIncomplete = true,
            )
            is AssistantResponseBlock.Status -> {
                if (block.text.isBlank()) {
                    null
                } else {
                    ChatMessage(
                        id = id,
                        author = MessageAuthor.Agent,
                        text = "",
                        createdAtMillis = index.toLong(),
                        responseGroupId = responseGroupId,
                        isIncomplete = true,
                        statusText = block.text,
                        statusDetail = block.detail,
                    )
                }
            }
        }
    }
    if (messages.isEmpty()) return null
    return ConversationListItem.AssistantGroup(messages)
}

private fun buildConversationListItems(
    messages: List<ChatMessage>,
): List<ConversationListItem> = buildList {
    var index = 0
    while (index < messages.size) {
        val message = messages[index]
        when (message.displayKind) {
            MessageDisplayKind.HiddenContext -> {
                index += 1
                continue
            }

            MessageDisplayKind.CompactStatus -> {
                add(ConversationListItem.CompactStatus(message))
                index += 1
                continue
            }

            MessageDisplayKind.Standard -> Unit
        }
        val responseGroupId = message.responseGroupId
        if (
            message.author == MessageAuthor.Agent &&
            (!responseGroupId.isNullOrBlank() || isLegacyAssistantGroupStart(messages, index))
        ) {
            val groupedMessages = buildList {
                var groupIndex = index
                while (groupIndex < messages.size) {
                    val candidate = messages[groupIndex]
                    if (candidate.author != MessageAuthor.Agent) {
                        break
                    }
                    val matchesGroup = if (!responseGroupId.isNullOrBlank()) {
                        candidate.responseGroupId == responseGroupId
                    } else {
                        val offset = groupIndex - index
                        candidate.responseGroupId.isNullOrBlank() &&
                            candidate.createdAtMillis == message.createdAtMillis + offset
                    }
                    if (!matchesGroup) {
                        break
                    }
                    add(candidate)
                    groupIndex += 1
                }
            }
            if (groupedMessages.isNotEmpty()) {
                add(ConversationListItem.AssistantGroup(groupedMessages))
                index += groupedMessages.size
                continue
            }
        }
        add(ConversationListItem.Message(message))
        index += 1
    }
}

private fun assistantGroupFinalBodyMarkdown(messages: List<ChatMessage>): String =
    messages.lastOrNull { message -> message.text.isNotBlank() }?.text.orEmpty()

private fun expandLongAssistantMarkdown(
    items: List<ConversationListItem>,
    parsedBlocks: Map<String, List<MarkdownBlock>>,
    streamingGroupKey: String?,
    imagesByGroupKey: Map<String, List<BrowserInlineImage>> = emptyMap(),
    requireTopicMatch: Boolean = false,
): List<ConversationListItem> = items.flatMap { item ->
    val group = item as? ConversationListItem.AssistantGroup ?: return@flatMap listOf(item)
    if (group.key == streamingGroupKey) return@flatMap listOf(item)
    val raw = assistantGroupFinalBodyMarkdown(group.messages)
    if (raw.length < AssistantMarkdownSplitMinChars) return@flatMap listOf(item)
    val markdown = attachTopicImages(
        markdown = injectAmapPlaceMarkup(
            normalizeMarkdownSource(raw),
            amapPlacesFromMessages(group.messages),
        ),
        bundles = topicBundlesFromImages(imagesByGroupKey[group.key].orEmpty()),
    )
    val blocks = parsedBlocks[group.key]
        ?: MarkdownBlockCache.peek(markdown)
        ?: return@flatMap listOf(item)
    if (blocks.size < AssistantMarkdownSplitMinBlocks) return@flatMap listOf(item)
    buildList {
        add(group.copy(omitBodyMarkdown = true))
        blocks.forEachIndexed { index, block ->
            add(
                ConversationListItem.AssistantMarkdownSlice(
                    groupKey = group.key,
                    blockIndex = index,
                    block = block,
                    isLast = index == blocks.lastIndex,
                    messages = group.messages,
                ),
            )
        }
    }
}

private fun conversationPlanHostItemKey(
    items: List<ConversationListItem>,
    planAnchorMessageId: String?,
    planAnchorGroupId: String?,
): String? {
    val chronological = items.flatMap { item ->
        when (item) {
            is ConversationListItem.Message -> listOf(item.message)
            is ConversationListItem.AssistantGroup -> item.messages
            is ConversationListItem.AssistantMarkdownSlice -> emptyList()
            is ConversationListItem.CompactStatus -> emptyList()
        }
    }
    val hostKey = sessionPlanHostKey(
        messages = chronological,
        planAnchorMessageId = planAnchorMessageId,
        planAnchorGroupId = planAnchorGroupId,
    )
    val matching = items.lastOrNull { item ->
        when (item) {
            is ConversationListItem.Message ->
                item.key == hostKey ||
                    chatMessageHostsSessionPlan(
                        message = item.message,
                        planAnchorMessageId = planAnchorMessageId,
                        planAnchorGroupId = planAnchorGroupId,
                    )
            is ConversationListItem.AssistantGroup ->
                item.key == hostKey ||
                    item.messages.any { message ->
                        chatMessageHostsSessionPlan(
                            message = message,
                            planAnchorMessageId = planAnchorMessageId,
                            planAnchorGroupId = planAnchorGroupId,
                        )
                    }
            is ConversationListItem.AssistantMarkdownSlice -> false
            is ConversationListItem.CompactStatus -> false
        }
    }
    return matching?.key
        ?: items.lastOrNull { it is ConversationListItem.AssistantGroup }?.key
        ?: hostKey
}

@Composable
private fun ConversationItemWithOptionalPlan(
    showPlan: Boolean,
    planEntries: List<SessionPlanEntry>,
    planDocumentMarkdown: String = "",
    content: @Composable () -> Unit,
) {
    val hasDocument = planDocumentMarkdown.isNotBlank()
    val hasTodos = planEntries.isNotEmpty()
    if (!showPlan || (!hasDocument && !hasTodos)) {
        content()
        return
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        content()
        if (hasDocument) {
            SessionPlanDocumentCard(markdown = planDocumentMarkdown)
        }
        if (hasTodos) {
            SessionPlanChecklist(
                entries = planEntries,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SessionPlanDocumentCard(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(AetherSurfaceHigh.copy(alpha = 0.72f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.chat_plan_document_title),
            style = MaterialTheme.typography.labelLarge,
            color = AetherOnSurface,
        )
        MarkdownContent(markdown = markdown)
    }
}

private data class CompactCommandSuggestion(
    val percent: Int?,
)

private fun compactCommandSuggestion(messages: List<ChatMessage>): CompactCommandSuggestion {
    val visibleMessages = messages.filter {
        it.displayKind != MessageDisplayKind.HiddenContext &&
            it.displayKind != MessageDisplayKind.CompactStatus
    }
    if (visibleMessages.size < 2) return CompactCommandSuggestion(percent = null)
    val estimatedChars = visibleMessages.sumOf { message ->
        message.text.length +
            message.attachments.sumOf { attachment ->
                attachment.name.length + attachment.mimeType.length + attachment.workspacePath.length
            } +
            message.toolInvocations.sumOf { invocation ->
                invocation.toolName.length + invocation.argumentsJson.length + invocation.outputJson.length
            }
    }
    val percent = ((estimatedChars * 100L) / 120_000L).toInt().coerceIn(1, 100)
    return CompactCommandSuggestion(percent = percent)
}

@Composable
private fun CompactStatusDivider(
    text: String,
    isRunning: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(AetherOnSurfaceVariant.copy(alpha = 0.08f)),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(
                imageVector = if (isRunning) Icons.Rounded.RadioButtonUnchecked else Icons.Rounded.Check,
                contentDescription = null,
                tint = AetherOnSurfaceVariant.copy(alpha = 0.82f),
                modifier = Modifier.size(15.dp),
            )
            if (isRunning) {
                ShimmerStatusText(
                    text = text,
                    modifier = Modifier.widthIn(max = 190.dp),
                    travelDurationMillis = 2200,
                    pauseDurationMillis = 700,
                )
            } else {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = AetherOnSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(AetherOnSurfaceVariant.copy(alpha = 0.08f)),
        )
    }
}

@Composable
private fun CompactCommandSuggestion(
    text: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(AetherSurface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.RadioButtonUnchecked,
                contentDescription = null,
                tint = AetherOnSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = stringResource(R.string.chat_compact),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = AetherOnSurface,
            maxLines = 1,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = AetherOnSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SlashCommandSuggestionRow(
    suggestion: SlashCommandSuggestion,
    detail: String,
    input: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(AetherSurfaceHigh.copy(alpha = 0.92f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = when (suggestion.icon) {
                SlashCommandIcon.Skill -> Icons.Rounded.AutoAwesome
                SlashCommandIcon.Extension -> Icons.Rounded.Extension
                SlashCommandIcon.Command -> Icons.Rounded.Compress
            },
            contentDescription = null,
            tint = AetherOnSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = slashHighlightedName(suggestion.command, input),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = AetherOnSurface,
        )
        MarqueeText(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
            color = AetherOnSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun FileMentionSuggestionRow(
    suggestion: FileMentionSuggestion,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(AetherSurfaceHigh.copy(alpha = 0.92f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.AttachFile,
            contentDescription = null,
            tint = AetherOnSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = suggestion.name,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = AetherOnSurface,
        )
        Text(
            text = suggestion.guestPath,
            style = MaterialTheme.typography.bodyMedium,
            color = AetherOnSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
        )
    }
}

private fun isLegacyAssistantGroupStart(
    messages: List<ChatMessage>,
    index: Int,
): Boolean {
    val message = messages.getOrNull(index) ?: return false
    if (
        message.author != MessageAuthor.Agent ||
        !message.responseGroupId.isNullOrBlank()
    ) {
        return false
    }
    val next = messages.getOrNull(index + 1) ?: return false
    return next.author == MessageAuthor.Agent &&
        next.responseGroupId.isNullOrBlank() &&
        next.createdAtMillis == message.createdAtMillis + 1
}

@Composable
private fun PendingSessionInputBubble(
    pendingInput: PendingSessionInput,
    onSendNow: () -> Unit,
) {
    val attachmentLabel = when (pendingInput.attachmentCount) {
        0 -> null
        1 -> stringResource(R.string.chat_attachment_count_one)
        else -> stringResource(R.string.chat_attachment_count_other, pendingInput.attachmentCount)
    }
    val canSendNow = pendingInput.mode == SessionFollowUpMode.Queue
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canSendNow) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(AetherSurfaceHigh)
                    .clickable(onClick = onSendNow),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.ArrowUpward,
                    contentDescription = stringResource(R.string.chat_pending_send_now),
                    tint = AetherOnSurface,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .shadow(10.dp, RoundedCornerShape(24.dp), ambientColor = AetherScrim, spotColor = AetherScrim)
                    .clip(RoundedCornerShape(24.dp))
                    .background(AetherMessageBubble)
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                Text(
                    text = pendingInput.preview.ifBlank {
                        stringResource(R.string.chat_additional_context)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = AetherOnPrimaryContainer,
                )
            }
            attachmentLabel?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = AetherOnSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ConversationComposerOverlay(
    modifier: Modifier = Modifier,
    conversationStateKey: String,
    onBodyHeightChanged: (Int) -> Unit,
    value: String,
    attachments: List<ChatAttachment>,
    availableSkills: List<InstalledSkill>,
    availableMcpServers: List<McpServerConfig>,
    selectedSkillIds: List<String>,
    selectedMcpServerIds: List<String>,
    agentModeAvailable: Boolean,
    agentModeSelected: Boolean,
    chromeAvailable: Boolean,
    chromeSelected: Boolean,
    isEditing: Boolean,
    termuxSetupState: TermuxSetupState,
    isSending: Boolean,
    showStarterPromptHint: Boolean,
    showTermuxSetupNotice: Boolean,
    compactSuggestionText: String,
    agentSlashCommands: List<SlashCommandSuggestion> = emptyList(),
    workspaceFileSuggestions: List<FileMentionSuggestion> = emptyList(),
    sessionModeId: String = "",
    onSessionModeSelected: (String) -> Unit = {},
    onSendCommand: (String) -> Unit = {},
    promptDirective: String = "",
    onSetPromptDirective: (String) -> Unit = {},
    pendingPermissionRequests: List<PendingPermissionRequest> = emptyList(),
    onAnswerPermissionRequest: (String, String) -> Unit = { _, _ -> },
    pendingElicitationRequests: List<PendingElicitationRequest> = emptyList(),
    onAnswerElicitationRequest: (String, JSONObject?) -> Unit = { _, _ -> },
    pendingMcpSecretPrompt: PendingMcpSecretPromptUi? = null,
    onSubmitMcpSecrets: (Map<String, String>) -> Unit = {},
    onSkipMcpSecrets: () -> Unit = {},
    onValueChange: (String) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onSetSkillSelected: (String, Boolean) -> Unit,
    onSetMcpServerSelected: (String, Boolean) -> Unit,
    onSetAgentModeSelected: (Boolean) -> Unit,
    onToggleAgentModeTeaching: () -> Unit = {},
    agentModeTeachingActive: Boolean = false,
    agentModePreviewHasContent: Boolean = false,
    onSetChromeSelected: (Boolean) -> Unit,
    onCancelEdit: () -> Unit,
    onPickImages: () -> Unit,
    onPickFiles: () -> Unit,
    onRequestTermuxPermission: () -> Unit,
    onOpenAppPermissions: () -> Unit,
    onOpenTermuxSettings: () -> Unit,
    onOpenTermux: () -> Unit,
    onInstallTermux: () -> Unit,
    onRefreshTermuxSetup: () -> Unit,
    onPauseGeneration: () -> Unit,
    onDismissTermuxSetupNotice: () -> Unit,
    onDismissStarterPromptHint: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    composerInteractive: Boolean = true,
    asrAvailable: Boolean = false,
    appSettings: AppSettings = AppSettings(),
    providerConfigs: List<LlmProviderConfig> = emptyList(),
    onRequestRecordAudio: () -> Unit = {},
    recordAudioGranted: Boolean = false,
    stationContent: @Composable () -> Unit = {},
    onSend: () -> Unit,
) {
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    var elicitationOtherPending by remember { mutableStateOf<ElicitationOtherPending?>(null) }
    var composerFocusNonce by remember { mutableIntStateOf(0) }
    val elicitationOtherPlaceholder = stringResource(R.string.chat_elicitation_other_placeholder)
    LaunchedEffect(pendingElicitationRequests) {
        val liveIds = pendingElicitationRequests.map { it.requestId }.toSet()
        if (elicitationOtherPending?.requestId !in liveIds) {
            elicitationOtherPending = null
        }
    }
    val bottomLift by animateDpAsState(
        targetValue = if (imeVisible) 12.dp else 18.dp,
        animationSpec = tween(durationMillis = 260, easing = ChatGptMotionEasing),
        label = "composer_bottom_lift",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.ime.only(WindowInsetsSides.Bottom))
            .navigationBarsPadding()
            .padding(bottom = bottomLift),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { onBodyHeightChanged(it.height) },
        ) {
            Column {
                AetherExtensionSlot(
                    slot = AetherExtensionSlotChatComposerTop,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                AgentPermissionRequestStack(
                    requests = pendingPermissionRequests,
                    onAnswer = onAnswerPermissionRequest,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
                AgentElicitationRequestStack(
                    requests = pendingElicitationRequests,
                    onSubmit = { requestId, answers ->
                        elicitationOtherPending = null
                        onAnswerElicitationRequest(requestId, answers)
                    },
                    onSkip = { requestId ->
                        elicitationOtherPending = null
                        onAnswerElicitationRequest(requestId, null)
                    },
                    onSelectOther = { pending ->
                        elicitationOtherPending = pending
                        if (pending != null) composerFocusNonce += 1
                    },
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
                pendingMcpSecretPrompt?.let { prompt ->
                    McpSecretPromptCard(
                        prompt = prompt,
                        onSubmit = onSubmitMcpSecrets,
                        onSkip = onSkipMcpSecrets,
                        modifier = Modifier.padding(horizontal = 14.dp),
                    )
                }
                stationContent()
                key(conversationStateKey) {
                    ConversationComposerBar(
                    conversationStateKey = conversationStateKey,
                    value = value,
                    attachments = attachments,
                    availableSkills = availableSkills,
                    availableMcpServers = availableMcpServers,
                    selectedSkillIds = selectedSkillIds,
                    selectedMcpServerIds = selectedMcpServerIds,
                    agentModeAvailable = agentModeAvailable,
                    agentModeSelected = agentModeSelected,
                    chromeAvailable = chromeAvailable,
                    chromeSelected = chromeSelected,
                    isEditing = isEditing,
                    termuxSetupState = termuxSetupState,
                    isSending = isSending,
                    showStarterPromptHint = showStarterPromptHint,
                    showTermuxSetupNotice = showTermuxSetupNotice,
                    compactSuggestionText = compactSuggestionText,
                    agentSlashCommands = agentSlashCommands,
                    workspaceFileSuggestions = workspaceFileSuggestions,
                    sessionModeId = sessionModeId,
                    onSessionModeSelected = onSessionModeSelected,
                    onSendCommand = onSendCommand,
                    promptDirective = promptDirective,
                    onSetPromptDirective = onSetPromptDirective,
                    onValueChange = onValueChange,
                    onRemoveAttachment = onRemoveAttachment,
                    onSetSkillSelected = onSetSkillSelected,
                    onSetMcpServerSelected = onSetMcpServerSelected,
                    onSetAgentModeSelected = onSetAgentModeSelected,
                    onToggleAgentModeTeaching = onToggleAgentModeTeaching,
                    agentModeTeachingActive = agentModeTeachingActive,
                    agentModePreviewHasContent = agentModePreviewHasContent,
                    onSetChromeSelected = onSetChromeSelected,
                    onCancelEdit = onCancelEdit,
                    onPickImages = onPickImages,
                    onPickFiles = onPickFiles,
                    onRequestTermuxPermission = onRequestTermuxPermission,
                    onOpenAppPermissions = onOpenAppPermissions,
                    onOpenTermuxSettings = onOpenTermuxSettings,
                    onOpenTermux = onOpenTermux,
                    onInstallTermux = onInstallTermux,
                    onRefreshTermuxSetup = onRefreshTermuxSetup,
                    onPauseGeneration = onPauseGeneration,
                    onDismissTermuxSetupNotice = onDismissTermuxSetupNotice,
                    onDismissStarterPromptHint = onDismissStarterPromptHint,
                    onFocusChanged = onFocusChanged,
                    composerFocusNonce = composerFocusNonce,
                    composerInteractive = composerInteractive,
                    asrAvailable = asrAvailable,
                    appSettings = appSettings,
                    providerConfigs = providerConfigs,
                    onRequestRecordAudio = onRequestRecordAudio,
                    recordAudioGranted = recordAudioGranted,
                    placeholderOverride = if (elicitationOtherPending != null) {
                        elicitationOtherPlaceholder
                    } else {
                        ""
                    },
                    onSend = {
                        val pending = elicitationOtherPending
                        val custom = value.trim()
                        if (pending != null && custom.isNotEmpty()) {
                            onAnswerElicitationRequest(
                                pending.requestId,
                                buildElicitationAnswers(pending.request, pending.selections, custom),
                            )
                            elicitationOtherPending = null
                            onValueChange("")
                        } else {
                            onSend()
                        }
                    },
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationComposerBar(
    modifier: Modifier = Modifier,
    conversationStateKey: String,
    value: String,
    attachments: List<ChatAttachment>,
    availableSkills: List<InstalledSkill>,
    availableMcpServers: List<McpServerConfig>,
    selectedSkillIds: List<String>,
    selectedMcpServerIds: List<String>,
    agentModeAvailable: Boolean,
    agentModeSelected: Boolean,
    chromeAvailable: Boolean,
    chromeSelected: Boolean,
    isEditing: Boolean,
    termuxSetupState: TermuxSetupState,
    isSending: Boolean,
    showStarterPromptHint: Boolean,
    showTermuxSetupNotice: Boolean,
    compactSuggestionText: String,
    agentSlashCommands: List<SlashCommandSuggestion> = emptyList(),
    workspaceFileSuggestions: List<FileMentionSuggestion> = emptyList(),
    sessionModeId: String = "",
    onSessionModeSelected: (String) -> Unit = {},
    onSendCommand: (String) -> Unit = {},
    promptDirective: String = "",
    onSetPromptDirective: (String) -> Unit = {},
    onValueChange: (String) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onSetSkillSelected: (String, Boolean) -> Unit,
    onSetMcpServerSelected: (String, Boolean) -> Unit,
    onSetAgentModeSelected: (Boolean) -> Unit,
    onToggleAgentModeTeaching: () -> Unit = {},
    agentModeTeachingActive: Boolean = false,
    agentModePreviewHasContent: Boolean = false,
    onSetChromeSelected: (Boolean) -> Unit,
    onCancelEdit: () -> Unit,
    onPickImages: () -> Unit,
    onPickFiles: () -> Unit,
    onRequestTermuxPermission: () -> Unit,
    onOpenAppPermissions: () -> Unit,
    onOpenTermuxSettings: () -> Unit,
    onOpenTermux: () -> Unit,
    onInstallTermux: () -> Unit,
    onRefreshTermuxSetup: () -> Unit,
    onPauseGeneration: () -> Unit,
    onDismissTermuxSetupNotice: () -> Unit,
    onDismissStarterPromptHint: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    composerFocusNonce: Int = 0,
    composerInteractive: Boolean = true,
    asrAvailable: Boolean = false,
    appSettings: AppSettings = AppSettings(),
    providerConfigs: List<LlmProviderConfig> = emptyList(),
    onRequestRecordAudio: () -> Unit = {},
    recordAudioGranted: Boolean = false,
    placeholderOverride: String = "",
    onSend: () -> Unit,
) {
    var attachmentMenuExpanded by remember(conversationStateKey) { mutableStateOf(false) }
    val attachmentMenuVisibility = remember(conversationStateKey) { MutableTransitionState(false) }
    attachmentMenuVisibility.targetState = attachmentMenuExpanded
    val attachmentMenuActionScope = rememberCoroutineScope()
    val fieldFocusRequester = remember { FocusRequester() }
    var textFieldFocused by remember { mutableStateOf(false) }
    var fieldValue by remember(conversationStateKey) { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    val context = LocalContext.current
    val asrScope = rememberCoroutineScope()
    val asrSession = remember(conversationStateKey) {
        ComposerAsrSession(context, AsrEngineRouter(context) { providerConfigs })
    }
    var asrListening by remember { mutableStateOf(false) }
    var asrStopping by remember { mutableStateOf(false) }
    var asrPartial by remember { mutableStateOf("") }
    var asrPrefix by remember { mutableStateOf("") }
    var asrHadError by remember { mutableStateOf(false) }
    var asrLevels by remember { mutableStateOf(List(ComposerAsrBarCount) { 0.12f }) }
    var asrStartedAtElapsed by remember { mutableStateOf(0L) }
    var pendingAsrStart by remember { mutableStateOf(false) }
    DisposableEffect(conversationStateKey) {
        onDispose { asrSession.cancel() }
    }
    fun pushAsrLevel(relative: Float) {
        val visual = composerAsrVisualLevel(relative)
        val last = asrLevels.lastOrNull() ?: 0.12f
        val mixed = if (visual >= last) {
            last + (visual - last) * 0.84f
        } else {
            last + (visual - last) * 0.50f
        }
        asrLevels = asrLevels.drop(1) + mixed.coerceIn(0.08f, 1f)
    }
    fun applyComposerAsrDraft(partial: String) {
        val next = joinComposerAsrText(asrPrefix, partial)
        fieldValue = TextFieldValue(next, TextRange(next.length))
        onValueChange(next)
    }
    fun startComposerAsr() {
        if (asrListening || asrStopping) return
        attachmentMenuExpanded = false
        if (!recordAudioGranted) {
            pendingAsrStart = true
            onRequestRecordAudio()
            return
        }
        asrLevels = List(ComposerAsrBarCount) { 0.12f }
        asrPrefix = value
        asrPartial = ""
        asrHadError = false
        asrStartedAtElapsed = SystemClock.elapsedRealtime()
        asrListening = true
        asrScope.launch {
            val started = asrSession.start(
                settings = appSettings,
                language = "zh",
                onRms = { rms -> pushAsrLevel(rms) },
                onPartial = { text ->
                    asrPartial = text
                    applyComposerAsrDraft(text)
                },
                onError = { message ->
                    asrHadError = true
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                },
            )
            if (!started) {
                asrListening = false
            }
        }
    }
    fun stopComposerAsr() {
        if (!asrListening || asrStopping) return
        val listenedFor = SystemClock.elapsedRealtime() - asrStartedAtElapsed
        if (listenedFor < ComposerAsrStopGuardMillis) return
        asrStopping = true
        asrScope.launch {
            try {
                val text = asrSession.stop()
                asrListening = false
                val spoken = text.ifBlank { asrPartial }
                asrPartial = ""
                if (spoken.isNotBlank()) {
                    applyComposerAsrDraft(spoken)
                } else {
                    applyComposerAsrDraft("")
                    if (!asrHadError && listenedFor >= ComposerAsrEmptyToastMinMillis) {
                        val messageRes = composerAsrEmptyMessageRes(
                            hadVisualVoice = asrLevels.any { level -> level > 0.28f },
                            listenedForMillis = listenedFor,
                        )
                        Toast.makeText(
                            context,
                            context.getString(messageRes),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            } finally {
                asrStopping = false
            }
        }
    }
    LaunchedEffect(recordAudioGranted, pendingAsrStart) {
        if (!pendingAsrStart || !recordAudioGranted || asrListening) return@LaunchedEffect
        pendingAsrStart = false
        startComposerAsr()
    }
    LaunchedEffect(value) {
        if (value != fieldValue.text) fieldValue = TextFieldValue(value, TextRange(value.length))
    }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var measuredTextLineCount by remember { mutableIntStateOf(1) }
    var measuredTextHeight by remember { mutableStateOf(22.dp) }
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    LaunchedEffect(composerInteractive) {
        if (!composerInteractive) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
    }
    LaunchedEffect(composerFocusNonce) {
        if (composerFocusNonce <= 0 || !composerInteractive) return@LaunchedEffect
        fieldFocusRequester.requestFocus()
        keyboardController?.show()
    }
    LaunchedEffect(asrListening) {
        if (!asrListening) return@LaunchedEffect
        fieldFocusRequester.requestFocus()
        keyboardController?.hide()
    }
    LaunchedEffect(asrListening, imeVisible) {
        if (asrListening && imeVisible) keyboardController?.hide()
    }
    val selectedSkillSet = remember(selectedSkillIds) { selectedSkillIds.toSet() }
    val selectedMcpServerSet = remember(selectedMcpServerIds) { selectedMcpServerIds.toSet() }
    val selectedSkillActions = remember(availableSkills, selectedSkillSet) {
        availableSkills.filter { selectedSkillSet.contains(it.id) }
    }
    val selectedMcpActions = remember(availableMcpServers, selectedMcpServerSet) {
        availableMcpServers.filter { selectedMcpServerSet.contains(it.id) }
    }
    val planModeActive = sessionModeId.trim().lowercase() == "plan"
    val activePromptDirective = ComposerPromptDirective.fromStorageValue(promptDirective)
    val hasSelectedActions =
        selectedSkillActions.isNotEmpty() ||
            selectedMcpActions.isNotEmpty() ||
            agentModeSelected ||
            chromeSelected ||
            activePromptDirective != null ||
            planModeActive
    val extensionUiController = LocalAetherExtensionUiController.current
    val hasExtensionActionTray =
        extensionUiController
            ?.snapshot
            ?.componentsAt(AetherExtensionComponentChatComposerActionTray)
            ?.isNotEmpty() == true ||
            extensionUiController
                ?.nativeComponents
                ?.any { it.target == AetherExtensionComponentChatComposerActionTray } == true
    val hasComposerActionTray = hasSelectedActions || hasExtensionActionTray
    val composerPlaceholder = when {
        value.isNotBlank() -> ""
        asrListening -> stringResource(R.string.composer_asr_placeholder)
        placeholderOverride.isNotBlank() -> placeholderOverride
        attachments.isNotEmpty() -> stringResource(R.string.chat_add_note)
        activePromptDirective == ComposerPromptDirective.Goal ->
            stringResource(R.string.chat_goal_chip_placeholder)
        activePromptDirective == ComposerPromptDirective.Swarm ->
            stringResource(R.string.chat_swarm_chip_placeholder)
        activePromptDirective == ComposerPromptDirective.Tower ->
            stringResource(R.string.chat_tower_chip_placeholder)
        planModeActive ->
            stringResource(R.string.chat_plan_mode_placeholder)
        agentModeSelected && selectedSkillActions.isEmpty() && selectedMcpActions.isEmpty() ->
            stringResource(R.string.chat_ask_agent_mode)
        chromeSelected && !agentModeSelected && selectedSkillActions.isEmpty() && selectedMcpActions.isEmpty() ->
            stringResource(R.string.chat_ask_chrome)
        selectedSkillActions.size + selectedMcpActions.size == 1 && !agentModeSelected && !chromeSelected -> {
            selectedSkillActions.firstOrNull()?.quickActionLabel()
                ?: selectedMcpActions.firstOrNull()?.quickActionLabel()
                ?: stringResource(R.string.chat_reply_to_aether)
        }
        selectedSkillActions.isNotEmpty() || selectedMcpActions.isNotEmpty() ||
            agentModeSelected || chromeSelected ->
            stringResource(R.string.chat_ask_with_selected_tools)
        else -> stringResource(R.string.chat_ask_aether)
    }
    val hasDraft = value.isNotBlank() || attachments.isNotEmpty()
    val slashSuggestions = remember(fieldValue.text, agentSlashCommands) {
        slashCommandSuggestions(fieldValue.text, agentCommands = agentSlashCommands)
    }
    val fileSuggestions = remember(fieldValue.text, workspaceFileSuggestions, slashSuggestions) {
        if (slashSuggestions.isNotEmpty()) emptyList()
        else {
            val query = fileMentionQuery(fieldValue.text) ?: return@remember emptyList()
            if (!workspaceFileMentionLooksSpecific(query)) emptyList()
            else fileMentionSuggestions(fieldValue.text, workspaceFileSuggestions)
        }
    }
    val pluginMentionSuggestions = remember(fieldValue.text, availableMcpServers, slashSuggestions) {
        if (slashSuggestions.isNotEmpty()) return@remember emptyList()
        val query = fileMentionQuery(fieldValue.text) ?: return@remember emptyList()
        availableMcpServers.filter { server ->
            pluginMentionQueryMatches(
                query = query,
                id = server.id,
                displayName = server.displayName,
                actionLabel = server.quickActionLabel(),
            )
        }.take(24)
    }
    // Keep the last non-empty suggestion list so the popup still has content
    // while its exit animation plays; otherwise the list empties instantly and
    // the dismiss animation runs on a collapsed, invisible popup.
    val retainedSlashSuggestions = remember { mutableStateOf(slashSuggestions) }
    val retainedFileSuggestions = remember { mutableStateOf(fileSuggestions) }
    val retainedPluginSuggestions = remember { mutableStateOf(pluginMentionSuggestions) }
    SideEffect {
        if (slashSuggestions.isNotEmpty()) retainedSlashSuggestions.value = slashSuggestions
        if (fileSuggestions.isNotEmpty()) retainedFileSuggestions.value = fileSuggestions
        if (pluginMentionSuggestions.isNotEmpty()) retainedPluginSuggestions.value = pluginMentionSuggestions
    }
    fun applySlashSuggestion(command: String) {
        val typedLength = fieldValue.text.drop(1).takeWhile { !it.isWhitespace() }.length
        val replaceEnd = (1 + typedLength).coerceAtMost(fieldValue.text.length)
        val suffix = fieldValue.text.substring(replaceEnd)
        val needsSpace = suffix.isEmpty() && slashSuggestions.firstOrNull { it.command == command }?.argumentHint?.isNotBlank() == true
        val replacement = command + if (needsSpace) " " else ""
        val next = replacement + suffix
        fieldValue = TextFieldValue(next, TextRange(replacement.length))
        onValueChange(next)
    }
    fun applyFileMention(suggestion: FileMentionSuggestion) {
        val text = fieldValue.text
        val at = text.lastIndexOf('@')
        if (at < 0) return
        val query = fileMentionQuery(text).orEmpty()
        val tokenEnd = (at + 1 + query.length).coerceAtMost(text.length)
        val suffix = text.substring(tokenEnd).trimStart()
        val replacement = suggestion.insertToken + " "
        val next = text.substring(0, at) + replacement + suffix
        fieldValue = TextFieldValue(next, TextRange((at + replacement.length).coerceAtMost(next.length)))
        onValueChange(next)
    }
    fun clearMentionTrigger() {
        val (next, cursor) = removeMentionTriggerToken(fieldValue.text) ?: return
        fieldValue = TextFieldValue(next, TextRange(cursor))
        onValueChange(next)
    }
    val canSendDraft = attachments.all { it.workspaceState == AttachmentWorkspaceState.Ready }
    val showPauseButton = isSending && !hasDraft
    val showSubmitButton = asrListening || !isSending || hasDraft
    val keepPlusSeparated = value.isNotBlank() || hasComposerActionTray
    val plusSeparated = !asrListening && (keepPlusSeparated || (textFieldFocused && imeVisible))
    val explicitTextLineCount = if (value.isBlank()) {
        1
    } else {
        value.count { it == '\n' } + 1
    }
    val composerTextLineCount = if (asrListening || value.isBlank()) {
        1
    } else {
        maxOf(explicitTextLineCount, measuredTextLineCount).coerceIn(1, 5)
    }
    val isMultilineComposer = !asrListening && composerTextLineCount > 1
    val composerTextStyle = MaterialTheme.typography.bodyLarge.copy(
        color = AetherOnSurface,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.Both,
        ),
    )
    val fieldTopPadding = when {
        hasComposerActionTray -> 12.dp
        isMultilineComposer -> 12.dp
        else -> 8.dp
    }
    val fieldBottomPadding = when {
        hasComposerActionTray -> 12.dp
        isMultilineComposer -> 12.dp
        else -> 8.dp
    }
    LaunchedEffect(textFieldFocused) {
        onFocusChanged(textFieldFocused)
    }
    fun runAfterAttachmentMenuDismiss(action: () -> Unit) {
        attachmentMenuExpanded = false
        action()
    }
    val composerHorizontalPadding by animateDpAsState(
        targetValue = when {
            plusSeparated -> 14.dp
            hasComposerActionTray -> 18.dp
            else -> 30.dp
        },
        animationSpec = tween(durationMillis = 260, easing = ChatGptMotionEasing),
        label = "composer_horizontal_padding",
    )
    val fieldStartPadding by animateDpAsState(
        targetValue = if (plusSeparated) 50.dp else 0.dp,
        animationSpec = tween(durationMillis = 260, easing = ChatGptMotionEasing),
        label = "composer_field_start",
    )
    val fieldContentStartPadding by animateDpAsState(
        targetValue = if (plusSeparated) 18.dp else 52.dp,
        animationSpec = tween(durationMillis = 260, easing = ChatGptMotionEasing),
        label = "composer_field_content_start",
    )
    val asrFieldContentStart = if (asrListening) 18.dp else fieldContentStartPadding
    val fieldMinHeight by animateDpAsState(
        targetValue = if (asrListening) {
            if (plusSeparated) 56.dp else 50.dp
        } else {
            maxOf(
                if (plusSeparated) 56.dp else 50.dp,
                measuredTextHeight + fieldTopPadding + fieldBottomPadding,
            )
        },
        animationSpec = tween(durationMillis = 260, easing = ChatGptMotionEasing),
        label = "composer_field_min_height",
    )
    val plusShadowElevation by animateDpAsState(
        targetValue = if (plusSeparated) 10.dp else 0.dp,
        animationSpec = tween(durationMillis = 260, easing = ChatGptMotionEasing),
        label = "composer_plus_shadow",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = composerHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (showTermuxSetupNotice) {
            TermuxSetupNotice(
                setupState = termuxSetupState,
                onRequestPermission = onRequestTermuxPermission,
                onOpenAppPermissions = onOpenAppPermissions,
                onOpenTermuxSettings = onOpenTermuxSettings,
                onOpenTermux = onOpenTermux,
                onInstallTermux = onInstallTermux,
                onRefresh = onRefreshTermuxSetup,
                onDismiss = onDismissTermuxSetupNotice,
            )
        }
        if (showStarterPromptHint) {
            SurfaceNotice(
                title = stringResource(R.string.chat_first_prompt_ready_title),
                subtitle = stringResource(R.string.chat_first_prompt_ready_subtitle),
                actionLabel = stringResource(R.string.common_hide),
                onAction = onDismissStarterPromptHint,
                actionEnabled = true,
            )
        }
        if (isEditing) {
            SurfaceNotice(
                title = stringResource(R.string.chat_editing_earlier_message_title),
                subtitle = stringResource(R.string.chat_editing_earlier_message_subtitle),
                actionLabel = stringResource(R.string.common_cancel),
                onAction = onCancelEdit,
                actionEnabled = true,
            )
        }
        AnimatedVisibility(
            visible = attachments.isEmpty() && slashSuggestions.isNotEmpty(),
            enter = fadeIn(animationSpec = tween(durationMillis = 160, easing = ChatGptMotionEasing)) +
                slideInVertically(
                    animationSpec = tween(durationMillis = 220, easing = ChatGptMotionEasing),
                    initialOffsetY = { it / 3 },
                ),
            exit = fadeOut(animationSpec = tween(durationMillis = 120, easing = ChatGptMotionEasing)) +
                slideOutVertically(
                    animationSpec = tween(durationMillis = 180, easing = ChatGptMotionEasing),
                    targetOffsetY = { it / 3 },
                ),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(AetherSurfaceHigh.copy(alpha = 0.98f))
                    .padding(vertical = 4.dp),
            ) {
                val compactRow = retainedSlashSuggestions.value.firstOrNull { suggestion ->
                    suggestion.command.equals(CompactSlashCommand, ignoreCase = true)
                }
                val otherSuggestions = retainedSlashSuggestions.value.filterNot { suggestion ->
                    suggestion.command.equals(CompactSlashCommand, ignoreCase = true)
                }
                if (compactRow != null) {
                    item(key = CompactSlashCommand) {
                        CompactCommandSuggestion(
                            text = compactSuggestionText,
                            onClick = {
                                val typed = fieldValue.text.trim()
                                onSendCommand(
                                    if (isCompactSlashCommand(typed)) typed else CompactSlashCommand,
                                )
                            },
                        )
                    }
                }
                items(otherSuggestions, key = { it.command }) { suggestion ->
                    SlashCommandSuggestionRow(
                        suggestion = suggestion,
                        detail = suggestion.description,
                        input = fieldValue.text,
                        onClick = { applySlashSuggestion(suggestion.command) },
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = attachments.isEmpty() && slashSuggestions.isEmpty() &&
                (fileSuggestions.isNotEmpty() || pluginMentionSuggestions.isNotEmpty()),
            enter = fadeIn(animationSpec = tween(durationMillis = 160, easing = ChatGptMotionEasing)) +
                slideInVertically(
                    animationSpec = tween(durationMillis = 220, easing = ChatGptMotionEasing),
                    initialOffsetY = { it / 3 },
                ),
            exit = fadeOut(animationSpec = tween(durationMillis = 120, easing = ChatGptMotionEasing)) +
                slideOutVertically(
                    animationSpec = tween(durationMillis = 180, easing = ChatGptMotionEasing),
                    targetOffsetY = { it / 3 },
                ),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(AetherSurfaceHigh.copy(alpha = 0.98f))
                    .padding(vertical = 4.dp),
            ) {
                items(retainedFileSuggestions.value, key = { it.guestPath }) { suggestion ->
                    FileMentionSuggestionRow(
                        suggestion = suggestion,
                        onClick = { applyFileMention(suggestion) },
                    )
                }
                items(retainedPluginSuggestions.value, key = { it.id }) { server ->
                    UpaPluginMentionRow(
                        server = server,
                        selected = selectedMcpServerSet.contains(server.id),
                        onClick = {
                            onSetMcpServerSelected(server.id, !selectedMcpServerSet.contains(server.id))
                            clearMentionTrigger()
                        },
                    )
                }
            }
        }
        if (attachments.isNotEmpty()) {
            ComposerAttachmentTray(
                attachments = attachments,
                onRemoveAttachment = onRemoveAttachment,
            )
        }

        val fieldShape = if (plusSeparated) ComposerFocusedCardShape else ComposerCardShape
        val fieldControlAlignment = if (isMultilineComposer) Alignment.Bottom else Alignment.CenterVertically
        val fieldTextAlignment = if (isMultilineComposer) Alignment.TopStart else Alignment.CenterStart
        val plusButtonAlignment = if (isMultilineComposer || hasComposerActionTray) Alignment.BottomStart else Alignment.CenterStart
        Box(
            modifier = Modifier
                .fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = fieldStartPadding)
            ) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .offset(y = 8.dp)
                        .blur(
                            radius = 22.dp,
                            edgeTreatment = BlurredEdgeTreatment.Unbounded,
                        )
                        .clip(fieldShape)
                        .background(ChatGptComposerShadow),
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = fieldMinHeight)
                        .animateContentSize(
                            animationSpec = tween(durationMillis = 320, easing = ChatGptMotionEasing),
                        )
                        .clip(fieldShape)
                        .background(AetherSurface)
                        .padding(
                            start = asrFieldContentStart,
                            end = 8.dp,
                            top = fieldTopPadding,
                            bottom = fieldBottomPadding,
                        ),
                    verticalArrangement = Arrangement.spacedBy(if (hasComposerActionTray) 10.dp else 0.dp),
                ) {
                    AnimatedVisibility(
                        visible = hasComposerActionTray,
                        enter = fadeIn(
                            animationSpec = tween(durationMillis = 220, easing = ChatGptMotionEasing),
                        ) + slideInVertically(
                            animationSpec = tween(durationMillis = 280, easing = ChatGptMotionEasing),
                            initialOffsetY = { -it / 2 },
                        ),
                        exit = fadeOut(
                            animationSpec = tween(durationMillis = 160, easing = ChatGptMotionEasing),
                        ) + slideOutVertically(
                            animationSpec = tween(durationMillis = 220, easing = ChatGptMotionEasing),
                            targetOffsetY = { -it / 3 },
                        ),
                    ) {
                        AetherExtensionComponentHost(
                            target = AetherExtensionComponentChatComposerActionTray,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            ComposerActionTray(
                                modifier = Modifier.fillMaxWidth(),
                                skills = selectedSkillActions,
                                mcpServers = selectedMcpActions,
                                agentModeSelected = agentModeSelected,
                                agentModeTeachingActive = agentModeTeachingActive,
                                agentModePreviewHasContent = agentModePreviewHasContent,
                                chromeSelected = chromeSelected,
                                promptDirective = activePromptDirective,
                                planModeActive = planModeActive,
                                onRemoveSkill = { skillId -> onSetSkillSelected(skillId, false) },
                                onRemoveMcpServer = { serverId -> onSetMcpServerSelected(serverId, false) },
                                onRemoveAgentMode = { onSetAgentModeSelected(false) },
                                onToggleAgentModeTeaching = onToggleAgentModeTeaching,
                                onRemoveChrome = { onSetChromeSelected(false) },
                                onRemovePromptDirective = {
                                    onSetPromptDirective("")
                                },
                                onRemovePlanMode = { onSessionModeSelected(DefaultKimiPermissionMode) },
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = fieldControlAlignment,
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(
                                    min = if (asrListening) {
                                        22.dp
                                    } else {
                                        measuredTextHeight.coerceAtLeast(22.dp)
                                    },
                                ),
                            contentAlignment = fieldTextAlignment,
                        ) {
                            if (value.isBlank() && !(asrListening && asrPartial.isNotBlank())) {
                                Text(
                                    text = composerPlaceholder,
                                    style = composerTextStyle,
                                    color = Color(0xFF8C8C8C),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (asrListening && (asrPrefix.isNotEmpty() || asrPartial.isNotBlank())) {
                                ComposerAsrLiveText(
                                    prefix = asrPrefix,
                                    live = asrPartial,
                                    style = composerTextStyle,
                                    color = AetherOnSurface,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            BasicTextField(
                                value = fieldValue,
                                onValueChange = { next ->
                                    if (!composerInteractive || asrListening) return@BasicTextField
                                    fieldValue = next
                                    onValueChange(next.text)
                                },
                                enabled = composerInteractive,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(fieldFocusRequester)
                                    .onFocusChanged { focusState ->
                                        if (textFieldFocused != focusState.isFocused) {
                                            textFieldFocused = focusState.isFocused
                                        }
                                    },
                                textStyle = composerTextStyle.copy(
                                    color = if (asrListening) Color.Transparent else composerTextStyle.color,
                                ),
                                maxLines = if (asrListening) 1 else 5,
                                cursorBrush = SolidColor(
                                    if (asrListening) Color.Transparent else AetherOnSurface,
                                ),
                                onTextLayout = { textLayoutResult ->
                                    if (asrListening) return@BasicTextField
                                    val lineCount = textLayoutResult.lineCount.coerceIn(1, 5)
                                    if (measuredTextLineCount != lineCount) {
                                        measuredTextLineCount = lineCount
                                    }
                                    val visibleLineBottom = textLayoutResult.getLineBottom(lineCount - 1)
                                    val visibleLineTop = textLayoutResult.getLineTop(0)
                                    val textHeight = with(density) {
                                        (visibleLineBottom - visibleLineTop).toDp()
                                    }
                                    if (measuredTextHeight != textHeight) {
                                        measuredTextHeight = textHeight
                                    }
                                },
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        if (showPauseButton) {
                            ComposerPauseButton(
                                onClick = onPauseGeneration,
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        if (showSubmitButton) {
                            ComposerSubmitButton(
                                hasDraft = hasDraft,
                                canSendDraft = canSendDraft,
                                isSending = isSending,
                                asrAvailable = asrAvailable,
                                asrListening = asrListening,
                                asrStopping = asrStopping,
                                asrLevels = asrLevels,
                                onClick = {
                                    if (asrListening) {
                                        stopComposerAsr()
                                        return@ComposerSubmitButton
                                    }
                                    if (!hasDraft && asrAvailable) {
                                        startComposerAsr()
                                        return@ComposerSubmitButton
                                    }
                                    if (!hasDraft || !canSendDraft) return@ComposerSubmitButton
                                    onSend()
                                },
                            )
                        }
                    }
                }
            }

            Box(
                modifier = Modifier.align(plusButtonAlignment)
            ) {
                if (!asrListening) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .shadow(plusShadowElevation, CircleShape, ambientColor = ChatGptControlShadow, spotColor = ChatGptControlShadow)
                            .clip(CircleShape)
                            .background(if (plusSeparated) AetherSurface else Color.Transparent)
                            .clickable(onClick = { attachmentMenuExpanded = !attachmentMenuExpanded }),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = stringResource(R.string.chat_add_attachment_or_tool),
                            tint = AetherOnSurface,
                            modifier = Modifier.size(27.dp),
                        )
                    }
                }

                if (attachmentMenuVisibility.currentState || attachmentMenuVisibility.targetState) {
                    Popup(
                        alignment = Alignment.BottomStart,
                        offset = with(density) {
                            IntOffset(0, -42.dp.roundToPx())
                        },
                        onDismissRequest = { attachmentMenuExpanded = false },
                        properties = PopupProperties(
                            focusable = true,
                            dismissOnBackPress = true,
                            dismissOnClickOutside = true,
                        ),
                    ) {
                        androidx.compose.animation.AnimatedVisibility(
                            visibleState = attachmentMenuVisibility,
                            enter = fadeIn() +
                                scaleIn(
                                    initialScale = 0.92f,
                                    transformOrigin = TransformOrigin(0f, 1f),
                                ) +
                                slideInVertically(initialOffsetY = { it / 10 }),
                            exit = fadeOut() +
                                scaleOut(
                                    targetScale = 0.96f,
                                    transformOrigin = TransformOrigin(0f, 1f),
                                ) +
                                slideOutVertically(targetOffsetY = { it / 12 }),
                        ) {
                            AetherCapsuleSurface(
                                shape = AetherCapsulePanelShape,
                            ) {
                                Column(
                                    modifier = Modifier
                                        .width(IntrinsicSize.Max)
                                        .widthIn(min = 200.dp, max = 260.dp)
                                        .padding(horizontal = 8.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    ComposerPlusMenuRow(
                                        title = stringResource(R.string.chat_files),
                                        subtitle = stringResource(R.string.chat_plus_files_subtitle),
                                        icon = Icons.Rounded.AttachFile,
                                        iconTint = AetherOnSurfaceVariant,
                                        iconContainerColor = AetherSurfaceHigh,
                                        onClick = {
                                            runAfterAttachmentMenuDismiss(onPickFiles)
                                        },
                                    )
                                    ComposerPlusMenuRow(
                                        title = stringResource(R.string.chat_plus_goal),
                                        subtitle = stringResource(R.string.chat_plus_goal_subtitle),
                                        icon = Icons.Rounded.GpsFixed,
                                        iconTint = AetherOnSurfaceVariant,
                                        iconContainerColor = AetherSurfaceHigh,
                                        onClick = {
                                            runAfterAttachmentMenuDismiss {
                                                onSetPromptDirective(ComposerPromptDirective.Goal.storageValue)
                                                attachmentMenuActionScope.launch {
                                                    delay(180)
                                                    fieldFocusRequester.requestFocus()
                                                }
                                            }
                                        },
                                    )
                                    ComposerPlusMenuRow(
                                        title = stringResource(R.string.chat_permission_mode_plan),
                                        subtitle = stringResource(R.string.chat_plus_plan_subtitle),
                                        icon = Icons.Rounded.Edit,
                                        selected = planModeActive,
                                        iconTint = AetherOnSurfaceVariant,
                                        iconContainerColor = AetherSurfaceHigh,
                                        onClick = {
                                            runAfterAttachmentMenuDismiss {
                                                onSessionModeSelected(
                                                    if (planModeActive) DefaultKimiPermissionMode else "plan",
                                                )
                                            }
                                        },
                                    )
                                    ComposerPlusMenuRow(
                                        title = stringResource(R.string.chat_plus_swarm),
                                        subtitle = stringResource(R.string.chat_plus_swarm_subtitle),
                                        icon = Icons.AutoMirrored.Rounded.CallSplit,
                                        iconTint = AetherOnSurfaceVariant,
                                        iconContainerColor = AetherSurfaceHigh,
                                        onClick = {
                                            runAfterAttachmentMenuDismiss {
                                                onSetPromptDirective(ComposerPromptDirective.Swarm.storageValue)
                                                attachmentMenuActionScope.launch {
                                                    delay(180)
                                                    fieldFocusRequester.requestFocus()
                                                }
                                            }
                                        },
                                    )
                                    ComposerPlusMenuRow(
                                        title = stringResource(R.string.chat_plus_tower),
                                        subtitle = stringResource(R.string.chat_plus_tower_subtitle),
                                        icon = Icons.Rounded.AccountTree,
                                        iconTint = AetherOnSurfaceVariant,
                                        iconContainerColor = AetherSurfaceHigh,
                                        onClick = {
                                            runAfterAttachmentMenuDismiss {
                                                onSetPromptDirective(ComposerPromptDirective.Tower.storageValue)
                                                attachmentMenuActionScope.launch {
                                                    delay(180)
                                                    fieldFocusRequester.requestFocus()
                                                }
                                            }
                                        },
                                    )
                                    ComposerPlusMenuRow(
                                        title = stringResource(R.string.agent_mode_label),
                                        subtitle = stringResource(R.string.chat_plus_agent_mode_subtitle),
                                        icon = LucideIcons.MousePointer2,
                                        selected = agentModeSelected,
                                        iconTint = AetherOnSurfaceVariant,
                                        iconContainerColor = AetherSurfaceHigh,
                                        onClick = {
                                            runAfterAttachmentMenuDismiss {
                                                onSetAgentModeSelected(!agentModeSelected)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerPauseButton(
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(ChatGptPurple)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .offset(x = 0.5.dp)
                .size(11.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White)
        )
    }
}

@Composable
private fun ComposerSubmitButton(
    hasDraft: Boolean,
    canSendDraft: Boolean,
    isSending: Boolean,
    asrAvailable: Boolean = false,
    asrListening: Boolean = false,
    asrStopping: Boolean = false,
    asrLevels: List<Float> = emptyList(),
    onClick: () -> Unit,
) {
    val showAsrIdle = asrAvailable && !hasDraft && !asrListening
    val enabled = when {
        asrListening -> !asrStopping
        hasDraft -> canSendDraft
        else -> showAsrIdle
    }
    val highlight by animateFloatAsState(
        targetValue = if ((hasDraft && canSendDraft) || showAsrIdle || asrListening) 1f else 0f,
        animationSpec = tween(durationMillis = 240, easing = ChatGptMotionEasing),
        label = "composer_send_highlight",
    )
    val capsuleWidth by animateDpAsState(
        targetValue = if (asrListening) ComposerAsrCapsuleWidth else ComposerSubmitButtonSize,
        animationSpec = tween(durationMillis = 260, easing = ChatGptMotionEasing),
        label = "composer_asr_capsule_width",
    )
    val buttonColor = lerp(AetherSurfaceHigher, ChatGptPurple, highlight)
    val iconTint = lerp(Color.White.copy(alpha = 0.42f), Color.White, highlight)
    val stopVoice = stringResource(R.string.composer_voice_stop)
    val startVoice = stringResource(R.string.composer_voice_start)
    val sendFollowUp = stringResource(R.string.common_send_follow_up)
    val sendLabel = stringResource(R.string.common_send)
    Box(
        modifier = Modifier
            .height(ComposerSubmitButtonSize)
            .width(capsuleWidth)
            .graphicsLayer {
                if (asrListening) return@graphicsLayer
                val scale = 0.92f + 0.08f * highlight
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(percent = 50))
            .background(buttonColor)
            .clickable(
                enabled = enabled,
                onClick = onClick,
            )
            .semantics {
                contentDescription = when {
                    asrListening -> stopVoice
                    showAsrIdle -> startVoice
                    isSending -> sendFollowUp
                    else -> sendLabel
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (asrListening) {
            ComposerAsrWaveform(
                levels = asrLevels,
                barColor = Color.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .height(22.dp),
            )
        } else {
            AnimatedContent(
                targetState = showAsrIdle,
                transitionSpec = {
                    (
                        fadeIn(animationSpec = tween(durationMillis = 180, easing = ChatGptMotionEasing)) +
                            scaleIn(
                                initialScale = 0.55f,
                                animationSpec = tween(durationMillis = 220, easing = ChatGptMotionEasing),
                            )
                        ) togetherWith (
                        fadeOut(animationSpec = tween(durationMillis = 140, easing = ChatGptMotionEasing)) +
                            scaleOut(
                                targetScale = 0.55f,
                                animationSpec = tween(durationMillis = 160, easing = ChatGptMotionEasing),
                            )
                        )
                },
                label = "composer_submit_icon",
            ) { asr ->
                Icon(
                    imageVector = if (asr) Icons.Rounded.GraphicEq else Icons.Rounded.ArrowUpward,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(21.dp),
                )
            }
        }
    }
}

@Composable
private fun ComposerAsrWaveform(
    levels: List<Float>,
    modifier: Modifier = Modifier,
    barColor: Color = ChatGptPurple,
) {
    Canvas(modifier = modifier) {
        if (levels.isEmpty()) return@Canvas
        val barCount = levels.size
        val gap = 3.2f
        val barWidth = ((size.width - gap * (barCount + 1)) / barCount).coerceAtLeast(3.4f)
        val maxH = size.height * 0.92f
        levels.forEachIndexed { index, level ->
            val spread = 0.92f + 0.08f * sin(index * 1.17f)
            val mixed = (level * spread).coerceIn(0.08f, 1f)
            val h = (maxH * mixed).coerceAtLeast(5f)
            val x = gap + index * (barWidth + gap)
            val y = (size.height - h) / 2f
            drawRoundRect(
                color = barColor.copy(alpha = 0.38f + 0.62f * mixed),
                topLeft = androidx.compose.ui.geometry.Offset(x, y),
                size = androidx.compose.ui.geometry.Size(barWidth, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

@Composable
private fun ComposerAsrLiveText(
    prefix: String,
    live: String,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    val display = joinComposerAsrText(prefix, live)
    Row(
        modifier = modifier
            .clipToBounds()
            .horizontalScroll(scroll, enabled = false),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (prefix.isNotEmpty()) {
            Text(
                text = prefix,
                style = style,
                color = color,
                maxLines = 1,
                softWrap = false,
            )
            if (live.isNotBlank()) {
                Spacer(modifier = Modifier.width(4.dp))
            }
        }
        AnimatedContent(
            targetState = live,
            transitionSpec = {
                val growing = targetState.length >= initialState.length
                if (growing) {
                    (
                        slideInHorizontally(
                            animationSpec = tween(durationMillis = 240, easing = ChatGptMotionEasing),
                            initialOffsetX = { it / 3 },
                        ) + fadeIn(animationSpec = tween(durationMillis = 180, easing = ChatGptMotionEasing))
                        ) togetherWith (
                        slideOutHorizontally(
                            animationSpec = tween(durationMillis = 140, easing = ChatGptMotionEasing),
                            targetOffsetX = { -it / 6 },
                        ) + fadeOut(animationSpec = tween(durationMillis = 90, easing = ChatGptMotionEasing))
                        )
                } else {
                    fadeIn(animationSpec = tween(durationMillis = 120, easing = ChatGptMotionEasing)) togetherWith
                        fadeOut(animationSpec = tween(durationMillis = 80, easing = ChatGptMotionEasing))
                }.using(SizeTransform(clip = false))
            },
            label = "composer_asr_live_text",
        ) { text ->
            Text(
                text = text,
                style = style,
                color = color,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun ComposerActionTray(
    modifier: Modifier = Modifier,
    skills: List<InstalledSkill>,
    mcpServers: List<McpServerConfig>,
    agentModeSelected: Boolean,
    agentModeTeachingActive: Boolean = false,
    agentModePreviewHasContent: Boolean = false,
    chromeSelected: Boolean,
    promptDirective: ComposerPromptDirective? = null,
    planModeActive: Boolean = false,
    onRemoveSkill: (String) -> Unit,
    onRemoveMcpServer: (String) -> Unit,
    onRemoveAgentMode: () -> Unit,
    onToggleAgentModeTeaching: () -> Unit = {},
    onRemoveChrome: () -> Unit,
    onRemovePromptDirective: () -> Unit = {},
    onRemovePlanMode: () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(end = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (planModeActive) {
            ComposerActionChip(
                label = stringResource(R.string.chat_plan_mode_chip),
                icon = Icons.Rounded.Edit,
                onRemove = onRemovePlanMode,
            )
        }
        when (promptDirective) {
            ComposerPromptDirective.Goal -> ComposerActionChip(
                label = stringResource(R.string.chat_plus_goal),
                icon = Icons.Rounded.GpsFixed,
                onRemove = onRemovePromptDirective,
            )

            ComposerPromptDirective.Swarm -> ComposerActionChip(
                label = stringResource(R.string.chat_plus_swarm),
                icon = Icons.AutoMirrored.Rounded.CallSplit,
                onRemove = onRemovePromptDirective,
            )

            ComposerPromptDirective.Tower -> ComposerActionChip(
                label = stringResource(R.string.chat_plus_tower),
                icon = Icons.Rounded.AccountTree,
                onRemove = onRemovePromptDirective,
            )

            null -> Unit
        }
        if (agentModeSelected) {
            ComposerAgentModeTeachingChip(
                teachingActive = agentModeTeachingActive,
                previewHasContent = agentModePreviewHasContent,
                onToggleTeaching = onToggleAgentModeTeaching,
                onRemove = onRemoveAgentMode,
            )
        }
        skills.forEach { skill ->
            ComposerActionChip(
                label = skill.quickActionLabel(),
                icon = Icons.Rounded.Extension,
                onRemove = { onRemoveSkill(skill.id) },
            )
        }
        mcpServers.forEach { server ->
            ComposerActionChip(
                label = server.quickActionLabel(),
                icon = server.composerIcon(),
                iconTint = when {
                    isKaggleBrand(server.icon, server.id) -> AetherOnSurface
                    kira.ditto.data.SpotifyMcp.isShippedServerId(server.id) -> Color.Unspecified
                    else -> AetherComposerChipForeground
                },
                onRemove = { onRemoveMcpServer(server.id) },
            )
        }
    }
}

@Composable
private fun AgentModeConversationDock(
    identityKey: String,
    agentModeSelected: Boolean,
    isSending: Boolean,
    displayState: AgentModeDisplayState,
    phoneDeskHandoff: PhoneDeskHandoffState,
    phoneSettlement: PhoneSettlementUiState,
    lastUserMessageText: String = "",
    phoneSubagentInvocations: List<ChatToolInvocation> = emptyList(),
    phoneSubagentStarted: Boolean = false,
    reviewingEverMe: Boolean = false,
    collapsePreview: Boolean = false,
    pendingAssistantText: String = "",
    onPreviewExpandedChange: (Boolean) -> Unit = {},
    transcript: String,
    workspaceDirectory: String,
    allowRootImageRead: Boolean,
    onOpenLink: (String) -> Unit,
    onAttachSurface: (Surface) -> Unit,
    onDetachSurface: (Surface) -> Unit,
    onBindSurfaceView: (SurfaceView?) -> Unit,
    onTap: (Int, Int) -> Unit,
    onSwipe: (Int, Int, Int, Int, Int) -> Unit,
    onFinishTeaching: () -> Unit = {},
) {
    if (!agentModeConversationDockVisible(agentModeSelected = agentModeSelected, hasPinnedSubagents = false)) {
        return
    }
    val showVirtualDesk = agentModeVirtualDeskVisible(agentModeSelected)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showVirtualDesk) {
            AgentModePreviewPanel(
                identityKey = identityKey,
                displayState = displayState,
                toolInvocation = null,
                isSending = isSending,
                overlayText = "",
                workspaceDirectory = workspaceDirectory,
                allowRootImageRead = allowRootImageRead,
                onOpenLink = onOpenLink,
                onAttachSurface = onAttachSurface,
                onDetachSurface = onDetachSurface,
                onBindSurfaceView = onBindSurfaceView,
                onTap = onTap,
                onSwipe = onSwipe,
                onFinishTeaching = onFinishTeaching,
                transcript = transcript,
                phoneDeskHandoff = phoneDeskHandoff,
                phoneSettlement = phoneSettlement,
                lastUserMessageText = lastUserMessageText,
                phoneSubagentInvocations = phoneSubagentInvocations,
                phoneSubagentStarted = phoneSubagentStarted,
                reviewingEverMe = reviewingEverMe,
                collapsePreview = collapsePreview,
                onPreviewExpandedChange = onPreviewExpandedChange,
            )
        }
    }
}

/**
 * Should a finished preview stay on screen while this turn runs?
 *
 * Only when the turn is actually using the browser. Keying it on `isSending` alone meant any turn
 * at all kept the previous task's card up — writing an email produced a web card next to the mail
 * card, for a page that had nothing to do with the request.
 */
internal fun browserDeskRetainDuringTurn(
    isSending: Boolean,
    pendingAssistantText: String = "",
    hasBrowserWork: Boolean = true,
): Boolean = isSending && hasBrowserWork

internal fun browserDeskDockVisible(
    invocations: List<ChatToolInvocation>,
    deskState: kira.ditto.browser.BrowserDeskState,
    keepCompletedPreview: Boolean = false,
    sessionRunning: Boolean = false,
): Boolean {
    if (invocations.any { it.isRunning } || deskState.activities.isNotEmpty()) return true
    if (sessionRunning && deskState.activities.isNotEmpty()) return true
    if (!keepCompletedPreview) return false
    return sanitizeBrowserDeskPreviewForCard(deskState.preview) != null
}

internal fun browserDeskForcedExpanded(
    running: Boolean,
    collapsePreview: Boolean,
): Boolean? = when {
    running -> true
    collapsePreview -> false
    else -> null
}

internal fun browserDeskHeaderBusy(
    sessionRunning: Boolean,
    invocations: List<ChatToolInvocation>,
    activities: List<kira.ditto.browser.BrowserDeskActivity>,
): Boolean = sessionRunning ||
    invocations.any { it.isRunning } ||
    activities.isNotEmpty()

@Composable
private fun AgentModePreviewPanel(
    identityKey: String = "agent-mode-computer",
    displayState: AgentModeDisplayState,
    toolInvocation: ChatToolInvocation?,
    label: String? = null,
    contentDescription: String? = null,
    pendingText: String? = null,
    useLiveSurface: Boolean = true,
    isSending: Boolean = false,
    overlayText: String = "",
    workspaceDirectory: String = "",
    allowRootImageRead: Boolean = false,
    onOpenLink: (String) -> Unit = {},
    onAttachSurface: (Surface) -> Unit,
    onDetachSurface: (Surface) -> Unit,
    onBindSurfaceView: (SurfaceView?) -> Unit = {},
    onTap: (Int, Int) -> Unit = { _, _ -> },
    onSwipe: (Int, Int, Int, Int, Int) -> Unit = { _, _, _, _, _ -> },
    onFinishTeaching: () -> Unit = {},
    transcript: String = "",
    phoneDeskHandoff: PhoneDeskHandoffState = PhoneDeskHandoffState(),
    phoneSettlement: PhoneSettlementUiState = PhoneSettlementUiState(),
    lastUserMessageText: String = "",
    phoneSubagentInvocations: List<ChatToolInvocation> = emptyList(),
    phoneSubagentStarted: Boolean = false,
    reviewingEverMe: Boolean = false,
    collapsePreview: Boolean = false,
    onPreviewExpandedChange: (Boolean) -> Unit = {},
) {
    val resolvedLabel = label ?: stringResource(R.string.agent_mode_label)
    val resolvedContentDescription =
        contentDescription ?: stringResource(R.string.chat_agent_mode_virtual_display)
    val resolvedPendingText =
        pendingText ?: stringResource(R.string.chat_agent_mode_preview_pending)
    val bitmap = remember(displayState.latestPreviewPath, displayState.lastUpdatedMillis) {
        displayState.latestPreviewPath
            .takeIf { it.isNotBlank() }
            ?.let { BitmapFactory.decodeFile(it) }
    }
    if (useLiveSurface) {
        val failed = toolInvocation != null &&
            !toolInvocation.isRunning &&
            parseJsonObject(toolInvocation.outputJson)?.optBoolean("isError") == true
        val phoneRunning = phoneSubagentInvocations.any { it.isRunning }
        val phoneFinished = phoneSubagentInvocations.any { !it.isRunning }
        val deskBusy = isSending || reviewingEverMe || phoneRunning
        val showPhonePreview = agentModePhonePreviewVisible(
            deskBusy = deskBusy,
            displayActive = displayState.isActive,
            hasPreviewBitmap = bitmap != null,
        )
        val phase = when {
            failed -> AgentModeComputerPhase.Failed
            reviewingEverMe -> AgentModeComputerPhase.ReviewingEverMe
            displayState.teachingActive -> AgentModeComputerPhase.LearningSteps
            isSending && phoneFinished && !phoneRunning -> AgentModeComputerPhase.Summarizing
            phoneRunning -> AgentModeComputerPhase.Dispatching
            isSending -> AgentModeComputerPhase.Working
            displayState.isActive || bitmap != null -> AgentModeComputerPhase.Idle
            else -> AgentModeComputerPhase.Ready
        }
        AgentModeComputerCard(
            identityKey = identityKey,
            phase = phase,
            isRunning = deskBusy,
            initiallyExpanded = agentModeComputerShouldAutoExpand(phoneSubagentStarted),
            autoExpand = phoneSubagentStarted,
            collapsePreview = collapsePreview,
            onExpandedChange = onPreviewExpandedChange,
            handoff = phoneDeskHandoff,
            settlement = PhoneSettlementUiState(),
            showCollapseChevron = true,
            showPhonePreview = showPhonePreview,
            showOperator = displayState.teachingActive || reviewingEverMe,
            showCrewCapsules = false,
            showGuiSteps = false,
            teachingActive = displayState.teachingActive,
            dispatchTask = phoneDeskHandoff.task.ifBlank { lastUserMessageText },
            phoneSubagentInvocations = phoneSubagentInvocations,
            listening = displayState.listening,
            listenTranscript = displayState.listenTranscript,
            listenVisualOnly = displayState.listenVisualOnly,
            transcript = if (transcript.isNotBlank()) {
                {
                    AgentModePinnedTranscript(
                        text = transcript,
                        workspaceDirectory = workspaceDirectory,
                        allowRootImageRead = allowRootImageRead,
                        onOpenLink = onOpenLink,
                    )
                }
            } else {
                null
            },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (displayState.teachingHint.isNotBlank()) {
                    AgentModeTeachingBanner(
                        hint = displayState.teachingHint,
                        onFinish = onFinishTeaching,
                    )
                }
                AgentModePreviewCanvas(
                displayState = displayState,
                bitmap = if (displayState.isActive) null else bitmap,
                contentDescription = resolvedContentDescription,
                pendingText = "",
                showBootPlaceholder = false,
                useLiveSurface = true,
                overlayText = "",
                workspaceDirectory = workspaceDirectory,
                allowRootImageRead = allowRootImageRead,
                onOpenLink = onOpenLink,
                onAttachSurface = onAttachSurface,
                onDetachSurface = onDetachSurface,
                onBindSurfaceView = onBindSurfaceView,
                onTap = onTap,
                onSwipe = onSwipe,
            )
            }
        }
        return
    }
    var expanded by rememberSaveable(
        displayState.displayId,
        toolInvocation?.id.orEmpty(),
        useLiveSurface,
    ) {
        mutableStateOf(true)
    }
    AetherCapsuleBorderedSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .animateContentSize(animationSpec = tween(durationMillis = 280)),
        shape = RoundedCornerShape(20.dp),
    ) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
        if (toolInvocation != null) {
            AgentModePreviewToolStatus(toolInvocation = toolInvocation)
        } else {
            AgentModePreviewHeader(
                displayState = displayState,
                label = resolvedLabel,
                isChrome = !useLiveSurface,
            )
        }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(220)) + expandVertically(tween(280)),
            exit = fadeOut(tween(180)) + shrinkVertically(tween(240)),
        ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AgentModePreviewCanvas(
                displayState = displayState,
                bitmap = bitmap,
                contentDescription = resolvedContentDescription,
                pendingText = resolvedPendingText,
                showBootPlaceholder = false,
                useLiveSurface = false,
                overlayText = overlayText,
                workspaceDirectory = workspaceDirectory,
                allowRootImageRead = allowRootImageRead,
                onOpenLink = onOpenLink,
                onAttachSurface = onAttachSurface,
                onDetachSurface = onDetachSurface,
            )
        }
        }
    }
    }
}

@Composable
private fun AgentModePreviewCanvas(
    displayState: AgentModeDisplayState,
    bitmap: android.graphics.Bitmap?,
    contentDescription: String,
    pendingText: String,
    showBootPlaceholder: Boolean,
    useLiveSurface: Boolean,
    overlayText: String,
    workspaceDirectory: String,
    allowRootImageRead: Boolean,
    onOpenLink: (String) -> Unit,
    onAttachSurface: (Surface) -> Unit,
    onDetachSurface: (Surface) -> Unit,
    onBindSurfaceView: (SurfaceView?) -> Unit = {},
    onTap: (Int, Int) -> Unit = { _, _ -> },
    onSwipe: (Int, Int, Int, Int, Int) -> Unit = { _, _, _, _, _ -> },
) {
    val live = useLiveSurface && LocalPreviewLiveSurfaceEnabled.current
        if (displayState.isActive || bitmap != null || live) {
            var surfaceBounds by remember { mutableStateOf(Rect.Zero) }
            val configuration = LocalConfiguration.current
            val phoneAspect = displayState.width.coerceAtLeast(1).toFloat() /
                displayState.height.coerceAtLeast(1).toFloat()
            val maxPreviewHeight = (configuration.screenHeightDp * 0.52f).dp
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth(),
            ) {
            val horizontalPad = 18.dp
            val verticalPad = 12.dp
            val availableWidth = (maxWidth - horizontalPad * 2).coerceAtLeast(1.dp)
            val heightIfFullWidth = availableWidth / phoneAspect
            val phoneHeight = minOf(heightIfFullWidth, maxPreviewHeight - verticalPad * 2)
            val phoneWidth = phoneHeight * phoneAspect
            val frameHeight = phoneHeight + verticalPad * 2
            val phoneScreenModifier = Modifier
                .size(phoneWidth, phoneHeight)
                .clip(RoundedCornerShape(22.dp))
                .focusProperties { canFocus = false }
                .onGloballyPositioned { coordinates ->
                    surfaceBounds = coordinates.boundsInParent()
                }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(frameHeight)
                    .clip(RoundedCornerShape(18.dp))
                    .background(agentModePreviewBackdropBrush())
                    .focusProperties { canFocus = false }
                    .then(
                        if (live) {
                            Modifier.pointerInput(
                                displayState.width,
                                displayState.height,
                                surfaceBounds,
                                displayState.teachingActive,
                            ) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val start = down.position
                            var last = start
                            var maxDist = 0f
                            val slop = viewConfiguration.touchSlop
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (change.pressed) {
                                    last = change.position
                                    maxDist = maxOf(maxDist, (last - start).getDistance())
                                    change.consume()
                                }
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }
                            }
                            if (!agentModePreviewUserInputEnabled(displayState.teachingActive)) {
                                return@awaitEachGesture
                            }
                            val preview = IntSize(size.width, size.height)
                            val padding = 12.dp.toPx()
                            val startDisplay = mapPreviewOffsetToDisplay(
                                offset = start,
                                previewSize = preview,
                                imagePaddingPx = padding,
                                displayWidth = displayState.width,
                                displayHeight = displayState.height,
                                surfaceBounds = surfaceBounds,
                            ) ?: return@awaitEachGesture
                            val endDisplay = mapPreviewOffsetToDisplay(
                                offset = last,
                                previewSize = preview,
                                imagePaddingPx = padding,
                                displayWidth = displayState.width,
                                displayHeight = displayState.height,
                                surfaceBounds = surfaceBounds,
                            ) ?: return@awaitEachGesture
                            if (maxDist > slop) {
                                onSwipe(
                                    startDisplay.first,
                                    startDisplay.second,
                                    endDisplay.first,
                                    endDisplay.second,
                                    280,
                                )
                            } else {
                                onTap(startDisplay.first, startDisplay.second)
                            }
                        }
                            }
                        } else {
                            Modifier
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                var previewSize by remember { mutableStateOf(IntSize.Zero) }
                val density = LocalDensity.current
                val imagePaddingPx = with(density) { 12.dp.toPx() }
                val cursorOffset = remember(
                    previewSize,
                    surfaceBounds,
                    displayState.width,
                    displayState.height,
                    displayState.cursorX,
                    displayState.cursorY,
                ) {
                    resolveAgentModeCursorOffset(
                        previewSize = previewSize,
                        imagePaddingPx = imagePaddingPx,
                        displayWidth = displayState.width,
                        displayHeight = displayState.height,
                        cursorX = displayState.cursorX,
                        cursorY = displayState.cursorY,
                        surfaceBounds = surfaceBounds,
                    )
                }
                val animationDurationMillis = displayState.cursorAnimationDurationMillis.coerceIn(80, 1_200)
                val animatedCursorOffset by animateIntOffsetAsState(
                    targetValue = cursorOffset,
                    animationSpec = tween(durationMillis = animationDurationMillis, easing = ChatGptMotionEasing),
                    label = "agent_mode_cursor_offset",
                )
                if (live) {
                    AgentModeLivePreviewSurface(
                        displayState = displayState,
                        onAttachSurface = onAttachSurface,
                        onDetachSurface = onDetachSurface,
                        onBindSurfaceView = onBindSurfaceView,
                        modifier = phoneScreenModifier,
                    )
                } else if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = contentDescription,
                        modifier = phoneScreenModifier,
                        contentScale = ContentScale.FillBounds,
                    )
                } else {
                    Box(
                        modifier = phoneScreenModifier.background(Color(0xFF111111)),
                    )
                }
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .onSizeChanged { previewSize = it },
                ) {
                    AgentModeCursor(
                        modifier = Modifier
                            .offset {
                                val tipInsetPx = with(density) { 5.dp.roundToPx() }
                                IntOffset(animatedCursorOffset.x - tipInsetPx, animatedCursorOffset.y - tipInsetPx)
                            }
                            .size(30.dp),
                    )
                    if (!live && overlayText.isNotBlank()) {
                        val bubbleOffset = remember(
                            cursorOffset,
                            previewSize,
                            density,
                        ) {
                            resolveAgentModeBubbleOffset(
                                cursorOffset = cursorOffset,
                                previewSize = previewSize,
                                density = density,
                            )
                        }
                        val animatedBubbleOffset by animateIntOffsetAsState(
                            targetValue = bubbleOffset,
                            animationSpec = tween(durationMillis = animationDurationMillis, easing = ChatGptMotionEasing),
                            label = "agent_mode_bubble_offset",
                        )
                        AgentModeCursorTextBubble(
                            text = overlayText,
                            workspaceDirectory = workspaceDirectory,
                            allowRootImageRead = allowRootImageRead,
                            onOpenLink = onOpenLink,
                            modifier = Modifier.offset { animatedBubbleOffset },
                        )
                    }
                }
            }
            }
        } else if (showBootPlaceholder) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(agentModePreviewBackdropBrush()),
                contentAlignment = Alignment.Center,
            ) {
                LemniscateBloomLoader(size = 36.dp)
            }
        } else if (pendingText.isNotBlank()) {
            Text(
                text = pendingText,
                style = MaterialTheme.typography.bodySmall,
                color = AetherOnSurfaceVariant,
            )
        }
}

@Composable
private fun AgentModeTeachingBanner(
    hint: String,
    onFinish: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AetherOnSurface.copy(alpha = 0.08f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.agent_mode_teach_banner, hint),
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurface,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onFinish) {
            Text(stringResource(R.string.agent_mode_teach_done))
        }
    }
}

/**
 * SurfaceView window for the GLES compositor. The virtual display keeps
 * rendering into a GL-owned SurfaceTexture; this view only supplies an EGL
 * window surface to blit into.
 */
@Composable
private fun AgentModeLivePreviewSurface(
    displayState: AgentModeDisplayState,
    onAttachSurface: (Surface) -> Unit,
    onDetachSurface: (Surface) -> Unit,
    onBindSurfaceView: (SurfaceView?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val latestOnAttachSurface by rememberUpdatedState(onAttachSurface)
    val latestOnDetachSurface by rememberUpdatedState(onDetachSurface)
    val latestOnBindSurfaceView by rememberUpdatedState(onBindSurfaceView)

    DisposableEffect(Unit) {
        onDispose {
            latestOnBindSurfaceView(null)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            val radiusPx = 22f * context.resources.displayMetrics.density
            SurfaceView(context).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                )
                isClickable = false
                isFocusable = false
                isFocusableInTouchMode = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setZOrderMediaOverlay(true)
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
                    }
                }
                holder.setFormat(PixelFormat.RGBA_8888)
                holder.addCallback(
                    object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            val surface = holder.surface?.takeIf { it.isValid } ?: return
                            latestOnAttachSurface(surface)
                            latestOnBindSurfaceView(this@apply)
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int,
                        ) {
                            val surface = holder.surface?.takeIf { it.isValid } ?: return
                            latestOnAttachSurface(surface)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            val surface = holder.surface ?: return
                            latestOnDetachSurface(surface)
                        }
                    },
                )
            }
        },
        update = {},
    )
}

@Composable
private fun AgentModeCursor(
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(R.drawable.mouse_pointer_2_white_fill),
        contentDescription = null,
        modifier = modifier,
    )
}

@Composable
private fun AgentModeCursorTextBubble(
    text: String,
    workspaceDirectory: String,
    allowRootImageRead: Boolean,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    Box(
        modifier = modifier
            .widthIn(max = 320.dp)
            .shadow(18.dp, RoundedCornerShape(12.dp), ambientColor = AetherScrim, spotColor = AetherScrim)
            .clip(RoundedCornerShape(12.dp))
            .background(AetherSurface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Box(
            modifier = Modifier
                .heightIn(max = 104.dp)
                .verticalScroll(scrollState),
        ) {
            StreamingMarkdownContent(
                markdown = text,
                workspaceDirectory = workspaceDirectory,
                allowRootImageRead = allowRootImageRead,
                onLinkClick = onOpenLink,
            )
        }
    }
}

private fun resolveAgentModeCursorOffset(
    previewSize: IntSize,
    imagePaddingPx: Float,
    displayWidth: Int,
    displayHeight: Int,
    cursorX: Int?,
    cursorY: Int?,
    surfaceBounds: Rect = Rect.Zero,
): IntOffset {
    val sourceWidth = displayWidth.coerceAtLeast(1)
    val sourceHeight = displayHeight.coerceAtLeast(1)
    val cursorFractionX = cursorX?.let { it.toFloat() / sourceWidth } ?: 0.58f
    val cursorFractionY = cursorY?.let { it.toFloat() / sourceHeight } ?: 0.56f
    if (surfaceBounds.width > 1f && surfaceBounds.height > 1f) {
        return IntOffset(
            x = (surfaceBounds.left + surfaceBounds.width * cursorFractionX.coerceIn(0f, 1f)).roundToInt(),
            y = (surfaceBounds.top + surfaceBounds.height * cursorFractionY.coerceIn(0f, 1f)).roundToInt(),
        )
    }
    if (previewSize.width <= 0 || previewSize.height <= 0) return IntOffset.Zero
    val contentWidth = (previewSize.width - imagePaddingPx * 2f).coerceAtLeast(1f)
    val contentHeight = (previewSize.height - imagePaddingPx * 2f).coerceAtLeast(1f)
    val scale = minOf(contentWidth / sourceWidth, contentHeight / sourceHeight)
    val renderedWidth = sourceWidth * scale
    val renderedHeight = sourceHeight * scale
    val renderedLeft = imagePaddingPx + (contentWidth - renderedWidth) / 2f
    val renderedTop = imagePaddingPx + (contentHeight - renderedHeight) / 2f
    return IntOffset(
        x = (renderedLeft + renderedWidth * cursorFractionX.coerceIn(0f, 1f)).roundToInt(),
        y = (renderedTop + renderedHeight * cursorFractionY.coerceIn(0f, 1f)).roundToInt(),
    )
}

private fun mapPreviewOffsetToDisplay(
    offset: Offset,
    previewSize: IntSize,
    imagePaddingPx: Float,
    displayWidth: Int,
    displayHeight: Int,
    surfaceBounds: Rect = Rect.Zero,
): Pair<Int, Int>? {
    val sourceWidth = displayWidth.coerceAtLeast(1)
    val sourceHeight = displayHeight.coerceAtLeast(1)
    if (surfaceBounds.width > 1f && surfaceBounds.height > 1f) {
        val x = ((offset.x - surfaceBounds.left) / surfaceBounds.width * sourceWidth).roundToInt()
        val y = ((offset.y - surfaceBounds.top) / surfaceBounds.height * sourceHeight).roundToInt()
        return x.coerceIn(0, sourceWidth - 1) to y.coerceIn(0, sourceHeight - 1)
    }
    if (previewSize.width <= 0 || previewSize.height <= 0) return null
    val contentWidth = (previewSize.width - imagePaddingPx * 2f).coerceAtLeast(1f)
    val contentHeight = (previewSize.height - imagePaddingPx * 2f).coerceAtLeast(1f)
    val scale = minOf(contentWidth / sourceWidth, contentHeight / sourceHeight)
    val renderedWidth = sourceWidth * scale
    val renderedHeight = sourceHeight * scale
    val renderedLeft = imagePaddingPx + (contentWidth - renderedWidth) / 2f
    val renderedTop = imagePaddingPx + (contentHeight - renderedHeight) / 2f
    val x = ((offset.x - renderedLeft) / scale).roundToInt()
    val y = ((offset.y - renderedTop) / scale).roundToInt()
    return x.coerceIn(0, sourceWidth - 1) to y.coerceIn(0, sourceHeight - 1)
}

@Composable
private fun AgentModePinnedTranscript(
    text: String,
    workspaceDirectory: String,
    allowRootImageRead: Boolean,
    onOpenLink: (String) -> Unit,
) {
    val scrollState = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 96.dp)
            .verticalScroll(scrollState),
    ) {
        StreamingMarkdownContent(
            markdown = text,
            workspaceDirectory = workspaceDirectory,
            allowRootImageRead = allowRootImageRead,
            onLinkClick = onOpenLink,
        )
    }
}

private fun stickySubagentInvocations(
    pendingBlocks: List<AssistantResponseBlock>,
    pendingTools: List<ChatToolInvocation>,
    messages: List<ChatMessage>,
): List<ChatToolInvocation> {
    val pending = (pendingTools + pendingBlocks.flatMap { block ->
        when (block) {
            is AssistantResponseBlock.ToolGroup -> block.toolInvocations
            is AssistantResponseBlock.Reasoning -> block.trace.toolInvocations
            else -> emptyList()
        }
    }).distinctBy(ChatToolInvocation::id).filter { it.isSubagentLaunch() }
    if (pending.isNotEmpty()) return pending
    val lastAgent = messages.lastOrNull { message ->
        message.author == MessageAuthor.Agent &&
            message.displayKind == MessageDisplayKind.Standard
    } ?: return emptyList()
    return (lastAgent.toolInvocations + lastAgent.reasoningTrace?.toolInvocations.orEmpty())
        .distinctBy(ChatToolInvocation::id)
        .filter { it.isSubagentLaunch() }
}

private fun resolveAgentModeBubbleOffset(
    cursorOffset: IntOffset,
    previewSize: IntSize,
    density: androidx.compose.ui.unit.Density,
): IntOffset {
    val bubbleMaxWidthPx = with(density) { 320.dp.roundToPx() }
    val bubbleMaxHeightPx = with(density) { 128.dp.roundToPx() }
    val horizontalGapPx = with(density) { 34.dp.roundToPx() }
    val verticalGapPx = with(density) { 34.dp.roundToPx() }
    val edgePaddingPx = with(density) { 8.dp.roundToPx() }
    val targetY = if (cursorOffset.y < previewSize.height / 2) {
        cursorOffset.y + verticalGapPx
    } else {
        cursorOffset.y - verticalGapPx - bubbleMaxHeightPx
    }
    return IntOffset(
        x = (cursorOffset.x + horizontalGapPx).coerceIn(
            edgePaddingPx,
            (previewSize.width - bubbleMaxWidthPx - edgePaddingPx).coerceAtLeast(edgePaddingPx),
        ),
        y = targetY.coerceIn(
            edgePaddingPx,
            (previewSize.height - edgePaddingPx).coerceAtLeast(edgePaddingPx),
        ),
    )
}

@Composable
private fun AgentModePreviewHeader(
    displayState: AgentModeDisplayState,
    label: String,
    isChrome: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = if (isChrome) Icons.Rounded.Public else LucideIcons.MousePointer2,
            contentDescription = null,
            tint = Color(0xFF6D5CFF),
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
            color = AetherOnSurface,
        )
        if (displayState.isLivePreviewActive) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0xFFE5F8EE))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF20A564)),
                )
                Text(
                    text = stringResource(R.string.chat_live),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                    color = Color(0xFF137A49),
                )
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = displayState.displayId?.let {
                stringResource(R.string.agent_mode_display_id, it)
            } ?: stringResource(R.string.chat_standby),
            style = MaterialTheme.typography.labelSmall,
            color = AetherOnSurfaceVariant,
        )
    }
}

@Composable
private fun AgentModePreviewToolStatus(
    toolInvocation: ChatToolInvocation,
) {
    val label = formatPendingAgentModeToolLabel(toolInvocation)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(AetherSurfaceHigh)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = when (toolInvocation.toolName.lowercase()) {
                "agent_display" -> LucideIcons.MousePointer2
                "chrome", "browser" -> Icons.Rounded.Public
                else -> Icons.Rounded.AutoAwesome
            },
            contentDescription = null,
            tint = Color(0xFF5D7CFF),
            modifier = Modifier.size(16.dp),
        )
        if (toolInvocation.isRunning) {
            ShimmerStatusText(
                text = label,
                modifier = Modifier.weight(1f),
                travelDurationMillis = 2600,
                pauseDurationMillis = 900,
            )
        } else {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = AetherOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun formatPendingAgentModeToolLabel(toolInvocation: ChatToolInvocation): String {
    val arguments = parseJsonObject(toolInvocation.argumentsJson)
    return formatPendingToolTitle(
        toolName = toolInvocation.toolName,
        isRunning = toolInvocation.isRunning,
        arguments = arguments,
    )
}

@Composable
private fun formatPendingToolTitle(
    toolName: String,
    isRunning: Boolean,
    arguments: JSONObject?,
): String {
    val context = LocalContext.current
    if (isAgentModeDisplayToolName(toolName)) {
        return formatAgentDisplayToolTitle(isRunning, arguments)
    }
    if (DeviceCatalogMcp.matchesToolName(toolName)) {
        return formatArgumentDrivenToolTitle(
            isRunning = isRunning,
            runningVerbRes = R.string.tool_title_reading,
            doneVerbRes = R.string.tool_title_read,
            subject = arguments?.optString("query").orEmpty(),
            fallbackRes = R.string.tool_title_installed_apps_fallback,
        )
    }
    if (EverMeTools.matches(toolName)) {
        val res = if (EverMeTools.isWrite(toolName)) {
            if (isRunning) R.string.tool_title_everme_writing_memory else R.string.tool_title_everme_wrote_memory
        } else {
            if (isRunning) R.string.tool_title_everme_reading_memory else R.string.tool_title_everme_read_memory
        }
        return stringResource(res)
    }
    if (SessionNoteTools.matches(toolName)) {
        val res = when (SessionNoteTools.canonical(toolName)) {
            "new_context" -> if (isRunning) R.string.tool_title_session_new_context else R.string.tool_title_session_new_context_done
            "read_original" -> if (isRunning) R.string.tool_title_reading_original else R.string.tool_title_read_original
            else -> if (isRunning) R.string.tool_title_session_note_updating else R.string.tool_title_session_note_updated
        }
        return stringResource(res)
    }
    if (GmailMcp.matchesToolName(toolName)) {
        return formatGmailToolTitle(isRunning, toolName, arguments)
    }
    if (SpotifyMcp.matchesToolName(toolName)) {
        return formatSpotifyToolTitle(isRunning, toolName, arguments)
    }
    if (GithubMcp.matchesToolName(toolName)) {
        return formatGithubToolTitle(isRunning, toolName, arguments)
    }
    if (HuggingFaceMcp.matchesToolName(toolName)) {
        return formatHuggingFaceToolTitle(isRunning, toolName, arguments)
    }
    (context.applicationContext as? kira.ditto.AetherApplication)?.runtime?.modKernel?.toolTitles
        ?.titleFor(toolName, isRunning)?.let { return it }
    return when (toolName.lowercase()) {
    "bash" -> toolStatusLabel(isRunning, R.string.tool_title_bash_running, R.string.tool_title_bash_done)
    "read" -> toolStatusLabel(isRunning, R.string.tool_title_read_running, R.string.tool_title_read_done)
    "edit" -> toolStatusLabel(isRunning, R.string.tool_title_edit_running, R.string.tool_title_edit_done)
    "write" -> toolStatusLabel(isRunning, R.string.tool_title_write_running, R.string.tool_title_write_done)
    "grep" -> toolStatusLabel(isRunning, R.string.tool_title_grep_running, R.string.tool_title_grep_done)
    "find" -> toolStatusLabel(isRunning, R.string.tool_title_find_running, R.string.tool_title_find_done)
    "ls" -> toolStatusLabel(isRunning, R.string.tool_title_ls_running, R.string.tool_title_ls_done)
    "aether_config_get",
    "aether_config_set",
    "aether_skill_manage",
    "aether_termux_manage",
    "aether_agent_mode_manage",
    "aether_scheduled_task_manage",
    "aether_developer_manage" -> formatAetherToolTitle(toolName, isRunning, arguments)
    "agent_display" -> formatAgentDisplayToolTitle(isRunning, arguments)
    "chrome", "browser" -> formatChromeToolTitle(isRunning, arguments)
    else -> if (isRunning) {
        stringResource(R.string.tool_title_using_tool, toolName)
    } else {
        stringResource(R.string.tool_title_used_tool, toolName)
    }
    }
}

@Composable
private fun toolStatusLabel(
    isRunning: Boolean,
    runningRes: Int,
    doneRes: Int,
): String = stringResource(if (isRunning) runningRes else doneRes)

@Composable
private fun formatArgumentDrivenToolTitle(
    isRunning: Boolean,
    runningVerbRes: Int,
    doneVerbRes: Int,
    subject: String,
    fallbackRes: Int,
): String {
    val action = stringResource(if (isRunning) runningVerbRes else doneVerbRes)
    val normalizedSubject = subject.trim()
    if (normalizedSubject.isBlank()) {
        return stringResource(R.string.tool_title_action_subject, action, stringResource(fallbackRes))
    }
    val clipped = normalizedSubject.take(72)
    val displaySubject = if (normalizedSubject.length > 72) "$clipped..." else clipped
    return stringResource(R.string.tool_title_action_subject, action, displaySubject)
}

@Composable
private fun formatGmailToolTitle(
    isRunning: Boolean,
    toolName: String,
    arguments: JSONObject?,
): String {
    val canonical = GmailMcp.canonicalToolName(toolName)
    val subject = when (canonical) {
        "search_threads", "list_drafts" -> arguments?.optString("query").orEmpty()
        "create_draft" -> arguments?.optString("subject").orEmpty()
        "get_thread", "label_thread", "unlabel_thread" ->
            arguments?.optString("threadId").orEmpty()
                .ifBlank { arguments?.optString("thread_id").orEmpty() }
                .ifBlank { arguments?.optString("id").orEmpty() }
        else -> arguments?.optString("id").orEmpty()
    }
    val verbs = when (canonical) {
        "create_draft" -> R.string.tool_title_edit_running to R.string.tool_title_edit_done
        "label_message", "label_thread", "unlabel_message", "unlabel_thread" ->
            R.string.tool_title_edit_running to R.string.tool_title_edit_done
        else -> R.string.tool_title_reading to R.string.tool_title_read
    }
    return formatArgumentDrivenToolTitle(
        isRunning = isRunning,
        runningVerbRes = verbs.first,
        doneVerbRes = verbs.second,
        subject = subject,
        fallbackRes = R.string.tool_title_gmail_fallback,
    )
}

@Composable
private fun formatSpotifyToolTitle(
    isRunning: Boolean,
    toolName: String,
    arguments: JSONObject?,
): String {
    val canonical = SpotifyMcp.canonicalToolName(toolName)
    val subject = when (canonical) {
        "SpotifySearch" -> arguments?.optString("query").orEmpty()
        "SpotifyPlayback", "SpotifyQueue", "SpotifyPlaylist", "SpotifyLibrary" ->
            arguments?.optString("action").orEmpty()
        "SpotifyGetInfo" -> arguments?.optString("item_uri").orEmpty()
            .ifBlank { arguments?.optString("itemUri").orEmpty() }
        else -> ""
    }
    return formatArgumentDrivenToolTitle(
        isRunning = isRunning,
        runningVerbRes = R.string.tool_title_reading,
        doneVerbRes = R.string.tool_title_read,
        subject = subject,
        fallbackRes = R.string.tool_title_spotify_fallback,
    )
}

@Composable
private fun formatGithubToolTitle(
    isRunning: Boolean,
    toolName: String,
    arguments: JSONObject?,
): String {
    val canonical = GithubMcp.canonicalToolName(toolName)
    val subject = when (canonical) {
        GithubMcp.CallTool -> arguments?.optString("method").orEmpty()
        else -> ""
    }
    return formatArgumentDrivenToolTitle(
        isRunning = isRunning,
        runningVerbRes = R.string.tool_title_reading,
        doneVerbRes = R.string.tool_title_read,
        subject = subject,
        fallbackRes = R.string.tool_title_github_fallback,
    )
}

@Composable
private fun formatHuggingFaceToolTitle(
    isRunning: Boolean,
    toolName: String,
    arguments: JSONObject?,
): String {
    val canonical = HuggingFaceMcp.canonicalToolName(toolName)
    val subject = when (canonical) {
        HuggingFaceMcp.CallTool -> arguments?.optString("method").orEmpty()
        else -> ""
    }
    return formatArgumentDrivenToolTitle(
        isRunning = isRunning,
        runningVerbRes = R.string.tool_title_reading,
        doneVerbRes = R.string.tool_title_read,
        subject = subject,
        fallbackRes = R.string.tool_title_huggingface_fallback,
    )
}

@Composable
private fun formatAetherToolTitle(
    toolName: String,
    isRunning: Boolean,
    arguments: JSONObject?,
): String {
    val action = arguments?.optString("action").orEmpty().trim()
    return when (toolName.lowercase()) {
        "aether_config_get" -> formatArgumentDrivenToolTitle(isRunning, R.string.tool_title_reading, R.string.tool_title_read, formatAetherCategories(arguments), R.string.tool_title_aether_settings_fallback)
        "aether_config_set" -> formatArgumentDrivenToolTitle(isRunning, R.string.tool_title_updating, R.string.tool_title_updated, arguments?.optString("category").orEmpty(), R.string.tool_title_aether_settings_fallback)
        "aether_skill_manage" -> when (action.lowercase()) {
            "install_remote" -> formatArgumentDrivenToolTitle(isRunning, R.string.tool_title_installing, R.string.tool_title_installed, arguments?.optString("url").orEmpty(), R.string.tool_title_agent_skill_fallback)
            "remove" -> formatArgumentDrivenToolTitle(isRunning, R.string.tool_title_removing, R.string.tool_title_removed, optAetherString(arguments, "skill_id", "skillId"), R.string.tool_title_agent_skill_fallback)
            "set_enabled" -> formatArgumentDrivenToolTitle(isRunning, R.string.tool_title_updating, R.string.tool_title_updated, optAetherString(arguments, "skill_id", "skillId"), R.string.tool_title_agent_skill_fallback)
            else -> toolStatusLabel(isRunning, R.string.tool_title_reading_agent_skills, R.string.tool_title_read_agent_skills)
        }
        "aether_mcp_manage" -> when (action.lowercase()) {
            "upsert_streamable_http", "upsert_stdio" -> formatArgumentDrivenToolTitle(isRunning, R.string.tool_title_saving, R.string.tool_title_saved, optAetherString(arguments, "display_name", "displayName"), R.string.tool_title_mcp_server_fallback)
            "remove" -> formatArgumentDrivenToolTitle(isRunning, R.string.tool_title_removing, R.string.tool_title_removed, optAetherString(arguments, "server_id", "serverId"), R.string.tool_title_mcp_server_fallback)
            "set_enabled" -> formatArgumentDrivenToolTitle(isRunning, R.string.tool_title_updating, R.string.tool_title_updated, optAetherString(arguments, "server_id", "serverId"), R.string.tool_title_mcp_server_fallback)
            else -> toolStatusLabel(isRunning, R.string.tool_title_reading_mcp_servers, R.string.tool_title_read_mcp_servers)
        }
        "aether_termux_manage" -> when (action.lowercase()) {
            "configure_root_access" -> toolStatusLabel(isRunning, R.string.tool_title_configuring_termux_root, R.string.tool_title_configured_termux_root)
            "inspect_root_setup" -> toolStatusLabel(isRunning, R.string.tool_title_checking_root_setup, R.string.tool_title_checked_root_setup)
            else -> toolStatusLabel(isRunning, R.string.tool_title_checking_termux_setup, R.string.tool_title_checked_termux_setup)
        }
        "aether_agent_mode_manage" -> when (action.lowercase()) {
            "set_authorization" -> toolStatusLabel(isRunning, R.string.tool_title_updating_agent_mode_authorization, R.string.tool_title_updated_agent_mode_authorization)
            "request_shizuku_permission" -> toolStatusLabel(isRunning, R.string.tool_title_requesting_shizuku_permission, R.string.tool_title_requested_shizuku_permission)
            "stop_display" -> toolStatusLabel(isRunning, R.string.tool_title_stopping_agent_mode_display, R.string.tool_title_stopped_agent_mode_display)
            "refresh_displays" -> toolStatusLabel(isRunning, R.string.tool_title_refreshing_agent_mode_displays, R.string.tool_title_refreshed_agent_mode_displays)
            else -> toolStatusLabel(isRunning, R.string.tool_title_checking_agent_mode_authorization, R.string.tool_title_checked_agent_mode_authorization)
        }
        "aether_scheduled_task_manage" -> when (action.lowercase()) {
            "create" -> formatArgumentDrivenToolTitle(isRunning, R.string.tool_title_creating, R.string.tool_title_created, arguments?.optString("name").orEmpty(), R.string.tool_title_scheduled_task_fallback)
            "update" -> formatArgumentDrivenToolTitle(isRunning, R.string.tool_title_updating, R.string.tool_title_updated, optAetherString(arguments, "task_id", "taskId"), R.string.tool_title_scheduled_task_fallback)
            "remove" -> formatArgumentDrivenToolTitle(isRunning, R.string.tool_title_removing, R.string.tool_title_removed, optAetherString(arguments, "task_id", "taskId"), R.string.tool_title_scheduled_task_fallback)
            "set_enabled" -> formatArgumentDrivenToolTitle(isRunning, R.string.tool_title_updating, R.string.tool_title_updated, optAetherString(arguments, "task_id", "taskId"), R.string.tool_title_scheduled_task_fallback)
            else -> toolStatusLabel(isRunning, R.string.tool_title_reading_scheduled_tasks, R.string.tool_title_read_scheduled_tasks)
        }
        "aether_developer_manage" -> toolStatusLabel(isRunning, R.string.tool_title_reading_aether_diagnostics, R.string.tool_title_read_aether_diagnostics)
        else -> toolStatusLabel(isRunning, R.string.tool_title_managing_aether, R.string.tool_title_managed_aether)
    }
}

@Composable
private fun formatAgentDisplayToolTitle(
    isRunning: Boolean,
    arguments: JSONObject?,
): String = when (arguments?.optString("action").orEmpty().lowercase()) {
    "list_apps", "apps", "installed_apps" -> formatArgumentDrivenToolTitle(
        isRunning = isRunning,
        runningVerbRes = R.string.tool_title_reading,
        doneVerbRes = R.string.tool_title_read,
        subject = arguments?.optString("query").orEmpty(),
        fallbackRes = R.string.tool_title_installed_apps_fallback,
    )
    "start" -> toolStatusLabel(isRunning, R.string.tool_title_starting_agent_mode_display, R.string.tool_title_started_agent_mode_display)
    "status" -> toolStatusLabel(isRunning, R.string.tool_title_checking_agent_mode_display, R.string.tool_title_checked_agent_mode_display)
    "launch" -> formatArgumentDrivenToolTitle(
        isRunning = isRunning,
        runningVerbRes = R.string.tool_title_launching,
        doneVerbRes = R.string.tool_title_launched,
        subject = arguments?.optString("target").orEmpty(),
        fallbackRes = R.string.tool_title_agent_mode_app_fallback,
    )
    "tap" -> toolStatusLabel(isRunning, R.string.tool_title_tapping_agent_mode_display, R.string.tool_title_tapped_agent_mode_display)
    "swipe", "swipe_left", "swipe_right", "swipe_up", "swipe_down", "fling" ->
        toolStatusLabel(isRunning, R.string.tool_title_swiping_agent_mode_display, R.string.tool_title_swiped_agent_mode_display)
    "search" -> toolStatusLabel(isRunning, R.string.tool_title_searching, R.string.tool_title_searched)
    "key" -> formatArgumentDrivenToolTitle(
        isRunning = isRunning,
        runningVerbRes = R.string.tool_title_pressing,
        doneVerbRes = R.string.tool_title_pressed,
        subject = arguments?.optString("key").orEmpty(),
        fallbackRes = R.string.tool_title_agent_mode_key_fallback,
    )
    "text" -> toolStatusLabel(isRunning, R.string.tool_title_typing_agent_mode, R.string.tool_title_typed_agent_mode)
    "screenshot" -> toolStatusLabel(isRunning, R.string.tool_title_capturing_agent_mode_display, R.string.tool_title_captured_agent_mode_display)
    "stop" -> toolStatusLabel(isRunning, R.string.tool_title_stopping_agent_mode_display, R.string.tool_title_stopped_agent_mode_display)
    else -> toolStatusLabel(isRunning, R.string.tool_title_using_agent_mode_display, R.string.tool_title_used_agent_mode_display)
}

@Composable
private fun formatChromeToolTitle(
    isRunning: Boolean,
    arguments: JSONObject?,
): String = when (arguments?.optString("action")?.trim()?.lowercase()) {
    "start" -> toolStatusLabel(
        isRunning,
        R.string.tool_title_starting_chrome,
        R.string.tool_title_started_chrome,
    )
    "status" -> toolStatusLabel(
        isRunning,
        R.string.tool_title_checking_chrome,
        R.string.tool_title_checked_chrome,
    )
    "navigate", "open" -> {
        val url = arguments?.optString("url").orEmpty().trim()
        if (url.isBlank()) {
            toolStatusLabel(
                isRunning,
                R.string.tool_title_navigating_chrome,
                R.string.tool_title_navigated_chrome,
            )
        } else {
            stringResource(
                if (isRunning) {
                    R.string.tool_title_navigating_chrome_url
                } else {
                    R.string.tool_title_navigated_chrome_url
                },
                url.take(72) + if (url.length > 72) "..." else "",
            )
        }
    }
    "tap", "click" -> toolStatusLabel(
        isRunning,
        R.string.tool_title_tapping_chrome,
        R.string.tool_title_tapped_chrome,
    )
    "swipe", "scroll" -> toolStatusLabel(
        isRunning,
        R.string.tool_title_scrolling_chrome,
        R.string.tool_title_scrolled_chrome,
    )
    "text", "type" -> toolStatusLabel(
        isRunning,
        R.string.tool_title_typing_chrome,
        R.string.tool_title_typed_chrome,
    )
    "key" -> {
        val key = arguments?.optString("key").orEmpty().trim()
        if (key.isBlank()) {
            toolStatusLabel(
                isRunning,
                R.string.tool_title_pressing_chrome,
                R.string.tool_title_pressed_chrome,
            )
        } else {
            stringResource(
                if (isRunning) {
                    R.string.tool_title_pressing_chrome_key
                } else {
                    R.string.tool_title_pressed_chrome_key
                },
                key.take(32),
            )
        }
    }
    "back" -> toolStatusLabel(
        isRunning,
        R.string.tool_title_going_back_chrome,
        R.string.tool_title_went_back_chrome,
    )
    "forward" -> toolStatusLabel(
        isRunning,
        R.string.tool_title_going_forward_chrome,
        R.string.tool_title_went_forward_chrome,
    )
    "reload" -> toolStatusLabel(
        isRunning,
        R.string.tool_title_reloading_chrome,
        R.string.tool_title_reloaded_chrome,
    )
    "evaluate", "execute_js", "get_text", "get_page_info", "find_elements", "get_readable", "get_backbone", "wait_for_dom_stable" -> toolStatusLabel(
        isRunning,
        R.string.tool_title_evaluating_chrome,
        R.string.tool_title_evaluated_chrome,
    )
    "screenshot" -> toolStatusLabel(
        isRunning,
        R.string.tool_title_capturing_chrome,
        R.string.tool_title_captured_chrome,
    )
    "stop" -> toolStatusLabel(
        isRunning,
        R.string.tool_title_stopping_chrome,
        R.string.tool_title_stopped_chrome,
    )
    else -> toolStatusLabel(
        isRunning,
        R.string.tool_title_using_chrome,
        R.string.tool_title_used_chrome,
    )
}

private fun formatAetherCategories(arguments: JSONObject?): String {
    val categories = arguments?.optJSONArray("categories") ?: return ""
    return buildList {
        for (index in 0 until categories.length()) {
            val value = categories.optString(index).trim()
            if (value.isNotBlank()) add(value)
        }
    }.joinToString(",")
}

private fun optAetherString(
    arguments: JSONObject?,
    primary: String,
    secondary: String,
): String = arguments?.optString(primary).orEmpty().ifBlank {
    arguments?.optString(secondary).orEmpty()
}

private fun parseJsonObject(rawValue: String): JSONObject? =
    if (rawValue.isBlank()) null else runCatching { JSONObject(rawValue) }.getOrNull()

private fun agentModePreviewBackdropBrush(): Brush = Brush.linearGradient(
    colorStops = arrayOf(
        0.00f to Color(0xFFBEEBFF),
        0.22f to Color(0xFF75C7FF),
        0.44f to Color(0xFFD5E9FF),
        0.68f to Color(0xFF83B5FF),
        1.00f to Color(0xFF4E86F7),
    ),
    start = Offset.Zero,
    end = Offset(900f, 620f),
)

@Composable
private fun ComposerAgentModeTeachingChip(
    teachingActive: Boolean,
    previewHasContent: Boolean,
    onToggleTeaching: () -> Unit,
    onRemove: () -> Unit,
) {
    val state = composerAgentModeChipState(
        teachingActive = teachingActive,
        previewHasContent = previewHasContent,
    )
    val label = stringResource(
        when (state) {
            ComposerAgentModeChipState.EndTeaching -> R.string.agent_mode_end_teaching
            ComposerAgentModeChipState.Takeover -> R.string.agent_mode_takeover_teaching
            ComposerAgentModeChipState.AgentMode -> R.string.agent_mode_label
        },
    )
    val appeared = remember { MutableTransitionState(false).apply { targetState = true } }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val press by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = tween(durationMillis = 90, easing = ChatGptMotionEasing),
        label = "agent_mode_chip_press",
    )
    AnimatedVisibility(
        visibleState = appeared,
        enter = fadeIn(tween(180, easing = ChatGptMotionEasing)) +
            scaleIn(initialScale = 0.92f, animationSpec = tween(220, easing = ChatGptMotionEasing)),
        exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.94f, animationSpec = tween(140)),
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = 240.dp)
                .graphicsLayer {
                    scaleX = press
                    scaleY = press
                }
                .clip(RoundedCornerShape(18.dp))
                .background(AetherComposerChipBackground)
                .animateContentSize(animationSpec = tween(durationMillis = 220, easing = ChatGptMotionEasing))
                .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .clickable(
                        enabled = state != ComposerAgentModeChipState.AgentMode,
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onToggleTeaching,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = LucideIcons.MousePointer2,
                    contentDescription = null,
                    tint = AetherComposerChipForeground,
                    modifier = Modifier.size(16.dp),
                )
                AnimatedContent(
                    targetState = label,
                    transitionSpec = {
                        (slideInHorizontally(tween(220)) { width -> width / 3 } + fadeIn(tween(220)))
                            .togetherWith(
                                slideOutHorizontally(tween(160)) { width -> -width / 4 } + fadeOut(tween(140)),
                            )
                            .using(SizeTransform(clip = false))
                    },
                    label = "agent_mode_chip_label",
                ) { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                        color = AetherComposerChipForeground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.common_remove),
                    tint = AetherComposerChipForeground,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun ComposerActionChip(
    label: String,
    icon: ImageVector,
    onRemove: () -> Unit,
    iconTint: Color = AetherComposerChipForeground,
) {
    Row(
        modifier = Modifier
            .widthIn(max = 220.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(AetherComposerChipBackground)
            .clickable(onClick = onRemove)
            .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            modifier = Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
            color = AetherComposerChipForeground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            modifier = Modifier.size(22.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.common_remove),
                tint = AetherComposerChipForeground,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun ComposerPlusMenuRow(
    title: String,
    icon: ImageVector,
    iconTint: Color,
    iconContainerColor: Color,
    onClick: () -> Unit,
    subtitle: String? = null,
    selected: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(iconContainerColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(17.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = AetherOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = AetherPrimary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun UpaPluginMentionRow(
    server: McpServerConfig,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(AetherSurfaceHigh.copy(alpha = 0.92f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Extension,
            contentDescription = null,
            tint = AetherOnSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = server.quickActionLabel(),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = AetherOnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = AetherPrimary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
