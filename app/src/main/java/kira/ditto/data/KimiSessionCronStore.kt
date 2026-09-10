package kira.ditto.data

import kira.ditto.data.kimi.KimiAcpProtocol
import kira.ditto.runtime.AlpineRuntime
import java.io.File
import java.util.UUID
import org.json.JSONObject

internal const val KimiCronAetherTaskTag = "aetherTaskId"
internal const val KimiCronNameTag = "name"
internal const val KimiCronSlotTag = "slot"

class KimiSessionCronStore(
    private val alpineRuntime: AlpineRuntime,
) {
    fun listRecords(): List<KimiCronRecord> {
        val root = runCatching { alpineRuntime.kimiCodeSessionsHostDir() }.getOrNull() ?: return emptyList()
        return listKimiCronRecords(root)
    }

    fun mergeWithDisk(stored: List<ScheduledTask>): List<ScheduledTask> {
        val records = listRecords()
        val storedIds = stored.map { it.id }.toHashSet()
        val storedCronIds = stored.flatMap { it.kimiCronIds }.toHashSet()
        val extras = records
            .filter { record ->
                record.aetherTaskId !in storedIds &&
                    record.id !in storedCronIds &&
                    record.id !in storedIds &&
                    record.displayTaskId() !in storedIds
            }
            .groupBy { it.aetherTaskId.ifBlank { "${it.kimiSessionId}:${it.id}" } }
            .map { (_, group) -> group.first().toScheduledTask(group) }
        return stored + extras
    }

    fun sync(
        task: ScheduledTask,
        kimiSessionId: String?,
    ): ScheduledTask {
        if (task.isGuiWatchTask()) return task
        remove(task)
        if (!task.isEnabled || kimiSessionId.isNullOrBlank()) {
            return task.copy(kimiCronIds = emptyList())
        }
        val sessionDir = resolveKimiSessionDir(
            alpineRuntime.kimiCodeSessionsHostDir(),
            kimiSessionId,
        ) ?: return task.copy(kimiCronIds = emptyList())
        val cronDir = File(sessionDir, "cron")
        cronDir.mkdirs()
        val expressions = task.schedule.toCronExpressions()
        if (expressions.isEmpty()) return task.copy(kimiCronIds = emptyList())
        val createdAt = task.createdAtMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
        val ids = expressions.mapIndexed { index, expression ->
            val id = kimiCronIdFor(task.id, index)
            val body = JSONObject()
                .put("id", id)
                .put("cron", expression)
                .put("prompt", task.prompt)
                .put("createdAt", createdAt)
                .put("recurring", true)
                .put(
                    "tags",
                    JSONObject()
                        .put(KimiCronAetherTaskTag, task.id)
                        .put(KimiCronNameTag, task.name)
                        .put(KimiCronSlotTag, index.toString()),
                )
            File(cronDir, "$id.json").writeText(body.toString())
            id
        }
        return task.copy(kimiCronIds = ids)
    }

    fun remove(task: ScheduledTask) {
        val knownIds = task.kimiCronIds.toHashSet()
        listRecords()
            .filter { record ->
                record.aetherTaskId == task.id ||
                    record.id in knownIds ||
                    record.displayTaskId() == task.id
            }
            .forEach { record ->
                runCatching { record.file.delete() }
            }
    }

    fun removeByJobId(jobId: String) {
        if (jobId.isBlank()) return
        listRecords()
            .filter { record -> record.id == jobId }
            .forEach { record ->
                runCatching { record.file.delete() }
            }
    }

    fun projectIntoSession(
        kimiSessionId: String,
        tasks: List<ScheduledTask>,
    ) {
        if (kimiSessionId.isBlank()) return
        tasks.filterNot { it.isGuiWatchTask() }.forEach { task ->
            if (task.isEnabled) {
                sync(task, kimiSessionId)
            } else {
                remove(task)
            }
        }
    }

    fun shouldSuppressIdleCronFire(
        promptText: String,
        tasks: List<ScheduledTask> = emptyList(),
    ): Boolean {
        return idleCronFireUsesAlarm(
            promptText = promptText,
            taggedJobIds = listRecords()
                .mapNotNull { record -> record.id.takeIf { record.aetherTaskId.isNotBlank() } }
                .toHashSet(),
            tasks = tasks,
        )
    }
}

internal fun idleCronFireUsesAlarm(
    promptText: String,
    taggedJobIds: Collection<String>,
    tasks: List<ScheduledTask>,
): Boolean {
    val jobId = KimiAcpProtocol.extractCronFireJobId(promptText)
    val firePrompt = KimiAcpProtocol.extractCronFirePrompt(promptText)
    if (jobId.isNotBlank()) {
        if (jobId in taggedJobIds) return true
        if (tasks.any { task ->
            !task.isGuiWatchTask() &&
                task.isEnabled &&
                (task.id == jobId || jobId in task.kimiCronIds)
        }) {
            return true
        }
    }
    if (firePrompt.isNotBlank()) {
        val normalized = firePrompt.trim()
        if (tasks.any { task ->
            !task.isGuiWatchTask() &&
                task.isEnabled &&
                task.prompt.trim() == normalized
        }) {
            return true
        }
    }
    return false
}

data class KimiCronRecord(
    val id: String,
    val kimiSessionId: String,
    val cron: String,
    val prompt: String,
    val createdAt: Long,
    val recurring: Boolean,
    val aetherTaskId: String,
    val name: String,
    val file: File,
) {
    fun displayTaskId(): String =
        aetherTaskId.ifBlank { "kimi-cron-$kimiSessionId-$id" }

    fun toScheduledTask(group: List<KimiCronRecord> = listOf(this)): ScheduledTask {
        val schedule = cronExpressionsToSchedule(group.map { it.cron })
        val now = System.currentTimeMillis()
        return ScheduledTask(
            id = displayTaskId(),
            name = name.ifBlank { prompt.take(32).ifBlank { "Kimi cron" } },
            prompt = prompt,
            schedule = schedule,
            isEnabled = true,
            sessionId = alpineSessionHint(),
            createdBy = ScheduledTaskCreator.Agent,
            createdAtMillis = createdAt.takeIf { it > 0L } ?: now,
            updatedAtMillis = now,
            kimiCronIds = group.map { it.id },
        ).withNextRunAfter(now)
    }

    private fun alpineSessionHint(): String = ""
}

internal fun listKimiCronJsonFiles(sessionsRoot: File): List<File> {
    if (!sessionsRoot.isDirectory) return emptyList()
    return sessionsRoot.walkTopDown()
        .maxDepth(5)
        .filter { file ->
            file.isFile &&
                file.extension.equals("json", ignoreCase = true) &&
                file.parentFile?.name.equals("cron", ignoreCase = true)
        }
        .toList()
}

internal fun kimiSessionIdFromCronFile(file: File): String =
    file.parentFile?.parentFile?.name.orEmpty()

internal fun resolveKimiSessionDir(sessionsRoot: File, kimiSessionId: String): File? {
    if (!sessionsRoot.isDirectory || kimiSessionId.isBlank()) return null
    val direct = File(sessionsRoot, kimiSessionId)
    if (direct.isDirectory) return direct
    sessionsRoot.listFiles().orEmpty()
        .filter { it.isDirectory }
        .forEach { bucket ->
            val nested = File(bucket, kimiSessionId)
            if (nested.isDirectory) return nested
        }
    return sessionsRoot.walkTopDown()
        .maxDepth(3)
        .firstOrNull { it.isDirectory && it.name == kimiSessionId }
}

internal fun listKimiCronRecords(sessionsRoot: File): List<KimiCronRecord> {
    return listKimiCronJsonFiles(sessionsRoot).mapNotNull { file ->
        val sessionId = kimiSessionIdFromCronFile(file)
        if (sessionId.isBlank()) null else parseKimiCronRecord(sessionId, file)
    }
}

internal fun kimiCronIdFor(taskId: String, slot: Int): String =
    UUID.nameUUIDFromBytes("$taskId:$slot".toByteArray())
        .toString()
        .replace("-", "")
        .take(8)

internal fun ScheduledTaskSchedule.toCronExpressions(): List<String> = when (this) {
    is ScheduledTaskSchedule.Interval -> listOf(intervalToCron(intervalMillis))
    is ScheduledTaskSchedule.Daily -> timesMinutesOfDay
        .filter(::isMinuteOfDay)
        .distinct()
        .sorted()
        .map { minuteOfDayToCron(it, dow = "*") }
    is ScheduledTaskSchedule.Weekly -> {
        val days = daysOfWeek
            .filter { it in 1..7 }
            .distinct()
            .sorted()
            .joinToString(",") { isoDayToCronDow(it).toString() }
        if (days.isBlank() || !isMinuteOfDay(minuteOfDay)) {
            emptyList()
        } else {
            listOf(minuteOfDayToCron(minuteOfDay, dow = days))
        }
    }
}

internal fun cronExpressionsToSchedule(expressions: List<String>): ScheduledTaskSchedule {
    val parsed = expressions.mapNotNull(::parseFiveFieldCron)
    if (parsed.isEmpty()) {
        return ScheduledTaskSchedule.Interval(intervalMillis = 60L * 60L * 1000L)
    }
    val first = parsed.first()
    if (parsed.size == 1 && first.minuteStep != null && first.hour == "*" && first.dow == "*") {
        return ScheduledTaskSchedule.Interval(
            intervalMillis = first.minuteStep * 60_000L,
        )
    }
    if (parsed.size == 1 && first.hourStep != null && first.minute == "0" && first.dow == "*") {
        return ScheduledTaskSchedule.Interval(
            intervalMillis = first.hourStep * 60L * 60L * 1000L,
        )
    }
    val dailyTimes = parsed.mapNotNull { field ->
        if (field.dow == "*" && field.dom == "*" && field.month == "*") {
            minuteOfDayFromCron(field.minute, field.hour)
        } else {
            null
        }
    }
    if (dailyTimes.size == parsed.size && dailyTimes.isNotEmpty()) {
        return ScheduledTaskSchedule.Daily(timesMinutesOfDay = dailyTimes.distinct().sorted())
    }
    val weekly = parsed.mapNotNull { field ->
        val minute = minuteOfDayFromCron(field.minute, field.hour) ?: return@mapNotNull null
        val days = parseCronDows(field.dow)
        if (days.isEmpty()) null else days to minute
    }
    if (weekly.size == parsed.size && weekly.isNotEmpty()) {
        val minute = weekly.first().second
        val days = weekly.flatMap { it.first }.distinct().sorted()
        return ScheduledTaskSchedule.Weekly(daysOfWeek = days, minuteOfDay = minute)
    }
    return ScheduledTaskSchedule.Interval(intervalMillis = 60L * 60L * 1000L)
}

private data class FiveFieldCron(
    val minute: String,
    val hour: String,
    val dom: String,
    val month: String,
    val dow: String,
    val minuteStep: Int? = null,
    val hourStep: Int? = null,
)

private fun parseFiveFieldCron(raw: String): FiveFieldCron? {
    val parts = raw.trim().split(Regex("\\s+"))
    if (parts.size != 5) return null
    val minuteStep = parts[0].removePrefix("*/").toIntOrNull()
        ?.takeIf { parts[0].startsWith("*/") }
    val hourStep = parts[1].removePrefix("*/").toIntOrNull()
        ?.takeIf { parts[1].startsWith("*/") }
    return FiveFieldCron(
        minute = parts[0],
        hour = parts[1],
        dom = parts[2],
        month = parts[3],
        dow = parts[4],
        minuteStep = minuteStep,
        hourStep = hourStep,
    )
}

private fun intervalToCron(intervalMillis: Long): String {
    val minutes = (intervalMillis / 60_000L).coerceAtLeast(1L)
    if (minutes < 60L && 60L % minutes == 0L) {
        return "*/$minutes * * * *"
    }
    if (minutes % 60L == 0L) {
        val hours = minutes / 60L
        return if (hours < 24L && 24L % hours == 0L) {
            "0 */$hours * * *"
        } else {
            "0 * * * *"
        }
    }
    val clamped = intArrayOf(1, 2, 3, 4, 5, 6, 10, 12, 15, 20, 30)
        .minBy { kotlin.math.abs(it - minutes.toInt().coerceIn(1, 59)) }
    return "*/$clamped * * * *"
}

private fun minuteOfDayToCron(minuteOfDay: Int, dow: String): String {
    val hour = minuteOfDay / 60
    val minute = minuteOfDay % 60
    return "$minute $hour * * $dow"
}

private fun minuteOfDayFromCron(minute: String, hour: String): Int? {
    val minuteValue = minute.toIntOrNull() ?: return null
    val hourValue = hour.toIntOrNull() ?: return null
    if (minuteValue !in 0..59 || hourValue !in 0..23) return null
    return hourValue * 60 + minuteValue
}

private fun isoDayToCronDow(iso: Int): Int = if (iso == 7) 0 else iso

private fun cronDowToIso(dow: Int): Int = if (dow == 0) 7 else dow

private fun parseCronDows(raw: String): List<Int> {
    if (raw == "*") return emptyList()
    return raw.split(',')
        .mapNotNull { token ->
            val value = token.trim().toIntOrNull() ?: return@mapNotNull null
            if (value in 0..7) cronDowToIso(if (value == 7) 0 else value) else null
        }
        .distinct()
        .sorted()
}

private fun parseKimiCronRecord(kimiSessionId: String, file: File): KimiCronRecord? {
    val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return null
    val id = json.optString("id").ifBlank { file.nameWithoutExtension }
    if (id.isBlank()) return null
    val tags = json.optJSONObject("tags")
    return KimiCronRecord(
        id = id,
        kimiSessionId = kimiSessionId,
        cron = json.optString("cron"),
        prompt = json.optString("prompt"),
        createdAt = json.optLong("createdAt"),
        recurring = json.optBoolean("recurring", true),
        aetherTaskId = tags?.optString(KimiCronAetherTaskTag).orEmpty(),
        name = tags?.optString(KimiCronNameTag).orEmpty(),
        file = file,
    )
}

private fun isMinuteOfDay(value: Int): Boolean = value in 0..(24 * 60 - 1)
