package kira.ditto.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.view.GestureDetector
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.ViewCompat
import kira.ditto.AetherApplication
import kira.ditto.R
import kira.ditto.data.AmapBrowserUserAgent
import kira.ditto.browser.BrowserActPrefix
import kira.ditto.browser.BrowserLoginContinueUserText
import kira.ditto.browser.BrowserResumePrefix
import kira.ditto.browser.browserActFollowUpUserText
import kira.ditto.browser.SuggestMarker
import kira.ditto.browser.SuggestPrefix
import kira.ditto.browser.parseBrowserActMarker
import kira.ditto.browser.suggestMarkersIn
import kira.ditto.browser.parseBrowserResumeKind
import kira.ditto.data.AmapCdnReferer
import kira.ditto.data.AmapPlace
import kira.ditto.data.KnowledgeCitation
import kira.ditto.data.LocalRuntimeId
import kira.ditto.data.prepareAmapPlaceAnswer
import kira.ditto.data.looksLikeAmapCdn
import kira.ditto.data.parseAmapPlaceCardsMarker
import kira.ditto.data.parseAmapPlaceMarker
import kira.ditto.data.parseAmapPlaceUrl
import kira.ditto.data.RuntimeWorkspaceFileBridge
import kira.ditto.data.WorkspaceFileBridge
import kira.ditto.data.extractMarkdownWebCitations
import kira.ditto.data.knowledgeCitationChipLabel
import kira.ditto.data.knowledgeCitationUrl
import kira.ditto.data.markdownSourceHost
import kira.ditto.data.markdownSourceHostLabel
import kira.ditto.data.mergeKnowledgeCitations
import kira.ditto.data.parseKnowledgeCitationMarker
import kira.ditto.data.parseKnowledgeCitationUrl
import kira.ditto.data.resolveWorkspaceRuntimeId
import kira.ditto.data.stripKnowledgeSourceAttributions
import kira.ditto.data.stripMarkdownWebSourceSection
import kira.ditto.ui.theme.AetherOnSurface
import kira.ditto.ui.theme.AetherOnSurfaceVariant
import kira.ditto.ui.theme.AetherOutlineSoft
import kira.ditto.ui.theme.AetherPrimary
import kira.ditto.ui.theme.AetherSurface
import kira.ditto.ui.theme.AetherSurfaceHigh
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private const val LinkAnnotationTag = "url"
private val LocalMarkdownCitationLabels = staticCompositionLocalOf { emptyMap<Int, String>() }
private const val MaxMarkdownImageBytes = 8 * 1024 * 1024
private const val MarkdownHtmlBridgeName = "AetherMarkdownHtmlBridge"
private const val MermaidScriptUrl = "https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.min.js"
private const val KatexCssUrl = "https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.css"
private const val KatexScriptUrl = "https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.js"
private const val KatexAutoRenderScriptUrl = "https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/contrib/auto-render.min.js"
private const val MarkdownHtmlBaseUrl = "https://localhost/"
internal const val DefaultImageMinHeightDp = 160
internal const val DefaultImageMaxHeightDp = 420
internal const val DefaultMermaidMinHeightDp = 220
internal const val DefaultMermaidMaxHeightDp = 640
private const val DefaultTextBlockMinHeightDp = 24
private const val DefaultTextBlockMaxHeightDp = 2048
private const val PreviewDialogMinHeightDp = 320
private const val PreviewDialogMaxHeightDp = 820
// Below this, a full parse is comfortably under a frame, and going async would only add
// a blank frame plus a dispatch.
private const val SynchronousMarkdownParseLimit = 4_000
private const val MarkdownCodeFenceMaxLines = 400
private const val MarkdownCodeFenceMaxChars = 20_000
private const val MarkdownCodeFencePreviewLines = 60
private val MarkdownCodeFenceMaxHeight = 4_000.dp
private const val MarkdownTablePageRows = 80
private val MarkdownTableMinColumnWidth = 128.dp
private val MarkdownTableDescriptionMinColumnWidth = 160.dp
private val MarkdownTableScrollableColumnWidth = 148.dp
private val MarkdownImageHttpClient: OkHttpClient by lazy {
    kira.ditto.data.AetherHttp.derive {
        followRedirects(true)
        followSslRedirects(true)
        callTimeout(20, TimeUnit.SECONDS)
    }
}

data class MarkdownFadeSpan(
    val sourceRange: IntRange,
    val alpha: Float = 1f,
    val mask: CharacterFadeMask? = null,
)

internal data class MarkdownSourceText(
    val text: String,
    val sourceOffset: Int,
)

private data class MarkdownLine(
    val text: String,
    val startOffset: Int,
)

internal data class MarkdownImageSpec(
    val altText: String,
    val url: String,
    val layout: MarkdownMediaLayout = defaultMarkdownImageLayout(),
    val sourceUrl: String? = null,
)

internal fun markdownImageSourceCitation(
    image: MarkdownImageSpec,
    citations: List<KnowledgeCitation>,
): KnowledgeCitation? {
    if (citations.isEmpty()) return null
    val hosts = listOfNotNull(image.sourceUrl, image.url)
        .map(::markdownSourceHost)
        .filter { it.isNotBlank() }
        .toSet()
    if (hosts.isEmpty()) return null
    return citations.firstOrNull { citation ->
        val host = markdownSourceHost(citation.url)
        host.isNotBlank() && host in hosts
    }
}

internal fun markdownImageSourceBadgeLabel(
    image: MarkdownImageSpec,
    citations: List<KnowledgeCitation>,
): String {
    val citation = markdownImageSourceCitation(image, citations)
    if (citation != null) return citation.index.toString()
    return markdownSourceHost(image.sourceUrl ?: image.url)
}

internal data class MarkdownImageLoadResult(
    val bitmap: ImageBitmap? = null,
    val html: String? = null,
    val error: String? = null,
    val unavailable: Boolean = false,
) {
    val usable: Boolean get() = bitmap != null || html != null
}

internal fun markdownBitmapTooSmall(bitmap: ImageBitmap, minEdgePx: Int = 240): Boolean {
    val min = minOf(bitmap.width, bitmap.height)
    val max = maxOf(bitmap.width, bitmap.height)
    if (min < minEdgePx) return true
    return max > 0 && min.toFloat() / max < 0.18f
}

private fun rejectLowQualityBrowserImage(result: MarkdownImageLoadResult): MarkdownImageLoadResult {
    val bitmap = result.bitmap ?: return result
    if (!markdownBitmapTooSmall(bitmap)) return result
    return MarkdownImageLoadResult(error = "too small", unavailable = true)
}

internal fun isMarkdownImageUnavailable(result: MarkdownImageLoadResult): Boolean {
    if (result.usable) return false
    if (result.unavailable) return true
    return looksLikeMissingMarkdownImageError(result.error.orEmpty())
}

internal fun looksLikeMissingMarkdownImageError(error: String): Boolean {
    if (error.isBlank()) return false
    return error.contains("404") ||
        error.contains("410") ||
        Regex("""HTTP\s*4\d\d""", RegexOption.IGNORE_CASE).containsMatchIn(error)
}

private data class MarkdownAutoLinkMatch(
    val displayText: String,
    val targetUrl: String,
)

private data class MarkdownImageBinary(
    val bytes: ByteArray,
    val mimeType: String? = null,
)

internal data class MarkdownMediaLayout(
    val width: MarkdownMediaWidth? = null,
    val heightDp: Int? = null,
    val minHeightDp: Int? = null,
    val maxHeightDp: Int? = null,
    val fit: MarkdownMediaFit = MarkdownMediaFit.Contain,
    val scroll: Boolean = false,
    val showAll: Boolean = false,
)

internal sealed interface MarkdownMediaWidth {
    data class Fraction(val value: Float) : MarkdownMediaWidth
    data class DpValue(val value: Int) : MarkdownMediaWidth
}

internal enum class MarkdownMediaFit {
    Contain,
    Cover,
}

internal data class MarkdownMermaidSpec(
    val code: MarkdownSourceText,
    val layout: MarkdownMediaLayout = defaultMarkdownMermaidLayout(),
)

internal data class MarkdownCodeFenceHeader(
    val language: String,
    val attributes: Map<String, String>,
)

/**
 * Resolves markdown source into blocks with as little work on the composition thread as
 * possible: streamed text goes through the incremental parser (which only re-parses the
 * unstable tail), short documents are parsed inline against a shared cache, and long ones
 * are parsed on [Dispatchers.Default] so scrolling never waits on a full re-parse.
 */
@Composable
internal fun rememberMarkdownBlocks(
    markdown: String,
    streaming: Boolean,
): List<MarkdownBlock> {
    val incrementalParser = remember { IncrementalMarkdownParser() }
    // While the transcript sits behind an overlay, hold the last parsed text: streaming
    // updates would otherwise keep re-parsing into a surface nobody can see.
    val paused = LocalChatActivityPaused.current
    val lastVisibleMarkdown = remember { mutableStateOf(markdown) }
    SideEffect {
        if (!paused) lastVisibleMarkdown.value = markdown
    }
    val source = if (paused) lastVisibleMarkdown.value else markdown
    return when {
        streaming -> remember(source) { incrementalParser.parse(source) }

        source.length <= SynchronousMarkdownParseLimit ->
            remember(source) { MarkdownBlockCache.getOrParse(source) }

        else -> {
            val state = remember(source) {
                mutableStateOf(MarkdownBlockCache.peek(source).orEmpty())
            }
            LaunchedEffect(source) {
                if (state.value.isEmpty()) {
                    state.value = withContext(Dispatchers.Default) {
                        MarkdownBlockCache.getOrParse(source)
                    }
                }
            }
            state.value
        }
    }
}

@Composable
fun MarkdownContent(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = AetherOnSurface,
    workspaceDirectory: String? = null,
    allowRootImageRead: Boolean = false,
    onLinkClick: (String) -> Unit = {},
    knowledgeCitations: List<KnowledgeCitation> = emptyList(),
    streaming: Boolean = false,
    fadeSpan: MarkdownFadeSpan? = null,
) {
    val places = LocalAmapPlaces.current
    val answer = remember(markdown, places, streaming) {
        prepareAmapPlaceAnswer(
            stripKnowledgeSourceAttributions(markdown.replace("\r\n", "\n")),
            places,
            streaming = streaming,
        )
    }
    val knowledgeStripped = answer.markdown
    val scrolling = LocalConversationScrolling.current
    val paused = LocalChatActivityPaused.current
    val composed = LocalConversationMarkdownComposed.current
    val showPlaceholder = markdownUsesScrollPlaceholder(
        streaming = streaming,
        scrolling = scrolling,
        source = knowledgeStripped,
        alreadyComposed = composed.contains(knowledgeStripped),
    )
    if (showPlaceholder) {
        MarkdownScrollPlaceholder(
            markdown = knowledgeStripped,
            color = color,
            modifier = modifier,
        )
        return
    }
    val lastVisibleStripped = remember { mutableStateOf(knowledgeStripped) }
    SideEffect {
        if (!paused) lastVisibleStripped.value = knowledgeStripped
    }
    val citationSource = if (paused) lastVisibleStripped.value else knowledgeStripped
    val inlineImages = LocalBrowserInlineImages.current
    val display = remember(citationSource, knowledgeCitations, inlineImages, streaming, places) {
        val native = knowledgeCitations.filter { citation ->
            citation.url.startsWith("http://") || citation.url.startsWith("https://")
        }
        val webCitations = mergeKnowledgeCitations(
            extractMarkdownWebCitations(citationSource),
            native,
        )
        val stripped = if (webCitations.isEmpty()) {
            citationSource
        } else {
            stripMarkdownWebSourceSection(citationSource)
        }
        MarkdownDisplaySource(
            markdown = attachTopicImages(
                markdown = hoistInlineMarkdownImages(stripped),
                bundles = topicBundlesFromImages(inlineImages),
                streaming = streaming,
            ),
            webCitations = webCitations,
        )
    }
    val normalizedMarkdown = display.markdown
    if (!streaming && knowledgeStripped.isNotBlank()) {
        SideEffect { composed.add(knowledgeStripped) }
    }
    val resolvedCitations = remember(display.webCitations, knowledgeCitations) {
        mergeKnowledgeCitations(display.webCitations, knowledgeCitations)
    }
    val blocks = rememberMarkdownBlocks(normalizedMarkdown, streaming)
    var openCitation by remember { mutableStateOf<KnowledgeCitation?>(null) }
    var openPlace by remember { mutableStateOf<AmapPlace?>(null) }
    var citedPlaces by remember { mutableStateOf(emptyList<AmapPlace>()) }
    val handleLinkClick: (String) -> Unit = { url ->
        val placeId = parseAmapPlaceUrl(url)
        if (placeId != null) {
            openPlace = places.firstOrNull { it.id == placeId }
        } else {
            val citationIndex = parseKnowledgeCitationUrl(url)
            if (citationIndex != null) {
                openCitation = resolvedCitations.firstOrNull { it.index == citationIndex }
            } else {
                onLinkClick(url)
            }
        }
    }

    CompositionLocalProvider(LocalAmapPlaces provides places) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
    SelectionContainer {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val showSourceChips = false
            val chipsAtTop = false
            if (chipsAtTop) {
                MarkdownWebSourceChips(
                    citations = display.webCitations,
                    onOpenCitation = { citation -> openCitation = citation },
                )
            }
            blocks.forEachIndexed { index, block ->
                key(index, block::class, block.sourceEndOffset()) {
                    val blockFadeSpan = fadeSpan?.takeIf { span ->
                        block.sourceEndOffset() > span.sourceRange.first
                    }
                    MarkdownBlockItem(
                        block = block,
                        color = color,
                        workspaceDirectory = workspaceDirectory,
                        allowRootImageRead = allowRootImageRead,
                        onLinkClick = handleLinkClick,
                        fadeSpan = blockFadeSpan,
                        sourceCitations = resolvedCitations,
                    )
                }
            }
            if (showSourceChips && !chipsAtTop) {
                MarkdownWebSourceChips(
                    citations = display.webCitations,
                    onOpenCitation = { citation -> openCitation = citation },
                )
            }
            if (citedPlaces.isNotEmpty()) {
                MarkdownAmapCiteChips(
                    places = citedPlaces,
                    onOpenPlace = { place -> openPlace = place },
                )
            }
        }
    }
    }
    }
    openCitation?.let { citation ->
        KnowledgeSourceSheet(
            citation = citation,
            onDismiss = { openCitation = null },
            onOpenLink = onLinkClick,
        )
    }
    openPlace?.let { place ->
        AmapPlaceActionDialog(
            place = place,
            onDismiss = { openPlace = null },
            onCite = { cited ->
                if (citedPlaces.none { it.id == cited.id }) {
                    citedPlaces = citedPlaces + cited
                }
            },
        )
    }
}

private data class MarkdownDisplaySource(
    val markdown: String,
    val webCitations: List<KnowledgeCitation>,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MarkdownWebSourceChips(
    citations: List<KnowledgeCitation>,
    onOpenCitation: (KnowledgeCitation) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        citations.forEach { citation ->
            val label = markdownSourceHostLabel(citation.url)
                .ifBlank { markdownSourceHost(citation.url) }
                .ifBlank { citation.sourceName }
                .ifBlank { citation.index.toString() }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = AetherOnSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(AetherSurfaceHigh)
                    .clickable { onOpenCitation(citation) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MarkdownAmapCiteChips(
    places: List<AmapPlace>,
    onOpenPlace: (AmapPlace) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        places.forEach { place ->
            Text(
                text = place.name,
                style = MaterialTheme.typography.labelSmall,
                color = AetherOnSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(AetherSurfaceHigh)
                    .clickable { onOpenPlace(place) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

internal fun markdownUsesScrollPlaceholder(
    streaming: Boolean,
    scrolling: Boolean,
    source: String,
    alreadyComposed: Boolean,
): Boolean = !streaming &&
    scrolling &&
    source.isNotBlank() &&
    !alreadyComposed &&
    !source.contains("![")

internal fun normalizeMarkdownSource(markdown: String): String =
    stripKnowledgeSourceAttributions(markdown.replace("\r\n", "\n"))

internal fun markdownSliceContentType(block: MarkdownBlock): String = when (block) {
    is MarkdownBlock.Paragraph -> "assistant-md-paragraph"
    is MarkdownBlock.Heading -> "assistant-md-heading"
    is MarkdownBlock.UnorderedList -> "assistant-md-ul"
    is MarkdownBlock.OrderedList -> "assistant-md-ol"
    is MarkdownBlock.Quote -> "assistant-md-quote"
    is MarkdownBlock.Table -> "assistant-md-table"
    is MarkdownBlock.CodeFence -> "assistant-md-code"
    is MarkdownBlock.Image -> "assistant-md-image"
    is MarkdownBlock.ImageGroup -> "assistant-md-images"
    is MarkdownBlock.Mermaid -> "assistant-md-mermaid"
    is MarkdownBlock.AmapCards -> "assistant-md-amap-cards"
    is MarkdownBlock.BrowserAct -> "assistant-md-browser-act"
    is MarkdownBlock.BrowserResume -> "assistant-md-browser-resume"
    is MarkdownBlock.Suggest -> "assistant-md-suggest"
    MarkdownBlock.Rule -> "assistant-md-rule"
}

@Composable
internal fun MarkdownBlockItem(
    block: MarkdownBlock,
    color: Color = AetherOnSurface,
    workspaceDirectory: String? = null,
    allowRootImageRead: Boolean = false,
    onLinkClick: (String) -> Unit = {},
    fadeSpan: MarkdownFadeSpan? = null,
    knowledgeCitations: List<KnowledgeCitation> = emptyList(),
    sourceCitations: List<KnowledgeCitation> = emptyList(),
) {
    var openCitation by remember { mutableStateOf<KnowledgeCitation?>(null) }
    val places = LocalAmapPlaces.current
    var openPlace by remember { mutableStateOf<AmapPlace?>(null) }
    var citedPlaces by remember { mutableStateOf(emptyList<AmapPlace>()) }
    val handleLinkClick: (String) -> Unit = { url ->
        val placeId = parseAmapPlaceUrl(url)
        if (placeId != null) {
            openPlace = places.firstOrNull { it.id == placeId }
        } else {
            val citationIndex = parseKnowledgeCitationUrl(url)
            if (citationIndex != null) {
                openCitation = knowledgeCitations.firstOrNull { it.index == citationIndex }
                    ?: sourceCitations.firstOrNull { it.index == citationIndex }
            } else {
                onLinkClick(url)
            }
        }
    }
    val resolvedClick = handleLinkClick
    val citations = sourceCitations.ifEmpty { knowledgeCitations }
    val citationLabels = remember(citations) {
        citations.associate { citation ->
            citation.index to knowledgeCitationChipLabel(citation)
        }
    }
    CompositionLocalProvider(LocalMarkdownCitationLabels provides citationLabels) {
    when (block) {
        is MarkdownBlock.Paragraph -> MarkdownParagraph(block.text, color, resolvedClick, fadeSpan)
        is MarkdownBlock.Heading -> MarkdownHeading(block.level, block.text, resolvedClick, fadeSpan)
        is MarkdownBlock.UnorderedList -> MarkdownBullets(block.items, resolvedClick, fadeSpan)
        is MarkdownBlock.OrderedList -> MarkdownNumbers(block.items, resolvedClick, fadeSpan)
        is MarkdownBlock.Quote -> MarkdownQuote(block.text, resolvedClick, fadeSpan)
        is MarkdownBlock.Table -> {
            MarkdownTable(block.headers, block.rows, resolvedClick, fadeSpan)
        }
        is MarkdownBlock.CodeFence -> MarkdownCodeFence(block.code, fadeSpan)
        is MarkdownBlock.Image -> MarkdownImageCarousel(
            images = listOf(block.image),
            workspaceDirectory = workspaceDirectory,
            allowRootImageRead = allowRootImageRead,
            onLinkClick = resolvedClick,
            sourceCitations = citations,
            onOpenCitation = { citation -> openCitation = citation },
        )
        is MarkdownBlock.ImageGroup -> MarkdownImageCarousel(
            images = block.images,
            workspaceDirectory = workspaceDirectory,
            allowRootImageRead = allowRootImageRead,
            onLinkClick = resolvedClick,
            sourceCitations = citations,
            onOpenCitation = { citation -> openCitation = citation },
        )
        is MarkdownBlock.Mermaid -> {
            if (LocalConversationScrolling.current) {
                MarkdownMermaidScrollPlaceholder(block.diagram)
            } else {
                MarkdownMermaidBlock(block.diagram)
            }
        }
        is MarkdownBlock.AmapCards -> {
            val resolved = block.placeIds.mapNotNull { id ->
                places.firstOrNull { place -> place.id == id }
            }
            if (resolved.isNotEmpty()) {
                DisableSelection {
                    AmapPlaceCardCluster(
                        places = resolved,
                        startIndex = block.startIndex,
                    )
                }
            }
        }
        is MarkdownBlock.BrowserAct -> {
            val ask = LocalAmapPlaceAsk.current
            DisableSelection {
                Box(modifier = Modifier.padding(top = 4.dp)) {
                    AmapPlaceAskCapsule(block.label) {
                        ask(browserActFollowUpUserText(block.label, block.url))
                    }
                }
            }
        }
        is MarkdownBlock.BrowserResume -> {
            val ask = LocalAmapPlaceAsk.current
            val label = stringResource(R.string.browser_preview_login_done)
            DisableSelection {
                Box(modifier = Modifier.padding(top = 4.dp)) {
                    AmapPlaceAskCapsule(label) {
                        ask(BrowserLoginContinueUserText)
                    }
                }
            }
        }
        is MarkdownBlock.Suggest -> {
            val ask = LocalAmapPlaceAsk.current
            DisableSelection {
                FlowRow(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    block.items.forEach { item ->
                        AmapPlaceAskCapsule(item.label) { ask(item.text) }
                    }
                }
            }
        }
        MarkdownBlock.Rule -> HorizontalDivider(color = AetherOutlineSoft)
    }
    }
    openCitation?.let { citation ->
        KnowledgeSourceSheet(
            citation = citation,
            onDismiss = { openCitation = null },
            onOpenLink = onLinkClick,
        )
    }
    openPlace?.let { place ->
        AmapPlaceActionDialog(
            place = place,
            onDismiss = { openPlace = null },
            onCite = { cited ->
                if (citedPlaces.none { it.id == cited.id }) {
                    citedPlaces = citedPlaces + cited
                }
            },
        )
    }
    if (citedPlaces.isNotEmpty()) {
        MarkdownAmapCiteChips(
            places = citedPlaces,
            onOpenPlace = { place -> openPlace = place },
        )
    }
}

@Composable
private fun MarkdownScrollPlaceholder(
    markdown: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = markdown.trim(),
        modifier = modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyLarge,
        color = color,
        maxLines = 16,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun MarkdownMermaidScrollPlaceholder(
    diagram: MarkdownMermaidSpec,
) {
    val heightDp = (diagram.layout.minHeightDp ?: DefaultMermaidMinHeightDp).coerceAtLeast(1)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(heightDp.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(AetherSurface),
    )
}

@Composable
private fun MarkdownParagraph(
    text: MarkdownSourceText,
    color: Color,
    onLinkClick: (String) -> Unit,
    fadeSpan: MarkdownFadeSpan?,
) {
    MarkdownRichTextBlock(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = color,
        onLinkClick = onLinkClick,
        variant = MarkdownHtmlTextVariant.Paragraph,
        fadeSpan = fadeSpan,
    )
}

@Composable
private fun MarkdownHeading(
    level: Int,
    text: MarkdownSourceText,
    onLinkClick: (String) -> Unit,
    fadeSpan: MarkdownFadeSpan?,
) {
    val style = when (level) {
        1 -> MaterialTheme.typography.headlineMedium
        2 -> MaterialTheme.typography.titleLarge
        3 -> MaterialTheme.typography.titleMedium
        else -> MaterialTheme.typography.labelLarge
    }

    MarkdownRichTextBlock(
        text = text,
        style = style,
        color = AetherOnSurface,
        onLinkClick = onLinkClick,
        variant = MarkdownHtmlTextVariant.Heading(level),
        fadeSpan = fadeSpan,
    )
}

@Composable
private fun MarkdownBullets(
    items: List<MarkdownSourceText>,
    onLinkClick: (String) -> Unit,
    fadeSpan: MarkdownFadeSpan?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "\u2022",
                    style = MaterialTheme.typography.bodyLarge,
                    color = AetherOnSurface,
                )
                Spacer(modifier = Modifier.width(10.dp))
                MarkdownRichTextBlock(
                    text = item,
                    style = MaterialTheme.typography.bodyLarge,
                    color = AetherOnSurface,
                    modifier = Modifier.weight(1f),
                    onLinkClick = onLinkClick,
                    variant = MarkdownHtmlTextVariant.ListItem,
                    fadeSpan = fadeSpan,
                )
            }
        }
    }
}

@Composable
private fun MarkdownNumbers(
    items: List<MarkdownSourceText>,
    onLinkClick: (String) -> Unit,
    fadeSpan: MarkdownFadeSpan?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEachIndexed { index, item ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "${index + 1}.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = AetherOnSurface,
                )
                Spacer(modifier = Modifier.width(10.dp))
                MarkdownRichTextBlock(
                    text = item,
                    style = MaterialTheme.typography.bodyLarge,
                    color = AetherOnSurface,
                    modifier = Modifier.weight(1f),
                    onLinkClick = onLinkClick,
                    variant = MarkdownHtmlTextVariant.ListItem,
                    fadeSpan = fadeSpan,
                )
            }
        }
    }
}

@Composable
private fun MarkdownQuote(
    text: MarkdownSourceText,
    onLinkClick: (String) -> Unit,
    fadeSpan: MarkdownFadeSpan?,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .background(AetherOutlineSoft, RoundedCornerShape(999.dp))
        )
        Spacer(modifier = Modifier.width(12.dp))
        MarkdownRichTextBlock(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = AetherOnSurface,
            modifier = Modifier.weight(1f),
            onLinkClick = onLinkClick,
            variant = MarkdownHtmlTextVariant.Quote,
            fadeSpan = fadeSpan,
        )
    }
}

@Composable
private fun MarkdownTable(
    headers: List<MarkdownSourceText>,
    rows: List<List<MarkdownSourceText>>,
    onLinkClick: (String) -> Unit,
    fadeSpan: MarkdownFadeSpan?,
) {
    val columnCount = remember(headers, rows) {
        maxOf(headers.size, rows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)
    }
    // Every cell is a laid-out text block, so a thousand-row table is a thousand-row
    // measure pass inside a single list item. Show a page at a time instead.
    var visibleRowCount by remember(rows) {
        mutableIntStateOf(minOf(rows.size, MarkdownTablePageRows))
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columnWidths = remember(columnCount, maxWidth) {
            markdownTableColumnWidths(columnCount, maxWidth)
        }
        val tableWidth = columnWidths.fold(0.dp) { width, columnWidth -> width + columnWidth }
            .coerceAtLeast(maxWidth)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .clip(RoundedCornerShape(18.dp))
                .background(AetherSurface.copy(alpha = 0.92f)),
        ) {
            MarkdownTableRow(
                cells = headers,
                columnWidths = columnWidths,
                tableWidth = tableWidth,
                onLinkClick = onLinkClick,
                fadeSpan = fadeSpan,
                isHeader = true,
            )
            for (index in 0 until visibleRowCount) {
                MarkdownTableRow(
                    cells = rows[index],
                    columnWidths = columnWidths,
                    tableWidth = tableWidth,
                    onLinkClick = onLinkClick,
                    fadeSpan = fadeSpan,
                    isHeader = false,
                    shaded = index % 2 == 1,
                )
            }
            if (visibleRowCount < rows.size) {
                Box(
                    modifier = Modifier
                        .width(tableWidth)
                        .clickable {
                            visibleRowCount = minOf(rows.size, visibleRowCount + MarkdownTablePageRows)
                        }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = stringResource(
                            R.string.markdown_table_show_more_rows,
                            rows.size - visibleRowCount,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = AetherOnSurfaceVariant,
                    )
                }
            }
        }
    }
}

internal fun markdownTableColumnWidths(
    columnCount: Int,
    viewportWidth: Dp,
): List<Dp> {
    val normalizedColumnCount = columnCount.coerceAtLeast(1)
    return when {
        normalizedColumnCount == 1 -> listOf(viewportWidth.coerceAtLeast(MarkdownTableMinColumnWidth))
        normalizedColumnCount == 2 -> {
            val firstColumn = (viewportWidth * 0.42f).coerceAtLeast(MarkdownTableMinColumnWidth)
            val secondColumn = (viewportWidth - firstColumn)
                .coerceAtLeast(MarkdownTableDescriptionMinColumnWidth)
            listOf(firstColumn, secondColumn)
        }
        normalizedColumnCount == 3 -> {
            val columnWidth = (viewportWidth / normalizedColumnCount)
                .coerceAtLeast(MarkdownTableMinColumnWidth)
            List(normalizedColumnCount) { columnWidth }
        }
        else -> List(normalizedColumnCount) { MarkdownTableScrollableColumnWidth }
    }
}

@Composable
private fun MarkdownTableRow(
    cells: List<MarkdownSourceText>,
    columnWidths: List<Dp>,
    tableWidth: Dp,
    onLinkClick: (String) -> Unit,
    fadeSpan: MarkdownFadeSpan?,
    isHeader: Boolean,
    shaded: Boolean = false,
) {
    Row(
        modifier = Modifier
            .width(tableWidth)
            .background(
                when {
                    isHeader -> AetherSurfaceHigh
                    shaded -> AetherSurface.copy(alpha = 0.68f)
                    else -> Color.Transparent
                }
            )
    ) {
        columnWidths.forEachIndexed { index, columnWidth ->
            val cell = cells.getOrNull(index) ?: MarkdownSourceText("", 0)
            Box(
                modifier = Modifier
                    .width(columnWidth)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                MarkdownRichTextBlock(
                    text = cell,
                    style = if (isHeader) {
                        MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    color = AetherOnSurface,
                    modifier = Modifier.heightIn(min = 20.dp),
                    onLinkClick = onLinkClick,
                    variant = MarkdownHtmlTextVariant.TableCell(isHeader = isHeader),
                    fadeSpan = fadeSpan,
                )
            }
        }
    }
}

@Composable
private fun MarkdownCodeFence(
    code: MarkdownSourceText,
    fadeSpan: MarkdownFadeSpan?,
) {
    // A single Text laying out tens of thousands of monospace characters blocks the frame
    // it appears in, so oversized fences are truncated to a head preview until expanded.
    val isOversized = remember(code.text) { markdownCodeFenceIsOversized(code.text) }
    var expanded by remember(code.text) { mutableStateOf(false) }
    val shown = remember(code.text, code.sourceOffset, fadeSpan, isOversized, expanded) {
        if (!isOversized || expanded) {
            code
        } else {
            code.copy(text = markdownCodeFencePreview(code.text))
        }
    }
    val annotated = remember(shown.text, shown.sourceOffset, fadeSpan) {
        plainMarkdownText(shown.text, shown.sourceOffset, fadeSpan)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AetherSurfaceHigh, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            text = annotated,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = AetherOnSurface,
            modifier = if (expanded) {
                Modifier.heightIn(max = MarkdownCodeFenceMaxHeight)
            } else {
                Modifier
            },
        )
        if (isOversized && !expanded) {
            Text(
                text = stringResource(R.string.markdown_code_fence_show_full),
                style = MaterialTheme.typography.labelLarge,
                color = AetherOnSurfaceVariant,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .clickable { expanded = true },
            )
        }
    }
}

private fun markdownCodeFenceIsOversized(text: String): Boolean =
    text.length > MarkdownCodeFenceMaxChars ||
        text.count { it == '\n' } > MarkdownCodeFenceMaxLines

private fun markdownCodeFencePreview(text: String): String {
    var newlines = 0
    var cut = text.length
    for (index in text.indices) {
        if (text[index] == '\n') {
            newlines++
            if (newlines == MarkdownCodeFencePreviewLines) {
                cut = index
                break
            }
        }
    }
    return text.take(minOf(cut, MarkdownCodeFenceMaxChars))
}

@Composable
private fun MarkdownImageBlock(
    image: MarkdownImageSpec,
    workspaceDirectory: String?,
    allowRootImageRead: Boolean,
    onLinkClick: (String) -> Unit,
) {
    val context = LocalContext.current
    val appRuntime = remember(context) {
        (context.applicationContext as? AetherApplication)?.runtime
    }
    val workspaceFileBridge = remember(context, appRuntime) {
        appRuntime?.workspaceFileBridge
    }
    val runtimeWorkspaceFileBridge = appRuntime?.runtimeWorkspaceFileBridge
    val resolvedUrl = remember(image.url) { normalizeMarkdownImageUrl(image.url).orEmpty() }
    val cacheKey = "$resolvedUrl|$workspaceDirectory|$allowRootImageRead"
    val originalLinkTarget = remember(resolvedUrl, workspaceDirectory) {
        buildMarkdownImageOriginalLinkTarget(
            rawUrl = resolvedUrl,
            workspaceFileBridge = workspaceFileBridge,
            workspaceDirectory = workspaceDirectory,
        )
    }
    val imageState by produceState(
        initialValue = MarkdownDecodedImageMemory.get(cacheKey) ?: MarkdownImageLoadResult(),
        key1 = resolvedUrl,
        key2 = workspaceDirectory,
        key3 = allowRootImageRead,
    ) {
        val cached = MarkdownDecodedImageMemory.get(cacheKey)
        if (cached != null) {
            value = cached
            return@produceState
        }
        value = withContext(Dispatchers.IO + NonCancellable) {
            loadMarkdownImage(
                context = context,
                workspaceFileBridge = workspaceFileBridge,
                runtimeWorkspaceFileBridge = runtimeWorkspaceFileBridge,
                rawUrl = resolvedUrl,
                workspaceDirectory = workspaceDirectory,
                allowRootImageRead = allowRootImageRead,
            )
        }
    }
    var showPreview by remember(resolvedUrl) { mutableStateOf(false) }
    val canPreview = imageState.usable
    if (!imageState.usable && imageState.error != null) return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MarkdownMediaWidthContainer(layout = image.layout) { widthModifier ->
            when {
                imageState.html != null -> MarkdownHtmlBlock(
                    html = imageState.html!!,
                    layout = image.layout,
                    defaultMinHeightDp = 1,
                    defaultMaxHeightDp = DefaultImageMaxHeightDp,
                    modifier = widthModifier
                        .clip(RoundedCornerShape(8.dp)),
                    backgroundColor = Color.Transparent,
                    onTap = if (canPreview) {
                        { showPreview = true }
                    } else {
                        null
                    },
                )

                imageState.bitmap != null -> MarkdownBitmapImageBlock(
                    bitmap = imageState.bitmap!!,
                    altText = image.altText,
                    layout = image.layout,
                    modifier = widthModifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showPreview = true },
                )

                else -> Box(
                    modifier = widthModifier
                        .heightIn(
                            min = 72.dp,
                            max = (image.layout.maxHeightDp ?: DefaultImageMaxHeightDp).dp,
                        )
                        .clip(RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    AmapSkeletonBar(modifier = Modifier.fillMaxSize())
                }
            }
        }
        if (image.altText.isNotBlank()) {
            Text(
                text = image.altText,
                style = MaterialTheme.typography.bodySmall,
                color = AetherOnSurfaceVariant,
            )
        }
    }

    if (showPreview && canPreview) {
        MarkdownImagePreviewDialog(
            altText = image.altText,
            imageState = imageState,
            originalLinkTarget = originalLinkTarget,
            onDismiss = { showPreview = false },
            onOpenLink = onLinkClick,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MarkdownImageCarousel(
    images: List<MarkdownImageSpec>,
    workspaceDirectory: String?,
    allowRootImageRead: Boolean,
    onLinkClick: (String) -> Unit,
    sourceCitations: List<KnowledgeCitation> = emptyList(),
    onOpenCitation: (KnowledgeCitation) -> Unit = {},
) {
    if (images.isEmpty()) return
    val outcomes = remember(images.map { it.url }) { mutableStateMapOf<String, MarkdownImageLoadResult>() }
    images.forEach { image ->
        key(image.url) {
            MarkdownCarouselLoadProbe(
                image = image,
                workspaceDirectory = workspaceDirectory,
                allowRootImageRead = allowRootImageRead,
            ) { result ->
                outcomes[image.url] = result
            }
        }
    }
    val shown = images.filter { image ->
        val result = outcomes[image.url]
        result == null || result.usable
    }
    if (shown.isEmpty()) return
    var previewIndex by remember { mutableStateOf<Int?>(null) }
    val chrome = LocalBrowserConversationChrome.current
    @Composable
    fun CarouselItem(index: Int, modifier: Modifier) {
        val image = shown[index]
        val badge = markdownImageSourceBadgeLabel(image, sourceCitations)
        val citation = markdownImageSourceCitation(image, sourceCitations)
        val result = outcomes[image.url]
        Box(
            modifier = modifier.clickable { previewIndex = index },
        ) {
            when {
                result?.html != null -> MarkdownHtmlBlock(
                    html = result.html!!,
                    layout = MarkdownMediaLayout(
                        heightDp = 205,
                        maxHeightDp = 205,
                        fit = MarkdownMediaFit.Cover,
                    ),
                    defaultMinHeightDp = 1,
                    defaultMaxHeightDp = 205,
                    modifier = Modifier.fillMaxSize(),
                    backgroundColor = Color.Transparent,
                )
                result?.bitmap != null -> Image(
                    bitmap = result.bitmap!!,
                    contentDescription = image.altText.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.markdown_image_carousel),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                else -> AmapSkeletonBar(modifier = Modifier.fillMaxSize())
            }
            if (badge.isNotBlank()) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .widthIn(max = 140.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.Black.copy(alpha = 0.52f))
                        .then(
                            if (citation != null) {
                                Modifier.clickable { onOpenCitation(citation) }
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val gap = 8.dp
        if (chrome.browserMode) {
            val carouselState = rememberCarouselState { shown.size.coerceAtLeast(1) }
            HorizontalMultiBrowseCarousel(
                state = carouselState,
                preferredItemWidth = if (shown.size == 1) maxWidth else maxWidth * 0.72f,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (shown.size == 1) 221.dp else 188.dp),
                itemSpacing = gap,
            ) { index ->
                CarouselItem(
                    index = index,
                    modifier = Modifier
                        .fillMaxSize()
                        .maskClip(RoundedCornerShape(22.dp)),
                )
            }
        } else {
            val places = LocalAmapPlaces.current
            val compact = shown.any { image ->
                looksLikeAmapCdn(image.url) || places.any { place ->
                    place.name == image.altText || place.photos.contains(image.url)
                }
            }
            val visibleCount = if (compact) 2.5f else 2f
            val itemWidth = if (shown.size == 1 && !compact) {
                maxWidth
            } else {
                (maxWidth - gap * 2) / visibleCount
            }
            val itemHeight = if (compact) {
                itemWidth
            } else if (shown.size == 1) {
                221.dp
            } else {
                168.dp
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                shown.indices.forEach { index ->
                    CarouselItem(
                        index = index,
                        modifier = Modifier
                            .width(itemWidth)
                            .height(itemHeight)
                            .clip(RoundedCornerShape(if (compact) 12.dp else 22.dp)),
                    )
                }
            }
        }
    }
    previewIndex?.let { index ->
        val image = shown.getOrNull(index) ?: return@let
        MarkdownImagePreviewHost(
            image = image,
            workspaceDirectory = workspaceDirectory,
            allowRootImageRead = allowRootImageRead,
            onLinkClick = onLinkClick,
            onDismiss = { previewIndex = null },
        )
    }
}

@Composable
private fun MarkdownCarouselLoadProbe(
    image: MarkdownImageSpec,
    workspaceDirectory: String?,
    allowRootImageRead: Boolean,
    onResult: (MarkdownImageLoadResult) -> Unit,
) {
    val context = LocalContext.current
    val appRuntime = remember(context) {
        (context.applicationContext as? AetherApplication)?.runtime
    }
    val workspaceFileBridge = remember(context, appRuntime) {
        appRuntime?.workspaceFileBridge
    }
    val runtimeWorkspaceFileBridge = appRuntime?.runtimeWorkspaceFileBridge
    val resolvedUrl = remember(image.url) { normalizeMarkdownImageUrl(image.url).orEmpty() }
    val chrome = LocalBrowserConversationChrome.current
    val cacheKey = "$resolvedUrl|$workspaceDirectory|$allowRootImageRead"
    val imageState by produceState(
        initialValue = MarkdownDecodedImageMemory.get(cacheKey) ?: MarkdownImageLoadResult(),
        resolvedUrl,
        workspaceDirectory,
        allowRootImageRead,
        chrome.browserMode,
    ) {
        val cached = MarkdownDecodedImageMemory.get(cacheKey)
        if (cached != null) {
            value = if (chrome.browserMode) rejectLowQualityBrowserImage(cached) else cached
            return@produceState
        }
        value = withContext(Dispatchers.IO + NonCancellable) {
            val loaded = loadMarkdownImage(
                context = context,
                workspaceFileBridge = workspaceFileBridge,
                runtimeWorkspaceFileBridge = runtimeWorkspaceFileBridge,
                rawUrl = resolvedUrl,
                workspaceDirectory = workspaceDirectory,
                allowRootImageRead = allowRootImageRead,
            )
            if (chrome.browserMode) rejectLowQualityBrowserImage(loaded) else loaded
        }
    }
    LaunchedEffect(imageState) {
        onResult(imageState)
    }
}

@Composable
private fun MarkdownImagePreviewHost(
    image: MarkdownImageSpec,
    workspaceDirectory: String?,
    allowRootImageRead: Boolean,
    onLinkClick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val appRuntime = remember(context) {
        (context.applicationContext as? AetherApplication)?.runtime
    }
    val workspaceFileBridge = remember(context, appRuntime) {
        appRuntime?.workspaceFileBridge
    }
    val runtimeWorkspaceFileBridge = appRuntime?.runtimeWorkspaceFileBridge
    val resolvedUrl = remember(image.url) { normalizeMarkdownImageUrl(image.url).orEmpty() }
    val originalLinkTarget = remember(resolvedUrl, workspaceDirectory) {
        buildMarkdownImageOriginalLinkTarget(
            rawUrl = resolvedUrl,
            workspaceFileBridge = workspaceFileBridge,
            workspaceDirectory = workspaceDirectory,
        )
    }
    val capsuleUrl = remember(image.sourceUrl, originalLinkTarget) {
        image.sourceUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: originalLinkTarget?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }
    val cacheKey = "$resolvedUrl|$workspaceDirectory|$allowRootImageRead"
    val imageState by produceState(
        initialValue = MarkdownDecodedImageMemory.get(cacheKey) ?: MarkdownImageLoadResult(),
        key1 = resolvedUrl,
        key2 = workspaceDirectory,
        key3 = allowRootImageRead,
    ) {
        val cached = MarkdownDecodedImageMemory.get(cacheKey)
        if (cached != null) {
            value = cached
            return@produceState
        }
        value = withContext(Dispatchers.IO + NonCancellable) {
            loadMarkdownImage(
                context = context,
                workspaceFileBridge = workspaceFileBridge,
                runtimeWorkspaceFileBridge = runtimeWorkspaceFileBridge,
                rawUrl = resolvedUrl,
                workspaceDirectory = workspaceDirectory,
                allowRootImageRead = allowRootImageRead,
            )
        }
    }
    if (imageState.bitmap != null || imageState.html != null) {
        MarkdownImagePreviewDialog(
            altText = image.altText,
            imageState = imageState,
            originalLinkTarget = capsuleUrl,
            onDismiss = onDismiss,
            onOpenLink = onLinkClick,
        )
    }
}

@Composable
private fun MarkdownMermaidBlock(
    diagram: MarkdownMermaidSpec,
) {
    val renderErrorTitle = stringResource(R.string.markdown_mermaid_error_render)
    val invalidSyntaxError = stringResource(R.string.markdown_mermaid_error_invalid_syntax)
    val previewLayout = remember(diagram.layout) {
        diagram.layout.copy(
            heightDp = null,
            minHeightDp = PreviewDialogMinHeightDp,
            maxHeightDp = PreviewDialogMaxHeightDp,
            scroll = true,
            showAll = false,
        )
    }
    var showPreview by remember(diagram.code.text, diagram.layout) { mutableStateOf(false) }
    MarkdownMediaWidthContainer(layout = diagram.layout) { widthModifier ->
        MarkdownHtmlBlock(
            html = remember(diagram.code.text, diagram.layout) {
                buildMermaidHtml(
                    code = diagram.code.text,
                    layout = diagram.layout,
                    renderErrorTitle = renderErrorTitle,
                    invalidSyntaxError = invalidSyntaxError,
                )
            },
            layout = diagram.layout,
            defaultMinHeightDp = DefaultMermaidMinHeightDp,
            defaultMaxHeightDp = DefaultMermaidMaxHeightDp,
            modifier = widthModifier
                .clip(RoundedCornerShape(18.dp))
                .background(AetherSurface),
            onTap = { showPreview = true },
        )
    }

    if (showPreview) {
        MarkdownMermaidPreviewDialog(
            html = remember(diagram.code.text, previewLayout) {
                buildMermaidHtml(
                    code = diagram.code.text,
                    layout = previewLayout,
                    renderErrorTitle = renderErrorTitle,
                    invalidSyntaxError = invalidSyntaxError,
                )
            },
            onDismiss = { showPreview = false },
        )
    }
}

@Composable
private fun MarkdownHtmlBlock(
    html: String,
    layout: MarkdownMediaLayout,
    defaultMinHeightDp: Int,
    defaultMaxHeightDp: Int,
    modifier: Modifier = Modifier,
    backgroundColor: Color = AetherSurface,
    onTap: (() -> Unit)? = null,
    onLinkClick: ((String) -> Unit)? = null,
) {
    val resolvedMinHeightDp = layout.minHeightDp ?: defaultMinHeightDp
    val resolvedMaxHeightDp = layout.maxHeightDp ?: defaultMaxHeightDp
    val scrollViewportHeightDp = if (layout.scroll) {
        (layout.heightDp ?: resolvedMaxHeightDp).coerceAtLeast(1)
    } else {
        null
    }
    var measuredHeightDp by remember(html) { mutableIntStateOf(resolvedMinHeightDp.coerceAtLeast(1)) }
    var hasMeasuredContent by remember(html) { mutableStateOf(false) }
    val effectiveMinHeightDp = if (hasMeasuredContent) 1 else resolvedMinHeightDp.coerceAtLeast(1)
    val appliedHeightDp = when {
        layout.showAll -> measuredHeightDp.coerceAtLeast(effectiveMinHeightDp)
        layout.heightDp != null -> layout.heightDp.coerceAtLeast(effectiveMinHeightDp)
        scrollViewportHeightDp != null -> measuredHeightDp.coerceIn(
            effectiveMinHeightDp,
            scrollViewportHeightDp,
        )
        else -> measuredHeightDp.coerceIn(
            effectiveMinHeightDp,
            resolvedMaxHeightDp.coerceAtLeast(effectiveMinHeightDp),
        )
    }

    AndroidView(
        modifier = modifier.height(appliedHeightDp.dp),
        factory = { context ->
            val gestureDetector = GestureDetector(
                context,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                        onTap?.invoke()
                        return false
                    }
                },
            )
            WebView(context).apply {
                setBackgroundColor(backgroundColor.toArgb())
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadsImagesAutomatically = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                isVerticalScrollBarEnabled = layout.scroll && !layout.showAll
                isHorizontalScrollBarEnabled = layout.scroll && !layout.showAll
                isFocusable = true
                isFocusableInTouchMode = true
                ViewCompat.setNestedScrollingEnabled(this, layout.scroll && !layout.showAll)
                webChromeClient = WebChromeClient()
                addJavascriptInterface(
                    MarkdownHtmlBridge(
                        onHeightMeasured = { measuredHeight ->
                            measuredHeightDp = measuredHeight.coerceAtLeast(1)
                            hasMeasuredContent = true
                        },
                        onTap = onTap,
                        onLinkClick = onLinkClick,
                    ),
                    MarkdownHtmlBridgeName,
                )
                setOnTouchListener { view, event ->
                    if (layout.scroll && !layout.showAll) {
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN,
                            MotionEvent.ACTION_MOVE -> view.parent?.requestDisallowInterceptTouchEvent(true)
                            MotionEvent.ACTION_UP,
                            MotionEvent.ACTION_CANCEL -> view.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                    gestureDetector.onTouchEvent(event)
                    false
                }
                setOnClickListener { onTap?.invoke() }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        val targetUrl = request?.url?.toString().orEmpty()
                        if (targetUrl.isBlank()) return false
                        onLinkClick?.invoke(targetUrl)
                        return true
                    }

                    override fun onPageFinished(
                        view: WebView?,
                        url: String?,
                    ) {
                        super.onPageFinished(view, url)
                        view?.evaluateJavascript("window.reportAetherHeight && window.reportAetherHeight();", null)
                    }
                }
            }
        },
        update = { webView ->
            webView.isVerticalScrollBarEnabled = layout.scroll && !layout.showAll
            webView.isHorizontalScrollBarEnabled = layout.scroll && !layout.showAll
            ViewCompat.setNestedScrollingEnabled(webView, layout.scroll && !layout.showAll)
            if (webView.tag != html) {
                webView.tag = html
                webView.loadDataWithBaseURL(
                    MarkdownHtmlBaseUrl,
                    html,
                    "text/html",
                    "utf-8",
                    null,
                )
            }
        },
    )
}

@Composable
private fun MarkdownBitmapImageBlock(
    bitmap: ImageBitmap,
    altText: String,
    layout: MarkdownMediaLayout,
    modifier: Modifier = Modifier,
) {
    val contentScale = if (layout.fit == MarkdownMediaFit.Cover) {
        ContentScale.Crop
    } else {
        ContentScale.Fit
    }
    BoxWithConstraints(modifier = modifier) {
        val resolvedMaxHeight = (layout.maxHeightDp ?: DefaultImageMaxHeightDp).dp
        val explicitHeight = layout.heightDp?.dp
        val naturalWidth = if (bitmap.width > 0) {
            bitmap.width.dp.coerceAtMost(maxWidth)
        } else {
            maxWidth
        }
        val renderWidth = if (layout.width == null) naturalWidth else maxWidth
        val naturalHeight = if (bitmap.width > 0 && bitmap.height > 0) {
            renderWidth * (bitmap.height.toFloat() / bitmap.width.toFloat())
        } else {
            resolvedMaxHeight
        }
        val containerHeight = when {
            layout.showAll -> naturalHeight.coerceAtLeast(1.dp)
            explicitHeight != null -> explicitHeight
            else -> naturalHeight.coerceAtMost(resolvedMaxHeight).coerceAtLeast(1.dp)
        }
        val needsVerticalScroll = !layout.showAll && layout.scroll && naturalHeight > containerHeight

        Box(
            modifier = Modifier
                .width(renderWidth)
                .height(containerHeight),
            contentAlignment = if (needsVerticalScroll) Alignment.TopCenter else Alignment.Center,
        ) {
            when {
                needsVerticalScroll -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = altText.takeIf { it.isNotBlank() },
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = contentScale,
                    )
                }

                explicitHeight != null || (!layout.showAll && naturalHeight > containerHeight) -> Image(
                    bitmap = bitmap,
                    contentDescription = altText.takeIf { it.isNotBlank() },
                    modifier = Modifier.fillMaxSize(),
                    contentScale = contentScale,
                )

                else -> Image(
                    bitmap = bitmap,
                    contentDescription = altText.takeIf { it.isNotBlank() },
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = contentScale,
                )
            }
        }
    }
}

@Composable
private fun MarkdownMediaWidthContainer(
    layout: MarkdownMediaLayout,
    content: @Composable (Modifier) -> Unit,
) {
    val outerModifier = if (layout.width is MarkdownMediaWidth.DpValue) {
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    } else {
        Modifier.fillMaxWidth()
    }

    Box(modifier = outerModifier) {
        content(Modifier.markdownMediaWidth(layout.width))
    }
}

private fun Modifier.markdownMediaWidth(
    mediaWidth: MarkdownMediaWidth?,
): Modifier = when (mediaWidth) {
    null -> fillMaxWidth()
    is MarkdownMediaWidth.Fraction -> fillMaxWidth(mediaWidth.value.coerceIn(0.1f, 1f))
    is MarkdownMediaWidth.DpValue -> width(mediaWidth.value.dp)
}

@Composable
private fun MarkdownImagePreviewDialog(
    altText: String,
    imageState: MarkdownImageLoadResult,
    originalLinkTarget: String?,
    onDismiss: () -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val capsuleHost = originalLinkTarget
        ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        ?.let { markdownSourceHost(it).ifBlank { it } }
        .orEmpty()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f)),
        ) {
            Text(
                text = stringResource(R.string.common_close),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(18.dp)
                    .clickable(onClick = onDismiss),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 72.dp),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    imageState.bitmap != null -> Image(
                        bitmap = imageState.bitmap,
                        contentDescription = altText.takeIf { it.isNotBlank() },
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Fit,
                    )
                    imageState.html != null -> MarkdownHtmlBlock(
                        html = imageState.html,
                        layout = MarkdownMediaLayout(
                            maxHeightDp = PreviewDialogMaxHeightDp,
                            scroll = true,
                            showAll = false,
                        ),
                        defaultMinHeightDp = 1,
                        defaultMaxHeightDp = PreviewDialogMaxHeightDp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp)),
                        backgroundColor = Color.Transparent,
                    )
                    else -> Text(
                        text = imageState.error ?: stringResource(R.string.markdown_preview_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.8f),
                    )
                }
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (altText.isNotBlank()) {
                    Text(
                        text = altText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                    )
                }
                if (!originalLinkTarget.isNullOrBlank() && capsuleHost.isNotBlank()) {
                    Text(
                        text = capsuleHost,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color.White.copy(alpha = 0.16f))
                            .clickable { onOpenLink(originalLinkTarget) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkdownMermaidPreviewDialog(
    html: String,
    onDismiss: () -> Unit,
) {
    MarkdownPreviewDialogFrame(
        title = stringResource(R.string.markdown_mermaid_preview_title),
        onDismiss = onDismiss,
    ) {
        MarkdownHtmlBlock(
            html = html,
            layout = MarkdownMediaLayout(
                minHeightDp = PreviewDialogMinHeightDp,
                maxHeightDp = PreviewDialogMaxHeightDp,
                scroll = true,
                showAll = false,
            ),
            defaultMinHeightDp = PreviewDialogMinHeightDp,
            defaultMaxHeightDp = PreviewDialogMaxHeightDp,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(AetherSurfaceHigh),
            backgroundColor = AetherSurfaceHigh,
        )
    }
}

@Composable
private fun MarkdownPreviewDialogFrame(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 24.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(AetherSurface.copy(alpha = 0.98f))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = AetherOnSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.common_close),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AetherPrimary,
                    modifier = Modifier.clickable(onClick = onDismiss),
                )
            }
            content()
        }
    }
}

@Composable
private fun MarkdownBitmapPreviewBlock(
    bitmap: ImageBitmap,
    altText: String,
    maxHeightDp: Int,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val maxHeight = maxHeightDp.dp
        val naturalHeight = if (bitmap.width > 0 && bitmap.height > 0) {
            maxWidth * (bitmap.height.toFloat() / bitmap.width.toFloat())
        } else {
            maxHeight
        }
        val containerHeight = naturalHeight.coerceAtMost(maxHeight).coerceAtLeast(1.dp)
        val needsVerticalScroll = naturalHeight > containerHeight

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(containerHeight)
                .padding(12.dp),
            contentAlignment = if (needsVerticalScroll) Alignment.TopCenter else Alignment.Center,
        ) {
            if (needsVerticalScroll) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState()),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = altText.takeIf { it.isNotBlank() },
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Fit,
                    )
                }
            } else {
                Image(
                    bitmap = bitmap,
                    contentDescription = altText.takeIf { it.isNotBlank() },
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}

private sealed interface MarkdownHtmlTextVariant {
    data object Paragraph : MarkdownHtmlTextVariant
    data class Heading(val level: Int) : MarkdownHtmlTextVariant
    data object ListItem : MarkdownHtmlTextVariant
    data object Quote : MarkdownHtmlTextVariant
    data class TableCell(val isHeader: Boolean) : MarkdownHtmlTextVariant
}

@Composable
private fun MarkdownRichTextBlock(
    text: MarkdownSourceText,
    style: TextStyle,
    color: Color,
    onLinkClick: (String) -> Unit,
    variant: MarkdownHtmlTextVariant,
    fadeSpan: MarkdownFadeSpan?,
    modifier: Modifier = Modifier,
) {
    val shouldUseMathHtml = remember(text.text) {
        containsRenderableMarkdownMath(text.text)
    }
    if (shouldUseMathHtml) {
        MarkdownTextHtmlBlock(
            text = text.text,
            variant = variant,
            modifier = modifier,
            onLinkClick = onLinkClick,
        )
        return
    }

    // Building the AnnotatedString walks the whole span tree; without this it happened on
    // every recomposition of every visible paragraph.
    val citationLabels = LocalMarkdownCitationLabels.current
    val places = LocalAmapPlaces.current
    val annotated = remember(text.text, text.sourceOffset, fadeSpan, citationLabels, places, onLinkClick) {
        inlineMarkdown(text.text, text.sourceOffset, fadeSpan, citationLabels, onLinkClick)
    }
    val citationInline = remember(citationLabels, onLinkClick) {
        markdownCitationInlineContent(citationLabels, onLinkClick)
    }
    val placeInline = remember(places, onLinkClick) {
        markdownAmapPlaceInlineContent(places, onLinkClick)
    }
    MarkdownText(
        text = annotated,
        style = style,
        color = color,
        modifier = modifier,
        onLinkClick = onLinkClick,
        inlineContent = citationInline + placeInline,
    )
}

@Composable
private fun MarkdownTextHtmlBlock(
    text: String,
    variant: MarkdownHtmlTextVariant,
    modifier: Modifier = Modifier,
    onLinkClick: (String) -> Unit,
) {
    val citationLabels = LocalMarkdownCitationLabels.current
    val places = LocalAmapPlaces.current
    val html = remember(text, variant, citationLabels, places) {
        buildMarkdownTextHtml(
            text = text,
            variant = variant,
            citationLabels = citationLabels,
            amapPlaces = places,
        )
    }
    MarkdownHtmlBlock(
        html = html,
        layout = MarkdownMediaLayout(showAll = true),
        defaultMinHeightDp = DefaultTextBlockMinHeightDp,
        defaultMaxHeightDp = DefaultTextBlockMaxHeightDp,
        modifier = modifier.fillMaxWidth(),
        backgroundColor = Color.Transparent,
        onLinkClick = onLinkClick,
    )
}

private fun markdownCitationInlineId(index: Int): String = "aether-cite-$index"

private fun markdownAmapPlaceInlineId(id: String): String = "aether-amap-$id"

private fun markdownAmapPlaceInlineContent(
    places: List<AmapPlace>,
    @Suppress("UNUSED_PARAMETER") onLinkClick: (String) -> Unit,
): Map<String, InlineTextContent> {
    if (places.isEmpty()) return emptyMap()
    return places.associate { place ->
        val label = place.name.ifBlank { place.id }
        markdownAmapPlaceInlineId(place.id) to InlineTextContent(
            Placeholder(
                width = (label.length * 0.62f + 0.35f).em,
                height = 1.25.em,
                placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
            ),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = AetherOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun markdownCitationInlineContent(
    citationLabels: Map<Int, String>,
    onLinkClick: (String) -> Unit,
): Map<String, InlineTextContent> {
    if (citationLabels.isEmpty()) return emptyMap()
    return citationLabels.map { (index, label) ->
        val chip = label.ifBlank { index.toString() }
        markdownCitationInlineId(index) to InlineTextContent(
            Placeholder(
                width = (chip.length * 0.62f + 1.55f).em,
                height = 1.2.em,
                placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
            ),
        ) {
            Text(
                text = chip,
                style = MaterialTheme.typography.labelSmall,
                color = AetherOnSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(AetherSurfaceHigh)
                    .clickable { onLinkClick(knowledgeCitationUrl(index)) }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }.toMap()
}

@Composable
private fun MarkdownText(
    text: AnnotatedString,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    onLinkClick: (String) -> Unit,
    inlineContent: Map<String, InlineTextContent> = emptyMap(),
) {
    val hasLegacyLinks = text.getStringAnnotations(
        tag = LinkAnnotationTag,
        start = 0,
        end = text.length,
    ).isNotEmpty()

    if (inlineContent.isNotEmpty() || !hasLegacyLinks) {
        Text(
            text = text,
            style = style,
            color = color,
            modifier = modifier,
            inlineContent = inlineContent,
        )
        return
    }

    ClickableText(
        text = text,
        style = style.copy(color = color),
        modifier = modifier,
    ) { offset ->
        text.getStringAnnotations(
            tag = LinkAnnotationTag,
            start = offset,
            end = offset,
        ).firstOrNull()?.let { annotation ->
            onLinkClick(annotation.item)
        }
    }
}

// Parsed blocks are cached and shared across composables and background parses, so they
// must be treated as values that are never mutated after construction.
@Immutable
internal sealed interface MarkdownBlock {
    data class Paragraph(val text: MarkdownSourceText) : MarkdownBlock
    data class Heading(val level: Int, val text: MarkdownSourceText) : MarkdownBlock
    data class UnorderedList(val items: List<MarkdownSourceText>) : MarkdownBlock
    data class OrderedList(val items: List<MarkdownSourceText>) : MarkdownBlock
    data class Quote(val text: MarkdownSourceText) : MarkdownBlock
    data class Image(val image: MarkdownImageSpec) : MarkdownBlock
    data class ImageGroup(val images: List<MarkdownImageSpec>) : MarkdownBlock
    data class Mermaid(val diagram: MarkdownMermaidSpec) : MarkdownBlock
    data class Table(
        val headers: List<MarkdownSourceText>,
        val rows: List<List<MarkdownSourceText>>,
    ) : MarkdownBlock
    data class CodeFence(val code: MarkdownSourceText) : MarkdownBlock
    data class AmapCards(
        val placeIds: List<String>,
        val startIndex: Int,
        val sourceOffset: Int,
    ) : MarkdownBlock
    data class BrowserAct(
        val label: String,
        val url: String,
        val sourceOffset: Int,
    ) : MarkdownBlock
    data class BrowserResume(
        val kind: String,
        val sourceOffset: Int,
    ) : MarkdownBlock
    /** A row of follow-ups the model offered. Carries no URL: tapping one sends its text. */
    data class Suggest(
        val items: List<SuggestMarker>,
        val sourceOffset: Int,
    ) : MarkdownBlock
    data object Rule : MarkdownBlock
}

private fun MarkdownSourceText.sourceEndOffset(): Int = sourceOffset + text.length

private fun MarkdownBlock.sourceEndOffset(): Int = when (this) {
    is MarkdownBlock.Paragraph -> text.sourceEndOffset()
    is MarkdownBlock.Heading -> text.sourceEndOffset()
    is MarkdownBlock.UnorderedList -> items.lastOrNull()?.sourceEndOffset() ?: Int.MAX_VALUE
    is MarkdownBlock.OrderedList -> items.lastOrNull()?.sourceEndOffset() ?: Int.MAX_VALUE
    is MarkdownBlock.Quote -> text.sourceEndOffset()
    is MarkdownBlock.Table ->
        (rows.lastOrNull()?.lastOrNull() ?: headers.lastOrNull())?.sourceEndOffset() ?: Int.MAX_VALUE
    is MarkdownBlock.CodeFence -> code.sourceEndOffset()
    is MarkdownBlock.AmapCards -> sourceOffset + 1
    is MarkdownBlock.BrowserAct -> sourceOffset + 1
    is MarkdownBlock.Suggest -> sourceOffset + 1
    is MarkdownBlock.BrowserResume -> sourceOffset + 1
    // Blocks that never consume the fade span; treat as always intersecting.
    is MarkdownBlock.Image,
    is MarkdownBlock.ImageGroup,
    is MarkdownBlock.Mermaid,
    MarkdownBlock.Rule,
    -> Int.MAX_VALUE
}

private class IncrementalMarkdownParser {
    private var lastStablePrefix = ""
    private var lastStableBlocks: List<MarkdownBlock> = emptyList()
    private var lastMarkdown = ""
    private var lastBlocks: List<MarkdownBlock> = emptyList()

    fun parse(markdown: String): List<MarkdownBlock> {
        if (markdown == lastMarkdown) return lastBlocks
        if (lastStablePrefix.isNotEmpty() &&
            markdown.startsWith(lastStablePrefix) &&
            !markdownOpenFence(markdown)
        ) {
            val lastBreak = markdown.lastIndexOf("\n\n")
            val stablePrefix = if (lastBreak >= 32) markdown.substring(0, lastBreak + 2) else lastStablePrefix
            if (stablePrefix == lastStablePrefix) {
                val tail = parseMarkdown(markdown.substring(lastStablePrefix.length))
                lastMarkdown = markdown
                lastBlocks = lastStableBlocks + tail
                return lastBlocks
            }
        }
        return fullParse(markdown)
    }

    private fun fullParse(markdown: String): List<MarkdownBlock> {
        val blocks = parseMarkdown(markdown)
        val lastBreak = markdown.lastIndexOf("\n\n")
        lastStablePrefix = if (lastBreak >= 32) markdown.substring(0, lastBreak + 2) else ""
        lastStableBlocks = if (lastStablePrefix.isEmpty()) {
            emptyList()
        } else {
            parseMarkdown(lastStablePrefix)
        }
        lastMarkdown = markdown
        lastBlocks = blocks
        return blocks
    }
}

private fun markdownOpenFence(markdown: String): Boolean {
    var count = 0
    var index = 0
    while (true) {
        val at = markdown.indexOf("```", index)
        if (at < 0) break
        val lineStart = markdown.lastIndexOf('\n', at - 1) + 1
        if (markdown.substring(lineStart, at).isBlank()) count++
        index = at + 3
    }
    return count % 2 == 1
}

internal fun parseMarkdownBlocks(markdown: String): List<MarkdownBlock> = parseMarkdown(markdown)

private fun parseMarkdown(markdown: String): List<MarkdownBlock> {
    val lines = splitMarkdownLines(markdown)
    val blocks = mutableListOf<MarkdownBlock>()
    var index = 0

    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.text.trim()

        if (trimmed.isBlank()) {
            index++
            continue
        }

        if (trimmed.startsWith("```")) {
            val fenceHeader = parseMarkdownCodeFenceHeader(trimmed)
            val fenceLanguage = fenceHeader.language.lowercase()
            index++
            val codeStartOffset = if (index < lines.size) lines[index].startOffset else line.startOffset + line.text.length
            val codeLines = mutableListOf<String>()
            while (index < lines.size && !lines[index].text.trim().startsWith("```")) {
                codeLines += lines[index].text
                index++
            }
            val closed = index < lines.size && lines[index].text.trim().startsWith("```")
            if (index < lines.size) index++
            val code = MarkdownSourceText(
                text = codeLines.joinToString("\n"),
                sourceOffset = codeStartOffset,
            )
            blocks += if (fenceLanguage == "mermaid" && closed) {
                MarkdownBlock.Mermaid(
                    MarkdownMermaidSpec(
                        code = code,
                        layout = parseMarkdownMediaLayout(
                            attributes = fenceHeader.attributes,
                            defaults = defaultMarkdownMermaidLayout(),
                        ),
                    )
                )
            } else {
                MarkdownBlock.CodeFence(code)
            }
            continue
        }

        val setextHeadingLevel = setextHeadingLevel(lines, index)
        if (setextHeadingLevel != null) {
            blocks += MarkdownBlock.Heading(
                level = setextHeadingLevel,
                text = MarkdownSourceText(
                    text = trimmed,
                    sourceOffset = line.startOffset + line.text.indexOf(trimmed).coerceAtLeast(0),
                ),
            )
            index += 2
            continue
        }

        val headingMatch = headingPattern.matchEntire(trimmed)
        if (headingMatch != null) {
            blocks += MarkdownBlock.Heading(
                level = headingMatch.groupValues[1].length,
                text = MarkdownSourceText(
                    text = headingMatch.groupValues[2].trim(),
                    sourceOffset = contentStartOffset(
                        line = line,
                        trimmedLine = trimmed,
                        markerLength = headingMatch.groupValues[1].length,
                    ),
                ),
            )
            index++
            continue
        }

        if (horizontalRulePattern.matches(trimmed)) {
            blocks += MarkdownBlock.Rule
            index++
            continue
        }

        val amapCards = parseAmapPlaceCardsMarker(trimmed)
        if (amapCards != null) {
            blocks += MarkdownBlock.AmapCards(
                placeIds = amapCards.ids,
                startIndex = amapCards.startIndex,
                sourceOffset = line.startOffset,
            )
            index++
            continue
        }

        val browserAct = parseBrowserActMarker(trimmed)
        if (browserAct != null) {
            blocks += MarkdownBlock.BrowserAct(
                label = browserAct.label,
                url = browserAct.url,
                sourceOffset = line.startOffset,
            )
            index++
            continue
        }
        val browserResume = parseBrowserResumeKind(trimmed)
        if (browserResume != null) {
            blocks += MarkdownBlock.BrowserResume(
                kind = browserResume,
                sourceOffset = line.startOffset,
            )
            index++
            continue
        }
        if (trimmed.contains(SuggestPrefix)) {
            // One line can hold several chips, so the whole line becomes one row.
            val suggestions = suggestMarkersIn(trimmed)
            if (suggestions.isNotEmpty()) {
                blocks += MarkdownBlock.Suggest(
                    items = suggestions,
                    sourceOffset = line.startOffset,
                )
            }
            index++
            continue
        }
        if (trimmed.startsWith(BrowserActPrefix) || trimmed.startsWith(BrowserResumePrefix)) {
            index++
            continue
        }

        if (trimmed.startsWith(">")) {
            val quoteLines = mutableListOf<String>()
            val quoteStartOffset = contentStartOffset(
                line = line,
                trimmedLine = trimmed,
                markerLength = 1,
            )
            while (index < lines.size && lines[index].text.trim().startsWith(">")) {
                quoteLines += lines[index].text.trim().removePrefix(">").trimStart()
                index++
            }
            blocks += MarkdownBlock.Quote(
                MarkdownSourceText(
                    text = quoteLines.joinToString("\n"),
                    sourceOffset = quoteStartOffset,
                )
            )
            continue
        }

        if (looksLikeMarkdownTable(lines, index)) {
            val headerLine = lines[index]
            val headerCells = parseMarkdownTableCells(headerLine)
            val columnCount = headerCells.size
            index += 2

            val rows = mutableListOf<List<MarkdownSourceText>>()
            while (index < lines.size) {
                val candidate = lines[index]
                if (candidate.text.trim().isBlank() || !looksLikeMarkdownTableDataRow(candidate.text, columnCount)) {
                    break
                }
                rows += normalizeMarkdownTableRow(
                    cells = parseMarkdownTableCells(candidate),
                    columnCount = columnCount,
                    line = candidate,
                )
                index++
            }

            blocks += MarkdownBlock.Table(
                headers = normalizeMarkdownTableRow(
                    cells = headerCells,
                    columnCount = columnCount,
                    line = headerLine,
                ),
                rows = rows,
            )
            continue
        }

        if (unorderedPattern.matches(trimmed)) {
            val items = mutableListOf<MarkdownSourceText>()
            while (index < lines.size) {
                val candidate = lines[index]
                val match = unorderedPattern.matchEntire(candidate.text.trim())
                if (match == null) break
                items += MarkdownSourceText(
                    text = match.groupValues[1],
                    sourceOffset = contentStartOffset(
                        line = candidate,
                        trimmedLine = candidate.text.trim(),
                        markerLength = 1,
                    ),
                )
                index++
            }
            val listImages = imagesFromMarkdownListItems(items)
            blocks += if (listImages != null) {
                markdownImageBlock(listImages)
            } else {
                MarkdownBlock.UnorderedList(items)
            }
            continue
        }

        if (orderedPattern.matches(trimmed)) {
            val items = mutableListOf<MarkdownSourceText>()
            while (index < lines.size) {
                val candidate = lines[index]
                val candidateTrimmed = candidate.text.trim()
                val match = orderedPattern.matchEntire(candidateTrimmed)
                if (match == null) break
                items += MarkdownSourceText(
                    text = match.groupValues[2],
                    sourceOffset = contentStartOffset(
                        line = candidate,
                        trimmedLine = candidateTrimmed,
                        markerLength = match.groupValues[1].length + 1,
                    ),
                )
                index++
            }
            val listImages = imagesFromMarkdownListItems(items)
            blocks += if (listImages != null) {
                markdownImageBlock(listImages)
            } else {
                MarkdownBlock.OrderedList(items)
            }
            continue
        }

        val images = parseMarkdownImageSequence(trimmed)
            ?: parseMarkdownImageOnlyListLine(trimmed)
        if (images != null) {
            val grouped = images.toMutableList()
            index++
            while (index < lines.size) {
                val next = lines[index].text.trim()
                if (next.isBlank()) {
                    val peek = lines.getOrNull(index + 1)?.text?.trim().orEmpty()
                    val more = parseMarkdownImageSequence(peek)
                        ?: parseMarkdownImageOnlyListLine(peek)
                    if (more != null &&
                        markdownImagesShareTopic(grouped.last().altText, more.first().altText)
                    ) {
                        index++
                        continue
                    }
                    break
                }
                val more = parseMarkdownImageSequence(next)
                    ?: parseMarkdownImageOnlyListLine(next)
                    ?: break
                if (!markdownImagesShareTopic(grouped.last().altText, more.first().altText)) break
                grouped += more
                index++
            }
            blocks += markdownImageBlock(grouped)
            continue
        }

        val paragraphLines = mutableListOf<String>()
        val paragraphStartOffset = line.startOffset
        while (index < lines.size) {
            val candidate = lines[index].text
            if (candidate.trim().isBlank() || beginsSpecialBlock(lines, index)) {
                break
            }
            paragraphLines += candidate.trimEnd()
            index++
        }
        if (paragraphLines.isEmpty()) {
            paragraphLines += line.text.trimEnd()
            index++
        }
        blocks += MarkdownBlock.Paragraph(
            MarkdownSourceText(
                text = paragraphLines.joinToString("\n"),
                sourceOffset = paragraphStartOffset,
            )
        )
    }

    return coalesceAdjacentMarkdownImageBlocks(blocks)
}

private fun markdownImageBlock(images: List<MarkdownImageSpec>): MarkdownBlock =
    if (images.size == 1) {
        MarkdownBlock.Image(images.single())
    } else {
        MarkdownBlock.ImageGroup(images)
    }

private fun imagesFromMarkdownListItems(items: List<MarkdownSourceText>): List<MarkdownImageSpec>? {
    if (items.isEmpty()) return null
    val images = ArrayList<MarkdownImageSpec>(items.size)
    for (item in items) {
        val parsed = parseMarkdownImageSequence(item.text.trim()) ?: return null
        images += parsed
    }
    return images.takeIf { it.isNotEmpty() }
}

private fun parseMarkdownImageOnlyListLine(line: String): List<MarkdownImageSpec>? {
    val trimmed = line.trim()
    val content = unorderedPattern.matchEntire(trimmed)?.groupValues?.get(1)
        ?: orderedPattern.matchEntire(trimmed)?.groupValues?.get(2)
        ?: return null
    return parseMarkdownImageSequence(content.trim())
}

private fun markdownBlockImages(block: MarkdownBlock): List<MarkdownImageSpec>? = when (block) {
    is MarkdownBlock.Image -> listOf(block.image)
    is MarkdownBlock.ImageGroup -> block.images
    else -> null
}

internal fun coalesceAdjacentMarkdownImageBlocks(blocks: List<MarkdownBlock>): List<MarkdownBlock> {
    if (blocks.size < 2) return blocks
    val merged = ArrayList<MarkdownBlock>(blocks.size)
    for (block in blocks) {
        val images = markdownBlockImages(block)
        val previous = merged.lastOrNull()
        val previousImages = previous?.let(::markdownBlockImages)
        if (images != null && previousImages != null &&
            markdownImagesShareTopic(previousImages.last().altText, images.first().altText)
        ) {
            merged[merged.lastIndex] = markdownImageBlock(previousImages + images)
        } else {
            merged += block
        }
    }
    return merged
}

private fun splitMarkdownLines(markdown: String): List<MarkdownLine> {
    val lines = mutableListOf<MarkdownLine>()
    var start = 0
    for (index in 0..markdown.length) {
        if (index == markdown.length || markdown[index] == '\n') {
            lines += MarkdownLine(
                text = markdown.substring(start, index),
                startOffset = start,
            )
            start = index + 1
        }
    }
    return lines
}

private fun contentStartOffset(
    line: MarkdownLine,
    trimmedLine: String,
    markerLength: Int,
): Int {
    val trimmedOffset = line.text.indexOf(trimmedLine).coerceAtLeast(0)
    var contentOffset = line.startOffset + trimmedOffset + markerLength
    val lineEndOffset = line.startOffset + line.text.length
    while (contentOffset < lineEndOffset && line.text[contentOffset - line.startOffset].isWhitespace()) {
        contentOffset++
    }
    return contentOffset
}

private fun beginsSpecialBlock(
    lines: List<MarkdownLine>,
    index: Int,
): Boolean {
    val line = lines.getOrNull(index)?.text ?: return false
    val trimmed = line.trim()
    return trimmed.startsWith("```") ||
        looksLikeMarkdownImageLine(trimmed) ||
        setextHeadingLevel(lines, index) != null ||
        looksLikeMarkdownTable(lines, index) ||
        headingPattern.matches(trimmed) ||
        unorderedPattern.matches(trimmed) ||
        orderedPattern.matches(trimmed) ||
        trimmed.startsWith(">") ||
        parseAmapPlaceCardsMarker(trimmed) != null ||
        parseBrowserActMarker(trimmed) != null ||
        parseBrowserResumeKind(trimmed) != null ||
        trimmed.startsWith(BrowserActPrefix) ||
        trimmed.startsWith(BrowserResumePrefix) ||
        trimmed.contains(SuggestPrefix) ||
        horizontalRulePattern.matches(trimmed)
}

private val headingPattern = Regex("^(#{1,6})\\s+(.+)$")
private val unorderedPattern = Regex("^[-*+]\\s+(.+)$")
private val orderedPattern = Regex("^(\\d+)[.)]\\s+(.+)$")
private val setextHeadingPattern = Regex("^\\s*(=+|-+)\\s*$")
private val horizontalRulePattern = Regex(
    "^(?:(?:\\*\\s*){3,}|(?:-\\s*){3,}|(?:_\\s*){3,})$"
)
private val markdownTableSeparatorPattern = Regex("^:?-{3,}:?$")
private val autoLinkPattern = Regex("""^(https?://\S+|www\.\S+)""")

private fun setextHeadingLevel(
    lines: List<MarkdownLine>,
    index: Int,
): Int? {
    val heading = lines.getOrNull(index)?.text?.trim().orEmpty()
    if (heading.isBlank() || beginsStandaloneMarkdownBlock(heading)) return null
    val underline = lines.getOrNull(index + 1)?.text ?: return null
    val match = setextHeadingPattern.matchEntire(underline) ?: return null
    return if (match.groupValues[1].startsWith("=")) 1 else 2
}

private fun beginsStandaloneMarkdownBlock(trimmed: String): Boolean =
    trimmed.startsWith("```") ||
        trimmed.startsWith(">") ||
        headingPattern.matches(trimmed) ||
        unorderedPattern.matches(trimmed) ||
        orderedPattern.matches(trimmed) ||
        parseMarkdownImageSequence(trimmed) != null ||
        parseBrowserActMarker(trimmed) != null ||
        parseBrowserResumeKind(trimmed) != null ||
        trimmed.startsWith(BrowserActPrefix) ||
        trimmed.startsWith(BrowserResumePrefix) ||
        trimmed.contains(SuggestPrefix) ||
        horizontalRulePattern.matches(trimmed)

private fun looksLikeMarkdownTable(
    lines: List<MarkdownLine>,
    index: Int,
): Boolean {
    if (index + 1 >= lines.size) return false
    val headerCells = parseMarkdownTableCells(lines[index])
    if (headerCells.size < 2) return false
    return isMarkdownTableSeparator(
        line = lines[index + 1].text,
        expectedColumns = headerCells.size,
    )
}

private fun looksLikeMarkdownTableLine(line: String): Boolean =
    line.count { it == '|' } >= 1

private fun looksLikeMarkdownImageLine(line: String): Boolean =
    parseMarkdownImageSequence(line) != null

private fun looksLikeMarkdownTableDataRow(
    line: String,
    expectedColumns: Int,
): Boolean {
    if (!looksLikeMarkdownTableLine(line)) return false
    return splitMarkdownTableCells(line).size == expectedColumns
}

private fun isMarkdownTableSeparator(
    line: String,
    expectedColumns: Int,
): Boolean {
    val cells = splitMarkdownTableCells(line)
    if (cells.size != expectedColumns) return false
    return cells.all { markdownTableSeparatorPattern.matches(it.trim()) }
}

private fun parseMarkdownTableCells(line: MarkdownLine): List<MarkdownSourceText> =
    splitMarkdownTableCellsWithOffsets(line.text).map { (cellText, startOffset) ->
        val trimmedCell = cellText.trim()
        val leadingWhitespace = cellText.indexOfFirst { !it.isWhitespace() }
            .let { if (it < 0) cellText.length else it }
        MarkdownSourceText(
            text = trimmedCell,
            sourceOffset = line.startOffset + startOffset + leadingWhitespace,
        )
    }

private fun normalizeMarkdownTableRow(
    cells: List<MarkdownSourceText>,
    columnCount: Int,
    line: MarkdownLine,
): List<MarkdownSourceText> {
    if (cells.size >= columnCount) return cells.take(columnCount)
    val trailingOffset = line.startOffset + line.text.length
    return cells + List(columnCount - cells.size) {
        MarkdownSourceText(text = "", sourceOffset = trailingOffset)
    }
}

private fun splitMarkdownTableCells(line: String): List<String> =
    splitMarkdownTableCellsWithOffsets(line).map { it.first }

private fun splitMarkdownTableCellsWithOffsets(line: String): List<Pair<String, Int>> {
    if ('|' !in line) return emptyList()

    val pipeIndices = line.indices.filter { line[it] == '|' }
    val cells = mutableListOf<Pair<String, Int>>()
    var segmentStart = 0

    if (pipeIndices.isNotEmpty() && line.substring(0, pipeIndices.first()).isBlank()) {
        segmentStart = pipeIndices.first() + 1
    }

    pipeIndices.forEach { pipeIndex ->
        if (pipeIndex < segmentStart) return@forEach
        val cellText = line.substring(segmentStart, pipeIndex)
        val isTrailingEmptyCell = pipeIndex == line.lastIndex && cellText.isBlank()
        if (!isTrailingEmptyCell) {
            cells += cellText to segmentStart
        }
        segmentStart = pipeIndex + 1
    }

    if (segmentStart <= line.length) {
        val tail = line.substring(segmentStart)
        val hasExplicitTrailingPipe = line.trimEnd().endsWith("|")
        if (!(hasExplicitTrailingPipe && tail.isBlank())) {
            cells += tail to segmentStart
        }
    }

    return cells
}

private data class MarkdownInlineLinkMatch(
    val label: String,
    val destination: String,
    val endExclusive: Int,
)

private data class MarkdownMathMatch(
    val rawText: String,
    val endExclusive: Int,
)

private fun buildMarkdownTextHtml(
    text: String,
    variant: MarkdownHtmlTextVariant,
    citationLabels: Map<Int, String> = emptyMap(),
    amapPlaces: List<AmapPlace> = emptyList(),
): String {
    val contentHtml = inlineMarkdownToHtml(text, citationLabels, amapPlaces)
    val textColor = AetherOnSurface.toCssHex()
    val linkColor = AetherPrimary.toCssHex()
    val citeColor = AetherOnSurfaceVariant.toCssHex()
    val citeBackground = AetherSurfaceHigh.toCssHex()
    val codeBackgroundColor = AetherSurfaceHigh.toCssHex()
    val variantCss = buildMarkdownTextVariantCss(variant)
    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0" />
            <link rel="stylesheet" href="$KatexCssUrl" />
            <style>
                html, body {
                    margin: 0;
                    padding: 0;
                    background: transparent;
                }
                body {
                    color: $textColor;
                    font-family: sans-serif;
                }
                .aether-text {
                    $variantCss
                    white-space: pre-wrap;
                    overflow-wrap: anywhere;
                    word-break: break-word;
                }
                .aether-text a {
                    color: $linkColor;
                    text-decoration: none;
                }
                .aether-text a.aether-cite {
                    color: $citeColor;
                    background: $citeBackground;
                    border-radius: 999px;
                    padding: 0.08em 0.5em;
                    font-size: 11px;
                    font-weight: 500;
                    text-decoration: none;
                    vertical-align: baseline;
                    white-space: nowrap;
                }
                .aether-text a.aether-amap,
                .aether-text span.aether-amap {
                    color: $textColor;
                    text-decoration: none;
                    border-bottom: none;
                    white-space: nowrap;
                }
                .aether-text strong {
                    font-weight: 600;
                }
                .aether-text em {
                    font-style: italic;
                }
                .aether-text code {
                    font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
                    font-size: 0.92em;
                    background: $codeBackgroundColor;
                    border-radius: 8px;
                    padding: 0.08em 0.34em;
                }
                .aether-text .katex {
                    white-space: normal;
                }
                .aether-text .katex-display {
                    margin: 0.45em 0;
                    overflow-x: auto;
                    overflow-y: hidden;
                    padding-bottom: 2px;
                }
                .aether-text .katex-display > .katex {
                    white-space: nowrap;
                }
            </style>
            <script defer src="$KatexScriptUrl"></script>
            <script defer src="$KatexAutoRenderScriptUrl"></script>
        </head>
        <body>
            <div class="aether-text">$contentHtml</div>
            <script>
                function reportAetherHeight() {
                    const height = Math.max(
                        document.documentElement.scrollHeight || 0,
                        document.body.scrollHeight || 0,
                        $DefaultTextBlockMinHeightDp
                    );
                    if (window.$MarkdownHtmlBridgeName && window.$MarkdownHtmlBridgeName.reportHeight) {
                        window.$MarkdownHtmlBridgeName.reportHeight(String(height));
                    }
                }

                function bindAetherLinks() {
                    const anchors = document.querySelectorAll('a[href]');
                    anchors.forEach(function(anchor) {
                        anchor.onclick = function(event) {
                            event.preventDefault();
                            const href = anchor.getAttribute('href');
                            if (href && window.$MarkdownHtmlBridgeName && window.$MarkdownHtmlBridgeName.reportLink) {
                                window.$MarkdownHtmlBridgeName.reportLink(href);
                            }
                            return false;
                        };
                    });
                }

                function renderAetherMath() {
                    bindAetherLinks();
                    try {
                        if (window.renderMathInElement) {
                            window.renderMathInElement(document.body, {
                                delimiters: [
                                    { left: '$$', right: '$$', display: true },
                                    { left: '\\\\[', right: '\\\\]', display: true },
                                    { left: '$', right: '$', display: false },
                                    { left: '\\\\(', right: '\\\\)', display: false }
                                ],
                                throwOnError: false,
                                strict: 'ignore',
                                ignoredTags: ['script', 'noscript', 'style', 'textarea', 'pre', 'code', 'option'],
                            });
                        }
                    } catch (error) {
                    } finally {
                        bindAetherLinks();
                        setTimeout(reportAetherHeight, 0);
                        setTimeout(reportAetherHeight, 120);
                        setTimeout(reportAetherHeight, 360);
                    }
                }

                window.reportAetherHeight = reportAetherHeight;
                window.addEventListener('resize', reportAetherHeight);
                window.addEventListener('load', function() {
                    setTimeout(renderAetherMath, 0);
                    setTimeout(reportAetherHeight, 120);
                });
            </script>
        </body>
        </html>
    """.trimIndent()
}

private fun buildMarkdownTextVariantCss(
    variant: MarkdownHtmlTextVariant,
): String = when (variant) {
    MarkdownHtmlTextVariant.Paragraph,
    MarkdownHtmlTextVariant.ListItem,
    MarkdownHtmlTextVariant.Quote -> "font-size: 16px; line-height: 24px; font-weight: 400;"
    is MarkdownHtmlTextVariant.Heading -> when (variant.level) {
        1 -> "font-size: 28px; line-height: 34px; font-weight: 600;"
        2 -> "font-size: 22px; line-height: 30px; font-weight: 600;"
        3 -> "font-size: 18px; line-height: 26px; font-weight: 600;"
        else -> "font-size: 14px; line-height: 20px; font-weight: 600;"
    }
    is MarkdownHtmlTextVariant.TableCell -> if (variant.isHeader) {
        "font-size: 14px; line-height: 20px; font-weight: 600;"
    } else {
        "font-size: 14px; line-height: 20px; font-weight: 400;"
    }
}

private fun inlineMarkdownToHtml(
    text: String,
    citationLabels: Map<Int, String> = emptyMap(),
    amapPlaces: List<AmapPlace> = emptyList(),
): String = buildString {
    appendInlineHtml(text, citationLabels, amapPlaces)
}

private fun StringBuilder.appendCitationChipHtml(
    index: Int,
    citationLabels: Map<Int, String>,
) {
    val label = citationLabels[index] ?: index.toString()
    append("<a class=\"aether-cite\" href=\"")
    append(escapeHtml(knowledgeCitationUrl(index)))
    append("\">")
    append(escapeHtml(label))
    append("</a>")
}

private fun StringBuilder.appendAmapPlaceHtml(
    id: String,
    amapPlaces: List<AmapPlace>,
) {
    val place = amapPlaces.firstOrNull { it.id == id }
    val label = place?.name?.ifBlank { id } ?: id
    append("<span class=\"aether-amap\">")
    append(escapeHtml(label))
    append("</span>")
}

private fun StringBuilder.appendInlineHtml(
    text: String,
    citationLabels: Map<Int, String>,
    amapPlaces: List<AmapPlace> = emptyList(),
) {
    var index = 0
    while (index < text.length) {
        val mathMatch = parseMarkdownMathAt(text, index)
        if (mathMatch != null) {
            append(escapeHtml(mathMatch.rawText))
            index = mathMatch.endExclusive
            continue
        }

        if (text.startsWith("**", index)) {
            val end = text.indexOf("**", index + 2)
            if (end > index + 2) {
                append("<strong>")
                appendInlineHtml(text.substring(index + 2, end), citationLabels, amapPlaces)
                append("</strong>")
                index = end + 2
                continue
            }
        }

        if (text.startsWith("`", index)) {
            val end = text.indexOf('`', index + 1)
            if (end > index + 1) {
                append("<code>")
                append(escapeHtml(text.substring(index + 1, end)))
                append("</code>")
                index = end + 1
                continue
            }
        }

        val amapMatch = parseAmapPlaceMarker(text, index)
        if (amapMatch != null) {
            appendAmapPlaceHtml(amapMatch.id, amapPlaces)
            index = amapMatch.endExclusive
            continue
        }

        val citationMatch = parseKnowledgeCitationMarker(text, index)
        if (citationMatch != null) {
            appendCitationChipHtml(citationMatch.index, citationLabels)
            index = citationMatch.endExclusive
            continue
        }

        if (text.startsWith("![", index)) {
            val imageMatch = parseInlineMarkdownLink(text, index + 1)
            if (imageMatch != null) {
                append(escapeHtml(imageMatch.label))
                index = imageMatch.endExclusive
                continue
            }
        }

        val linkMatch = parseInlineMarkdownLink(text, index)
        if (linkMatch != null) {
            val placeId = parseAmapPlaceUrl(linkMatch.destination)
            if (placeId != null) {
                appendAmapPlaceHtml(placeId, amapPlaces)
                index = linkMatch.endExclusive
                continue
            }
            val citationIndex = parseKnowledgeCitationUrl(linkMatch.destination)
            if (citationIndex != null) {
                appendCitationChipHtml(citationIndex, citationLabels)
                index = linkMatch.endExclusive
                continue
            }
            append("<a href=\"")
            append(escapeHtml(linkMatch.destination))
            append("\">")
            appendInlineHtml(linkMatch.label, citationLabels, amapPlaces)
            append("</a>")
            index = linkMatch.endExclusive
            continue
        }

        val autoLink = parseAutoLink(text, index)
        if (autoLink != null) {
            append("<a href=\"")
            append(escapeHtml(autoLink.targetUrl))
            append("\">")
            append(escapeHtml(autoLink.displayText))
            append("</a>")
            index += autoLink.displayText.length
            continue
        }

        if (text.startsWith("*", index)) {
            val end = text.indexOf('*', index + 1)
            if (end > index + 1) {
                append("<em>")
                appendInlineHtml(text.substring(index + 1, end), citationLabels, amapPlaces)
                append("</em>")
                index = end + 1
                continue
            }
        }

        append(escapeHtml(text[index].toString()))
        index++
    }
}

private fun containsRenderableMarkdownMath(
    text: String,
): Boolean {
    var index = 0
    while (index < text.length) {
        if (text.startsWith("`", index)) {
            val codeEnd = text.indexOf('`', index + 1)
            if (codeEnd > index) {
                index = codeEnd + 1
                continue
            }
        }
        val mathMatch = parseMarkdownMathAt(text, index)
        if (mathMatch != null) return true
        index++
    }
    return false
}

private fun parseMarkdownMathAt(
    text: String,
    startIndex: Int,
): MarkdownMathMatch? {
    if (startIndex !in text.indices) return null
    val openingDelimiter = when {
        text.startsWith("$$", startIndex) -> "$$"
        text.startsWith("\\[", startIndex) -> "\\["
        text.startsWith("\\(", startIndex) -> "\\("
        text[startIndex] == '$' -> "$"
        else -> return null
    }
    val closingDelimiter = when (openingDelimiter) {
        "$$" -> "$$"
        "\\[" -> "\\]"
        "\\(" -> "\\)"
        else -> "$"
    }
    val contentStart = startIndex + openingDelimiter.length
    if (contentStart >= text.length) return null

    var index = contentStart
    while (index < text.length) {
        if (text.startsWith(closingDelimiter, index)) {
            val content = text.substring(contentStart, index)
            if (!isRenderableMarkdownMathContent(openingDelimiter, content)) return null
            return MarkdownMathMatch(
                rawText = text.substring(startIndex, index + closingDelimiter.length),
                endExclusive = index + closingDelimiter.length,
            )
        }
        if (text[index] == '\\' && !text.startsWith(closingDelimiter, index) && index + 1 < text.length) {
            index += 2
            continue
        }
        index++
    }
    return null
}

private fun isRenderableMarkdownMathContent(
    openingDelimiter: String,
    content: String,
): Boolean {
    val trimmed = content.trim()
    if (trimmed.isBlank()) return false
    if (openingDelimiter != "$") return true
    if (trimmed.contains('\n')) return false
    return trimmed.any { it in "\\_^{}=+-*/()[]<>" } || trimmed.none(Char::isWhitespace)
}

private fun AnnotatedString.Builder.appendKnowledgeCitation(
    index: Int,
    label: String,
) {
    append('\u00A0')
    appendInlineContent(
        id = markdownCitationInlineId(index),
        alternateText = "\u00A0${label.ifBlank { index.toString() }}\u00A0",
    )
}

private fun AnnotatedString.Builder.appendAmapPlace(id: String, name: String) {
    appendInlineContent(
        id = markdownAmapPlaceInlineId(id),
        alternateText = name.ifBlank { id },
    )
}

private fun parseInlineMarkdownLink(
    text: String,
    startIndex: Int,
): MarkdownInlineLinkMatch? {
    if (startIndex !in text.indices || text[startIndex] != '[') return null
    val closeBracket = text.indexOf("](", startIndex)
    if (closeBracket <= startIndex) return null

    var index = closeBracket + 2
    var nestedParentheses = 0
    while (index < text.length) {
        val character = text[index]
        if (character == '\\' && index + 1 < text.length) {
            index += 2
            continue
        }
        when (character) {
            '(' -> nestedParentheses++
            ')' -> {
                if (nestedParentheses == 0) break
                nestedParentheses--
            }
        }
        index++
    }
    if (index >= text.length || text[index] != ')') return null

    val destination = extractMarkdownLinkDestination(
        text.substring(closeBracket + 2, index)
    ).orEmpty()
    if (destination.isBlank()) return null

    return MarkdownInlineLinkMatch(
        label = text.substring(startIndex + 1, closeBracket),
        destination = destination,
        endExclusive = index + 1,
    )
}

private fun inlineMarkdown(
    text: String,
    sourceOffset: Int,
    fadeSpan: MarkdownFadeSpan?,
    citationLabels: Map<Int, String> = emptyMap(),
    onLinkClick: (String) -> Unit = {},
): AnnotatedString = buildAnnotatedString {
    appendInline(text, sourceOffset, fadeSpan, citationLabels, onLinkClick)
}

private fun plainMarkdownText(
    text: String,
    sourceOffset: Int,
    fadeSpan: MarkdownFadeSpan?,
): AnnotatedString = buildAnnotatedString {
    appendSourceSegment(text, sourceOffset, fadeSpan)
}

private fun AnnotatedString.Builder.appendInline(
    text: String,
    sourceOffset: Int,
    fadeSpan: MarkdownFadeSpan?,
    citationLabels: Map<Int, String>,
    onLinkClick: (String) -> Unit = {},
) {
    var index = 0
    while (index < text.length) {
        val mathMatch = parseMarkdownMathAt(text, index)
        if (mathMatch != null) {
            appendSourceSegment(
                text = mathMatch.rawText,
                sourceOffset = sourceOffset + index,
                fadeSpan = fadeSpan,
            )
            index = mathMatch.endExclusive
            continue
        }

        if (text.startsWith("**", index)) {
            val end = text.indexOf("**", index + 2)
            if (end > index + 2) {
                pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold))
                appendInline(
                    text = text.substring(index + 2, end),
                    sourceOffset = sourceOffset + index + 2,
                    fadeSpan = fadeSpan,
                    citationLabels = citationLabels,
                    onLinkClick = onLinkClick,
                )
                pop()
                index = end + 2
                continue
            }
        }

        if (text.startsWith("`", index)) {
            val end = text.indexOf('`', index + 1)
            if (end > index + 1) {
                pushStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = AetherSurfaceHigh,
                    )
                )
                appendSourceSegment(
                    text = text.substring(index + 1, end),
                    sourceOffset = sourceOffset + index + 1,
                    fadeSpan = fadeSpan,
                )
                pop()
                index = end + 1
                continue
            }
        }

        val amapMatch = parseAmapPlaceMarker(text, index)
        if (amapMatch != null) {
            appendAmapPlace(amapMatch.id, amapMatch.id)
            index = amapMatch.endExclusive
            continue
        }

        val citationMatch = parseKnowledgeCitationMarker(text, index)
        if (citationMatch != null) {
            appendKnowledgeCitation(
                index = citationMatch.index,
                label = citationLabels[citationMatch.index] ?: citationMatch.index.toString(),
            )
            index = citationMatch.endExclusive
            continue
        }

        // An image the hoist could not lift out (a table cell, say). Render its alt text and
        // consume the whole token — letting it fall through prints a stray "!" and then turns the
        // alt text into a link to the image file, which is the "!老北京炸酱面" the user sees.
        if (text.startsWith("![", index)) {
            val imageMatch = parseInlineMarkdownLink(text, index + 1)
            if (imageMatch != null) {
                appendSourceSegment(
                    text = imageMatch.label,
                    sourceOffset = sourceOffset + index + 2,
                    fadeSpan = fadeSpan,
                )
                index = imageMatch.endExclusive
                continue
            }
        }

        val linkMatch = parseInlineMarkdownLink(text, index)
        if (linkMatch != null) {
            val placeId = parseAmapPlaceUrl(linkMatch.destination)
            if (placeId != null) {
                appendAmapPlace(placeId, placeId)
                index = linkMatch.endExclusive
                continue
            }
            val citationIndex = parseKnowledgeCitationUrl(linkMatch.destination)
            if (citationIndex != null) {
                appendKnowledgeCitation(
                    index = citationIndex,
                    label = citationLabels[citationIndex] ?: citationIndex.toString(),
                )
                index = linkMatch.endExclusive
                continue
            }
            withLink(
                LinkAnnotation.Clickable(
                    tag = linkMatch.destination,
                    styles = TextLinkStyles(style = SpanStyle(color = AetherPrimary)),
                    linkInteractionListener = { onLinkClick(linkMatch.destination) },
                ),
            ) {
                appendInline(
                    text = linkMatch.label,
                    sourceOffset = sourceOffset + index + 1,
                    fadeSpan = fadeSpan,
                    citationLabels = citationLabels,
                    onLinkClick = onLinkClick,
                )
            }
            index = linkMatch.endExclusive
            continue
        }

        val autoLink = parseAutoLink(text, index)
        if (autoLink != null) {
            withLink(
                LinkAnnotation.Clickable(
                    tag = autoLink.targetUrl,
                    styles = TextLinkStyles(style = SpanStyle(color = AetherPrimary)),
                    linkInteractionListener = { onLinkClick(autoLink.targetUrl) },
                ),
            ) {
                appendSourceSegment(
                    text = autoLink.displayText,
                    sourceOffset = sourceOffset + index,
                    fadeSpan = fadeSpan,
                )
            }
            index += autoLink.displayText.length
            continue
        }

        if (text.startsWith("*", index)) {
            val end = text.indexOf('*', index + 1)
            if (end > index + 1) {
                pushStyle(
                    SpanStyle(fontStyle = FontStyle.Italic)
                )
                appendInline(
                    text = text.substring(index + 1, end),
                    sourceOffset = sourceOffset + index + 1,
                    fadeSpan = fadeSpan,
                    citationLabels = citationLabels,
                    onLinkClick = onLinkClick,
                )
                pop()
                index = end + 1
                continue
            }
        }

        appendSourceSegment(
            text = text[index].toString(),
            sourceOffset = sourceOffset + index,
            fadeSpan = fadeSpan,
        )
        index++
    }
}

private fun parseAutoLink(
    text: String,
    startIndex: Int,
): MarkdownAutoLinkMatch? {
    val rawMatch = autoLinkPattern.find(text.substring(startIndex)) ?: return null
    if (rawMatch.range.first != 0) return null
    val displayText = rawMatch.value.trimEnd('.', ',', ';', ':')
    if (displayText.isBlank()) return null
    val targetUrl = if (displayText.startsWith("www.", ignoreCase = true)) {
        "https://$displayText"
    } else {
        displayText
    }
    return MarkdownAutoLinkMatch(
        displayText = displayText,
        targetUrl = targetUrl,
    )
}

internal object MarkdownDecodedImageMemory {
    private const val MaxEntries = 48
    private val lock = Any()
    private val map = object : LinkedHashMap<String, MarkdownImageLoadResult>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, MarkdownImageLoadResult>?,
        ): Boolean = size > MaxEntries
    }

    fun get(key: String): MarkdownImageLoadResult? = synchronized(lock) { map[key] }

    fun put(key: String, value: MarkdownImageLoadResult) {
        synchronized(lock) { map[key] = value }
    }
}

internal suspend fun loadMarkdownImage(
    context: Context,
    workspaceFileBridge: WorkspaceFileBridge?,
    runtimeWorkspaceFileBridge: RuntimeWorkspaceFileBridge?,
    rawUrl: String,
    workspaceDirectory: String?,
    allowRootImageRead: Boolean,
): MarkdownImageLoadResult {
    val cacheKey = "$rawUrl|$workspaceDirectory|$allowRootImageRead"
    MarkdownDecodedImageMemory.get(cacheKey)?.let { return it }
    val result = runCatching {
    val loadPreviewError = context.getString(R.string.markdown_image_error_load_preview)
    val readDataError = context.getString(R.string.markdown_image_error_read_data)
    val readWorkspaceError = context.getString(R.string.markdown_image_error_read_workspace)
    val normalizedUrl = normalizeMarkdownImageUrl(rawUrl)
        ?: error(loadPreviewError)
    val imageBinary = when {
        normalizedUrl.startsWith("http://", ignoreCase = true) ||
            normalizedUrl.startsWith("https://", ignoreCase = true) -> {
            fetchRemoteMarkdownImage(context, normalizedUrl)
        }

        normalizedUrl.startsWith("data:", ignoreCase = true) -> {
            decodeDataUrl(context, normalizedUrl)
        }

        normalizedUrl.startsWith("content://", ignoreCase = true) -> {
            readContentMarkdownImage(context, normalizedUrl)
        }

        else -> {
            val localFilePath = parseAssistantLocalFileLink(normalizedUrl)
            loadWorkspaceImageBinary(
                context = context,
                workspaceFileBridge = workspaceFileBridge,
                runtimeWorkspaceFileBridge = runtimeWorkspaceFileBridge,
                rawPath = localFilePath ?: normalizedUrl,
                workingDirectory = workspaceDirectory
                    ?.trim()
                    ?.ifBlank { "/root" }
                    ?: "/root",
                allowRootImageRead = allowRootImageRead,
                readDataError = readDataError,
                readWorkspaceError = readWorkspaceError,
            ) ?: readLocalMarkdownImage(context, normalizedUrl)
        }
    } ?: error(loadPreviewError)

    decodeMarkdownImageResult(
        context = context,
        imageBinary = imageBinary,
        rawUrl = normalizedUrl,
    )
    }.getOrElse { throwable ->
        val message = throwable.message ?: context.getString(R.string.markdown_image_error_load_preview)
        MarkdownImageLoadResult(
            error = message,
            unavailable = looksLikeMissingMarkdownImageError(message),
        )
    }
    MarkdownDecodedImageMemory.put(cacheKey, result)
    return result
}

private suspend fun loadWorkspaceImageBinary(
    context: Context,
    workspaceFileBridge: WorkspaceFileBridge?,
    runtimeWorkspaceFileBridge: RuntimeWorkspaceFileBridge?,
    rawPath: String,
    workingDirectory: String,
    allowRootImageRead: Boolean,
    readDataError: String,
    readWorkspaceError: String,
) : MarkdownImageBinary? {
    val resolvedPath = if (rawPath.startsWith("file://", ignoreCase = true)) {
        workspaceFileBridge?.resolveLinkPath(rawPath) ?: rawPath
    } else {
        workspaceFileBridge?.resolveTermuxPath(
            path = rawPath,
            workingDirectory = workingDirectory,
        ) ?: rawPath
    }
    val localFile = File(resolvedPath)
    var localReadFailure: Throwable? = null
    if (localFile.exists() && localFile.isFile) {
        val localResult = runCatching { readLocalMarkdownImage(context, localFile.absolutePath) }
        localResult.getOrNull()?.let { return it }
        localReadFailure = localResult.exceptionOrNull()
        if (!allowRootImageRead) {
            error(localReadFailure?.message ?: readDataError)
        }
    }
    val defaultRuntimeId = resolveWorkspaceRuntimeId(
        path = "",
        workingDirectory = workingDirectory,
        defaultRuntimeId = LocalRuntimeId.Alpine,
    )
    val resolvedRuntimeId = resolveWorkspaceRuntimeId(
        path = resolvedPath,
        workingDirectory = workingDirectory,
        defaultRuntimeId = defaultRuntimeId,
    )
    val workspaceResult = runtimeWorkspaceFileBridge?.readWorkspaceFile(
        path = resolvedPath,
        workingDirectory = workingDirectory,
        defaultRuntimeId = defaultRuntimeId,
        byteLimit = MaxMarkdownImageBytes,
    ) ?: workspaceFileBridge?.readWorkspaceFile(
        path = resolvedPath,
        workingDirectory = workingDirectory,
        byteLimit = MaxMarkdownImageBytes,
    ) ?: Result.failure(IllegalStateException(readWorkspaceError))
    val payload = workspaceResult.getOrElse { workspaceThrowable ->
        if (!allowRootImageRead || resolvedRuntimeId == LocalRuntimeId.Alpine) {
            error(
                localReadFailure?.message
                    ?: workspaceThrowable.message
                    ?: readWorkspaceError
            )
        }
        workspaceFileBridge?.readRootImageFile(
            path = resolvedPath,
            workingDirectory = workingDirectory,
            byteLimit = MaxMarkdownImageBytes,
        )?.getOrElse { rootThrowable ->
            error(rootThrowable.message ?: workspaceThrowable.message ?: readDataError)
        } ?: error(workspaceThrowable.message ?: readDataError)
    }
    return MarkdownImageBinary(
        bytes = payload.bytes,
        mimeType = workspaceFileBridge?.guessMimeType(resolvedPath)?.ifBlank { null },
    )
}

private fun readLocalMarkdownImage(
    context: Context,
    rawPath: String,
): MarkdownImageBinary? {
    val file = File(rawPath)
    if (!file.exists() || !file.isFile) return null
    val byteLimit = MaxMarkdownImageBytes + 1
    val bytes = file.inputStream().use { readBytesWithLimit(context, it, byteLimit) }
    ensureMarkdownImageWithinLimit(context, bytes.size)
    return MarkdownImageBinary(
        bytes = bytes,
        mimeType = inferMarkdownImageMimeType(
            reportedMimeType = guessMimeTypeFromPath(file.name),
            rawUrl = rawPath,
            bytes = bytes,
        ),
    )
}

private fun readContentMarkdownImage(
    context: Context,
    rawUrl: String,
): MarkdownImageBinary? {
    val uri = Uri.parse(rawUrl)
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        readBytesWithLimit(context, input, MaxMarkdownImageBytes + 1)
    } ?: return null
    ensureMarkdownImageWithinLimit(context, bytes.size)
    return MarkdownImageBinary(
        bytes = bytes,
        mimeType = inferMarkdownImageMimeType(
            reportedMimeType = context.contentResolver.getType(uri),
            rawUrl = rawUrl,
            bytes = bytes,
        ),
    )
}

private fun fetchRemoteMarkdownImage(
    context: Context,
    url: String,
): MarkdownImageBinary {
    val builder = Request.Builder()
        .url(url)
        .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
        .header("User-Agent", if (looksLikeAmapCdn(url)) AmapBrowserUserAgent else "Aether/0.1")
    if (looksLikeAmapCdn(url)) {
        builder.header("Referer", AmapCdnReferer)
    }
    val request = builder.build()
    return MarkdownImageHttpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            error(context.getString(R.string.markdown_image_error_load_preview_http, response.code))
        }
        val body = response.body ?: error(context.getString(R.string.markdown_image_error_load_preview))
        val bytes = body.byteStream().use { readBytesWithLimit(context, it, MaxMarkdownImageBytes + 1) }
        ensureMarkdownImageWithinLimit(context, bytes.size)
        MarkdownImageBinary(
            bytes = bytes,
            mimeType = inferMarkdownImageMimeType(
                reportedMimeType = body.contentType()?.toString(),
                rawUrl = url,
                bytes = bytes,
            ),
        )
    }
}

private fun decodeDataUrl(
    context: Context,
    dataUrl: String,
): MarkdownImageBinary {
    val commaIndex = dataUrl.indexOf(',')
    require(commaIndex > "data:".length) { context.getString(R.string.markdown_image_error_load_preview) }

    val metadata = dataUrl.substring("data:".length, commaIndex)
    val payload = dataUrl.substring(commaIndex + 1)
    val metadataParts = metadata.split(';')
    val reportedMimeType = metadataParts.firstOrNull().orEmpty().ifBlank { null }
    val isBase64 = metadataParts.any { it.equals("base64", ignoreCase = true) }
    val bytes = if (isBase64) {
        Base64.getDecoder().decode(payload)
    } else {
        URLDecoder.decode(payload, Charsets.UTF_8.name()).toByteArray(Charsets.UTF_8)
    }
    ensureMarkdownImageWithinLimit(context, bytes.size)
    return MarkdownImageBinary(
        bytes = bytes,
        mimeType = inferMarkdownImageMimeType(
            reportedMimeType = reportedMimeType,
            rawUrl = dataUrl,
            bytes = bytes,
        ),
    )
}

private fun decodeMarkdownImageResult(
    context: Context,
    imageBinary: MarkdownImageBinary,
    rawUrl: String,
): MarkdownImageLoadResult {
    val bitmap = decodeMarkdownBitmap(imageBinary.bytes)
    if (bitmap != null) {
        return MarkdownImageLoadResult(bitmap = bitmap)
    }

    val mimeType = inferMarkdownImageMimeType(
        reportedMimeType = imageBinary.mimeType,
        rawUrl = rawUrl,
        bytes = imageBinary.bytes,
    )
    if (mimeType != null) {
        if (isSvgMarkdownImage(mimeType)) {
            val svg = decodeMarkdownSvgText(imageBinary.bytes)
            if (svg.isNotBlank()) {
                return MarkdownImageLoadResult(html = buildMarkdownInlineSvgHtml(svg))
            }
        }
        val imageUrl = writeMarkdownImageCacheFile(
            context = context,
            bytes = imageBinary.bytes,
            rawUrl = rawUrl,
            mimeType = mimeType,
        ) ?: buildImageDataUrl(imageBinary.bytes, mimeType)
        return MarkdownImageLoadResult(
            html = buildMarkdownImageHtml(
                imageUrl = imageUrl,
                loadErrorMessage = context.getString(R.string.markdown_image_error_load_preview),
            ),
        )
    }

    error(context.getString(R.string.markdown_image_error_load_preview))
}

internal fun inferMarkdownImageMimeType(
    reportedMimeType: String?,
    rawUrl: String,
    bytes: ByteArray,
): String? {
    val normalizedMimeType = reportedMimeType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase()
        ?.ifBlank { null }
    if (looksLikeSvgDocument(bytes) || normalizedMimeType?.contains("svg") == true) return "image/svg+xml"
    if (bytes.startsWith(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))) return "image/png"
    if (bytes.startsWith(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))) return "image/jpeg"
    if (bytes.startsWith("GIF87a".toByteArray()) || bytes.startsWith("GIF89a".toByteArray())) return "image/gif"
    if (bytes.size >= 12 &&
        bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
        bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray())
    ) {
        return "image/webp"
    }

    if (normalizedMimeType?.startsWith("image/") == true) {
        return normalizedMimeType
    }

    return guessMimeTypeFromPath(rawUrl)
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && copyOfRange(0, prefix.size).contentEquals(prefix)

private fun decodeMarkdownBitmap(bytes: ByteArray): ImageBitmap? {
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { return it.asImageBitmap() }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
    return runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))).asImageBitmap()
    }.getOrNull()
}

private fun looksLikeSvgDocument(bytes: ByteArray): Boolean {
    val preview = bytes.toString(Charsets.UTF_8).trimStart()
    return preview.startsWith("<svg", ignoreCase = true) ||
        preview.startsWith("<?xml", ignoreCase = true) && preview.contains("<svg", ignoreCase = true)
}

private fun buildImageDataUrl(
    bytes: ByteArray,
    mimeType: String,
): String = "data:$mimeType;base64,${Base64.getEncoder().encodeToString(bytes)}"

internal fun buildMarkdownBadgeGroupHtml(
    images: List<MarkdownImageSpec>,
): String {
    val imageTags = images.joinToString("\n") { image ->
        """<img src="${escapeHtml(image.url)}" alt="${escapeHtml(image.altText)}" />"""
    }
    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0" />
            <style>
                html, body {
                    margin: 0;
                    padding: 0;
                    background: transparent;
                }
                .badge-row {
                    display: flex;
                    flex-wrap: wrap;
                    align-items: center;
                    gap: 6px;
                }
                img {
                    display: block;
                    width: auto;
                    height: auto;
                    max-width: 100%;
                    max-height: 32px;
                }
            </style>
        </head>
        <body>
            <div class="badge-row">$imageTags</div>
            <script>
                function reportAetherHeight() {
                    const height = Math.max(
                        document.documentElement.scrollHeight || 0,
                        document.body.scrollHeight || 0,
                        1
                    );
                    if (window.$MarkdownHtmlBridgeName && window.$MarkdownHtmlBridgeName.reportHeight) {
                        window.$MarkdownHtmlBridgeName.reportHeight(String(height));
                    }
                }
                document.querySelectorAll('img').forEach(function(image) {
                    image.addEventListener('load', reportAetherHeight);
                    image.addEventListener('error', function() {
                        image.style.display = 'none';
                        reportAetherHeight();
                    });
                });
                window.reportAetherHeight = reportAetherHeight;
                window.addEventListener('load', function() {
                    setTimeout(reportAetherHeight, 0);
                    setTimeout(reportAetherHeight, 120);
                });
            </script>
        </body>
        </html>
    """.trimIndent()
}

internal fun buildMarkdownInlineSvgHtml(
    svg: String,
): String {
    val sanitizedSvg = sanitizeInlineMarkdownSvg(svg)
    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0" />
            <style>
                html, body {
                    margin: 0;
                    padding: 0;
                    background: transparent;
                }
                body {
                    padding: 0;
                }
                .image-shell {
                    display: flex;
                    align-items: center;
                    justify-content: flex-start;
                }
                .image-shell > svg {
                    display: block;
                    max-width: 100%;
                    height: auto;
                    border-radius: 12px;
                }
            </style>
        </head>
        <body>
            <div id="preview-image" class="image-shell">
                $sanitizedSvg
            </div>
            <script>
                function reportAetherHeight() {
                    const height = Math.max(
                        document.documentElement.scrollHeight || 0,
                        document.body.scrollHeight || 0,
                        1
                    );
                    if (window.$MarkdownHtmlBridgeName && window.$MarkdownHtmlBridgeName.reportHeight) {
                        window.$MarkdownHtmlBridgeName.reportHeight(String(height));
                    }
                }

                const image = document.getElementById('preview-image');
                if (image) {
                    image.addEventListener('click', function() {
                        if (window.$MarkdownHtmlBridgeName && window.$MarkdownHtmlBridgeName.reportTap) {
                            window.$MarkdownHtmlBridgeName.reportTap();
                        }
                    });
                }

                window.addEventListener('load', function() {
                    setTimeout(reportAetherHeight, 0);
                    setTimeout(reportAetherHeight, 120);
                });
            </script>
        </body>
        </html>
    """.trimIndent()
}

private fun buildMarkdownImageHtml(
    imageUrl: String,
    loadErrorMessage: String,
): String = """
    <!DOCTYPE html>
    <html>
    <head>
        <meta name="viewport" content="width=device-width, initial-scale=1.0" />
        <style>
            html, body {
                margin: 0;
                padding: 0;
                background: transparent;
            }
            body {
                padding: 0;
            }
            .image-shell {
                display: flex;
                align-items: center;
                justify-content: flex-start;
            }
            img {
                display: block;
                max-width: 100%;
                height: auto;
                border-radius: 12px;
            }
            .image-error {
                color: #6b7280;
                display: none;
                font: 14px sans-serif;
                padding: 16px;
            }
        </style>
    </head>
    <body>
        <div class="image-shell">
            <img id="preview-image" src="${escapeHtml(imageUrl)}" alt="" />
        </div>
        <div id="image-error" class="image-error">${escapeHtml(loadErrorMessage)}</div>
        <script>
            function reportAetherHeight() {
                const height = Math.max(
                    document.documentElement.scrollHeight || 0,
                    document.body.scrollHeight || 0,
                    1
                );
                if (window.$MarkdownHtmlBridgeName && window.$MarkdownHtmlBridgeName.reportHeight) {
                    window.$MarkdownHtmlBridgeName.reportHeight(String(height));
                }
            }

            const image = document.getElementById('preview-image');
            if (image) {
                image.addEventListener('load', function() {
                    setTimeout(reportAetherHeight, 0);
                    setTimeout(reportAetherHeight, 120);
                });
                image.addEventListener('click', function() {
                    if (window.$MarkdownHtmlBridgeName && window.$MarkdownHtmlBridgeName.reportTap) {
                        window.$MarkdownHtmlBridgeName.reportTap();
                    }
                });
                image.addEventListener('error', function() {
                    const error = document.getElementById('image-error');
                    if (error) {
                        error.style.display = 'block';
                    }
                    image.style.display = 'none';
                    reportAetherHeight();
                });
            }

            window.addEventListener('load', function() {
                setTimeout(reportAetherHeight, 0);
                setTimeout(reportAetherHeight, 120);
            });
        </script>
    </body>
    </html>
""".trimIndent()

internal fun sanitizeInlineMarkdownSvg(svg: String): String = svg
    .trim { it <= ' ' || it == '\uFEFF' }
    .replace(Regex("(?is)^\\s*<\\?xml[^>]*>"), "")
    .replace(Regex("(?is)<script\\b[^>]*>.*?</script>"), "")
    .replace(Regex("(?i)\\s+on[a-z]+\\s*=\\s*\"[^\"]*\""), "")
    .replace(Regex("(?i)\\s+on[a-z]+\\s*=\\s*'[^']*'"), "")
    .replace(Regex("(?i)\\s+(?:xlink:href|href)\\s*=\\s*\"\\s*javascript:[^\"]*\""), "")
    .replace(Regex("(?i)\\s+(?:xlink:href|href)\\s*=\\s*'\\s*javascript:[^']*'"), "")
    .trim()

private fun decodeMarkdownSvgText(bytes: ByteArray): String =
    bytes.toString(Charsets.UTF_8)

private fun isSvgMarkdownImage(mimeType: String): Boolean =
    mimeType.substringBefore(';').trim().lowercase().contains("svg")

private fun writeMarkdownImageCacheFile(
    context: Context,
    bytes: ByteArray,
    rawUrl: String,
    mimeType: String,
): String? = runCatching {
    val directory = File(context.cacheDir, "markdown-images").apply { mkdirs() }
    val extension = markdownImageCacheExtension(mimeType, rawUrl)
    val file = File(directory, "${markdownImageCacheKey(rawUrl, bytes)}.$extension")
    if (!file.exists() || file.length() != bytes.size.toLong()) {
        file.outputStream().use { output ->
            output.write(bytes)
            output.flush()
        }
    }
    Uri.fromFile(file).toString()
}.getOrNull()

private fun markdownImageCacheExtension(
    mimeType: String,
    rawUrl: String,
): String = when (mimeType.substringBefore(';').trim().lowercase()) {
    "image/png" -> "png"
    "image/jpeg", "image/jpg" -> "jpg"
    "image/gif" -> "gif"
    "image/webp" -> "webp"
    "image/avif" -> "avif"
    "image/heic", "image/heif" -> "heic"
    "image/bmp", "image/x-bmp" -> "bmp"
    else -> rawUrl
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('.', "")
        .lowercase()
        .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
        ?: "img"
}

private fun markdownImageCacheKey(
    rawUrl: String,
    bytes: ByteArray,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(rawUrl.toByteArray(Charsets.UTF_8))
    digest.update(0.toByte())
    digest.update(bytes)
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun guessMimeTypeFromPath(rawUrl: String): String? {
    val candidatePath = rawUrl
        .substringAfterLast('/')
        .substringBefore('?')
        .substringBefore('#')
    val extension = candidatePath.substringAfterLast('.', "").lowercase()
    return when (extension) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg", "svgz" -> "image/svg+xml"
        "bmp" -> "image/bmp"
        "avif" -> "image/avif"
        else -> null
    }
}

private fun readBytesWithLimit(
    context: Context,
    inputStream: java.io.InputStream,
    byteLimit: Int,
): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var totalRead = 0

    while (true) {
        val read = inputStream.read(buffer)
        if (read <= 0) break
        totalRead += read
        if (totalRead > byteLimit) {
            error(context.getString(R.string.markdown_image_error_too_large))
        }
        output.write(buffer, 0, read)
    }

    return output.toByteArray()
}

private fun ensureMarkdownImageWithinLimit(context: Context, sizeBytes: Int) {
    if (sizeBytes > MaxMarkdownImageBytes) {
        error(context.getString(R.string.markdown_image_error_too_large))
    }
}

private fun buildMermaidHtml(
    code: String,
    layout: MarkdownMediaLayout,
    renderErrorTitle: String,
    invalidSyntaxError: String,
): String {
    val escapedCode = escapeHtml(code)
    val renderErrorTitleLiteral = jsStringLiteral(renderErrorTitle)
    val invalidSyntaxErrorLiteral = jsStringLiteral(invalidSyntaxError)
    val svgMaxWidth = if (layout.scroll) "none" else "100%"
    val containerWidthRule = if (layout.scroll) {
        "display: inline-block; min-width: 100%;"
    } else {
        ""
    }
    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0" />
            <style>
                html, body {
                    margin: 0;
                    padding: 0;
                    background: transparent;
                    color: #111827;
                    font-family: sans-serif;
                }
                #container {
                    padding: 12px;
                    $containerWidthRule
                }
                svg {
                    max-width: $svgMaxWidth;
                    height: auto;
                }
                pre {
                    white-space: pre-wrap;
                    font-family: monospace;
                    background: #f5f5f5;
                    border-radius: 12px;
                    padding: 12px;
                }
                .mermaid-error {
                    padding: 12px;
                    color: #1f2937;
                    font-family: sans-serif;
                }
                .mermaid-error-title {
                    font-size: 15px;
                    font-weight: 600;
                    margin-bottom: 8px;
                }
                .mermaid-error-detail {
                    color: #6b7280;
                    font-size: 13px;
                }
                .mermaid-source {
                    margin: 0 0 10px;
                }
            </style>
            <script src="$MermaidScriptUrl"></script>
        </head>
        <body>
            <div id="container">
                <pre id="diagram" class="mermaid">$escapedCode</pre>
            </div>
            <script>
                const renderErrorTitle = $renderErrorTitleLiteral;
                const invalidSyntaxError = $invalidSyntaxErrorLiteral;

                function escapeHtml(value) {
                    return String(value || '')
                        .replace(/&/g, '&amp;')
                        .replace(/</g, '&lt;')
                        .replace(/>/g, '&gt;')
                        .replace(/"/g, '&quot;')
                        .replace(/'/g, '&#39;');
                }

                function reportAetherHeight() {
                    const height = Math.max(
                        document.documentElement.scrollHeight || 0,
                        document.body.scrollHeight || 0,
                        220
                    );
                    if (window.$MarkdownHtmlBridgeName && window.$MarkdownHtmlBridgeName.reportHeight) {
                        window.$MarkdownHtmlBridgeName.reportHeight(String(height));
                    }
                }

                function reportAetherTap() {
                    if (window.$MarkdownHtmlBridgeName && window.$MarkdownHtmlBridgeName.reportTap) {
                        window.$MarkdownHtmlBridgeName.reportTap();
                    }
                }

                function showMermaidError(code, detail) {
                    document.getElementById('container').innerHTML =
                        '<div class="mermaid-error">' +
                        '<div class="mermaid-error-title">' + escapeHtml(renderErrorTitle) + '</div>' +
                        '<pre class="mermaid-source">' + escapeHtml(code) + '</pre>' +
                        '<div class="mermaid-error-detail">' + escapeHtml(detail || invalidSyntaxError) + '</div>' +
                        '</div>';
                }

                async function renderDiagram() {
                    const element = document.getElementById('diagram');
                    const code = element.textContent.trim();
                    try {
                        if (!window.mermaid) {
                            throw new Error('Mermaid library failed to load.');
                        }
                        mermaid.initialize({ startOnLoad: false, securityLevel: 'loose', theme: 'neutral' });
                        await mermaid.parse(code, { suppressErrors: false });
                        const rendered = await mermaid.render('aether-mermaid-' + Date.now(), code);
                        if ((rendered.svg || '').includes('class="error-icon"') || (rendered.svg || '').includes('Syntax error in text')) {
                            throw new Error('Mermaid reported a syntax error.');
                        }
                        document.getElementById('container').innerHTML = rendered.svg;
                        document.getElementById('container').onclick = reportAetherTap;
                    } catch (error) {
                        showMermaidError(
                            code,
                            error && error.message ? error.message : invalidSyntaxError,
                        );
                        document.getElementById('container').onclick = reportAetherTap;
                    } finally {
                        setTimeout(reportAetherHeight, 0);
                        setTimeout(reportAetherHeight, 120);
                        setTimeout(reportAetherHeight, 360);
                    }
                }

                window.addEventListener('load', renderDiagram);
            </script>
        </body>
        </html>
    """.trimIndent()
}

private fun escapeHtml(value: String): String = buildString {
    value.forEach { character ->
        when (character) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(character)
        }
    }
}

private fun jsStringLiteral(value: String): String = buildString {
    append('"')
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> {
                append('\\')
                append('"')
            }
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '<' -> append("\\u003C")
            '>' -> append("\\u003E")
            '&' -> append("\\u0026")
            else -> append(character)
        }
    }
    append('"')
}

private fun Color.toCssHex(): String {
    val argb = toArgb()
    val alpha = (argb ushr 24) and 0xFF
    val red = (argb ushr 16) and 0xFF
    val green = (argb ushr 8) and 0xFF
    val blue = argb and 0xFF
    return if (alpha == 0xFF) {
        String.format("#%02X%02X%02X", red, green, blue)
    } else {
        String.format("#%02X%02X%02X%02X", red, green, blue, alpha)
    }
}

private class MarkdownHtmlBridge(
    private val onHeightMeasured: (Int) -> Unit,
    private val onTap: (() -> Unit)? = null,
    private val onLinkClick: ((String) -> Unit)? = null,
) {
    @JavascriptInterface
    fun reportHeight(height: String?) {
        height?.toIntOrNull()?.let(onHeightMeasured)
    }

    @JavascriptInterface
    fun reportTap() {
        onTap?.invoke()
    }

    @JavascriptInterface
    fun reportLink(url: String?) {
        url?.takeIf { it.isNotBlank() }?.let { targetUrl ->
            onLinkClick?.invoke(targetUrl)
        }
    }
}

private fun AnnotatedString.Builder.appendSourceSegment(
    text: String,
    sourceOffset: Int,
    fadeSpan: MarkdownFadeSpan?,
) {
    if (text.isEmpty()) return
    if (fadeSpan == null || fadeSpan.sourceRange.isEmpty()) {
        append(text)
        return
    }

    val segmentEndExclusive = sourceOffset + text.length
    val fadeStart = fadeSpan.sourceRange.first.coerceAtLeast(sourceOffset)
    val fadeEndExclusive = (fadeSpan.sourceRange.last + 1).coerceAtMost(segmentEndExclusive)
    if (fadeEndExclusive <= fadeStart) {
        append(text)
        return
    }

    val localFadeStart = fadeStart - sourceOffset
    val localFadeEndExclusive = fadeEndExclusive - sourceOffset

    if (localFadeStart > 0) {
        append(text.substring(0, localFadeStart))
    }

    val fadeText = text.substring(localFadeStart, localFadeEndExclusive)
    if (fadeSpan.mask != null) {
        appendCharacterFadedSegment(
            text = fadeText,
            sourceOffset = fadeStart,
            mask = fadeSpan.mask,
            color = AetherOnSurface,
        )
    } else {
        pushStyle(SpanStyle(color = AetherOnSurface.copy(alpha = fadeSpan.alpha)))
        append(fadeText)
        pop()
    }

    if (localFadeEndExclusive < text.length) {
        append(text.substring(localFadeEndExclusive))
    }
}
