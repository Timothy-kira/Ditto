package kira.ditto.data

import kira.ditto.ui.ChatMessage
import kira.ditto.ui.MessageAuthor
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KimiSessionEventsTest {
    @Test
    fun planEntriesParseFullReplacementProjection() {
        val entries = parseSessionPlanEntries(
            JSONObject().put(
                "entries",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("content", "Scan repo")
                            .put("priority", "high")
                            .put("status", "completed"),
                    )
                    .put(JSONObject().put("content", "Edit file")),
            ),
        )

        assertEquals(2, entries.size)
        assertEquals(SessionPlanEntry("Scan repo", "high", "completed"), entries[0])
        assertEquals("pending", entries[1].status)
        assertEquals(emptyList<SessionPlanEntry>(), parseSessionPlanEntries(JSONObject()))
    }

    @Test
    fun planEntriesAcceptTitleDoneAndNestedV2Shape() {
        val entries = parseSessionPlanEntries(
            JSONObject().put(
                "plan",
                JSONObject()
                    .put("type", "items")
                    .put("planId", "plan-1")
                    .put(
                        "entries",
                        JSONArray()
                            .put(JSONObject().put("title", "Write tests").put("status", "in progress"))
                            .put(JSONObject().put("content", "Ship").put("status", "done")),
                    ),
            ),
        )
        assertEquals(2, entries.size)
        assertEquals("in_progress", entries[0].status)
        assertEquals("Write tests", entries[0].content)
        assertEquals("completed", entries[1].status)
        assertTrue(sessionPlanEntryIsOpen(entries[0].status))
        assertTrue(sessionPlanEntryIsCompleted(entries[1].status))
    }

    @Test
    fun todoToolArgumentsProjectIntoPlanEntries() {
        val parsed = parseSessionPlanEntriesFromTool(
            toolName = "TodoList",
            toolKind = "",
            argumentsJson = JSONObject()
                .put(
                    "todos",
                    JSONArray()
                        .put(JSONObject().put("title", "Scan apps").put("status", "pending"))
                        .put(JSONObject().put("title", "Open WeChat").put("status", "in_progress")),
                )
                .toString(),
            outputJson = null,
        )
        assertEquals(2, parsed?.size)
        assertEquals("Scan apps", parsed?.get(0)?.content)
        assertEquals("in_progress", parsed?.get(1)?.status)
        assertNull(
            parseSessionPlanEntriesFromTool(
                toolName = "AgentSwarm",
                toolKind = "agent",
                argumentsJson = JSONObject()
                    .put("items", JSONArray().put(JSONObject().put("title", "not a todo")))
                    .toString(),
                outputJson = null,
            ),
        )
    }

    @Test
    fun contextUsageParsesAndComputesFraction() {
        assertNull(parseSessionContextUsage(JSONObject().put("used", -1).put("size", -1)))

        val usage = parseSessionContextUsage(JSONObject().put("used", 32_000).put("size", 128_000))!!
        assertEquals(32_000L, usage.usedTokens)
        assertEquals(128_000L, usage.windowTokens)
        assertEquals(0.25f, usage.fraction!!, 0.001f)

        val unknownWindow = parseSessionContextUsage(JSONObject().put("used", 100))!!
        assertNull(unknownWindow.fraction)
        assertNull(parseSessionContextUsage(JSONObject().put("used", -1).put("size", 128_000)))
        assertEquals(
            SessionContextUsage(24_200L, 128_000L),
            mergeSessionContextUsage(
                SessionContextUsage(24_200L, 128_000L),
                SessionContextUsage(0L, 128_000L),
            ),
        )
        assertEquals(
            SessionContextUsage(8_000L, 128_000L),
            mergeSessionContextUsage(
                SessionContextUsage(24_200L, 200_000L),
                SessionContextUsage(8_000L, 128_000L),
            ),
        )
    }

    @Test
    fun agentSlashCommandsParseDeduplicateAndStripPrefix() {
        val commands = parseAgentSlashCommands(
            JSONObject().put(
                "commands",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("name", "/compact")
                            .put("description", "Compact context"),
                    )
                    .put(
                        JSONObject()
                            .put("name", "Compact")
                            .put("description", "duplicate"),
                    )
                    .put(
                        JSONObject()
                            .put("name", "skill:review")
                            .put("description", "Review code")
                            .put("input", JSONObject().put("hint", "<path>")),
                    )
                    .put(JSONObject().put("description", "nameless")),
            ),
        )

        assertEquals(2, commands.size)
        assertEquals("compact", commands[0].name)
        assertEquals("Compact context", commands[0].description)
        assertEquals("skill:review", commands[1].name)
        assertEquals("<path>", commands[1].argumentHint)
        assertEquals(emptyList<AgentSlashCommand>(), parseAgentSlashCommands(JSONObject()))
    }

    @Test
    fun configOptionUpdateProjectsKnownIdsOntoAgentConfig() {
        val initial = SessionAgentConfig()
        val withMode = initial.applyConfigOptionUpdate(
            JSONObject().put("configId", "mode").put("value", "plan"),
        )
        assertEquals("plan", withMode.modeId)
        assertNull(withMode.modelId)

        val withModel = withMode.applyConfigOptionUpdate(
            JSONObject().put("config_id", "model").put("currentValue", "kimi/k2"),
        )
        assertEquals("plan", withModel.modeId)
        assertEquals("kimi/k2", withModel.modelId)

        val withThinking = withModel.applyConfigOptionUpdate(
            JSONObject().put("id", "thinking").put("value", "high"),
        )
        assertEquals("high", withThinking.thinkingLevel)

        val untouched = withThinking.applyConfigOptionUpdate(
            JSONObject().put("configId", "something_else").put("value", "x"),
        )
        assertEquals(withThinking, untouched)

        // Agent replay is authoritative: a later value overwrites the old one.
        val overwritten = withThinking.applyConfigOptionUpdate(
            JSONObject().put("configId", "mode").put("value", "yolo"),
        )
        assertEquals("yolo", overwritten.modeId)
    }

    @Test
    fun currentModeAndSessionInfoTitlesParse() {
        assertEquals("auto", parseCurrentModeId(JSONObject().put("modeId", "auto")))
        assertNull(parseCurrentModeId(JSONObject().put("modeId", " ")))

        assertEquals(
            "My session",
            parseSessionInfoTitle(JSONObject().put("title", "My session")),
        )
        assertNull(parseSessionInfoTitle(JSONObject().put("title", JSONObject.NULL)))
        assertNull(parseSessionInfoTitle(JSONObject().put("title", "null")))
        assertNull(parseSessionInfoTitle(JSONObject().put("title", "  ")))
    }

    @Test
    fun toolDiffsRoundTripThroughPayloadJson() {
        val payload = JSONObject().put(
            "diffs",
            JSONArray().put(
                JSONObject()
                    .put("path", "/workspace/a.txt")
                    .put("oldText", "old")
                    .put("newText", "new"),
            ),
        )

        val json = toolDiffsJson(payload)!!
        val diffs = parseToolDiffs(json)
        assertEquals(1, diffs.size)
        assertEquals("/workspace/a.txt", diffs[0].path)
        assertEquals("old", diffs[0].oldText)
        assertEquals("new", diffs[0].newText)

        assertNull(toolDiffsJson(JSONObject()))
        assertEquals(emptyList<kira.ditto.ui.ToolCallDiff>(), parseToolDiffs(null))
        assertEquals(emptyList<kira.ditto.ui.ToolCallDiff>(), parseToolDiffs("not json"))
    }

    @Test
    fun planContentFingerprintIgnoresStatus() {
        val pending = listOf(SessionPlanEntry("Scan apps", "high", "pending"))
        val completed = listOf(SessionPlanEntry("Scan apps", "high", "completed"))
        assertEquals(sessionPlanContentFingerprint(pending), sessionPlanContentFingerprint(completed))
        assertTrue(sessionPlanContentFingerprint(pending).isNotEmpty())
    }

    @Test
    fun firstPlanAnchorsToActiveTurnEvenIfOlderAssistantExists() {
        val placement = nextSessionPlanPlacement(
            previousEntries = emptyList(),
            previousFingerprint = "",
            previousMessageId = null,
            previousGroupId = null,
            nextEntries = listOf(SessionPlanEntry("Scan apps", "medium", "pending")),
            activeResponseGroupId = "g2",
            messages = listOf(
                userMessage("u1"),
                agentMessage("a1", "g1"),
                userMessage("u2"),
            ),
        )
        assertEquals("u2", placement.messageId)
        assertEquals("g2", placement.groupId)
        assertEquals("Scan apps", placement.contentFingerprint)
    }

    @Test
    fun statusOnlyPlanUpdateKeepsPreviousAnchor() {
        val previous = listOf(SessionPlanEntry("Scan apps", "medium", "pending"))
        val updated = listOf(SessionPlanEntry("Scan apps", "medium", "completed"))
        val placement = nextSessionPlanPlacement(
            previousEntries = previous,
            previousFingerprint = sessionPlanContentFingerprint(previous),
            previousMessageId = "a1",
            previousGroupId = "g1",
            nextEntries = updated,
            activeResponseGroupId = "g2",
            messages = listOf(
                userMessage("u1"),
                agentMessage("a1", "g1"),
                userMessage("u2"),
            ),
        )
        assertEquals("a1", placement.messageId)
        assertEquals("g1", placement.groupId)
        assertEquals(sessionPlanContentFingerprint(previous), placement.contentFingerprint)
        assertEquals("completed", placement.entries.single().status)
    }

    @Test
    fun rewrittenPlanReanchorsToCurrentTurn() {
        val previous = listOf(SessionPlanEntry("Scan apps", "medium", "completed"))
        val rewritten = listOf(
            SessionPlanEntry("Scan apps", "medium", "completed"),
            SessionPlanEntry("Open WeChat", "medium", "pending"),
        )
        val placement = nextSessionPlanPlacement(
            previousEntries = previous,
            previousFingerprint = sessionPlanContentFingerprint(previous),
            previousMessageId = "a1",
            previousGroupId = "g1",
            nextEntries = rewritten,
            activeResponseGroupId = "g2",
            messages = listOf(
                userMessage("u1"),
                agentMessage("a1", "g1"),
                userMessage("u2"),
            ),
        )
        assertEquals("u2", placement.messageId)
        assertEquals("g2", placement.groupId)
    }

    @Test
    fun existingPlanWithoutFingerprintPinsToLastAssistantNotActiveTurn() {
        val previous = listOf(SessionPlanEntry("Scan apps", "medium", "pending"))
        val placement = nextSessionPlanPlacement(
            previousEntries = previous,
            previousFingerprint = "",
            previousMessageId = null,
            previousGroupId = null,
            nextEntries = previous,
            activeResponseGroupId = "g2",
            messages = listOf(
                userMessage("u1"),
                agentMessage("a1", "g1"),
                userMessage("u2"),
            ),
        )
        assertEquals("a1", placement.messageId)
        assertEquals("g1", placement.groupId)
    }

    @Test
    fun emptyPlanClearsAnchor() {
        val previous = listOf(SessionPlanEntry("Scan apps", "medium", "pending"))
        val placement = nextSessionPlanPlacement(
            previousEntries = previous,
            previousFingerprint = sessionPlanContentFingerprint(previous),
            previousMessageId = "a1",
            previousGroupId = "g1",
            nextEntries = emptyList(),
            activeResponseGroupId = "g2",
            messages = listOf(userMessage("u1"), agentMessage("a1", "g1")),
        )
        assertEquals(emptyList<SessionPlanEntry>(), placement.entries)
        assertNull(placement.messageId)
        assertNull(placement.groupId)
        assertEquals("", placement.contentFingerprint)
    }

    @Test
    fun sessionPlanHostKeyStaysOnCreatingAssistantAfterFollowUp() {
        val messages = listOf(
            userMessage("u1"),
            agentMessage("a1", "g1"),
            userMessage("u2"),
        )
        assertEquals("g1", sessionPlanHostKey(messages, "a1", "g1"))
        assertEquals("g1", sessionPlanHostKey(messages, null, null))
        assertEquals("u1", sessionPlanHostKey(messages, "u1", null))
    }

    private fun userMessage(id: String) = ChatMessage(
        id = id,
        author = MessageAuthor.User,
        text = "hi",
    )

    private fun agentMessage(id: String, groupId: String) = ChatMessage(
        id = id,
        author = MessageAuthor.Agent,
        text = "ok",
        responseGroupId = groupId,
    )
}
