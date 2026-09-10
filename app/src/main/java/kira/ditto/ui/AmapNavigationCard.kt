package kira.ditto.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kira.ditto.R
import kira.ditto.data.AmapAuth
import kira.ditto.data.AmapNavRoute
import kira.ditto.data.AmapNavigation
import kira.ditto.data.AmapNavigationToolName
import kira.ditto.data.HostSecretStore
import kira.ditto.ui.theme.AetherIsDark
import kira.ditto.ui.theme.AetherOnSurface
import kira.ditto.ui.theme.AetherOnSurfaceVariant
import kira.ditto.ui.theme.AetherOutlineSoft
import kira.ditto.ui.theme.AetherSurfaceHigh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val AmapNavCardShape = RoundedCornerShape(28.dp)
private val AmapNavGlassShape = RoundedCornerShape(24.dp)
private val AmapNavSelectedIcon = Color(0xFF2A7FFF)
private val AmapNavMapFillLight = Color(0xFFDFE8EB)
private val AmapNavCardHeight = 280.dp
private val AmapNavGlassOverlap = 22.dp
private val AmapNavGlassFallback = 88.dp
private const val AmapNavRevealDelayMs = 300L
private val AmapNavDarkMapFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            -0.9f, 0f, 0f, 0f, 255f,
            0f, -0.9f, 0f, 0f, 255f,
            0f, 0f, -0.85f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)

internal fun ChatToolInvocation.isAmapNavigationCard(): Boolean =
    toolName == AmapNavigationToolName ||
        toolName.endsWith("_$AmapNavigationToolName") ||
        toolName.endsWith("__$AmapNavigationToolName")

internal fun collectAmapNavigationInvocations(
    invocations: List<ChatToolInvocation>,
): List<ChatToolInvocation> =
    invocations.distinctBy(ChatToolInvocation::id).filter { it.isAmapNavigationCard() }

internal fun collectMessageAmapNavigationInvocations(
    messages: List<ChatMessage>,
): List<ChatToolInvocation> = collectAmapNavigationInvocations(
    messages.flatMap { message ->
        message.toolInvocations + message.reasoningTrace?.toolInvocations.orEmpty()
    },
)

@Composable
internal fun AmapNavigationCardStack(
    invocations: List<ChatToolInvocation>,
    modifier: Modifier = Modifier,
) {
    val cards = collectAmapNavigationInvocations(invocations)
    if (cards.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        cards.forEach { invocation ->
            AmapNavigationCard(invocation)
        }
    }
}

@Composable
internal fun AmapNavigationCard(
    toolInvocation: ChatToolInvocation,
    modifier: Modifier = Modifier,
) {
    val payload = remember(toolInvocation.outputJson) {
        AmapNavigation.parsePayload(toolInvocation.outputJson)
    }
    val context = LocalContext.current
    val density = LocalDensity.current
    val dark = AetherIsDark
    val available = AmapNavigation.DisplayModeOrder.filter { mode ->
        payload?.routesByMode?.containsKey(mode) == true
    }
    var selectedMode by remember(toolInvocation.id, payload?.preferredMode()) {
        mutableStateOf(payload?.preferredMode().orEmpty())
    }
    val selected = payload?.routesByMode?.get(selectedMode)
        ?: available.firstOrNull()?.let { payload?.routesByMode?.get(it) }
    val mapHaze = remember { HazeState() }
    val mapFill = if (dark) AetherSurfaceHigh else AmapNavMapFillLight
    val selectedPill = if (dark) AetherSurfaceHigh else Color.White
    var glassHeightPx by remember(toolInvocation.id) { mutableIntStateOf(0) }
    var mapSettled by remember(toolInvocation.id) { mutableStateOf(false) }
    var revealed by remember(toolInvocation.id) { mutableStateOf(false) }
    val running = toolInvocation.isRunning
    val apiKey = remember(toolInvocation.id) {
        AmapAuth.snapshot(HostSecretStore(context)).key
    }
    LaunchedEffect(toolInvocation.id, running, mapSettled, payload?.ok, selected?.polyline) {
        if (running) {
            revealed = false
            mapSettled = false
            return@LaunchedEffect
        }
        val finishedWithoutMap = payload?.ok != true ||
            selected?.polyline.isNullOrBlank() ||
            apiKey.isBlank()
        if (mapSettled || finishedWithoutMap) {
            delay(AmapNavRevealDelayMs)
            revealed = true
        }
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(AmapNavCardHeight)
                .clip(AmapNavCardShape)
                .background(mapFill),
        ) {
            val glassFallbackPx = with(density) { AmapNavGlassFallback.roundToPx() }
            val overlapPx = with(density) { AmapNavGlassOverlap.roundToPx() }
            val cardPx = with(density) { AmapNavCardHeight.roundToPx() }
            val glassPx = glassHeightPx.takeIf { it > 0 } ?: glassFallbackPx
            val visiblePx = (cardPx - glassPx + overlapPx).coerceIn(120, cardPx)
            val mapWidth = with(density) { maxWidth.roundToPx() }.coerceIn(100, 1024)
            val mapHeight = visiblePx.coerceIn(100, 1024)
            val mapUrl = remember(
                apiKey,
                selectedMode,
                selected?.polyline,
                payload?.origin,
                payload?.dest,
                mapWidth,
                mapHeight,
                running,
            ) {
                if (running || apiKey.isBlank() || selected == null || selected.polyline.isBlank()) {
                    ""
                } else {
                    AmapNavigation.staticMapUrl(
                        key = apiKey,
                        origin = payload?.origin.orEmpty(),
                        dest = payload?.dest.orEmpty(),
                        polyline = selected?.polyline.orEmpty(),
                        width = mapWidth,
                        height = mapHeight,
                    )
                }
            }
            if (mapUrl.isNotBlank()) {
                AmapNavStaticMap(
                    url = mapUrl,
                    dark = dark,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(with(density) { mapHeight.toDp() })
                        .hazeSource(mapHaze),
                    onSettled = { mapSettled = true },
                )
            }
            if (revealed) {
                CompositionLocalProvider(LocalSpotifyHazeState provides mapHaze) {
                    SpotifyPlaybackCardSurface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
                            .fillMaxWidth()
                            .onSizeChanged { glassHeightPx = it.height },
                        frostBackdrop = true,
                        strongFrost = true,
                        shape = AmapNavGlassShape,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 14.dp, top = 10.dp, end = 14.dp, bottom = 10.dp),
                        ) {
                            AmapNavModeTabs(
                                selectedMode = selectedMode.ifBlank { available.firstOrNull().orEmpty() },
                                available = available.ifEmpty { AmapNavigation.DisplayModeOrder },
                                enabled = available.isNotEmpty(),
                                selectedPill = selectedPill,
                                onSelect = { selectedMode = it },
                            )
                            AnimatedContent(
                                targetState = selectedMode,
                                modifier = Modifier.fillMaxWidth(),
                                transitionSpec = {
                                    amapNavSlideSpec(initialState, targetState)
                                },
                                label = "amap_nav_stats_slide",
                            ) { mode ->
                                AmapNavRouteSummary(
                                    route = payload?.routesByMode?.get(mode) ?: selected,
                                    mode = mode,
                                    running = false,
                                    error = payload?.error.orEmpty(),
                                )
                            }
                        }
                    }
                }
            }
            AmapSkeletonFade(visible = !revealed) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(mapFill),
                ) {
                    AmapSkeletonBar(
                        modifier = Modifier.fillMaxSize(),
                        shape = AmapNavCardShape,
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
                            .fillMaxWidth()
                            .clip(AmapNavGlassShape)
                            .background(mapFill)
                            .padding(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 12.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                repeat(4) {
                                    AmapSkeletonBar(
                                        modifier = Modifier
                                            .height(28.dp)
                                            .weight(1f),
                                        shape = RoundedCornerShape(999.dp),
                                    )
                                }
                            }
                            AmapSkeletonBar(
                                modifier = Modifier
                                    .fillMaxWidth(0.62f)
                                    .height(14.dp),
                            )
                        }
                    }
                }
            }
        }
        Box {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val howto = selected?.summary.orEmpty()
                val destPayload = payload
                if (howto.isNotBlank() && revealed) {
                    SpotifyBlurTitle(
                        text = howto,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AetherOnSurface,
                        maxLines = 4,
                    )
                }
                if (revealed && destPayload != null && destPayload.dest.contains(',')) {
                    AmapPlaceAskCapsule(stringResource(R.string.amap_nav_open_amap)) {
                        if (!navigateAmapPlace(
                                context,
                                destPayload.destPlace(),
                                selectedMode.ifBlank { "driving" },
                            )
                        ) {
                            Toast.makeText(context, R.string.amap_place_open_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            AmapSkeletonFade(visible = !revealed) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AmapSkeletonBar(
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .height(16.dp),
                    )
                    AmapSkeletonBar(
                        modifier = Modifier
                            .fillMaxWidth(0.78f)
                            .height(16.dp),
                    )
                    AmapSkeletonBar(
                        modifier = Modifier
                            .width(132.dp)
                            .height(28.dp),
                        shape = RoundedCornerShape(999.dp),
                    )
                }
            }
        }
    }
}

private fun amapNavSlideSpec(
    initialMode: String,
    targetMode: String,
) = run {
    val from = AmapNavigation.DisplayModeOrder.indexOf(initialMode)
    val to = AmapNavigation.DisplayModeOrder.indexOf(targetMode)
    val forward = to >= from
    val enter = slideInHorizontally(
        animationSpec = tween(280, easing = FastOutSlowInEasing),
    ) { width -> if (forward) width else -width } + fadeIn(tween(180))
    val exit = slideOutHorizontally(
        animationSpec = tween(280, easing = FastOutSlowInEasing),
    ) { width -> if (forward) -width else width } + fadeOut(tween(180))
    enter togetherWith exit
}

@Composable
private fun AmapNavModeTabs(
    selectedMode: String,
    available: List<String>,
    enabled: Boolean,
    selectedPill: Color,
    onSelect: (String) -> Unit,
) {
    val modes = available.ifEmpty { AmapNavigation.DisplayModeOrder }
    val selectedIndex = modes.indexOf(selectedMode).coerceAtLeast(0)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp),
    ) {
        val slot = maxWidth / modes.size.coerceAtLeast(1)
        val indicatorOffset by animateDpAsState(
            targetValue = slot * selectedIndex,
            animationSpec = tween(280, easing = FastOutSlowInEasing),
            label = "amap_nav_tab_pill",
        )
        Box(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .width(slot)
                .fillMaxHeight()
                .padding(horizontal = 2.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(selectedPill),
        )
        Row(modifier = Modifier.fillMaxSize()) {
            modes.forEach { mode ->
                val selected = mode == selectedMode
                val label = stringResource(amapNavModeLabel(mode))
                val interaction = remember(mode) { MutableInteractionSource() }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(999.dp))
                        .clickable(
                            enabled = enabled,
                            indication = null,
                            interactionSource = interaction,
                        ) { onSelect(mode) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    if (selected) {
                        Icon(
                            painter = painterResource(amapNavModeIcon(mode)),
                            contentDescription = null,
                            tint = AmapNavSelectedIcon,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) AetherOnSurface else AetherOnSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun AmapNavRouteSummary(
    route: AmapNavRoute?,
    mode: String,
    running: Boolean,
    error: String,
) {
    val duration = AmapNavigation.formatDuration(route?.durationSeconds ?: 0.0)
    val distance = AmapNavigation.formatDistance(route?.distanceMeters ?: 0.0)
    val lights = route?.trafficLights ?: 0
    val message = when {
        running -> stringResource(R.string.amap_nav_loading)
        error == "gps" -> stringResource(R.string.amap_nav_error_gps)
        error == "destination" -> stringResource(R.string.amap_nav_error_destination)
        error == "key" -> stringResource(R.string.amap_nav_error_key)
        error.isNotBlank() && duration.isBlank() && distance.isBlank() ->
            stringResource(R.string.amap_nav_error_route)
        duration.isBlank() && distance.isBlank() -> stringResource(R.string.amap_nav_empty_route)
        else -> ""
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (message.isNotBlank()) {
            SpotifyBlurTitle(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = AetherOnSurfaceVariant,
            )
        } else {
            if (duration.isNotBlank()) {
                SpotifyBlurTitle(
                    text = duration,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AetherOnSurface,
                )
            }
            if (distance.isNotBlank()) {
                AmapNavStatDivider()
                SpotifyBlurTitle(
                    text = distance,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AetherOnSurface,
                )
            }
            if (mode == "driving" && lights > 0) {
                AmapNavStatDivider()
                Icon(
                    painter = painterResource(R.drawable.ic_amap_nav_traffic_light),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(width = 10.dp, height = 16.dp),
                )
                SpotifyBlurTitle(
                    text = lights.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AetherOnSurface,
                )
            }
        }
    }
}

@Composable
private fun AmapNavStatDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(14.dp)
            .background(AetherOutlineSoft),
    )
}

@Composable
private fun AmapNavStaticMap(
    url: String,
    dark: Boolean,
    modifier: Modifier = Modifier,
    onSettled: () -> Unit,
) {
    val context = LocalContext.current
    val cacheKey = "$url|null|false"
    val image by produceState(
        initialValue = MarkdownDecodedImageMemory.get(cacheKey) ?: MarkdownImageLoadResult(),
        url,
    ) {
        val cached = MarkdownDecodedImageMemory.get(cacheKey)
        if (cached != null) {
            value = cached
            return@produceState
        }
        value = withContext(Dispatchers.IO + NonCancellable) {
            loadMarkdownImage(
                context = context,
                workspaceFileBridge = null,
                runtimeWorkspaceFileBridge = null,
                rawUrl = url,
                workspaceDirectory = null,
                allowRootImageRead = false,
            )
        }
    }
    LaunchedEffect(image.bitmap, image.error) {
        if (image.bitmap != null || image.error != null) onSettled()
    }
    val bitmap = image.bitmap
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.FillBounds,
            colorFilter = if (dark) AmapNavDarkMapFilter else null,
        )
    }
}

private fun amapNavModeLabel(mode: String): Int = when (mode) {
    "transit" -> R.string.amap_nav_mode_transit
    "bicycling" -> R.string.amap_nav_mode_bike
    "walking" -> R.string.amap_nav_mode_walk
    else -> R.string.amap_nav_mode_drive
}

private fun amapNavModeIcon(mode: String): Int = when (mode) {
    "transit" -> R.drawable.ic_amap_nav_transit
    "bicycling" -> R.drawable.ic_amap_nav_bike
    "walking" -> R.drawable.ic_amap_nav_walk
    else -> R.drawable.ic_amap_nav_drive
}
