package kira.ditto.ui

import kira.ditto.data.InstalledSkill
import kira.ditto.data.McpServerConfig
import kira.ditto.data.McpTransportConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaDefaultsTest {
    @Test
    fun memoryPluginMatchIsEverMeOnly() {
        assertTrue(looksLikePersonaMemoryPlugin("everme"))
        assertTrue(looksLikePersonaMemoryPlugin("plugin-ever-me", "Memory"))
        assertTrue(looksLikePersonaMemoryPlugin("upa", "EverMe Memory"))
        assertFalse(looksLikePersonaMemoryPlugin("generic-memory", "Personal memory"))
        assertFalse(looksLikePersonaMemoryPlugin("weather", "Weather"))
    }

    @Test
    fun newPersonaDefaultsToEnabledEverMeSkillAndUpa() {
        val skills = listOf(
            InstalledSkill(
                id = "weather",
                name = "Weather",
                description = "",
                skillRootPath = "/skills/weather",
                skillMdPath = "/skills/weather/SKILL.md",
            ),
            InstalledSkill(
                id = "everme-memory",
                name = "EverMe",
                description = "Personal memory",
                skillRootPath = "/skills/everme",
                skillMdPath = "/skills/everme/SKILL.md",
            ),
            InstalledSkill(
                id = "everme-disabled",
                name = "EverMe offline",
                description = "",
                skillRootPath = "/skills/everme-off",
                skillMdPath = "/skills/everme-off/SKILL.md",
                isEnabled = false,
            ),
        )
        val servers = listOf(
            McpServerConfig(
                id = "upa-everme",
                displayName = "EverMe",
                transport = McpTransportConfig.UpaManifest(pluginId = "everme"),
            ),
            McpServerConfig(
                id = "browser",
                displayName = "Browser",
                transport = McpTransportConfig.UpaManifest(pluginId = "browser"),
            ),
        )
        assertEquals(listOf("everme-memory"), defaultPersonaMemorySkillIds(skills))
        assertEquals(listOf("upa-everme"), defaultPersonaMemoryMcpIds(servers))
    }
}
