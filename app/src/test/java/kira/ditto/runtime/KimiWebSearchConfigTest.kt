package kira.ditto.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KimiWebSearchConfigTest {
    @Test
    fun launchCommandWiresGeckoBackedKimiSearch() {
        val command = kimiAcpLaunchCommandText(384)
        assertTrue(command.contains("KIMI_WEB_SEARCH_BASE_URL='http://127.0.0.1:18791/search'"))
        assertTrue(command.contains("KIMI_WEB_FETCH_BASE_URL='http://127.0.0.1:18791/fetch'"))
        assertTrue(command.contains("KIMI_WEB_SEARCH_API_KEY='ditto-local'"))
        assertTrue(command.contains("KIMI_CODE_EXPERIMENTAL_FLAG=1"))
        assertTrue(command.contains("KIMI_CODE_EXPERIMENTAL_TOWER=1"))
        assertTrue(command.contains("kimi acp"))
    }

    @Test
    fun managedConfigEnablesBuiltinWebSearchAndFetchViaGecko() {
        val toml = kimiManagedConfigToml(
            modelAlias = "aether-demo/model",
            permissionMode = "yolo",
            providerId = "aether-demo",
            providerType = "openai",
            apiKey = "sk-test",
            baseUrl = "https://example.invalid/v1",
            modelId = "model",
            contextWindow = 128_000,
            maxTokens = 16_384,
        )
        assertTrue(toml.contains("[services.moonshot_search]"))
        assertTrue(toml.contains("[services.moonshot_fetch]"))
        assertTrue(toml.contains("18791/search"))
        assertTrue(toml.contains("18791/fetch"))
        assertFalse(toml.contains("disabled = [\"WebSearch\", \"FetchURL\"]"))
        assertTrue(toml.contains("yolo = true"))
        assertTrue(toml.contains("default_permission_mode = \"yolo\""))
        assertTrue(toml.contains("[experimental]"))
        assertTrue(toml.contains("subagent_fork = true"))
        assertTrue(toml.contains("tower = true"))
    }

    @Test
    fun managedConfigMapsDefaultAndPlanToCliPermissionIds() {
        val alwaysAsk = kimiManagedConfigToml(
            modelAlias = "aether-demo/model",
            permissionMode = "default",
            providerId = "aether-demo",
            providerType = "openai",
            apiKey = "sk-test",
            baseUrl = "https://example.invalid/v1",
            modelId = "model",
            contextWindow = 128_000,
            maxTokens = 16_384,
        )
        assertTrue(alwaysAsk.contains("default_permission_mode = \"manual\""))
        assertFalse(alwaysAsk.contains("yolo = true"))
        assertFalse(alwaysAsk.contains("default_plan_mode = true"))

        val plan = kimiManagedConfigToml(
            modelAlias = "aether-demo/model",
            permissionMode = "plan",
            providerId = "aether-demo",
            providerType = "openai",
            apiKey = "sk-test",
            baseUrl = "https://example.invalid/v1",
            modelId = "model",
            contextWindow = 128_000,
            maxTokens = 16_384,
        )
        assertTrue(plan.contains("default_permission_mode = \"manual\""))
        assertTrue(plan.contains("default_plan_mode = true"))
    }
}
