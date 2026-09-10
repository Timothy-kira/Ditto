package kira.ditto.data.pi

import kira.ditto.data.platformCurrentTimeMillis
import kira.ditto.data.platformRandomUuid
import kira.ditto.runtime.MultiplatformLocalRuntime
import kira.ditto.runtime.RuntimeProcess
import kira.ditto.runtime.RuntimeProcessSignal
import kira.ditto.runtime.RuntimeProcessSpec
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlin.io.encoding.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class SharedBrowserDisplayState(
    val isActive: Boolean = false,
    val width: Int = 390,
    val height: Int = 844,
    val screenshotBase64: String = "",
    val screenshotMimeType: String = "image/png",
    val previewPath: String = "",
    val url: String = "",
    val title: String = "",
    val cursorX: Int? = null,
    val cursorY: Int? = null,
    val cursorAnimationDurationMillis: Int = 220,
    val status: String = "Browser is stopped.",
    val lastUpdatedMillis: Long = 0L,
)

class SharedChromeManager(
    private val runtime: MultiplatformLocalRuntime,
) : SharedHostToolExecutor {
    var enabled: Boolean = false
    internal val platformBackend: SharedBrowserBackend? = createPlatformBrowserBackend(runtime)
    private var started = false
    private var chromeClient: ChromeCdpClient? = null
    private val _displayState = MutableStateFlow(SharedBrowserDisplayState())
    val displayState: StateFlow<SharedBrowserDisplayState> = _displayState.asStateFlow()
    val viewerUrl: String
        get() = platformBackend?.viewerUrl
            ?: "http://localhost:6080/vnc.html?autoconnect=1&resize=scale&reconnect=1"

    override val definitions: JsonArray
        get() = sharedBrowserToolDefinitions(enabled)

    @Throws(Exception::class)
    suspend fun start(): JsonObject {
        platformBackend?.let { backend ->
            val result = backend.start()
            started = true
            return processResponse("start", result)
        }
        runtime.initialize()
        if (started) return status()
        val setup = RuntimeHostToolExecutor(runtime).execute(
            "bash",
            buildJsonObject {
                put("working_directory", runtime.workspaceRoot)
                put("command", ChromeStartCommand)
            },
        )
        check(!setup.isError) { setup.outputJson }
        val client = ChromeCdpClient(runtime)
        try {
            client.start()
        } catch (error: Throwable) {
            client.stop()
            RuntimeHostToolExecutor(runtime).execute(
                "bash",
                buildJsonObject { put("command", ChromeStopCommand) },
            )
            throw error
        }
        chromeClient = client
        started = true
        return processResponse("start", status())
    }

    suspend fun stop() {
        platformBackend?.let { backend ->
            backend.stop()
            started = false
            updateStoppedDisplayState()
            return
        }
        val client = chromeClient
        chromeClient = null
        if (client != null) runCatching { client.stop() }
        RuntimeHostToolExecutor(runtime).execute(
            "bash",
            buildJsonObject { put("command", ChromeStopCommand) },
        )
        started = false
        updateStoppedDisplayState()
    }

    suspend fun status(): JsonObject {
        platformBackend?.let { return it.status() }
        if (!started || chromeClient == null) return buildJsonObject { put("started", false) }
        return runCatching {
            control(buildJsonObject { put("action", "status") })
        }.getOrElse { buildJsonObject { put("started", false); put("error", it.message.orEmpty()) } }
    }

    @Throws(Exception::class)
    suspend fun executeJson(requestJson: String): String {
        val request = Json.parseToJsonElement(requestJson).jsonObject
        platformBackend?.let { backend ->
            return executePlatformAction(backend, request).toString()
        }
        if (request.string("action").equals("stop", ignoreCase = true)) {
            stop()
            return buildJsonObject { put("ok", true); put("stdout", "Browser stopped.") }.toString()
        }
        start()
        return executeCdpAction(request).toString()
    }

    override suspend fun execute(name: String, arguments: JsonObject): SharedHostToolResult {
        if (name != BrowserToolName && name != LegacyChromeToolName) {
            return SharedHostToolResult("{\"error\":\"Unknown browser tool\"}", true)
        }
        return runCatching {
            val response = platformBackend?.let { executePlatformAction(it, arguments) }
                ?: if (arguments.string("action").equals("stop", ignoreCase = true)) {
                    stop()
                    buildJsonObject { put("ok", true); put("stdout", "Browser stopped.") }
                } else {
                    start()
                    executeCdpAction(arguments)
                }
            SharedHostToolResult(response.toString(), response["error"] != null)
        }.getOrElse { error ->
            SharedHostToolResult(buildJsonObject { put("error", error.message ?: "Browser failed") }.toString(), true)
        }
    }

    private suspend fun control(request: JsonObject): JsonObject =
        checkNotNull(chromeClient) { "Chromium is not running." }.execute(request)

    private suspend fun executeCdpAction(arguments: JsonObject): JsonObject {
        val action = arguments.string("action").lowercase()
        var response = control(arguments)
        val succeeded = response["error"] == null &&
            response["ok"]?.jsonPrimitive?.booleanOrNull != false
        if (
            succeeded &&
            action in SharedBrowserVisualActions &&
            response.string("screenshot_base64").isBlank()
        ) {
            val snapshot = control(buildJsonObject { put("action", "screenshot") })
            response = JsonObject(
                response + snapshot.filterKeys { key -> key in SharedBrowserSnapshotKeys },
            )
        }
        return processResponse(action, response)
    }

    private suspend fun executePlatformAction(
        backend: SharedBrowserBackend,
        arguments: JsonObject,
    ): JsonObject {
        val action = arguments.string("action").lowercase()
        var response = backend.execute(arguments)
        val succeeded = response["error"] == null &&
            response["ok"]?.jsonPrimitive?.booleanOrNull != false
        if (
            succeeded &&
            action in SharedBrowserVisualActions &&
            response.string("screenshot_base64").isBlank()
        ) {
            val snapshot = backend.execute(buildJsonObject { put("action", "screenshot") })
            response = JsonObject(
                response + snapshot.filterKeys { key ->
                    key in SharedBrowserSnapshotKeys
                },
            )
        }
        return processResponse(action, response)
    }

    private suspend fun processResponse(action: String, response: JsonObject): JsonObject {
        val normalizedAction = action.lowercase()
        val encoded = response.string("screenshot_base64")
        var previewPath = response.string("preview_path")
        if (encoded.isNotBlank() && previewPath.isBlank()) {
            previewPath = runCatching {
                val root = "${runtime.workspaceRoot}/.aether/browser-previews"
                runtime.fileSystem.createDirectories(root)
                val extension = if (response.string("screenshot_mime_type").contains("jpeg")) "jpg" else "png"
                val path = "$root/browser-${platformCurrentTimeMillis()}-${platformRandomUuid()}.$extension"
                runtime.fileSystem.write(path, Base64.decode(encoded))
                path
            }.getOrDefault("")
        }
        val enriched = if (previewPath.isNotBlank() && response.string("preview_path").isBlank()) {
            JsonObject(response + ("preview_path" to JsonPrimitive(previewPath)))
        } else {
            response
        }
        val succeeded = enriched["error"] == null &&
            enriched["ok"]?.jsonPrimitive?.booleanOrNull != false
        val previous = _displayState.value
        _displayState.value = previous.copy(
            isActive = when {
                normalizedAction == "stop" -> false
                succeeded -> true
                else -> previous.isActive
            },
            width = enriched.int("width")?.coerceAtLeast(1) ?: previous.width,
            height = enriched.int("height")?.coerceAtLeast(1) ?: previous.height,
            screenshotBase64 = encoded.ifBlank { previous.screenshotBase64 },
            screenshotMimeType = enriched.string("screenshot_mime_type")
                .ifBlank { previous.screenshotMimeType },
            previewPath = previewPath.ifBlank { previous.previewPath },
            url = enriched.string("url").ifBlank { previous.url },
            title = enriched.string("title").ifBlank { previous.title },
            cursorX = enriched.int("cursor_x") ?: previous.cursorX,
            cursorY = enriched.int("cursor_y") ?: previous.cursorY,
            cursorAnimationDurationMillis = enriched.int("cursor_animation_duration_ms")
                ?.coerceIn(80, 1_200)
                ?: previous.cursorAnimationDurationMillis,
            status = enriched.string("stdout").ifBlank {
                enriched.string("error").ifBlank { previous.status }
            },
            lastUpdatedMillis = platformCurrentTimeMillis(),
        )
        return enriched
    }

    private fun updateStoppedDisplayState() {
        _displayState.value = _displayState.value.copy(
            isActive = false,
            status = "Browser is stopped.",
            lastUpdatedMillis = platformCurrentTimeMillis(),
        )
    }
}

private val SharedBrowserVisualActions = setOf(
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
    "back",
    "forward",
    "reload",
    "set_viewport",
    "new_tab",
    "close_tab",
)

private val SharedBrowserSnapshotKeys = setOf(
    "screenshot_base64",
    "screenshot_mime_type",
    "width",
    "height",
    "url",
    "title",
)

internal interface SharedBrowserBackend {
    val viewerUrl: String
    suspend fun start(): JsonObject
    suspend fun stop()
    suspend fun status(): JsonObject
    suspend fun execute(arguments: JsonObject): JsonObject
}

internal expect fun createPlatformBrowserBackend(
    runtime: MultiplatformLocalRuntime,
): SharedBrowserBackend?

class SharedCompositeHostTools(
    private val delegates: List<SharedHostToolExecutor>,
) : SharedSessionAwareHostToolExecutor {
    override val definitions: JsonArray
        get() = JsonArray(delegates.flatMap { it.definitions })

    override fun definitions(sessionId: String): JsonArray = JsonArray(
        delegates.flatMap { delegate ->
            if (delegate is SharedSessionAwareHostToolExecutor) {
                delegate.definitions(sessionId)
            } else {
                delegate.definitions
            }
        },
    )

    override suspend fun execute(name: String, arguments: JsonObject): SharedHostToolResult {
        val sessionId = arguments.sharedHostToolSessionId()
        val delegate = delegates.firstOrNull { candidate ->
            val definitions = if (candidate is SharedSessionAwareHostToolExecutor) {
                candidate.definitions(sessionId)
            } else {
                candidate.definitions
            }
            definitions.any {
                (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
                    ?.equals(name, ignoreCase = true) == true
            }
        } ?: return SharedHostToolResult(
            buildJsonObject {
                put("ok", false)
                put("error", "Unknown tool '$name'.")
            }.toString(),
            true,
        )
        return delegate.execute(name, arguments)
    }
}

private val ChromeStartCommand = """
set -eu
if ! command -v chromium-browser >/dev/null 2>&1 || ! command -v Xvnc >/dev/null 2>&1 || \
   [ ! -f /usr/lib/chromium/libvk_swiftshader.so ]; then
  apk add --no-cache chromium chromium-swiftshader tigervnc openbox novnc websockify >/tmp/aether-chrome-install.log 2>&1
fi
LLVM_ROOT=/opt/aether/chromium-deps
if [ ! -f "${'$'}LLVM_ROOT/usr/lib/libLLVM.so.19.1" ] || \
   [ ! -f "${'$'}LLVM_ROOT/usr/lib/xorg/modules/dri/swrast_dri.so" ] || \
   ! find "${'$'}LLVM_ROOT/usr/share/vulkan/icd.d" -name '*lvp*.json' -print -quit 2>/dev/null | grep -q .; then
  apk --root "${'$'}LLVM_ROOT" --keys-dir /etc/apk/keys --repositories-file /etc/apk/repositories \
    --no-cache --no-scripts --initdb add llvm19-libs mesa-dri-gallium mesa-vulkan-swrast >/tmp/aether-llvm-install.log 2>&1
fi
ln -sf "${'$'}LLVM_ROOT/usr/lib/libvulkan_lvp.so" /usr/lib/libvulkan_lvp.so
export LD_LIBRARY_PATH="${'$'}LLVM_ROOT/usr/lib${'$'}{LD_LIBRARY_PATH:+:${'$'}LD_LIBRARY_PATH}"
CHROME=$(command -v chromium-browser || command -v chromium)
chmod 0755 /usr/lib/chromium/chromium /usr/lib/chromium/chromium-launcher.sh /usr/lib/chromium/chrome-sandbox >/dev/null 2>&1 || true
if [ -x /usr/lib/chromium/chromium ]; then CHROME=/usr/lib/chromium/chromium; fi
"${'$'}CHROME" --version >/tmp/aether-chromium-version.log 2>&1 || {
  cat /tmp/aether-chromium-version.log >&2
  exit 1
}
stop_pid() {
  if [ -s "${'$'}1" ]; then
    kill "${'$'}(cat "${'$'}1")" >/dev/null 2>&1 || true
    rm -f "${'$'}1"
  fi
}
stop_pid /tmp/aether-chromium.pid
stop_pid /tmp/aether-novnc.pid
stop_pid /tmp/aether-openbox.pid
stop_pid /tmp/aether-xvnc.pid
pkill -x chromium >/dev/null 2>&1 || true
pkill -x Xvnc >/dev/null 2>&1 || true
pkill -x openbox >/dev/null 2>&1 || true
pkill -x websockify >/dev/null 2>&1 || true
rm -f /tmp/.X99-lock /tmp/.X11-unix/X99
nohup Xvnc :99 -geometry 1280x800 -depth 24 -SecurityTypes None -localhost \
  -rfbport 5900 -AlwaysShared -extension MIT-SHM -nolock -ac >/tmp/aether-xvnc.log 2>&1 &
echo ${'$'}! >/tmp/aether-xvnc.pid
for i in ${'$'}(seq 1 30); do
  if [ -S /tmp/.X11-unix/X99 ] || [ -e /tmp/.X11-unix/X99 ]; then break; fi
  sleep 1
done
if [ ! -S /tmp/.X11-unix/X99 ] && [ ! -e /tmp/.X11-unix/X99 ]; then
  cat /tmp/aether-xvnc.log >&2
  exit 1
fi
nohup env DISPLAY=:99 openbox >/tmp/aether-openbox.log 2>&1 &
echo ${'$'}! >/tmp/aether-openbox.pid
NOVNC=$(find /usr/share -path '*/novnc/vnc.html' -print -quit | xargs dirname)
nohup websockify --web="${'$'}NOVNC" 6080 127.0.0.1:5900 >/tmp/aether-novnc.log 2>&1 &
echo ${'$'}! >/tmp/aether-novnc.pid
mkdir -p /root/.aether/chrome-profile
for i in $(seq 1 60); do wget -qO- http://127.0.0.1:6080/vnc.html >/dev/null 2>&1 && exit 0; sleep 1; done
cat /tmp/aether-novnc.log >&2
cat /tmp/aether-xvnc.log >&2
exit 1
""".trimIndent()

private val ChromeStopCommand = """
for pid_file in /tmp/aether-novnc.pid /tmp/aether-openbox.pid /tmp/aether-xvnc.pid; do
  if [ -s "${'$'}pid_file" ]; then
    kill "${'$'}(cat "${'$'}pid_file")" >/dev/null 2>&1 || true
    rm -f "${'$'}pid_file"
  fi
done
""".trimIndent()

private class ChromeCdpClient(
    private val runtime: MultiplatformLocalRuntime,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val httpClient = createChromeHttpClient()
    private val stateMutex = Mutex()
    private val writeMutex = Mutex()
    private val pending = mutableMapOf<Int, CompletableDeferred<JsonObject>>()
    private var nextId = 0
    private var process: RuntimeProcess? = null
    private var webSocket: DefaultClientWebSocketSession? = null
    private var readerJob: Job? = null
    private var outputJob: Job? = null
    private var errorJob: Job? = null
    private var exitJob: Job? = null
    private var sessionId = ""
    private var activeTargetId = ""
    private var recentErrors = ""

    suspend fun start() {
        check(process == null) { "Chromium is already running." }
        val activeProcess = runtime.startProcess(
            RuntimeProcessSpec(
                executable = ChromeExecutable,
                arguments = ChromeArguments,
                environment = mapOf(
                    "DISPLAY" to ":99",
                    "HOME" to runtime.homeDirectory,
                    "ISH_V8_NO_MUTE" to "1",
                    "LD_LIBRARY_PATH" to "/opt/aether/chromium-deps/usr/lib",
                    "LIBGL_DRIVERS_PATH" to "/opt/aether/chromium-deps/usr/lib/xorg/modules/dri",
                    "LIBGL_ALWAYS_SOFTWARE" to "true",
                    "MESA_LOADER_DRIVER_OVERRIDE" to "swrast",
                    "GALLIUM_DRIVER" to "llvmpipe",
                    "VK_ICD_FILENAMES" to "/usr/lib/chromium/vk_swiftshader_icd.json",
                ),
                workingDirectory = runtime.homeDirectory,
            )
        )
        process = activeProcess
        outputJob = scope.launch { activeProcess.stdout.collect {} }
        errorJob = scope.launch {
            activeProcess.stderr.collect { bytes ->
                val appended = recentErrors + bytes.decodeToString()
                recentErrors = appended.takeLast(32_768)
            }
        }
        exitJob = scope.launch {
            val exit = activeProcess.awaitExit()
            val message = "Chromium exited with code ${exit.exitCode}" +
                if (exit.signalNumber == 0) "." else " (signal ${exit.signalNumber})."
            failPending(message)
        }
        val socket = httpClient.webSocketSession(awaitBrowserWebSocketUrl())
        webSocket = socket
        readerJob = scope.launch { readFrames(socket) }
        attachToPage()
    }

    suspend fun stop() {
        val activeProcess = process
        process = null
        sessionId = ""
        activeTargetId = ""
        failPending("Chromium stopped.")
        readerJob?.cancel()
        outputJob?.cancel()
        errorJob?.cancel()
        exitJob?.cancel()
        readerJob = null
        outputJob = null
        errorJob = null
        exitJob = null
        webSocket?.close(CloseReason(CloseReason.Codes.NORMAL, "Chromium stopped"))
        webSocket = null
        if (activeProcess != null) {
            activeProcess.closeStdin()
            activeProcess.signal(RuntimeProcessSignal.Terminate)
            if (withTimeoutOrNull(2_000) { activeProcess.awaitExit() } == null) {
                activeProcess.signal(RuntimeProcessSignal.Kill)
                withTimeoutOrNull(2_000) { activeProcess.awaitExit() }
            }
        }
        httpClient.close()
        scope.cancel()
    }

    suspend fun execute(input: JsonObject): JsonObject {
        val action = input.string("action").lowercase()
        val requestedTabId = input.string("tab_id")
        if (
            requestedTabId.isNotBlank() &&
            requestedTabId != activeTargetId &&
            action !in setOf("new_tab", "close_tab", "list_tabs", "start", "status")
        ) {
            attachToTarget(requestedTabId)
        }
        return when (action) {
        "start", "status" -> buildJsonObject {
            put("started", true)
            put("active_tab_id", activeTargetId)
            put("pages", pages())
        }
        "list_tabs" -> buildJsonObject {
            put("ok", true)
            put("active_tab_id", activeTargetId)
            put("tabs", pages())
        }
        "new_tab" -> {
            val url = input.string("url").ifBlank { "about:blank" }
            val targetId = send("Target.createTarget", buildJsonObject { put("url", url) }).string("targetId")
            check(targetId.isNotBlank()) { "Chromium did not create a page target." }
            attachToTarget(targetId)
            buildJsonObject {
                put("ok", true)
                put("tab_id", targetId)
                put("active_tab_id", targetId)
                put("url", url)
                put("stdout", "Opened a new browser tab.")
            }
        }
        "close_tab" -> {
            val targetId = requestedTabId.ifBlank { activeTargetId }
            if (targetId.isBlank()) {
                buildJsonObject { put("ok", false); put("error", "No browser tab is active.") }
            } else {
                send("Target.closeTarget", buildJsonObject { put("targetId", targetId) })
                if (targetId == activeTargetId) {
                    sessionId = ""
                    activeTargetId = ""
                    val remaining = pages().firstOrNull()?.jsonObject?.string("id")
                    if (remaining != null) attachToTarget(remaining) else attachToPage()
                }
                buildJsonObject {
                    put("ok", true)
                    put("closed_tab_id", targetId)
                    put("active_tab_id", activeTargetId)
                    put("stdout", "Closed browser tab.")
                }
            }
        }
        "navigate" -> {
            val url = input.string("url").ifBlank { "about:blank" }
            cdp("Page.navigate", buildJsonObject { put("url", url) })
            buildJsonObject { put("ok", true); put("url", url) }
        }
        "reload" -> cdp("Page.reload")
        "back" -> evaluate("history.back()")
        "forward" -> evaluate("history.forward()")
        "evaluate", "execute_js" -> evaluateJson(
            input.string("script").ifBlank { input.string("expression") },
        )
        "click", "tap" -> evaluateJson(
            browserClickScript(
                selector = input.string("selector"),
                x = input.optionalNumber("x"),
                y = input.optionalNumber("y"),
            ),
        )
        "hover" -> evaluateJson(
            browserHoverScript(
                selector = input.string("selector"),
                x = input.optionalNumber("x"),
                y = input.optionalNumber("y"),
            ),
        )
        "type", "text" -> evaluateJson(
            browserTypeScript(input.string("selector"), input.string("text")),
        )
        "get_text" -> evaluateJson(browserGetTextScript(input.string("selector")))
        "scroll", "swipe" -> evaluateJson(
            browserScrollScript(
                selector = input.string("selector"),
                direction = input.string("direction").ifBlank { "down" },
                amount = input.int("amount")?.coerceIn(1, 20_000) ?: 600,
            ),
        )
        "scroll_and_collect" -> evaluateJson(
            browserScrollAndCollectScript(
                maxSteps = input.int("steps") ?: 10,
                amount = input.int("amount") ?: 600,
            ),
        )
        "set_user_agent" -> {
            val userAgent = input.string("user_agent")
            if (userAgent.isBlank()) {
                buildJsonObject { put("ok", false); put("error", "set_user_agent requires user_agent.") }
            } else {
                cdp("Network.setUserAgentOverride", buildJsonObject { put("userAgent", userAgent) })
                buildJsonObject { put("ok", true); put("stdout", "Updated browser User-Agent."); put("user_agent", userAgent) }
            }
        }
        "set_viewport" -> {
            val width = (input.int("width") ?: 390).coerceIn(240, 4_096)
            val height = (input.int("height") ?: 844).coerceIn(240, 4_096)
            cdp(
                "Emulation.setDeviceMetricsOverride",
                buildJsonObject {
                    put("width", width)
                    put("height", height)
                    put("deviceScaleFactor", 1)
                    put("mobile", false)
                },
            )
            evaluateJson(browserSetViewportScript(width, height))
        }
        "get_page_info" -> evaluateJson(BrowserPageInfoScript)
        "find_elements" -> {
            val selector = input.string("selector")
            if (selector.isBlank()) error("find_elements requires selector.")
            evaluateJson(browserFindElementsScript(selector))
        }
        "get_readable" -> evaluateJson(BrowserReadableScript)
        "get_backbone" -> evaluateJson(browserBackboneScript(input.int("max_depth") ?: 5))
        "wait_for_dom_stable" -> {
            val timeout = (input.int("timeout") ?: 5_000).coerceIn(1_000, 60_000)
            evaluateJson(browserWaitForDomStableScript(timeout))
        }
        "screenshot" -> captureScreenshot(input.string("path"))
        else -> error("Unknown browser action: ${input.string("action")}")
        }
    }

    private suspend fun attachToPage() {
        val targets = send("Target.getTargets")
        val existing = targets["targetInfos"]?.jsonArray
            ?.map { it.jsonObject }
            ?.firstOrNull { it.string("type") == "page" }
        val targetId = existing?.string("targetId")?.takeIf(String::isNotBlank)
            ?: send("Target.createTarget", buildJsonObject { put("url", "about:blank") }).string("targetId")
        check(targetId.isNotBlank()) { "Chromium did not create a page target." }
        attachToTarget(targetId)
    }

    private suspend fun attachToTarget(targetId: String) {
        if (targetId == activeTargetId && sessionId.isNotBlank()) return
        val targetExists = pages().any { it.jsonObject.string("id") == targetId }
        check(targetExists) { "Browser tab '$targetId' was not found." }
        sessionId.takeIf(String::isNotBlank)?.let { existingSession ->
            runCatching {
                send("Target.detachFromTarget", buildJsonObject { put("sessionId", existingSession) })
            }
        }
        sessionId = send(
            "Target.attachToTarget",
            buildJsonObject { put("targetId", targetId); put("flatten", true) },
        ).string("sessionId")
        check(sessionId.isNotBlank()) { "Chromium did not create a CDP session." }
        activeTargetId = targetId
        cdp("Page.enable")
    }

    private suspend fun pages(): JsonArray {
        val targets = send("Target.getTargets")
        return buildJsonArray {
            targets["targetInfos"]?.jsonArray?.forEach { element ->
                val target = element.jsonObject
                if (target.string("type") == "page") add(buildJsonObject {
                    put("id", target.string("targetId"))
                    put("type", "page")
                    put("title", target.string("title"))
                    put("url", target.string("url"))
                })
            }
        }
    }

    private suspend fun evaluate(expression: String): JsonObject = cdp(
        "Runtime.evaluate",
        buildJsonObject {
            put("expression", expression)
            put("returnByValue", true)
            put("awaitPromise", true)
        },
    )

    private suspend fun evaluateJson(expression: String): JsonObject {
        if (expression.isBlank()) return buildJsonObject {
            put("ok", false)
            put("error", "Missing JavaScript source.")
        }
        val response = evaluate(expression)
        val remote = response["result"] as? JsonObject ?: JsonObject(emptyMap())
        val exception = response["exceptionDetails"] as? JsonObject
        if (exception != null) {
            return buildJsonObject {
                put("ok", false)
                put("error", exception.string("text").ifBlank { "JavaScript evaluation failed." })
            }
        }
        val value = remote["value"] ?: kotlinx.serialization.json.JsonNull
        val parsedObject = (value as? JsonPrimitive)
            ?.contentOrNull
            ?.let { encoded -> runCatching { Json.parseToJsonElement(encoded) as? JsonObject }.getOrNull() }
        return parsedObject ?: buildJsonObject {
            put("ok", true)
            put("result", value)
        }
    }

    private suspend fun captureScreenshot(requestedPath: String): JsonObject {
        val response = cdp("Page.captureScreenshot", buildJsonObject { put("format", "png") })
        val encoded = response.string("data")
        val bytes = Base64.decode(encoded)
        val path = requestedPath.ifBlank { "${runtime.workspaceRoot}/chrome-screenshot.png" }
        runtime.fileSystem.write(path, bytes)
        return buildJsonObject {
            put("ok", true)
            put("path", path)
            put("size", bytes.size)
            put("screenshot_base64", encoded)
            put("screenshot_mime_type", "image/png")
            put("stdout", "Captured browser screenshot.")
        }
    }

    private suspend fun cdp(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject {
        check(sessionId.isNotBlank()) { "Chromium CDP session is not attached." }
        return send(method, params, sessionId)
    }

    private suspend fun send(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
        session: String = "",
    ): JsonObject {
        checkNotNull(process) { "Chromium is not running." }
        val socket = checkNotNull(webSocket) { "Chromium CDP is not connected." }
        val deferred = CompletableDeferred<JsonObject>()
        val id = stateMutex.withLock {
            (++nextId).also { pending[it] = deferred }
        }
        try {
            val request = buildJsonObject {
                put("id", id)
                put("method", method)
                put("params", params)
                if (session.isNotBlank()) put("sessionId", session)
            }
            writeMutex.withLock {
                socket.send(Frame.Text(request.toString()))
            }
            return withTimeout(60_000) { deferred.await() }
        } catch (error: Throwable) {
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            val errors = recentErrors.trim()
            val detail = if (errors.length <= 8_192) {
                errors
            } else {
                errors.take(4_096) + "\n...\n" + errors.takeLast(4_096)
            }
            throw IllegalStateException(
                "CDP $method failed (${error.message ?: error::class.simpleName})" +
                    if (detail.isBlank()) "." else ": $detail",
                error,
            )
        } finally {
            stateMutex.withLock { pending.remove(id) }
        }
    }

    private suspend fun awaitBrowserWebSocketUrl(): String {
        val endpoint = withTimeoutOrNull(60_000) {
            while (true) {
                val candidate = runCatching {
                    Json.parseToJsonElement(
                        httpClient.get("http://localhost:9222/json/version").bodyAsText()
                    ).jsonObject.string("webSocketDebuggerUrl")
                        .replace("ws://127.0.0.1:", "ws://localhost:")
                }.getOrNull().orEmpty()
                if (candidate.isNotBlank()) return@withTimeoutOrNull candidate
                delay(200)
            }
            ""
        }
        if (!endpoint.isNullOrBlank()) return endpoint
        val errors = recentErrors.trim().takeLast(16_384)
        error(
            "Chromium CDP endpoint did not become available" +
                if (errors.isBlank()) "." else ": $errors"
        )
    }

    private suspend fun readFrames(socket: DefaultClientWebSocketSession) {
        try {
            for (frame in socket.incoming) {
                val text = when (frame) {
                    is Frame.Text -> frame.readText()
                    is Frame.Binary -> frame.data.decodeToString()
                    else -> continue
                }
                handleMessage(text)
            }
            failPending("Chromium CDP connection closed.")
        } catch (error: Throwable) {
            if (error !is CancellationException) failPending(error.message ?: "Chromium CDP output failed.")
        }
    }

    private suspend fun handleMessage(text: String) {
        val message = Json.parseToJsonElement(text).jsonObject
        val id = message["id"]?.jsonPrimitive?.intOrNull ?: return
        val deferred = stateMutex.withLock { pending.remove(id) } ?: return
        val error = message["error"] as? JsonObject
        if (error != null) {
            deferred.completeExceptionally(IllegalStateException(error.string("message")))
        } else {
            deferred.complete(message["result"] as? JsonObject ?: JsonObject(emptyMap()))
        }
    }

    private suspend fun failPending(message: String) {
        val requests = stateMutex.withLock { pending.values.toList().also { pending.clear() } }
        requests.forEach { it.completeExceptionally(IllegalStateException(message)) }
    }
}

private const val ChromeExecutable = "/usr/lib/chromium/chromium"
private val ChromeArguments = listOf(
    "--no-sandbox",
    "--disable-setuid-sandbox",
    "--no-zygote",
    "--disable-gpu-sandbox",
    "--no-proxy-server",
    "--disable-crash-reporter",
    "--disable-breakpad",
    "--disable-dev-shm-usage",
    "--use-gl=angle",
    "--use-angle=swiftshader",
    "--enable-unsafe-swiftshader",
    "--mute-audio",
    "--disable-audio-output",
    "--disable-features=AudioServiceOutOfProcess",
    "--enable-features=UsePollForMessagePumpEpoll",
    "--no-first-run",
    "--no-default-browser-check",
    "--password-store=basic",
    "--remote-debugging-address=127.0.0.1",
    "--remote-debugging-port=9222",
    "--remote-allow-origins=*",
    "--user-data-dir=/root/.aether/chrome-profile",
    "--window-size=1280,800",
    "--window-position=0,0",
    "--start-maximized",
    "--ozone-platform=x11",
    "about:blank",
)

private fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

private fun JsonObject.number(key: String): Double =
    this[key]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0

private fun JsonObject.optionalNumber(key: String): Double? =
    this[key]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()

private fun JsonObject.int(key: String): Int? =
    this[key]?.jsonPrimitive?.intOrNull
