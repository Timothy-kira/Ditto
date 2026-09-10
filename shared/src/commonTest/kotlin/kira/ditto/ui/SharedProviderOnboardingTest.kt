package kira.ditto.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class SharedProviderOnboardingTest {
    @Test
    fun prioritizesModelFamiliesThenSortsAlphabetically() {
        assertEquals(
            listOf(
                "gpt-5.6-sol",
                "gpt-5.6-terra",
                "claude-fable-5",
            ),
            prioritizedSharedProviderModelOptions(
                piProviderId = "openai",
                cachedModels = listOf(
                    " gpt-5.6-terra ",
                    "gpt-5.6-sol",
                    "claude-fable-5",
                ),
            ),
        )
    }

    @Test
    fun trimsDeduplicatesAndUsesGlobalModelOrdering() {
        assertEquals(
            listOf(
                "gpt-a",
                "gpt-z",
                "z-model",
            ),
            prioritizedSharedProviderModelOptions(
                piProviderId = "openai",
                cachedModels = listOf(
                    " z-model ",
                    "gpt-z",
                    "GPT-Z",
                    "gpt-a",
                    "",
                ),
            ),
        )
    }
}
