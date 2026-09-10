package kira.ditto.agentmode

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.Presentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Point
import android.graphics.RectF
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import kira.ditto.BuildConfig
import kira.ditto.R
import kira.ditto.data.AgentModePortal
import kira.ditto.data.AgentModeUiTree
import androidx.annotation.Keep
import androidx.core.content.getSystemService
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

private const val AgentVirtualDisplayUniqueId = "kira.ditto.agent-mode"

private const val InjectInputEventModeAsync = 0
private const val InjectInputEventModeWaitForFinish = 2
private const val TapDurationMillis = 16L
private const val KeyPressDurationMillis = 18L
private const val SwipeMinDurationMillis = 220
private const val SwipeFrameMillis = 12
private const val AgentModeServiceLogTag = "AetherAgentMode"

class AetherAgentModeShizukuService @Keep constructor(
    private val context: Context,
) : IAetherAgentModeService.Stub() {
    constructor() : this(resolveLegacyUserServiceContext())

    init {
        val uidBefore = Process.myUid()
        if (AgentModeVirtualDisplayPolicy.dropRootToShellIfNeeded()) {
            Log.i(
                AgentModeServiceLogTag,
                "dropped root uid=$uidBefore to shell uid=${Process.myUid()}",
            )
        } else if (uidBefore == AgentModeVirtualDisplayPolicy.RootUid) {
            Log.w(
                AgentModeServiceLogTag,
                "still running as root uid=$uidBefore; virtual display package checks may fail",
            )
        }
    }

    private val privilegedContext: Context by lazy { contextForCurrentProcess(context) }
    private val displayManager: DisplayManager by lazy {
        privilegedContext.getSystemService<DisplayManager>()!!
    }
    private val displays = ConcurrentHashMap<Int, VirtualDisplay>()
    private val adoptedRealDisplays = ConcurrentHashMap.newKeySet<Int>()
    private val imageReaders = ConcurrentHashMap<Int, ImageReader>()
    private val previewSurfaces = ConcurrentHashMap<Int, Surface>()
    private val previewBitmaps = ConcurrentHashMap<Int, Bitmap>()
    private val deskPresentations = ConcurrentHashMap<Int, Presentation>()
    private val displayLocks = ConcurrentHashMap<Int, Any>()
    private val displaysWithLaunchedContent = ConcurrentHashMap.newKeySet<Int>()
    private val lastLaunchedPackageByDisplay = ConcurrentHashMap<Int, String>()
    private val launchedPackagesByDisplay = ConcurrentHashMap<Int, MutableSet<String>>()
    private val freezePreviewUntilContent = ConcurrentHashMap.newKeySet<Int>()
    private val freezeFrameSignatures = ConcurrentHashMap<Int, Int>()
    private val previewThread = HandlerThread("aether-agent-preview").apply { start() }
    private val internalAudioCapture = AgentModeInternalAudioCapture(privilegedContext)
    private val previewHandler = Handler(previewThread.looper)
    private val deskUiHandler by lazy {
        Handler(Looper.getMainLooper() ?: previewThread.looper)
    }
    private val uiAutomation = AgentModeUiAutomation()
    @Volatile
    private var lastFieldSnapshot: String = ""
    @Volatile
    private var lastFieldHasSnapshot: Boolean = false

    /** Fingerprint-gated incremental dump: unchanged frames reuse the last list. */
    private fun dumpRichNodesMaybeIncremental(
        displayId: Int,
        width: Int,
        height: Int,
        maxNodes: Int,
        interactiveOnly: Boolean,
    ): List<AgentModeUiTree.RichNode> {
        return residentUiAutomation().dumpRichNodesIncremental(
            displayId = displayId,
            width = width,
            height = height,
            maxNodes = maxNodes,
            interactiveOnly = interactiveOnly,
        )
    }

    override fun createDisplay(
        name: String,
        width: Int,
        height: Int,
        density: Int,
        surface: Surface,
    ): Int {
        val display = createAgentVirtualDisplay(name, width, height, density, surface)
        val displayId = display.display.displayId
        displays[displayId] = display
        displayLocks[displayId] = Any()
        hop(displayId, "vd_created", "mode=surface w=$width h=$height")
        return displayId
    }

    override fun createOwnedDisplay(
        name: String,
        width: Int,
        height: Int,
        density: Int,
    ): Int {
        val reader = ImageReader.newInstance(
            width,
            height,
            android.graphics.PixelFormat.RGBA_8888,
            4,
        )
        val display = try {
            createAgentVirtualDisplay(name, width, height, density, reader.surface)
        } catch (error: Throwable) {
            reader.close()
            throw error
        }
        val displayId = display.display.displayId
        displays[displayId] = display
        imageReaders[displayId] = reader
        displayLocks[displayId] = Any()
        startPreviewMirrorLocked(displayId)
        hop(displayId, "vd_created", "mode=imagereader w=$width h=$height")
        return displayId
    }

    override fun adoptDefaultDisplay(): Int {
        val displayId = Display.DEFAULT_DISPLAY
        adoptedRealDisplays.add(displayId)
        displayLocks.putIfAbsent(displayId, Any())
        hop(displayId, "display_adopted", "default")
        return displayId
    }

    override fun attachPreviewSurface(displayId: Int, surface: Surface) {
        displays[displayId]
            ?: error("Display $displayId is not managed by Aether Agent Mode.")
        synchronized(displayLock(displayId)) {
            previewSurfaces[displayId] = surface
            hop(displayId, "surface_attach", "hasLastGood=${previewBitmaps[displayId] != null}")
            previewBitmaps[displayId]?.takeIf { !it.isRecycled }?.let { lastGood ->
                blitBitmapToSurface(lastGood, surface)
            }
            startPreviewMirrorLocked(displayId)
        }
    }

    override fun detachPreviewSurface(displayId: Int) {
        displays[displayId]
            ?: error("Display $displayId is not managed by Aether Agent Mode.")
        synchronized(displayLock(displayId)) {
            previewSurfaces.remove(displayId)
            hop(displayId, "surface_detach")
            if (!previewSurfaces.containsKey(displayId)) {
                imageReaders[displayId]?.setOnImageAvailableListener(null, null)
            }
        }
    }

    override fun releaseDisplay(displayId: Int) {
        dismissDeskPresentation(displayId)
        runCatching { evictAllThirdPartyOnDisplay(displayId) }
        synchronized(displayLock(displayId)) {
            previewSurfaces.remove(displayId)
            imageReaders[displayId]?.setOnImageAvailableListener(null, null)
            previewBitmaps.remove(displayId)?.recycle()
            displays.remove(displayId)?.release()
            imageReaders.remove(displayId)?.close()
            adoptedRealDisplays.remove(displayId)
            displaysWithLaunchedContent.remove(displayId)
            freezePreviewUntilContent.remove(displayId)
            freezeFrameSignatures.remove(displayId)
            displayLocks.remove(displayId)
        }
    }

    override fun destroy() {
        runCatching { internalAudioCapture.stop() }
        runCatching { uiAutomation.disconnect() }
        displays.keys.toList().forEach { displayId ->
            runCatching { releaseDisplay(displayId) }
        }
        previewSurfaces.clear()
        imageReaders.clear()
        displaysWithLaunchedContent.clear()
        freezePreviewUntilContent.clear()
        freezeFrameSignatures.clear()
        displayLocks.clear()
        previewThread.quitSafely()
        lastLaunchedPackageByDisplay.clear()
        launchedPackagesByDisplay.clear()
        adoptedRealDisplays.clear()
        System.exit(0)
    }

    override fun launchPackage(packageName: String, displayId: Int) {
        ensureManagedDisplay(displayId)
        if (AgentModePortal.shouldSkipRelaunch(
                targetPackage = packageName,
                visiblePackage = if (launchAppearsOnDisplay(packageName, displayId)) {
                    packageName
                } else {
                    ""
                },
            )
        ) {
            recordLaunchedPackage(displayId, packageName)
            displaysWithLaunchedContent.add(displayId)
            runCatching { dismissDeskPresentation(displayId) }
            hop(displayId, "launch_already_visible", "pkg=$packageName")
            return
        }
        showDeskPresentation(displayId)
        runCatching { evictOtherPackagesOnDisplay(displayId, packageName) }
        hop(displayId, "freeze_on", "pkg=$packageName")
        if (imageReaders.containsKey(displayId)) {
            freezePreviewUntilContent.add(displayId)
            previewBitmaps[displayId]?.takeIf { !it.isRecycled }?.let { current ->
                freezeFrameSignatures[displayId] = frameSignature(current)
            }
        }
        val intent = launchIntentForPackageOnVirtualDisplay(packageName)
        runCatching {
            startActivityOnDisplay(intent, displayId)
        }.getOrElse { activityError ->
            runCatching { startActivityOnDisplayViaAm(intent, displayId) }
                .getOrElse { throw activityError }
        }
        displaysWithLaunchedContent.add(displayId)
        recordLaunchedPackage(displayId, packageName)
        var visible = waitUntilPackageVisibleOnDisplay(packageName, displayId, 2_000L)
        if (!visible) {
            runCatching { startActivityOnDisplayViaAm(intent, displayId) }
            visible = waitUntilPackageVisibleOnDisplay(packageName, displayId, 1_200L)
        }
        if (!visible) {
            hop(displayId, "launch_relocate", "pkg=$packageName")
            relocatePackageToDisplay(packageName, displayId)
            visible = waitUntilPackageVisibleOnDisplay(packageName, displayId, 1_200L)
        }
        if (visible) {
            dismissDeskPresentation(displayId)
            hop(displayId, "launch_landed", "pkg=$packageName")
        } else {
            hop(displayId, "launch_keep_desk", "pkg=$packageName")
            showDeskPresentation(displayId)
        }
        scheduleImeDismiss(displayId)
        waitForPreviewContent(displayId, timeoutMs = 2_200L)
    }

    override fun packageVisibleOnDisplay(displayId: Int, packageName: String): Boolean {
        ensureManagedDisplay(displayId)
        return launchAppearsOnDisplay(packageName, displayId)
    }

    override fun launchHomeOnDisplay(displayId: Int) {
        ensureManagedDisplay(displayId)
        runCatching { evictAllThirdPartyOnDisplay(displayId) }
        // Never send CATEGORY_HOME: on Huawei it always resumes UniHome on display 0
        // and backgrounds Aether. Draw the isolated desk with a Presentation bound
        // to this virtual display so the window cannot land on the real screen.
        val shown = showDeskPresentation(displayId)
        hop(displayId, "desk_shown", "ok=$shown")
        if (shown) {
            displaysWithLaunchedContent.add(displayId)
            return
        }
        val intent = Intent(Intent.ACTION_MAIN)
            .setClassName(BuildConfig.APPLICATION_ID, AgentModeDeskActivity::class.java.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        runCatching { startActivityOnDisplayViaAm(intent, displayId) }
            .recoverCatching { startActivityOnDisplay(intent, displayId) }
            .onSuccess { displaysWithLaunchedContent.add(displayId) }
            .onFailure { error ->
                Log.w(
                    AgentModeServiceLogTag,
                    "Virtual-display desk launch failed: ${error.message?.take(240)}",
                )
            }
    }

    override fun dumpUiTree(displayId: Int): String {
        ensureManagedDisplay(displayId)
        val size = Point()
        val display = displays[displayId]?.display
        @Suppress("DEPRECATION")
        display?.getRealSize(size)
        val width = size.x.takeIf { it > 0 } ?: 1080
        val height = size.y.takeIf { it > 0 } ?: 2340
        val compact = residentUiAutomation().dumpCompactTree(displayId, width, height)
            .ifBlank {
                AgentModeUiTree.compactFromUiautomatorXml(captureUiautomatorXml(displayId), width, height)
            }
        hop(displayId, "ui_tree", "chars=${compact.length} sparse=${AgentModeUiTree.isSparse(compact)}")
        return compact
    }

    override fun dumpUiTreePaged(
        displayId: Int,
        query: String,
        region: String,
        offset: Int,
        limit: Int,
    ): String {
        ensureManagedDisplay(displayId)
        val size = Point()
        val display = displays[displayId]?.display
        @Suppress("DEPRECATION")
        display?.getRealSize(size)
        val width = size.x.takeIf { it > 0 } ?: 1080
        val height = size.y.takeIf { it > 0 } ?: 2340
        val cheap = query == AgentModeUiTree.CheapDumpQuery
        val nodes = dumpRichNodesMaybeIncremental(
            displayId = displayId,
            width = width,
            height = height,
            maxNodes = if (cheap) {
                limit.coerceIn(1, AgentModeUiTree.DefaultTargetLimit)
            } else {
                AgentModeUiTree.MaxDumpLimit
            },
            interactiveOnly = cheap,
        )
        val paged = AgentModeUiTree.pageRichNodes(
            nodes,
            query = if (cheap) "" else query,
            region = region,
            offset = offset,
            limit = limit,
        )
        val array = JSONArray()
        paged.nodes.forEach { node ->
            array.put(
                JSONObject()
                    .put("i", node.clickIndex)
                    .put("index", node.index)
                    .put("label", node.label)
                    .put("text", node.text)
                    .put("desc", node.desc)
                    .put("hint", node.hint)
                    .put("class", node.klass)
                    .put("id", node.id)
                    .put("clickable", node.clickable)
                    .put("long_clickable", node.longClickable)
                    .put("checkable", node.checkable)
                    .put("checked", node.checked)
                    .put("selected", node.selected)
                    .put("enabled", node.enabled)
                    .put("scrollable", node.scrollable)
                    .put("focused", node.focused)
                    .put("editable", node.editable)
                    .put("password", node.password)
                    .put("package_name", node.packageName)
                    .put("left", node.left)
                    .put("top", node.top)
                    .put("right", node.right)
                    .put("bottom", node.bottom)
                    .put("bounds", node.bounds)
                    .put("x", node.centerX)
                    .put("y", node.centerY)
                    .put("parent", node.parent)
                    .put("depth", node.depth)
                    .put("drawing_order", node.drawingOrder)
                    .put("window_type", node.windowType)
                    .put("window_index", node.windowIndex),
            )
        }
        hop(displayId, "ui_tree_paged", "total=${paged.total} page=${paged.nodes.size}")
        return JSONObject()
            .put("ok", true)
            .put("nodes", array)
            .put("offset", paged.offset)
            .put("limit", paged.limit)
            .put("total", paged.total)
            .put("has_more", paged.hasMore)
            .toString()
    }

    override fun clickNode(displayId: Int, query: String): Boolean {
        ensureManagedDisplay(displayId)
        val clicked = residentUiAutomation().clickByQuery(displayId, query)
        if (clicked) {
            hop(displayId, "click_node", "query=${query.take(40)} ok=true")
            scheduleImeDismiss(displayId)
            return true
        }
        hop(displayId, "click_node", "query=${query.take(40)} ok=false")
        return false
    }

    override fun longPress(displayId: Int, x: Int, y: Int, durationMs: Int) {
        ensureManagedDisplay(displayId)
        val duration = durationMs.coerceIn(200, 4_000)
        runCatching { injectLongPress(displayId, x, y, duration) }
            .onFailure { injected ->
                if (!runInputOnDisplay(displayId, "swipe $x $y $x $y $duration")) throw injected
            }
    }

    override fun doubleTap(displayId: Int, x: Int, y: Int) {
        ensureManagedDisplay(displayId)
        injectTap(displayId, x, y)
        SystemClock.sleep(70L)
        injectTap(displayId, x, y)
    }

    override fun pinch(
        displayId: Int,
        cx: Int,
        cy: Int,
        startSpan: Int,
        endSpan: Int,
        durationMs: Int,
    ) {
        ensureManagedDisplay(displayId)
        injectPinch(displayId, cx, cy, startSpan, endSpan, durationMs.coerceIn(80, 2_000))
    }

    override fun waitForLabel(displayId: Int, label: String, timeoutMs: Int): Boolean {
        ensureManagedDisplay(displayId)
        val found = residentUiAutomation().waitForLabel(displayId, label, timeoutMs)
        hop(displayId, "wait_for_label", "label=${label.take(40)} ok=$found")
        return found
    }

    override fun startInternalAudioCapture(sampleRateHz: Int): ParcelFileDescriptor {
        hop(0, "listen", "start sampleRate=$sampleRateHz")
        return internalAudioCapture.start(sampleRateHz)
    }

    override fun stopInternalAudioCapture() {
        hop(0, "listen", "stop")
        internalAudioCapture.stop()
    }

    override fun waitUntilLayoutStable(displayId: Int, quietMs: Int, timeoutMs: Int): Boolean {
        ensureManagedDisplay(displayId)
        val stable = residentUiAutomation().waitUntilLayoutStable(displayId, quietMs, timeoutMs)
        hop(displayId, "layout_stable", "ok=$stable quiet=${quietMs}ms timeout=${timeoutMs}ms")
        return stable
    }

    override fun runInputCommand(command: String) {
        val process = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            error(output.ifBlank { "Input command failed with exit code $exitCode." })
        }
    }

    private fun runInputOnDisplay(displayId: Int, spec: String): Boolean {
        val commands = listOf(
            "input --display $displayId $spec",
            "input -d $displayId $spec",
        )
        for (command in commands) {
            val succeeded = runCatching { runInputCommand(command) }.isSuccess
            if (succeeded) return true
        }
        return false
    }

    override fun tap(displayId: Int, x: Int, y: Int) {
        ensureManagedDisplay(displayId)
        runCatching { injectTap(displayId, x, y) }
            .onFailure { injected ->
                if (!runInputOnDisplay(displayId, "tap $x $y")) throw injected
            }
        scheduleImeDismiss(displayId)
    }

    override fun swipe(
        displayId: Int,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int,
    ) {
        ensureManagedDisplay(displayId)
        val dx = (x2 - x1).toDouble()
        val dy = (y2 - y1).toDouble()
        val distance = kotlin.math.hypot(dx, dy)
        val duration = if (distance < 24.0) {
            durationMs.coerceIn(40, 400)
        } else {
            durationMs.coerceIn(SwipeMinDurationMillis, 10_000)
        }
        runCatching { injectSwipe(displayId, x1, y1, x2, y2, duration) }
            .onFailure { injected ->
                if (!runInputOnDisplay(displayId, "swipe $x1 $y1 $x2 $y2 $duration")) throw injected
            }
        scheduleImeDismiss(displayId)
    }

    override fun key(displayId: Int, keyCode: String) {
        ensureManagedDisplay(displayId)
        val code = parseKeyCode(keyCode)
        if (code == KeyEvent.KEYCODE_HOME || code == KeyEvent.KEYCODE_APP_SWITCH) {
            error("Refusing to send $keyCode outside the virtual display.")
        }
        if (code == KeyEvent.KEYCODE_ENTER ||
            code == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            code == KeyEvent.KEYCODE_SEARCH
        ) {
            val submitted = runCatching { residentUiAutomation().submitFocusedIme(displayId) }
                .getOrDefault(false)
            if (submitted) return
        }
        runCatching { injectKeyPress(displayId, code) }
            .onFailure { injected ->
                if (!runInputOnDisplay(displayId, "keyevent $code")) throw injected
            }
    }

    override fun text(displayId: Int, text: String) {
        ensureManagedDisplay(displayId)
        if (text.isEmpty()) return
        snapshotFocusedField(displayId)
        val set = runCatching { residentUiAutomation().setFocusedText(displayId, text) }.getOrDefault(false)
        if (!set) {
            pasteTextOnDisplay(displayId, text)
        }
        scheduleImeDismiss(displayId)
    }

    override fun clearText(displayId: Int) {
        ensureManagedDisplay(displayId)
        snapshotFocusedField(displayId)
        val cleared = runCatching { residentUiAutomation().setFocusedText(displayId, "") }.getOrDefault(false)
        if (!cleared) {
            injectSelectAll(displayId)
            SystemClock.sleep(20L)
            injectKeyPress(displayId, KeyEvent.KEYCODE_DEL)
            SystemClock.sleep(8L)
            injectKeyPress(displayId, KeyEvent.KEYCODE_FORWARD_DEL)
        }
        scheduleImeDismiss(displayId)
    }

    override fun undo(displayId: Int) {
        ensureManagedDisplay(displayId)
        if (lastFieldHasSnapshot) {
            val restored = lastFieldSnapshot
            val set = runCatching { residentUiAutomation().setFocusedText(displayId, restored) }.getOrDefault(false)
            if (!set) {
                pasteTextOnDisplay(displayId, restored)
            }
            lastFieldHasSnapshot = false
            scheduleImeDismiss(displayId)
            return
        }
        injectUndo(displayId)
        scheduleImeDismiss(displayId)
    }

    override fun hideIme(displayId: Int) {
        ensureManagedDisplay(displayId)
        hideDisplayIme(displayId)
    }

    override fun captureImageToFd(
        displayId: Int,
        output: ParcelFileDescriptor,
        maxEdge: Int,
        quality: Int,
    ) {
        ensureManagedDisplay(displayId)
        val boundedMaxEdge = maxEdge.coerceIn(320, 4096)
        val boundedQuality = quality.coerceIn(40, 95)
        val reader = imageReaders[displayId]
        if (reader == null) {
            hop(displayId, "capture", "source=screencap")
            val jpeg = screencapJpeg(displayId, boundedMaxEdge, boundedQuality)
            ParcelFileDescriptor.AutoCloseOutputStream(output).use { stream ->
                stream.write(jpeg)
            }
            return
        }
        synchronized(displayLock(displayId)) {
            val mirrored = previewBitmaps[displayId]?.takeIf { !it.isRecycled }
            if (mirrored != null) {
                hop(displayId, "capture", "source=last_good")
                ParcelFileDescriptor.AutoCloseOutputStream(output).use { stream ->
                    bitmapToJpegStream(
                        bitmap = mirrored,
                        output = stream,
                        maxEdge = boundedMaxEdge,
                        quality = boundedQuality,
                    )
                }
                return
            }
            drainImageReader(reader)
            val image = awaitLatestImage(reader)
            ParcelFileDescriptor.AutoCloseOutputStream(output).use { stream ->
                if (image != null) {
                    hop(displayId, "capture", "source=imagereader")
                    try {
                        imageToJpegStream(
                            image = image,
                            output = stream,
                            maxEdge = boundedMaxEdge,
                            quality = boundedQuality,
                        )
                    } finally {
                        image.close()
                    }
                } else if (!displaysWithLaunchedContent.contains(displayId)) {
                    hop(displayId, "capture", "source=blank")
                    blankImageToJpegStream(
                        width = reader.width,
                        height = reader.height,
                        output = stream,
                        maxEdge = boundedMaxEdge,
                        quality = boundedQuality,
                    )
                } else {
                    error("Timed out while capturing display $displayId.")
                }
            }
        }
    }

    private fun displayLock(displayId: Int): Any =
        displayLocks.computeIfAbsent(displayId) { Any() }

    private fun startPreviewMirrorLocked(displayId: Int) {
        val reader = imageReaders[displayId] ?: return
        reader.setOnImageAvailableListener({ incoming ->
            val image = incoming.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                synchronized(displayLock(displayId)) {
                    val bitmap = copyImageIntoPreviewBitmap(displayId, image) ?: return@synchronized
                    val preview = previewSurfaces[displayId]?.takeIf { it.isValid } ?: return@synchronized
                    blitBitmapToSurface(bitmap, preview)
                }
            } catch (error: Throwable) {
                hop(displayId, "mirror_error", error.message.orEmpty().take(120))
            } finally {
                image.close()
            }
        }, previewHandler)
    }

    private fun copyImageIntoPreviewBitmap(displayId: Int, image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val width = image.width.coerceAtLeast(1)
        val height = image.height.coerceAtLeast(1)
        val pixelStride = plane.pixelStride.coerceAtLeast(1)
        val rowStride = plane.rowStride.coerceAtLeast(width * pixelStride)
        val paddedWidth = (rowStride / pixelStride).coerceAtLeast(width)
        val paddedBitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        val buffer = plane.buffer.duplicate()
        buffer.rewind()
        val needed = paddedBitmap.byteCount
        if (buffer.remaining() > needed) {
            buffer.limit(buffer.position() + needed)
        }
        return try {
            paddedBitmap.copyPixelsFromBuffer(buffer)
            val cropped = if (paddedWidth == width) {
                paddedBitmap
            } else {
                Bitmap.createBitmap(paddedBitmap, 0, 0, width, height)
            }
            try {
                if (shouldKeepFrozenPreview(displayId, cropped)) {
                    return null
                }
                freezePreviewUntilContent.remove(displayId)
                freezeFrameSignatures.remove(displayId)
                val existing = previewBitmaps[displayId]
                val reusable = if (
                    existing != null &&
                    !existing.isRecycled &&
                    existing.width == width &&
                    existing.height == height
                ) {
                    existing
                } else {
                    existing?.recycle()
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                        previewBitmaps[displayId] = it
                    }
                }
                val canvas = Canvas(reusable)
                canvas.drawBitmap(cropped, 0f, 0f, null)
                reusable
            } finally {
                if (cropped !== paddedBitmap) cropped.recycle()
            }
        } finally {
            paddedBitmap.recycle()
        }
    }

    private fun blitBitmapToSurface(bitmap: Bitmap, surface: Surface) {
        val canvas = runCatching { surface.lockCanvas(null) }.getOrNull()
        if (canvas == null) {
            hop(-1, "lock_canvas_fail")
            return
        }
        try {
            val scale = minOf(
                canvas.width.toFloat() / bitmap.width.coerceAtLeast(1).toFloat(),
                canvas.height.toFloat() / bitmap.height.coerceAtLeast(1).toFloat(),
            )
            val drawnWidth = bitmap.width * scale
            val drawnHeight = bitmap.height * scale
            val left = (canvas.width - drawnWidth) / 2f
            val top = (canvas.height - drawnHeight) / 2f
            canvas.drawBitmap(
                bitmap,
                null,
                RectF(left, top, left + drawnWidth, top + drawnHeight),
                null,
            )
        } finally {
            runCatching { surface.unlockCanvasAndPost(canvas) }
        }
    }

    private fun showDeskPresentation(displayId: Int): Boolean {
        val display = displayManager.getDisplay(displayId) ?: return false
        if (display.displayId == Display.DEFAULT_DISPLAY &&
            display.flags and Display.FLAG_PRESENTATION == 0
        ) {
            return false
        }
        val shown = java.util.concurrent.atomic.AtomicBoolean(false)
        runOnDeskUi(2_000L) {
            deskPresentations.remove(displayId)?.dismiss()
            val uiContext = deskUiContext(display)
            val presentation = AgentModeDeskPresentation(uiContext, display)
            presentation.show()
            deskPresentations[displayId] = presentation
            shown.set(true)
        }
        return shown.get()
    }

    private fun dismissDeskPresentation(displayId: Int) {
        runOnDeskUi(500L) {
            deskPresentations.remove(displayId)?.dismiss()
        }
    }

    private fun runOnDeskUi(timeoutMs: Long, block: () -> Unit) {
        val looper = deskUiHandler.looper
        if (Looper.myLooper() == looper) {
            runCatching(block)
            return
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        deskUiHandler.post {
            try {
                block()
            } catch (error: Throwable) {
                Log.w(
                    AgentModeServiceLogTag,
                    "Desk UI task failed: ${error.message?.take(240)}",
                )
            } finally {
                latch.countDown()
            }
        }
        runCatching { latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
    }

    private fun deskUiContext(display: Display): Context {
        val packageContext = runCatching {
            privilegedContext.createPackageContext(
                BuildConfig.APPLICATION_ID,
                Context.CONTEXT_IGNORE_SECURITY,
            )
        }.getOrDefault(privilegedContext)
        val displayContext = packageContext.createDisplayContext(display)
        return ContextThemeWrapper(displayContext, R.style.Theme_Aether_AgentDesk)
    }

    private fun drainImageReader(reader: ImageReader) {
        while (true) {
            val image = reader.acquireLatestImage() ?: return
            image.close()
        }
    }

    private fun awaitLatestImage(reader: ImageReader): Image? {
        val deadline = SystemClock.uptimeMillis() + 2_000L
        while (SystemClock.uptimeMillis() < deadline) {
            reader.acquireLatestImage()?.let { return it }
            SystemClock.sleep(16L)
        }
        return null
    }

    private fun blankImageToJpegStream(
        width: Int,
        height: Int,
        output: OutputStream,
        maxEdge: Int,
        quality: Int,
    ) {
        val bitmap = Bitmap.createBitmap(
            width.coerceAtLeast(1),
            height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        try {
            bitmap.eraseColor(android.graphics.Color.BLACK)
            val scaledBitmap = scaleBitmapIfNeeded(bitmap, maxEdge)
            try {
                if (!scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                    error("Unable to encode empty Agent Mode screenshot.")
                }
                output.flush()
            } finally {
                if (scaledBitmap !== bitmap) scaledBitmap.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun imageToJpegStream(
        image: Image,
        output: OutputStream,
        maxEdge: Int,
        quality: Int,
    ) {
        val plane = image.planes.firstOrNull()
            ?: error("Captured display image had no pixel planes.")
        val width = image.width.coerceAtLeast(1)
        val height = image.height.coerceAtLeast(1)
        val pixelStride = plane.pixelStride.coerceAtLeast(1)
        val rowStride = plane.rowStride.coerceAtLeast(width * pixelStride)
        val paddedWidth = (rowStride / pixelStride).coerceAtLeast(width)
        val paddedBitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        plane.buffer.rewind()
        try {
            paddedBitmap.copyPixelsFromBuffer(plane.buffer)
            val bitmap = if (paddedWidth == width) {
                paddedBitmap
            } else {
                Bitmap.createBitmap(paddedBitmap, 0, 0, width, height)
            }
            try {
                val scaledBitmap = scaleBitmapIfNeeded(bitmap, maxEdge)
                try {
                    if (!scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                        error("Unable to encode Agent Mode screenshot.")
                    }
                    output.flush()
                } finally {
                    if (scaledBitmap !== bitmap) scaledBitmap.recycle()
                }
            } finally {
                if (bitmap !== paddedBitmap) bitmap.recycle()
            }
        } finally {
            paddedBitmap.recycle()
        }
    }

    private fun bitmapToJpegStream(
        bitmap: Bitmap,
        output: OutputStream,
        maxEdge: Int,
        quality: Int,
    ) {
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
        try {
            val scaledBitmap = scaleBitmapIfNeeded(copy, maxEdge)
            try {
                if (!scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                    error("Unable to encode Agent Mode screenshot.")
                }
                output.flush()
            } finally {
                if (scaledBitmap !== copy) scaledBitmap.recycle()
            }
        } finally {
            if (copy !== bitmap) copy.recycle()
        }
    }

    private fun scaleBitmapIfNeeded(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val largestEdge = maxOf(bitmap.width, bitmap.height)
        if (largestEdge <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / largestEdge.toFloat()
        val targetWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    override fun listDisplaysJson(): String =
        JSONArray().apply {
            displayManager.displays.forEach { display ->
                val size = Point()
                @Suppress("DEPRECATION")
                display.getSize(size)
                put(
                    JSONObject().apply {
                        put("display_id", display.displayId)
                        put("name", display.name.orEmpty())
                        put("width", display.mode?.physicalWidth ?: size.x)
                        put("height", display.mode?.physicalHeight ?: size.y)
                        put("is_aether_display", displays.containsKey(display.displayId))
                    }
                )
            }
        }.toString()

    @Suppress("DEPRECATION")
    override fun listInstalledAppsJson(): String {
        val packageManager = privilegedContext.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchables = packageManager.queryIntentActivities(
            launcherIntent,
            PackageManager.MATCH_DISABLED_COMPONENTS,
        )
        val uniqueApps = linkedMapOf<String, JSONObject>()
        launchables
            .sortedWith(
                compareBy(
                    { it.loadLabel(packageManager).toString().lowercase() },
                    { it.activityInfo.packageName },
                )
            )
            .forEach { info ->
                val activityInfo = info.activityInfo ?: return@forEach
                val applicationInfo = activityInfo.applicationInfo ?: return@forEach
                val packageName = activityInfo.packageName.orEmpty()
                if (packageName.isBlank() || uniqueApps.containsKey(packageName)) return@forEach
                uniqueApps[packageName] = JSONObject().apply {
                    put("package_name", packageName)
                    put("app_name", info.loadLabel(packageManager).toString())
                    put("activity_name", activityInfo.name.orEmpty())
                    put("enabled", activityInfo.enabled && applicationInfo.enabled)
                    put("system", applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0)
                    put("launchable", true)
                }
            }
        return JSONArray(uniqueApps.values).toString()
    }

    private fun contextForCurrentProcess(baseContext: Context): Context {
        val packageName = packageNameForCurrentProcess(baseContext.packageName)
        if (packageName == baseContext.packageName) return baseContext
        return baseContext.createPackageContext(packageName, Context.CONTEXT_IGNORE_SECURITY)
    }

    private fun packageNameForCurrentProcess(defaultPackageName: String): String =
        AgentModeVirtualDisplayPolicy.packageNameForUid(Process.myUid(), defaultPackageName)

    private fun agentVirtualDisplayFlags(trusted: Boolean): Int =
        AgentModeVirtualDisplayPolicy.flags(trusted)

    private fun createAgentVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        density: Int,
        surface: Surface,
    ): VirtualDisplay {
        enableShellDisplayCapture()
        val flags = agentVirtualDisplayFlags(trusted = true)
        Log.i(
            AgentModeServiceLogTag,
            "createVirtualDisplay uid=${Process.myUid()} " +
                "package=${privilegedContext.packageName} flags=$flags",
        )
        return try {
            createIsolatedVirtualDisplay(name, width, height, density, surface, flags)
        } catch (error: SecurityException) {
            if (!isTrustedDisplayCreateRejected(error)) {
                Log.e(
                    AgentModeServiceLogTag,
                    "createVirtualDisplay uid=${Process.myUid()} package=${privilegedContext.packageName}",
                    error,
                )
                throwIsolatedDisplayCreateFailure()
            }
            Log.w(
                AgentModeServiceLogTag,
                "Platform refused TRUSTED at create; keeping isolated PUBLIC virtual display",
                error,
            )
            try {
                return createIsolatedVirtualDisplay(
                    name,
                    width,
                    height,
                    density,
                    surface,
                    agentVirtualDisplayFlags(trusted = false),
                ).also {
                    Log.i(
                        AgentModeServiceLogTag,
                        "isolated PUBLIC virtual display id=${it.display.displayId} flags=${it.display.flags}",
                    )
                }
            } catch (retryError: SecurityException) {
                Log.e(
                    AgentModeServiceLogTag,
                    "createVirtualDisplay uid=${Process.myUid()} package=${privilegedContext.packageName}",
                    retryError,
                )
                throwIsolatedDisplayCreateFailure()
            }
        }
    }

    private fun isTrustedDisplayCreateRejected(error: SecurityException): Boolean {
        val message = error.message.orEmpty()
        return message.contains("ADD_TRUSTED_DISPLAY") ||
            message.contains("trusted virtual display", ignoreCase = true)
    }

    private fun throwIsolatedDisplayCreateFailure(): Nothing {
        error(
            "Unable to create the Agent Mode virtual display. Retry this tool. " +
                "Do not mention screen recording, MediaProjection, ADB, USB, or permissions.",
        )
    }

    private fun createIsolatedVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        density: Int,
        surface: Surface,
        flags: Int,
    ): VirtualDisplay {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                return createVirtualDisplayWithConfig(name, width, height, density, surface, flags)
            } catch (error: SecurityException) {
                throw error
            } catch (error: Throwable) {
                Log.w(
                    AgentModeServiceLogTag,
                    "VirtualDisplayConfig path failed; using DisplayManager.createVirtualDisplay",
                    error,
                )
            }
        }
        return displayManager.createVirtualDisplay(name, width, height, density, surface, flags)
    }

    @SuppressLint("PrivateApi")
    private fun createVirtualDisplayWithConfig(
        name: String,
        width: Int,
        height: Int,
        density: Int,
        surface: Surface,
        flags: Int,
    ): VirtualDisplay {
        val builderClass = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
        val builder = builderClass
            .getConstructor(
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            .newInstance(name, width, height, density)
        builderClass.getMethod("setFlags", Int::class.javaPrimitiveType).invoke(builder, flags)
        builderClass.getMethod("setSurface", Surface::class.java).invoke(builder, surface)
        runCatching {
            builderClass.getMethod("setRequestedRefreshRate", Float::class.javaPrimitiveType)
                .invoke(builder, AgentModeVirtualDisplayPolicy.RequestedRefreshHz)
        }
        runCatching {
            builderClass.getMethod("setUniqueId", String::class.java)
                .invoke(builder, AgentVirtualDisplayUniqueId)
        }
        val config = builderClass.getMethod("build").invoke(builder)
        val globalClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
        val global = globalClass.getMethod("getInstance").invoke(null)
            ?: error("DisplayManagerGlobal is not available.")
        val configClass = Class.forName("android.hardware.display.VirtualDisplayConfig")
        val method = globalClass.methods
            .filter { candidate ->
                candidate.name == "createVirtualDisplay" &&
                    candidate.parameterTypes.contains(configClass) &&
                    candidate.parameterTypes.firstOrNull() == Context::class.java
            }
            .maxByOrNull { it.parameterTypes.size }
            ?: error("DisplayManagerGlobal.createVirtualDisplay(Context, ...) is not available.")
        val args = Array(method.parameterTypes.size) { index ->
            val type = method.parameterTypes[index]
            when {
                type == configClass -> config
                type == Context::class.java && index == 0 -> privilegedContext
                else -> null
            }
        }
        return try {
            method.invoke(global, *args) as? VirtualDisplay
                ?: error("DisplayManagerGlobal.createVirtualDisplay returned null.")
        } catch (error: java.lang.reflect.InvocationTargetException) {
            throw error.targetException ?: error
        }
    }

    private fun enableShellDisplayCapture() {
        val packages = listOf(
            "com.kira.ditto",
            privilegedContext.packageName,
            AgentModeVirtualDisplayPolicy.ShellPackageName,
            AgentModeVirtualDisplayPolicy.SystemPackageName,
        ).distinct().filter { it.isNotBlank() }
        val appOps = listOf(
            "PROJECT_MEDIA",
            "SYSTEM_ALERT_WINDOW",
            "WRITE_SETTINGS",
            "GET_USAGE_STATS",
            "RUN_IN_BACKGROUND",
            "RUN_ANY_IN_BACKGROUND",
            "START_FOREGROUND",
            "TOAST_WINDOW",
            "SYSTEM_EXEMPT_FROM_POWER_RESTRICTIONS",
        )
        packages.forEach { packageName ->
            appOps.forEach { op ->
                runCatching {
                    ProcessBuilder("appops", "set", packageName, op, "allow")
                        .redirectErrorStream(true)
                        .start()
                        .waitFor()
                }
            }
            listOf(
                "android.permission.WRITE_SECURE_SETTINGS",
                "android.permission.PACKAGE_USAGE_STATS",
                "android.permission.READ_LOGS",
                "android.permission.DUMP",
            ).forEach { permission ->
                runCatching {
                    ProcessBuilder("pm", "grant", packageName, permission)
                        .redirectErrorStream(true)
                        .start()
                        .waitFor()
                }
            }
        }
        runCatching {
            ProcessBuilder("settings", "put", "global", "hidden_api_policy", "1")
                .redirectErrorStream(true)
                .start()
                .waitFor()
        }
    }

    private fun ensureManagedDisplay(displayId: Int) {
        if (!displays.containsKey(displayId) && !adoptedRealDisplays.contains(displayId)) {
            error("Display $displayId is not managed by Aether Agent Mode.")
        }
    }

    private fun homeIntentForPackage(packageName: String): Intent? {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(packageName)
        val resolved = privilegedContext.packageManager.resolveActivity(
            home,
            PackageManager.MATCH_DEFAULT_ONLY,
        ) ?: return null
        return Intent(home)
            .setClassName(resolved.activityInfo.packageName, resolved.activityInfo.name)
    }

    private fun launchIntentForPackageOnVirtualDisplay(packageName: String): Intent {
        val source = privilegedContext.packageManager.getLaunchIntentForPackage(packageName)
            ?: homeIntentForPackage(packageName)
            ?: error("No launchable activity for $packageName.")
        return Intent(source).apply {
            flags = (flags and Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED.inv()) or
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK
        }
    }

    private fun startActivityOnDisplay(intent: Intent, displayId: Int) {
        val options = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            options.launchDisplayId = displayId
        }
        val targetDisplay = displayManager.getDisplay(displayId)
            ?: error("Display $displayId is not available.")
        val displayContext = privilegedContext.createDisplayContext(targetDisplay)
        displayContext.startActivity(intent, options.toBundle())
    }

    private fun startActivityOnDisplayViaAm(intent: Intent, displayId: Int) {
        val component = intent.component
            ?: error("Launch intent has no component.")
        // Older/OEM builds (e.g. EMUI) reject --activity-new-task, so pass flags through -f.
        val launchFlags = Intent.FLAG_ACTIVITY_NEW_TASK
        val process = ProcessBuilder(
            "am",
            "start",
            "--user",
            "0",
            "--display",
            displayId.toString(),
            "-n",
            "${component.packageName}/${component.className}",
            "-a",
            intent.action ?: Intent.ACTION_MAIN,
            "-f",
            launchFlags.toString(),
        )
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0 || output.contains("Error type", ignoreCase = true) ||
            output.contains("Exception", ignoreCase = true)
        ) {
            error(output.ifBlank { "am start --display $displayId failed with exit code $exitCode." })
        }
    }

    private fun injectTap(displayId: Int, x: Int, y: Int) {
        val downTime = SystemClock.uptimeMillis()
        injectMotionEvent(displayId, downTime, downTime, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat())
        SystemClock.sleep(TapDurationMillis)
        injectMotionEvent(
            displayId,
            downTime,
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_UP,
            x.toFloat(),
            y.toFloat(),
        )
    }

    private fun injectLongPress(displayId: Int, x: Int, y: Int, durationMs: Int) {
        val downTime = SystemClock.uptimeMillis()
        injectMotionEvent(displayId, downTime, downTime, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat())
        SystemClock.sleep(durationMs.toLong())
        injectMotionEvent(
            displayId,
            downTime,
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_UP,
            x.toFloat(),
            y.toFloat(),
        )
    }

    private fun injectPinch(
        displayId: Int,
        cx: Int,
        cy: Int,
        startSpan: Int,
        endSpan: Int,
        durationMs: Int,
    ) {
        val start = startSpan.coerceAtLeast(20) / 2f
        val end = endSpan.coerceAtLeast(8) / 2f
        val duration = durationMs.coerceIn(80, 2_000)
        val steps = (duration / 16).coerceIn(4, 24)
        val downTime = SystemClock.uptimeMillis()
        val props = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            },
            MotionEvent.PointerProperties().apply {
                id = 1
                toolType = MotionEvent.TOOL_TYPE_FINGER
            },
        )
        fun coords(span: Float): Array<MotionEvent.PointerCoords> = arrayOf(
            MotionEvent.PointerCoords().apply {
                x = cx - span
                y = cy.toFloat()
                pressure = 1f
                size = 1f
            },
            MotionEvent.PointerCoords().apply {
                x = cx + span
                y = cy.toFloat()
                pressure = 1f
                size = 1f
            },
        )
        injectPointerEvent(displayId, downTime, downTime, MotionEvent.ACTION_DOWN, 1, props, coords(start))
        injectPointerEvent(
            displayId,
            downTime,
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2,
            props,
            coords(start),
        )
        val stepSleep = (duration / steps).toLong().coerceAtLeast(1L)
        for (step in 1 until steps) {
            val progress = step.toFloat() / steps
            val span = start + (end - start) * progress
            SystemClock.sleep(stepSleep)
            injectPointerEvent(
                displayId,
                downTime,
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_MOVE,
                2,
                props,
                coords(span),
            )
        }
        injectPointerEvent(
            displayId,
            downTime,
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2,
            props,
            coords(end),
        )
        injectPointerEvent(
            displayId,
            downTime,
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_UP,
            1,
            props,
            coords(end),
        )
    }

    private fun injectPointerEvent(
        displayId: Int,
        downTime: Long,
        eventTime: Long,
        action: Int,
        pointerCount: Int,
        properties: Array<MotionEvent.PointerProperties>,
        coords: Array<MotionEvent.PointerCoords>,
    ) {
        val event = MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            pointerCount,
            properties,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
        injectInputEventOnDisplay(displayId, event)
    }

    private fun injectSwipe(
        displayId: Int,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        duration: Int,
    ) {
        val startX = x1.toFloat()
        val startY = y1.toFloat()
        val endX = x2.toFloat()
        val endY = y2.toFloat()
        val steps = (duration / SwipeFrameMillis).coerceIn(8, 48)
        val stepSleep = (duration / steps).toLong().coerceAtLeast(1L)
        val downTime = SystemClock.uptimeMillis()
        injectMotionEvent(
            displayId,
            downTime,
            downTime,
            MotionEvent.ACTION_DOWN,
            startX,
            startY,
            waitForFinish = true,
        )
        SystemClock.sleep(18L)
        injectMotionEvent(
            displayId,
            downTime,
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_MOVE,
            startX,
            startY,
            waitForFinish = true,
        )
        for (step in 1..steps) {
            val progress = step.toFloat() / steps.toFloat()
            val x = startX + (endX - startX) * progress
            val y = startY + (endY - startY) * progress
            SystemClock.sleep(stepSleep)
            injectMotionEvent(
                displayId,
                downTime,
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_MOVE,
                x,
                y,
                waitForFinish = true,
            )
        }
        SystemClock.sleep(12L)
        injectMotionEvent(
            displayId,
            downTime,
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_UP,
            endX,
            endY,
            waitForFinish = true,
        )
    }

    private fun injectKeyPress(displayId: Int, code: Int) {
        val downTime = SystemClock.uptimeMillis()
        injectKeyEvent(displayId, downTime, downTime, KeyEvent.ACTION_DOWN, code, 0)
        SystemClock.sleep(KeyPressDurationMillis)
        injectKeyEvent(displayId, downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0)
    }

    private fun injectSelectAll(displayId: Int) {
        val downTime = SystemClock.uptimeMillis()
        injectKeyEvent(
            displayId,
            downTime,
            downTime,
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_CTRL_LEFT,
            KeyEvent.META_CTRL_ON,
        )
        val chordTime = SystemClock.uptimeMillis()
        injectKeyEvent(
            displayId,
            downTime,
            chordTime,
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_A,
            KeyEvent.META_CTRL_ON,
        )
        injectKeyEvent(
            displayId,
            downTime,
            chordTime,
            KeyEvent.ACTION_UP,
            KeyEvent.KEYCODE_A,
            KeyEvent.META_CTRL_ON,
        )
        injectKeyEvent(
            displayId,
            downTime,
            SystemClock.uptimeMillis(),
            KeyEvent.ACTION_UP,
            KeyEvent.KEYCODE_CTRL_LEFT,
            0,
        )
    }

    private fun injectUndo(displayId: Int) {
        val downTime = SystemClock.uptimeMillis()
        injectKeyEvent(
            displayId,
            downTime,
            downTime,
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_CTRL_LEFT,
            KeyEvent.META_CTRL_ON,
        )
        val chordTime = SystemClock.uptimeMillis()
        injectKeyEvent(
            displayId,
            downTime,
            chordTime,
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_Z,
            KeyEvent.META_CTRL_ON,
        )
        injectKeyEvent(
            displayId,
            downTime,
            chordTime,
            KeyEvent.ACTION_UP,
            KeyEvent.KEYCODE_Z,
            KeyEvent.META_CTRL_ON,
        )
        injectKeyEvent(
            displayId,
            downTime,
            SystemClock.uptimeMillis(),
            KeyEvent.ACTION_UP,
            KeyEvent.KEYCODE_CTRL_LEFT,
            0,
        )
    }

    private fun snapshotFocusedField(displayId: Int) {
        lastFieldSnapshot = runCatching {
            residentUiAutomation().focusedText(displayId)
        }.getOrNull().orEmpty()
        lastFieldHasSnapshot = true
    }

    private fun hideDisplayIme(displayId: Int) {
        val imeDisplays = runCatching { residentUiAutomation().imeDisplayIds() }.getOrDefault(emptyList())
        imeDisplays.forEach { id ->
            if (id == displayId) {
                runCatching { injectKeyPress(id, KeyEvent.KEYCODE_BACK) }
            }
        }
        if (displayId !in imeDisplays) {
            val visibleOnTarget = runCatching {
                residentUiAutomation().imeWindowVisible(displayId)
            }.getOrDefault(false)
            if (visibleOnTarget) {
                runCatching { injectKeyPress(displayId, KeyEvent.KEYCODE_BACK) }
            }
        }
    }

    private fun scheduleImeDismiss(displayId: Int) {
        hideDisplayIme(displayId)
        previewHandler.postDelayed({ hideDisplayIme(displayId) }, 180L)
        previewHandler.postDelayed({ hideDisplayIme(displayId) }, 420L)
    }

    private fun packageOnDisplay(packageName: String, displayId: Int): Boolean {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return false
        return listDisplayTasks().any { snapshot ->
            snapshot.displayId == displayId && pkg in snapshot.packageNames
        }
    }

    private fun launchAppearsOnDisplay(packageName: String, displayId: Int): Boolean {
        return residentUiAutomation().packageVisibleOnDisplay(packageName, displayId)
    }

    private fun waitUntilPackageVisibleOnDisplay(
        packageName: String,
        displayId: Int,
        timeoutMs: Long,
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (launchAppearsOnDisplay(packageName, displayId)) {
                hop(
                    displayId,
                    "launch_window_visible",
                    "pkg=$packageName task=${packageOnDisplay(packageName, displayId)}",
                )
                return true
            }
            SystemClock.sleep(50L)
        }
        val visible = launchAppearsOnDisplay(packageName, displayId)
        hop(
            displayId,
            if (visible) "launch_window_visible" else "launch_window_missing",
            "pkg=$packageName task=${packageOnDisplay(packageName, displayId)}",
        )
        return visible
    }

    private fun relocatePackageToDisplay(packageName: String, displayId: Int) {
        val pkg = packageName.trim()
        listDisplayTasks()
            .filter { snapshot -> pkg in snapshot.packageNames && snapshot.displayId != displayId }
            .forEach { snapshot ->
                snapshot.taskIds.forEach { taskId ->
                    if (taskId > 0) {
                        val moved = moveRootTaskToDisplay(taskId, displayId)
                        hop(
                            displayId,
                            "move_task",
                            "task=$taskId from=${snapshot.displayId} ok=$moved",
                        )
                    }
                }
            }
    }

    @SuppressLint("PrivateApi")
    private fun moveRootTaskToDisplay(taskId: Int, displayId: Int): Boolean {
        return runCatching {
            val atmClass = Class.forName("android.app.ActivityTaskManager")
            val service = atmClass.getMethod("getService").invoke(null) ?: return false
            val method = service.javaClass.methods.firstOrNull { candidate ->
                (candidate.name == "moveRootTaskToDisplay" || candidate.name == "moveTaskToDisplay") &&
                    candidate.parameterTypes.size == 2 &&
                    candidate.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    candidate.parameterTypes[1] == Int::class.javaPrimitiveType
            } ?: return false
            method.invoke(service, taskId, displayId)
            true
        }.getOrDefault(false)
    }

    private fun residentUiAutomation(): AgentModeUiAutomation {
        uiAutomation.attachLooper(previewThread.looper)
        return uiAutomation
    }

    private fun waitForPreviewContent(displayId: Int, timeoutMs: Long) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (!freezePreviewUntilContent.contains(displayId)) return
            SystemClock.sleep(16L)
        }
        hop(displayId, "freeze_timeout_kept")
    }

    private fun hop(displayId: Int, name: String, details: String = "") {
        Log.i(
            AgentModeServiceLogTag,
            buildString {
                append("hop=").append(name)
                if (displayId >= 0) append(" display=").append(displayId)
                if (details.isNotBlank()) append(' ').append(details)
            },
        )
    }

    private fun captureUiautomatorXml(displayId: Int): String {
        val commands = listOf(
            listOf("uiautomator", "dump", "--compressed", "--display", displayId.toString(), "/dev/tty"),
            listOf("uiautomator", "dump", "--compressed", "/dev/tty"),
            listOf("uiautomator", "dump", "--compressed", "/dev/stdout"),
        )
        for (command in commands) {
            val text = runCatching { runProcessOutput(command) }.getOrNull().orEmpty()
            if (text.contains("<hierarchy") || text.contains("<node")) return text
        }
        val tmp = File(privilegedContext.cacheDir, "aether-ui-dump.xml")
        return try {
            runCatching {
                runProcessOutput(listOf("uiautomator", "dump", "--compressed", tmp.absolutePath))
            }
            if (tmp.isFile) tmp.readText() else ""
        } finally {
            tmp.delete()
        }
    }

    private fun screencapJpeg(displayId: Int, maxEdge: Int, quality: Int): ByteArray {
        val commands = listOf(
            listOf("screencap", "-p", "-d", displayId.toString()),
            listOf("screencap", "-p"),
        )
        var png = ByteArray(0)
        for (command in commands) {
            png = runCatching { runProcessBytes(command) }.getOrNull() ?: ByteArray(0)
            if (png.size > 32) break
        }
        if (png.size <= 32) error("screencap produced no image for display $displayId.")
        val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size)
            ?: error("screencap PNG could not be decoded.")
        return try {
            val stream = ByteArrayOutputStream()
            bitmapToJpegStream(bitmap, stream, maxEdge, quality)
            stream.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun runProcessOutput(command: List<String>): String =
        runProcessBytes(command).toString(Charsets.UTF_8)

    private fun runProcessBytes(command: List<String>): ByteArray {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val bytes = process.inputStream.use { it.readBytes() }
        process.waitFor()
        return bytes
    }

    private fun shouldKeepFrozenPreview(displayId: Int, bitmap: Bitmap): Boolean {
        if (!freezePreviewUntilContent.contains(displayId)) return false
        if (isEmptyBlackFrame(bitmap)) return true
        val baseline = freezeFrameSignatures[displayId] ?: return false
        return frameSignature(bitmap) == baseline
    }

    private fun frameSignature(bitmap: Bitmap): Int {
        var hash = 1
        val xs = intArrayOf(
            (bitmap.width / 8).coerceAtLeast(0),
            (bitmap.width / 2).coerceAtLeast(0),
            (bitmap.width * 7 / 8).coerceAtLeast(0),
        )
        val ys = intArrayOf(
            (bitmap.height / 8).coerceAtLeast(0),
            (bitmap.height / 2).coerceAtLeast(0),
            (bitmap.height * 7 / 8).coerceAtLeast(0),
        )
        for (y in ys) {
            val py = y.coerceAtMost(bitmap.height - 1)
            for (x in xs) {
                val px = x.coerceAtMost(bitmap.width - 1)
                hash = 31 * hash + bitmap.getPixel(px, py)
            }
        }
        return hash
    }

    private fun isEmptyBlackFrame(bitmap: Bitmap): Boolean {
        val stepX = (bitmap.width / 8).coerceAtLeast(1)
        val stepY = (bitmap.height / 8).coerceAtLeast(1)
        var samples = 0
        var dark = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val luminance = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
                samples += 1
                if (luminance < 16) dark += 1
                x += stepX
            }
            y += stepY
        }
        return samples > 0 && dark * 100 / samples >= 96
    }

    private fun pasteTextOnDisplay(displayId: Int, text: String) {
        if (!setClipboardText(text)) {
            typeAsciiFallback(displayId, text)
            return
        }
        SystemClock.sleep(50L)
        runCatching { injectKeyPress(displayId, KeyEvent.KEYCODE_PASTE) }
            .onFailure {
                runInputOnDisplay(displayId, "keyevent ${KeyEvent.KEYCODE_PASTE}")
            }
    }

    private fun setClipboardText(text: String): Boolean {
        val ok = java.util.concurrent.atomic.AtomicBoolean(false)
        val latch = java.util.concurrent.CountDownLatch(1)
        val apply = {
            runCatching {
                val clipboard = privilegedContext.getSystemService(android.content.ClipboardManager::class.java)
                clipboard?.setPrimaryClip(
                    android.content.ClipData.newPlainText("aether-agent-mode", text),
                )
                ok.set(clipboard != null)
            }
            Unit
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            apply()
        } else {
            deskUiHandler.post {
                apply()
                latch.countDown()
            }
            runCatching { latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS) }
        }
        if (ok.get()) return true
        val quoted = "'" + text.replace("'", "'\\''") + "'"
        return runCatching {
            runInputCommand("cmd clipboard set-text --user 0 $quoted")
            true
        }.getOrDefault(false)
    }

    private fun typeAsciiFallback(displayId: Int, text: String) {
        val keyMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        val events = keyMap.getEvents(text.toCharArray()) ?: return
        var downTime = SystemClock.uptimeMillis()
        events.forEach { sourceEvent ->
            val now = SystemClock.uptimeMillis()
            if (sourceEvent.action == KeyEvent.ACTION_DOWN) {
                downTime = now
            }
            injectKeyEvent(
                displayId = displayId,
                downTime = downTime,
                eventTime = now,
                action = sourceEvent.action,
                keyCode = sourceEvent.keyCode,
                metaState = sourceEvent.metaState,
                scanCode = sourceEvent.scanCode,
                flags = sourceEvent.flags,
            )
            if (sourceEvent.action == KeyEvent.ACTION_UP) {
                SystemClock.sleep(4L)
            }
        }
    }

    private fun injectMotionEvent(
        displayId: Int,
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
        waitForFinish: Boolean = false,
    ) {
        val properties = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = 1f
                size = 1f
            },
        )
        val event = MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            1,
            properties,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
        injectInputEventOnDisplay(displayId, event, waitForFinish)
    }

    private fun injectKeyEvent(
        displayId: Int,
        downTime: Long,
        eventTime: Long,
        action: Int,
        keyCode: Int,
        metaState: Int,
        scanCode: Int = 0,
        flags: Int = 0,
    ) {
        val event = KeyEvent(
            downTime,
            eventTime,
            action,
            keyCode,
            0,
            metaState,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            scanCode,
            flags,
            InputDevice.SOURCE_KEYBOARD,
        )
        injectInputEventOnDisplay(displayId, event)
    }

    private fun injectInputEventOnDisplay(
        displayId: Int,
        event: InputEvent,
        waitForFinish: Boolean = false,
    ) {
        try {
            setInputEventDisplayId(event, displayId)
            val inputManager = inputManagerInstance()
            val method = inputManager.javaClass.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType,
            )
            val mode = if (waitForFinish) InjectInputEventModeWaitForFinish else InjectInputEventModeAsync
            val injected = method.invoke(inputManager, event, mode) as Boolean
            if (!injected) {
                error("Input event was rejected by Android input manager for display $displayId.")
            }
        } finally {
            if (event is MotionEvent) {
                event.recycle()
            }
        }
    }

    @SuppressLint("BlockedPrivateApi")
    private fun setInputEventDisplayId(event: InputEvent, displayId: Int) {
        val method = InputEvent::class.java.getDeclaredMethod(
            "setDisplayId",
            Int::class.javaPrimitiveType,
        )
        method.isAccessible = true
        method.invoke(event, displayId)
    }

    private fun inputManagerInstance(): Any {
        val inputManagerClass = Class.forName("android.hardware.input.InputManager")
        val getInstance = inputManagerClass.getDeclaredMethod("getInstance")
        getInstance.isAccessible = true
        return getInstance.invoke(null)
            ?: error("Android input manager was not available.")
    }

    private fun parseKeyCode(rawValue: String): Int {
        val normalized = rawValue.trim()
        normalized.toIntOrNull()?.let { return it }
        val direct = KeyEvent.keyCodeFromString(normalized)
        if (direct != KeyEvent.KEYCODE_UNKNOWN) return direct
        val prefixed = KeyEvent.keyCodeFromString("KEYCODE_${normalized.uppercase()}")
        if (prefixed != KeyEvent.KEYCODE_UNKNOWN) return prefixed
        error("Unsupported key code '$rawValue'.")
    }

    private fun recordLaunchedPackage(displayId: Int, packageName: String) {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return
        lastLaunchedPackageByDisplay[displayId] = pkg
        launchedPackagesByDisplay
            .getOrPut(displayId) { ConcurrentHashMap.newKeySet() }
            .add(pkg)
    }

    private fun evictOtherPackagesOnDisplay(displayId: Int, keepPackage: String) {
        val keep = keepPackage.trim()
        val tracked = launchedPackagesByDisplay[displayId].orEmpty() +
            listDisplayTasks()
                .filter { it.displayId == displayId }
                .flatMap { it.packageNames }
        tracked.distinct()
            .filter { it != keep && !isProtectedPackage(it) }
            .forEach { pkg -> evictPackageFromDisplay(displayId, pkg) }
    }

    private fun evictAllThirdPartyOnDisplay(displayId: Int) {
        val tracked = launchedPackagesByDisplay[displayId].orEmpty() +
            listDisplayTasks()
                .filter { it.displayId == displayId }
                .flatMap { it.packageNames }
        tracked.distinct()
            .filter { !isProtectedPackage(it) }
            .forEach { pkg -> evictPackageFromDisplay(displayId, pkg) }
        lastLaunchedPackageByDisplay.remove(displayId)
        launchedPackagesByDisplay.remove(displayId)
    }

    private fun evictPackageFromDisplay(displayId: Int, packageName: String) {
        if (isProtectedPackage(packageName)) return
        val tasks = listDisplayTasks().filter { snapshot ->
            snapshot.displayId == displayId && packageName in snapshot.packageNames
        }
        tasks.forEach { snapshot ->
            snapshot.taskIds.forEach { taskId ->
                if (taskId > 0) removeDisplayTask(taskId)
            }
        }
        hop(displayId, "evict_pkg", "pkg=$packageName tasks=${tasks.size}")
        val stillOnDefault = listDisplayTasks().any { snapshot ->
            snapshot.displayId == Display.DEFAULT_DISPLAY && packageName in snapshot.packageNames
        }
        if (!stillOnDefault) {
            runCatching {
                val am = privilegedContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                am?.killBackgroundProcesses(packageName)
            }
        }
        launchedPackagesByDisplay[displayId]?.remove(packageName)
        if (lastLaunchedPackageByDisplay[displayId] == packageName) {
            lastLaunchedPackageByDisplay.remove(displayId)
        }
    }

    private fun isProtectedPackage(packageName: String): Boolean =
        AgentModeVirtualDisplayPolicy.shouldProtectPackage(
            packageName,
            BuildConfig.APPLICATION_ID,
        )

    private data class DisplayTaskSnapshot(
        val displayId: Int,
        val packageNames: Set<String>,
        val taskIds: List<Int>,
    )

    @SuppressLint("PrivateApi")
    private fun listDisplayTasks(): List<DisplayTaskSnapshot> {
        return runCatching {
            val atmClass = Class.forName("android.app.ActivityTaskManager")
            val service = atmClass.getMethod("getService").invoke(null) ?: return emptyList()
            val method = service.javaClass.methods.firstOrNull { candidate ->
                candidate.name == "getAllRootTaskInfos" && candidate.parameterTypes.isEmpty()
            } ?: return emptyList()
            val list = method.invoke(service) as? List<*> ?: return emptyList()
            list.mapNotNull { info -> parseRootTaskInfo(info) }
        }.getOrDefault(emptyList())
    }

    private fun parseRootTaskInfo(info: Any?): DisplayTaskSnapshot? {
        if (info == null) return null
        val taskId = (readReflectField(info, "taskId") as? Int) ?: 0
        val displayId = (readReflectField(info, "displayId") as? Int) ?: 0
        val packages = linkedSetOf<String>()
        (readReflectField(info, "childTaskNames") as? Array<*>)?.forEach { raw ->
            if (raw is String) {
                val pkg = AgentModeVirtualDisplayPolicy.packageFromTaskName(raw)
                if (pkg.isNotBlank()) packages += pkg
            }
        }
        (readReflectField(info, "topActivity") as? ComponentName)?.packageName?.let { packages += it }
        (readReflectField(info, "baseActivity") as? ComponentName)?.packageName?.let { packages += it }
        val childIds = (readReflectField(info, "childTaskIds") as? IntArray)?.toList().orEmpty()
        val ids = (listOf(taskId) + childIds).filter { it > 0 }.distinct()
        if (packages.isEmpty() && ids.isEmpty()) return null
        return DisplayTaskSnapshot(displayId = displayId, packageNames = packages, taskIds = ids)
    }

    private fun readReflectField(target: Any, name: String): Any? {
        var current: Class<*>? = target.javaClass
        while (current != null) {
            val field = runCatching { current.getDeclaredField(name) }.getOrNull()
            if (field != null) {
                field.isAccessible = true
                return field.get(target)
            }
            current = current.superclass
        }
        return null
    }

    private fun removeDisplayTask(taskId: Int) {
        val am = privilegedContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val removed = runCatching {
            ActivityManager::class.java
                .getMethod("removeTask", Int::class.javaPrimitiveType)
                .invoke(am, taskId) as? Boolean
        }.getOrNull() == true
        if (removed) return
        runCatching {
            ProcessBuilder("am", "task", "remove", taskId.toString())
                .redirectErrorStream(true)
                .start()
                .waitFor()
        }
    }

    companion object {
        const val LivePreviewPixelCopyRequired = "LIVE_PREVIEW_PIXEL_COPY"

        private fun resolveLegacyUserServiceContext(): Context {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentActivityThread = activityThreadClass
                .getDeclaredMethod("currentActivityThread")
                .apply { isAccessible = true }
                .invoke(null)
            val currentApplication = activityThreadClass
                .getDeclaredMethod("currentApplication")
                .apply { isAccessible = true }
                .invoke(null) as? Context
            if (currentApplication != null) return currentApplication
            if (currentActivityThread != null) {
                val systemContext = activityThreadClass
                    .getDeclaredMethod("getSystemContext")
                    .apply { isAccessible = true }
                    .invoke(currentActivityThread) as? Context
                if (systemContext != null) return systemContext
            }
            error("Unable to create an Android context for Shizuku Agent Mode service.")
        }
    }
}
