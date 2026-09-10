package kira.ditto.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class StepHistoryStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "health-step-history.json")

    @Synchronized
    fun record(stepsSinceBoot: Long, epochMs: Long = System.currentTimeMillis()) {
        if (stepsSinceBoot < 0L) return
        val samples = load().toMutableList()
        val last = samples.lastOrNull()
        if (last != null &&
            epochMs - last.epochMs < MinRecordGapMs &&
            last.stepsSinceBoot == stepsSinceBoot
        ) {
            return
        }
        samples += StepCounterSample(epochMs = epochMs, stepsSinceBoot = stepsSinceBoot)
        val cutoff = epochMs - TimeUnit.DAYS.toMillis(7)
        val kept = samples.filter { it.epochMs >= cutoff }.takeLast(MaxSamples)
        file.parentFile?.mkdirs()
        file.writeText(
            JSONArray().apply {
                kept.forEach { sample ->
                    put(
                        JSONObject()
                            .put("epochMs", sample.epochMs)
                            .put("stepsSinceBoot", sample.stepsSinceBoot),
                    )
                }
            }.toString(),
        )
    }

    @Synchronized
    fun load(): List<StepCounterSample> {
        if (!file.isFile) return emptyList()
        val raw = runCatching { file.readText() }.getOrNull().orEmpty()
        if (raw.isBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            StepCounterSample(
                epochMs = item.optLong("epochMs"),
                stepsSinceBoot = item.optLong("stepsSinceBoot"),
            ).takeIf { it.epochMs > 0L }
        }
    }

    companion object {
        private const val MaxSamples = 2_000
        private const val MinRecordGapMs = 60_000L
    }
}
