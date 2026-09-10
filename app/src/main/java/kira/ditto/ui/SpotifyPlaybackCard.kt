package kira.ditto.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kira.ditto.R
import kira.ditto.data.SpotifyMcp
import kira.ditto.data.SpotifyNowPlayingStore
import kira.ditto.ui.theme.AetherIsDark
import kira.ditto.ui.theme.AetherOnSurface
import kira.ditto.ui.theme.AetherOnSurfaceVariant
import kira.ditto.ui.theme.AetherOutlineSoft
import kira.ditto.ui.theme.AetherSurface
import kira.ditto.ui.theme.AetherSurfaceHigh
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.lerp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import org.json.JSONObject

internal val SpotifyGreen = Color(0xFF1DB954)

private val SpotifyOrbPopSpring = spring<Float>(
    dampingRatio = 0.48f,
    stiffness = 620f,
)

private const val SpotifyOrbPopStartScale = 0.12f
private const val SpotifyPlaybackCardAlpha = 0.88f
private val SpotifyCardGray = Color(0xFF808080)
private val SpotifyTimelineBlurRadius = 22.dp
private val SpotifyDockedBlurRadius = 40.dp

internal val LocalSpotifyHazeState = compositionLocalOf<HazeState?> { null }

internal data class SpotifyOverlayHost(
    val overlay: SpotifyOverlayUi,
    val viewportBottom: Float,
    val timelineVisible: Boolean,
    val onActivate: (String) -> Unit,
    val onTimelineVisible: (Boolean) -> Unit,
    val onSetDocked: (Boolean) -> Unit,
    val onSetOrbMenuOpen: (Boolean) -> Unit,
    val onSetOrbUnlocked: (Boolean) -> Unit,
    val onSetOrbOffsetY: (Float) -> Unit,
    val onDestroy: () -> Unit,
    val onDockedCardHeightPx: (Int) -> Unit = {},
)

internal val LocalSpotifyOverlayHost = compositionLocalOf<SpotifyOverlayHost?> { null }

internal val LocalLatestSpotifyPlaybackId = compositionLocalOf { "" }

internal fun ChatToolInvocation.isSpotifyPlaybackCard(): Boolean =
    SpotifyMcp.canonicalToolName(toolName) == "SpotifyPlayback"

internal fun ChatToolInvocation.spotifyPlaybackRecency(): Long {
    val timeline = timelineOrder.takeIf { it > 0L }
    val started = startedAtMillis.takeIf { it > 0L }
    val uptime = startedAtUptimeMillis.takeIf { it > 0L }
    return timeline ?: started ?: uptime ?: 0L
}

internal fun latestSpotifyPlaybackInvocation(
    invocations: List<ChatToolInvocation>,
): ChatToolInvocation? =
    invocations
        .distinctBy(ChatToolInvocation::id)
        .filter { it.isSpotifyPlaybackCard() }
        .maxByOrNull { it.spotifyPlaybackRecency() }

internal fun collectSpotifyPlaybackInvocations(
    invocations: List<ChatToolInvocation>,
): List<ChatToolInvocation> =
    invocations.distinctBy(ChatToolInvocation::id).filter { it.isSpotifyPlaybackCard() }

internal fun collectMessageSpotifyPlaybackInvocations(
    messages: List<ChatMessage>,
): List<ChatToolInvocation> = collectSpotifyPlaybackInvocations(
    messages.flatMap { message ->
        message.toolInvocations + message.reasoningTrace?.toolInvocations.orEmpty()
    },
)

internal fun latestConversationSpotifyPlaybackId(
    messages: List<ChatMessage>,
    pendingToolInvocations: List<ChatToolInvocation>,
    pendingResponseBlocks: List<AssistantResponseBlock>,
): String {
    val pendingFromBlocks = pendingResponseBlocks.flatMap { block ->
        when (block) {
            is AssistantResponseBlock.ToolGroup -> block.toolInvocations
            is AssistantResponseBlock.Reasoning -> block.trace.toolInvocations
            else -> emptyList()
        }
    }
    return latestSpotifyPlaybackInvocation(
        collectMessageSpotifyPlaybackInvocations(messages) + pendingToolInvocations + pendingFromBlocks,
    )?.id.orEmpty()
}

@Composable
internal fun SpotifyPlaybackCardStack(
    invocations: List<ChatToolInvocation>,
    topPadding: Dp = 0.dp,
) {
    val conversationLatest = LocalLatestSpotifyPlaybackId.current
    val live = latestSpotifyPlaybackInvocation(invocations) ?: return
    if (conversationLatest.isNotBlank() && live.id != conversationLatest) return
    SpotifyPlaybackCard(
        toolInvocation = live,
        topPadding = topPadding,
        reportVisibility = true,
    )
}

@Composable
internal fun SpotifyPlaybackCard(
    toolInvocation: ChatToolInvocation,
    topPadding: Dp = 6.dp,
    reportVisibility: Boolean = false,
    collapseToOrb: Boolean = false,
) {
    val host = LocalSpotifyOverlayHost.current
    LaunchedEffect(toolInvocation.id) {
        host?.onActivate?.invoke(toolInvocation.id)
    }
    val snapshot = remember(toolInvocation.outputJson) {
        parseSpotifyPlaybackSnapshot(toolInvocation.outputJson)
    }
    LaunchedEffect(snapshot) {
        SpotifyNowPlayingStore.seedIfEmpty(
            title = snapshot.title,
            artist = snapshot.artist,
            isPlaying = snapshot.isPlaying,
            imageUri = snapshot.imageUri,
            coverPath = snapshot.coverPath,
        )
    }
    SpotifyNowPlayingSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPadding),
        reportVisibility = reportVisibility,
        collapseToOrb = collapseToOrb,
    )
}

@Composable
internal fun SpotifyNowPlayingSurface(
    modifier: Modifier = Modifier,
    reportVisibility: Boolean = false,
    collapseToOrb: Boolean = false,
) {
    val context = LocalContext.current
    val host = LocalSpotifyOverlayHost.current
    val density = LocalDensity.current
    val playing by SpotifyNowPlayingStore.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        SpotifyNowPlayingStore.attach(context)
        SpotifyNowPlayingStore.addUiObserver()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                SpotifyNowPlayingStore.attach(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            SpotifyNowPlayingStore.removeUiObserver()
        }
    }
    val minVisiblePx = with(density) { 24.dp.toPx() }
    val visibilityReporter = host?.onTimelineVisible
    DisposableEffect(reportVisibility) {
        onDispose {
            if (reportVisibility) visibilityReporter?.invoke(false)
        }
    }
    var expanded by remember { mutableStateOf(true) }
    val bodyExpanded = collapseToOrb || expanded
    val chevronRotation by animateFloatAsState(
        targetValue = if (bodyExpanded) 0f else 180f,
        animationSpec = tween(220),
        label = "spotify_header_chevron",
    )
    SpotifyPlaybackCardSurface(
        frostBackdrop = collapseToOrb,
        strongFrost = collapseToOrb,
        modifier = modifier.then(
            if (reportVisibility) {
                Modifier.onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInWindow()
                    val bottomLimit = host?.viewportBottom ?: Float.POSITIVE_INFINITY
                    val visibleTop = bounds.top.coerceAtLeast(0f)
                    val visibleBottom = bounds.bottom.coerceAtMost(bottomLimit)
                    val visibleHeight = (visibleBottom - visibleTop).coerceAtLeast(0f)
                    visibilityReporter?.invoke(visibleHeight >= minVisiblePx)
                }
            } else {
                Modifier
            },
        ),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {
                            if (collapseToOrb) {
                                host?.onSetDocked?.invoke(false)
                            } else {
                                expanded = !expanded
                            }
                        },
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SpotifyMark(
                    modifier = Modifier.size(20.dp),
                    cardMark = true,
                )
                SpotifyBlurTitle(
                    text = if (bodyExpanded) {
                        stringResource(R.string.settings_spotify_mcp_title)
                    } else {
                        playing.title.ifBlank { stringResource(R.string.spotify_now_playing_untitled) }
                    },
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Rounded.KeyboardDoubleArrowLeft,
                    contentDescription = stringResource(
                        if (collapseToOrb || bodyExpanded) R.string.spotify_now_playing_collapse
                        else R.string.spotify_now_playing_expand,
                    ),
                    tint = AetherOnSurfaceVariant,
                    modifier = Modifier
                        .size(20.dp)
                        .graphicsLayer { rotationZ = chevronRotation },
                )
            }
            PreviewCardExpandingBody(expanded = bodyExpanded) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SpotifyCoverArt(
                        cover = playing.cover,
                        coverToken = playing.coverToken,
                        title = playing.title,
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        SpotifyBlurTitle(
                            text = playing.title.ifBlank {
                                stringResource(R.string.spotify_now_playing_untitled)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = AetherOnSurface,
                            maxLines = 2,
                        )
                        val artist = playing.artist
                        if (artist.isNotBlank()) {
                            SpotifyBlurTitle(
                                text = artist,
                                style = MaterialTheme.typography.bodyMedium,
                                color = AetherOnSurfaceVariant,
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            SpotifyNowPlayingStore.requestNotificationAccess(context)
                            SpotifyNowPlayingStore.skipToPrevious()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SkipPrevious,
                            contentDescription = stringResource(R.string.spotify_now_playing_previous),
                            tint = AetherOnSurface,
                        )
                    }
                    IconButton(
                        onClick = {
                            SpotifyNowPlayingStore.requestNotificationAccess(context)
                            SpotifyNowPlayingStore.togglePlayPause()
                        },
                    ) {
                        AnimatedContent(
                            targetState = playing.isPlaying,
                            transitionSpec = { fadeIn() + scaleIn() togetherWith fadeOut() + scaleOut() },
                            label = "spotify_play_pause",
                        ) { isPlaying ->
                            Icon(
                                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = stringResource(
                                    if (isPlaying) R.string.spotify_now_playing_pause
                                    else R.string.spotify_now_playing_play,
                                ),
                                tint = SpotifyGreen,
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            SpotifyNowPlayingStore.requestNotificationAccess(context)
                            SpotifyNowPlayingStore.skipToNext()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SkipNext,
                            contentDescription = stringResource(R.string.spotify_now_playing_next),
                            tint = AetherOnSurface,
                        )
                    }
                }
                if (!playing.hasNotificationAccess) {
                    TextButton(
                        onClick = { SpotifyNowPlayingStore.requestNotificationAccess(context, force = true) },
                    ) {
                        Text(
                            text = stringResource(R.string.spotify_now_playing_enable_media),
                            color = SpotifyGreen,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SpotifyPlaybackCardSurface(
    modifier: Modifier = Modifier,
    frostBackdrop: Boolean = false,
    strongFrost: Boolean = false,
    shape: Shape = RoundedCornerShape(20.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val hazeState = LocalSpotifyHazeState.current
    val dyedSurface = lerp(AetherSurface, SpotifyCardGray, 0.05f)
    val fill = dyedSurface.copy(alpha = SpotifyPlaybackCardAlpha)
    val gaussianState = hazeState.takeIf { frostBackdrop }
    val blurRadius = if (strongFrost) SpotifyDockedBlurRadius else SpotifyTimelineBlurRadius
    val frostTintAlpha = when {
        strongFrost && AetherIsDark -> 0.52f
        strongFrost -> 0.42f
        else -> 0.68f
    }
    Box(
        modifier = modifier
            .clip(shape)
            .then(
                if (gaussianState != null) {
                    Modifier.hazeEffect(state = gaussianState) {
                        blurEnabled = true
                        backgroundColor = dyedSurface
                        this.blurRadius = blurRadius
                        tints = listOf(
                            HazeTint(dyedSurface.copy(alpha = frostTintAlpha)),
                            HazeTint(SpotifyCardGray.copy(alpha = 0.05f)),
                        )
                    }
                } else {
                    Modifier.background(fill)
                },
            )
            .border(0.5.dp, AetherOutlineSoft.copy(alpha = 0.55f), shape),
        content = content,
    )
}

@Composable
internal fun SpotifyBlurTitle(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = AetherOnSurface,
    fontWeight: FontWeight? = null,
    maxLines: Int = 1,
) {
    var rendered by remember { mutableStateOf(text) }
    val blur = remember { Animatable(0f) }
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(text) {
        if (text == rendered) {
            if (alpha.value != 1f) alpha.snapTo(1f)
            if (blur.value != 0f) blur.snapTo(0f)
            return@LaunchedEffect
        }
        coroutineScope {
            launch {
                blur.animateTo(12f, tween(220, easing = FastOutLinearInEasing))
            }
            launch {
                alpha.animateTo(0f, tween(220, easing = FastOutLinearInEasing))
            }
        }
        rendered = text
        blur.snapTo(0f)
        alpha.snapTo(0f)
        alpha.animateTo(1f, tween(260, easing = LinearOutSlowInEasing))
    }
    Text(
        text = rendered,
        modifier = modifier
            .graphicsLayer { this.alpha = alpha.value }
            .then(
                if (blur.value > 0.4f) {
                    Modifier.blur(blur.value.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                } else {
                    Modifier
                },
            ),
        style = style,
        color = color,
        fontWeight = fontWeight,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun SpotifyDockedNowPlayingCard() {
    val host = LocalSpotifyOverlayHost.current ?: return
    val visible = host.overlay.active && host.overlay.docked && !host.timelineVisible
    DisposableEffect(visible) {
        if (!visible) host.onDockedCardHeightPx(0)
        onDispose { host.onDockedCardHeightPx(0) }
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(220)) + expandVertically(
            animationSpec = tween(280),
            expandFrom = Alignment.Bottom,
        ),
        exit = fadeOut(animationSpec = tween(180)) + shrinkVertically(
            animationSpec = tween(240),
            shrinkTowards = Alignment.Bottom,
        ),
    ) {
        SpotifyNowPlayingSurface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .onSizeChanged { host.onDockedCardHeightPx(it.height) },
            collapseToOrb = true,
        )
    }
}

@Composable
internal fun BoxScope.SpotifyNowPlayingOverlay(
    @Suppress("UNUSED_PARAMETER") composerBottomPadding: Dp,
) {
    val host = LocalSpotifyOverlayHost.current ?: return
    val showOrb = host.overlay.active && !host.timelineVisible && !host.overlay.docked
    var mounted by remember { mutableStateOf(showOrb) }
    val pop = remember { Animatable(if (showOrb) SpotifyOrbPopStartScale else 0f) }
    LaunchedEffect(showOrb) {
        if (showOrb) {
            mounted = true
            if (pop.value < 0.05f) pop.snapTo(SpotifyOrbPopStartScale)
            pop.animateTo(1f, SpotifyOrbPopSpring)
        } else if (mounted) {
            pop.animateTo(SpotifyOrbPopStartScale, SpotifyOrbPopSpring)
            mounted = false
            pop.snapTo(0f)
        }
    }
    if (!mounted && !showOrb) return
    Box(modifier = Modifier.align(Alignment.CenterEnd)) {
        SpotifyEdgeOrb(
            overlay = host.overlay,
            popScale = pop.value,
            onDock = { host.onSetDocked(true) },
            onMenuOpen = host.onSetOrbMenuOpen,
            onDestroy = host.onDestroy,
        )
    }
}

@Composable
private fun SpotifyEdgeOrb(
    overlay: SpotifyOverlayUi,
    popScale: Float,
    onDock: () -> Unit,
    onMenuOpen: (Boolean) -> Unit,
    onDestroy: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val menuOpen = overlay.orbMenuOpen
    val currentMenuOpen = rememberUpdatedState(menuOpen)
    val currentOnDock = rememberUpdatedState(onDock)
    val currentOnMenuOpen = rememberUpdatedState(onMenuOpen)
    val defaultExtraPx = with(density) { 72.dp.toPx() }
    val orbPx = with(density) { 48.dp.toPx() }
    val actionPx = with(density) { 40.dp.toPx() }
    val arcDistancePx = with(density) { 52.dp.toPx() }
    Box(
        modifier = Modifier
            .padding(end = 6.dp)
            .size(120.dp)
            .offset {
                IntOffset(
                    x = 0,
                    y = (defaultExtraPx + overlay.orbOffsetY).roundToInt(),
                )
            },
    ) {
        SpotifyActionOrb(
            visible = menuOpen,
            angleDegrees = 220f,
            appearDelayMillis = 0,
            arcDistancePx = arcDistancePx,
            mainOrbPx = orbPx,
            actionPx = actionPx,
            icon = Icons.Rounded.Delete,
            contentDescription = stringResource(R.string.spotify_orb_delete),
            onClick = onDestroy,
        )
        SpotifyActionOrb(
            visible = menuOpen,
            angleDegrees = 150f,
            appearDelayMillis = 55,
            arcDistancePx = arcDistancePx,
            mainOrbPx = orbPx,
            actionPx = actionPx,
            icon = Icons.Rounded.OpenInFull,
            contentDescription = stringResource(R.string.spotify_now_playing_expand),
            onClick = { currentOnDock.value() },
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(48.dp)
                .graphicsLayer {
                    scaleX = popScale
                    scaleY = popScale
                }
                .shadow(6.dp, CircleShape)
                .clip(CircleShape)
                .background(SpotifyGreen)
                .pointerInput(menuOpen) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var longPressed = false
                        val longPressMs = viewConfiguration.longPressTimeoutMillis
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            val held = change.uptimeMillis - down.uptimeMillis
                            if (!longPressed && held >= longPressMs) {
                                longPressed = true
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                currentOnMenuOpen.value(true)
                            }
                            if (!change.pressed) {
                                if (!longPressed && !currentMenuOpen.value) {
                                    currentOnDock.value()
                                } else if (!longPressed && currentMenuOpen.value) {
                                    currentOnMenuOpen.value(false)
                                }
                                break
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            SpotifyMark(
                modifier = Modifier.size(28.dp),
                contentDescription = stringResource(R.string.spotify_now_playing_orb),
            )
        }
    }
}

@Composable
private fun BoxScope.SpotifyActionOrb(
    visible: Boolean,
    angleDegrees: Float,
    appearDelayMillis: Int,
    arcDistancePx: Float,
    mainOrbPx: Float,
    actionPx: Float,
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        if (visible) {
            delay(appearDelayMillis.toLong())
            progress.snapTo(SpotifyOrbPopStartScale)
            progress.animateTo(1f, SpotifyOrbPopSpring)
        } else if (progress.value > 0.001f) {
            progress.animateTo(SpotifyOrbPopStartScale, SpotifyOrbPopSpring)
            progress.snapTo(0f)
        }
    }
    val p = progress.value
    if (p > 0.001f) {
        val radians = Math.toRadians(angleDegrees.toDouble())
        val dx = (cos(radians) * arcDistancePx - (mainOrbPx - actionPx) / 2f).roundToInt()
        val dy = (sin(radians) * arcDistancePx).roundToInt()
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset { IntOffset(dx, dy) }
                .graphicsLayer {
                    scaleX = p
                    scaleY = p
                    alpha = p.coerceIn(0f, 1f)
                }
                .size(40.dp)
                .shadow(4.dp, CircleShape)
                .clip(CircleShape)
                .background(Color(0xE6191914))
                .clickable(enabled = p > 0.85f, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
internal fun SpotifyMark(
    modifier: Modifier = Modifier,
    contentDescription: String? = "Spotify",
    cardMark: Boolean = false,
) {
    Image(
        painter = painterResource(
            if (cardMark) R.drawable.ic_spotify_card else R.drawable.ic_spotify,
        ),
        contentDescription = contentDescription,
        modifier = modifier,
    )
}

@Composable
private fun SpotifyCoverArt(
    cover: Bitmap?,
    coverToken: String,
    title: String,
) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(AetherSurfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(
            targetState = coverToken,
            animationSpec = tween(420),
            label = "spotify_cover",
        ) { token ->
            val frame = remember(token) { mutableStateOf(cover) }
            SideEffect {
                if (token == coverToken) {
                    frame.value = cover
                }
            }
            val bitmap = frame.value?.takeIf { token.isNotBlank() }?.asImageBitmap()
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = title.ifBlank {
                        stringResource(R.string.spotify_now_playing_untitled)
                    },
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Album,
                    contentDescription = null,
                    tint = AetherOnSurfaceVariant,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

private data class SpotifyPlaybackSnapshot(
    val title: String,
    val artist: String,
    val isPlaying: Boolean,
    val imageUri: String,
    val coverPath: String,
)

private fun parseSpotifyPlaybackSnapshot(raw: String): SpotifyPlaybackSnapshot {
    val root = playbackJson(raw)
    val track = root?.optJSONObject("track") ?: JSONObject()
    val status = root?.optString("status").orEmpty()
    val isPlaying = when {
        root?.has("is_playing") == true -> root.optBoolean("is_playing")
        status == "playing" -> true
        status == "paused" -> false
        else -> false
    }
    return SpotifyPlaybackSnapshot(
        title = track.optString("name").orEmpty()
            .ifBlank { root?.optString("name").orEmpty() },
        artist = artistNames(track).ifBlank { artistNames(root) },
        isPlaying = isPlaying,
        imageUri = track.optString("image_uri").ifBlank { root?.optString("image_uri").orEmpty() },
        coverPath = track.optString("cover_path").ifBlank { root?.optString("cover_path").orEmpty() },
    )
}

private fun artistNames(obj: JSONObject?): String {
    if (obj == null) return ""
    obj.optJSONArray("artists")?.let { array ->
        val names = (0 until array.length()).mapNotNull { index ->
            when (val value = array.opt(index)) {
                is JSONObject -> value.optString("name")
                is String -> value
                else -> null
            }?.takeIf { it.isNotBlank() }
        }
        if (names.isNotEmpty()) return names.joinToString(", ")
    }
    return obj.optString("artists").orEmpty()
}

private fun playbackJson(raw: String): JSONObject? {
    if (raw.isBlank()) return null
    val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
    if (root.has("track") || root.has("is_playing") || root.has("cover_path")) return root
    root.optJSONObject("now_playing")?.let { nested ->
        if (nested.has("track") || nested.has("is_playing")) return nested
    }
    root.optJSONObject("structuredContent")?.let { structured ->
        if (structured.has("track") || structured.has("is_playing")) return structured
        structured.optJSONObject("now_playing")?.let { return it }
    }
    val text = root.optJSONArray("content")?.optJSONObject(0)?.optString("text").orEmpty()
    if (text.isBlank()) return root
    val inner = runCatching { JSONObject(text) }.getOrNull()
    if (inner != null && (inner.has("track") || inner.has("is_playing"))) return inner
    return root
}
