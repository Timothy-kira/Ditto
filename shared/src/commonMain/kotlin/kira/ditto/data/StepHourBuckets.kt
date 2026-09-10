package kira.ditto.data

data class StepCounterSample(
    val epochMs: Long,
    val stepsSinceBoot: Long,
)

data class HourlyStepBucket(
    val startMs: Long,
    val endMs: Long,
    val steps: Long,
)

object StepHourBuckets {
    const val HourMs = 3_600_000L

    fun hourly(
        samples: List<StepCounterSample>,
        rangeStartMs: Long,
        rangeEndMs: Long,
        hourStartMs: (Long) -> Long = { epoch -> (epoch / HourMs) * HourMs },
    ): List<HourlyStepBucket> {
        val sorted = samples.sortedBy { it.epochMs }
        if (sorted.size < 2 || rangeEndMs <= rangeStartMs) return emptyList()
        val totals = linkedMapOf<Long, Long>()
        for (index in 1 until sorted.size) {
            val previous = sorted[index - 1]
            val current = sorted[index]
            val overlapStart = maxOf(previous.epochMs, rangeStartMs)
            val overlapEnd = minOf(current.epochMs, rangeEndMs)
            if (overlapEnd <= overlapStart) continue
            val rawDelta = if (current.stepsSinceBoot >= previous.stepsSinceBoot) {
                current.stepsSinceBoot - previous.stepsSinceBoot
            } else {
                current.stepsSinceBoot.coerceAtLeast(0L)
            }
            if (rawDelta <= 0L) continue
            val fullSpan = (current.epochMs - previous.epochMs).coerceAtLeast(1L)
            val overlapSpan = overlapEnd - overlapStart
            val delta = if (overlapSpan >= fullSpan) {
                rawDelta
            } else {
                (rawDelta * overlapSpan) / fullSpan
            }
            if (delta <= 0L) continue
            distribute(
                delta = delta,
                fromMs = overlapStart,
                toMs = overlapEnd,
                rangeStartMs = rangeStartMs,
                rangeEndMs = rangeEndMs,
                hourStartMs = hourStartMs,
                totals = totals,
            )
        }
        return totals.entries.map { (start, steps) ->
            val end = nextHourStart(start, hourStartMs)
            HourlyStepBucket(startMs = start, endMs = end, steps = steps)
        }
    }

    private fun distribute(
        delta: Long,
        fromMs: Long,
        toMs: Long,
        rangeStartMs: Long,
        rangeEndMs: Long,
        hourStartMs: (Long) -> Long,
        totals: MutableMap<Long, Long>,
    ) {
        var remaining = delta
        var cursor = fromMs
        val totalMs = (toMs - fromMs).coerceAtLeast(1L)
        while (cursor < toMs && remaining > 0L) {
            val bucket = hourStartMs(cursor)
            val sliceEnd = minOf(nextHourStart(bucket, hourStartMs), toMs)
            val lastSlice = sliceEnd >= toMs
            val sliceSteps = if (lastSlice) {
                remaining
            } else {
                val sliceMs = (sliceEnd - cursor).coerceAtLeast(1L)
                (delta * sliceMs / totalMs).coerceAtMost(remaining)
            }
            if (sliceSteps > 0L) {
                val bucketEnd = nextHourStart(bucket, hourStartMs)
                val overlaps = bucketEnd > rangeStartMs && bucket < rangeEndMs
                if (overlaps) {
                    totals[bucket] = (totals[bucket] ?: 0L) + sliceSteps
                }
            }
            remaining -= sliceSteps
            cursor = sliceEnd
        }
    }

    private fun nextHourStart(bucketStartMs: Long, hourStartMs: (Long) -> Long): Long {
        val next = hourStartMs(bucketStartMs + HourMs)
        return if (next > bucketStartMs) next else bucketStartMs + HourMs
    }
}
