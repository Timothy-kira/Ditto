package kira.ditto.data.pi

import kira.ditto.data.AppSettings
import kira.ditto.data.AetherLlmUserAgent
import kira.ditto.data.LlmCustomHeader
import kira.ditto.data.LlmImagePart
import kira.ditto.data.LlmMessage
import kira.ditto.data.LlmResourceLinkPart
import kira.ditto.data.LlmTextPart
import kira.ditto.data.LlmTokenUsage
import kira.ditto.data.ProviderAuthMethod
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PiProviderMapperTest {
    @Test
    fun builtInOpenAiMapsDirectlyToPiCatalog() {
        val config = AppSettings(
            providerConfigId = "openai-config",
            piProviderId = "openai",
            apiKey = "sk-test",
            baseUrl = "https://api.openai.com/v1",
            modelId = "gpt-5.4",
        ).toPiModelConfig()

        assertEquals("builtin", config.providerType)
        assertEquals("openai-config", config.providerConfigId)
        assertEquals("openai-config", config.toJson().getString("provider_config_id"))
        assertEquals("openai", config.piProviderId)
        assertEquals("builtin", config.piApi)
        assertEquals("gpt-5.4", config.modelId)
        assertEquals("sk-test", config.apiKey)
        assertFalse(config.reasoning)
        assertEquals("high", AppSettings(
            piProviderId = "openai",
            modelId = "gpt-5.4",
            reasoningEffort = "high",
        ).toPiThinkingLevel())
    }

    @Test
    fun maxReasoningEffortPassesThroughToPi() {
        assertEquals("max", AppSettings(
            piProviderId = "openai",
            modelId = "gpt-5.6-luna",
            reasoningEffort = "max",
        ).toPiThinkingLevel())
    }

    @Test
    fun unknownBuiltInModelStillPassesSelectedLevelToPi() {
        assertEquals("high", AppSettings(
            piProviderId = "openai",
            modelId = "future-reasoning-model",
            reasoningEffort = "high",
        ).toPiThinkingLevel())
    }

    @Test
    fun customModelEnablesReasoningWhenTheSelectedEffortRequiresIt() {
        val config = AppSettings(
            piProviderId = "openai-compatible",
            providerConfigId = "custom-provider-id",
            baseUrl = "https://example.test/v1",
            modelId = "custom-model",
            reasoningEffort = "high",
        ).toPiModelConfig()

        assertTrue(config.reasoning)
        assertEquals("high", AppSettings(
            piProviderId = "openai-compatible",
            modelId = "custom-model",
            reasoningEffort = "high",
        ).toPiThinkingLevel())
    }

    @Test
    fun legacyNoneReasoningEffortMigratesToPiOff() {
        assertEquals("off", AppSettings(
            piProviderId = "openai",
            modelId = "gpt-5.4",
            reasoningEffort = "none",
        ).toPiThinkingLevel())
    }

    @Test
    fun anthropicMapsToBuiltInPiProvider() {
        val config = AppSettings(
            piProviderId = "anthropic",
            apiKey = "anthropic-key",
            baseUrl = "https://api.anthropic.com/v1",
            modelId = "claude-sonnet-4-5",
        ).toPiModelConfig()

        assertEquals("builtin", config.providerType)
        assertEquals("anthropic", config.piProviderId)
        assertEquals("builtin", config.piApi)
    }

    @Test
    fun stepfunFlashUsesOpenAiCompletions() {
        val config = AppSettings(
            piProviderId = "stepfun",
            apiKey = "stepfun-key",
            baseUrl = "https://api.stepfun.com",
            modelId = "step-3.7-flash",
        ).toPiModelConfig()

        assertEquals("custom", config.providerType)
        assertEquals("stepfun", config.piProviderId)
        assertEquals("openai-completions", config.piApi)
        assertEquals("https://api.stepfun.com/step_plan/v1", config.baseUrl)
    }

    @Test
    fun stepfunKeepsStepPlanForFlashModels() {
        val config = AppSettings(
            piProviderId = "stepfun",
            apiKey = "stepfun-key",
            baseUrl = "https://api.stepfun.com/step_plan/v1/messages",
            modelId = "step-3.7-flash",
        ).toPiModelConfig()

        assertEquals("openai-completions", config.piApi)
        assertEquals("https://api.stepfun.com/step_plan/v1", config.baseUrl)
    }

    @Test
    fun stepfunKeepsStepPlanForRouterModels() {
        val config = AppSettings(
            piProviderId = "stepfun",
            apiKey = "stepfun-key",
            baseUrl = "https://api.stepfun.com",
            modelId = "step-router-v1",
        ).toPiModelConfig()

        assertEquals("anthropic-messages", config.piApi)
        assertEquals("https://api.stepfun.com/step_plan", config.baseUrl)
    }

    @Test
    fun vertexMapsToPiGoogleVertex() {
        val config = AppSettings(
            piProviderId = "google-vertex",
            apiKey = "vertex-key",
            baseUrl = "https://aiplatform.googleapis.com/v1",
            modelId = "gemini-2.5-flash",
            providerAuthMethod = ProviderAuthMethod.ApiKey,
        ).toPiModelConfig()

        assertEquals("builtin", config.providerType)
        assertEquals("google-vertex", config.piProviderId)
        assertEquals("builtin", config.piApi)
        assertEquals("vertex-key", config.apiKey)
        assertEquals(ProviderAuthMethod.ApiKey, config.authMethod)
    }

    @Test
    fun oauthAndAmbientProvidersDoNotForwardStaleApiKeys() {
        val oauth = AppSettings(
            piProviderId = "openai-codex",
            apiKey = "legacy-openai-key",
            providerAuthMethod = ProviderAuthMethod.OAuth,
        ).toPiModelConfig()
        val ambient = AppSettings(
            piProviderId = "amazon-bedrock",
            apiKey = "legacy-aws-key",
            providerAuthMethod = ProviderAuthMethod.Ambient,
        ).toPiModelConfig()

        assertEquals("", oauth.apiKey)
        assertEquals("", ambient.apiKey)
    }

    @Test
    fun openAiCompatibleMapsToCustomOpenAiCompletionsProvider() {
        val config = AppSettings(
            piProviderId = "openai-compatible",
            providerConfigId = "custom-provider-id",
            apiKey = "custom-key",
            baseUrl = "https://example.test/v1",
            modelId = "custom-model",
            userAgent = "CustomAgent/3.0",
            customHeaders = listOf(
                LlmCustomHeader("X-Test", "yes"),
                LlmCustomHeader("User-Agent", "ignored"),
                LlmCustomHeader(" ", "ignored"),
            ),
        ).toPiModelConfig()

        assertEquals("custom", config.providerType)
        assertTrue(config.piProviderId.startsWith("aether-"))
        assertEquals("openai-completions", config.piApi)
        assertEquals(
            mapOf(
                "X-Test" to "yes",
                "User-Agent" to "CustomAgent/3.0",
            ),
            config.customHeaders,
        )
        assertEquals("custom-model", config.modelId)
        assertFalse(config.reasoning)
        assertEquals("off", AppSettings(
            piProviderId = "openai-compatible",
            modelId = "custom-model",
        ).toPiThinkingLevel())
        assertEquals(
            AetherLlmUserAgent,
            AppSettings(
                piProviderId = "openai-compatible",
                providerConfigId = "default-agent",
                baseUrl = "https://example.test/v1",
                modelId = "custom-model",
            ).toPiModelConfig().customHeaders["User-Agent"],
        )
    }

    @Test
    fun llmMessagesMapToPiJsonWithTextAndImages() {
        val json = listOf(
            LlmMessage(
                role = "user",
                contentParts = listOf(
                    LlmTextPart("hello"),
                    LlmImagePart("image/png", "abc123"),
                ),
            )
        ).toPiJson()

        val message = json.getJSONObject(0)
        assertEquals("user", message.optString("role"))
        val content = message.getJSONArray("content")
        assertEquals("text", content.getJSONObject(0).optString("type"))
        assertEquals("hello", content.getJSONObject(0).optString("text"))
        assertEquals("image", content.getJSONObject(1).optString("type"))
        assertEquals("image/png", content.getJSONObject(1).optString("mime_type"))
        assertEquals("abc123", content.getJSONObject(1).optString("data"))
        assertFalse(message.has("provider_payload"))
    }

    @Test
    fun llmMessagesMapResourceLinkParts() {
        val json = listOf(
            LlmMessage(
                role = "user",
                contentParts = listOf(
                    LlmTextPart("see this"),
                    LlmResourceLinkPart("/workspace/notes.md", "notes.md"),
                ),
            )
        ).toPiJson()

        val content = json.getJSONObject(0).getJSONArray("content")
        assertEquals("resource_link", content.getJSONObject(1).optString("type"))
        assertEquals("file:///workspace/notes.md", content.getJSONObject(1).optString("uri"))
        assertEquals("notes.md", content.getJSONObject(1).optString("name"))
    }

    @Test
    fun piAssistantPayloadIsWrappedForRoomReplay() {
        val assistantMessage = JSONObject().apply {
            put("role", "assistant")
            put("content", org.json.JSONArray().put(
                JSONObject().put("type", "text").put("text", "done")
            ))
        }
        val payload = PiCompletionResult(
            assistantText = "done",
            assistantMessage = assistantMessage,
            usage = LlmTokenUsage(inputTokens = 3, outputTokens = 2, totalTokens = 5),
            provider = "openai",
            model = "gpt-5.4",
            responseId = "resp-1",
            stopReason = "stop",
        ).toProviderPayloadJson()
        val wrapped = JSONObject(payload)

        assertEquals("assistant", wrapped.getJSONObject("piAssistantMessage").getString("role"))
        assertEquals("openai", wrapped.getString("provider"))
        assertEquals("resp-1", wrapped.getString("responseId"))
        assertEquals(5L, wrapped.getJSONObject("usage").getLong("total_tokens"))
    }

    @Test
    fun customModelEnablesReasoningWhenThinkingLevelMapMapsOffToNone() {
        val config = AppSettings(
            piProviderId = "openai-compatible",
            providerConfigId = "custom-provider-id",
            baseUrl = "https://example.test/v1",
            modelId = "gpt-5.3-codex-spark",
            reasoningEffort = "off",
        ).toPiModelConfig(
            thinkingLevelMap = mapOf("off" to "none"),
            isReasoningModel = true,
        )

        assertTrue(config.reasoning)
        assertEquals(mapOf("off" to "none"), config.thinkingLevelMap)
        assertEquals("none", config.toJson().getJSONObject("thinking_level_map").getString("off"))
    }

    @Test
    fun knownReasoningModelKeepsItsCapabilityWhenOffUsesANativeDisableDirective() {
        val config = AppSettings(
            piProviderId = "anthropic",
            modelId = "claude-sonnet-5",
            reasoningEffort = "off",
        ).toPiModelConfig(isReasoningModel = true)

        assertTrue(config.reasoning)
        assertTrue(config.thinkingLevelMap.isEmpty())
    }

    @Test
    fun effortOffDoesNotForceReasoningJustBecauseCatalogMapsNone() {
        val config = AppSettings(
            piProviderId = "openai-compatible",
            providerConfigId = "custom-provider-id",
            baseUrl = "https://example.test/v1",
            modelId = "some-model",
            reasoningEffort = "off",
        ).toPiModelConfig(
            thinkingLevelMap = mapOf("off" to "none"),
        )
        assertFalse(config.reasoning)
    }
}
