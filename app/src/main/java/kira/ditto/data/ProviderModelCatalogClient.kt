package kira.ditto.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private val ThinkingLevels = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")

internal fun thinkingCatalogKey(providerId: String, modelId: String): String =
    "${providerId.trim()}/${modelId.substringAfterLast('/').trim()}"

private fun ProviderModelOption.publicCatalogProviderIds(): List<String> = buildList {
    add(
        when (piProviderId.trim()) {
            "openai-codex" -> "openai"
            "kimi-coding" -> "moonshotai"
            else -> piProviderId.trim()
        }
    )
    modelId.substringBeforeLast('/', "").trim().takeIf(String::isNotBlank)?.let(::add)
    if (modelId.substringAfterLast('/').trim().startsWith("kimi-", ignoreCase = true)) {
        add("moonshotai")
    }
}.filter(String::isNotBlank).distinct()

private fun ProviderModelOption.publicCatalogModelKeys(): List<String> = listOf(
    modelId,
    modelId.substringAfter("$piProviderId/", modelId),
    modelId.substringAfterLast('/'),
).map(String::trim).filter(String::isNotBlank).distinct()

private fun JSONObject.findPublicCatalogModel(option: ProviderModelOption): JSONObject? {
    option.publicCatalogModelKeys().firstNotNullOfOrNull(::optJSONObject)?.let { return it }
    val normalizedModelId = option.modelId.substringAfterLast('/').trim()
    return keys().asSequence().mapNotNull { key ->
        optJSONObject(key)?.takeIf { model ->
            listOf(key, model.optString("id")).any { candidateId ->
                candidateId.substringAfterLast('/').trim()
                    .equals(normalizedModelId, ignoreCase = true)
            }
        }
    }.firstOrNull()
}

private fun JSONObject.findPublicCatalogModelAcrossProviders(option: ProviderModelOption): JSONObject? {
    option.publicCatalogProviderIds().firstNotNullOfOrNull { providerId ->
        optJSONObject(providerId)?.optJSONObject("models")?.findPublicCatalogModel(option)
    }?.let { return it }
    return keys().asSequence().mapNotNull { providerId ->
        optJSONObject(providerId)?.optJSONObject("models")?.findPublicCatalogModel(option)
    }.firstOrNull()
}

data class PublicCatalogThinkingResult(
    val levelsByProviderModel: Map<String, List<String>> = emptyMap(),
    val levelMapsByProviderModel: Map<String, Map<String, String>> = emptyMap(),
    val reasoningModels: Set<String> = emptySet(),
)

internal fun publicCatalogThinkingResult(
    catalog: JSONObject,
    options: List<ProviderModelOption>,
): PublicCatalogThinkingResult {
    val providers = catalog.optJSONObject("providers") ?: return PublicCatalogThinkingResult()
    val levelsMap = mutableMapOf<String, List<String>>()
    val levelMapsMap = mutableMapOf<String, Map<String, String>>()
    val reasoningModels = mutableSetOf<String>()
    options.forEach { option ->
        val model = providers.findPublicCatalogModelAcrossProviders(option)
        if (model?.optBoolean("reasoning") == true) {
            val key = thinkingCatalogKey(option.piProviderId, option.modelId)
            reasoningModels += key
            val reasoningOptions = model.optJSONArray("reasoning_options")
            val hasToggle = (0 until (reasoningOptions?.length() ?: 0)).any {
                reasoningOptions?.optJSONObject(it)?.optString("type") == "toggle"
            }
            var hasNone = false
            val levels = buildList {
                if (hasToggle) add("off")
                for (index in 0 until (reasoningOptions?.length() ?: 0)) {
                    val effort = reasoningOptions?.optJSONObject(index) ?: continue
                    if (effort.optString("type") != "effort") continue
                    val values = effort.optJSONArray("values") ?: continue
                    for (valueIndex in 0 until values.length()) {
                        val value = values.optString(valueIndex).trim()
                        if (value == "none") {
                            hasNone = true
                            if ("off" !in this) add("off")
                        } else if (value in ThinkingLevels && value !in this) {
                            add(value)
                        }
                    }
                }
            }
            val levelMap = buildMap<String, String> {
                if (hasToggle || hasNone) put("off", "none")
            }
            levelsMap[key] = levels
            if (levelMap.isNotEmpty()) levelMapsMap[key] = levelMap
        } else if (model != null) {
            val key = thinkingCatalogKey(option.piProviderId, option.modelId)
            levelsMap[key] = emptyList()
        }
    }
    return PublicCatalogThinkingResult(levelsMap, levelMapsMap, reasoningModels)
}

internal fun publicCatalogThinkingLevels(
    catalog: JSONObject,
    options: List<ProviderModelOption>,
): Map<String, List<String>> = publicCatalogThinkingResult(catalog, options).levelsByProviderModel

object ProviderModelCatalogClient {

    private const val ModelsDevUrl = "https://models.dev/catalog.json"

    data class FetchModelsResult(
        val models: List<String>,
        val error: String? = null,
    )

    suspend fun fetchModels(
        config: LlmProviderConfig,
    ): FetchModelsResult = fetchModels(config, ModelsDevUrl)

    internal suspend fun fetchModels(
        config: LlmProviderConfig,
        modelsDevUrl: String,
    ): FetchModelsResult = withContext(Dispatchers.IO) {
        try {
            val definition = PiProviderCatalog.resolve(config.piProviderId)
            val providerModels = runCatching { fetchProviderModels(config) }.getOrElse { error ->
                FetchModelsResult(emptyList(), error.message ?: "Unable to fetch models.")
            }
            if (providerModels.models.isNotEmpty()) return@withContext providerModels

            val publicModels = fetchModelsDevModels(definition, modelsDevUrl)
            if (publicModels.models.isNotEmpty()) publicModels else FetchModelsResult(
                models = emptyList(),
                error = providerModels.error ?: publicModels.error,
            )
        } catch (e: Exception) {
            FetchModelsResult(emptyList(), e.message ?: "Unknown error")
        }
    }

    suspend fun fetchPublicThinkingCatalog(options: List<ProviderModelOption>): PublicCatalogThinkingResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val connection = URL("https://models.dev/catalog.json").openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10_000
                connection.readTimeout = 20_000
                try {
                    if (connection.responseCode != 200) return@runCatching PublicCatalogThinkingResult()
                    publicCatalogThinkingResult(
                        JSONObject(connection.inputStream.bufferedReader().readText()),
                        options,
                    )
                } finally {
                    connection.disconnect()
                }
            }.getOrDefault(PublicCatalogThinkingResult())
        }

    suspend fun fetchPublicThinkingLevels(options: List<ProviderModelOption>): Map<String, List<String>> =
        fetchPublicThinkingCatalog(options).levelsByProviderModel

    suspend fun resolveThinkingCatalog(option: ProviderModelOption): PublicCatalogThinkingResult {
        val catalog = fetchPublicThinkingCatalog(listOf(option))
        val key = thinkingCatalogKey(option.piProviderId, option.modelId)
        if (key in catalog.levelsByProviderModel) return catalog
        return catalog.withProbedThinking(key, probeProviderThinkingLevels(option))
    }

    suspend fun probeProviderThinkingLevels(
        option: ProviderModelOption,
    ): ThinkingProbeResult = withContext(Dispatchers.IO) {
        if (option.baseUrl.isBlank()) return@withContext ThinkingProbeResult()
        if (option.authMethod == ProviderAuthMethod.ApiKey && option.apiKey.isBlank()) {
            return@withContext ThinkingProbeResult()
        }
        val results = mutableListOf<ThinkingProbeResult>()
        for (style in thinkingProbeStyles(option.piProviderId, option.modelId)) {
            val posted = postThinkingProbe(option, style)
            results += posted
            if (posted.levels.isNotEmpty()) return@withContext posted
            if (!posted.parameterUnsupported && !posted.ignored) {
                return@withContext posted
            }
        }
        reduceThinkingProbeResults(results)
    }

    private fun postThinkingProbe(
        option: ProviderModelOption,
        style: ThinkingProbeStyle,
    ): ThinkingProbeResult {
        val connection = URL(
            thinkingProbeUrl(option.baseUrl, option.piProviderId, option.modelId, style),
        ).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 8_000
        connection.readTimeout = 12_000
        connection.setRequestProperty("Content-Type", "application/json")
        if (option.apiKey.isNotBlank()) {
            connection.setRequestProperty("Authorization", "Bearer ${option.apiKey}")
            if (style == ThinkingProbeStyle.AnthropicMessages) {
                connection.setRequestProperty("x-api-key", option.apiKey)
                connection.setRequestProperty("anthropic-version", "2023-06-01")
            }
        }
        connection.applyAetherLlmHeaders(option.userAgent, option.customHeaders)
        return try {
            connection.outputStream.use { stream ->
                stream.write(thinkingProbeRequestBody(option.modelId, style).toByteArray())
            }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.readText()
                .orEmpty()
            interpretThinkingProbeResponse(status, text)
        } catch (_: Exception) {
            ThinkingProbeResult()
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchModelsDevModels(
        definition: PiProviderDefinition,
        modelsDevUrl: String = ModelsDevUrl,
    ): FetchModelsResult {
        val connection = URL(modelsDevUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        return try {
            if (connection.responseCode != 200) {
                return FetchModelsResult(emptyList(), "models.dev returned HTTP ${connection.responseCode}.")
            }
            modelsDevProviderModels(
                JSONObject(connection.inputStream.bufferedReader().readText()),
                definition.modelsDevProviderIds(),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchProviderModels(config: LlmProviderConfig): FetchModelsResult {
        val modelsUrl = providerModelsEndpoint(config.baseUrl)

        val connection = URL(modelsUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.applyAetherLlmHeaders(config.userAgent, config.customHeaders)
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000

        return try {
            if (connection.responseCode == 200) {
                val responseText = connection.inputStream.bufferedReader().readText()
                val models = if (shouldScanModelListByRegex(responseText)) {
                    parseOpenAiCompatibleModelIds(responseText)
                } else {
                    val json = JSONObject(responseText)
                    val dataArray = json.optJSONArray("data")
                    val parsed = mutableListOf<String>()
                    if (dataArray != null) {
                        for (i in 0 until dataArray.length()) {
                            val modelId = dataArray.optJSONObject(i)?.optString("id")
                            if (!modelId.isNullOrBlank()) parsed.add(modelId)
                        }
                    }
                    if (parsed.isEmpty()) parseOpenAiCompatibleModelIds(responseText) else parsed
                }
                FetchModelsResult(
                    models
                        .map(String::trim)
                        .filter(String::isNotBlank)
                        .distinctBy { it.lowercase() },
                )
            } else {
                val errorText = connection.errorStream?.bufferedReader()?.readText() ?: "HTTP ${connection.responseCode}"
                FetchModelsResult(emptyList(), errorText)
            }
        } finally {
            connection.disconnect()
        }
    }

}

internal fun PublicCatalogThinkingResult.withProbedThinking(
    key: String,
    probe: ThinkingProbeResult,
): PublicCatalogThinkingResult {
    if (!probe.resolved) return this
    return copy(
        levelsByProviderModel = levelsByProviderModel + (key to probe.levels),
        levelMapsByProviderModel = if (probe.levelMap.isNotEmpty()) {
            levelMapsByProviderModel + (key to probe.levelMap)
        } else {
            levelMapsByProviderModel
        },
        reasoningModels = if (probe.reasoningSupported) {
            reasoningModels + key
        } else {
            reasoningModels - key
        },
    )
}

internal fun providerModelsEndpoint(baseUrl: String): String {
    val normalized = baseUrl.trim().trimEnd('/')
    require(normalized.isNotBlank()) { "A base URL is required to fetch models." }
    if (
        normalized.equals("https://api.stepfun.com", ignoreCase = true) ||
        normalized.startsWith("https://api.stepfun.com/step_plan", ignoreCase = true)
    ) {
        // StepFun exposes model discovery on the public /v1 endpoint even
        // when inference uses its Step Plan Anthropic endpoint.
        return "https://api.stepfun.com/v1/models"
    }
    return when {
        normalized.endsWith("/responses") -> normalized.removeSuffix("/responses") + "/models"
        normalized.endsWith("/chat/completions") -> normalized.removeSuffix("/chat/completions") + "/models"
        else -> "$normalized/models"
    }
}

internal fun modelsDevProviderModels(
    catalog: JSONObject,
    providerIds: List<String>,
): ProviderModelCatalogClient.FetchModelsResult {
    val providers = catalog.optJSONObject("providers")
        ?: return ProviderModelCatalogClient.FetchModelsResult(
            emptyList(),
            "No provider catalog was returned by models.dev.",
        )
    providerIds.forEach { providerId ->
        val models = providers.optJSONObject(providerId)?.optJSONObject("models") ?: return@forEach
        val modelIds = buildList {
            models.keys().forEach { key ->
                val modelId = models.optJSONObject(key)?.optString("id").orEmpty()
                    .ifBlank { key }
                    .trim()
                if (modelId.isNotBlank()) add(modelId)
            }
        }.distinctBy(String::lowercase)
        if (modelIds.isNotEmpty()) {
            return ProviderModelCatalogClient.FetchModelsResult(modelIds)
        }
    }
    return ProviderModelCatalogClient.FetchModelsResult(
        emptyList(),
        "Provider ${providerIds.joinToString()} is unavailable in models.dev.",
    )
}
