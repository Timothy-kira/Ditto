package kira.ditto.data

import java.time.LocalDateTime
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledTaskModelsTest {
    private val zoneId: ZoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun dailyScheduleChoosesNextConfiguredTime() {
        val task = ScheduledTask(
            id = "daily",
            name = "Daily",
            prompt = "Run",
            schedule = ScheduledTaskSchedule.Daily(
                timesMinutesOfDay = listOf(9 * 60, 17 * 60 + 30),
            ),
            createdAtMillis = millis("2026-05-16T08:00:00"),
        )

        val next = task.withNextRunAfter(
            afterMillis = millis("2026-05-16T10:00:00"),
            zoneId = zoneId,
        ).nextRunAtMillis

        assertEquals(millis("2026-05-16T17:30:00"), next)
    }

    @Test
    fun weeklyScheduleRollsToNextMatchingDay() {
        val task = ScheduledTask(
            id = "weekly",
            name = "Weekly",
            prompt = "Run",
            schedule = ScheduledTaskSchedule.Weekly(
                daysOfWeek = listOf(1, 5),
                minuteOfDay = 10 * 60,
            ),
            createdAtMillis = millis("2026-05-16T08:00:00"),
        )

        val next = task.withNextRunAfter(
            afterMillis = millis("2026-05-16T10:00:00"),
            zoneId = zoneId,
        ).nextRunAtMillis

        assertEquals(millis("2026-05-18T10:00:00"), next)
    }

    @Test
    fun intervalScheduleHonorsDailyActiveWindow() {
        val task = ScheduledTask(
            id = "interval",
            name = "Interval",
            prompt = "Run",
            schedule = ScheduledTaskSchedule.Interval(
                intervalMillis = 30 * 60 * 1000L,
                activeStartMinuteOfDay = 13 * 60,
                activeEndMinuteOfDay = 18 * 60,
            ),
            createdAtMillis = millis("2026-05-16T12:40:00"),
        )

        val next = task.withNextRunAfter(
            afterMillis = millis("2026-05-16T18:20:00"),
            zoneId = zoneId,
        ).nextRunAtMillis

        assertEquals(millis("2026-05-17T13:00:00"), next)
    }

    @Test
    fun parserAcceptsAgentScheduleJsonShapes() {
        val interval = parseScheduledTaskSchedule(
            JSONObject()
                .put("type", "interval")
                .put("interval_minutes", 30)
                .put("active_start_time", "13:00")
                .put("active_end_time", "18:00")
        ) as ScheduledTaskSchedule.Interval
        val daily = parseScheduledTaskSchedule(
            JSONObject()
                .put("type", "daily")
                .put("times", JSONArray().put("09:00").put("17:30"))
        ) as ScheduledTaskSchedule.Daily
        val weekly = parseScheduledTaskSchedule(
            JSONObject()
                .put("type", "weekly")
                .put("days_of_week", JSONArray().put("mon").put("fri"))
                .put("time", "10:00")
        ) as ScheduledTaskSchedule.Weekly

        assertEquals(30 * 60 * 1000L, interval.intervalMillis)
        assertEquals(13 * 60, interval.activeStartMinuteOfDay)
        assertEquals(18 * 60, interval.activeEndMinuteOfDay)
        assertEquals(listOf(9 * 60, 17 * 60 + 30), daily.timesMinutesOfDay)
        assertEquals(listOf(1, 5), weekly.daysOfWeek)
        assertEquals(10 * 60, weekly.minuteOfDay)
    }

    @Test
    fun disabledTaskClearsNextRun() {
        val task = ScheduledTask(
            id = "disabled",
            name = "Disabled",
            prompt = "Run",
            schedule = ScheduledTaskSchedule.Daily(timesMinutesOfDay = listOf(9 * 60)),
            isEnabled = false,
            nextRunAtMillis = millis("2026-05-16T09:00:00"),
        )

        assertTrue(task.withNextRunAfter(millis("2026-05-16T08:00:00"), zoneId).nextRunAtMillis == null)
    }

    @Test
    fun dueTaskKeepsStoredNextRunForReschedule() {
        val task = ScheduledTask(
            id = "due",
            name = "Due",
            prompt = "Run",
            schedule = ScheduledTaskSchedule.Daily(timesMinutesOfDay = listOf(20 * 60 + 30)),
            nextRunAtMillis = millis("2026-05-16T20:30:00"),
        )

        val refreshed = refreshScheduledTaskNextRuns(
            tasks = listOf(task),
            afterMillis = millis("2026-05-16T20:35:00"),
            zoneId = zoneId,
        ).single()

        assertEquals(millis("2026-05-16T20:30:00"), refreshed.nextRunAtMillis)
    }

    @Test
    fun intervalAndDailySchedulesMapToFiveFieldCron() {
        assertEquals(
            listOf("*/15 * * * *"),
            ScheduledTaskSchedule.Interval(intervalMillis = 15 * 60 * 1000L).toCronExpressions(),
        )
        assertEquals(
            listOf("0 */2 * * *"),
            ScheduledTaskSchedule.Interval(intervalMillis = 2 * 60 * 60 * 1000L).toCronExpressions(),
        )
        assertEquals(
            listOf("0 9 * * *", "30 17 * * *"),
            ScheduledTaskSchedule.Daily(timesMinutesOfDay = listOf(9 * 60, 17 * 60 + 30)).toCronExpressions(),
        )
        assertEquals(
            listOf("0 10 * * 1,5"),
            ScheduledTaskSchedule.Weekly(daysOfWeek = listOf(1, 5), minuteOfDay = 10 * 60).toCronExpressions(),
        )
    }

    @Test
    fun kimiCronIdIsStableEightHex() {
        val first = kimiCronIdFor("task-a", 0)
        val second = kimiCronIdFor("task-a", 0)
        assertEquals(first, second)
        assertEquals(8, first.length)
        assertTrue(first.all { it in '0'..'9' || it in 'a'..'f' })
    }

    private fun millis(value: String): Long =
        LocalDateTime.parse(value)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
}
