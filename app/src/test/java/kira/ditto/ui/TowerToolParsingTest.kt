package kira.ditto.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TowerToolParsingTest {
    @Test
    fun recognizesOfficialTowerTools() {
        listOf("TowerInit", "TowerPlan", "TowerSpawn", "TowerStatus", "TowerMerge").forEach { name ->
            assertTrue(name, isTowerToolName(name))
            assertTrue(
                name,
                ChatToolInvocation(id = name, toolName = name, argumentsJson = "{}").isTowerRelated(),
            )
        }
        assertFalse(isTowerToolName("AgentSwarm"))
        assertFalse(isTowerToolName("CronCreate"))
    }

    @Test
    fun rosterReadsSpawnedWorkersAndReviewers() {
        val worker = ChatToolInvocation(
            id = "s1",
            toolName = "TowerSpawn",
            argumentsJson = """{"name":"agent-build","kind":"worker","mission_id":"M1"}""",
            isRunning = true,
        )
        val reviewer = ChatToolInvocation(
            id = "s2",
            toolName = "TowerSpawn",
            argumentsJson = """{"name":"reviewer-a","kind":"reviewer","review_target":"feat/x"}""",
        )
        val status = ChatToolInvocation(
            id = "st",
            toolName = "TowerStatus",
            argumentsJson = "{}",
            outputJson = "all green",
        )
        val roster = parseTowerRoster(listOf(status, worker, reviewer))
        assertEquals(2, roster.size)
        assertEquals("agent-build", roster[0].name)
        assertEquals("worker", roster[0].kind)
        assertEquals("M1", roster[0].missionId)
        assertTrue(roster[0].running)
        assertEquals("reviewer-a", roster[1].name)
        assertEquals("feat/x", roster[1].reviewTarget)
    }

    @Test
    fun towerWorkerAgentJoinsRosterWithoutSwarmCapsule() {
        val invocation = ChatToolInvocation(
            id = "a1",
            toolName = "Launching tower-worker agent: M2",
            argumentsJson = """{"subagent_type":"tower-worker","description":"agent-docs","prompt":"write docs"}""",
        )
        assertTrue(invocation.isTowerRelated())
        assertFalse(invocation.isSubagentLaunch())
        val member = parseTowerRosterMember(invocation)!!
        assertEquals("agent-docs", member.name)
        assertEquals("write docs", member.instructions)
    }
}
