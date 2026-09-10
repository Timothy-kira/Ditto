package kira.ditto.data

import android.content.Context
import kira.ditto.browser.BrowserHistoryStore
import kira.ditto.runtime.AlpineRuntime
import kira.ditto.termux.TermuxBashTool
import java.io.File
import java.util.Locale

enum class AppStorageClearKind {
    Evidence,
    MarkdownImages,
    BrowserCache,
    BrowserHistory,
    AppCache,
    CommandHistory,
}

data class AppStorageUsage(
    val evidenceBytes: Long = 0L,
    val markdownImageBytes: Long = 0L,
    val browserHistoryBytes: Long = 0L,
    val geckoCacheBytes: Long = 0L,
    val appCacheBytes: Long = 0L,
    val commandHistoryBytes: Long = 0L,
    val chatDataBytes: Long = 0L,
    val autoCleanEnabled: Boolean = true,
    val retentionDays: Int = DefaultStaleCacheRetentionDays,
) {
    val managedBytes: Long =
        evidenceBytes + markdownImageBytes + browserHistoryBytes + geckoCacheBytes +
            appCacheBytes + commandHistoryBytes
}

class AppStorageManager(
    context: Context,
    private val alpineRuntime: AlpineRuntime,
    private val browserHistoryStore: BrowserHistoryStore,
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
    private val termuxBashTool = TermuxBashTool(appContext, AetherDiagnosticLogger.NoOp)

    var autoCleanEnabled: Boolean
        get() = prefs.getBoolean(KeyAutoClean, true)
        set(value) {
            prefs.edit().putBoolean(KeyAutoClean, value).apply()
        }

    var retentionDays: Int
        get() = prefs.getInt(KeyRetentionDays, DefaultStaleCacheRetentionDays)
            .coerceIn(MinStaleCacheRetentionDays, MaxStaleCacheRetentionDays)
        set(value) {
            prefs.edit()
                .putInt(
                    KeyRetentionDays,
                    value.coerceIn(MinStaleCacheRetentionDays, MaxStaleCacheRetentionDays),
                )
                .apply()
        }

    fun usage(): AppStorageUsage = AppStorageUsage(
        evidenceBytes = directorySize(evidenceDir()) + directorySize(aetherEvidenceDir()),
        markdownImageBytes = directorySize(markdownImagesDir()),
        browserHistoryBytes = fileSize(browserHistoryFile()),
        geckoCacheBytes = geckoDirectories().sumOf(::directorySize),
        appCacheBytes = appCacheBytes(),
        commandHistoryBytes = directorySize(alpineBashRunsDir()),
        chatDataBytes = chatDataBytes(),
        autoCleanEnabled = autoCleanEnabled,
        retentionDays = retentionDays,
    )

    fun pruneStale(commandHistoryRetentionHours: Int = DefaultOldCommandHistoryRetentionHours): AppStorageUsage {
        termuxBashTool.setManagedBashRunCleanupPolicy(
            enabled = autoCleanEnabled,
            retentionHours = commandHistoryRetentionHours,
        )
        if (autoCleanEnabled) {
            val cutoff = System.currentTimeMillis() - retentionDays * 24L * 60L * 60L * 1000L
            deleteOlderThan(markdownImagesDir(), cutoff)
            deleteOlderThan(evidenceShotsDir(), cutoff)
            deleteOlderThan(File(appContext.cacheDir, "asr"), cutoff)
            appContext.cacheDir.listFiles().orEmpty()
                .filter { it.name.startsWith("asr-chunk-") && it.lastModified() < cutoff }
                .forEach { it.delete() }
        }
        alpineRuntime.pruneManagedBashRuns(commandHistoryRetentionHours)
        return usage()
    }

    fun clear(kind: AppStorageClearKind) {
        when (kind) {
            AppStorageClearKind.Evidence -> {
                deleteRecursively(evidenceDir())
                deleteRecursively(aetherEvidenceDir())
            }
            AppStorageClearKind.MarkdownImages -> deleteRecursively(markdownImagesDir())
            AppStorageClearKind.BrowserCache -> Unit
            AppStorageClearKind.BrowserHistory -> browserHistoryStore.clear()
            AppStorageClearKind.AppCache -> {
                deleteRecursively(markdownImagesDir())
                appContext.cacheDir.listFiles().orEmpty()
                    .filter { it.name.startsWith("asr-chunk-") || it.name == "asr" }
                    .forEach(::deleteRecursively)
            }
            AppStorageClearKind.CommandHistory -> deleteRecursively(alpineBashRunsDir())
        }
    }

    private fun evidenceDir(): File = File(alpineRuntime.hostWorkspaceDirectory(), "reports")

    private fun evidenceShotsDir(): File = File(evidenceDir(), "evidence")

    private fun aetherEvidenceDir(): File = File(alpineRuntime.hostWorkspaceDirectory(), ".aether")

    private fun markdownImagesDir(): File = File(appContext.cacheDir, "markdown-images")

    private fun browserHistoryFile(): File = File(appContext.filesDir, "browser/history.json")

    private fun alpineBashRunsDir(): File =
        File(appContext.filesDir, "runtimes/alpine/rootfs/root/.aether/bash-runs")

    private fun geckoDirectories(): List<File> {
        val names = listOf("mozilla", "gv_files", "geckoview", "crash_reports")
        val files = appContext.filesDir.listFiles().orEmpty().toList() +
            appContext.cacheDir.listFiles().orEmpty().toList()
        return files.filter { file ->
            val name = file.name.lowercase(Locale.US)
            names.any { token -> name.contains(token) }
        }
    }

    private fun appCacheBytes(): Long {
        val markdown = markdownImagesDir()
        return directorySize(appContext.cacheDir) { file ->
            file != markdown && !file.absolutePath.startsWith(markdown.absolutePath + File.separator)
        }
    }

    private fun chatDataBytes(): Long {
        val datastore = File(appContext.filesDir, "datastore")
        val databases = File(appContext.filesDir, "databases")
        return directorySize(datastore) { file ->
            file.name.contains("aether_chat", ignoreCase = true) ||
                file.name.contains("chat", ignoreCase = true)
        } + directorySize(databases) { file ->
            file.name.contains("chat", ignoreCase = true) ||
                file.name.contains("aether", ignoreCase = true)
        }
    }

    companion object {
        private const val PrefsName = "aether_storage"
        private const val KeyAutoClean = "auto_clean_stale_caches"
        private const val KeyRetentionDays = "stale_cache_retention_days"
    }
}

const val DefaultStaleCacheRetentionDays = 7
const val MinStaleCacheRetentionDays = 1
const val MaxStaleCacheRetentionDays = 90

fun formatStorageBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB")
    var value = bytes.toDouble() / 1024.0
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit += 1
    }
    return if (value >= 10.0) {
        "${value.toInt()} ${units[unit]}"
    } else {
        String.format(Locale.US, "%.1f %s", value, units[unit])
    }
}

private fun fileSize(file: File): Long = if (file.isFile) file.length().coerceAtLeast(0L) else 0L

private fun directorySize(directory: File, include: (File) -> Boolean = { true }): Long {
    if (!directory.exists()) return 0L
    var total = 0L
    directory.walkTopDown().forEach { file ->
        if (file.isFile && include(file)) total += file.length().coerceAtLeast(0L)
    }
    return total
}

private fun deleteRecursively(file: File) {
    if (!file.exists()) return
    file.deleteRecursively()
}

private fun deleteOlderThan(directory: File, cutoffMillis: Long) {
    if (!directory.exists()) return
    directory.walkBottomUp().forEach { file ->
        if (file == directory) return@forEach
        val stale = !file.isDirectory && file.lastModified() < cutoffMillis
        val emptyDir = file.isDirectory && file.listFiles().isNullOrEmpty()
        if (stale || emptyDir) file.delete()
    }
}
