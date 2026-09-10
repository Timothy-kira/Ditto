package kira.ditto.data

import android.content.Context
import java.io.File
import java.util.UUID
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class PersonaKnowledgeSlice(
    val id: String,
    val sourceId: String,
    val sourceName: String,
    val text: String,
    val embedding: FloatArray,
    val index: Int,
)

class PersonaKnowledgeIndexer(
    private val context: Context,
    private val personaRepository: PersonaRepository,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val usageRecorder: UsageRecorder? = null,
) {
    @Suppress("UNUSED_PARAMETER")
    suspend fun importAndIndex(
        personaId: String,
        bytes: ByteArray,
        displayName: String,
        mimeType: String,
        vectorModel: ProviderModelOption?,
        onProgress: (Float, PersonaKnowledgeImportPhase) -> Unit = { _, _ -> },
    ): PersonaKnowledgeFile = withContext(Dispatchers.IO) {
        val knowledgeId = UUID.randomUUID().toString()
        val pending = PersonaKnowledgeFile(
            id = knowledgeId,
            displayName = displayName.ifBlank { "knowledge" },
            relativePath = "personas/$personaId/slices/${knowledgeId}.json",
            mimeType = mimeType,
            sizeBytes = bytes.size.toLong(),
            sliceCount = 0,
        )
        val persona = personaRepository.store.first().personas.firstOrNull { it.id == personaId }
            ?: error("Persona was not found.")
        personaRepository.upsertPersona(
            persona.copy(
                knowledgeFiles = persona.knowledgeFiles + pending,
                updatedAtMillis = System.currentTimeMillis(),
            ),
        )
        try {
            onProgress(0.08f, PersonaKnowledgeImportPhase.Parsing)
            PersonaPdfBox.ensure(context)
            val extracted = extractPersonaKnowledgeText(bytes, displayName, mimeType)
            if (extracted.isBlank()) error("Couldn't extract text from $displayName")
            onProgress(0.28f, PersonaKnowledgeImportPhase.Parsing)
            val chunks = chunkPersonaKnowledgeText(extracted)
            onProgress(0.36f, PersonaKnowledgeImportPhase.Embedding)
            val slices = chunks.mapIndexed { index, chunk ->
                PersonaKnowledgeSlice(
                    id = "$knowledgeId-$index",
                    sourceId = knowledgeId,
                    sourceName = displayName,
                    text = chunk,
                    embedding = floatArrayOf(),
                    index = index,
                )
            }
            val destination = File(context.filesDir, pending.relativePath)
            destination.parentFile?.mkdirs()
            destination.writeText(serializeSlices(slices))
            val file = pending.copy(
                sizeBytes = destination.length(),
                sliceCount = slices.size,
            )
            val latest = personaRepository.store.first().personas.firstOrNull { it.id == personaId }
                ?: error("Persona was not found.")
            personaRepository.upsertPersona(
                latest.copy(
                    knowledgeFiles = latest.knowledgeFiles.map { existing ->
                        if (existing.id == knowledgeId) file else existing
                    },
                    updatedAtMillis = System.currentTimeMillis(),
                ),
            )
            onProgress(1f, PersonaKnowledgeImportPhase.Embedding)
            file
        } catch (throwable: Throwable) {
            val latest = personaRepository.store.first().personas.firstOrNull { it.id == personaId }
            if (latest != null) {
                personaRepository.upsertPersona(
                    latest.copy(
                        knowledgeFiles = latest.knowledgeFiles.filterNot { it.id == knowledgeId },
                        updatedAtMillis = System.currentTimeMillis(),
                    ),
                )
            }
            throw throwable
        }
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun retrieve(
        personaId: String,
        query: String,
        vectorModel: ProviderModelOption?,
        limit: Int = 6,
    ): List<PersonaKnowledgeSlice> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val persona = personaRepository.store.first().personas.firstOrNull { it.id == personaId }
            ?: return@withContext emptyList()
        val slices = persona.knowledgeFiles.flatMap { file ->
            val stored = File(context.filesDir, file.relativePath)
            if (!stored.isFile) emptyList() else parseSlices(stored.readText())
        }
        if (slices.isEmpty()) return@withContext emptyList()
        val ranked = kira.ditto.browser.AgentIndex.rankTexts(query, slices.map { it.text }, limit = limit)
        ranked.map { slices[it] }
    }

    internal fun chunkText(text: String, maxChars: Int = 900): List<String> =
        chunkPersonaKnowledgeText(text, maxChars)

    private suspend fun embed(
        texts: List<String>,
        model: ProviderModelOption,
        client: OkHttpClient = httpClient,
        onBatch: (Float) -> Unit = {},
    ): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val token = embeddingBearerToken(
            apiKey = model.apiKey,
            oauthCredentialJson = model.oauthCredentialJson,
            environment = model.providerEnvironmentVariables.map { it.name to it.value },
            customHeaders = model.customHeaders,
        )
        if (token.isBlank() && model.authMethod != ProviderAuthMethod.Ambient) {
            error("Embedding request needs an API key. Choose a vector model that uses API-key auth.")
        }
        val url = embeddingRequestUrl(model.baseUrl)
        val vectors = ArrayList<FloatArray>(texts.size)
        val batches = texts.chunked(EmbeddingBatchSize)
        batches.forEachIndexed { batchIndex, batch ->
            vectors += embedBatch(url, batch, model, token, client)
            onBatch((batchIndex + 1).toFloat() / batches.size.toFloat())
        }
        return vectors
    }

    private suspend fun embedBatch(
        url: String,
        texts: List<String>,
        model: ProviderModelOption,
        token: String,
        client: OkHttpClient = httpClient,
    ): List<FloatArray> {
        val body = JSONObject()
            .put("model", model.modelId)
            .put("input", JSONArray(texts))
            .toString()
        val requestBuilder = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .applyAetherLlmHeaders(model.userAgent, model.customHeaders)
        applyEmbeddingAuthHeaders(requestBuilder, token)
        val response = client.newCall(requestBuilder.build()).execute()
        val payload = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            error("Embedding request failed (${response.code}): ${embeddingErrorMessage(payload)}")
        }
        val payloadJson = JSONObject(payload)
        val parsedUsage = parseEmbeddingUsage(payloadJson, texts.sumOf { it.length })
        usageRecorder?.recordEmbedding(
            providerId = model.piProviderId.ifBlank { model.providerId },
            modelId = model.modelId,
            usage = parsedUsage.first.takeIf { parsedUsage.second == "api" },
            inputChars = texts.sumOf { it.length },
        )
        val data = payloadJson.optJSONArray("data") ?: error("Embedding response had no data.")
        return buildList {
            for (index in 0 until data.length()) {
                val vector = data.optJSONObject(index)?.optJSONArray("embedding") ?: continue
                add(FloatArray(vector.length()) { offset -> vector.optDouble(offset).toFloat() })
            }
        }
    }

    private fun cosineSimilarity(left: FloatArray, right: FloatArray): Float {
        val size = minOf(left.size, right.size)
        if (size == 0) return 0f
        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0
        for (index in 0 until size) {
            dot += left[index] * right[index]
            leftNorm += left[index] * left[index]
            rightNorm += right[index] * right[index]
        }
        val denom = sqrt(leftNorm) * sqrt(rightNorm)
        return if (denom <= 0.0) 0f else (dot / denom).toFloat()
    }

    private fun serializeSlices(slices: List<PersonaKnowledgeSlice>): String = JSONArray().apply {
        slices.forEach { slice ->
            put(
                JSONObject()
                    .put("id", slice.id)
                    .put("source_id", slice.sourceId)
                    .put("source_name", slice.sourceName)
                    .put("text", slice.text)
                    .put("index", slice.index)
                    .put("embedding", JSONArray().apply { slice.embedding.forEach { put(it.toDouble()) } }),
            )
        }
    }.toString()

    private fun parseSlices(raw: String): List<PersonaKnowledgeSlice> {
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val embedding = item.optJSONArray("embedding")
                add(
                    PersonaKnowledgeSlice(
                        id = item.optString("id"),
                        sourceId = item.optString("source_id"),
                        sourceName = item.optString("source_name"),
                        text = item.optString("text"),
                        embedding = if (embedding == null) {
                            floatArrayOf()
                        } else {
                            FloatArray(embedding.length()) { offset ->
                                embedding.optDouble(offset).toFloat()
                            }
                        },
                        index = item.optInt("index"),
                    ),
                )
            }
        }
    }
}

internal fun chunkPersonaKnowledgeText(text: String, maxChars: Int = 900): List<String> {
    val paragraphs = text.split(Regex("\\n{2,}")).map { it.trim() }.filter { it.isNotEmpty() }
    val chunks = ArrayList<String>()
    val buffer = StringBuilder()
    fun flush() {
        if (buffer.isNotBlank()) {
            chunks += buffer.toString().trim()
            buffer.clear()
        }
    }
    paragraphs.ifEmpty { listOf(text.trim()) }.forEach { paragraph ->
        if (paragraph.length > maxChars) {
            flush()
            paragraph.chunked(maxChars).forEach { chunks += it.trim() }
        } else if (buffer.length + paragraph.length + 2 > maxChars) {
            flush()
            buffer.append(paragraph)
        } else {
            if (buffer.isNotEmpty()) buffer.append("\n\n")
            buffer.append(paragraph)
        }
    }
    flush()
    return chunks.filter { it.isNotBlank() }
}

fun formatKnowledgeCitations(slices: List<PersonaKnowledgeSlice>): String {
    if (slices.isEmpty()) return ""
    return buildString {
        append(
            "You have numbered knowledge slices. When a sentence is grounded in a slice, " +
                "append [[N]] immediately after that claim (N is the slice number). " +
                "Place the marker inline next to the sentence, not as a bibliography.\n" +
                "Never mention file names, PDFs, \"以上信息均来自\", \"according to the document\", " +
                "or any source attribution in the visible reply. " +
                "The host app reveals sources when the user taps [[N]].\n",
        )
        slices.forEachIndexed { index, slice ->
            append("\n[[")
            append(index + 1)
            append("]]\n")
            append(slice.text.trim())
            append('\n')
        }
    }.trim()
}

internal fun parseEmbeddingUsage(payload: JSONObject, inputChars: Int): Pair<LlmTokenUsage?, String> {
    val usage = payload.optJSONObject("usage")
    val prompt = usagePositiveLong(usage, "prompt_tokens", "promptTokens", "input_tokens", "inputTokens")
    val total = usagePositiveLong(usage, "total_tokens", "totalTokens")
    if (prompt != null || total != null) {
        val input = prompt ?: total
        val resolvedTotal = total ?: prompt
        return LlmTokenUsage(
            inputTokens = input,
            totalTokens = resolvedTotal,
        ) to "api"
    }
    val estimated = estimateTokensFromChars(inputChars)
    return LlmTokenUsage(
        inputTokens = estimated.takeIf { it > 0L },
        totalTokens = estimated.takeIf { it > 0L },
    ) to "estimated"
}

private fun usagePositiveLong(source: JSONObject?, vararg keys: String): Long? {
    if (source == null) return null
    keys.forEach { key ->
        if (source.has(key) && !source.isNull(key)) {
            val value = source.optLong(key)
            if (value > 0L) return value
        }
    }
    return null
}

internal const val EmbeddingBatchSize = 16

internal fun embeddingRequestUrl(baseUrl: String): String {
    val base = baseUrl.trim().trimEnd('/')
    if (base.isEmpty()) error("Vector model has no base URL.")
    val lower = base.lowercase()
    return when {
        lower.endsWith("/embeddings") -> base
        lower.endsWith("/chat/completions") ->
            base.substring(0, base.length - "/chat/completions".length) + "/embeddings"
        else -> "$base/embeddings"
    }
}

internal fun embeddingBearerToken(
    apiKey: String,
    oauthCredentialJson: String = "",
    environment: List<Pair<String, String>> = emptyList(),
    customHeaders: List<LlmCustomHeader> = emptyList(),
    fallbackApiKey: String = "",
): String {
    apiKey.trim().takeIf { it.isNotEmpty() }?.let { return it }
    val oauth = oauthCredentialJson.trim()
    if (oauth.isNotEmpty()) {
        val json = runCatching { JSONObject(oauth) }.getOrNull()
        if (json != null) {
            sequenceOf("access_token", "accessToken", "token", "api_key", "apiKey")
                .map { json.optString(it).trim() }
                .firstOrNull { it.isNotEmpty() }
                ?.let { return it }
            json.optJSONObject("tokens")?.optString("access")?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
        }
    }
    customHeaders.forEach { header ->
        val name = header.name.trim().lowercase()
        if (
            name == "authorization" ||
            name == "authentication" ||
            name == "api-key" ||
            name == "x-api-key"
        ) {
            header.value.trim()
                .removePrefix("Bearer ")
                .removePrefix("bearer ")
                .trim()
                .takeIf { it.isNotEmpty() }
                ?.let { return it }
        }
    }
    environment.forEach { (name, value) ->
        val key = name.trim().uppercase()
        if (key.endsWith("API_KEY") || key == "TOKEN" || key.endsWith("_TOKEN")) {
            value.trim().takeIf { it.isNotEmpty() }?.let { return it }
        }
    }
    fallbackApiKey.trim().takeIf { it.isNotEmpty() }?.let { return it }
    return ""
}

internal fun applyEmbeddingAuthHeaders(
    requestBuilder: Request.Builder,
    token: String,
) {
    val raw = token.trim().removePrefix("Bearer ").removePrefix("bearer ").trim()
    if (raw.isEmpty()) return
    val bearer = "Bearer $raw"
    requestBuilder.header("Authorization", bearer)
    requestBuilder.header("Authentication", bearer)
    requestBuilder.header("api-key", raw)
    requestBuilder.header("x-api-key", raw)
}

internal fun embeddingErrorMessage(payload: String): String {
    val trimmed = payload.trim()
    if (trimmed.isEmpty()) return "empty response"
    val json = runCatching { JSONObject(trimmed) }.getOrNull()
    val nested = json?.optJSONObject("error")
    val message = nested?.optString("message")?.trim().orEmpty()
        .ifBlank { json?.optString("message")?.trim().orEmpty() }
        .ifBlank { nested?.optString("code")?.trim().orEmpty() }
    return message.ifBlank { trimmed.take(240) }
}
