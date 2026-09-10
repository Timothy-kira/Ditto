package kira.ditto.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Gmail MCP served on the loopback gateway and listed like any other MCP.
 * Tool names match Google's Gmail MCP. Auth is Google webpage OAuth.
 */
internal object GmailMcp {
    const val PluginId = "aether-gmail"
    const val ServerName = "gmail"

    val ToolNames: List<String> = listOf(
        "search_threads",
        "get_thread",
        "list_labels",
        "list_drafts",
        "create_draft",
        "label_message",
        "label_thread",
        "unlabel_message",
        "unlabel_thread",
    )

    fun httpUrl(): String = upaMcpHttpUrl(PluginId)

    fun mcpServerConfig(sessionId: String = ""): McpServerConfig = McpServerConfig(
        id = PluginId,
        displayName = ServerName,
        actionLabel = "Gmail",
        transport = McpTransportConfig.StreamableHttp(
            url = httpUrl(),
            headers = learningSessionHeaders(sessionId),
        ),
        isEnabled = true,
    )

    fun isShippedServerId(serverId: String): Boolean = serverId == PluginId

    fun toAcpServer(sessionId: String = ""): JSONObject? =
        mcpServerConfig(sessionId).toAcpMcpServer()

    fun initializeResult(): JSONObject = JSONObject()
        .put("protocolVersion", UpaMcpProtocolVersion)
        .put("capabilities", JSONObject().put("tools", JSONObject()))
        .put(
            "serverInfo",
            JSONObject()
                .put("name", ServerName)
                .put("version", "1"),
        )

    fun listToolsResult(): JSONObject = JSONObject().put("tools", toolsArray())

    fun matchesToolName(name: String): Boolean {
        val n = name.trim().lowercase().replace('-', '_')
        if (n.contains("gmail") && ToolNames.any { tool -> n.endsWith(tool) || n.contains("_$tool") }) {
            return true
        }
        return ToolNames.any { tool ->
            n == tool || n.endsWith("_$tool") || n.endsWith("__$tool")
        }
    }

    fun canonicalToolName(name: String): String {
        val n = name.trim().lowercase().replace('-', '_')
        return ToolNames.firstOrNull { tool ->
            n == tool || n.endsWith("_$tool") || n.endsWith("__$tool")
        }.orEmpty()
    }

    fun wrapCallResult(rawOutput: String): JSONObject {
        val parsed = runCatching { JSONObject(rawOutput) }.getOrNull()
        val visible = parsed?.toString() ?: rawOutput
        val code = parsed?.optString("code").orEmpty()
        val isError = parsed?.optBoolean("ok", true) == false && code != "input_required"
        val result = JSONObject()
            .put(
                "content",
                JSONArray().put(
                    JSONObject()
                        .put("type", "text")
                        .put("text", visible),
                ),
            )
            .put("structuredContent", parsed ?: JSONObject().put("raw", rawOutput))
            .put("isError", isError)
        if (code == "input_required") {
            result.put("_meta", JSONObject().put("input_required", true))
        }
        return result
    }

    private fun toolsArray(): JSONArray = JSONArray().apply {
        put(
            tool(
                "search_threads",
                "Search the user's Gmail threads. query uses Gmail search syntax " +
                    "(from:, to:, subject:, is:unread, newer_than:7d, has:attachment, in:inbox). " +
                    "Returns thread ids plus subject/snippet/from/to — not full bodies. " +
                    "Use get_thread for the full conversation. Defaults to 20 threads, max 50.",
                extra = JSONObject()
                    .put("query", stringProp("Gmail search syntax. Omit to list recent threads."))
                    .put("pageSize", intProp("Max threads to return. Default 20, max 50."))
                    .put("page_size", intProp("Alias for pageSize."))
                    .put("pageToken", stringProp("Pagination token from a previous search_threads call."))
                    .put("page_token", stringProp("Alias for pageToken."))
                    .put("includeTrash", boolProp("Include TRASH. Default false."))
                    .put(
                        "view",
                        stringProp("THREAD_VIEW_MINIMAL (default) or THREAD_VIEW_METADATA_ONLY."),
                    ),
            ),
        )
        put(
            tool(
                "get_thread",
                "Fetch one Gmail thread by id, including plaintext bodies. " +
                    "Pass the thread id from search_threads.",
                extra = JSONObject()
                    .put("threadId", stringProp("Thread id from search_threads."))
                    .put("thread_id", stringProp("Alias for threadId."))
                    .put("id", stringProp("Alias for threadId.")),
                required = listOf(),
            ),
        )
        put(
            tool(
                "list_labels",
                "List Gmail labels (system and user). Use the returned id with " +
                    "label_message / label_thread / Gmail search label:<id>.",
            ),
        )
        put(
            tool(
                "list_drafts",
                "List Gmail drafts. query uses the same Gmail search syntax as search_threads.",
                extra = JSONObject()
                    .put("query", stringProp("Optional Gmail search syntax filter."))
                    .put("pageSize", intProp("Max drafts. Default 20, max 50."))
                    .put("page_size", intProp("Alias for pageSize."))
                    .put("pageToken", stringProp("Pagination token."))
                    .put("page_token", stringProp("Alias for pageToken.")),
            ),
        )
        put(
            tool(
                "create_draft",
                "Create a Gmail draft (does not send). to/cc/bcc must be plain email addresses. " +
                    "Pass replyToMessageId to reply in that thread. Attachments are not supported. " +
                    "Do not restate to/subject/body in the assistant message; the live card already previews them. " +
                    "Only add extra remarks, such as noreply warnings or asking whether to send.",
                extra = JSONObject()
                    .put("to", stringArrayProp("Primary recipients. Plain addresses only."))
                    .put("cc", stringArrayProp("Cc recipients."))
                    .put("bcc", stringArrayProp("Bcc recipients."))
                    .put("subject", stringProp("Subject line."))
                    .put("body", stringProp("Plain-text body. If htmlBody is set, this is the text alternative."))
                    .put("htmlBody", stringProp("HTML body."))
                    .put("html_body", stringProp("Alias for htmlBody."))
                    .put("replyToMessageId", stringProp("Message id to reply to."))
                    .put("reply_to_message_id", stringProp("Alias for replyToMessageId.")),
            ),
        )
        put(
            tool(
                "label_message",
                "Add labels to one Gmail message.",
                extra = JSONObject()
                    .put("messageId", stringProp("Message id."))
                    .put("message_id", stringProp("Alias for messageId."))
                    .put("id", stringProp("Alias for messageId."))
                    .put("labelIds", stringArrayProp("Label ids from list_labels."))
                    .put("label_ids", stringArrayProp("Alias for labelIds.")),
            ),
        )
        put(
            tool(
                "label_thread",
                "Add labels to every message in a Gmail thread.",
                extra = JSONObject()
                    .put("threadId", stringProp("Thread id."))
                    .put("thread_id", stringProp("Alias for threadId."))
                    .put("id", stringProp("Alias for threadId."))
                    .put("labelIds", stringArrayProp("Label ids from list_labels."))
                    .put("label_ids", stringArrayProp("Alias for labelIds.")),
            ),
        )
        put(
            tool(
                "unlabel_message",
                "Remove labels from one Gmail message.",
                extra = JSONObject()
                    .put("messageId", stringProp("Message id."))
                    .put("message_id", stringProp("Alias for messageId."))
                    .put("id", stringProp("Alias for messageId."))
                    .put("labelIds", stringArrayProp("Label ids from list_labels."))
                    .put("label_ids", stringArrayProp("Alias for labelIds.")),
            ),
        )
        put(
            tool(
                "unlabel_thread",
                "Remove labels from every message in a Gmail thread.",
                extra = JSONObject()
                    .put("threadId", stringProp("Thread id."))
                    .put("thread_id", stringProp("Alias for threadId."))
                    .put("id", stringProp("Alias for threadId."))
                    .put("labelIds", stringArrayProp("Label ids from list_labels."))
                    .put("label_ids", stringArrayProp("Alias for labelIds.")),
            ),
        )
    }

    private fun tool(
        name: String,
        description: String,
        extra: JSONObject = JSONObject(),
        required: List<String> = emptyList(),
    ): JSONObject = JSONObject()
        .put("name", name)
        .put("description", description)
        .put(
            "inputSchema",
            JSONObject()
                .put("type", "object")
                .put("properties", extra)
                .put("required", JSONArray(required))
                .put("additionalProperties", false),
        )

    private fun stringProp(description: String): JSONObject =
        JSONObject().put("type", "string").put("description", description)

    private fun intProp(description: String): JSONObject =
        JSONObject().put("type", "integer").put("description", description)

    private fun boolProp(description: String): JSONObject =
        JSONObject().put("type", "boolean").put("description", description)

    private fun stringArrayProp(description: String): JSONObject =
        JSONObject()
            .put("type", "array")
            .put("items", JSONObject().put("type", "string"))
            .put("description", description)
}
