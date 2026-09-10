package kira.ditto.data

import java.util.Locale
import java.util.UUID
import org.json.JSONObject

internal const val AppScheduledSessionId = "aether-scheduled"

internal data class IdleCronResumeSpec(
    val sessionId: String,
    val adoptAsCurrentSession: Boolean,
    val agentModeEnabled: Boolean,
)

internal fun shouldDeferIdleCronResume(
    runningSessionIds: Collection<String>,
    displayActive: Boolean,
    lastAgentModeTurnSettledAtMillis: Long,
    nowMillis: Long,
    quietWindowMillis: Long = 15_000L,
): Boolean {
    if (displayActive) return true
    if (runningSessionIds.any { it.isNotBlank() && it != AppScheduledSessionId }) return true
    if (lastAgentModeTurnSettledAtMillis > 0L &&
        nowMillis - lastAgentModeTurnSettledAtMillis < quietWindowMillis
    ) {
        return true
    }
    return false
}

internal fun officialIdleCronResume(): IdleCronResumeSpec = IdleCronResumeSpec(
    sessionId = AppScheduledSessionId,
    adoptAsCurrentSession = false,
    agentModeEnabled = false,
)

internal enum class OfficialCronKind {
    Create,
    List,
    Delete,
}

internal fun classifyOfficialCronTool(
    toolName: String,
    argumentsJson: String = "",
): OfficialCronKind? {
    val raw = toolName.trim()
    if (raw.isNotBlank()) {
        val compact = raw.lowercase(Locale.US)
            .replace("_", "")
            .replace("-", "")
            .replace(" ", "")
        when (compact) {
            "croncreate" -> return OfficialCronKind.Create
            "cronlist" -> return OfficialCronKind.List
            "crondelete" -> return OfficialCronKind.Delete
        }
        val title = raw.lowercase(Locale.US)
        when {
            title.startsWith("scheduling cron") ||
                title.startsWith("scheduling one-shot") ||
                title.startsWith("scheduled cron") -> return OfficialCronKind.Create
            title.startsWith("listing scheduled cron") -> return OfficialCronKind.List
            title.startsWith("deleting cron") ||
                title.startsWith("deleted cron") -> return OfficialCronKind.Delete
        }
        if (!title.contains("cron")) {
            return null
        }
    }
    return classifyOfficialCronFromArguments(argumentsJson)
}

internal fun isCanonicalOfficialCronName(name: String): Boolean {
    val compact = name.trim().lowercase(Locale.US)
        .replace("_", "")
        .replace("-", "")
        .replace(" ", "")
    return compact == "croncreate" || compact == "cronlist" || compact == "crondelete"
}

internal fun preferStableOfficialCronToolName(
    incoming: String,
    existing: String?,
): String {
    val incomingTrim = incoming.trim()
    val existingTrim = existing?.trim().orEmpty()
    if (incomingTrim.isBlank()) return existingTrim
    if (existingTrim.isBlank()) return incomingTrim
    val existingKind = classifyOfficialCronTool(existingTrim)
    val incomingKind = classifyOfficialCronTool(incomingTrim)
    if (
        existingKind != null &&
        incomingKind == existingKind &&
        isCanonicalOfficialCronName(existingTrim) &&
        !isCanonicalOfficialCronName(incomingTrim)
    ) {
        return existingTrim
    }
    return incomingTrim
}

internal fun extractOfficialCronJobId(
    outputJson: String,
    argumentsJson: String = "",
): String {
    runCatching { JSONObject(argumentsJson) }.getOrNull()
        ?.optString("id")
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { return it }
    if (outputJson.isBlank()) return ""
    val json = runCatching { JSONObject(outputJson) }.getOrNull()
    json?.optString("id")?.trim()?.takeIf(String::isNotBlank)?.let { return it }
    json?.optJSONObject("task")?.optString("id")?.trim()?.takeIf(String::isNotBlank)?.let { return it }
    val text = buildString {
        append(outputJson)
        json?.optString("stdout")?.let { append('\n').append(it) }
        json?.optString("text")?.let { append('\n').append(it) }
        val content = json?.optJSONArray("content")
        if (content != null) {
            for (index in 0 until content.length()) {
                val item = content.optJSONObject(index) ?: continue
                append('\n').append(item.optString("text"))
            }
        }
    }
    Regex("""Scheduled cron ([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
        .find(text)
        ?.groupValues
        ?.getOrNull(1)
        ?.let { return it }
    Regex(""""id"\s*:\s*"([^"]+)"""")
        .find(text)
        ?.groupValues
        ?.getOrNull(1)
        ?.let { return it }
    return ""
}

internal suspend fun ingestOfficialCronTool(
    scheduledTaskManager: ScheduledTaskManager,
    kimiCronStore: KimiSessionCronStore?,
    toolName: String,
    argumentsJson: String,
    outputJson: String,
) {
    when (classifyOfficialCronTool(toolName, argumentsJson)) {
        OfficialCronKind.Create -> ingestOfficialCronCreate(
            scheduledTaskManager = scheduledTaskManager,
            argumentsJson = argumentsJson,
            outputJson = outputJson,
        )
        OfficialCronKind.Delete -> ingestOfficialCronDelete(
            scheduledTaskManager = scheduledTaskManager,
            kimiCronStore = kimiCronStore,
            argumentsJson = argumentsJson,
            outputJson = outputJson,
        )
        OfficialCronKind.List -> ingestOfficialCronList(
            scheduledTaskManager = scheduledTaskManager,
            outputJson = outputJson,
        )
        null -> Unit
    }
}

private fun classifyOfficialCronFromArguments(argumentsJson: String): OfficialCronKind? {
    val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull() ?: return null
    val cron = arguments.optString("cron").trim()
    val prompt = arguments.optString("prompt").trim()
    if (cron.isNotBlank() && prompt.isNotBlank()) return OfficialCronKind.Create
    return null
}

private suspend fun ingestOfficialCronCreate(
    scheduledTaskManager: ScheduledTaskManager,
    argumentsJson: String,
    outputJson: String,
) {
    val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
    val outputRecord = parseOfficialCronJobRecords(outputJson).firstOrNull()
    val cron = arguments?.optString("cron").orEmpty().trim().ifBlank { outputRecord?.cron.orEmpty() }
    val prompt = arguments?.optString("prompt").orEmpty().trim().ifBlank { outputRecord?.prompt.orEmpty() }
    if (cron.isBlank() || prompt.isBlank()) return
    val jobId = extractOfficialCronJobId(outputJson, argumentsJson)
        .ifBlank { outputRecord?.id.orEmpty() }
    if (outputLooksFailed(outputJson) && jobId.isBlank()) return
    val schedule = cronExpressionsToSchedule(listOf(cron))
    val taskId = jobId.ifBlank {
        "kimi-cron-" + UUID.nameUUIDFromBytes("$cron|$prompt".toByteArray()).toString()
    }
    val snapshot = scheduledTaskManager.snapshot()
    val existing = snapshot.firstOrNull { task ->
        task.id == taskId ||
            (jobId.isNotBlank() && jobId in task.kimiCronIds) ||
            (task.prompt == prompt && task.schedule == schedule && !task.isGuiWatchTask())
    }
    if (existing?.isGuiWatchTask() == true) return
    val now = System.currentTimeMillis()
    val task = (existing ?: ScheduledTask(
        id = taskId,
        name = prompt.take(48).ifBlank { "Scheduled task" },
        prompt = prompt,
        schedule = schedule,
        createdBy = ScheduledTaskCreator.Agent,
        createdAtMillis = now,
    )).copy(
        name = existing?.name?.takeIf(String::isNotBlank) ?: prompt.take(48).ifBlank { "Scheduled task" },
        prompt = prompt,
        schedule = schedule,
        isEnabled = existing?.isEnabled ?: true,
        sessionId = "",
        createdBy = existing?.createdBy ?: ScheduledTaskCreator.Agent,
        kimiCronIds = listOfNotNull(jobId.takeIf(String::isNotBlank))
            .plus(existing?.kimiCronIds.orEmpty())
            .distinct(),
    )
    scheduledTaskManager.upsertTask(task)
}

private suspend fun ingestOfficialCronDelete(
    scheduledTaskManager: ScheduledTaskManager,
    kimiCronStore: KimiSessionCronStore?,
    argumentsJson: String,
    outputJson: String,
) {
    val jobId = extractOfficialCronJobId(outputJson, argumentsJson)
    if (jobId.isBlank()) return
    scheduledTaskManager.snapshot()
        .filter { task ->
            !task.isGuiWatchTask() &&
                (task.id == jobId || jobId in task.kimiCronIds || task.id.endsWith("-$jobId"))
        }
        .forEach { task -> scheduledTaskManager.removeTask(task.id) }
    kimiCronStore?.removeByJobId(jobId)
}

internal data class OfficialCronJobDraft(
    val id: String,
    val cron: String,
    val prompt: String,
)

internal fun parseOfficialCronJobRecords(outputJson: String): List<OfficialCronJobDraft> {
    val text = officialCronOutputText(outputJson)
    if (text.isBlank()) return emptyList()
    return text.split(Regex("""\n---\n"""))
        .mapNotNull { block ->
            val id = Regex("""(?m)^id:\s*(\S+)""").find(block)?.groupValues?.getOrNull(1).orEmpty()
            val cron = Regex("""(?m)^cron:\s*(.+)$""").find(block)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            val promptRaw = Regex("""(?m)^prompt:\s*(.+)$""").find(block)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            val prompt = decodeCronPromptField(promptRaw)
            if (cron.isBlank() || prompt.isBlank()) return@mapNotNull null
            OfficialCronJobDraft(
                id = id,
                cron = cron,
                prompt = prompt,
            )
        }
}

private suspend fun ingestOfficialCronList(
    scheduledTaskManager: ScheduledTaskManager,
    outputJson: String,
) {
    parseOfficialCronJobRecords(outputJson).forEach { record ->
        ingestOfficialCronCreate(
            scheduledTaskManager = scheduledTaskManager,
            argumentsJson = JSONObject()
                .put("id", record.id)
                .put("cron", record.cron)
                .put("prompt", record.prompt)
                .toString(),
            outputJson = JSONObject()
                .put("id", record.id)
                .put("stdout", "id: ${record.id}\ncron: ${record.cron}\nprompt: ${JSONObject.quote(record.prompt)}")
                .toString(),
        )
    }
}

internal fun officialCronOutputText(outputJson: String): String {
    if (outputJson.isBlank()) return ""
    val json = runCatching { JSONObject(outputJson) }.getOrNull() ?: return outputJson
    return buildString {
        append(outputJson)
        json.optString("stdout").takeIf(String::isNotBlank)?.let { append('\n').append(it) }
        json.optString("text").takeIf(String::isNotBlank)?.let { append('\n').append(it) }
        json.optString("output").takeIf(String::isNotBlank)?.let { append('\n').append(it) }
        val content = json.optJSONArray("content")
        if (content != null) {
            for (index in 0 until content.length()) {
                val item = content.optJSONObject(index) ?: continue
                append('\n').append(item.optString("text"))
            }
        }
    }
}

private fun decodeCronPromptField(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    if (trimmed.startsWith("\"")) {
        val wrapped = "{\"v\":$trimmed}"
        runCatching { JSONObject(wrapped).optString("v") }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
            ?.let { return it }
    }
    return trimmed.trim('"')
}

private fun outputLooksFailed(outputJson: String): Boolean {
    if (outputJson.isBlank()) return false
    val json = runCatching { JSONObject(outputJson) }.getOrNull() ?: return false
    if (!json.has("error") || json.isNull("error")) return false
    val error = json.opt("error") ?: return false
    return error.toString().isNotBlank() && error.toString() != "null"
}
