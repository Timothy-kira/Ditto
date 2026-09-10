package kira.ditto.data

import android.os.Build
import kira.ditto.runtime.alpineHostAbiFolder
import org.json.JSONArray
import org.json.JSONObject

/**
 * In-process MCP for Agent Mode. Kimi ACP has no Pi host_tools channel, so
 * `agent_display` is exposed on the same loopback HTTP gateway as UPA plugins.
 * The tools operate the apps actually installed on this phone.
 */
internal object AgentDisplayMcp {
    const val PluginId = "aether-agent-display"
    const val ToolName = "agent_display"
    const val ServerName = "agent_display"

    const val ToolDescription =
        "Control apps actually installed on this Android phone (WeChat, Settings, Chrome, " +
            "the real launcher, and other launcher icons). list_apps returns this device's " +
            "packages. launch opens that real package on the Agent Mode virtual phone, which " +
            "matches this device's screen. Launch directly when the app label or package is known; " +
            "use list_apps only for an ambiguous target or a failed launch. Coordinates for tap/" +
            "swipe are 0..1000 normalized to the virtual phone. Batch consecutive tap/swipe in " +
            "actions[]. Gestures do not return a screenshot unless observe=true or action is " +
            "screenshot. Do not tap an input just to type; call text/clear_text/undo on the " +
            "focused field. Use dump_tree (region=top/middle/bottom, query, offset/limit) or " +
            "click_node clicks by 1-based #index from dump_tree (ACTION_CLICK). " +
            "dump_tree defaults to interactive JSON with index, bounds, resource-id, flags; " +
            "observe=ax|full uses that tree, observe=text returns visible text. " +
            "Every mutating receipt includes tree_diff versus the previous tree — informational, " +
            "not a reason to replan after every gesture. Simple tasks finish in one stage; " +
            "complex tasks replan only at stage boundaries or on STALLED/MACRO_MISMATCH. " +
            "Use long_press, double_tap, pinch, fling, scroll_until, swipe_left/swipe_right/" +
            "swipe_up/swipe_down, search, wait_idle, wait_for_label " +
            "for real touchscreen flows. screenshot accepts crop_left/top/right/bottom or crop_region. " +
            "Pass observe=som to overlay numbered targets on the screenshot. If cluttered, skip unlabeled ImageView/layout chrome " +
            "or pass simplify=true. If unsure after retries, call request_teaching with a " +
            "简体中文 hint, then poll teaching_status until the user demonstrates on the virtual " +
            "screen. Do not invent emulator-only apps. " +
            "For a playing video or lesson, call listen_start before tapping play or 下一节 if " +
            "spoken content should be captured (same actions[] is fine: listen_start first). " +
            "Do not call listen_start on every tap. Pass duration_sec from remaining time, then " +
            "GUI_TASK_WATCHING. Screenshot or dump_tree of 简介, title, or quiz is a supplement, " +
            "not a substitute for listening. An empty transcript is still listening, not a reason " +
            "to skip audio. Silent or muted video is still a valid watch. If listen_start returns " +
            "ASR_CAPTURE_UNAVAILABLE with visual_only=true, that is not a task failure — dump_tree " +
            "or screenshot to watch the screen (简介 included) and still emit GUI_TASK_WATCHING. " +
            "Poll listen_status later only if listening " +
            "started; listen_stop when done. Never ask for MediaProjection or screen recording. " +
            "If a result has code SHIZUKU_NOT_RUNNING, wait for the user to tap Start in the " +
            "Shizuku app, then retry this tool. Never tell the user to run ADB, grant " +
            "MediaProjection, enable screen recording, or change developer options. If the " +
            "virtual display is not ready, retry this tool instead of asking the user for " +
            "permissions. Extra actions: back, home, recents, notifications, force_stop."

    fun httpUrl(): String = upaMcpHttpUrl(PluginId)

    fun mcpServerConfig(sessionId: String = ""): McpServerConfig = McpServerConfig(
        id = PluginId,
        displayName = ServerName,
        transport = McpTransportConfig.StreamableHttp(
            url = httpUrl(),
            headers = learningSessionHeaders(sessionId),
        ),
        isEnabled = true,
    )

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

    fun listToolsResult(): JSONObject = JSONObject().put(
        "tools",
        JSONArray().put(
            JSONObject()
                .put("name", ToolName)
                .put("description", ToolDescription)
                .put("inputSchema", inputSchema()),
        ),
    )

    fun wrapCallResult(rawOutput: String): JSONObject {
        val parsed = runCatching { JSONObject(rawOutput) }.getOrNull()
        val imageData = parsed?.optString("screenshot_base64").orEmpty()
        val imageMimeType = parsed?.optString("screenshot_mime_type")
            .orEmpty()
            .ifBlank { "image/jpeg" }
        val structured = parsed?.let { JSONObject(it.toString()) }
            ?: JSONObject().put("raw", rawOutput)
        structured.remove("screenshot_base64")
        structured.remove("screenshot_injected_into_next_model_request")
        AgentModePortal.stripBulkyFieldsForModel(structured)
        val visible = AetherToolExecutor.sanitizeToolOutputForConversation(ToolName, structured.toString())
        val isError = !AetherToolExecutor.inferToolOutputOk(visible)
        val content = JSONArray().put(
            JSONObject()
                .put("type", "text")
                .put("text", visible),
        )
        if (imageData.isNotBlank()) {
            content.put(
                JSONObject()
                    .put("type", "image")
                    .put("data", imageData)
                    .put("mimeType", imageMimeType),
            )
        }
        return JSONObject()
            .put("content", content)
            .put("structuredContent", structured)
            .put("isError", isError)
    }

    fun inputSchema(): JSONObject = JSONObject().apply {
        put("type", "object")
        put(
            "properties",
            JSONObject().apply {
                put(
                    "action",
                    stringProperty(
                        "One of: list_apps, start, status, launch, tap, swipe, swipe_left, swipe_right, swipe_up, swipe_down, " +
                            "key, text, search, clear_text, undo, screenshot, " +
                            "list_targets, dump_tree, click_node, long_press, double_tap, pinch, fling, scroll_until, " +
                            "wait_idle, wait_for_label, request_teaching, teaching_status, " +
                            "listen_start, listen_status, listen_stop, " +
                            "back, home, recents, notifications, force_stop, stop. " +
                            "list_apps / launch use this phone's installed packages. " +
                            "dump_tree returns interactive accessibility JSON (1-based i plus 0-based index) " +
                            "with resource-id, class, hint, selected, checked, enabled, scrollable, password, " +
                            "package_name, parent, and drawing-order. Pass observe=text for visible text only or delta for changed lines only. " +
                            "Pass region=top|middle|bottom and query/offset/limit to page a dense screen. " +
                            "list_targets returns numbered accessibility tap targets using the same #index as dump_tree. " +
                            "observe=ax|text|image|full|som|delta. observe=som overlays those numbers on the screenshot. " +
                            "screenshot can crop with crop_region or crop_left/top/right/bottom. " +
                            "click_node(#n, snapshot_id) performs ACTION_CLICK on that snapshot node; " +
                            "coordinates are only for WebView/games (used_backend=inject_tap). " +
                            "tree_diff is informational; replan at stage boundaries, not after every tap. " +
                            "If the page is cluttered, pass simplify=true to drop unlabeled chrome. " +
                            "request_teaching asks the user to demonstrate on the virtual screen. " +
                            "listen_start captures internal playback when available (not speaker+mic) and returns immediately. " +
                            "Call listen_start before tapping play or 下一节 so the opening is not missed; " +
                            "in actions[] put listen_start first. Do not listen_start on every tap. " +
                            "Screenshot of 简介/title/quiz is a supplement, not a replacement for listening. " +
                            "If capture is unavailable it returns ASR_CAPTURE_UNAVAILABLE with visual_only=true: " +
                            "watch the screen with dump_tree/screenshot (简介 included) and still GUI_TASK_WATCHING. " +
                            "Pass duration_sec from remaining video time. listen_status returns elapsed_sec and transcript. " +
                            "Gestures do not return a screenshot unless observe=true or action is screenshot. " +
                            "Use actions[] to send several gestures in one call. " +
                            "search types text into the search field and submits with Enter (IME search); " +
                            "most apps do not need a separate 搜索 button tap. " +
                            "swipe_left/swipe_right/swipe_up/swipe_down (or swipe/fling/scroll_until with " +
                            "direction=left|right|up|down) swipe inside the scrollable region; " +
                            "coordinates are optional for those named directions.",
                    ),
                )
                put(
                    "actions",
                    JSONObject()
                        .put("type", "array")
                        .put(
                            "description",
                            "Optional batch of gestures. Each item uses the same fields as a single call " +
                                "(action, x, y, ...). Only the last step is observed when observe is true.",
                        )
                        .put(
                            "items",
                            JSONObject()
                                .put("type", "object")
                                .put("additionalProperties", true),
                        ),
                )
                put(
                    "observe",
                    JSONObject()
                        .put(
                            "description",
                            "When true, capture a screenshot and compact tree after this call (or after the last " +
                                "batched action). Defaults to false. Pass ax, text, image, full, \"som\" " +
                                "(overlay numbered tap targets), or delta (changed lines only). Boolean true means full.",
                        ),
                )
                put(
                    "simplify",
                    booleanProperty(
                        "For list_targets: when true, drop unlabeled ImageView/layout chrome. " +
                            "Use only if click_targets is too crowded. Defaults to false.",
                    ),
                )
                put("query", stringProperty("For list_apps: optional app label, package, or activity filter. For dump_tree/list_targets/click_node/wait_for_label/scroll_until/search: GUI text, resource-id, or #index."))
                put("snapshot_id", stringProperty("For click_node: dump_tree snapshot_id. Rejected as STALE_SNAPSHOT if the tree generation changed."))
                put("confirm", booleanProperty("For click_node: required true when the control label looks like pay/delete/install/grant."))
                put("region", stringProperty("For dump_tree/list_targets: top, middle, bottom, left, or right. For screenshot: optional crop region."))
                put("offset", integerProperty("For dump_tree: skip this many matching nodes."))
                put("limit", integerProperty("For dump_tree: page size. Defaults to 80, max 800."))
                put("crop_left", integerProperty("For screenshot: left crop edge in 0..1000 coordinates."))
                put("crop_top", integerProperty("For screenshot: top crop edge in 0..1000 coordinates."))
                put("crop_right", integerProperty("For screenshot: right crop edge in 0..1000 coordinates."))
                put("crop_bottom", integerProperty("For screenshot: bottom crop edge in 0..1000 coordinates."))
                put("crop_region", stringProperty("For screenshot: top, middle, bottom, left, or right."))
                // Constant, deliberately: this string is part of the tool declaration, which sits
                // in the system layer ahead of everything the context folder keeps byte-stable. A
                // description that varies by device is also a description that varies between the
                // cached prefix and the next request whenever anything about the environment is
                // re-read. It stated the branch rather than the rule; stating the rule is both
                // stable and more informative to the model.
                //
                // Reading `Build.SUPPORTED_ABIS` here was additionally why this tool's list could
                // not be built off-device at all: on the JVM the field is null and `listToolsResult`
                // threw. The default still differs by ABI - that decision just belongs in the
                // handler, where it is behaviour, not in the schema, where it is prompt text.
                put(
                    "include_system",
                    booleanProperty(
                        "For list_apps: include system/launcher apps. Defaults to true on x86_64 emulators, where Clock, Contacts, Gmail and Settings are system apps, and false on physical devices, where it returns user-installed apps.",
                    ),
                )
                put("max_results", integerProperty("For list_apps: maximum number of apps to return."))
                put(
                    "persist",
                    booleanProperty(
                        "For screenshot: write the JPEG under reports/ and return workspace_path. " +
                            "Defaults to false. Do not persist routine observe frames.",
                    ),
                )
                put(
                    "persist_path",
                    stringProperty(
                        "For screenshot: workspace-relative path under reports/ or .aether/. " +
                            "Implies persist. Example: reports/yunnan/shots/hotel.jpg.",
                    ),
                )
                put("target", stringProperty("For launch: package name or exact app label of an app installed on this phone."))
                listOf("x", "y", "x1", "y1", "x2", "y2").forEach { key ->
                    put(key, integerProperty("0..1000 normalized coordinate for $key. Values above 1000 are treated as pixels."))
                }
                put(
                    "duration_ms",
                    integerProperty("For swipe/fling/pinch: milliseconds. Use 300-500 for swipe; under 200 often fails to scroll."),
                )
                put(
                    "direction",
                    stringProperty(
                        "For swipe/fling/scroll_until: left, right, up, or down. " +
                            "left/right are horizontal (ViewPager, stories, tabs). " +
                            "Named actions swipe_left/swipe_right/swipe_up/swipe_down set this automatically.",
                    ),
                )
                listOf("start_span", "end_span").forEach { key ->
                    put(key, integerProperty("Normalized coordinate or span for $key."))
                }
                put("key", stringProperty("For key: Android key code name or number."))
                put("text", stringProperty("For text: text to paste into the focused field. Do not tap the field first unless it is not focused. Prefer SET_TEXT; never open the IME. For search: the query to type, then Enter. For request_teaching: 简体中文 hint of what the user should demonstrate."))
                put("undo", stringProperty("For undo: restore the focused field to the text from before the last text/clear_text, or send Ctrl+Z."))
                put("duration_sec", integerProperty("For listen_start: remaining video/lesson seconds. listen_start returns immediately."))
                put("language", stringProperty("For listen_start: optional BCP-47 language tag such as zh-CN."))
            },
        )
        put("required", JSONArray().put("action"))
        put("additionalProperties", false)
    }

    private fun stringProperty(description: String): JSONObject = JSONObject()
        .put("type", "string")
        .put("description", description)

    private fun integerProperty(description: String): JSONObject = JSONObject()
        .put("type", "integer")
        .put("description", description)

    private fun booleanProperty(description: String): JSONObject = JSONObject()
        .put("type", "boolean")
        .put("description", description)
}

internal const val AetherLearningSessionHeader = "X-Aether-Session-Id"

internal fun learningSessionHeaders(sessionId: String): List<McpKeyValue> =
    sessionId.trim().takeIf { it.isNotBlank() }
        ?.let { listOf(McpKeyValue(AetherLearningSessionHeader, it)) }
        .orEmpty()
