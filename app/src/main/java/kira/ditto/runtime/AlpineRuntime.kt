package kira.ditto.runtime

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import kira.ditto.data.AetherDiagnosticLogger
import kira.ditto.data.AlpineEnvironmentVariable
import kira.ditto.data.DefaultKimiPermissionMode
import kira.ditto.data.DefaultUpaProxyBase
import kira.ditto.data.KimiBrowserSubagentProfileMarkdown
import kira.ditto.data.KimiBrowserSubagentProfileName
import kira.ditto.data.KimiImageSubagentProfileMarkdown
import kira.ditto.data.KimiImageSubagentProfileName
import kira.ditto.data.KimiPhoneSubagentProfileMarkdown
import kira.ditto.data.KimiPhoneSubagentProfileName
import kira.ditto.data.LocalRuntimeId
import kira.ditto.data.SupportedKimiPermissionModes
import kira.ditto.data.UpaPluginLibrary
import kira.ditto.data.extractOfficialRemoteControlUrl
import kira.ditto.data.acpMcpServersFingerprint
import kira.ditto.data.kimiAgentsMdContents
import kira.ditto.data.kimi.AcpListedSession
import kira.ditto.data.kimi.KimiAcpClient
import kira.ditto.data.kimi.KimiAcpInteractionHandler
import kira.ditto.data.kimi.KimiAcpProtocol
import kira.ditto.data.normalizeAlpineEnvironmentVariables
import kira.ditto.data.stepfunUsesStepPlan
import kira.ditto.data.SessionMemoryMcp
import kira.ditto.data.toAcpMcpServer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONArray

private const val KimiTurnGateWaitTimeoutMillis = 90_000L
private const val KimiSetupTimeoutMillis = 75_000L
private const val KimiTurnWatchdogSilenceMillis = 180_000L
private const val KimiTurnWatchdogCheckMillis = 5_000L

/** Silence is not a stall while the agent is waiting on the user, nested tools, GUI teaching, or a live terminal. */
internal fun kimiTurnWatchdogIsExempt(
    pendingInteraction: Boolean,
    inFlightTools: Boolean,
    guiTeaching: Boolean,
    activeTerminals: Boolean,
): Boolean = pendingInteraction || inFlightTools || guiTeaching || activeTerminals

private const val ManagedProcessSpawnTimeoutMillis = 20_000L
private const val AlpineWatchWindowMillis = 45_000L
private const val AlpineDefaultTailBytes = 12 * 1024
private const val AlpineMaxTailBytes = 64 * 1024
private const val AlpineNetworkRateWindowMillis = 5_000L
internal fun canBoundReuseKimiSession(
    canReuse: Boolean,
    needsNewSession: Boolean,
    isBound: Boolean,
): Boolean = canReuse && !needsNewSession && isBound

internal enum class KimiSessionRestoreKind {
    BoundReuse,
    LoadOrResume,
    New,
}

internal fun kimiSessionRestoreKind(
    canReuse: Boolean,
    needsNewSession: Boolean,
    isBound: Boolean,
): KimiSessionRestoreKind = when {
    canBoundReuseKimiSession(canReuse, needsNewSession, isBound) ->
        KimiSessionRestoreKind.BoundReuse
    canReuse -> KimiSessionRestoreKind.LoadOrResume
    else -> KimiSessionRestoreKind.New
}

internal fun isUnknownKimiSessionError(error: Throwable): Boolean =
    generateSequence(error) { it.cause }
        .any { it.message.orEmpty().contains("Unknown sessionId", ignoreCase = true) }

internal fun isKimiUserCancelError(error: Throwable): Boolean =
    generateSequence(error) { it.cause }
        .any { it.message.orEmpty().contains("Turn cancelled", ignoreCase = true) }

internal fun alpineHostAbiFolder(abis: Array<out String>): String {
    val primary = abis.firstOrNull().orEmpty()
    if (primary == "x86_64" || primary == "x86") {
        return if ("x86_64" in abis) "x86_64" else ""
    }
    if (primary == "arm64-v8a" || primary == "armeabi-v7a") {
        return if ("arm64-v8a" in abis) "arm64-v8a" else ""
    }
    return when {
        "x86_64" in abis -> "x86_64"
        "arm64-v8a" in abis -> "arm64-v8a"
        else -> ""
    }
}

private fun alpineHostAbiFolder(): String = alpineHostAbiFolder(Build.SUPPORTED_ABIS)

internal fun alpineGuestShellArgs(command: String?, interactive: Boolean): List<String> {
    return if (interactive) {
        listOf("/bin/sh", "-i")
    } else {
        listOf("/bin/sh", "-lc", command.orEmpty())
    }
}

private fun alpineAssetRoot(): String = "runtimes/alpine/${alpineHostAbiFolder()}"
private const val KimiAssetRoot = "runtimes/kimi"
internal const val BundledKimiCodeCliVersion = "0.41.0"
private const val KimiCodeVersion = BundledKimiCodeCliVersion
private const val KimiCodeAsset = "$KimiAssetRoot/kimi-code-$KimiCodeVersion.tgz"
private const val KimiCodeInstallRoot = "/root/.kimi-code-mobile"
private const val KimiCodeHome = "/root/.kimi-code"
private val KimiPermissionModes = SupportedKimiPermissionModes.toSet()
private const val EverMeVersion = "0.6.1"
private const val EverCliVersion = "0.32.0"
private const val EverMeAssetRoot = "runtimes/everme"
private const val EverMePluginAsset = "$EverMeAssetRoot/everme-kimicode-bundle-$EverMeVersion.tgz"
// AAPT may expand .gz assets to .tar, or leave the original .tar.gz in the APK.
private fun everCliAssetCandidates(): List<String> {
    val stem = if (alpineHostAbiFolder() == "x86_64") {
        "evercli_linux_amd64"
    } else {
        "evercli_linux_arm64"
    }
    return listOf(
        "$EverMeAssetRoot/$stem.tar",
        "$EverMeAssetRoot/$stem.tar.gz",
    )
}
// +deps-1: kimi-code ESM-imports ws/qrcode; the npm pack tarball
// does not include node_modules, so those packages (plus qrcode's
// dijkstrajs/pngjs) are vendored inside the asset tgz.
private const val SessionMemoryVersion = SessionMemoryMcp.Version
private const val SessionMemoryAssetRoot = "runtimes/session-memory"
private const val SessionMemoryInstallRoot = SessionMemoryMcp.InstallRoot
// Runtime instances share one installed filesystem. Serialize preparation across instances too.
private val SharedRuntimeInitializeMutex = Mutex()
private val SharedKimiInstallMutex = Mutex()

private const val BundledAgentRuntimeVersion =
    "$KimiCodeVersion+everme-$EverMeVersion+evercli-$EverCliVersion+acp-1+deps-1+mem-$SessionMemoryVersion"
private const val EverMeBundleRoot = "$KimiCodeInstallRoot/node_modules/@everme/kimicode"
private const val EverMeStagedRoot = "$KimiCodeHome/everme"
private const val EverMeEnvironmentFile = "$KimiCodeHome/everme.env"
private const val EverMeCredentialsFile = "/root/.local/share/evercli/credentials.json"
private const val EverMeConfigFile = "/root/.config/evercli/config.yaml"
private const val EverMeDefaultApiBase = "https://api.everme.evermind.ai"
private const val EverMeDittoPlatform = "ditto"
// EverMe's managed backend rejects unlisted platform identifiers. Use its
// generic CLI host category while exposing this integration by the exact
// agent name "ditto"; never register it under the Kimi Code category.
private const val EverMeCloudPlatform = "evercli"
private const val EverMeDittoStageMarker = ".ditto-everme-$EverMeVersion"
private const val EverMePendingDisconnectFile = "/root/.aether/everme-legacy-agent.pending"
private const val KimiNodeCompileCacheGuestPath = "/root/.aether/node-compile-cache"
private const val KimiBinaryGuestPath = "/usr/local/bin/kimi"

/** The O_CREAT|O_EXCL open that replaces the hard link; also the "already patched" probe. */
private const val QueryStoreExclusiveCreate =
    "const __aetherMetaHandle = await fs\$1.open(metaPath, \"wx\");"

private const val KimiMainJsGuestPath =
    "$KimiCodeInstallRoot/node_modules/@moonshot-ai/kimi-code/dist/main.mjs"
private const val AlpineHostLinker = "/system/bin/linker64"
private const val AlpineNetworkTraceUrl = "https://www.cloudflare.com/cdn-cgi/trace"
private const val AlpineNetworkDetectionTimeoutMillis = 4_000
private const val AlpineOfficialRepository = "https://dl-cdn.alpinelinux.org/alpine"
private const val AlpineChinaRepository = "https://mirrors.tuna.tsinghua.edu.cn/alpine"
private fun alpineRootfsAssetCandidates(): List<RootfsAsset> {
    val root = alpineAssetRoot()
    return listOf(
        RootfsAsset("$root/rootfs.tar.gz", compressed = true),
        RootfsAsset("$root/rootfs.tar", compressed = false),
    )
}

internal fun resolveKimiNodeHeapMb(totalRamBytes: Long): Int {
    val gb = totalRamBytes / (1024L * 1024L * 1024L)
    return when {
        gb >= 12L -> 768
        gb >= 8L -> 512
        else -> 384
    }
}

internal enum class ApkNetworkEnvironment {
    China,
    International,
    Unknown,
}

internal class KimiTurnGate {
    // setupMutex: provider/session resolution and process spawn are globally
    // exclusive. Turns hold it only for the setup section; prewarm uses it too.
    // The wait is bounded so a wedged prewarm cannot leave later turns on
    // "设置工作环境中" forever.
    // turnMutex: ACP is a single process with one prompt slot. Sessions stay
    // serial at the prompt, with a bounded wait and a visible busy error.
    private val setupMutex = Mutex()
    private val turnMutex = Mutex()

    suspend fun <T> runSetup(block: suspend () -> T): T {
        val acquired = withTimeoutOrNull(KimiSetupTimeoutMillis) {
            setupMutex.lock()
            true
        } ?: false
        if (!acquired) {
            error("Kimi 进程启动失败：工作环境占用超过 ${KimiSetupTimeoutMillis / 1000}s。")
        }
        return try {
            withTimeout(KimiSetupTimeoutMillis) { block() }
        } catch (timeout: TimeoutCancellationException) {
            throw IllegalStateException(
                "Kimi 进程启动失败：工作环境设置超过 ${KimiSetupTimeoutMillis / 1000}s。",
                timeout,
            )
        } finally {
            setupMutex.unlock()
        }
    }

    suspend fun <T> runTurn(
        waitTimeoutMillis: Long,
        onBusy: () -> T,
        block: suspend () -> T,
    ): T {
        val acquired = withTimeoutOrNull(waitTimeoutMillis) {
            turnMutex.lock()
            true
        } ?: false
        if (!acquired) return onBusy()
        return try {
            block()
        } finally {
            turnMutex.unlock()
        }
    }
}

internal fun parseCloudflareCountryCode(trace: String): String? =
    trace.lineSequence()
        .firstOrNull { it.startsWith("loc=", ignoreCase = true) }
        ?.substringAfter('=')
        ?.trim()
        ?.takeIf { it.length == 2 }
        ?.uppercase()

internal fun apkRepositories(
    original: String,
    environment: ApkNetworkEnvironment,
): String =
    original.lines().flatMap { line ->
        val official = line
            .replace(Regex("https?://dl-cdn\\.alpinelinux\\.org/alpine"), AlpineOfficialRepository)
            .replace(Regex("https?://mirrors\\.tuna\\.tsinghua\\.edu\\.cn/alpine"), AlpineOfficialRepository)
        if (official == line &&
            "dl-cdn.alpinelinux.org/alpine" !in line &&
            "mirrors.tuna.tsinghua.edu.cn/alpine" !in line
        ) {
            listOf(line)
        } else {
            val china = official.replace(AlpineOfficialRepository, AlpineChinaRepository)
            when (environment) {
                ApkNetworkEnvironment.China -> listOf(china, official)
                ApkNetworkEnvironment.International -> listOf(official)
                ApkNetworkEnvironment.Unknown -> listOf(official, china)
            }
        }
    }.distinct().joinToString("\n")

internal fun chinaApkRepositories(original: String): String =
    apkRepositories(original, ApkNetworkEnvironment.China)

internal fun kimiAcpLaunchCommandText(nodeMaxOldSpaceMb: Int): String =
    "HOME=/root KIMI_CODE_HOME='$KimiCodeHome' " +
        "KIMI_WEB_SEARCH_BASE_URL='$DittoWebSearchUrl' " +
        "KIMI_WEB_SEARCH_API_KEY='$DittoWebSearchApiKey' " +
        "KIMI_WEB_FETCH_BASE_URL='$DittoWebFetchUrl' " +
        "KIMI_WEB_FETCH_API_KEY='$DittoWebSearchApiKey' " +
        "KIMI_CODE_IDENTITY_NAME='Ditto' " +
        "KIMI_CODE_EXPERIMENTAL_FLAG=1 " +
        "KIMI_CODE_EXPERIMENTAL_TOWER=1 " +
        // Android /data/data forbids hardlink(2); the minidb session-index
        // mirror's atomic link() then fails with EACCES and its flush timer
        // retries every 100ms with no backoff, starving the event loop.
        "KIMI_CODE_EXPERIMENTAL_PERSISTENCE_MINIDB_READMODEL=0 " +
        "NODE_COMPILE_CACHE='$KimiNodeCompileCacheGuestPath' " +
        "NODE_OPTIONS='--max-old-space-size=$nodeMaxOldSpaceMb' " +
        "kimi acp"

/** Map Aether UI ids (default|plan|auto|yolo) to kimi-code config.toml permission. */
internal fun kimiTomlPermissionMode(uiMode: String): String =
    when (uiMode.trim().lowercase()) {
        "auto" -> "auto"
        "yolo" -> "yolo"
        else -> "manual"
    }

internal fun kimiTomlPlanMode(uiMode: String): Boolean =
    uiMode.trim().lowercase() == "plan"

internal fun kimiManagedConfigToml(
    modelAlias: String,
    permissionMode: String,
    providerId: String,
    providerType: String,
    apiKey: String,
    baseUrl: String,
    modelId: String,
    contextWindow: Int,
    maxTokens: Int,
    extraHeaders: Map<String, String> = emptyMap(),
): String = buildString {
    val resolvedUi = permissionMode.trim().lowercase().ifBlank { DefaultKimiPermissionMode }
    val tomlPermission = kimiTomlPermissionMode(resolvedUi)
    appendLine("# Aether-managed kimi-code config. Do not append duplicate provider tables.")
    appendLine("default_model = ${tomlQuote(modelAlias)}")
    appendLine("default_permission_mode = ${tomlQuote(tomlPermission)}")
    if (tomlPermission == "yolo") appendLine("yolo = true")
    if (kimiTomlPlanMode(resolvedUi)) appendLine("default_plan_mode = true")
    appendLine()
    appendLine("[identity]")
    appendLine("name = \"Ditto\"")
    appendLine()
    appendLine("[services.moonshot_search]")
    appendLine("base_url = ${tomlQuote(DittoWebSearchUrl)}")
    appendLine("api_key = ${tomlQuote(DittoWebSearchApiKey)}")
    appendLine()
    appendLine("[services.moonshot_fetch]")
    appendLine("base_url = ${tomlQuote(DittoWebFetchUrl)}")
    appendLine("api_key = ${tomlQuote(DittoWebSearchApiKey)}")
    appendLine()
    appendLine("[providers.${tomlQuote(providerId)}]")
    appendLine("type = ${tomlQuote(providerType)}")
    appendLine("api_key = ${tomlQuote(apiKey)}")
    if (baseUrl.isNotBlank()) appendLine("base_url = ${tomlQuote(baseUrl)}")
    appendLine("default_model = ${tomlQuote(modelAlias)}")
    if (extraHeaders.isNotEmpty()) {
        appendLine()
        appendLine("[providers.${tomlQuote(providerId)}.custom_headers]")
        extraHeaders.forEach { (name, value) ->
            appendLine("${tomlQuote(name)} = ${tomlQuote(value)}")
        }
    }
    appendLine()
    appendLine("[models.${tomlQuote(modelAlias)}]")
    appendLine("provider = ${tomlQuote(providerId)}")
    appendLine("model = ${tomlQuote(modelId)}")
    appendLine("max_context_size = $contextWindow")
    appendLine("max_tokens = $maxTokens")
    appendLine("display_name = ${tomlQuote(modelId)}")
    appendLine()
    appendLine("[experimental]")
    appendLine("subagent_fork = true")
    appendLine("tower = true")
}

private fun tomlQuote(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

class AlpineRuntime(
    context: Context,
    private val diagnosticLogger: AetherDiagnosticLogger = AetherDiagnosticLogger.NoOp,
) : LocalRuntime {
    private val appContext = context.applicationContext
    private val runtimeRoot = File(appContext.filesDir, "runtimes/alpine")
    private val stagingRoot = File(runtimeRoot.parentFile, "alpine-installing")
    private val rootfsDir = File(runtimeRoot, "rootfs")
    private val workspaceDir = File(runtimeRoot, "workspace")
    private val hostBinDir = File(runtimeRoot, "bin")
    private val hostLibDir = File(runtimeRoot, "lib")
    private val hostTmpDir = File(runtimeRoot, "tmp")
    private val termJobsDir = File(hostTmpDir, "term-jobs")
    private val termDaemonLock = Any()
    @Volatile
    private var termDaemonProcess: Process? = null
    private val kimiNodeMaxOldSpaceMb: Int by lazy {
        val memory = ActivityManager.MemoryInfo()
        val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (activityManager != null) {
            activityManager.getMemoryInfo(memory)
            resolveKimiNodeHeapMb(memory.totalMem)
        } else {
            384
        }
    }
    private val loaderDir = File(runtimeRoot, "libexec/proot")
    private val loaderFile = File(loaderDir, "loader")
    private val prootFile = File(hostBinDir, "proot")
    private val libTallocFile = File(hostLibDir, "libtalloc.so.2")
    private val runs = ConcurrentHashMap<String, AlpineRun>()
    private val nextRunId = AtomicInteger(1)
    private val packageInstallMutex = Mutex()
    private val apkRepositoryMutex = Mutex()
    private val kimiInstallMutex = SharedKimiInstallMutex
    private val kimiProviderMutex = Mutex()
    private val kimiSessionMutex = Mutex()
    private val kimiTurnGate = KimiTurnGate()
    private val kimiSessionMapLock = Any()
    private val initializeMutex = SharedRuntimeInitializeMutex
    @Volatile
    private var completedSetupState: LocalRuntimeSetupState? = null
    @Volatile
    private var kimiBundleReady = false
    @Volatile
    private var kimiAskUserPatched = false
    @Volatile
    private var kimiAcpFailurePatched = false
    @Volatile
    private var kimiSessionMemoryPatched = false
    @Volatile
    private var alpineReadyCached = false
    private var lastWrittenMcpJson: String? = null
    private var lastWrittenAgentsMd: String? = null
    private var lastSessionUiConfig: String? = null
    @Volatile
    private var kimiProviderFingerprint: String? = null
    @Volatile
    private var kimiProcessFingerprint: String? = null
    @Volatile
    private var kimiPermissionMode: String = DefaultKimiPermissionMode
    @Volatile
    private var kimiInteractionHandler: KimiAcpInteractionHandler? = null
    private val kimiToAetherSessionIds = ConcurrentHashMap<String, String>()
    private val pendingSteerPrompts = ConcurrentLinkedQueue<JSONArray>()
    @Volatile
    var onKimiTurnSettled: (suspend (aetherSessionId: String, kimiSessionId: String) -> Unit)? = null
    @Volatile
    var onKimiSessionPrepared: ((aetherSessionId: String, kimiSessionId: String) -> Unit)? = null
    @Volatile
    private var everMeBranded = false
    private val acpClientDelegate = lazy { KimiAcpClient(this, diagnosticLogger) }
    private val acpClient by acpClientDelegate
    private val remoteControlLock = Any()
    @Volatile
    private var remoteControlProcess: Process? = null
    @Volatile
    private var remoteControlUrl: String = ""
    /**
     * True while the user is demonstrating on the virtual screen. Nested
     * phone Agent tools also hold in-flight ACP ids; this covers the gap
     * after the subagent asked for teaching and before the next MCP poll.
     */
    @Volatile
    var isGuiTeachingActive: () -> Boolean = { false }
    private val webSearchGateway = DittoWebSearchGateway()
    private val upaMcpGateway = UpaMcpHttpGateway().also { gateway ->
        gateway.onToolActivity = {
            if (acpClientDelegate.isInitialized()) acpClient.noteActivity()
        }
    }
    @Volatile
    private var upaPluginLibrary: UpaPluginLibrary? = null
    private val kimiMcpGeneration = AtomicInteger(0)
    private var kimiSessionMapCache: JSONObject? = null
    @Volatile
    private var pendingKimiConfigRestart: Boolean = false
    private val kimiPrewarmEpoch = AtomicInteger(0)
    private val kimiEagerBackoffMs = AtomicLong(0)
    private val kimiEagerNotBeforeElapsed = AtomicLong(0)
    @Volatile
    private var upaProxyBaseOverride: String = ""
    @Volatile
    private var startupNetworkEnvironment: ApkNetworkEnvironment? = null
    @Volatile
    private var apkRepositoriesConfigured = false
    @Volatile
    private var environmentVariables: List<AlpineEnvironmentVariable> = emptyList()

    override val id: LocalRuntimeId = LocalRuntimeId.Alpine
    override val displayName: String = "Alpine"
    override val homeDirectory: String = "/root"
    override val workspaceRoot: String = "/workspace"
    override val managedCommandsDirectory: String = "/root/.aether/bash-runs"

    fun hostWorkspaceDirectory(): File = workspaceDir

    fun pruneManagedBashRuns(retentionHours: Int): Long {
        val hours = retentionHours.coerceAtLeast(1)
        val cutoff = System.currentTimeMillis() - hours * 60L * 60L * 1000L
        val directory = runCatching { guestPathToHostFile(managedCommandsDirectory) }.getOrNull()
            ?: return 0L
        return deleteFilesOlderThan(directory, cutoff)
    }

    internal fun resolveWorkspaceHostPath(
        path: String,
        workingDirectory: String = workspaceRoot,
    ): AlpineWorkspaceHostPath? {
        val normalizedWorkingDirectory = normalizePath(workingDirectory.trim())
            .ifBlank { workspaceRoot }
        val normalizedGuestPath = when {
            path.isBlank() -> normalizedWorkingDirectory
            path.startsWith("/") -> java.nio.file.Paths.get(path).normalize().toString()
            else -> java.nio.file.Paths.get(normalizedWorkingDirectory).resolve(path).normalize().toString()
        }
        if (
            normalizedGuestPath != workspaceRoot &&
            !normalizedGuestPath.startsWith("$workspaceRoot/")
        ) {
            return null
        }
        val relativePath = normalizedGuestPath.removePrefix(workspaceRoot).trimStart('/')
        val canonicalWorkspace = workspaceDir.canonicalFile
        val hostFile = File(canonicalWorkspace, relativePath).canonicalFile
        if (
            hostFile != canonicalWorkspace &&
            !hostFile.path.startsWith("${canonicalWorkspace.path}${File.separator}")
        ) {
            return null
        }
        return AlpineWorkspaceHostPath(
            guestPath = normalizedGuestPath,
            hostFile = hostFile,
        )
    }

    fun setEnvironmentVariables(variables: List<AlpineEnvironmentVariable>) {
        environmentVariables = normalizeAlpineEnvironmentVariables(variables)
    }

    /** Permission mode applied to new/resumed Kimi sessions (default|plan|auto|yolo). */
    fun setKimiPermissionMode(mode: String) {
        kimiPermissionMode = mode.trim().lowercase()
            .takeIf { it in KimiPermissionModes }
            ?: DefaultKimiPermissionMode
    }

    /**
     * Installs the UI-layer interaction handler (permission/elicitation prompts).
     * The handler is also re-attached on every turn because the ACP client
     * process can be invalidated and recreated at any time.
     */
    fun setKimiInteractionHandler(handler: KimiAcpInteractionHandler?) {
        kimiInteractionHandler = handler
        if (acpClientDelegate.isInitialized()) {
            acpClient.interactionHandler = handler
        }
    }

    /**
     * Switches the permission mode of the live kimi session bound to
     * [aetherSessionId] and records it as the default for future sessions.
     * Returns false when no live kimi session is known (the new default still
     * applies to the next turn).
     */
    suspend fun setKimiSessionMode(aetherSessionId: String, modeId: String): Boolean {
        val normalized = modeId.trim().lowercase()
            .takeIf { it in KimiPermissionModes }
            ?: DefaultKimiPermissionMode
        setKimiPermissionMode(normalized)
        val kimiSessionId = kimiSessionIdForAetherSession(aetherSessionId) ?: return false
        val configOk = runCatching {
            acpClient.setConfigOption(kimiSessionId, "mode", normalized)
            true
        }.getOrDefault(false)
        val sessionMode = if (normalized == "plan") "plan" else "code"
        val modeOk = runCatching {
            acpClient.setMode(kimiSessionId, sessionMode)
            true
        }.getOrElse {
            if (sessionMode != "default") {
                runCatching {
                    acpClient.setMode(kimiSessionId, "default")
                    true
                }.getOrDefault(false)
            } else {
                false
            }
        }
        return configOk || modeOk
    }

    fun mappedKimiSessionId(aetherSessionId: String): String? =
        kimiSessionIdForAetherSession(aetherSessionId)

    fun kimiCodeSessionsHostDir(): File = guestPathToHostFile("$KimiCodeHome/sessions")

    fun ensureKimiAcpSessionDirectories() {
        workspaceDir.mkdirs()
        val sessions = kimiCodeSessionsHostDir()
        if (sessions.exists() && !sessions.isDirectory) {
            sessions.delete()
        }
        check(sessions.mkdirs() || sessions.isDirectory) {
            "Unable to create the Kimi session directory."
        }
    }

    fun mappedKimiSessionIds(): Set<String> {
        val ids = kimiToAetherSessionIds.keys.toMutableSet()
        val mapped = synchronized(kimiSessionMapLock) { readKimiSessionMap() }
        val keys = mapped.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.endsWith("::mcp")) continue
            mapped.optString(key).takeIf(String::isNotBlank)?.let(ids::add)
        }
        return ids
    }

    fun bindAetherKimiSession(aetherSessionId: String, kimiSessionId: String) {
        persistKimiSessionBinding(aetherSessionId, kimiSessionId)
    }

    fun unbindAetherKimiSession(aetherSessionId: String) {
        removeKimiSessionBinding(aetherSessionId)
    }

    fun setAvailableCommandsListener(listener: ((JSONObject) -> Unit)?) {
        acpClient.onAvailableCommandsUpdate = listener
    }

    fun setPlanDocumentListener(
        listener: ((aetherSessionId: String, path: String, markdown: String) -> Unit)?,
    ) {
        acpClient.onPlanDocumentWrite = if (listener == null) {
            null
        } else {
            { kimiSessionId, path, markdown ->
                val aetherId = aetherSessionIdForKimiSession(kimiSessionId)
                if (aetherId.isNotBlank()) listener(aetherId, path, markdown)
            }
        }
    }

    fun setIdleCronPromptHandler(listener: ((aetherSessionId: String, promptText: String) -> Unit)?) {
        acpClient.onIdleCronPrompt = if (listener == null) {
            null
        } else {
            { kimiSessionId, promptText ->
                val aetherId = kimiToAetherSessionIds[kimiSessionId]
                if (!aetherId.isNullOrBlank()) listener(aetherId, promptText)
            }
        }
    }

    suspend fun ensureKimiAcpReady(): Boolean {
        val setup = inspectSetup()
        if (!setup.isReady) return false
        acpClient.ensureReady()
        return true
    }

    fun isKimiRemoteControlRunning(): Boolean = remoteControlProcess?.isAlive == true

    fun kimiRemoteControlUrl(): String =
        remoteControlUrl.takeIf { isKimiRemoteControlRunning() }.orEmpty()

    fun stopKimiRemoteControl() {
        remoteControlUrl = ""
        val process = synchronized(remoteControlLock) {
            remoteControlProcess.also { remoteControlProcess = null }
        } ?: return
        runCatching { process.destroy() }
        if (!process.waitFor(800, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            runCatching { process.destroyForcibly() }
        }
    }

    suspend fun startKimiRemoteControl(waitForUrlMillis: Long = 25_000L): String {
        stopKimiRemoteControl()
        val process = startManagedProcess(
            command = kimiRemoteControlLaunchCommand(),
            workingDirectory = homeDirectory,
            redirectErrorStream = true,
        )
        synchronized(remoteControlLock) {
            remoteControlProcess = process
        }
        Thread(
            {
                runCatching {
                    process.inputStream.bufferedReader().use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            extractOfficialRemoteControlUrl(line)?.let { found ->
                                remoteControlUrl = found
                            }
                        }
                    }
                }
            },
            "kimi-rc-stdout",
        ).apply {
            isDaemon = true
            start()
        }
        val deadline = System.currentTimeMillis() + waitForUrlMillis
        while (System.currentTimeMillis() < deadline && process.isAlive && remoteControlUrl.isBlank()) {
            kotlinx.coroutines.delay(200)
        }
        if (!process.isAlive && remoteControlUrl.isBlank()) {
            error("kimi rc exited before advertising a Remote Control URL.")
        }
        return remoteControlUrl
    }

    private fun kimiRemoteControlLaunchCommand(): String =
        kimiAcpLaunchCommandText(kimiNodeMaxOldSpaceMb).replace("kimi acp", "kimi rc --no-open")

    fun enqueueSteerPrompt(prompt: JSONArray) {
        if (prompt.length() > 0) pendingSteerPrompts.add(prompt)
    }

    private fun takePendingSteerPrompt(): JSONArray? = pendingSteerPrompts.poll()

    suspend fun listAcpSessions(cwd: String? = workspaceRoot): List<AcpListedSession> =
        withContext(Dispatchers.IO) {
            runCatching {
                acpClient.ensureReady()
                val all = mutableListOf<AcpListedSession>()
                var cursor: String? = null
                repeat(20) {
                    val result = acpClient.listSessions(cwd, cursor)
                    all += KimiAcpProtocol.parseSessionList(result)
                    cursor = KimiAcpProtocol.parseSessionListCursor(result) ?: return@runCatching all
                }
                all
            }.getOrDefault(emptyList())
        }

    suspend fun forkAetherKimiSession(aetherSessionId: String): String? = withContext(Dispatchers.IO) {
        val kimiId = kimiSessionIdForAetherSession(aetherSessionId) ?: return@withContext null
        runCatching {
            acpClient.ensureReady()
            acpClient.forkSession(kimiId).optString("sessionId")
        }.getOrNull()?.takeIf(String::isNotBlank)
    }

    suspend fun deleteAetherKimiSession(aetherSessionId: String) = withContext(Dispatchers.IO) {
        val kimiId = kimiSessionIdForAetherSession(aetherSessionId)
        if (!kimiId.isNullOrBlank()) {
            runCatching {
                acpClient.ensureReady()
                acpClient.deleteSession(kimiId)
            }
        }
        removeKimiSessionBinding(aetherSessionId)
    }

    data class ReplayedKimiMessage(
        val role: String,
        val text: String,
    )

    data class WorkspaceFileEntry(
        val guestPath: String,
        val name: String,
    )

    suspend fun loadKimiSessionHistory(
        aetherSessionId: String,
        cwd: String = workspaceRoot,
    ): List<ReplayedKimiMessage> = withContext(Dispatchers.IO) {
        val kimiId = kimiSessionIdForAetherSession(aetherSessionId) ?: return@withContext emptyList()
        acpClient.ensureReady()
        val turns = mutableListOf<ReplayedKimiMessage>()
        val user = StringBuilder()
        val agent = StringBuilder()
        fun flushUser() {
            if (user.isNotBlank()) {
                turns += ReplayedKimiMessage("user", user.toString())
                user.clear()
            }
        }
        fun flushAgent() {
            if (agent.isNotBlank()) {
                turns += ReplayedKimiMessage("assistant", agent.toString())
                agent.clear()
            }
        }
        runCatching {
            acpClient.loadSession(
                sessionId = kimiId,
                cwd = cwd,
                onEvent = { name, payload ->
                    when (name) {
                        "user_message_chunk" -> {
                            flushAgent()
                            payload.optString("delta").takeIf(String::isNotEmpty)?.let(user::append)
                        }
                        "assistant_text_delta" -> {
                            flushUser()
                            payload.optString("delta").takeIf(String::isNotEmpty)?.let(agent::append)
                        }
                    }
                },
            )
        }
        flushUser()
        flushAgent()
        turns
    }

    fun listWorkspaceFileSuggestions(query: String, limit: Int = 50): List<WorkspaceFileEntry> {
        val normalizedQuery = query.trim().lowercase()
        val results = mutableListOf<WorkspaceFileEntry>()
        fun walk(directory: File, depth: Int) {
            if (results.size >= limit || depth > 4 || !directory.isDirectory) return
            val children = directory.listFiles()?.sortedBy { it.name.lowercase() } ?: return
            for (child in children) {
                if (results.size >= limit) return
                if (child.name.startsWith('.')) continue
                if (child.isDirectory) {
                    walk(child, depth + 1)
                    continue
                }
                val relative = child.relativeTo(workspaceDir).invariantSeparatorsPath
                val guestPath = "/workspace/$relative"
                if (
                    normalizedQuery.isEmpty() ||
                    relative.lowercase().contains(normalizedQuery) ||
                    child.name.lowercase().contains(normalizedQuery)
                ) {
                    results += WorkspaceFileEntry(guestPath = guestPath, name = child.name)
                }
            }
        }
        if (workspaceDir.isDirectory) walk(workspaceDir, 0)
        return results
    }

    /** Resolves the Aether chat session id owning a kimi ACP session ("" when unknown). */
    fun aetherSessionIdForKimiSession(kimiSessionId: String): String {
        if (kimiSessionId.isBlank()) return ""
        kimiToAetherSessionIds[kimiSessionId]?.let { return it }
        val mapped = synchronized(kimiSessionMapLock) { readKimiSessionMap() }
        val keys = mapped.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.endsWith("::mcp")) continue
            if (mapped.optString(key) == kimiSessionId) {
                kimiToAetherSessionIds[kimiSessionId] = key
                return key
            }
        }
        return ""
    }

    private fun kimiSessionIdForAetherSession(aetherSessionId: String): String? {
        if (aetherSessionId.isBlank()) return null
        kimiToAetherSessionIds.entries
            .firstOrNull { it.value == aetherSessionId }
            ?.let { return it.key }
        val mapped = synchronized(kimiSessionMapLock) { readKimiSessionMap() }
        return mapped.optString(aetherSessionId).takeIf(String::isNotBlank)
    }

    /**
     * Idempotent: the app runtime and the ViewModel both ask for provisioning during
     * startup, and doing the asset install twice concurrently is both slow and unsafe.
     * Only a ready result is memoised so a failed attempt can still be retried.
     */
    suspend fun initialize(
        onProgress: (AlpineSetupProgress) -> Unit = {},
    ): LocalRuntimeSetupState {
        completedSetupState?.let { return it }
        return initializeMutex.withLock {
            completedSetupState ?: performInitialize(onProgress).also { state ->
                if (state.isReady) completedSetupState = state
            }
        }
    }

    private suspend fun performInitialize(
        onProgress: (AlpineSetupProgress) -> Unit,
    ): LocalRuntimeSetupState = withContext(Dispatchers.IO) {
        inspectSetup().let { state ->
            if (
                state.issue != LocalRuntimeIssue.NotInstalled &&
                state.issue != LocalRuntimeIssue.Failed &&
                state.issue != LocalRuntimeIssue.MissingAssets
            ) {
                if (state.isReady) {
                    return@withContext runCatching {
                        ensureWorkspace()
                        ensureGuestNetworkConfig()
                        ensureWebSearchGateway()
                        ensureUpaMcpGateway()
                        ensureBundledKimiCode(onProgress)
                        restoreEverMeLoginState()
                        inspectSetup()
                    }.getOrElse { throwable ->
                        state.copy(
                            issue = LocalRuntimeIssue.Failed,
                            detail = throwable.message ?: "Failed to initialize bundled Kimi Code.",
                        )
                    }
                }
                return@withContext state
            }
        }
        if (!isSupportedAbi()) return@withContext unsupportedAbiState()
        if (!hasBundledRuntimeAssets()) {
            return@withContext LocalRuntimeSetupState(
                runtimeId = id,
                issue = LocalRuntimeIssue.MissingAssets,
                detail = "Alpine runtime assets are not bundled in this build.",
            )
        }
        runCatching {
            installFromAssets(onProgress)
            ensureWebSearchGateway()
            ensureUpaMcpGateway()
            ensureBundledKimiCode(onProgress)
            restoreEverMeLoginState()
        }.fold(
            onSuccess = {
                inspectSetup().let { state ->
                    if (state.isReady) {
                        state.copy(detail = "Alpine runtime is ready.")
                    } else {
                        state
                    }
                }
            },
            onFailure = { throwable ->
                LocalRuntimeSetupState(
                    runtimeId = id,
                    issue = LocalRuntimeIssue.Failed,
                    detail = throwable.message ?: "Failed to install Alpine runtime.",
                )
            },
        )
    }

    suspend fun reset(): LocalRuntimeSetupState = withContext(Dispatchers.IO) {
        completedSetupState = null
        runs.values.forEach { run ->
            run.process?.takeIf(Process::isAlive)?.let { process ->
                run.cancelled = true
                runCatching { process.destroy() }
                if (!process.waitFor(800, TimeUnit.MILLISECONDS)) {
                    runCatching { process.destroyForcibly() }
                }
            }
        }
        runs.clear()
        kimiBundleReady = false
        kimiAskUserPatched = false
        kimiAcpFailurePatched = false
        kimiSessionMemoryPatched = false
        alpineReadyCached = false
        lastWrittenMcpJson = null
        lastWrittenAgentsMd = null
        lastSessionUiConfig = null
        everMeBranded = false
        check(!runtimeRoot.exists() || runtimeRoot.deleteRecursively()) {
            "Unable to reset Alpine runtime data."
        }
        check(!stagingRoot.exists() || stagingRoot.deleteRecursively()) {
            "Unable to reset incomplete Alpine installation data."
        }
        LocalRuntimeSetupState(
            runtimeId = id,
            issue = LocalRuntimeIssue.NotInstalled,
            detail = "Alpine runtime data was reset.",
        )
    }

    suspend fun createTerminalLaunchSpec(): AlpineTerminalLaunchSpec = withContext(Dispatchers.IO) {
        requireReady()
        ensureWorkspace()
        ensureGuestNetworkConfig()
        val command = buildAlpineInteractiveCommand()
        AlpineTerminalLaunchSpec(
            executable = command.first(),
            arguments = command.toTypedArray(),
            environment = buildAlpineProcessEnvironment()
                .map { (key, value) -> "$key=$value" }
                .toTypedArray(),
            workingDirectory = runtimeRoot.absolutePath,
        )
    }

    suspend fun installAsset(
        assetPath: String,
        guestPath: String,
        executable: Boolean = false,
    ): File = withContext(Dispatchers.IO) {
        requireReady()
        val normalizedGuestPath = normalizePath(guestPath)
        val target = guestPathToHostFile(normalizedGuestPath)
        copyAsset(assetPath, target, executable)
        target
    }

    suspend fun installPreinstalledExtensions(): Unit = withContext(Dispatchers.IO) {
        installAssetDirectoryRecursively("extensions", "/root/.aether/extensions")
    }

    internal fun installPreinstalledExtensionsSync() {
        installAssetDirectoryRecursively("extensions", "/root/.aether/extensions")
    }

    private fun installAssetDirectoryRecursively(assetDir: String, guestTargetDir: String) {
        val list = runCatching { appContext.assets.list(assetDir) }.getOrNull() ?: return
        if (list.isEmpty()) return
        for (item in list) {
            if (
                assetDir == "extensions" &&
                guestPathToHostFile("/root/.aether/.removed-preinstalled-extensions/$item").existsNoFollow()
            ) {
                continue
            }
            val childAssetPath = "$assetDir/$item"
            val childGuestPath = "$guestTargetDir/$item"
            // Aether extensions are shared with user imports. Once a user owns a
            // top-level entry, never merge or overwrite it with bundled files.
            val existingTarget = guestPathToHostFile(normalizePath(childGuestPath))
            if (existingTarget.existsNoFollow()) continue
            val subList = runCatching { appContext.assets.list(childAssetPath) }.getOrNull()
            if (subList != null && subList.isNotEmpty()) {
                installAssetDirectoryRecursively(childAssetPath, childGuestPath)
            } else {
                runCatching {
                    val target = guestPathToHostFile(normalizePath(childGuestPath))
                    copyAsset(childAssetPath, target, executable = false)
                }
            }
        }
    }

    internal fun markPreinstalledExtensionRemoved(name: String) {
        val safeName = name.trim()
        require(safeName.isNotBlank() && '/' !in safeName && '\\' !in safeName) {
            "Invalid preinstalled extension name."
        }
        val marker = guestPathToHostFile("/root/.aether/.removed-preinstalled-extensions/$safeName")
        require(marker.parentFile?.mkdirs() != false || marker.parentFile?.isDirectory == true) {
            "Unable to store the removed preinstalled extension state."
        }
        marker.writeText("removed\n")
    }

    internal fun clearPreinstalledExtensionRemoved(name: String) {
        val marker = guestPathToHostFile("/root/.aether/.removed-preinstalled-extensions/$name")
        if (marker.existsNoFollow()) require(marker.delete()) {
            "Unable to restore the preinstalled extension state."
        }
    }

    internal suspend fun ensureGuestDirectory(guestPath: String): File = withContext(Dispatchers.IO) {
        requireReady()
        guestPathToHostFile(normalizePath(guestPath)).apply {
            require(mkdirs() || isDirectory) {
                "Unable to create Alpine directory: $guestPath"
            }
        }
    }

    internal suspend fun resolveGuestPath(guestPath: String): File = withContext(Dispatchers.IO) {
        requireReady()
        guestPathToHostFile(normalizePath(guestPath))
    }

    override suspend fun replaceHostDirectories(
        guestRootPath: String,
        signature: String,
        directories: Map<String, File>,
    ): Boolean = withContext(Dispatchers.IO) {
        requireReady()
        val targetRoot = guestPathToHostFile(normalizePath(guestRootPath))
        val signatureFileName = ".aether-mirror-signature"
        val currentSignature = File(targetRoot, signatureFileName)
            .takeIf(File::isFile)
            ?.readText()
        if (currentSignature == signature) return@withContext true

        val parent = targetRoot.parentFile
            ?: error("Unable to resolve Alpine mirror parent: $guestRootPath")
        require(parent.mkdirs() || parent.isDirectory) {
            "Unable to create Alpine mirror parent: $guestRootPath"
        }
        val staging = File(parent, ".${targetRoot.name}.staging-${UUID.randomUUID()}")
        val backup = File(parent, ".${targetRoot.name}.backup-${UUID.randomUUID()}")
        try {
            require(staging.mkdirs()) { "Unable to create Alpine mirror staging directory." }
            directories.toSortedMap().forEach { (name, source) ->
                require(name.matches(Regex("[A-Za-z0-9._-]+"))) {
                    "Invalid Alpine mirror directory name: $name"
                }
                val canonicalSource = source.canonicalFile
                require(canonicalSource.isDirectory) {
                    "Alpine mirror source is unavailable: ${source.path}"
                }
                copyDirectoryWithoutSymbolicLinks(canonicalSource, File(staging, name))
            }
            File(staging, signatureFileName).writeText(signature)

            if (targetRoot.exists()) {
                require(targetRoot.renameTo(backup)) {
                    "Unable to stage the existing Alpine mirror directory."
                }
            }
            if (!staging.renameTo(targetRoot)) {
                if (backup.exists()) backup.renameTo(targetRoot)
                error("Unable to activate the Alpine mirror directory.")
            }
            backup.deleteRecursively()
            true
        } finally {
            staging.deleteRecursively()
            if (backup.exists() && !targetRoot.exists()) backup.renameTo(targetRoot)
            if (targetRoot.exists()) backup.deleteRecursively()
        }
    }

    internal fun resolveManagedGuestPath(guestPath: String): File =
        guestPathToHostFile(normalizePath(guestPath))

    internal fun syncUpaPluginDirectory(pluginId: String, hostDir: File) {
        val safeId = pluginId.trim().ifBlank { return }
        val target = guestPathToHostFile(normalizePath("/root/.aether/upa-plugins/$safeId"))
        if (target.exists()) target.deleteRecursively()
        if (hostDir.isDirectory) {
            hostDir.copyRecursively(target, overwrite = true)
        }
    }

    suspend fun startManagedProcess(
        command: String,
        workingDirectory: String = homeDirectory,
        redirectErrorStream: Boolean = false,
        extraEnvironment: Map<String, String> = emptyMap(),
    ): Process = withContext(Dispatchers.IO) {
        val normalizedWorkingDirectory = normalizePath(workingDirectory)
        requireReady()
        ensureWorkspace()
        ensureGuestNetworkConfig()
        val processBuilder = buildAlpineProcess(command, normalizedWorkingDirectory).apply {
            extraEnvironment.forEach { (name, value) ->
                environment()[name] = value
            }
        }.redirectErrorStream(redirectErrorStream)
        return@withContext try {
            withTimeout(ManagedProcessSpawnTimeoutMillis) {
                runInterruptible { processBuilder.start() }
            }
        } catch (timeout: TimeoutCancellationException) {
            alpineReadyCached = false
            error("Alpine process spawn timed out after ${ManagedProcessSpawnTimeoutMillis / 1000}s.")
        }
    }

    internal suspend fun startAcpTerminalProcess(
        command: String,
        args: List<String>,
        workingDirectory: String,
        extraEnvironment: Map<String, String>,
    ): AlpineProcessHandle {
        val commandLine = buildString {
            append(command.ifBlank { "/bin/sh" })
            args.forEach { arg ->
                append(' ')
                append(shellQuote(arg))
            }
        }
        val cwd = workingDirectory.ifBlank { workspaceRoot }
        return withContext(Dispatchers.IO) {
            requireReady()
            ensureWorkspace()
            ensureGuestNetworkConfig()
            try {
                ensureTermDaemonLocked()
                val handle = writeAlpineTermJob(
                    jobsDir = termJobsDir,
                    jobId = UUID.randomUUID().toString(),
                    workingDirectory = cwd,
                    commandLine = commandLine,
                    extraEnvironment = extraEnvironment,
                )
                if (termDaemonProcess?.isAlive != true) {
                    error("term daemon exited")
                }
                handle
            } catch (error: Exception) {
                diagnosticLogger.event(
                    category = "alpine",
                    event = "term_daemon_fallback",
                    level = "warn",
                    details = mapOf("error" to (error.message ?: error.toString())),
                )
                JavaProcessHandle(
                    buildAlpineProcess(commandLine, normalizePath(cwd)).apply {
                        extraEnvironment.forEach { (name, value) ->
                            environment()[name] = value
                        }
                    }
                        .redirectErrorStream(true)
                        .start(),
                )
            }
        }
    }

    private fun ensureTermDaemonLocked() {
        synchronized(termDaemonLock) {
            cleanupStaleTermJobs()
            val live = termDaemonProcess
            if (live != null && live.isAlive) return
            termJobsDir.mkdirs()
            val scriptFile = guestPathToHostFile(AlpineTermDaemonGuestPath)
            scriptFile.parentFile?.mkdirs()
            scriptFile.writeText(AlpineTermDaemonScript)
            scriptFile.setExecutable(true)
            val started = buildAlpineProcess(
                command = "/bin/sh $AlpineTermDaemonGuestPath",
                workingDirectory = workspaceRoot,
                extraBinds = listOf(termJobsDir.absolutePath to AlpineTermJobsGuestPath),
            )
                .redirectOutput(File("/dev/null"))
                .redirectError(File("/dev/null"))
                .start()
            termDaemonProcess = started
        }
    }

    private fun cleanupStaleTermJobs() {
        val cutoff = System.currentTimeMillis() - 30L * 60L * 1000L
        termJobsDir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }

    fun setWebSearchBackend(tavilyApiKey: String, tavilyBaseUrl: String) {
        // Tavily is unused. Kimi WebSearch/FetchURL are fulfilled by Gecko.
    }

    fun attachGeckoWebSearch(
        search: (String, Int) -> List<DittoWebSearchHit>,
        fetch: (String) -> String,
    ) {
        webSearchGateway.attachExecutors(search, fetch)
    }

    fun ensureWebSearchGateway() {
        webSearchGateway.ensureStarted()
    }

    fun attachUpaPluginLibrary(library: UpaPluginLibrary) {
        upaPluginLibrary = library
    }

    fun attachAgentDisplayExecutor(executor: (org.json.JSONObject, String) -> String) {
        upaMcpGateway.attachAgentDisplayExecutor(executor)
    }

    fun attachPhoneAppFlow(
        listTools: () -> org.json.JSONObject,
        callTool: (String, org.json.JSONObject, String) -> String,
    ) {
        upaMcpGateway.attachPhoneAppFlow(listTools, callTool)
    }

    fun attachDeviceCatalogExecutor(executor: (org.json.JSONObject) -> String) {
        upaMcpGateway.attachDeviceCatalogExecutor(executor)
    }

    fun attachWebMcp(
        listTools: () -> org.json.JSONObject,
        callTool: (String, org.json.JSONObject, String) -> String,
    ) {
        upaMcpGateway.attachWebMcp(listTools, callTool)
    }

    fun attachGmailMcp(callTool: (String, org.json.JSONObject) -> String) {
        upaMcpGateway.attachGmailMcp(callTool)
    }

    fun attachSpotifyMcp(callTool: (String, org.json.JSONObject) -> String) {
        upaMcpGateway.attachSpotifyMcp(callTool)
    }

    fun attachAmapMcp(callTool: (String, org.json.JSONObject) -> String) {
        upaMcpGateway.attachAmapMcp(callTool)
    }

    fun attachGithubMcp(callTool: (String, org.json.JSONObject) -> String) {
        upaMcpGateway.attachGithubMcp(callTool)
    }

    fun attachHuggingFaceMcp(callTool: (String, org.json.JSONObject) -> String) {
        upaMcpGateway.attachHuggingFaceMcp(callTool)
    }

    fun ensureUpaMcpGateway() {
        upaMcpGateway.ensureStarted()
    }

    fun setUpaProxyBase(url: String) {
        upaProxyBaseOverride = url.trim().trimEnd('/')
    }

    suspend fun publishKimiMcpConfig() {
        ensureUpaMcpGateway()
        kimiMcpGeneration.incrementAndGet()
        // Never kill a process with an active prompt; the next turn applies
        // the new MCP config during its own setup instead.
        if (acpClient.hasActivePrompt()) {
            pendingKimiConfigRestart = true
        } else {
            acpClient.invalidate()
        }
        val library = upaPluginLibrary ?: return
        val servers = JSONArray()
        runCatching { writeKimiMcpJson(servers) }
        reapStaleUpaStdioProcesses()
    }

    private fun upaProxyBase(): String {
        val override = upaProxyBaseOverride.trim().trimEnd('/')
        if (override.isNotBlank()) return override
        val fromEnv = environmentVariables
            .firstOrNull { it.name.equals("UPA_PROXY_BASE", ignoreCase = true) }
            ?.value
            ?.trim()
            .orEmpty()
        if (fromEnv.isNotBlank()) return fromEnv.trimEnd('/')
        val fromFile = File(appContext.filesDir, "upa-proxy-base.txt")
            .takeIf { it.isFile }
            ?.readText()
            ?.trim()
            .orEmpty()
        if (fromFile.isNotBlank()) return fromFile.trimEnd('/')
        return DefaultUpaProxyBase
    }

    private suspend fun reapStaleUpaStdioProcesses() {
        runCatching {
            executeCommand(
                command = "pkill -f '/.aether/upa-plugins/.*/mcp/server' >/dev/null 2>&1 || true",
                workingDirectory = homeDirectory,
                awaitTimeoutMillis = 8_000L,
            )
        }
    }

    fun peekPersistedEverMeBinding(): EverMeBindingState {
        if (!hasPersistedEverMeLogin()) return EverMeBindingState()
        return EverMeBindingState(
            phase = EverMeBindingPhase.Bound,
            email = peekPersistedEverMeEmail(),
            detail = "EverMe is connected to Ditto.",
        )
    }

    suspend fun inspectEverMeBinding(): EverMeBindingState = withContext(Dispatchers.IO) {
        runCatching {
            requireReady()
            restoreEverMeLoginState()
            val account = readEverCliAccount() ?: run {
                restoreEverMeLoginState()
                readEverCliAccount()
            }
            val email = account?.optString("email").orEmpty().ifBlank { peekPersistedEverMeEmail() }
            val authenticated = account != null &&
                (account.optString("accountId").isNotBlank() || email.isNotBlank())
            if (!authenticated && !hasPersistedEverMeLogin()) {
                return@runCatching EverMeBindingState(
                    phase = EverMeBindingPhase.Idle,
                    detail = "Log in to EverMe to connect memory with Ditto.",
                )
            }
            persistEverMeLoginState(email)
            runCatching {
                ensureEverMeDittoPluginStaged()
                installStagedEverMePlugin()
            }
            EverMeBindingState(
                phase = EverMeBindingPhase.Bound,
                email = email,
                detail = "EverMe is connected to Ditto.",
            )
        }.getOrElse { error ->
            if (hasPersistedEverMeLogin()) {
                EverMeBindingState(
                    phase = EverMeBindingPhase.Bound,
                    email = peekPersistedEverMeEmail(),
                    detail = "EverMe is connected to Ditto.",
                )
            } else {
                val detail = error.message ?: "Unable to check the EverMe account."
                val signedOut = detail.contains("not_logged_in", ignoreCase = true) ||
                    detail.contains("not logged in", ignoreCase = true)
                EverMeBindingState(
                    phase = if (signedOut) EverMeBindingPhase.Idle else EverMeBindingPhase.Failed,
                    detail = if (signedOut) "Log in to EverMe to connect memory with Ditto." else detail,
                )
            }
        }
    }

    suspend fun startEverMeDeviceFlow(): EverMeBindingState = withContext(Dispatchers.IO) {
        val existing = inspectEverMeBinding()
        if (existing.isBound) {
            runCatching {
                disconnectCurrentEverMeAgent()
                guestPathToHostFile(EverMeEnvironmentFile).delete()
                everCliData(
                    executeCommand(
                        command = "HOME=/root evercli auth logout --no-prompt --format json",
                        workingDirectory = homeDirectory,
                        awaitTimeoutMillis = 60_000L,
                    )
                )
                clearPersistedEverMeLoginState()
            }.getOrElse { error ->
                return@withContext EverMeBindingState(
                    phase = EverMeBindingPhase.Failed,
                    email = existing.email,
                    detail = error.message ?: "Unable to sign out of the current EverMe account.",
                )
            }
        }
        runCatching {
            requireReady()
            val result = everCliData(
                executeCommand(
                    command = "HOME=/root evercli auth login --no-wait --no-prompt --format json",
                    workingDirectory = homeDirectory,
                    awaitTimeoutMillis = 60_000L,
                )
            )
            check(result.optString("status") == "pending") {
                "EverMe did not start a device authorization session."
            }
            val expiresInSec = result.optLong("expiresInSec").coerceAtLeast(0L)
            EverMeBindingState(
                phase = EverMeBindingPhase.AwaitingApproval,
                verificationUrl = result.optString("verificationUrl"),
                userCode = result.optString("userCode"),
                deviceCode = result.optString("deviceCode"),
                expiresAtMillis = System.currentTimeMillis() + expiresInSec * 1_000L,
                detail = "Approve this device in EverMe to connect memory with Ditto.",
            ).also { state ->
                check(state.verificationUrl.isNotBlank() && state.userCode.isNotBlank() && state.deviceCode.isNotBlank()) {
                    "EverMe returned an incomplete device authorization response."
                }
            }
        }.getOrElse { error ->
            EverMeBindingState(
                phase = EverMeBindingPhase.Failed,
                detail = error.message ?: "Unable to start EverMe login.",
            )
        }
    }

    suspend fun pollEverMeDeviceFlow(
        deviceCode: String,
        verificationUrl: String,
        userCode: String,
        expiresAtMillis: Long,
    ): EverMeBindingState = withContext(Dispatchers.IO) {
        if (deviceCode.isBlank()) {
            return@withContext EverMeBindingState(
                phase = EverMeBindingPhase.Failed,
                detail = "The EverMe authorization session is missing. Start login again.",
            )
        }
        runCatching {
            val result = everCliData(
                executeCommand(
                    command = "HOME=/root evercli auth login --device-code ${shellQuote(deviceCode)} --no-prompt --format json",
                    workingDirectory = homeDirectory,
                    awaitTimeoutMillis = 60_000L,
                )
            )
            when (result.optString("status")) {
                "pending" -> EverMeBindingState(
                    phase = EverMeBindingPhase.AwaitingApproval,
                    verificationUrl = verificationUrl,
                    userCode = userCode,
                    deviceCode = deviceCode,
                    expiresAtMillis = expiresAtMillis,
                    detail = "Waiting for EverMe authorization...",
                )

                "approved" -> {
                    ensureEverMeDittoPluginStaged()
                    runCatching { installStagedEverMePlugin() }
                    persistEverMeLoginState(result.optString("email"))
                    EverMeBindingState(
                        phase = EverMeBindingPhase.Bound,
                        email = result.optString("email"),
                        detail = "EverMe is connected to Ditto.",
                    )
                }

                else -> error("EverMe returned an unknown authorization status.")
            }
        }.getOrElse { error ->
            EverMeBindingState(
                phase = EverMeBindingPhase.Failed,
                verificationUrl = verificationUrl,
                userCode = userCode,
                expiresAtMillis = expiresAtMillis,
                detail = error.message ?: "EverMe authorization failed.",
            )
        }
    }

    suspend fun runKimiTurn(
        payload: JSONObject,
        onEvent: suspend (String, JSONObject) -> Unit = { _, _ -> },
    ): JSONObject {
        val normalizedPayload = JSONObject(payload.toString())
        val aetherSessionId = normalizedPayload.optString("session_id").ifBlank {
            "aether-${UUID.randomUUID()}".also { normalizedPayload.put("session_id", it) }
        }
        val isSideCompletion = aetherSessionId.startsWith("completion-")
        // Session titles and summaries share the same ACP process as the
        // visible chat; they never wait on the turn gate. The busy result
        // must carry error_message, otherwise callers treat it as an empty
        // success and retry pointlessly.
        if (isSideCompletion) {
            return busyTurnResult("Side completions never wait on the Kimi turn gate.")
        }
        return kimiTurnGate.runTurn(
            waitTimeoutMillis = KimiTurnGateWaitTimeoutMillis,
            onBusy = { busyTurnResult("Another Kimi turn is still running.") },
        ) {
            runKimiTurnLocked(normalizedPayload, onEvent)
        }
    }

    private fun busyTurnResult(reason: String): JSONObject =
        JSONObject()
            .put("ok", false)
            .put("assistant_text", "")
            .put("stop_reason", "busy")
            .put("error_message", reason)

    private data class KimiTurnSetup(
        val modelConfig: JSONObject,
        val aetherSessionId: String,
        val kimiSessionId: String,
        val cwd: String,
        val createdNewKimiSession: Boolean,
    )

    private data class KimiSessionResolution(
        val sessionId: String,
        val createdNew: Boolean,
    )

    private suspend fun prepareKimiTurn(payload: JSONObject): KimiTurnSetup {
        val setupStartedAt = SystemClock.elapsedRealtime()
        // hop 日志同时进 logcat（被华为系统隐藏）和诊断文件（可读回），
        // 用来定位「设置工作环境中」卡在哪一步。
        fun hop(name: String) {
            val elapsed = SystemClock.elapsedRealtime() - setupStartedAt
            Log.i("AetherAgentMode", "hop=$name elapsed_ms=$elapsed")
            diagnosticLogger.event(
                category = "kimi_turn",
                event = "hop",
                details = mapOf("hop" to name, "elapsed_ms" to elapsed),
            )
        }
        hop("kimi_setup_started")
        requireReady()
        hop("kimi_runtime_ready")
        ensureBundledKimiCode {}
        hop("kimi_bundle_ready")
        brandEverMeForDitto()
        val modelConfig = payload.optJSONObject("model_config")
            ?: error("Kimi Code provider configuration is missing.")
        val aetherSessionId = payload.optString("session_id").ifBlank {
            "aether-${UUID.randomUUID()}"
        }
        val providerSetup = configureKimiProvider(modelConfig)
        hop("kimi_provider_ready")
        writeKimiAgentsMd(payload.optString("system_prompt"))
        // MCP/plugin config changes that arrived while a turn was active are
        // applied here, before the next prompt, instead of killing the
        // process mid-turn.
        if (pendingKimiConfigRestart) {
            pendingKimiConfigRestart = false
            lastSessionUiConfig = null
            acpClient.invalidate()
        }
        if (providerSetup.requiresProcessRestart) {
            lastSessionUiConfig = null
            acpClient.invalidate()
        }
        acpClient.ensureReady()
        hop("kimi_acp_ready")
        // The client process may have been recreated since the handler was set;
        // re-attach it so permission/elicitation prompts always reach the UI.
        acpClient.interactionHandler = kimiInteractionHandler
        val cwd = payload.optString("workspace_directory").ifBlank { workspaceRoot }
        val resolvedSession = resolveOrCreateKimiSession(
            aetherSessionId = aetherSessionId,
            cwd = cwd,
            modelAlias = providerSetup.modelAlias,
            thinking = mapThinkingConfigValue(
                raw = payload.optString("reasoning"),
                modelConfig = modelConfig,
            ),
            mcpServers = payload.optJSONArray("mcp_servers") ?: JSONArray(),
            permissionMode = payload.optString("permission_mode").ifBlank { kimiPermissionMode },
        )
        hop("kimi_session_ready")
        runCatching {
            onKimiSessionPrepared?.invoke(aetherSessionId, resolvedSession.sessionId)
        }
        hop("kimi_cron_projected")
        return KimiTurnSetup(
            modelConfig = modelConfig,
            aetherSessionId = aetherSessionId,
            kimiSessionId = resolvedSession.sessionId,
            cwd = cwd,
            createdNewKimiSession = resolvedSession.createdNew,
        )
    }

    private suspend fun runKimiTurnLocked(
        payload: JSONObject,
        onEvent: suspend (String, JSONObject) -> Unit,
    ): JSONObject = withContext(Dispatchers.IO) {
        val setupStartedAt = SystemClock.elapsedRealtime()
        val setup = kimiTurnGate.runSetup {
            prepareKimiTurn(payload)
        }
        val modelConfig = setup.modelConfig
        var kimiSessionId = setup.kimiSessionId
        val cwd = setup.cwd
        val prompt = KimiAcpProtocol.promptBlocksFromUserContent(
            KimiAcpProtocol.turnUserContent(payload.optJSONArray("messages")),
        )
        check(prompt.length() > 0) { "Kimi Code prompt is empty." }
        Log.i(
            "AetherAgentMode",
            "hop=kimi_turn_ready elapsed_ms=${SystemClock.elapsedRealtime() - setupStartedAt} " +
                "session=$kimiSessionId",
        )
        onEvent("assistant_request_start", JSONObject())
        val streamedAssistant = StringBuilder()
        val streamedReasoning = StringBuilder()
        val onTurnEvent: suspend (String, JSONObject) -> Unit = { event, eventPayload ->
            when (event) {
                "assistant_text_delta" ->
                    eventPayload.optString("delta").takeIf(String::isNotEmpty)?.let(streamedAssistant::append)
                "assistant_reasoning_delta" ->
                    eventPayload.optString("delta").takeIf(String::isNotEmpty)?.let(streamedReasoning::append)
            }
            onEvent(event, eventPayload)
        }
        // Turn watchdog: inbound ACP frames, nested tool calls, MCP heartbeats,
        // user teaching, and live terminal sessions all count as live work.
        // Parent session/update goes quiet while Agent(phone) thinks, while
        // terminal/wait_for_exit runs a long command, or while a slow first
        // token is in flight. Do not kill those as a stall. Pure thinking with
        // no tools/terminals still has a 180s ceiling; the prompt timeout remains.
        val lastActivityAt = AtomicLong(SystemClock.elapsedRealtime())
        acpClient.activityListener = { lastActivityAt.set(SystemClock.elapsedRealtime()) }
        val watchdog = launch {
            while (true) {
                delay(KimiTurnWatchdogCheckMillis)
                if (
                    kimiTurnWatchdogIsExempt(
                        pendingInteraction = acpClient.hasPendingInteraction(),
                        inFlightTools = acpClient.hasInFlightTools(),
                        guiTeaching = isGuiTeachingActive(),
                        activeTerminals = acpClient.hasActiveTerminals(),
                    )
                ) {
                    // Live work is not silence. Reset so a 3-minute wait_for_exit
                    // does not immediately trip the watchdog the moment the process exits.
                    lastActivityAt.set(SystemClock.elapsedRealtime())
                    continue
                }
                val silentFor = SystemClock.elapsedRealtime() - lastActivityAt.get()
                if (silentFor > KimiTurnWatchdogSilenceMillis) {
                    diagnosticLogger.event(
                        category = "kimi_turn",
                        event = "turn_watchdog_fired",
                        level = "error",
                        details = mapOf(
                            "silent_ms" to silentFor,
                            "session" to kimiSessionId,
                            "in_flight_tools" to acpClient.hasInFlightTools(),
                            "teaching" to isGuiTeachingActive(),
                            "active_terminals" to acpClient.hasActiveTerminals(),
                        ),
                    )
                    acpClient.invalidate(
                        "Kimi ACP turn stalled: no activity for ${silentFor / 1000}s.",
                    )
                    break
                }
            }
        }
        var promptBlocks = KimiAcpProtocol.ensureDeskLeadPromptBlocks(
            prompt = KimiAcpProtocol.ensureRestoredHistoryPromptBlocks(
                prompt = prompt,
                messages = payload.optJSONArray("messages"),
                createdNewSession = setup.createdNewKimiSession,
            ),
            createdNewSession = setup.createdNewKimiSession,
            agentModeEnabled = payload.optBoolean("agent_mode_enabled"),
        )
        var result: JSONObject
        try {
            while (true) {
                result = try {
                    if (acpClient.hasBufferedIdleTurn(kimiSessionId)) {
                        acpClient.observeUntilIdle(kimiSessionId, onTurnEvent)
                    } else {
                        acpClient.prompt(kimiSessionId, promptBlocks, onTurnEvent)
                    }
                } catch (error: Throwable) {
                    val cancelled = error is CancellationException ||
                        error.message.orEmpty().contains("process restarted", ignoreCase = true) ||
                        error.message.orEmpty().contains("process exited", ignoreCase = true) ||
                        error.message.orEmpty().contains("Turn cancelled", ignoreCase = true)
                    if (!cancelled) throw error
                    val steered = takePendingSteerPrompt()
                    if (steered != null) {
                        promptBlocks = steered
                        continue
                    }
                    acpClient.abortActiveTurn()
                    if (
                        streamedAssistant.isEmpty() &&
                        streamedReasoning.isEmpty() &&
                        (
                            error is CancellationException ||
                                error.message.orEmpty().contains("process exited", ignoreCase = true)
                        )
                    ) {
                        if (error is CancellationException) throw error
                        throw IllegalStateException(
                            "Kimi 进程启动失败：${error.message ?: "ACP 进程已退出"}",
                            error,
                        )
                    }
                    JSONObject().put("stopReason", "cancelled")
                }
                val steered = takePendingSteerPrompt() ?: break
                promptBlocks = steered
            }
        } finally {
            watchdog.cancel()
            acpClient.activityListener = null
            runCatching {
                onKimiTurnSettled?.invoke(setup.aetherSessionId, kimiSessionId)
            }
        }
        val assistantText = streamedAssistant.toString()
        val reasoningText = streamedReasoning.toString()
        val stopReason = KimiAcpProtocol.mapStopReason(result.optString("stopReason"))
        JSONObject().apply {
            put("ok", true)
            put("assistant_text", assistantText)
            put("reasoning_text", reasoningText)
            put("assistant_message", JSONObject())
            put("provider", modelConfig.optString("pi_provider_id"))
            put("model", modelConfig.optString("model_id"))
            put("response_id", kimiSessionId)
            put("stop_reason", stopReason)
            put("session_id", kimiSessionId)
            put("runtime", LocalRuntimeId.Alpine.storageValue)
            put("cwd", cwd)
            KimiAcpProtocol.parseUsage(result)?.let { put("usage", it) }
        }
    }

    fun requestSessionMemoryNewContext() {
        val dir = guestPathToHostFile("$KimiCodeHome/session-memory")
        dir.mkdirs()
        File(dir, "new-context.request").writeText(System.currentTimeMillis().toString())
    }

    suspend fun compactKimiSession(
        aetherSessionId: String,
        modelConfig: JSONObject,
        workspaceDirectory: String,
        instruction: String = "",
    ): JSONObject {
        requestSessionMemoryNewContext()
        val mapped = kimiSessionIdForAetherSession(aetherSessionId)
        if (mapped.isNullOrBlank()) {
            return JSONObject()
                .put("ok", true)
                .put("assistant_text", "AETHER_COMPACT_DONE")
                .put("skipped_prompt", true)
        }
        val prompt = if (instruction.isBlank()) "/compact" else "/compact $instruction"
        val payload = JSONObject()
            .put("session_id", aetherSessionId)
            .put("model_config", modelConfig)
            .put("workspace_directory", workspaceDirectory.ifBlank { workspaceRoot })
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "content",
                            JSONArray().put(
                                JSONObject().put("type", "text").put("text", prompt),
                            ),
                        ),
                ),
            )
        return runKimiTurn(payload)
    }

    fun abortActiveKimiTurn() {
        acpClient.abortActiveTurn()
    }

    private fun shouldSkipEagerKimiSpawn(): Boolean =
        SystemClock.elapsedRealtime() < kimiEagerNotBeforeElapsed.get()

    private fun noteEagerKimiSpawnSuccess() {
        kimiEagerBackoffMs.set(0)
    }

    private fun noteEagerKimiSpawnFailure() {
        val next = when (kimiEagerBackoffMs.get()) {
            0L -> 30_000L
            30_000L -> 60_000L
            else -> 120_000L
        }
        kimiEagerBackoffMs.set(next)
        kimiEagerNotBeforeElapsed.set(SystemClock.elapsedRealtime() + next)
        diagnosticLogger.event(
            category = "kimi_acp",
            event = "eager_spawn_backoff",
            level = "warn",
            details = mapOf("backoff_ms" to next),
        )
    }

    suspend fun prewarmKimiProcess() = withContext(Dispatchers.IO) {
        if (shouldSkipEagerKimiSpawn()) return@withContext
        runCatching {
            kimiTurnGate.runSetup {
                requireReady()
                ensureBundledKimiCode {}
                brandEverMeForDitto()
                acpClient.ensureReady()
            }
        }.onSuccess {
            noteEagerKimiSpawnSuccess()
        }.onFailure { error ->
            if (error is CancellationException) throw error
            noteEagerKimiSpawnFailure()
        }
    }

    suspend fun warmKimiEngine(modelConfig: JSONObject) = withContext(Dispatchers.IO) {
        if (shouldSkipEagerKimiSpawn()) return@withContext
        runCatching {
            kimiTurnGate.runSetup {
                requireReady()
                ensureBundledKimiCode {}
                brandEverMeForDitto()
                val providerSetup = configureKimiProvider(modelConfig)
                if (providerSetup.requiresProcessRestart) {
                    // Never kill a process with an active prompt; the next
                    // turn applies the new config during its own setup.
                    if (acpClient.hasActivePrompt()) {
                        pendingKimiConfigRestart = true
                        return@runSetup
                    }
                    lastSessionUiConfig = null
                    acpClient.invalidate()
                }
                acpClient.ensureReady()
            }
        }.onSuccess {
            noteEagerKimiSpawnSuccess()
        }.onFailure { error ->
            if (error is CancellationException) throw error
            noteEagerKimiSpawnFailure()
        }
    }

    suspend fun prewarmKimiSession(
        aetherSessionId: String,
        cwd: String,
        modelConfig: JSONObject,
        mcpServers: JSONArray = JSONArray(),
        thinking: String = "",
        permissionMode: String = kimiPermissionMode,
    ) = withContext(Dispatchers.IO) {
        if (shouldSkipEagerKimiSpawn()) return@withContext
        val epoch = kimiPrewarmEpoch.incrementAndGet()
        runCatching {
            kimiTurnGate.runSetup {
                requireReady()
                ensureBundledKimiCode {}
                brandEverMeForDitto()
                val providerSetup = configureKimiProvider(modelConfig)
                if (providerSetup.requiresProcessRestart) {
                    if (acpClient.hasActivePrompt()) {
                        pendingKimiConfigRestart = true
                        return@runSetup
                    }
                    lastSessionUiConfig = null
                    acpClient.invalidate()
                }
                acpClient.ensureReady()
                resolveOrCreateKimiSession(
                    aetherSessionId = aetherSessionId,
                    cwd = cwd.ifBlank { workspaceRoot },
                    modelAlias = providerSetup.modelAlias,
                    thinking = mapThinkingConfigValue(
                        raw = thinking,
                        modelConfig = modelConfig,
                    ),
                    mcpServers = mcpServers,
                    permissionMode = permissionMode.ifBlank { kimiPermissionMode },
                    expectedPrewarmEpoch = epoch,
                )
            }
        }.onSuccess {
            noteEagerKimiSpawnSuccess()
        }.onFailure { error ->
            if (error is CancellationException) throw error
            noteEagerKimiSpawnFailure()
        }
    }

    internal fun kimiAcpLaunchCommand(): String =
        kimiAcpLaunchCommandText(kimiNodeMaxOldSpaceMb)

    private data class KimiProviderSetup(
        val modelAlias: String,
        val requiresProcessRestart: Boolean,
    )

    private suspend fun configureKimiProvider(modelConfig: JSONObject): KimiProviderSetup = kimiProviderMutex.withLock {
        val providerId = "aether-" + modelConfig.optString("provider_config_id")
            .ifBlank { modelConfig.optString("pi_provider_id") }
            .lowercase()
            .replace(Regex("[^a-z0-9_-]+"), "-")
            .trim('-')
            .take(52)
            .ifBlank { "provider" }
        val modelId = modelConfig.optString("model_id").trim()
        check(modelId.isNotBlank()) { "Select a model before sending a message." }
        val configuredBaseUrl = modelConfig.optString("base_url").trim().trimEnd('/')
        val api = modelConfig.optString("pi_api")
        val effectiveBaseUrl = when {
            modelConfig.optString("pi_provider_id") == "stepfun" &&
                stepfunUsesStepPlan(modelId) &&
                (configuredBaseUrl.contains("api.stepfun.com", ignoreCase = true) ||
                    configuredBaseUrl.contains("api.stepfun.ai", ignoreCase = true)) -> {
                val root = if (configuredBaseUrl.contains("api.stepfun.ai", ignoreCase = true)) {
                    "https://api.stepfun.ai"
                } else {
                    "https://api.stepfun.com"
                }
                if (api == "anthropic-messages") "$root/step_plan" else "$root/step_plan/v1"
            }
            else -> configuredBaseUrl
        }
        val providerType = when {
            api == "anthropic-messages" -> "anthropic"
            api == "openai-responses" -> "openai_responses"
            api == "google-generative-ai" -> "google-genai"
            modelConfig.optString("pi_provider_id") == "kimi-code" -> "kimi"
            else -> "openai"
        }
        val apiKey = modelConfig.optString("api_key").trim()
        check(apiKey.isNotBlank()) {
            "Provider API key is missing. Add a key in Settings before sending a message."
        }
        val extraHeaders = linkedMapOf<String, String>()
        modelConfig.optJSONObject("custom_headers")?.let { headers ->
            headers.keys().forEach { name ->
                val value = headers.optString(name).trim()
                if (name.isNotBlank() && value.isNotBlank()) extraHeaders[name] = value
            }
        }
        if (modelConfig.optString("pi_provider_id") == "dots" && apiKey.isNotBlank()) {
            extraHeaders.putIfAbsent("api-key", apiKey)
        }
        val phoneProfileChanged = writeKimiPhoneAgentProfile()
        val browserProfileChanged = writeKimiBrowserAgentProfile()
        val imageProfileChanged = writeKimiImageAgentProfile()
        val maxTokens = modelConfig.optInt("max_tokens", 16_384).coerceAtLeast(1)
        val processFingerprint = listOf(
            providerId,
            providerType,
            effectiveBaseUrl,
            apiKey.sha256Fingerprint(),
            extraHeaders.entries.sortedBy { it.key }
                .joinToString(",") { "${it.key}:${it.value.sha256Fingerprint()}" },
            "websearch-gecko-v1",
            "step-plan-flash-v1",
            "native-phone-agent-v1",
            "native-image-agent-v1",
        ).joinToString("|")
        val fingerprint = "$processFingerprint|$modelId|$maxTokens"
        val modelAlias = "$providerId/$modelId"
        if (kimiProviderFingerprint == fingerprint) {
            return@withLock KimiProviderSetup(
                modelAlias,
                requiresProcessRestart = phoneProfileChanged ||
                    browserProfileChanged ||
                    imageProfileChanged,
            )
        }
        writeKimiProviderConfig(
            providerId = providerId,
            providerType = providerType,
            modelId = modelId,
            modelAlias = modelAlias,
            apiKey = apiKey,
            baseUrl = effectiveBaseUrl,
            contextWindow = modelConfig.optInt("context_window", 128_000).coerceAtLeast(1),
            maxTokens = maxTokens,
            extraHeaders = extraHeaders,
        )
        val requiresProcessRestart = kimiProcessFingerprint != processFingerprint ||
            phoneProfileChanged ||
            browserProfileChanged ||
            imageProfileChanged
        kimiProcessFingerprint = processFingerprint
        kimiProviderFingerprint = fingerprint
        KimiProviderSetup(modelAlias, requiresProcessRestart = requiresProcessRestart)
    }

    private fun writeKimiProviderConfig(
        providerId: String,
        providerType: String,
        modelId: String,
        modelAlias: String,
        apiKey: String,
        baseUrl: String,
        contextWindow: Int,
        maxTokens: Int,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val configFile = guestPathToHostFile("$KimiCodeHome/config.toml")
        configFile.parentFile?.mkdirs()
        ensureUpaMcpGateway()
        // kimi-code 0.38 rejects duplicate TOML tables as an empty provider set,
        // then ACP session/new returns "Authentication required". Own the whole file.
        configFile.writeText(
            kimiManagedConfigToml(
                modelAlias = modelAlias,
                permissionMode = kimiPermissionMode,
                providerId = providerId,
                providerType = providerType,
                apiKey = apiKey,
                baseUrl = baseUrl,
                modelId = modelId,
                contextWindow = contextWindow,
                maxTokens = maxTokens,
                extraHeaders = extraHeaders,
            ),
        )
    }

    private fun writeKimiPhoneAgentProfile(): Boolean {
        val profileFile = guestPathToHostFile(
            "$KimiCodeHome/agents/$KimiPhoneSubagentProfileName.md",
        )
        profileFile.parentFile?.mkdirs()
        if (profileFile.isFile && profileFile.readText() == KimiPhoneSubagentProfileMarkdown) {
            return false
        }
        profileFile.writeText(KimiPhoneSubagentProfileMarkdown)
        return true
    }

    private fun writeKimiBrowserAgentProfile(): Boolean {
        val profileFile = guestPathToHostFile(
            "$KimiCodeHome/agents/$KimiBrowserSubagentProfileName.md",
        )
        profileFile.parentFile?.mkdirs()
        if (profileFile.isFile && profileFile.readText() == KimiBrowserSubagentProfileMarkdown) {
            return false
        }
        profileFile.writeText(KimiBrowserSubagentProfileMarkdown)
        return true
    }

    private fun writeKimiImageAgentProfile(): Boolean {
        val profileFile = guestPathToHostFile(
            "$KimiCodeHome/agents/$KimiImageSubagentProfileName.md",
        )
        profileFile.parentFile?.mkdirs()
        if (profileFile.isFile && profileFile.readText() == KimiImageSubagentProfileMarkdown) {
            return false
        }
        profileFile.writeText(KimiImageSubagentProfileMarkdown)
        return true
    }

    private fun writeKimiAgentsMd(systemPrompt: String) {
        val file = guestPathToHostFile("$KimiCodeHome/AGENTS.md")
        val contents = kimiAgentsMdContents(systemPrompt)
        if (contents == null) {
            if (file.exists()) file.delete()
            lastWrittenAgentsMd = ""
            return
        }
        if (contents == lastWrittenAgentsMd && file.isFile) return
        file.parentFile?.mkdirs()
        file.writeText(contents)
        lastWrittenAgentsMd = contents
    }

    private fun String.sha256Fingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private suspend fun resolveOrCreateKimiSession(
        aetherSessionId: String,
        cwd: String,
        modelAlias: String,
        thinking: String,
        mcpServers: JSONArray = JSONArray(),
        permissionMode: String = kimiPermissionMode,
        expectedPrewarmEpoch: Int? = null,
    ): KimiSessionResolution = kimiSessionMutex.withLock {
        if (expectedPrewarmEpoch != null && expectedPrewarmEpoch != kimiPrewarmEpoch.get()) {
            throw CancellationException("stale kimi workspace prewarm")
        }
        ensureUpaMcpGateway()
        writeKimiMcpJson(mcpServers)
        val fingerprint = "upa-gps-v1|${kimiMcpGeneration.get()}|${acpMcpServersFingerprint(mcpServers)}"
        val mapped = readKimiSessionMap()
        val previousId = mapped.optString(aetherSessionId)
        val previousFingerprint = mapped.optString("$aetherSessionId::mcp")
        val needsNewSession = acpClient.sessionNeedsRecreate
        val canReuse = previousId.isNotBlank() && previousFingerprint == fingerprint
        val restoreKind = kimiSessionRestoreKind(
            canReuse = canReuse,
            needsNewSession = needsNewSession,
            isBound = acpClient.isBoundSession(previousId),
        )
        var createdNew = false
        val sessionId = when (restoreKind) {
            KimiSessionRestoreKind.BoundReuse -> {
                Log.i("AetherAgentMode", "hop=kimi_session_reuse bound=$previousId")
                previousId
            }
            KimiSessionRestoreKind.LoadOrResume -> {
                val restored = restoreMappedKimiSession(previousId, cwd, mcpServers)
                if (restored) {
                    Log.i("AetherAgentMode", "hop=kimi_session_restore id=$previousId")
                    previousId
                } else {
                    createdNew = true
                    Log.i("AetherAgentMode", "hop=kimi_session_restore_failed id=$previousId")
                    acpClient.createSession(cwd, mcpServers).optString("sessionId")
                }
            }
            KimiSessionRestoreKind.New -> {
                createdNew = true
                Log.i("AetherAgentMode", "hop=kimi_session_new aether=$aetherSessionId")
                acpClient.createSession(cwd, mcpServers).optString("sessionId")
            }
        }
        if (expectedPrewarmEpoch != null && expectedPrewarmEpoch != kimiPrewarmEpoch.get()) {
            throw CancellationException("stale kimi workspace prewarm")
        }
        acpClient.markSessionRecreated()
        check(sessionId.isNotBlank()) { "Kimi Code did not create a session." }
        val resolvedPermission = permissionMode.trim().lowercase()
            .takeIf { it in KimiPermissionModes }
            ?: DefaultKimiPermissionMode
        val uiConfig = "$sessionId|$modelAlias|$thinking|$resolvedPermission"
        if (lastSessionUiConfig != uiConfig) {
            runCatching {
                acpClient.setConfigOption(sessionId, "mode", resolvedPermission)
            }
            runCatching { acpClient.setConfigOption(sessionId, "model", modelAlias) }
            thinking.trim().takeIf(String::isNotBlank)?.let { value ->
                runCatching { acpClient.setConfigOption(sessionId, "thinking", value) }
            }
            lastSessionUiConfig = uiConfig
        }
        persistKimiSessionBinding(aetherSessionId, sessionId, fingerprint)
        KimiSessionResolution(sessionId = sessionId, createdNew = createdNew)
    }

    private suspend fun restoreMappedKimiSession(
        sessionId: String,
        cwd: String,
        mcpServers: JSONArray,
    ): Boolean {
        try {
            acpClient.loadSession(
                sessionId = sessionId,
                cwd = cwd,
                mcpServers = mcpServers,
                onEvent = { _, _ -> },
            )
            acpClient.discardIdleUpdates(sessionId)
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            diagnosticLogger.event(
                category = "kimi_acp",
                event = "session_load_failed",
                level = "warn",
                details = mapOf(
                    "session" to sessionId,
                    "message" to (error.message ?: error.javaClass.simpleName),
                ),
            )
        }
        try {
            acpClient.resumeSession(sessionId, cwd, mcpServers)
            acpClient.discardIdleUpdates(sessionId)
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            diagnosticLogger.event(
                category = "kimi_acp",
                event = "session_resume_failed",
                level = "warn",
                details = mapOf(
                    "session" to sessionId,
                    "message" to (error.message ?: error.javaClass.simpleName),
                ),
            )
        }
        return false
    }

    private fun mapThinkingConfigValue(raw: String, modelConfig: JSONObject): String {
        val key = raw.trim()
        if (key.isBlank()) return key
        val map = modelConfig.optJSONObject("thinking_level_map") ?: return key
        return map.optString(key)
            .ifBlank { map.optString(key.lowercase()) }
            .ifBlank { key }
    }

    private fun persistKimiSessionBinding(
        aetherSessionId: String,
        kimiSessionId: String,
        fingerprint: String? = null,
    ) {
        if (aetherSessionId.isBlank() || kimiSessionId.isBlank()) return
        val previousKimi = kimiSessionIdForAetherSession(aetherSessionId)
        if (previousKimi != null && previousKimi != kimiSessionId) {
            kimiToAetherSessionIds.remove(previousKimi, aetherSessionId)
        }
        kimiToAetherSessionIds[kimiSessionId] = aetherSessionId
        synchronized(kimiSessionMapLock) {
            val map = readKimiSessionMap()
            val sameId = map.optString(aetherSessionId) == kimiSessionId
            val sameFingerprint = fingerprint == null ||
                map.optString("$aetherSessionId::mcp") == fingerprint
            if (sameId && sameFingerprint) return
            map.put(aetherSessionId, kimiSessionId)
            fingerprint?.let { map.put("$aetherSessionId::mcp", it) }
            writeKimiSessionMap(map)
        }
    }

    private fun removeKimiSessionBinding(aetherSessionId: String) {
        if (aetherSessionId.isBlank()) return
        val kimiId = kimiSessionIdForAetherSession(aetherSessionId)
        if (kimiId != null) {
            kimiToAetherSessionIds.remove(kimiId, aetherSessionId)
        }
        synchronized(kimiSessionMapLock) {
            val map = readKimiSessionMap()
            map.remove(aetherSessionId)
            map.remove("$aetherSessionId::mcp")
            writeKimiSessionMap(map)
        }
    }

    private fun writeKimiSessionMap(map: JSONObject) {
        kimiSessionMapCache = JSONObject(map.toString())
        val file = guestPathToHostFile("$KimiCodeHome/aether-session-map.json")
        file.parentFile?.mkdirs()
        file.writeText(map.toString())
    }

    private fun readKimiSessionMap(): JSONObject = synchronized(kimiSessionMapLock) {
        kimiSessionMapCache?.let { return JSONObject(it.toString()) }
        val raw = guestPathToHostFile("$KimiCodeHome/aether-session-map.json").readTextSafe()
        val loaded = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        kimiSessionMapCache = JSONObject(loaded.toString())
        loaded
    }

    private fun writeKimiMcpJson(mcpServers: JSONArray) {
        val servers = JSONObject()
        for (index in 0 until mcpServers.length()) {
            val item = mcpServers.optJSONObject(index) ?: continue
            val name = item.optString("name").trim()
            if (name.isBlank()) continue
            val entry = JSONObject()
            val url = item.optString("url").trim()
            val command = item.optString("command").trim()
            when {
                url.isNotBlank() -> entry.put("url", url)
                command.isNotBlank() -> {
                    entry.put("command", command)
                    item.optJSONArray("args")?.let { entry.put("args", it) }
                }
                else -> continue
            }
            val toolTimeoutMs = item.optLong("toolTimeoutMs")
            if (toolTimeoutMs > 0) {
                entry.put("toolTimeoutMs", toolTimeoutMs)
            }
            servers.put(name, entry)
        }
        if (!servers.has(SessionMemoryMcp.ServerName)) {
            servers.put(
                SessionMemoryMcp.ServerName,
                JSONObject()
                    .put("command", "node")
                    .put("args", JSONArray().put(SessionMemoryMcp.McpGuestPath)),
            )
        }
        val contents = JSONObject().put("mcpServers", servers).toString(2)
        if (contents == lastWrittenMcpJson) return
        val file = guestPathToHostFile("$KimiCodeHome/mcp.json")
        file.parentFile?.mkdirs()
        file.writeText(contents)
        lastWrittenMcpJson = contents
    }

    private fun everCliData(commandResult: String): JSONObject {
        val run = JSONObject(commandResult)
        check(run.optBoolean("ok")) {
            run.optString("stderr").ifBlank { run.optString("stdout") }.ifBlank {
                "evercli command failed."
            }
        }
        val output = run.optString("stdout").trim()
        check(output.isNotBlank()) { "evercli returned no response." }
        val envelope = JSONObject(output)
        check(envelope.optBoolean("ok")) {
            envelope.optJSONObject("error")?.optString("message")
                ?.takeIf(String::isNotBlank)
                ?: envelope.optString("message").ifBlank { "evercli request failed." }
        }
        return envelope.optJSONObject("data") ?: JSONObject()
    }

    private suspend fun ensureEverMeDittoPluginStaged() {
        ensureBundledKimiCode {}
        stageOfficialEverMeBundle()
        brandEverMeForDitto()

        val environmentFile = guestPathToHostFile(EverMeEnvironmentFile)
        val currentEnvironment = environmentFile.readTextSafe()
        val apiKey = everMeApiKey()
        if (!currentEnvironment.isDittoEverMeEnvironment(apiKey)) {
            val legacyAgentId = currentEnvironment.everMeEnvironmentValue("EVERME_AGENT_ID")
                .takeIf { it.startsWith("agt_") }
            val registration = registerEverMeDittoAgent(apiKey)
            writeEverMeDittoEnvironment(
                apiBase = registration.apiBase,
                agentId = registration.agentId,
                agentToken = registration.agentToken,
                accountFingerprint = everMeAccountFingerprint(apiKey),
            )
            if (legacyAgentId != null && legacyAgentId != registration.agentId) {
                val pending = guestPathToHostFile(EverMePendingDisconnectFile)
                require(pending.parentFile?.mkdirs() != false || pending.parentFile?.isDirectory == true) {
                    "Unable to prepare the EverMe migration state."
                }
                pending.writeText("$legacyAgentId\n")
            }
        }
        disconnectPendingLegacyEverMeAgent()
    }

    private fun stageOfficialEverMeBundle() {
        val source = guestPathToHostFile(EverMeBundleRoot)
        check(source.isDirectory && File(source, "kimi.plugin.json").isFile) {
            "The bundled EverMe plugin is unavailable."
        }
        val target = guestPathToHostFile(EverMeStagedRoot)
        val marker = File(target, EverMeDittoStageMarker)
        if (File(target, "kimi.plugin.json").isFile && marker.isFile) return

        val parent = target.parentFile ?: error("Unable to resolve the EverMe plugin directory.")
        require(parent.mkdirs() || parent.isDirectory) { "Unable to prepare the EverMe plugin directory." }
        val staging = File(parent, ".everme-ditto-staging-${UUID.randomUUID()}")
        val backup = File(parent, ".everme-ditto-backup-${UUID.randomUUID()}")
        try {
            copyDirectory(source, staging)
            File(staging, EverMeDittoStageMarker).writeText("$EverMeVersion\n")
            if (target.exists()) {
                require(target.renameTo(backup)) { "Unable to stage the existing EverMe plugin." }
            }
            if (!staging.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target)
                error("Unable to activate the bundled EverMe plugin.")
            }
            backup.deleteRecursively()
        } finally {
            staging.deleteRecursively()
            if (backup.exists() && !target.exists()) backup.renameTo(target)
            if (target.exists()) backup.deleteRecursively()
        }
    }

    private fun registerEverMeDittoAgent(apiKey: String): EverMeAgentRegistration {
        val apiBase = everMeApiBase()
        val connection = (URL("${everMeApiV1Base(apiBase)}/agents").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("User-Agent", "ditto/android")
        }
        try {
            val request = JSONObject()
                .put("platform", EverMeCloudPlatform)
                .put("name", EverMeDittoPlatform)
                .put("clientVersion", "ditto/android")
                .put("machineFingerprint", everMeDittoMachineFingerprint())
            connection.outputStream.use { output -> output.write(request.toString().toByteArray()) }
            val body = connection.responseBody()
            val envelope = runCatching { JSONObject(body) }.getOrNull()
            check(connection.responseCode in 200..299 && envelope != null) {
                "EverMe could not register ditto (HTTP ${connection.responseCode})."
            }
            check(envelope.optInt("status", -1) == 0) {
                envelope.optString("error").ifBlank { "EverMe rejected the ditto agent." }
            }
            val result = envelope.optJSONObject("result") ?: JSONObject()
            val agentId = result.optString("agentId").trim()
            val agentToken = result.optString("agentToken").trim()
            check(agentId.startsWith("agt_") && agentToken.startsWith("evt_")) {
                "EverMe returned incomplete ditto credentials."
            }
            return EverMeAgentRegistration(apiBase, agentId, agentToken)
        } finally {
            connection.disconnect()
        }
    }

    private fun disconnectPendingLegacyEverMeAgent() {
        val pending = guestPathToHostFile(EverMePendingDisconnectFile)
        if (!pending.isFile) return
        val agentId = pending.readTextSafe().trim()
        if (!agentId.startsWith("agt_")) {
            pending.delete()
            return
        }
        disconnectEverMeAgent(agentId, everMeApiKey())
        check(pending.delete() || !pending.exists()) { "Unable to finish the EverMe identity migration." }
    }

    private fun disconnectCurrentEverMeAgent() {
        val agentId = guestPathToHostFile(EverMeEnvironmentFile)
            .readTextSafe()
            .everMeEnvironmentValue("EVERME_AGENT_ID")
        if (!agentId.startsWith("agt_")) return
        disconnectEverMeAgent(agentId, everMeApiKey())
    }

    private fun disconnectEverMeAgent(agentId: String, apiKey: String) {
        val connection = (URL("${everMeApiV1Base(everMeApiBase())}/agents/disconnect").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("User-Agent", "ditto/android")
        }
        try {
            connection.outputStream.use { output ->
                output.write(JSONObject().put("agentId", agentId).toString().toByteArray())
            }
            val envelope = runCatching { JSONObject(connection.responseBody()) }.getOrNull()
            check(connection.responseCode in 200..299 && envelope?.optInt("status", -1) == 0) {
                "EverMe connected ditto, but could not disconnect the old Kimi agent."
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun writeEverMeDittoEnvironment(
        apiBase: String,
        agentId: String,
        agentToken: String,
        accountFingerprint: String,
    ) {
        val values = listOf(apiBase, agentId, agentToken, accountFingerprint)
        check(values.none { value -> value.any { it == '\r' || it == '\n' || it == '\u0000' } }) {
            "EverMe returned an invalid ditto credential."
        }
        val target = guestPathToHostFile(EverMeEnvironmentFile)
        val parent = target.parentFile ?: error("Unable to resolve the EverMe environment directory.")
        require(parent.mkdirs() || parent.isDirectory) { "Unable to prepare the EverMe environment directory." }
        val temporary = File(parent, ".everme.env.${UUID.randomUUID()}.tmp")
        try {
            temporary.writeText(
                "# Managed by ditto for the official EverMe plugin.\n" +
                    "EVERME_API_BASE=$apiBase\n" +
                    "EVERME_AGENT_ID=$agentId\n" +
                    "EVERME_AGENT_TOKEN=$agentToken\n" +
                    "EVERME_AGENT_PLATFORM=$EverMeDittoPlatform\n" +
                    "EVERME_ACCOUNT_FINGERPRINT=$accountFingerprint\n"
            )
            temporary.setReadable(false, false)
            temporary.setWritable(false, false)
            temporary.setReadable(true, true)
            temporary.setWritable(true, true)
            runCatching {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            persistEverMeLoginState()
        } finally {
            temporary.delete()
        }
    }

    private fun everMeApiBase(): String {
        val configured = guestPathToHostFile(EverMeConfigFile).readTextSafe()
            .lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("api_base_url:") }
            ?.substringAfter(':')
            ?.trim()
            ?.trim('"', '\'')
            ?.takeIf(String::isNotBlank)
            ?: EverMeDefaultApiBase
        return configured.trimEnd('/')
    }

    private fun everMeApiV1Base(apiBase: String): String {
        val normalized = apiBase.trim().trimEnd('/')
        val url = URL(normalized)
        check(url.protocol == "https" || url.protocol == "http") { "EverMe API URL must use HTTP or HTTPS." }
        check(url.host.isNotBlank()) { "EverMe API URL is invalid." }
        return if (Regex("/api/v\\d+$").containsMatchIn(normalized)) normalized else "$normalized/api/v1"
    }

    private fun everMeDittoMachineFingerprint(): String {
        val machineId = sequenceOf("/etc/machine-id", "/var/lib/dbus/machine-id")
            .map { guestPathToHostFile(it).readTextSafe().trim() }
            .firstOrNull(String::isNotBlank)
            ?: Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "ditto-android"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$machineId\u0000root\u0000$EverMeDittoPlatform".toByteArray())
        return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun everMeApiKey(): String {
        val apiKey = runCatching {
            JSONObject(guestPathToHostFile(EverMeCredentialsFile).readTextSafe()).optString("api-key").trim()
        }.getOrDefault("")
        check(apiKey.startsWith("emk_") && apiKey.none { it == '\r' || it == '\n' || it == '\u0000' }) {
            "Log in to EverMe before connecting ditto."
        }
        return apiKey
    }

    private fun everMeAccountFingerprint(apiKey: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(apiKey.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun String.isDittoEverMeEnvironment(apiKey: String): Boolean =
        everMeEnvironmentValue("EVERME_AGENT_PLATFORM") == EverMeDittoPlatform &&
            everMeEnvironmentValue("EVERME_ACCOUNT_FINGERPRINT") == everMeAccountFingerprint(apiKey) &&
            everMeEnvironmentValue("EVERME_AGENT_ID").startsWith("agt_") &&
            everMeEnvironmentValue("EVERME_AGENT_TOKEN").startsWith("evt_")

    private fun String.everMeEnvironmentValue(key: String): String =
        lineSequence()
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')
            ?.trim()
            .orEmpty()

    private suspend fun readEverCliAccount(): JSONObject? = runCatching {
        val data = everCliData(
            executeCommand(
                command = "HOME=/root evercli auth status --no-prompt --format json",
                workingDirectory = homeDirectory,
                awaitTimeoutMillis = 60_000L,
            )
        )
        if (data.optString("accountId").isBlank() && data.optString("email").isBlank()) null else data
    }.getOrNull()

    private fun everMeHostLoginDir(): File = File(appContext.filesDir, "everme-login")

    private fun hasPersistedEverMeLogin(): Boolean {
        val credentials = File(everMeHostLoginDir(), "credentials.json")
        val apiKey = runCatching {
            JSONObject(credentials.readTextSafe()).optString("api-key").trim()
        }.getOrDefault("")
        return apiKey.startsWith("emk_")
    }

    private fun peekPersistedEverMeEmail(): String =
        runCatching {
            JSONObject(File(everMeHostLoginDir(), "account.json").readTextSafe()).optString("email").trim()
        }.getOrDefault("")

    private fun persistEverMeLoginState(email: String = peekPersistedEverMeEmail()) {
        val hostDir = everMeHostLoginDir()
        hostDir.mkdirs()
        listOf(
            EverMeCredentialsFile to "credentials.json",
            EverMeConfigFile to "config.yaml",
            EverMeEnvironmentFile to "everme.env",
        ).forEach { (guestPath, hostName) ->
            val source = guestPathToHostFile(guestPath)
            if (source.isFile && source.length() > 0L) {
                copyFileDurable(source, File(hostDir, hostName))
            }
        }
        writeTextDurable(
            File(hostDir, "account.json"),
            JSONObject().put("email", email).put("bound", hasPersistedEverMeLogin()).toString(),
        )
    }

    private fun restoreEverMeLoginState() {
        val hostDir = everMeHostLoginDir()
        if (!hostDir.isDirectory) return
        listOf(
            "credentials.json" to EverMeCredentialsFile,
            "config.yaml" to EverMeConfigFile,
            "everme.env" to EverMeEnvironmentFile,
        ).forEach { (hostName, guestPath) ->
            val source = File(hostDir, hostName)
            if (!source.isFile || source.length() <= 0L) return@forEach
            val target = guestPathToHostFile(guestPath)
            if (!target.isFile || target.length() <= 0L || source.lastModified() >= target.lastModified()) {
                copyFileDurable(source, target)
            }
        }
    }

    private fun clearPersistedEverMeLoginState() {
        everMeHostLoginDir().deleteRecursively()
    }

    private fun copyFileDurable(source: File, target: File) {
        val parent = target.parentFile ?: return
        require(parent.mkdirs() || parent.isDirectory) { "Unable to prepare ${parent.path}" }
        val temporary = File(parent, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output)
                    output.flush()
                    output.fd.sync()
                }
            }
            runCatching {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun writeTextDurable(target: File, text: String) {
        val parent = target.parentFile ?: return
        require(parent.mkdirs() || parent.isDirectory)
        val temporary = File(parent, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            runCatching {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private data class EverMeAgentRegistration(
        val apiBase: String,
        val agentId: String,
        val agentToken: String,
    )

    private suspend fun installStagedEverMePlugin() {
        brandEverMeForDitto()
        val manifestFile = guestPathToHostFile("$EverMeStagedRoot/kimi.plugin.json")
        check(manifestFile.isFile) { "The bundled EverMe plugin is unavailable." }
        val pluginId = runCatching {
            JSONObject(manifestFile.readText()).optString("name")
        }.getOrNull()?.ifBlank { null } ?: "everme"
        val managedRoot = "$KimiCodeHome/plugins/managed/$pluginId"
        val source = guestPathToHostFile(EverMeStagedRoot)
        val target = guestPathToHostFile(managedRoot)
        if (target.exists()) target.deleteRecursively()
        target.parentFile?.mkdirs()
        source.copyRecursively(target, overwrite = true)
        val installedFile = guestPathToHostFile("$KimiCodeHome/plugins/installed.json")
        installedFile.parentFile?.mkdirs()
        val installed = runCatching { JSONObject(installedFile.readTextSafe()) }
            .getOrDefault(JSONObject().put("version", 1).put("plugins", JSONArray()))
        if (!installed.has("plugins")) installed.put("plugins", JSONArray())
        val plugins = installed.getJSONArray("plugins")
        val now = Instant.now().toString()
        var found = false
        for (index in 0 until plugins.length()) {
            val entry = plugins.optJSONObject(index) ?: continue
            if (entry.optString("id") != pluginId) continue
            entry.put("root", managedRoot)
            entry.put("source", "local-path")
            entry.put("enabled", true)
            entry.put("updatedAt", now)
            entry.put("originalSource", EverMeStagedRoot)
            found = true
            break
        }
        if (!found) {
            plugins.put(
                JSONObject()
                    .put("id", pluginId)
                    .put("root", managedRoot)
                    .put("source", "local-path")
                    .put("enabled", true)
                    .put("installedAt", now)
                    .put("updatedAt", now)
                    .put("originalSource", EverMeStagedRoot),
            )
        }
        installedFile.writeText(installed.toString(2))
    }

    private fun brandEverMeForDitto() {
        if (everMeBranded) return
        val manifestFile = guestPathToHostFile("$EverMeStagedRoot/kimi.plugin.json")
        if (!manifestFile.isFile) return
        val manifest = runCatching { JSONObject(manifestFile.readText()) }.getOrNull() ?: return
        var changed = false
        val description = manifest.optString("description")
        val brandedDescription = description.replace("Kimi Code", "ditto").replace("Kimi", "ditto")
        if (description != brandedDescription) {
            manifest.put("description", brandedDescription)
            changed = true
        }
        val sessionStart = manifest.optJSONObject("sessionStart")
        if (sessionStart == null) {
            manifest.put("sessionStart", JSONObject().put("skill", "memory-recall"))
            changed = true
        }
        val skillsRoot = guestPathToHostFile("$EverMeStagedRoot/skills")
        if (skillsRoot.isDirectory) {
            skillsRoot.walkTopDown()
                .filter { it.isFile && it.extension.equals("md", ignoreCase = true) }
                .forEach { skillFile ->
                    val original = skillFile.readTextSafe()
                    val branded = original.replace("Kimi Code", "ditto").replace("Kimi", "ditto")
                    if (branded != original) skillFile.writeText(branded)
                }
        }
        val adapterFile = guestPathToHostFile("$EverMeStagedRoot/hooks/scripts/lib/adapter.js")
        if (adapterFile.isFile) {
            val original = adapterFile.readTextSafe()
            val branded = original
                .replace("platform: \"kimi-code\"", "platform: \"ditto\"")
                .replace("\"kimi-code-session\"", "\"ditto-session\"")
            if (branded != original) adapterFile.writeText(branded)
        }
        if (changed) manifestFile.writeText(manifest.toString(2))
        everMeBranded = true
    }

    private fun HttpURLConnection.responseBody(): String {
        val stream = if (responseCode in 200..299) inputStream else errorStream
        return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    private suspend fun requireReady() {
        if (alpineReadyCached) return
        val setup = inspectSetup()
        if (!setup.isReady) {
            alpineReadyCached = false
            error(setup.detail.ifBlank { "Alpine runtime is not ready." })
        }
        alpineReadyCached = true
    }

    suspend fun installPackageProfile(
        profileId: String,
        onProgress: (AlpineSetupProgress) -> Unit = {},
    ): LocalRuntimeSetupState {
        packageInstallMutex.lock()
        return try {
            installPackageProfileLocked(profileId, onProgress)
        } finally {
            packageInstallMutex.unlock()
        }
    }

    private suspend fun installPackageProfileLocked(
        profileId: String,
        onProgress: (AlpineSetupProgress) -> Unit,
    ): LocalRuntimeSetupState {
        if (profileId !in AlpinePackageProfiles) {
            return LocalRuntimeSetupState(
                runtimeId = id,
                issue = LocalRuntimeIssue.Failed,
                detail = "Unknown Alpine package profile: $profileId",
            )
        }
        val setup = inspectSetup()
        if (!setup.isReady) return setup
        refreshApkRepositoriesForCurrentNetwork(onProgress)
        if (verifyPackageProfile(profileId)) {
            return LocalRuntimeSetupState(
                runtimeId = id,
                issue = LocalRuntimeIssue.Ready,
                detail = "Alpine profile $profileId is already installed.",
            )
        }
        val command = packageProfileInstallCommand(profileId)
            ?: return LocalRuntimeSetupState(
                runtimeId = id,
                issue = LocalRuntimeIssue.Failed,
                detail = "Unknown Alpine package profile: $profileId",
            )
        val progressTracker = AlpinePackageInstallProgressTracker()
        onProgress(progressTracker.onOutput("\$ $command\n"))
        val rateSampler = NetworkRateSampler { bytesPerSecond ->
            onProgress(progressTracker.onRate(bytesPerSecond))
        }
        rateSampler.start()
        val result = try {
            JSONObject(
                executeCommand(
                    command = command,
                    workingDirectory = homeDirectory,
                    awaitTimeoutMillis = 10 * 60 * 1000L,
                    onOutput = { output ->
                        onProgress(progressTracker.onOutput(output))
                    },
                )
            )
        } finally {
            rateSampler.stop()
        }
        return if (result.optBoolean("ok") || verifyPackageProfile(profileId)) {
            LocalRuntimeSetupState(
                runtimeId = id,
                issue = LocalRuntimeIssue.Ready,
                detail = "Installed Alpine profile $profileId.",
            )
        } else {
            LocalRuntimeSetupState(
                runtimeId = id,
                issue = LocalRuntimeIssue.Failed,
                detail = result.optString("errmsg").ifBlank { result.optString("stderr") },
            )
        }
    }

    fun packageProfileInstallCommand(profileId: String): String? {
        val packages = AlpinePackageProfiles[profileId] ?: return null
        return "apk add --no-cache --no-chown ${packages.joinToString(" ")}"
    }

    suspend fun isPackageProfileInstalled(profileId: String): Boolean =
        verifyPackageProfile(profileId)

    override suspend fun inspectSetup(): LocalRuntimeSetupState = withContext(Dispatchers.IO) {
        when {
            !isSupportedAbi() -> unsupportedAbiState()
            !hasBundledRuntimeAssets() -> LocalRuntimeSetupState(
                runtimeId = id,
                issue = LocalRuntimeIssue.MissingAssets,
                detail = "This build does not include Alpine proot/rootfs assets.",
            )
            !rootfsDir.isDirectory -> LocalRuntimeSetupState(
                runtimeId = id,
                issue = LocalRuntimeIssue.NotInstalled,
                detail = "Alpine rootfs is not installed.",
            )
            !prootFile.isFile || !loaderFile.isFile || !libTallocFile.isFile -> LocalRuntimeSetupState(
                runtimeId = id,
                issue = LocalRuntimeIssue.Failed,
                detail = "Alpine host runtime is incomplete.",
            )
            !File(AlpineHostLinker).isFile -> LocalRuntimeSetupState(
                runtimeId = id,
                issue = LocalRuntimeIssue.Failed,
                detail = "Android dynamic linker is unavailable: $AlpineHostLinker",
            )
            !File(rootfsDir, "bin/sh").existsNoFollow() -> LocalRuntimeSetupState(
                runtimeId = id,
                issue = LocalRuntimeIssue.Failed,
                detail = "Alpine rootfs is incomplete: /bin/sh is missing.",
            )
            !File(rootfsDir, "etc/alpine-release").existsNoFollow() -> LocalRuntimeSetupState(
                runtimeId = id,
                issue = LocalRuntimeIssue.Failed,
                detail = "Alpine rootfs is incomplete: /etc/alpine-release is missing.",
            )
            else -> LocalRuntimeSetupState(LocalRuntimeId.Alpine, LocalRuntimeIssue.Ready)
        }
    }

    override suspend fun execute(
        argumentsJson: String,
        onProgress: (suspend (String) -> Unit)?,
    ): String = withContext(Dispatchers.IO) {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return@withContext invalidArguments("Arguments were not valid JSON.")
        val command = arguments.optString("command").trim()
        val workingDirectory = normalizePath(
            arguments.optString("working_directory").trim()
                .ifBlank { arguments.optString("workingDirectory").trim() }
                .ifBlank { homeDirectory }
        )
        if (command.isBlank()) return@withContext invalidArguments("Missing required 'command' argument.")

        val setup = inspectSetup()
        if (!setup.isReady) return@withContext setupError(command, workingDirectory, setup)

        ensureWorkspace()
        ensureGuestNetworkConfig()
        val runId = nextRunId()
        val runDir = File(runtimeRoot, "runs/$runId").apply { mkdirs() }
        val stdoutFile = File(runDir, "stdout.log")
        val stderrFile = File(runDir, "stderr.log")
        val run = AlpineRun(
            runId = runId,
            command = command,
            workingDirectory = workingDirectory,
            startedAtMillis = System.currentTimeMillis(),
            stdoutFile = stdoutFile,
            stderrFile = stderrFile,
        )
        runs[runId] = run

        val process = runCatching {
            buildAlpineProcess(command, workingDirectory)
                .redirectOutput(stdoutFile)
                .redirectError(stderrFile)
                .start()
        }.getOrElse { throwable ->
            runs.remove(runId)
            return@withContext commandError(
                command = command,
                workingDirectory = workingDirectory,
                runId = runId,
                message = throwable.message ?: "Failed to start Alpine command.",
            )
        }
        run.process = process
        AlpineRunReaper.watch(run, runs)

        try {
            val deadline = System.currentTimeMillis() + AlpineWatchWindowMillis
            while (System.currentTimeMillis() < deadline && process.isAlive) {
                delay(1_000L)
                onProgress?.invoke(snapshot(run, AlpineDefaultTailBytes))
            }
            snapshot(run, AlpineDefaultTailBytes)
        } catch (cancellationException: CancellationException) {
            runCatching { killExecutionByRunId(runId) }
            throw cancellationException
        }
    }

    override suspend fun fetchExecution(argumentsJson: String): String = withContext(Dispatchers.IO) {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return@withContext invalidArguments("Arguments were not valid JSON.")
        val runId = arguments.optString("run_id").trim()
            .ifBlank { arguments.optString("runId").trim() }
        if (runId.isBlank()) return@withContext invalidArguments("Missing required 'run_id' argument.")
        val tailBytes = resolveTailBytes(arguments)
        runs[runId]?.let { return@withContext snapshot(it, tailBytes) }
        invalidArguments("Unknown Alpine run_id: $runId")
    }

    override suspend fun killExecution(argumentsJson: String): String = withContext(Dispatchers.IO) {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return@withContext invalidArguments("Arguments were not valid JSON.")
        val runId = arguments.optString("run_id").trim()
            .ifBlank { arguments.optString("runId").trim() }
        val tailBytes = resolveTailBytes(arguments)
        killExecutionByRunId(runId, tailBytes)
    }

    override suspend fun killExecutionByRunId(
        runId: String,
        tailBytes: Int,
    ): String = withContext(Dispatchers.IO) {
        if (runId.isBlank()) return@withContext invalidArguments("Missing required run id.")
        val run = runs[runId] ?: return@withContext invalidArguments("Unknown Alpine run_id: $runId")
        run.process?.let { process ->
            if (process.isAlive) {
                run.cancelled = true
                runCatching { process.destroy() }
                if (!process.waitFor(800, TimeUnit.MILLISECONDS)) {
                    runCatching { process.destroyForcibly() }
                }
            }
        }
        snapshot(run, tailBytes)
    }

    override suspend fun executeCommand(
        command: String,
        workingDirectory: String,
        awaitTimeoutMillis: Long,
    ): String = executeCommand(command, workingDirectory, awaitTimeoutMillis, null)

    private suspend fun executeCommand(
        command: String,
        workingDirectory: String,
        awaitTimeoutMillis: Long,
        onOutput: ((String) -> Unit)?,
    ): String = withContext(Dispatchers.IO) {
        val normalizedWorkingDirectory = normalizePath(workingDirectory)
        val setup = inspectSetup()
        if (!setup.isReady) return@withContext setupError(command, normalizedWorkingDirectory, setup)
        ensureWorkspace()
        ensureGuestNetworkConfig()
        val startedAtMillis = System.currentTimeMillis()
        val stdoutFile = File.createTempFile("aether-alpine-stdout", ".log", runtimeRoot)
        val stderrFile = File.createTempFile("aether-alpine-stderr", ".log", runtimeRoot)
        val streamedStdout = StringBuilder()
        val streamedStderr = StringBuilder()
        val process = runCatching {
            buildAlpineProcess(command, normalizedWorkingDirectory).apply {
                if (onOutput == null) {
                    redirectOutput(stdoutFile)
                    redirectError(stderrFile)
                }
            }.start()
        }.getOrElse { throwable ->
            return@withContext commandError(
                command = command,
                workingDirectory = normalizedWorkingDirectory,
                message = throwable.message ?: "Failed to start Alpine command.",
            )
        }
        val outputThreads = if (onOutput != null) {
            listOf(
                streamProcessOutput(process.inputStream, streamedStdout, onOutput),
                streamProcessOutput(process.errorStream, streamedStderr, onOutput),
            )
        } else {
            emptyList()
        }
        val finished = process.waitFor(awaitTimeoutMillis, TimeUnit.MILLISECONDS)
        if (!finished) {
            runCatching { process.destroy() }
            if (!process.waitFor(800, TimeUnit.MILLISECONDS)) {
                runCatching { process.destroyForcibly() }
            }
        }
        outputThreads.forEach { thread -> runCatching { thread.join(500L) } }
        val stdout = if (onOutput == null) stdoutFile.readTextSafe() else streamedStdout.toString()
        val stderr = if (onOutput == null) stderrFile.readTextSafe() else streamedStderr.toString()
        stdoutFile.delete()
        stderrFile.delete()
        JSONObject().apply {
            put("ok", finished && process.exitValueSafe() == 0)
            put("command", command)
            put("working_directory", normalizedWorkingDirectory)
            put("duration_ms", System.currentTimeMillis() - startedAtMillis)
            put("stdout", stdout)
            put("stderr", stderr)
            put("exit_code", if (finished) process.exitValueSafe() else -1)
            put("err", if (finished) -1 else -2)
            put("errmsg", if (finished) "" else "Timed out waiting for Alpine to reply.")
        }.toString()
    }

    private fun streamProcessOutput(
        stream: InputStream,
        sink: StringBuilder,
        onOutput: (String) -> Unit,
    ): Thread = Thread(
        {
            val buffer = ByteArray(4096)
            runCatching {
                stream.use {
                    while (true) {
                        val read = it.read(buffer)
                        if (read == -1) break
                        if (read > 0) {
                            val chunk = String(buffer, 0, read, Charsets.UTF_8)
                            synchronized(sink) {
                                sink.append(chunk)
                                if (sink.length > 64_000) {
                                    sink.delete(0, sink.length - 64_000)
                                }
                            }
                            onOutput(chunk)
                        }
                    }
                }
            }
        },
        "aether-alpine-command-output",
    ).apply {
        isDaemon = true
        start()
    }

    private fun buildAlpineProcess(
        command: String,
        workingDirectory: String,
        extraBinds: List<Pair<String, String>> = emptyList(),
        bindSys: Boolean = false,
    ): ProcessBuilder {
        val prootCommand = buildList {
            add(AlpineHostLinker)
            add(prootFile.absolutePath)
            add("-0")
            add("-r")
            add(rootfsDir.absolutePath)
            add("-b")
            add("${workspaceDir.absolutePath}:/workspace")
            add("-b")
            add("/dev")
            add("-b")
            add("/proc")
            if (bindSys) {
                add("-b")
                add("/sys")
            }
            extraBinds.forEach { (host, guest) ->
                add("-b")
                add("$host:$guest")
            }
            add("-w")
            add(workingDirectory)
            addAll(alpineGuestShellArgs(command, interactive = false))
        }
        return ProcessBuilder(prootCommand).apply {
            configureAlpineProcessEnvironment()
        }
    }

    private fun buildAlpineInteractiveProcess(): ProcessBuilder {
        val prootCommand = buildAlpineInteractiveCommand()
        return ProcessBuilder(prootCommand).apply {
            configureAlpineProcessEnvironment()
            environment()["PS1"] = "aether-alpine:\\w# "
            environment()["TERM"] = "xterm-256color"
        }
    }

    private fun buildAlpineInteractiveCommand(): List<String> =
        listOf(
            AlpineHostLinker,
            prootFile.absolutePath,
            "-0",
            "-r",
            rootfsDir.absolutePath,
            "-b",
            "${workspaceDir.absolutePath}:/workspace",
            "-b",
            "/dev",
            "-b",
            "/proc",
            "-b",
            "/sys",
            "-w",
            homeDirectory,
        ) + alpineGuestShellArgs(command = null, interactive = true)

    private fun ProcessBuilder.configureAlpineProcessEnvironment() {
        directory(runtimeRoot)
        guestPathToHostFile(KimiNodeCompileCacheGuestPath).mkdirs()
        // Termux unsets this before proot; a host wrap.sh / native-bridge
        // preload would otherwise apply to musl guest binaries.
        environment().remove("LD_PRELOAD")
        environment().putAll(buildAlpineProcessEnvironment())
    }

    private fun buildAlpineProcessEnvironment(): Map<String, String> =
        buildMap {
            put("HOME", homeDirectory)
            put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            put("KIMI_CODE_HOME", KimiCodeHome)
            put("KIMI_CODE_EXPERIMENTAL_FLAG", "1")
            // Android /data/data forbids hardlink(2); the minidb session-index
            // mirror's atomic link() then fails with EACCES and its flush timer
            // retries every 100ms with no backoff, starving the event loop.
            // The sidecar BM25 memory replaces this index, so keep it off.
            put("KIMI_CODE_EXPERIMENTAL_PERSISTENCE_MINIDB_READMODEL", "0")
            put("NODE_COMPILE_CACHE", KimiNodeCompileCacheGuestPath)
            put("NODE_OPTIONS", "--max-old-space-size=$kimiNodeMaxOldSpaceMb")
            // Without this the guest runs in UTC, and the CLI - which is what decides the date
            // the model sees - disagrees with the host for every evening east of UTC.
            put("TZ", posixTimeZoneSpec())
            put("AETHER_RUNTIME", "alpine")
            put("AETHER_HOST_WORKSPACE", workspaceDir.absolutePath)
            put("PROOT_ROOTFS", rootfsDir.absolutePath)
            put("PROOT_BIN", prootFile.absolutePath)
            put("PROOT_LOADER", loaderFile.absolutePath)
            put("PROOT_TMP_DIR", hostTmpDir.absolutePath)
            put("LD_LIBRARY_PATH", hostLibDir.absolutePath)
            put("PS1", "aether-alpine:\\w# ")
            put("TERM", "xterm-256color")
            put("COLORTERM", "truecolor")
        }.toMutableMap().also { environment ->
        environmentVariables.forEach { variable ->
            environment[variable.name] = variable.value
        }
    }

    private fun hasBundledRuntimeAssets(): Boolean =
        assetExists("${alpineAssetRoot()}/proot.bin") &&
            assetExists("${alpineAssetRoot()}/loader.bin") &&
            assetExists("${alpineAssetRoot()}/libtalloc.so.2") &&
            alpineRootfsAssetCandidates().any { assetExists(it.path) }

    private suspend fun ensureBundledKimiCode(
        onProgress: (AlpineSetupProgress) -> Unit,
    ) {
        if (kimiBundleReady) return
        kimiInstallMutex.lock()
        try {
            if (kimiBundleReady) return
            ensureBundledKimiCodeLocked(onProgress)
        } finally {
            kimiInstallMutex.unlock()
        }
    }

    private suspend fun ensureBundledKimiCodeLocked(
        onProgress: (AlpineSetupProgress) -> Unit,
    ) {
        val marker = guestPathToHostFile("$KimiCodeInstallRoot/.bundled-version")
        val installedVersion = marker.readTextSafe().trim()
        val sameDependencies = installedVersion.substringBefore("+mem-") ==
            BundledAgentRuntimeVersion.substringBefore("+mem-")
        if (sameDependencies && guestPathToHostFile(KimiMainJsGuestPath).isFile) {
            if (!kimiAskUserPatched) {
                if (!kimiAskUserPatchMarker().isFile) {
                    patchKimiAskUserOtherAnswers()
                }
                kimiAskUserPatched = true
            }
            if (!kimiAcpFailurePatched) {
                if (!kimiAcpFailurePatchMarker().isFile) {
                    patchKimiAcpFailurePropagation()
                }
                kimiAcpFailurePatched = true
            }
            if (!kimiSessionMemoryPatched) {
                installSessionMemorySidecar()
                val freshPatch = !kimiSessionMemoryPatchMarker().isFile
                if (freshPatch) {
                    patchKimiSessionMemory()
                }
                patchKimiQueryStoreHardlink()
                kimiSessionMemoryPatched = true
                if (freshPatch) verifySessionMemorySidecar()
            }
            marker.writeText("$BundledAgentRuntimeVersion\n")
            kimiBundleReady = true
            return
        }

        onProgress(AlpineSetupProgress(output = "Preparing the bundled Aether agent runtime...\n"))
        val offlineDirectory = guestPathToHostFile("/root/.aether/kimi-offline").apply {
            deleteRecursively()
            mkdirs()
        }
        val packageDirectory = File(offlineDirectory, "packages").apply { mkdirs() }
        val packageAssets = appContext.assets
            .open("${alpineAssetRoot()}/packages/manifest-v323.txt")
            .bufferedReader()
            .use { reader -> reader.readLines().map(String::trim).filter(String::isNotEmpty) }
        check(packageAssets.isNotEmpty()) { "Bundled Node.js packages are missing." }
        packageAssets.forEach { name ->
            copyAsset("${alpineAssetRoot()}/packages/$name", File(packageDirectory, name), executable = false)
        }
        copyAsset(KimiCodeAsset, File(offlineDirectory, "kimi-code.tgz"), executable = false)
        copyAsset(EverMePluginAsset, File(offlineDirectory, "everme-kimicode.tgz"), executable = false)
        val everCliAsset = everCliAssetCandidates().firstOrNull { assetExists(it) }
            ?: error("Evercli asset is missing.")
        copyAsset(everCliAsset, File(offlineDirectory, "evercli.tar"), executable = false)
        val everCliExtract = if (everCliAsset.endsWith(".tar.gz")) {
            "tar -xzf /root/.aether/kimi-offline/evercli.tar -C /usr/local/bin"
        } else {
            "tar -xf /root/.aether/kimi-offline/evercli.tar -C /usr/local/bin"
        }

        val command = listOf(
            // A previous interrupted install may already have a working Node
            // toolchain while apk's database is mid-transaction. Reusing that
            // bundled toolchain avoids reopening the database and is safe
            // because its version is verified below. A fresh rootfs still
            // installs exclusively from the APK-bundled package closure.
            "if node --version >/dev/null 2>&1; then :; else apk add --no-network --no-cache --allow-untrusted /root/.aether/kimi-offline/packages/*.apk; fi",
            "rm -rf '$KimiCodeInstallRoot/node_modules/@moonshot-ai/kimi-code'",
            "mkdir -p '$KimiCodeInstallRoot/node_modules/@moonshot-ai/kimi-code'",
            "tar -xzf /root/.aether/kimi-offline/kimi-code.tgz -C '$KimiCodeInstallRoot/node_modules/@moonshot-ai/kimi-code' --strip-components=1",
            "rm -rf '$KimiCodeInstallRoot/node_modules/@moonshot-ai/kimi-code/dist-web' '$KimiCodeInstallRoot/node_modules/@moonshot-ai/kimi-code/native'",
            "mkdir -p '$KimiCodeHome/sessions'",
            "printf '#!/bin/sh\\nexport KIMI_CODE_HOME=\"\${KIMI_CODE_HOME:-$KimiCodeHome}\"\\nexport KIMI_CODE_EXPERIMENTAL_FLAG=\"\${KIMI_CODE_EXPERIMENTAL_FLAG:-1}\"\\nexport KIMI_CODE_EXPERIMENTAL_TOWER=\"\${KIMI_CODE_EXPERIMENTAL_TOWER:-1}\"\\nexport KIMI_CODE_EXPERIMENTAL_PERSISTENCE_MINIDB_READMODEL=\"\${KIMI_CODE_EXPERIMENTAL_PERSISTENCE_MINIDB_READMODEL:-0}\"\\nexec node $KimiMainJsGuestPath \"\$@\"\\n' > $KimiBinaryGuestPath",
            "chmod 0755 '$KimiBinaryGuestPath'",
            everCliExtract,
            "chmod 0755 /usr/local/bin/evercli",
            "rm -rf '$EverMeBundleRoot'",
            "mkdir -p '$EverMeBundleRoot'",
            "tar -xzf /root/.aether/kimi-offline/everme-kimicode.tgz -C '$EverMeBundleRoot'",
            "kimi --version",
            "evercli --version",
            "node -e \"const p='$EverMeBundleRoot/kimi.plugin.json';const m=JSON.parse(require('fs').readFileSync(p,'utf8'));if(m.name!=='everme')process.exit(1)\"",
        ).joinToString(" && ")
        val result = JSONObject(executeCommand(command, homeDirectory, 10 * 60_000L))
        check(result.optBoolean("ok")) {
            result.optString("stderr").ifBlank { result.optString("stdout") }.ifBlank {
                "Bundled Kimi Code installation failed."
            }
        }
        marker.parentFile?.mkdirs()
        marker.writeText("$BundledAgentRuntimeVersion\n")
        offlineDirectory.deleteRecursively()
        patchKimiAskUserOtherAnswers()
        kimiAskUserPatched = true
        patchKimiAcpFailurePropagation()
        kimiAcpFailurePatched = true
        installSessionMemorySidecar()
        patchKimiSessionMemory()
        patchKimiQueryStoreHardlink()
        kimiSessionMemoryPatched = true
        // The full-reinstall path needs the same proof as the incremental one. Verifying only on the
        // fast path meant a version bump - the exact moment the sidecar changes - was the one time
        // nothing checked it.
        verifySessionMemorySidecar()
        kimiBundleReady = true
        onProgress(
            AlpineSetupProgress(
                output = "Aether agent runtime, EverMe $EverMeVersion, and session memory $SessionMemoryVersion are ready.\n",
            )
        )
    }

    private fun kimiAskUserPatchMarker(): File =
        guestPathToHostFile("$KimiCodeInstallRoot/.ask-user-patched-$BundledAgentRuntimeVersion")

    private fun kimiAcpFailurePatchMarker(): File =
        guestPathToHostFile("$KimiCodeInstallRoot/.acp-failure-patched-2")

    private fun kimiSessionMemoryPatchMarker(): File =
        guestPathToHostFile("$KimiCodeInstallRoot/.session-memory-patched-$BundledAgentRuntimeVersion")

    /**
     * Record the installed memory version and drop the old retrieval store.
     *
     * Version 9 removes local search and archive windows. cards/blobs/ledgers are no longer a
     * fact source - EverMe holds long-term recall, Chat holds the wording. Pending EverMe ingest
     * and local Markdown notes stay: an upgrade must not drop turns that have not been uploaded.
     */
    private fun recordSessionMemoryStoreVersion() {
        val marker = guestPathToHostFile("$KimiCodeInstallRoot/.session-memory-store-$SessionMemoryVersion")
        if (marker.exists()) return
        val root = runCatching { memoryRootDir() }.getOrNull()
        if (root != null) {
            for (name in listOf("cards", "blobs", "ledgers")) {
                runCatching { File(root, name).deleteRecursively() }
            }
            for (name in listOf("clustered-on", "active.json")) {
                runCatching { File(root, name).delete() }
            }
        }
        runCatching {
            marker.parentFile?.mkdirs()
            marker.writeText(SessionMemoryVersion)
        }
    }

    private fun installSessionMemorySidecar() {
        recordSessionMemoryStoreVersion()
        val targetRoot = guestPathToHostFile(SessionMemoryInstallRoot)
        targetRoot.mkdirs()
        SessionMemoryMcp.AssetFiles.forEach { name ->
            copyAsset(
                "$SessionMemoryAssetRoot/$name",
                File(targetRoot, name),
                executable = false,
            )
        }
        SessionMemoryMcp.ObsoleteAssetFiles.forEach { name ->
            runCatching { File(targetRoot, name).delete() }
        }
        runCatching { File(targetRoot, "models").deleteRecursively() }
    }

    /**
     * Sidecar needles only. Memory logic lives in @aether/session-memory;
     * upgrading kimi-code should re-run this injector, not rebase agent-core.
     */
    /**
     * Prove the folder actually runs, once per install.
     *
     * The whole point is that a hook can be present, imported and syntactically fine while sitting
     * on a code path nobody takes - which is what happened, undetected, because nothing ever
     * asserted that folding had occurred. The selftest exercises the real module in the real guest
     * runtime and asserts the properties that matter, including that an unchanged history folds to
     * a byte-identical prompt. A failure is logged rather than thrown: a broken sidecar must not
     * stop the app from starting, but it must not be quiet either.
     */
    private suspend fun verifySessionMemorySidecar() {
        val output = runCatching {
            executeCommand(
                command = "node ${SessionMemoryInstallRoot}/selftest.mjs 2>&1",
                awaitTimeoutMillis = 30_000L,
            )
        }.getOrElse { error ->
            android.util.Log.w("AlpineRuntime", "session-memory selftest could not run", error)
            return
        }
        if ("session-memory selftest ok" in output) {
            android.util.Log.i("AlpineRuntime", "session-memory selftest ok")
        } else {
            android.util.Log.e("AlpineRuntime", "session-memory selftest FAILED: ${output.takeLast(2000)}")
        }
    }

    private fun kimiQueryStorePatchMarker(): File =
        guestPathToHostFile("$KimiCodeInstallRoot/.query-store-patched-$BundledAgentRuntimeVersion")

    /**
     * Stop the query store from creating a file with a hard link.
     *
     * `cluster.meta.json` is created with `link(tmp, meta)` because a hard link fails with EEXIST
     * when the target already exists - a create-if-absent that is atomic on a normal filesystem.
     * Under proot on Android the call fails with EACCES instead, which is not EEXIST, so it is
     * rethrown, the store never opens, and the session index mirror retries on every record it is
     * handed. The device log held 12,561 consecutive failures of exactly this, about one per
     * 100ms, each one real CPU and IO on a phone.
     *
     * `open(path, "wx")` is O_CREAT|O_EXCL: the same create-only-if-absent, atomic in the same way,
     * and it needs no link. The EEXIST branch below it keeps working unchanged.
     *
     * Unlike the session-memory needles, a miss here does not fail the build. This is a performance
     * fix inside someone else's code; a future CLI that renames `fs$1` should cost a log line, not
     * a runtime that refuses to start.
     */
    private fun patchKimiQueryStoreHardlink() {
        val patchMarker = kimiQueryStorePatchMarker()
        if (patchMarker.isFile) return
        val file = guestPathToHostFile(KimiMainJsGuestPath)
        if (!file.isFile) return
        var text = file.readTextSafe()
        if (text.contains(QueryStoreExclusiveCreate)) {
            patchMarker.parentFile?.mkdirs()
            patchMarker.writeText("ok\n")
            return
        }
        val needle =
            "\t\t\ttry {\n" +
                "\t\t\t\tawait fs\$1.writeFile(tmpPath, JSON.stringify(requested, null, 2));\n" +
                "\t\t\t\tawait fs\$1.link(tmpPath, metaPath);\n" +
                "\t\t\t\treturn new Topology(dir, requested);\n" +
                "\t\t\t} catch (e) {\n" +
                "\t\t\t\tif (e.code !== \"EEXIST\") throw e;"
        if (!text.contains(needle)) {
            android.util.Log.w("AlpineRuntime", "query-store hardlink needle missing; left as is")
            return
        }
        val replacement =
            "\t\t\ttry {\n" +
                "\t\t\t\t" + QueryStoreExclusiveCreate + "\n" +
                "\t\t\t\ttry { await __aetherMetaHandle.writeFile(JSON.stringify(requested, null, 2)); }\n" +
                "\t\t\t\tfinally { await __aetherMetaHandle.close(); }\n" +
                "\t\t\t\treturn new Topology(dir, requested);\n" +
                "\t\t\t} catch (e) {\n" +
                "\t\t\t\tif (e.code !== \"EEXIST\") throw e;"
        text = text.replace(needle, replacement)
        file.writeText(text)
        patchMarker.parentFile?.mkdirs()
        patchMarker.writeText("ok\n")
    }
    private fun patchKimiSessionMemory() {
        val patchMarker = kimiSessionMemoryPatchMarker()
        if (patchMarker.isFile) return
        val file = guestPathToHostFile(KimiMainJsGuestPath)
        if (!file.isFile) return
        var text = file.readTextSafe()
        val missing = mutableListOf<String>()

        val importNeedle = "import * as QRCode from \"qrcode\";"
        val importLine =
            "import { foldForModel as __aetherFoldForModel } from \"@aether/session-memory/hook.mjs\";"
        if (!text.contains("__aetherFoldForModel")) {
            if (!text.contains(importNeedle)) {
                missing += "qrcode-import"
            } else {
                text = text.replace(importNeedle, "$importNeedle\n$importLine")
            }
        }

        // The fold has to sit on `project()`, not inside `projectStrict`.
        //
        // `projectStrict` reads like the strict variant of the projector, and it is - but main.mjs
        // only selects it when `policy.structure === "strict"`, and the one place that sets that
        // flag is the error-recovery branch that resends after a provider rejects the request
        // structure. Hooked there, folding ran on approximately no requests: the session-memory
        // store directory was never even created, because `ensureStore()` - the second line of
        // `foldForModel` - had not executed once.
        //
        // Folding before the projection rather than after is also deliberate. Projection repairs
        // structure (pairs tool calls with their results, drops leading non-user messages); folding
        // removes messages and can create exactly those problems, so the repair has to run last.
        val staleProjectStrictInjection =
            "\tconst projected = dropLeadingNonUserMessages(mergeConsecutiveAssistantMessages(dedupeDuplicateToolCalls(project(history, onAnomaly), onAnomaly), onAnomaly), onAnomaly);\n" +
                "\ttry { return typeof __aetherFoldForModel === \"function\" ? __aetherFoldForModel(projected) : projected; } catch { return projected; }\n"
        val restoredProjectStrict =
            "\treturn dropLeadingNonUserMessages(mergeConsecutiveAssistantMessages(dedupeDuplicateToolCalls(project(history, onAnomaly), onAnomaly), onAnomaly), onAnomaly);\n"
        if (text.contains(staleProjectStrictInjection)) {
            text = text.replace(staleProjectStrictInjection, restoredProjectStrict)
        }

        val projectNeedle =
            "\t\t\tconst projected = this.projectWithTrace(messages, policy.structure === \"strict\" ? projectStrict : project);"
        val projectReplacement =
            "\t\t\tlet __aetherFolded = messages;\n" +
                "\t\t\ttry { if (typeof __aetherFoldForModel === \"function\") __aetherFolded = __aetherFoldForModel(messages); }\n" +
                "\t\t\tcatch (error) { try { this.log?.warn?.(\"aether session-memory fold failed\", { error: String(error?.message || error) }); } catch {} }\n" +
                "\t\t\tconst projected = this.projectWithTrace(__aetherFolded, policy.structure === \"strict\" ? projectStrict : project);"
        if (!text.contains("__aetherFoldForModel(messages)")) {
            if (!text.contains(projectNeedle)) {
                missing += "contextProjector-project"
            } else {
                text = text.replace(projectNeedle, projectReplacement)
            }
        }

        val compactOriginal =
            "\t\tshouldCompact(usedSize) {\n" +
                "\t\t\tif (this.maxSize <= 0) return false;\n" +
                "\t\t\treturn usedSize >= this.maxSize * this.config.triggerRatio || this.shouldUseReservedContext(usedSize);\n" +
                "\t\t}"
        val compactDisabledBlock =
            "\t\tshouldCompact(usedSize) {\n" +
                "\t\t\treturn false;\n" +
                "\t\t}"
        if (text.contains(compactDisabledBlock)) {
            text = text.replace(compactDisabledBlock, compactOriginal)
        } else if (!text.contains(compactOriginal)) {
            missing += "shouldCompact"
        }

        val slashNeedle =
            "\t\t{\n" +
                "\t\t\tname: \"compact\",\n" +
                "\t\t\tdescription: \"Compact the conversation context\",\n" +
                "\t\t\tinput: { hint: \"<optional custom summarization instructions>\" }\n" +
                "\t\t},"
        if (!text.contains(slashNeedle)) {
            val acpInsertAnchors = listOf(
                "\t\t{\n\t\t\tname: \"cost\",\n",
                "\t\t{\n\t\t\tname: \"cancel\",\n",
                "\t\t{\n\t\t\tname: \"status\",\n",
            )
            val anchor = acpInsertAnchors.firstOrNull { text.contains(it) }
            if (anchor != null) {
                text = text.replaceFirst(anchor, slashNeedle + "\n" + anchor)
            } else {
                missing += "acp-compact-command"
            }
        }

        val runCompactOriginal =
            "\t\tcase \"compact\": return await deps.agent.compact({ instruction: args === \"\" ? void 0 : args }) ? \"Context compaction started — it runs in the background and the compacted context applies once it finishes.\" : \"A context compaction is already running.\";"
        val runCompactAwait =
            "\t\tcase \"compact\": {\n" +
                "\t\t\tconst __aetherCompactStarted = await deps.agent.compact({ instruction: args === \"\" ? void 0 : args });\n" +
                "\t\t\ttry {\n" +
                "\t\t\t\tconst __aetherPending = deps.agent.fullCompaction?.compacting?.promise;\n" +
                "\t\t\t\tif (__aetherPending) await __aetherPending;\n" +
                "\t\t\t} catch {}\n" +
                "\t\t\treturn __aetherCompactStarted ? \"AETHER_COMPACT_DONE\" : \"AETHER_COMPACT_BUSY\";\n" +
                "\t\t}"
        val runCompactDisabled =
            "\t\tcase \"compact\": return \"Context compact is disabled. Older turns are frozen as memory nodes; use search_memory_nodes to recall them.\";"
        when {
            text.contains("AETHER_COMPACT_DONE") -> Unit
            text.contains(runCompactOriginal) -> text = text.replace(runCompactOriginal, runCompactAwait)
            text.contains(runCompactDisabled) -> text = text.replace(runCompactDisabled, runCompactAwait)
            !text.contains("deps.agent.compact") -> missing += "runBuiltinSlashCommand-compact"
        }

        // Fold still injects the local note. Kimi compact is the window safety valve again;
        // leaving shouldCompact as `return false` after this reverse patch is the failure mode.
        val foldMounted = text.contains("__aetherFoldForModel(messages)")
        val compactDisabled = text.contains(compactDisabledBlock)
        if (missing.isNotEmpty()) {
            android.util.Log.w(
                "AlpineRuntime",
                "session-memory patch needles missing: ${missing.joinToString()} " +
                    "(foldMounted=$foldMounted compactDisabled=$compactDisabled)",
            )
        }
        check(foldMounted) {
            "session-memory patch did not mount foldForModel " +
                "(missing: ${missing.joinToString()})."
        }
        check(!compactDisabled) {
            "session-memory patch left shouldCompact returning false " +
                "(missing: ${missing.joinToString()}). Kimi compact must stay enabled."
        }
        file.writeText(text)
        // The marker records only a fully applied patch. A partial one is re-attempted next launch,
        // which is what lets a later app update - carrying needles for the new CLI - repair itself
        // instead of staying degraded until someone wipes the runtime.
        if (missing.isEmpty()) {
            patchMarker.parentFile?.mkdirs()
            patchMarker.writeText("ok\n")
        }
    }

    /**
     * kimi-code's ACP elicitation bridge drops values that are not declared
     * enum labels, so the TUI's synthetic "Other" free-text answer never
     * reaches AskUserQuestion. Keep unmatched non-blank strings.
     */
    private fun patchKimiAskUserOtherAnswers() {
        val patchMarker = kimiAskUserPatchMarker()
        if (patchMarker.isFile) return
        val file = guestPathToHostFile(KimiMainJsGuestPath)
        if (!file.isFile) return
        val original = file.readTextSafe()
        val alreadyPatched = original.contains("else if (value.trim().length > 0) answers[q.question] = value;")
        if (!alreadyPatched) {
            val needle =
                "if (typeof value === \"string\" && q.options.some((opt) => opt.label === value)) answers[q.question] = value;"
            val replacement = """if (typeof value === "string") {
			if (q.options.some((opt) => opt.label === value)) answers[q.question] = value;
			else if (value.trim().length > 0) answers[q.question] = value;
		}"""
            if (!original.contains(needle)) return
            val multiNeedle =
                "const picked = q.options.map((opt) => opt.label).filter((label) => value.includes(label));\n" +
                    "\t\t\tif (picked.length > 0) answers[q.question] = picked.join(\", \");"
            val multiReplacement = """const declared = q.options.map((opt) => opt.label);
			const picked = declared.filter((label) => value.includes(label));
			const extras = value.filter((item) => typeof item === "string" && item.trim().length > 0 && !declared.includes(item));
			const merged = [...picked, ...extras];
			if (merged.length > 0) answers[q.question] = merged.join(", ");"""
            file.writeText(
                original.replace(needle, replacement).let { patched ->
                    if (patched.contains(multiNeedle)) patched.replace(multiNeedle, multiReplacement) else patched
                },
            )
        }
        patchMarker.parentFile?.mkdirs()
        patchMarker.writeText("ok\n")
    }

    /**
     * kimi-code maps a failed provider turn to ACP `end_turn`, which makes
     * clients mistake HTTP/provider failures for a successful empty response.
     * Keep ACP's public stop-reason shape intact while rejecting the prompt RPC
     * with the provider's already-sanitized error message.
     */
    private fun patchKimiAcpFailurePropagation() {
        val patchMarker = kimiAcpFailurePatchMarker()
        if (patchMarker.isFile) return
        val file = guestPathToHostFile(KimiMainJsGuestPath)
        if (!file.isFile) return
        val original = file.readTextSafe()
        val legacyNeedle = """const authErr = authRequiredFromPayload(event.error);
						if (authErr) {
							reject(authErr);
							return;
						}"""
        val legacyReplacement = """const authErr = authRequiredFromPayload(event.error);
						if (authErr) {
							reject(authErr);
							return;
						}
						reject(RequestError${'$'}2.internalError(void 0, event.error?.message ?? "session prompt failed"));
						return;"""
        val currentNeedle = """if (event.reason === "failed" && isAuthError(error)) {
					driver.reject(RequestError${'$'}1.authRequired(void 0, error?.message));
					return;
				}
				driver.resolve({ stopReason: turnEndReasonToStopReason(event.reason, error) });"""
        val currentReplacement = """if (event.reason === "failed") {
					if (isAuthError(error)) driver.reject(RequestError${'$'}1.authRequired(void 0, error?.message));
					else driver.reject(RequestError${'$'}1.internalError(void 0, error?.message ?? "session prompt failed"));
					return;
				}
				driver.resolve({ stopReason: turnEndReasonToStopReason(event.reason, error) });"""
        val patched = original
            .replace(legacyNeedle, legacyReplacement)
            .replace(currentNeedle, currentReplacement)
        if (patched == original) return
        file.writeText(patched)
        patchMarker.parentFile?.mkdirs()
        patchMarker.writeText("ok\n")
    }

    private fun assetExists(path: String): Boolean =
        runCatching {
            appContext.assets.open(path).use { true }
        }.getOrDefault(false)

    private suspend fun installFromAssets(
        onProgress: (AlpineSetupProgress) -> Unit,
    ) {
        onProgress(AlpineSetupProgress(output = "Preparing Alpine runtime files...\n"))
        stagingRoot.apply {
            deleteRecursively()
            mkdirs()
        }
        val stagingBin = File(stagingRoot, "bin").apply { mkdirs() }
        val stagingLib = File(stagingRoot, "lib").apply { mkdirs() }
        val stagingLoader = File(stagingRoot, "libexec/proot").apply { mkdirs() }
        val stagingRootfs = File(stagingRoot, "rootfs").apply { mkdirs() }
        File(stagingRoot, "workspace").mkdirs()
        File(stagingRoot, "tmp").mkdirs()

        onProgress(AlpineSetupProgress(output = "Copying proot runtime...\n"))
        copyAsset("${alpineAssetRoot()}/proot.bin", File(stagingBin, "proot"), executable = true)
        copyAsset("${alpineAssetRoot()}/loader.bin", File(stagingLoader, "loader"), executable = true)
        copyAsset("${alpineAssetRoot()}/libtalloc.so.2", File(stagingLib, "libtalloc.so.2"), executable = false)
        if (assetExists("${alpineAssetRoot()}/libandroid-shmem.so")) {
            copyAsset(
                "${alpineAssetRoot()}/libandroid-shmem.so",
                File(stagingLib, "libandroid-shmem.so"),
                executable = false,
            )
        }
        val rootfsAsset = alpineRootfsAssetCandidates().firstOrNull { assetExists(it.path) }
            ?: error("Alpine rootfs asset is missing.")
        onProgress(
            AlpineSetupProgress(
                activity = AlpineSetupActivity.Extracting,
                output = "Extracting ${rootfsAsset.path}...\n",
            )
        )
        appContext.assets.open(rootfsAsset.path).use { stream ->
            if (rootfsAsset.compressed) {
                extractTarGz(stream, stagingRootfs, onProgress)
            } else {
                extractTar(stream, stagingRootfs, onProgress)
            }
        }
        File(stagingRoot, ".installed-version").writeText(
            "alpine-3.23.4-${alpineHostAbiFolder()}\n",
        )

        runtimeRoot.deleteRecursively()
        if (!stagingRoot.renameTo(runtimeRoot)) {
            copyDirectory(stagingRoot, runtimeRoot)
            stagingRoot.deleteRecursively()
        }
        ensureWorkspace()
        ensureGuestNetworkConfig()
        installPreinstalledExtensionsSync()
        refreshApkRepositoriesForCurrentNetwork(onProgress)
        onProgress(AlpineSetupProgress(output = "Alpine runtime files are ready.\n"))
    }

    suspend fun refreshApkRepositoriesForCurrentNetwork(
        onProgress: (AlpineSetupProgress) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        apkRepositoryMutex.lock()
        try {
            if (apkRepositoriesConfigured) return@withContext
            val environment = startupNetworkEnvironment ?: detectNetworkEnvironment().also {
                startupNetworkEnvironment = it
            }
            configureApkRepositories(environment, onProgress)
            apkRepositoriesConfigured = true
        } finally {
            apkRepositoryMutex.unlock()
        }
    }

    private fun detectNetworkEnvironment(): ApkNetworkEnvironment {
        val countryCode = runCatching {
            val connection = URL(AlpineNetworkTraceUrl).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = AlpineNetworkDetectionTimeoutMillis
                connection.readTimeout = AlpineNetworkDetectionTimeoutMillis
                connection.setRequestProperty("Accept", "text/plain")
                connection.setRequestProperty("User-Agent", "Aether-Alpine-Network-Check")
                if (connection.responseCode !in 200..299) return@runCatching null
                connection.inputStream.bufferedReader().use { parseCloudflareCountryCode(it.readText()) }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
        return when {
            countryCode == null -> ApkNetworkEnvironment.Unknown
            countryCode.equals("CN", ignoreCase = true) -> ApkNetworkEnvironment.China
            else -> ApkNetworkEnvironment.International
        }
    }

    private fun configureApkRepositories(
        environment: ApkNetworkEnvironment,
        onProgress: (AlpineSetupProgress) -> Unit,
    ) {
        val repositories = File(rootfsDir, "etc/apk/repositories")
        if (!repositories.isFile) return
        val original = runCatching { repositories.readText() }.getOrNull() ?: return
        val updated = apkRepositories(original, environment)
        if (updated != original) {
            runCatching { repositories.writeText(updated) }.getOrNull() ?: return
            onProgress(
                AlpineSetupProgress(
                    output = when (environment) {
                        ApkNetworkEnvironment.China ->
                            "Using the Tsinghua Alpine mirror with the official CDN as fallback.\n"
                        ApkNetworkEnvironment.International ->
                            "Using the official Alpine CDN for the current network.\n"
                        ApkNetworkEnvironment.Unknown ->
                            "Network region detection failed; using official and China Alpine sources.\n"
                    },
                )
            )
        }
    }

    private fun copyAsset(
        assetPath: String,
        target: File,
        executable: Boolean,
    ) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.delete()
        try {
            val copied = appContext.assets.open(assetPath).use { input ->
                temp.outputStream().use { output ->
                    val count = input.copyTo(output)
                    output.fd.sync()
                    count
                }
            }
            check(copied > 0L && temp.length() == copied) { "Asset copy incomplete: $assetPath" }
            check(temp.renameTo(target)) { "Cannot atomically install asset: $assetPath" }
        } finally {
            temp.delete()
        }
        target.setReadable(true, true)
        target.setWritable(true, true)
        if (executable) target.setExecutable(true, true)
    }

    internal fun resolveAcpHostFile(guestPath: String): File {
        val normalized = normalizePath(guestPath.trim())
        resolveWorkspaceHostPath(normalized)?.let { return it.hostFile }
        return guestPathToHostFile(normalized)
    }

    private fun memoryRootDir(): File = guestPathToHostFile("$KimiCodeHome/session-memory")

    private fun memoryNotesDir(): File = File(memoryRootDir(), "notes")

    /**
     * Local conversation notes on disk, for the settings screen.
     *
     * EverMe holds long-term recall; this list is only the Codex-style Markdown files the model
     * rewrites in place. Forgetting one deletes the file, not the cloud entry.
     */
    fun readMemoryPages(): List<kira.ditto.data.MemoryPage> {
        val dir = runCatching { memoryNotesDir() }.getOrNull() ?: return emptyList()
        if (!dir.isDirectory) return emptyList()
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".md") }.orEmpty()
        return kira.ditto.data.sortMemoryPages(
            files.mapNotNull { file ->
                val id = file.name.removeSuffix(".md")
                kira.ditto.data.parseMemoryNoteFile(
                    id = id,
                    markdown = file.readTextSafe(),
                    updatedAtMillis = file.lastModified(),
                )
            },
        )
    }

    /** Delete one local note file. EverMe cloud entries are not touched. */
    fun deleteMemoryPage(id: String): Boolean {
        val safe = id.trim()
        if (safe.isEmpty() || safe.contains('/') || safe.contains('\\') || safe.contains("..")) return false
        val file = runCatching { File(memoryNotesDir(), "$safe.md") }.getOrNull() ?: return false
        return file.existsNoFollow() && file.delete()
    }

    /** Forget every local note. Pending EverMe ingest and hash originals stay. */
    fun clearMemoryPages(): Boolean = runCatching {
        val notes = memoryNotesDir()
        if (notes.isDirectory) notes.deleteRecursively() else true
    }.getOrDefault(false)

    private fun guestPathToHostFile(guestPath: String): File {
        val relativePath = guestPath.trim().trimStart('/')
        val target = if (relativePath.isBlank()) rootfsDir.canonicalFile else File(rootfsDir, relativePath).canonicalFile
        val canonicalRoot = rootfsDir.canonicalFile
        require(target.path == canonicalRoot.path || target.path.startsWith(canonicalRoot.path + File.separator)) {
            "Refusing to write outside Alpine rootfs: $guestPath"
        }
        return target
    }

    private fun deleteFilesOlderThan(directory: File, cutoffMillis: Long): Long {
        if (!directory.exists()) return 0L
        var deleted = 0L
        directory.walkBottomUp().forEach { file ->
            if (file == directory) return@forEach
            val stale = !file.isDirectory && file.lastModified() < cutoffMillis
            val emptyDir = file.isDirectory && file.listFiles().isNullOrEmpty()
            if ((stale || emptyDir) && file.delete()) {
                deleted += if (file.isDirectory) 0L else file.length().coerceAtLeast(0L)
            }
        }
        return deleted
    }

    private fun extractTarGz(
        stream: InputStream,
        targetDirectory: File,
        onProgress: (AlpineSetupProgress) -> Unit,
    ) {
        GZIPInputStream(stream).use { gzip -> extractTar(gzip, targetDirectory, onProgress) }
    }

    private fun extractTar(
        stream: InputStream,
        targetDirectory: File,
        onProgress: (AlpineSetupProgress) -> Unit,
    ) {
        val progressReporter = ExtractionProgressReporter(onProgress)
        stream.use { tar ->
            val header = ByteArray(512)
            while (true) {
                val read = tar.readFullyOrEnd(header)
                if (read == 0) break
                if (read < 512) error("Invalid Alpine rootfs tar header.")
                if (header.all { it.toInt() == 0 }) break

                val name = header.tarString(0, 100)
                val prefix = header.tarString(345, 155)
                val path = listOf(prefix, name)
                    .filter(String::isNotBlank)
                    .joinToString("/")
                    .trimStart('/')
                val size = header.tarOctal(124, 12)
                val mode = header.tarOctal(100, 8).toInt()
                val type = header[156].toInt().toChar()
                val linkName = header.tarString(157, 100)
                val target = File(targetDirectory, path).canonicalFile
                val canonicalRoot = targetDirectory.canonicalFile
                if (!target.path.startsWith(canonicalRoot.path)) {
                    error("Refusing to extract path outside Alpine rootfs: $path")
                }

                when (type) {
                    '0', '\u0000' -> {
                        progressReporter.onEntry(path)
                        target.parentFile?.mkdirs()
                        target.outputStream().use { output ->
                            tar.copyExactlyTo(output, size, progressReporter::onBytes)
                        }
                        target.applyTarMode(mode)
                    }
                    '5' -> {
                        target.mkdirs()
                        target.applyTarMode(mode)
                    }
                    '2' -> {
                        target.parentFile?.mkdirs()
                        runCatching {
                            java.nio.file.Files.deleteIfExists(target.toPath())
                            java.nio.file.Files.createSymbolicLink(
                                target.toPath(),
                                java.nio.file.Paths.get(linkName),
                            )
                        }.onFailure {
                            File(target.parentFile, target.name).writeText(linkName)
                        }
                    }
                    else -> {
                        tar.skipExactly(size)
                    }
                }
                tar.skipPadding(size)
            }
        }
        progressReporter.finish()
    }

    private fun InputStream.readFullyOrEnd(buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = read(buffer, offset, buffer.size - offset)
            if (read == -1) return offset
            offset += read
        }
        return offset
    }

    private fun InputStream.copyExactlyTo(
        output: java.io.OutputStream,
        byteCount: Long,
        onBytesCopied: (Int) -> Unit = {},
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = byteCount
        while (remaining > 0) {
            val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read == -1) error("Unexpected end of Alpine rootfs tar entry.")
            output.write(buffer, 0, read)
            onBytesCopied(read)
            remaining -= read
        }
    }

    private fun InputStream.skipExactly(byteCount: Long) {
        var remaining = byteCount
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped <= 0) {
                if (read() == -1) error("Unexpected end of Alpine rootfs tar entry.")
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    private fun InputStream.skipPadding(size: Long) {
        val padding = (512 - (size % 512)) % 512
        if (padding > 0) skipExactly(padding)
    }

    private fun ByteArray.tarString(
        offset: Int,
        length: Int,
    ): String {
        val end = (offset until offset + length)
            .firstOrNull { this[it].toInt() == 0 }
            ?: (offset + length)
        return copyOfRange(offset, end).toString(Charsets.UTF_8).trim()
    }

    private fun ByteArray.tarOctal(
        offset: Int,
        length: Int,
    ): Long =
        tarString(offset, length).trim().ifBlank { "0" }.toLong(8)

    private fun File.applyTarMode(mode: Int) {
        setReadable(true, mode and 0b100_000_000 == 0)
        setWritable(true, mode and 0b010_000_000 == 0)
        if (mode and 0b001_000_000 != 0) setExecutable(true, false)
    }

    private fun copyDirectory(
        source: File,
        target: File,
    ) {
        if (source.isDirectory) {
            target.mkdirs()
            source.listFiles().orEmpty().forEach { child ->
                copyDirectory(child, File(target, child.name))
            }
        } else {
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = true)
        }
    }

    private fun copyDirectoryWithoutSymbolicLinks(
        source: File,
        target: File,
    ) {
        if (Files.isSymbolicLink(source.toPath())) return
        if (source.isDirectory) {
            require(target.mkdirs() || target.isDirectory) {
                "Unable to create Alpine mirror directory: ${target.path}"
            }
            source.listFiles().orEmpty().forEach { child ->
                copyDirectoryWithoutSymbolicLinks(child, File(target, child.name))
            }
        } else if (source.isFile) {
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = true)
        }
    }

    private fun isSupportedAbi(): Boolean = alpineHostAbiFolder().isNotEmpty()

    private fun unsupportedAbiState(): LocalRuntimeSetupState =
        LocalRuntimeSetupState(
            runtimeId = id,
            issue = LocalRuntimeIssue.UnsupportedAbi,
            detail = "Alpine runtime currently supports arm64-v8a and x86_64 devices. Device ABIs: ${Build.SUPPORTED_ABIS.joinToString()}",
        )

    private fun ensureWorkspace() {
        runtimeRoot.mkdirs()
        workspaceDir.mkdirs()
        hostTmpDir.mkdirs()
        ensureKimiAcpSessionDirectories()
    }

    private fun ensureGuestNetworkConfig() {
        val resolvConf = File(rootfsDir, "etc/resolv.conf")
        val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
        val activeDnsServers = runCatching {
            connectivityManager
                .getLinkProperties(connectivityManager.activeNetwork)
                ?.dnsServers
                .orEmpty()
                .mapNotNull { it.hostAddress?.substringBefore('%') }
        }.getOrDefault(emptyList())
        val resolverConfiguration = (
            activeDnsServers + listOf("223.5.5.5", "180.76.76.76", "1.1.1.1", "8.8.8.8")
        ).distinct().joinToString(separator = "\n", postfix = "\noptions timeout:2 attempts:2\n") {
            "nameserver $it"
        }
        if (resolvConf.readTextSafe() != resolverConfiguration) {
            resolvConf.parentFile?.mkdirs()
            resolvConf.writeText(resolverConfiguration)
            resolvConf.setReadable(true, false)
            resolvConf.setWritable(true, true)
        }
        val apkWorld = File(rootfsDir, "lib/apk/db/world")
        if (!apkWorld.exists()) {
            apkWorld.parentFile?.mkdirs()
            apkWorld.writeText("")
            apkWorld.setReadable(true, false)
            apkWorld.setWritable(true, true)
        }
    }

    private suspend fun verifyPackageProfile(profileId: String): Boolean {
        val command = when (profileId) {
            "python" -> "python3 --version && pip3 --version && virtualenv --version"
            "node" -> "node --version && npm --version"
            "git_search" -> "git --version && rg --version"
            "ssh" -> "ssh -V"
            else -> return false
        }
        val result = JSONObject(executeCommand(command, homeDirectory, 30_000L))
        return result.optBoolean("ok")
    }

    private fun nextRunId(): String =
        "run-${System.currentTimeMillis()}-${nextRunId.getAndIncrement()}-${UUID.randomUUID().toString().take(8)}"

    private fun snapshot(
        run: AlpineRun,
        tailBytes: Int,
    ): String {
        val process = run.process
        val running = process?.isAlive == true
        val exitCode = if (!running && process != null) process.exitValueSafe() else JSONObject.NULL
        val status = when {
            running -> "running"
            run.cancelled -> "cancelled"
            exitCode == 0 -> "completed"
            else -> "failed"
        }
        return JSONObject().apply {
            put("ok", status == "completed" || status == "running")
            put("runtime", id.storageValue)
            put("run_id", run.runId)
            put("command", run.command)
            put("working_directory", run.workingDirectory)
            put("status", status)
            put("running", running)
            put("completed", !running)
            put("stdout", run.stdoutFile.tailText(tailBytes))
            put("stderr", run.stderrFile.tailText(tailBytes))
            put("stdout_bytes", run.stdoutFile.lengthSafe())
            put("stderr_bytes", run.stderrFile.lengthSafe())
            put("exit_code", exitCode)
            put("err", -1)
            put("duration_ms", System.currentTimeMillis() - run.startedAtMillis)
            if (status == "failed") put("errmsg", "Alpine command failed.")
            if (status == "cancelled") put("errmsg", "Stopped by user.")
        }.toString()
    }

    private fun setupError(
        command: String,
        workingDirectory: String,
        setup: LocalRuntimeSetupState,
    ): String = JSONObject().apply {
        put("ok", false)
        put("runtime", id.storageValue)
        put("command", command)
        put("working_directory", workingDirectory)
        put("stdout", "")
        put("stderr", "")
        put("exit_code", -1)
        put("err", -1)
        put("errmsg", setup.detail.ifBlank { "Alpine runtime is not ready." })
        put("setup_issue", setup.issue.name)
    }.toString()

    private fun commandError(
        command: String,
        workingDirectory: String,
        runId: String = "",
        message: String,
    ): String = JSONObject().apply {
        put("ok", false)
        put("runtime", id.storageValue)
        if (runId.isNotBlank()) put("run_id", runId)
        put("command", command)
        put("working_directory", workingDirectory)
        put("stdout", "")
        put("stderr", "")
        put("exit_code", -1)
        put("err", -1)
        put("errmsg", message)
    }.toString()

    private fun invalidArguments(message: String): String =
        JSONObject().put("ok", false).put("errmsg", message).toString()

    private fun resolveTailBytes(arguments: JSONObject): Int =
        arguments.optInt("tail_bytes", arguments.optInt("tailBytes", AlpineDefaultTailBytes))
            .coerceIn(1, AlpineMaxTailBytes)

    private fun File.tailText(maxBytes: Int): String {
        if (!isFile) return ""
        val bytes = readBytes()
        val start = (bytes.size - maxBytes).coerceAtLeast(0)
        return String(bytes.copyOfRange(start, bytes.size), Charsets.UTF_8)
    }

    private fun File.readTextSafe(): String =
        runCatching { readText() }.getOrDefault("")

    private fun File.lengthSafe(): Long =
        runCatching { length() }.getOrDefault(0L)

    private fun File.existsNoFollow(): Boolean =
        Files.exists(toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun Process.exitValueSafe(): Int =
        runCatching { exitValue() }.getOrDefault(-1)

    private data class AlpineRun(
        val runId: String,
        val command: String,
        val workingDirectory: String,
        val startedAtMillis: Long,
        val stdoutFile: File,
        val stderrFile: File,
        @Volatile var process: Process? = null,
        @Volatile var cancelled: Boolean = false,
    )

    private object AlpineRunReaper {
        fun watch(
            run: AlpineRun,
            runs: ConcurrentHashMap<String, AlpineRun>,
        ) {
            Thread(
                {
                    runCatching { run.process?.waitFor() }
                    Thread.sleep(5 * 60 * 1000L)
                    if (run.process?.isAlive != true) {
                        runs.remove(run.runId, run)
                    }
                },
                "aether-alpine-run-${run.runId}",
            ).apply {
                isDaemon = true
                start()
            }
        }
    }

    companion object {
        val AlpinePackageProfiles: Map<String, List<String>> = mapOf(
            "python" to listOf("python3", "py3-pip", "py3-virtualenv"),
            "node" to listOf("nodejs", "npm"),
            "git_search" to listOf("git", "ripgrep"),
            "ssh" to listOf("openssh-client"),
        )
    }

    private class ExtractionProgressReporter(
        private val onProgress: (AlpineSetupProgress) -> Unit,
    ) {
        private var totalBytes = 0L
        private var sampledBytes = 0L
        private var sampledAtMillis = System.currentTimeMillis()
        private var lastEntryAtMillis = 0L

        fun onEntry(path: String) {
            val now = System.currentTimeMillis()
            if (lastEntryAtMillis == 0L || now - lastEntryAtMillis >= 120L) {
                lastEntryAtMillis = now
                onProgress(
                    AlpineSetupProgress(
                        activity = AlpineSetupActivity.Extracting,
                        output = "Extracting $path\n",
                    )
                )
            }
        }

        fun onBytes(byteCount: Int) {
            totalBytes += byteCount
            val now = System.currentTimeMillis()
            val elapsed = now - sampledAtMillis
            if (elapsed < 400L) return
            val bytesPerSecond = ((totalBytes - sampledBytes) * 1_000L / elapsed).coerceAtLeast(0L)
            sampledBytes = totalBytes
            sampledAtMillis = now
            onProgress(
                AlpineSetupProgress(
                    activity = AlpineSetupActivity.Extracting,
                    bytesPerSecond = bytesPerSecond,
                )
            )
        }

        fun finish() {
            val megabytes = totalBytes / (1024L * 1024L)
            onProgress(
                AlpineSetupProgress(
                    activity = AlpineSetupActivity.Extracting,
                    output = "Finished extracting the Alpine root filesystem ($megabytes MB).\n",
                )
            )
        }
    }

    private class NetworkRateSampler(
        private val onRate: (Long) -> Unit,
    ) {
        @Volatile
        private var running = false
        private var thread: Thread? = null

        fun start() {
            val initialBytes = TrafficStats.getUidRxBytes(android.os.Process.myUid())
            if (initialBytes == TrafficStats.UNSUPPORTED.toLong()) return
            running = true
            thread = Thread(
                {
                    var previousBytes = initialBytes
                    var previousAtMillis = System.currentTimeMillis()
                    runCatching {
                        while (running) {
                            Thread.sleep(AlpineNetworkRateWindowMillis)
                            val now = System.currentTimeMillis()
                            val currentBytes = TrafficStats.getUidRxBytes(android.os.Process.myUid())
                            val elapsed = now - previousAtMillis
                            if (currentBytes >= previousBytes && elapsed > 0L) {
                                onRate((currentBytes - previousBytes) * 1_000L / elapsed)
                            }
                            previousBytes = currentBytes
                            previousAtMillis = now
                        }
                    }
                },
                "aether-alpine-download-rate",
            ).apply {
                isDaemon = true
                start()
            }
        }

        fun stop() {
            running = false
            thread?.interrupt()
            thread = null
        }
    }
}

private data class RootfsAsset(
    val path: String,
    val compressed: Boolean,
)

data class AlpineTerminalLaunchSpec(
    val executable: String,
    val arguments: Array<String>,
    val environment: Array<String>,
    val workingDirectory: String,
)

enum class AlpineSetupActivity {
    None,
    Extracting,
    Downloading,
    Installing,
}

data class AlpineSetupProgress(
    val activity: AlpineSetupActivity = AlpineSetupActivity.None,
    val bytesPerSecond: Long = 0L,
    val progressPercent: Int? = null,
    val output: String = "",
)

enum class EverMeBindingPhase {
    Idle,
    Starting,
    AwaitingApproval,
    Installing,
    Bound,
    Failed,
}

data class EverMeBindingState(
    val phase: EverMeBindingPhase = EverMeBindingPhase.Idle,
    val verificationUrl: String = "",
    val userCode: String = "",
    internal val deviceCode: String = "",
    val expiresAtMillis: Long = 0L,
    val email: String = "",
    val detail: String = "",
) {
    val isWorking: Boolean
        get() = phase == EverMeBindingPhase.Starting || phase == EverMeBindingPhase.Installing

    val isBound: Boolean
        get() = phase == EverMeBindingPhase.Bound
}

internal class AlpinePackageInstallProgressTracker {
    private val outputTail = StringBuilder()
    private var activity = AlpineSetupActivity.Downloading
    private var bytesPerSecond = 0L
    private var progressPercent: Int? = null

    @Synchronized
    fun onOutput(output: String): AlpineSetupProgress {
        outputTail.append(output)
        if (outputTail.length > MaxTrackedOutputChars) {
            outputTail.delete(0, outputTail.length - MaxTrackedOutputChars)
        }
        ApkInstallProgressRegex.findAll(outputTail).lastOrNull()?.let { match ->
            val completed = match.groupValues[1].toIntOrNull() ?: 0
            val total = match.groupValues[2].toIntOrNull() ?: 0
            activity = AlpineSetupActivity.Installing
            progressPercent = if (total > 0) {
                (completed * 100 / total).coerceIn(0, 100)
            } else {
                null
            }
        }
        return snapshot(output)
    }

    @Synchronized
    fun onRate(rate: Long): AlpineSetupProgress {
        bytesPerSecond = rate.coerceAtLeast(0L)
        return snapshot()
    }

    private fun snapshot(output: String = ""): AlpineSetupProgress =
        AlpineSetupProgress(
            activity = activity,
            bytesPerSecond = bytesPerSecond,
            progressPercent = progressPercent,
            output = output,
        )

    private companion object {
        const val MaxTrackedOutputChars = 8_192
        val ApkInstallProgressRegex = Regex(
            """\((\d+)/(\d+)\)\s+(?:Installing|Upgrading|Downgrading|Replacing)\b""",
            RegexOption.IGNORE_CASE,
        )
    }
}

internal data class AlpineWorkspaceHostPath(
    val guestPath: String,
    val hostFile: File,
)
