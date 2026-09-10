package kira.ditto.data

import org.json.JSONArray
import org.json.JSONObject

internal object WebMcpMcp {
    const val PluginId = "aether-webmcp"
    const val ServerName = "webmcp"
    const val Protocol2025 = UpaMcpProtocolVersion
    const val Protocol2026 = "2026-07-28"

    val ToolNames: List<String> = listOf(
        "browser_tasks",
        "browser_events",
        "browser_capabilities",
        "browser_execute",
        "browser_open",
        "tabs_navigate",
        "tabs_list",
        "tabs_manage",
        "page_snapshot",
        "page_inspect",
        "page_read",
        "page_grep",
        "page_image",
        "page_form",
        "page_click",
        "page_fill",
        "page_select",
        "page_scroll",
        "page_keys",
        "page_hover",
        "page_file",
        "page_dialog",
        "page_screenshot",
        "page_recall",
        "tool_recall",
        "browser_fetch_many",
        "browser_find_signup",
        "page_settle",
        "page_wait",
        "page_batch",
        "page_js",
        "passwords",
        "history_search",
        "resources_list",
        "webmcp_list",
        "webmcp_call",
        "search_images",
    )

    fun httpUrl(): String = upaMcpHttpUrl(PluginId)

    fun mcpServerConfig(sessionId: String = ""): McpServerConfig = McpServerConfig(
        id = PluginId,
        displayName = ServerName,
        transport = McpTransportConfig.StreamableHttp(
            url = httpUrl(),
            headers = learningSessionHeaders(sessionId),
        ),
        isEnabled = true,
        // Must stay above WebMcpHost.ToolBudgetMs so the tool gives up before the client does.
        requestTimeoutMillis = 45_000L,
    )

    fun toAcpServer(sessionId: String = ""): JSONObject? =
        mcpServerConfig(sessionId).toAcpMcpServer()

    fun initializeResult(protocol: String = Protocol2025): JSONObject = JSONObject()
        .put("protocolVersion", protocol)
        .put("capabilities", JSONObject().put("tools", JSONObject()))
        .put(
            "serverInfo",
            JSONObject()
                .put("name", ServerName)
                .put("version", "1"),
        )

    fun discoverResult(): JSONObject = JSONObject()
        .put("protocolVersion", Protocol2026)
        .put(
            "serverInfo",
            JSONObject().put("name", ServerName).put("version", "1"),
        )
        .put("tools", toolsArray())

    /**
     * The catalog, in [ToolNames] order.
     *
     * `toolsArray()` builds the declarations in whatever order they were written, so every tool
     * added since has drifted from the name list the rest of the app matches against. Ordering the
     * result by [ToolNames] makes the two impossible to disagree.
     */
    fun listToolsResult(): JSONObject {
        val declared = toolsArray()
        val byName = LinkedHashMap<String, JSONObject>()
        for (index in 0 until declared.length()) {
            val tool = declared.optJSONObject(index) ?: continue
            byName[tool.optString("name")] = tool
        }
        val ordered = JSONArray()
        ToolNames.forEach { name -> byName.remove(name)?.let(ordered::put) }
        byName.values.forEach(ordered::put)
        return JSONObject().put("tools", ordered)
    }

    fun matchesToolName(name: String): Boolean {
        val n = name.trim().lowercase().replace('-', '_')
        if (n == "network_recent" || n.endsWith("_network_recent") || n.endsWith("__network_recent")) {
            return true
        }
        return ToolNames.any { tool ->
            n == tool || n.endsWith("_$tool") || n.endsWith("__$tool")
        } || n.contains("webmcp")
    }

    fun wrapCallResult(rawOutput: String): JSONObject {
        val parsed = runCatching { JSONObject(rawOutput) }.getOrNull()
        val imageData = parsed?.optString("image_base64").orEmpty()
            .ifBlank { parsed?.optString("screenshot_base64").orEmpty() }
        val imageMime = parsed?.optString("image_mime").orEmpty().ifBlank { "image/jpeg" }
        val structured = WebMcpCompact.forModel(
            parsed ?: JSONObject().put("raw", rawOutput.cap(WebMcpCompact.MaxJsonFallback)),
        )
        structured.remove("image_base64")
        structured.remove("screenshot_base64")
        val code = structured.optString("code")
        val isError = structured.optBoolean("ok", true) == false &&
            code != "input_required"
        val visible = WebMcpCompact.visibleText(structured)
        val content = JSONArray().put(
            JSONObject().put("type", "text").put("text", visible),
        )
        if (imageData.isNotBlank()) {
            content.put(
                JSONObject()
                    .put("type", "image")
                    .put("data", imageData)
                    .put("mimeType", imageMime),
            )
        }
        val result = JSONObject()
            .put("content", content)
            .put("structuredContent", structured)
            .put("isError", isError)
        if (code == "input_required") {
            result.put(
                "_meta",
                JSONObject().put("input_required", true),
            )
        }
        return result
    }

    fun resolveMethod(bodyMethod: String, headers: Map<String, String>): String {
        val headerMethod = headers["mcp-method"]?.trim().orEmpty()
        if (headerMethod.isNotBlank()) return headerMethod
        return bodyMethod
    }

    fun resolveProtocol(headers: Map<String, String>, body: JSONObject): String {
        val header = headers["mcp-protocol-version"]?.trim().orEmpty()
        if (header == Protocol2026 || header == Protocol2025) return header
        val meta = body.optJSONObject("_meta")
        val metaProtocol = meta?.optString("protocolVersion").orEmpty()
        if (metaProtocol == Protocol2026) return Protocol2026
        return Protocol2025
    }

    private fun toolsArray(): JSONArray = JSONArray().apply {
        put(tool("browser_execute", "Execute a discovered form capability with exact field refs and verify all values and constraints in one call. Currently supports fill_and_validate; does not submit or claim business success.", extra = JSONObject()
            .put("capability_id", stringProp("Capability id returned by browser_capabilities"))
            .put("operation", stringProp("fill_and_validate"))
            .put("fields", JSONObject().put("type", "array").put("items", JSONObject().put("type", "object")
                .put("properties", JSONObject().put("ref", stringProp("Exact field ref")).put("value", stringProp("Desired value"))) )),
            required = listOf("capability_id", "operation", "fields")))
        put(tool("browser_capabilities", "Discover observed page capabilities, form inputs, constraints and website tools. Structural verification is not business completion. Prefer before planning individual clicks.", extra = JSONObject().put("tab_id", stringProp("Target tab id"))))
        put(tool("browser_tasks", "List this session's browser execution receipts from the desk (running, waiting_user, needs_verification, failed, completed). No navigation.", extra = JSONObject().put("limit", JSONObject().put("type", "integer"))))
        put(tool("browser_events", "Read up to 100 desk execution events after a sequence number in this session. No navigation.", extra = JSONObject().put("after", JSONObject().put("type", "integer"))))
        put(
            tool(
                "browser_open",
                "Open a URL and return the page in one call: navigates, brings the tab to the " +
                    "foreground, waits for the load and for a JavaScript-rendered page to mount, " +
                    "then returns the element tree and the readable text. Prefer this over " +
                    "tabs_navigate + page_snapshot. If the page renders with JavaScript and " +
                    "exposes no elements, the text is still returned with csr_shell=true.",
                urlArg = true,
                extra = JSONObject()
                    .put("region", JSONObject().put("type", "string").put("description", "viewport | page, default page"))
                    .put("limit", JSONObject().put("type", "integer").put("description", "Max nodes, default 60"))
                    .put("topic_id", stringProp("Topic tab to reuse")),
            ),
        )
        put(
            tool(
                "browser_fetch_many",
                "Fetch several URLs at once and file them all in the page graph. One call costs " +
                    "about as long as the slowest page, not the sum. Use it to read a batch of " +
                    "search results before deciding which one matters; then page_recall any of them.",
                extra = JSONObject()
                    .put(
                        "urls",
                        JSONObject()
                            .put("type", "array")
                            .put("items", JSONObject().put("type", "string"))
                            .put("description", "Up to 12 http(s) URLs"),
                    )
                    .put("query", stringProp("Rank each page's snippet against this")),
                required = listOf("urls"),
            ),
        )
        put(
            tool(
                "browser_find_signup",
                "Decide which candidate URL is really a registration page. Fetches every candidate " +
                    "in parallel and checks whether the page carries an actual form with fillable " +
                    "fields; when none does, it follows one hop through the 报名 / 阅读原文 links the " +
                    "candidates contain. Call this before reporting any official signup URL — a " +
                    "reprint article that merely mentions 报名 will be rejected here.",
                extra = JSONObject()
                    .put(
                        "urls",
                        JSONObject()
                            .put("type", "array")
                            .put("items", JSONObject().put("type", "string"))
                            .put("description", "Candidate URLs to verify"),
                    )
                    .put("from_graph", JSONObject().put("type", "boolean").put("description", "Also test pages already in page_graph"))
                    .put("follow_links", JSONObject().put("type", "boolean").put("description", "Follow one hop through signup links, default true")),
            ),
        )
        put(
            tool(
                "page_recall",
                "Read a page this conversation has already been through, from the page graph. " +
                    "No navigation and no network. Every browser tool result carries a page_graph " +
                    "index; pass the n from it, or the url. Add query to get only the relevant " +
                    "passages. Use this instead of opening a page you have already read.",
                extra = JSONObject()
                    .put("url", stringProp("Page url, as shown in page_graph"))
                    .put("n", JSONObject().put("type", "integer").put("description", "Index shown in page_graph"))
                    .put("query", stringProp("Rank passages against this; omit for the whole page"))
                    .put("limit", JSONObject().put("type", "integer").put("description", "Max characters, default 6000")),
            ),
        )
        put(
            tool(
                "tool_recall",
                "Read the part of an earlier tool result that was left out of the context. When a " +
                    "result ends with [spill:<sha> N chars omitted], the full text is on disk, not " +
                    "gone: pass that sha with an offset to continue reading from where the visible " +
                    "part stopped. Same idea as page_recall, for tool output rather than pages.",
                extra = JSONObject()
                    .put("sha", stringProp("The sha shown in the [spill:...] marker"))
                    .put("offset", JSONObject().put("type", "integer").put("description", "Start character; the marker states where the visible part ended"))
                    .put("limit", JSONObject().put("type", "integer").put("description", "Max characters, default 4000")),
                required = listOf("sha"),
            ),
        )
        put(
            tool(
                "page_settle",
                "Wait for a client-rendered page to finish mounting: returns when the DOM has " +
                    "stopped changing. Use after a navigation when a snapshot came back empty.",
                extra = JSONObject()
                    .put("timeout_ms", JSONObject().put("type", "integer").put("description", "Max wait, default 6000"))
                    .put("quiet_ms", JSONObject().put("type", "integer").put("description", "Required quiet period, default 350")),
            ),
        )
        put(
            tool(
                "tabs_navigate",
                "Open/replace the current Gecko tab. url may be a URL or an address-bar search query. Pass images=true to open the user's engine image search. Returns a compact viewport tree (@e refs). Do not screenshot. Never skip Gecko with HTTP fetch or Kimi web_search.",
                extra = JSONObject()
                    .put("url", stringProp("URL or search query"))
                    .put("query", stringProp("Alias for url"))
                    .put(
                        "images",
                        JSONObject().put("type", "boolean").put("description", "Open the configured engine's image search"),
                    ),
            ),
        )
        put(tool("tabs_list", "List open tabs: id, url, title, selected."))
        put(
            tool(
                "tabs_manage",
                "Tab chrome: action=open|select|close|back|forward|reload. open needs url. select/close need id from tabs_list.",
                extra = JSONObject()
                    .put("action", stringProp("open, select, close, back, forward, reload"))
                    .put("id", stringProp("Tab id from tabs_list"))
                    .put("url", stringProp("URL for action=open")),
                required = listOf("action"),
            ),
        )
        put(
            tool(
                "page_snapshot",
                "Compact HTML-AAM tree with roles, accessible names, and stable @e refs. Default region=viewport limit=40. Filter with query. Page dense UIs with region=top|middle|bottom|page and offset/limit. Prefer this over page_js and screenshots.",
                extra = JSONObject()
                    .put("query", stringProp("Case-insensitive filter on name/role/value/href"))
                    .put("region", stringProp("viewport (default), page, top, middle, bottom"))
                    .put("offset", JSONObject().put("type", "integer").put("description", "Skip this many matching nodes"))
                    .put("limit", JSONObject().put("type", "integer").put("description", "Max nodes, default 40, max 80"))
                    .put("outline", JSONObject().put("type", "boolean").put("description", "Only headings (with px) and images")),
            ),
        )
        put(
            tool(
                "page_inspect",
                "Details for 1–8 @e refs: value, options, nearby text, disabled/checked. Use after snapshot when a line is not enough.",
                extra = JSONObject()
                    .put("ref", stringProp("One ref such as @e3"))
                    .put("refs", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))),
            ),
        )
        put(
            tool(
                "page_read",
                "Readable article/main text (nav/chrome stripped). Pass ref to read one node. offset/limit are character ranges, default 4000.",
                extra = JSONObject()
                    .put("ref", stringProp("Optional @e ref; omit to read main/article"))
                    .put("offset", JSONObject().put("type", "integer"))
                    .put("limit", JSONObject().put("type", "integer")),
            ),
        )
        put(
            tool(
                "page_grep",
                "Search visible page text. Returns snippets with nearest heading (level/px) and an @e ref. Use this before dumping page_read.",
                extra = JSONObject()
                    .put("query", stringProp("Literal text to find"))
                    .put("limit", JSONObject().put("type", "integer").put("description", "Max hits, default 8"))
                    .put("context", JSONObject().put("type", "integer")),
                required = listOf("query"),
            ),
        )
        put(
            tool(
                "page_image",
                "Describe one image @e ref (alt, size, nearby heading). Returns a small JPEG when CORS allows so you can see the picture.",
                extra = JSONObject().put("ref", stringProp("Image @e ref from snapshot")),
            ),
        )
        put(
            tool(
                "page_form",
                "List, fill, or submit the page form in one round-trip. action=list|fill|submit. fill uses fields=[{ref|name|label|kind, value}]; kind=username|password. select/checkbox/radio supported. Passwords in list are masked. fill with submit=true also submits.",
                extra = JSONObject()
                    .put("action", stringProp("list (default), fill, or submit"))
                    .put("fields", JSONObject().put("type", "array").put("description", "For fill: {ref|name|label|kind, value}"))
                    .put("submit", JSONObject().put("type", "boolean").put("description", "fill: also submit the form"))
                    .put("ref", stringProp("Optional submit button @e ref")),
            ),
        )
        put(
            tool(
                "page_click",
                "Click @e ref from the latest snapshot. Do not guess coordinates.",
                extra = JSONObject().put("ref", stringProp("Snapshot ref such as @e3")),
                required = listOf("ref"),
            ),
        )
        put(
            tool(
                "page_fill",
                "Type into a textbox/contenteditable @e ref. For checkbox/radio pass true/false.",
                extra = JSONObject()
                    .put("ref", stringProp("Snapshot ref such as @e3"))
                    .put("text", stringProp("Value to type")),
                required = listOf("ref", "text"),
            ),
        )
        put(
            tool(
                "page_select",
                "Choose a <select> option by visible label or value.",
                extra = JSONObject()
                    .put("ref", stringProp("Combobox @e ref"))
                    .put("text", stringProp("Option label or value")),
                required = listOf("ref", "text"),
            ),
        )
        put(
            tool(
                "page_scroll",
                "Scroll the page or an @e ref into view. direction=up|down|left|right, or pass delta pixels.",
                extra = JSONObject()
                    .put("ref", stringProp("Optional node to scroll into view"))
                    .put("direction", stringProp("down (default), up, left, right"))
                    .put("delta", JSONObject().put("type", "integer")),
            ),
        )
        put(
            tool(
                "page_keys",
                "Press a key on the focused node or @e ref. Examples: Enter, Tab, Escape, Backspace, ArrowDown, Control+a.",
                extra = JSONObject()
                    .put("key", stringProp("Key or combo"))
                    .put("ref", stringProp("Optional @e ref to focus first")),
                required = listOf("key"),
            ),
        )
        put(
            tool(
                "page_hover",
                "Hover an @e ref (mouseover/mouseenter). Use before opening CSS menus; do not guess coordinates.",
                extra = JSONObject().put("ref", stringProp("Snapshot ref such as @e3")),
                required = listOf("ref"),
            ),
        )
        put(
            tool(
                "page_file",
                "Upload a workspace file into a file input. Pass the file-input @e ref and a path under /workspace. Gecko cannot set <input type=file> from JS.",
                extra = JSONObject()
                    .put("ref", stringProp("File input or its label @e ref"))
                    .put("path", stringProp("Absolute /workspace/... path or host file path")),
                required = listOf("path"),
            ),
        )
        put(
            tool(
                "page_dialog",
                "List or close HTML <dialog>, intercepted alert/confirm/prompt, and native Gecko prompts. action=list|accept|dismiss.",
                extra = JSONObject()
                    .put("action", stringProp("list (default), accept, dismiss"))
                    .put("ref", stringProp("Optional HTML dialog @e ref"))
                    .put("value", stringProp("Text for a prompt() accept")),
            ),
        )
        put(
            tool(
                "page_screenshot",
                "Last-resort full-page JPEG of the visible browser. Prefer page_snapshot. Pass annotate=true to draw @e boxes. Requires the built-in browser window.",
                extra = JSONObject().put(
                    "annotate",
                    JSONObject().put("type", "boolean").put("description", "Draw @e boxes on the JPEG"),
                ),
            ),
        )
        put(
            tool(
                "page_wait",
                "Wait up to timeout_ms for load, visible text, an @e ref, or a download URL (pdf/zip/apk…). Timeout returns ok=false code=timeout. After two timeouts the host returns wait_exhausted — do not call page_wait again; snapshot or stop. Prefer this over sleeping in page_js.",
                extra = JSONObject()
                    .put("load", JSONObject().put("type", "boolean").put("description", "Wait for document complete"))
                    .put("text", stringProp("Substring to appear in the page"))
                    .put("ref", stringProp("Wait until this ref is in the viewport"))
                    .put("download", JSONObject().put("type", "boolean").put("description", "Wait for a download-like network URL"))
                    .put("query", stringProp("Optional URL substring for download=true"))
                    .put("timeout_ms", JSONObject().put("type", "integer").put("description", "Default 8000, max 20000")),
            ),
        )
        put(
            tool(
                "page_batch",
                "Run up to 12 page ops in one extension round-trip (click/fill/select/wait/snapshot…). High-frequency flows should use this instead of many separate calls.",
                extra = JSONObject().put(
                    "steps",
                    JSONObject().put("type", "array").put("description", "Each item has op plus that op's fields"),
                ),
                required = listOf("steps"),
            ),
        )
        put(
            tool(
                "page_js",
                "Last resort: short JS expression. Return value is capped. Prefer snapshot/inspect/read/webmcp_call.",
                extra = JSONObject().put("expression", stringProp("JavaScript expression")),
                required = listOf("expression"),
            ),
        )
        put(
            tool(
                "passwords",
                "Local encrypted password vault the model can call. action=list|get|save|delete|fill|generate. list never returns secrets. fill applies username+password on the current form without echoing the secret. generate with fill=true/save=true keeps the new password out of the transcript. Prefer fill over get. Never invent credentials.",
                extra = JSONObject()
                    .put("action", stringProp("list, get, save, delete, fill, generate"))
                    .put("origin", stringProp("Site origin or URL"))
                    .put("id", stringProp("Vault id from list"))
                    .put("username", stringProp("Username"))
                    .put("password", stringProp("Password for save only"))
                    .put("length", JSONObject().put("type", "integer").put("description", "generate: 10–64, default 18"))
                    .put("fill", JSONObject().put("type", "boolean").put("description", "generate: type into the current form without echoing"))
                    .put("save", JSONObject().put("type", "boolean").put("description", "generate: store in the vault"))
                    .put("submit", JSONObject().put("type", "boolean").put("description", "fill/generate: submit after filling")),
                required = listOf("action"),
            ),
        )
        put(
            tool(
                "history_search",
                "Search this browser's visit history (no Gecko start). Empty query lists recent URLs.",
                extra = JSONObject()
                    .put("query", stringProp("Substring of title or URL"))
                    .put("limit", JSONObject().put("type", "integer")),
            ),
        )
        put(
            tool(
                "resources_list",
                "Sniffed page resources (image, media, script, xhr, document). Filter with type and query. Alias: network_recent.",
                extra = JSONObject()
                    .put("type", stringProp("image, media, script, xhr, document, stylesheet, font"))
                    .put("query", stringProp("URL substring")),
            ),
        )
        put(tool("webmcp_list", "Tools the page registered on document.modelContext. Prefer these over clicking."))
        put(
            tool(
                "webmcp_call",
                "Call a page-registered WebMCP tool by name.",
                extra = JSONObject()
                    .put("name", stringProp("Site tool name"))
                    .put("arguments", JSONObject().put("type", "object").put("description", "Tool arguments")),
                required = listOf("name"),
            ),
        )
        put(
            tool(
                "search_images",
                "Find pictures in the built-in Gecko browser. When your answer names several " +
                    "things that each need their own picture, pass subjects=[...] with one entry " +
                    "per thing: each subject is searched separately and the result comes back " +
                    "keyed by subject, so a picture can only be used under the subject it was " +
                    "found for. subjects_without_image tells you which ones found nothing — " +
                    "leave those without a picture rather than reusing another subject's. " +
                    "Pass query alone only when you want pictures of one single thing.",
                extra = JSONObject()
                    .put("query", stringProp("Photograph subject, or shared context when subjects is used"))
                    .put(
                        "subjects",
                        JSONObject()
                            .put("type", "array")
                            .put("items", JSONObject().put("type", "string"))
                            .put("description", "One entry per thing that needs its own picture, max 8"),
                    ),
            ),
        )
    }

    private fun stringProp(description: String): JSONObject =
        JSONObject().put("type", "string").put("description", description)

    private fun tool(
        name: String,
        description: String,
        urlArg: Boolean = false,
        extra: JSONObject? = null,
        required: List<String> = emptyList(),
    ): JSONObject {
        val properties = extra ?: JSONObject()
        if (urlArg) {
            properties.put(
                "url",
                JSONObject().put("type", "string").put("description", "http(s) URL or search query"),
            )
        }
        val schema = JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("additionalProperties", true)
        if (required.isNotEmpty() || urlArg) {
            val req = JSONArray()
            if (urlArg) req.put("url")
            required.forEach(req::put)
            schema.put("required", req)
        }
        return JSONObject()
            .put("name", name)
            .put("description", description)
            .put("inputSchema", schema)
    }

    private fun String.cap(max: Int): String =
        if (length <= max) this else take(max - 1) + "…"
}
