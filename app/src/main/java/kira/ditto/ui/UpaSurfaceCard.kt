package kira.ditto.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kira.ditto.AetherApplication
import kira.ditto.a2ui.A2uiMessageProcessor
import kira.ditto.a2ui.compileUpaTreeToA2ui
import kira.ditto.data.UpaPluginHostState
import kira.ditto.data.UpaPluginLibrary
import kira.ditto.upa.UpaNode
import kira.ditto.upa.UpaTemplate
import kira.ditto.upa.applyUpaFields
import kira.ditto.upa.applyUpaTransition
import kira.ditto.upa.declaresUiContent
import kira.ditto.upa.pruneUpaTreeToFields
import kira.ditto.upa.upaNodeFlag
import kira.ditto.upa.upaNodeText
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

val LocalUpaRenderUi = compositionLocalOf { true }
val LocalUpaDisabledUiPlugins = compositionLocalOf { emptySet<String>() }
val LocalUpaMcpBindings = compositionLocalOf { emptyMap<String, List<String>>() }
val LocalUpaUserAction = compositionLocalOf<(pluginId: String, serverIds: List<String>, name: String) -> Unit> {
    { _, _, _ -> }
}

data class UpaChatSurface(
    val tree: UpaNode,
    val template: UpaTemplate,
    val title: String,
    val pluginId: String = "",
    val fields: Map<String, String> = emptyMap(),
    val cacheKey: String = "",
)

private val UpaSurfaceCache = LruCache<String, UpaChatSurface>(48)
internal val HostRetiredUpaPluginIds = setOf(
    "amap.maps.card",
    "amap.merchants.card",
    "food.order.card",
    "qweather.now.card",
)
private val UpaColorCache = ConcurrentHashMap<String, Color>()
private val UpaBitmapCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

fun resolveUpaChatSurface(
    output: JSONObject?,
    library: UpaPluginLibrary?,
    userRenderUi: Boolean,
    disabledUiPlugins: Set<String> = emptySet(),
): UpaChatSurface? {
    if (!userRenderUi || output == null) return null
    val data = output.optJSONObject("structuredContent") ?: output
    val upa = data.optJSONObject("_upa") ?: output.optJSONObject("_upa") ?: return null
    if (!upa.optBoolean("present", true)) return null
    val pluginId = upa.optString("pluginId")
    if (pluginId.isBlank() || pluginId == "kaggle.cli.card" || pluginId in HostRetiredUpaPluginIds) return null
    if (!UpaPluginHostState.rendersUi(pluginId, userRenderUi) || pluginId in disabledUiPlugins) {
        return null
    }
    val templateId = upa.optString("templateId")
    val fieldsObject = upa.optJSONObject("fields")
    val cacheKey = "$pluginId|$templateId|${fieldsObject?.toString().orEmpty()}"
    synchronized(UpaSurfaceCache) {
        UpaSurfaceCache.get(cacheKey)?.let { return it }
    }
    val fields = upaFieldsMap(fieldsObject)
    if (pluginId in HostRetiredUpaPluginIds) return null
    if (fields.isEmpty()) return null
    val manifest = library?.readManifestBlocking(pluginId) ?: return null
    if (!manifest.declaresUiContent()) return null
    val template = manifest.templates.firstOrNull { it.id == templateId }
        ?: manifest.templates.firstOrNull()
        ?: return null
    val surface = manifest.surfaces.firstOrNull { it.id == template.surface }
        ?: manifest.surfaces.firstOrNull()
    val tree = template.tree ?: surface?.tree ?: return null
    val resolved = UpaChatSurface(
        tree = pruneUpaTreeToFields(applyUpaFields(tree, template, fields), template),
        template = template,
        title = surface?.title.orEmpty(),
        pluginId = pluginId,
        fields = fields,
        cacheKey = cacheKey,
    )
    synchronized(UpaSurfaceCache) {
        UpaSurfaceCache.put(cacheKey, resolved)
    }
    return resolved
}

@Composable
fun MessageUpaProtocolCards(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier,
) {
    val renderUi = LocalUpaRenderUi.current
    if (!renderUi) return
    val disabled = LocalUpaDisabledUiPlugins.current
    val bindings = LocalUpaMcpBindings.current
    val allInvocations = remember(messages) {
        messages.flatMap { message ->
            (message.toolInvocations + message.reasoningTrace?.toolInvocations.orEmpty())
        }
    }
    val library = rememberUpaPluginLibrary()
    val cards = remember(allInvocations, library, renderUi, disabled, bindings) {
        resolveA2uiChatCards(
            invocations = allInvocations,
            library = library,
            userRenderUi = renderUi,
            disabledUiPlugins = disabled,
            mcpBindings = bindings,
        )
    }
    if (cards.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        cards.forEach { card ->
            key(card.cacheKey) {
                A2uiProtocolCard(card)
            }
        }
    }
}

@Composable
fun A2uiProtocolCard(
    card: A2uiChatCard,
    modifier: Modifier = Modifier,
) {
    if (card.pluginId in HostRetiredUpaPluginIds) return
    val context = LocalContext.current
    val onBoundMcpAction = LocalUpaUserAction.current
    var sourceTree by remember(card.cacheKey) { mutableStateOf(card.sourceTree) }
    var state by remember(card.cacheKey) { mutableStateOf(card.state) }
    kira.ditto.a2ui.A2uiSurfaceView(
        state = state,
        modifier = modifier.fillMaxWidth(),
        onUserAction = { name, _ ->
            when {
                name.startsWith("transition:") && sourceTree != null && card.template != null -> {
                    val next = applyUpaTransition(
                        sourceTree!!,
                        card.template,
                        name.removePrefix("transition:"),
                    )
                    sourceTree = next
                    A2uiMessageProcessor().apply(
                        compileUpaTreeToA2ui(next, surfaceId = card.state.surfaceId),
                    )?.let { state = it }
                }
                else -> {
                    val tool = name.removePrefix("tool:").removePrefix("mcp:").trim()
                    if (tool.isNotBlank()) {
                        onBoundMcpAction(card.pluginId, card.boundServerIds, tool)
                    }
                }
            }
        },
    )
}

@Composable
fun UpaProtocolSurfaceCard(
    surface: UpaChatSurface,
    modifier: Modifier = Modifier,
) {
    if (surface.pluginId in HostRetiredUpaPluginIds) return
    val uriHandler = LocalUriHandler.current
    var tree by remember(surface.cacheKey) { mutableStateOf(surface.tree) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(18.dp),
    ) {
        UpaNodeView(
            node = tree,
            hideQr = false,
            onEvent = { event ->
                when {
                    event.startsWith("transition:") -> {
                        val name = event.removePrefix("transition:")
                        if (name.isNotBlank()) {
                            tree = applyUpaTransition(tree, surface.template, name)
                        }
                    }
                    event == "host:open" || event.startsWith("open:") -> {
                        val fromEvent = event.removePrefix("open:").trim()
                        if (fromEvent.startsWith("http")) {
                            runCatching { uriHandler.openUri(fromEvent) }
                        }
                    }
                }
            },
        )
    }
}

@Composable
fun rememberUpaPluginLibrary(): UpaPluginLibrary? {
    val context = LocalContext.current
    return remember(context) {
        (context.applicationContext as? AetherApplication)?.runtime?.upaPluginLibrary
    }
}

@Composable
private fun UpaNodeView(
    node: UpaNode,
    onEvent: (String) -> Unit,
    hideQr: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!upaNodeShouldCompose(node, hideQr)) return
    val tint = parseUpaColor(node.style?.color.orEmpty())
    when (node.type) {
        "group" -> Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            node.children.forEach { child ->
                if (!upaNodeShouldCompose(child, hideQr)) return@forEach
                key(child.id) {
                    UpaNodeView(node = child, onEvent = onEvent, hideQr = hideQr)
                }
            }
        }
        "text" -> {
            val text = node.displayText()
            val role = upaNodeText(node, "role")
            Text(
                text = text,
                modifier = modifier,
                color = tint ?: when (role) {
                    "caption" -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                },
                style = when (role) {
                    "hero" -> MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 36.sp,
                        lineHeight = 40.sp,
                    )
                    "title" -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                    "caption" -> MaterialTheme.typography.bodySmall
                    else -> MaterialTheme.typography.bodyLarge
                },
            )
        }
        "badge" -> {
            val text = node.displayText()
            Column(
                modifier = modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background((tint ?: MaterialTheme.colorScheme.primary).copy(alpha = 0.14f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text = text,
                    color = tint ?: MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        "row" -> {
            val title = upaNodeText(node, "title")
            val subtitle = upaNodeText(node, "subtitle")
            Row(
                modifier = modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (title.isNotBlank()) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tint ?: MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        "image" -> {
            val src = upaNodeText(node, "src").ifBlank { upaNodeText(node, "text") }
            UpaRemoteImage(
                url = src,
                modifier = modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp),
            )
        }
        "action" -> {
            val label = upaNodeText(node, "label")
            val href = upaNodeText(node, "href").ifBlank { upaNodeText(node, "url") }
            val event = node.events["click"].orEmpty()
            val resolved = when {
                event == "host:open" -> {
                    val fromHref = href.takeIf { it.startsWith("http") }.orEmpty()
                    if (fromHref.isNotBlank()) "open:$fromHref" else "host:open"
                }
                event.startsWith("open:") && event.removePrefix("open:").trim().startsWith("http") -> event
                event.startsWith("tool:") || event.startsWith("food.logout:") || event.startsWith("transition:") -> event
                href.startsWith("http") -> "open:$href"
                else -> event
            }
            Text(
                text = label,
                modifier = modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                    .clickable(enabled = resolved.isNotBlank()) { onEvent(resolved) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                color = tint ?: MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        else -> {
            val visible = node.children.filter { upaNodeShouldCompose(it, hideQr) }
            if (visible.isNotEmpty()) {
                Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    visible.forEach { child ->
                        key(child.id) {
                            UpaNodeView(node = child, onEvent = onEvent, hideQr = hideQr)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun UpaRemoteImage(
    url: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    placeholder: Boolean = true,
) {
    var bitmap by remember(url) {
        mutableStateOf(UpaBitmapCache.get(url)?.asImageBitmap())
    }
    LaunchedEffect(url) {
        if (bitmap != null) return@LaunchedEffect
        val decoded = withContext(Dispatchers.IO) { decodeUpaBitmap(url) }
        if (decoded != null) {
            UpaBitmapCache.put(url, decoded)
            bitmap = decoded.asImageBitmap()
        }
    }
    val shown = bitmap
    if (shown == null) {
        if (placeholder) {
            Box(
                modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
            )
        }
        return
    }
    Image(
        bitmap = shown,
        contentDescription = null,
        modifier = modifier,
        contentScale = contentScale,
    )
}

private fun decodeUpaBitmap(url: String): Bitmap? {
    UpaBitmapCache.get(url)?.let { return it }
    return runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 12_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", kira.ditto.data.AmapBrowserUserAgent)
            setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            if (kira.ditto.data.looksLikeAmapCdn(url)) {
                setRequestProperty("Referer", kira.ditto.data.AmapCdnReferer)
            }
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val bytes = stream?.use { it.readBytes() } ?: byteArrayOf()
        connection.disconnect()
        if (code !in 200..299 || bytes.isEmpty()) return@runCatching null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        val maxPx = 560
        var sample = 1
        val largest = maxOf(bounds.outWidth, bounds.outHeight)
        while (largest / sample > maxPx) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }.getOrNull()
}

private fun upaNodeShouldCompose(node: UpaNode, hideQr: Boolean): Boolean {
    if (!upaNodeFlag(node, "visible", true)) return false
    if (hideQr && (node.id == "login-qr" || node.id == "pay-qr")) return false
    return when (node.type) {
        "text", "badge" -> node.displayText().isNotBlank()
        "row" -> upaNodeText(node, "subtitle").isNotBlank()
        "image" -> upaNodeText(node, "src").isNotBlank() || upaNodeText(node, "text").isNotBlank()
        "action" -> upaNodeText(node, "label").isNotBlank()
        else -> true
    }
}

private fun UpaNode.displayText(): String {
    val base = upaNodeText(this, "text")
    val suffix = upaNodeText(this, "suffix")
    return if (suffix.isNotBlank() && base.isNotBlank() && !base.endsWith(suffix)) {
        "$base$suffix"
    } else {
        base
    }
}

private fun parseUpaColor(raw: String): Color? {
    if (raw.isBlank()) return null
    UpaColorCache[raw]?.let { return it }
    val hex = raw.trim().removePrefix("#")
    val value = hex.toLongOrNull(16) ?: return null
    val color = when (hex.length) {
        6 -> Color(0xFF000000L or value)
        8 -> Color(value)
        else -> return null
    }
    UpaColorCache[raw] = color
    return color
}
