package kira.ditto.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal object GmailMcpHost {
    private const val GmailApiRoot = "https://gmail.googleapis.com/gmail/v1/users/me"

    fun execute(context: Context, name: String, arguments: JSONObject): String {
        val tool = GmailMcp.canonicalToolName(name)
        if (tool.isBlank()) {
            return errorJson("unknown_tool", "Unknown Gmail tool: $name")
        }
        val store = HostSecretStore(context.applicationContext)
        var token = runCatching { GmailAuth.accessToken(store) }.getOrDefault("")
        if (token.isBlank()) {
            runCatching { GmailAuth.connectWithBrowser(context, store) }
            token = runCatching { GmailAuth.accessToken(store) }.getOrDefault("")
        }
        if (token.isBlank()) {
            return JSONObject()
                .put("ok", false)
                .put("code", "input_required")
                .put(
                    "reason",
                    "Gmail is not signed in. Select the Gmail MCP in the composer and finish Google webpage login.",
                )
                .toString()
        }
        return runCatching { dispatch(tool, arguments, token).put("ok", true).toString() }
            .getOrElse { error ->
                errorJson("gmail_api", error.message ?: "Gmail request failed")
            }
    }

    internal fun dispatch(tool: String, arguments: JSONObject, token: String): JSONObject = when (tool) {
        "search_threads" -> searchThreads(arguments, token)
        "get_thread" -> getThread(arguments, token)
        "list_labels" -> listLabels(token)
        "list_drafts" -> listDrafts(arguments, token)
        "create_draft" -> createDraft(arguments, token)
        "label_message" -> modifyMessage(arguments, token, add = true)
        "unlabel_message" -> modifyMessage(arguments, token, add = false)
        "label_thread" -> modifyThread(arguments, token, add = true)
        "unlabel_thread" -> modifyThread(arguments, token, add = false)
        else -> error("Unknown Gmail tool: $tool")
    }

    private fun searchThreads(arguments: JSONObject, token: String): JSONObject {
        val pageSize = GmailCodec.intArg(arguments, 20, 50, "pageSize", "page_size")
        val query = GmailCodec.stringArg(arguments, "query")
        val pageToken = GmailCodec.stringArg(arguments, "pageToken", "page_token")
        val includeTrash = GmailCodec.boolArg(arguments, false, "includeTrash", "include_trash")
        val metadataOnly = GmailCodec.stringArg(arguments, "view")
            .contains("METADATA", ignoreCase = true)
        val listed = GmailAuth.gmailGet(
            buildUrl(
                "$GmailApiRoot/threads",
                "maxResults" to pageSize.toString(),
                "q" to query,
                "pageToken" to pageToken,
                "includeSpamTrash" to includeTrash.toString(),
            ),
            token,
        )
        val threadsIn = listed.optJSONArray("threads") ?: JSONArray()
        val threads = JSONArray()
        val limit = minOf(threadsIn.length(), pageSize)
        for (index in 0 until limit) {
            val id = threadsIn.optJSONObject(index)?.optString("id").orEmpty()
            if (id.isBlank()) continue
            val full = GmailAuth.gmailGet(
                "$GmailApiRoot/threads/${enc(id)}?format=metadata&metadataHeaders=From&metadataHeaders=To&metadataHeaders=Cc&metadataHeaders=Subject&metadataHeaders=Date",
                token,
            )
            threads.put(GmailCodec.compactThread(full, metadataOnly))
        }
        return JSONObject()
            .put("threads", threads)
            .put("nextPageToken", listed.optString("nextPageToken"))
            .put("resultCountEstimate", listed.opt("resultSizeEstimate") ?: JSONObject.NULL)
    }

    private fun getThread(arguments: JSONObject, token: String): JSONObject {
        val id = GmailCodec.stringArg(arguments, "threadId", "thread_id", "id")
        require(id.isNotBlank()) { "threadId is required" }
        val full = GmailAuth.gmailGet("$GmailApiRoot/threads/${enc(id)}?format=full", token)
        return GmailCodec.compactThread(full, metadataOnly = false, fullBody = true)
    }

    private fun listLabels(token: String): JSONObject {
        val raw = GmailAuth.gmailGet("$GmailApiRoot/labels", token)
        val labelsIn = raw.optJSONArray("labels") ?: JSONArray()
        val labels = JSONArray()
        for (index in 0 until labelsIn.length()) {
            val item = labelsIn.optJSONObject(index) ?: continue
            labels.put(
                JSONObject()
                    .put("id", item.optString("id"))
                    .put("name", item.optString("name"))
                    .put("type", item.optString("type")),
            )
        }
        return JSONObject().put("labels", labels)
    }

    private fun listDrafts(arguments: JSONObject, token: String): JSONObject {
        val pageSize = GmailCodec.intArg(arguments, 20, 50, "pageSize", "page_size")
        val query = GmailCodec.stringArg(arguments, "query")
        val pageToken = GmailCodec.stringArg(arguments, "pageToken", "page_token")
        val listed = GmailAuth.gmailGet(
            buildUrl(
                "$GmailApiRoot/drafts",
                "maxResults" to pageSize.toString(),
                "q" to query,
                "pageToken" to pageToken,
            ),
            token,
        )
        val draftsIn = listed.optJSONArray("drafts") ?: JSONArray()
        val drafts = JSONArray()
        for (index in 0 until minOf(draftsIn.length(), pageSize)) {
            val id = draftsIn.optJSONObject(index)?.optString("id").orEmpty()
            if (id.isBlank()) continue
            val full = GmailAuth.gmailGet("$GmailApiRoot/drafts/${enc(id)}?format=full", token)
            drafts.put(GmailCodec.compactDraft(full, metadataOnly = false))
        }
        return JSONObject()
            .put("drafts", drafts)
            .put("nextPageToken", listed.optString("nextPageToken"))
    }

    private fun createDraft(arguments: JSONObject, token: String): JSONObject {
        val to = GmailCodec.stringList(arguments, "to")
        val cc = GmailCodec.stringList(arguments, "cc")
        val bcc = GmailCodec.stringList(arguments, "bcc")
        val subject = GmailCodec.stringArg(arguments, "subject")
        val body = GmailCodec.stringArg(arguments, "body")
        val htmlBody = GmailCodec.stringArg(arguments, "htmlBody", "html_body")
        val replyToId = GmailCodec.stringArg(arguments, "replyToMessageId", "reply_to_message_id")
        var threadId = ""
        var inReplyTo = ""
        var references = ""
        if (replyToId.isNotBlank()) {
            val original = GmailAuth.gmailGet("$GmailApiRoot/messages/${enc(replyToId)}?format=metadata&metadataHeaders=Message-ID&metadataHeaders=References&metadataHeaders=Subject", token)
            threadId = original.optString("threadId")
            val headers = GmailCodec.headerMap(
                original.optJSONObject("payload")?.optJSONArray("headers") ?: JSONArray(),
            )
            inReplyTo = headers["message-id"].orEmpty()
            references = listOf(headers["references"], inReplyTo)
                .filter { !it.isNullOrBlank() }
                .joinToString(" ")
            if (subject.isBlank()) {
                val originalSubject = headers["subject"].orEmpty()
                if (originalSubject.isNotBlank() && !originalSubject.startsWith("Re:", ignoreCase = true)) {
                    arguments.put("subject", "Re: $originalSubject")
                }
            }
        }
        val rfc = GmailCodec.rfc2822Draft(
            to = to,
            cc = cc,
            bcc = bcc,
            subject = GmailCodec.stringArg(arguments, "subject").ifBlank { subject },
            body = body,
            htmlBody = htmlBody,
            inReplyTo = inReplyTo,
            references = references,
        )
        val message = JSONObject().put("raw", GmailCodec.rawUrlSafe(rfc))
        if (threadId.isNotBlank()) message.put("threadId", threadId)
        val created = GmailAuth.gmailPost(
            "$GmailApiRoot/drafts",
            token,
            JSONObject().put("message", message),
        )
        return JSONObject()
            .put("id", created.optString("id"))
            .put("messageId", created.optJSONObject("message")?.optString("id").orEmpty())
            .put("threadId", created.optJSONObject("message")?.optString("threadId").orEmpty())
    }

    private fun modifyMessage(arguments: JSONObject, token: String, add: Boolean): JSONObject {
        val id = GmailCodec.stringArg(arguments, "messageId", "message_id", "id")
        require(id.isNotBlank()) { "messageId is required" }
        val labels = GmailCodec.stringList(arguments, "labelIds", "label_ids")
        require(labels.isNotEmpty()) { "labelIds is required" }
        val body = JSONObject().put(
            if (add) "addLabelIds" else "removeLabelIds",
            JSONArray(labels),
        )
        val updated = GmailAuth.gmailPost("$GmailApiRoot/messages/${enc(id)}/modify", token, body)
        return JSONObject()
            .put("id", updated.optString("id"))
            .put("threadId", updated.optString("threadId"))
            .put("labelIds", updated.optJSONArray("labelIds") ?: JSONArray())
    }

    private fun modifyThread(arguments: JSONObject, token: String, add: Boolean): JSONObject {
        val id = GmailCodec.stringArg(arguments, "threadId", "thread_id", "id")
        require(id.isNotBlank()) { "threadId is required" }
        val labels = GmailCodec.stringList(arguments, "labelIds", "label_ids")
        require(labels.isNotEmpty()) { "labelIds is required" }
        val body = JSONObject().put(
            if (add) "addLabelIds" else "removeLabelIds",
            JSONArray(labels),
        )
        val updated = GmailAuth.gmailPost("$GmailApiRoot/threads/${enc(id)}/modify", token, body)
        return JSONObject()
            .put("id", updated.optString("id"))
            .put("labelIds", updated.optJSONArray("labelIds") ?: JSONArray())
    }

    private fun buildUrl(base: String, vararg query: Pair<String, String>): String {
        val parts = query.mapNotNull { (key, value) ->
            value.trim().takeIf { it.isNotBlank() }?.let { "$key=${enc(it)}" }
        }
        return if (parts.isEmpty()) base else "$base?${parts.joinToString("&")}"
    }

    private fun enc(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun errorJson(code: String, message: String): String = JSONObject()
        .put("ok", false)
        .put("code", code)
        .put("error", message)
        .toString()
}
