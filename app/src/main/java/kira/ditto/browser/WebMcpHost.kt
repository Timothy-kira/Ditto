package kira.ditto.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.util.Base64
import android.util.Log
import kira.ditto.AetherApplication
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kira.ditto.data.BrowserPreferences
import kira.ditto.data.WebMcpCompact
import kira.ditto.runtime.DittoWebSearchMapper
import kira.ditto.runtime.dittoSearchBudgetHit
import kira.ditto.runtime.dittoSearchLoopHit
import kira.ditto.runtime.dittoSearchSimilarHit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mozilla.components.concept.engine.prompt.PromptRequest
import org.json.JSONArray
import org.json.JSONObject

object WebMcpHost {
    private const val SnapshotSettleMs = 700L

    /**
     * Wall-clock a single tool call may spend. It must stay under the MCP client's request
     * timeout, or the client gives up while the tool keeps running and holding the tab lock —
     * which is how one screenshot came to take 219 seconds and poison every call behind it.
     */
    private const val ToolBudgetMs = 32_000L

    /** Longest a tool call will sit open waiting for the user to clear a login or captcha. */
    private const val MaxTakeoverWaitMs = 24_000L
    private val recoveryStrikes = ConcurrentHashMap<String, Int>()

    /**
     * Tabs with a tool call in flight, per tab id.
     *
     * Selecting a tab moves the one visible engine surface. With two agents alternating calls the
     * selection flipped on every request, so the surface re-rendered constantly and each agent's
     * url-less tools resolved onto the other's page. Selection is now claimed only when no other
     * tab is mid-call; activation (which is what actually keeps a page rendering) is unconditional.
     */
    private val inflightTabs = ConcurrentHashMap<String, Int>()

    private fun enterTab(tabId: String): Boolean {
        if (tabId.isBlank()) return false
        val othersBusy = inflightTabs.keys.any { it != tabId }
        inflightTabs.merge(tabId, 1) { old, add -> old + add }
        return !othersBusy
    }

    private fun leaveTab(tabId: String) {
        if (tabId.isBlank()) return
        inflightTabs.computeIfPresent(tabId) { _, count -> if (count <= 1) null else count - 1 }
    }

    /** Last turn each MCP session was seen acting in. */
    private val lastTurnBySession = ConcurrentHashMap<String, Int>()

    /**
     * First tool call of a new turn: hand this session a clean slate.
     *
     * Recovery strikes, tab leases and open tab groups all lived for the life of the process, so a
     * URL that failed in the previous task started the next one already in the penalty box, a lease
     * left behind by an interrupted turn blocked the new one outright, and the old task's pages
     * kept running scripts next to it. The page graph is deliberately not reset — reusing what has
     * already been read is the point of it.
     */
    private fun beginTurnIfNeeded(sessionId: String) {
        val turn = BrowserDesk.turnId()
        val key = sessionId.ifBlank { "_default" }
        val previous = lastTurnBySession.put(key, turn)
        if (previous == null || previous == turn) return
        recoveryStrikes.clear()
        inflightTabs.clear()
        BrowserTabLease.clear()
        runCatching { AetherBrowserRuntime.retireTurnTabs(sessionId, BrowserDesk.heldLiveUrl()) }
    }
    private val deadlineAt = ThreadLocal<Long>()
    private val currentTabId = ThreadLocal<String>()
    private val currentSessionId = ThreadLocal<String>()

    fun execute(
        context: Context,
        prefs: BrowserPreferences,
        vault: BrowserLoginVault,
        history: BrowserHistoryStore,
        name: String,
        arguments: JSONObject,
        sessionId: String = "",
    ): String {
        val requestedTool = name.trim().lowercase().substringAfterLast("__").replace('-', '_')
        if (requestedTool == "browser_tasks") {
            return ok(JSONObject().put("tasks", BrowserDesk.listTasks(arguments.optInt("limit", 30))))
        }
        if (requestedTool == "browser_events") {
            return ok(JSONObject().put("events", BrowserDesk.taskEvents(arguments.optLong("after", 0))))
        }
        AetherBrowserRuntime.updatePreferences(prefs, agentSession = true)
        AetherBrowserRuntime.loginVault = vault
        AetherBrowserRuntime.historyStore = history
        AetherBrowserRuntime.warmAsync(context)
        val tool = name.trim().lowercase().substringAfterLast("__").replace('-', '_')
        if (sessionId.isNotBlank()) BrowserTopicGraph.bindSession(sessionId)
        beginTurnIfNeeded(sessionId)
        val activityId = BrowserDesk.begin(tool, arguments)
        val forImages = tool == "search_images" || arguments.optBoolean("images")
        val needsWorkspace = tool !in setOf(
            "page_recall",
            "browser_fetch_many",
            "browser_find_signup",
            "tabs_list",
            "network_recent",
            "resources_list",
            "history_search",
            "passwords",
        )
        val target = resolveBrowserTopicTarget(
            arguments,
            forImages = forImages,
            sessionId = sessionId,
            tool = tool,
        )
        val tabId = if (needsWorkspace) {
            AetherBrowserRuntime.ensureTopicTab(context, target, sessionId)
        } else {
            target.tabId
        }
        val ownsSurface = enterTab(tabId)
        if (tabId.isNotBlank()) {
            if (ownsSurface) AetherBrowserRuntime.selectTab(tabId)
            AetherBrowserRuntime.activateTab(context, tabId)
        }
        if (needsWorkspace) {
            val holdUrl = arguments.optString("url").ifBlank { arguments.optString("query") }
            BrowserDesk.holdLivePage(if (looksLikeHttpUrl(holdUrl)) holdUrl else "")
            if (tool != "tabs_manage") awaitPageReady(context, tabId)
        }
        if (tool == "page_read" || tool == "page_grep") {
            val pageUrl = arguments.optString("url").ifBlank {
                val components = AetherBrowserRuntime.getBlocking(context)
                if (tabId.isNotBlank()) components.tabUrl(tabId) else ""
            }.ifBlank {
                AetherBrowserRuntime.getBlocking(context).selectedUrl()
            }
            BrowserDesk.noteReadingTarget(pageUrl)
        }
        val leaseOwner = target.topicId.ifBlank { activityId }
        currentTabId.set(tabId)
        currentSessionId.set(sessionId)
        deadlineAt.set(System.currentTimeMillis() + ToolBudgetMs)
        val result = try {
            if (BrowserTabLease.isActTool(tool)) {
                if (tabId.isNotBlank() && !BrowserTabLease.tryAcquire(tabId, leaseOwner)) {
                    JSONObject()
                        .put("ok", false)
                        .put("code", "preempted")
                        .put("errmsg", "Another agent is acting on this tab.")
                        .toString()
                } else {
                    try {
                        dispatch(
                            context = context,
                            prefs = prefs,
                            vault = vault,
                            history = history,
                            tool = tool,
                            name = name,
                            arguments = arguments,
                        )
                    } finally {
                        BrowserTabLease.release(leaseOwner)
                    }
                }
            } else {
                dispatch(
                    context = context,
                    prefs = prefs,
                    vault = vault,
                    history = history,
                    tool = tool,
                    name = name,
                    arguments = arguments,
                )
            }
        } catch (error: Throwable) {
            BrowserTabLease.release(leaseOwner)
            BrowserDesk.fail(activityId)
            leaveTab(tabId)
            currentSessionId.remove()
            BrowserTopicGraph.unbindSession()
            throw error
        } finally {
            currentTabId.remove()
            deadlineAt.remove()
        }
        try {
            BrowserDesk.complete(activityId, tool, result)
            return attachPageGraph(BrowserDesk.annotateToolResult(tool, result), sessionId, tool)
        } finally {
            currentSessionId.remove()
            leaveTab(tabId)
            BrowserTopicGraph.unbindSession()
        }
    }

    /**
     * Hand the model a table of contents for what this conversation has already read. Titles and
     * headings only — the body stays in the graph until page_recall asks for it.
     */
    private fun attachPageGraph(raw: String, sessionId: String, tool: String): String {
        if (tool == "page_recall" || tool == "tabs_list") return raw
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return raw
        val pages = BrowserPageLedger.index(sessionId)
        if (pages.length() == 0) return raw
        json.put(
            "page_graph",
            JSONObject()
                .put(
                    "note",
                    "Pages this conversation has already read. To read one again call " +
                        "page_recall with its n or url — do not browser_open it a second time.",
                )
                .put("pages", pages),
        )
        return json.toString()
    }

    /** The page an action would run against, when a tab is bound to this call. */
    private fun currentPageUrl(context: Context): String {
        val tabId = currentTabId.get().orEmpty()
        if (tabId.isBlank()) return ""
        return runCatching { AetherBrowserRuntime.getBlocking(context).tabUrl(tabId) }
            .getOrDefault("")
    }

    private fun dispatch(
        context: Context,
        prefs: BrowserPreferences,
        vault: BrowserLoginVault,
        history: BrowserHistoryStore,
        tool: String,
        name: String,
        arguments: JSONObject,
    ): String {
        BrowserDesk.lookupOnlyBlockedMessage(tool)?.let { return errorJson(it, "lookup_only") }
        // The never-automatic tier is enforced here rather than asked for in the profile. A profile
        // rule holds until the model reads a page that argues against it; this holds regardless,
        // which is the point of having a host at all.
        val verdict = classifyBrowserAction(tool, arguments, currentPageUrl(context))
        if (verdict.level == BrowserAuthorization.Never) {
            return browserAuthorizationRefusal(verdict, tool).toString()
        }
        return when (tool) {
            "browser_capabilities" -> page(context, "capabilities")
            "browser_execute" -> page(context, "execute_capability", arguments)
            "tabs_list" -> listTabs(context, warm = AetherBrowserRuntime.isWarm())
            "network_recent", "resources_list" -> resources(arguments)
            "history_search" -> historySearch(history, arguments)
            "passwords" -> passwords(context, vault, arguments)
            "webmcp_list" -> if (!AetherBrowserRuntime.isWarm()) {
                ok(JSONObject().put("tools", JSONArray()))
            } else {
                page(context, "webmcp_list")
            }
            "browser_open" -> browserOpen(context, prefs, vault, arguments)
            "page_recall" -> pageRecall(arguments)
            "tool_recall" -> toolRecall(arguments)
            "browser_fetch_many" -> fetchMany(context, arguments)
            "browser_find_signup" -> findSignup(context, arguments)
            "tabs_navigate" -> navigate(context, prefs, vault, arguments)
            "tabs_manage" -> manageTabs(context, prefs, arguments)
            "page_settle" -> page(
                context,
                "settle",
                JSONObject()
                    .put("timeout_ms", arguments.optInt("timeout_ms", 6_000))
                    .put("quiet_ms", arguments.optInt("quiet_ms", 350)),
                timeoutMs = 12_000,
            )
            "page_snapshot" -> snapshot(context, vault, arguments)
            "page_inspect" -> page(
                context,
                "inspect",
                JSONObject()
                    .put("ref", arguments.optString("ref"))
                    .put("refs", arguments.opt("refs")),
            )
            "page_read" -> page(
                context,
                "read",
                JSONObject()
                    .put("ref", arguments.optString("ref"))
                    .put("offset", arguments.optInt("offset", 0))
                    .put("limit", arguments.optInt("limit", 0)),
            )
            "page_grep" -> page(
                context,
                "grep",
                JSONObject()
                    .put("query", arguments.optString("query"))
                    .put("limit", arguments.optInt("limit", 0))
                    .put("context", arguments.optInt("context", 0)),
            )
            "page_image" -> page(context, "image", JSONObject().put("ref", arguments.optString("ref")))
            "page_form" -> form(context, arguments)
            "page_click" -> page(context, "click", JSONObject().put("ref", arguments.optString("ref")))
            "page_hover" -> page(context, "hover", JSONObject().put("ref", arguments.optString("ref")))
            "page_file" -> uploadFile(context, arguments)
            "page_dialog" -> dialog(context, arguments)
            "page_screenshot" -> screenshot(context, arguments)
            "page_fill" -> page(
                context,
                "fill",
                JSONObject()
                    .put("ref", arguments.optString("ref"))
                    .put("value", arguments.optString("text").ifBlank { arguments.optString("value") }),
            )
            "page_select" -> page(
                context,
                "select",
                JSONObject()
                    .put("ref", arguments.optString("ref"))
                    .put("value", arguments.optString("text").ifBlank { arguments.optString("value") }),
            )
            "page_scroll" -> page(
                context,
                "scroll",
                JSONObject()
                    .put("ref", arguments.optString("ref"))
                    .put("direction", arguments.optString("direction"))
                    .put("delta", arguments.opt("delta"))
                    .put("block", arguments.optString("block")),
            )
            "page_keys" -> page(
                context,
                "keys",
                JSONObject()
                    .put("key", arguments.optString("key").ifBlank { arguments.optString("keys") })
                    .put("ref", arguments.optString("ref")),
            )
            "page_wait" -> {
                if (BrowserDesk.waitTimeouts() >= BrowserWaitTimeoutBudget) {
                    errorJson(
                        "Wait did not succeed. Do not call page_wait again. Take a snapshot or stop.",
                        "wait_exhausted",
                    )
                } else if (arguments.optBoolean("download")) {
                    waitDownload(arguments)
                } else {
                    val waited = page(
                        context,
                        "wait",
                        JSONObject()
                            .put("load", arguments.optBoolean("load"))
                            .put("text", arguments.optString("text"))
                            .put("ref", arguments.optString("ref"))
                            .put("timeout_ms", arguments.optInt("timeout_ms", 8000)),
                        timeoutMs = 16_000,
                    )
                    val parsed = runCatching { JSONObject(waited) }.getOrDefault(JSONObject())
                    if (!parsed.optBoolean("ok", true) && parsed.optString("code") == "timeout") {
                        val count = BrowserDesk.noteWaitTimeout()
                        if (count >= BrowserWaitTimeoutBudget) {
                            errorJson(
                                "Wait timed out. Do not call page_wait again. Take a snapshot or stop.",
                                "wait_exhausted",
                            )
                        } else {
                            errorJson(
                                "Timed out waiting. Do not retry the same wait. Snapshot or stop.",
                                "timeout",
                            )
                        }
                    } else {
                        waited
                    }
                }
            }
            "page_batch" -> page(
                context,
                "batch",
                JSONObject().put("steps", arguments.optJSONArray("steps") ?: JSONArray()),
                timeoutMs = 18_000,
            )
            "page_js" -> {
                val count = BrowserDesk.notePageJs()
                if (count > BrowserPageJsBudget) {
                    errorJson(
                        "page_js budget exhausted ($BrowserPageJsBudget per turn). " +
                            "If a form drawer or iframe did not load, stop with GUI_TASK_NEEDS_TEACHING. " +
                            "Do not call page_js again.",
                        "js_budget",
                    )
                } else {
                    page(
                        context,
                        "js",
                        JSONObject().put("expression", arguments.optString("expression")),
                    )
                }
            }
            "webmcp_call" -> page(
                context,
                "webmcp_call",
                JSONObject()
                    .put("name", arguments.optString("name"))
                    .put("args", arguments.optJSONObject("arguments") ?: arguments.optJSONObject("args") ?: JSONObject()),
            )
            "search_images" -> searchImages(context, prefs, vault, arguments)
            "page_harvest" -> page(
                context,
                "harvest",
                JSONObject()
                    .put("kind", arguments.optString("kind").ifBlank { "links" })
                    .put("limit", arguments.optInt("limit", 0)),
            )
            else -> errorJson("Unknown tool: $name")
        }
    }

    private fun waitDownload(arguments: JSONObject): String {
        val timeout = arguments.optInt("timeout_ms", 8000).coerceIn(200, 20_000)
        val query = arguments.optString("query").trim()
        val started = System.currentTimeMillis()
        val since = started - 250
        while (System.currentTimeMillis() - started < timeout) {
            val hit = AetherBrowserRuntime.recentNetwork().lastOrNull { item ->
                val url = item.optString("url")
                val at = item.optLong("at")
                at >= since &&
                    looksLikeDownload(url, item.optString("type")) &&
                    (query.isBlank() || url.contains(query, ignoreCase = true))
            }
            if (hit != null) {
                return ok(
                    JSONObject()
                        .put("download", true)
                        .put("url", hit.optString("url"))
                        .put("type", hit.optString("type"))
                        .put("waited_ms", System.currentTimeMillis() - started),
                )
            }
            runBlocking { delay(200) }
        }
        val count = BrowserDesk.noteWaitTimeout()
        val message = if (count >= BrowserWaitTimeoutBudget) {
            "Timed out waiting for a download. Do not call page_wait again."
        } else {
            "Timed out waiting for a download."
        }
        return errorJson(message, if (count >= BrowserWaitTimeoutBudget) "wait_exhausted" else "timeout")
    }

    private fun uploadFile(context: Context, arguments: JSONObject): String {
        val path = arguments.optString("path").ifBlank { arguments.optString("file") }
        val file = resolveUploadFile(context, path)
            ?: return errorJson("File not found: $path")
        val uri = Uri.fromFile(file)
        val components = AetherBrowserRuntime.getBlocking(context)
        val existing = components.nativePrompts().filterIsInstance<PromptRequest.File>().lastOrNull()
        if (existing == null) {
            val ref = arguments.optString("ref")
            if (ref.isNotBlank()) {
                page(context, "click", JSONObject().put("ref", ref))
            }
        }
        val prompt = waitForFilePrompt(components, timeoutMs = 4_000)
            ?: return errorJson("No file picker opened. Click a file input @e ref first.")
        if (prompt.isMultipleFilesSelection) {
            prompt.onMultipleFilesSelected(context.applicationContext, arrayOf(uri))
        } else {
            prompt.onSingleFileSelected(context.applicationContext, uri)
        }
        components.consumePrompt(prompt)
        return ok(
            JSONObject()
                .put("path", file.absolutePath)
                .put("name", file.name)
                .put("bytes", file.length()),
        )
    }

    private fun waitForFilePrompt(
        components: AetherBrowserRuntime.Components,
        timeoutMs: Long,
    ): PromptRequest.File? {
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < timeoutMs) {
            val prompt = components.nativePrompts().filterIsInstance<PromptRequest.File>().lastOrNull()
            if (prompt != null) return prompt
            runBlocking { delay(150) }
        }
        return components.nativePrompts().filterIsInstance<PromptRequest.File>().lastOrNull()
    }

    private fun resolveUploadFile(context: Context, raw: String): File? {
        val trimmed = raw.trim().removePrefix("file://").ifBlank { return null }
        val candidates = ArrayList<File>()
        if (trimmed.startsWith("/workspace/") || trimmed == "/workspace") {
            val relative = trimmed.removePrefix("/workspace").trimStart('/')
            val hostRoot = File(context.applicationContext.filesDir, "runtimes/alpine/workspace")
            candidates += if (relative.isBlank()) hostRoot else File(hostRoot, relative)
            candidates += File(hostRoot, "uploads/$relative")
        }
        candidates += File(trimmed)
        if (!trimmed.startsWith("/")) {
            val hostRoot = File(context.applicationContext.filesDir, "runtimes/alpine/workspace")
            candidates += File(hostRoot, trimmed)
            candidates += File(hostRoot, "uploads/$trimmed")
        }
        return candidates.map { it.canonicalFile }.firstOrNull { it.isFile && it.canRead() }
    }

    private fun dialog(context: Context, arguments: JSONObject): String {
        val action = arguments.optString("action").ifBlank { "list" }.lowercase()
        val pageResult = JSONObject(
            page(
                context,
                "dialog",
                JSONObject()
                    .put("action", action)
                    .put("ref", arguments.optString("ref"))
                    .put("value", arguments.optString("value").ifBlank { arguments.optString("text") }),
            ),
        )
        val components = AetherBrowserRuntime.peek()
        val native = components?.nativePrompts().orEmpty()
        if (action == "list" || action.isBlank()) {
            val listed = pageResult.optJSONArray("js_prompts") ?: JSONArray()
            native.forEach { prompt ->
                listed.put(describeNativePrompt(prompt))
            }
            pageResult.put("js_prompts", listed)
            pageResult.put("native_prompts", native.size)
            return if (pageResult.optBoolean("ok", true)) ok(pageResult) else pageResult.toString()
        }
        val handled = handleNativeDialog(components, native, action, arguments.optString("value"))
        if (handled) {
            return ok(JSONObject().put("handled", "native").put("action", action))
        }
        return if (pageResult.optBoolean("ok", true)) ok(pageResult) else pageResult.toString()
    }

    private fun handleNativeDialog(
        components: AetherBrowserRuntime.Components?,
        prompts: List<PromptRequest>,
        action: String,
        value: String,
    ): Boolean {
        val prompt = prompts.lastOrNull() ?: return false
        val accept = action == "accept"
        when (prompt) {
            is PromptRequest.Alert -> if (accept) prompt.onConfirm(false) else prompt.onDismiss()
            is PromptRequest.Confirm -> {
                if (accept) prompt.onConfirmPositiveButton(false) else prompt.onConfirmNegativeButton(false)
            }
            is PromptRequest.TextPrompt -> {
                if (accept) prompt.onConfirm(false, value) else prompt.onDismiss()
            }
            is PromptRequest.Repost -> if (accept) prompt.onConfirm() else prompt.onDismiss()
            is PromptRequest.BeforeUnload -> if (accept) prompt.onLeave() else prompt.onStay()
            else -> return false
        }
        components?.consumePrompt(prompt)
        return true
    }

    private fun describeNativePrompt(prompt: PromptRequest): JSONObject = when (prompt) {
        is PromptRequest.Alert -> JSONObject().put("type", "alert").put("message", prompt.message).put("native", true)
        is PromptRequest.Confirm -> JSONObject().put("type", "confirm").put("message", prompt.message).put("native", true)
        is PromptRequest.TextPrompt -> JSONObject()
            .put("type", "prompt")
            .put("message", prompt.inputLabel.ifBlank { prompt.title })
            .put("default", prompt.inputValue)
            .put("native", true)
        is PromptRequest.File -> JSONObject().put("type", "file").put("native", true)
        else -> JSONObject().put("type", prompt::class.java.simpleName).put("native", true)
    }

    private fun screenshot(context: Context, arguments: JSONObject): String {
        val tabId = currentTabId.get().orEmpty()
        awaitPageReady(context, tabId, requireHttp = true)
        val components = AetherBrowserRuntime.getBlocking(context)
        val annotate = arguments.optBoolean("annotate")
        // Two different things look alike here. A missing or malformed bitmap means the engine
        // surface has not composited and there is nothing to show. A valid but single-colour
        // bitmap is a real picture of a page that drew nothing — usually a JavaScript-rendered
        // page that never mounted. The first is a failure; the second is a photograph of the
        // truth, and turning it into an error just blocks the agent.
        fun missing(candidate: Bitmap?): Boolean =
            candidate == null || !isUsableBrowserScreenshot(candidate.width, candidate.height)
        var bitmap = runBlocking { components.captureVisibleBitmap() }
        if (missing(bitmap) || isBlankBitmap(bitmap!!)) {
            components.activateTab(tabId)
            awaitPageReady(context, tabId, timeoutMs = 8_000L, requireHttp = true)
            runBlocking { delay(SnapshotSettleMs) }
            bitmap = runBlocking { components.captureVisibleBitmap() }
        }
        if (missing(bitmap)) {
            val strikes = strike("shot", tabId)
            val retryable = strikes < 3
            return errorJson(
                "The engine surface has not composited, so there is no image to capture. " +
                    if (retryable) {
                        "Work from the page tree instead."
                    } else {
                        "Stop calling page_screenshot for this tab and report what page_snapshot shows."
                    },
                if (retryable) "screenshot_unavailable" else "screenshot_unavailable_final",
                retryable = retryable,
                nextTool = if (retryable) "page_snapshot" else "",
                nextArgs = if (retryable) JSONObject().put("region", "page").put("limit", 80) else null,
            )
        }
        val blank = isBlankBitmap(bitmap!!)
        clearStrikes(tabId)
        val shot = bitmap ?: return errorJson(
            "Visible screenshot came back blank.",
            "screenshot_unavailable",
        )
        val labeled = if (annotate) {
            val snap = runBlocking {
                components.pageCommand(
                    "snapshot",
                    JSONObject().put("boxes", true).put("limit", 40),
                    tabId = tabId,
                )
            }
            annotateScreenshot(shot, snap.optJSONArray("boxes") ?: JSONArray())
        } else {
            shot
        }
        val scaled = scaleBitmap(labeled, 960)
        val jpeg = jpegBase64(scaled)
        return ok(
            JSONObject()
                .put("screenshot_base64", jpeg)
                .put("image_mime", "image/jpeg")
                .put("width", scaled.width)
                .put("height", scaled.height)
                .put("annotate", annotate)
                .put("fallback", true)
                .apply {
                    if (blank) {
                        put("blank", true)
                        put(
                            "note",
                            "The page drew nothing: this screenshot is a single flat colour. " +
                                "That usually means a JavaScript-rendered page has not mounted, " +
                                "not that the capture failed. Use the text from browser_open or " +
                                "page_read as the page content; another screenshot will look the same.",
                        )
                    }
                },
        )
    }

    /**
     * A thumbnail of an un-composited EngineView has the right dimensions and one single colour.
     * Sample a grid and call it blank when almost nothing differs from the first sample.
     */
    private fun isBlankBitmap(bitmap: Bitmap): Boolean {
        val stepX = (bitmap.width / 24).coerceAtLeast(1)
        val stepY = (bitmap.height / 24).coerceAtLeast(1)
        var first = 0
        var seen = 0
        var distinct = 0
        var x = 0
        while (x < bitmap.width) {
            var y = 0
            while (y < bitmap.height) {
                val pixel = runCatching { bitmap.getPixel(x, y) }.getOrNull() ?: return false
                if (seen == 0) first = pixel else if (pixel != first) distinct++
                seen++
                y += stepY
            }
            x += stepX
        }
        if (seen < 16) return false
        return distinct * 100 < seen
    }

    private fun annotateScreenshot(
        bitmap: Bitmap,
        boxes: JSONArray,
    ): Bitmap {
        if (boxes.length() == 0) return bitmap
        val view = AetherBrowserRuntime.attachedEngineView()?.asView()
        val srcW = (view?.width ?: 0).coerceAtLeast(1).toFloat()
        val srcH = (view?.height ?: 0).coerceAtLeast(1).toFloat()
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: return bitmap
        val canvas = Canvas(copy)
        val sx = copy.width / srcW
        val sy = copy.height / srcH
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(220, 40, 40)
            style = Paint.Style.STROKE
            strokeWidth = (2f * sx).coerceAtLeast(2f)
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(220, 40, 40)
            textSize = (11f * sx).coerceAtLeast(11f)
        }
        for (index in 0 until boxes.length().coerceAtMost(40)) {
            val box = boxes.optJSONObject(index) ?: continue
            val left = box.optDouble("x").toFloat() * sx
            val top = box.optDouble("y").toFloat() * sy
            val right = left + box.optDouble("w").toFloat() * sx
            val bottom = top + box.optDouble("h").toFloat() * sy
            canvas.drawRect(left, top, right, bottom, stroke)
            val label = box.optString("ref")
            if (label.isNotBlank()) {
                canvas.drawText(label, left + 2f, (top - 3f).coerceAtLeast(text.textSize), text)
            }
        }
        return copy
    }

    private fun scaleBitmap(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val edge = maxOf(bitmap.width, bitmap.height)
        if (edge <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / edge
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun jpegBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 62, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    private fun listTabs(context: Context, warm: Boolean): String {
        if (!warm) return ok(JSONObject().put("tabs", JSONArray()))
        val components = AetherBrowserRuntime.getBlocking(context)
        return ok(JSONObject().put("tabs", components.tabList()).put("url", components.selectedUrl()))
    }

    private fun resources(arguments: JSONObject): String {
        val array = JSONArray()
        AetherBrowserRuntime.recentNetwork(
            type = arguments.optString("type"),
            query = arguments.optString("query"),
        ).forEach(array::put)
        return ok(JSONObject().put("resources", array).put("requests", array))
    }

    private fun historySearch(history: BrowserHistoryStore, arguments: JSONObject): String {
        val query = arguments.optString("query")
        val limit = arguments.optInt("limit", 20).let { if (it <= 0) 20 else it }
        val items = (if (query.isBlank()) history.recent(limit) else history.search(query, limit))
            .filterNot { visit -> isNonContentBrowserHost(visit.url) }
        return ok(JSONObject().put("visits", history.toJson(items)).put("query", query))
    }

    private fun passwords(context: Context, vault: BrowserLoginVault, arguments: JSONObject): String {
        val action = arguments.optString("action").ifBlank { "list" }.lowercase()
        return when (action) {
            "list" -> {
                val origin = arguments.optString("origin")
                val items = if (origin.isBlank()) vault.list() else vault.find(origin)
                ok(JSONObject().put("logins", publicLogins(items)).put("count", items.size))
            }
            "get" -> {
                val login = resolveLogin(vault, arguments, fallbackUrl = "")
                    ?: return errorJson("No saved login for that origin.")
                ok(
                    JSONObject()
                        .put("id", login.id)
                        .put("origin", login.origin)
                        .put("username", login.username)
                        .put("password", login.password)
                        .put("include_secret", true),
                )
            }
            "save", "update" -> {
                val origin = arguments.optString("origin")
                val username = arguments.optString("username")
                val password = arguments.optString("password")
                if (origin.isBlank() || password.isBlank()) {
                    return errorJson("save needs origin and password.")
                }
                val saved = vault.save(origin, username, password)
                ok(JSONObject().put("id", saved.id).put("origin", saved.origin).put("username", saved.username))
            }
            "delete" -> {
                val id = arguments.optString("id")
                if (id.isBlank()) return errorJson("delete needs id.")
                ok(JSONObject().put("deleted", vault.delete(id)))
            }
            "fill" -> fillSavedLogin(context, vault, arguments)
            "generate" -> generatePassword(context, vault, arguments)
            else -> errorJson("Unknown passwords action: $action")
        }
    }

    private fun generatePassword(
        context: Context,
        vault: BrowserLoginVault,
        arguments: JSONObject,
    ): String {
        val password = BrowserLoginVault.generatePassword(arguments.optInt("length", 18))
        val username = arguments.optString("username")
        val shouldFill = arguments.optBoolean("fill")
        val shouldSave = arguments.optBoolean("save")
        var filled: JSONObject? = null
        if (shouldFill) {
            filled = fillCredentials(
                context = context,
                username = username,
                password = password,
                submit = arguments.optBoolean("submit"),
            )
        }
        var saved: SavedBrowserLogin? = null
        if (shouldSave) {
            val origin = arguments.optString("origin").ifBlank {
                AetherBrowserRuntime.peek()?.selectedUrl().orEmpty()
            }
            if (origin.isBlank() || origin.startsWith("about:")) {
                return errorJson("generate save needs origin, or fill=true on an open page.")
            }
            saved = vault.save(origin, username, password)
        }
        val body = JSONObject()
            .put("generated", true)
            .put("length", password.length)
            .put("username", username)
        saved?.let { login ->
            body.put("id", login.id).put("origin", login.origin).put("saved", true)
        }
        filled?.let { result ->
            body.put("filled", result.optInt("filled"))
            body.put("results", result.optJSONArray("results") ?: JSONArray())
            body.put("submitted", result.optBoolean("submitted"))
            if (saved == null) body.put("origin", AetherBrowserRuntime.peek()?.selectedUrl().orEmpty())
        }
        if ((!shouldFill && !shouldSave) || arguments.optBoolean("include_secret")) {
            body.put("password", password).put("include_secret", true)
        }
        return ok(body)
    }

    private fun fillSavedLogin(
        context: Context,
        vault: BrowserLoginVault,
        arguments: JSONObject,
    ): String {
        val origin = arguments.optString("origin").ifBlank {
            AetherBrowserRuntime.peek()?.selectedUrl().orEmpty()
        }
        val login = resolveLogin(vault, arguments, fallbackUrl = origin)
            ?: return errorJson("No saved login for $origin. Ask the user to sign in once, or passwords action=save.")
        val filled = fillCredentials(
            context = context,
            username = login.username,
            password = login.password,
            submit = arguments.optBoolean("submit"),
        )
        return ok(
            JSONObject()
                .put("origin", login.origin)
                .put("username", login.username)
                .put("id", login.id)
                .put("filled", filled.optInt("filled"))
                .put("submitted", filled.optBoolean("submitted"))
                .put("results", filled.optJSONArray("results") ?: JSONArray()),
        )
    }

    private fun fillCredentials(
        context: Context,
        username: String,
        password: String,
        submit: Boolean,
    ): JSONObject {
        val fields = JSONArray()
        if (username.isNotBlank()) {
            fields.put(JSONObject().put("kind", "username").put("value", username))
        }
        fields.put(
            JSONObject()
                .put("kind", "password")
                .put("value", password)
                .put("all", true),
        )
        val filled = JSONObject(page(context, "form_fill", JSONObject().put("fields", fields)))
        if (submit) {
            val submitted = JSONObject(page(context, "form_submit"))
            filled.put("submitted", submitted.optBoolean("ok", true))
        }
        return filled
    }

    private fun resolveLogin(
        vault: BrowserLoginVault,
        arguments: JSONObject,
        fallbackUrl: String,
    ): SavedBrowserLogin? {
        val id = arguments.optString("id")
        if (id.isNotBlank()) return vault.findById(id)
        val origin = arguments.optString("origin").ifBlank { fallbackUrl }
        return vault.pick(origin, arguments.optString("username"))
    }

    private fun publicLogins(items: List<SavedBrowserLogin>): JSONArray {
        val array = JSONArray()
        items.forEach { login ->
            array.put(
                JSONObject()
                    .put("id", login.id)
                    .put("origin", login.origin)
                    .put("username", login.username),
            )
        }
        return array
    }

    private fun form(context: Context, arguments: JSONObject): String {
        val action = arguments.optString("action").ifBlank { "list" }.lowercase()
        return when (action) {
            "fill" -> {
                val filled = page(
                    context,
                    "form_fill",
                    JSONObject().put("fields", arguments.optJSONArray("fields") ?: JSONArray()),
                )
                if (!arguments.optBoolean("submit")) return filled
                val parsed = JSONObject(filled)
                val submitted = JSONObject(
                    page(
                        context,
                        "form_submit",
                        JSONObject().put("ref", arguments.optString("ref")),
                    ),
                )
                parsed.put("submitted", submitted.optBoolean("ok", true)).toString()
            }
            "submit" -> page(
                context,
                "form_submit",
                JSONObject().put("ref", arguments.optString("ref")),
            )
            else -> {
                val listed = page(context, "form_list")
                val parsed = runCatching { JSONObject(listed) }.getOrDefault(JSONObject())
                if (!parsed.optBoolean("ok", true)) return listed
                val count = parsed.optJSONArray("fields")?.length() ?: 0
                if (count <= 0) {
                    val empty = BrowserDesk.noteEmptyFormList()
                    if (empty >= BrowserEmptyFormBudget) {
                        return errorJson(
                            "No form fields after $empty list attempts " +
                                "(empty drawer, iframe, or SPA). Stop with GUI_TASK_NEEDS_TEACHING. " +
                                "Do not click 报名 again or loop page_js.",
                            "form_unavailable",
                        )
                    }
                } else {
                    BrowserDesk.noteFormFieldsFound()
                }
                listed
            }
        }
    }

    private fun manageTabs(context: Context, prefs: BrowserPreferences, arguments: JSONObject): String {
        val action = arguments.optString("action").ifBlank { arguments.optString("op") }.lowercase()
        val components = AetherBrowserRuntime.getBlocking(context)
        when (action) {
            "open" -> {
                val url = normalizeBrowserAddress(arguments.optString("url"), prefs)
                val openedId = components.openTab(url, selectTab = true, topicQuery = "")
                if (openedId.isNotBlank()) {
                    currentTabId.set(openedId)
                    components.selectTab(openedId)
                    val sessionId = currentSessionId.get().orEmpty()
                    val topicId = BrowserTopicGraph.ensure(arguments, sessionId)
                    if (topicId.isNotBlank()) {
                        BrowserTopicGraph.bindGeckoTab(
                            topicId = topicId,
                            tabId = openedId,
                            asImageTab = false,
                            sessionId = sessionId,
                        )
                    }
                    waitForPageLoad(components, tabId = openedId, targetUrl = url)
                }
            }
            "select", "switch" -> {
                val id = arguments.optString("id").ifBlank { arguments.optString("tab_id") }
                if (!components.selectTab(id)) return errorJson("Unknown tab: $id")
                currentTabId.set(id)
            }
            "close" -> {
                val id = arguments.optString("id").ifBlank { arguments.optString("tab_id") }
                if (!components.closeTab(id)) return errorJson("Unknown tab: $id")
                val live = components.firstHttpTabId()
                if (live.isNotBlank()) {
                    components.selectTab(live)
                    currentTabId.set(live)
                }
            }
            "back" -> components.goBack(currentTabId.get().orEmpty())
            "forward" -> components.goForward(currentTabId.get().orEmpty())
            "reload" -> {
                val id = currentTabId.get().orEmpty().ifBlank { components.selectedTabId() }
                val target = if (components.isLiveHttpTab(id)) id else components.firstHttpTabId()
                if (target.isBlank()) {
                    return errorJson("No live page tab to reload.", "page_not_ready")
                }
                components.selectTab(target)
                currentTabId.set(target)
                components.reload(target)
            }
            else -> return errorJson("Unknown tabs_manage action: $action")
        }
        return ok(JSONObject().put("tabs", components.tabList()).put("url", components.selectedUrl()))
    }

    private fun navigate(
        context: Context,
        prefs: BrowserPreferences,
        vault: BrowserLoginVault,
        arguments: JSONObject,
    ): String {
        val rawUrl = arguments.optString("url").ifBlank { arguments.optString("query") }
        val extractedQuery = arguments.optString("query")
            .ifBlank { searchQueryFromBrowserUrl(rawUrl) }
            .ifBlank { rawUrl }
        val images = arguments.optBoolean("images")
        val searchish = images ||
            isBrowserSearchQuery(
                arguments.optString("query").ifBlank { rawUrl },
                arguments.optString("url"),
                images,
            ) ||
            isBrowserSerpUrl(rawUrl)
        val humanQuery = if (searchish) {
            humanizeBrowserSearchQuery(extractedQuery).ifBlank { extractedQuery.trim() }
        } else {
            extractedQuery.trim()
        }
        val url = when {
            images -> normalizeBrowserImageSearch(humanQuery.ifBlank { rawUrl }, prefs)
            searchish && humanQuery.isNotBlank() && !looksLikeUrl(humanQuery) ->
                normalizeBrowserAddress(humanQuery, prefs)
            else -> normalizeBrowserAddress(rawUrl, prefs)
        }
        if (!images) {
            val serp = searchish || isBrowserSerpUrl(url)
            if (serp) {
                admitSerpSearch(humanQuery.ifBlank { extractedQuery })?.let { return it }
            }
        }
        BrowserDesk.lookupFailed(url)?.let { failed ->
            return missingPageError(url, failed.code, "Already failed this turn. Do not open this URL again.")
        }
        val components = AetherBrowserRuntime.getBlocking(context)
        val topicQuery = humanQuery.ifBlank { extractedQuery.ifBlank { rawUrl } }
        val tabId = currentTabId.get().orEmpty()
        BrowserTopicGraph.ensure(arguments, currentSessionId.get().orEmpty())
        components.navigate(url, tabId)
        awaitPageReady(context, tabId)
        waitForPageLoad(components, tabId = tabId, targetUrl = url)
        var observed = components.tabUrl(tabId).ifBlank { components.selectedUrl() }
        if (isSeedBrowserUrl(observed) || observed.isBlank()) {
            components.navigate(url, tabId)
            waitForPageLoad(components, tabId = tabId, targetUrl = url)
            observed = components.tabUrl(tabId).ifBlank { components.selectedUrl() }
        }
        if (tabId.isNotBlank()) {
            BrowserTopicGraph.attachGeckoTab(
                tabId = tabId,
                query = topicQuery,
                asImageTab = arguments.optBoolean("images"),
                sessionId = currentSessionId.get().orEmpty(),
            )
        }
        val title = components.tabTitle(tabId).ifBlank { components.selectedTitle() }
        if (isStaleMissingPageDocument(observed, title, url) || isWrongHostDocument(observed, url)) {
            components.navigate(url, tabId)
            waitForPageLoad(components, tabId = tabId, targetUrl = url)
            observed = components.tabUrl(tabId).ifBlank { components.selectedUrl() }
        }
        val settledTitle = components.tabTitle(tabId).ifBlank { components.selectedTitle() }
        if (isMissingBrowserPage(observed, settledTitle, requestedUrl = url)) {
            val dead = missingPageRequestedUrl(observed, url)
            if (!isStaleMissingPageDocument(observed, settledTitle, url)) {
                BrowserDesk.recordFailed(dead, "not_found", settledTitle)
                return missingPageError(dead, "not_found", settledTitle.ifBlank { "page does not exist" })
            }
        }
        var snap = snapshotSettled(components, WebMcpCompact.snapshotArgs(arguments, defaultLimit = 24), tabId)
        if (isDisconnectedBrowserResult(snap)) {
            awaitPageReady(context, tabId, requireHttp = true)
            snap = snapshotSettled(components, WebMcpCompact.snapshotArgs(arguments, defaultLimit = 24), tabId)
        }
        if (isDisconnectedBrowserResult(snap)) {
            return disconnectedBrowserError(snap, components, tabId)
        }
        var result = snapshotPayload(snap, components, url, tabId)
        if (isStalePlaceholderDocument(result.optString("url"), url) ||
            isStaleMissingPageDocument(result.optString("url"), result.optString("title"), url) ||
            isWrongHostDocument(result.optString("url"), url)
        ) {
            waitForPageLoad(components, timeoutMs = 4_000, commandTimeoutMs = 6_000, targetUrl = url, tabId = tabId)
            snap = snapshotObject(components, WebMcpCompact.snapshotArgs(arguments, defaultLimit = 24), tabId)
            result = snapshotPayload(snap, components, url, tabId)
        }
        if (isStalePlaceholderDocument(result.optString("url"), url)) {
            return errorJson(
                "Page did not leave the example.com placeholder. Retry navigation.",
                "stale_document",
            )
        }
        if (isWrongHostDocument(result.optString("url"), url)) {
            return errorJson(
                "Gecko stayed on ${result.optString("url")} instead of $url. " +
                    "Do not treat that page as the official site. Retry tabs_navigate or pick another result.",
                "stale_document",
            )
        }
        if (isMissingBrowserPage(
                result.optString("url"),
                result.optString("title"),
                result.optString("tree"),
                requestedUrl = url,
            )
        ) {
            val dead = missingPageRequestedUrl(result.optString("url"), url)
            BrowserDesk.recordFailed(dead, "not_found", result.optString("title"))
            return missingPageError(dead, "not_found", result.optString("title").ifBlank { "page does not exist" })
        }
        pauseIfUserBlocked(result, snap, vault)?.let { return it }
        if (!arguments.optBoolean("images") && isBrowserSearchEngineHost(result.optString("url").ifBlank { observed })) {
            attachSerpHits(context, result, topicQuery, result.optString("url").ifBlank { observed })
            BrowserDesk.markWebSearchReturned()
        }
        // If the document we ended up on is not the one that was asked for, say so in the
        // payload. Silently answering about the previous page is worse than an error.
        val landed = result.optString("url")
        if (landed.isNotBlank() && looksLikeHttpUrl(url) &&
            normalizeBrowsedUrl(landed) != normalizeBrowsedUrl(url)
        ) {
            result.put("requested_url", url)
            result.put("redirected", true)
        }
        if (isEmptySnapshot(snap) && result.optJSONArray("hits") == null) {
            recoverEmptyPage(components, result, snap, url)?.let { recovered ->
                clearStrikes(tabId.ifBlank { url })
                return recovered
            }
            return emptySnapshotError(snap, tabId, result.optString("url").ifBlank { url })
        }
        clearStrikes(tabId.ifBlank { url })
        return ok(result)
    }

    /**
     * One call that answers "open this page and tell me what is on it": navigate, activate, wait
     * for the load and the mount, snapshot the whole page, and attach the readable text — falling
     * back to the shared ledger when Gecko cannot present the page itself.
     */
    private fun browserOpen(
        context: Context,
        prefs: BrowserPreferences,
        vault: BrowserLoginVault,
        arguments: JSONObject,
    ): String {
        val raw = arguments.optString("url").ifBlank { arguments.optString("query") }.trim()
        if (raw.isBlank()) {
            return errorJson("url is required", "invalid_argument", retryable = false)
        }
        val sessionId = currentSessionId.get().orEmpty()
        val navArgs = JSONObject(arguments.toString())
            .put("region", arguments.optString("region").ifBlank { "page" })
            .put("limit", if (arguments.optInt("limit", 0) > 0) arguments.optInt("limit") else 60)
        val navigated = navigate(context, prefs, vault, navArgs)
        val parsed = runCatching { JSONObject(navigated) }.getOrNull() ?: return navigated

        if (parsed.optBoolean("ok", false)) {
            val target = parsed.optString("url").ifBlank { raw }
            // Navigation still happens, so the page stays live for clicks and forms. What we skip
            // is the extra read roundtrip when this conversation already holds the text.
            if (parsed.optString("text").isBlank() && looksLikeHttpUrl(target)) {
                BrowserPageLedger.recover(target, sessionId)?.let { known ->
                    parsed.put("text", known.text.take(6_000))
                    parsed.put("text_source", known.source)
                    parsed.put("reused_from_graph", true)
                }
            }
            if (parsed.optString("text").isBlank() && looksLikeHttpUrl(target)) {
                val components = AetherBrowserRuntime.getBlocking(context)
                val read = runCatching {
                    runBlocking {
                        components.pageCommand(
                            "read",
                            JSONObject().put("limit", 6_000),
                            timeoutMs = remainingMs(8_000L),
                            tabId = currentTabId.get().orEmpty(),
                        )
                    }
                }.getOrNull()
                val text = read?.optString("text").orEmpty()
                if (text.isNotBlank()) {
                    parsed.put("text", text.take(6_000))
                    rememberPageText(target, parsed.optString("title"), text, "gecko-dom")
                }
            }
            return parsed.toString()
        }

        BrowserPageLedger.recover(raw, sessionId)?.let { page ->
            val payload = BrowserPageLedger.describe(page)
            payload.put(
                "note",
                "Gecko could not present this page, but its content is already known " +
                    "(via " + page.source + "). Use the text below instead of retrying.",
            )
            return ok(payload)
        }
        return navigated
    }

    private fun waitForPageLoad(
        components: AetherBrowserRuntime.Components,
        timeoutMs: Int = 8_000,
        commandTimeoutMs: Long = 12_000,
        targetUrl: String = "",
        tabId: String = "",
    ) {
        // Event-driven: the browser store emits on every content change, so this returns the
        // moment the tab settles instead of burning a fixed polling budget on a page that is
        // already done. commandTimeoutMs is kept for call-site compatibility.
        val budget = remainingMs(timeoutMs.toLong().coerceAtMost(commandTimeoutMs))
        val target = tabId.ifBlank { components.selectedTabId() }
        if (target.isBlank()) return
        runBlocking { components.awaitLoadSettled(target, targetUrl, budget) }
    }

    private fun attachSerpHits(
        context: Context,
        result: JSONObject,
        query: String,
        url: String,
    ) {
        val harvested = harvestUntil(context, kind = "links", limit = 8, timeoutMs = 3_500L)
        val hitsJson = harvested.optJSONArray("hits") ?: JSONArray()
        if (hitsJson.length() == 0) {
            val fromTree = DittoWebSearchMapper.fromSnapshotTree(result.optString("tree"), limit = 8)
            if (fromTree.isEmpty()) return
            val mapped = JSONArray()
            fromTree.forEach { hit ->
                mapped.put(
                    JSONObject()
                        .put("title", hit.title)
                        .put("url", hit.url)
                        .put("snippet", hit.snippet),
                )
            }
            result.put("hits", mapped)
            result.put("query", query)
            BrowserDesk.recordSearch(
                query = query,
                url = url,
                hits = fromTree.map { hit ->
                    BrowserDeskHit(title = hit.title, url = hit.url, snippet = hit.snippet)
                },
            )
            return
        }
        val known = graphHits(query, limit = 3)
        if (known.length() > 0) {
            val merged = JSONArray()
            val seen = HashSet<String>()
            for (index in 0 until known.length()) {
                val entry = known.optJSONObject(index) ?: continue
                if (seen.add(normalizeBrowsedUrl(entry.optString("url")))) merged.put(entry)
            }
            for (index in 0 until hitsJson.length()) {
                val entry = hitsJson.optJSONObject(index) ?: continue
                if (seen.add(normalizeBrowsedUrl(entry.optString("url")))) merged.put(entry)
            }
            result.put("page_graph_first", true)
            result.put("hits", merged)
        } else {
            result.put("hits", hitsJson)
        }
        result.put("query", query)
        val hits = DittoWebSearchMapper.fromHarvest(hitsJson, limit = 8).map { hit ->
            BrowserDeskHit(title = hit.title, url = hit.url, snippet = hit.snippet)
        }
        if (hits.isNotEmpty()) {
            BrowserDesk.recordSearch(query = query, url = url, hits = hits)
        }
    }

    private fun missingPageError(url: String, code: String, detail: String): String =
        errorJson(
            "This page does not exist or failed to load ($url). $detail. " +
                "Do not open this URL again. Pick a different result.",
            code.ifBlank { "not_found" },
        )

    private fun snapshot(context: Context, vault: BrowserLoginVault, arguments: JSONObject): String {
        val components = AetherBrowserRuntime.getBlocking(context)
        val tabId = currentTabId.get().orEmpty()
        var snap = snapshotSettled(components, WebMcpCompact.snapshotArgs(arguments), tabId)
        if (isDisconnectedBrowserResult(snap)) {
            awaitPageReady(context, tabId, requireHttp = true)
            snap = snapshotSettled(components, WebMcpCompact.snapshotArgs(arguments), tabId)
        }
        if (isDisconnectedBrowserResult(snap) || snap.optBoolean("ok", true) == false) {
            return disconnectedBrowserError(snap, components, tabId)
        }
        var payload = snapshotPayload(snap, components, "", tabId)
        pauseIfUserBlocked(payload, snap, vault)?.let { blocked ->
            if (!waitOutUserTakeover()) return blocked
            // The user cleared it in the card. Read the page again right here — the turn never
            // had to stop, so the agent keeps whatever it was in the middle of doing.
            snap = snapshotSettled(components, WebMcpCompact.snapshotArgs(arguments), tabId)
            payload = snapshotPayload(snap, components, "", tabId)
            pauseIfUserBlocked(payload, snap, vault)?.let { return it }
            payload.put("resumed_after_takeover", true)
        }
        if (isEmptySnapshot(snap)) {
            recoverEmptyPage(components, payload, snap, payload.optString("url"))?.let { recovered ->
                clearStrikes(tabId.ifBlank { payload.optString("url") })
                return recovered
            }
            return emptySnapshotError(snap, tabId, payload.optString("url"))
        }
        clearStrikes(tabId.ifBlank { payload.optString("url") })
        return ok(payload)
    }

    private fun pauseIfUserBlocked(
        payload: JSONObject,
        snap: JSONObject,
        vault: BrowserLoginVault,
    ): String? {
        val url = payload.optString("url").ifBlank { snap.optString("url") }
        val title = payload.optString("title").ifBlank { snap.optString("title") }
        val body = payload.optString("tree").ifBlank {
            snap.optString("tree").ifBlank { payload.optString("text") }
        }
        if (detectBrowserChallenge(url, title, body)) {
            BrowserDesk.requestUserTakeover(url, "challenge")
            return inputRequired(
                payload
                    .put("reason", BrowserChallengeTakeoverReason)
                    .put("user_takeover", true)
                    .put("pause_code", "challenge"),
            )
        }
        applyLoginHint(payload, snap, vault)
        if (shouldPauseForLogin(snap, vault)) {
            BrowserDesk.requestUserTakeover(url, "login")
            return inputRequired(
                payload
                    .put("reason", BrowserLoginTakeoverReason)
                    .put("user_takeover", true)
                    .put("pause_code", "login"),
            )
        }
        return null
    }

    private fun applyLoginHint(target: JSONObject, snap: JSONObject, vault: BrowserLoginVault) {
        if (!detectLoginWall(snap)) return
        val saved = vault.find(snap.optString("url").ifBlank { target.optString("url") })
        target.put("login_wall", true)
        target.put("saved_logins", saved.size)
        if (saved.isNotEmpty()) {
            target.put("saved_username", saved.first().username)
            target.put("hint", "Call passwords action=fill to apply the saved login without echoing the secret.")
        }
    }

    private fun shouldPauseForLogin(snap: JSONObject, vault: BrowserLoginVault): Boolean {
        if (!detectLoginWall(snap)) return false
        return vault.find(snap.optString("url")).isEmpty()
    }

    private fun snapshotPayload(
        snap: JSONObject,
        components: AetherBrowserRuntime.Components,
        fallbackUrl: String,
        tabId: String = "",
    ): JSONObject = JSONObject()
        .put("url", snap.optString("url").ifBlank {
            (if (tabId.isNotBlank()) components.tabUrl(tabId) else components.selectedUrl())
                .ifBlank { fallbackUrl }
        })
        .put("title", snap.optString("title").ifBlank {
            if (tabId.isNotBlank()) components.tabTitle(tabId) else components.selectedTitle()
        })
        .put("tree", snap.optString("tree"))
        .put("total", snap.optInt("total"))
        .put("offset", snap.optInt("offset"))
        .put("limit", snap.optInt("limit"))
        .put("shown", snap.optInt("shown"))
        .put("region", snap.optString("region").ifBlank { "viewport" })
        .put("more", snap.optString("more"))
        .put("ready_state", snap.optString("readyState"))
        .put("dom_total", snap.optInt("dom_total"))
        .apply {
            if (snap.has("activated")) put("activated", snap.optBoolean("activated"))
            if (snap.has("settle_nodes")) put("settle_nodes", snap.optInt("settle_nodes"))
        }

    private fun page(
        context: Context,
        op: String,
        args: JSONObject = JSONObject(),
        timeoutMs: Long = 10_000,
    ): String {
        val components = AetherBrowserRuntime.getBlocking(context)
        val tabId = currentTabId.get().orEmpty()
        awaitPageReady(context, tabId)
        val budget = remainingMs(timeoutMs)
        val result = runBlocking { components.pageCommand(op, args, budget, tabId) }
        if (isDisconnectedBrowserResult(result)) {
            if (!browserPageOperationCanReplay(op)) {
                return errorJson(
                    "The operation lost its response. Observe the page and verify its outcome before acting again.",
                    "outcome_unknown", retryable = false,
                )
            }
            // "extension_not_ready" already means we looked and found no channel. Retrying the
            // whole wait ladder just spends the rest of the budget to learn the same thing.
            if (result.optString("code") == "extension_not_ready" &&
                !AetherBrowserRuntime.hasAnyChannel(tabId)
            ) {
                return finalizePage(result)
            }
            awaitPageReady(context, tabId, requireHttp = true)
            val retry = runBlocking { components.pageCommand(op, args, remainingMs(timeoutMs), tabId) }
            return finalizePage(retry)
        }
        return finalizePage(result)
    }

    private fun awaitPageReady(
        context: Context,
        tabId: String,
        timeoutMs: Long = 6_000L,
        requireHttp: Boolean = false,
    ): Boolean {
        if (tabId.isBlank()) return false
        AetherBrowserRuntime.preparePageChannel(context, tabId)
        // Nothing connected and nothing loading: no amount of waiting produces a channel.
        if (!AetherBrowserRuntime.hasAnyChannel(tabId) &&
            AetherBrowserRuntime.peek()?.tabLoading(tabId) != true
        ) {
            runBlocking { delay(400) }
            if (!AetherBrowserRuntime.hasAnyChannel(tabId)) {
                return AetherBrowserRuntime.isExtensionReady(tabId)
            }
        }
        val deadline = System.currentTimeMillis() + remainingMs(timeoutMs)
        var nudgedAt = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            val ready = AetherBrowserRuntime.isExtensionReady(tabId)
            val url = AetherBrowserRuntime.peek()?.tabUrl(tabId).orEmpty().ifBlank {
                AetherBrowserRuntime.peek()?.selectedUrl().orEmpty()
            }
            val httpOk = !requireHttp || (looksLikeHttpUrl(url) && !isSeedBrowserUrl(url))
            if (ready && httpOk) return true
            val now = System.currentTimeMillis()
            if (now - nudgedAt >= 600L) {
                nudgedAt = now
                AetherBrowserRuntime.preparePageChannel(context, tabId)
            }
            runBlocking { delay(50) }
        }
        return AetherBrowserRuntime.isExtensionReady(tabId)
    }

    private fun disconnectedBrowserError(
        snap: JSONObject,
        components: AetherBrowserRuntime.Components,
        tabId: String,
    ): String {
        val url = snap.optString("url").ifBlank {
            (if (tabId.isNotBlank()) components.tabUrl(tabId) else components.selectedUrl())
        }
        val detail = snap.optString("errmsg").ifBlank {
            "WebMCP extension is not connected yet."
        }
        if (looksLikeHttpUrl(url) && !isSeedBrowserUrl(url)) {
            BrowserDesk.holdLivePage(url)
        } else {
            BrowserDesk.holdLivePage()
        }
        val strikes = strike("not_ready", tabId.ifBlank { url })
        val next = when (strikes) {
            1 -> "Call page_snapshot once while the page channel reattaches."
            2 -> "Call page_snapshot once more with region=\"page\"; the content script is still coming back."
            3 -> "Reload with tabs_navigate to the same URL, then call page_snapshot with region=\"page\"."
            else -> "Stop retrying. Report that the page channel for " + url + " did not come back."
        }
        val report = runCatching { components.channelReport(tabId) }.getOrNull()
        if (report != null) {
            Log.w("WebMcpHost", "page_not_ready $report")
        }
        val body = JSONObject()
            .put("ok", false)
            .put("code", if (strikes >= 4) "page_not_ready_final" else "page_not_ready")
            .put("retryable", strikes < 4)
            .put(
                "errmsg",
                "Page tools are not ready yet ($detail). " + next + " " +
                    "Do not ask the user to open a browser card. " +
                    "Do not stop with GUI_TASK_NEEDS_TEACHING.",
            )
        if (report != null) body.put("diagnostics", report)
        if (strikes < 4) {
            body.put("next", JSONObject().put("tool", "page_snapshot").put("args", JSONObject().put("region", "page")))
        }
        return body.toString()
    }

    private fun finalizePage(result: JSONObject): String {
        if (isDisconnectedBrowserResult(result) || result.optBoolean("ok", true) == false) {
            if (isDisconnectedBrowserResult(result)) {
                val components = AetherBrowserRuntime.peek()
                if (components != null) {
                    return disconnectedBrowserError(
                        result,
                        components,
                        currentTabId.get().orEmpty(),
                    )
                }
            }
            return errorJson(
                result.optString("errmsg").ifBlank { result.toString() },
                result.optString("code").ifBlank { "error" },
            )
        }
        val url = result.optString("url")
        val title = result.optString("title")
        val body = result.optString("text").ifBlank { result.optString("tree") }
        rememberPageText(url, title, result.optString("text"), "gecko-dom")
        if (url.isNotBlank() && detectBrowserChallenge(url, title, body)) {
            BrowserDesk.requestUserTakeover(url, "challenge")
            if (waitOutUserTakeover()) {
                // Cleared while we held the call. Say so as a retryable result rather than
                // GUI_TASK_NEEDS_TEACHING, so the agent repeats one call instead of ending.
                return errorJson(
                    "The user completed the verification. The page is open and clear now — " +
                        "repeat your last call on this url to read it.",
                    code = "takeover_cleared",
                    retryable = true,
                    nextTool = "page_snapshot",
                    nextArgs = JSONObject().put("region", "page"),
                )
            }
            return inputRequired(
                result
                    .put("reason", BrowserChallengeTakeoverReason)
                    .put("user_takeover", true)
                    .put("pause_code", "challenge"),
            )
        }
        return ok(result)
    }

    private fun strike(kind: String, key: String): Int =
        recoveryStrikes.merge(kind + "@" + key, 1) { old, add -> old + add } ?: 1

    private fun clearStrikes(key: String) {
        if (key.isBlank()) return
        recoveryStrikes.keys.removeAll { it.endsWith("@" + key) }
    }

    internal fun isEmptySnapshot(snap: JSONObject): Boolean =
        snap.optString("tree").isBlank() && snap.optInt("total", 0) == 0

    /**
     * Snapshot, then work out *why* it came back empty before answering. A document that is still
     * parsing gets time; a laid-out page whose content simply sits outside the viewport gets
     * re-read with region=page; only a genuinely empty DOM is reported as empty.
     */
    private fun snapshotSettled(
        components: AetherBrowserRuntime.Components,
        arguments: JSONObject,
        tabId: String,
    ): JSONObject {
        var snap = snapshotObject(components, arguments, tabId)
        if (!isEmptySnapshot(snap) ||
            isDisconnectedBrowserResult(snap) ||
            !snap.optBoolean("ok", true)
        ) {
            return snap
        }
        // An empty DOM is often just a background tab: Gecko suspends rAF on inactive sessions,
        // so a client-rendered page never mounts. Make it the foreground tab and let it mount.
        val activated = components.activateTab(tabId)
        val settle = runCatching {
            runBlocking {
                components.pageCommand(
                    "settle",
                    JSONObject()
                        .put("timeout_ms", remainingMs(8_000L))
                        .put("quiet_ms", 350),
                    timeoutMs = remainingMs(12_000L),
                    tabId = tabId,
                )
            }
        }.getOrNull() ?: JSONObject()
        snap = snapshotObject(components, arguments, tabId)
        if (!isEmptySnapshot(snap) || !snap.optBoolean("ok", true)) return snap
        var attempts = 0
        while (attempts < 2 &&
            (snap.optString("readyState") != "complete" || components.tabLoading(tabId))
        ) {
            attempts++
            runBlocking { delay(SnapshotSettleMs) }
            snap = snapshotObject(components, arguments, tabId)
            if (!isEmptySnapshot(snap) || !snap.optBoolean("ok", true)) return snap
        }
        snap.put("activated", activated)
        if (settle.has("nodes")) {
            snap.put("settle_nodes", settle.optInt("nodes"))
            snap.put("settle_grew", settle.optInt("grew"))
        }
        val requestedRegion = arguments.optString("region").ifBlank { "viewport" }
        if (snap.optInt("dom_total", 0) > 0 && requestedRegion != "page") {
            val widened = JSONObject(arguments.toString()).put("region", "page")
            val pageWide = snapshotObject(components, widened, tabId)
            if (!isEmptySnapshot(pageWide)) {
                pageWide.put("region_fallback", requestedRegion + "->page")
                return pageWide
            }
            snap = pageWide
        }
        return snap
    }

    /**
     * A JavaScript-rendered page can finish loading with nothing in the DOM. That is a state, not
     * a failure. Recover the text from whichever path already has it — this run's ledger, an
     * earlier read by a browser subagent, or a direct Necko fetch — and answer with it, so the
     * work another agent already did is never thrown away.
     */
    private fun recoverEmptyPage(
        components: AetherBrowserRuntime.Components,
        payload: JSONObject,
        snap: JSONObject,
        fallbackUrl: String,
    ): String? {
        val target = payload.optString("url").ifBlank { fallbackUrl }
        if (target.isBlank() || !looksLikeHttpUrl(target)) return null
        val tabId = currentTabId.get().orEmpty()
        val sessionId = currentSessionId.get().orEmpty()

        BrowserPageLedger.recover(target, sessionId)?.let { page ->
            return okCsrShell(payload, BrowserPageLedger.describe(page), page.source)
        }

        val read = runCatching {
            runBlocking {
                components.pageCommand(
                    "read",
                    JSONObject().put("limit", 8_000),
                    timeoutMs = remainingMs(8_000L),
                    tabId = tabId,
                )
            }
        }.getOrNull()
        val domText = read?.optString("text").orEmpty()
            .ifBlank { read?.optString("body").orEmpty() }
            .ifBlank { read?.optString("content").orEmpty() }
        if (domText.length >= 200) {
            val title = payload.optString("title").ifBlank { read?.optString("title").orEmpty() }
            BrowserPageLedger.ingest(
                url = target,
                title = title,
                text = domText,
                source = "gecko-dom",
                sessionId = sessionId,
                readyState = snap.optString("readyState"),
                domTotal = snap.optInt("dom_total", -1),
                csrShell = true,
            )
            BrowserPageLedger.recover(target, sessionId)?.let { page ->
                return okCsrShell(payload, BrowserPageLedger.describe(page), "gecko-dom")
            }
        }

        val fetched = runCatching { components.fetchDocument(target, remainingMs(8_000L)) }.getOrNull()
        val html = fetched?.html.orEmpty()
        val text = readableTextFromHtml(html)
        if (text.length >= 200) {
            val title = titleFromHtml(html).ifBlank { payload.optString("title") }
            BrowserPageLedger.ingest(
                url = target,
                title = title,
                text = text,
                source = "necko-fetch",
                sessionId = sessionId,
                readyState = snap.optString("readyState"),
                domTotal = snap.optInt("dom_total", -1),
                csrShell = true,
            )
            BrowserPageLedger.recover(target, sessionId)?.let { page ->
                return okCsrShell(payload, BrowserPageLedger.describe(page), "necko-fetch")
            }
        }
        return null
    }

    private fun okCsrShell(payload: JSONObject, recovered: JSONObject, source: String): String {
        payload.put("csr_shell", true)
        payload.put("source", source)
        payload.put("text", recovered.optString("text"))
        payload.put("text_truncated", recovered.optBoolean("text_truncated"))
        if (payload.optString("title").isBlank()) {
            payload.put("title", recovered.optString("title"))
        }
        payload.put(
            "note",
            "This page builds its content with JavaScript and exposed no elements to snapshot. " +
                "The text below is the page content, recovered via " + source + ". " +
                "Use it as the page. Do not re-navigate, reload, or retry page_snapshot for this URL. " +
                "If you need to click or fill something, call page_settle once and then " +
                "page_snapshot with region=\"page\".",
        )
        return ok(payload)
    }

    private fun urlArguments(arguments: JSONObject): List<String> {
        val out = mutableListOf<String>()
        arguments.optJSONArray("urls")?.let { array ->
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let(out::add)
            }
        }
        arguments.optString("url").takeIf { it.isNotBlank() }?.let(out::add)
        return out
    }

    /** Fetch many URLs at once and file them all in the page graph. */
    private fun fetchMany(context: Context, arguments: JSONObject): String {
        val sessionId = currentSessionId.get().orEmpty()
        val urls = urlArguments(arguments)
        if (urls.isEmpty()) return errorJson("urls is required", "invalid_argument", retryable = false)
        val components = AetherBrowserRuntime.getBlocking(context)
        val query = arguments.optString("query")
        val docs = runBlocking {
            components.fetchParsedDocuments(
                urls,
                timeoutMs = remainingMs(8_000L),
                sessionId = sessionId,
            )
        }
        val pages = JSONArray()
        var stored = 0
        urls.distinct().forEach { url ->
            val doc = docs[url.trim()]
            val entry = JSONObject().put("url", url)
            if (doc == null) {
                entry.put("ok", false).put("error", "unreachable")
            } else {
                val landed = doc.url.ifBlank { url }
                val title = doc.title
                val text = doc.text
                if (text.length >= BrowserPageLedger.MinUsefulChars) {
                    stored++
                }
                entry.put("ok", true)
                    .put("final_url", landed)
                    .put("title", title)
                    .put("chars", text.length)
                    .put("truncated", doc.truncated)
                    .put("truncation_reason", if (doc.truncated) "body_limit" else JSONObject.NULL)
                    .put("fetch_ms", doc.fetchMs)
                    .put("parse_ms", doc.parseMs)
                    .put(
                        "snippet",
                        BrowserResearchGraph.passagesSnippet(landed, query, 500)
                            .ifBlank { text.take(500) },
                    )
            }
            pages.put(entry)
        }
        return ok(
            JSONObject()
                .put("pages", pages)
                .put("stored_in_graph", stored)
                .put(
                    "note",
                    "Reachable pages are now in page_graph. Use page_recall to read any of them " +
                        "in full instead of opening them again.",
                ),
        )
    }

    /**
     * Which of these URLs is one a person could actually register on? Fetches every candidate in
     * parallel, scores each on whether it carries a real form, and when none does, follows one hop
     * through the signup links the candidates contain.
     */
    private fun findSignup(context: Context, arguments: JSONObject): String {
        val sessionId = currentSessionId.get().orEmpty()
        val seeds = urlArguments(arguments).toMutableList()
        if (arguments.optBoolean("from_graph", seeds.isEmpty())) {
            val index = BrowserPageLedger.index(sessionId, limit = 8)
            for (i in 0 until index.length()) {
                index.optJSONObject(i)?.optString("url")?.takeIf { it.isNotBlank() }?.let(seeds::add)
            }
        }
        if (seeds.isEmpty()) {
            return errorJson(
                "Pass urls, or from_graph=true once this conversation has read some pages.",
                "invalid_argument",
                retryable = false,
            )
        }
        val components = AetherBrowserRuntime.getBlocking(context)
        val probes = runBlocking {
            BrowserSignupProbe.rank(
                components = components,
                seeds = seeds,
                sessionId = sessionId,
                followHops = arguments.optBoolean("follow_links", true),
            )
        }
        val best = probes.firstOrNull { it.verified }
        val list = JSONArray()
        probes.take(10).forEach { list.put(it.toJson()) }
        val payload = JSONObject()
            .put("candidates", list)
            .put("verified", best != null)
        if (best != null) {
            payload.put("signup_url", best.finalUrl)
                .put("title", best.title)
                .put(
                    "note",
                    "Verified: this page carries a real form. Report it as the official signup " +
                        "URL and open it with browser_open to fill it in.",
                )
        } else {
            payload.put(
                "note",
                "None of these pages carries a signup form. Do not report any of them as the " +
                    "official signup URL. Open a live page and harvest its 报名 or 阅读原文 link " +
                    "instead, then verify that.",
            )
        }
        return ok(payload)
    }

    /**
     * Read a page this conversation has already been through, straight out of the page graph.
     * No navigation, no tab, no network: the content is already chunked into ranked passages,
     * so a query returns only the relevant ones.
     */
    private fun pageRecall(arguments: JSONObject): String {
        val sessionId = currentSessionId.get().orEmpty()
        val requested = arguments.optString("url").trim()
        val n = arguments.optInt("n", 0)
        val url = requested.ifBlank { if (n > 0) BrowserPageLedger.urlForIndex(sessionId, n) else "" }
        if (url.isBlank()) {
            return errorJson(
                "Pass url, or n as shown in the page_graph index.",
                "invalid_argument",
                retryable = false,
            )
        }
        val query = arguments.optString("query")
        val page = BrowserPageLedger.recover(url, sessionId)
        val indexed = BrowserResearchGraph.lookup(url)
        if (page == null && indexed == null) {
            return errorJson(
                "This conversation has not read " + url + " yet.",
                "not_in_graph",
                retryable = false,
                nextTool = "browser_open",
                nextArgs = JSONObject().put("url", url),
            )
        }
        val markdown = BrowserResearchGraph.markdownFor(url, query)
            .ifBlank { page?.text.orEmpty() }
        val limit = arguments.optInt("limit", 0).let { if (it in 1..20_000) it else 6_000 }
        // Cheap first, network only if cheap could not answer. A page read inside this turn is
        // trusted on age alone and costs nothing; an older one gets a conditional request whose 304
        // is a round trip with no body. Recall still serves the cached text either way - the model
        // is told what it is reading rather than silently handed text the site has since replaced.
        val freshness = indexed?.let { pageFreshness(it) } ?: PageFreshness.Unknown
        val body = JSONObject()
            .put("url", url)
            .put("title", indexed?.title.orEmpty().ifBlank { page?.title.orEmpty() })
            .put("source", "page-graph")
            .put("query", query)
            .put("text", markdown.take(limit))
            .put("text_truncated", markdown.length > limit)
            .put("passages_total", indexed?.passages?.size ?: 0)
            .put("freshness", freshness.name.lowercase())
        if (freshness == PageFreshness.Stale) {
            body.put(
                "note",
                "This page has changed since it was read. The text above is what was read; " +
                    "browser_open it again if the change could matter to your answer.",
            )
        }
        return ok(body)
    }

    /** Every successful read feeds the shared ledger, so later tools and other agents can use it. */
    private fun rememberPageText(url: String, title: String, text: String, source: String) {
        if (!looksLikeHttpUrl(url)) return
        BrowserPageLedger.ingest(
            url = url,
            title = title,
            text = text,
            source = source,
            sessionId = currentSessionId.get().orEmpty(),
        )
    }

    private fun emptySnapshotError(snap: JSONObject, tabId: String, fallbackUrl: String): String {
        val where = snap.optString("url").ifBlank { fallbackUrl }
        val strikes = strike("empty", tabId.ifBlank { where })
        val state = snap.optString("readyState").ifBlank { "unknown" }
        val next = when (strikes) {
            1 -> "Call page_snapshot once more with region=\"page\" and limit=80."
            2 -> "Call page_read for the text, or page_snapshot with a query naming the control you need."
            3 -> "Reload once with tabs_navigate to the same URL, then page_snapshot with region=\"page\"."
            else -> "Stop retrying this page. Say that it rendered nothing and take another route."
        }
        return errorJson(
            "Page " + where + " exposed no readable elements (readyState=" + state +
                ", dom=" + snap.optInt("dom_total") + "), and no text could be recovered " +
                "from the page, the shared ledger, or a direct fetch. " + next,
            if (strikes >= 4) "page_empty_final" else "page_empty",
            retryable = strikes < 4,
            nextTool = if (strikes >= 4) "" else "page_snapshot",
            nextArgs = if (strikes >= 4) null else JSONObject().put("region", "page").put("limit", 80),
        )
    }

    private fun snapshotObject(
        components: AetherBrowserRuntime.Components,
        arguments: JSONObject = JSONObject(),
        tabId: String = currentTabId.get().orEmpty(),
    ): JSONObject {
        val result = runBlocking { components.pageCommand("snapshot", arguments, tabId = tabId) }
        if (!result.has("url")) {
            result.put("url", if (tabId.isNotBlank()) components.tabUrl(tabId) else components.selectedUrl())
        }
        if (!result.has("title")) {
            result.put("title", if (tabId.isNotBlank()) components.tabTitle(tabId) else components.selectedTitle())
        }
        AetherBrowserRuntime.recordVisit(result.optString("url"), result.optString("title"))
        return WebMcpCompact.forModel(result)
    }

    private fun subjectArguments(arguments: JSONObject): List<String> {
        val out = mutableListOf<String>()
        arguments.optJSONArray("subjects")?.let { array ->
            for (index in 0 until array.length()) {
                array.optString(index).trim().takeIf { it.isNotBlank() }?.let(out::add)
            }
        }
        return out.distinct()
    }

    /**
     * Pair pictures to things, in the harness.
     *
     * With one flat list per query the model has to decide which picture belongs to which dish,
     * and it guesses: six dishes, two broad searches, one dish with two images and four with
     * none. Searching per subject and returning the result keyed by subject removes the guess —
     * a caller can only take an image from the subject it was found for.
     */
    private fun searchImagesBySubject(
        context: Context,
        prefs: BrowserPreferences,
        vault: BrowserLoginVault,
        arguments: JSONObject,
        subjects: List<String>,
    ): String {
        val context7 = arguments.optString("query").ifBlank { arguments.optString("q") }.trim()
        val wanted = subjects.take(8)
        val bySubject = JSONObject()
        val missing = JSONArray()
        var totalKept = 0
        wanted.forEach { subject ->
            if (remainingMs(60_000L) < 4_000L) {
                missing.put(subject)
                return@forEach
            }
            val perSubject = JSONObject(arguments.toString())
                .put("query", listOf(subject, context7).filter { it.isNotBlank() }.joinToString(" "))
                .put("subject", subject)
                .put("peer_subjects", JSONArray(wanted))
            val raw = runCatching { JSONObject(searchImagesOnce(context, prefs, vault, perSubject)) }
                .getOrNull()
            val images = raw?.optJSONArray("images") ?: JSONArray()
            if (images.length() == 0) {
                missing.put(subject)
            } else {
                bySubject.put(subject, images)
                totalKept += images.length()
            }
        }
        return ok(
            JSONObject()
                .put("by_subject", bySubject)
                .put("subjects_without_image", missing)
                .put("kept", totalKept)
                .put(
                    "note",
                    "Images are keyed by subject. Use an image only under the subject it is " +
                        "listed for. Subjects in subjects_without_image have no usable picture — " +
                        "leave them without one rather than borrowing another subject's.",
                ),
        )
    }

    private fun searchImages(
        context: Context,
        prefs: BrowserPreferences,
        vault: BrowserLoginVault,
        arguments: JSONObject,
    ): String {
        val subjects = subjectArguments(arguments)
        if (subjects.isNotEmpty()) {
            return searchImagesBySubject(context, prefs, vault, arguments, subjects)
        }
        return searchImagesOnce(context, prefs, vault, arguments)
    }

    private fun searchImagesOnce(
        context: Context,
        prefs: BrowserPreferences,
        vault: BrowserLoginVault,
        arguments: JSONObject,
    ): String {
        val query = arguments.optString("query").ifBlank { arguments.optString("q") }.trim()
        if (query.isBlank()) return errorJson("query is required", "invalid_argument")
        val navigated = JSONObject(
            navigate(
                context,
                prefs,
                vault,
                JSONObject().put("query", query).put("images", true).put("region", "page").put("limit", 80)
                    .put("topic_id", arguments.optString("topic_id"))
                    .put("topicId", arguments.optString("topicId")),
            ),
        )
        if (!navigated.optBoolean("ok", true)) return navigated.toString()
        val images = JSONArray()
        val harvested = harvestUntil(context, kind = "images", limit = 12, timeoutMs = 3_500L)
        val harvestedImages = harvested.optJSONArray("images")
        if (harvestedImages != null) {
            for (index in 0 until harvestedImages.length()) {
                val item = harvestedImages.optJSONObject(index) ?: continue
                images.put(item)
            }
        }
        if (images.length() == 0) {
            extractImagesFromSnapshotTree(navigated.optString("tree")).forEach { image ->
                images.put(
                    JSONObject()
                        .put("src", image.url)
                        .put("url", image.url)
                        .put("alt", image.alt),
                )
            }
        }
        val harvestedCount = images.length()
        val subject = arguments.optString("subject").trim()
        val peers = mutableListOf<String>()
        arguments.optJSONArray("peer_subjects")?.let { array ->
            for (index in 0 until array.length()) {
                array.optString(index).trim().takeIf { it.isNotBlank() }?.let(peers::add)
            }
        }
        val curated = ArrayList<BrowserDeskImage>()
        val scored = ArrayList<Pair<Int, BrowserDeskImage>>()
        for (index in 0 until images.length()) {
            val item = images.optJSONObject(index) ?: continue
            val url = item.optString("src").ifBlank { item.optString("url") }
            val alt = item.optString("alt")
            val caption = item.optString("caption")
            val pageTitle = item.optString("page_title")
            val altIsTitle = item.optBoolean("alt_is_page_title")
            if (subject.isBlank()) {
                if (!imageMatchesTopic(query, alt, url)) continue
                curated += BrowserDeskImage(url = url, alt = alt)
                continue
            }
            val score = scoreImageForSubject(
                subject = subject,
                alt = alt,
                caption = caption,
                pageTitle = pageTitle,
                url = url,
                altIsPageTitle = altIsTitle,
                otherSubjects = peers,
            )
            if (score <= 0) continue
            scored += score to BrowserDeskImage(
                url = url,
                alt = caption.ifBlank { alt },
                sourceUrl = item.optString("page_url").ifBlank { item.optString("source") },
                caption = caption,
                topicId = arguments.optString("topic_id"),
            )
        }
        if (subject.isNotBlank()) {
            scored.sortedByDescending { it.first }.take(4).forEach { curated += it.second }
        }
        logSearchImagesAudit(context, query, harvestedCount, curated.size)
        BrowserTopicGraph.recordImages(query, curated, arguments.optString("topic_id"))
        val kept = JSONArray()
        curated.forEach { image ->
            kept.put(
                JSONObject()
                    .put("src", image.url)
                    .put("url", image.url)
                    .put("alt", image.alt),
            )
        }
        return ok(
            JSONObject()
                .put("query", query)
                .put("url", harvested.optString("url").ifBlank { navigated.optString("url") })
                .put("images", kept)
                .put("harvested", harvestedCount)
                .put("kept", curated.size),
        )
    }

    private fun logSearchImagesAudit(
        context: Context,
        query: String,
        harvested: Int,
        kept: Int,
    ) {
        Log.i("AetherBrowser", "search_images harvested=$harvested kept=$kept query=${query.take(80)}")
        val logger = (context.applicationContext as? AetherApplication)?.runtime?.diagnosticLogger
            ?: return
        logger.event(
            category = "browser",
            event = "search_images_audit",
            details = mapOf(
                "query" to query.take(80),
                "harvested" to harvested,
                "kept" to kept,
            ),
        )
    }

    private fun harvestUntil(
        context: Context,
        kind: String,
        limit: Int,
        timeoutMs: Long,
    ): JSONObject {
        val components = AetherBrowserRuntime.getBlocking(context)
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = JSONObject()
        while (true) {
            last = runBlocking {
                components.pageCommand(
                    "harvest",
                    JSONObject().put("kind", kind).put("limit", limit),
                    timeoutMs = 6_000,
                    tabId = currentTabId.get().orEmpty(),
                )
            }
            val count = when (kind) {
                "images", "image" -> last.optJSONArray("images")?.length() ?: 0
                else -> last.optJSONArray("hits")?.length() ?: 0
            }
            if (last.optBoolean("ok", true) && count > 0) return last
            if (System.currentTimeMillis() >= deadline) return last
            runBlocking { delay(250) }
        }
    }

    /**
     * Hits taken from what this conversation has already read, newest ranking first.
     *
     * A refused search used to answer with a bare "you are looping" placeholder, which tells the
     * model nothing it can use. The pages it already has usually do answer the question.
     */
    private fun graphHits(query: String, limit: Int = 5): JSONArray {
        val sessionId = currentSessionId.get().orEmpty()
        val array = JSONArray()
        BrowserPageLedger.rankedFor(query, sessionId, limit).forEach { (page, snippet) ->
            array.put(
                JSONObject()
                    .put("title", page.title.ifBlank { page.url })
                    .put("url", page.url)
                    .put("snippet", snippet)
                    .put("from_page_graph", true),
            )
        }
        return array
    }

    private fun admitSerpSearch(query: String): String? {
        val hit = when (BrowserDesk.admitWebSearchAfterWait(query)) {
            WebSearchAdmit.WaitForFirstHop -> dittoSearchLoopHit()
            WebSearchAdmit.Similar -> dittoSearchSimilarHit(BrowserDesk.lastAdmittedQuery())
            WebSearchAdmit.Budget -> dittoSearchBudgetHit(BrowserDesk.searchCount())
            WebSearchAdmit.Allow -> return null
        }
        val known = graphHits(query)
        if (known.length() > 0) {
            return ok(
                JSONObject()
                    .put("url", hit.url)
                    .put("query", query)
                    .put("title", hit.title)
                    .put("hits", known)
                    .put("from_page_graph", true)
                    .put(
                        "note",
                        "No new search was run. These pages are already in page_graph — call " +
                            "page_recall with a url and your query to read any of them in full.",
                    ),
            )
        }
        return ok(
            JSONObject()
                .put("url", hit.url)
                .put("query", query)
                .put("title", hit.title)
                .put(
                    "hits",
                    JSONArray().put(
                        JSONObject()
                            .put("title", hit.title)
                            .put("url", hit.url)
                            .put("snippet", hit.snippet),
                    ),
                )
                .put("errmsg", hit.snippet),
        )
    }

    private fun ok(body: JSONObject): String = body.put("ok", true).toString()

    private fun errorJson(
        message: String,
        code: String = "error",
        retryable: Boolean? = null,
        nextTool: String = "",
        nextArgs: JSONObject? = null,
    ): String {
        val body = JSONObject().put("ok", false).put("code", code).put("errmsg", message)
        if (retryable != null) body.put("retryable", retryable)
        if (nextTool.isNotBlank()) {
            val next = JSONObject().put("tool", nextTool)
            if (nextArgs != null) next.put("args", nextArgs)
            body.put("next", next)
        }
        return body.toString()
    }

    /** Milliseconds this tool call may still spend, capped at [cap]. */
    private fun remainingMs(cap: Long): Long {
        val deadline = deadlineAt.get() ?: return cap
        val left = deadline - System.currentTimeMillis()
        return left.coerceIn(1_000L, cap)
    }

    private fun inputRequired(body: JSONObject): String =
        body.put("ok", false).put("code", "input_required").toString()

    /**
     * Park this call while the user signs in or clears a captcha in the browser card.
     *
     * @return true when the user finished inside our remaining budget, so the caller can look at
     * the page again and carry on in the same call instead of ending the turn.
     */
    private fun waitOutUserTakeover(): Boolean {
        if (!BrowserDesk.isAwaitingUserTakeover()) return false
        val budget = remainingMs(MaxTakeoverWaitMs) - 2_000L
        if (budget < 3_000L) return false
        return BrowserDesk.awaitUserTakeover(budget)
    }
    /**
     * Serve the remainder of a spilled tool result.
     *
     * Deliberately not silent about an unknown sha. The spill store is cleared with the rest of the
     * cache, so a marker the model kept from an older turn can outlive the file it names; saying so
     * lets it re-read the page instead of concluding the content was empty.
     */
    private fun toolRecall(arguments: JSONObject): String {
        val sha = arguments.optString("sha").trim()
        val offset = arguments.optInt("offset", 0)
        val limit = arguments.optInt("limit", 0).let { if (it <= 0) 4_000 else it }
        val text = kira.ditto.data.ToolResultSpill.read(sha, offset, limit)
            ?: return JSONObject()
                .put("ok", false)
                .put("code", "not_found")
                .put("reason", "No stored output for that sha; it may have been cleared. Read the source again.")
                .toString()
        return JSONObject()
            .put("ok", true)
            .put("sha", sha)
            .put("offset", offset)
            .put("text", text)
            .toString()
    }

}

internal fun browserPageOperationCanReplay(op: String): Boolean = op in setOf(
    "snapshot", "inspect", "read", "grep", "harvest", "image", "form_list",
    "capabilities", "webmcp_list", "settle", "wait",
)
