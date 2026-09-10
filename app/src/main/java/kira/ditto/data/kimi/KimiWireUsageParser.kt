package kira.ditto.data.kimi

import java.io.BufferedReader
import java.io.File
import org.json.JSONObject

data class KimiWireUsageDelta(
    val modelId: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long,
    val totalTokens: Long,
)

data class KimiWireExtractResult(
    val deltas: List<KimiWireUsageDelta>,
    val totalRecordCount: Int,
)

object KimiWireUsageParser {
    fun extractTurnUsageSince(wireFile: File, cursor: Int): KimiWireExtractResult {
        if (!wireFile.isFile) return KimiWireExtractResult(emptyList(), 0)
        return wireFile.bufferedReader().use { reader ->
            extractTurnUsageSince(reader, cursor)
        }
    }

    fun extractTurnUsageSince(reader: BufferedReader, cursor: Int): KimiWireExtractResult {
        val parsed = ArrayList<KimiWireUsageDelta?>()
        while (true) {
            val line = reader.readLine() ?: break
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val record = runCatching { JSONObject(trimmed) }.getOrNull() ?: continue
            if (record.optString("type") != "usage.record") continue
            parsed += parseTurnDelta(record)
        }
        val total = parsed.size
        if (total == 0) return KimiWireExtractResult(emptyList(), 0)
        val slice = when {
            cursor <= 0 -> parsed
            cursor == total -> emptyList()
            cursor > total -> parsed.takeLast(1)
            else -> parsed.subList(cursor, total)
        }
        return KimiWireExtractResult(
            deltas = slice.mapNotNull { it },
            totalRecordCount = total,
        )
    }

    fun parseTurnDelta(record: JSONObject): KimiWireUsageDelta? {
        if (record.optString("type") != "usage.record") return null
        if (!record.optString("usageScope").equals("turn", ignoreCase = true)) return null
        val usage = record.optJSONObject("usage") ?: return null
        val input = usageNonNegative(usage, "inputOther", "input_other")
        val output = usageNonNegative(usage, "output")
        val cacheRead = usageNonNegative(usage, "inputCacheRead", "input_cache_read")
        val cacheCreate = usageNonNegative(usage, "inputCacheCreation", "input_cache_creation")
        // Only a cache *read* is a hit. Writing the cache costs full price - on some providers more
        // than full price - and counting it as cached made the hit rate report the opposite of the
        // truth: a first request, which by definition hits nothing, writes the whole prompt to cache
        // and so reported ~100%. Creation is charged like fresh input, so it is counted as input.
        val cached = cacheRead
        val total = input + cacheCreate + output + cached
        if (input <= 0L && output <= 0L && cached <= 0L && cacheCreate <= 0L) return null
        val rawModel = record.optString("model").trim()
        return KimiWireUsageDelta(
            modelId = usageDisplayModelId(rawModel).ifBlank { rawModel },
            inputTokens = input + cacheCreate,
            outputTokens = output,
            cachedInputTokens = cached,
            totalTokens = total,
        )
    }

    private fun usageNonNegative(source: JSONObject, vararg keys: String): Long {
        keys.forEach { key ->
            if (source.has(key) && !source.isNull(key)) {
                val value = source.optLong(key)
                if (value > 0L) return value
            }
        }
        return 0L
    }
}

private val AetherSessionFolder = Regex(
    """^aether-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$""",
)

/** Display/group name: last path segment, never the aether-{uuid} session folder. */
fun usageDisplayModelId(raw: String): String {
    val trimmed = raw.trim().trimEnd('/')
    if (trimmed.isBlank()) return ""
    val slash = trimmed.lastIndexOf('/')
    if (slash >= 0) {
        val prefix = trimmed.substring(0, slash).trim()
        val suffix = trimmed.substring(slash + 1).trim()
        if (looksLikeAetherSessionFolder(prefix) && suffix.isNotBlank() && !looksLikeAetherSessionFolder(suffix)) {
            return suffix
        }
        if (suffix.isNotBlank() && !looksLikeAetherSessionFolder(suffix)) return suffix
    }
    return if (looksLikeAetherSessionFolder(trimmed)) "" else trimmed
}

private fun looksLikeAetherSessionFolder(value: String): Boolean =
    AetherSessionFolder.matches(value.trim())

fun storedUsageModelId(raw: String): String =
    usageDisplayModelId(raw).ifBlank { raw.trim() }

fun mergeTokensByDisplayModelId(
    items: List<Pair<String, Long>>,
    limit: Int = Int.MAX_VALUE,
): List<Pair<String, Long>> {
    if (items.isEmpty() || limit <= 0) return emptyList()
    return items
        .groupBy { storedUsageModelId(it.first).lowercase() }
        .map { (_, rows) ->
            storedUsageModelId(rows.first().first) to rows.sumOf { it.second }
        }
        .sortedByDescending { it.second }
        .take(limit)
}
