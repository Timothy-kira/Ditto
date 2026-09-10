package kira.ditto.data

import java.io.File
import kira.ditto.data.pi.toPiCompletionResult
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageRecorderTest {
    @Test
    fun recordsTurnTitleAndFiltersBySource(): Unit = runBlocking {
        val store = InMemoryLlmUsageStore()
        val recorder = UsageRecorder(store, sessionsRoot = { File("missing") }, aetherSessionIdForKimi = { "" })
        recorder.recordTurn(
            sessionId = "s1",
            providerId = "kimi",
            modelId = "kimi-for-coding",
            usage = LlmTokenUsage(inputTokens = 10, outputTokens = 4, totalTokens = 14),
            usageSource = "api",
            startedAtMillis = 1,
            completedAtMillis = 2,
            firstTokenAtMillis = 1,
            messageId = "m1",
        )
        recorder.recordTitle(
            sessionId = "s1",
            providerId = "kimi",
            modelId = "kimi-for-coding",
            result = null,
            promptChars = 8,
        )
        val listed = recorder.listRecords()
        assertEquals(2, listed.size)
        assertEquals(1, listed.count { it.source == LlmUsageSources.Turn })
        assertEquals(1, listed.count { it.source == LlmUsageSources.Title })
        assertEquals("estimated", listed.first { it.source == LlmUsageSources.Title }.usageSource)
        assertEquals(14L, listed.filter { it.source == LlmUsageSources.Turn }.sumOf { it.effectiveTokenTotal() })
    }

    @Test
    fun messageBackfillIsIdempotent(): Unit = runBlocking {
        val store = InMemoryLlmUsageStore(
            messageRecords = listOf(
                LlmUsageRecord(
                    id = "turn:s1:m1",
                    sessionId = "s1",
                    source = LlmUsageSources.Turn,
                    usageSource = "api",
                    totalTokens = 9,
                ),
            ),
        )
        val recorder = UsageRecorder(store, sessionsRoot = { File("missing") }, aetherSessionIdForKimi = { "" })
        recorder.ensureMessageBackfill()
        recorder.ensureMessageBackfill()
        assertEquals(1, recorder.listRecords().size)
        assertEquals(9L, recorder.listRecords().single().effectiveTokenTotal())
    }

    @Test
    fun harvestsPhoneWireAndMainTurn(): Unit = runBlocking {
        val root = File.createTempFile("kimi-sessions", "").apply {
            delete()
            mkdirs()
        }
        val sessionDir = File(root, "wd/sess-1").apply { mkdirs() }
        File(sessionDir, "state.json").writeText(
            """{"agents":{"agt-phone":{"profileName":"phone"}}}""",
        )
        File(sessionDir, "agents/main").mkdirs()
        File(sessionDir, "agents/main/wire.jsonl").writeText(
            """{"type":"usage.record","model":"main","usage":{"inputOther":999,"output":9},"usageScope":"turn"}""" + "\n",
        )
        File(sessionDir, "agents/agt-phone").mkdirs()
        File(sessionDir, "agents/agt-phone/wire.jsonl").writeText(
            """
            {"type":"usage.record","model":"kimi-code/a","usage":{"inputOther":11,"output":2,"inputCacheRead":4,"inputCacheCreation":0},"usageScope":"turn"}
            {"type":"usage.record","model":"kimi-code/a","usage":{"inputOther":100,"output":5},"usageScope":"session"}
            """.trimIndent() + "\n",
        )
        val store = InMemoryLlmUsageStore()
        val recorder = UsageRecorder(
            store = store,
            sessionsRoot = { root },
            aetherSessionIdForKimi = { if (it == "sess-1") "chat-1" else "" },
        )
        recorder.harvestKimiSession("chat-1", "sess-1")
        recorder.harvestKimiSession("chat-1", "sess-1")
        val phone = recorder.listRecords().filter { it.source == LlmUsageSources.Phone }
        assertEquals(1, phone.size)
        assertEquals("chat-1", phone.single().sessionId)
        assertEquals(11L, phone.single().inputTokens)
        assertEquals(2L, phone.single().outputTokens)
        assertEquals(4L, phone.single().cachedInputTokens)
        assertEquals(17L, phone.single().effectiveTokenTotal())
        // The main agent used to be skipped here, which is why every conversation turn was recorded
        // as an estimate while the subagents beside it carried real provider numbers.
        val turns = recorder.listRecords().filter { it.source == LlmUsageSources.Turn }
        assertEquals(1, turns.size)
        assertEquals(999L, turns.single().inputTokens)
        assertEquals("api", turns.single().usageSource)
        root.deleteRecursively()
        Unit
    }

    @Test
    fun harvestsBrowserWireAndReclassifiesOldSubagentRecords(): Unit = runBlocking {
        val root = File.createTempFile("kimi-sessions", "").apply {
            delete()
            mkdirs()
        }
        val sessionDir = File(root, "wd/sess-1").apply { mkdirs() }
        File(sessionDir, "state.json").writeText(
            """{"agents":{"agt-browser":{"profileName":"browser"}}}""",
        )
        File(sessionDir, "agents/agt-browser").mkdirs()
        File(sessionDir, "agents/agt-browser/wire.jsonl").writeText(
            """{"type":"usage.record","model":"kimi-code/a","usage":{"inputOther":8,"output":3},"usageScope":"turn"}""" + "\n",
        )
        val store = InMemoryLlmUsageStore()
        store.insert(
            listOf(
                LlmUsageRecord(
                    id = "wire:sess-1:agt-browser:1:kimi-code/a",
                    sessionId = "chat-1",
                    source = LlmUsageSources.Subagent,
                    usageSource = "api",
                    agentId = "agt-browser",
                    totalTokens = 11,
                ),
            ),
        )
        store.commitHarvest("sess-1/agt-browser", consumedCount = 1, records = emptyList())
        val recorder = UsageRecorder(
            store = store,
            sessionsRoot = { root },
            aetherSessionIdForKimi = { if (it == "sess-1") "chat-1" else "" },
        )
        recorder.harvestKimiSession("chat-1", "sess-1")
        val browser = recorder.listRecords().filter { it.source == LlmUsageSources.Browser }
        assertEquals(1, browser.size)
        assertEquals(11L, browser.single().effectiveTokenTotal())
        assertTrue(recorder.listRecords().none { it.source == LlmUsageSources.Subagent })
        root.deleteRecursively()
        Unit
    }

    @Test
    fun compactCompletionUsesApiUsageWhenPresent(): Unit = runBlocking {
        val store = InMemoryLlmUsageStore()
        val recorder = UsageRecorder(store, sessionsRoot = { File("missing") }, aetherSessionIdForKimi = { "" })
        val result = JSONObject(
            """{"assistant_text":"ok","usage":{"input_tokens":12,"output_tokens":3,"total_tokens":15}}""",
        ).toPiCompletionResult()
        recorder.recordCompact("s1", "kimi", "kimi-for-coding", result)
        val compact = recorder.listRecords().single()
        assertEquals(LlmUsageSources.Compact, compact.source)
        assertEquals("api", compact.usageSource)
        assertEquals(15L, compact.effectiveTokenTotal())
    }

    @Test
    fun recordTurnStoresDisplayModelId(): Unit = runBlocking {
        val store = InMemoryLlmUsageStore()
        val recorder = UsageRecorder(store, sessionsRoot = { File("missing") }, aetherSessionIdForKimi = { "" })
        recorder.recordTurn(
            sessionId = "s1",
            providerId = "stepfun",
            modelId = "stepfun/note3prev",
            usage = LlmTokenUsage(inputTokens = 3, outputTokens = 1, totalTokens = 4),
            usageSource = "api",
            startedAtMillis = 1,
            completedAtMillis = 2,
            firstTokenAtMillis = 1,
            messageId = "m1",
        )
        assertEquals("note3prev", recorder.listRecords().single().modelId)
    }

    /**
     * The wire numbers must replace the estimate, not stack on top of it.
     *
     * Both rows describe the same turn: one written immediately so the UI has something, one that
     * arrives when the provider's own accounting lands. Keeping both double-counts the conversation
     * and leaves a permanent 0%-cache row in the average.
     */
    @Test
    fun mainWireSupersedesTheTurnEstimate(): Unit = runBlocking {
        val root = File.createTempFile("kimi-sessions-2", "").apply {
            delete()
            mkdirs()
        }
        val sessionDir = File(root, "wd/sess-2").apply { mkdirs() }
        File(sessionDir, "agents/main").mkdirs()
        File(sessionDir, "agents/main/wire.jsonl").writeText(
            """{"type":"usage.record","model":"m","usage":{"inputOther":300,"output":40,"inputCacheRead":700},"usageScope":"turn"}""" + "\n",
        )
        val store = InMemoryLlmUsageStore()
        val recorder = UsageRecorder(
            store = store,
            sessionsRoot = { root },
            aetherSessionIdForKimi = { if (it == "sess-2") "chat-2" else "" },
        )
        recorder.recordTurn(
            sessionId = "chat-2",
            providerId = "p",
            modelId = "m",
            usage = LlmTokenUsage(inputTokens = 1000L, outputTokens = 50L),
            usageSource = "estimated",
            startedAtMillis = 1,
            completedAtMillis = 2,
            firstTokenAtMillis = 1,
            messageId = "m-est",
        )
        assertEquals("estimated", recorder.listRecords().single().usageSource)

        recorder.harvestKimiSession("chat-2", "sess-2")

        val turns = recorder.listRecords().filter { it.source == LlmUsageSources.Turn }
        assertEquals("the estimate must be gone, not doubled", 1, turns.size)
        assertEquals("api", turns.single().usageSource)
        assertEquals(300L, turns.single().inputTokens)
        assertEquals(700L, turns.single().cachedInputTokens)
        assertEquals(70, tokenCacheHitPercent(700L, 300L))
        root.deleteRecursively()
        Unit
    }
}

private class InMemoryLlmUsageStore(
    private val messageRecords: List<LlmUsageRecord> = emptyList(),
) : LlmUsageStore {
    private val records = mutableListOf<LlmUsageRecord>()
    private val cursors = mutableMapOf<String, Int>()

    override suspend fun insert(records: List<LlmUsageRecord>) {
        this.records += records
    }

    override suspend fun list(): List<LlmUsageRecord> = records.toList()

    override suspend fun listSince(sinceMillis: Long): List<LlmUsageRecord> =
        records.filter { it.completedAtMillis >= sinceMillis }

    override suspend fun totals(): LlmUsageTotals = LlmUsageTotals(
        recordCount = records.size,
        sessionCount = records.mapNotNull { it.sessionId }.filter { it.isNotBlank() }.distinct().size,
        turnCount = records.count { it.source == LlmUsageSources.Turn },
        totalTokens = records.sumOf { it.effectiveTokenTotal() },
        inputTokens = records.sumOf { it.inputTokens ?: 0L },
        outputTokens = records.sumOf { it.outputTokens ?: 0L },
        reasoningTokens = records.sumOf { it.reasoningTokens ?: 0L },
        cachedInputTokens = records.sumOf { it.cachedInputTokens ?: 0L },
        largestTurnTokens = records.filter { it.source == LlmUsageSources.Turn }
            .maxOfOrNull { it.effectiveTokenTotal() }
            ?.takeIf { it > 0L },
        tokensBySource = records.groupBy { it.source }
            .map { (source, items) -> LlmUsageGroupTotal(source, items.sumOf { it.effectiveTokenTotal() }) },
        tokensByModel = records.groupBy { it.modelId }
            .map { (model, items) -> LlmUsageGroupTotal(model, items.sumOf { it.effectiveTokenTotal() }) },
    )

    override suspend fun cursor(wireKey: String): Int? = cursors[wireKey]

    override suspend fun commitHarvest(
        wireKey: String,
        consumedCount: Int,
        records: List<LlmUsageRecord>,
    ) {
        this.records += records
        cursors[wireKey] = consumedCount
    }

    override suspend fun deleteEstimatedRecords(sessionId: String, source: String): Int {
        val before = records.size
        records.removeAll { record ->
            record.sessionId == sessionId &&
                record.source == source &&
                record.usageSource == "estimated"
        }
        return before - records.size
    }

    override suspend fun reclassifyWireRecords(idPrefix: String, source: String) {
        if (idPrefix.isBlank() || source.isBlank()) return
        records.replaceAll { record ->
            if (record.id.startsWith(idPrefix) && record.source != source) {
                record.copy(source = source)
            } else {
                record
            }
        }
    }

    override suspend fun backfillMessagesIfNeeded(): Int {
        if ((cursors[MessageUsageBackfillCursorKey] ?: 0) > 0) return 0
        records += messageRecords
        cursors[MessageUsageBackfillCursorKey] = 1
        return messageRecords.size
    }
}
