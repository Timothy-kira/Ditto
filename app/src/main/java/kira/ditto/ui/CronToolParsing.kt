package kira.ditto.ui

import kira.ditto.data.OfficialCronKind
import kira.ditto.data.classifyOfficialCronTool
import kira.ditto.data.extractOfficialCronJobId
import org.json.JSONObject

internal enum class CronToolKind {
    Create,
    List,
    Delete,
}

internal data class CronToolInfo(
    val kind: CronToolKind,
    val cron: String,
    val prompt: String,
    val jobId: String,
    val recurring: Boolean?,
    val argumentsComplete: Boolean,
)

internal fun parseCronTool(
    toolName: String,
    argumentsJson: String,
    outputJson: String = "",
): CronToolInfo? {
    val kind = cronToolKind(toolName, argumentsJson) ?: return null
    val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
    return CronToolInfo(
        kind = kind,
        cron = arguments?.optString("cron").orEmpty().trim(),
        prompt = arguments?.optString("prompt").orEmpty().trim(),
        jobId = arguments?.optString("id").orEmpty().trim()
            .ifBlank { extractOfficialCronJobId(outputJson, argumentsJson) },
        recurring = arguments?.takeIf { it.has("recurring") }?.optBoolean("recurring"),
        argumentsComplete = arguments != null,
    )
}

internal fun ChatToolInvocation.isCronTool(): Boolean =
    parseCronTool(toolName, argumentsJson, outputJson) != null

private fun cronToolKind(
    toolName: String,
    argumentsJson: String,
): CronToolKind? = when (classifyOfficialCronTool(toolName, argumentsJson)) {
    OfficialCronKind.Create -> CronToolKind.Create
    OfficialCronKind.List -> CronToolKind.List
    OfficialCronKind.Delete -> CronToolKind.Delete
    null -> null
}
