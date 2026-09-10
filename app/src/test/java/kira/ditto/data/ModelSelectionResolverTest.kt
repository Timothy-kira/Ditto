package kira.ditto.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelSelectionResolverTest {
    @Test
    fun automaticChatModelUsesConfiguredPriorityOrder() {
        val models = listOf(
            "muse-spark-1.1",
            "glm-5.2",
            "deepseek-v4-flash",
            "deepseek-v4-pro",
            "kimi-k3",
            "gemini-3.1-pro",
            "grok-4.5",
            "gemini-3.5-flash",
            "claude-sonnet-5",
            "claude-opus-4.8",
            "gpt-5.6-terra",
            "gpt-5.6-sol",
            "claude-fable-5",
        )
        val configs = models.mapIndexed { index, modelId ->
            providerConfig(
                id = "provider-$index",
                providerId = "provider-$index",
                baseUrl = "https://provider-$index.example/v1",
                modelId = modelId,
            )
        }
        val options = configs.availableModelOptions()

        assertEquals(
            "claude-fable-5",
            options.findModelOption(
                options.resolveAutomaticModelKey(AutomaticModelPurpose.Chat)
            )?.modelId,
        )
        assertEquals(models.reversed(), options.sortedForAutomaticModelPurpose(
            AutomaticModelPurpose.Chat
        ).map { it.modelId })
    }

    @Test
    fun automaticTitleAndNamingModelsUseEfficientPriorityAndWildcardFallback() {
        val models = listOf(
            "vendor-fast-mini",
            "claude-4.5-haiku",
            "gpt-5.4-mini",
            "gpt-5.6-luna",
            "gemini-3.1-flash-lite",
        )
        val options = models.mapIndexed { index, modelId ->
            providerConfig(
                id = "provider-$index",
                providerId = "provider-$index",
                baseUrl = "https://provider-$index.example/v1",
                modelId = modelId,
            )
        }.availableModelOptions()

        val expectedOrder = models.reversed()
        assertEquals(
            expectedOrder,
            options.sortedForAutomaticModelPurpose(AutomaticModelPurpose.Title).map { it.modelId },
        )
        assertEquals(
            expectedOrder,
            options.sortedForAutomaticModelPurpose(AutomaticModelPurpose.Naming).map { it.modelId },
        )
    }

    @Test
    fun explicitOnboardingModelOverridesAutomaticChatPreference() {
        val config = providerConfig(
            id = "tour-provider",
            providerId = "tour",
            baseUrl = "https://tour.example/v1",
            modelId = "user-selected-model",
        ).copy(
            cachedModels = listOf("gpt-5.5", "user-selected-model"),
            enabledModelIds = listOf("gpt-5.5", "user-selected-model"),
        )

        val updated = AppSettings().withExplicitDefaultChatModel(config)

        assertEquals("user-selected-model", updated.modelId)
        assertEquals(
            buildModelOptionKey(config.id, "user-selected-model"),
            updated.defaultChatModelKey,
        )
    }

    @Test
    fun resolveModelSettingsUsesSessionPreferredModelBeforeDefault() {
        val defaultConfig = providerConfig(
            id = "default-provider",
            providerId = "default",
            baseUrl = "https://default.example/v1",
            modelId = "gpt-5.4",
        )
        val sessionConfig = providerConfig(
            id = "session-provider",
            providerId = "session",
            baseUrl = "https://session.example/v1",
            modelId = "claude-sonnet-4-5",
        )
        val providerConfigs = listOf(defaultConfig, sessionConfig)
        val settings = AppSettings(
            baseUrl = "https://legacy.example/v1",
            modelId = "legacy",
            defaultChatModelKey = buildModelOptionKey(defaultConfig.id, defaultConfig.modelId),
        )

        val resolved = resolveModelSettings(
            baseSettings = settings,
            providerConfigs = providerConfigs,
            preferredModelKey = buildModelOptionKey(sessionConfig.id, sessionConfig.modelId),
            fallbackModelKey = resolveDefaultChatModelKey(settings, providerConfigs),
        )

        assertEquals("https://session.example/v1", resolved.baseUrl)
        assertEquals("claude-sonnet-4-5", resolved.modelId)
    }

    @Test
    fun newChatDraftUsesStoredDefaultChatModel() {
        val defaultConfig = providerConfig(
            id = "default-provider",
            providerId = "default",
            baseUrl = "https://default.example/v1",
            modelId = "step-3.7-flash",
        )
        val previousSessionConfig = providerConfig(
            id = "session-provider",
            providerId = "session",
            baseUrl = "https://session.example/v1",
            modelId = "dots3-note-prev",
        )
        val settings = AppSettings(
            defaultChatModelKey = buildModelOptionKey(defaultConfig.id, defaultConfig.modelId),
        )
        assertEquals(
            buildModelOptionKey(defaultConfig.id, defaultConfig.modelId),
            resolveDefaultChatModelKey(
                settings,
                listOf(defaultConfig, previousSessionConfig),
            ),
        )
    }

    @Test
    fun resolveModelSettingsFallsBackToDefaultWhenPreferredMissing() {
        val defaultConfig = providerConfig(
            id = "default-provider",
            providerId = "default",
            baseUrl = "https://default.example/v1",
            modelId = "gpt-5.4",
        )
        val settings = AppSettings(
            baseUrl = "https://legacy.example/v1",
            modelId = "legacy",
            defaultChatModelKey = buildModelOptionKey(defaultConfig.id, defaultConfig.modelId),
        )

        val resolved = resolveModelSettings(
            baseSettings = settings,
            providerConfigs = listOf(defaultConfig),
            preferredModelKey = "missing-provider::missing-model",
            fallbackModelKey = resolveDefaultChatModelKey(settings, listOf(defaultConfig)),
        )

        assertEquals("https://default.example/v1", resolved.baseUrl)
        assertEquals("gpt-5.4", resolved.modelId)
    }

    @Test
    fun resolveDefaultVectorModelDoesNotAutoSelect() {
        val chatConfig = providerConfig(
            id = "chat-provider",
            providerId = "chat",
            baseUrl = "https://chat.example/v1",
            modelId = "gpt-5.4",
        )
        val vectorConfig = providerConfig(
            id = "vector-provider",
            providerId = "vector",
            baseUrl = "https://vector.example/v1",
            modelId = "text-embedding-3-large",
        )

        val unresolved = resolveDefaultVectorModelKey(
            settings = AppSettings(),
            providerConfigs = listOf(chatConfig, vectorConfig),
        )
        assertEquals("", unresolved)

        val stored = resolveDefaultVectorModelKey(
            settings = AppSettings(
                defaultVectorModelKey = buildModelOptionKey(vectorConfig.id, vectorConfig.modelId),
            ),
            providerConfigs = listOf(chatConfig, vectorConfig),
        )
        assertEquals(buildModelOptionKey(vectorConfig.id, vectorConfig.modelId), stored)
    }

    @Test
    fun resolveDefaultVectorModelKeepsDisabledEmbedding() {
        val vectorConfig = providerConfig(
            id = "vector-provider",
            providerId = "vector",
            baseUrl = "https://vector.example/v1",
            modelId = "text-embedding-3-large",
        ).copy(
            cachedModels = listOf("text-embedding-3-large"),
            enabledModelIds = emptyList(),
        )
        val stored = resolveDefaultVectorModelKey(
            settings = AppSettings(
                defaultVectorModelKey = buildModelOptionKey(vectorConfig.id, vectorConfig.modelId),
            ),
            providerConfigs = listOf(vectorConfig),
        )
        assertEquals(buildModelOptionKey(vectorConfig.id, vectorConfig.modelId), stored)
    }

    @Test
    fun resolveDefaultImageModelDoesNotAutoSelect() {
        val chatConfig = providerConfig(
            id = "chat-provider",
            providerId = "chat",
            baseUrl = "https://chat.example/v1",
            modelId = "gpt-5.4",
        )
        val imageConfig = providerConfig(
            id = "image-provider",
            providerId = "image",
            baseUrl = "https://image.example/v1",
            modelId = "gpt-image-1",
        )

        val unresolved = resolveDefaultImageModelKey(
            settings = AppSettings(),
            providerConfigs = listOf(chatConfig, imageConfig),
        )
        assertEquals("", unresolved)

        val stored = resolveDefaultImageModelKey(
            settings = AppSettings(
                defaultImageModelKey = buildModelOptionKey(imageConfig.id, imageConfig.modelId),
            ),
            providerConfigs = listOf(chatConfig, imageConfig),
        )
        assertEquals(buildModelOptionKey(imageConfig.id, imageConfig.modelId), stored)
    }

    @Test
    fun resolveDefaultAsrModelKeepsDeviceKeysAndDoesNotAutoSelect() {
        val unresolved = resolveDefaultAsrModelKey(
            settings = AppSettings(),
            providerConfigs = emptyList(),
        )
        assertEquals("", unresolved)

        val auto = resolveDefaultAsrModelKey(
            settings = AppSettings(defaultAsrModelKey = AsrDeviceModelKeys.Auto),
            providerConfigs = emptyList(),
        )
        assertEquals(AsrDeviceModelKeys.Auto, auto)
    }

    private fun providerConfig(
        id: String,
        providerId: String,
        baseUrl: String,
        modelId: String,
        piProviderId: String = "openai-compatible",
    ): LlmProviderConfig = LlmProviderConfig(
        id = id,
        providerId = providerId,
        name = providerId,
        piProviderId = piProviderId,
        apiKey = "test-key",
        baseUrl = baseUrl,
        modelId = modelId,
    )
}
