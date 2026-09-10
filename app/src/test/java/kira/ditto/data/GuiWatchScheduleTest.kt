package kira.ditto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuiWatchScheduleTest {
    @Test
    fun parseWatchingIntervalUsesMinutesAndCoercesBelowOneMinute() {
        assertEquals(10L * 60_000L, parseWatchingIntervalMillis("GUI_TASK_WATCHING: 课 interval=10m"))
        assertEquals(5L * 60_000L, parseWatchingIntervalMillis("interval=5 minutes leftover"))
        assertEquals(60_000L, parseWatchingIntervalMillis("interval=5s"))
        assertEquals(DefaultWatchingIntervalMillis, parseWatchingIntervalMillis("GUI_TASK_WATCHING: 课"))
        assertEquals(15L * 60_000L, parseWatchingIntervalMillis("interval=*/15 * * * *"))
        assertEquals(60L * 60_000L, parseWatchingIntervalMillis("interval=1h"))
    }

    @Test
    fun watchTaskIdIsStableAndAgentCreated() {
        val task = buildGuiWatchScheduledTask(
            sessionId = "session-a",
            intervalMillis = 10L * 60_000L,
            nowMillis = 1_000L,
        )
        assertEquals("gui-watch-session-a", task.id)
        assertTrue(task.isGuiWatchTask())
        assertEquals(ScheduledTaskCreator.Agent, task.createdBy)
        assertEquals("session-a", task.sessionId)
        assertEquals("盯课", task.name)
        assertTrue(task.prompt.contains("dump_tree"))
        assertTrue(task.prompt.contains("listen_status"))
        assertTrue(task.prompt.contains("简介"))
        assertEquals(
            10L * 60_000L,
            (task.schedule as ScheduledTaskSchedule.Interval).intervalMillis,
        )
        assertFalse(
            ScheduledTask(id = "user-1", name = "x", prompt = "y", schedule = ScheduledTaskSchedule.Interval(60_000L))
                .isGuiWatchTask(),
        )
    }
}
