package kira.ditto.data

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P2: balanced promotion, replay validation gate, and sop_id drift repair. */
class AgentModeSopValidationTest {
    private fun store(dir: java.io.File) = AgentModeSopStore(dir)

    private fun steps() = listOf(
        AgentModeSopStep(action = "launch", packageName = "com.example.app"),
        AgentModeSopStep(action = "tap", guiLabel = "搜索", x = 500, y = 200),
    )

    @Test
    fun automaticSuccessStaysValidatingUntilReplayPasses() {
        val dir = Files.createTempDirectory("sop-validation").toFile()
        try {
            val s = store(dir)
            val sop = s.recordVerifiedSuccess(
                goal = "搜索",
                steps = steps(),
                provenance = AgentModeSopProvenance.KimiAgent,
                validationState = "pending_replay",
            )
            assertEquals(AgentModeSopMaturity.Validating, sop.maturity)
            assertEquals("pending_replay", sop.validationState)
            assertFalse(sop.promoted)

            val failed = s.recordValidationResult(sop.id, replayPassed = false)
            assertEquals("failed", failed?.validationState)
            assertEquals(AgentModeSopMaturity.Degraded, failed?.maturity)
            assertEquals(1, failed?.failureStreak)

            val again = s.recordVerifiedSuccess(
                goal = "搜索",
                steps = steps(),
                provenance = AgentModeSopProvenance.KimiAgent,
                validationState = "pending_replay",
            )
            val passed = s.recordValidationResult(again.id, replayPassed = true)
            assertEquals("validated", passed?.validationState)
            assertEquals(0, passed?.failureStreak)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun mismatchFeedbackDemotesAndBreaksStreakOnSuccess() {
        val dir = Files.createTempDirectory("sop-streak").toFile()
        try {
            val s = store(dir)
            val sop = s.recordVerifiedSuccess(goal = "搜索", steps = steps())
            s.recordFeedback(sop.id, succeeded = false)
            val afterFail = s.findById(sop.id)
            assertEquals(1, afterFail?.failureStreak)
            s.recordFeedback(sop.id, succeeded = true)
            val afterSuccess = s.findById(sop.id)
            assertEquals(0, afterSuccess?.failureStreak)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun catalogFindsSopAcrossSegmentSuffixDrift() {
        val dir = Files.createTempDirectory("sop-drift").toFile()
        try {
            val catalogStore = PhoneAppCatalogStore(dir)
            catalogStore.merge(
                packageName = "com.example.app",
                appName = "Example",
                incoming = listOf(
                    PhoneAppPrimitive(
                        kind = PhoneAppPrimitiveKind.Search,
                        toolName = "example_search",
                        title = "搜索",
                        description = "Search",
                        sopId = "sop-abc-search",
                        goal = "搜索",
                        successCount = 3,
                        promoted = true,
                        provenance = AgentModeSopProvenance.KimiAgent.storageValue,
                        acceptsQuery = true,
                        guiLabels = listOf("搜索"),
                    ),
                ),
            )
            // Base id stored before segmentation must still resolve.
            assertTrue(catalogStore.findReadySop("sop-abc-search") != null)
            assertTrue(catalogStore.findReadySop("sop-abc") != null)
            assertNull(catalogStore.findReadySop("sop-unrelated"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun pendingReplaySopIsRejectedFromReplayNamed() {
        val dir = Files.createTempDirectory("sop-not-ready").toFile()
        try {
            val s = store(dir)
            val sop = s.recordVerifiedSuccess(
                goal = "搜索",
                steps = steps(),
                provenance = AgentModeSopProvenance.KimiAgent,
                validationState = "pending_replay",
            )
            assertEquals(AgentModeSopMaturity.Validating, sop.maturity)
            assertFalse(sop.promoted)
        } finally {
            dir.deleteRecursively()
        }
    }
}
