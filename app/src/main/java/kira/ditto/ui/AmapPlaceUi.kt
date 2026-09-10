package kira.ditto.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kira.ditto.AetherApplication
import kira.ditto.R
import kira.ditto.data.AmapNavigation
import kira.ditto.data.AmapPlace
import kira.ditto.data.amapPlaceAskDishesPrompt
import kira.ditto.data.amapPlaceAskIntroPrompt
import kira.ditto.data.amapPlacesFromToolResults
import kira.ditto.data.isAmapFoodPlace
import kira.ditto.ui.theme.AetherOnSurface
import kira.ditto.ui.theme.AetherOnSurfaceVariant
import kira.ditto.ui.theme.AetherOutlineSoft
import kira.ditto.ui.theme.AetherSurface
import kira.ditto.ui.theme.AetherSurfaceHigh
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal val LocalAmapPlaces = staticCompositionLocalOf { emptyList<AmapPlace>() }

internal val LocalAmapPlaceAsk = staticCompositionLocalOf<(String) -> Unit> { {} }

internal val LocalAmapPlaceNavigateInChat = staticCompositionLocalOf<(AmapPlace) -> Unit> { {} }

internal fun amapPlacesFromInvocations(invocations: List<ChatToolInvocation>): List<AmapPlace> =
    amapPlacesFromToolResults(invocations.map { it.toolName to it.outputJson })

internal fun amapPlacesFromMessages(messages: List<ChatMessage>): List<AmapPlace> =
    amapPlacesFromInvocations(
        messages.flatMap { message ->
            message.toolInvocations + message.reasoningTrace?.toolInvocations.orEmpty()
        },
    )

internal val AmapPlaceReturnIconRes = R.drawable.ic_amap_place_arrow

private const val AmapPlaceCardAlpha = 0.88f
private val AmapCardGray = Color(0xFF808080)
private val AmapPlaceCardShape = RoundedCornerShape(20.dp)
private val AmapPlaceCarouselShape = RoundedCornerShape(22.dp)
private val AmapPlaceCarouselGap = 8.dp
private const val AmapPlaceCarouselVisibleCount = 2.5f
private val AmapStarColor = Color(0xFFFFB400)
private const val AmapPlaceRevealTimeoutMs = 8_000L
private const val AmapPlaceCardGrayMix = 0.05f
private const val AmapPlaceAskCapsuleGrayMix = 0.16f
private val AmapPlaceAskEdgeFade = 10.dp
private const val AmapPlacePhotoRevealDelayMs = 300L

@Composable
internal fun amapPlaceCardFill(): Color =
    lerp(AetherSurface, AmapCardGray, AmapPlaceCardGrayMix).copy(alpha = AmapPlaceCardAlpha)

@Composable
private fun amapPlaceAskCapsuleFill(): Color =
    lerp(AetherSurface, AmapCardGray, AmapPlaceAskCapsuleGrayMix).copy(alpha = AmapPlaceCardAlpha)

internal fun amapPlaceCarouselItemWidth(
    maxWidth: Dp,
    gap: Dp = AmapPlaceCarouselGap,
): Dp = (maxWidth - gap * 2) / AmapPlaceCarouselVisibleCount

internal fun amapPlaceCarouselHeight(
    maxWidth: Dp,
    gap: Dp = AmapPlaceCarouselGap,
): Dp = amapPlaceCarouselItemWidth(maxWidth, gap)

internal fun amapPlaceCarouselUsesExactSquares(photoCount: Int): Boolean = photoCount in 1..2

internal fun AmapPlace.photoLoadsSettled(loads: Map<String, MarkdownImageLoadResult>): Boolean =
    photos.all { url ->
        val result = loads[url]
        result != null && (result.bitmap != null || result.error != null)
    }

@Composable
internal fun AmapPlaceCardCluster(
    places: List<AmapPlace>,
    startIndex: Int,
    modifier: Modifier = Modifier,
) {
    if (places.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        places.forEachIndexed { offset, place ->
            key(place.id) {
                AmapPlaceCard(
                    index = startIndex + offset,
                    place = place,
                )
            }
        }
    }
}

@Composable
internal fun AmapPlaceCard(
    index: Int,
    place: AmapPlace,
    modifier: Modifier = Modifier,
) {
    val photoLoads = remember(place.id, place.photos) { mutableStateMapOf<String, MarkdownImageLoadResult>() }
    place.photos.forEach { url ->
        key(place.id, url) {
            AmapPlacePhotoProbe(url) { result -> photoLoads[url] = result }
        }
    }
    val photosReady = place.photoLoadsSettled(photoLoads)
    var textRevealed by remember(place.id) { mutableStateOf(false) }
    var photosRevealed by remember(place.id) { mutableStateOf(false) }
    LaunchedEffect(place.id, photosReady) {
        if (photosReady) textRevealed = true
    }
    LaunchedEffect(place.id, textRevealed, photosReady) {
        if (!textRevealed || !photosReady) return@LaunchedEffect
        delay(AmapPlacePhotoRevealDelayMs)
        photosRevealed = true
    }
    LaunchedEffect(place.id) {
        delay(AmapPlaceRevealTimeoutMs)
        textRevealed = true
        delay(AmapPlacePhotoRevealDelayMs)
        photosRevealed = true
    }
    val title = if (textRevealed) "$index. ${place.name}" else ""
    val metaBits = buildList {
        if (place.distance.isNotBlank()) add(place.distance)
        if (place.cost.isNotBlank()) add(place.cost)
    }
    val metaText = if (textRevealed) metaBits.joinToString("  ") else ""
    val dishesText = if (textRevealed) place.dishes else ""
    var navigateChooser by remember(place.id) { mutableStateOf(false) }
    AmapPlaceCardSurface(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box {
                        SpotifyBlurTitle(
                            text = title,
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = AetherOnSurface,
                            maxLines = 2,
                        )
                        AmapSkeletonFade(visible = !textRevealed) {
                            AmapSkeletonBar(
                                modifier = Modifier
                                    .fillMaxWidth(0.78f)
                                    .height(18.dp),
                            )
                        }
                    }
                    if (place.rating.isNotBlank() || metaBits.isNotEmpty()) {
                        Box {
                            AmapPlaceMetaRow(
                                rating = if (textRevealed) place.rating else "",
                                metaText = metaText,
                            )
                            AmapSkeletonFade(visible = !textRevealed) {
                                AmapSkeletonBar(
                                    modifier = Modifier
                                        .fillMaxWidth(0.52f)
                                        .height(14.dp),
                                )
                            }
                        }
                    }
                    if (place.dishes.isNotBlank()) {
                        Box {
                            SpotifyBlurTitle(
                                text = dishesText,
                                modifier = Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.bodySmall,
                                color = AetherOnSurfaceVariant,
                            )
                            AmapSkeletonFade(visible = !textRevealed) {
                                AmapSkeletonBar(
                                    modifier = Modifier
                                        .fillMaxWidth(0.64f)
                                        .height(12.dp),
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.padding(start = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_amap_card),
                        contentDescription = stringResource(R.string.amap_place_brand),
                        modifier = Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(5.dp)),
                    )
                    Text(
                        text = stringResource(R.string.amap_place_brand),
                        style = MaterialTheme.typography.labelSmall,
                        color = AetherOnSurface,
                        maxLines = 1,
                    )
                }
            }
            if (place.photos.isNotEmpty()) {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val photoHeight = amapPlaceCarouselHeight(maxWidth)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(photoHeight),
                    ) {
                        if (photosRevealed) {
                            AmapPlacePhotoCarousel(
                                urls = place.photos,
                                loads = photoLoads,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        AmapSkeletonFade(visible = !photosRevealed) {
                            AmapSkeletonBar(
                                modifier = Modifier.fillMaxSize(),
                                shape = AmapPlaceCarouselShape,
                            )
                        }
                    }
                }
            }
            Box {
                AmapPlaceAskRow(
                    place = place,
                    enabled = textRevealed,
                    onNavigateHere = { navigateChooser = true },
                )
                AmapSkeletonFade(visible = !textRevealed) {
                    AmapPlaceAskSkeleton(showDishes = isAmapFoodPlace(place))
                }
            }
        }
    }
    if (navigateChooser) {
        AmapPlaceNavigateSheet(
            place = place,
            onDismiss = { navigateChooser = false },
        )
    }
}

@Composable
private fun AmapPlaceCardSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(AmapPlaceCardShape)
            .background(amapPlaceCardFill())
            .border(0.5.dp, AetherOutlineSoft.copy(alpha = 0.55f), AmapPlaceCardShape),
    ) {
        content()
    }
}

@Composable
internal fun AmapSkeletonFade(
    visible: Boolean,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(220, easing = FastOutLinearInEasing)),
    ) {
        content()
    }
}

@Composable
internal fun AmapSkeletonBar(
    modifier: Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
) {
    val shift by rememberInfiniteTransition(label = "amap_skeleton").animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "amap_skeleton_shift",
    )
    val fill = amapPlaceCardFill()
    val highlight = AetherOnSurface.copy(alpha = 0.12f)
    Box(
        modifier = modifier
            .clip(shape)
            .background(fill)
            .background(
                Brush.linearGradient(
                    colors = listOf(Color.Transparent, highlight, Color.Transparent),
                    start = Offset(shift * 420f, 0f),
                    end = Offset(shift * 420f + 220f, 0f),
                ),
            ),
    )
}

@Composable
private fun AmapPlaceAskSkeleton(showDishes: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(if (showDishes) 3 else 2) {
            AmapSkeletonBar(
                modifier = Modifier
                    .height(28.dp)
                    .weight(1f),
                shape = RoundedCornerShape(999.dp),
            )
        }
    }
}

@Composable
private fun AmapPlaceAskRow(
    place: AmapPlace,
    enabled: Boolean,
    onNavigateHere: () -> Unit,
) {
    val ask = LocalAmapPlaceAsk.current
    val scroll = rememberScrollState()
    AnimatedVisibility(
        visible = enabled,
        enter = fadeIn(tween(260, easing = LinearOutSlowInEasing)),
        exit = fadeOut(tween(120)),
    ) {
        val overflow = scroll.maxValue > 0
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .amapPlaceHorizontalEdgeFade(overflow)
                .horizontalScroll(scroll),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AmapPlaceAskCapsule(stringResource(R.string.amap_place_navigate_here), onNavigateHere)
            if (isAmapFoodPlace(place)) {
                AmapPlaceAskCapsule(stringResource(R.string.amap_place_ask_dishes)) {
                    ask(amapPlaceAskDishesPrompt(place))
                }
            }
            AmapPlaceAskCapsule(stringResource(R.string.amap_place_ask_intro)) {
                ask(amapPlaceAskIntroPrompt(place))
            }
        }
    }
}

private fun Modifier.amapPlaceHorizontalEdgeFade(
    enabled: Boolean,
    fade: Dp = AmapPlaceAskEdgeFade,
): Modifier {
    if (!enabled) return this
    return graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val stop = (fade.toPx() / size.width.coerceAtLeast(1f)).coerceIn(0f, 0.45f)
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Transparent,
                    stop to Color.White,
                    1f - stop to Color.White,
                    1f to Color.Transparent,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
}

@Composable
private fun AmapPlaceChooserButton(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(999.dp))
            .background(amapPlaceAskCapsuleFill())
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = AetherOnSurface,
            maxLines = 1,
        )
    }
}

@Composable
internal fun AmapPlaceAskCapsule(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(amapPlaceAskCapsuleFill())
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            painter = painterResource(AmapPlaceReturnIconRes),
            contentDescription = null,
            tint = AetherOnSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = AetherOnSurface,
            maxLines = 1,
        )
    }
}

@Composable
private fun AmapPlaceMetaRow(
    rating: String,
    metaText: String,
) {
    if (rating.isBlank() && metaText.isBlank()) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (rating.isNotBlank()) {
            Icon(
                imageVector = Icons.Rounded.Star,
                contentDescription = null,
                tint = AmapStarColor,
                modifier = Modifier.size(14.dp),
            )
            SpotifyBlurTitle(
                text = rating,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = AetherOnSurface,
                modifier = Modifier.padding(start = 2.dp, end = 8.dp),
            )
        }
        if (metaText.isNotBlank()) {
            SpotifyBlurTitle(
                text = metaText,
                style = MaterialTheme.typography.bodySmall,
                color = AetherOnSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AmapPlacePhotoCarousel(
    urls: List<String>,
    loads: Map<String, MarkdownImageLoadResult>,
    modifier: Modifier = Modifier,
) {
    if (urls.isEmpty()) return
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val gap = AmapPlaceCarouselGap
        val itemWidth = amapPlaceCarouselItemWidth(maxWidth, gap)
        val carouselHeight = amapPlaceCarouselHeight(maxWidth, gap)
        if (amapPlaceCarouselUsesExactSquares(urls.size)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(carouselHeight),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                urls.forEach { url ->
                    AmapPlacePhoto(
                        result = loads[url],
                        modifier = Modifier
                            .size(itemWidth)
                            .clip(AmapPlaceCarouselShape),
                    )
                }
            }
        } else {
            val carouselState = rememberCarouselState { urls.size.coerceAtLeast(1) }
            HorizontalMultiBrowseCarousel(
                state = carouselState,
                preferredItemWidth = itemWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(carouselHeight),
                itemSpacing = gap,
            ) { index ->
                val url = urls[index]
                AmapPlacePhoto(
                    result = loads[url],
                    modifier = Modifier
                        .fillMaxSize()
                        .maskClip(AmapPlaceCarouselShape),
                )
            }
        }
    }
}

@Composable
private fun AmapPlacePhotoProbe(
    url: String,
    onResult: (MarkdownImageLoadResult) -> Unit,
) {
    val context = LocalContext.current
    val cacheKey = "$url||false"
    val imageState by produceState(
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
                runtimeWorkspaceFileBridge = (context.applicationContext as? AetherApplication)
                    ?.runtime?.runtimeWorkspaceFileBridge,
                rawUrl = url,
                workspaceDirectory = null,
                allowRootImageRead = false,
            )
        }
    }
    LaunchedEffect(imageState) {
        onResult(imageState)
    }
}

@Composable
private fun AmapPlacePhoto(
    result: MarkdownImageLoadResult?,
    modifier: Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when {
            result?.bitmap != null -> Image(
                bitmap = result.bitmap!!,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            result?.error != null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AetherSurfaceHigh),
            )
            else -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AetherSurfaceHigh),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AmapPlaceNavigateSheet(
    place: AmapPlace,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val openInChat = LocalAmapPlaceNavigateInChat.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 640.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, top = 36.dp, end = 24.dp, bottom = 30.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = stringResource(R.string.amap_place_navigate_here),
                    style = MaterialTheme.typography.labelMedium,
                    color = AetherOnSurfaceVariant,
                )
                Text(
                    text = place.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = AetherOnSurface,
                )
                if (place.address.isNotBlank()) {
                    Text(
                        text = place.address,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AetherOnSurfaceVariant,
                    )
                }
                AmapPlaceChooserButton(stringResource(R.string.amap_place_open_app)) {
                    if (!navigateAmapPlace(context, place)) {
                        Toast.makeText(context, R.string.amap_place_open_failed, Toast.LENGTH_SHORT).show()
                    }
                    onDismiss()
                }
                AmapPlaceChooserButton(stringResource(R.string.amap_place_open_in_chat)) {
                    openInChat(place)
                    onDismiss()
                }
            }
            AetherSheetDragHandleScrim()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AmapPlaceActionDialog(
    place: AmapPlace,
    onDismiss: () -> Unit,
    onCite: (AmapPlace) -> Unit,
) {
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 640.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, top = 36.dp, end = 24.dp, bottom = 30.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = place.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = AetherOnSurfaceVariant,
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(AetherSurfaceHigh)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    val meta = listOfNotNull(
                        place.rating.takeIf { it.isNotBlank() }?.let {
                            stringResource(R.string.amap_place_rating, it)
                        },
                        place.cost.takeIf { it.isNotBlank() }?.let {
                            stringResource(R.string.amap_place_cost, it)
                        },
                        place.dishes.takeIf { it.isNotBlank() }?.let {
                            stringResource(R.string.amap_place_dishes, it)
                        },
                        place.distance.takeIf { it.isNotBlank() },
                    ).joinToString(" · ")
                    if (meta.isNotBlank()) {
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.bodyMedium,
                            color = AetherOnSurfaceVariant,
                        )
                    }
                    if (place.address.isNotBlank()) {
                        Text(
                            text = place.address,
                            style = MaterialTheme.typography.bodyMedium,
                            color = AetherOnSurfaceVariant,
                        )
                    }
                    AmapPlaceActionRow(stringResource(R.string.amap_place_open_app)) {
                        if (!openAmapPlace(context, place)) {
                            Toast.makeText(context, R.string.amap_place_open_failed, Toast.LENGTH_SHORT).show()
                        }
                        onDismiss()
                    }
                    AmapPlaceActionRow(stringResource(R.string.amap_place_navigate)) {
                        if (!navigateAmapPlace(context, place)) {
                            Toast.makeText(context, R.string.amap_place_open_failed, Toast.LENGTH_SHORT).show()
                        }
                        onDismiss()
                    }
                    AmapPlaceActionRow(stringResource(R.string.amap_place_cite)) {
                        citeAmapPlace(context, place)
                        onCite(place)
                        onDismiss()
                    }
                }
            }
            AetherSheetDragHandleScrim()
        }
    }
}

@Composable
private fun AmapPlaceActionRow(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        color = AetherOnSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    )
}

internal fun openAmapPlace(context: Context, place: AmapPlace): Boolean {
    val encoded = enc(place.name)
    val uris = buildList {
        if (place.id.isNotBlank()) {
            add("amapuri://poi/detail?poiid=${enc(place.id)}")
        }
        val lat = place.lat
        val lng = place.lng
        if (lat != null && lng != null) {
            add("androidamap://viewMap?sourceApplication=aether&poiname=$encoded&lat=$lat&lon=$lng&dev=0")
            add("amapuri://viewMap?sourceApplication=aether&poiname=$encoded&lat=$lat&lon=$lng&dev=0")
        }
        add("androidamap://poi?sourceApplication=aether&keywords=$encoded")
        add("amapuri://poi?sourceApplication=aether&keywords=$encoded")
    }
    return launchAmapUris(context, uris)
}

internal fun navigateAmapPlace(
    context: Context,
    place: AmapPlace,
    mode: String = "driving",
): Boolean {
    val lat = place.lat
    val lng = place.lng
    if (lat == null || lng == null) return openAmapPlace(context, place)
    val encoded = enc(place.name)
    val travel = AmapNavigation.amapUriTravelType(mode)
    val uris = listOf(
        "androidamap://route/plan/?sourceApplication=aether&dlat=$lat&dlon=$lng&dname=$encoded&dev=0&t=$travel",
        "amapuri://route/plan/?dlat=$lat&dlon=$lng&dname=$encoded&dev=0&t=$travel",
        "androidamap://navi?sourceApplication=aether&poiname=$encoded&lat=$lat&lon=$lng&dev=0&style=2",
    )
    return launchAmapUris(context, uris)
}

internal fun citeAmapPlace(context: Context, place: AmapPlace) {
    val text = listOf(place.name, place.address).filter { it.isNotBlank() }.joinToString(" · ")
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText(place.name, text))
    Toast.makeText(context, R.string.amap_place_cited, Toast.LENGTH_SHORT).show()
}

private fun launchAmapUris(context: Context, uris: List<String>): Boolean {
    val app = context.applicationContext
    uris.forEach { raw ->
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return@forEach
        val view = Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val packaged = Intent(view).setPackage("com.autonavi.minimap")
        val launched = runCatching {
            if (packaged.resolveActivity(app.packageManager) != null) {
                app.startActivity(packaged)
                true
            } else if (view.resolveActivity(app.packageManager) != null) {
                app.startActivity(view)
                true
            } else {
                false
            }
        }.getOrDefault(false)
        if (launched) return true
    }
    return false
}

private fun enc(value: String): String =
    URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
