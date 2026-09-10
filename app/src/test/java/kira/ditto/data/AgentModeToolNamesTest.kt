package kira.ditto.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModeToolNamesTest {
    @Test
    fun matchesKimiAcpMcpPrefixedName() {
        assertTrue(isAgentModeDisplayToolName("agent_display"))
        assertTrue(isAgentModeDisplayToolName("mcp_agent_display_aether-agent-display"))
        assertTrue(isAgentModeDisplayToolName("mcp-agent-display"))
        assertTrue(isAgentModeDisplayToolName("Aether_Agent_Display"))
        assertFalse(isAgentModeDisplayToolName("bash"))
        assertFalse(isAgentModeDisplayToolName("aether_agent_mode_manage"))
        assertFalse(isAgentModeDisplayToolName("chrome"))
    }
}
