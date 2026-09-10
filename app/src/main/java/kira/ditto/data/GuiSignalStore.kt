package kira.ditto.data

import java.io.File
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.json.JSONObject

/**
 * Append-only journal of typed GUI failure signals derived from agent_display /
 * phone_app receipts. Distinct from the trajectory store: trajectories answer
 * "what happened in this attempt", this journal answers "which failure modes
 * keep recurring" so distribution stats and policy tuning stay cheap.
 */
class GuiSignalStore(
    private val root: File,
) {
    private val lock = ReentrantLock()

    init {
        root.mkdirs()
    }

    data class Entry(
        val attemptId: String,
        val signal: String,
        val where: String,
        val packageName: String,
        val snapshotId: String,
        val sopId: String,
        val repairStrategy: String = "",
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("attempt_id", attemptId)
            .put("signal", signal)
            .put("where", where)
            .put("package_name", packageName)
            .put("snapshot_id", snapshotId)
            .put("sop_id", sopId)
            .put("repair_strategy", repairStrategy)
            .put("created_at_millis", System.currentTimeMillis())
    }

    fun append(entry: Entry) = lock.withLock {
        if (entry.signal.isBlank()) return@withLock
        root.mkdirs()
        File(root, SignalsFileName).appendText(entry.toJson().toString() + "\n")
    }

    fun append(
        attemptId: String,
        signal: String,
        where: String,
        packageName: String = "",
        snapshotId: String = "",
        sopId: String = "",
        repairStrategy: String = "",
    ) = append(
        Entry(
            attemptId = attemptId,
            signal = signal,
            where = where,
            packageName = packageName,
            snapshotId = snapshotId,
            sopId = sopId,
            repairStrategy = repairStrategy,
        ),
    )

    /** Append one entry for the failure carried by an agent_display receipt. */
    fun appendReceipt(
        attemptId: String,
        where: String,
        result: JSONObject,
        sopId: String = "",
    ) {
        val failure = GuiFailureSignals.failureCode(result)
        if (failure.isBlank()) return
        append(
            attemptId = attemptId.ifBlank { "unknown-" + UUID.randomUUID().toString().take(8) },
            signal = failure,
            where = where,
            packageName = result.optString("package_name"),
            snapshotId = result.optString("snapshot_id"),
            sopId = sopId.ifBlank { result.optString("sop_id") },
            repairStrategy = result.optJSONObject("failure")?.optString("repair_strategy").orEmpty(),
        )
    }

    fun recent(limit: Int = 200): List<Entry> = lock.withLock {
        val file = File(root, SignalsFileName)
        if (!file.isFile) return@withLock emptyList()
        file.readLines()
            .asReversed()
            .asSequence()
            .take(limit.coerceIn(1, 2_000))
            .mapNotNull { line ->
                runCatching {
                    val json = JSONObject(line)
                    Entry(
                        attemptId = json.optString("attempt_id"),
                        signal = json.optString("signal"),
                        where = json.optString("where"),
                        packageName = json.optString("package_name"),
                        snapshotId = json.optString("snapshot_id"),
                        sopId = json.optString("sop_id"),
                        repairStrategy = json.optString("repair_strategy"),
                    )
                }.getOrNull()
            }
            .toList()
    }

    /** signal -> occurrences, for offline failure-distribution stats. */
    fun distribution(limit: Int = 2_000): Map<String, Int> =
        recent(limit).groupingBy { it.signal }.eachCount()

    companion object {
        const val SignalsFileName = "signals.jsonl"
    }
}
