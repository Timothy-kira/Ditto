package kira.ditto.ui

import org.json.JSONObject

internal const val TowerWorkerProfile = "tower-worker"

internal data class TowerRosterMember(
    val toolCallId: String,
    val name: String,
    val kind: String,
    val missionId: String,
    val reviewTarget: String,
    val instructions: String,
    val running: Boolean,
    val failed: Boolean,
    val output: String,
)

internal fun ChatToolInvocation.isTowerRelated(): Boolean {
    if (isTowerToolName(toolName)) return true
    if (toolName.contains(TowerWorkerProfile, ignoreCase = true)) return true
    val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull() ?: return false
    return arguments.optString("subagent_type").equals(TowerWorkerProfile, ignoreCase = true)
}

internal fun isTowerToolName(toolName: String): Boolean {
    val name = toolName.trim().lowercase().replace("_", "")
    if (!name.startsWith("tower")) return false
    if (name == "tower") return true
    return name in TowerToolNames
}

internal fun parseTowerRoster(invocations: List<ChatToolInvocation>): List<TowerRosterMember> {
    val members = ArrayList<TowerRosterMember>()
    val seenNames = HashSet<String>()
    invocations.forEach { invocation ->
        val member = parseTowerRosterMember(invocation) ?: return@forEach
        val key = member.name.ifBlank { member.toolCallId }.lowercase()
        if (!seenNames.add(key)) return@forEach
        members += member
    }
    return members
}

internal fun parseTowerRosterMember(invocation: ChatToolInvocation): TowerRosterMember? {
    val arguments = runCatching { JSONObject(invocation.argumentsJson) }.getOrNull()
    val output = invocation.outputJson.trim()
    val failed = !invocation.isRunning && output.isNotBlank() &&
        output.contains("failed", ignoreCase = true) &&
        !output.contains("\"ok\"", ignoreCase = true)
    if (isTowerToolName(invocation.toolName) &&
        invocation.toolName.replace("_", "").equals("TowerSpawn", ignoreCase = true)
    ) {
        val name = arguments?.optString("name").orEmpty().trim()
        val kind = arguments?.optString("kind").orEmpty().trim().ifBlank { "worker" }
        return TowerRosterMember(
            toolCallId = invocation.id,
            name = name.ifBlank { kind },
            kind = kind,
            missionId = arguments?.optString("mission_id").orEmpty().trim(),
            reviewTarget = arguments?.optString("review_target").orEmpty().trim(),
            instructions = arguments?.optString("instructions").orEmpty().trim(),
            running = invocation.isRunning,
            failed = failed,
            output = output,
        )
    }
    val profile = arguments?.optString("subagent_type").orEmpty()
    val isWorker = profile.equals(TowerWorkerProfile, ignoreCase = true) ||
        invocation.toolName.contains(TowerWorkerProfile, ignoreCase = true)
    if (!isWorker) return null
    val description = arguments?.optString("description").orEmpty().trim()
        .ifBlank { arguments?.optString("name").orEmpty().trim() }
        .ifBlank { TowerWorkerProfile }
    return TowerRosterMember(
        toolCallId = invocation.id,
        name = description,
        kind = "worker",
        missionId = arguments?.optString("mission_id").orEmpty().trim(),
        reviewTarget = "",
        instructions = arguments?.optString("prompt").orEmpty().trim(),
        running = invocation.isRunning,
        failed = failed,
        output = output,
    )
}

private val TowerToolNames = setOf(
    "towerinit",
    "towerplan",
    "towerspawn",
    "towermerge",
    "towerteardown",
    "towersend",
    "towerinbox",
    "towerfinding",
    "towerreview",
    "towermission",
    "towerstatus",
)
