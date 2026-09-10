package kira.ditto.data

import android.util.Base64
import kira.ditto.upa.UpaInstallPreview
import kira.ditto.upa.hostFromUrl
import kira.ditto.upa.isAllowedUpaInstallHost
import kira.ditto.upa.UpaManifest
import kira.ditto.upa.UpaManifestResult
import kira.ditto.upa.UpaRiskLevel
import kira.ditto.upa.allowsEverMeTrajectory
import kira.ditto.upa.UpaInstallRef
import kira.ditto.upa.UpaSourceKind
import kira.ditto.upa.inAppDisplayName
import kira.ditto.upa.inAppIcon
import kira.ditto.upa.isUpaVersionNewer
import kira.ditto.upa.isVolatileCdnManifestUrl
import kira.ditto.upa.parseReleasedAtEpochMs
import kira.ditto.upa.parseUpaInstallRef
import kira.ditto.upa.parseUpaManifest
import kira.ditto.upa.scanUpaPackage
import kira.ditto.upa.shouldUpdateUpaPlugin
import kira.ditto.upa.upaManifestCandidateUrls
import kira.ditto.runtime.AlpineRuntime
import kira.ditto.upa.upaPackageFileCandidateUrls
import kira.ditto.upa.upaPackageListingUrls
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

enum class UpaScanPhase {
    ParseSource,
    DownloadManifest,
    ParseManifest,
    ListFiles,
    ScanSecrets,
    ScanPermissions,
    ScanUi,
    ScanMcp,
    ScanScripts,
}

data class UpaScanProgress(
    val fraction: Float,
    val phase: UpaScanPhase,
    val host: String = "",
)

class UpaPluginInstaller(
    private val library: UpaPluginLibrary,
    private val alpineRuntime: AlpineRuntime? = null,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) {
    suspend fun preview(
        rawSource: String,
        includeListing: Boolean = true,
        commitSha: String = "",
        onProgress: suspend (UpaScanProgress) -> Unit = {},
    ): UpaInstallPreview = previewAll(rawSource, includeListing, commitSha, onProgress).first()

    suspend fun previewAll(
        rawSource: String,
        includeListing: Boolean = true,
        commitSha: String = "",
        onProgress: suspend (UpaScanProgress) -> Unit = {},
    ): List<UpaInstallPreview> = withContext(Dispatchers.IO) {
        onProgress(UpaScanProgress(0.04f, UpaScanPhase.ParseSource))
        val source = parseUpaInstallRef(rawSource) ?: error("来源不是 GitHub 仓库或 upa.json 地址")
        if (source.kind == UpaSourceKind.Local) {
            error("本机路径安装尚未开放，请使用 GitHub 来源")
        }
        val sha = commitSha.trim()
        onProgress(UpaScanProgress(0.08f, UpaScanPhase.ParseSource))
        val single = runCatching {
            previewOne(source, rawSource, includeListing, sha) { local ->
                onProgress(local.copy(fraction = 0.08f + local.fraction * 0.90f))
            }
        }.getOrNull()
        if (single != null) {
            onProgress(UpaScanProgress(1f, UpaScanPhase.ScanScripts))
            return@withContext listOf(single)
        }
        if (source.kind == UpaSourceKind.Github && source.path.isBlank()) {
            onProgress(UpaScanProgress(0.12f, UpaScanPhase.ListFiles))
            val dirs = listingNames(source)
                .filter { it.endsWith("/") }
                .map { it.trimEnd('/') }
                .filter { it.isNotBlank() && !it.startsWith(".") }
            val children = dirs.mapIndexedNotNull { index, dir ->
                val from = 0.12f + index * 0.86f / dirs.size.coerceAtLeast(1)
                val to = 0.12f + (index + 1) * 0.86f / dirs.size.coerceAtLeast(1)
                runCatching {
                    previewOne(source.copy(path = dir), rawSource, includeListing, sha) { local ->
                        onProgress(local.copy(fraction = from + (to - from) * local.fraction))
                    }
                }.getOrNull()
            }
            if (children.isNotEmpty()) {
                onProgress(UpaScanProgress(1f, UpaScanPhase.ScanScripts))
                return@withContext children
            }
            error(
                "该仓库根目录没有 upa.json，也没有发现子插件。请指定插件目录，例如 " +
                    "https://github.com/${source.owner}/${source.repo}/tree/main/插件名",
            )
        }
        val failed = runCatching {
            previewOne(source, rawSource, includeListing, sha) { local ->
                onProgress(local.copy(fraction = 0.12f + local.fraction * 0.86f))
            }
        }
        onProgress(UpaScanProgress(1f, UpaScanPhase.ScanScripts))
        throw failed.exceptionOrNull() ?: IllegalStateException("无法下载 upa.json")
    }

    private suspend fun previewOne(
        source: UpaInstallRef,
        rawSource: String,
        includeListing: Boolean,
        commitSha: String,
        onProgress: suspend (UpaScanProgress) -> Unit = {},
    ): UpaInstallPreview {
        val urls = upaManifestCandidateUrls(source, commitSha)
        if (urls.isEmpty()) error("无法从该来源构造下载地址")
        var fetchedUrl = ""
        var body = ""
        var best: UpaManifest? = null
        var lastError = "无法下载 upa.json"
        onProgress(UpaScanProgress(0.06f, UpaScanPhase.DownloadManifest))
        for ((index, url) in urls.withIndex()) {
            val start = 0.10f + 0.52f * index / urls.size.coerceAtLeast(1)
            val end = 0.10f + 0.52f * (index + 1f) / urls.size.coerceAtLeast(1)
            val host = hostFromUrl(url)
            onProgress(UpaScanProgress(start, UpaScanPhase.DownloadManifest, host))
            if (!isAllowedUpaInstallHost(host)) continue
            val result = getText(url) { read, total ->
                val frac = when {
                    total > 0L -> (read.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    else -> (read.toFloat() / 48_000f).coerceIn(0f, 0.92f)
                }
                onProgress(UpaScanProgress(start + (end - start) * frac, UpaScanPhase.DownloadManifest, host))
            }
            onProgress(UpaScanProgress(end, UpaScanPhase.ParseManifest, host))
            if (result.body == null) {
                lastError = result.error.ifBlank { "下载失败：$url" }
                continue
            }
            val decoded = decodeManifestBody(result.body)
            if (decoded == null) {
                lastError = "下载到的内容不是 upa.json：$url"
                continue
            }
            val parsed = parseUpaManifest(decoded)
            if (parsed is UpaManifestResult.Err) {
                lastError = parsed.message
                continue
            }
            val manifest = (parsed as UpaManifestResult.Ok).manifest
            if (best == null || isFresherManifest(manifest, best)) {
                fetchedUrl = url
                body = decoded
                best = manifest
            }
            if (!isVolatileCdnManifestUrl(url)) break
        }
        if (body.isBlank() || best == null) {
            val hint = if (source.kind == UpaSourceKind.Github && source.path.isBlank()) {
                "该仓库根目录没有 upa.json，请指定插件子目录，例如 github:${source.owner}/${source.repo}@main:amap-maps"
            } else {
                ""
            }
            error(listOf(lastError, hint).filter { it.isNotBlank() }.joinToString("。"))
        }
        val manifest = best
        onProgress(UpaScanProgress(0.72f, UpaScanPhase.ListFiles))
        val fileNames = if (includeListing) {
            listingNames(source).ifEmpty { listOf("upa.json") }
        } else {
            listOf("upa.json")
        }
        onProgress(UpaScanProgress(0.82f, UpaScanPhase.ScanSecrets))
        val findings = scanUpaPackage(
            manifest = manifest,
            rawJson = body,
            fileNames = fileNames,
            sourceUrl = fetchedUrl,
            nowEpochMs = System.currentTimeMillis(),
        )
        onProgress(UpaScanProgress(0.88f, UpaScanPhase.ScanPermissions))
        onProgress(UpaScanProgress(0.92f, UpaScanPhase.ScanUi))
        onProgress(UpaScanProgress(0.96f, UpaScanPhase.ScanMcp))
        onProgress(UpaScanProgress(1f, UpaScanPhase.ScanScripts))
        return UpaInstallPreview(
            sourceRaw = rawSource.trim(),
            source = source,
            manifest = manifest,
            rawJson = body,
            fileNames = fileNames,
            findings = findings,
        )
    }

    suspend fun checkUpdates(installed: List<InstalledUpaPlugin>): UpaPluginUpdateResult =
        supervisorScope {
            val shaJobs = ConcurrentHashMap<String, Deferred<String?>>()
            val outcomes = installed.map { plugin ->
                async(Dispatchers.IO) {
                    if (plugin.id == "health.apps.card") {
                        return@async PluginCheck.Current
                    }
                    val sourceRaw = plugin.installSource()
                    if (sourceRaw.isBlank()) {
                        return@async PluginCheck.Error(plugin.inAppName(), "没有安装来源")
                    }
                    val source = parseUpaInstallRef(sourceRaw)
                        ?: return@async PluginCheck.Error(plugin.inAppName(), "来源无法解析")
                    val shaKey = "${source.owner}/${source.repo}@${source.ref.ifBlank { "main" }}"
                    val sha = shaJobs.getOrPut(shaKey) {
                        this@supervisorScope.async(Dispatchers.IO) { resolveGithubSha(source) }
                    }.await()
                    val scanned = runCatching {
                        preview(sourceRaw, includeListing = false, commitSha = sha.orEmpty())
                    }.getOrElse { error ->
                        return@async PluginCheck.Error(
                            plugin.inAppName(),
                            error.message ?: "下载失败",
                        )
                    }
                    if (scanned.blocked) {
                        return@async PluginCheck.Error(plugin.inAppName(), "远程风险扫描未通过")
                    }
                    if (!shouldUpdateUpaPlugin(
                            localVersion = plugin.version,
                            localReleasedAt = plugin.releasedAt,
                            remoteVersion = scanned.manifest.version,
                            remoteReleasedAt = scanned.manifest.releasedAt,
                        )
                    ) {
                        return@async PluginCheck.Current
                    }
                    PluginCheck.Update(UpaPluginUpdate(plugin = plugin, preview = scanned))
                }
            }.awaitAll()
            UpaPluginUpdateResult(
                updates = outcomes.mapNotNull { (it as? PluginCheck.Update)?.update },
                errors = outcomes.mapNotNull { outcome ->
                    (outcome as? PluginCheck.Error)?.let { "${it.name}：${it.message}" }
                },
            )
        }

    suspend fun install(preview: UpaInstallPreview): InstalledUpaPlugin {
        if (preview.blocked) error("风险扫描未通过，不能安装")
        val plugin = InstalledUpaPlugin(
            id = preview.manifest.id,
            name = preview.manifest.name,
            version = preview.manifest.version,
            description = preview.manifest.description,
            source = preview.source.display(),
            installedAtEpochMs = System.currentTimeMillis(),
            everMeTrajectory = preview.manifest.allowsEverMeTrajectory(),
            toolNames = preview.manifest.tools.map { it.name },
            warningCount = preview.findings.count { it.level == UpaRiskLevel.Warn },
            displayName = preview.manifest.inAppDisplayName(),
            icon = preview.manifest.inAppIcon(),
            releasedAt = preview.manifest.releasedAt,
            sourceRaw = preview.sourceRaw,
        )
        val extraFiles = fetchPluginAssets(preview)
        val installed = library.install(plugin, preview.rawJson, extraFiles)
        installRendererPacks(preview.manifest.rendererPacks)
        runCatching {
            alpineRuntime?.publishKimiMcpConfig()
        }
        return installed
    }

    private suspend fun fetchPluginAssets(preview: UpaInstallPreview): Map<String, String> {
        val extra = mutableMapOf<String, String>()
        val names = preview.fileNames.ifEmpty {
            listOf("catalog.json", "ui/catalog.json")
        }
        names.forEach { name ->
            val relative = name.trim().trimStart('/')
            if (relative.isBlank() || relative.equals("upa.json", ignoreCase = true)) return@forEach
            if (relative.endsWith(".dex", true) || relative.endsWith(".apk", true) ||
                relative.endsWith(".so", true) || relative.endsWith(".jar", true)
            ) {
                return@forEach
            }
            val allowed = relative.endsWith(".json", true) ||
                relative.startsWith("ui/") ||
                relative.startsWith("catalogs/")
            if (!allowed) return@forEach
            val body = fetchFirst(upaPackageFileCandidateUrls(preview.source, relative)) ?: return@forEach
            extra[relative] = body
        }
        if (extra.keys.none { it.endsWith("catalog.json") }) {
            fetchFirst(upaPackageFileCandidateUrls(preview.source, "catalog.json"))?.let {
                extra["catalog.json"] = it
            }
        }
        return extra
    }

    private suspend fun installRendererPacks(packIds: List<String>) {
        val packsRoot = java.io.File(library.appContext.filesDir, "upa-packs")
        packIds.distinct().forEach { packId ->
            if (packId.isBlank() || packId in kira.ditto.a2ui.A2uiPackRegistry.builtinPacks) return@forEach
            val urls = listOf(
                "https://raw.githubusercontent.com/Timothy-kira/upa-plugin/main/packs/$packId/pack.json",
                "https://cdn.jsdelivr.net/gh/Timothy-kira/upa-plugin@main/packs/$packId/pack.json",
            )
            val packJson = fetchFirst(urls) ?: return@forEach
            val obj = runCatching { JSONObject(packJson) }.getOrNull() ?: return@forEach
            val catalogUrl = obj.optString("catalogUrl")
            val signature = obj.optString("signature")
            val catalogBody = when {
                catalogUrl.isNotBlank() && kira.ditto.a2ui.A2uiPackRegistry.isAllowedPackSource(catalogUrl) ->
                    fetchFirst(listOf(catalogUrl))
                else -> fetchFirst(
                    listOf(
                        "https://raw.githubusercontent.com/Timothy-kira/upa-plugin/main/packs/$packId/catalog.json",
                    ),
                )
            } ?: return@forEach
            if (signature.isNotBlank() &&
                !kira.ditto.a2ui.A2uiPackRegistry.verifyPack(
                    message = catalogBody.toByteArray(Charsets.UTF_8),
                    signatureHex = signature,
                )
            ) {
                return@forEach
            }
            val dir = java.io.File(packsRoot, packId)
            dir.mkdirs()
            java.io.File(dir, "pack.json").writeText(packJson)
            java.io.File(dir, "catalog.json").writeText(catalogBody)
        }
    }

    private suspend fun fetchFirst(urls: List<String>): String? {
        urls.forEach { url ->
            val body = getText(url).body
            if (!body.isNullOrBlank()) return body
        }
        return null
    }

    private suspend fun resolveGithubSha(source: UpaInstallRef): String? {
        if (source.kind != UpaSourceKind.Github) return null
        val ref = source.ref.ifBlank { "main" }
        val atom = getText("https://github.com/${source.owner}/${source.repo}/commits/$ref.atom").body
        GithubCommitInUrl.find(atom.orEmpty())?.groupValues?.get(1)?.let { return it }
        val api = getText("https://api.github.com/repos/${source.owner}/${source.repo}/commits/$ref").body
            ?: return null
        val sha = runCatching { JSONObject(api).optString("sha") }.getOrNull().orEmpty()
        return sha.takeIf { GithubShaValue.matches(it) }
    }

    private fun isFresherManifest(candidate: UpaManifest, current: UpaManifest): Boolean {
        if (isUpaVersionNewer(candidate.version, current.version)) return true
        if (isUpaVersionNewer(current.version, candidate.version)) return false
        val candidateTime = parseReleasedAtEpochMs(candidate.releasedAt) ?: 0L
        val currentTime = parseReleasedAtEpochMs(current.releasedAt) ?: 0L
        return candidateTime > currentTime
    }

    private suspend fun listingNames(source: UpaInstallRef): List<String> {
        for (url in upaPackageListingUrls(source)) {
            val body = getText(url).body ?: continue
            val names = parseGithubListing(body) ?: continue
            if (names.isNotEmpty()) return names
        }
        return emptyList()
    }

    private suspend fun getText(
        url: String,
        hops: Int = 0,
        onBytes: (suspend (read: Long, total: Long) -> Unit)? = null,
    ): UpaHttpText {
        if (hops > 3) return UpaHttpText(error = "重定向过多：$url")
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Aether-UPA/0.2")
                .header("Accept", "application/vnd.github+json, application/json, text/plain, application/atom+xml")
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                val location = response.header("Location").orEmpty()
                if (response.code in 300..399 && location.isNotBlank()) {
                    val resolved = resolveRedirect(url, location)
                        ?: return UpaHttpText(error = "无效重定向：$url")
                    val host = hostFromUrl(resolved)
                    if (!isAllowedUpaInstallHost(host)) {
                        return UpaHttpText(error = "拒绝重定向到 $host")
                    }
                    return getText(resolved, hops + 1, onBytes)
                }
                if (response.code == 404) {
                    return UpaHttpText(error = "未找到 $url")
                }
                if (!response.isSuccessful) {
                    return UpaHttpText(error = "HTTP ${response.code}：$url")
                }
                val responseBody = response.body ?: return UpaHttpText(error = "空响应：$url")
                val total = responseBody.contentLength()
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(4_096)
                var readTotal = 0L
                var lastReported = -1L
                responseBody.byteStream().use { stream ->
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        readTotal += read
                        if (readTotal > MaxManifestBytes) {
                            return UpaHttpText(error = "文件过大：$url")
                        }
                        bytes.write(buffer, 0, read)
                        if (
                            onBytes != null &&
                            (lastReported < 0L || readTotal - lastReported >= 2_048L || (total > 0L && readTotal >= total))
                        ) {
                            lastReported = readTotal
                            onBytes(readTotal, total)
                        }
                    }
                }
                UpaHttpText(body = String(bytes.toByteArray(), Charsets.UTF_8))
            }
        } catch (error: Exception) {
            UpaHttpText(error = error.message?.ifBlank { null } ?: "下载失败：$url")
        }
    }

    private fun resolveRedirect(from: String, location: String): String? {
        val base = from.toHttpUrlOrNull() ?: return location.takeIf {
            it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
        }
        return base.resolve(location)?.toString()
    }

    private fun decodeManifestBody(body: String): String? {
        val trimmed = body.trim()
        if (trimmed.startsWith("{")) {
            val obj = runCatching { JSONObject(trimmed) }.getOrNull()
            if (obj != null && obj.optString("encoding") == "base64" && obj.has("content")) {
                val encoded = obj.optString("content").replace("\\s".toRegex(), "")
                val decoded = runCatching {
                    String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
                }.getOrNull()
                return decoded?.takeIf { it.contains("\"upa\"") }
            }
            if (obj?.has("upa") == true) return trimmed
        }
        return trimmed.takeIf { it.contains("\"upa\"") }
    }

    private fun parseGithubListing(body: String): List<String>? {
        val trimmed = body.trim()
        if (!trimmed.startsWith("[")) return null
        val array = runCatching { JSONArray(trimmed) }.getOrNull() ?: return null
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val name = item.optString("name")
            val type = item.optString("type")
            when {
                name.isBlank() -> null
                type == "dir" -> "$name/"
                else -> name
            }
        }
    }

    companion object {
        private const val MaxManifestBytes = 512 * 1024
        private val GithubCommitInUrl = Regex("""github\.com/[^/]+/[^/]+/commit/([0-9a-f]{40})""")
        private val GithubShaValue = Regex("^[0-9a-f]{7,40}$")
    }
}

private sealed class PluginCheck {
    data class Update(val update: UpaPluginUpdate) : PluginCheck()
    data class Error(val name: String, val message: String) : PluginCheck()
    data object Current : PluginCheck()
}

data class UpaPluginUpdateResult(
    val updates: List<UpaPluginUpdate>,
    val errors: List<String> = emptyList(),
)

data class UpaPluginUpdate(
    val plugin: InstalledUpaPlugin,
    val preview: UpaInstallPreview,
)

private data class UpaHttpText(
    val body: String? = null,
    val error: String = "",
)
