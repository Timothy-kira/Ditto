package kira.ditto.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LlmProviderConfigValidationTest {
    @Test
    fun modelListsPutMajorFamiliesFirstThenSortAlphabetically() {
        assertEquals(
            listOf(
                "gpt-4o",
                "gpt-5",
                "claude-3-haiku",
                "claude-sonnet-4",
                "gemini-2.5-flash",
                "alpha",
                "Mistral-Large",
                "zeta",
            ),
            listOf(
                "zeta",
                "claude-sonnet-4",
                "Mistral-Large",
                "gemini-2.5-flash",
                "gpt-5",
                "alpha",
                "claude-3-haiku",
                "gpt-4o",
            ).sortedByPreferredModelName(),
        )
    }

    @Test
    fun namespacedModelIdsUseTheModelNameForFamilyPriority() {
        assertEquals(
            listOf("openai/gpt-5", "anthropic/claude-opus-4", "vendor/alpha"),
            listOf(
                "vendor/alpha",
                "anthropic/claude-opus-4",
                "openai/gpt-5",
            ).sortedByPreferredModelName(),
        )
    }

    @Test
    fun builtInApiKeyProviderRequiresSupportedNonBlankKey() {
        assertFalse(provider(piProviderId = "openai", apiKey = "").isSharedProviderSetupValid())
        assertTrue(provider(piProviderId = "openai", apiKey = "secret").isSharedProviderSetupValid())
        assertFalse(
            provider(
                piProviderId = "openai-codex",
                authMethod = ProviderAuthMethod.ApiKey,
                apiKey = "secret",
            ).isSharedProviderSetupValid(),
        )
    }

    @Test
    fun oauthProviderRequiresSupportAndCredential() {
        assertFalse(
            provider(
                piProviderId = "openai-codex",
                authMethod = ProviderAuthMethod.OAuth,
            ).isSharedProviderSetupValid(),
        )
        assertTrue(
            provider(
                piProviderId = "openai-codex",
                authMethod = ProviderAuthMethod.OAuth,
                oauthCredentialJson = "{\"access\":\"token\"}",
            ).isSharedProviderSetupValid(),
        )
        assertFalse(
            provider(
                piProviderId = "openai",
                authMethod = ProviderAuthMethod.OAuth,
                oauthCredentialJson = "{\"access\":\"token\"}",
            ).isSharedProviderSetupValid(),
        )
    }

    @Test
    fun ambientProviderRequiresCatalogSupport() {
        assertTrue(
            provider(
                piProviderId = "google-vertex",
                authMethod = ProviderAuthMethod.Ambient,
                baseUrl = "",
            ).isSharedProviderSetupValid(),
        )
        assertFalse(
            provider(
                piProviderId = "openai",
                authMethod = ProviderAuthMethod.Ambient,
            ).isSharedProviderSetupValid(),
        )
    }

    @Test
    fun customProviderMatchesAndroidBaseUrlAndApiKeyRules() {
        assertFalse(
            provider(
                piProviderId = "openai-compatible",
                apiKey = "",
                baseUrl = "",
            ).isSharedProviderSetupValid(),
        )
        assertTrue(
            provider(
                piProviderId = "openai-compatible",
                apiKey = "",
                baseUrl = "https://models.example/v1",
            ).isSharedProviderSetupValid(),
        )
    }

    @Test
    fun requiredBuiltInBaseUrlIsValidated() {
        assertFalse(
            provider(
                piProviderId = "azure-openai-responses",
                apiKey = "secret",
                baseUrl = " ",
            ).isSharedProviderSetupValid(),
        )
        assertTrue(
            provider(
                piProviderId = "azure-openai-responses",
                apiKey = "secret",
                baseUrl = "https://example.openai.azure.com",
            ).isSharedProviderSetupValid(),
        )
    }
}

private fun provider(
    piProviderId: String,
    authMethod: ProviderAuthMethod = ProviderAuthMethod.ApiKey,
    apiKey: String = "",
    baseUrl: String = PiProviderCatalog.resolve(piProviderId).defaultBaseUrl,
    oauthCredentialJson: String = "",
) = LlmProviderConfig(
    providerId = "provider",
    name = "Provider",
    piProviderId = piProviderId,
    apiKey = apiKey,
    baseUrl = baseUrl,
    authMethod = authMethod,
    oauthCredentialJson = oauthCredentialJson,
    modelId = "model",
)
