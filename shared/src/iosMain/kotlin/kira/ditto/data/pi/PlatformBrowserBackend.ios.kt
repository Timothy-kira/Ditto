@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package kira.ditto.data.pi

import kira.ditto.data.platformRandomUuid
import kira.ditto.runtime.MultiplatformLocalRuntime
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.TimeSource
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import platform.CoreGraphics.CGRectGetHeight
import platform.CoreGraphics.CGRectGetWidth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.UIScreen
import platform.WebKit.WKSnapshotConfiguration
import platform.WebKit.WKContentMode
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration

internal actual fun createPlatformBrowserBackend(
    runtime: MultiplatformLocalRuntime,
): SharedBrowserBackend? = IosBrowserBackend()

internal class IosBrowserBackend : SharedBrowserBackend {
    private val operationMutex = Mutex()
    private var started = false
    private val tabs = linkedMapOf<String, WKWebView>()
    private val _activeTabId = MutableStateFlow(platformRandomUuid())
    internal val activeTabId: StateFlow<String> = _activeTabId.asStateFlow()

    override val viewerUrl: String = ""

    internal val webView: WKWebView
        get() = tabs.getOrPut(_activeTabId.value) { createWebView() }

    private fun createWebView(): WKWebView =
        WKWebView(
            frame = defaultBrowserViewportFrame(),
            configuration = WKWebViewConfiguration().apply {
                // iPad should expose the desktop browsing experience to responsive sites.
                if (isIPadDevice()) {
                    defaultWebpagePreferences.preferredContentMode =
                        WKContentMode.WKContentModeDesktop
                }
            },
        ).apply {
            allowsBackForwardNavigationGestures = true
        }

    override suspend fun start(): JsonObject = operationMutex.withLock {
        ensureStarted()
        statusUnlocked()
    }

    override suspend fun stop() = operationMutex.withLock {
        withContext(Dispatchers.Main) {
            tabs.values.forEach { tab ->
                tab.stopLoading()
                tab.loadHTMLString("", baseURL = null)
            }
        }
        started = false
    }

    override suspend fun status(): JsonObject = operationMutex.withLock {
        statusUnlocked()
    }

    override suspend fun execute(arguments: JsonObject): JsonObject = operationMutex.withLock {
        val action = arguments.string("action").lowercase()
        val requestedTabId = arguments.string("tab_id")
        if (
            requestedTabId.isNotBlank() &&
            requestedTabId != _activeTabId.value &&
            action !in setOf("new_tab", "close_tab", "list_tabs", "start", "status")
        ) {
            if (tabs[requestedTabId] == null) return@withLock browserError("Browser tab '$requestedTabId' was not found.")
            _activeTabId.value = requestedTabId
        }
        if (action != "stop") ensureStarted()
        val result = when (action) {
            "start", "status" -> statusUnlocked()
            "navigate", "open" -> navigate(arguments.string("url"))
            "click", "tap" -> evaluateJson(
                browserClickScript(
                    selector = arguments.string("selector"),
                    x = arguments.double("x"),
                    y = arguments.double("y"),
                ),
            ).withPageInfo()
            "hover" -> evaluateJson(
                browserHoverScript(
                    selector = arguments.string("selector"),
                    x = arguments.double("x"),
                    y = arguments.double("y"),
                ),
            ).withPageInfo()
            "type", "text" -> evaluateJson(
                browserTypeScript(arguments.string("selector"), arguments.string("text")),
            ).withPageInfo()
            "get_text" -> evaluateJson(browserGetTextScript(arguments.string("selector"))).withPageInfo()
            "scroll", "swipe" -> evaluateJson(
                browserScrollScript(
                    selector = arguments.string("selector"),
                    direction = arguments.string("direction").ifBlank { "down" },
                    amount = arguments.int("amount")?.coerceIn(1, 20_000) ?: 600,
                ),
            ).withPageInfo()
            "scroll_and_collect" -> evaluateJson(
                browserScrollAndCollectScript(
                    maxSteps = arguments.int("steps") ?: 10,
                    amount = arguments.int("amount") ?: 600,
                ),
            ).withPageInfo()
            "set_user_agent" -> {
                val userAgent = arguments.string("user_agent")
                if (userAgent.isBlank()) browserError("set_user_agent requires user_agent.")
                else {
                    withContext(Dispatchers.Main) { webView.customUserAgent = userAgent }
                    buildJsonObject {
                        put("ok", true)
                        put("stdout", "Updated browser User-Agent.")
                        put("user_agent", userAgent)
                    }
                }
            }
            "set_viewport" -> {
                val defaultSize = withContext(Dispatchers.Main) { defaultBrowserViewportSize() }
                val width = (arguments.int("width") ?: defaultSize.first).coerceIn(240, 4_096)
                val height = (arguments.int("height") ?: defaultSize.second).coerceIn(240, 4_096)
                withContext(Dispatchers.Main) {
                    webView.setFrame(
                        platform.CoreGraphics.CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()),
                    )
                }
                evaluateJson(browserSetViewportScript(width, height)).withPageInfo()
            }
            "list_tabs" -> listTabs()
            "new_tab" -> newTab(arguments.string("url"))
            "close_tab" -> closeTab(requestedTabId)
            "get_page_info" -> pageInfo()
            "execute_js", "evaluate" -> executeJavaScript(
                arguments.string("script").ifBlank { arguments.string("expression") },
            ).withPageInfo()
            "find_elements" -> {
                val selector = arguments.string("selector")
                if (selector.isBlank()) browserError("find_elements requires selector.")
                else evaluateJson(browserFindElementsScript(selector)).withPageInfo()
            }
            "get_readable" -> evaluateJson(BrowserReadableScript).withPageInfo()
            "get_backbone" -> evaluateJson(
                browserBackboneScript(arguments.int("max_depth") ?: 5),
            ).withPageInfo()
            "back" -> {
                withContext(Dispatchers.Main) { if (webView.canGoBack) webView.goBack() }
                awaitNavigation()
                pageInfo("Went back in browser.")
            }
            "forward" -> {
                withContext(Dispatchers.Main) { if (webView.canGoForward) webView.goForward() }
                awaitNavigation()
                pageInfo("Went forward in browser.")
            }
            "reload" -> {
                withContext(Dispatchers.Main) { webView.reload() }
                awaitNavigation()
                pageInfo("Reloaded browser.")
            }
            "screenshot" -> screenshot()
            "wait_for_dom_stable" -> {
                waitForDomStable(arguments.int("timeout")).withPageInfo()
            }
            "stop" -> {
                withContext(Dispatchers.Main) {
                    tabs.values.forEach { tab ->
                        tab.stopLoading()
                        tab.loadHTMLString("", baseURL = null)
                    }
                }
                started = false
                buildJsonObject { put("ok", true); put("stdout", "Browser stopped.") }
            }
            else -> browserError("Unsupported browser action '$action'.")
        }
        if (action in IosBrowserCaptureActions && result["error"] == null) {
            val capture = screenshot()
            JsonObject(capture + result)
        } else {
            result
        }
    }

    private suspend fun ensureStarted() {
        if (started) return
        withContext(Dispatchers.Main) {
            webView.loadHTMLString(
                "<html><head><meta name=\"viewport\" content=\"width=device-width\"></head><body></body></html>",
                baseURL = null,
            )
        }
        started = true
        awaitNavigation()
    }

    private suspend fun newTab(rawUrl: String): JsonObject {
        val tabId = platformRandomUuid()
        val tab = withContext(Dispatchers.Main) { createWebView() }
        tabs[tabId] = tab
        _activeTabId.value = tabId
        val url = rawUrl.trim().takeIf(String::isNotBlank)?.let(::normalizeUrl)
        if (rawUrl.isNotBlank() && url == null) {
            tabs.remove(tabId)
            _activeTabId.value = tabs.keys.lastOrNull() ?: platformRandomUuid()
            return browserError("Unsupported URL.")
        }
        withContext(Dispatchers.Main) {
            if (url != null) tab.loadRequest(NSURLRequest(url))
            else tab.loadHTMLString("<html><body></body></html>", baseURL = null)
        }
        awaitNavigation()
        return pageInfo("Opened a new browser tab.").withValue("tab_id", JsonPrimitive(tabId))
            .withValue("active_tab_id", JsonPrimitive(tabId))
    }

    private suspend fun closeTab(requestedTabId: String): JsonObject {
        val tabId = requestedTabId.ifBlank { _activeTabId.value }
        val removed = tabs.remove(tabId) ?: return browserError("Browser tab '$tabId' was not found.")
        withContext(Dispatchers.Main) { removed.stopLoading() }
        val nextId = tabs.keys.lastOrNull() ?: platformRandomUuid().also { id ->
            tabs[id] = withContext(Dispatchers.Main) { createWebView() }
        }
        _activeTabId.value = nextId
        ensureStarted()
        return pageInfo("Closed browser tab.")
            .withValue("closed_tab_id", JsonPrimitive(tabId))
            .withValue("active_tab_id", JsonPrimitive(nextId))
    }

    private suspend fun listTabs(): JsonObject = withContext(Dispatchers.Main) {
        buildJsonObject {
            put("ok", true)
            put("active_tab_id", _activeTabId.value)
            put("tabs", kotlinx.serialization.json.buildJsonArray {
                tabs.forEach { (id, tab) ->
                    add(buildJsonObject {
                        put("id", id)
                        put("type", "page")
                        put("title", tab.title.orEmpty())
                        put("url", tab.URL?.absoluteString.orEmpty())
                    })
                }
            })
        }
    }

    private suspend fun statusUnlocked(): JsonObject {
        if (!started) return buildJsonObject {
            put("ok", true)
            put("started", false)
            put("stdout", "Browser is stopped.")
        }
        return pageInfo("Browser is running.").withValue("started", JsonPrimitive(true))
    }

    private suspend fun navigate(rawUrl: String): JsonObject {
        val url = normalizeUrl(rawUrl) ?: return browserError("Missing or unsupported URL.")
        withContext(Dispatchers.Main) {
            webView.loadRequest(NSURLRequest(url))
        }
        awaitNavigation()
        return pageInfo("Opened ${url.absoluteString.orEmpty()}")
    }

    private fun normalizeUrl(rawUrl: String): NSURL? {
        val value = rawUrl.trim()
        if (value.isBlank()) return null
        val normalized = if (value.contains("://") || value.startsWith("about:")) value else "https://$value"
        val url = NSURL.URLWithString(normalized) ?: return null
        return if (url.scheme?.lowercase() in setOf("http", "https", "about")) url else null
    }

    private suspend fun awaitNavigation() {
        delay(80)
        runCatching {
            withTimeout(30_000) {
                while (withContext(Dispatchers.Main) { webView.loading }) delay(75)
            }
        }
    }

    private suspend fun pageInfo(stdout: String = ""): JsonObject {
        val info = evaluateJson(BrowserPageInfoScript)
        val viewport = info["viewport"]?.let { value ->
            runCatching { value.jsonObject }.getOrNull()
        }
        val enriched = JsonObject(
            info + buildMap {
                viewport?.get("width")?.let { put("width", it) }
                viewport?.get("height")?.let { put("height", it) }
            },
        )
        return if (stdout.isBlank()) enriched else enriched.withValue("stdout", JsonPrimitive(stdout))
    }

    private suspend fun JsonObject.withPageInfo(): JsonObject {
        val info = pageInfo()
        return JsonObject(info + this)
    }

    private suspend fun executeJavaScript(script: String): JsonObject {
        if (script.isBlank()) return browserError("execute_js requires script.")
        val wrapped = """
            (() => {
              try {
                const value = (0, eval)(${JsonPrimitive(script)});
                return JSON.stringify({ok:true,result:value === undefined ? null : value});
              } catch (error) {
                return JSON.stringify({ok:false,error:String(error?.message || error)});
              }
            })()
        """.trimIndent()
        return evaluateJson(wrapped)
    }

    private suspend fun waitForDomStable(timeoutMillis: Int?): JsonObject {
        val timeout = (timeoutMillis ?: 5_000).coerceIn(1_000, 60_000)
        val started = TimeSource.Monotonic.markNow()
        evaluate(
            """
                (() => {
                  window.__aetherDomMutationCount = 0;
                  window.__aetherDomObserver?.disconnect?.();
                  window.__aetherDomObserver = new MutationObserver((mutations) => {
                    window.__aetherDomMutationCount += mutations.length;
                  });
                  window.__aetherDomObserver.observe(document.documentElement || document, {
                    childList:true,
                    subtree:true,
                    attributes:true,
                    characterData:true
                  });
                  return true;
                })()
            """.trimIndent(),
        )
        var quietSamples = 0
        var totalMutations = 0
        var lastSample = JsonObject(emptyMap())
        try {
            while (started.elapsedNow().inWholeMilliseconds < timeout) {
                delay(250)
                lastSample = evaluateJson(
                    """
                        (() => {
                          const changes = window.__aetherDomMutationCount || 0;
                          window.__aetherDomMutationCount = 0;
                          return JSON.stringify({
                            ok:true,
                            changes,
                            ready_state:document.readyState,
                            element_count:document.querySelectorAll('*').length,
                            url:location.href
                          });
                        })()
                    """.trimIndent(),
                )
                val changes = lastSample.int("changes") ?: 0
                totalMutations += changes
                quietSamples = if (
                    changes == 0 && lastSample.string("ready_state") != "loading"
                ) {
                    quietSamples + 1
                } else {
                    0
                }
                if (quietSamples >= 2) {
                    return JsonObject(
                        lastSample + mapOf(
                            "stable" to JsonPrimitive(true),
                            "timed_out" to JsonPrimitive(false),
                            "elapsed_ms" to JsonPrimitive(started.elapsedNow().inWholeMilliseconds),
                            "mutation_count" to JsonPrimitive(totalMutations),
                        ),
                    )
                }
            }
            return JsonObject(
                lastSample + mapOf(
                    "ok" to JsonPrimitive(true),
                    "stable" to JsonPrimitive(false),
                    "timed_out" to JsonPrimitive(true),
                    "elapsed_ms" to JsonPrimitive(started.elapsedNow().inWholeMilliseconds),
                    "mutation_count" to JsonPrimitive(totalMutations),
                ),
            )
        } finally {
            runCatching {
                evaluate(
                    "window.__aetherDomObserver?.disconnect?.(); window.__aetherDomObserver = null; true;",
                )
            }
        }
    }

    private suspend fun evaluateJson(script: String): JsonObject {
        val raw = evaluate(script)
        val text = raw as? String
            ?: return buildJsonObject { put("ok", true); put("result", raw.toJsonPrimitive()) }
        return runCatching { Json.parseToJsonElement(text).jsonObject }.getOrElse {
            buildJsonObject { put("ok", true); put("result", text) }
        }
    }

    private suspend fun evaluate(script: String): Any? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            webView.evaluateJavaScript(script) { result, error ->
                when {
                    error != null -> continuation.resumeWithException(error.toException())
                    continuation.isActive -> continuation.resume(result)
                }
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun screenshot(): JsonObject = withContext(Dispatchers.Main) {
        val image = suspendCancellableCoroutine { continuation ->
            webView.takeSnapshotWithConfiguration(WKSnapshotConfiguration()) { result, error ->
                when {
                    error != null -> continuation.resumeWithException(error.toException())
                    result != null && continuation.isActive -> continuation.resume(result)
                    continuation.isActive -> continuation.resumeWithException(IllegalStateException("Browser screenshot was empty."))
                }
            }
        }
        val data = UIImagePNGRepresentation(image)
            ?: return@withContext browserError("Browser screenshot encoding failed.")
        val bytes = data.bytes?.reinterpret<ByteVar>()?.readBytes(data.length.toInt())
            ?: return@withContext browserError("Browser screenshot bytes were unavailable.")
        buildJsonObject {
            put("ok", true)
            put("stdout", "Captured browser screenshot.")
            put("screenshot_base64", Base64.encode(bytes))
            put("screenshot_mime_type", "image/png")
            put("url", webView.URL?.absoluteString.orEmpty())
            put("title", webView.title.orEmpty())
            put("width", CGRectGetWidth(webView.frame).toInt().coerceAtLeast(1))
            put("height", CGRectGetHeight(webView.frame).toInt().coerceAtLeast(1))
        }
    }
}

private fun defaultBrowserViewportSize(): Pair<Int, Int> {
    val bounds = UIScreen.mainScreen.bounds
    return CGRectGetWidth(bounds).toInt().coerceAtLeast(240) to
        CGRectGetHeight(bounds).toInt().coerceAtLeast(240)
}

private fun defaultBrowserViewportFrame() = defaultBrowserViewportSize().let { (width, height) ->
    platform.CoreGraphics.CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble())
}

private fun isIPadDevice(): Boolean =
    platform.UIKit.UIDevice.currentDevice.model.equals("iPad", ignoreCase = true)

private val IosBrowserCaptureActions = setOf(
    "start",
    "navigate",
    "open",
    "click",
    "tap",
    "hover",
    "type",
    "text",
    "scroll",
    "swipe",
    "scroll_and_collect",
    "set_viewport",
    "new_tab",
    "back",
    "forward",
    "reload",
    "execute_js",
    "evaluate",
)

private fun browserError(message: String): JsonObject = buildJsonObject {
    put("ok", false)
    put("error", message)
    put("stdout", message)
}

private fun JsonObject.withValue(key: String, value: JsonPrimitive): JsonObject = JsonObject(this + (key to value))

private fun Any?.toJsonPrimitive() = when (this) {
    null -> JsonNull
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    else -> JsonPrimitive(toString())
}

private fun NSError.toException(): IllegalStateException =
    IllegalStateException(localizedDescription.ifBlank { "WebKit operation failed." })

private fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
private fun JsonObject.double(key: String): Double? = this[key]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
