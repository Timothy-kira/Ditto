package kira.ditto.data

internal const val MaxCachedFetchedModels = 2000
internal const val MaxAutoEnabledFetchedModels = 24
internal const val MaxOverflowEmbeddingModels = 120

private val OpenAiModelIdPattern = Regex(""""id"\s*:\s*"([^"]+)"""")
private const val LargeModelListChars = 400_000

fun parseOpenAiCompatibleModelIds(
    body: String,
    maxModels: Int = MaxCachedFetchedModels,
): List<String> {
    val text = body.trim()
    if (text.isEmpty()) return emptyList()
    val ids = LinkedHashSet<String>()
    val overflowEmbeddings = LinkedHashSet<String>()
    for (match in OpenAiModelIdPattern.findAll(text)) {
        val id = match.groupValues[1].trim()
        if (id.isBlank() || id.equals("model", ignoreCase = true)) continue
        if (ids.size < maxModels) {
            ids += id
        } else if (
            looksLikeEmbeddingModel(id) &&
            overflowEmbeddings.size < MaxOverflowEmbeddingModels
        ) {
            overflowEmbeddings += id
        }
    }
    return (ids + overflowEmbeddings).toList()
}

fun shouldScanModelListByRegex(body: String): Boolean = body.length >= LargeModelListChars

fun looksLikeEmbeddingModel(id: String): Boolean {
    val n = id.lowercase()
    return n.contains("embed") ||
        n.contains("bge") ||
        n.contains("gte-") ||
        n.contains("gte_") ||
        n.contains("/gte") ||
        n.contains("voyage") ||
        n.contains("nomic") ||
        n.contains("m3e") ||
        n.contains("jina-clip") ||
        n.contains("text-embedding")
}

fun looksLikeAsrModel(id: String): Boolean {
    val n = id.lowercase()
    return n.contains("whisper") ||
        n.contains("paraformer") ||
        n.contains("sensevoice") ||
        n.contains("sense-voice") ||
        n.contains("speech-to-text") ||
        n.contains("speechtotext") ||
        n.contains("transcribe") ||
        n.contains("asr") ||
        (n.contains("speech") && !n.contains("speechless"))
}

fun looksLikeTtsModel(id: String): Boolean {
    val n = id.lowercase()
    return n.contains("tts") ||
        n.contains("step-tts") ||
        (n.contains("speech") && !n.contains("asr") && !n.contains("transcribe") && !n.contains("speechless"))
}

fun looksLikeRealtimeModel(id: String): Boolean {
    val n = id.lowercase()
    return n.contains("realtime") || n.contains("step-audio")
}

fun looksLikeImageModel(id: String): Boolean {
    val n = id.lowercase()
    return n.contains("dall-e") ||
        n.contains("dalle") ||
        n.contains("gpt-image") ||
        n.contains("imagen") ||
        n.contains("imagegen") ||
        n.contains("flux") ||
        n.contains("kolors") ||
        n.contains("wanx") ||
        n.contains("seedream") ||
        n.contains("qwen-image") ||
        n.contains("qwenimage") ||
        n.contains("stable-diffusion") ||
        n.contains("stable_diffusion") ||
        (n.contains("image") && !looksLikeEmbeddingModel(id))
}

fun autoEnableFetchedModels(
    fetched: List<String>,
    previouslyEnabled: List<String>,
    previousCached: Set<String>,
    preferredModelId: String = "",
): List<String> {
    val available = fetched
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
    val availableSet = available.toHashSet()
    val previous = previouslyEnabled
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
    val kept = previous.filter { it in availableSet }
    val extras = previous.filter { it !in availableSet }
    val hasPriorSelection = previous.isNotEmpty() || previousCached.any { it.isNotBlank() }
    if (hasPriorSelection) {
        return (kept + extras).ifEmpty {
            seedEnabledFetchedModels(available, availableSet, preferredModelId)
        }
    }
    return seedEnabledFetchedModels(available, availableSet, preferredModelId)
}

private fun seedEnabledFetchedModels(
    available: List<String>,
    availableSet: Set<String>,
    preferredModelId: String,
): List<String> {
    val preferred = preferredModelId.trim()
    return buildList {
        if (preferred.isNotEmpty() && preferred in availableSet) add(preferred)
        addAll(available.sortedByPreferredModelName())
    }.distinct().take(MaxAutoEnabledFetchedModels)
}
