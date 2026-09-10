package kira.ditto.upa

import kotlin.test.Test
import kotlin.test.assertTrue

class UpaRiskScanTest {
    @Test
    fun blocksSecretsAndAllowsCleanWeatherManifest() {
        val clean = parseUpaManifest(
            """
            {
              "upa": "0.1",
              "id": "example.weather.card",
              "name": "QWeather Now",
              "version": "1.0.0",
              "releasedAt": "2026-08-24T10:00:00Z",
              "permissions": ["network"],
              "memory": { "everme": { "trajectory": true } },
              "surfaces": [{ "id": "card", "slot": "bubble", "tree": { "id": "root", "type": "group" } }],
              "templates": [{ "id": "weather.now", "surface": "card" }],
              "tools": [{ "name": "get_weather" }]
            }
            """.trimIndent(),
        )
        val manifest = (clean as UpaManifestResult.Ok).manifest
        val findings = scanUpaPackage(
            manifest = manifest,
            rawJson = """{"upa":"0.1","description":"key-holding proxy"}""",
            fileNames = listOf("upa.json", "mcp/server.py", "README.md"),
            sourceUrl = "https://raw.githubusercontent.com/Timothy-kira/upa-plugin/main/amap-maps/upa.json",
            nowEpochMs = parseReleasedAtEpochMs("2026-08-24T12:00:00Z")!!,
        )
        assertTrue(findings.none { it.level == UpaRiskLevel.Block })
        assertTrue(findings.any { it.code == "perm.network" })
        assertTrue(findings.any { it.code == "perm.guest" })
        assertTrue(findings.any { it.code == "memory.everme_trajectory" })
        assertTrue(findings.any { it.code == "files.scripts" })
    }

    @Test
    fun blocksMissingReleasedAtAndHostWrite() {
        val parsed = parseUpaManifest(
            """
            {
              "upa": "0.1",
              "id": "health.apps.card",
              "name": "Health Apps",
              "version": "1.0.0",
              "permissions": ["host.apps.read"],
              "surfaces": [{ "id": "card", "slot": "bubble", "tree": { "id": "root", "type": "group" } }],
              "templates": [{ "id": "health.apps", "surface": "card" }],
              "tools": [{ "name": "get_health_apps" }]
            }
            """.trimIndent(),
        )
        val manifest = (parsed as UpaManifestResult.Ok).manifest
        val findings = scanUpaPackage(
            manifest = manifest,
            rawJson = """{"upa":"0.1"}""",
            fileNames = listOf("upa.json"),
            sourceUrl = "https://raw.githubusercontent.com/Timothy-kira/upa-plugin/main/health-apps/upa.json",
            nowEpochMs = parseReleasedAtEpochMs("2026-08-24T12:00:00Z")!!,
        )
        assertTrue(findings.any { it.code == "protocol.released_at" && it.level == UpaRiskLevel.Block })
        assertTrue(findings.any { it.code == "perm.host_read" })
    }

    @Test
    fun rejectsHostMutationPermission() {
        val parsed = parseUpaManifest(
            """
            {
              "upa": "0.1",
              "id": "bad.host.write",
              "name": "Bad",
              "version": "1.0.0",
              "permissions": ["host.storage.write"]
            }
            """.trimIndent(),
        )
        val err = parsed as UpaManifestResult.Err
        assertTrue(err.message.contains("只允许只读"))
    }

    @Test
    fun blocksEmbeddedKeyAndSecretFilename() {
        val parsed = parseUpaManifest(
            """
            {
              "upa": "0.1",
              "id": "bad.plugin",
              "name": "Bad",
              "version": "1.0.0"
            }
            """.trimIndent(),
        )
        val manifest = (parsed as UpaManifestResult.Ok).manifest
        val findings = scanUpaPackage(
            manifest = manifest,
            rawJson = """{"api_key":"sk-abcdefghijklmnopqrstuvwxyz"}""",
            fileNames = listOf(".qweather_key"),
            sourceUrl = "http://evil.example/upa.json",
        )
        assertTrue(findings.any { it.code == "secret.manifest" && it.level == UpaRiskLevel.Block })
        assertTrue(findings.any { it.code == "secret.file" && it.level == UpaRiskLevel.Block })
        assertTrue(findings.any { it.code == "source.host" && it.level == UpaRiskLevel.Block })
        assertTrue(findings.any { it.code == "source.insecure" && it.level == UpaRiskLevel.Block })
    }

    @Test
    fun githubManifestUrlsPreferFreshHosts() {
        val source = parseUpaInstallRef("github:Timothy-kira/upa-plugin@main:amap-maps")!!
        val urls = upaManifestCandidateUrls(source)
        val githubRaw = urls.indexOfFirst { it.contains("github.com/") && it.contains("/raw/") }
        val jsdelivrMain = urls.indexOfFirst { it.contains("cdn.jsdelivr.net") && it.contains("@main/") }
        assertTrue(urls.any { it.contains("/amap-maps/upa.json") })
        assertTrue(githubRaw >= 0)
        assertTrue(jsdelivrMain >= 0)
        assertTrue(jsdelivrMain < githubRaw)
        val pinned = upaManifestCandidateUrls(source, commitSha = "47156b0")
        assertTrue(pinned.first().contains("@47156b0/"))
        assertTrue(!isVolatileCdnManifestUrl(pinned.first()))
        assertTrue(isVolatileCdnManifestUrl("https://cdn.jsdelivr.net/gh/a/b@main/upa.json"))
    }

    @Test
    fun blocksExecutableFilesInPluginZip() {
        val parsed = parseUpaManifest(
            """
            {
              "upa": "0.2",
              "id": "example.weather.card",
              "name": "Weather",
              "version": "1.0.0",
              "releasedAt": "2026-08-24T10:00:00Z"
            }
            """.trimIndent(),
        )
        val manifest = (parsed as UpaManifestResult.Ok).manifest
        val findings = scanUpaPackage(
            manifest = manifest,
            rawJson = """{"upa":"0.2"}""",
            fileNames = listOf("upa.json", "payload.dex"),
            sourceUrl = "https://raw.githubusercontent.com/Timothy-kira/upa-plugin/main/amap-maps/upa.json",
        )
        assertTrue(findings.any { it.code == "files.executable" && it.level == UpaRiskLevel.Block })
    }
}
