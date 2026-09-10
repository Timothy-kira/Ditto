package kira.ditto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KimiSessionCronStoreTest {
    @Test
    fun taggedJobIdSuppressesIdleFire() {
        val prompt = """<cron-fire jobId="abc12"><prompt>dump_tree</prompt></cron-fire>"""
        assertTrue(
            idleCronFireUsesAlarm(
                promptText = prompt,
                taggedJobIds = setOf("abc12"),
                tasks = emptyList(),
            ),
        )
        assertFalse(
            idleCronFireUsesAlarm(
                promptText = prompt,
                taggedJobIds = emptySet(),
                tasks = emptyList(),
            ),
        )
    }

    @Test
    fun datastoreJobIdOrPromptSuppressesIdleFire() {
        val task = ScheduledTask(
            id = "task-1",
            name = "ping",
            prompt = "dump_tree",
            schedule = ScheduledTaskSchedule.Interval(60_000L),
            kimiCronIds = listOf("abc12"),
        )
        assertTrue(
            idleCronFireUsesAlarm(
                promptText = """<cron-fire jobId="abc12"><prompt>other</prompt></cron-fire>""",
                taggedJobIds = emptySet(),
                tasks = listOf(task),
            ),
        )
        assertTrue(
            idleCronFireUsesAlarm(
                promptText = """<cron-fire jobId="zzzz"><prompt>dump_tree</prompt></cron-fire>""",
                taggedJobIds = emptySet(),
                tasks = listOf(task),
            ),
        )
    }

    @Test
    fun nestedWorkDirKeyCronFilesAreDiscovered() {
        val root = kotlin.io.path.createTempDirectory("kimi-cron-sessions").toFile()
        try {
            val cronDir = java.io.File(root, "wd_workspace_hash/sess-abc/cron")
            cronDir.mkdirs()
            java.io.File(cronDir, "ab12cd34.json").writeText(
                """{"id":"ab12cd34","cron":"0 9 * * *","prompt":"morning ping","createdAt":1}""",
            )
            val records = listKimiCronRecords(root)
            assertEquals(1, records.size)
            assertEquals("ab12cd34", records.single().id)
            assertEquals("sess-abc", records.single().kimiSessionId)
            assertEquals("morning ping", records.single().prompt)
            val resolved = resolveKimiSessionDir(root, "sess-abc")
            assertEquals(java.io.File(root, "wd_workspace_hash/sess-abc").canonicalPath, resolved?.canonicalPath)
        } finally {
            root.deleteRecursively()
        }
    }
}
