package kira.ditto.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceView
import androidx.core.content.getSystemService
import com.rosan.app_process.AppProcess
import kira.ditto.agentmode.AetherAgentModeShizukuService
import kira.ditto.agentmode.AgentModeDisplaySpec
import kira.ditto.agentmode.AgentModeGlPreviewRenderer
import kira.ditto.agentmode.AgentModeImeGuard
import kira.ditto.agentmode.IAetherAgentModeService
import kira.ditto.agentmode.scaleAgentModeDisplaySpec
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku

private const val FallbackAgentDisplayWidth = 720
private const val FallbackAgentDisplayHeight = 1280
private const val FallbackAgentDisplayDensityDpi = 320
private const val AgentDisplayName = "aether-agent-mode"
private const val AgentModeCaptureExtension = "jpg"
private const val AgentModeCaptureMimeType = "image/jpeg"
private const val AgentModeCaptureMaxEdge = 720
private const val AgentModeCaptureJpegQuality = 55
private const val AgentModeAppLogTag = "AetherAgentMode"
private const val ShizukuPermissionRequestCode = 4201
private const val RootAuthorizationProbeTimeoutMillis = 2_000L
private const val ShizukuUserServiceBindTimeoutMillis = 20_000L
private const val ShizukuUserServiceTag = "aether-agent-mode"
private const val ShizukuUserServiceVersion = 26
private const val PixelCopyTimeoutMillis = 2_000L
private const val CaptureJpegTimeoutMillis = 4_000L

private val ShizukuManagerPackages = listOf(
    "moe.shizuku.privileged.api",
    "moe.shizuku.manager",
)

data class AgentModeDisplayState(
    val isActive: Boolean = false,
    val displayId: Int? = null,
    val width: Int = FallbackAgentDisplayWidth,
    val height: Int = FallbackAgentDisplayHeight,
    val displays: List<AgentModeDisplayInfo> = emptyList(),
    val latestPreviewPath: String = "",
    val latestWorkspacePath: String = "",
    val cursorX: Int? = null,
    val cursorY: Int? = null,
    val cursorAnimationDurationMillis: Int = 220,
    val isLivePreviewActive: Boolean = false,
    val lastUpdatedMillis: Long = 0L,
    val status: String = "",
    val teachingHint: String = "",
    val teachingActive: Boolean = false,
    val listening: Boolean = false,
    val listenTranscript: String = "",
    val listenVisualOnly: Boolean = false,
)

data class AgentModeDisplayInfo(
    val displayId: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val isAetherDisplay: Boolean,
)

data class AgentModeInstalledAppInfo(
    val packageName: String,
    val appName: String,
    val activityName: String,
    val isSystemApp: Boolean,
    val isEnabled: Boolean,
)

data class AgentModePreopenResult(
    val packageName: String,
    val appName: String,
)

enum class AgentModeAuthorizationIssue {
    Disabled,
    Ready,
    ShizukuNotInstalled,
    ShizukuNotRunning,
    ShizukuPermissionMissing,
    ShizukuPermissionDenied,
    RootUnavailable,
    RootPermissionMissing,
    RootPermissionDenied,
    Error,
}

data class AgentModeAuthorizationState(
    val issue: AgentModeAuthorizationIssue = AgentModeAuthorizationIssue.Disabled,
    val detail: String = "",
) {
    val isReady: Boolean
        get() = issue == AgentModeAuthorizationIssue.Ready
}

class AgentModeController(
    private val context: Context,
    private val runtimeWorkspaceFileBridge: RuntimeWorkspaceFileBridge,
    private val diagnosticLogger: AetherDiagnosticLogger = AetherDiagnosticLogger.NoOp,
    private val signalStore: GuiSignalStore? = null,
) {
    private val displayManager = context.getSystemService<DisplayManager>()!!
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val captureMutex = Mutex()
    private val inFlightCaptureJob = java.util.concurrent.atomic.AtomicReference<Job?>(null)
    private val ensureDisplayMutex = Mutex()
    private val shizukuServiceMutex = Mutex()
    private val activeSkipCapture = ThreadLocal.withInitial { false }
    private val activeSkipWorkspace = ThreadLocal.withInitial { false }
    private val activePersistPath = ThreadLocal.withInitial { "" }
    private val activeSomOverlay = ThreadLocal.withInitial { false }
    private val activeUsedBackend = ThreadLocal.withInitial { "" }
    private val activeCropArguments = ThreadLocal<JSONObject?>()
    var prepareShizuku: (suspend () -> Unit)? = null
    var userGestureListener: AgentModeUserGestureListener? = null
    var loadProviderConfigs: () -> List<LlmProviderConfig> = { emptyList() }
    private val additionalUserGestureListeners = CopyOnWriteArrayList<AgentModeUserGestureListener>()
    @Volatile
    private var lastLaunchedPackageName: String = ""
    @Volatile
    private var lastLaunchedAppName: String = ""
    @Volatile
    private var lastSnapshot: GuiSnapshot? = null
    private val stallMutatingCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val activePortalAction = ThreadLocal.withInitial { "" }
    private val activeTargetNode = ThreadLocal<AgentModeUiTree.RichNode?>()
    private val portalMutatingActions = setOf(
        "tap", "swipe", "swipe_left", "swipe_right", "swipe_up", "swipe_down",
        "key", "text", "search", "clear_text", "undo", "back", "home",
        "recents", "overview", "notifications", "force_stop", "click_node",
        "long_press", "double_tap", "pinch", "fling", "launch", "scroll_until",
        "gesture",
    )
    private val teachingActive = AtomicBoolean(false)
    private val teachingFinished = AtomicBoolean(false)
    private val teachingGestures = AtomicInteger(0)
    private val lastTeachingGestureAt = AtomicLong(0L)
    val isTeachingActive: Boolean get() = teachingActive.get()
    private val asrRouter by lazy {
        AsrEngineRouter(context) { loadProviderConfigs() }
    }
    private val listenSession by lazy {
        AgentModeListenSession(context, asrRouter, controllerScope).also { session ->
            session.onTranscriptChanged = { text ->
                val current = _displayState.value
                if (current.listenTranscript != text) {
                    _displayState.value = current.copy(listenTranscript = text)
                }
            }
        }
    }
    private val _displayState = MutableStateFlow(AgentModeDisplayState())
    private val installedPackageCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val _authorizationState = MutableStateFlow(AgentModeAuthorizationState())
    private val shizukuPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == ShizukuPermissionRequestCode) {
                AetherAnalytics.capture(
                    event = "permission result",
                    properties = mapOf(
                        "permission" to "shizuku",
                        "source" to "agent_mode_authorization",
                        "granted" to (grantResult == PackageManager.PERMISSION_GRANTED),
                        "result" to if (grantResult == PackageManager.PERMISSION_GRANTED) "granted" else "denied",
                    ),
                )
                _authorizationState.value = if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    diagnosticLogger.event(
                        category = "agent_mode",
                        event = "shizuku_permission_granted",
                    )
                    AgentModeAuthorizationState(
                        issue = AgentModeAuthorizationIssue.Ready,
                        detail = "Shizuku permission is granted.",
                    )
                } else {
                    diagnosticLogger.event(
                        category = "agent_mode",
                        event = "shizuku_permission_denied",
                        level = "warn",
                    )
                    AgentModeAuthorizationState(
                        issue = AgentModeAuthorizationIssue.ShizukuPermissionDenied,
                        detail = "Shizuku permission was denied. Grant Aether permission in Shizuku before using Agent Mode.",
                    )
                }
            }
        }
    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        if (_authorizationState.value.issue != AgentModeAuthorizationIssue.Disabled) {
            _authorizationState.value = AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.ShizukuNotRunning,
                detail = "Shizuku stopped. Aether will try to restart it.",
            )
        }
        clearShizukuService("Shizuku stopped. Agent Mode virtual display was reset.")
    }

    private var shizukuDisplayId: Int? = null
    private var displayOwnerMethod: AgentModeAuthorizationMethod? = null
    private var displayOwnerBinder: IBinder? = null
    private var shizukuService: IAetherAgentModeService? = null
    private var shizukuServiceArgs: Shizuku.UserServiceArgs? = null
    private var shizukuServiceConnection: ServiceConnection? = null
    private var rootService: IAetherAgentModeService? = null
    private var rootProcess: AppProcess.Terminal? = null
    /**
     * Virtual display renders into a GL-owned SurfaceTexture. A SurfaceView
     * is only an EGL window the compositor draws into; collapsing it never
     * rebinds the virtual display.
     */
    private val glPreviewRenderer = AgentModeGlPreviewRenderer()
    @Volatile
    private var previewSurface: Surface? = null
    private val previewSurfaceLock = Any()
    private val pixelCopyHandler by lazy {
        Handler(HandlerThread("aether-agent-pixelcopy").apply { start() }.looper)
    }

    val displayState: StateFlow<AgentModeDisplayState> = _displayState.asStateFlow()
    val authorizationState: StateFlow<AgentModeAuthorizationState> = _authorizationState.asStateFlow()

    init {
        runCatching {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionResultListener)
        }
        runCatching {
            Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
        }
        val spec = currentDeviceDisplaySpec()
        _displayState.value = AgentModeDisplayState(
            width = spec.width,
            height = spec.height,
        )
    }

    suspend fun execute(
        settings: AppSettings,
        workspaceDirectory: String,
        termuxWorkspaceDirectory: String,
        argumentsJson: String,
    ): String = withContext(Dispatchers.IO) {
        if (!settings.agentModeAuthorizationEnabled) {
            captureAgentModeFailed(
                settings = settings,
                action = "unknown",
                reason = "authorization_disabled",
                message = "Agent Mode is not authorized.",
            )
            return@withContext JSONObject().apply {
                put("ok", false)
                put("errmsg", "Agent Mode is not authorized. Enable it in Settings > Agent Mode first.")
            }.toString()
        }

        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return@withContext invalidArguments("Arguments were not valid JSON.").also {
                captureAgentModeFailed(
                    settings = settings,
                    action = "unknown",
                    reason = "invalid_arguments",
                    message = "Arguments were not valid JSON.",
                )
            }
        val actionsArray = arguments.optJSONArray("actions")
        if (actionsArray != null && actionsArray.length() > 0) {
            val observe = AgentModeCapture.wantsObserve(arguments)
            var last = ""
            val completed = JSONArray()
            for (index in 0 until actionsArray.length()) {
                val step = JSONObject(actionsArray.getJSONObject(index).toString())
                val lastStep = index == actionsArray.length() - 1
                val stepAction = PhoneUiMcp.normalizeAction(step.optString("action"))
                if (!step.has("skip_capture")) {
                    step.put(
                        "skip_capture",
                        !(AgentModeCapture.wantsJpeg(arguments) && lastStep) &&
                            stepAction != "screenshot" &&
                            stepAction != "start",
                    )
                }
                if (observe && lastStep && !step.has("observe")) {
                    step.put("observe", arguments.opt("observe") ?: true)
                }
                last = execute(
                    settings,
                    workspaceDirectory,
                    termuxWorkspaceDirectory,
                    step.toString(),
                )
                val parsed = runCatching { JSONObject(last) }.getOrNull()
                if (parsed?.optBoolean("ok", true) == true) {
                    completed.put(
                        JSONObject()
                            .put("index", index)
                            .put("action", stepAction)
                            .put("ok", true),
                    )
                    continue
                }
                val failed = parsed ?: JSONObject().put("ok", false).put("errmsg", last)
                failed.put("completed_steps", completed)
                failed.put("failed_step", index)
                failed.put("failed_action", stepAction)
                if (failed.optString("code").isBlank()) {
                    failed.put("code", AgentModeSafety.MacroMismatchCode)
                }
                failed.put("consistent", "no")
                failed.put("degraded_to", AgentModeSafety.ReplanDegradedTo)
                GuiFailureSignals.attach(
                    result = failed,
                    action = stepAction,
                    snapshot = lastSnapshot,
                    previous = null,
                )
                return@withContext failed.toString()
            }
            return@withContext last
        }
        val action = PhoneUiMcp.normalizeAction(arguments.optString("action"))
        val observe = AgentModeCapture.wantsObserve(arguments)
        val som = AgentModeCapture.wantsSom(arguments)
        val persistPath = resolveEvidencePersistPath(
            persist = arguments.optBoolean("persist") ||
                arguments.optString("persist_path").isNotBlank() ||
                arguments.optString("persistPath").isNotBlank(),
            persistPath = arguments.optString("persist_path").ifBlank {
                arguments.optString("persistPath")
            },
            nowMillis = System.currentTimeMillis(),
        )
        val skipCapture = if (persistPath.isNotBlank()) {
            false
        } else if (arguments.has("skip_capture")) {
            arguments.optBoolean("skip_capture")
        } else {
            !AgentModeCapture.wantsJpeg(arguments) && action != "screenshot" && action != "start"
        }
        val skipWorkspace = arguments.optBoolean("skip_workspace")
        diagnosticLogger.event(
            category = "agent_mode",
            event = "action_start",
            details = mapOf(
                "action" to action.ifBlank { "unknown" },
                "authorization_method" to settings.agentModeAuthorizationMethod.storageValue,
                "display_active" to _displayState.value.isActive,
            ),
        )

        activeSkipCapture.set(skipCapture)
        activeSkipWorkspace.set(skipWorkspace)
        activePersistPath.set(persistPath)
        activeSomOverlay.set(som)
        activeUsedBackend.set("")
        activePortalAction.set(action)
        if (action == "screenshot") {
            activeCropArguments.set(arguments)
        } else {
            activeCropArguments.remove()
        }
        try {
        runCatching {
            when (action) {
            "start" -> {
                ensureDisplay(settings)
                    captureAfterDelay(
                        settings,
                        workspaceDirectory,
                        termuxWorkspaceDirectory,
                        delayMillis = 0,
                    )
            }
            "status" -> statusResult(settings)
            "list_apps", "apps", "installed_apps" -> listInstalledAppsResult(settings, arguments)
            "launch" -> {
                ensureDisplay(settings)
                val target = arguments.optString("target").trim()
                if (target.isBlank()) {
                    invalidArguments("Missing required 'target' argument.")
                } else {
                    val packageName = launchTarget(settings, target)
                    if (AgentModeSafety.shouldBlockLaunch(packageName)) {
                        gatedError(
                            AgentModeSafety.ProtectedWindowCode,
                            "Refusing to launch protected package $packageName.",
                        )
                    } else {
                    lastLaunchedPackageName = packageName
                    lastLaunchedAppName = currentInstalledApps(settings)
                        .firstOrNull { it.packageName == packageName }
                        ?.appName
                        .orEmpty()
                        .ifBlank { target }
                    hideHostIme()
                    val displayId = currentManagedDisplayId(settings)
                    val visibleOnVirtual = displayId != null &&
                        runCatching {
                            requireAgentModeService(settings).packageVisibleOnDisplay(displayId, packageName)
                        }.getOrDefault(false)
                    if (!visibleOnVirtual) {
                        JSONObject()
                            .put("ok", false)
                            .put("code", AgentModeSafety.StalledCode)
                            .put(
                                "errmsg",
                                "Launch of $packageName did not appear on the virtual display " +
                                    "(it may have opened on the real screen). Do not assume it is in " +
                                    "the foreground. Launch again with the package name, then dump_tree.",
                            )
                            .put("package_name", packageName)
                            .put("target", target)
                            .put("consistent", "no")
                            .put("degraded_to", AgentModeSafety.ReplanDegradedTo)
                            .toString()
                    } else {
                    captureAfterDelay(
                        settings,
                        workspaceDirectory,
                        termuxWorkspaceDirectory,
                        delayMillis = if (skipCapture) 0 else 180,
                    )
                    }
                    }
                }
            }
            "tap" -> {
                val displayId = ensureDisplay(settings)
                val x = normalizedX(arguments.optDouble("x", Double.NaN))
                val y = normalizedY(arguments.optDouble("y", Double.NaN))
                if (x == null || y == null) {
                    invalidArguments("Both 'x' and 'y' are required, using 0..1000 screen coordinates.")
                } else {
                    requireAgentModeService(settings).tap(displayId, x, y)
                    dismissVirtualIme(settings, displayId)
                    updateCursorPosition(x, y, animationDurationMillis = 80)
                    captureAfterDelay(
                        settings,
                        workspaceDirectory,
                        termuxWorkspaceDirectory,
                        delayMillis = 40,
                    )
                }
            }
            "swipe", "swipe_left", "swipe_right", "swipe_up", "swipe_down" -> {
                val displayId = ensureDisplay(settings)
                val durationMs = arguments.optInt("duration_ms", arguments.optInt("durationMs", 320))
                    .coerceIn(80, 10_000)
                val endpoints = resolveSwipeEndpoints(settings, arguments, action)
                if (endpoints == null) {
                    invalidArguments(
                        "Pass x1,y1,x2,y2 in 0..1000 coordinates, or use swipe_left/swipe_right/" +
                            "swipe_up/swipe_down (or direction=left|right|up|down).",
                    )
                } else {
                    val (start, end) = endpoints
                    updateCursorPosition(start.first, start.second, animationDurationMillis = 80)
                    controllerScope.launch {
                        delay(40)
                        updateCursorPosition(end.first, end.second, animationDurationMillis = durationMs)
                    }
                    requireAgentModeService(settings).swipe(
                        displayId,
                        start.first,
                        start.second,
                        end.first,
                        end.second,
                        durationMs,
                    )
                    dismissVirtualIme(settings, displayId)
                    captureAfterDelay(
                        settings,
                        workspaceDirectory,
                        termuxWorkspaceDirectory,
                        delayMillis = durationMs.toLong() + 80L,
                    )
                }
            }
            "search" -> {
                val displayId = ensureDisplay(settings)
                val query = arguments.optString("text").ifBlank { arguments.optString("query") }.trim()
                if (query.isBlank()) {
                    invalidArguments("Missing required 'text' (or query) for search.")
                } else {
                    val snapshot = lastSnapshot ?: runCatching { loadPortalSnapshot(settings) }.getOrNull()
                    val field = AgentModeUiTree.findSearchField(snapshot?.nodes.orEmpty())
                    if (field != null && !field.focused) {
                        val locator = when {
                            field.clickIndex > 0 -> "#${field.clickIndex}"
                            field.label.isNotBlank() -> field.label
                            else -> "搜索"
                        }
                        runCatching { requireAgentModeService(settings).clickNode(displayId, locator) }
                        delay(160)
                    } else if (field == null) {
                        runCatching { requireAgentModeService(settings).clickNode(displayId, "搜索") }
                        delay(160)
                    }
                    requireAgentModeService(settings).text(displayId, query)
                    delay(40)
                    requireAgentModeService(settings).key(displayId, "KEYCODE_ENTER")
                    dismissVirtualIme(settings, displayId)
                    captureAfterDelay(
                        settings,
                        workspaceDirectory,
                        termuxWorkspaceDirectory,
                        delayMillis = 200,
                    )
                }
            }
            "key" -> {
                val displayId = ensureDisplay(settings)
                val keyCode = arguments.optString("key").trim()
                if (keyCode.isBlank()) {
                    invalidArguments("Missing required 'key' argument.")
                } else {
                    requireAgentModeService(settings).key(displayId, keyCode)
                    dismissVirtualIme(settings, displayId)
                    captureAfterDelay(
                        settings,
                        workspaceDirectory,
                        termuxWorkspaceDirectory,
                        delayMillis = 80,
                    )
                }
            }
            "text" -> {
                val displayId = ensureDisplay(settings)
                val text = arguments.optString("text")
                if (text.isBlank()) {
                    invalidArguments("Missing required 'text' argument.")
                } else {
                    val snapshot = lastSnapshot ?: runCatching { loadPortalSnapshot(settings) }.getOrNull()
                    val focusedPkg = snapshot?.nodes
                        ?.firstOrNull { it.focused && it.password }
                        ?.packageName
                        .orEmpty()
                        .ifBlank { snapshot?.packageName.orEmpty() }
                    if (snapshot != null && AgentModeSafety.focusedPassword(snapshot.nodes) &&
                        AgentModeSafety.isPasswordManagerPackage(focusedPkg)
                    ) {
                        gatedError(
                            AgentModeSafety.ProtectedWindowCode,
                            "Refusing to type into a password-manager field.",
                        )
                    } else {
                    setHostClipboard(text)
                    requireAgentModeService(settings).text(displayId, text)
                    dismissVirtualIme(settings, displayId)
                    captureAfterDelay(
                        settings,
                        workspaceDirectory,
                        termuxWorkspaceDirectory,
                        delayMillis = 40,
                    )
                    }
                }
            }
            "clear_text" -> {
                val displayId = ensureDisplay(settings)
                requireAgentModeService(settings).clearText(displayId)
                dismissVirtualIme(settings, displayId)
                captureAfterDelay(
                    settings,
                    workspaceDirectory,
                    termuxWorkspaceDirectory,
                    delayMillis = 40,
                )
            }
            "undo" -> {
                val displayId = ensureDisplay(settings)
                requireAgentModeService(settings).undo(displayId)
                dismissVirtualIme(settings, displayId)
                captureAfterDelay(
                    settings,
                    workspaceDirectory,
                    termuxWorkspaceDirectory,
                    delayMillis = 40,
                )
            }
            "back" -> sendSystemKey(
                settings,
                workspaceDirectory,
                termuxWorkspaceDirectory,
                "KEYCODE_BACK",
            )
            "home" -> sendSystemKey(
                settings,
                workspaceDirectory,
                termuxWorkspaceDirectory,
                "KEYCODE_HOME",
            )
            "recents", "overview" -> sendSystemKey(
                settings,
                workspaceDirectory,
                termuxWorkspaceDirectory,
                "KEYCODE_APP_SWITCH",
            )
            "notifications" -> sendSystemKey(
                settings,
                workspaceDirectory,
                termuxWorkspaceDirectory,
                "KEYCODE_NOTIFICATION",
            )
            "force_stop" -> {
                    val packageName = arguments.optString("target").trim()
                if (packageName.isBlank()) {
                    invalidArguments("Missing required 'target' argument.")
                } else {
                    ensureDisplay(settings)
                    requireAgentModeService(settings).runInputCommand(
                        "am force-stop --user 0 ${shellQuote(packageName)}",
                    )
                    captureAfterDelay(
                        settings,
                        workspaceDirectory,
                        termuxWorkspaceDirectory,
                        delayMillis = 400,
                    )
                }
            }
            "screenshot" -> {
                ensureDisplay(settings)
                captureAfterDelay(
                    settings,
                    workspaceDirectory,
                    termuxWorkspaceDirectory,
                    delayMillis = 0,
                )
            }
            "list_targets", "targets", "click_targets" -> {
                ensureDisplay(settings)
                val listed = listClickTargets(
                    settings,
                    simplify = arguments.optBoolean("simplify", false),
                    query = arguments.optString("query"),
                    region = arguments.optString("region"),
                )
                if (!observe) {
                    listed
                } else {
                    val captured = JSONObject(
                        captureAfterDelay(
                            settings,
                            workspaceDirectory,
                            termuxWorkspaceDirectory,
                            delayMillis = 0,
                        ),
                    )
                    val listedJson = JSONObject(listed)
                    captured.put("click_targets", listedJson.optJSONArray("click_targets"))
                    captured.put("click_targets_text", listedJson.optString("click_targets_text"))
                    captured.put("click_target_count", listedJson.optInt("click_target_count"))
                    captured.put("cluttered", listedJson.optBoolean("cluttered"))
                    captured.put("ui_tree", listedJson.optString("ui_tree"))
                    captured.toString()
                }
            }
            "dump_tree" -> {
                ensureDisplay(settings)
                dumpTreePaged(settings, arguments)
            }
            "wait_idle" -> {
                val displayId = ensureDisplay(settings)
                val quiet = arguments.optInt("quiet_ms", arguments.optInt("quietMs", 50))
                val timeout = arguments.optInt("timeout_ms", arguments.optInt("timeoutMs", 800))
                val stable = requireAgentModeService(settings).waitUntilLayoutStable(displayId, quiet, timeout)
                JSONObject()
                    .put("ok", stable)
                    .put("idle", stable)
                    .put("stdout", if (stable) "idle" else "timeout")
                    .toString()
            }
            "wait_for_label" -> {
                val displayId = ensureDisplay(settings)
                val label = arguments.optString("text").ifBlank { arguments.optString("query") }
                    .ifBlank { arguments.optString("target") }
                if (label.isBlank()) {
                    invalidArguments("Missing required 'text' (or query/target) for wait_for_label.")
                } else {
                    val timeout = arguments.optInt("timeout_ms", arguments.optInt("timeoutMs", 2_000))
                    val found = requireAgentModeService(settings).waitForLabel(displayId, label, timeout)
                    JSONObject()
                        .put("ok", found)
                        .put("found", found)
                        .put("label", label)
                        .put("stdout", if (found) "found" else "timeout")
                        .toString()
                }
            }
            "click_node" -> {
                val displayId = ensureDisplay(settings)
                val query = arguments.optString("query").ifBlank {
                    arguments.optString("target").ifBlank { arguments.optString("text") }
                }
                if (query.isBlank()) {
                    invalidArguments("Missing required 'query' for click_node.")
                } else {
                    val requestedSnapshot = arguments.optString("snapshot_id")
                    val liveSnapshot = lastSnapshot ?: runCatching {
                        loadPortalSnapshot(settings, alreadyStable = true)
                    }.getOrNull()
                    if (requestedSnapshot.isNotBlank() && liveSnapshot?.id != requestedSnapshot) {
                        gatedError(
                            AgentModeSafety.StaleSnapshotCode,
                            "snapshot_id $requestedSnapshot is stale; dump_tree again before click_node.",
                        )
                    } else {
                        val nodes = liveSnapshot?.nodes.orEmpty()
                        val target = AgentModePortal.resolveTarget(nodes, query)
                        if (target != null &&
                            AgentModeSafety.looksSensitiveLabel(target.label.ifBlank { target.text }) &&
                            !arguments.optBoolean("confirm")
                        ) {
                            gatedError(
                                AgentModeSafety.SensitiveActionCode,
                                "Sensitive control '${target.label}'. Pass confirm=true to proceed.",
                            )
                        } else {
                            activeTargetNode.set(target)
                            val clicked = requireAgentModeService(settings).clickNode(
                                displayId,
                                AgentModePortal.clickQueryForNode(query, target),
                            )
                            var usedBackend = if (clicked) "a11y" else ""
                            if (!clicked) {
                                val x = target?.centerX
                                val y = target?.centerY
                                if (x != null && y != null) {
                                    val tapX = normalizedX(x.toDouble()) ?: x
                                    val tapY = normalizedY(y.toDouble()) ?: y
                                    requireAgentModeService(settings).tap(displayId, tapX, tapY)
                                    updateCursorPosition(tapX, tapY, animationDurationMillis = 80)
                                    usedBackend = "inject_tap"
                                }
                            }
                            if (usedBackend.isBlank()) {
                                JSONObject()
                                    .put("ok", false)
                                    .put("errmsg", "click_node did not match '$query'.")
                                    .put("consistent", "no")
                                    .toString()
                            } else {
                                activeUsedBackend.set(usedBackend)
                                dismissVirtualIme(settings, displayId)
                                captureAfterDelay(
                                    settings,
                                    workspaceDirectory,
                                    termuxWorkspaceDirectory,
                                    delayMillis = 40,
                                )
                            }
                        }
                    }
                }
            }
            "long_press" -> {
                val displayId = ensureDisplay(settings)
                val x = normalizedX(arguments.optDouble("x", Double.NaN))
                val y = normalizedY(arguments.optDouble("y", Double.NaN))
                if (x == null || y == null) {
                    invalidArguments("Both 'x' and 'y' are required, using 0..1000 screen coordinates.")
                } else {
                    val duration = arguments.optInt("duration_ms", arguments.optInt("durationMs", 600))
                    requireAgentModeService(settings).longPress(displayId, x, y, duration)
                    updateCursorPosition(x, y, animationDurationMillis = 80)
                    dismissVirtualIme(settings, displayId)
                    captureAfterDelay(settings, workspaceDirectory, termuxWorkspaceDirectory, delayMillis = 40)
                }
            }
            "double_tap" -> {
                val displayId = ensureDisplay(settings)
                val x = normalizedX(arguments.optDouble("x", Double.NaN))
                val y = normalizedY(arguments.optDouble("y", Double.NaN))
                if (x == null || y == null) {
                    invalidArguments("Both 'x' and 'y' are required, using 0..1000 screen coordinates.")
                } else {
                    requireAgentModeService(settings).doubleTap(displayId, x, y)
                    updateCursorPosition(x, y, animationDurationMillis = 80)
                    dismissVirtualIme(settings, displayId)
                    captureAfterDelay(settings, workspaceDirectory, termuxWorkspaceDirectory, delayMillis = 40)
                }
            }
            "pinch" -> {
                val displayId = ensureDisplay(settings)
                val cx = normalizedX(arguments.optDouble("x", Double.NaN))
                val cy = normalizedY(arguments.optDouble("y", Double.NaN))
                val startSpan = normalizedX(arguments.optDouble("start_span", arguments.optDouble("startSpan", 180.0)))
                val endSpan = normalizedX(arguments.optDouble("end_span", arguments.optDouble("endSpan", 60.0)))
                if (cx == null || cy == null || startSpan == null || endSpan == null) {
                    invalidArguments("pinch needs x, y, start_span, and end_span in 0..1000 coordinates.")
                } else {
                    val duration = arguments.optInt("duration_ms", arguments.optInt("durationMs", 280))
                    requireAgentModeService(settings).pinch(displayId, cx, cy, startSpan, endSpan, duration)
                    updateCursorPosition(cx, cy, animationDurationMillis = 80)
                    dismissVirtualIme(settings, displayId)
                    captureAfterDelay(settings, workspaceDirectory, termuxWorkspaceDirectory, delayMillis = duration.toLong().coerceAtMost(160L))
                }
            }
            "fling" -> {
                val displayId = ensureDisplay(settings)
                val duration = arguments.optInt("duration_ms", arguments.optInt("durationMs", 90)).coerceIn(40, 240)
                val endpoints = resolveSwipeEndpoints(settings, arguments, action)
                if (endpoints == null) {
                    invalidArguments(
                        "fling needs x1,y1,x2,y2 or direction=left|right|up|down in 0..1000 coordinates.",
                    )
                } else {
                    val (start, end) = endpoints
                    requireAgentModeService(settings).swipe(
                        displayId,
                        start.first,
                        start.second,
                        end.first,
                        end.second,
                        duration,
                    )
                    updateCursorPosition(end.first, end.second, animationDurationMillis = duration)
                    dismissVirtualIme(settings, displayId)
                    captureAfterDelay(settings, workspaceDirectory, termuxWorkspaceDirectory, delayMillis = 40)
                }
            }
            "scroll_until" -> {
                val displayId = ensureDisplay(settings)
                val label = arguments.optString("text").ifBlank { arguments.optString("query") }
                    .ifBlank { arguments.optString("target") }
                if (label.isBlank()) {
                    invalidArguments("Missing required 'text' (or query) for scroll_until.")
                } else {
                    val maxSwipes = arguments.optInt("max_swipes", 6).coerceIn(1, 12)
                    var found = requireAgentModeService(settings).waitForLabel(displayId, label, 200)
                    var swipes = 0
                    val direction = arguments.optString("direction").ifBlank { "up" }
                    val swipe = scrollSwipeForDisplay(settings, direction)
                    while (!found && swipes < maxSwipes) {
                        val x1 = normalizedX(swipe.first.first.toDouble()) ?: 500
                        val y1 = normalizedY(swipe.first.second.toDouble()) ?: 780
                        val x2 = normalizedX(swipe.second.first.toDouble()) ?: x1
                        val y2 = normalizedY(swipe.second.second.toDouble()) ?: 220
                        requireAgentModeService(settings).swipe(displayId, x1, y1, x2, y2, 320)
                        swipes += 1
                        found = requireAgentModeService(settings).waitForLabel(displayId, label, 400)
                    }
                    captureAfterDelay(settings, workspaceDirectory, termuxWorkspaceDirectory, delayMillis = 40).let { raw ->
                        JSONObject(raw)
                            .put("found", found)
                            .put("swipes", swipes)
                            .put("label", label)
                            .put("ok", found)
                            .toString()
                    }
                }
            }
            "request_teaching" -> {
                ensureDisplay(settings)
                startTeaching(
                    arguments.optString("text").ifBlank {
                        arguments.optString("hint")
                    },
                )
            }
            "teaching_status" -> teachingStatusResult()
            "finish_teaching" -> {
                finishTeaching()
                teachingStatusResult()
            }
            "listen_start" -> {
                ensureDisplay(settings)
                val duration = arguments.optInt(
                    "duration_sec",
                    arguments.optInt("durationSec", 0),
                )
                val languageTag = arguments.optString("language").trim().ifBlank { null }
                val started = listenSession.start(
                    settings = settings,
                    service = requireAgentModeService(settings),
                    requestedDurationSec = duration,
                    languageTag = languageTag,
                )
                _displayState.value = _displayState.value.copy(
                    listening = started.optBoolean("ok") && started.optBoolean("listening"),
                    listenVisualOnly = started.optBoolean("visual_only"),
                    listenTranscript = started.optString("transcript"),
                )
                started.toString()
            }
            "listen_status" -> {
                val status = listenSession.status()
                _displayState.value = _displayState.value.copy(
                    listening = status.optBoolean("listening"),
                    listenTranscript = status.optString("transcript"),
                    listenVisualOnly = false,
                )
                status.toString()
            }
            "listen_stop" -> {
                val stopped = listenSession.stop(
                    runCatching { requireAgentModeService(settings) }.getOrNull(),
                )
                _displayState.value = _displayState.value.copy(
                    listening = false,
                    listenVisualOnly = false,
                    listenTranscript = stopped.optString("transcript"),
                )
                stopped.toString()
            }
            "stop" -> {
                releaseDisplay()
                JSONObject().apply {
                    put("ok", true)
                    put("stdout", "Agent Mode virtual display stopped.")
                }.toString()
            }
            else -> invalidArguments("Unsupported action '$action'.").also {
                captureAgentModeFailed(
                    settings = settings,
                    action = action.ifBlank { "unknown" },
                    reason = "unsupported_action",
                    message = "Unsupported action '$action'.",
                )
            }
            }.also {
                val result = runCatching { JSONObject(it) }.getOrNull()
                diagnosticLogger.event(
                    category = "agent_mode",
                    event = "action_end",
                    level = if (result?.optBoolean("ok", true) == false) "warn" else "info",
                    details = mapOf(
                        "action" to action.ifBlank { "unknown" },
                        "ok" to (result?.optBoolean("ok", true) ?: true),
                        "display_id" to _displayState.value.displayId,
                        "display_active" to _displayState.value.isActive,
                        "message" to result?.optString("errmsg").orEmpty(),
                        "failure_code" to GuiFailureSignals.failureCode(result ?: JSONObject()),
                        "repair_strategy" to result?.optJSONObject("failure")?.optString("repair_strategy").orEmpty(),
                        "snapshot_id" to result?.optString("snapshot_id").orEmpty(),
                        "tree_diff_changed" to (result?.optJSONObject("tree_diff")?.optBoolean("changed") == true),
                    ),
                )
            }
        }.getOrElse { throwable ->
            captureAgentModeFailed(
                settings = settings,
                action = action.ifBlank { "unknown" },
                reason = "exception",
                message = throwable.message ?: throwable.javaClass.simpleName,
            )
            val interruptCode = when {
                throwable is CancellationException -> AgentModeSafety.CaptureTimeoutCode
                else -> throwable.message
                    ?.substringBefore(':')
                    ?.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{3,}")) }
            }
            toolError(
                message = sanitizeAgentModeError(throwable.message),
                action = action,
                code = interruptCode,
            )
        }
        } finally {
            activeSkipCapture.remove()
            activeSkipWorkspace.remove()
            activePersistPath.remove()
            activeSomOverlay.remove()
            activeUsedBackend.remove()
            activePortalAction.remove()
            activeTargetNode.remove()
            activeCropArguments.remove()
        }
    }

    suspend fun refreshAuthorization(settings: AppSettings) {
        if (
            shizukuDisplayId != null &&
            displayOwnerMethod != null &&
            displayOwnerMethod != settings.agentModeAuthorizationMethod
        ) {
            releaseDisplay("Virtual display reset because Agent Mode authorization method changed.")
        }
        _authorizationState.value = inspectAuthorization(settings).also { state ->
            diagnosticLogger.event(
                category = "agent_mode",
                event = "authorization_refreshed",
                level = if (state.isReady || state.issue == AgentModeAuthorizationIssue.Disabled) "info" else "warn",
                details = mapOf(
                    "issue" to state.issue.name,
                    "detail" to state.detail,
                    "method" to settings.agentModeAuthorizationMethod.storageValue,
                    "enabled" to settings.agentModeAuthorizationEnabled,
                ),
            )
        }
    }

    fun requestShizukuPermission(): AgentModeAuthorizationState {
        val current = inspectShizukuAuthorization()
        if (current.issue == AgentModeAuthorizationIssue.Ready) {
            _authorizationState.value = current
            return current
        }
        if (
            current.issue != AgentModeAuthorizationIssue.ShizukuPermissionMissing &&
            current.issue != AgentModeAuthorizationIssue.ShizukuPermissionDenied
        ) {
            _authorizationState.value = current
            return current
        }

        return runCatching {
            AetherAnalytics.capture(
                event = "permission requested",
                properties = mapOf(
                    "permission" to "shizuku",
                    "source" to "agent_mode_authorization",
                    "current_issue" to current.issue.name.lowercase(),
                ),
            )
            Shizuku.requestPermission(ShizukuPermissionRequestCode)
            AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.ShizukuPermissionMissing,
                detail = "Confirm the Shizuku permission prompt, then refresh Agent Mode status.",
            )
        }.getOrElse { throwable ->
            AetherAnalytics.capture(
                event = "permission result",
                properties = mapOf(
                    "permission" to "shizuku",
                    "source" to "agent_mode_authorization",
                    "granted" to false,
                    "result" to "request_failed",
                    "error" to (throwable.message ?: throwable.javaClass.simpleName),
                ),
            )
            AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.Error,
                detail = throwable.message ?: "Failed to request Shizuku permission.",
            )
        }.also { _authorizationState.value = it }
    }

    private suspend fun ensureDisplay(settings: AppSettings): Int = ensureDisplayMutex.withLock {
        val service = requireAgentModeService(settings)
        val serviceBinder = service.asBinder()
        shizukuDisplayId?.let { displayId ->
            if (isCurrentDisplayOwner(settings, serviceBinder)) {
                return@withLock displayId
            }
            releaseDisplay("Virtual display reset because Agent Mode authorization service changed.")
        }
        val displaySpec = currentDeviceDisplaySpec()
        val boundSurface = runCatching {
            acquirePreviewSurface(displaySpec.width, displaySpec.height)
        }.getOrNull()?.takeIf { it.isValid }
        Log.i(
            AgentModeAppLogTag,
            "hop=ensure_display surface=${boundSurface != null}",
        )
        val displayId = if (boundSurface != null) {
            service.createDisplay(
                AgentDisplayName,
                displaySpec.width,
                displaySpec.height,
                displaySpec.densityDpi,
                boundSurface,
            )
        } else {
            diagnosticLogger.event(
                category = "agent_mode",
                event = "preview_surface_missing",
                level = "warn",
                details = mapOf("fallback" to "imagereader"),
            )
            service.createOwnedDisplay(
                AgentDisplayName,
                displaySpec.width,
                displaySpec.height,
                displaySpec.densityDpi,
            )
        }
        shizukuDisplayId = displayId
        displayOwnerMethod = settings.agentModeAuthorizationMethod
        displayOwnerBinder = serviceBinder
        diagnosticLogger.event(
            category = "agent_mode",
            event = "display_created",
            details = mapOf(
                "display_id" to displayId,
                "width" to displaySpec.width,
                "height" to displaySpec.height,
                "density_dpi" to displaySpec.densityDpi,
                "method" to settings.agentModeAuthorizationMethod.storageValue,
                "surface_bound" to (boundSurface != null),
            ),
        )
        _displayState.value = AgentModeDisplayState(
            isActive = true,
            displayId = displayId,
            width = displaySpec.width,
            height = displaySpec.height,
            displays = currentDisplays(settings, displayId),
            isLivePreviewActive = boundSurface != null,
            status = "${settings.agentModeAuthorizationMethod.displayName} virtual display ready",
            lastUpdatedMillis = System.currentTimeMillis(),
        )
        if (boundSurface == null) {
            attachCurrentPreviewSurface(settings, displayId)
        }
        runCatching { service.launchHomeOnDisplay(displayId) }
        displayId
    }

    fun stopDisplay() {
        releaseDisplay()
    }

    fun bindPreviewSurfaceView(view: SurfaceView?) {
        // SurfaceView is an EGL window only. The holder callback attaches the
        // window surface; this bind is kept so Compose can track the view.
        if (view == null) return
        view.holder.surface?.takeIf { it.isValid }?.let { surface ->
            glPreviewRenderer.attachOutput(surface)
        }
    }

    /**
     * Returns the surface the virtual display should render into, creating the
     * GL EXTERNAL_OES producer on first use. Never swapped afterwards: Huawei
     * EMUI blanks a virtual display whose surface is replaced.
     */
    private fun acquirePreviewSurface(width: Int, height: Int): Surface {
        synchronized(previewSurfaceLock) {
            val existing = previewSurface?.takeIf { it.isValid }
                ?: glPreviewRenderer.producerSurfaceOrNull()
            if (existing != null && existing.isValid) {
                previewSurface = existing
                return existing
            }
            val surface = glPreviewRenderer.ensureProducerSurface(width, height)
            previewSurface = surface
            return surface
        }
    }

    suspend fun injectUserTap(settings: AppSettings, x: Int, y: Int) = withContext(Dispatchers.IO) {
        if (!isTeachingActive) return@withContext
        val displayId = currentManagedDisplayId(settings) ?: return@withContext
        val boundedX = x.coerceIn(0, (_displayState.value.width - 1).coerceAtLeast(0))
        val boundedY = y.coerceIn(0, (_displayState.value.height - 1).coerceAtLeast(0))
        requireAgentModeService(settings).tap(displayId, boundedX, boundedY)
        dismissVirtualIme(settings, displayId)
        hideHostIme()
        updateCursorPosition(boundedX, boundedY, animationDurationMillis = 120)
        val state = _displayState.value
        notifyUserTap(
            normalizedX = toNormalizedAxis(boundedX, state.width),
            normalizedY = toNormalizedAxis(boundedY, state.height),
        )
        captureTeachingPageIfNeeded(settings)
    }

    suspend fun injectUserSwipe(
        settings: AppSettings,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int = 280,
    ) = withContext(Dispatchers.IO) {
        if (!isTeachingActive) return@withContext
        val displayId = currentManagedDisplayId(settings) ?: return@withContext
        val width = _displayState.value.width
        val height = _displayState.value.height
        val startX = x1.coerceIn(0, (width - 1).coerceAtLeast(0))
        val startY = y1.coerceIn(0, (height - 1).coerceAtLeast(0))
        val endX = x2.coerceIn(0, (width - 1).coerceAtLeast(0))
        val endY = y2.coerceIn(0, (height - 1).coerceAtLeast(0))
        val duration = durationMs.coerceIn(50, 10_000)
        updateCursorPosition(startX, startY, animationDurationMillis = 80)
        controllerScope.launch {
            delay(40)
            updateCursorPosition(endX, endY, animationDurationMillis = duration)
        }
        requireAgentModeService(settings).swipe(displayId, startX, startY, endX, endY, duration)
        dismissVirtualIme(settings, displayId)
        hideHostIme()
        notifyUserSwipe(
            normalizedX1 = toNormalizedAxis(startX, width),
            normalizedY1 = toNormalizedAxis(startY, height),
            normalizedX2 = toNormalizedAxis(endX, width),
            normalizedY2 = toNormalizedAxis(endY, height),
            durationMs = duration,
        )
        captureTeachingPageIfNeeded(settings)
    }

    /**
     * [surface] is the SurfaceView window. GLES draws the virtual display into
     * it; the virtual display itself is never rebound.
     */
    suspend fun attachPreviewSurface(settings: AppSettings, surface: Surface) = withContext(Dispatchers.IO) {
        if (surface.isValid) {
            glPreviewRenderer.attachOutput(surface)
        }
        if (_displayState.value.isActive) return@withContext
        runCatching { ensureDisplay(settings) }
    }

    fun detachPreviewSurface(surface: Surface) {
        glPreviewRenderer.detachOutput(surface)
    }

    suspend fun refreshDisplays(settings: AppSettings) {
        currentManagedDisplayId(settings)
        val state = _displayState.value
        _displayState.value = state.copy(
            displays = currentDisplays(settings, state.displayId),
            lastUpdatedMillis = System.currentTimeMillis(),
        )
    }

    private suspend fun attachCurrentPreviewSurface(settings: AppSettings, displayId: Int) {
        val surface = previewSurface?.takeIf { it.isValid } ?: return
        runCatching {
            requireAgentModeService(settings).attachPreviewSurface(displayId, surface)
        }.onSuccess {
            val state = _displayState.value
            if (state.displayId == displayId && state.isActive) {
                _displayState.value = state.copy(
                    isLivePreviewActive = true,
                    status = "Streaming virtual display",
                    lastUpdatedMillis = System.currentTimeMillis(),
                )
            }
        }.onFailure { throwable ->
            val state = _displayState.value
            if (state.displayId == displayId && state.isActive) {
                _displayState.value = state.copy(
                    isLivePreviewActive = false,
                    status = throwable.message ?: "Live preview surface is not available.",
                    lastUpdatedMillis = System.currentTimeMillis(),
                )
            }
        }
    }

    private suspend fun launchTarget(
        settings: AppSettings,
        target: String,
    ): String {
        val displayId = ensureDisplay(settings)
        val launchPackage = resolveLaunchPackage(settings, target)
            ?: error("No launchable app matched '$target'. Try a package name such as com.android.chrome, or a shorter app label.")
        requireAgentModeService(settings).launchPackage(launchPackage, displayId)
        return launchPackage
    }

    /**
     * Start the virtual display and, when the user named a unique installed
     * app, launch it before the phone subagent is spawned.
     */
    suspend fun preopenForGoal(
        settings: AppSettings,
        goal: String,
    ): AgentModePreopenResult? = withContext(Dispatchers.IO) {
        val match = inferUniqueLaunchTarget(settings, goal)
        if (match == null) {
            runCatching { ensureDisplay(settings) }
            return@withContext null
        }
        runCatching {
            val launched = launchTarget(settings, match.packageName)
            val displayId = currentManagedDisplayId(settings)
            val visible = displayId != null &&
                runCatching {
                    requireAgentModeService(settings).packageVisibleOnDisplay(displayId, launched)
                }.getOrDefault(false)
            diagnosticLogger.event(
                category = "agent_mode",
                event = if (visible) "preopen_launch" else "preopen_not_visible",
                details = mapOf(
                    "app_name" to match.appName,
                    "package_name" to match.packageName,
                    "visible_on_virtual" to visible,
                ),
            )
            match.takeIf { visible }
        }.getOrNull()
    }

    private suspend fun inferUniqueLaunchTarget(
        settings: AppSettings,
        goal: String,
    ): AgentModePreopenResult? {
        val text = goal.trim()
        if (text.length < 2) return null
        val apps = currentInstalledApps(settings).filter { it.isEnabled }
        val named = apps.mapNotNull { app ->
            val name = app.appName.trim()
            if (name.length < 2) return@mapNotNull null
            if (!text.contains(name, ignoreCase = true) &&
                !text.contains(app.packageName, ignoreCase = true)
            ) {
                return@mapNotNull null
            }
            AgentModePreopenResult(packageName = app.packageName, appName = app.appName)
        }
        val best = named.maxByOrNull { it.appName.length } ?: return null
        val ambiguous = named.any { other ->
            other.packageName != best.packageName &&
                !best.appName.contains(other.appName, ignoreCase = true) &&
                !other.appName.contains(best.appName, ignoreCase = true)
        }
        return best.takeUnless { ambiguous }
    }

    private fun hideHostIme() {
        Handler(Looper.getMainLooper()).post {
            AgentModeImeGuard.hide(context)
        }
    }

    suspend fun suppressVirtualIme(settings: AppSettings) = withContext(Dispatchers.IO) {
        val displayId = currentManagedDisplayId(settings)
        if (displayId == null) {
            hideHostIme()
            return@withContext
        }
        dismissVirtualIme(settings, displayId)
    }

    private suspend fun dismissVirtualIme(settings: AppSettings, displayId: Int) {
        runCatching { requireAgentModeService(settings).hideIme(displayId) }
        hideHostIme()
    }

    private suspend fun resolveLaunchPackage(
        settings: AppSettings,
        target: String,
    ): String? {
        val normalizedTarget = target.trim().lowercase()
        if (normalizedTarget.isBlank()) return null
        context.packageManager.getLaunchIntentForPackage(target)?.let { return target }

        val hostPkg = context.packageName
        val launchables = currentInstalledApps(settings)
        val tokens = normalizedTarget.split(Regex("\\s+"))
            .filter { it.length > 2 && it !in setOf("app", "browser", "managed") }
        return launchables.firstOrNull { app ->
            app.packageName.equals(normalizedTarget, ignoreCase = true) ||
                app.appName.equals(target, ignoreCase = true)
        }?.packageName ?: launchables.firstOrNull { app ->
            app.packageName != hostPkg && (
                app.packageName.lowercase().contains(normalizedTarget) ||
                    app.appName.lowercase().contains(normalizedTarget) ||
                    app.activityName.lowercase().contains(normalizedTarget) ||
                    tokens.any { token ->
                        app.packageName.lowercase().contains(token) ||
                            app.appName.lowercase().contains(token) ||
                            app.activityName.lowercase().contains(token)
                    }
                )
        }?.packageName ?: target.takeIf {
            it.matches(Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+"""))
        }
    }

    internal suspend fun listInstalledAppsCatalog(
        settings: AppSettings,
        arguments: JSONObject = JSONObject(),
        workspaceDirectory: String = "",
    ): String {
        val raw = listInstalledAppsResult(settings, arguments)
        persistDeviceAppsSnapshot(settings, workspaceDirectory, raw)
        return raw
    }

    private suspend fun persistDeviceAppsSnapshot(
        settings: AppSettings,
        workspaceDirectory: String,
        raw: String,
    ) {
        if (workspaceDirectory.isBlank()) return
        val parsed = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (!parsed.optBoolean("ok", false)) return
        val snapshot = JSONObject()
            .put("ok", true)
            .put("count", parsed.optInt("count"))
            .put("apps", parsed.optJSONArray("apps") ?: JSONArray())
        runtimeWorkspaceFileBridge.writeWorkspaceBytes(
            settings = settings,
            workspaceDirectory = workspaceDirectory,
            termuxWorkspaceDirectory = workspaceDirectory,
            absolutePath = DeviceCatalogMcp.SnapshotGuestPath,
            bytes = snapshot.toString().toByteArray(Charsets.UTF_8),
        )
    }

    private suspend fun listInstalledAppsResult(
        settings: AppSettings,
        arguments: JSONObject,
    ): String {
        val query = arguments.optString("query").trim()
        val normalizedQuery = query.lowercase()
        val includeSystem = arguments.optBoolean(
            "include_system",
            arguments.optBoolean("includeSystem", false),
        )
        val maxResults = arguments.optInt(
            "max_results",
            arguments.optInt("maxResults", 500),
        ).coerceIn(1, 1_000)
        val apps = currentInstalledApps(settings)
            .asSequence()
            .filter { includeSystem || !it.isSystemApp }
            .filter { app ->
                normalizedQuery.isBlank() ||
                    app.packageName.lowercase().contains(normalizedQuery) ||
                    app.appName.lowercase().contains(normalizedQuery) ||
                    app.activityName.lowercase().contains(normalizedQuery)
            }
            .toList()
        val visibleApps = apps.take(maxResults)
        return JSONObject().apply {
            put("ok", true)
            put("count", apps.size)
            put("truncated", apps.size > visibleApps.size)
            put(
                "apps",
                JSONArray().apply {
                    visibleApps.forEach { app ->
                        put(
                            JSONObject().apply {
                                put("app_name", app.appName)
                                put("package_name", app.packageName)
                                put("activity_name", app.activityName)
                                put("enabled", app.isEnabled)
                                put("system", app.isSystemApp)
                                put("launchable", true)
                            }
                        )
                    }
                },
            )
            put(
                "stdout",
                buildString {
                    append("Found ")
                    append(apps.size)
                    append(if (includeSystem) " launchable apps." else " non-system launchable apps.")
                    if (apps.size > visibleApps.size) {
                        append(" Showing ")
                        append(visibleApps.size)
                        append(".")
                    }
                    visibleApps.forEach { app ->
                        append('\n')
                        append(app.appName)
                        append(" -> ")
                        append(app.packageName)
                    }
                },
            )
        }.toString()
    }

    private suspend fun currentInstalledApps(settings: AppSettings): List<AgentModeInstalledAppInfo> {
        val privilegedApps = runCatching {
            parseInstalledApps(requireAgentModeService(settings).listInstalledAppsJson())
        }.getOrNull()
        return (privilegedApps?.takeIf { it.isNotEmpty() } ?: currentInstalledAppsLocal())
            .distinctBy { it.packageName }
            .sortedWith(compareBy({ it.appName.lowercase() }, { it.packageName }))
    }

    @Suppress("DEPRECATION")
    private fun currentInstalledAppsLocal(): List<AgentModeInstalledAppInfo> {
        val packageManager = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(
            launcherIntent,
            PackageManager.MATCH_DISABLED_COMPONENTS,
        ).mapNotNull { info ->
            val activityInfo = info.activityInfo ?: return@mapNotNull null
            val applicationInfo = activityInfo.applicationInfo ?: return@mapNotNull null
            AgentModeInstalledAppInfo(
                packageName = activityInfo.packageName.orEmpty(),
                appName = info.loadLabel(packageManager).toString(),
                activityName = activityInfo.name.orEmpty(),
                isSystemApp = applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                isEnabled = activityInfo.enabled && applicationInfo.enabled,
            )
        }
    }

    private fun parseInstalledApps(rawValue: String): List<AgentModeInstalledAppInfo> {
        val array = JSONArray(rawValue)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val packageName = item.optString("package_name").ifBlank {
                    item.optString("packageName")
                }
                if (packageName.isBlank()) continue
                add(
                    AgentModeInstalledAppInfo(
                        packageName = packageName,
                        appName = item.optString("app_name").ifBlank {
                            item.optString("appName").ifBlank { packageName }
                        },
                        activityName = item.optString("activity_name").ifBlank {
                            item.optString("activityName")
                        },
                        isSystemApp = item.optBoolean("system"),
                        isEnabled = item.optBoolean("enabled", true),
                    )
                )
            }
        }
    }

    private suspend fun listClickTargets(
        settings: AppSettings,
        simplify: Boolean,
        query: String = "",
        region: String = "",
    ): String {
        val snapshot = loadPortalSnapshot(settings)
        val filtered = AgentModeUiTree.interactiveNodes(snapshot.nodes)
            .filter { it.matches(query) && it.inRegion(region) }
            .filter { !simplify || !AgentModeUiTree.shouldSimplifyRich(it) }
        val result = JSONObject()
            .put("ok", true)
            .put("display_id", snapshot.displayId)
            .put("stdout", "ok")
        attachRichClickTargets(result, filtered)
        result.put(
            "ui_tree",
            AgentModeUiTree.wrapUntrustedGuiContent(
                filtered.joinToString("\n") { AgentModeUiTree.compactLine(it) },
            ),
        )
        return applyPortalReceipt(result, snapshot, action = "list_targets").toString()
    }

    private suspend fun dumpTreePaged(settings: AppSettings, arguments: JSONObject): String {
        val snapshot = loadPortalSnapshot(
            settings,
            cheap = arguments.optBoolean("cheap"),
        )
        val paged = AgentModeUiTree.pageRichNodes(
            nodes = snapshot.nodes,
            query = arguments.optString("query"),
            region = arguments.optString("region"),
            offset = arguments.optInt("offset"),
            limit = arguments.optInt("limit", arguments.optInt("max_results", 80))
                .coerceIn(1, AgentModeUiTree.MaxDumpLimit),
        )
        val parsed = JSONObject()
            .put("ok", true)
            .put("display_id", snapshot.displayId)
            .put("offset", paged.offset)
            .put("limit", paged.limit)
            .put("total", paged.total)
            .put("has_more", paged.hasMore)
        val nodeArray = JSONArray()
        paged.nodes.forEach { nodeArray.put(AgentModePortal.toNodeJson(it)) }
        parsed.put("nodes", nodeArray)
        val textOnly = AgentModeCapture.wantsTextTree(arguments)
        val deltaOnly = AgentModeCapture.wantsDeltaTree(arguments)
        val lines = when {
            textOnly -> paged.nodes.map { node ->
                node.text.ifBlank { node.label }.ifBlank { node.desc }
            }.filter { it.isNotBlank() }
            deltaOnly -> AgentModeUiTree.deltaLines(
                previous = lastSnapshot?.nodes.orEmpty(),
                current = paged.nodes,
            ).ifEmpty { listOf("ok") }
            else -> paged.nodes.map { AgentModeUiTree.compactLine(it) }
        }
        parsed.put("stdout", lines.joinToString("\n").ifBlank { "ok" })
        return applyPortalReceipt(parsed, snapshot, action = "dump_tree").toString()
    }

    private suspend fun scrollSwipeForDisplay(
        settings: AppSettings,
        direction: String = "up",
    ): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        val snapshot = runCatching { loadPortalSnapshot(settings) }.getOrNull()
            ?: return AgentModeUiTree.directionalSwipe(emptyList(), direction)
        return AgentModeUiTree.directionalSwipe(snapshot.nodes, direction)
    }

    private suspend fun resolveSwipeEndpoints(
        settings: AppSettings,
        arguments: JSONObject,
        action: String,
    ): Pair<Pair<Int, Int>, Pair<Int, Int>>? {
        val x1 = normalizedX(arguments.optDouble("x1", Double.NaN))
        val y1 = normalizedY(arguments.optDouble("y1", Double.NaN))
        val x2 = normalizedX(arguments.optDouble("x2", Double.NaN))
        val y2 = normalizedY(arguments.optDouble("y2", Double.NaN))
        if (x1 != null && y1 != null && x2 != null && y2 != null) {
            return (x1 to y1) to (x2 to y2)
        }
        val direction = arguments.optString("direction").ifBlank {
            AgentModeUiTree.parseSwipeDirection(action)
        }
        if (direction.isBlank()) return null
        return scrollSwipeForDisplay(settings, direction)
    }

    private fun attachClickTargets(
        result: JSONObject,
        uiTree: String,
        simplify: Boolean = false,
        query: String = "",
        region: String = "",
    ) {
        val targets = AgentModeUiTree.clickTargetsFromCompact(uiTree, simplify = simplify)
            .filter { target ->
                val q = query.trim()
                val matchesQuery = q.isBlank() ||
                    target.label.contains(q, ignoreCase = true)
                val matchesRegion = when (region.trim().lowercase()) {
                    "top" -> target.centerY < 334
                    "middle" -> target.centerY in 334..666
                    "bottom" -> target.centerY > 666
                    "left" -> target.centerX < 500
                    "right" -> target.centerX >= 500
                    else -> true
                }
                matchesQuery && matchesRegion
            }
        val json = JSONArray()
        targets.forEachIndexed { index, target ->
            json.put(
                JSONObject()
                    .put("i", index + 1)
                    .put("label", target.label)
                    .put("x", target.centerX)
                    .put("y", target.centerY)
                    .put("bounds", target.bounds)
                    .put("kind", "tap"),
            )
        }
        result.put("click_targets", json)
        result.put(
            "click_targets_text",
            targets.mapIndexed { index, target -> "#${index + 1} ${target.tapLine}" }.joinToString("\n"),
        )
        result.put("click_target_count", targets.size)
        result.put("cluttered", AgentModeUiTree.isCluttered(targets.size))
    }

    private fun attachRichClickTargets(
        result: JSONObject,
        nodes: List<AgentModeUiTree.RichNode>,
    ) {
        val json = JSONArray()
        nodes.forEach { node ->
            json.put(
                AgentModePortal.toNodeJson(node)
                    .put("kind", if (node.editable) "input" else "tap"),
            )
        }
        result.put("click_targets", json)
        result.put(
            "click_targets_text",
            AgentModeUiTree.wrapUntrustedGuiContent(
                nodes.joinToString("\n") { AgentModeUiTree.compactLine(it) },
            ),
        )
        result.put("click_target_count", nodes.size)
        result.put("cluttered", AgentModeUiTree.isCluttered(nodes.size))
    }

    private fun startTeaching(rawHint: String): String {
        val hint = rawHint.trim().ifBlank { "请在虚拟屏上点一遍这一步" }
        teachingActive.set(true)
        teachingFinished.set(false)
        teachingGestures.set(0)
        lastTeachingGestureAt.set(0L)
        val state = _displayState.value
        _displayState.value = state.copy(teachingHint = hint, teachingActive = true)
        return JSONObject()
            .put("ok", true)
            .put("teaching", true)
            .put("done", false)
            .put("hint", hint)
            .put(
                "stdout",
                "Ask the user to demonstrate on the virtual screen. Poll teaching_status until done=true.",
            )
            .toString()
    }

    fun startUserTeaching(hint: String = "") {
        teachingActive.set(true)
        teachingFinished.set(false)
        teachingGestures.set(0)
        lastTeachingGestureAt.set(0L)
        val state = _displayState.value
        _displayState.value = state.copy(
            teachingHint = hint.trim(),
            teachingActive = true,
        )
    }

    fun finishTeaching() {
        teachingActive.set(false)
        teachingFinished.set(true)
        val state = _displayState.value
        if (state.teachingHint.isNotBlank() || state.teachingActive) {
            _displayState.value = state.copy(teachingHint = "", teachingActive = false)
        }
    }

    private fun teachingStatusResult(): String {
        val gestures = teachingGestures.get()
        val done = teachingFinished.get()
        return JSONObject()
            .put("ok", true)
            .put("teaching", teachingActive.get())
            .put("done", done)
            .put("gesture_count", gestures)
            .put("hint", _displayState.value.teachingHint)
            .put("stdout", if (done) "teaching complete" else "waiting for user teaching")
            .toString()
    }

    private suspend fun captureAfterDelay(
        settings: AppSettings,
        workspaceDirectory: String,
        termuxWorkspaceDirectory: String,
        delayMillis: Long,
    ): String {
        if (activeSkipCapture.get() == true) {
            if (delayMillis > 0) delay(delayMillis.coerceAtMost(80L))
            val snapshot = loadPortalSnapshot(settings, alreadyStable = true)
            return applyPortalReceipt(
                JSONObject().put("ok", true).put("stdout", "ok").put("display_id", snapshot.displayId),
                snapshot,
                action = "gesture",
            ).toString()
        }
        val displayId = currentManagedDisplayId(settings) ?: ensureDisplay(settings)
        val stableTimeout = if (delayMillis >= 180L) 800 else 520
        runCatching {
            requireAgentModeService(settings).waitUntilLayoutStable(displayId, 50, stableTimeout)
        }
        var bytes = captureJpegBytes(settings)
        if (jpegLooksBlack(bytes)) {
            delay(80)
            bytes = captureJpegBytes(settings)
        }
        val snapshot = runCatching { loadPortalSnapshot(settings, alreadyStable = true) }.getOrNull()
        val uiTree = snapshot?.nodes?.joinToString("\n") { AgentModeUiTree.compactLine(it) }
            ?: runCatching {
                requireAgentModeService(settings).dumpUiTree(displayId)
            }.getOrDefault("")
        if (activeSomOverlay.get() == true) {
            val somTargets = snapshot?.let { AgentModeUiTree.interactiveNodes(it.nodes) }
                ?.map { node ->
                    AgentModeUiTarget(node.label, node.left, node.top, node.right, node.bottom)
                }
                ?: AgentModeUiTree.clickTargetsFromCompact(uiTree)
            bytes = overlaySomNumbers(bytes, somTargets)
        }
        activeCropArguments.get()?.let { cropArgs ->
            bytes = cropJpegBytes(bytes, cropArgs)
        }
        diagnosticLogger.event(
            category = "agent_mode",
            event = "capture",
            details = mapOf(
                "attempt" to 0,
                "bytes" to bytes.size,
                "black" to jpegLooksBlack(bytes),
                "ui_tree_chars" to uiTree.length,
                "disk" to false,
            ),
        )
        val persistRelative = activePersistPath.get().orEmpty()
        var workspaceRelative = ""
        var workspaceGuestPath = ""
        if (persistRelative.isNotBlank() && bytes.isNotEmpty()) {
            val guestPath = if (persistRelative.startsWith("/")) {
                persistRelative
            } else {
                "/workspace/$persistRelative"
            }
            val written = runtimeWorkspaceFileBridge.writeWorkspaceBytes(
                settings = settings,
                workspaceDirectory = workspaceDirectory,
                termuxWorkspaceDirectory = termuxWorkspaceDirectory,
                absolutePath = guestPath,
                bytes = bytes,
            )
            if (written.isSuccess) {
                workspaceGuestPath = guestPath
                workspaceRelative = persistRelative.removePrefix("workspace/")
            }
        }
        val state = _displayState.value
        _displayState.value = state.copy(
            isActive = true,
            displayId = displayId,
            displays = currentDisplays(settings, displayId),
            latestPreviewPath = "",
            latestWorkspacePath = workspaceGuestPath,
            lastUpdatedMillis = System.currentTimeMillis(),
            status = "Streaming virtual display",
        )
        val captured = JSONObject().apply {
            put("ok", true)
            put("display_id", displayId)
            put("width", state.width)
            put("height", state.height)
            put("ui_tree", uiTree)
            attachClickTargets(this, uiTree)
            if (lastLaunchedPackageName.isNotBlank()) {
                put("package_name", lastLaunchedPackageName)
            }
            if (lastLaunchedAppName.isNotBlank()) {
                put("app_name", lastLaunchedAppName)
            }
            state.cursorX?.let { put("cursor_x", it) }
            state.cursorY?.let { put("cursor_y", it) }
            put("screenshot_mime_type", AgentModeCaptureMimeType)
            if (bytes.isNotEmpty()) {
                put("screenshot_base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            }
            if (workspaceGuestPath.isNotBlank()) {
                put("workspace_path", workspaceGuestPath)
                put("workspace_relative", workspaceRelative)
            }
            if (activeSomOverlay.get() == true) {
                put("som", true)
            }
            if (snapshot != null && defaultGestureObserveDelta()) {
                applyDeltaBudget(
                    result = this,
                    snapshot = snapshot,
                    previous = lastSnapshot,
                )
            }
            put(
                "stdout",
                if (workspaceGuestPath.isNotBlank()) {
                    "Captured Agent Mode frame and wrote $workspaceGuestPath."
                } else {
                    "Captured Agent Mode frame in memory."
                },
            )
        }
        return if (snapshot != null) {
            applyPortalReceipt(captured, snapshot, action = "screenshot").toString()
        } else {
            captured.toString()
        }
    }

    private fun defaultGestureObserveDelta(): Boolean {
        return activeCropArguments.get() == null && activeSomOverlay.get() != true
    }

    /**
     * P3-1 observation budget: for plain gestures without an explicit observe,
     * replace the full interactive tree with +/- lines; when the fingerprint is
     * unchanged strip the tree entirely (snapshot_id + tree_diff already tell
     * the model the page did not move).
     */
    private fun applyDeltaBudget(
        result: JSONObject,
        snapshot: GuiSnapshot,
        previous: GuiSnapshot?,
    ) {
        val delta = AgentModeUiTree.deltaLines(previous?.nodes.orEmpty(), snapshot.nodes)
        if (delta.isNotEmpty()) {
            result.put("stdout", delta.joinToString("\n"))
            result.put("ui_delta_text", AgentModeUiTree.wrapUntrustedGuiContent(delta.joinToString("\n")))
        }
    }

    private fun overlaySomNumbers(bytes: ByteArray, targets: List<AgentModeUiTarget>): ByteArray {
        if (bytes.isEmpty() || targets.isEmpty()) return bytes
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
        return try {
            val mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: return bytes
            try {
                val canvas = Canvas(mutable)
                val radius = (bitmap.width / 22f).coerceIn(16f, 28f)
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(230, 37, 99, 235)
                }
                val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.STROKE
                    strokeWidth = 2f
                }
                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textAlign = Paint.Align.CENTER
                    typeface = Typeface.DEFAULT_BOLD
                    textSize = (bitmap.width / 28f).coerceIn(18f, 36f)
                }
                targets.forEachIndexed { index, target ->
                    val x = target.centerX * bitmap.width / 1000f
                    val y = target.centerY * bitmap.height / 1000f
                    canvas.drawCircle(x, y, radius, fill)
                    canvas.drawCircle(x, y, radius, stroke)
                    canvas.drawText(
                        "${index + 1}",
                        x,
                        y + textPaint.textSize * 0.35f,
                        textPaint,
                    )
                }
                val stream = ByteArrayOutputStream()
                if (!mutable.compress(Bitmap.CompressFormat.JPEG, AgentModeCaptureJpegQuality, stream)) {
                    bytes
                } else {
                    stream.toByteArray()
                }
            } finally {
                if (!mutable.isRecycled && mutable !== bitmap) mutable.recycle()
            }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun cropJpegBytes(bytes: ByteArray, arguments: JSONObject): ByteArray {
        if (bytes.isEmpty()) return bytes
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
        return try {
            val crop = AgentModeCapture.pixelCrop(bitmap.width, bitmap.height, arguments) ?: return bytes
            val cropped = Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.width, crop.height)
            try {
                val stream = ByteArrayOutputStream()
                if (!cropped.compress(Bitmap.CompressFormat.JPEG, AgentModeCaptureJpegQuality, stream)) {
                    bytes
                } else {
                    stream.toByteArray()
                }
            } finally {
                if (cropped !== bitmap && !cropped.isRecycled) cropped.recycle()
            }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun updateCursorPosition(
        x: Int,
        y: Int,
        animationDurationMillis: Int = 220,
    ) {
        val state = _displayState.value
        _displayState.value = state.copy(
            cursorX = x.coerceIn(0, state.width),
            cursorY = y.coerceIn(0, state.height),
            cursorAnimationDurationMillis = animationDurationMillis.coerceIn(80, 1_200),
            lastUpdatedMillis = System.currentTimeMillis(),
        )
    }

    internal fun abortInFlightCapture() {
        cancelInFlightCapture()
    }

    private fun cancelInFlightCapture() {
        inFlightCaptureJob.getAndSet(null)?.cancel()
    }

    private suspend fun captureJpegBytes(settings: AppSettings): ByteArray {
        val job = currentCoroutineContext()[Job]
        inFlightCaptureJob.set(job)
        try {
            return withTimeout(CaptureJpegTimeoutMillis) {
                captureMutex.withLock {
                    val live = captureLivePreviewJpeg()
                    if (live != null && live.isNotEmpty()) return@withLock live
                    val displayId = ensureDisplay(settings)
                    val pipe = ParcelFileDescriptor.createPipe()
                    val read = pipe[0]
                    val write = pipe[1]
                    try {
                        requireAgentModeService(settings).captureImageToFd(
                            displayId,
                            write,
                            AgentModeCaptureMaxEdge,
                            AgentModeCaptureJpegQuality,
                        )
                        java.io.FileInputStream(read.fileDescriptor).use { it.readBytes() }
                    } finally {
                        runCatching { read.close() }
                        runCatching { write.close() }
                    }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            throw IllegalStateException(
                "${AgentModeSafety.CaptureTimeoutCode}: screenshot timed out",
                timeout,
            )
        } catch (cancelled: CancellationException) {
            throw IllegalStateException(
                "${AgentModeSafety.CaptureTimeoutCode}: screenshot cancelled",
                cancelled,
            )
        } finally {
            inFlightCaptureJob.compareAndSet(job, null)
        }
    }

    private suspend fun captureLivePreviewJpeg(): ByteArray? {
        val surface = previewSurface?.takeIf { it.isValid } ?: return null
        val width = _displayState.value.width.coerceAtLeast(1)
        val height = _displayState.value.height.coerceAtLeast(1)
        return withContext(Dispatchers.Default) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val copied = try {
                withTimeout(PixelCopyTimeoutMillis) {
                    suspendCancellableCoroutine { continuation ->
                        PixelCopy.request(
                            surface,
                            bitmap,
                            { result ->
                                if (continuation.isActive) {
                                    continuation.resume(result == PixelCopy.SUCCESS)
                                }
                            },
                            pixelCopyHandler,
                        )
                    }
                }
            } catch (_: TimeoutCancellationException) {
                false
            } catch (_: Throwable) {
                false
            }
            if (!copied) {
                bitmap.recycle()
                null
            } else {
                try {
                    encodePreviewJpeg(bitmap)
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    private fun encodePreviewJpeg(bitmap: Bitmap): ByteArray? {
        val longest = maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)
        val scaled = if (longest > AgentModeCaptureMaxEdge) {
            val scale = AgentModeCaptureMaxEdge.toFloat() / longest.toFloat()
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        return try {
            val stream = java.io.ByteArrayOutputStream()
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, AgentModeCaptureJpegQuality, stream)) {
                null
            } else {
                stream.toByteArray()
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun jpegLooksBlack(bytes: ByteArray): Boolean {
        if (bytes.size < 32) return true
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return true
        return try {
            val stepX = (bitmap.width / 8).coerceAtLeast(1)
            val stepY = (bitmap.height / 8).coerceAtLeast(1)
            var samples = 0
            var dark = 0
            var y = 0
            while (y < bitmap.height) {
                var x = 0
                while (x < bitmap.width) {
                    val pixel = bitmap.getPixel(x, y)
                    val luminance =
                        (android.graphics.Color.red(pixel) * 299 +
                            android.graphics.Color.green(pixel) * 587 +
                            android.graphics.Color.blue(pixel) * 114) / 1000
                    samples += 1
                    if (luminance < 16) dark += 1
                    x += stepX
                }
                y += stepY
            }
            samples > 0 && dark * 100 / samples >= 96
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun statusResult(settings: AppSettings): String {
        currentManagedDisplayId(settings)
        val state = _displayState.value
        val displays = currentDisplays(settings, state.displayId)
        _displayState.value = state.copy(
            displays = displays,
            lastUpdatedMillis = System.currentTimeMillis(),
        )
        return JSONObject().apply {
            put("ok", true)
            put("active", state.isActive)
            put("display_id", state.displayId)
            put("width", state.width)
            put("height", state.height)
            put("live_preview", state.isLivePreviewActive)
            put(
                "displays",
                org.json.JSONArray().apply {
                    displays.forEach { display ->
                        put(
                            JSONObject().apply {
                                put("display_id", display.displayId)
                                put("name", display.name)
                                put("width", display.width)
                                put("height", display.height)
                                put("is_aether_display", display.isAetherDisplay)
                            }
                        )
                    }
                },
            )
            put("screenshot_path", "")
            put("stdout", if (state.isActive) "Agent Mode display is active." else "Agent Mode display is stopped.")
        }.toString()
    }

    private fun releaseDisplay(status: String = "Virtual display stopped") {
        _displayState.value = _displayState.value.copy(
            listening = false,
            listenVisualOnly = false,
        )
        controllerScope.launch {
            listenSession.stop(shizukuService ?: rootService)
        }
        finishTeaching()
        cancelInFlightCapture()
        shizukuDisplayId?.let { displayId ->
            diagnosticLogger.event(
                category = "agent_mode",
                event = "display_released",
                details = mapOf(
                    "display_id" to displayId,
                    "method" to displayOwnerMethod?.storageValue.orEmpty(),
                    "status" to status,
                ),
            )
            when (displayOwnerMethod) {
                AgentModeAuthorizationMethod.Shizuku -> runCatching { shizukuService?.releaseDisplay(displayId) }
                AgentModeAuthorizationMethod.Root -> runCatching { rootService?.releaseDisplay(displayId) }
                null -> {
                    runCatching { shizukuService?.releaseDisplay(displayId) }
                    runCatching { rootService?.releaseDisplay(displayId) }
                }
            }
        }
        shizukuDisplayId = null
        displayOwnerMethod = null
        displayOwnerBinder = null
        synchronized(previewSurfaceLock) {
            previewSurface = null
            runCatching { glPreviewRenderer.releaseProducer() }
        }
        val spec = currentDeviceDisplaySpec()
        _displayState.value = AgentModeDisplayState(
            isActive = false,
            width = spec.width,
            height = spec.height,
            displays = currentDisplaysLocal(null),
            status = status,
            lastUpdatedMillis = System.currentTimeMillis(),
        )
    }

    private suspend fun currentManagedDisplayId(settings: AppSettings): Int? {
        val displayId = shizukuDisplayId ?: return null
        val service = runCatching { requireAgentModeService(settings) }
            .getOrElse { throwable ->
                if (displayOwnerMethod == settings.agentModeAuthorizationMethod) {
                    releaseDisplay(
                        throwable.message ?: "Virtual display reset because Agent Mode service is unavailable."
                    )
                }
                return null
            }
        if (isCurrentDisplayOwner(settings, service.asBinder())) {
            return displayId
        }
        releaseDisplay("Virtual display reset because Agent Mode authorization service changed.")
        return null
    }

    private fun isCurrentDisplayOwner(
        settings: AppSettings,
        binder: IBinder,
    ): Boolean =
        displayOwnerMethod == settings.agentModeAuthorizationMethod &&
            displayOwnerBinder === binder &&
            binder.isBinderAlive

    private fun clearShizukuService(displayStatus: String) {
        shizukuService = null
        shizukuServiceArgs = null
        shizukuServiceConnection = null
        if (displayOwnerMethod == AgentModeAuthorizationMethod.Shizuku) {
            releaseDisplay(displayStatus)
        }
    }

    private fun clearRootService(displayStatus: String) {
        rootService = null
        rootProcess = null
        if (displayOwnerMethod == AgentModeAuthorizationMethod.Root) {
            releaseDisplay(displayStatus)
        }
    }

    private fun captureAgentModeFailed(
        settings: AppSettings,
        action: String,
        reason: String,
        message: String,
    ) {
        diagnosticLogger.event(
            category = "agent_mode",
            event = "action_failed",
            level = "warn",
            details = mapOf(
                "action" to action,
                "reason" to reason,
                "message" to message,
                "authorization_enabled" to settings.agentModeAuthorizationEnabled,
                "authorization_method" to settings.agentModeAuthorizationMethod.storageValue,
                "display_active" to _displayState.value.isActive,
            ),
        )
        AetherAnalytics.capture(
            event = "agent mode failed",
            properties = mapOf(
                "action" to action,
                "reason" to reason,
                "message" to message.take(280),
                "authorization_enabled" to settings.agentModeAuthorizationEnabled,
                "authorization_method" to settings.agentModeAuthorizationMethod.storageValue,
                "display_active" to _displayState.value.isActive,
            ),
        )
    }

    private suspend fun currentDisplays(
        settings: AppSettings,
        aetherDisplayId: Int?,
    ): List<AgentModeDisplayInfo> {
        val privilegedDisplays = runCatching {
            parseDisplays(requireAgentModeService(settings).listDisplaysJson(), aetherDisplayId)
        }.onFailure {
        }.getOrNull()
        if (privilegedDisplays != null) return privilegedDisplays
        return currentDisplaysLocal(aetherDisplayId)
    }

    private fun currentDisplaysLocal(aetherDisplayId: Int?): List<AgentModeDisplayInfo> =
        displayManager.displays.map { display ->
            val size = Point()
            @Suppress("DEPRECATION")
            display.getSize(size)
            AgentModeDisplayInfo(
                displayId = display.displayId,
                name = display.name.orEmpty(),
                width = display.mode?.physicalWidth ?: size.x,
                height = display.mode?.physicalHeight ?: size.y,
                isAetherDisplay = display.displayId == aetherDisplayId ||
                    display.name.orEmpty().contains(AgentDisplayName, ignoreCase = true),
            )
        }.sortedBy { it.displayId }

    private fun parseDisplays(
        rawValue: String,
        aetherDisplayId: Int?,
    ): List<AgentModeDisplayInfo> {
        val array = org.json.JSONArray(rawValue)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val displayId = item.optInt("display_id")
                add(
                    AgentModeDisplayInfo(
                        displayId = displayId,
                        name = item.optString("name"),
                        width = item.optInt("width"),
                        height = item.optInt("height"),
                        isAetherDisplay = item.optBoolean("is_aether_display") ||
                            displayId == aetherDisplayId ||
                            item.optString("name").contains(AgentDisplayName, ignoreCase = true),
                    )
                )
            }
        }.sortedBy { it.displayId }
    }

    private suspend fun requireAgentModeService(settings: AppSettings): IAetherAgentModeService =
        when (settings.agentModeAuthorizationMethod) {
            AgentModeAuthorizationMethod.Shizuku -> requireShizukuService()
            AgentModeAuthorizationMethod.Root -> requireRootService()
        }

    private suspend fun requireRootService(): IAetherAgentModeService = withContext(Dispatchers.IO) {
        val existing = rootService
        if (existing != null) {
            if (existing.asBinder().isBinderAlive) {
                return@withContext existing
            }
            clearRootService("Root Agent Mode service disconnected. Virtual display was reset.")
        }
        val suPath = findSuPath()
        if (suPath.isBlank()) {
            _authorizationState.value = AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.RootUnavailable,
                detail = "No su binary was detected on this device.",
            )
            error("No su binary was detected on this device.")
        }
        val process = object : AppProcess.Terminal() {
            override fun newTerminal(): List<String?> = listOf(suPath)
        }
        if (!process.init(context)) {
            _authorizationState.value = AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.RootPermissionDenied,
                detail = "Root Agent Mode service failed to start. Check that su can be granted to Aether.",
            )
            error("Root Agent Mode service failed to start. Check that su can be granted to Aether.")
        }
        val binder = process.serviceBinder(
            ComponentName(context, AetherAgentModeShizukuService::class.java),
        )
        val service = IAetherAgentModeService.Stub.asInterface(binder)
            ?: error("Root Agent Mode service returned an invalid binder.")
        rootProcess = process
        rootService = service
        _authorizationState.value = AgentModeAuthorizationState(
            issue = AgentModeAuthorizationIssue.Ready,
            detail = "Root Agent Mode service is connected.",
        )
        service
    }

    @Suppress("RestrictedApi")
    private suspend fun requireShizukuService(): IAetherAgentModeService = shizukuServiceMutex.withLock {
        val existing = shizukuService
        if (existing != null) {
            if (existing.asBinder().isBinderAlive) {
                return@withLock existing
            }
            clearShizukuService("Shizuku Agent Mode service disconnected. Virtual display was reset.")
        }
        if (!Shizuku.pingBinder()) {
            runCatching { prepareShizuku?.invoke() }
        }
        if (!Shizuku.pingBinder()) {
            error(
                "SHIZUKU_NOT_RUNNING: Shizuku is not running. Stay in Aether and retry after Shizuku starts. " +
                    "Do not open the Shizuku app, and do not mention ADB, USB, wireless debugging, or computer commands.",
            )
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            error("Aether does not have Shizuku permission. Grant it in Shizuku first.")
        }
        val deferred = CompletableDeferred<IAetherAgentModeService>()
        val args = Shizuku.UserServiceArgs(
            ComponentName(context, AetherAgentModeShizukuService::class.java),
        )
            .processNameSuffix("agentmode")
            .tag(ShizukuUserServiceTag)
            .version(ShizukuUserServiceVersion)
            .daemon(true)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val bound = IAetherAgentModeService.Stub.asInterface(service)
                if (shizukuServiceConnection !== this) return
                shizukuService = bound
                if (bound == null) {
                    if (!deferred.isCompleted) {
                        deferred.completeExceptionally(
                            IllegalStateException("Shizuku Agent Mode service returned an invalid binder.")
                        )
                    }
                } else if (!deferred.isCompleted) {
                    deferred.complete(bound)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (shizukuServiceConnection === this) {
                    clearShizukuService("Shizuku Agent Mode service disconnected. Virtual display was reset.")
                }
            }
        }
        shizukuServiceArgs = args
        shizukuServiceConnection = connection
        diagnosticLogger.event(
            category = "agent_mode",
            event = "shizuku_service_bind_start",
            details = mapOf(
                "timeout_ms" to ShizukuUserServiceBindTimeoutMillis,
                "tag" to ShizukuUserServiceTag,
                "version" to ShizukuUserServiceVersion,
            ),
        )
        val bindStartMillis = System.currentTimeMillis()
        runCatching {
            Shizuku.bindUserService(args, connection)
        }.onSuccess {
            diagnosticLogger.event(
                category = "agent_mode",
                event = "shizuku_service_bind_dispatched",
                details = mapOf(
                    "duration_ms" to (System.currentTimeMillis() - bindStartMillis),
                ),
            )
        }.onFailure { throwable ->
            if (shizukuServiceConnection === connection) {
                shizukuService = null
                shizukuServiceArgs = null
                shizukuServiceConnection = null
            }
            throw throwable
        }
        return@withLock try {
            withTimeout(ShizukuUserServiceBindTimeoutMillis) { deferred.await() }.also {
                diagnosticLogger.event(
                    category = "agent_mode",
                    event = "shizuku_service_bound",
                )
            }
        } catch (throwable: TimeoutCancellationException) {
            if (shizukuServiceConnection === connection) {
                shizukuService = null
                shizukuServiceArgs = null
                shizukuServiceConnection = null
            }
            runCatching { Shizuku.unbindUserService(args, connection, true) }
            diagnosticLogger.event(
                category = "agent_mode",
                event = "shizuku_service_bind_timeout",
                level = "warn",
                details = mapOf(
                    "timeout_ms" to ShizukuUserServiceBindTimeoutMillis,
                    "shizuku_version" to runCatching { Shizuku.getVersion() }.getOrDefault(-1),
                    "shizuku_server_patch_version" to runCatching { Shizuku.getServerPatchVersion() }.getOrDefault(-1),
                ),
            )
            error(
                "Timed out starting Shizuku Agent Mode service after " +
                    "$ShizukuUserServiceBindTimeoutMillis ms. Restart Shizuku or update Shizuku, then refresh Agent Mode status."
            )
        }
    }

    private suspend fun inspectAuthorization(settings: AppSettings): AgentModeAuthorizationState =
        when {
            !settings.agentModeAuthorizationEnabled -> AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.Disabled,
                detail = "Agent Mode authorization is disabled.",
            )

            settings.agentModeAuthorizationMethod == AgentModeAuthorizationMethod.Root -> inspectRootAuthorization()

            // getPackageInfo() is a synchronous binder round trip per candidate package.
            else -> withContext(Dispatchers.IO) { inspectShizukuAuthorization() }
        }

    private suspend fun inspectRootAuthorization(): AgentModeAuthorizationState = withContext(Dispatchers.IO) {
        val existing = rootService
        if (existing != null && existing.asBinder().isBinderAlive) {
            return@withContext AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.Ready,
                detail = "Root Agent Mode service is already connected.",
            )
        }
        if (existing != null) {
            clearRootService("Root Agent Mode service disconnected. Virtual display was reset.")
        }

        val suPath = findSuPath()
        if (suPath.isBlank()) {
            return@withContext AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.RootUnavailable,
                detail = "No su binary was detected on this device.",
            )
        }

        val probe = runRootAuthorizationProbe(suPath)
        when {
            probe.launchError.isNotBlank() -> AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.Error,
                detail = probe.launchError,
            )

            probe.exitCode == 0 -> AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.Ready,
                detail = "Root authorization is granted.",
            )

            probe.timedOut -> AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.RootPermissionMissing,
                detail = "Root authorization timed out. Grant su to Aether, then refresh Agent Mode status.",
            )

            else -> AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.RootPermissionDenied,
                detail = probe.combinedOutput().ifBlank {
                    "Root authorization was not granted. Grant su to Aether, then refresh Agent Mode status."
                }.take(280),
            )
        }
    }

    private fun inspectShizukuAuthorization(): AgentModeAuthorizationState {
        if (!isAnyPackageInstalled(ShizukuManagerPackages)) {
            return AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.ShizukuNotInstalled,
                detail = "Install Shizuku before using Shizuku Agent Mode.",
            )
        }
        val isRunning = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!isRunning) {
            return AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.ShizukuNotRunning,
                detail = "Shizuku is not running. Pair wireless debugging in Shizuku if the system supports it.",
            )
        }
        return runCatching {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                AgentModeAuthorizationState(
                    issue = AgentModeAuthorizationIssue.Ready,
                    detail = "Shizuku permission is granted.",
                )
            } else {
                AgentModeAuthorizationState(
                    issue = AgentModeAuthorizationIssue.ShizukuPermissionMissing,
                    detail = "Grant Aether permission in Shizuku before using Agent Mode.",
                )
            }
        }.getOrElse { throwable ->
            AgentModeAuthorizationState(
                issue = AgentModeAuthorizationIssue.Error,
                detail = throwable.message ?: "Unable to inspect Shizuku permission.",
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun isAnyPackageInstalled(packageNames: List<String>): Boolean =
        packageNames.any(::isPackageInstalled)

    /**
     * Only a positive result is memoised: a package can appear while the app is running,
     * but one that is already present will not vanish behind our back.
     */
    @Suppress("DEPRECATION")
    private fun isPackageInstalled(packageName: String): Boolean {
        if (installedPackageCache.containsKey(packageName)) return true
        val installed = runCatching {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrDefault(false)
        if (installed) installedPackageCache[packageName] = true
        return installed
    }

    private fun findSuPath(): String {
        val commonPaths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/debug_ramdisk/su",
        )
        commonPaths.firstOrNull { path ->
            File(path).let { it.exists() && it.canExecute() }
        }?.let { return it }

        val result = runProcess(
            command = listOf("sh", "-c", "command -v su 2>/dev/null || true"),
            timeoutMillis = RootAuthorizationProbeTimeoutMillis,
        )
        return result.stdout.lineSequence().firstOrNull()?.trim().orEmpty()
    }

    private fun runRootAuthorizationProbe(suPath: String): RootCommandResult =
        runProcess(
            command = listOf(suPath, "-c", "true"),
            timeoutMillis = RootAuthorizationProbeTimeoutMillis,
        )

    private fun runProcess(
        command: List<String>,
        timeoutMillis: Long,
    ): RootCommandResult {
        val process = runCatching {
            ProcessBuilder(command).start()
        }.getOrElse { throwable ->
            return RootCommandResult(
                exitCode = -1,
                launchError = throwable.message.orEmpty(),
            )
        }

        val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        if (!finished) {
            runCatching { process.destroy() }
            if (!process.waitFor(400, TimeUnit.MILLISECONDS)) {
                runCatching { process.destroyForcibly() }
            }
        }

        val stdout = runCatching {
            process.inputStream.bufferedReader().readText()
        }.getOrDefault("")
        val stderr = runCatching {
            process.errorStream.bufferedReader().readText()
        }.getOrDefault("")
        return RootCommandResult(
            exitCode = if (finished) process.exitValue() else -1,
            stdout = stdout,
            stderr = stderr,
            timedOut = !finished,
        )
    }


    private fun setHostClipboard(text: String) {
        val clipboardSetter = {
            runCatching {
                val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                clipboard?.setPrimaryClip(
                    android.content.ClipData.newPlainText("aether-agent-mode", text),
                )
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            clipboardSetter()
            return
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            clipboardSetter()
            latch.countDown()
        }
        runCatching { latch.await(400, TimeUnit.MILLISECONDS) }
    }

    private fun normalizedX(value: Double): Int? =
        axisToDisplayPx(value, _displayState.value.width)

    private fun normalizedY(value: Double): Int? =
        axisToDisplayPx(value, _displayState.value.height)

    private fun axisToDisplayPx(value: Double, size: Int): Int? {
        if (value.isNaN() || size <= 0) return null
        val max = (size - 1).coerceAtLeast(0)
        return if (value > 1000.0) {
            value.toInt().coerceIn(0, max)
        } else {
            (value.coerceIn(0.0, 1000.0) * size / 1000.0).toInt().coerceIn(0, max)
        }
    }

    private fun sanitizeAgentModeError(raw: String?): String {
        val text = raw.orEmpty()
        if (
            text.contains("virtual display", ignoreCase = true) ||
            text.contains("CAPTURE_VIDEO") ||
            text.contains("ADD_TRUSTED") ||
            text.contains("MediaProjection") ||
            text.contains("uid=")
        ) {
            return "Unable to create the Agent Mode virtual display. Retry this tool. " +
                "Do not mention screen recording, MediaProjection, ADB, USB, or permissions."
        }
        return raw ?: "Agent Mode failed."
    }

    private suspend fun sendSystemKey(
        settings: AppSettings,
        workspaceDirectory: String,
        termuxWorkspaceDirectory: String,
        keyCode: String,
    ): String {
        val displayId = ensureDisplay(settings)
        requireAgentModeService(settings).key(displayId, keyCode)
        return captureAfterDelay(
            settings,
            workspaceDirectory,
            termuxWorkspaceDirectory,
            delayMillis = 300,
        )
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun gatedError(code: String, message: String): String =
        JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("errmsg", message)
            .put("consistent", "no")
            .put("degraded_to", AgentModeSafety.ReplanDegradedTo)
            .toString()

    private suspend fun loadPortalSnapshot(
        settings: AppSettings,
        alreadyStable: Boolean = false,
        cheap: Boolean = false,
    ): GuiSnapshot {
        val displayId = currentManagedDisplayId(settings) ?: ensureDisplay(settings)
        val spec = if (cheap) {
            AgentModePortal.snapshotDumpSpec("gesture", skipCapture = true)
        } else {
            AgentModePortal.snapshotDumpSpec(
                action = activePortalAction.get().orEmpty(),
                skipCapture = activeSkipCapture.get() == true,
            )
        }
        if (spec.waitStable && !alreadyStable) {
            runCatching {
                requireAgentModeService(settings).waitUntilLayoutStable(displayId, 40, 400)
            }
        }
        val raw = requireAgentModeService(settings).dumpUiTreePaged(
            displayId,
            spec.query,
            "",
            0,
            spec.limit.coerceAtLeast(1),
        )
        val parsed = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
        val nodes = AgentModePortal.parseNodes(parsed.optJSONArray("nodes"))
        val state = _displayState.value
        val packageName = nodes.map { it.packageName }.firstOrNull { it.isNotBlank() }.orEmpty()
            .ifBlank { lastLaunchedPackageName }
        return AgentModePortal.buildSnapshot(
            displayId = displayId,
            width = state.width,
            height = state.height,
            packageName = packageName,
            nodes = nodes,
        )
    }

    private fun applyPortalReceipt(
        result: JSONObject,
        snapshot: GuiSnapshot,
        action: String,
    ): JSONObject {
        val previous = lastSnapshot
        lastSnapshot = snapshot
        val backend = activeUsedBackend.get().orEmpty()
        val resolvedAction = activePortalAction.get().orEmpty().ifBlank { action }
        AgentModePortal.attachReceipt(
            result = result,
            snapshot = snapshot,
            previous = previous,
            usedBackend = backend,
            target = activeTargetNode.get(),
            compact = AgentModePortal.shouldCompactReceipt(
                action = resolvedAction,
                skipCapture = activeSkipCapture.get() == true,
            ),
        )
        val protectedPkg = AgentModeSafety.protectedWindowPackage(
            snapshot.nodes,
            lastLaunchedPackageName,
        )
        if (protectedPkg != null && resolvedAction in portalMutatingActions) {
            result.put("ok", false)
            result.put("code", AgentModeSafety.ProtectedWindowCode)
            result.put("protected_package", protectedPkg)
            result.put("errmsg", "Refusing to operate a protected window ($protectedPkg).")
            result.put("consistent", "no")
            result.put("degraded_to", AgentModeSafety.ReplanDegradedTo)
            GuiFailureSignals.attach(result, resolvedAction, snapshot, previous)
        }
        val diffChanged = result.optJSONObject("tree_diff")?.optBoolean("changed") == true
        if (!diffChanged) result.put("delta_budget", true)
        if (resolvedAction in portalMutatingActions && previous != null && !diffChanged) {
            val stalledFor = stallMutatingCount.incrementAndGet()
            if (stalledFor >= AgentModeSafety.StallMutatingLimit) {
                result.put("ok", false)
                result.put("code", AgentModeSafety.StalledCode)
                result.put("errmsg", "UI did not change after $stalledFor mutating gestures.")
                result.put("consistent", "no")
                result.put("degraded_to", AgentModeSafety.ReplanDegradedTo)
                GuiFailureSignals.attach(result, resolvedAction, snapshot, previous)
            }
        } else if (resolvedAction in portalMutatingActions && diffChanged) {
            stallMutatingCount.set(0)
        }
        signalStore?.appendReceipt(
            attemptId = "controller",
            where = "agent_display/$resolvedAction",
            result = result,
        )
        return result
    }

    private fun invalidArguments(message: String): String =
        JSONObject().apply {
            put("ok", false)
            put("errmsg", message)
        }.toString()

    private fun toolError(
        message: String,
        action: String,
        code: String? = null,
    ): String = JSONObject().apply {
        put("ok", false)
        put("action", action)
        put("errmsg", message)
        put("stdout", "")
        if (!code.isNullOrBlank()) {
            put("code", code)
            if (code in AgentModeSafety.InterruptCodes) {
                put("consistent", "no")
                put("degraded_to", AgentModeSafety.ReplanDegradedTo)
            }
        }
    }.toString()

    private fun currentDeviceDisplaySpec(): AgentModeDisplaySpec {
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        if (display != null) {
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
        } else {
            val fallback = context.resources.displayMetrics
            metrics.widthPixels = fallback.widthPixels
            metrics.heightPixels = fallback.heightPixels
            metrics.densityDpi = fallback.densityDpi
        }
        val width = metrics.widthPixels.takeIf { it > 0 }
            ?: display?.mode?.physicalWidth?.takeIf { it > 0 }
            ?: FallbackAgentDisplayWidth
        val height = metrics.heightPixels.takeIf { it > 0 }
            ?: display?.mode?.physicalHeight?.takeIf { it > 0 }
            ?: FallbackAgentDisplayHeight
        val densityDpi = metrics.densityDpi.takeIf { it > 0 } ?: FallbackAgentDisplayDensityDpi
        return scaleAgentModeDisplaySpec(
            physicalWidth = width,
            physicalHeight = height,
            densityDpi = densityDpi,
        )
    }

    private data class RootCommandResult(
        val exitCode: Int,
        val stdout: String = "",
        val stderr: String = "",
        val timedOut: Boolean = false,
        val launchError: String = "",
    ) {
        fun combinedOutput(): String = listOf(stdout, stderr, launchError)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .joinToString("\n")
    }

    private fun toNormalizedAxis(value: Int, size: Int): Int {
        if (size <= 1) return 0
        return ((value.coerceIn(0, size - 1) * 1000f) / (size - 1)).roundToInt().coerceIn(0, 1000)
    }

    private suspend fun captureTeachingPageIfNeeded(settings: AppSettings) {
        if (!teachingActive.get()) return
        val displayId = currentManagedDisplayId(settings) ?: return
        runCatching {
            requireAgentModeService(settings).waitUntilLayoutStable(displayId, 40, 700)
        }
        val tree = dumpCompactUiTree(settings, displayId)
        if (tree.isBlank()) return
        notifyTeachingPageSettled(tree)
    }

    private suspend fun dumpCompactUiTree(settings: AppSettings, displayId: Int): String {
        val raw = runCatching {
            requireAgentModeService(settings).dumpUiTree(displayId)
        }.getOrDefault("")
        val compact = AgentModeUiTree.compactFromUiautomatorXml(
            xml = raw,
            width = _displayState.value.width,
            height = _displayState.value.height,
        )
        return compact.ifBlank { AgentModeUiTree.clickTargetsText(raw).ifBlank { raw } }
    }

    private fun notifyTeachingPageSettled(compactTree: String) {
        userGestureListener?.onTeachingPageSettled(compactTree)
        additionalUserGestureListeners.forEach { listener ->
            listener.onTeachingPageSettled(compactTree)
        }
    }

    private fun noteTeachingGesture() {
        if (!teachingActive.get()) return
        teachingGestures.incrementAndGet()
        lastTeachingGestureAt.set(System.currentTimeMillis())
    }

    fun addUserGestureListener(listener: AgentModeUserGestureListener) {
        additionalUserGestureListeners.addIfAbsent(listener)
    }

    fun removeUserGestureListener(listener: AgentModeUserGestureListener) {
        additionalUserGestureListeners.remove(listener)
    }

    private fun notifyUserTap(normalizedX: Int, normalizedY: Int) {
        noteTeachingGesture()
        userGestureListener?.onUserTap(normalizedX, normalizedY)
        additionalUserGestureListeners.forEach { listener ->
            listener.onUserTap(normalizedX, normalizedY)
        }
    }

    private fun notifyUserSwipe(
        normalizedX1: Int,
        normalizedY1: Int,
        normalizedX2: Int,
        normalizedY2: Int,
        durationMs: Int,
    ) {
        noteTeachingGesture()
        userGestureListener?.onUserSwipe(
            normalizedX1,
            normalizedY1,
            normalizedX2,
            normalizedY2,
            durationMs,
        )
        additionalUserGestureListeners.forEach { listener ->
            listener.onUserSwipe(
                normalizedX1,
                normalizedY1,
                normalizedX2,
                normalizedY2,
                durationMs,
            )
        }
    }
}

interface AgentModeUserGestureListener {
    fun onUserTap(normalizedX: Int, normalizedY: Int)
    fun onUserSwipe(
        normalizedX1: Int,
        normalizedY1: Int,
        normalizedX2: Int,
        normalizedY2: Int,
        durationMs: Int,
    )

    fun onTeachingPageSettled(compactTree: String) = Unit
}
