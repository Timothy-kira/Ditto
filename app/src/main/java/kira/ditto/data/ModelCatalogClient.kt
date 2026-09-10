package kira.ditto.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ModelCatalogInfo(
    val displayName: String,
    val labId: String,
    val labName: String,
    val labLogoUrl: String,
    val labLogoPathData: List<String> = emptyList(),
    val labLogoViewportWidth: Float = 40f,
    val labLogoViewportHeight: Float = 40f,
    val contextWindow: Long? = null,
)

object ModelCatalogClient {
    private const val CatalogUrl = "https://models.dev/catalog.json"
    private const val LogoBaseUrl = "https://models.dev/logos/labs"
    private val labLogoCacheLock = Any()
    private val labLogoCache = mutableMapOf<String, LabLogo?>()

    suspend fun fetchModelInfo(options: List<ProviderModelOption>): Map<String, ModelCatalogInfo> =
        withContext(Dispatchers.IO) {
            if (options.isEmpty()) return@withContext emptyMap()
            try {
                val catalog = fetchCatalogModels()
                val matchedInfo = options.map { option ->
                    val info = option.catalogLookupKeys()
                        .firstNotNullOfOrNull { key -> catalog.models[key.lowercase()] }
                        ?: option.inferredCatalogInfo()
                    option.key to info
                }
                val logoByLab = fetchLabLogos(
                    catalog.labIds + matchedInfo.map { it.second.labId },
                )
                matchedInfo.associate { (key, info) ->
                    val logo = logoByLab[info.labId]
                    key to if (logo == null) {
                        info
                    } else {
                        info.copy(
                            labLogoPathData = logo.pathData,
                            labLogoViewportWidth = logo.viewportWidth,
                            labLogoViewportHeight = logo.viewportHeight,
                        )
                    }
                }
            } catch (_: Exception) {
                emptyMap()
            }
        }

    private data class CatalogModels(
        val models: Map<String, ModelCatalogInfo>,
        val labIds: Set<String>,
    )

    private fun fetchCatalogModels(): CatalogModels {
        val connection = URL(CatalogUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000

        return try {
            if (connection.responseCode != 200) return CatalogModels(emptyMap(), emptySet())
            val root = JSONObject(connection.inputStream.bufferedReader().readText())
            val models = root.optJSONObject("models") ?: return CatalogModels(emptyMap(), emptySet())
            val labIds = mutableSetOf<String>()
            val modelInfo = buildMap {
                for (key in models.keys()) {
                    val model = models.optJSONObject(key) ?: continue
                    val id = model.optString("id", key).trim()
                    val labId = id.substringBefore('/').takeIf { it != id }.orEmpty()
                    if (labId.isNotBlank()) labIds += labId
                    val name = model.optString("name").trim()
                    if (name.isBlank()) continue
                    val contextWindow = model.optJSONObject("limit")
                        ?.optLong("context")
                        ?.takeIf { it > 0L }
                    val info = ModelCatalogInfo(
                        displayName = name,
                        labId = labId,
                        labName = labDisplayName(labId),
                        labLogoUrl = if (labId.isBlank()) "" else "$LogoBaseUrl/$labId.svg",
                        contextWindow = contextWindow,
                    )
                    put(key.trim().lowercase(), info)
                    put(id.lowercase(), info)
                    id.substringAfterLast('/').takeIf { it.isNotBlank() }?.let { shortId ->
                        putIfAbsent(shortId.lowercase(), info)
                    }
                }
            }
            CatalogModels(modelInfo, labIds)
        } finally {
            connection.disconnect()
        }
    }

    private fun ProviderModelOption.catalogLookupKeys(): List<String> = listOf(
        fullLabel,
        "$providerId/$modelId",
        modelId,
        modelId.substringAfterLast('/'),
    ).map(String::trim).filter(String::isNotEmpty).distinct()

    private fun ProviderModelOption.inferredCatalogInfo(): ModelCatalogInfo {
        val inferredLabId = inferLabId(modelId)
        return ModelCatalogInfo(
            displayName = modelId,
            labId = inferredLabId,
            labName = labDisplayName(inferredLabId),
            labLogoUrl = if (inferredLabId.isBlank()) "" else "$LogoBaseUrl/$inferredLabId.svg",
        )
    }

    private fun inferLabId(modelId: String): String {
        val normalized = modelId.substringAfterLast('/').lowercase()
        return when {
            normalized.startsWith("gpt") || normalized.startsWith("o1") || normalized.startsWith("o3") || normalized.startsWith("o4") -> "openai"
            normalized.startsWith("gemini") -> "google"
            normalized.startsWith("claude") -> "anthropic"
            normalized.startsWith("grok") -> "xai"
            normalized.startsWith("qwen") -> "alibaba"
            normalized.startsWith("kimi") -> "moonshotai"
            normalized.startsWith("mimo") -> "xiaomi"
            normalized.startsWith("glm") -> "zhipuai"
            normalized.startsWith("nemotron") -> "nvidia"
            normalized.startsWith("deepseek") -> "deepseek"
            normalized.startsWith("mistral") || normalized.startsWith("mixtral") || normalized.startsWith("codestral") -> "mistral"
            normalized.startsWith("llama") -> "meta"
            normalized.startsWith("phi") -> "microsoft"
            normalized.startsWith("minimax") || normalized.startsWith("abab") -> "minimax"
            normalized.startsWith("sonar") -> "perplexity"
            normalized.startsWith("command") -> "cohere"
            else -> ""
        }
    }

    private data class LabLogo(
        val pathData: List<String>,
        val viewportWidth: Float,
        val viewportHeight: Float,
    )

    private suspend fun fetchLabLogos(labIds: Collection<String>): Map<String, LabLogo?> = coroutineScope {
        val normalizedLabIds = labIds
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
        val missingLabIds = synchronized(labLogoCacheLock) {
            normalizedLabIds.filterNot(labLogoCache::containsKey)
        }
        missingLabIds.map { labId ->
            async(Dispatchers.IO) {
                labId to runCatching { fetchLabLogo(labId) }.getOrNull()
            }
        }.awaitAll().forEach { (labId, logo) ->
            synchronized(labLogoCacheLock) {
                labLogoCache[labId] = logo
            }
        }
        synchronized(labLogoCacheLock) {
            normalizedLabIds.associateWith { labId -> labLogoCache[labId] }
        }
    }

    private fun fetchLabLogo(labId: String): LabLogo? {
        val connection = URL("$LogoBaseUrl/$labId.svg").openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 5_000
        connection.readTimeout = 10_000

        return try {
            if (connection.responseCode != 200) return null
            parseLabLogoSvg(connection.inputStream.bufferedReader().readText())
        } finally {
            connection.disconnect()
        }
    }

    private fun parseLabLogoSvg(svg: String): LabLogo? {
        val viewBoxMatch = Regex("""viewBox\s*=\s*"([^"]+)"""").find(svg)
        val viewBox = viewBoxMatch?.groupValues?.getOrNull(1)
            ?.split(Regex("\\s+|,"))
            ?.mapNotNull { it.toFloatOrNull() }
            .orEmpty()
        val viewportWidth = viewBox.getOrNull(2) ?: 40f
        val viewportHeight = viewBox.getOrNull(3) ?: 40f
        val pathData = Regex("""<path\b[^>]*\bd\s*=\s*"([^"]+)"""")
            .findAll(svg)
            .mapNotNull { it.groupValues.getOrNull(1)?.trim()?.takeIf(String::isNotBlank) }
            .toList()
        if (pathData.isEmpty()) return null
        return LabLogo(
            pathData = pathData,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
        )
    }

    private fun labDisplayName(labId: String): String = when (labId.lowercase()) {
        "alibaba" -> "Alibaba"
        "anthropic" -> "Anthropic"
        "cohere" -> "Cohere"
        "deepreinforce" -> "DeepReinforce"
        "deepseek" -> "DeepSeek"
        "google" -> "Google"
        "meituan" -> "Meituan"
        "meta" -> "Meta"
        "microsoft" -> "Microsoft"
        "minimax" -> "MiniMax"
        "mistral" -> "Mistral"
        "moonshotai" -> "Moonshot AI"
        "nvidia" -> "NVIDIA"
        "openai" -> "OpenAI"
        "perplexity" -> "Perplexity"
        "sakana" -> "Sakana AI"
        "sarvam" -> "Sarvam AI"
        "stepfun" -> "StepFun"
        "tencent" -> "Tencent"
        "xai" -> "xAI"
        "xiaomi" -> "Xiaomi"
        "zhipuai" -> "Zhipu AI"
        else -> labId.split('-', '_')
            .filter(String::isNotBlank)
            .joinToString(" ") { token ->
                token.replaceFirstChar { char ->
                    if (char.isLowerCase()) char.titlecase() else char.toString()
                }
            }
    }
}
