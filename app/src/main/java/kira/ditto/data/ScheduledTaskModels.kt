package kira.ditto.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

private const val MinScheduledIntervalMillis = 60_000L
private const val DefaultScheduledIntervalMillis = 60L * 60L * 1000L
private val StoredTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

enum class ScheduledTaskCreator(
    val storageValue: String,
) {
    User("user"),
    Agent("agent");

    companion object {
        fun fromStorage(value: String?): ScheduledTaskCreator =
            entries.firstOrNull { it.storageValue == value } ?: User
    }
}

enum class ScheduledTaskScheduleType(
    val storageValue: String,
) {
    Interval("interval"),
    Daily("daily"),
    Weekly("weekly");

    companion object {
        fun fromStorage(value: String?): ScheduledTaskScheduleType? =
            entries.firstOrNull { it.storageValue == value?.trim()?.lowercase(Locale.US) }
    }
}

sealed interface ScheduledTaskSchedule {
    val type: ScheduledTaskScheduleType

    data class Interval(
        val intervalMillis: Long,
        val activeStartMinuteOfDay: Int? = null,
        val activeEndMinuteOfDay: Int? = null,
    ) : ScheduledTaskSchedule {
        override val type: ScheduledTaskScheduleType = ScheduledTaskScheduleType.Interval
    }

    data class Daily(
        val timesMinutesOfDay: List<Int>,
    ) : ScheduledTaskSchedule {
        override val type: ScheduledTaskScheduleType = ScheduledTaskScheduleType.Daily
    }

    data class Weekly(
        val daysOfWeek: List<Int>,
        val minuteOfDay: Int,
    ) : ScheduledTaskSchedule {
        override val type: ScheduledTaskScheduleType = ScheduledTaskScheduleType.Weekly
    }
}

data class ScheduledTask(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val prompt: String,
    val schedule: ScheduledTaskSchedule,
    val isEnabled: Boolean = true,
    val sessionId: String = "",
    val createdBy: ScheduledTaskCreator = ScheduledTaskCreator.User,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = createdAtMillis,
    val lastRunStartedAtMillis: Long? = null,
    val nextRunAtMillis: Long? = null,
    val kimiCronIds: List<String> = emptyList(),
)

fun ScheduledTask.defaultSessionId(): String = sessionId.ifBlank { "scheduled-$id" }

fun ScheduledTask.withNextRunAfter(
    afterMillis: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): ScheduledTask = copy(
    nextRunAtMillis = if (isEnabled) schedule.nextRunAfter(
        afterMillis = afterMillis,
        lastRunStartedAtMillis = lastRunStartedAtMillis,
        createdAtMillis = createdAtMillis,
        zoneId = zoneId,
    ) else null,
)

internal fun refreshScheduledTaskNextRuns(
    tasks: List<ScheduledTask>,
    afterMillis: Long = System.currentTimeMillis(),
    keepDueTasks: Boolean = true,
    zoneId: ZoneId = ZoneId.systemDefault(),
): List<ScheduledTask> = tasks.map { task ->
    if (
        keepDueTasks &&
        task.isEnabled &&
        task.nextRunAtMillis != null &&
        task.nextRunAtMillis <= afterMillis
    ) {
        task
    } else {
        task.withNextRunAfter(afterMillis, zoneId)
    }
}

fun ScheduledTask.toStorageJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("prompt", prompt)
    put("schedule", schedule.toJson())
    put("isEnabled", isEnabled)
    put("sessionId", sessionId)
    put("createdBy", createdBy.storageValue)
    put("createdAtMillis", createdAtMillis)
    put("updatedAtMillis", updatedAtMillis)
    put("lastRunStartedAtMillis", lastRunStartedAtMillis ?: JSONObject.NULL)
    put("nextRunAtMillis", nextRunAtMillis ?: JSONObject.NULL)
    put("kimiCronIds", JSONArray().apply { kimiCronIds.forEach(::put) })
}

fun ScheduledTask.toPublicJson(): JSONObject = toStorageJson().apply {
    put("scheduleSummary", schedule.summary())
    put("defaultSessionId", defaultSessionId())
}

fun parseScheduledTasks(rawValue: String): List<ScheduledTask> {
    if (rawValue.isBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(rawValue)
        buildList {
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                parseScheduledTask(json)?.let(::add)
            }
        }
    }.getOrDefault(emptyList())
}

fun serializeScheduledTasks(tasks: List<ScheduledTask>): String =
    JSONArray().apply {
        tasks.forEach { put(it.toStorageJson()) }
    }.toString()

fun parseScheduledTaskSchedule(json: JSONObject?): ScheduledTaskSchedule? {
    if (json == null) return null
    return when (ScheduledTaskScheduleType.fromStorage(json.optString("type"))) {
        ScheduledTaskScheduleType.Interval -> {
            val intervalMillis = parseIntervalMillis(json).coerceAtLeast(MinScheduledIntervalMillis)
            ScheduledTaskSchedule.Interval(
                intervalMillis = intervalMillis,
                activeStartMinuteOfDay = parseMinuteOfDay(json.optStringAny("active_start_time", "activeStartTime"))
                    ?: json.optNullableInt("activeStartMinuteOfDay"),
                activeEndMinuteOfDay = parseMinuteOfDay(json.optStringAny("active_end_time", "activeEndTime"))
                    ?: json.optNullableInt("activeEndMinuteOfDay"),
            )
        }

        ScheduledTaskScheduleType.Daily -> {
            val times = parseTimes(json.optJSONArray("times"))
                .ifEmpty { parseMinuteOfDay(json.optString("time")).let { value -> listOfNotNull(value) } }
                .ifEmpty { json.optJSONArray("timesMinutesOfDay").toIntList().filter(::isMinuteOfDay) }
            if (times.isEmpty()) null else ScheduledTaskSchedule.Daily(timesMinutesOfDay = times.distinct().sorted())
        }

        ScheduledTaskScheduleType.Weekly -> {
            val days = parseDaysOfWeek(json.optJSONArray("days_of_week"))
                .ifEmpty { parseDaysOfWeek(json.optJSONArray("daysOfWeek")) }
            val minute = parseMinuteOfDay(json.optString("time"))
                ?: json.optNullableInt("minuteOfDay")
                ?: return null
            if (days.isEmpty()) null else ScheduledTaskSchedule.Weekly(
                daysOfWeek = days.distinct().sorted(),
                minuteOfDay = minute.coerceIn(0, 1_439),
            )
        }

        null -> null
    }
}

internal fun ScheduledTaskSchedule.summary(): String = when (this) {
    is ScheduledTaskSchedule.Interval -> buildString {
        append("Every ")
        append(formatInterval(intervalMillis))
        val start = activeStartMinuteOfDay
        val end = activeEndMinuteOfDay
        if (start != null && end != null) {
            append(" between ")
            append(formatMinuteOfDay(start))
            append(" and ")
            append(formatMinuteOfDay(end))
        }
    }

    is ScheduledTaskSchedule.Daily ->
        "Daily at ${timesMinutesOfDay.joinToString(", ") { formatMinuteOfDay(it) }}"

    is ScheduledTaskSchedule.Weekly ->
        "Weekly on ${daysOfWeek.joinToString(", ") { dayOfWeekLabel(it) }} at ${formatMinuteOfDay(minuteOfDay)}"
}

fun Long.formatScheduledTaskTime(
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val local = Instant.ofEpochMilli(this).atZone(zoneId)
    return "${local.toLocalDate()} ${local.toLocalTime().format(StoredTimeFormatter)}"
}

private fun parseScheduledTask(json: JSONObject): ScheduledTask? {
    val schedule = parseScheduledTaskSchedule(json.optJSONObject("schedule")) ?: return null
    val id = json.optString("id").ifBlank { UUID.randomUUID().toString() }
    val now = System.currentTimeMillis()
    return ScheduledTask(
        id = id,
        name = json.optString("name").trim().ifBlank { "Scheduled task" },
        prompt = json.optString("prompt"),
        schedule = schedule,
        isEnabled = json.optBoolean("isEnabled", true),
        sessionId = json.optString("sessionId"),
        createdBy = ScheduledTaskCreator.fromStorage(json.optString("createdBy")),
        createdAtMillis = json.optLong("createdAtMillis", now),
        updatedAtMillis = json.optLong("updatedAtMillis", now),
        lastRunStartedAtMillis = json.optNullableLong("lastRunStartedAtMillis"),
        nextRunAtMillis = json.optNullableLong("nextRunAtMillis"),
        kimiCronIds = parseStringList(json.optJSONArray("kimiCronIds")),
    )
}

private fun ScheduledTaskSchedule.toJson(): JSONObject = JSONObject().apply {
    put("type", type.storageValue)
    when (val schedule = this@toJson) {
        is ScheduledTaskSchedule.Interval -> {
            put("intervalMillis", schedule.intervalMillis.coerceAtLeast(MinScheduledIntervalMillis))
            put("activeStartMinuteOfDay", schedule.activeStartMinuteOfDay ?: JSONObject.NULL)
            put("activeEndMinuteOfDay", schedule.activeEndMinuteOfDay ?: JSONObject.NULL)
            schedule.activeStartMinuteOfDay?.let { put("activeStartTime", formatMinuteOfDay(it)) }
            schedule.activeEndMinuteOfDay?.let { put("activeEndTime", formatMinuteOfDay(it)) }
        }

        is ScheduledTaskSchedule.Daily -> {
            put("timesMinutesOfDay", JSONArray().apply { schedule.timesMinutesOfDay.forEach(::put) })
            put("times", JSONArray().apply { schedule.timesMinutesOfDay.forEach { put(formatMinuteOfDay(it)) } })
        }

        is ScheduledTaskSchedule.Weekly -> {
            put("daysOfWeek", JSONArray().apply { schedule.daysOfWeek.forEach(::put) })
            put("minuteOfDay", schedule.minuteOfDay)
            put("time", formatMinuteOfDay(schedule.minuteOfDay))
        }
    }
}

private fun ScheduledTaskSchedule.nextRunAfter(
    afterMillis: Long,
    lastRunStartedAtMillis: Long?,
    createdAtMillis: Long,
    zoneId: ZoneId,
): Long? = when (this) {
    is ScheduledTaskSchedule.Interval -> nextIntervalRunAfter(
        afterMillis = afterMillis,
        lastRunStartedAtMillis = lastRunStartedAtMillis,
        createdAtMillis = createdAtMillis,
        zoneId = zoneId,
    )

    is ScheduledTaskSchedule.Daily -> nextDailyRunAfter(
        afterMillis = afterMillis,
        zoneId = zoneId,
    )

    is ScheduledTaskSchedule.Weekly -> nextWeeklyRunAfter(
        afterMillis = afterMillis,
        zoneId = zoneId,
    )
}

private fun ScheduledTaskSchedule.Interval.nextIntervalRunAfter(
    afterMillis: Long,
    lastRunStartedAtMillis: Long?,
    createdAtMillis: Long,
    zoneId: ZoneId,
): Long {
    val interval = intervalMillis.coerceAtLeast(MinScheduledIntervalMillis)
    val earliest = afterMillis + 1L
    val anchor = lastRunStartedAtMillis ?: createdAtMillis
    var candidate = if (anchor >= earliest) {
        anchor
    } else {
        anchor + (((earliest - anchor - 1L) / interval) + 1L) * interval
    }

    val startMinute = activeStartMinuteOfDay
    val endMinute = activeEndMinuteOfDay
    if (startMinute == null || endMinute == null) {
        return candidate
    }

    repeat(370) {
        val localCandidate = Instant.ofEpochMilli(candidate).atZone(zoneId)
        val candidateDate = localCandidate.toLocalDate()
        val window = dailyWindow(candidateDate, startMinute, endMinute, zoneId)
        candidate = when {
            candidate < window.first -> window.first
            candidate <= window.second -> return candidate
            else -> dailyWindow(candidateDate.plusDays(1), startMinute, endMinute, zoneId).first
        }
    }
    return candidate
}

private fun ScheduledTaskSchedule.Daily.nextDailyRunAfter(
    afterMillis: Long,
    zoneId: ZoneId,
): Long? {
    val times = timesMinutesOfDay.filter(::isMinuteOfDay).distinct().sorted()
    if (times.isEmpty()) return null
    val afterLocal = Instant.ofEpochMilli(afterMillis).atZone(zoneId)
    repeat(370) { dayOffset ->
        val date = afterLocal.toLocalDate().plusDays(dayOffset.toLong())
        times.forEach { minute ->
            val candidate = date.atMinuteOfDay(minute, zoneId)
            if (candidate > afterMillis) return candidate
        }
    }
    return null
}

private fun ScheduledTaskSchedule.Weekly.nextWeeklyRunAfter(
    afterMillis: Long,
    zoneId: ZoneId,
): Long? {
    val days = daysOfWeek.filter { it in 1..7 }.distinct()
    if (days.isEmpty() || !isMinuteOfDay(minuteOfDay)) return null
    val afterLocal = Instant.ofEpochMilli(afterMillis).atZone(zoneId)
    repeat(371) { dayOffset ->
        val date = afterLocal.toLocalDate().plusDays(dayOffset.toLong())
        if (date.dayOfWeek.value in days) {
            val candidate = date.atMinuteOfDay(minuteOfDay, zoneId)
            if (candidate > afterMillis) return candidate
        }
    }
    return null
}

private fun parseIntervalMillis(json: JSONObject): Long {
    json.optNullableLong("intervalMillis")?.let { return it }
    json.optNullableLong("interval_millis")?.let { return it }
    json.optNullableLong("interval_minutes")?.let { return it * 60_000L }
    json.optNullableLong("intervalMinutes")?.let { return it * 60_000L }
    json.optNullableLong("interval_hours")?.let { return it * 60L * 60L * 1000L }
    json.optNullableLong("intervalHours")?.let { return it * 60L * 60L * 1000L }
    return DefaultScheduledIntervalMillis
}

private fun parseStringList(array: JSONArray?): List<String> {
    if (array == null) return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            array.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
        }
    }
}

private fun parseTimes(array: JSONArray?): List<Int> {
    if (array == null) return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            parseMinuteOfDay(array.optString(index))?.let(::add)
        }
    }
}

private fun parseDaysOfWeek(array: JSONArray?): List<Int> {
    if (array == null) return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val raw = array.opt(index)
            when (raw) {
                is Number -> raw.toInt()
                else -> parseDayOfWeek(raw?.toString().orEmpty())
            }.takeIf { it in 1..7 }?.let(::add)
        }
    }
}

private fun parseDayOfWeek(value: String): Int {
    val normalized = value.trim().lowercase(Locale.US)
    return when (normalized.take(3)) {
        "mon" -> DayOfWeek.MONDAY.value
        "tue" -> DayOfWeek.TUESDAY.value
        "wed" -> DayOfWeek.WEDNESDAY.value
        "thu" -> DayOfWeek.THURSDAY.value
        "fri" -> DayOfWeek.FRIDAY.value
        "sat" -> DayOfWeek.SATURDAY.value
        "sun" -> DayOfWeek.SUNDAY.value
        else -> normalized.toIntOrNull() ?: 0
    }
}

private fun parseMinuteOfDay(value: String): Int? {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return null
    val parts = trimmed.split(':')
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}

private fun formatMinuteOfDay(minuteOfDay: Int): String {
    val normalized = minuteOfDay.coerceIn(0, 1_439)
    return LocalTime.of(normalized / 60, normalized % 60).format(StoredTimeFormatter)
}

private fun formatInterval(intervalMillis: Long): String {
    val totalMinutes = (intervalMillis / 60_000L).coerceAtLeast(1L)
    if (totalMinutes % (24L * 60L) == 0L) return "${totalMinutes / (24L * 60L)}d"
    if (totalMinutes % 60L == 0L) return "${totalMinutes / 60L}h"
    return "${totalMinutes}m"
}

private fun dayOfWeekLabel(value: Int): String =
    DayOfWeek.of(value.coerceIn(1, 7)).name.lowercase(Locale.US).replaceFirstChar(Char::uppercase)

private fun dailyWindow(
    date: LocalDate,
    startMinute: Int,
    endMinute: Int,
    zoneId: ZoneId,
): Pair<Long, Long> {
    val start = date.atMinuteOfDay(startMinute, zoneId)
    val endDate = if (endMinute < startMinute) date.plusDays(1) else date
    val end = endDate.atMinuteOfDay(endMinute, zoneId)
    return start to end
}

private fun LocalDate.atMinuteOfDay(
    minuteOfDay: Int,
    zoneId: ZoneId,
): Long = atTime(minuteOfDay.coerceIn(0, 1_439) / 60, minuteOfDay.coerceIn(0, 1_439) % 60)
    .atZone(zoneId)
    .toInstant()
    .toEpochMilli()

private fun isMinuteOfDay(value: Int): Boolean = value in 0..1_439

private fun JSONArray?.toIntList(): List<Int> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            add(optInt(index, Int.MIN_VALUE))
        }
    }
}

private fun JSONObject.optNullableLong(name: String): Long? =
    if (has(name) && !isNull(name)) optLong(name) else null

private fun JSONObject.optNullableInt(name: String): Int? =
    if (has(name) && !isNull(name)) optInt(name) else null

private fun JSONObject.optStringAny(vararg names: String): String =
    names.firstOrNull(::has)?.let(::optString).orEmpty()
