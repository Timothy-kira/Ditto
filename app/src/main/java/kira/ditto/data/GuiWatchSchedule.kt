package kira.ditto.data

internal const val GuiWatchTaskIdPrefix = "gui-watch-"
internal const val DefaultWatchingIntervalMillis = 10L * 60L * 1000L
internal const val MinWatchingIntervalMillis = 60_000L

internal const val GuiWatchResumePrompt =
    "Host watch timer fired. Resume the SAME phone agent. If listen_start is still active, " +
        "call listen_status and read transcript. Always dump_tree (optionally by region) and look for quiz, continue, " +
        "homework, 下一节, 继续学习, 作业, 答题. Screenshot of 简介 or quiz overlay is a useful supplement. " +
        "Handle those overlays, then emit GUI_TASK_WATCHING or GUI_TASK_SUCCEEDED. " +
        "Empty transcript while still listening means keep watching, not failure. " +
        "ASR_CAPTURE_UNAVAILABLE/visual_only is still watching, not failure. Do not tell the user GUI_TASK_* markers."

internal data class GuiWatchScheduleAction(
    val sessionId: String,
    val intervalMillis: Long?,
)

internal fun guiWatchTaskId(sessionId: String): String = "$GuiWatchTaskIdPrefix$sessionId"

internal fun ScheduledTask.isGuiWatchTask(): Boolean = id.startsWith(GuiWatchTaskIdPrefix)

internal fun buildGuiWatchScheduledTask(
    sessionId: String,
    intervalMillis: Long,
    nowMillis: Long = System.currentTimeMillis(),
    existing: ScheduledTask? = null,
): ScheduledTask = ScheduledTask(
    id = guiWatchTaskId(sessionId),
    name = "盯课",
    prompt = GuiWatchResumePrompt,
    schedule = ScheduledTaskSchedule.Interval(
        intervalMillis = intervalMillis.coerceAtLeast(MinWatchingIntervalMillis),
    ),
    sessionId = sessionId,
    createdBy = ScheduledTaskCreator.Agent,
    createdAtMillis = existing?.createdAtMillis ?: nowMillis,
    updatedAtMillis = nowMillis,
).withNextRunAfter(nowMillis)

internal fun parseWatchingIntervalMillis(output: String): Long {
    val match = Regex("""interval\s*=\s*([^\s,;]+)""", RegexOption.IGNORE_CASE).find(output)
    val raw = match?.groupValues?.get(1).orEmpty().trim()
    val parsed = parseWatchingDurationToken(raw) ?: DefaultWatchingIntervalMillis
    return parsed.coerceAtLeast(MinWatchingIntervalMillis)
}

internal fun parseWatchingDurationToken(raw: String): Long? {
    val token = raw.trim()
    if (token.isBlank()) return null
    Regex("""^\*/(\d+)""").find(token)?.groupValues?.get(1)?.toLongOrNull()?.let { minutes ->
        return minutes * 60_000L
    }
    val compact = Regex(
        """^(\d+(?:\.\d+)?)\s*(ms|s|sec|secs|seconds|m|min|mins|minutes|h|hr|hrs|hours)?$""",
        RegexOption.IGNORE_CASE,
    ).matchEntire(token) ?: return null
    val amount = compact.groupValues[1].toDoubleOrNull() ?: return null
    val unit = compact.groupValues[2].lowercase()
    val millis = when {
        unit == "ms" -> amount
        unit.startsWith("s") -> amount * 1_000.0
        unit.startsWith("h") -> amount * 3_600_000.0
        else -> amount * 60_000.0
    }
    return millis.toLong()
}
