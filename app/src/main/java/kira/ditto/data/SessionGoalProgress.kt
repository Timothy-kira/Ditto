package kira.ditto.data

import org.json.JSONObject

/**
 * Session-level projection of the CLI's goal state, reduced from goal tool
 * calls (CreateGoal / SetGoalBudget / UpdateGoal) as they stream through ACP.
 *
 * Only CreateGoal carries a full JSON snapshot (`{ "goal": { ... } }`, goalId
 * stripped upstream); SetGoalBudget and UpdateGoal return plain status text,
 * so their reductions merge the confirmed change into the snapshot we already
 * have. Everything is tolerant: unparseable payloads leave the snapshot
 * untouched and missing fields degrade to objective + status only.
 */
data class SessionGoalSnapshot(
    val objective: String = "",
    val completionCriterion: String = "",
    /** active | paused | blocked | complete (empty when unknown). */
    val status: String = "",
    val turnsUsed: Long = 0L,
    val tokensUsed: Long = 0L,
    val wallClockMs: Long = 0L,
    val tokenBudget: Long? = null,
    val turnBudget: Long? = null,
    val wallClockBudgetMs: Long? = null,
    val updatedAtMillis: Long = 0L,
) {
    val tokenFraction: Float?
        get() = budgetFraction(tokensUsed, tokenBudget)

    val turnFraction: Float?
        get() = budgetFraction(turnsUsed, turnBudget)

    val wallClockFraction: Float?
        get() = budgetFraction(wallClockMs, wallClockBudgetMs)

    private fun budgetFraction(used: Long, budget: Long?): Float? {
        val limit = budget ?: return null
        if (limit <= 0L) return null
        return (used.toFloat() / limit.toFloat()).coerceIn(0f, 1f)
    }
}

internal enum class GoalToolKind {
    Create,
    SetBudget,
    UpdateStatus,
}

private val GoalBudgetTimeUnits = mapOf(
    "milliseconds" to 1L,
    "seconds" to 1_000L,
    "minutes" to 60_000L,
    "hours" to 3_600_000L,
)

/**
 * Classifies a tool call as goal-related from its ACP title. The title is
 * either the bare tool name (lazy streaming create) or the CLI's execution
 * description ("Creating a goal", "Setting goal budget: …", "Setting goal
 * status: …").
 */
internal fun classifyGoalToolCall(toolName: String): GoalToolKind? {
    val title = toolName.trim()
    return when {
        title.equals("CreateGoal", ignoreCase = true) ||
            title.equals("Creating a goal", ignoreCase = true) -> GoalToolKind.Create
        title.equals("SetGoalBudget", ignoreCase = true) ||
            title.startsWith("Setting goal budget", ignoreCase = true) -> GoalToolKind.SetBudget
        title.equals("UpdateGoal", ignoreCase = true) ||
            title.startsWith("Setting goal status", ignoreCase = true) -> GoalToolKind.UpdateStatus
        else -> null
    }
}

/**
 * Folds one finished goal tool call into the session's snapshot. Returns the
 * (possibly unchanged) snapshot; a null [current] stays null unless [kind] is
 * [GoalToolKind.Create] with a parseable goal payload.
 */
internal fun reduceGoalToolResult(
    current: SessionGoalSnapshot?,
    kind: GoalToolKind,
    argumentsJson: String,
    outputJson: String,
    nowMillis: Long,
): SessionGoalSnapshot? = when (kind) {
    GoalToolKind.Create -> reduceCreateGoal(current, argumentsJson, outputJson, nowMillis)
    GoalToolKind.SetBudget -> reduceSetGoalBudget(current, argumentsJson, outputJson, nowMillis)
    GoalToolKind.UpdateStatus -> reduceUpdateGoal(current, argumentsJson, outputJson, nowMillis)
}

private fun reduceCreateGoal(
    current: SessionGoalSnapshot?,
    argumentsJson: String,
    outputJson: String,
    nowMillis: Long,
): SessionGoalSnapshot? {
    val output = parseJson(outputJson) ?: return current
    val goal = output.optJSONObject("goal") ?: return current
    val arguments = parseJson(argumentsJson)
    return SessionGoalSnapshot(
        objective = goal.optString("objective").trim()
            .ifBlank { arguments?.optString("objective")?.trim().orEmpty() },
        completionCriterion = goal.optString("completionCriterion").trim()
            .ifBlank { arguments?.optString("completionCriterion")?.trim().orEmpty() },
        status = goal.optString("status").trim().ifBlank { "active" },
        turnsUsed = goal.optLong("turnsUsed"),
        tokensUsed = goal.optLong("tokensUsed"),
        wallClockMs = goal.optLong("wallClockMs"),
        tokenBudget = goal.optJSONObject("budget").nullableLong("tokenBudget"),
        turnBudget = goal.optJSONObject("budget").nullableLong("turnBudget"),
        wallClockBudgetMs = goal.optJSONObject("budget").nullableLong("wallClockBudgetMs"),
        updatedAtMillis = nowMillis,
    )
}

private fun reduceSetGoalBudget(
    current: SessionGoalSnapshot?,
    argumentsJson: String,
    outputJson: String,
    nowMillis: Long,
): SessionGoalSnapshot? {
    // Failures read "Goal budget not set: …"; only confirmed sets mutate state.
    if (!outputJson.trimStart().startsWith("Goal budget set")) return current
    val existing = current ?: return null
    val arguments = parseJson(argumentsJson) ?: return current
    val value = arguments.optLong("value")
    if (value <= 0L) return current
    return when (arguments.optString("unit").trim().lowercase()) {
        "turns" -> existing.copy(turnBudget = value, updatedAtMillis = nowMillis)
        "tokens" -> existing.copy(tokenBudget = value, updatedAtMillis = nowMillis)
        in GoalBudgetTimeUnits -> existing.copy(
            wallClockBudgetMs = value * GoalBudgetTimeUnits.getValue(arguments.optString("unit").trim().lowercase()),
            updatedAtMillis = nowMillis,
        )
        else -> current
    }
}

private fun reduceUpdateGoal(
    current: SessionGoalSnapshot?,
    argumentsJson: String,
    outputJson: String,
    nowMillis: Long,
): SessionGoalSnapshot? {
    val existing = current ?: return null
    val output = outputJson.trimStart()
    val newStatus = when {
        output.startsWith("Goal resumed") -> "active"
        output.startsWith("Goal completed successfully") -> "complete"
        output.startsWith("Goal blocked") -> "blocked"
        // "Goal not resumed/completed/blocked", "Invalid goal status", …
        output.isNotEmpty() -> return current
        else -> parseJson(argumentsJson)?.optString("status")?.trim()
            ?.takeIf { it == "active" || it == "complete" || it == "blocked" }
            ?: return current
    }
    return existing.copy(status = newStatus, updatedAtMillis = nowMillis)
}

private fun parseJson(raw: String): JSONObject? =
    runCatching { JSONObject(raw) }.getOrNull()

private fun JSONObject?.nullableLong(key: String): Long? =
    if (this != null && has(key) && !isNull(key)) optLong(key) else null
