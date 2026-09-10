package kira.ditto.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentSkillManagerTest {
    @Test
    fun discoveredSkillIdsAreStableAndSourceSpecific() {
        val path = "/root/.agents/skills/review/SKILL.md"

        assertEquals(piDiscoveredSkillId(path), piDiscoveredSkillId("  $path "))
        assertEquals(34, piDiscoveredSkillId(path).length)
        org.junit.Assert.assertNotEquals(
            piDiscoveredSkillId(path),
            piDiscoveredSkillId("/workspace/.pi/skills/review/SKILL.md"),
        )
    }

    @Test
    fun keepsGitHubArchiveZipUrlAsDirectDownload() {
        val url = "https://github.com/owner/repository/archive/refs/heads/main.zip"

        val plan = resolveRemoteDownloadPlan(url)

        assertEquals(SkillInstallKind.RemoteZip, plan.kind)
        assertEquals(url, plan.downloadUrl)
    }

    @Test
    fun acceptsGitHubCodeloadZipUrl() {
        val url = "https://codeload.github.com/owner/repository/zip/refs/heads/main"

        val plan = resolveRemoteDownloadPlan(url)

        assertEquals(SkillInstallKind.RemoteZip, plan.kind)
        assertEquals(url, plan.downloadUrl)
    }

    @Test
    fun keepsGitHubTreeUrlWhenSubpathEndsWithZip() {
        val plan = resolveRemoteDownloadPlan(
            "https://github.com/owner/repository/tree/main/skills/example.zip",
        )

        assertEquals(SkillInstallKind.GitHub, plan.kind)
        assertEquals(
            "https://api.github.com/repos/owner/repository/zipball/main",
            plan.downloadUrl,
        )
        assertEquals("main", plan.ref)
        assertEquals("skills/example.zip", plan.subpath)
    }

}
