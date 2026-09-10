package kira.ditto.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath

@Serializable
data class SharedThinkingCatalogCache(
    val source: String = "",
    val levelsByProviderModel: Map<String, List<String>> = emptyMap(),
    val clampsByProviderModel: Map<String, Map<String, String>> = emptyMap(),
    val reasoningModels: Set<String> = emptySet(),
)

@Serializable
data class SharedModelCatalogCache(
    val models: Map<String, SharedModelCatalogInfo> = emptyMap(),
)

const val ModelsDevThinkingCatalogSource = "models.dev"

data class SharedPersistedSettings(
    val providerConfigs: List<LlmProviderConfig> = emptyList(),
    val activeProviderConfigId: String = "",
    val onboardingCompletedVersion: Int = 0,
    val appSettings: AppSettings = AppSettings(),
    val thinkingCatalogCache: SharedThinkingCatalogCache = SharedThinkingCatalogCache(),
    val modelCatalogCache: SharedModelCatalogCache = SharedModelCatalogCache(),
    val uiState: SharedPersistedUiState = SharedPersistedUiState(),
) {
    val activeProviderConfig: LlmProviderConfig?
        get() = providerConfigs.firstOrNull { it.id == activeProviderConfigId && it.isEnabled }
            ?: providerConfigs.firstOrNull { it.isEnabled }
            ?: providerConfigs.firstOrNull()
}

data class SharedPersistedUiState(
    val route: String = "",
)

class AetherSettingsStore(
    private val dataStore: DataStore<Preferences>,
) {
    suspend fun load(): SharedPersistedSettings {
        val preferences = dataStore.data.first()
        val defaults = AppSettings()
        // The first launch follows the platform language once, then keeps the
        // persisted choice stable even if the system language changes later.
        val storedLanguage = preferences[Language]
        val initialLanguage = AppLanguage.fromStorage(
            storedLanguage ?: preferences[AppSettingsJson]
                ?.let { parseAppSettings(it, defaults).language.storageValue },
        )
        if (storedLanguage != initialLanguage.storageValue) {
            dataStore.edit { it[Language] = initialLanguage.storageValue }
        }
        val legacySettings = defaults.copy(
            language = initialLanguage,
            themeMode = AppThemeMode.fromStorage(preferences[ThemeMode]),
            systemPrompt = preferences[SystemPrompt] ?: defaults.systemPrompt,
            reasoningEffort = normalizeReasoningEffort(preferences[ReasoningEffort]),
            onboardingCompletedVersion = preferences[OnboardingCompletedVersion] ?: 0,
        )
        val fullSettings = parseAppSettings(preferences[AppSettingsJson].orEmpty(), legacySettings)
        val privacyPolicyAccepted = preferences[PrivacyPolicyAccepted]
            ?: fullSettings.privacyPolicyAccepted
        return SharedPersistedSettings(
            providerConfigs = parseProviderConfigs(preferences[ProviderConfigs].orEmpty()),
            activeProviderConfigId = preferences[ActiveProviderConfigId].orEmpty(),
            onboardingCompletedVersion = preferences[OnboardingCompletedVersion] ?: 0,
            appSettings = fullSettings.copy(
                onboardingCompletedVersion = preferences[OnboardingCompletedVersion] ?: 0,
                privacyPolicyAccepted = privacyPolicyAccepted,
            ),
            thinkingCatalogCache = parseSharedThinkingCatalogCache(
                preferences[ThinkingCatalogCacheJson].orEmpty(),
            ),
            modelCatalogCache = parseSharedModelCatalogCache(
                preferences[ModelCatalogCacheJson].orEmpty(),
            ),
            uiState = SharedPersistedUiState(
                route = preferences[LastRoute].orEmpty(),
            ),
        )
    }

    suspend fun saveProvider(config: LlmProviderConfig) {
        dataStore.edit { preferences ->
            val current = parseProviderConfigs(preferences[ProviderConfigs].orEmpty())
            val updated = current.filterNot { it.id == config.id } + config
            preferences[ProviderConfigs] = serializeProviderConfigs(updated)
            preferences[ActiveProviderConfigId] = config.id
        }
    }

    suspend fun saveProviders(
        configs: List<LlmProviderConfig>,
        activeProviderConfigId: String,
    ) {
        dataStore.edit { preferences ->
            val normalized = configs.distinctBy(LlmProviderConfig::id)
            preferences[ProviderConfigs] = serializeProviderConfigs(normalized)
            preferences[ActiveProviderConfigId] = activeProviderConfigId
                .takeIf { id -> normalized.any { it.id == id } }
                ?: normalized.firstOrNull()?.id.orEmpty()
        }
    }

    suspend fun setActiveProvider(configId: String) {
        dataStore.edit { preferences ->
            val configs = parseProviderConfigs(preferences[ProviderConfigs].orEmpty())
            if (configs.any { it.id == configId && it.isEnabled }) {
                preferences[ActiveProviderConfigId] = configId
            }
        }
    }

    suspend fun deleteProvider(configId: String) {
        dataStore.edit { preferences ->
            val updated = parseProviderConfigs(preferences[ProviderConfigs].orEmpty())
                .filterNot { it.id == configId }
            preferences[ProviderConfigs] = serializeProviderConfigs(updated)
            if (preferences[ActiveProviderConfigId] == configId) {
                preferences[ActiveProviderConfigId] = updated.firstOrNull { it.isEnabled }?.id
                    ?: updated.firstOrNull()?.id.orEmpty()
            }
        }
    }

    suspend fun saveGeneralSettings(settings: AppSettings) {
        dataStore.edit { preferences ->
            val accepted = privacyPolicyAccepted(
                persisted = preferences[PrivacyPolicyAccepted]
                    ?: parseAppSettings(preferences[AppSettingsJson].orEmpty()).privacyPolicyAccepted,
                requested = settings.privacyPolicyAccepted,
            )
            val persistedSettings = settings.copy(privacyPolicyAccepted = accepted)
            preferences[AppSettingsJson] = serializeAppSettings(persistedSettings)
            preferences[PrivacyPolicyAccepted] = accepted
            preferences[Language] = settings.language.storageValue
            preferences[ThemeMode] = settings.themeMode.storageValue
            preferences[SystemPrompt] = settings.systemPrompt
            preferences[ReasoningEffort] = normalizeReasoningEffort(settings.reasoningEffort)
        }
    }

    suspend fun saveThinkingCatalogCache(cache: SharedThinkingCatalogCache) {
        dataStore.edit { preferences ->
            val current = parseSharedThinkingCatalogCache(preferences[ThinkingCatalogCacheJson].orEmpty())
            val refreshedKeys = cache.levelsByProviderModel.keys
            val merged = SharedThinkingCatalogCache(
                source = cache.source.ifBlank { current.source },
                levelsByProviderModel = current.levelsByProviderModel + cache.levelsByProviderModel,
                clampsByProviderModel =
                    (current.clampsByProviderModel - refreshedKeys) + cache.clampsByProviderModel,
                reasoningModels = (current.reasoningModels - refreshedKeys) + cache.reasoningModels,
            )
            preferences[ThinkingCatalogCacheJson] = serializeSharedThinkingCatalogCache(merged)
        }
    }

    suspend fun saveModelCatalogCache(cache: SharedModelCatalogCache) {
        dataStore.edit { preferences ->
            val merged = parseSharedModelCatalogCache(preferences[ModelCatalogCacheJson].orEmpty())
                .models + cache.models
            preferences[ModelCatalogCacheJson] = SharedSettingsJson.encodeToString(
                SharedModelCatalogCache(merged)
            )
        }
    }

    suspend fun saveUiState(route: String) {
        dataStore.edit { preferences ->
            preferences[LastRoute] = route
        }
    }

    suspend fun markOnboardingComplete() {
        dataStore.edit { preferences ->
            preferences[OnboardingCompletedVersion] = CurrentOnboardingVersion
            val current = parseAppSettings(preferences[AppSettingsJson].orEmpty())
            preferences[AppSettingsJson] = serializeAppSettings(
                current.copy(
                    onboardingSeenVersion = CurrentOnboardingVersion,
                    onboardingCompletedVersion = CurrentOnboardingVersion,
                )
            )
        }
    }

    suspend fun markOnboardingSeen() {
        dataStore.edit { preferences ->
            val current = parseAppSettings(preferences[AppSettingsJson].orEmpty())
            preferences[AppSettingsJson] = serializeAppSettings(
                current.copy(onboardingSeenVersion = CurrentOnboardingVersion)
            )
        }
    }

    suspend fun acceptPrivacyPolicy() {
        dataStore.edit { preferences ->
            val current = parseAppSettings(preferences[AppSettingsJson].orEmpty())
            val updated = current.copy(privacyPolicyAccepted = true)
            preferences[AppSettingsJson] = serializeAppSettings(updated)
            preferences[PrivacyPolicyAccepted] = true
        }
    }

    suspend fun replaceAll(persisted: SharedPersistedSettings) {
        dataStore.edit { preferences ->
            val accepted = privacyPolicyAccepted(
                persisted = preferences[PrivacyPolicyAccepted] ?: false,
                requested = persisted.appSettings.privacyPolicyAccepted,
            )
            val persistedSettings = persisted.appSettings.copy(privacyPolicyAccepted = accepted)
            preferences[ProviderConfigs] = serializeProviderConfigs(persisted.providerConfigs)
            preferences[ActiveProviderConfigId] = persisted.activeProviderConfigId
            preferences[OnboardingCompletedVersion] = persisted.onboardingCompletedVersion
            preferences[AppSettingsJson] = serializeAppSettings(persistedSettings)
            preferences[PrivacyPolicyAccepted] = accepted
            preferences[Language] = persisted.appSettings.language.storageValue
            preferences[ThemeMode] = persisted.appSettings.themeMode.storageValue
            preferences[SystemPrompt] = persisted.appSettings.systemPrompt
            preferences[ReasoningEffort] = normalizeReasoningEffort(persisted.appSettings.reasoningEffort)
        }
    }

    private companion object {
        val ProviderConfigs = stringPreferencesKey("provider_configs")
        val ActiveProviderConfigId = stringPreferencesKey("provider_config_id")
        val OnboardingCompletedVersion = intPreferencesKey("onboarding_completed_version")
        val Language = stringPreferencesKey("language")
        val ThemeMode = stringPreferencesKey("theme_mode")
        val SystemPrompt = stringPreferencesKey("system_prompt")
        val ReasoningEffort = stringPreferencesKey("reasoning_effort")
        val AppSettingsJson = stringPreferencesKey("app_settings_json")
        val PrivacyPolicyAccepted = booleanPreferencesKey("privacy_policy_accepted")
        val ThinkingCatalogCacheJson = stringPreferencesKey("thinking_catalog_cache_json")
        val ModelCatalogCacheJson = stringPreferencesKey("model_catalog_cache_json")
        val LastRoute = stringPreferencesKey("last_route")
    }
}

internal fun privacyPolicyAccepted(persisted: Boolean, requested: Boolean): Boolean =
    persisted || requested

private val SharedThinkingCatalogCacheJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private val SharedSettingsJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

internal fun parseSharedModelCatalogCache(value: String): SharedModelCatalogCache =
    value.takeIf(String::isNotBlank)
        ?.let { runCatching { SharedSettingsJson.decodeFromString<SharedModelCatalogCache>(it) }.getOrNull() }
        ?: SharedModelCatalogCache()

internal fun parseSharedThinkingCatalogCache(value: String): SharedThinkingCatalogCache =
    value.takeIf(String::isNotBlank)
        ?.let { runCatching { SharedThinkingCatalogCacheJson.decodeFromString<SharedThinkingCatalogCache>(it) }.getOrNull() }
        ?: SharedThinkingCatalogCache()

internal fun serializeSharedThinkingCatalogCache(cache: SharedThinkingCatalogCache): String =
    SharedThinkingCatalogCacheJson.encodeToString(cache)

fun createAetherSettingsStore(path: String): AetherSettingsStore = AetherSettingsStore(
    PreferenceDataStoreFactory.createWithPath(
        produceFile = { path.toPath() },
    )
)
