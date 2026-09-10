package kira.ditto.data

import java.io.File
import java.util.UUID
import kira.ditto.data.kimi.KimiSubagentUsageHarvester
import kira.ditto.data.kimi.KimiWireUsageDelta
import kira.ditto.data.kimi.KimiWireUsageParser
import kira.ditto.data.kimi.storedUsageModelId
import kira.ditto.data.pi.PiCompletionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object LlmUsageSources {
    const val Turn = "turn"
    const val Title = "title"
    const val Compact = "compact"
    const val Embedding = "embedding"
    const val Phone = "phone"
    const val Browser = "browser"
    const val Image = "image"
    const val Subagent = "subagent"
}

const val MessageUsageBackfillCursorKey = "__message_backfill__"

data class LlmUsageRecord(
    val id: String,
    val sessionId: String? = null,
    val source: String,
    val providerId: String = "",
    val modelId: String = "",
    val agentId: String? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val totalTokens: Long? = null,
    val reasoningTokens: Long? = null,
    val cachedInputTokens: Long? = null,
    val requestCount: Int = 1,
    val usageSource: String,
    val startedAtMillis: Long = 0L,
    val completedAtMillis: Long = 0L,
    val firstTokenAtMillis: Long? = null,
) {
    fun effectiveTokenTotal(): Long {
        val total = totalTokens ?: 0L
        if (total > 0L) return total
        return (inputTokens ?: 0L) + (outputTokens ?: 0L) + (reasoningTokens ?: 0L)
    }

    fun withStoredModelId(): LlmUsageRecord {
        val stored = storedUsageModelId(modelId)
        return if (stored == modelId) this else copy(modelId = stored)
    }
}

/**
 * Lifetime usage figures computed by the database. The statistics page needs these totals
 * over every record ever written, which is exactly the query that must not be answered by
 * pulling the table into memory.
 */
data class LlmUsageTotals(
    val recordCount: Int = 0,
    val sessionCount: Int = 0,
    val turnCount: Int = 0,
    val totalTokens: Long = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val reasoningTokens: Long = 0,
    val cachedInputTokens: Long = 0,
    val largestTurnTokens: Long? = null,
    val tokensBySource: List<LlmUsageGroupTotal> = emptyList(),
    val tokensByModel: List<LlmUsageGroupTotal> = emptyList(),
)

data class LlmUsageGroupTotal(
    val key: String,
    val tokens: Long,
)

interface LlmUsageStore {
    suspend fun insert(records: List<LlmUsageRecord>)
    suspend fun list(): List<LlmUsageRecord>

    /** Detailed records completed at or after [sinceMillis]; used for the recent charts. */
    suspend fun listSince(sinceMillis: Long): List<LlmUsageRecord>
    suspend fun totals(): LlmUsageTotals
    suspend fun cursor(wireKey: String): Int?
    suspend fun commitHarvest(wireKey: String, consumedCount: Int, records: List<LlmUsageRecord>)

    /**
     * Drop the placeholder rows the real numbers have just replaced.
     *
     * A turn records an estimate immediately so the UI has something to show, then the wire file
     * lands with what the provider actually charged. Keeping both would double-count the whole
     * conversation; keeping the estimate would leave a row that reports 0% cache hit forever.
     */
    suspend fun deleteEstimatedRecords(sessionId: String, source: String): Int
    suspend fun reclassifyWireRecords(idPrefix: String, source: String)
    suspend fun backfillMessagesIfNeeded(): Int
}

class UsageRecorder(
    private val store: LlmUsageStore,
    private val sessionsRoot: () -> File,
    private val aetherSessionIdForKimi: (String) -> String,
) {
    private val harvestMutex = Mutex()

    suspend fun record(record: LlmUsageRecord) {
        if (record.effectiveTokenTotal() <= 0L && (record.cachedInputTokens ?: 0L) <= 0L) return
        store.insert(listOf(record.withStoredModelId()))
    }

    suspend fun recordTurn(
        sessionId: String,
        providerId: String,
        modelId: String,
        usage: LlmTokenUsage?,
        usageSource: String,
        startedAtMillis: Long,
        completedAtMillis: Long,
        firstTokenAtMillis: Long?,
        messageId: String?,
    ) {
        val resolved = usage?.withMissingTotalResolved() ?: return
        record(
            LlmUsageRecord(
                id = usageRecordId("turn", sessionId, messageId),
                sessionId = sessionId.takeIf { it.isNotBlank() },
                source = LlmUsageSources.Turn,
                providerId = providerId,
                modelId = modelId,
                inputTokens = resolved.inputTokens,
                outputTokens = resolved.outputTokens,
                totalTokens = resolved.totalTokens,
                reasoningTokens = resolved.reasoningTokens,
                cachedInputTokens = resolved.cachedInputTokens,
                requestCount = resolved.requestCount.coerceAtLeast(1),
                usageSource = usageSource.ifBlank { "unavailable" },
                startedAtMillis = startedAtMillis,
                completedAtMillis = completedAtMillis,
                firstTokenAtMillis = firstTokenAtMillis,
            ),
        )
    }

    suspend fun recordCompletion(
        source: String,
        sessionId: String?,
        providerId: String,
        modelId: String,
        usage: LlmTokenUsage?,
        promptChars: Int,
        outputChars: Int,
    ) {
        val (resolved, origin) = usage.orEstimated(promptChars, outputChars)
        record(
            LlmUsageRecord(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId?.takeIf { it.isNotBlank() },
                source = source,
                providerId = providerId,
                modelId = modelId,
                inputTokens = resolved.inputTokens,
                outputTokens = resolved.outputTokens,
                totalTokens = resolved.totalTokens,
                reasoningTokens = resolved.reasoningTokens,
                cachedInputTokens = resolved.cachedInputTokens,
                requestCount = resolved.requestCount.coerceAtLeast(1),
                usageSource = origin,
                startedAtMillis = System.currentTimeMillis(),
                completedAtMillis = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun recordTitle(
        sessionId: String,
        providerId: String,
        modelId: String,
        result: PiCompletionResult?,
        promptChars: Int,
    ) {
        recordCompletion(
            source = LlmUsageSources.Title,
            sessionId = sessionId,
            providerId = providerId,
            modelId = modelId,
            usage = result?.usage,
            promptChars = promptChars,
            outputChars = result?.assistantText.orEmpty().length,
        )
    }

    suspend fun recordCompact(
        sessionId: String,
        providerId: String,
        modelId: String,
        result: PiCompletionResult?,
    ) {
        recordCompletion(
            source = LlmUsageSources.Compact,
            sessionId = sessionId,
            providerId = providerId,
            modelId = modelId,
            usage = result?.usage,
            promptChars = 0,
            outputChars = result?.assistantText.orEmpty().length,
        )
    }

    suspend fun recordEmbedding(
        providerId: String,
        modelId: String,
        usage: LlmTokenUsage?,
        inputChars: Int,
    ) {
        recordCompletion(
            source = LlmUsageSources.Embedding,
            sessionId = null,
            providerId = providerId,
            modelId = modelId,
            usage = usage,
            promptChars = inputChars,
            outputChars = 0,
        )
    }

    suspend fun listRecords(): List<LlmUsageRecord> = store.list()

    suspend fun listRecentRecords(sinceMillis: Long): List<LlmUsageRecord> = store.listSince(sinceMillis)

    suspend fun usageTotals(): LlmUsageTotals = store.totals()

    suspend fun ensureMessageBackfill() {
        store.backfillMessagesIfNeeded()
    }

    suspend fun harvestKimiSession(aetherSessionId: String, kimiSessionId: String) {
        harvestMutex.withLock {
            harvestWires(
                wires = KimiSubagentUsageHarvester.listAgentWires(
                    sessionsRoot = sessionsRoot(),
                    kimiSessionId = kimiSessionId,
                ),
                fallbackAetherSessionId = aetherSessionId,
            )
        }
    }

    suspend fun harvestAllSessions() {
        harvestMutex.withLock {
            harvestWires(
                wires = KimiSubagentUsageHarvester.listAgentWires(sessionsRoot()),
                fallbackAetherSessionId = "",
            )
        }
    }

    private suspend fun harvestWires(
        wires: List<kira.ditto.data.kimi.KimiAgentWire>,
        fallbackAetherSessionId: String,
    ) {
        wires.forEach { wire ->
            val wireKey = "${wire.kimiSessionId}/${wire.agentId}"
            val recordIdPrefix = "wire:${wire.kimiSessionId}:${wire.agentId}:"
            val cursor = store.cursor(wireKey) ?: 0
            val extracted = KimiWireUsageParser.extractTurnUsageSince(wire.wireFile, cursor)
            if (extracted.totalRecordCount != cursor || extracted.deltas.isNotEmpty()) {
                val sessionId = aetherSessionIdForKimi(wire.kimiSessionId)
                    .ifBlank { fallbackAetherSessionId }
                    .takeIf { it.isNotBlank() }
                val records = extracted.deltas
                    .groupBy { it.modelId.ifBlank { "" } }
                    .map { (modelId, deltas) ->
                        deltas.toUsageRecord(
                            id = "wire:${wire.kimiSessionId}:${wire.agentId}:${extracted.totalRecordCount}:$modelId",
                            sessionId = sessionId,
                            source = wire.source,
                            agentId = wire.agentId,
                            modelId = modelId,
                        )
                    }
                // Before the real rows land, clear the estimates they supersede. Only for the
                // main agent: subagent turns never had an estimated row to begin with.
                if (wire.source == LlmUsageSources.Turn && records.isNotEmpty() && sessionId != null) {
                    runCatching { store.deleteEstimatedRecords(sessionId, LlmUsageSources.Turn) }
                }
                store.commitHarvest(
                    wireKey = wireKey,
                    consumedCount = extracted.totalRecordCount,
                    records = records,
                )
            }
            try {
                store.reclassifyWireRecords(recordIdPrefix, wire.source)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
            }
        }
    }
}

fun LlmTokenUsage?.orEstimated(promptChars: Int, outputChars: Int): Pair<LlmTokenUsage, String> {
    val resolved = this?.withMissingTotalResolved()
    if (
        resolved != null &&
        (
            (resolved.totalTokens ?: 0L) > 0L ||
                (resolved.inputTokens ?: 0L) > 0L ||
                (resolved.outputTokens ?: 0L) > 0L ||
                (resolved.cachedInputTokens ?: 0L) > 0L
            )
    ) {
        return resolved to "api"
    }
    val input = estimateTokensFromChars(promptChars)
    val output = estimateTokensFromChars(outputChars)
    return LlmTokenUsage(
        inputTokens = input.takeIf { it > 0L },
        outputTokens = output.takeIf { it > 0L },
        totalTokens = (input + output).takeIf { it > 0L },
    ) to "estimated"
}

private fun List<KimiWireUsageDelta>.toUsageRecord(
    id: String,
    sessionId: String?,
    source: String,
    agentId: String,
    modelId: String,
): LlmUsageRecord {
    val input = sumOf { it.inputTokens }
    val output = sumOf { it.outputTokens }
    val cached = sumOf { it.cachedInputTokens }
    val total = sumOf { it.totalTokens }
    val now = System.currentTimeMillis()
    return LlmUsageRecord(
        id = id,
        sessionId = sessionId,
        source = source,
        providerId = "kimi-code",
        modelId = storedUsageModelId(modelId),
        agentId = agentId,
        inputTokens = input.takeIf { it > 0L },
        outputTokens = output.takeIf { it > 0L },
        totalTokens = total.takeIf { it > 0L },
        cachedInputTokens = cached.takeIf { it > 0L },
        requestCount = size.coerceAtLeast(1),
        usageSource = "api",
        startedAtMillis = now,
        completedAtMillis = now,
    )
}

private fun usageRecordId(prefix: String, sessionId: String, messageId: String?): String {
    val suffix = messageId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
    return "$prefix:$sessionId:$suffix"
}
