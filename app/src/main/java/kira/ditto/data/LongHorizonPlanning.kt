package kira.ditto.data

import kira.ditto.data.kimi.PendingPermissionRequest

enum class DeskLeadKind {
    None,
    Research,
    QuickGui,
    LongHorizon,
}

internal fun resolveDeskLeadKind(
    agentModeEnabled: Boolean,
    planMode: Boolean,
    longHorizonApproved: Boolean,
    hasOpenTodos: Boolean,
): DeskLeadKind = when {
    agentModeEnabled && (longHorizonApproved || hasOpenTodos) -> DeskLeadKind.LongHorizon
    agentModeEnabled -> DeskLeadKind.QuickGui
    planMode -> DeskLeadKind.Research
    else -> DeskLeadKind.None
}

internal fun isPlanExitPermission(title: String, kind: String = ""): Boolean {
    val titleNorm = title.trim().lowercase()
    val kindNorm = kind.trim().lowercase()
    if (titleNorm.contains("exitplanmode") || titleNorm.contains("exit plan")) return true
    if (titleNorm.contains("plan mode") && (titleNorm.contains("exit") || titleNorm.contains("submit"))) {
        return true
    }
    return kindNorm.contains("plan") && (titleNorm.contains("plan") || titleNorm.contains("exit"))
}

internal fun isPlanApprovalOption(optionId: String): Boolean {
    val id = optionId.trim().lowercase()
    if (id.isBlank()) return false
    if (id.contains("reject") || id.contains("revise") || id.contains("deny")) return false
    return id.startsWith("plan_opt_") ||
        id.contains("approve") ||
        id == "plan_accept" ||
        id == "plan_confirm"
}

internal fun isPlanApprovalAnswer(
    request: PendingPermissionRequest,
    optionId: String,
): Boolean {
    if (!isPlanExitPermission(request.toolCallTitle, request.toolCallKind)) return false
    if (!isPlanApprovalOption(optionId)) return false
    return request.options.any { it.optionId == optionId }
}

internal fun sanitizeEvidenceRelativePath(raw: String): String {
    val trimmed = raw.trim().replace('\\', '/').trimStart('/')
    if (trimmed.isBlank()) return ""
    val parts = trimmed.split('/').filter { segment ->
        segment.isNotBlank() && segment != "." && segment != ".."
    }
    if (parts.isEmpty()) return ""
    val joined = parts.joinToString("/")
    val allowed = joined.startsWith("reports/") ||
        joined.startsWith(".aether/") ||
        joined.startsWith("workspace/reports/")
    return if (allowed) joined.removePrefix("workspace/") else ""
}

internal fun resolveEvidencePersistPath(persist: Boolean, persistPath: String, nowMillis: Long): String {
    val explicit = sanitizeEvidenceRelativePath(persistPath)
    if (explicit.isNotBlank()) return explicit
    if (!persist) return ""
    return "reports/evidence/shots/$nowMillis.jpg"
}

const val DeviceCatalogResearchReminder =
    "You are researching a multi-app phone task in Plan mode. Do not operate the GUI. " +
        "Call mcp__device_catalog__list_apps (read-only, no virtual display) and map which " +
        "installed apps can do which parts of the user's goal. If an app is missing, say so " +
        "and suggest an installed alternative or the browser. Write the plan file, call " +
        "SetTodoList with one step per app or deliverable, then ExitPlanMode so the user can " +
        "approve. Do not call Agent(subagent_type=\"phone\"), do not launch, do not tap. " +
        "After ExitPlanMode, wait; the host will start Agent Mode and continue."

const val LongHorizonLeadReminder =
    "A long-horizon plan was approved. You are the desk lead. Do not use the one-shot " +
        "single-phone shortcut when the user named several apps. Relay with Kimi's native Agent " +
        "and AgentSwarm tools:\n" +
        "- Research, web, or files: you or Agent(subagent_type=\"explore\"). Never phone.\n" +
        "- Independent GUI todos on different apps: one AgentSwarm with subagent_type=\"phone\", " +
        "items = those exact app names, and a prompt_template containing {{item}}. Use sequential " +
        "Agent(subagent_type=\"phone\") only when a later app depends on an earlier result.\n" +
        "- Each GUI todo: name the installed app, the goal, and a persist_path under " +
        "reports/<slug>/shots/ for one evidence screenshot. Wait for GUI_TASK_SUCCEEDED/FAILED/" +
        "NEEDS_TEACHING/WATCHING.\n" +
        "- Long waits: GUI_TASK_WATCHING then CronCreate on THIS desk-lead session (same as usual). CronDelete when done.\n" +
        "- Final report: YOU Write reports/<slug>/README.md with markdown images pointing at " +
        "those shots. Do not ask phone to write the markdown. The README and the user-facing " +
        "简体中文 reply must cover every todo, not only the last app. If a tool result includes " +
        "completed_this_turn, treat it as the checklist.\n" +
        "Before a GUI step, SetTodoList in_progress; after a verified report, mark it completed. " +
        "Do not call phone MCP tools yourself. Reply to the user in 简体中文 without GUI_TASK_* markers. " +
        "If a phone report contains everme_scene_fact, mem_save_fact it. When every todo is " +
        "completed, write the README and stop."

internal const val LongHorizonExecuteUserVisibleText = "计划已批准，开始按清单执行。"

internal val LongHorizonExecuteHiddenPrompt =
    "The user approved the plan. Agent Mode is now on. Follow the long-horizon desk-lead " +
        "contract: pick the next pending todo, relay it to the matching agent (explore vs phone), " +
        "persist evidence shots under reports/, update SetTodoList, and when finished Write " +
        "reports/<slug>/README.md with markdown images. Start now."
