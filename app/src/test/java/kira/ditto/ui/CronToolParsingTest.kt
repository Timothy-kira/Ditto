package kira.ditto.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CronToolParsingTest {
    @Test
    fun parsesCronCreate() {
        val info = parseCronTool(
            toolName = "CronCreate",
            argumentsJson = """{"cron":"0 9 * * *","prompt":"check deploy","recurring":true}""",
        )!!
        assertEquals(CronToolKind.Create, info.kind)
        assertEquals("0 9 * * *", info.cron)
        assertEquals("check deploy", info.prompt)
        assertEquals(true, info.recurring)
    }

    @Test
    fun parsesAcpSchedulingTitles() {
        val scheduling = parseCronTool(
            toolName = "Scheduling cron 0 9 * * *",
            argumentsJson = """{"cron":"0 9 * * *","prompt":"check deploy"}""",
        )
        assertNotNull(scheduling)
        assertEquals(CronToolKind.Create, scheduling!!.kind)
        assertEquals(
            CronToolKind.Create,
            parseCronTool("Scheduling one-shot 30 14 2 9 *", """{"cron":"30 14 2 9 *","prompt":"once"}""")?.kind,
        )
        assertEquals(
            CronToolKind.List,
            parseCronTool("Listing scheduled cron jobs", "{}")?.kind,
        )
        assertEquals(
            CronToolKind.Delete,
            parseCronTool("Deleting cron ab12cd34", """{"id":"ab12cd34"}""")?.kind,
        )
        assertEquals(
            CronToolKind.Create,
            parseCronTool("Scheduled cron ab12cd34", """{"cron":"0 9 * * *","prompt":"x"}""")?.kind,
        )
    }

    @Test
    fun parsesCronListAndDelete() {
        assertEquals(CronToolKind.List, parseCronTool("CronList", "{}")?.kind)
        val deleted = parseCronTool("CronDelete", """{"id":"ab12cd34"}""")!!
        assertEquals(CronToolKind.Delete, deleted.kind)
        assertEquals("ab12cd34", deleted.jobId)
        assertNull(parseCronTool("Agent", "{}"))
        assertNull(parseCronTool("Read", """{"path":"/tmp"}"""))
    }

    @Test
    fun invocationHelper() {
        val create = ChatToolInvocation(id = "1", toolName = "CronCreate", argumentsJson = "{}")
        val scheduling = ChatToolInvocation(
            id = "3",
            toolName = "Scheduling cron 0 9 * * *",
            argumentsJson = """{"cron":"0 9 * * *","prompt":"nudge"}""",
        )
        val swarm = ChatToolInvocation(
            id = "2",
            toolName = "Launching agent swarm: x",
            argumentsJson = """{"items":["a"]}""",
        )
        assertTrue(create.isCronTool())
        assertTrue(create.isCollaborationCapsule())
        assertTrue(scheduling.isCronTool())
        assertTrue(scheduling.isCollaborationCapsule())
        assertFalse(swarm.isCronTool())
    }
}
