package kira.ditto.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.net.HttpURLConnection
import java.net.URL
import kira.ditto.data.GmailCodec
import kira.ditto.data.GmailMcp
import org.json.JSONObject

/**
 * Warm cache for Gmail live cards: parsed search snapshots, sender avatars, and
 * assembled HTML documents. Conversation prefetch fills this for nearby LazyColumn
 * items the same way [MarkdownBlockCache] warms markdown.
 */
internal object GmailCardCache {
    private val snapshots = GmailEntryLru<GmailSearchSnapshot>(48)
    private val drafts = GmailEntryLru<GmailDraftPreview>(24)
    private val threads = GmailEntryLru<GmailThreadDetail>(24)
    private val htmlDocs = GmailEntryLru<String>(24)
    private val avatars = GmailBitmapLru(2 * 1024 * 1024)

    fun peekSnapshot(argumentsJson: String, outputJson: String): GmailSearchSnapshot? =
        snapshots.get(snapshotKey(argumentsJson, outputJson))

    fun getOrParseSnapshot(argumentsJson: String, outputJson: String): GmailSearchSnapshot {
        val key = snapshotKey(argumentsJson, outputJson)
        snapshots.get(key)?.let { return it }
        return parseGmailSearchSnapshot(argumentsJson, outputJson).also { snapshots.put(key, it) }
    }

    fun getOrParseDraft(argumentsJson: String, outputJson: String): GmailDraftPreview {
        val key = "draft\n" + snapshotKey(argumentsJson, outputJson)
        drafts.get(key)?.let { return it }
        return parseGmailDraftPreview(argumentsJson, outputJson).also { drafts.put(key, it) }
    }

    fun getOrParseThread(outputJson: String, fallbackId: String): GmailThreadDetail? {
        val key = "$fallbackId\n$outputJson"
        threads.get(key)?.let { return it }
        return parseGmailThreadDetail(outputJson, fallbackId)?.also { threads.put(key, it) }
    }

    fun getOrBuildHtml(messages: List<GmailThreadMessage>, dark: Boolean = false): String {
        val key = "${if (dark) "d" else "l"}\u0001" +
            messages.joinToString("\u0001") { "${it.id}\n${it.htmlBody}\n${it.body}" }
        htmlDocs.get(key)?.let { return it }
        val rule = if (dark) "#3c4043" else "#eee"
        val muted = if (dark) "#9aa0a6" else "#5f6368"
        val parts = messages.map { message ->
            val heading = buildString {
                append("<div style=\"padding:10px 0 6px;border-bottom:1px solid $rule;\">")
                append("<div style=\"font-weight:600;\">${escapeGmailHtml(message.from)}</div>")
                if (message.date.isNotBlank()) {
                    append("<div style=\"color:$muted;font-size:12px;\">${escapeGmailHtml(message.date)}</div>")
                }
                append("</div>")
            }
            val rendered = gmailRenderableHtml(message.htmlBody, message.body, dark)
            val inner = rendered.substringAfter("<body>", missingDelimiterValue = "")
                .substringBeforeLast("</body>")
                .ifBlank { rendered }
            heading + inner
        }
        return gmailRenderableHtml(
            parts.joinToString("<hr style=\"border:0;border-top:1px solid $rule;margin:16px 0;\">"),
            "",
            dark,
        ).also { htmlDocs.put(key, it) }
    }

    fun peekAvatar(email: String): ImageBitmap? {
        val key = email.trim().lowercase()
        if (key.isBlank()) return null
        return avatars.get(key)?.asImageBitmap()
    }

    fun prefetch(invocations: List<ChatToolInvocation>) {
        invocations.forEach { invocation ->
            runCatching {
                when (GmailMcp.canonicalToolName(invocation.toolName)) {
                    "search_threads" -> {
                        val snapshot = getOrParseSnapshot(
                            invocation.argumentsJson,
                            invocation.outputJson,
                        )
                        gmailSearchPreview(snapshot.threads).forEach { hit -> loadAvatar(hit.email) }
                    }
                    "create_draft" -> {
                        val draft = getOrParseDraft(invocation.argumentsJson, invocation.outputJson)
                        if (draft.htmlBody.isNotBlank() || draft.body.isNotBlank()) {
                            val message = GmailThreadMessage(
                                id = draft.draftId,
                                from = draft.to.joinToString(", "),
                                email = draft.to.firstOrNull().orEmpty(),
                                date = "",
                                subject = draft.subject,
                                body = draft.body,
                                htmlBody = draft.htmlBody,
                            )
                            getOrBuildHtml(listOf(message), dark = false)
                            getOrBuildHtml(listOf(message), dark = true)
                        }
                    }
                    "get_thread" -> {
                        if (invocation.outputJson.isBlank()) return@forEach
                        val args = runCatching { JSONObject(invocation.argumentsJson) }.getOrNull()
                        val threadId = args?.let {
                            GmailCodec.stringArg(it, "threadId", "thread_id", "id")
                        }.orEmpty()
                        val detail = getOrParseThread(invocation.outputJson, fallbackId = threadId)
                            ?: return@forEach
                        if (detail.messages.isNotEmpty()) {
                            getOrBuildHtml(detail.messages, dark = false)
                            getOrBuildHtml(detail.messages, dark = true)
                            detail.messages
                                .map { it.email }
                                .distinct()
                                .take(4)
                                .forEach { loadAvatar(it) }
                        }
                    }
                }
            }
        }
    }

    fun loadAvatar(email: String): ImageBitmap? {
        val key = email.trim().lowercase()
        if (key.isBlank() || !key.contains('@')) return null
        avatars.get(key)?.let { return it.asImageBitmap() }
        val bitmap = gmailAvatarUrls(key).firstNotNullOfOrNull { url ->
            runCatching {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 1200
                    readTimeout = 1200
                    instanceFollowRedirects = true
                }
                connection.inputStream.use { stream ->
                    BitmapFactory.decodeStream(stream)?.takeIf { it.width >= 16 }
                }
            }.getOrNull()
        } ?: return null
        avatars.put(key, bitmap)
        return bitmap.asImageBitmap()
    }

    private fun snapshotKey(argumentsJson: String, outputJson: String): String =
        argumentsJson + "\n" + outputJson
}

private class GmailEntryLru<V>(private val maxEntries: Int) {
    private val map = object : LinkedHashMap<String, V>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?): Boolean =
            size > maxEntries
    }

    @Synchronized fun get(key: String): V? = map[key]

    @Synchronized fun put(key: String, value: V) {
        map[key] = value
    }
}

private class GmailBitmapLru(private val maxBytes: Int) {
    private val map = LinkedHashMap<String, Bitmap>(16, 0.75f, true)
    private var bytes = 0

    @Synchronized fun get(key: String): Bitmap? = map[key]

    @Synchronized fun put(key: String, value: Bitmap) {
        map.remove(key)?.let { bytes -= bitmapBytes(it) }
        map[key] = value
        bytes += bitmapBytes(value)
        val iterator = map.entries.iterator()
        while (bytes > maxBytes && iterator.hasNext()) {
            val eldest = iterator.next()
            iterator.remove()
            bytes -= bitmapBytes(eldest.value)
        }
    }

    private fun bitmapBytes(bitmap: Bitmap): Int = bitmap.byteCount.coerceAtLeast(1)
}

internal fun escapeGmailHtml(raw: String): String = buildString(raw.length) {
    raw.forEach { ch ->
        when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(ch)
        }
    }
}
