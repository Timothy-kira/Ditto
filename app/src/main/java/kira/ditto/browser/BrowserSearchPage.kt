package kira.ditto.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kira.ditto.runtime.DittoWebSearchHit
import kira.ditto.ui.BrowserConversationChrome
import kira.ditto.ui.BrowserPreviewHost
import kira.ditto.ui.ConversationAssistantGroupBubble
import kira.ditto.ui.LocalBrowserConversationChrome
import kira.ditto.ui.LocalBrowserPreviewHost
import kira.ditto.ui.assistantMessagesForPendingBlocks
import kira.ditto.ui.theme.AetherBackground
import kira.ditto.ui.theme.AetherOnSurface
import kira.ditto.ui.theme.AetherOnSurfaceVariant
import kira.ditto.ui.theme.AetherOutlineSoft
import kira.ditto.ui.theme.AetherPrimary
import kira.ditto.ui.theme.AetherSurfaceHigh

internal enum class BrowserSearchTab(val label: String, val icon: ImageVector) {
    Answer("答案", Icons.Rounded.AutoAwesome),
    Links("链接", Icons.Rounded.Public),
    Images("图片", Icons.Rounded.Image),
}

internal data class BrowserSearchState(
    val query: String = "",
    val tab: BrowserSearchTab = BrowserSearchTab.Links,
    val loadingLinks: Boolean = false,
    val loadingImages: Boolean = false,
    val links: List<DittoWebSearchHit> = emptyList(),
    val images: List<BrowserImageHit> = emptyList(),
    val answer: BrowserAnswerState = BrowserAnswerState(),
)

@Composable
internal fun BrowserSearchPage(
    state: BrowserSearchState,
    onTab: (BrowserSearchTab) -> Unit,
    onOpen: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(AetherBackground)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrowserSearchTab.entries.forEach { tab ->
                SearchTabButton(tab = tab, selected = tab == state.tab, onClick = { onTab(tab) })
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(AetherOutlineSoft),
        )
        when (state.tab) {
            BrowserSearchTab.Answer -> AnswerResult(state.answer, onOpen)
            BrowserSearchTab.Links -> LinkResults(state, onOpen)
            BrowserSearchTab.Images -> ImageResults(state, onOpen)
        }
    }
}

@Composable
private fun SearchTabButton(tab: BrowserSearchTab, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.padding(top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = tab.icon,
                contentDescription = null,
                tint = if (selected) AetherOnSurface else AetherOnSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = tab.label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) AetherOnSurface else AetherOnSurfaceVariant,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(if (selected) AetherOnSurface else androidx.compose.ui.graphics.Color.Transparent),
        )
    }
}

@Composable
private fun LinkResults(state: BrowserSearchState, onOpen: (String) -> Unit) {
    if (state.loadingLinks && state.links.isEmpty()) {
        LoadingBody()
        return
    }
    if (state.links.isEmpty()) {
        EmptyBody("没有找到结果")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Row(
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "搜索结果：",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AetherOnSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = state.query,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AetherOnSurface,
                )
            }
        }
        items(state.links, key = { it.url }) { hit ->
            LinkResultRow(hit = hit, onOpen = onOpen)
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun LinkResultRow(hit: DittoWebSearchHit, onOpen: (String) -> Unit) {
    val site = hit.siteName.ifBlank { addressLabel(hit.url) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(hit.url) }
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SiteMark(site)
            Spacer(Modifier.width(10.dp))
            Text(
                text = site,
                style = MaterialTheme.typography.bodyMedium,
                color = AetherOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = hit.url,
            style = MaterialTheme.typography.bodySmall,
            color = AetherOnSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 38.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = hit.title,
            style = MaterialTheme.typography.titleSmall,
            color = AetherPrimary,
            fontWeight = FontWeight.Medium,
        )
        if (hit.snippet.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = hit.snippet,
                style = MaterialTheme.typography.bodyMedium,
                color = AetherOnSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The reference draws each site's favicon. Fetching one per result would be a second network
 * round trip per row on a page that already cost one; the site's initial carries the same
 * "these are different sources" signal for free.
 */
@Composable
private fun SiteMark(site: String) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(AetherSurfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = site.takeIf { it.isNotBlank() }?.take(1)?.uppercase().orEmpty(),
            style = MaterialTheme.typography.labelLarge,
            color = AetherOnSurface,
        )
    }
}

@Composable
private fun ImageResults(state: BrowserSearchState, onOpen: (String) -> Unit) {
    if (state.loadingImages && state.images.isEmpty()) {
        LoadingBody()
        return
    }
    if (state.images.isEmpty()) {
        EmptyBody("没有找到图片")
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        gridItems(state.images) { hit ->
            AsyncImage(
                model = hit.imageUrl,
                contentDescription = hit.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AetherSurfaceHigh)
                    .clickable { onOpen(hit.pageUrl.ifBlank { hit.imageUrl }) },
            )
        }
    }
}

/**
 * Ditto's answer, drawn by the same bubble the main conversation uses. The browser chrome
 * composition locals are pinned to "off" so none of the browser preview cards — which exist to
 * show what the agent is browsing — come along into a surface that is itself the browser.
 */
@Composable
private fun AnswerResult(answer: BrowserAnswerState, onOpen: (String) -> Unit) {
    if (answer.failure.isNotBlank()) {
        EmptyBody(answer.failure)
        return
    }
    if (!answer.hasContent) {
        if (answer.running) LoadingBody() else EmptyBody("正在准备答案")
        return
    }
    val messages = assistantMessagesForPendingBlocks(
        blocks = answer.blocks,
        responseGroupId = answer.responseGroupId,
        messageIdPrefix = answer.messageIdPrefix,
    )
    if (messages.isEmpty()) {
        LoadingBody()
        return
    }
    CompositionLocalProvider(
        LocalBrowserConversationChrome provides BrowserConversationChrome(),
        LocalBrowserPreviewHost provides BrowserPreviewHost(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        ) {
            item {
                ConversationAssistantGroupBubble(
                    messages = messages,
                    actionsEnabled = false,
                    workspaceDirectory = null,
                    onOpenAttachment = {},
                    onOpenLink = onOpen,
                    onCopy = {},
                    onRedo = {},
                    onDelete = {},
                    isStreaming = answer.running,
                )
            }
        }
    }
}

@Composable
private fun LoadingBody() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = AetherPrimary, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun EmptyBody(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = AetherOnSurfaceVariant,
        )
    }
}
