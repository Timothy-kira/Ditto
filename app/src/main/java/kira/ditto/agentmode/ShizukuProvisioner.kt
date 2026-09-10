package kira.ditto.agentmode

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kira.ditto.agentmode.adb.AdbClient
import kira.ditto.agentmode.adb.AdbKey
import kira.ditto.agentmode.adb.AdbMdns
import kira.ditto.agentmode.adb.PreferenceAdbKeyStore
import java.io.File
import java.util.concurrent.TimeUnit
import kira.ditto.data.AetherDiagnosticLogger
import kira.ditto.data.AgentModeController
import kira.ditto.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

private const val BundledShizukuAsset = "shizuku.apk"
private const val ShizukuPackage = "moe.shizuku.privileged.api"
private const val LegacyShizukuPackage = "moe.shizuku.manager"
private const val BinderWaitMillis = 12_000L
private const val RootCommandTimeoutMillis = 20_000L

class ShizukuProvisioner(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val agentModeController: AgentModeController,
    private val diagnosticLogger: AetherDiagnosticLogger = AetherDiagnosticLogger.NoOp,
) {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        scope.launch { onBinderAlive() }
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        scope.launch(Dispatchers.IO) {
            if (isShizukuInstalled()) {
                ensureReady()
            } else {
                refreshAuthorization()
            }
        }
    }

    init {
        android.os.Handler(context.mainLooper).post {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
        }
    }

    fun isReady(): Boolean = isBinderReady()

    fun isInstalled(): Boolean = isShizukuInstalled()

    /** Starts Shizuku if it is already installed. Does not install or enable Agent Mode. */
    suspend fun ensureReady() = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!isShizukuInstalled()) {
                refreshAuthorization()
                return@withContext
            }
            if (isBinderReady()) {
                requestPermissionIfNeeded()
                refreshAuthorization()
                return@withContext
            }
            startShizukuServer()
            awaitBinder(BinderWaitMillis)
            if (isBinderReady()) {
                requestPermissionIfNeeded()
            }
            refreshAuthorization()
        }
    }

    /** After the user agrees: enable Agent Mode, install bundled Shizuku if needed, then start it. */
    suspend fun enableAndInstall() = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching { settingsRepository.enableAgentModeShizuku() }
            if (!isShizukuInstalled()) {
                installBundled()
            }
            if (!isShizukuInstalled()) {
                refreshAuthorization()
                return@withContext
            }
            if (!isBinderReady()) {
                startShizukuServer()
                awaitBinder(BinderWaitMillis)
            }
            if (isBinderReady()) {
                requestPermissionIfNeeded()
            }
            refreshAuthorization()
        }
    }

    fun promptUserInstall() {
        val apk = extractBundledApk() ?: return
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { error ->
                diagnosticLogger.event(
                    category = "shizuku",
                    event = "install_prompt_failed",
                    level = "warn",
                    details = mapOf("error" to (error.message ?: error.javaClass.simpleName)),
                )
            }
    }

    private suspend fun onBinderAlive() {
        val settings = runCatching { settingsRepository.settings.first() }.getOrNull()
        if (settings?.agentModeAuthorizationEnabled == true) {
            requestPermissionIfNeeded()
        }
        refreshAuthorization()
    }

    private suspend fun refreshAuthorization() {
        val settings = runCatching { settingsRepository.settings.first() }.getOrNull() ?: return
        agentModeController.refreshAuthorization(settings)
    }

    private fun isBinderReady(): Boolean =
        runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    private fun hasShizukuPermission(): Boolean =
        runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    private fun isShizukuInstalled(): Boolean =
        listOf(ShizukuPackage, LegacyShizukuPackage).any { packageName ->
            runCatching {
                context.packageManager.getPackageInfo(packageName, 0)
                true
            }.getOrDefault(false)
        }

    private fun installBundled() {
        val apk = extractBundledApk() ?: return
        val su = findSuPath()
        if (su.isNotBlank()) {
            val staged = File("/data/local/tmp/aether-shizuku.apk")
            val result = runRoot(
                su,
                "cp ${shellQuote(apk.absolutePath)} ${shellQuote(staged.absolutePath)} && " +
                    "chmod 644 ${shellQuote(staged.absolutePath)} && " +
                    "pm install -r ${shellQuote(staged.absolutePath)}",
            )
            diagnosticLogger.event(
                category = "shizuku",
                event = "root_install",
                details = mapOf(
                    "exit_code" to result.exitCode,
                    "output" to result.output.take(280),
                ),
            )
            if (isShizukuInstalled()) return
        }
        withContextMain { promptUserInstall() }
    }

    private fun extractBundledApk(): File? {
        val dest = File(File(context.cacheDir, "shizuku").apply { mkdirs() }, "shizuku.apk")
        if (dest.isFile && dest.length() > 1_000_000L) return dest
        val copied = runCatching {
            context.assets.open(BundledShizukuAsset).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest
        }.getOrNull()
        if (copied == null) {
            diagnosticLogger.event(
                category = "shizuku",
                event = "bundled_apk_missing",
                level = "warn",
            )
        }
        return copied?.takeIf { it.isFile && it.length() > 1_000_000L }
    }

    private fun startShizukuServer() {
        val command = shizukuStartCommand()
        if (command == null) {
            diagnosticLogger.event(
                category = "shizuku",
                event = "start_skipped",
                level = "warn",
                details = mapOf("reason" to "starter_missing"),
            )
            return
        }
        val su = findSuPath()
        if (su.isNotBlank()) {
            val result = runRoot(su, command)
            diagnosticLogger.event(
                category = "shizuku",
                event = "root_start",
                details = mapOf(
                    "starter" to command,
                    "exit_code" to result.exitCode,
                    "output" to result.output.take(280),
                ),
            )
            if (isBinderReady() || looksStarted(result.output) || result.exitCode == 0) {
                return
            }
        }
        val adbOutput = startViaLocalAdb(command)
        if (adbOutput != null) {
            diagnosticLogger.event(
                category = "shizuku",
                event = "adb_start",
                details = mapOf("output" to adbOutput.take(280)),
            )
            if (isBinderReady() || looksStarted(adbOutput)) {
                return
            }
        }
        val pairingPort = discoverPairingPort()
        val promptPairing = ShizukuStartPlanner.shouldPromptWirelessPairing(
            binderReady = isBinderReady(),
            silentStartSucceeded = false,
            adbWifiEnabled = isAdbWifiEnabled(),
            pairingPort = pairingPort,
        )
        if (promptPairing) {
            diagnosticLogger.event(
                category = "shizuku",
                event = "pairing_prompt",
                details = mapOf(
                    "adb_wifi_enabled" to isAdbWifiEnabled(),
                    "pairing_port" to pairingPort,
                ),
            )
            withContextMain { openShizukuManager() }
            return
        }
        diagnosticLogger.event(
            category = "shizuku",
            event = "start_silent_failed",
            level = "warn",
            details = mapOf(
                "has_su" to su.isNotBlank(),
                "starter" to command,
                "adb_wifi_enabled" to isAdbWifiEnabled(),
                "tcp_port" to (tcpAdbPort() ?: -1),
            ),
        )
    }

    private fun looksStarted(output: String): Boolean =
        output.contains("shizuku_server pid", ignoreCase = true) ||
            output.contains("starter begin", ignoreCase = true) ||
            output.contains("info: shizuku", ignoreCase = true)

    private fun startViaLocalAdb(command: String): String? {
        enableWirelessAdbIfPossible()
        val key = runCatching {
            AdbKey(
                PreferenceAdbKeyStore(context.getSharedPreferences("aether-adb", Context.MODE_PRIVATE)),
                "aether",
            )
        }.getOrElse { error ->
            diagnosticLogger.event(
                category = "shizuku",
                event = "adb_key_failed",
                level = "warn",
                details = mapOf("error" to (error.message ?: error.javaClass.simpleName)),
            )
            return null
        }
        val ports = linkedSetOf<Int>()
        tcpAdbPort()?.let { ports += it }
        ports += 5555
        tryAdbPorts(ports, key, command)?.let { return it }
        if (!isWifiEnabled()) return null
        val discovered = runCatching {
            AdbMdns(context, AdbMdns.TlsConnect).discover(4_000L)
        }.getOrDefault(-1)
        if (discovered > 0 && discovered !in ports) {
            return tryAdbPorts(setOf(discovered), key, command)
        }
        return null
    }

    private fun tryAdbPorts(
        ports: Set<Int>,
        key: AdbKey,
        command: String,
    ): String? {
        for (port in ports) {
            val output = runCatching {
                AdbClient("127.0.0.1", port, key).use { client ->
                    client.connect()
                    client.shellCommand(command)
                }
            }.getOrElse { error ->
                diagnosticLogger.event(
                    category = "shizuku",
                    event = "adb_connect_failed",
                    level = "warn",
                    details = mapOf(
                        "port" to port,
                        "error" to (error.message ?: error.javaClass.simpleName),
                    ),
                )
                null
            } ?: continue
            return output
        }
        return null
    }

    private fun enableWirelessAdbIfPossible() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching {
            val resolver = context.contentResolver
            Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 1)
            Settings.Global.putInt(resolver, "adb_wifi_enabled", 1)
        }
    }

    private fun tcpAdbPort(): Int? {
        val output = runProcess(listOf("getprop", "service.adb.tcp.port"), 1_500L).output.trim()
        return output.toIntOrNull()?.takeIf { it in 1..65535 }
    }

    private fun isAdbWifiEnabled(): Boolean =
        runCatching {
            Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1
        }.getOrDefault(false)

    private fun discoverPairingPort(): Int {
        if (!isWifiEnabled()) return 0
        return runCatching {
            AdbMdns(context, AdbMdns.TlsPairing).discover(1_500L)
        }.getOrDefault(0).coerceAtLeast(0)
    }

    private fun openShizukuManager() {
        val launchIntent = listOf(ShizukuPackage, LegacyShizukuPackage).firstNotNullOfOrNull { packageName ->
            context.packageManager.getLaunchIntentForPackage(packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: return
        runCatching { context.startActivity(launchIntent) }
            .onFailure { error ->
                diagnosticLogger.event(
                    category = "shizuku",
                    event = "open_manager_failed",
                    level = "warn",
                    details = mapOf("error" to (error.message ?: error.javaClass.simpleName)),
                )
            }
    }

    private fun isWifiEnabled(): Boolean =
        runCatching {
            context.getSystemService(android.net.wifi.WifiManager::class.java)?.isWifiEnabled == true
        }.getOrDefault(false)

    private fun shizukuStartCommand(): String? {
        val applicationInfo = listOf(ShizukuPackage, LegacyShizukuPackage).firstNotNullOfOrNull { packageName ->
            runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.getOrNull()
        } ?: return null
        val starter = resolveStarterBinary(applicationInfo) ?: return startScriptCommand()
        return "cp ${shellQuote(starter.absolutePath)} /data/local/tmp/aether_shizuku_starter && " +
            "chmod 700 /data/local/tmp/aether_shizuku_starter && " +
            "/data/local/tmp/aether_shizuku_starter --apk=${shellQuote(applicationInfo.sourceDir)}"
    }

    private fun resolveStarterBinary(applicationInfo: android.content.pm.ApplicationInfo): File? {
        val names = listOf("libshizuku.so", "libstarter.so")
        names.map { File(applicationInfo.nativeLibraryDir, it) }.firstOrNull { it.isFile }?.let { return it }
        return extractStarterFromApk(applicationInfo.sourceDir)
    }

    private fun extractStarterFromApk(apkPath: String): File? {
        val dest = File(File(context.cacheDir, "shizuku-starter").apply { mkdirs() }, "libshizuku.so")
        if (dest.isFile && dest.length() > 10_000L) return dest
        val zip = runCatching { java.util.zip.ZipFile(apkPath) }.getOrNull() ?: return null
        zip.use { archive ->
            for (abi in android.os.Build.SUPPORTED_ABIS) {
                val entry = archive.getEntry("lib/$abi/libshizuku.so")
                    ?: archive.getEntry("lib/$abi/libstarter.so")
                    ?: continue
                runCatching {
                    archive.getInputStream(entry).use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                if (dest.isFile && dest.length() > 10_000L) return dest
            }
        }
        return dest.takeIf { it.isFile && it.length() > 10_000L }
    }

    private fun startScriptCommand(): String? {
        val scriptCandidates = listOf(
            File("/storage/emulated/0/Android/data/$ShizukuPackage/start.sh"),
            File("/sdcard/Android/data/$ShizukuPackage/start.sh"),
            File("/storage/emulated/0/Android/data/$ShizukuPackage/files/start.sh"),
        )
        return scriptCandidates.firstOrNull { it.isFile }?.let { "sh ${shellQuote(it.absolutePath)}" }
    }

    private fun awaitBinder(timeoutMillis: Long = BinderWaitMillis) {
        if (isBinderReady()) return
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (isBinderReady()) return
            SystemClock.sleep(200)
        }
    }

    private fun requestPermissionIfNeeded() {
        if (!isBinderReady() || hasShizukuPermission()) return
        withContextMain {
            runCatching { Shizuku.requestPermission(4201) }
        }
    }

    private fun withContextMain(block: () -> Unit) {
        val handler = android.os.Handler(context.mainLooper)
        val latch = java.util.concurrent.CountDownLatch(1)
        handler.post {
            runCatching(block)
            latch.countDown()
        }
        runCatching { latch.await(4, TimeUnit.SECONDS) }
    }

    private fun findSuPath(): String {
        val candidates = listOf(
            "su",
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/debug_ramdisk/su",
        )
        for (path in candidates) {
            val result = runProcess(listOf(path, "-c", "id"), 400L)
            if (result.output.contains("uid=0")) {
                return path
            }
        }
        val which = runProcess(listOf("sh", "-c", "command -v su 2>/dev/null || true"), 1_500L)
            .output.lineSequence().firstOrNull()?.trim().orEmpty()
        if (which.isNotBlank()) {
            val result = runProcess(listOf(which, "-c", "id"), 1_500L)
            if (result.output.contains("uid=0")) return which
        }
        return ""
    }

    private fun runRoot(suPath: String, command: String): ProcessResult =
        runProcess(listOf(suPath, "-c", command), RootCommandTimeoutMillis)

    private fun runProcess(command: List<String>, timeoutMillis: Long): ProcessResult {
        val process = runCatching { ProcessBuilder(command).redirectErrorStream(true).start() }
            .getOrElse { throwable ->
                return ProcessResult(-1, throwable.message.orEmpty())
            }
        val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        if (!finished) {
            runCatching { process.destroyForcibly() }
        }
        val output = runCatching { process.inputStream.bufferedReader().readText() }.getOrDefault("")
        return ProcessResult(
            exitCode = if (finished) process.exitValue() else -1,
            output = output,
        )
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private data class ProcessResult(
        val exitCode: Int,
        val output: String,
    )
}