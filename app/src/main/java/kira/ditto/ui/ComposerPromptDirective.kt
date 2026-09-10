package kira.ditto.ui

/**
 * One-shot composer chips (Goal / Swarm / Tower) that turn the next sent message into a
 * directive prompt for the agent instead of a plain chat message.
 *
 * The CLI's ACP layer only implements a handful of built-in commands, so the
 * composer cannot send "/goal" or "/swarm" as text. Instead the chip rides on
 * the outgoing [ChatMessage.promptDirective] and the hidden instruction below
 * is prepended to the prompt that actually reaches the agent, mirroring what
 * the kimi-code CLI does internally (host goal-intake prompt, swarm
 * enter-reminder). The user bubble keeps showing only the user's own text.
 */
enum class ComposerPromptDirective(val storageValue: String) {
    Goal("goal"),
    Swarm("swarm"),
    Tower("tower"),
    ;

    companion object {
        fun fromStorageValue(value: String?): ComposerPromptDirective? =
            entries.firstOrNull { it.storageValue == value?.trim()?.lowercase() }
    }
}

/**
 * Removes a trailing "@query" mention trigger from [input], e.g. after the user
 * picked a plugin from the mention popup. Returns the cleaned text and the
 * cursor position (right where the trigger started), or null when the input
 * does not end with a valid mention trigger. A trigger is only valid when the
 * '@' starts the input or follows whitespace and the query contains no
 * whitespace, so text like "email me at a@b" is left untouched.
 */
fun removeMentionTriggerToken(input: String): Pair<String, Int>? {
    val at = input.lastIndexOf('@')
    if (at < 0) return null
    val query = fileMentionQuery(input) ?: return null
    val tokenEnd = (at + 1 + query.length).coerceAtMost(input.length)
    val next = input.substring(0, at) + input.substring(tokenEnd)
    return next to at.coerceAtMost(next.length)
}

/** CLI wording: packages/agent-core/src/agent/swarm/enter-reminder.md (kimi-code 0.41.0). */
private val SwarmEnterReminder: String = """
## Swarm Mode

You are now in "agent swarm" mode. The user may send tasks that require a large number of parallel subagents.

## Workflow

You do not need to use TodoList to record this workflow.

1. First, you may need to do a small amount of exploratory work before deciding how to divide the task across subagents. You may not need subagents during this exploratory phase.

2. After exploring, if you are convinced no subagent is needed to complete the task, tell the user why and wait for further instructions; otherwise, continue with the appropriate delegation.

3. Once you have enough context, do not handle the main work yourself. Use AgentSwarm with a `prompt_template` containing the `{{item}}` placeholder and an `items` array for the requested or appropriate number of subagents, partitioning the problem so each item gives one subagent a distinct part of the work. Pass `subagent_type` when the whole swarm should use a non-default subagent profile.

## Coordination

- Give each subagent a distinct scope of work.
- Avoid duplicating work across subagents.
- Avoid assigning conflicting changes or responsibilities to different subagents.
- Remember that subagents have your full capabilities. Do not overload their prompts with excessive detail; only describe the necessary background and each subagent's specific task.
- Unless the user explicitly specifies a lower limit, do not try to conserve the number of agents. AgentSwarm supports up to 128 subagents and queues launches automatically, so decompose work as finely as possible while keeping subagent responsibilities non-conflicting; combine tasks only when they are genuinely inseparable. If the subagents only need to read, inspect, or report back without making changes, their scopes may overlap slightly.
""".trimIndent()

/** Condensed from packages/agent-core-v2 tower-mode-full-reminder.md (kimi-code 0.41.0). */
private val TowerEnterReminder: String = """
## Tower Mode

Tower mode is active. You are the control tower for this repository — you plan missions, spawn worker and reviewer agents, route information, merge branches, and keep the human informed. You never write product code yourself.

## Roles

- The human owns the objective and may speak, launch, or redirect work at any time.
- You (the tower) are exactly one lead. Workers and reviewers are subagents you spawn with TowerSpawn.
- Each worker owns one mission in its own git worktree; reviewers audit branches.

## Workflow

Do not use TodoList in tower mode. Run the protocol only through Tower* tools; never create or edit files under `.tower/` by hand.

1. TowerInit — create `.tower/` if needed. If the directory is not a git repo, the engine inits git and makes an initial commit (empty commit, or a snapshot of present files).
2. TowerPlan — split the objective into 2–4 missions with disjoint file scopes. Mark read-only investigation as kind "survey".
3. TowerSpawn — one worker per unblocked mission, fired back to back (kind "worker"). Spawn reviewers with kind "reviewer" and review_target when a branch needs review.
4. Supervise on every wake with TowerInbox and TowerStatus. Relay wake-ups; merge only with TowerMerge after a clean review.
5. TowerTeardown promptly when every mission is merged and the inbox is clear. Teardown does not exit tower mode.

Workers inherit the secondary model pool when configured; reviewers use the primary model.
""".trimIndent()

/**
 * Hidden instruction prepended to the user's text when a directive chip is
 * attached. Borrowed from the CLI's own goal/swarm wording so the model treats
 * it like the CLI's built-in flows. Never shown in the user bubble.
 */
fun composerDirectivePromptPrefix(directive: ComposerPromptDirective): String = when (directive) {
    ComposerPromptDirective.Goal ->
        "This is a host goal-intake prompt. The user asked to start a goal and work autonomously " +
            "toward the outcome described below.\n" +
            "Call the CreateGoal tool with the user's text as the objective. Include a " +
            "completionCriterion only when it can be stated without inventing new requirements. " +
            "Do not ask for confirmation before calling CreateGoal; the user already confirmed by " +
            "choosing Goal mode. After creating the goal, keep pursuing it across turns until it " +
            "completes.\n\nUser's goal:\n"

    ComposerPromptDirective.Swarm -> SwarmEnterReminder + "\n\nThe user's task:\n"

    ComposerPromptDirective.Tower -> TowerEnterReminder + "\n\nThe user's task:\n"
}

/**
 * The prompt text that should actually reach the agent for a user message.
 * Plain messages pass through unchanged; unknown directive values are ignored.
 */
fun composerDirectivePromptText(
    directiveStorageValue: String,
    userText: String,
): String {
    val directive = ComposerPromptDirective.fromStorageValue(directiveStorageValue) ?: return userText
    return composerDirectivePromptPrefix(directive) + userText
}
