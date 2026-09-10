package kira.ditto.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderModelListParserTest {
    @Test
    fun parsesOpenAiCompatibleModelIdsAndDropsDuplicates() {
        val ids = parseOpenAiCompatibleModelIds(
            """{"data":[{"id":"model-a"},{"id":"MODEL-A"},{"id":"model-a"},{"id":"model-b"}]}""",
        )
        assertEquals(listOf("model-a", "MODEL-A", "model-b"), ids)
    }

    @Test
    fun scansLargeBodiesByRegex() {
        assertFalse(shouldScanModelListByRegex("short"))
        assertTrue(shouldScanModelListByRegex("x".repeat(400_000)))
    }

    @Test
    fun firstFetchEnablesAtMostTwentyFourModelsAndKeepsPreferred() {
        val fetched = (1..40).map { "model-$it" }
        val enabled = autoEnableFetchedModels(
            fetched = fetched,
            previouslyEnabled = emptyList(),
            previousCached = emptySet(),
            preferredModelId = "model-40",
        )
        assertEquals(24, enabled.size)
        assertEquals("model-40", enabled.first())
    }

    @Test
    fun laterFetchKeepsPreviouslyEnabledModels() {
        val fetched = listOf("kept", "new-a", "new-b")
        val enabled = autoEnableFetchedModels(
            fetched = fetched,
            previouslyEnabled = listOf("kept", "gone"),
            previousCached = setOf("kept", "gone"),
        )
        assertEquals(listOf("kept", "gone"), enabled)
    }
}
