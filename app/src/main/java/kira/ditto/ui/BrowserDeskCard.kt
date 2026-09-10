package kira.ditto.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kira.ditto.AetherApplication
import kira.ditto.R
import kira.ditto.browser.BrowserDeskActivity
import kira.ditto.browser.BrowserDeskImage
import kira.ditto.browser.BrowserDeskPreview
import kira.ditto.browser.BrowserDeskPreviewKind
import kira.ditto.browser.BrowserDeskState
import kira.ditto.browser.BrowserDeskMosaicTile
import kira.ditto.browser.browserDeskMosaicTiles
import kira.ditto.browser.BrowserDeskVerb
import kira.ditto.browser.BrowserDesk
import kira.ditto.browser.BrowserTopicRailItem
import kira.ditto.browser.browserDeskCaption
import kira.ditto.browser.browserDeskShowsTopicRail
import kira.ditto.browser.browserTopicRailItems
import kira.ditto.browser.previewPageKey
import kira.ditto.browser.sanitizeBrowserDeskPreviewForCard
import kira.ditto.ui.theme.AetherOnSurface
import kira.ditto.ui.theme.AetherOnSurfaceVariant
import kira.ditto.ui.theme.AetherSurface
import kira.ditto.ui.theme.AetherSurfaceHigh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val BrowserDeskPreviewHeight = 280.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BrowserDeskCard(
    identityKey: String,
    invocations: List<ChatToolInvocation>,
    deskState: BrowserDeskState,
    workspaceDirectory: String,
    allowRootImageRead: Boolean,
    collapsePreview: Boolean = false,
    useMosaic: Boolean = false,
    sessionRunning: Boolean = false,
    onOpenLink: (String) -> Unit = {},
) {
    val names = subagentPersonNames()
    val people = remember(identityKey, invocations, names) {
        browserDeskPeople(identityKey, invocations, names)
    }
    val operator = people.first()
    val running = browserDeskHeaderBusy(
        sessionRunning = sessionRunning,
        invocations = invocations,
        activities = deskState.activities,
    )
    val failed = invocations.any { invocation ->
        !invocation.isRunning && subagentCallFailed(
            parseSubagentLaunch(invocation.toolName, invocation.argumentsJson, invocation.toolKind)
                ?: return@any false,
            invocation.outputJson,
        )
    }
    val swarmInfo = invocations.firstOrNull()?.let { invocation ->
        parseSubagentLaunch(invocation.toolName, invocation.argumentsJson, invocation.toolKind)
    }
    val swarmMembers = remember(invocations) {
        val invocation = invocations.firstOrNull() ?: return@remember emptyList()
        val info = parseSubagentLaunch(invocation.toolName, invocation.argumentsJson, invocation.toolKind)
            ?: return@remember emptyList()
        swarmMemberViews(info, invocation.outputJson)
    }
    val title = browserDeskTitle(
        personName = operator.name,
        running = running,
        failed = failed,
        activities = deskState.activities,
        preview = deskState.preview,
        description = swarmInfo?.description.orEmpty(),
        prompt = swarmInfo?.prompt.orEmpty(),
        swarmCount = if (swarmInfo?.isSwarm == true) {
            people.size.coerceAtLeast(swarmInfo.agentCount)
        } else {
            0
        },
        swarmHeadline = if (swarmInfo?.isSwarm == true) {
            browserSwarmHeadline(swarmInfo.items, swarmInfo.description)
        } else {
            ""
        },
        swarmPeople = people,
        swarmMembers = swarmMembers,
    )
    var expanded by remember(identityKey) { mutableStateOf(true) }
    LaunchedEffect(running, collapsePreview) {
        when (browserDeskForcedExpanded(running = running, collapsePreview = collapsePreview)) {
            true -> expanded = true
            false -> expanded = false
            null -> Unit
        }
    }
    AetherCapsuleBorderedSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            SubagentCollapsedHeader(
                people = people,
                title = title,
                failed = failed,
                isRunning = running,
                onClick = { expanded = !expanded },
                showCollapseChevron = true,
                expanded = expanded,
            )
            PreviewCardExpandingBody(expanded = expanded) {
                BrowserExecutionSummary(deskState, deskState.uiPreviewTopicId)
                val swarmItems = if (invocations.any { it.isCoalescedBrowserSwarm() }) {
                    emptyList()
                } else {
                    swarmInfo?.items.orEmpty()
                }
                val railItems = remember(deskState.previewsByTopic, swarmItems) {
                    browserTopicRailItems(deskState, swarmItems)
                }
                val showRail = browserDeskShowsTopicRail(deskState, swarmItems)
                val shownPreview = deskState.previewsByTopic[deskState.uiPreviewTopicId]
                    ?: deskState.preview
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showRail) {
                        BrowserTopicStageRail(
                            items = railItems,
                            selectedTopicId = deskState.uiPreviewTopicId,
                            workspaceDirectory = workspaceDirectory,
                            allowRootImageRead = allowRootImageRead,
                            modifier = Modifier.height(BrowserDeskPreviewHeight),
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        if (useMosaic) {
                            BrowserDeskMosaic(
                                deskState = deskState.copy(preview = shownPreview),
                                workspaceDirectory = workspaceDirectory,
                                allowRootImageRead = allowRootImageRead,
                                onOpenLink = onOpenLink,
                            )
                        } else {
                            if (deskState.activities.isNotEmpty()) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    deskState.activities.forEach { activity ->
                                        BrowserDeskActivityChip(activity = activity)
                                    }
                                }
                            }
                            BrowserDeskPreviewPane(
                                preview = shownPreview,
                                workspaceDirectory = workspaceDirectory,
                                allowRootImageRead = allowRootImageRead,
                                onOpenLink = onOpenLink,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun browserDeskTitle(
    personName: String,
    running: Boolean,
    failed: Boolean,
    activities: List<BrowserDeskActivity>,
    preview: BrowserDeskPreview,
    description: String,
    prompt: String,
    swarmCount: Int = 0,
    swarmHeadline: String = "",
    swarmPeople: List<SubagentPersonUi> = emptyList(),
    swarmMembers: List<SwarmMemberView> = emptyList(),
): String {
    if (swarmCount > 1) {
        return browserSwarmHeaderTitle(
            personNames = swarmPeople.map { person -> person.name }.ifEmpty { listOf(personName) },
            members = swarmMembers,
            swarmRunning = running,
            swarmCount = swarmCount,
            headline = activities.lastOrNull()?.detail.orEmpty().ifBlank { swarmHeadline },
            failed = failed,
            failedPersonName = personName,
        )
    }
    if (failed && !running) return stringResource(R.string.agent_mode_computer_failed, personName)
    if (!running) return stringResource(R.string.agent_mode_team_task_completed)
    val activity = activities.lastOrNull()
    val verbRes = browserDeskVerbRes(activity?.verb ?: previewKindVerb(preview.kind))
    val label = stringResource(verbRes, personName)
    val detail = browserDeskCaption(
        description = description,
        prompt = prompt,
        deskDetail = activity?.detail.orEmpty(),
        deskTitle = preview.title,
    ).ifBlank { activities.asReversed().firstOrNull { it.detail.isNotBlank() }?.detail.orEmpty() }
    return if (detail.isNotBlank()) "$label · ${subagentDisplayCaption(detail)}" else label
}

@Composable
private fun BrowserDeskActivityChip(activity: BrowserDeskActivity) {
    val verb = stringResource(browserDeskVerbRes(activity.verb), "").trim()
    val detail = activity.detail.trim()
    val label = if (detail.isNotBlank()) "$verb · $detail" else verb
    val searching = activity.verb == BrowserDeskVerb.Searching
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(AetherSurfaceHigh)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = AetherOnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (searching) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = AetherOnSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

private fun browserDeskVerbRes(verb: BrowserDeskVerb?): Int = when (verb) {
    BrowserDeskVerb.Searching -> R.string.browser_desk_searching
    BrowserDeskVerb.Reading -> R.string.browser_desk_reading
    BrowserDeskVerb.Opening -> R.string.browser_desk_opening
    BrowserDeskVerb.Inspecting -> R.string.browser_desk_inspecting
    BrowserDeskVerb.Operating -> R.string.browser_desk_operating
    BrowserDeskVerb.Noting, BrowserDeskVerb.Idle, null -> R.string.browser_desk_noting
}

private fun previewKindVerb(kind: BrowserDeskPreviewKind): BrowserDeskVerb = when (kind) {
    BrowserDeskPreviewKind.Search -> BrowserDeskVerb.Searching
    BrowserDeskPreviewKind.Article -> BrowserDeskVerb.Reading
    BrowserDeskPreviewKind.Images -> BrowserDeskVerb.Searching
    BrowserDeskPreviewKind.Snapshot -> BrowserDeskVerb.Inspecting
    BrowserDeskPreviewKind.Empty -> BrowserDeskVerb.Noting
}

private const val MosaicTileDebounceMs = 280L

@Composable
private fun BrowserTopicStageRail(
    items: List<BrowserTopicRailItem>,
    selectedTopicId: String,
    workspaceDirectory: String,
    allowRootImageRead: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val selected = selectedTopicId.ifBlank { items.firstOrNull()?.topicId.orEmpty() }
    Column(
        modifier = modifier
            .width(92.dp)
            .padding(start = 2.dp, end = 0.dp, top = 10.dp, bottom = 10.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.End,
    ) {
        items.forEach { item ->
            val active = item.topicId == selected
            BrowserTopicStageMini(
                item = item,
                active = active,
                density = density.density,
                workspaceDirectory = workspaceDirectory,
                allowRootImageRead = allowRootImageRead,
            )
        }
    }
}

@Composable
private fun BrowserTopicStageMini(
    item: BrowserTopicRailItem,
    active: Boolean,
    density: Float,
    workspaceDirectory: String,
    allowRootImageRead: Boolean,
) {
    val stacked = item.preview.images.isNotEmpty() &&
        (item.preview.hits.isNotEmpty() || item.preview.body.isNotBlank())
    val tilt = if (active) -12f else -34f
    val scale = if (active) 1.04f else 0.96f
    Box(
        modifier = Modifier
            .width(84.dp)
            .height(104.dp)
            .offset(x = 10.dp)
            .graphicsLayer {
                rotationY = tilt
                cameraDistance = 14f * density
                transformOrigin = TransformOrigin(1f, 0.5f)
                scaleX = scale
                scaleY = scale
                alpha = if (active) 1f else 0.78f
                shadowElevation = if (active) 10f else 6f
            }
            .clickable { BrowserDesk.selectUiTopic(item.topicId) },
        contentAlignment = Alignment.Center,
    ) {
        if (stacked) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = 6.dp, y = 8.dp)
                    .width(62.dp)
                    .height(78.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(AetherSurface.copy(alpha = 0.55f)),
            )
        }
        Box(
            modifier = Modifier
                .width(72.dp)
                .height(96.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(AetherSurface.copy(alpha = if (active) 0.96f else 0.88f))
                .then(
                    if (active) {
                        Modifier.border(
                            1.5.dp,
                            AetherOnSurface.copy(alpha = 0.28f),
                            RoundedCornerShape(16.dp),
                        )
                    } else {
                        Modifier
                    },
                ),
        ) {
            BrowserTopicWindowThumb(
                preview = item.preview,
                topicId = item.topicId,
                workspaceDirectory = workspaceDirectory,
                allowRootImageRead = allowRootImageRead,
            )
        }
    }
}

@Composable
private fun BrowserTopicWindowThumb(
    preview: BrowserDeskPreview,
    topicId: String,
    workspaceDirectory: String,
    allowRootImageRead: Boolean,
) {
    val caption = preview.title.ifBlank { topicId }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(7.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(AetherSurfaceHigh),
        ) {
            when {
                preview.images.isNotEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    BrowserDeskLazyImage(
                        url = preview.images.first().url,
                        alt = preview.images.first().alt,
                        workspaceDirectory = workspaceDirectory,
                        allowRootImageRead = allowRootImageRead,
                        decode = true,
                    )
                }
                preview.hits.isNotEmpty() -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(5.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    preview.hits.take(3).forEach { hit ->
                        if (hit.title.isNotBlank()) {
                            Text(
                                text = hit.title,
                                style = MaterialTheme.typography.labelSmall,
                                color = AetherOnSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                preview.body.isNotBlank() -> Text(
                    text = preview.body,
                    style = MaterialTheme.typography.labelSmall,
                    color = AetherOnSurfaceVariant,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(5.dp),
                )
                else -> Text(
                    text = caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = AetherOnSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(5.dp),
                )
            }
        }
        Text(
            text = caption,
            style = MaterialTheme.typography.labelSmall,
            color = AetherOnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private data class MosaicLayoutFrame(
    val columns: Int,
    val count: Int,
    val tiles: List<BrowserDeskMosaicTile>,
)

@Composable
private fun BrowserDeskMosaic(
    deskState: BrowserDeskState,
    workspaceDirectory: String,
    allowRootImageRead: Boolean,
    onOpenLink: (String) -> Unit,
) {
    val tiles = remember(
        deskState.activities,
        deskState.preview,
    ) { browserDeskMosaicTiles(deskState) }
    var displayed by remember { mutableStateOf(tiles) }
    LaunchedEffect(tiles) {
        if (displayed.isEmpty()) {
            displayed = tiles
            return@LaunchedEffect
        }
        delay(MosaicTileDebounceMs)
        displayed = tiles
    }
    val shown = if (displayed.isEmpty()) tiles else displayed
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(BrowserDeskPreviewHeight)
            .clip(RoundedCornerShape(18.dp))
            .background(browserDeskPreviewBackdropBrush())
            .padding(10.dp),
    ) {
        if (shown.isEmpty()) {
            BrowserDeskMosaicCell(
                tile = BrowserDeskMosaicTile(
                    key = "pending",
                    fullWidth = true,
                    preview = sanitizeBrowserDeskPreviewForCard(deskState.preview)
                        ?: BrowserDeskPreview(),
                ),
                modifier = Modifier.fillMaxSize(),
                workspaceDirectory = workspaceDirectory,
                allowRootImageRead = allowRootImageRead,
                onOpenLink = onOpenLink,
            )
            return@Box
        }
        val columns = if (shown.size == 1) 1 else 2
        val gap = 6.dp
        val frame = MosaicLayoutFrame(columns = columns, count = shown.size, tiles = shown)
        AnimatedContent(
            targetState = frame,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                fadeIn(tween(180)) togetherWith fadeOut(tween(120))
            },
            contentKey = { layout -> "${layout.columns}:${layout.count}" },
            label = "browser-desk-mosaic-layout",
        ) { layout ->
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                layout.tiles.chunked(layout.columns).forEach { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        row.forEach { tile ->
                            key(tile.key) {
                                BrowserDeskMosaicCell(
                                    tile = tile,
                                    modifier = Modifier
                                        .then(
                                            if (row.size == 1) Modifier.fillMaxWidth()
                                            else Modifier.weight(1f),
                                        )
                                        .fillMaxHeight(),
                                    workspaceDirectory = workspaceDirectory,
                                    allowRootImageRead = allowRootImageRead,
                                    onOpenLink = onOpenLink,
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
private fun BrowserDeskMosaicCell(
    tile: BrowserDeskMosaicTile,
    modifier: Modifier,
    workspaceDirectory: String,
    allowRootImageRead: Boolean,
    onOpenLink: (String) -> Unit,
) {
    val activity = tile.activity
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(AetherSurface.copy(alpha = 0.92f))
            .padding(8.dp),
    ) {
        if (activity != null) {
            Crossfade(
                targetState = activity,
                animationSpec = tween(160),
                label = "browser-desk-activity-copy",
            ) { shown ->
                val verb = stringResource(browserDeskVerbRes(shown.verb), "").trim()
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = verb.ifBlank { shown.detail },
                        style = MaterialTheme.typography.labelMedium,
                        color = AetherOnSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (shown.detail.isNotBlank() && shown.detail != verb) {
                        Text(
                            text = shown.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = AetherOnSurfaceVariant,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        } else {
            Crossfade(
                targetState = tile.preview,
                animationSpec = tween(160),
                label = "browser-desk-preview-copy",
            ) { shown ->
                BrowserDeskPreviewBody(
                    preview = shown,
                    workspaceDirectory = workspaceDirectory,
                    allowRootImageRead = allowRootImageRead,
                    onOpenLink = onOpenLink,
                )
            }
        }
    }
}

@Composable
private fun BrowserDeskPreviewPane(
    preview: BrowserDeskPreview,
    workspaceDirectory: String,
    allowRootImageRead: Boolean,
    onOpenLink: (String) -> Unit,
) {
    val paused = LocalChatActivityPaused.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(BrowserDeskPreviewHeight)
            .clip(RoundedCornerShape(18.dp))
            .background(browserDeskPreviewBackdropBrush()),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(AetherSurface.copy(alpha = 0.94f))
                .padding(12.dp),
        ) {
            AnimatedContent(
                targetState = preview,
                transitionSpec = {
                    (slideInVertically(animationSpec = tween(280)) { height -> -height } + fadeIn(tween(180)))
                        .togetherWith(
                            slideOutVertically(animationSpec = tween(240)) { height -> height } + fadeOut(tween(160)),
                        )
                },
                contentKey = { shown -> previewPageKey(shown) },
                label = "browser-desk-preview-page",
            ) { shown ->
                Box(modifier = Modifier.fillMaxSize()) {
                    BrowserDeskPreviewBody(
                        preview = shown,
                        workspaceDirectory = workspaceDirectory,
                        allowRootImageRead = allowRootImageRead,
                        onOpenLink = onOpenLink,
                        decodeImages = !paused,
                    )
                }
            }
        }
    }
}

@Composable
internal fun BrowserDeskPreviewBody(
    preview: BrowserDeskPreview,
    workspaceDirectory: String,
    allowRootImageRead: Boolean,
    onOpenLink: (String) -> Unit,
    decodeImages: Boolean = true,
) {
    when {
        preview.kind == BrowserDeskPreviewKind.Empty -> Text(
            text = stringResource(R.string.chat_chrome_preview_pending),
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
        )
        preview.kind == BrowserDeskPreviewKind.Search -> BrowserDeskHitList(
            preview = preview,
            onOpenLink = onOpenLink,
        )
        preview.kind == BrowserDeskPreviewKind.Article -> BrowserDeskArticle(preview)
        preview.kind == BrowserDeskPreviewKind.Snapshot -> Text(
            text = preview.body,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = AetherOnSurfaceVariant,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        )
        preview.kind == BrowserDeskPreviewKind.Images && preview.images.isNotEmpty() -> BrowserDeskImageStrip(
            images = preview.images,
            workspaceDirectory = workspaceDirectory,
            allowRootImageRead = allowRootImageRead,
            decodeImages = decodeImages,
        )
        else -> BrowserDeskArticle(preview)
    }
}

@Composable
private fun BrowserDeskHitList(
    preview: BrowserDeskPreview,
    onOpenLink: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (preview.title.isNotBlank() && !kira.ditto.browser.isBrowserLeadReminderLeak(preview.title)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = preview.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = AetherOnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = AetherOnSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        preview.hits.forEach { hit ->
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = hit.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AetherOnSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.then(
                        if (hit.url.isBlank()) Modifier else Modifier.clickable { onOpenLink(hit.url) },
                    ),
                )
                if (hit.snippet.isNotBlank()) {
                    Text(
                        text = hit.snippet,
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherOnSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
internal fun BrowserDeskArticle(preview: BrowserDeskPreview) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val heading = preview.title.trim()
        if (heading.isNotBlank() &&
            !kira.ditto.browser.isBrowserLeadReminderLeak(heading) &&
            !kira.ditto.browser.looksLikeDomainLabel(heading, preview.url)
        ) {
            Text(
                text = heading,
                style = MaterialTheme.typography.labelMedium,
                color = AetherOnSurface,
            )
        }
        Text(
            text = preview.body,
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
        )
    }
}

@Composable
internal fun BrowserDeskImageStrip(
    images: List<BrowserDeskImage>,
    workspaceDirectory: String,
    allowRootImageRead: Boolean,
    decodeImages: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        images.take(6).forEach { image ->
            BrowserDeskLazyImage(
                url = image.url,
                alt = image.alt,
                workspaceDirectory = workspaceDirectory,
                allowRootImageRead = allowRootImageRead,
                decode = decodeImages,
            )
        }
    }
}

@Composable
private fun BrowserDeskLazyImage(
    url: String,
    alt: String,
    workspaceDirectory: String,
    allowRootImageRead: Boolean,
    decode: Boolean,
) {
    val context = LocalContext.current
    val appRuntime = remember(context) {
        (context.applicationContext as? AetherApplication)?.runtime
    }
    val cacheKey = "$url|$workspaceDirectory|$allowRootImageRead"
    val bitmap by produceState<ImageBitmap?>(
        initialValue = MarkdownDecodedImageMemory.get(cacheKey)?.bitmap,
        url,
        workspaceDirectory,
        allowRootImageRead,
        decode,
    ) {
        if (!decode) {
            MarkdownDecodedImageMemory.get(cacheKey)?.bitmap?.let { value = it }
            return@produceState
        }
        val cached = MarkdownDecodedImageMemory.get(cacheKey)
        if (cached?.bitmap != null) {
            value = cached.bitmap
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            loadMarkdownImage(
                context = context,
                workspaceFileBridge = appRuntime?.workspaceFileBridge,
                runtimeWorkspaceFileBridge = appRuntime?.runtimeWorkspaceFileBridge,
                rawUrl = url,
                workspaceDirectory = workspaceDirectory,
                allowRootImageRead = allowRootImageRead,
            ).bitmap
        }
    }
    Box(
        modifier = Modifier
            .width(148.dp)
            .height(112.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(AetherSurface),
        contentAlignment = Alignment.Center,
    ) {
        val loaded = bitmap
        if (loaded != null) {
            Image(
                bitmap = loaded,
                contentDescription = alt.takeIf { it.isNotBlank() },
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AetherSurfaceHigh),
            )
        }
    }
}

internal fun browserDeskPeople(
    identityKey: String,
    invocations: List<ChatToolInvocation>,
    names: List<String>,
): List<SubagentPersonUi> {
    val invocation = invocations.firstOrNull()
    val info = invocation?.let { item ->
        parseSubagentLaunch(item.toolName, item.argumentsJson, item.toolKind)
    }
    if (invocation != null && info != null) {
        return subagentPeopleForLaunch(
            identityKey = identityKey,
            toolCallId = invocation.id,
            info = info,
            members = swarmMemberViews(info, invocation.outputJson),
            names = names,
        )
    }
    val occupied = linkedSetOf<String>()
    val seed = phoneSubagentAvatarSeed(
        turnIdentityKey = identityKey,
        toolCallId = identityKey,
        profile = kira.ditto.data.KimiBrowserSubagentProfileName,
        isSwarm = false,
        memberKey = kira.ditto.data.KimiBrowserSubagentProfileName,
        memberIndex = 0,
    )
    return listOf(
        SubagentPersonUi(
            index = 0,
            name = assignPersonName(seed, names, occupied),
            avatar = subagentAvatarSpec(seed),
            member = null,
            crewRole = null,
            sourceToolCallId = invocation?.id,
        ),
    )
}

private fun browserDeskPreviewBackdropBrush(): Brush = Brush.linearGradient(
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
