package kira.ditto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialCronToolsTest {
    @Test
    fun classifiesCanonicalAndAcpTitles() {
        assertEquals(OfficialCronKind.Create, classifyOfficialCronTool("CronCreate"))
        assertEquals(OfficialCronKind.Create, classifyOfficialCronTool("cron_create"))
        assertEquals(OfficialCronKind.Create, classifyOfficialCronTool("Scheduling cron 0 9 * * *"))
        assertEquals(OfficialCronKind.Create, classifyOfficialCronTool("Scheduling one-shot 0 9 1 1 *"))
        assertEquals(OfficialCronKind.List, classifyOfficialCronTool("Listing scheduled cron jobs"))
        assertEquals(OfficialCronKind.Delete, classifyOfficialCronTool("Deleting cron ab12"))
        assertEquals(OfficialCronKind.Delete, classifyOfficialCronTool("CronDelete"))
        assertNull(classifyOfficialCronTool("Agent"))
        assertNull(classifyOfficialCronTool("Read"))
    }

    @Test
    fun classifiesCreateFromCronArgumentsWhenTitleMentionsCron() {
        assertEquals(
            OfficialCronKind.Create,
            classifyOfficialCronTool(
                "cron helper",
                """{"cron":"*/10 * * * *","prompt":"ping"}""",
            ),
        )
        assertNull(
            classifyOfficialCronTool(
                "Read",
                """{"cron":"*/10 * * * *","prompt":"ping"}""",
            ),
        )
    }

    @Test
    fun keepsCanonicalNameWhenAcpReplacesTitle() {
        assertEquals(
            "CronCreate",
            preferStableOfficialCronToolName(
                incoming = "Scheduling cron 0 9 * * *",
                existing = "CronCreate",
            ),
        )
        assertEquals(
            "Scheduling cron 0 9 * * *",
            preferStableOfficialCronToolName(
                incoming = "Scheduling cron 0 9 * * *",
                existing = null,
            ),
        )
        assertEquals(
            "Agent",
            preferStableOfficialCronToolName(
                incoming = "",
                existing = "Agent",
            ),
        )
    }

    @Test
    fun extractsJobIdFromScheduledMessage() {
        assertEquals(
            "ab12cd34",
            extractOfficialCronJobId("""{"content":[{"type":"text","text":"Scheduled cron ab12cd34"}]}"""),
        )
        assertEquals(
            "job9",
            extractOfficialCronJobId("{}", """{"id":"job9"}"""),
        )
        assertEquals(
            "task-1",
            extractOfficialCronJobId("""{"id":"task-1"}"""),
        )
    }

    @Test
    fun processDeathReasonIsStable() {
        assertEquals("process_death", processDeathCancellationDetail()["reason"])
    }

    @Test
    fun officialIdleCronDoesNotStealCurrentChatOrAgentMode() {
        val spec = officialIdleCronResume()
        assertEquals(AppScheduledSessionId, spec.sessionId)
        assertEquals(false, spec.adoptAsCurrentSession)
        assertEquals(false, spec.agentModeEnabled)
        assertEquals("aether-scheduled", spec.sessionId)
    }

    @Test
    fun parsesCronListRecordsIncludingQuotedPrompts() {
        val output = """
            cron_jobs: 2
            id: ab12cd34
            cron: 0 9 * * *
            prompt: "check weather"
            ---
            id: ef56gh78
            cron: */15 * * * *
            prompt: "dump_tree\nand resume"
        """.trimIndent()
        val records = parseOfficialCronJobRecords(output)
        assertEquals(2, records.size)
        assertEquals("ab12cd34", records[0].id)
        assertEquals("0 9 * * *", records[0].cron)
        assertEquals("check weather", records[0].prompt)
        assertEquals("ef56gh78", records[1].id)
        assertEquals("dump_tree\nand resume", records[1].prompt)
    }

    @Test
    fun parsesCronListFromWrappedStdout() {
        val records = parseOfficialCronJobRecords(
            """{"stdout":"id: job9\ncron: 30 14 * * *\nprompt: \"ping\""}""",
        )
        assertEquals(1, records.size)
        assertEquals("job9", records[0].id)
        assertEquals("30 14 * * *", records[0].cron)
        assertEquals("ping", records[0].prompt)
    }

    @Test
    fun idleCronDefersWhileGuiDisplayOrTurnIsActive() {
        assertTrue(
            shouldDeferIdleCronResume(
                runningSessionIds = listOf("session-gui"),
                displayActive = false,
                lastAgentModeTurnSettledAtMillis = 0L,
                nowMillis = 10_000L,
            ),
        )
        assertTrue(
            shouldDeferIdleCronResume(
                runningSessionIds = emptyList(),
                displayActive = true,
                lastAgentModeTurnSettledAtMillis = 0L,
                nowMillis = 10_000L,
            ),
        )
        assertTrue(
            shouldDeferIdleCronResume(
                runningSessionIds = emptyList(),
                displayActive = false,
                lastAgentModeTurnSettledAtMillis = 9_000L,
                nowMillis = 10_000L,
            ),
        )
        assertFalse(
            shouldDeferIdleCronResume(
                runningSessionIds = listOf(AppScheduledSessionId),
                displayActive = false,
                lastAgentModeTurnSettledAtMillis = 1L,
                nowMillis = 20_000L,
            ),
        )
    }
}
