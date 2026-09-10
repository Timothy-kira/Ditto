package kira.ditto.ui

import android.content.Context
import kira.ditto.AetherApplication
import kira.ditto.data.visibleUserMessageText
import kira.ditto.data.isAgentModeDisplayToolName
import kira.ditto.data.DeviceCatalogMcp
import kira.ditto.data.GmailMcp
import kira.ditto.data.EverMeTools
import kira.ditto.data.SessionNoteTools
import kira.ditto.data.SpotifyMcp
import kira.ditto.data.GithubMcp
import kira.ditto.data.HuggingFaceMcp
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kira.ditto.R
import kira.ditto.data.KnowledgeCitation
import kira.ditto.ui.theme.AetherMessageBubble
import kira.ditto.ui.theme.AetherError
import kira.ditto.ui.theme.AetherOnPrimaryContainer
import kira.ditto.ui.theme.AetherOnSurface
import kira.ditto.ui.theme.AetherOnSurfaceVariant
import kira.ditto.ui.theme.AetherOutlineSoft
import kira.ditto.ui.theme.AetherPrimary
import kira.ditto.ui.theme.AetherScrim
import kira.ditto.ui.theme.AetherSecondary
import kira.ditto.ui.theme.AetherSurface
import kira.ditto.platform.LocalReduceMotion
import kira.ditto.ui.theme.AetherSurfaceHigh
import kira.ditto.ui.theme.AetherTertiary

import kira.ditto.termux.TermuxContract
import kira.ditto.termux.TermuxSetupIssue
import kira.ditto.termux.TermuxSetupState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.IDN
import java.net.URI
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.max

private const val ToolInvocationCollapseThreshold = 3
private const val ToolTransitionDurationMillis = 360
private const val ToolInvocationAutoExpandDelayMillis = 1_000L
private const val ToolGroupCollapseStageDelayMillis = 180L
private const val FaviconFetchTimeoutMillis = 3_000
private const val MinimumEpochMillis = 946_684_800_000L
private const val TavilyFallbackDomain = "tavily.com"
private const val DefaultFaviconUserAgent =
    "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"
private val ToolTransitionEasing = CubicBezierEasing(0.22f, 0.84f, 0.18f, 1f)

/** TTS playback state passed down to assistant message action bars. */
data class TtsPlaybackState(
    val showButton: Boolean = false,
    val playingMessageId: String? = null,
    val onPlay: (String) -> Unit = {},
)

internal val LocalTtsPlayback = compositionLocalOf { TtsPlaybackState() }

private val ToolGroupIndent = 14.dp
private val TimelineGlyphWidth = 22.dp
private val TimelineIconSize = 18.dp
private val TimelineLineWidth = 2.dp
private val TimelineLineTopGap = 9.dp
private val TimelineLineBottomGap = 0.dp
private val MessageTimestampFormatter = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US)

@Composable
fun ConversationMessageBubble(
    message: ChatMessage,
    actionsEnabled: Boolean,
    workspaceDirectory: String?,
    allowRootImageRead: Boolean = false,
    onOpenAttachment: (ChatAttachment) -> Unit,
    onOpenLink: (String) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onRedo: () -> Unit,
    onRetry: () -> Unit,
    onSwitchBranch: (Int) -> Unit,
    sessionTotalTokens: Long? = null,
    knowledgeCitations: List<KnowledgeCitation> = emptyList(),
) {
    if (message.author == MessageAuthor.User) {
        UserMessageBlock(
            message = message,
            onOpenAttachment = onOpenAttachment,
            onEdit = onEdit,
            onCopy = onCopy,
            onRetry = onRetry,
            onSwitchBranch = onSwitchBranch,
        )
    } else {
        AssistantMessageBlock(
            message = message,
            actionsEnabled = actionsEnabled,
            showActions = !message.assistantActionsHidden,
            workspaceDirectory = workspaceDirectory,
            allowRootImageRead = allowRootImageRead,
            onOpenAttachment = onOpenAttachment,
            onOpenLink = onOpenLink,
            onCopy = onCopy,
            onRedo = onRedo,
            onDelete = onDelete,
            sessionTotalTokens = sessionTotalTokens,
            knowledgeCitations = knowledgeCitations,
        )
    }
}

@Composable
fun SurfaceNotice(
    title: String,
    subtitle: String,
    actionLabel: String,
    onAction: () -> Unit,
    actionEnabled: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(12.dp, RoundedCornerShape(24.dp), ambientColor = AetherScrim, spotColor = AetherScrim)
            .clip(RoundedCornerShape(24.dp))
            .background(AetherSurface.copy(alpha = 0.96f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = AetherOnSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AetherOnSurfaceVariant)
        }
        ActionIconLabel(
            icon = Icons.Rounded.Close,
            label = actionLabel,
            enabled = actionEnabled,
            onClick = onAction,
        )
    }
}

@Composable
fun TermuxSetupNotice(
    setupState: TermuxSetupState,
    onRequestPermission: () -> Unit,
    onOpenAppPermissions: () -> Unit,
    onOpenTermuxSettings: () -> Unit,
    onOpenTermux: () -> Unit,
    onInstallTermux: () -> Unit,
    onRefresh: () -> Unit,
    showRefreshAction: Boolean = true,
    onDismiss: (() -> Unit)? = null,
) {
    if (setupState.isReady) return
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val setupCommandCopiedLabel = stringResource(R.string.termux_setup_command_copied)

    fun copyTermuxSetupCommand() {
        clipboardManager.setText(AnnotatedString(TermuxContract.ExternalAppsSetupCommand))
        Toast.makeText(
            context,
            setupCommandCopiedLabel,
            Toast.LENGTH_SHORT,
        ).show()
    }
    fun copyTermuxSetupCommandAndOpenTermux() {
        copyTermuxSetupCommand()
        onOpenTermux()
    }
    val showInactiveTermuxPrompt = setupState.previouslyConfigured &&
        (setupState.issue == TermuxSetupIssue.DispatchFailed ||
            setupState.issue == TermuxSetupIssue.ExternalAppsDisabled)

    val title: String
    val subtitle: String

    when (setupState.issue) {
        TermuxSetupIssue.NotInstalled -> {
            title = stringResource(R.string.termux_install_to_enable_bash)
            subtitle = stringResource(R.string.termux_runs_shell_commands_on_device)
        }

        TermuxSetupIssue.PermissionMissing -> {
            title = stringResource(R.string.termux_grant_command_access)
            subtitle = stringResource(R.string.termux_allow_run_commands_permission)
        }

        TermuxSetupIssue.ExternalAppsDisabled -> {
            if (showInactiveTermuxPrompt) {
                title = stringResource(R.string.termux_not_running_background)
                subtitle = stringResource(R.string.termux_keep_running_and_refresh)
            } else {
                title = stringResource(R.string.termux_enable_external_apps)
                subtitle = stringResource(R.string.termux_paste_setup_command_and_refresh)
            }
        }

        TermuxSetupIssue.DispatchFailed -> {
            if (showInactiveTermuxPrompt) {
                title = stringResource(R.string.termux_not_running_background)
                subtitle = stringResource(R.string.termux_keep_running_and_refresh)
            } else {
                title = stringResource(R.string.termux_finish_setup)
                val fallbackSubtitle = stringResource(R.string.termux_open_once_verify_settings)
                subtitle = setupState.detail.ifBlank { fallbackSubtitle }
            }
        }

        TermuxSetupIssue.Ready -> return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(12.dp, RoundedCornerShape(24.dp), ambientColor = AetherScrim, spotColor = AetherScrim)
            .clip(RoundedCornerShape(24.dp))
            .background(AetherSurface.copy(alpha = 0.96f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = AetherOnSurface)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AetherOnSurfaceVariant)
                if (setupState.detail.isNotBlank() && setupState.detail != subtitle) {
                    Text(setupState.detail, style = MaterialTheme.typography.bodySmall, color = AetherOnSurfaceVariant)
                }
            }
            if (onDismiss != null) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.common_close),
                        tint = AetherOnSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (setupState.issue) {
                TermuxSetupIssue.NotInstalled -> {
                    ActionIconLabel(
                        icon = Icons.AutoMirrored.Rounded.OpenInNew,
                        label = stringResource(R.string.termux_install_termux),
                        enabled = true,
                        onClick = onInstallTermux,
                    )
                    if (showRefreshAction) {
                        ActionIconLabel(
                            icon = Icons.Rounded.Refresh,
                            label = stringResource(R.string.common_refresh),
                            enabled = true,
                            onClick = onRefresh,
                        )
                    }
                }

                TermuxSetupIssue.PermissionMissing -> {
                    ActionIconLabel(
                        icon = Icons.Rounded.Settings,
                        label = stringResource(R.string.termux_grant_access),
                        enabled = true,
                        onClick = onRequestPermission,
                    )
                    ActionIconLabel(
                        icon = Icons.AutoMirrored.Rounded.OpenInNew,
                        label = stringResource(R.string.termux_app_settings),
                        enabled = true,
                        onClick = onOpenAppPermissions,
                    )
                }

                TermuxSetupIssue.ExternalAppsDisabled -> {
                    if (showInactiveTermuxPrompt) {
                        ActionIconLabel(
                            icon = Icons.AutoMirrored.Rounded.OpenInNew,
                            label = stringResource(R.string.common_open),
                            enabled = true,
                            onClick = onOpenTermux,
                        )
                    } else {
                        ActionIconLabel(
                            icon = Icons.AutoMirrored.Rounded.OpenInNew,
                            label = stringResource(R.string.termux_copy_and_open_termux),
                            enabled = true,
                            onClick = ::copyTermuxSetupCommandAndOpenTermux,
                        )
                    }
                    if (showRefreshAction) {
                        ActionIconLabel(
                            icon = Icons.Rounded.Refresh,
                            label = stringResource(R.string.common_refresh),
                            enabled = true,
                            onClick = onRefresh,
                        )
                    }
                }

                TermuxSetupIssue.DispatchFailed -> {
                    if (showInactiveTermuxPrompt) {
                        ActionIconLabel(
                            icon = Icons.AutoMirrored.Rounded.OpenInNew,
                            label = stringResource(R.string.common_open),
                            enabled = true,
                            onClick = onOpenTermux,
                        )
                    } else {
                        ActionIconLabel(
                            icon = Icons.Rounded.ContentCopy,
                            label = stringResource(R.string.termux_copy_setup_command),
                            enabled = true,
                            onClick = ::copyTermuxSetupCommand,
                        )
                        ActionIconLabel(
                            icon = Icons.AutoMirrored.Rounded.OpenInNew,
                            label = stringResource(R.string.termux_open_termux),
                            enabled = true,
                            onClick = onOpenTermux,
                        )
                        ActionIconLabel(
                            icon = Icons.Rounded.Settings,
                            label = stringResource(R.string.termux_settings),
                            enabled = true,
                            onClick = onOpenTermuxSettings,
                        )
                    }
                    if (showRefreshAction) {
                        ActionIconLabel(
                            icon = Icons.Rounded.Refresh,
                            label = stringResource(R.string.common_refresh),
                            enabled = true,
                            onClick = onRefresh,
                        )
                    }
                }

                TermuxSetupIssue.Ready -> Unit
            }
        }
    }
}

@Composable
fun ComposerAttachmentTray(
    attachments: List<ChatAttachment>,
    onRemoveAttachment: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(10.dp, RoundedCornerShape(24.dp), ambientColor = AetherScrim, spotColor = AetherScrim)
            .clip(RoundedCornerShape(24.dp))
            .background(AetherSurface.copy(alpha = 0.96f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        attachments.forEach { attachment ->
            if (attachment.kind == AttachmentKind.Image) {
                ComposerImageAttachmentCard(attachment = attachment, onRemove = { onRemoveAttachment(attachment.id) })
            } else {
                ComposerFileAttachmentCard(attachment = attachment, onRemove = { onRemoveAttachment(attachment.id) })
            }
        }
    }
}

@Composable
private fun UserMessageBlock(
    message: ChatMessage,
    onOpenAttachment: (ChatAttachment) -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onRetry: () -> Unit,
    onSwitchBranch: (Int) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var selectTextExpanded by remember { mutableStateOf(false) }
    val branchNavigation = message.branchNavigation()

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BoxWithConstraints {
            val userBubbleMaxWidth = (maxWidth * 0.72f).coerceIn(300.dp, 520.dp)
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                UserAttachments(message.attachments, onOpenAttachment)
                UserPromptDirectiveTag(message.promptDirective)
                if (message.agentModeEnabled) {
                    UserComposerModeTag(
                        icon = LucideIcons.MousePointer2,
                        label = stringResource(R.string.agent_mode_label),
                    )
                }
                val visibleText = message.text.visibleUserMessageText()
                if (visibleText.isNotBlank()) {
                    UserTextBubble(
                        text = visibleText,
                        maxWidth = userBubbleMaxWidth,
                        onLongPress = { menuExpanded = true },
                    )
                }
                if (branchNavigation != null) {
                    UserMessageBranchSwitcher(
                        navigation = branchNavigation,
                        onPrevious = { onSwitchBranch(-1) },
                        onNext = { onSwitchBranch(1) },
                    )
                }
            }

            UserMessageActionDialog(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                timestamp = formatMessageTimestamp(message.createdAtMillis),
                onCopy = {
                    menuExpanded = false
                    onCopy()
                },
                onSelectText = {
                    menuExpanded = false
                    selectTextExpanded = true
                },
                onEdit = {
                    menuExpanded = false
                    onEdit()
                },
                onRetry = {
                    menuExpanded = false
                    onRetry()
                },
            )

            SelectUserMessageTextDialog(
                expanded = selectTextExpanded,
                text = message.text.visibleUserMessageText(),
                onDismissRequest = { selectTextExpanded = false },
            )
        }
    }
}

@Composable
private fun UserPromptDirectiveTag(promptDirective: String) {
    val directive = ComposerPromptDirective.fromStorageValue(promptDirective) ?: return
    val (icon, labelRes) = when (directive) {
        ComposerPromptDirective.Goal -> Icons.Rounded.GpsFixed to R.string.chat_plus_goal
        ComposerPromptDirective.Swarm -> Icons.AutoMirrored.Rounded.CallSplit to R.string.chat_plus_swarm
        ComposerPromptDirective.Tower -> Icons.Rounded.AccountTree to R.string.chat_plus_tower
    }
    UserComposerModeTag(
        icon = icon,
        label = stringResource(labelRes),
    )
}

@Composable
private fun UserComposerModeTag(
    icon: ImageVector,
    label: String,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(AetherSurfaceHigh)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AetherOnSurfaceVariant,
            modifier = Modifier.size(13.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = AetherOnSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun UserMessageActionDialog(
    expanded: Boolean,
    timestamp: String,
    onDismissRequest: () -> Unit,
    onCopy: () -> Unit,
    onSelectText: () -> Unit,
    onEdit: () -> Unit,
    onRetry: () -> Unit,
) {
    val menuVisibility = remember { MutableTransitionState(false) }
    menuVisibility.targetState = expanded
    if (!menuVisibility.currentState && !menuVisibility.targetState) return

    val density = LocalDensity.current
    val popupOffset = with(density) {
        IntOffset(x = 0, y = 30.dp.roundToPx())
    }

    Popup(
        alignment = Alignment.TopEnd,
        offset = popupOffset,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        AnimatedVisibility(
            visibleState = menuVisibility,
            enter = fadeIn() +
                scaleIn(
                    initialScale = 0.92f,
                    transformOrigin = TransformOrigin(1f, 0f),
                ) +
                slideInVertically(initialOffsetY = { -it / 10 }),
            exit = fadeOut() +
                scaleOut(
                    targetScale = 0.96f,
                    transformOrigin = TransformOrigin(1f, 0f),
                ) +
                slideOutVertically(targetOffsetY = { -it / 12 }),
        ) {
            AetherCapsuleSurface(
                modifier = Modifier.width(228.dp),
                shape = AetherCapsulePanelShape,
            ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (timestamp.isNotBlank()) {
                    Text(
                        text = timestamp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = AetherOnSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                UserMessageActionRow(
                    icon = Icons.Rounded.ContentCopy,
                    label = stringResource(R.string.common_copy),
                    onClick = onCopy,
                )
                UserMessageActionRow(
                    icon = Icons.Rounded.Description,
                    label = stringResource(R.string.common_select_text),
                    onClick = onSelectText,
                )
                UserMessageActionRow(
                    icon = Icons.Rounded.Edit,
                    label = stringResource(R.string.common_edit_message),
                    onClick = onEdit,
                )
                UserMessageActionRow(
                    icon = Icons.Rounded.Refresh,
                    label = stringResource(R.string.common_retry),
                    onClick = onRetry,
                )
            }
            }
        }
    }
}

@Composable
private fun UserMessageBranchSwitcher(
    navigation: ChatBranchNavigation,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier.padding(end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BranchStepButton(
            enabled = navigation.canGoPrevious,
            icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
            onClick = onPrevious,
        )
        Text(
            text = "${navigation.selectedIndex + 1}/${navigation.branchCount}",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = AetherOnSurfaceVariant,
        )
        BranchStepButton(
            enabled = navigation.canGoNext,
            icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            onClick = onNext,
        )
    }
}

@Composable
private fun BranchStepButton(
    enabled: Boolean,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) AetherOnSurfaceVariant else AetherOnSurfaceVariant.copy(alpha = 0.32f),
            modifier = Modifier.size(30.dp),
        )
    }
}

@Composable
private fun UserMessageActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AetherOnSurface,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = AetherOnSurface,
        )
    }
}

@Composable
private fun SelectUserMessageTextDialog(
    expanded: Boolean,
    text: String,
    onDismissRequest: () -> Unit,
) {
    if (!expanded) return

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .widthIn(max = 520.dp)
                .shadow(22.dp, RoundedCornerShape(28.dp), ambientColor = AetherScrim, spotColor = AetherScrim)
                .clip(RoundedCornerShape(28.dp))
                .background(AetherSurface)
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.common_select_text),
                style = MaterialTheme.typography.titleMedium,
                color = AetherOnSurface,
            )
            SelectionContainer {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = AetherOnSurface,
                )
            }
        }
    }
}

@Composable
private fun UserAttachments(
    attachments: List<ChatAttachment>,
    onOpenAttachment: (ChatAttachment) -> Unit,
) {
    attachments.forEach { attachment ->
        when (attachment.kind) {
            AttachmentKind.Image -> UserImageAttachmentCard(attachment = attachment, onClick = { onOpenAttachment(attachment) })
            AttachmentKind.File -> UserFileAttachmentCard(attachment = attachment, onClick = { onOpenAttachment(attachment) })
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun UserTextBubble(
    text: String,
    maxWidth: Dp,
    onLongPress: () -> Unit,
) {
    Box(
        modifier = Modifier
            .widthIn(max = maxWidth)
            .shadow(10.dp, RoundedCornerShape(24.dp), ambientColor = AetherScrim, spotColor = AetherScrim)
            .clip(RoundedCornerShape(24.dp))
            .background(AetherMessageBubble)
            .combinedClickable(
                onClick = {},
                onLongClick = onLongPress,
            )
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = AetherOnPrimaryContainer,
        )
    }
}

@Composable
private fun AssistantMessageBlock(
    message: ChatMessage,
    actionsEnabled: Boolean,
    showActions: Boolean,
    workspaceDirectory: String?,
    allowRootImageRead: Boolean,
    onOpenAttachment: (ChatAttachment) -> Unit,
    onOpenLink: (String) -> Unit,
    onCopy: () -> Unit,
    onRedo: () -> Unit,
    onDelete: () -> Unit,
    sessionTotalTokens: Long?,
    knowledgeCitations: List<KnowledgeCitation> = emptyList(),
) {
    val chrome = LocalBrowserConversationChrome.current
    val shouldFoldWorkBeforeFinalText = message.text.isNotBlank() &&
        (message.reasoningTrace != null ||
            message.thoughtDurationMillis != null ||
            message.toolInvocations.isNotEmpty() ||
            message.attachments.isNotEmpty())
    val amapPlaces = remember(message.toolInvocations, message.reasoningTrace) {
        amapPlacesFromInvocations(
            message.toolInvocations + message.reasoningTrace?.toolInvocations.orEmpty(),
        )
    }
    CompositionLocalProvider(LocalAmapPlaces provides amapPlaces) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val context = LocalContext.current
        val replayToolInvocations = message.replayToolInvocations()
        val agentModeFrames = remember(context, replayToolInvocations) {
            buildAgentModeReplayFrames(context, replayToolInvocations)
        }
        // Subagent capsules stay visible after the turn completes: hoist them
        // out of the collapsible "worked for …" disclosure so the collapsed
        // "N subagents completed" capsule never disappears behind the fold.
        val hoistedSubagentInvocations = remember(message.toolInvocations, message.reasoningTrace, chrome.deskVisible) {
            collectToolCollaborationInvocations(
                message.toolInvocations + message.reasoningTrace?.toolInvocations.orEmpty(),
                hideBrowserSwarmUi = chrome.deskVisible,
            )
        }
        val hoistedSpotifyInvocations = remember(message.toolInvocations, message.reasoningTrace) {
            collectSpotifyPlaybackInvocations(
                message.toolInvocations + message.reasoningTrace?.toolInvocations.orEmpty(),
            )
        }
        val hoistedGmailSearchInvocations = remember(message.toolInvocations, message.reasoningTrace) {
            collectGmailLiveInvocations(
                message.toolInvocations + message.reasoningTrace?.toolInvocations.orEmpty(),
            )
        }
        val hoistedAmapNavInvocations = remember(message.toolInvocations, message.reasoningTrace) {
            collectAmapNavigationInvocations(
                message.toolInvocations + message.reasoningTrace?.toolInvocations.orEmpty(),
            )
        }
        val hoistedSubagentIds = hoistedSubagentInvocations.mapTo(hashSetOf()) { it.id }
        val hoistedOutOfFoldIds = hoistedSubagentIds +
            hoistedSpotifyInvocations.map { it.id } +
            hoistedGmailSearchInvocations.map { it.id } +
            hoistedAmapNavInvocations.map { it.id } +
            (message.toolInvocations + message.reasoningTrace?.toolInvocations.orEmpty())
                .filter { it.isBrowserDeskSubagent() }
                .map { it.id }
        CollaborationCapsuleStack(invocations = hoistedSubagentInvocations)
        SpotifyPlaybackCardStack(invocations = hoistedSpotifyInvocations)
        GmailSearchCardStack(
            invocations = hoistedGmailSearchInvocations,
            relatedInvocations = message.toolInvocations + message.reasoningTrace?.toolInvocations.orEmpty(),
        )
        BrowserPreviewCardHost(
            placement = BrowserPreviewPlacement.Message,
            messages = listOf(message),
        )
        AmapNavigationCardStack(invocations = hoistedAmapNavInvocations)

        val workContent: @Composable () -> Unit = {
            AssistantMessageWorkContent(
                message = message,
                agentModeFrames = agentModeFrames,
                workspaceDirectory = workspaceDirectory,
                allowRootImageRead = allowRootImageRead,
                excludedToolInvocationIds = hoistedOutOfFoldIds,
                onOpenAttachment = onOpenAttachment,
                onOpenLink = onOpenLink,
            )
        }
        if (shouldFoldWorkBeforeFinalText) {
            AgentWorkSummaryDisclosure(
                title = formatWorkedSummaryTitle(
                    workDurationMillisForMessages(
                        messages = listOf(message),
                        endAtMillis = message.createdAtMillis,
                    ),
                ),
                stateKey = "message-work-${message.id}",
                content = workContent,
            )
        }
        if (!shouldFoldWorkBeforeFinalText) {
        if (message.reasoningTrace != null) {
            ReasoningTraceStatus(
                trace = message.reasoningTrace,
                excludedToolInvocationIds = hoistedOutOfFoldIds,
                onOpenLink = onOpenLink,
            )
        } else {
            message.thoughtDurationMillis?.let { duration ->
                Text(
                    text = stringResource(R.string.chat_thought_for_duration, formatThoughtDuration(duration)),
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                )
            }
        }
        if (agentModeFrames.isNotEmpty()) {
            AgentModeReplayPanel(
                frames = agentModeFrames,
                stateKey = "agent-mode-replay-${message.id}",
                workspaceDirectory = workspaceDirectory,
                allowRootImageRead = allowRootImageRead,
                onOpenLink = onOpenLink,
            )
        } else if (message.reasoningTrace == null) {
            ToolInvocationList(
                toolInvocations = message.toolInvocations.filterNot { invocation ->
                    invocation.id in hoistedOutOfFoldIds ||
                        invocation.isAgentModeDisplayInvocation() ||
                        invocation.isGmailLiveCard() ||
                        invocation.isBrowserDeskSubagent() ||
                        invocation.isSilentBrowserHostTool(message.toolInvocations) ||
                        invocation.isAmapNavigationCard()
                },
                stateKey = "message-tools-${message.id}",
            )
        }
        AssistantAttachments(
            attachments = message.attachments,
            onOpenAttachment = onOpenAttachment,
        )
        }
        MessageUpaProtocolCards(listOf(message))
        if (message.text.isNotBlank()) {
            val visible = assistantMarkdownForGmailCards(
                message.text,
                message.toolInvocations + message.reasoningTrace?.toolInvocations.orEmpty(),
            )
            if (visible.isNotBlank()) {
                MarkdownContent(
                    markdown = visible,
                    workspaceDirectory = workspaceDirectory,
                    allowRootImageRead = allowRootImageRead,
                    onLinkClick = onOpenLink,
                    knowledgeCitations = knowledgeCitations,
                )
            }
        }
        if (message.statusText.isNotBlank()) {
            ReconnectingStatusCard(
                text = message.statusText,
                detail = message.statusDetail,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (showActions) {
            AssistantMessageActions(
                actionsEnabled = actionsEnabled,
                onCopy = onCopy,
                onRedo = onRedo,
                onDelete = onDelete,
                messageId = message.id,
            )
        }
    }
    }
}

@Composable
private fun AssistantMessageWorkContent(
    message: ChatMessage,
    agentModeFrames: List<AgentModeReplayFrame>,
    workspaceDirectory: String?,
    allowRootImageRead: Boolean,
    excludedToolInvocationIds: Set<String>,
    onOpenAttachment: (ChatAttachment) -> Unit,
    onOpenLink: (String) -> Unit,
) {
    if (message.reasoningTrace != null) {
        ReasoningTraceStatus(
            trace = message.reasoningTrace,
            excludedToolInvocationIds = excludedToolInvocationIds,
            onOpenLink = onOpenLink,
        )
    } else {
        message.thoughtDurationMillis?.let { duration ->
            Text(
                text = stringResource(R.string.chat_thought_for_duration, formatThoughtDuration(duration)),
                style = MaterialTheme.typography.bodySmall,
                color = AetherOnSurfaceVariant,
            )
        }
    }
    if (agentModeFrames.isNotEmpty()) {
        AgentModeReplayPanel(
            frames = agentModeFrames,
            stateKey = "agent-mode-replay-${message.id}",
            workspaceDirectory = workspaceDirectory,
            allowRootImageRead = allowRootImageRead,
            onOpenLink = onOpenLink,
        )
    } else if (message.reasoningTrace == null) {
        ToolInvocationList(
            toolInvocations = message.toolInvocations.filterNot {
                it.id in excludedToolInvocationIds ||
                    it.isAgentModeDisplayInvocation() ||
                    it.isGmailLiveCard() ||
                    it.isBrowserDeskSubagent() ||
                    it.isAmapNavigationCard()
            },
            stateKey = "message-tools-${message.id}",
        )
    }
    AssistantAttachments(
        attachments = message.attachments,
        onOpenAttachment = onOpenAttachment,
    )
}

@Composable
fun ConversationAssistantGroupBubble(
    messages: List<ChatMessage>,
    actionsEnabled: Boolean,
    workspaceDirectory: String?,
    allowRootImageRead: Boolean = false,
    onOpenAttachment: (ChatAttachment) -> Unit,
    onOpenLink: (String) -> Unit,
    onCopy: () -> Unit,
    onRedo: () -> Unit,
    onDelete: () -> Unit,
    sessionTotalTokens: Long? = null,
    knowledgeCitations: List<KnowledgeCitation> = emptyList(),
    isStreaming: Boolean = false,
    omitBodyMarkdown: Boolean = false,
) {
    if (messages.isEmpty()) return
    val chrome = LocalBrowserConversationChrome.current
    val amapPlaces = remember(messages) { amapPlacesFromMessages(messages) }
    val groupUsageStatistics = messages.lastOrNull { it.usageStatistics != null }?.usageStatistics
    val thoughtDurationMillis = messages.lastOrNull()?.thoughtDurationMillis
    val hasReasoningTrace = messages.any { it.reasoningTrace != null }
    val showActions = messages.none { it.assistantActionsHidden }
    val context = LocalContext.current
    val agentModeReplayTimeline = remember(context, messages) {
        buildAgentModeReplayTimeline(context, messages)
    }
    val groupAgentModeFrames = agentModeReplayTimeline.frames
    val interleavedAgentModeTextIds = agentModeReplayTimeline.interleavedTextMessageIds
    val firstAgentModeMessageIndex = agentModeReplayTimeline.firstFrameMessageIndex
    val finalTextMessageIndex = messages.indexOfLast { message ->
        message.text.isNotBlank() && message.id !in interleavedAgentModeTextIds
    }
    // Subagent capsules stay visible after the turn completes: hoist them out
    // of every per-message work area (including the collapsible "worked for …"
    // disclosure) so the collapsed "团队呼叫完成" capsule never disappears.
    val hoistedSubagentInvocations = remember(messages, chrome.deskVisible) {
        collectMessageCollaborationInvocations(messages, hideBrowserSwarmUi = chrome.deskVisible)
    }
    val hoistedSpotifyInvocations = remember(messages) {
        collectMessageSpotifyPlaybackInvocations(messages)
    }
    val hoistedGmailSearchInvocations = remember(messages) {
        collectMessageGmailSearchInvocations(messages)
    }
    val hoistedAmapNavInvocations = remember(messages) {
        collectMessageAmapNavigationInvocations(messages)
    }
    val hoistedGmailRelatedInvocations = remember(messages) {
        messages.flatMap { message ->
            message.toolInvocations + message.reasoningTrace?.toolInvocations.orEmpty()
        }
    }
    val hoistedSubagentIds = hoistedSubagentInvocations.mapTo(hashSetOf()) { it.id }
    val hoistedOutOfFoldIds = hoistedSubagentIds +
        hoistedSpotifyInvocations.map { it.id } +
        hoistedGmailSearchInvocations.map { it.id } +
        hoistedAmapNavInvocations.map { it.id } +
        hoistedGmailRelatedInvocations.filter { it.isBrowserDeskSubagent() }.map { it.id }
    val finalBodyMessageId = messages.lastOrNull { it.text.isNotBlank() }?.id
    val showBodyActions = showActions && !omitBodyMarkdown
    CompositionLocalProvider(LocalAmapPlaces provides amapPlaces) {
    if (groupAgentModeFrames.isNotEmpty()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CollaborationCapsuleStack(invocations = hoistedSubagentInvocations)
            SpotifyPlaybackCardStack(invocations = hoistedSpotifyInvocations)
            GmailSearchCardStack(
                invocations = hoistedGmailSearchInvocations,
                relatedInvocations = hoistedGmailRelatedInvocations,
            )
            BrowserPreviewCardHost(
                placement = BrowserPreviewPlacement.Message,
                messages = messages,
            )
            AmapNavigationCardStack(invocations = hoistedAmapNavInvocations)
            AgentModeReplayPanel(
                frames = groupAgentModeFrames,
                stateKey = "agent-mode-replay-${messages.first().responseGroupId ?: messages.first().id}",
                workspaceDirectory = workspaceDirectory,
                allowRootImageRead = allowRootImageRead,
                onOpenLink = onOpenLink,
            )
            MessageUpaProtocolCards(messages)
            finalTextMessageIndex
                .takeIf { it >= 0 && !omitBodyMarkdown }
                ?.let { messages[it] }
                ?.let { message ->
                    val visible = assistantMarkdownForGmailCards(
                        message.text,
                        hoistedGmailRelatedInvocations,
                    )
                    if (visible.isNotBlank()) {
                        MarkdownContent(
                            markdown = visible,
                            workspaceDirectory = workspaceDirectory,
                            allowRootImageRead = allowRootImageRead,
                            onLinkClick = onOpenLink,
                            knowledgeCitations = knowledgeCitations,
                            streaming = isStreaming,
                        )
                    }
                }
            if (showBodyActions) {
                AssistantMessageActions(
                    actionsEnabled = actionsEnabled,
                    onCopy = onCopy,
                    onRedo = onRedo,
                    onDelete = onDelete,
                    messageId = messages.lastOrNull()?.id.orEmpty(),
                )
            }
        }
    } else {
    val shouldFoldWorkBeforeFinalText = finalTextMessageIndex > 0
    val workMessages = if (shouldFoldWorkBeforeFinalText) {
        messages.take(finalTextMessageIndex)
    } else {
        emptyList()
    }
    val visibleMessages = if (shouldFoldWorkBeforeFinalText) {
        messages.drop(finalTextMessageIndex)
    } else {
        messages
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CollaborationCapsuleStack(invocations = hoistedSubagentInvocations)
        SpotifyPlaybackCardStack(invocations = hoistedSpotifyInvocations)
        GmailSearchCardStack(
            invocations = hoistedGmailSearchInvocations,
            relatedInvocations = hoistedGmailRelatedInvocations,
        )
        BrowserPreviewCardHost(
            placement = BrowserPreviewPlacement.Message,
            messages = messages,
        )
        AmapNavigationCardStack(invocations = hoistedAmapNavInvocations)
        if (!shouldFoldWorkBeforeFinalText && !hasReasoningTrace) thoughtDurationMillis?.let { duration ->
            Text(
                text = stringResource(R.string.chat_thought_for_duration, formatThoughtDuration(duration)),
                style = MaterialTheme.typography.bodySmall,
                color = AetherOnSurfaceVariant,
            )
        }
        if (shouldFoldWorkBeforeFinalText) {
            AgentWorkSummaryDisclosure(
                title = formatWorkedSummaryTitle(
                    workDurationMillisForMessages(
                        messages = messages,
                        endAtMillis = messages[finalTextMessageIndex].createdAtMillis,
                    ),
                ),
                stateKey = "assistant-work-${messages.first().responseGroupId ?: messages.first().id}",
            ) {
                if (groupAgentModeFrames.isNotEmpty()) {
                    AgentModeReplayPanel(
                        frames = groupAgentModeFrames,
                        stateKey = "agent-mode-replay-${messages.first().responseGroupId ?: messages.first().id}-folded",
                        workspaceDirectory = workspaceDirectory,
                        allowRootImageRead = allowRootImageRead,
                        onOpenLink = onOpenLink,
                    )
                }
                workMessages.forEachIndexed { index, message ->
                    AssistantGroupMessageContent(
                        message = message,
                        groupAgentModeFrames = if (groupAgentModeFrames.isNotEmpty() && index >= firstAgentModeMessageIndex) {
                            groupAgentModeFrames
                        } else {
                            emptyList()
                        },
                        interleavedAgentModeTextIds = interleavedAgentModeTextIds,
                        workspaceDirectory = workspaceDirectory,
                        allowRootImageRead = allowRootImageRead,
                        excludedToolInvocationIds = hoistedOutOfFoldIds,
                        onOpenAttachment = onOpenAttachment,
                        onOpenLink = onOpenLink,
                    )
                }
            }
        }
        MessageUpaProtocolCards(messages)
        if (!shouldFoldWorkBeforeFinalText && groupAgentModeFrames.isNotEmpty() && firstAgentModeMessageIndex > 0) {
            messages.take(firstAgentModeMessageIndex).forEach { message ->
                AssistantGroupMessageContent(
                    message = message,
                    groupAgentModeFrames = emptyList(),
                    interleavedAgentModeTextIds = emptySet(),
                    workspaceDirectory = workspaceDirectory,
                    allowRootImageRead = allowRootImageRead,
                    excludedToolInvocationIds = hoistedOutOfFoldIds,
                    onOpenAttachment = onOpenAttachment,
                    onOpenLink = onOpenLink,
                )
            }
        }
        if (!shouldFoldWorkBeforeFinalText && groupAgentModeFrames.isNotEmpty()) {
            AgentModeReplayPanel(
                frames = groupAgentModeFrames,
                stateKey = "agent-mode-replay-${messages.first().responseGroupId ?: messages.first().id}",
                workspaceDirectory = workspaceDirectory,
                allowRootImageRead = allowRootImageRead,
                onOpenLink = onOpenLink,
            )
        }
        messages.forEachIndexed { index, message ->
            if (shouldFoldWorkBeforeFinalText && message !in visibleMessages) {
                return@forEachIndexed
            }
            if (groupAgentModeFrames.isNotEmpty() && index < firstAgentModeMessageIndex) {
                return@forEachIndexed
            }
            AssistantGroupMessageContent(
                message = message,
                groupAgentModeFrames = groupAgentModeFrames,
                interleavedAgentModeTextIds = interleavedAgentModeTextIds,
                workspaceDirectory = workspaceDirectory,
                allowRootImageRead = allowRootImageRead,
                excludedToolInvocationIds = hoistedOutOfFoldIds,
                onOpenAttachment = onOpenAttachment,
                onOpenLink = onOpenLink,
                knowledgeCitations = knowledgeCitations,
                // Only the tail of the turn is still growing; earlier messages are final.
                isStreaming = isStreaming && index == messages.lastIndex,
                omitMarkdown = omitBodyMarkdown && message.id == finalBodyMessageId,
            )
        }
        if (showBodyActions) {
            AssistantMessageActions(
                actionsEnabled = actionsEnabled,
                onCopy = onCopy,
                onRedo = onRedo,
                onDelete = onDelete,
                messageId = messages.lastOrNull()?.id.orEmpty(),
            )
        }
    }
    }
    }
}

@Composable
internal fun ConversationAssistantMarkdownSlice(
    block: MarkdownBlock,
    isLast: Boolean,
    actionsEnabled: Boolean,
    workspaceDirectory: String?,
    allowRootImageRead: Boolean,
    onOpenLink: (String) -> Unit,
    knowledgeCitations: List<KnowledgeCitation>,
    onCopy: () -> Unit,
    onRedo: () -> Unit,
    onDelete: () -> Unit,
    showActions: Boolean,
    messageId: String = "",
    amapPlaces: List<kira.ditto.data.AmapPlace> = emptyList(),
) {
    CompositionLocalProvider(LocalAmapPlaces provides amapPlaces) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SelectionContainer {
            MarkdownBlockItem(
                block = block,
                workspaceDirectory = workspaceDirectory,
                allowRootImageRead = allowRootImageRead,
                onLinkClick = onOpenLink,
                knowledgeCitations = knowledgeCitations,
            )
        }
        if (isLast && showActions) {
            AssistantMessageActions(
                actionsEnabled = actionsEnabled,
                onCopy = onCopy,
                onRedo = onRedo,
                onDelete = onDelete,
                messageId = messageId,
            )
        }
    }
    }
}

@Composable
private fun AssistantGroupMessageContent(
    message: ChatMessage,
    groupAgentModeFrames: List<AgentModeReplayFrame>,
    interleavedAgentModeTextIds: Set<String>,
    workspaceDirectory: String?,
    allowRootImageRead: Boolean,
    excludedToolInvocationIds: Set<String> = emptySet(),
    onOpenAttachment: (ChatAttachment) -> Unit,
    onOpenLink: (String) -> Unit,
    knowledgeCitations: List<KnowledgeCitation> = emptyList(),
    isStreaming: Boolean = false,
    omitMarkdown: Boolean = false,
) {
    val context = LocalContext.current
    val replayToolInvocations = message.replayToolInvocations()
    val agentModeFrames = remember(context, replayToolInvocations) {
        buildAgentModeReplayFrames(context, replayToolInvocations)
    }
    if (message.reasoningTrace != null) {
        ReasoningTraceStatus(
            trace = message.reasoningTrace,
            excludedToolInvocationIds = excludedToolInvocationIds,
            onOpenLink = onOpenLink,
        )
    } else if (agentModeFrames.isNotEmpty() && groupAgentModeFrames.isEmpty()) {
        AgentModeReplayPanel(
            frames = agentModeFrames,
            stateKey = "agent-mode-replay-${message.id}",
            workspaceDirectory = workspaceDirectory,
            allowRootImageRead = allowRootImageRead,
            onOpenLink = onOpenLink,
        )
    } else {
        val visibleInvocations = message.toolInvocations.filterNot { invocation ->
            invocation.isAgentModeDisplayInvocation() || invocation.isGmailLiveCard() ||
                invocation.isBrowserDeskSubagent() || invocation.isAmapNavigationCard() ||
                invocation.isSilentBrowserHostTool(message.toolInvocations)
        }
        ToolInvocationList(
            toolInvocations = visibleInvocations.filterNot { it.id in excludedToolInvocationIds },
            stateKey = "message-tools-${message.id}",
        )
    }
    AssistantAttachments(
        attachments = message.attachments,
        onOpenAttachment = onOpenAttachment,
    )
    if (!omitMarkdown && message.text.isNotBlank() && message.id !in interleavedAgentModeTextIds) {
        val visible = assistantMarkdownForGmailCards(
            message.text,
            message.toolInvocations + message.reasoningTrace?.toolInvocations.orEmpty(),
        )
        if (visible.isNotBlank()) {
            MarkdownContent(
                markdown = visible,
                workspaceDirectory = workspaceDirectory,
                allowRootImageRead = allowRootImageRead,
                onLinkClick = onOpenLink,
                knowledgeCitations = knowledgeCitations,
                streaming = isStreaming,
            )
        }
    }
    if (message.statusText.isNotBlank()) {
        ReconnectingStatusCard(
            text = message.statusText,
            detail = message.statusDetail,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun AgentModeReplayPanel(
    frames: List<AgentModeReplayFrame>,
    stateKey: String,
    workspaceDirectory: String? = null,
    allowRootImageRead: Boolean = false,
    onOpenLink: (String) -> Unit = {},
) {
    var selectedIndex by rememberSaveable(stateKey, frames.size) {
        mutableStateOf((frames.size - 1).coerceAtLeast(0))
    }
    if (selectedIndex !in frames.indices) {
        selectedIndex = (frames.size - 1).coerceAtLeast(0)
    }
    val frame = frames[selectedIndex]
    val overlayText = frame.overlayText
    val bitmap = remember(frame.previewPath, frame.completedAtUptimeMillis) {
        frame.previewPath
            .takeIf { it.isNotBlank() && File(it).exists() }
            ?.let { BitmapFactory.decodeFile(it) }
    }

    AgentModeComputerCard(
        identityKey = stateKey,
        phase = AgentModeComputerPhase.Ready,
        isRunning = false,
        initiallyExpanded = true,
        expandedKey = stateKey,
    ) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(agentModeReplayBackdropBrush()),
            contentAlignment = Alignment.Center,
        ) {
            var previewSize by remember { mutableStateOf(IntSize.Zero) }
            val density = LocalDensity.current
            val imagePaddingPx = with(density) { 10.dp.toPx() }
            val cursorOffset = remember(
                previewSize,
                frame.width,
                frame.height,
                frame.cursorX,
                frame.cursorY,
            ) {
                resolveAgentModeCursorOffset(
                    previewSize = previewSize,
                    imagePaddingPx = imagePaddingPx,
                    displayWidth = frame.width,
                    displayHeight = frame.height,
                    cursorX = frame.cursorX,
                    cursorY = frame.cursorY,
                )
            }
            val animatedCursorOffset by animateIntOffsetAsState(
                targetValue = cursorOffset,
                animationSpec = tween(durationMillis = 260, easing = ToolTransitionEasing),
                label = "agent_mode_replay_cursor_offset",
            )
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.agent_mode_replay_frame),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp)
                        .clip(RoundedCornerShape(14.dp)),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text(
                    text = stringResource(R.string.agent_mode_replay_preview_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
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
                    if (overlayText.isNotBlank()) {
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
                            animationSpec = tween(durationMillis = 260, easing = ToolTransitionEasing),
                            label = "agent_mode_replay_bubble_offset",
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
        if (frames.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReplayStepButton(
                    enabled = selectedIndex > 0,
                    reverse = true,
                    onClick = { selectedIndex = (selectedIndex - 1).coerceAtLeast(0) },
                )
                Text(
                    text = "${selectedIndex + 1} / ${frames.size}",
                    modifier = Modifier.padding(horizontal = 14.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = AetherOnSurfaceVariant,
                )
                ReplayStepButton(
                    enabled = selectedIndex < frames.lastIndex,
                    reverse = false,
                    onClick = { selectedIndex = (selectedIndex + 1).coerceAtMost(frames.lastIndex) },
                )
            }
        }
    }
    }
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
    workspaceDirectory: String?,
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
): IntOffset {
    if (previewSize.width <= 0 || previewSize.height <= 0) return IntOffset.Zero
    val contentWidth = (previewSize.width - imagePaddingPx * 2f).coerceAtLeast(1f)
    val contentHeight = (previewSize.height - imagePaddingPx * 2f).coerceAtLeast(1f)
    val sourceWidth = displayWidth.coerceAtLeast(1)
    val sourceHeight = displayHeight.coerceAtLeast(1)
    val scale = minOf(contentWidth / sourceWidth, contentHeight / sourceHeight)
    val renderedWidth = sourceWidth * scale
    val renderedHeight = sourceHeight * scale
    val renderedLeft = imagePaddingPx + (contentWidth - renderedWidth) / 2f
    val renderedTop = imagePaddingPx + (contentHeight - renderedHeight) / 2f
    val cursorFractionX = cursorX?.let { it.toFloat() / sourceWidth } ?: 0.58f
    val cursorFractionY = cursorY?.let { it.toFloat() / sourceHeight } ?: 0.56f
    return IntOffset(
        x = (renderedLeft + renderedWidth * cursorFractionX.coerceIn(0f, 1f)).roundToInt(),
        y = (renderedTop + renderedHeight * cursorFractionY.coerceIn(0f, 1f)).roundToInt(),
    )
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
private fun ReplayStepButton(
    enabled: Boolean,
    reverse: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(if (enabled) AetherSurfaceHigh else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
            contentDescription = null,
            tint = if (enabled) AetherOnSurface else AetherOnSurfaceVariant.copy(alpha = 0.36f),
            modifier = Modifier
                .size(13.dp)
                .graphicsLayer { rotationZ = if (reverse) 180f else 0f },
        )
    }
}

@Composable
fun ToolInvocationList(
    toolInvocations: List<ChatToolInvocation>,
    stateKey: String,
    autoExpand: Boolean = false,
) {
    val visibleInvocations = toolInvocations.filterNot { it.isAgentModeDisplayInvocation() }
    if (visibleInvocations.isEmpty()) return

    if (visibleInvocations.size < ToolInvocationCollapseThreshold) {
        ToolInvocationCardsColumn(
            toolInvocations = visibleInvocations,
        )
        return
    }

    val isRunning = visibleInvocations.any { it.isRunning }
    var headerVisible by rememberSaveable(stateKey) { mutableStateOf(!autoExpand) }
    var expanded by rememberSaveable(stateKey) { mutableStateOf(autoExpand) }
    var lastAutoExpanded by rememberSaveable(stateKey) { mutableStateOf(autoExpand) }
    LaunchedEffect(autoExpand) {
        if (lastAutoExpanded != autoExpand) {
            if (autoExpand) {
                headerVisible = false
                expanded = true
            } else {
                headerVisible = true
                expanded = true
                delay(ToolGroupCollapseStageDelayMillis)
                expanded = false
            }
            lastAutoExpanded = autoExpand
        }
    }
    val childIndent by animateDpAsState(
        targetValue = if (headerVisible) ToolGroupIndent else 0.dp,
        animationSpec = tween(durationMillis = ToolTransitionDurationMillis, easing = ToolTransitionEasing),
        label = "tool_group_indent",
    )
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(durationMillis = ToolTransitionDurationMillis, easing = ToolTransitionEasing),
        label = "tool_group_arrow_rotation",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AnimatedVisibility(
            visible = headerVisible,
            enter = fadeIn(
                animationSpec = tween(
                    durationMillis = ToolTransitionDurationMillis - 100,
                    easing = ToolTransitionEasing,
                ),
            ) + expandVertically(
                animationSpec = tween(durationMillis = ToolTransitionDurationMillis, easing = ToolTransitionEasing),
                expandFrom = Alignment.Top,
            ),
            exit = fadeOut(
                animationSpec = tween(durationMillis = 180, easing = FastOutLinearInEasing),
            ) + shrinkVertically(
                animationSpec = tween(durationMillis = 220, easing = FastOutLinearInEasing),
                shrinkTowards = Alignment.Top,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .noRippleClickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isRunning) {
                    ShimmerStatusText(
                        text = stringResource(R.string.tool_invocation_group_executing, visibleInvocations.size),
                        modifier = Modifier.weight(1f),
                        travelDurationMillis = 2600,
                        pauseDurationMillis = 1000,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.tool_invocation_group_executed, visibleInvocations.size),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AetherOnSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
                    contentDescription = if (expanded) {
                        stringResource(R.string.tool_invocation_collapse_tools)
                    } else {
                        stringResource(R.string.tool_invocation_expand_tools)
                    },
                    tint = AetherOnSurfaceVariant,
                    modifier = Modifier
                        .size(14.dp)
                        .graphicsLayer { rotationZ = arrowRotation },
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(
                animationSpec = tween(durationMillis = ToolTransitionDurationMillis, easing = ToolTransitionEasing),
                expandFrom = Alignment.Top,
            ) + fadeIn(
                animationSpec = tween(
                    durationMillis = ToolTransitionDurationMillis - 90,
                    delayMillis = 40,
                    easing = ToolTransitionEasing,
                ),
            ),
            exit = shrinkVertically(
                animationSpec = tween(durationMillis = 260, easing = FastOutLinearInEasing),
                shrinkTowards = Alignment.Top,
            ) + fadeOut(
                animationSpec = tween(durationMillis = 180, easing = FastOutLinearInEasing),
            ),
        ) {
            ToolInvocationCardsColumn(
                toolInvocations = visibleInvocations,
                indent = childIndent,
                topPadding = 4.dp,
            )
        }
    }
}

@Composable
fun AgentWorkSummaryDisclosure(
    title: String,
    stateKey: String,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(stateKey) { mutableStateOf(initiallyExpanded) }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(durationMillis = ToolTransitionDurationMillis, easing = ToolTransitionEasing),
        label = "agent_work_arrow_rotation",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .noRippleClickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = AetherOnSurfaceVariant,
            )
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
                contentDescription = if (expanded) stringResource(R.string.agent_work_collapse) else stringResource(R.string.agent_work_expand),
                tint = AetherOnSurfaceVariant,
                modifier = Modifier
                    .size(14.dp)
                    .graphicsLayer { rotationZ = arrowRotation },
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(
                animationSpec = tween(durationMillis = ToolTransitionDurationMillis, easing = ToolTransitionEasing),
                expandFrom = Alignment.Top,
            ) + fadeIn(
                animationSpec = tween(
                    durationMillis = ToolTransitionDurationMillis - 90,
                    delayMillis = 40,
                    easing = ToolTransitionEasing,
                ),
            ),
            exit = shrinkVertically(
                animationSpec = tween(durationMillis = 260, easing = FastOutLinearInEasing),
                shrinkTowards = Alignment.Top,
            ) + fadeOut(
                animationSpec = tween(durationMillis = 180, easing = FastOutLinearInEasing),
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = ToolGroupIndent),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
fun AgentWorkingStatusHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = AetherOnSurfaceVariant,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(AetherOutlineSoft.copy(alpha = 0.62f))
        )
    }
}

@Composable
private fun ToolInvocationCardsColumn(
    toolInvocations: List<ChatToolInvocation>,
    indent: Dp = 0.dp,
    topPadding: Dp = 6.dp,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = indent),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        toolInvocations.forEach { toolInvocation ->
            ToolInvocationCard(
                toolInvocation = toolInvocation,
                topPadding = topPadding,
            )
        }
    }
}

@Composable
fun PendingAssistantResponseBlock(
    text: String,
    workspaceDirectory: String?,
    allowRootImageRead: Boolean = false,
    onOpenLink: (String) -> Unit,
    knowledgeCitations: List<KnowledgeCitation> = emptyList(),
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StreamingMarkdownContent(
            markdown = text,
            workspaceDirectory = workspaceDirectory,
            allowRootImageRead = allowRootImageRead,
            onLinkClick = onOpenLink,
            knowledgeCitations = knowledgeCitations,
        )
    }
}

@Composable
fun StreamingMarkdownContent(
    markdown: String,
    workspaceDirectory: String?,
    allowRootImageRead: Boolean,
    onLinkClick: (String) -> Unit,
    knowledgeCitations: List<KnowledgeCitation> = emptyList(),
) {
    MarkdownContent(
        markdown = markdown,
        modifier = Modifier.fillMaxWidth(),
        workspaceDirectory = workspaceDirectory,
        allowRootImageRead = allowRootImageRead,
        onLinkClick = onLinkClick,
        knowledgeCitations = knowledgeCitations,
        streaming = true,
    )
}

@Composable
fun ShimmerStatusText(
    text: String,
    modifier: Modifier = Modifier,
    travelDurationMillis: Int = 1800,
    pauseDurationMillis: Int = 1000,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    if (LocalReduceMotion.current || LocalChatActivityPaused.current) {
        Text(
            text = text,
            modifier = modifier,
            style = style,
            color = AetherOnSurfaceVariant,
            maxLines = maxLines,
            overflow = overflow,
        )
        return
    }
    val totalDurationMillis = travelDurationMillis + pauseDurationMillis
    val travelDistance = 420f
    val shimmerOffset by rememberInfiniteTransition(label = "status_shimmer").animateFloat(
        initialValue = -travelDistance,
        targetValue = travelDistance,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = totalDurationMillis
                travelDistance at travelDurationMillis using LinearEasing
                travelDistance at totalDurationMillis
            },
        ),
        label = "status_shimmer_offset",
    )
    val brush = Brush.linearGradient(
        colors = listOf(
            AetherOnSurfaceVariant.copy(alpha = 0.42f),
            AetherOnSurface.copy(alpha = 0.96f),
            AetherOnSurfaceVariant.copy(alpha = 0.42f),
        ),
        start = Offset(shimmerOffset - 180f, 0f),
        end = Offset(shimmerOffset + 180f, 0f),
    )
    Text(
        text = text,
        modifier = modifier,
        style = style.copy(brush = brush),
        maxLines = maxLines,
        overflow = overflow,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReasoningTraceStatus(
    trace: ReasoningTrace,
    modifier: Modifier = Modifier,
    excludedToolInvocationIds: Set<String> = emptySet(),
    onOpenLink: (String) -> Unit = {},
) {
    var sheetVisible by remember(trace.id) { mutableStateOf(false) }
    val completed = trace.completedAtMillis != null
    val hasSubagentWork = trace.toolInvocations.any { it.isCollaborationCapsule() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .noRippleClickable { sheetVisible = true },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            completed -> Text(
                text = formatReasoningTraceDoneLabel(trace),
                style = MaterialTheme.typography.bodyMedium,
                color = AetherOnSurfaceVariant,
            )

            else -> {
                ShimmerStatusText(
                    text = stringResource(
                        if (hasSubagentWork) R.string.chat_team_collaborating else R.string.chat_thinking,
                    ),
                )
            }
        }
        // Subagent/AgentSwarm reasoning belongs in their clickable detail
        // cards. Keep the conversation body compact and avoid showing the
        // generic "waiting for reasoning" placeholder under collaboration.
        if (!completed && !hasSubagentWork) {
            ReasoningStreamCard(
                text = trace.rawText.ifBlank { stringResource(R.string.chat_waiting_for_reasoning) },
                maxHeight = 132.dp,
                followLatest = trace.completedAtMillis == null,
            )
        }
        val visibleToolInvocations = trace.toolInvocations.filterNot { invocation ->
                invocation.id in excludedToolInvocationIds ||
                invocation.isAgentModeDisplayInvocation() ||
                invocation.isGmailLiveCard() ||
                invocation.isBrowserDeskSubagent() ||
                invocation.isSilentBrowserHostTool(trace.toolInvocations) ||
                invocation.isAmapNavigationCard()
        }
        if (visibleToolInvocations.isNotEmpty()) {
            ToolInvocationList(
                toolInvocations = visibleToolInvocations,
                stateKey = "reasoning-tools-${trace.id}",
                autoExpand = !completed,
            )
        }
    }

    if (sheetVisible) {
        ModalBottomSheet(
            onDismissRequest = { sheetVisible = false },
            containerColor = AetherSurface,
            contentColor = AetherOnSurface,
            tonalElevation = 0.dp,
            dragHandle = null,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 640.dp),
            ) {
                ReasoningTraceSheetContent(
                    trace = trace,
                    onOpenLink = onOpenLink,
                )
                AetherSheetDragHandleScrim()
            }
        }
    }
}

@Composable
private fun ReasoningTraceSheetContent(
    trace: ReasoningTrace,
    @Suppress("UNUSED_PARAMETER") onOpenLink: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 640.dp)
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, top = 36.dp, end = 24.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        RawReasoningPanel(rawText = trace.rawText)
        val sheetTools = trace.toolInvocations.filterNot { it.isAgentModeDisplayInvocation() }
        if (sheetTools.isNotEmpty()) {
            Text(
                text = stringResource(R.string.chat_thinking_process),
                style = MaterialTheme.typography.labelMedium,
                color = AetherOnSurfaceVariant,
            )
            ToolInvocationList(
                toolInvocations = sheetTools,
                stateKey = "reasoning-sheet-tools-${trace.id}",
                autoExpand = true,
            )
        }
    }
}

@Composable
private fun RawReasoningPanel(
    rawText: String,
) {
    val waitingForReasoning = stringResource(R.string.chat_waiting_for_reasoning)
    val displayText = remember(rawText, waitingForReasoning) {
        rawText.ifBlank { waitingForReasoning }.let { text ->
            if (text.length <= 12_000) text else text.take(12_000).trimEnd() + "\n..."
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.chat_thinking_process),
            style = MaterialTheme.typography.labelMedium,
            color = AetherOnSurfaceVariant,
        )
        SelectionContainer {
            Text(
                text = displayText,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(AetherSurfaceHigh)
                    .padding(14.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = AetherOnSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReasoningTimeline(
    trace: ReasoningTrace,
    onOpenLink: (String) -> Unit,
) {
    val timelineItems = remember(trace.chunks, trace.toolInvocations) {
        reasoningTimelineItems(trace)
    }
    val hasDoneChunk = trace.completedAtMillis != null
    val summarizingReasoningTitle = stringResource(R.string.chat_summarizing_reasoning)
    val preparingReasoningSummary = stringResource(R.string.chat_preparing_reasoning_summary)
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        timelineItems.forEachIndexed { index, item ->
            val isLast = !hasDoneChunk && index == timelineItems.lastIndex
            when (item) {
                is ReasoningTimelineItem.Summary -> ReasoningTimelineRow(
                    title = item.chunk.title.ifBlank { summarizingReasoningTitle },
                    detail = when {
                        item.chunk.detail.isNotBlank() -> item.chunk.detail
                        else -> preparingReasoningSummary
                    },
                    isLast = isLast,
                )

                is ReasoningTimelineItem.Tool -> ReasoningTimelineToolRow(
                    toolInvocation = item.toolInvocation,
                    isLast = isLast,
                    onOpenLink = onOpenLink,
                )
            }
        }
        if (hasDoneChunk) {
            ReasoningTimelineDoneRow(trace = trace)
        }
    }
}

internal sealed interface ReasoningTimelineItem {
    val sortOrder: Long
    val fallbackOrder: Int

    data class Summary(
        val chunk: ReasoningSummaryChunk,
        override val sortOrder: Long,
        override val fallbackOrder: Int,
    ) : ReasoningTimelineItem

    data class Tool(
        val toolInvocation: ChatToolInvocation,
        override val sortOrder: Long,
        override val fallbackOrder: Int,
    ) : ReasoningTimelineItem
}

internal fun reasoningTimelineItems(trace: ReasoningTrace): List<ReasoningTimelineItem> {
    val visibleChunks = trace.chunks.filter {
        it.title.isNotBlank() || it.detail.isNotBlank() || it.isPending || it.rawText.isNotBlank()
    }
    val items = buildList {
        visibleChunks.forEachIndexed { index, chunk ->
            add(
                ReasoningTimelineItem.Summary(
                    chunk = chunk,
                    sortOrder = chunk.timelineOrder
                        .takeIf { it > 0L }
                        ?: chunk.createdAtMillis.takeIf { it > 0L }
                        ?: Long.MAX_VALUE,
                    fallbackOrder = index,
                )
            )
        }
        trace.toolInvocations.forEachIndexed { index, invocation ->
            add(
                ReasoningTimelineItem.Tool(
                    toolInvocation = invocation,
                    sortOrder = invocation.timelineOrder
                        .takeIf { it > 0L }
                        ?: invocation.startedAtMillis.takeIf { it > 0L }
                        ?: Long.MAX_VALUE,
                    fallbackOrder = visibleChunks.size + index,
                )
            )
        }
    }

    return items.sortedWith(
        compareBy<ReasoningTimelineItem> { it.sortOrder }
            .thenBy { it.fallbackOrder }
    )
}

@Composable
private fun ReasoningTimelineRow(
    title: String,
    detail: String,
    isLast: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TimelineGlyph(isLast = isLast)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = if (isLast) 0.dp else 22.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = AetherOnSurface,
            )
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AetherOnSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ReasoningTimelineToolRow(
    toolInvocation: ChatToolInvocation,
    isLast: Boolean,
    @Suppress("UNUSED_PARAMETER") onOpenLink: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TimelineGlyph(
            icon = reasoningToolIcon(toolInvocation.toolName),
            isLast = isLast,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = if (isLast) 0.dp else 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToolInvocationCard(
                toolInvocation = toolInvocation,
                topPadding = 0.dp,
            )
        }
    }
}

@Composable
private fun ReasoningTimelineDoneRow(
    trace: ReasoningTrace,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TimelineGlyph(
            icon = Icons.Rounded.CheckCircle,
            isLast = true,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = formatReasoningTraceDoneChunkTitle(trace),
                style = MaterialTheme.typography.bodyMedium,
                color = AetherOnSurface,
            )
            Text(
                text = stringResource(R.string.common_done),
                style = MaterialTheme.typography.bodyMedium,
                color = AetherOnSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TimelineGlyph(
    icon: ImageVector? = null,
    isLast: Boolean,
) {
    Box(
        modifier = Modifier
            .width(TimelineGlyphWidth)
            .fillMaxHeight(),
        contentAlignment = Alignment.TopCenter,
    ) {
        if (!isLast) {
            Box(
                modifier = Modifier
                    .padding(
                        top = TimelineIconSize + TimelineLineTopGap,
                        bottom = TimelineLineBottomGap,
                    )
                    .width(TimelineLineWidth)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(999.dp))
                    .background(AetherOnSurfaceVariant.copy(alpha = 0.12f))
            )
        }
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = AetherOnSurfaceVariant,
                modifier = Modifier.size(TimelineIconSize),
            )
        } else {
            Box(
                modifier = Modifier
                    .padding(top = 5.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(AetherOnSurfaceVariant)
            )
        }
    }
}

private fun reasoningToolIcon(toolName: String): ImageVector = when (toolName.lowercase()) {
    "bash" -> Icons.Rounded.Terminal
    "fetch_web_url", "tavily_search" -> Icons.Rounded.Language
    else -> Icons.Rounded.Build
}

@Composable
private fun WebSourcePill(
    metadata: WebSourceMetadata,
    onOpenLink: (String) -> Unit,
) {
    val favicon = rememberRemoteBitmap(metadata.faviconUrl)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(AetherSurfaceHigh.copy(alpha = 0.72f))
            .noRippleClickable { onOpenLink(metadata.url) }
            .padding(start = 10.dp, top = 7.dp, end = 12.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (favicon != null) {
            Image(
                bitmap = favicon,
                contentDescription = null,
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Fit,
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.Language,
                contentDescription = null,
                tint = AetherOnSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = metadata.domain,
            style = MaterialTheme.typography.bodyMedium,
            color = AetherOnSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun rememberRemoteBitmap(
    url: String?,
): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, url) {
        value = withContext(Dispatchers.IO) {
            val sourceUrl = url?.takeIf { it.isNotBlank() } ?: return@withContext null
            runCatching {
                val connection = (URL(sourceUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = FaviconFetchTimeoutMillis
                    readTimeout = FaviconFetchTimeoutMillis
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", DefaultFaviconUserAgent)
                    setRequestProperty("Accept", "image/avif,image/webp,image/png,image/svg+xml,image/*;q=0.8,*/*;q=0.5")
                }
                connection.inputStream.use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
    return bitmap
}

private fun webSourceMetadata(
    toolName: String,
    arguments: JSONObject?,
    output: JSONObject?,
): WebSourceMetadata? = when (toolName.lowercase()) {
    "fetch_web_url" -> {
        val sourceUrl = output?.optString("final_url").orEmpty()
            .ifBlank { output?.optString("request_url").orEmpty() }
            .ifBlank { arguments?.optString("url").orEmpty() }
        webSourceMetadataFromUrl(sourceUrl)
    }
    "tavily_search" -> tavilySourceMetadata(arguments, output)
    else -> null
}

private fun tavilySourceMetadata(
    arguments: JSONObject?,
    output: JSONObject?,
): WebSourceMetadata {
    val result = output?.optJSONArray("results")?.let { results ->
        (0 until results.length())
            .asSequence()
            .mapNotNull(results::optJSONObject)
            .firstOrNull { it.optString("url").isNotBlank() || it.optString("favicon").isNotBlank() }
    }
    val resultUrl = result?.optString("url").orEmpty()
    val argumentDomain = firstSearchArgumentDomain(arguments)
    val domain = normalizedDomain(resultUrl)
        .ifBlank { argumentDomain }
        .ifBlank { TavilyFallbackDomain }
    val url = normalizedHttpUrl(resultUrl)
        .ifBlank { normalizedHttpUrl(domain) }
        .ifBlank { "https://$TavilyFallbackDomain" }
    val faviconUrl = result?.optString("favicon").orEmpty()
        .takeIf { (it.startsWith("http://") || it.startsWith("https://")) && !it.endsWith(".svg", ignoreCase = true) }
        ?: faviconUrlForDomain(domain)
    return WebSourceMetadata(
        domain = domain,
        url = url,
        faviconUrl = faviconUrl,
    )
}

private fun webSourceMetadataFromUrl(url: String): WebSourceMetadata? {
    val domain = normalizedDomain(url)
    if (domain.isBlank()) return null
    return WebSourceMetadata(
        domain = domain,
        url = normalizedHttpUrl(url).ifBlank { "https://$domain" },
        faviconUrl = faviconUrlForDomain(domain),
    )
}

private fun firstSearchArgumentDomain(arguments: JSONObject?): String {
    val includeDomains = arguments?.optJSONArray("include_domains")
        ?: arguments?.optJSONArray("includeDomains")
    if (includeDomains != null) {
        for (index in 0 until includeDomains.length()) {
            val domain = normalizedDomain(includeDomains.optString(index))
            if (domain.isNotBlank()) return domain
        }
    }

    val query = arguments?.optString("query").orEmpty()
    val domainPattern = Regex("""(?:site:)?([A-Za-z0-9][A-Za-z0-9.-]*\.[A-Za-z]{2,})(?:/[^\s]*)?""")
    return domainPattern.find(query)
        ?.groupValues
        ?.getOrNull(1)
        ?.let(::normalizedDomain)
        .orEmpty()
}

private fun normalizedDomain(urlOrDomain: String): String {
    val trimmed = urlOrDomain.trim()
    if (trimmed.isBlank()) return ""
    val candidate = if (trimmed.contains("://")) trimmed else "https://$trimmed"
    val host = runCatching { URI(candidate).host }
        .getOrNull()
        .orEmpty()
        .ifBlank { trimmed.substringBefore('/').substringBefore('?').substringBefore('#') }
        .trim('.')
    if (host.isBlank()) return ""
    return runCatching { IDN.toUnicode(host) }
        .getOrDefault(host)
        .removePrefix("www.")
        .lowercase(Locale.US)
}

private fun normalizedHttpUrl(urlOrDomain: String): String {
    val trimmed = urlOrDomain.trim()
    if (trimmed.isBlank()) return ""
    val candidate = if (trimmed.contains("://")) trimmed else "https://$trimmed"
    val uri = runCatching { URI(candidate) }.getOrNull() ?: return ""
    val scheme = uri.scheme?.lowercase(Locale.US).orEmpty()
    if (scheme != "http" && scheme != "https") return ""
    val host = uri.host.orEmpty()
    if (host.isBlank()) return ""
    return uri.toString()
}

private fun faviconUrlForDomain(domain: String): String =
    "https://www.google.com/s2/favicons?domain=${Uri.encode(domain)}&sz=64"

@Composable
fun ReconnectingStatusCard(
    text: String,
    detail: String,
    isRunning: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(text, detail) { mutableStateOf(false) }
    LaunchedEffect(detail) {
        if (detail.isBlank()) {
            expanded = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .noRippleClickable(enabled = detail.isNotBlank()) { expanded = !expanded },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (isRunning) {
            ShimmerStatusText(text = text)
        } else {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = AetherOnSurfaceVariant,
            )
        }

        AnimatedVisibility(
            visible = expanded && detail.isNotBlank(),
            enter = expandVertically(
                animationSpec = tween(durationMillis = ToolTransitionDurationMillis, easing = ToolTransitionEasing),
                expandFrom = Alignment.Top,
            ) + fadeIn(
                animationSpec = tween(
                    durationMillis = ToolTransitionDurationMillis - 90,
                    delayMillis = 40,
                    easing = ToolTransitionEasing,
                ),
            ),
            exit = shrinkVertically(
                animationSpec = tween(durationMillis = 240, easing = FastOutLinearInEasing),
                shrinkTowards = Alignment.Top,
            ) + fadeOut(
                animationSpec = tween(durationMillis = 160, easing = FastOutLinearInEasing),
            ),
        ) {
            SyntaxHighlightedCodeBlock(
                label = stringResource(R.string.common_error),
                content = remember(detail) { highlightToolResult(detail) },
            )
        }
    }
}

@Composable
fun ToolInvocationCard(
    toolInvocation: ChatToolInvocation,
    topPadding: Dp = 6.dp,
) {
    if (toolInvocation.isSpotifyPlaybackCard()) {
        val latest = LocalLatestSpotifyPlaybackId.current
        if (latest.isBlank() || toolInvocation.id == latest) {
            SpotifyPlaybackCard(
                toolInvocation = toolInvocation,
                topPadding = topPadding,
            )
        }
        return
    }
    if (toolInvocation.isGmailLiveCard()) {
        return
    }
    if (toolInvocation.isAmapNavigationCard()) {
        AmapNavigationCard(toolInvocation = toolInvocation, modifier = Modifier.padding(top = topPadding))
        return
    }
    val subagentInfo = remember(toolInvocation.toolName, toolInvocation.argumentsJson, toolInvocation.toolKind) {
        parseSubagentLaunch(toolInvocation.toolName, toolInvocation.argumentsJson, toolInvocation.toolKind)
    }
    if (subagentInfo != null) {
        SubagentCapsuleCard(
            toolInvocation = toolInvocation,
            info = subagentInfo,
            topPadding = topPadding,
        )
        return
    }
    val cronInfo = remember(
        toolInvocation.toolName,
        toolInvocation.argumentsJson,
        toolInvocation.outputJson,
    ) {
        parseCronTool(
            toolInvocation.toolName,
            toolInvocation.argumentsJson,
            toolInvocation.outputJson,
        )
    }
    if (cronInfo != null) {
        CronCapsuleCard(
            toolInvocation = toolInvocation,
            info = cronInfo,
            topPadding = topPadding,
        )
        return
    }
    if (toolInvocation.isTowerRelated()) {
        TowerBoardCard(
            invocations = listOf(toolInvocation),
            topPadding = topPadding,
        )
        return
    }
    if (toolInvocation.isAgentModeDisplayInvocation()) {
        return
    }
    val context = LocalContext.current
    val arguments = remember(toolInvocation.argumentsJson) { parseJsonObject(toolInvocation.argumentsJson) }
    val output = remember(toolInvocation.outputJson) { parseJsonObject(toolInvocation.outputJson) }
    val title = remember(context, toolInvocation.toolName, toolInvocation.isRunning, toolInvocation.argumentsJson) {
        formatToolInvocationTitleLabel(context, toolInvocation, arguments = arguments)
    }
    val showEverMeMark = EverMeTools.matches(toolInvocation.toolName)
    val noOutputLabel = stringResource(R.string.chat_no_output)
    val contentTruncatedLabel = stringResource(R.string.chat_content_truncated)
    val exitCodeLabel = remember(context) {
        { exitCode: Int -> context.getString(R.string.tool_invocation_exit_code, exitCode) }
    }
    val detail = remember(
        toolInvocation.toolName,
        toolInvocation.isRunning,
        toolInvocation.argumentsJson,
        toolInvocation.outputJson,
        noOutputLabel,
        contentTruncatedLabel,
        exitCodeLabel,
    ) {
        formatToolInvocationDetail(
            toolInvocation = toolInvocation,
            arguments = arguments,
            output = output,
            noOutputLabel = noOutputLabel,
            contentTruncatedLabel = contentTruncatedLabel,
            exitCodeLabel = exitCodeLabel,
        )
    }
    var expanded by rememberSaveable(toolInvocation.id) { mutableStateOf(false) }
    LaunchedEffect(
        toolInvocation.id,
        toolInvocation.isRunning,
        toolInvocation.startedAtUptimeMillis,
        toolInvocation.completedAtUptimeMillis,
    ) {
        if (!toolInvocation.isRunning) {
            expanded = false
            return@LaunchedEffect
        }

        val startedAt = toolInvocation.startedAtUptimeMillis
            .takeIf { it > 0L }
            ?: SystemClock.uptimeMillis()
        val remainingDelayMillis = ToolInvocationAutoExpandDelayMillis -
            (SystemClock.uptimeMillis() - startedAt)
        if (remainingDelayMillis > 0L) {
            delay(remainingDelayMillis)
        }
        expanded = true
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .noRippleClickable { expanded = !expanded }
            .padding(top = topPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (showEverMeMark) {
                Image(
                    painter = painterResource(R.drawable.everme_mark),
                    contentDescription = stringResource(R.string.tool_title_everme_mark),
                    modifier = Modifier.size(16.dp),
                )
            }
            if (toolInvocation.isRunning) {
                ShimmerStatusText(
                    text = title,
                    modifier = Modifier.weight(1f, fill = false),
                    travelDurationMillis = 3200,
                    pauseDurationMillis = 1000,
                )
            } else {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AetherOnSurfaceVariant,
                )
            }
        }
        AnimatedVisibility(
            visible = expanded && (detail.command.isNotBlank() || toolInvocation.diffs.isNotEmpty()),
            enter = expandVertically(
                animationSpec = tween(durationMillis = ToolTransitionDurationMillis, easing = ToolTransitionEasing),
                expandFrom = Alignment.Top,
            ) + fadeIn(
                animationSpec = tween(
                    durationMillis = ToolTransitionDurationMillis - 90,
                    delayMillis = 40,
                    easing = ToolTransitionEasing,
                ),
            ),
            exit = shrinkVertically(
                animationSpec = tween(durationMillis = 240, easing = FastOutLinearInEasing),
                shrinkTowards = Alignment.Top,
            ) + fadeOut(
                animationSpec = tween(durationMillis = 160, easing = FastOutLinearInEasing),
            ),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (detail.command.isNotBlank()) {
                    SyntaxHighlightedCodeBlock(
                        label = stringResource(R.string.tool_invocation_command),
                        content = remember(detail.command) { highlightBashCommand(detail.command) },
                    )
                }
                detail.result?.let { result ->
                    SyntaxHighlightedCodeBlock(
                        label = stringResource(R.string.tool_invocation_result),
                        content = remember(result) { highlightToolResult(result) },
                    )
                }
                if (toolInvocation.diffs.isNotEmpty()) {
                    ToolCallDiffSection(diffs = toolInvocation.diffs)
                }
            }
        }
    }
}

private fun Modifier.noRippleClickable(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    clickable(
        enabled = enabled,
        indication = null,
        interactionSource = remember { MutableInteractionSource() },
        onClick = onClick,
    )
}

@Composable
internal fun SyntaxHighlightedCodeBlock(
    label: String,
    content: AnnotatedString,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 220.dp,
    scrollState: ScrollState = rememberScrollState(),
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = AetherOnSurfaceVariant,
        )
        SelectionContainer {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(AetherSurfaceHigh)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .heightIn(max = maxHeight)
                    .verticalScroll(scrollState),
            ) {
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = AetherOnSurface,
                )
            }
        }
    }
}

@Composable
private fun AssistantAttachments(
    attachments: List<ChatAttachment>,
    onOpenAttachment: (ChatAttachment) -> Unit,
) {
    attachments.forEach { attachment ->
        when (attachment.kind) {
            AttachmentKind.Image -> UserImageAttachmentCard(attachment = attachment, onClick = { onOpenAttachment(attachment) })
            AttachmentKind.File -> UserFileAttachmentCard(attachment = attachment, onClick = { onOpenAttachment(attachment) })
        }
    }
}

@Composable
fun AttachmentPreviewDialog(
    attachment: ChatAttachment,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    val attachmentKindLabel = attachmentTypeLabel(attachment.kind)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 24.dp)
                .shadow(24.dp, RoundedCornerShape(30.dp), ambientColor = AetherScrim, spotColor = AetherScrim)
                .clip(RoundedCornerShape(30.dp))
            .background(AetherSurface.copy(alpha = 0.98f))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(AetherSurfaceHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (attachment.kind == AttachmentKind.Image) Icons.Rounded.Image else Icons.Rounded.Description,
                        contentDescription = null,
                        tint = AetherOnSurface,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = attachment.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = AetherOnSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = formatAttachmentMetaLabel(attachmentKindLabel, attachment),
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherOnSurfaceVariant,
                    )
                }
                IconOnlyAction(
                    icon = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.attachment_close_preview),
                    onClick = onDismiss,
                )
            }

            when (attachment.kind) {
                AttachmentKind.Image -> AttachmentImagePreview(attachment)
                AttachmentKind.File -> AttachmentFilePreview(attachment)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionIconLabel(
                    icon = Icons.Rounded.Download,
                    label = stringResource(R.string.common_save),
                    enabled = true,
                    onClick = onSave,
                )
            }
        }
    }
}

@Composable
private fun AttachmentImagePreview(
    attachment: ChatAttachment,
) {
    val bitmap = rememberAttachmentBitmap(attachment, maxSize = 2200)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 220.dp, max = 560.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(AetherSurfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = attachment.name,
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.Fit,
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.Image,
                contentDescription = null,
                tint = AetherOnSurfaceVariant,
                modifier = Modifier.size(40.dp),
            )
        }
    }
}

@Composable
private fun AttachmentFilePreview(
    attachment: ChatAttachment,
) {
    val preview = rememberAttachmentTextPreview(attachment)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(AetherSurfaceHigh)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (preview == null) {
            Text(
                text = stringResource(R.string.attachment_file_preview_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = AetherOnSurfaceVariant,
            )
        } else {
            SelectionContainer {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = preview.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AetherOnSurface,
                    )
                }
            }
            if (preview.isTruncated) {
                Text(
                    text = stringResource(R.string.attachment_preview_truncated),
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun UserImageAttachmentCard(
    attachment: ChatAttachment,
    onClick: () -> Unit,
) {
    val bitmap = rememberAttachmentBitmap(attachment, maxSize = 1400)
    Box(
        modifier = Modifier
            .widthIn(max = 300.dp)
            .shadow(10.dp, RoundedCornerShape(24.dp), ambientColor = AetherScrim, spotColor = AetherScrim)
            .clip(RoundedCornerShape(24.dp))
            .background(AetherSurface)
            .clickable(onClick = onClick)
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = attachment.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp)
                    .background(AetherSurfaceHigh),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Image,
                    contentDescription = null,
                    tint = AetherOnSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

@Composable
private fun UserFileAttachmentCard(
    attachment: ChatAttachment,
    onClick: () -> Unit,
) {
    val attachmentKindLabel = attachmentTypeLabel(attachment.kind)
    Row(
        modifier = Modifier
            .widthIn(max = 300.dp)
            .shadow(10.dp, RoundedCornerShape(22.dp), ambientColor = AetherScrim, spotColor = AetherScrim)
            .clip(RoundedCornerShape(22.dp))
            .background(AetherSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(AetherSurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Description, contentDescription = null, tint = AetherOnSurface)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = attachment.name,
                style = MaterialTheme.typography.labelLarge,
                color = AetherOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatAttachmentMetaLabel(attachmentKindLabel, attachment),
                style = MaterialTheme.typography.bodySmall,
                color = AetherOnSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
            contentDescription = null,
            tint = AetherOnSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun ComposerImageAttachmentCard(
    attachment: ChatAttachment,
    onRemove: () -> Unit,
) {
    val attachmentKindLabel = attachmentTypeLabel(attachment.kind)
    val copyingToWorkspaceLabel = stringResource(R.string.attachment_copying_to_workspace)
    val workspaceCopyFailedLabel = stringResource(R.string.attachment_workspace_copy_failed)
    val bitmap = rememberAttachmentBitmap(attachment, maxSize = 600)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(AetherSurfaceHigh)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(62.dp)
                .clip(RoundedCornerShape(16.dp))
            .background(AetherSurface),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = attachment.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(Icons.Rounded.Image, contentDescription = null, tint = AetherOnSurfaceVariant)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = attachment.name,
                style = MaterialTheme.typography.labelLarge,
                color = AetherOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatComposerAttachmentMetaLabel(
                    attachmentKindLabel = attachmentKindLabel,
                    copyingToWorkspaceLabel = copyingToWorkspaceLabel,
                    workspaceCopyFailedLabel = workspaceCopyFailedLabel,
                    attachment = attachment,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = AetherOnSurfaceVariant,
            )
        }
        IconOnlyAction(
            icon = Icons.Rounded.Close,
            contentDescription = stringResource(R.string.attachment_remove),
            onClick = onRemove,
        )
    }
}

@Composable
private fun ComposerFileAttachmentCard(
    attachment: ChatAttachment,
    onRemove: () -> Unit,
) {
    val attachmentKindLabel = attachmentTypeLabel(attachment.kind)
    val copyingToWorkspaceLabel = stringResource(R.string.attachment_copying_to_workspace)
    val workspaceCopyFailedLabel = stringResource(R.string.attachment_workspace_copy_failed)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(AetherSurfaceHigh)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
            .background(AetherSurface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.AttachFile, contentDescription = null, tint = AetherOnSurface)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = attachment.name,
                style = MaterialTheme.typography.labelLarge,
                color = AetherOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatComposerAttachmentMetaLabel(
                    attachmentKindLabel = attachmentKindLabel,
                    copyingToWorkspaceLabel = copyingToWorkspaceLabel,
                    workspaceCopyFailedLabel = workspaceCopyFailedLabel,
                    attachment = attachment,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = AetherOnSurfaceVariant,
            )
        }
        IconOnlyAction(
            icon = Icons.Rounded.Close,
            contentDescription = stringResource(R.string.attachment_remove),
            onClick = onRemove,
        )
    }
}

@Composable
private fun ActionIconLabel(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (enabled) AetherSurfaceHigh else AetherSurfaceHigh.copy(alpha = 0.45f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) AetherOnSurface else AetherOnSurface.copy(alpha = 0.45f), modifier = Modifier.size(14.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (enabled) AetherOnSurface else AetherOnSurface.copy(alpha = 0.45f))
    }
}

@Composable
private fun IconOnlyAction(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(
                if (enabled) AetherSurface.copy(alpha = 0.72f) else AetherSurface.copy(alpha = 0.35f)
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) AetherOnSurfaceVariant else AetherOnSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun AssistantMessageAction(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) AetherOnSurfaceVariant else AetherOnSurfaceVariant.copy(alpha = 0.36f),
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun AssistantMessageActions(
    actionsEnabled: Boolean,
    onCopy: () -> Unit,
    onRedo: () -> Unit,
    onDelete: () -> Unit,
    messageId: String = "",
) {
    val ttsPlayback = LocalTtsPlayback.current
    val showPlayButton = ttsPlayback.showButton && messageId.isNotBlank()
    val isPlaying = ttsPlayback.playingMessageId == messageId
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistantMessageAction(
            icon = LucideIcons.Copy,
            contentDescription = stringResource(R.string.common_copy_reply),
            onClick = onCopy,
        )
        if (showPlayButton) {
            AssistantMessageAction(
                icon = if (isPlaying) LucideIcons.Square else LucideIcons.Play,
                contentDescription = stringResource(R.string.action_play_tts),
                onClick = { ttsPlayback.onPlay(messageId) },
            )
        }
        AssistantMessageAction(
            icon = LucideIcons.RotateCcw,
            contentDescription = stringResource(R.string.common_redo_reply),
            enabled = actionsEnabled,
            onClick = onRedo,
        )
        AssistantMessageAction(
            icon = LucideIcons.Trash2,
            contentDescription = stringResource(R.string.common_delete_reply),
            enabled = actionsEnabled,
            onClick = onDelete,
        )
    }
}

private fun formatMessageTimestamp(createdAtMillis: Long): String {
    if (createdAtMillis <= 0L) return ""
    return runCatching {
        Instant.ofEpochMilli(createdAtMillis)
            .atZone(ZoneId.systemDefault())
            .format(MessageTimestampFormatter)
    }.getOrDefault("")
}

@Composable
private fun rememberAttachmentBitmap(
    attachment: ChatAttachment,
    maxSize: Int,
): ImageBitmap? {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, attachment, maxSize) {
        value = withContext(Dispatchers.IO) {
            decodeInlineAttachmentBitmap(attachment, maxSize)
                ?: decodeUriAttachmentBitmap(
                    resolver = context.contentResolver,
                    uriString = attachment.uri,
                    maxSize = maxSize,
                )
        }
    }
    return bitmap
}

private fun decodeInlineAttachmentBitmap(
    attachment: ChatAttachment,
    maxSize: Int,
): ImageBitmap? = runCatching {
    if (attachment.inlineBase64.isBlank()) return@runCatching null
    val bytes = Base64.getDecoder().decode(attachment.inlineBase64)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

    val sampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, maxSize)
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}.getOrNull()

private fun decodeUriAttachmentBitmap(
    resolver: android.content.ContentResolver,
    uriString: String,
    maxSize: Int,
): ImageBitmap? = decodeUriAttachmentBitmap(
    uriString = uriString,
    maxSize = maxSize,
    openInputStream = { uri -> resolver.openInputStream(uri) },
)

internal fun decodeUriAttachmentBitmap(
    uriString: String,
    maxSize: Int,
    openInputStream: (Uri) -> java.io.InputStream?,
): ImageBitmap? = runCatching {
    val uri = Uri.parse(uriString)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        return@runCatching null
    }

    val sampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, maxSize)
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, options)?.asImageBitmap()
    }
}.getOrNull()

@Composable
private fun rememberAttachmentTextPreview(
    attachment: ChatAttachment,
): AttachmentTextPreview? {
    val context = LocalContext.current
    val preview by produceState<AttachmentTextPreview?>(initialValue = null, attachment) {
        value = withContext(Dispatchers.IO) {
            if (!isLikelyTextAttachment(attachment)) {
                return@withContext null
            }

            val bytes = readAttachmentBytes(
                resolver = context.contentResolver,
                uriString = attachment.uri,
                byteLimit = 64 * 1024 + 1,
            ) ?: return@withContext null

            val decoded = decodeTextAttachment(bytes) ?: return@withContext null
            val isTruncated = bytes.size > 64 * 1024 || decoded.length > 12_000
            AttachmentTextPreview(
                text = decoded.take(12_000),
                isTruncated = isTruncated,
            )
        }
    }
    return preview
}

private fun readAttachmentBytes(
    resolver: android.content.ContentResolver,
    uriString: String,
    byteLimit: Int,
): ByteArray? = runCatching {
    resolver.openInputStream(Uri.parse(uriString))?.use { inputStream ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var totalRead = 0

        while (true) {
            val read = inputStream.read(buffer)
            if (read <= 0) break

            val remaining = byteLimit - totalRead
            if (remaining <= 0) break

            val writeCount = minOf(read, remaining)
            output.write(buffer, 0, writeCount)
            totalRead += writeCount

            if (totalRead >= byteLimit) break
        }

        output.toByteArray()
    }
}.getOrNull()

private fun decodeTextAttachment(
    bytes: ByteArray,
): String? {
    if (bytes.take(512).any { it == 0.toByte() }) return null

    val decoded = bytes.toString(Charsets.UTF_8)
    val replacementCount = decoded.count { it == '\uFFFD' }
    return if (replacementCount > 12) null else decoded
}

private fun isLikelyTextAttachment(
    attachment: ChatAttachment,
): Boolean {
    val mimeType = attachment.mimeType.lowercase()
    if (
        mimeType.startsWith("text/") ||
        mimeType.contains("json") ||
        mimeType.contains("xml") ||
        mimeType.contains("yaml") ||
        mimeType.contains("csv") ||
        mimeType.contains("javascript")
    ) {
        return true
    }

    val extension = attachment.name.substringAfterLast('.', "").lowercase()
    return extension in setOf(
        "txt",
        "md",
        "json",
        "xml",
        "yaml",
        "yml",
        "csv",
        "log",
        "kt",
        "java",
        "kts",
        "js",
        "ts",
        "tsx",
        "jsx",
        "py",
        "rb",
        "go",
        "rs",
        "c",
        "cc",
        "cpp",
        "h",
        "hpp",
        "html",
        "css",
        "sql",
        "sh",
        "gradle",
        "properties",
    )
}

private fun calculateSampleSize(
    width: Int,
    height: Int,
    maxSize: Int,
): Int {
    var sampleSize = 1
    var currentWidth = width
    var currentHeight = height

    while (currentWidth > maxSize || currentHeight > maxSize) {
        currentWidth /= 2
        currentHeight /= 2
        sampleSize *= 2
    }
    return sampleSize.coerceAtLeast(1)
}

private fun formatThoughtDuration(durationMillis: Long): String {
    val totalSeconds = (durationMillis / 1000f).roundToInt().coerceAtLeast(1)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return buildList {
        if (hours > 0) add("${hours}h")
        if (minutes > 0 || hours > 0) add("${minutes}min")
        add("${seconds}s")
    }.joinToString(" ")
}

@Composable
fun formatWorkedSummaryTitle(durationMillis: Long): String =
    stringResource(R.string.chat_working_for_duration, formatThoughtDuration(durationMillis))

fun workDurationMillisForMessages(
    messages: List<ChatMessage>,
    endAtMillis: Long? = null,
): Long {
    messages.asReversed().forEach { message ->
        val usage = message.usageStatistics ?: return@forEach
        if (
            usage.startedAtMillis >= MinimumEpochMillis &&
            usage.completedAtMillis >= usage.startedAtMillis
        ) {
            return (usage.completedAtMillis - usage.startedAtMillis).coerceAtLeast(1_000L)
        }
    }
    messages.lastOrNull()?.thoughtDurationMillis?.let { duration ->
        return duration.coerceAtLeast(1_000L)
    }

    val timestamps = mutableListOf<Long>()
    messages.forEach { message ->
        if (message.createdAtMillis > 0L) {
            timestamps += message.createdAtMillis
        }
        message.reasoningTrace?.let { trace ->
            if (trace.startedAtMillis > 0L) timestamps += trace.startedAtMillis
            trace.completedAtMillis?.takeIf { it > 0L }?.let { timestamps += it }
            trace.chunks.forEach { chunk ->
                if (chunk.createdAtMillis > 0L) timestamps += chunk.createdAtMillis
            }
        }
        message.toolInvocations.forEach { invocation ->
            if (invocation.startedAtMillis > 0L) timestamps += invocation.startedAtMillis
            invocation.completedAtMillis?.takeIf { it > 0L }?.let { timestamps += it }
        }
    }
    endAtMillis?.takeIf { it > 0L }?.let { timestamps += it }

    val wallClockTimestamps = timestamps.filter { it >= MinimumEpochMillis }
    if (wallClockTimestamps.size < 2) {
        return 1_000L
    }
    return (wallClockTimestamps.maxOrNull()!! - wallClockTimestamps.minOrNull()!!)
        .coerceAtLeast(1_000L)
}

fun workDurationMillisForBlocks(
    blocks: List<AssistantResponseBlock>,
    endAtMillis: Long = System.currentTimeMillis(),
): Long {
    val timestamps = mutableListOf<Long>()
    blocks.forEach { block ->
        when (block) {
            is AssistantResponseBlock.Text -> Unit
            is AssistantResponseBlock.Status -> Unit
            is AssistantResponseBlock.ToolGroup -> block.toolInvocations.forEach { invocation ->
                if (invocation.startedAtMillis > 0L) timestamps += invocation.startedAtMillis
                invocation.completedAtMillis?.takeIf { it > 0L }?.let { timestamps += it }
            }
            is AssistantResponseBlock.Reasoning -> {
                val trace = block.trace
                if (trace.startedAtMillis > 0L) timestamps += trace.startedAtMillis
                trace.completedAtMillis?.takeIf { it > 0L }?.let { timestamps += it }
                trace.chunks.forEach { chunk ->
                    if (chunk.createdAtMillis > 0L) timestamps += chunk.createdAtMillis
                }
                trace.toolInvocations.forEach { invocation ->
                    if (invocation.startedAtMillis > 0L) timestamps += invocation.startedAtMillis
                    invocation.completedAtMillis?.takeIf { it > 0L }?.let { timestamps += it }
                }
            }
        }
    }
    timestamps += endAtMillis

    val wallClockTimestamps = timestamps.filter { it >= MinimumEpochMillis }
    if (wallClockTimestamps.size < 2) {
        return 1_000L
    }
    return (wallClockTimestamps.maxOrNull()!! - wallClockTimestamps.minOrNull()!!)
        .coerceAtLeast(1_000L)
}

fun workDurationMillisForToolInvocations(
    toolInvocations: List<ChatToolInvocation>,
    endAtMillis: Long = System.currentTimeMillis(),
): Long {
    val timestamps = mutableListOf<Long>()
    toolInvocations.forEach { invocation ->
        if (invocation.startedAtMillis > 0L) timestamps += invocation.startedAtMillis
        invocation.completedAtMillis?.takeIf { it > 0L }?.let { timestamps += it }
    }
    timestamps += endAtMillis

    val wallClockTimestamps = timestamps.filter { it >= MinimumEpochMillis }
    if (wallClockTimestamps.size < 2) {
        return 1_000L
    }
    return (wallClockTimestamps.maxOrNull()!! - wallClockTimestamps.minOrNull()!!)
        .coerceAtLeast(1_000L)
}

@Composable
private fun formatReasoningTraceDoneLabel(trace: ReasoningTrace): String {
    val startedAt = trace.startedAtMillis.takeIf { it > 0L }
    val endedAt = trace.completedAtMillis ?: System.currentTimeMillis()
    val duration = startedAt?.let { formatThoughtDuration((endedAt - it).coerceAtLeast(1L)) } ?: "0s"
    val toolCount = trace.toolInvocations.size
    return if (toolCount > 0) {
        stringResource(R.string.chat_thought_for_duration_and_tools, duration, toolCount)
    } else {
        stringResource(R.string.chat_thought_for_duration, duration)
    }
}

@Composable
private fun formatReasoningTraceDoneChunkTitle(trace: ReasoningTrace): String {
    val startedAt = trace.startedAtMillis.takeIf { it > 0L }
    val endedAt = trace.completedAtMillis ?: System.currentTimeMillis()
    val duration = startedAt?.let { formatThoughtDuration((endedAt - it).coerceAtLeast(1L)) } ?: "0s"
    return stringResource(R.string.chat_thought_for_duration, duration)
}

@Composable
private fun attachmentTypeLabel(kind: AttachmentKind): String = stringResource(
    if (kind == AttachmentKind.Image) R.string.attachment_type_photo else R.string.attachment_type_file,
)

private fun formatAttachmentMetaLabel(attachmentKindLabel: String, attachment: ChatAttachment): String {
    val sizeLabel = attachment.sizeBytes?.let(::formatAttachmentSize)
    return listOfNotNull(attachmentKindLabel, sizeLabel).joinToString(" | ")
}

private fun formatComposerAttachmentMetaLabel(
    attachmentKindLabel: String,
    copyingToWorkspaceLabel: String,
    workspaceCopyFailedLabel: String,
    attachment: ChatAttachment,
): String {
    val statusLabel = when (attachment.workspaceState) {
        AttachmentWorkspaceState.Pending -> copyingToWorkspaceLabel
        AttachmentWorkspaceState.Failed -> workspaceCopyFailedLabel
        AttachmentWorkspaceState.Ready -> null
    }
    return listOfNotNull(
        formatAttachmentMetaLabel(attachmentKindLabel, attachment).ifBlank { null },
        if (attachment.workspaceState == AttachmentWorkspaceState.Pending) {
            formatWorkspaceCopyProgress(copyingToWorkspaceLabel, attachment)
        } else {
            statusLabel
        },
    ).joinToString(" | ")
}

private fun formatWorkspaceCopyProgress(copyingToWorkspaceLabel: String, attachment: ChatAttachment): String {
    val copiedLabel = attachment.workspaceBytesCopied
        .takeIf { it > 0L }
        ?.let(::formatAttachmentSize)
    val totalLabel = attachment.sizeBytes?.takeIf { it > 0L }?.let(::formatAttachmentSize)
    val speedLabel = attachment.workspaceBytesPerSecond
        .takeIf { it > 0L }
        ?.let { "${formatAttachmentSize(it)}/s" }
    val progressLabel = when {
        copiedLabel != null && totalLabel != null -> "$copiedLabel / $totalLabel"
        copiedLabel != null -> copiedLabel
        else -> null
    }
    return listOfNotNull(copyingToWorkspaceLabel, progressLabel, speedLabel).joinToString(" · ")
}

private fun formatToolInvocationTitleLabel(
    context: Context,
    toolInvocation: ChatToolInvocation,
    isRunningOverride: Boolean = toolInvocation.isRunning,
    arguments: JSONObject? = parseJsonObject(toolInvocation.argumentsJson),
): String {
    val isRunning = isRunningOverride
    if (isAgentModeDisplayToolName(toolInvocation.toolName)) {
        return formatAgentDisplayTitle(context = context, isRunning = isRunning, arguments = arguments)
    }
    if (DeviceCatalogMcp.matchesToolName(toolInvocation.toolName)) {
        return formatArgumentDrivenTitle(
            isRunning = isRunning,
            progressiveVerb = context.getString(R.string.tool_title_reading),
            completedVerb = context.getString(R.string.tool_title_read),
            subject = arguments?.optString("query").orEmpty(),
            fallback = context.getString(R.string.tool_title_installed_apps_fallback),
        )
    }
    if (EverMeTools.matches(toolInvocation.toolName)) {
        val res = if (EverMeTools.isWrite(toolInvocation.toolName)) {
            if (isRunning) R.string.tool_title_everme_writing_memory else R.string.tool_title_everme_wrote_memory
        } else {
            if (isRunning) R.string.tool_title_everme_reading_memory else R.string.tool_title_everme_read_memory
        }
        return context.getString(res)
    }
    if (SessionNoteTools.matches(toolInvocation.toolName)) {
        val res = when (SessionNoteTools.canonical(toolInvocation.toolName)) {
            "new_context" -> if (isRunning) R.string.tool_title_session_new_context else R.string.tool_title_session_new_context_done
            "read_original" -> if (isRunning) R.string.tool_title_reading_original else R.string.tool_title_read_original
            else -> if (isRunning) R.string.tool_title_session_note_updating else R.string.tool_title_session_note_updated
        }
        return context.getString(res)
    }
    if (GmailMcp.matchesToolName(toolInvocation.toolName)) {
        val canonical = GmailMcp.canonicalToolName(toolInvocation.toolName)
        val subject = when (canonical) {
            "search_threads", "list_drafts" -> arguments?.optString("query").orEmpty()
            "create_draft" -> arguments?.optString("subject").orEmpty()
            "get_thread", "label_thread", "unlabel_thread" ->
                arguments?.optString("threadId").orEmpty()
                    .ifBlank { arguments?.optString("thread_id").orEmpty() }
                    .ifBlank { arguments?.optString("id").orEmpty() }
            else -> arguments?.optString("id").orEmpty()
        }
        val running = if (canonical.startsWith("create") || canonical.contains("label")) {
            context.getString(R.string.tool_title_edit_running)
        } else {
            context.getString(R.string.tool_title_reading)
        }
        val done = if (canonical.startsWith("create") || canonical.contains("label")) {
            context.getString(R.string.tool_title_edit_done)
        } else {
            context.getString(R.string.tool_title_read)
        }
        return formatArgumentDrivenTitle(
            isRunning = isRunning,
            progressiveVerb = running,
            completedVerb = done,
            subject = subject,
            fallback = context.getString(R.string.tool_title_gmail_fallback),
        )
    }
    if (SpotifyMcp.matchesToolName(toolInvocation.toolName)) {
        val canonical = SpotifyMcp.canonicalToolName(toolInvocation.toolName)
        val subject = when (canonical) {
            "SpotifySearch" -> arguments?.optString("query").orEmpty()
            "SpotifyPlayback", "SpotifyQueue", "SpotifyPlaylist", "SpotifyLibrary" ->
                arguments?.optString("action").orEmpty()
            "SpotifyGetInfo" -> arguments?.optString("item_uri").orEmpty()
                .ifBlank { arguments?.optString("itemUri").orEmpty() }
            else -> ""
        }
        return formatArgumentDrivenTitle(
            isRunning = isRunning,
            progressiveVerb = context.getString(R.string.tool_title_reading),
            completedVerb = context.getString(R.string.tool_title_read),
            subject = subject,
            fallback = context.getString(R.string.tool_title_spotify_fallback),
        )
    }
    if (GithubMcp.matchesToolName(toolInvocation.toolName)) {
        val canonical = GithubMcp.canonicalToolName(toolInvocation.toolName)
        val subject = if (canonical == GithubMcp.CallTool) {
            arguments?.optString("method").orEmpty()
        } else {
            ""
        }
        return formatArgumentDrivenTitle(
            isRunning = isRunning,
            progressiveVerb = context.getString(R.string.tool_title_reading),
            completedVerb = context.getString(R.string.tool_title_read),
            subject = subject,
            fallback = context.getString(R.string.tool_title_github_fallback),
        )
    }
    if (HuggingFaceMcp.matchesToolName(toolInvocation.toolName)) {
        val canonical = HuggingFaceMcp.canonicalToolName(toolInvocation.toolName)
        val subject = if (canonical == HuggingFaceMcp.CallTool) {
            arguments?.optString("method").orEmpty()
        } else {
            ""
        }
        return formatArgumentDrivenTitle(
            isRunning = isRunning,
            progressiveVerb = context.getString(R.string.tool_title_reading),
            completedVerb = context.getString(R.string.tool_title_read),
            subject = subject,
            fallback = context.getString(R.string.tool_title_huggingface_fallback),
        )
    }
    (context.applicationContext as? AetherApplication)?.runtime?.modKernel?.toolTitles
        ?.titleFor(toolInvocation.toolName, isRunning)?.let { return it }
    return when (toolInvocation.toolName.lowercase()) {
        "bash" -> context.getString(if (isRunning) R.string.tool_title_bash_running else R.string.tool_title_bash_done)
        "read" -> context.getString(if (isRunning) R.string.tool_title_read_running else R.string.tool_title_read_done)
        "edit" -> context.getString(if (isRunning) R.string.tool_title_edit_running else R.string.tool_title_edit_done)
        "write" -> context.getString(if (isRunning) R.string.tool_title_write_running else R.string.tool_title_write_done)
        "grep" -> context.getString(if (isRunning) R.string.tool_title_grep_running else R.string.tool_title_grep_done)
        "find" -> context.getString(if (isRunning) R.string.tool_title_find_running else R.string.tool_title_find_done)
        "ls" -> context.getString(if (isRunning) R.string.tool_title_ls_running else R.string.tool_title_ls_done)
        "agent_display" -> formatAgentDisplayTitle(context = context, isRunning = isRunning, arguments = arguments)
        "chrome", "browser" -> formatChromeTitle(context = context, isRunning = isRunning, arguments = arguments)
        "aether_config_get",
        "aether_config_set",
        "aether_skill_manage",
        "aether_termux_manage",
        "aether_agent_mode_manage",
        "aether_developer_manage" -> formatAetherToolTitle(
            context = context,
            toolName = toolInvocation.toolName,
            isRunning = isRunning,
            arguments = arguments,
        )
        else -> if (isRunning) {
            context.getString(R.string.tool_title_using_tool, toolInvocation.toolName)
        } else {
            context.getString(R.string.tool_title_used_tool, toolInvocation.toolName)
        }
    }
}

private fun summarizeToolInvocationCommandLabel(
    toolName: String,
    arguments: JSONObject?,
): String {
    if (arguments == null) return toolName
    return when (toolName.lowercase()) {
        "bash" -> arguments.optString("command").trim()
        "read" -> buildString {
            append("read ")
            append(arguments.optString("path").trim())
            val offset = arguments.takeIf { it.has("offset") }?.optInt("offset") ?: 0
            val limit = arguments.takeIf { it.has("limit") }?.optInt("limit")
            if (offset > 0 || limit != null) {
                append(" (offset=")
                append(offset)
                if (limit != null) {
                    append(", limit=")
                    append(limit)
                }
                append(')')
            }
        }
        "edit" -> {
            val path = arguments.optString("path").trim()
            val editCount = arguments.optJSONArray("edits")?.length()
                ?: if (arguments.has("oldText") || arguments.has("newText")) 1 else 0
            "edit $path${if (editCount > 0) " ($editCount edit${if (editCount == 1) "" else "s"})" else ""}"
        }
        "write" -> "write ${arguments.optString("path").trim()}"
        "grep" -> "grep ${arguments.optString("pattern").trim()} in ${arguments.optString("path").trim()}"
        "find" -> "find ${arguments.optString("pattern").trim()} in ${arguments.optString("path").trim()}"
        "ls" -> "ls ${arguments.optString("path").trim()}"
        "analyze_image" -> buildString {
            append("analyze_image ")
            append(arguments.optString("path").trim())
            val prompt = arguments.optString("prompt").trim()
            if (prompt.isNotBlank()) {
                append(" (")
                append(prompt.take(48))
                if (prompt.length > 48) {
                    append("...")
                }
                append(')')
            }
        }
        "agent_display" -> summarizeAgentDisplayCommand(arguments)
        "chrome", "browser" -> summarizeChromeCommand(arguments)
        "tavily_search" -> "search ${arguments.optString("query").trim()}"
        "fetch_web_url" -> "fetch ${arguments.optString("url").trim()}"
        "aether_config_get",
        "aether_config_set",
        "aether_skill_manage",
        "aether_mcp_manage",
        "aether_termux_manage",
        "aether_agent_mode_manage",
        "aether_developer_manage" -> summarizeAetherToolCommand(toolName, arguments)
        else -> toolName
    }.trim()
}

private fun formatToolInvocationDetail(
    toolInvocation: ChatToolInvocation,
    arguments: JSONObject? = parseJsonObject(toolInvocation.argumentsJson),
    output: JSONObject? = parseJsonObject(toolInvocation.outputJson),
    noOutputLabel: String,
    contentTruncatedLabel: String,
    exitCodeLabel: (Int) -> String,
): ToolInvocationDetail {
    val command = output?.optString("command")
        .orEmpty()
        .trim()
        .ifBlank { summarizeToolInvocationCommandLabel(toolInvocation.toolName, arguments) }
        .ifBlank { toolInvocation.toolName }

    if (toolInvocation.isRunning && output == null) {
        return ToolInvocationDetail(
            command = command,
            result = null,
        )
    }

    val result = when {
        output == null -> toolInvocation.outputJson.trim().ifBlank { noOutputLabel }
        toolInvocation.toolName.startsWith("aether_", ignoreCase = true) -> {
            toolInvocation.outputJson.trim().ifBlank { noOutputLabel }
        }
        toolInvocation.toolName.equals("fetch_web_url", ignoreCase = true) -> {
            formatFetchWebUrlResult(output, noOutputLabel, contentTruncatedLabel)
        }
        output.optString("stdout").trim().isNotBlank() &&
            output.optString("stderr").trim().isNotBlank() -> {
            buildString {
                appendLine(output.optString("stdout").trim())
                appendLine()
                append("stderr: ")
                append(output.optString("stderr").trim())
            }
        }
        output.optString("stdout").trim().isNotBlank() -> output.optString("stdout").trim()
        output.optString("stderr").trim().isNotBlank() -> output.optString("stderr").trim()
        output.optString("errmsg").trim().isNotBlank() -> output.optString("errmsg").trim()
        output.optString("hint").trim().isNotBlank() -> output.optString("hint").trim()
        output.has("exit_code") && output.optInt("exit_code") != 0 -> exitCodeLabel(output.optInt("exit_code"))
        else -> toolInvocationResultText(output, toolInvocation.outputJson, noOutputLabel)
    }

    return ToolInvocationDetail(
        command = command,
        result = result,
    )
}

private fun formatFetchWebUrlResult(
    output: JSONObject,
    noOutputLabel: String,
    contentTruncatedLabel: String,
): String {
    val markdown = output.optString("markdown").trim()
    if (markdown.isNotBlank()) {
        return buildString {
            append(markdown)

            if (output.optBoolean("truncated")) {
                appendLine()
                appendLine()
                append(contentTruncatedLabel)
            }
        }.trim()
    }

    return output.optString("stdout").trim().ifBlank { noOutputLabel }
}

private fun summarizeToolInvocationCommand(
    toolName: String,
    arguments: JSONObject?,
): String {
    if (arguments == null) return toolName
    return when (toolName.lowercase()) {
        "bash" -> arguments.optString("command").trim()
        "read" -> buildString {
            append("read ")
            append(arguments.optString("path").trim())
            val offset = arguments.takeIf { it.has("offset") }?.optInt("offset") ?: 0
            val limit = arguments.takeIf { it.has("limit") }?.optInt("limit")
            if (offset > 0 || limit != null) {
                append(" (offset=")
                append(offset)
                if (limit != null) {
                    append(", limit=")
                    append(limit)
                }
                append(')')
            }
        }
        "edit" -> {
            val path = arguments.optString("path").trim()
            val editCount = arguments.optJSONArray("edits")?.length()
                ?: if (arguments.has("oldText") || arguments.has("newText")) 1 else 0
            "edit $path${if (editCount > 0) " ($editCount edit${if (editCount == 1) "" else "s"})" else ""}"
        }
        "write" -> "write ${arguments.optString("path").trim()}"
        "grep" -> "grep ${arguments.optString("pattern").trim()} in ${arguments.optString("path").trim()}"
        "find" -> "find ${arguments.optString("pattern").trim()} in ${arguments.optString("path").trim()}"
        "ls" -> "ls ${arguments.optString("path").trim()}"
        "agent_display" -> summarizeAgentDisplayCommand(arguments)
        "chrome", "browser" -> summarizeChromeCommand(arguments)
        "tavily_search" -> "search ${arguments.optString("query").trim()}"
        "fetch_web_url" -> "fetch ${arguments.optString("url").trim()}"
        "aether_config_get",
        "aether_config_set",
        "aether_skill_manage",
        "aether_mcp_manage",
        "aether_termux_manage",
        "aether_agent_mode_manage",
        "aether_developer_manage" -> summarizeAetherToolCommand(toolName, arguments)
        else -> toolName
    }.trim()
}

private fun formatArgumentDrivenTitle(
    isRunning: Boolean,
    progressiveVerb: String,
    completedVerb: String,
    subject: String,
    fallback: String,
): String {
    val normalizedSubject = subject.trim()
    val action = if (isRunning) progressiveVerb else completedVerb
    if (normalizedSubject.isBlank()) {
        return "$action $fallback"
    }

    return "$action ${normalizedSubject.take(72)}${if (normalizedSubject.length > 72) "..." else ""}"
}

private fun formatAgentDisplayTitle(
    context: Context,
    isRunning: Boolean,
    arguments: JSONObject?,
): String {
    val action = arguments?.optString("action").orEmpty().lowercase()
    return when (action) {
        "list_apps", "apps", "installed_apps" -> formatArgumentDrivenTitle(
            isRunning = isRunning,
            progressiveVerb = context.getString(R.string.tool_title_reading),
            completedVerb = context.getString(R.string.tool_title_read),
            subject = arguments?.optString("query").orEmpty(),
            fallback = context.getString(R.string.tool_title_installed_apps_fallback),
        )
        "start" -> context.getString(if (isRunning) R.string.tool_title_starting_agent_mode_display else R.string.tool_title_started_agent_mode_display)
        "status" -> context.getString(if (isRunning) R.string.tool_title_checking_agent_mode_display else R.string.tool_title_checked_agent_mode_display)
        "launch" -> formatArgumentDrivenTitle(
            isRunning = isRunning,
            progressiveVerb = context.getString(R.string.tool_title_launching),
            completedVerb = context.getString(R.string.tool_title_launched),
            subject = arguments?.optString("target").orEmpty(),
            fallback = context.getString(R.string.tool_title_agent_mode_app_fallback),
        )
        "tap" -> context.getString(if (isRunning) R.string.tool_title_tapping_agent_mode_display else R.string.tool_title_tapped_agent_mode_display)
        "swipe", "swipe_left", "swipe_right", "swipe_up", "swipe_down", "fling" ->
            context.getString(if (isRunning) R.string.tool_title_swiping_agent_mode_display else R.string.tool_title_swiped_agent_mode_display)
        "search" -> context.getString(if (isRunning) R.string.tool_title_searching else R.string.tool_title_searched)
        "key" -> formatArgumentDrivenTitle(
            isRunning = isRunning,
            progressiveVerb = context.getString(R.string.tool_title_pressing),
            completedVerb = context.getString(R.string.tool_title_pressed),
            subject = arguments?.optString("key").orEmpty(),
            fallback = context.getString(R.string.tool_title_agent_mode_key_fallback),
        )
        "text" -> context.getString(if (isRunning) R.string.tool_title_typing_agent_mode else R.string.tool_title_typed_agent_mode)
        "screenshot" -> context.getString(if (isRunning) R.string.tool_title_capturing_agent_mode_display else R.string.tool_title_captured_agent_mode_display)
        "stop" -> context.getString(if (isRunning) R.string.tool_title_stopping_agent_mode_display else R.string.tool_title_stopped_agent_mode_display)
        else -> context.getString(if (isRunning) R.string.tool_title_using_agent_mode_display else R.string.tool_title_used_agent_mode_display)
    }
}

private fun formatAetherToolTitle(
    context: Context,
    toolName: String,
    isRunning: Boolean,
    arguments: JSONObject?,
): String {
    val action = arguments?.optString("action").orEmpty().trim()
    return when (toolName.lowercase()) {
        "aether_config_get" -> formatArgumentDrivenTitle(
            isRunning = isRunning,
            progressiveVerb = context.getString(R.string.tool_title_reading),
            completedVerb = context.getString(R.string.tool_title_read),
            subject = summarizeAetherCategories(arguments),
            fallback = context.getString(R.string.tool_title_aether_settings_fallback),
        )
        "aether_config_set" -> formatArgumentDrivenTitle(
            isRunning = isRunning,
            progressiveVerb = context.getString(R.string.tool_title_updating),
            completedVerb = context.getString(R.string.tool_title_updated),
            subject = arguments?.optString("category").orEmpty(),
            fallback = context.getString(R.string.tool_title_aether_settings_fallback),
        )
        "aether_skill_manage" -> when (action.lowercase()) {
            "install_remote" -> formatArgumentDrivenTitle(isRunning, context.getString(R.string.tool_title_installing), context.getString(R.string.tool_title_installed), arguments?.optString("url").orEmpty(), context.getString(R.string.tool_title_agent_skill_fallback))
            "remove" -> formatArgumentDrivenTitle(isRunning, context.getString(R.string.tool_title_removing), context.getString(R.string.tool_title_removed), optAetherString(arguments, "skill_id", "skillId"), context.getString(R.string.tool_title_agent_skill_fallback))
            "set_enabled" -> formatArgumentDrivenTitle(isRunning, context.getString(R.string.tool_title_updating), context.getString(R.string.tool_title_updated), optAetherString(arguments, "skill_id", "skillId"), context.getString(R.string.tool_title_agent_skill_fallback))
            else -> context.getString(if (isRunning) R.string.tool_title_reading_agent_skills else R.string.tool_title_read_agent_skills)
        }
        "aether_mcp_manage" -> when (action.lowercase()) {
            "upsert_streamable_http", "upsert_stdio" -> formatArgumentDrivenTitle(isRunning, context.getString(R.string.tool_title_saving), context.getString(R.string.tool_title_saved), optAetherString(arguments, "display_name", "displayName"), context.getString(R.string.tool_title_mcp_server_fallback))
            "remove" -> formatArgumentDrivenTitle(isRunning, context.getString(R.string.tool_title_removing), context.getString(R.string.tool_title_removed), optAetherString(arguments, "server_id", "serverId"), context.getString(R.string.tool_title_mcp_server_fallback))
            "set_enabled" -> formatArgumentDrivenTitle(isRunning, context.getString(R.string.tool_title_updating), context.getString(R.string.tool_title_updated), optAetherString(arguments, "server_id", "serverId"), context.getString(R.string.tool_title_mcp_server_fallback))
            else -> context.getString(if (isRunning) R.string.tool_title_reading_mcp_servers else R.string.tool_title_read_mcp_servers)
        }
        "aether_termux_manage" -> when (action.lowercase()) {
            "configure_root_access" -> context.getString(if (isRunning) R.string.tool_title_configuring_termux_root else R.string.tool_title_configured_termux_root)
            "inspect_root_setup" -> context.getString(if (isRunning) R.string.tool_title_checking_root_setup else R.string.tool_title_checked_root_setup)
            else -> context.getString(if (isRunning) R.string.tool_title_checking_termux_setup else R.string.tool_title_checked_termux_setup)
        }
        "aether_agent_mode_manage" -> when (action.lowercase()) {
            "set_authorization" -> context.getString(if (isRunning) R.string.tool_title_updating_agent_mode_authorization else R.string.tool_title_updated_agent_mode_authorization)
            "request_shizuku_permission" -> context.getString(if (isRunning) R.string.tool_title_requesting_shizuku_permission else R.string.tool_title_requested_shizuku_permission)
            "stop_display" -> context.getString(if (isRunning) R.string.tool_title_stopping_agent_mode_display else R.string.tool_title_stopped_agent_mode_display)
            "refresh_displays" -> context.getString(if (isRunning) R.string.tool_title_refreshing_agent_mode_displays else R.string.tool_title_refreshed_agent_mode_displays)
            else -> context.getString(if (isRunning) R.string.tool_title_checking_agent_mode_authorization else R.string.tool_title_checked_agent_mode_authorization)
        }
        "aether_developer_manage" -> context.getString(if (isRunning) R.string.tool_title_reading_aether_diagnostics else R.string.tool_title_read_aether_diagnostics)
        else -> context.getString(if (isRunning) R.string.tool_title_managing_aether else R.string.tool_title_managed_aether)
    }
}

private fun summarizeAetherToolCommand(
    toolName: String,
    arguments: JSONObject,
): String {
    val action = arguments.optString("action").trim()
    return when (toolName.lowercase()) {
        "aether_config_get" -> "aether_config_get categories=${summarizeAetherCategories(arguments).ifBlank { "all" }}"
        "aether_config_set" -> "aether_config_set category=${arguments.optString("category").trim()} ${summarizeAetherSettingsPatch(arguments.optJSONObject("settings"))}".trim()
        "aether_skill_manage" -> buildString {
            append("aether_skill_manage action=")
            append(action.ifBlank { "list" })
            appendAetherKeyValue(arguments, "skill_id", "skillId")
            appendAetherKeyValue(arguments, "url")
            if (arguments.has("enabled")) append(" enabled=").append(arguments.optBoolean("enabled"))
        }.trim()
        "aether_mcp_manage" -> buildString {
            append("aether_mcp_manage action=")
            append(action.ifBlank { "list" })
            appendAetherKeyValue(arguments, "server_id", "serverId")
            appendAetherKeyValue(arguments, "display_name", "displayName")
            appendAetherKeyValue(arguments, "url")
            appendAetherKeyValue(arguments, "command")
            if (arguments.has("enabled")) append(" enabled=").append(arguments.optBoolean("enabled"))
        }.trim()
        "aether_termux_manage" -> "aether_termux_manage action=${action.ifBlank { "inspect_setup" }}"
        "aether_agent_mode_manage" -> buildString {
            append("aether_agent_mode_manage action=")
            append(action.ifBlank { "inspect_authorization" })
            if (arguments.has("enabled")) append(" enabled=").append(arguments.optBoolean("enabled"))
            appendAetherKeyValue(arguments, "method")
        }.trim()
        "aether_developer_manage" -> buildString {
            append("aether_developer_manage action=")
            append(action.ifBlank { "read_diagnostics" })
            appendAetherKeyValue(arguments, "include")
            appendAetherKeyValue(arguments, "max_chars", "maxChars")
        }.trim()
        else -> toolName
    }
}

private fun summarizeAetherCategories(arguments: JSONObject?): String {
    val categories = arguments?.optJSONArray("categories") ?: return ""
    return buildList {
        for (index in 0 until categories.length()) {
            val value = categories.optString(index).trim()
            if (value.isNotBlank()) add(value)
        }
    }.joinToString(",")
}

private fun summarizeAetherSettingsPatch(settings: JSONObject?): String {
    if (settings == null) return ""
    val keys = buildList {
        settings.keys().forEach { add(it) }
    }
    return if (keys.isEmpty()) "" else "fields=${keys.joinToString(",")}"
}

private fun optAetherString(
    arguments: JSONObject?,
    primary: String,
    secondary: String,
): String = arguments?.optString(primary).orEmpty().ifBlank {
    arguments?.optString(secondary).orEmpty()
}

private fun StringBuilder.appendAetherKeyValue(
    arguments: JSONObject,
    primary: String,
    secondary: String = "",
) {
    val value = arguments.optString(primary).ifBlank {
        if (secondary.isBlank()) "" else arguments.optString(secondary)
    }.trim()
    if (value.isNotBlank()) {
        append(' ')
        append(primary)
        append('=')
        append(value.take(96))
        if (value.length > 96) append("...")
    }
}

private fun summarizeAgentDisplayCommand(arguments: JSONObject?): String {
    if (arguments == null) return "agent_display"
    val action = arguments.optString("action").trim().ifBlank { "unknown" }
    return when (action.lowercase()) {
        "list_apps", "apps", "installed_apps" -> {
            val query = arguments.optString("query").trim()
            "agent_display list_apps${if (query.isBlank()) "" else " $query"}"
        }
        "launch" -> "agent_display launch ${arguments.optString("target").trim()}"
        "tap" -> "agent_display tap x=${arguments.optString("x").trim()} y=${arguments.optString("y").trim()}"
        "swipe", "swipe_left", "swipe_right", "swipe_up", "swipe_down", "fling" -> {
            val from = "${arguments.optString("x1").trim()},${arguments.optString("y1").trim()}"
            val to = "${arguments.optString("x2").trim()},${arguments.optString("y2").trim()}"
            val duration = arguments.optString("duration_ms").ifBlank { arguments.optString("durationMs") }.trim()
            val direction = arguments.optString("direction").trim()
            "agent_display $action ${if (direction.isNotBlank()) "$direction " else ""}$from -> $to ${duration}ms".trim()
        }
        "search" -> {
            val query = arguments.optString("text").ifBlank { arguments.optString("query") }.trim()
            "agent_display search ${query.take(48)}${if (query.length > 48) "..." else ""}"
        }
        "key" -> "agent_display key ${arguments.optString("key").trim()}"
        "text" -> {
            val text = arguments.optString("text").trim()
            "agent_display text ${text.take(48)}${if (text.length > 48) "..." else ""}"
        }
        "start", "status", "screenshot", "stop", "listen_start", "listen_status", "listen_stop" -> "agent_display $action"
        else -> "agent_display $action"
    }.trim()
}

private fun buildAgentModeReplayTimeline(
    context: Context,
    messages: List<ChatMessage>,
): AgentModeReplayTimeline {
    val frames = mutableListOf<AgentModeReplayFrame>()
    val interleavedTextMessageIds = mutableSetOf<String>()
    var firstFrameMessageIndex = -1
    messages.forEachIndexed { index, message ->
        val messageFrames = buildAgentModeReplayFrames(context, message.replayToolInvocations())
        if (messageFrames.isNotEmpty()) {
            if (firstFrameMessageIndex < 0) {
                firstFrameMessageIndex = index
            }
            frames += messageFrames
        }
        if (message.text.isNotBlank() && frames.isNotEmpty() && messages.hasFutureAgentModeFrame(context, index + 1)) {
            frames[frames.lastIndex] = frames.last().copy(overlayText = message.text)
            interleavedTextMessageIds += message.id
        }
    }
    return AgentModeReplayTimeline(
        frames = frames,
        interleavedTextMessageIds = interleavedTextMessageIds,
        firstFrameMessageIndex = firstFrameMessageIndex,
    )
}

private fun List<ChatMessage>.hasFutureAgentModeFrame(context: Context, startIndex: Int): Boolean =
    drop(startIndex).any { message ->
        buildAgentModeReplayFrames(context, message.replayToolInvocations()).isNotEmpty()
    }

private fun ChatMessage.replayToolInvocations(): List<ChatToolInvocation> =
    (toolInvocations + reasoningTrace?.toolInvocations.orEmpty()).distinctBy(ChatToolInvocation::id)

private fun buildAgentModeReplayFrames(
    context: Context,
    toolInvocations: List<ChatToolInvocation>,
): List<AgentModeReplayFrame> = buildList {
    toolInvocations.forEach { invocation ->
        if (
            !invocation.isAgentModeDisplayInvocation() &&
            !invocation.toolName.equals("chrome", ignoreCase = true) &&
            !invocation.toolName.equals("browser", ignoreCase = true)
        ) return@forEach
        val output = parseJsonObject(invocation.outputJson) ?: return@forEach
        if (!output.optBoolean("ok")) return@forEach
        val previewPath = output.optString("preview_path").ifBlank {
            output.optString("previewPath").ifBlank {
                output.optString("screenshot_path")
            }
        }
        if (previewPath.isBlank()) return@forEach
        add(
            AgentModeReplayFrame(
                previewPath = previewPath,
                width = output.optInt("width"),
                height = output.optInt("height"),
                cursorX = output.optionalInt("cursor_x", "cursorX"),
                cursorY = output.optionalInt("cursor_y", "cursorY"),
                overlayText = "",
                completedAtUptimeMillis = invocation.completedAtUptimeMillis ?: invocation.startedAtUptimeMillis,
                toolTitle = formatToolInvocationTitleLabel(
                    context = context,
                    toolInvocation = invocation,
                    isRunningOverride = true,
                    arguments = parseJsonObject(invocation.argumentsJson),
                ),
            )
        )
    }
}

private fun agentModeReplayBackdropBrush(): Brush = Brush.linearGradient(
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

private fun parseJsonObject(rawValue: String): JSONObject? {
    if (rawValue.isBlank()) return null
    return runCatching { JSONObject(rawValue) }.getOrNull()
}

private fun JSONObject.optionalInt(vararg keys: String): Int? =
    keys.firstOrNull { has(it) && !isNull(it) }?.let { optInt(it) }

internal fun highlightBashCommand(command: String): AnnotatedString = buildAnnotatedString {
    appendStyled("$ ", SpanStyle(color = AetherSecondary, fontWeight = FontWeight.SemiBold))

    val tokenPattern = Regex("""\s+|&&|\|\||[|;><()]|"(?:\\.|[^"])*"|'(?:\\.|[^'])*'|\$[A-Za-z_][A-Za-z0-9_]*|--?[A-Za-z0-9][\w-]*|[^\s|;><()]+""")
    var expectsCommand = true

    tokenPattern.findAll(command).forEach { match ->
        val token = match.value
        val style = when {
            token.isBlank() -> null
            token in setOf("|", "||", "&&", ";", ">", "<", "(", ")") -> {
                expectsCommand = true
                SpanStyle(color = AetherOnSurfaceVariant)
            }
            token.startsWith("\"") || token.startsWith("'") -> SpanStyle(color = AetherTertiary)
            token.startsWith("$") -> SpanStyle(color = AetherPrimary)
            token.startsWith("-") -> SpanStyle(color = AetherSecondary)
            token.startsWith("/") || token.startsWith("~/") || token.startsWith("./") || token.startsWith("../") -> SpanStyle(color = AetherSecondary)
            expectsCommand -> {
                expectsCommand = false
                SpanStyle(color = AetherPrimary, fontWeight = FontWeight.SemiBold)
            }
            token.all(Char::isDigit) -> SpanStyle(color = AetherTertiary)
            else -> SpanStyle(color = AetherOnSurface)
        }
        appendStyled(token, style)
    }
}

internal fun highlightToolResult(result: String): AnnotatedString = buildAnnotatedString {
    val tokenPattern = Regex("""\s+|~?/[\w./-]+|\b\d+\b|[A-Za-z_][A-Za-z0-9_]*:|[^\s]+""")

    tokenPattern.findAll(result).forEach { match ->
        val token = match.value
        val lowerToken = token.lowercase()
        val style = when {
            token.isBlank() -> null
            lowerToken.contains("error") ||
                lowerToken.contains("failed") ||
                lowerToken.contains("denied") -> SpanStyle(color = AetherError, fontWeight = FontWeight.Medium)
            lowerToken.startsWith("http://") ||
                lowerToken.startsWith("https://") ||
                token.startsWith("/") ||
                token.startsWith("~/") ||
                token.contains("/") -> SpanStyle(color = AetherSecondary)
            token.all(Char::isDigit) -> SpanStyle(color = AetherTertiary)
            token.endsWith(":") -> SpanStyle(color = AetherOnSurfaceVariant, fontWeight = FontWeight.Medium)
            else -> SpanStyle(color = AetherOnSurface)
        }
        appendStyled(token, style)
    }
}

private fun formatChromeTitle(
    context: Context,
    isRunning: Boolean,
    arguments: JSONObject?,
): String = when (arguments?.optString("action")?.trim()?.lowercase()) {
    "start" -> context.getString(
        if (isRunning) R.string.tool_title_starting_chrome else R.string.tool_title_started_chrome,
    )
    "status" -> context.getString(
        if (isRunning) R.string.tool_title_checking_chrome else R.string.tool_title_checked_chrome,
    )
    "navigate", "open" -> {
        val url = arguments?.optString("url").orEmpty().trim()
        if (url.isBlank()) {
            context.getString(
                if (isRunning) {
                    R.string.tool_title_navigating_chrome
                } else {
                    R.string.tool_title_navigated_chrome
                },
            )
        } else {
            context.getString(
                if (isRunning) {
                    R.string.tool_title_navigating_chrome_url
                } else {
                    R.string.tool_title_navigated_chrome_url
                },
                url.take(72) + if (url.length > 72) "..." else "",
            )
        }
    }
    "tap", "click" -> context.getString(
        if (isRunning) R.string.tool_title_tapping_chrome else R.string.tool_title_tapped_chrome,
    )
    "swipe", "scroll" -> context.getString(
        if (isRunning) R.string.tool_title_scrolling_chrome else R.string.tool_title_scrolled_chrome,
    )
    "text", "type" -> context.getString(
        if (isRunning) R.string.tool_title_typing_chrome else R.string.tool_title_typed_chrome,
    )
    "key" -> {
        val key = arguments?.optString("key").orEmpty().trim()
        if (key.isBlank()) {
            context.getString(
                if (isRunning) R.string.tool_title_pressing_chrome else R.string.tool_title_pressed_chrome,
            )
        } else {
            context.getString(
                if (isRunning) {
                    R.string.tool_title_pressing_chrome_key
                } else {
                    R.string.tool_title_pressed_chrome_key
                },
                key.take(32),
            )
        }
    }
    "back" -> context.getString(
        if (isRunning) R.string.tool_title_going_back_chrome else R.string.tool_title_went_back_chrome,
    )
    "forward" -> context.getString(
        if (isRunning) R.string.tool_title_going_forward_chrome else R.string.tool_title_went_forward_chrome,
    )
    "reload" -> context.getString(
        if (isRunning) R.string.tool_title_reloading_chrome else R.string.tool_title_reloaded_chrome,
    )
    "evaluate", "execute_js", "get_text", "get_page_info", "find_elements", "get_readable", "get_backbone", "wait_for_dom_stable" -> context.getString(
        if (isRunning) R.string.tool_title_evaluating_chrome else R.string.tool_title_evaluated_chrome,
    )
    "screenshot" -> context.getString(
        if (isRunning) R.string.tool_title_capturing_chrome else R.string.tool_title_captured_chrome,
    )
    "stop" -> context.getString(
        if (isRunning) R.string.tool_title_stopping_chrome else R.string.tool_title_stopped_chrome,
    )
    else -> context.getString(
        if (isRunning) R.string.tool_title_using_chrome else R.string.tool_title_used_chrome,
    )
}

private fun summarizeChromeCommand(arguments: JSONObject?): String {
    if (arguments == null) return "chrome"
    val action = arguments.optString("action").trim().ifBlank { "unknown" }
    return when (action.lowercase()) {
        "navigate", "open" -> "browser navigate ${arguments.optString("url").trim()}"
        "tap", "click" -> arguments.optString("selector").trim().ifBlank {
            "browser click x=${arguments.optString("x").trim()} y=${arguments.optString("y").trim()}"
        }.let { value -> if (value.startsWith("browser ")) value else "browser click $value" }
        "swipe", "scroll" -> "browser scroll"
        "key" -> "browser key ${arguments.optString("key").trim()}"
        "text", "type" -> "browser type"
        "evaluate", "execute_js" -> "browser execute_js"
        else -> "browser $action"
    }.trim()
}

internal fun highlightTerminalTranscript(result: String): AnnotatedString = buildAnnotatedString {
    result.lineSequence().forEachIndexed { index, line ->
        if (index > 0) append('\n')
        if (line.startsWith("$ ")) {
            append(highlightBashCommand(line.removePrefix("$ ")))
        } else {
            append(highlightToolResult(line))
        }
    }
}

private fun AnnotatedString.Builder.appendStyled(
    text: String,
    style: SpanStyle?,
) {
    if (style == null) {
        append(text)
        return
    }

    pushStyle(style)
    append(text)
    pop()
}

private fun formatAttachmentSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024f * 1024f))
    bytes >= 1024 -> String.format("%.1f KB", bytes / 1024f)
    else -> "$bytes B"
}

private data class AttachmentTextPreview(
    val text: String,
    val isTruncated: Boolean,
)

private data class ToolInvocationDetail(
    val command: String,
    val result: String?,
)

private data class WebSourceMetadata(
    val domain: String,
    val url: String,
    val faviconUrl: String?,
)

private data class AgentModeReplayFrame(
    val previewPath: String,
    val width: Int,
    val height: Int,
    val cursorX: Int?,
    val cursorY: Int?,
    val overlayText: String,
    val completedAtUptimeMillis: Long,
    val toolTitle: String,
)

private data class AgentModeReplayTimeline(
    val frames: List<AgentModeReplayFrame>,
    val interleavedTextMessageIds: Set<String>,
    val firstFrameMessageIndex: Int,
)
