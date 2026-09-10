package kira.ditto.data

import kotlin.test.Test
import kotlin.test.assertEquals

class StepHourBucketsTest {
    @Test
    fun bucketsDeltasIntoSampleHours() {
        val hour = StepHourBuckets.HourMs
        val aligned = (1_700_000_000_000L / hour) * hour
        val samples = listOf(
            StepCounterSample(aligned + 5 * 60_000, 100),
            StepCounterSample(aligned + 20 * 60_000, 180),
        )
        val buckets = StepHourBuckets.hourly(samples, aligned, aligned + 2 * hour)
        assertEquals(listOf(80L), buckets.map { it.steps })
    }

    @Test
    fun splitsDeltaAcrossHourBoundary() {
        val hour = StepHourBuckets.HourMs
        val aligned = (1_700_000_000_000L / hour) * hour
        val samples = listOf(
            StepCounterSample(aligned + 50 * 60_000, 100),
            StepCounterSample(aligned + 70 * 60_000, 180),
        )
        val buckets = StepHourBuckets.hourly(samples, aligned, aligned + 2 * hour)
        assertEquals(listOf(40L, 40L), buckets.map { it.steps })
    }

    @Test
    fun treatsCounterResetAsNewBoot() {
        val hour = StepHourBuckets.HourMs
        val aligned = (1_700_000_000_000L / hour) * hour
        val samples = listOf(
            StepCounterSample(aligned + 1_000, 20_000),
            StepCounterSample(aligned + 10_000, 40),
        )
        val buckets = StepHourBuckets.hourly(samples, aligned, aligned + hour)
        assertEquals(listOf(40L), buckets.map { it.steps })
    }
}
