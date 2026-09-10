package kira.ditto.upa

enum class UpaRiskLevel {
    Block,
    Warn,
    Info,
}

data class UpaRiskFinding(
    val level: UpaRiskLevel,
    val code: String,
    val args: List<String> = emptyList(),
)

data class UpaInstallPreview(
    val sourceRaw: String,
    val source: UpaInstallRef,
    val manifest: UpaManifest,
    val rawJson: String,
    val fileNames: List<String>,
    val findings: List<UpaRiskFinding>,
) {
    val blocked: Boolean get() = findings.any { it.level == UpaRiskLevel.Block }
}

private val SecretFileName = Regex(
    """(?i)^(?:\.env(?:\..*)?|\.secrets?|\.secret_key|.*\.(?:key|pem|p12|jks|keystore)|\.qweather.*|\.amap.*|credentials(?:\..*)?|id_rsa(?:\..*)?)$""",
)
private val SecretAssignment = Regex(
    """(?i)(?:api[_-]?key|secret|token|password)\s*["']?\s*[:=]\s*["'][A-Za-z0-9/+=._-]{16,}["']""",
)
private val OpenAiStyleKey = Regex("""(?<![A-Za-z0-9])sk-[A-Za-z0-9]{16,}""")
private val ScriptFile = Regex("""(?i)\.(?:py|js|mjs|cjs|sh|bat|ps1|exe|so|dll)$""")
private val ExecutablePluginFile = Regex("""(?i)\.(?:dex|apk|so|jar|class|bin)$""")

val UpaAllowedInstallHosts = setOf(
    "github.com",
    "raw.githubusercontent.com",
    "api.github.com",
    "codeload.github.com",
    "cdn.jsdelivr.net",
    "fastly.jsdelivr.net",
    "gcore.jsdelivr.net",
    "localhost",
    "127.0.0.1",
)

fun upaPackageFileCandidateUrls(source: UpaInstallRef, relativePath: String): List<String> {
    val relative = relativePath.trim().trimStart('/')
    if (relative.isBlank()) return emptyList()
    return when (source.kind) {
        UpaSourceKind.Github -> {
            val refs = if (source.ref.isBlank()) listOf("main", "master") else listOf(source.ref)
            refs.flatMap { ref ->
                listOf(
                    "https://cdn.jsdelivr.net/gh/${source.owner}/${source.repo}@$ref/$relative",
                    "https://raw.githubusercontent.com/${source.owner}/${source.repo}/$ref/$relative",
                    "https://api.github.com/repos/${source.owner}/${source.repo}/contents/$relative?ref=$ref",
                )
            }.distinct()
        }
        UpaSourceKind.Url -> emptyList()
        UpaSourceKind.Local -> emptyList()
    }
}

fun upaManifestCandidateUrls(
    source: UpaInstallRef,
    commitSha: String = "",
): List<String> {
    return when (source.kind) {
        UpaSourceKind.Github -> {
            val relative = source.path.trim('/').let { path ->
                when {
                    path.isBlank() -> "upa.json"
                    path.endsWith("upa.json") -> path
                    else -> "$path/upa.json"
                }
            }
            val sha = commitSha.trim().lowercase().takeIf { GithubCommitSha.matches(it) }
            val refs = if (source.ref.isBlank()) listOf("main", "master") else listOf(source.ref)
            buildList {
                if (sha != null) {
                    add("https://cdn.jsdelivr.net/gh/${source.owner}/${source.repo}@$sha/$relative")
                    add("https://gcore.jsdelivr.net/gh/${source.owner}/${source.repo}@$sha/$relative")
                    add("https://fastly.jsdelivr.net/gh/${source.owner}/${source.repo}@$sha/$relative")
                }
                refs.forEach { ref ->
                    add("https://cdn.jsdelivr.net/gh/${source.owner}/${source.repo}@$ref/$relative")
                    add("https://gcore.jsdelivr.net/gh/${source.owner}/${source.repo}@$ref/$relative")
                    add("https://fastly.jsdelivr.net/gh/${source.owner}/${source.repo}@$ref/$relative")
                    add("https://raw.githubusercontent.com/${source.owner}/${source.repo}/$ref/$relative")
                    add("https://github.com/${source.owner}/${source.repo}/raw/$ref/$relative")
                    add("https://api.github.com/repos/${source.owner}/${source.repo}/contents/$relative?ref=$ref")
                }
            }.distinct()
        }
        UpaSourceKind.Url -> listOf(source.uri.trim())
        UpaSourceKind.Local -> emptyList()
    }
}

fun isVolatileCdnManifestUrl(url: String): Boolean {
    val match = JsdelivrGhRef.find(url) ?: return false
    return !GithubCommitSha.matches(match.groupValues[1].lowercase())
}

fun upaPackageListingUrls(source: UpaInstallRef): List<String> {
    if (source.kind != UpaSourceKind.Github) return emptyList()
    val refs = if (source.ref.isBlank()) listOf("main", "master") else listOf(source.ref)
    val dir = source.path.trim('/').removeSuffix("/upa.json")
    return refs.map { ref ->
        val suffix = if (dir.isBlank()) "" else "/$dir"
        "https://api.github.com/repos/${source.owner}/${source.repo}/contents$suffix?ref=$ref"
    }.distinct()
}

fun scanUpaPackage(
    manifest: UpaManifest,
    rawJson: String,
    fileNames: List<String> = emptyList(),
    sourceUrl: String = "",
    nowEpochMs: Long = 0L,
): List<UpaRiskFinding> = buildList {
    val host = hostFromUrl(sourceUrl)
    if (host.isNotBlank() && !isAllowedUpaInstallHost(host)) {
        add(UpaRiskFinding(UpaRiskLevel.Block, "source.host", listOf(host)))
    }
    if (sourceUrl.trim().startsWith("http://") && host !in setOf("localhost", "127.0.0.1")) {
        add(UpaRiskFinding(UpaRiskLevel.Block, "source.insecure"))
    }
    if (SecretAssignment.containsMatchIn(rawJson) || OpenAiStyleKey.containsMatchIn(rawJson)) {
        add(UpaRiskFinding(UpaRiskLevel.Block, "secret.manifest"))
    }
    fileNames.filter { SecretFileName.matches(it.substringAfterLast('/').substringAfterLast('\\')) }
        .forEach { name ->
            add(UpaRiskFinding(UpaRiskLevel.Block, "secret.file", listOf(name)))
        }
    if (manifest.tools.isNotEmpty() && manifest.templates.isEmpty() && manifest.surfaces.isEmpty()) {
        add(UpaRiskFinding(UpaRiskLevel.Warn, "protocol.no_ui"))
    }
    if (manifest.releasedAt.isBlank()) {
        add(UpaRiskFinding(UpaRiskLevel.Block, "protocol.released_at"))
    } else {
        val releasedAt = parseReleasedAtEpochMs(manifest.releasedAt)
        if (releasedAt == null) {
            add(UpaRiskFinding(UpaRiskLevel.Block, "protocol.released_at_invalid"))
        } else if (nowEpochMs > 0L && releasedAt > nowEpochMs + ReleasedAtFutureSkewMs) {
            add(UpaRiskFinding(UpaRiskLevel.Block, "protocol.released_at_future"))
        }
    }
    if (manifest.runtimes.html?.sandbox == "trusted") {
        add(UpaRiskFinding(UpaRiskLevel.Block, "perm.html_trusted"))
    }
    manifest.permissions.forEach { wire ->
        if (isForbiddenHostMutation(wire)) {
            add(UpaRiskFinding(UpaRiskLevel.Block, "perm.host_write", listOf(wire)))
        }
    }
    val guest = manifest.guestPermissionWires()
    val hostReads = manifest.hostReadPermissionWires()
    if (guest.isNotEmpty()) {
        add(UpaRiskFinding(UpaRiskLevel.Info, "perm.guest", listOf(guest.joinToString())))
    }
    if (hostReads.isNotEmpty()) {
        add(UpaRiskFinding(UpaRiskLevel.Info, "perm.host_read", listOf(hostReads.joinToString())))
    }
    if (manifest.permissions.contains(UpaPermission.Network.wire)) {
        add(UpaRiskFinding(UpaRiskLevel.Warn, "perm.network"))
    }
    if (manifest.permissions.contains(UpaPermission.HtmlSandbox.wire)) {
        add(UpaRiskFinding(UpaRiskLevel.Warn, "perm.html_sandbox"))
    }
    if (manifest.allowsEverMeTrajectory()) {
        add(UpaRiskFinding(UpaRiskLevel.Warn, "memory.everme_trajectory"))
    }
    if (manifest.mcp?.importIds?.isNotEmpty() == true) {
        add(UpaRiskFinding(UpaRiskLevel.Warn, "mcp.import", listOf(manifest.mcp.importIds.joinToString())))
    }
    manifest.agents.filter { endpoint ->
        val value = endpoint.endpoint.trim()
        value.startsWith("http://") || value.startsWith("https://")
    }.forEach { agent ->
        add(UpaRiskFinding(UpaRiskLevel.Warn, "agent.remote", listOf(agent.id)))
    }
    val scripts = fileNames.filter { ScriptFile.containsMatchIn(it) }
    if (scripts.isNotEmpty()) {
        add(UpaRiskFinding(UpaRiskLevel.Info, "files.scripts", listOf(scripts.joinToString())))
    }
    val executables = fileNames.filter { ExecutablePluginFile.containsMatchIn(it) }
    if (executables.isNotEmpty()) {
        add(UpaRiskFinding(UpaRiskLevel.Block, "files.executable", listOf(executables.joinToString())))
    }
    if (manifest.tools.isNotEmpty()) {
        add(UpaRiskFinding(UpaRiskLevel.Info, "tools.listed", listOf(manifest.tools.joinToString { it.name })))
    }
}

private const val ReleasedAtFutureSkewMs = 24L * 60L * 60L * 1000L
private val GithubCommitSha = Regex("^[0-9a-f]{7,40}$")
private val JsdelivrGhRef = Regex("""(?i)jsdelivr\.net/gh/[^/]+/[^/@]+@([^/]+)/""")

fun hostFromUrl(url: String): String {
    val trimmed = url.trim()
    val withoutScheme = trimmed.substringAfter("://", missingDelimiterValue = "")
    if (withoutScheme.isBlank()) return ""
    return withoutScheme.substringBefore('/').substringBefore(':').lowercase()
}

fun isAllowedUpaInstallHost(host: String): Boolean {
    val normalized = host.trim().lowercase().removePrefix("www.")
    return normalized in UpaAllowedInstallHosts ||
        normalized.endsWith(".jsdelivr.net") ||
        normalized.endsWith(".githubusercontent.com")
}
