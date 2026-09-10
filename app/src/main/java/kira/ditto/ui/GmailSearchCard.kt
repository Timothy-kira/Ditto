package kira.ditto.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewParent
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import kira.ditto.R
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kira.ditto.data.GmailCodec
import kira.ditto.data.GmailMcp
import kira.ditto.data.GmailMcpHost
import kira.ditto.ui.theme.AetherIsDark
import kira.ditto.ui.theme.AetherOnSurface
import kira.ditto.ui.theme.AetherOnSurfaceVariant
import kira.ditto.ui.theme.AetherOutlineSoft
import kira.ditto.ui.theme.AetherSurface
import kira.ditto.ui.theme.AetherSurfaceHigh
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal val GmailRed = Color(0xFFFC413D)

private const val GmailCardAlpha = 0.88f
internal const val GmailSearchPreviewLimit = 6
private val GmailCardGray = Color(0xFF808080)
private val GmailTimelineBlurRadius = 22.dp
private val GmailHeaderIconSize = 20.dp
private val GmailHeaderPadTop = 10.dp
private val GmailHeaderPadBottomCollapsed = 10.dp
private val GmailBodyBottomPad = 12.dp
private val GmailExpandedHeaderHeight =
    GmailHeaderPadTop + GmailHeaderIconSize + GmailHeaderPadBottomCollapsed
private val GmailAvatarPalette = listOf(
    Color(0xFF7986CB),
    Color(0xFF64B5F6),
    Color(0xFF4DB6AC),
    Color(0xFF81C784),
    Color(0xFFFFB74D),
    Color(0xFFE57373),
    Color(0xFFBA68C8),
    Color(0xFFA1887F),
)

internal data class GmailSearchHit(
    val threadId: String,
    val subject: String,
    val from: String,
    val email: String = "",
    val snippet: String,
    val date: String,
    val unread: Boolean,
)

internal data class GmailThreadMessage(
    val id: String,
    val from: String,
    val email: String,
    val date: String,
    val subject: String,
    val body: String,
    val htmlBody: String = "",
)

internal data class GmailThreadDetail(
    val threadId: String,
    val subject: String,
    val messages: List<GmailThreadMessage>,
    val error: String = "",
)

internal data class GmailSearchSnapshot(
    val query: String,
    val threads: List<GmailSearchHit>,
    val resultCountEstimate: Int,
    val error: String,
    val ok: Boolean,
)

internal data class GmailDraftPreview(
    val draftId: String,
    val messageId: String = "",
    val threadId: String,
    val to: List<String>,
    val cc: List<String>,
    val subject: String,
    val body: String,
    val htmlBody: String,
    val error: String,
    val ok: Boolean,
)

internal fun ChatToolInvocation.isGmailSearchCard(): Boolean =
    GmailMcp.canonicalToolName(toolName) == "search_threads"

internal fun ChatToolInvocation.isGmailDraftCard(): Boolean =
    GmailMcp.canonicalToolName(toolName) == "create_draft"

internal fun ChatToolInvocation.isGmailLiveCard(): Boolean =
    isGmailSearchCard() || isGmailDraftCard()

internal fun collectGmailSearchInvocations(
    invocations: List<ChatToolInvocation>,
): List<ChatToolInvocation> =
    invocations.distinctBy(ChatToolInvocation::id).filter { it.isGmailSearchCard() }

internal fun collectGmailDraftInvocations(
    invocations: List<ChatToolInvocation>,
): List<ChatToolInvocation> =
    invocations.distinctBy(ChatToolInvocation::id).filter { it.isGmailDraftCard() }

internal fun collectGmailLiveInvocations(
    invocations: List<ChatToolInvocation>,
): List<ChatToolInvocation> {
    val unique = invocations.distinctBy(ChatToolInvocation::id).filter { it.isGmailLiveCard() }
    val searches = unique.filter { it.isGmailSearchCard() }
    val draft = unique.lastOrNull { it.isGmailDraftCard() }
    return searches + listOfNotNull(draft)
}

internal fun collectMessageGmailSearchInvocations(
    messages: List<ChatMessage>,
): List<ChatToolInvocation> = collectGmailLiveInvocations(
    messages.flatMap { message ->
        message.toolInvocations + message.reasoningTrace?.toolInvocations.orEmpty()
    },
)

internal fun ChatToolInvocation.isGmailWarmContent(): Boolean {
    val name = GmailMcp.canonicalToolName(toolName)
    return name == "search_threads" || name == "get_thread" || name == "create_draft"
}

internal fun collectGmailWarmInvocations(
    invocations: List<ChatToolInvocation>,
): List<ChatToolInvocation> =
    invocations.distinctBy(ChatToolInvocation::id).filter {
        it.isGmailWarmContent() && it.outputJson.isNotBlank()
    }

internal fun collectMessageGmailWarmInvocations(
    messages: List<ChatMessage>,
): List<ChatToolInvocation> = collectGmailWarmInvocations(
    messages.flatMap { message ->
        message.toolInvocations + message.reasoningTrace?.toolInvocations.orEmpty()
    },
)

internal fun gmailPercentEncode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

internal fun gmailSearchQuery(argumentsJson: String): String {
    val args = runCatching { JSONObject(argumentsJson) }.getOrNull() ?: return ""
    return GmailCodec.stringArg(args, "query")
}

internal fun gmailSenderDisplay(raw: String): String {
    val name = raw.substringBefore('<').trim().trim('"', '\'')
    if (name.isNotBlank()) return name
    val email = raw.substringAfter('<', missingDelimiterValue = "")
        .substringBefore('>')
        .trim()
    return email.ifBlank { raw.trim() }
}

internal fun gmailSenderEmail(raw: String): String {
    val angled = raw.substringAfter('<', missingDelimiterValue = "")
        .substringBefore('>')
        .trim()
        .trim('"', '\'')
    if (angled.contains('@')) return angled.lowercase()
    val token = raw.split(Regex("[\\s,;]+"))
        .map { it.trim().trim('<', '>', '"', '\'') }
        .firstOrNull { it.contains('@') }
        .orEmpty()
    return token.lowercase()
}

internal fun gmailSearchPreview(
    hits: List<GmailSearchHit>,
    limit: Int = GmailSearchPreviewLimit,
): List<GmailSearchHit> = hits.take(limit.coerceAtLeast(0))

internal fun gmailWebUrl(
    threadId: String = "",
    query: String = "",
    draftId: String = "",
    messageId: String = "",
): String = gmailWebOpenUrls(
    threadId = threadId,
    query = query,
    draftId = draftId,
    messageId = messageId,
).first()

internal fun gmailOpenUrls(
    threadId: String = "",
    query: String = "",
    draftId: String = "",
    messageId: String = "",
): List<String> = gmailWebOpenUrls(
    threadId = threadId,
    query = query,
    draftId = draftId,
    messageId = messageId,
)

internal fun gmailWebOpenUrls(
    threadId: String = "",
    query: String = "",
    draftId: String = "",
    messageId: String = "",
): List<String> {
    val composeIds = listOf(messageId, draftId).map { it.trim() }.filter { it.isNotBlank() }.distinct()
    if (composeIds.isNotEmpty()) {
        return composeIds.map { id -> "https://mail.google.com/mail/u/0/#drafts?compose=$id" }
    }
    if (threadId.isNotBlank()) return listOf("https://mail.google.com/mail/u/0/#all/$threadId")
    if (query.isNotBlank()) return listOf("https://mail.google.com/mail/u/0/#search/${gmailPercentEncode(query)}")
    return listOf("https://mail.google.com/mail/u/0/#inbox")
}

internal fun gmailAppOpenUrls(
    threadId: String = "",
    query: String = "",
    draftId: String = "",
    messageId: String = "",
): List<String> {
    val thread = threadId.trim()
    val message = messageId.trim()
    val draft = draftId.trim()
    val urls = linkedSetOf<String>()
    fun addConversation(id: String) {
        if (id.isBlank()) return
        urls.add("https://mail.google.com/mail/u/0/?view=cv&search=all&th=$id")
        urls.add("https://mail.google.com/mail/mu/mp/0/#cv/All%20Mail/$id")
        urls.add("googlegmail://gmail/thread/$id")
    }
    if (draft.isNotBlank() || message.isNotBlank()) {
        addConversation(thread)
        addConversation(message)
        addConversation(draft)
        return urls.toList()
    }
    if (thread.isNotBlank()) {
        addConversation(thread)
        return urls.toList()
    }
    if (query.isNotBlank()) {
        val encoded = gmailPercentEncode(query)
        urls.add("https://mail.google.com/mail/u/0/?view=tl&search=query&q=$encoded")
        urls.add("googlegmail://gmail/search/$encoded")
        return urls.toList()
    }
    return emptyList()
}

internal enum class GmailOpenDestination { App, Web }

internal const val GmailAppPackage = "com.google.android.gm"

internal val GmailWebPackages = listOf(
    "com.android.chrome",
    "com.google.android.apps.chrome",
    "com.huawei.browser",
    "com.huawei.android.browser",
    "org.mozilla.firefox",
)

internal fun gmailAppInstalled(context: android.content.Context): Boolean =
    context.packageManager.getLaunchIntentForPackage(GmailAppPackage) != null

private fun launchGmailIntent(context: android.content.Context, intent: Intent): Boolean {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val resolved = context.packageManager.resolveActivity(intent, 0)
    if (resolved == null) return false
    return runCatching {
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}

internal fun openGmail(
    context: android.content.Context,
    threadId: String = "",
    query: String = "",
    destination: GmailOpenDestination,
    draftId: String = "",
    messageId: String = "",
): Boolean {
    return when (destination) {
        GmailOpenDestination.App -> {
            gmailAppOpenUrls(
                threadId = threadId,
                query = query,
                draftId = draftId,
                messageId = messageId,
            ).map(Uri::parse).map { uri ->
                Intent(Intent.ACTION_VIEW, uri)
                    .setPackage(GmailAppPackage)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
            }.any { launchGmailIntent(context, it) }
        }
        GmailOpenDestination.Web -> {
            gmailWebOpenUrls(
                threadId = threadId,
                query = query,
                draftId = draftId,
                messageId = messageId,
            ).map(Uri::parse).flatMap { uri ->
                GmailWebPackages.map { packageName ->
                    Intent(Intent.ACTION_VIEW, uri)
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                        .setPackage(packageName)
                } + Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
            }.any { launchGmailIntent(context, it) }
        }
    }
}

internal fun parseGmailToolObject(raw: String): JSONObject? {
    if (raw.isBlank()) return null
    val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
    if (root.has("threads") || (root.has("messages") && root.has("id")) || root.has("threadId")) return root
    root.optJSONObject("structuredContent")?.let { structured ->
        if (
            structured.has("threads") ||
            (structured.has("messages") && structured.has("id")) ||
            structured.has("threadId") ||
            structured.has("id")
        ) {
            return structured
        }
        structured.optJSONObject("raw")?.let { nested ->
            if (nested.has("threads") || nested.has("messages")) return nested
        }
    }
    val text = root.optJSONArray("content")?.optJSONObject(0)?.optString("text").orEmpty()
    if (text.isNotBlank()) {
        val inner = runCatching { JSONObject(text) }.getOrNull()
        if (inner != null && (inner.has("threads") || inner.has("messages") || inner.has("ok") || inner.has("id"))) {
            return inner
        }
    }
    return root
}

internal fun parseGmailSearchSnapshot(argumentsJson: String, outputJson: String): GmailSearchSnapshot {
    val query = gmailSearchQuery(argumentsJson)
    val root = parseGmailToolObject(outputJson)
    if (root == null) {
        return GmailSearchSnapshot(
            query = query,
            threads = emptyList(),
            resultCountEstimate = 0,
            error = "",
            ok = true,
        )
    }
    val ok = root.optBoolean("ok", true)
    val error = root.optString("error").ifBlank { root.optString("reason") }
    val threadsIn = root.optJSONArray("threads")
    val hits = buildList {
        if (threadsIn != null) {
            for (index in 0 until threadsIn.length()) {
                val item = threadsIn.optJSONObject(index) ?: continue
                gmailSearchHitFromThread(item)?.let(::add)
            }
        }
    }
    val estimate = when {
        root.has("resultCountEstimate") && !root.isNull("resultCountEstimate") ->
            root.optInt("resultCountEstimate", hits.size)
        else -> hits.size
    }
    return GmailSearchSnapshot(
        query = query,
        threads = hits,
        resultCountEstimate = estimate,
        error = if (ok) "" else error,
        ok = ok,
    )
}

internal fun parseGmailDraftPreview(argumentsJson: String, outputJson: String): GmailDraftPreview {
    val args = runCatching { JSONObject(argumentsJson) }.getOrNull() ?: JSONObject()
    val root = parseGmailToolObject(outputJson)
    val ok = root?.optBoolean("ok", true) ?: true
    val error = root?.optString("error").orEmpty().ifBlank { root?.optString("reason").orEmpty() }
    return GmailDraftPreview(
        draftId = root?.optString("id").orEmpty(),
        messageId = root?.optJSONObject("message")?.optString("id").orEmpty()
            .ifBlank { root?.optString("messageId").orEmpty() },
        threadId = root?.optString("threadId").orEmpty(),
        to = GmailCodec.stringList(args, "to"),
        cc = GmailCodec.stringList(args, "cc"),
        subject = GmailCodec.stringArg(args, "subject"),
        body = GmailCodec.stringArg(args, "body"),
        htmlBody = GmailCodec.stringArg(args, "htmlBody", "html_body"),
        error = if (ok) "" else error.ifBlank { "Gmail request failed" },
        ok = ok,
    )
}

private val GmailDraftHeadingLine = Regex(
    """(?i)^\s*(?:[-*]\s*)?(?:已保存为草稿|Saved as(?: a)? draft)\s*[:：]?\s*$""",
)
private val GmailDraftFieldLine = Regex(
    """(?i)^\s*>?\s*(?:\*\*)?(收件人|主题|内容|To|Subject|Body|Content)(?:\*\*)?\s*[:：]\s*(.*)$""",
)

internal fun assistantMarkdownForGmailCards(
    markdown: String,
    invocations: List<ChatToolInvocation>,
): String {
    val drafts = collectGmailDraftInvocations(invocations).map {
        parseGmailDraftPreview(it.argumentsJson, it.outputJson)
    }
    return stripGmailDraftRecap(markdown, drafts)
}

internal fun stripGmailDraftRecap(
    markdown: String,
    drafts: List<GmailDraftPreview>,
): String {
    if (markdown.isBlank() || drafts.isEmpty()) return markdown
    val lines = markdown.replace("\r\n", "\n").split('\n')
    val kept = ArrayList<String>(lines.size)
    var skipQuotedBody = false
    lines.forEach { line ->
        val trimmed = line.trim()
        if (skipQuotedBody) {
            if (trimmed.startsWith(">")) return@forEach
            skipQuotedBody = false
        }
        if (GmailDraftHeadingLine.matches(trimmed)) return@forEach
        val field = GmailDraftFieldLine.matchEntire(trimmed)
        if (field != null) {
            val name = field.groupValues[1].lowercase()
            if (name == "内容" || name == "body" || name == "content") {
                skipQuotedBody = true
            }
            return@forEach
        }
        val quoted = trimmed.removePrefix(">").trim().trim('"', '*')
        val duplicatesDraft = drafts.any { draft ->
            val recipients = draft.to.joinToString(", ")
            quoted.equals(recipients, ignoreCase = true) ||
                quoted.equals(draft.subject, ignoreCase = true) ||
                (draft.body.isNotBlank() && quoted.isNotBlank() && draft.body.startsWith(quoted))
        }
        if (trimmed.startsWith(">") && duplicatesDraft) return@forEach
        kept += line
    }
    return kept.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
}

internal fun gmailSearchHitFromThread(obj: JSONObject): GmailSearchHit? {
    val id = obj.optString("id").ifBlank { obj.optString("threadId") }
    if (id.isBlank()) return null
    val messages = obj.optJSONArray("messages")
    val lastIndex = ((messages?.length() ?: 0) - 1).coerceAtLeast(0)
    val last = messages?.optJSONObject(lastIndex)
    val first = messages?.optJSONObject(0)
    val src = last ?: first ?: obj
    val senderRaw = src.optString("sender").ifBlank { src.optString("from") }
    val unread = (0 until (messages?.length() ?: 0)).any { index ->
        val labels = messages?.optJSONObject(index)?.optJSONArray("labelIds") ?: return@any false
        (0 until labels.length()).any { labels.optString(it).equals("UNREAD", ignoreCase = true) }
    }
    return GmailSearchHit(
        threadId = id,
        subject = src.optString("subject").ifBlank { obj.optString("subject") },
        from = gmailSenderDisplay(senderRaw),
        email = gmailSenderEmail(senderRaw),
        snippet = src.optString("snippet"),
        date = src.optString("date"),
        unread = unread,
    )
}

internal fun parseGmailThreadDetail(raw: String, fallbackId: String = ""): GmailThreadDetail? {
    val root = parseGmailToolObject(raw) ?: return null
    val ok = root.optBoolean("ok", true)
    if (!ok) {
        return GmailThreadDetail(
            threadId = fallbackId,
            subject = "",
            messages = emptyList(),
            error = root.optString("error").ifBlank { root.optString("reason") }
                .ifBlank { "Gmail request failed" },
        )
    }
    val threadId = root.optString("id").ifBlank { fallbackId }
    val messagesIn = root.optJSONArray("messages") ?: return if (threadId.isBlank()) {
        null
    } else {
        GmailThreadDetail(threadId = threadId, subject = "", messages = emptyList())
    }
    val messages = buildList {
        for (index in 0 until messagesIn.length()) {
            val item = messagesIn.optJSONObject(index) ?: continue
            val senderRaw = item.optString("sender").ifBlank { item.optString("from") }
            val html = item.optString("htmlBody")
            val body = gmailPlainBody(item)
            add(
                GmailThreadMessage(
                    id = item.optString("id"),
                    from = gmailSenderDisplay(senderRaw),
                    email = gmailSenderEmail(senderRaw),
                    date = item.optString("date"),
                    subject = item.optString("subject"),
                    body = body.ifBlank { item.optString("snippet") },
                    htmlBody = html,
                ),
            )
        }
    }
    return GmailThreadDetail(
        threadId = threadId,
        subject = messages.lastOrNull()?.subject.orEmpty()
            .ifBlank { messages.firstOrNull()?.subject.orEmpty() },
        messages = messages,
    )
}

internal fun gmailPlainBody(message: JSONObject): String {
    val plain = message.optString("plaintextBody").trim()
    if (plain.isNotBlank()) return plain
    val html = message.optString("htmlBody")
    if (html.isBlank()) return ""
    return html
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</p>"), "\n")
        .replace(Regex("<[^>]+>"), " ")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex(" *\\n *"), "\n")
        .trim()
}

internal fun gmailRenderableHtml(html: String, plain: String, dark: Boolean = false): String {
    val source = html.trim().ifBlank {
        if (plain.isBlank()) return ""
        "<pre>${escapeGmailHtml(plain)}</pre>"
    }
    val cleaned = source
        .replace(Regex("(?is)<script[^>]*>.*?</script>"), "")
        .replace(Regex("(?is)<iframe[^>]*>.*?</iframe>"), "")
    val css = if (dark) {
        """
        :root{color-scheme:dark;}
        html,body{margin:0;padding:0;background:#1c1c1e!important;color:#e8eaed!important;font:15px/1.45 sans-serif;overflow-wrap:anywhere;word-break:break-word;}
        img,video{max-width:100%!important;height:auto!important;}
        table{max-width:100%!important;}
        pre,code{white-space:pre-wrap;word-break:break-word;}
        blockquote{border-left:3px solid #5f6368;margin:0;padding-left:12px;color:#9aa0a6;}
        a{color:#8ab4f8;}
        """.trimIndent()
    } else {
        """
        html,body{margin:0;padding:0;background:#fff;color:#202124;font:15px/1.45 sans-serif;overflow-wrap:anywhere;word-break:break-word;}
        img,video{max-width:100%!important;height:auto!important;}
        table{max-width:100%!important;}
        pre,code{white-space:pre-wrap;word-break:break-word;}
        blockquote{border-left:3px solid #dadce0;margin:0;padding-left:12px;color:#5f6368;}
        a{color:#1a73e8;}
        """.trimIndent()
    }
    return if (Regex("(?is)<html[\\s>]").containsMatchIn(cleaned)) {
        when {
            Regex("(?is)<head[\\s>]").containsMatchIn(cleaned) ->
                cleaned.replaceFirst(Regex("(?is)<head([^>]*)>"), "<head$1><meta charset=\"utf-8\"><style>$css</style>")
            else ->
                cleaned.replaceFirst(
                    Regex("(?is)<html([^>]*)>"),
                    "<html$1><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>$css</style></head>",
                )
        }
    } else {
        "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>$css</style></head><body>$cleaned</body></html>"
    }
}

internal fun gmailAvatarUrls(email: String): List<String> {
    val address = email.trim().lowercase()
    if (address.isBlank() || !address.contains('@')) return emptyList()
    val md5 = runCatching {
        MessageDigest.getInstance("MD5").digest(address.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
    }.getOrDefault("")
    return listOfNotNull(
        "https://www.google.com/s2/photos/profile/${gmailPercentEncode(address)}",
        md5.takeIf { it.isNotBlank() }?.let { "https://www.gravatar.com/avatar/$it?s=80&d=404" },
    )
}

internal fun gmailThreadFromInvocations(
    invocations: List<ChatToolInvocation>,
    threadId: String,
): GmailThreadDetail? {
    if (threadId.isBlank()) return null
    invocations.forEach { invocation ->
        if (GmailMcp.canonicalToolName(invocation.toolName) != "get_thread") return@forEach
        if (invocation.isRunning || invocation.outputJson.isBlank()) return@forEach
        val args = runCatching { JSONObject(invocation.argumentsJson) }.getOrNull()
        val argId = args?.let { GmailCodec.stringArg(it, "threadId", "thread_id", "id") }.orEmpty()
        val parsed = GmailCardCache.getOrParseThread(invocation.outputJson, fallbackId = threadId)
            ?: return@forEach
        if (argId == threadId || parsed.threadId == threadId) return parsed
    }
    return null
}

private val GmailConsumeLeftoverVertical = object : NestedScrollConnection {
    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset = Offset(0f, available.y)

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        Velocity(0f, available.y)
}

private fun disallowParentIntercept(view: View, disallow: Boolean) {
    var current: ViewParent? = view.parent
    while (current != null) {
        if (current is ViewGroup) current.requestDisallowInterceptTouchEvent(disallow)
        current = current.parent
    }
}

@Composable
private fun Modifier.gmailScrollableIsolation(): Modifier {
    val view = LocalView.current
    return this
        .nestedScroll(GmailConsumeLeftoverVertical)
        .pointerInput(view) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                disallowParentIntercept(view, true)
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        if (event.changes.all { !it.pressed }) break
                    }
                } finally {
                    disallowParentIntercept(view, false)
                }
            }
        }
}

@Composable
internal fun GmailSearchCardStack(
    invocations: List<ChatToolInvocation>,
    relatedInvocations: List<ChatToolInvocation> = invocations,
    topPadding: Dp = 0.dp,
) {
    val live = collectGmailLiveInvocations(invocations)
    if (live.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        live.forEach { invocation ->
            when {
                invocation.isGmailDraftCard() -> GmailDraftCard(
                    toolInvocation = invocation,
                    topPadding = topPadding,
                )
                else -> GmailSearchCard(
                    toolInvocation = invocation,
                    relatedInvocations = relatedInvocations,
                    topPadding = topPadding,
                )
            }
        }
    }
}

@Composable
internal fun GmailSearchCard(
    toolInvocation: ChatToolInvocation,
    relatedInvocations: List<ChatToolInvocation> = emptyList(),
    topPadding: Dp = 6.dp,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val halfScreen = (LocalConfiguration.current.screenHeightDp.dp / 2).coerceAtLeast(220.dp)
    val snapshot = remember(toolInvocation.argumentsJson, toolInvocation.outputJson) {
        GmailCardCache.getOrParseSnapshot(toolInvocation.argumentsJson, toolInvocation.outputJson)
    }
    LaunchedEffect(toolInvocation.id, toolInvocation.argumentsJson, toolInvocation.outputJson, relatedInvocations) {
        val warm = collectGmailWarmInvocations(listOf(toolInvocation) + relatedInvocations)
        if (warm.isEmpty()) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            GmailCardCache.prefetch(warm)
        }
    }
    val query = snapshot.query
    var typedCount by rememberSaveable(toolInvocation.id) { mutableIntStateOf(0) }
    LaunchedEffect(toolInvocation.id, query) {
        if (query.isBlank()) {
            typedCount = 0
            return@LaunchedEffect
        }
        if (typedCount > query.length) typedCount = 0
        val step = when {
            query.length <= 12 -> 38L
            query.length <= 28 -> 26L
            else -> 16L
        }
        while (typedCount < query.length) {
            typedCount += 1
            delay(step)
        }
    }
    val typed = query.take(typedCount)
    val typing = typedCount < query.length && query.isNotBlank()
    val searching = toolInvocation.isRunning
    val showCaret = typing || searching
    var expanded by rememberSaveable(toolInvocation.id) { mutableStateOf(false) }
    var playedEnter by rememberSaveable(toolInvocation.id) { mutableStateOf(false) }
    LaunchedEffect(toolInvocation.id) {
        if (!playedEnter) {
            expanded = true
            playedEnter = true
        }
    }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 0f else 180f,
        animationSpec = tween(220),
        label = "gmail_search_chevron",
    )
    var selectedId by rememberSaveable(toolInvocation.id) { mutableStateOf<String?>(null) }
    val bodyCache = remember(toolInvocation.id) { mutableStateMapOf<String, GmailThreadDetail>() }
    var bodyLoading by remember(toolInvocation.id) { mutableStateOf(false) }
    val selectedHit = snapshot.threads.firstOrNull { it.threadId == selectedId }
    val selectedDetail = selectedId?.let { bodyCache[it] }
    val brandTitle = stringResource(R.string.gmail_search_card_title)
    val untitled = stringResource(R.string.gmail_search_untitled)
    val headerTitle = if (!expanded) {
        brandTitle
    } else {
        selectedHit?.subject?.ifBlank { untitled } ?: brandTitle
    }
    val bodyMaxHeight = (halfScreen - GmailExpandedHeaderHeight).coerceAtLeast(160.dp)

    fun openThread(hit: GmailSearchHit) {
        selectedId = hit.threadId
        expanded = true
        if (bodyCache.containsKey(hit.threadId)) return
        gmailThreadFromInvocations(relatedInvocations, hit.threadId)?.let { cached ->
            if (cached.error.isBlank() && cached.messages.isNotEmpty()) {
                bodyCache[hit.threadId] = cached
                return
            }
        }
        bodyLoading = true
        scope.launch {
            val raw = withContext(Dispatchers.IO) {
                GmailMcpHost.execute(
                    context,
                    "get_thread",
                    JSONObject().put("threadId", hit.threadId),
                )
            }
            val parsed = GmailCardCache.getOrParseThread(raw, fallbackId = hit.threadId)
                ?: GmailThreadDetail(
                    threadId = hit.threadId,
                    subject = hit.subject,
                    messages = emptyList(),
                    error = context.getString(R.string.gmail_search_body_failed),
                )
            bodyCache[hit.threadId] = parsed
            bodyLoading = false
        }
    }

    GmailCardSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPadding),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            GmailCardHeader(
                expanded = expanded,
                title = headerTitle,
                chevronRotation = chevronRotation,
                onToggle = { expanded = !expanded },
            )
            GmailExpandingBody(expanded = expanded) {
                AnimatedContent(
                    targetState = selectedId,
                    transitionSpec = {
                        val spec = if (targetState != null) {
                            (slideInHorizontally { it / 3 } + fadeIn(tween(220))) togetherWith
                                (slideOutHorizontally { -it / 6 } + fadeOut(tween(160)))
                        } else {
                            (slideInHorizontally { -it / 6 } + fadeIn(tween(220))) togetherWith
                                (slideOutHorizontally { it / 3 } + fadeOut(tween(160)))
                        }
                        spec.using(SizeTransform(clip = false))
                    },
                    label = "gmail_search_pane",
                ) { openId ->
                    if (openId == null) {
        GmailSearchListPane(
            typed = typed,
            query = query,
            showCaret = showCaret,
            searching = searching,
            typing = typing,
            snapshot = snapshot,
            maxHeight = bodyMaxHeight,
            onOpen = { openThread(it) },
        )
                    } else {
                        GmailSearchThreadPane(
                            hit = selectedHit,
                            detail = selectedDetail,
                            loading = bodyLoading && selectedDetail == null,
                            maxHeight = bodyMaxHeight,
                            threadId = openId,
                            query = query,
                            onBack = { selectedId = null },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GmailSearchListPane(
    typed: String,
    query: String,
    showCaret: Boolean,
    searching: Boolean,
    typing: Boolean,
    snapshot: GmailSearchSnapshot,
    maxHeight: Dp,
    onOpen: (GmailSearchHit) -> Unit,
) {
    val preview = gmailSearchPreview(snapshot.threads)
    val leftover = (snapshot.threads.size - preview.size).coerceAtLeast(0)
    val scroll = rememberScrollState()
    val overflow = scroll.maxValue > 0
    GmailClippedBody(maxHeight = maxHeight) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = GmailHeaderPadBottomCollapsed)
                .then(if (overflow) Modifier.gmailScrollableIsolation() else Modifier)
                .verticalScroll(scroll, enabled = overflow),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
        GmailSearchField(
            typed = typed,
            query = query,
            showCaret = showCaret,
            searching = searching,
        )
        when {
            snapshot.error.isNotBlank() -> {
                Text(
                    text = snapshot.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                )
            }
            searching && snapshot.threads.isEmpty() -> {
                Text(
                    text = stringResource(R.string.gmail_search_searching),
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                )
            }
            !searching && snapshot.threads.isEmpty() && query.isNotBlank() -> {
                Text(
                    text = stringResource(R.string.gmail_search_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                )
            }
            preview.isNotEmpty() -> {
                val total = snapshot.resultCountEstimate.takeIf { it > 0 } ?: snapshot.threads.size
                Text(
                    text = if (leftover > 0) {
                        stringResource(R.string.gmail_search_showing, preview.size, total)
                    } else {
                        stringResource(R.string.gmail_search_result_count, total)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = AetherOnSurfaceVariant,
                )
                preview.forEachIndexed { index, hit ->
                    var shown by remember(hit.threadId) { mutableStateOf(false) }
                    LaunchedEffect(hit.threadId) {
                        delay(index * 48L)
                        shown = true
                    }
                    AnimatedVisibility(
                        visible = shown,
                        enter = fadeIn(tween(180)),
                    ) {
                        GmailSearchHitRow(hit = hit, onOpen = { onOpen(hit) })
                    }
                }
                if (leftover > 0) {
                    Text(
                        text = stringResource(R.string.gmail_search_more, leftover),
                        style = MaterialTheme.typography.labelSmall,
                        color = AetherOnSurfaceVariant,
                    )
                }
            }
        }
        if (!searching && !typing) {
            GmailOpenChooser(query = query)
            Spacer(Modifier.height(GmailBodyBottomPad))
        }
    }
    }
}

@Composable
private fun GmailSearchField(
    typed: String,
    query: String,
    showCaret: Boolean,
    searching: Boolean,
) {
    val placeholder = if (query.isBlank()) {
        stringResource(R.string.gmail_search_recent)
    } else {
        stringResource(R.string.gmail_search_placeholder)
    }
    val infinite = rememberInfiniteTransition(label = "gmail_search_caret")
    val blinking by infinite.animateFloat(
        initialValue = 0.12f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(420),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "gmail_search_caret_alpha",
    )
    val caretAlpha = if (showCaret) blinking else 0f
    val shown = typed.ifBlank {
        if (!searching && query.isBlank()) placeholder else ""
    }
    val dimPlaceholder = typed.isBlank() && query.isBlank() && !searching
    val fill = lerp(AetherSurfaceHigh, GmailRed, 0.12f).copy(alpha = 0.78f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(fill)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = if (searching) GmailRed else AetherOnSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = shown,
                style = MaterialTheme.typography.bodyMedium,
                color = if (dimPlaceholder) AetherOnSurfaceVariant else AetherOnSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (showCaret) {
                Box(
                    modifier = Modifier
                        .padding(start = 1.dp)
                        .width(1.5.dp)
                        .height(16.dp)
                        .graphicsLayer { alpha = caretAlpha }
                        .background(GmailRed, RoundedCornerShape(1.dp)),
                )
            }
        }
    }
}

@Composable
private fun GmailSearchHitRow(
    hit: GmailSearchHit,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        GmailSenderAvatar(name = hit.from, email = hit.email, unread = hit.unread)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = hit.subject.ifBlank { stringResource(R.string.gmail_search_untitled) },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AetherOnSurface,
                    fontWeight = if (hit.unread) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (hit.date.isNotBlank()) {
                    Text(
                        text = hit.date,
                        style = MaterialTheme.typography.labelSmall,
                        color = AetherOnSurfaceVariant,
                    )
                }
            }
            if (hit.from.isNotBlank()) {
                Text(
                    text = hit.from,
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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

@Composable
private fun GmailSearchThreadPane(
    hit: GmailSearchHit?,
    detail: GmailThreadDetail?,
    loading: Boolean,
    maxHeight: Dp,
    threadId: String,
    query: String,
    onBack: () -> Unit,
) {
    GmailClippedBody(maxHeight = maxHeight) {
        val scroll = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = GmailHeaderPadBottomCollapsed)
                .verticalScroll(scroll)
                .gmailScrollableIsolation(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
        Row(
            modifier = Modifier
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onBack,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.gmail_search_back),
                tint = AetherOnSurface,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.gmail_search_back),
                style = MaterialTheme.typography.labelLarge,
                color = AetherOnSurface,
            )
        }
        when {
            loading -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = GmailRed,
                    )
                    Text(
                        text = stringResource(R.string.gmail_search_loading_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherOnSurfaceVariant,
                    )
                }
            }
            detail != null && detail.error.isNotBlank() -> {
                Text(
                    text = detail.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                )
            }
            else -> {
                val messages = detail?.messages.orEmpty()
                if (messages.isEmpty()) {
                    Text(
                        text = hit?.snippet?.ifBlank {
                            stringResource(R.string.gmail_search_body_failed)
                        } ?: stringResource(R.string.gmail_search_body_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AetherOnSurface,
                    )
                } else {
                    GmailThreadHtmlBody(
                        messages = messages,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = (maxHeight - 40.dp).coerceAtLeast(120.dp))
                            .gmailScrollableIsolation(),
                    )
                }
            }
        }
        GmailOpenChooser(threadId = threadId, query = query)
        Spacer(Modifier.height(GmailBodyBottomPad))
    }
    }
}

@Composable
private fun GmailThreadHtmlBody(
    messages: List<GmailThreadMessage>,
    modifier: Modifier = Modifier,
) {
    val dark = AetherIsDark
    val html = remember(messages, dark) { GmailCardCache.getOrBuildHtml(messages, dark) }
    val pageColor = if (dark) Color(0xFF1C1C1E) else Color.White
    val pageShape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier.background(pageColor, pageShape),
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp),
            factory = { viewContext ->
                WebView(viewContext).apply {
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    clipToOutline = true
                    outlineProvider = ViewOutlineProvider.BOUNDS
                    settings.javaScriptEnabled = false
                    settings.domStorageEnabled = false
                    settings.loadsImagesAutomatically = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    isVerticalScrollBarEnabled = true
                    isHorizontalScrollBarEnabled = false
                    isFocusable = true
                    ViewCompat.setNestedScrollingEnabled(this, false)
                    setOnTouchListener { view, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN,
                            MotionEvent.ACTION_MOVE,
                            -> view.parent?.requestDisallowInterceptTouchEvent(true)
                            MotionEvent.ACTION_UP,
                            MotionEvent.ACTION_CANCEL,
                            -> view.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                        false
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            val target = request?.url ?: return false
                            runCatching {
                                viewContext.startActivity(
                                    Intent(Intent.ACTION_VIEW, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                            return true
                        }
                    }
                }
            },
            update = { webView ->
                applyGmailWebViewDark(webView, dark)
                val tag = "$dark|$html"
                if (webView.tag != tag) {
                    webView.tag = tag
                    webView.loadDataWithBaseURL(
                        "https://mail.google.com/",
                        html,
                        "text/html",
                        "utf-8",
                        null,
                    )
                }
            },
        )
    }
}

@Suppress("DEPRECATION")
private fun applyGmailWebViewDark(webView: WebView, dark: Boolean) {
    webView.setBackgroundColor((if (dark) Color(0xFF1C1C1E) else Color.White).toArgb())
    if (Build.VERSION.SDK_INT >= 33) {
        webView.settings.isAlgorithmicDarkeningAllowed = dark
    } else if (Build.VERSION.SDK_INT >= 29) {
        webView.settings.forceDark =
            if (dark) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
    }
}

@Composable
private fun GmailSenderAvatar(
    name: String,
    email: String,
    unread: Boolean,
) {
    val cached = remember(email) { GmailCardCache.peekAvatar(email) }
    val bitmap by produceState(initialValue = cached, email) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) { GmailCardCache.loadAvatar(email) }
    }
    val letter = name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "G"
    val fallback = GmailAvatarPalette[kotlin.math.abs(email.ifBlank { name }.hashCode()) % GmailAvatarPalette.size]
    val avatar = bitmap
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(if (avatar == null) fallback.copy(alpha = if (unread) 0.95f else 0.82f) else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        if (avatar != null) {
            Image(
                bitmap = avatar,
                contentDescription = name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = letter,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
internal fun GmailDraftCard(
    toolInvocation: ChatToolInvocation,
    topPadding: Dp = 6.dp,
) {
    val halfScreen = (LocalConfiguration.current.screenHeightDp.dp / 2).coerceAtLeast(220.dp)
    val preview = remember(toolInvocation.argumentsJson, toolInvocation.outputJson) {
        GmailCardCache.getOrParseDraft(toolInvocation.argumentsJson, toolInvocation.outputJson)
    }
    var expanded by rememberSaveable(toolInvocation.id) { mutableStateOf(false) }
    var playedEnter by rememberSaveable(toolInvocation.id) { mutableStateOf(false) }
    LaunchedEffect(toolInvocation.id) {
        if (!playedEnter) {
            expanded = true
            playedEnter = true
        }
    }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 0f else 180f,
        animationSpec = tween(220),
        label = "gmail_draft_chevron",
    )
    val recipients = preview.to.joinToString(", ")
    val draftTitle = stringResource(R.string.gmail_draft_card_title)
    val brandTitle = stringResource(R.string.gmail_search_card_title)
    val headerTitle = if (expanded) draftTitle else brandTitle
    val bodyMaxHeight = (halfScreen - GmailExpandedHeaderHeight).coerceAtLeast(160.dp)
    GmailCardSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPadding),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            GmailCardHeader(
                expanded = expanded,
                title = headerTitle,
                chevronRotation = chevronRotation,
                onToggle = { expanded = !expanded },
            )
            GmailExpandingBody(expanded = expanded) {
                GmailClippedBody(maxHeight = bodyMaxHeight) {
                    val scroll = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = GmailHeaderPadBottomCollapsed)
                            .verticalScroll(scroll)
                            .gmailScrollableIsolation(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                GmailDraftField(
                    label = stringResource(R.string.gmail_draft_to),
                    value = recipients.ifBlank { "—" },
                )
                GmailDraftField(
                    label = stringResource(R.string.gmail_draft_subject),
                    value = preview.subject.ifBlank { stringResource(R.string.gmail_search_untitled) },
                )
                Text(
                    text = stringResource(R.string.gmail_draft_body),
                    style = MaterialTheme.typography.labelMedium,
                    color = AetherOnSurfaceVariant,
                )
                if (preview.error.isNotBlank()) {
                    Text(
                        text = preview.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherOnSurfaceVariant,
                    )
                } else if (preview.htmlBody.isNotBlank() || preview.body.isNotBlank()) {
                    GmailThreadHtmlBody(
                        messages = listOf(
                            GmailThreadMessage(
                                id = preview.draftId,
                                from = recipients,
                                email = preview.to.firstOrNull().orEmpty(),
                                date = "",
                                subject = preview.subject,
                                body = preview.body,
                                htmlBody = preview.htmlBody,
                            ),
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = (halfScreen - 160.dp).coerceAtLeast(120.dp))
                            .gmailScrollableIsolation(),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.gmail_search_empty_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherOnSurfaceVariant,
                    )
                }
                GmailOpenChooser(
                    threadId = preview.threadId,
                    draftId = preview.draftId,
                    messageId = preview.messageId,
                )
                Spacer(Modifier.height(GmailBodyBottomPad))
                    }
                }
            }
        }
    }
}

@Composable
private fun GmailDraftField(
    label: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = AetherOnSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = AetherOnSurface,
        )
    }
}

@Composable
private fun GmailOpenChooser(
    threadId: String = "",
    query: String = "",
    draftId: String = "",
    messageId: String = "",
) {
    val context = LocalContext.current
    fun open(destination: GmailOpenDestination) {
        val opened = openGmail(
            context = context,
            threadId = threadId,
            query = query,
            destination = destination,
            draftId = draftId,
            messageId = messageId,
        )
        if (!opened) {
            val missingApp = destination == GmailOpenDestination.App &&
                !gmailAppInstalled(context)
            val message = context.getString(
                if (missingApp) {
                    R.string.gmail_search_open_app_missing
                } else {
                    R.string.gmail_search_open_failed
                },
            )
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GmailOpenChip(
            label = stringResource(R.string.gmail_search_open_app),
            modifier = Modifier.weight(1f),
            onClick = { open(GmailOpenDestination.App) },
        )
        GmailOpenChip(
            label = stringResource(R.string.gmail_search_open_web),
            modifier = Modifier.weight(1f),
            onClick = { open(GmailOpenDestination.Web) },
        )
    }
}

@Composable
private fun GmailOpenChip(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(lerp(AetherSurfaceHigh, GmailRed, 0.10f).copy(alpha = 0.72f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = AetherOnSurface,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun GmailCardHeader(
    expanded: Boolean,
    title: String,
    chevronRotation: Float,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onToggle,
            )
            .padding(
                start = 14.dp,
                end = 14.dp,
                top = GmailHeaderPadTop,
                bottom = GmailHeaderPadBottomCollapsed,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GmailMark(modifier = Modifier.size(GmailHeaderIconSize))
        SpotifyBlurTitle(
            text = title,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Rounded.KeyboardDoubleArrowLeft,
            contentDescription = stringResource(
                if (expanded) R.string.gmail_search_collapse else R.string.gmail_search_expand,
            ),
            tint = AetherOnSurfaceVariant,
            modifier = Modifier
                .size(GmailHeaderIconSize)
                .graphicsLayer { rotationZ = chevronRotation },
        )
    }
}

@Composable
private fun GmailExpandingBody(
    expanded: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn(animationSpec = tween(220)) + expandVertically(
            animationSpec = tween(280),
            expandFrom = Alignment.Top,
        ),
        exit = fadeOut(animationSpec = tween(180)) + shrinkVertically(
            animationSpec = tween(240),
            shrinkTowards = Alignment.Top,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
private fun GmailClippedBody(
    maxHeight: Dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val fill = gmailCardFill()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight),
    ) {
        content()
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(GmailHeaderPadBottomCollapsed)
                .background(
                    Brush.verticalGradient(
                        0f to fill,
                        1f to Color.Transparent,
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(GmailBodyBottomPad)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to fill,
                    ),
                ),
        )
    }
}

@Composable
private fun gmailCardDyedSurface(): Color = lerp(AetherSurface, GmailCardGray, 0.05f)

@Composable
private fun gmailCardFill(): Color = gmailCardDyedSurface().copy(alpha = GmailCardAlpha)

@Composable
internal fun GmailCardSurface(
    modifier: Modifier = Modifier,
    frostBackdrop: Boolean = false,
    shape: Shape = RoundedCornerShape(20.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val hazeState = LocalSpotifyHazeState.current
    val dyedSurface = gmailCardDyedSurface()
    val fill = gmailCardFill()
    val gaussianState = hazeState.takeIf { frostBackdrop }
    val frostTintAlpha = 0.68f
    Box(
        modifier = modifier
            .then(
                if (gaussianState != null) {
                    Modifier
                        .clip(shape)
                        .hazeEffect(state = gaussianState) {
                            blurEnabled = true
                            backgroundColor = dyedSurface
                            this.blurRadius = GmailTimelineBlurRadius
                            tints = listOf(
                                HazeTint(dyedSurface.copy(alpha = frostTintAlpha)),
                                HazeTint(GmailCardGray.copy(alpha = 0.05f)),
                            )
                        }
                } else {
                    Modifier.background(fill, shape)
                },
            )
            .border(0.5.dp, AetherOutlineSoft.copy(alpha = 0.55f), shape),
        content = content,
    )
}

@Composable
internal fun GmailMark(
    modifier: Modifier = Modifier,
    contentDescription: String? = "Gmail",
) {
    Image(
        painter = painterResource(R.drawable.ic_gmail_card),
        contentDescription = contentDescription,
        modifier = modifier,
    )
}
