package kira.ditto.data.chatdb

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/**
 * `totalTokens` is authoritative when the provider reported it; otherwise the components
 * are summed. Kept in one place so the aggregates match the in-memory calculation.
 */
private const val EffectiveTokenTotalSql =
    "CASE WHEN COALESCE(totalTokens, 0) > 0 THEN totalTokens " +
        "ELSE COALESCE(inputTokens, 0) + COALESCE(outputTokens, 0) + COALESCE(reasoningTokens, 0) END"

data class LlmUsageTotalsEntity(
    val recordCount: Int = 0,
    val sessionCount: Int = 0,
    val turnCount: Int = 0,
    val totalTokens: Long = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val reasoningTokens: Long = 0,
    val cachedInputTokens: Long = 0,
    val largestTurnTokens: Long? = null,
)

data class LlmUsageGroupTotalEntity(
    val groupKey: String,
    val tokens: Long,
)

@Dao
interface LlmUsageDao {
    @Query("SELECT * FROM llm_usage_records ORDER BY completedAtMillis ASC, id ASC")
    suspend fun getAllRecords(): List<LlmUsageRecordEntity>

    /**
     * Detailed rows for the recent window the statistics charts actually plot. Lifetime
     * figures come from the aggregates below so the whole table never enters memory.
     */
    @Query("""
        SELECT * FROM llm_usage_records
        WHERE completedAtMillis >= :sinceMillis
        ORDER BY completedAtMillis ASC, id ASC
    """)
    suspend fun getRecordsSince(sinceMillis: Long): List<LlmUsageRecordEntity>

    @Query("""
        SELECT
            COUNT(*) AS recordCount,
            COUNT(DISTINCT CASE WHEN sessionId IS NOT NULL AND sessionId != '' THEN sessionId END) AS sessionCount,
            SUM(CASE WHEN source = 'turn' THEN 1 ELSE 0 END) AS turnCount,
            COALESCE(SUM($EffectiveTokenTotalSql), 0) AS totalTokens,
            COALESCE(SUM(COALESCE(inputTokens, 0)), 0) AS inputTokens,
            COALESCE(SUM(COALESCE(outputTokens, 0)), 0) AS outputTokens,
            COALESCE(SUM(COALESCE(reasoningTokens, 0)), 0) AS reasoningTokens,
            COALESCE(SUM(COALESCE(cachedInputTokens, 0)), 0) AS cachedInputTokens,
            MAX(CASE WHEN source = 'turn' THEN $EffectiveTokenTotalSql END) AS largestTurnTokens
        FROM llm_usage_records
    """)
    suspend fun getUsageTotals(): LlmUsageTotalsEntity?

    @Query("""
        SELECT source AS groupKey, COALESCE(SUM($EffectiveTokenTotalSql), 0) AS tokens
        FROM llm_usage_records
        GROUP BY source
        HAVING tokens > 0
        ORDER BY tokens DESC
    """)
    suspend fun getTokensBySource(): List<LlmUsageGroupTotalEntity>

    @Query("""
        SELECT modelId AS groupKey, COALESCE(SUM($EffectiveTokenTotalSql), 0) AS tokens
        FROM llm_usage_records
        GROUP BY modelId
        HAVING tokens > 0
        ORDER BY tokens DESC
        LIMIT :limit
    """)
    suspend fun getTokensByModel(limit: Int): List<LlmUsageGroupTotalEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRecords(records: List<LlmUsageRecordEntity>)

    /**
     * Remove the estimated rows for one session and source.
     *
     * Called the moment the wire file yields real numbers for the same turns. Scoped to
     * `usageSource = 'estimated'` so a row that already carries provider data is never touched.
     */
    @Query(
        "DELETE FROM llm_usage_records WHERE sessionId = :sessionId AND source = :source " +
            "AND usageSource = 'estimated'",
    )
    suspend fun deleteEstimatedRecords(sessionId: String, source: String): Int

    @Query("SELECT consumedCount FROM llm_usage_wire_cursors WHERE wireKey = :wireKey")
    suspend fun getConsumedCount(wireKey: String): Int?

    @Upsert
    suspend fun upsertCursor(cursor: LlmUsageWireCursorEntity)

    @Transaction
    suspend fun commitHarvest(
        records: List<LlmUsageRecordEntity>,
        wireKey: String,
        consumedCount: Int,
    ) {
        if (records.isNotEmpty()) insertRecords(records)
        upsertCursor(LlmUsageWireCursorEntity(wireKey = wireKey, consumedCount = consumedCount))
    }

    @Transaction
    suspend fun commitMessageBackfill(records: List<LlmUsageRecordEntity>, cursorKey: String) {
        if (records.isNotEmpty()) insertRecords(records)
        upsertCursor(LlmUsageWireCursorEntity(wireKey = cursorKey, consumedCount = 1))
    }
}
