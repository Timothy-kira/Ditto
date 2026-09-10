package kira.ditto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionGoalProgressTest {
    @Test
    fun classifyMatchesToolNamesAndDescriptionTitles() {
        assertEquals(GoalToolKind.Create, classifyGoalToolCall("CreateGoal"))
        assertEquals(GoalToolKind.Create, classifyGoalToolCall("Creating a goal"))
        assertEquals(GoalToolKind.SetBudget, classifyGoalToolCall("SetGoalBudget"))
        assertEquals(GoalToolKind.SetBudget, classifyGoalToolCall("Setting goal budget: 20 turns"))
        assertEquals(GoalToolKind.UpdateStatus, classifyGoalToolCall("UpdateGoal"))
        assertEquals(GoalToolKind.UpdateStatus, classifyGoalToolCall("Setting goal status: complete"))

        assertNull(classifyGoalToolCall("Bash"))
        assertNull(classifyGoalToolCall("Launching coder agent: x"))
        assertNull(classifyGoalToolCall("GetGoal"))
        assertNull(classifyGoalToolCall("Setting something else"))
    }

    @Test
    fun createGoalParsesFullSnapshot() {
        val output = """
            {
              "goal": {
                "objective": "Ship the release",
                "completionCriterion": "CI green",
                "status": "active",
                "turnsUsed": 3,
                "tokensUsed": 42000,
                "wallClockMs": 95000,
                "budget": {
                  "tokenBudget": 500000,
                  "turnBudget": 20,
                  "wallClockBudgetMs": 1800000,
                  "remainingTokens": 458000,
                  "remainingTurns": 17,
                  "remainingWallClockMs": 1705000,
                  "tokenBudgetReached": false,
                  "turnBudgetReached": false,
                  "wallClockBudgetReached": false,
                  "overBudget": false
                }
              }
            }
        """.trimIndent()

        val snapshot = reduceGoalToolResult(
            current = null,
            kind = GoalToolKind.Create,
            argumentsJson = """{"objective":"Ship the release"}""",
            outputJson = output,
            nowMillis = 1_000L,
        )!!

        assertEquals("Ship the release", snapshot.objective)
        assertEquals("CI green", snapshot.completionCriterion)
        assertEquals("active", snapshot.status)
        assertEquals(3L, snapshot.turnsUsed)
        assertEquals(42_000L, snapshot.tokensUsed)
        assertEquals(95_000L, snapshot.wallClockMs)
        assertEquals(500_000L, snapshot.tokenBudget)
        assertEquals(20L, snapshot.turnBudget)
        assertEquals(1_800_000L, snapshot.wallClockBudgetMs)
        assertEquals(1_000L, snapshot.updatedAtMillis)
        assertEquals(0.084f, snapshot.tokenFraction!!, 0.001f)
        assertEquals(0.15f, snapshot.turnFraction!!, 0.001f)
    }

    @Test
    fun createGoalDegradesToObjectiveWhenFieldsMissing() {
        val snapshot = reduceGoalToolResult(
            current = null,
            kind = GoalToolKind.Create,
            argumentsJson = """{"objective":"Fallback objective","completionCriterion":"done"}""",
            outputJson = """{"goal": {}}""",
            nowMillis = 7L,
        )!!

        assertEquals("Fallback objective", snapshot.objective)
        assertEquals("done", snapshot.completionCriterion)
        assertEquals("active", snapshot.status)
        assertNull(snapshot.tokenBudget)
        assertNull(snapshot.tokenFraction)
    }

    @Test
    fun createGoalKeepsCurrentWhenOutputIsNotSnapshotJson() {
        val existing = SessionGoalSnapshot(objective = "Keep me", status = "active")
        assertEquals(
            existing,
            reduceGoalToolResult(existing, GoalToolKind.Create, "{}", "Creating a goal failed", 0L),
        )
        assertNull(reduceGoalToolResult(null, GoalToolKind.Create, "{}", "not json", 0L))
        assertNull(reduceGoalToolResult(null, GoalToolKind.Create, "{}", """{"goal": null}""", 0L))
    }

    @Test
    fun setGoalBudgetMergesIntoExistingSnapshot() {
        val existing = SessionGoalSnapshot(objective = "Goal", status = "active", tokensUsed = 100L)

        val withTurns = reduceGoalToolResult(
            existing,
            GoalToolKind.SetBudget,
            """{"value":20,"unit":"turns"}""",
            "Goal budget set: 20 turns.",
            5L,
        )!!
        assertEquals(20L, withTurns.turnBudget)
        assertEquals("Goal", withTurns.objective)
        assertEquals(5L, withTurns.updatedAtMillis)

        val withTokens = reduceGoalToolResult(
            withTurns,
            GoalToolKind.SetBudget,
            """{"value":500000,"unit":"tokens"}""",
            "Goal budget set: 500000 tokens. The goal has already reached this budget and will stop now.",
            6L,
        )!!
        assertEquals(500_000L, withTokens.tokenBudget)
        assertEquals(20L, withTokens.turnBudget)

        val withTime = reduceGoalToolResult(
            withTokens,
            GoalToolKind.SetBudget,
            """{"value":30,"unit":"minutes"}""",
            "Goal budget set: 30 minutes.",
            7L,
        )!!
        assertEquals(1_800_000L, withTime.wallClockBudgetMs)
    }

    @Test
    fun setGoalBudgetIgnoresFailuresAndMissingGoal() {
        val existing = SessionGoalSnapshot(objective = "Goal", status = "active")
        assertEquals(
            existing,
            reduceGoalToolResult(existing, GoalToolKind.SetBudget, """{"value":5,"unit":"turns"}""", "Goal budget not set: no current goal.", 0L),
        )
        assertNull(
            reduceGoalToolResult(null, GoalToolKind.SetBudget, """{"value":5,"unit":"turns"}""", "Goal budget set: 5 turns.", 0L),
        )
        assertEquals(
            existing,
            reduceGoalToolResult(existing, GoalToolKind.SetBudget, "not json", "Goal budget set: 5 turns.", 0L),
        )
    }

    @Test
    fun updateGoalAppliesConfirmedStatuses() {
        val active = SessionGoalSnapshot(objective = "Goal", status = "active")

        val completed = reduceGoalToolResult(
            active,
            GoalToolKind.UpdateStatus,
            """{"status":"complete"}""",
            "Goal completed successfully: all done.\nWorked 4 turns over 2m10s, using 9.2k tokens.",
            9L,
        )!!
        assertEquals("complete", completed.status)
        assertEquals(9L, completed.updatedAtMillis)

        val blocked = reduceGoalToolResult(
            active,
            GoalToolKind.UpdateStatus,
            """{"status":"blocked"}""",
            "Goal blocked.\nWorked 1 turn over 10s, using 1.0k tokens.",
            10L,
        )!!
        assertEquals("blocked", blocked.status)

        val resumed = reduceGoalToolResult(
            blocked,
            GoalToolKind.UpdateStatus,
            """{"status":"active"}""",
            "Goal resumed.",
            11L,
        )!!
        assertEquals("active", resumed.status)
    }

    @Test
    fun updateGoalIgnoresFailuresAndAppliesRequestedStatusWhenOutputMissing() {
        val active = SessionGoalSnapshot(objective = "Goal", status = "active")
        assertEquals(
            active,
            reduceGoalToolResult(active, GoalToolKind.UpdateStatus, """{"status":"complete"}""", "Goal not completed: no active goal.", 0L),
        )
        assertEquals(
            active,
            reduceGoalToolResult(active, GoalToolKind.UpdateStatus, """{"status":"nope"}""", "Invalid goal status. Use `active`, `complete`, or `blocked`.", 0L),
        )
        // Blank output (missing update): fall back to the requested status.
        assertEquals(
            "complete",
            reduceGoalToolResult(active, GoalToolKind.UpdateStatus, """{"status":"complete"}""", "", 0L)?.status,
        )
        assertNull(reduceGoalToolResult(null, GoalToolKind.UpdateStatus, """{"status":"complete"}""", "Goal resumed.", 0L))
    }

    @Test
    fun budgetFractionsClampAndStayNullWithoutBudget() {
        val snapshot = SessionGoalSnapshot(
            objective = "Goal",
            tokensUsed = 750L,
            tokenBudget = 500L,
            turnsUsed = 2L,
        )
        assertEquals(1f, snapshot.tokenFraction!!, 0.0001f)
        assertNull(snapshot.turnFraction)
        assertNull(snapshot.wallClockFraction)
        assertTrue(snapshot.objective.isNotBlank())
    }
}
