package kira.ditto.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThinkingLevelsTest {
    @Test
    fun parseAllowedThinkingLevelsReadsQuotedEnum() {
        assertEquals(
            listOf("low", "medium", "high"),
            parseAllowedThinkingLevels(
                """Invalid reasoning_effort. Supported values are: "low", "medium", "high".""",
            ),
        )
    }

    @Test
    fun parseAllowedThinkingLevelsMapsNoneToOff() {
        assertEquals(
            listOf("off", "low", "high"),
            parseAllowedThinkingLevels("must be one of: 'none', 'low', 'high'"),
        )
    }

    @Test
    fun parseAllowedThinkingLevelsIgnoresUnrelatedHigh() {
        assertEquals(
            emptyList(),
            parseAllowedThinkingLevels("The request failed because of high latency."),
        )
    }

    @Test
    fun interpretUnknownParameterIsUnsupported() {
        val result = interpretThinkingProbeResponse(
            400,
            """{"error":{"message":"Unknown parameter: reasoning_effort"}}""",
        )
        assertTrue(result.parameterUnsupported)
        assertFalse(result.resolved)
        assertEquals(emptyList(), result.levels)
    }

    @Test
    fun interpretEnumErrorResolvesLevels() {
        val result = interpretThinkingProbeResponse(
            400,
            """{"error":{"message":"output_config.effort must be one of: 'low', 'medium', 'high'"}}""",
        )
        assertTrue(result.resolved)
        assertTrue(result.reasoningSupported)
        assertEquals(listOf("low", "medium", "high"), result.levels)
    }

    @Test
    fun stepFunFlashProbeUsesOpenAiCompletionsFirst() {
        assertEquals(
            listOf(ThinkingProbeStyle.OpenAiCompletions, ThinkingProbeStyle.AnthropicMessages),
            thinkingProbeStyles("stepfun", "step-3.7-flash"),
        )
        assertEquals(
            "https://api.stepfun.com/step_plan/v1/chat/completions",
            thinkingProbeUrl(
                "https://api.stepfun.com",
                "stepfun",
                "step-3.7-flash",
                ThinkingProbeStyle.OpenAiCompletions,
            ),
        )
        assertEquals(
            "https://api.stepfun.com/step_plan/v1/messages",
            thinkingProbeUrl(
                "https://api.stepfun.com",
                "stepfun",
                "step-3.7-flash",
                ThinkingProbeStyle.AnthropicMessages,
            ),
        )
    }

    @Test
    fun stepFunRouterProbeUsesStepPlanMessages() {
        assertEquals(
            listOf(ThinkingProbeStyle.AnthropicMessages, ThinkingProbeStyle.OpenAiCompletions),
            thinkingProbeStyles("stepfun", "step-router-v1"),
        )
        assertEquals(
            "https://api.stepfun.com/step_plan/v1/messages",
            thinkingProbeUrl(
                "https://api.stepfun.com",
                "stepfun",
                "step-router-v1",
                ThinkingProbeStyle.AnthropicMessages,
            ),
        )
    }

    @Test
    fun reduceKeepsFirstDiscoveredLevels() {
        val reduced = reduceThinkingProbeResults(
            listOf(
                ThinkingProbeResult(parameterUnsupported = true),
                ThinkingProbeResult(
                    levels = listOf("low", "high"),
                    reasoningSupported = true,
                    resolved = true,
                ),
            ),
        )
        assertEquals(listOf("low", "high"), reduced.levels)
    }
}
