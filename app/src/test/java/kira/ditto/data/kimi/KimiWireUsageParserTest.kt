package kira.ditto.data.kimi

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import kira.ditto.data.tokenCacheHitPercent
import org.junit.Test

class KimiWireUsageParserTest {
    @Test
    fun turnRecordsSumAndSessionScopeIsIgnored() {
        val jsonl = """
            {"type":"metadata","protocol_version":1}
            {"type":"usage.record","model":"kimi-code/a","usage":{"inputOther":10,"output":4,"inputCacheRead":20,"inputCacheCreation":2},"usageScope":"turn"}
            {"type":"usage.record","model":"kimi-code/a","usage":{"inputOther":100,"output":50,"inputCacheRead":200,"inputCacheCreation":0},"usageScope":"session"}
            {"type":"usage.record","model":"kimi-code/a","usage":{"inputOther":5,"output":1,"inputCacheRead":0,"inputCacheCreation":0},"usageScope":"turn"}
        """.trimIndent()
        val extracted = KimiWireUsageParser.extractTurnUsageSince(jsonl.reader().buffered(), cursor = 0)
        assertEquals(3, extracted.totalRecordCount)
        assertEquals(2, extracted.deltas.size)
        // Cache creation is billed like fresh input and hits nothing, so it belongs on the input
        // side: 10 + 2 + 5. Only inputCacheRead counts as cached.
        assertEquals(17L, extracted.deltas.sumOf { it.inputTokens })
        assertEquals(5L, extracted.deltas.sumOf { it.outputTokens })
        assertEquals(20L, extracted.deltas.sumOf { it.cachedInputTokens })
    }

    /**
     * A first request writes the whole prompt to cache and reads none of it.
     *
     * Counting creation as cached made exactly this case report a perfect hit rate, which is the
     * reverse of the truth and the reason the reported number could not be used to judge whether a
     * prompt-shape change had helped.
     */
    @Test
    fun writingTheCacheIsNotAHit() {
        val jsonl = """
            {"type":"usage.record","model":"m","usage":{"inputOther":0,"output":9,"inputCacheRead":0,"inputCacheCreation":4000},"usageScope":"turn"}
        """.trimIndent()
        val delta = KimiWireUsageParser.extractTurnUsageSince(jsonl.reader().buffered(), cursor = 0)
            .deltas
            .single()
        assertEquals(4000L, delta.inputTokens)
        assertEquals(0L, delta.cachedInputTokens)
        assertEquals(0, tokenCacheHitPercent(delta.cachedInputTokens, delta.inputTokens))
    }

    @Test
    fun cursorSkipsAlreadyConsumedRecords() {
        val jsonl = """
            {"type":"usage.record","model":"m","usage":{"inputOther":10,"output":1},"usageScope":"turn"}
            {"type":"usage.record","model":"m","usage":{"inputOther":20,"output":2},"usageScope":"turn"}
        """.trimIndent()
        val extracted = KimiWireUsageParser.extractTurnUsageSince(jsonl.reader().buffered(), cursor = 1)
        assertEquals(2, extracted.totalRecordCount)
        assertEquals(1, extracted.deltas.size)
        assertEquals(20L, extracted.deltas.single().inputTokens)
    }

    @Test
    fun shrunkenFileFallsBackToLastRecord() {
        val jsonl = """
            {"type":"usage.record","model":"m","usage":{"inputOther":7,"output":3},"usageScope":"turn"}
        """.trimIndent()
        val extracted = KimiWireUsageParser.extractTurnUsageSince(jsonl.reader().buffered(), cursor = 9)
        assertEquals(1, extracted.totalRecordCount)
        assertEquals(7L, extracted.deltas.single().inputTokens)
    }

    @Test
    fun parseTurnDeltaRejectsSessionScope() {
        val record = JSONObject(
            """{"type":"usage.record","model":"m","usage":{"inputOther":1,"output":1},"usageScope":"session"}""",
        )
        assertNull(KimiWireUsageParser.parseTurnDelta(record))
    }

    @Test
    fun usageDisplayModelIdUsesTheNameAfterTheSessionFolder() {
        assertEquals(
            "dots3-note-prev",
            usageDisplayModelId("aether-53b159a0-71e4-4365-b1a3-1de3d055639d/dots3-note-prev"),
        )
        assertEquals(
            "step-3.7-flash",
            usageDisplayModelId("aether-8cd45cba-2f83-4b1d-ae1d-5dd8fd60f711/step-3.7-flash"),
        )
        assertEquals("kimi-for-coding", usageDisplayModelId("kimi-for-coding"))
        val record = JSONObject(
            """{"type":"usage.record","model":"aether-53b159a0-71e4-4365-b1a3-1de3d055639d/dots3-note-prev","usage":{"inputOther":1,"output":1},"usageScope":"turn"}""",
        )
        assertEquals("dots3-note-prev", KimiWireUsageParser.parseTurnDelta(record)!!.modelId)
    }

    @Test
    fun mergeTokensByDisplayModelIdCollapsesNote3prevAliases() {
        val merged = mergeTokensByDisplayModelId(
            items = listOf(
                "note3prev" to 10L,
                "aether-53b159a0-71e4-4365-b1a3-1de3d055639d/note3prev" to 20L,
                "stepfun/note3prev" to 5L,
                "step-3.7-flash" to 7L,
            ),
        )
        assertEquals(2, merged.size)
        assertEquals("note3prev" to 35L, merged.first())
        assertEquals("step-3.7-flash" to 7L, merged.last())
    }
}

class KimiSubagentUsageHarvesterTest {
    @Test
    fun skipsMainAndClassifiesPhoneFromState() {
        val root = File.createTempFile("kimi-sessions", "").apply {
            delete()
            mkdirs()
        }
        val sessionDir = File(root, "wd/sess-1").apply { mkdirs() }
        File(sessionDir, "state.json").writeText(
            """{"agents":{"agt-phone":{"type":"sub","profileName":"phone"},"agt-browser":{"type":"sub","profileName":"browser"},"agt-image":{"type":"sub","profileName":"image"},"agt-explore":{"type":"sub","profileName":"explore"}}}""",
        )
        File(sessionDir, "agents/main").mkdirs()
        File(sessionDir, "agents/main/wire.jsonl").writeText(
            """{"type":"usage.record","usageScope":"turn","usage":{"inputOther":999,"output":1},"model":"main"}""" + "\n",
        )
        File(sessionDir, "agents/agt-phone").mkdirs()
        File(sessionDir, "agents/agt-phone/wire.jsonl").writeText(
            """{"type":"usage.record","model":"kimi-code/a","usage":{"inputOther":11,"output":2},"usageScope":"turn"}""" + "\n",
        )
        File(sessionDir, "agents/agt-browser").mkdirs()
        File(sessionDir, "agents/agt-browser/wire.jsonl").writeText(
            """{"type":"usage.record","model":"kimi-code/a","usage":{"inputOther":7,"output":1},"usageScope":"turn"}""" + "\n",
        )
        File(sessionDir, "agents/agt-image").mkdirs()
        File(sessionDir, "agents/agt-image/wire.jsonl").writeText(
            """{"type":"usage.record","model":"kimi-code/a","usage":{"inputOther":4,"output":1},"usageScope":"turn"}""" + "\n",
        )
        File(sessionDir, "agents/agt-explore").mkdirs()
        File(sessionDir, "agents/agt-explore/wire.jsonl").writeText(
            """{"type":"usage.record","model":"kimi-code/a","usage":{"inputOther":3,"output":1},"usageScope":"turn"}""" + "\n",
        )

        val wires = KimiSubagentUsageHarvester.listNonMainAgentWires(root, "sess-1")
        assertEquals(4, wires.size)
        assertTrue(wires.none { it.agentId == "main" })
        assertEquals("phone", wires.first { it.agentId == "agt-phone" }.source)
        assertEquals("browser", wires.first { it.agentId == "agt-browser" }.source)
        assertEquals("image", wires.first { it.agentId == "agt-image" }.source)
        assertEquals("subagent", wires.first { it.agentId == "agt-explore" }.source)
        root.deleteRecursively()
    }

    @Test
    fun classifiesPhoneFromWirePeekWhenStateMissing() {
        val root = File.createTempFile("kimi-sessions", "").apply {
            delete()
            mkdirs()
        }
        val sessionDir = File(root, "wd/sess-2").apply { mkdirs() }
        File(sessionDir, "agents/op").mkdirs()
        File(sessionDir, "agents/op/wire.jsonl").writeText(
            """{"type":"tools.set_active_tools","tools":["mcp__phone_app__list_apps"]}""" + "\n",
        )
        val wires = KimiSubagentUsageHarvester.listNonMainAgentWires(root)
        assertEquals(1, wires.size)
        assertEquals("phone", wires.single().source)
        root.deleteRecursively()
    }

    @Test
    fun classifiesBrowserFromWirePeekWhenStateMissing() {
        val root = File.createTempFile("kimi-sessions", "").apply {
            delete()
            mkdirs()
        }
        val sessionDir = File(root, "wd/sess-3").apply { mkdirs() }
        File(sessionDir, "agents/web").mkdirs()
        File(sessionDir, "agents/web/wire.jsonl").writeText(
            """{"type":"tools.set_active_tools","tools":["mcp__webmcp__tabs_navigate","mcp__webmcp__page_read"]}""" + "\n",
        )
        val wires = KimiSubagentUsageHarvester.listNonMainAgentWires(root)
        assertEquals(1, wires.size)
        assertEquals("browser", wires.single().source)
        root.deleteRecursively()
    }

    @Test
    fun classifiesImageFromSearchImagesPeek() {
        val root = File.createTempFile("kimi-sessions", "").apply {
            delete()
            mkdirs()
        }
        val sessionDir = File(root, "wd/sess-4").apply { mkdirs() }
        File(sessionDir, "agents/img").mkdirs()
        File(sessionDir, "agents/img/wire.jsonl").writeText(
            """{"type":"tools.set_active_tools","tools":["mcp__webmcp__search_images"]}""" + "\n",
        )
        val wires = KimiSubagentUsageHarvester.listNonMainAgentWires(root)
        assertEquals(1, wires.size)
        assertEquals("image", wires.single().source)
        root.deleteRecursively()
    }
}
