package kira.ditto.browser

import kira.ditto.data.chunkPersonaKnowledgeText
import kotlin.math.ln

data class AgentPassage(
    val url: String = "",
    val title: String = "",
    val heading: String = "",
    val text: String,
    /**
     * Position of this passage inside its page, starting at 0.
     *
     * A citation has to be able to say *which part* of a page an answer leaned on, and until this
     * field existed a passage had no identity at all: [AgentIndex.chunkPage] computed the index and
     * threw it away, so [AgentIndex.rankTexts] had to smuggle the caller's index through [url].
     * -1 means the passage was built outside a page (a bare text ranked on its own).
     */
    val ordinal: Int = -1,
)

data class ScoredAgentPassage(
    val passage: AgentPassage,
    val score: Float,
)

/**
 * Outline / paragraph chunking plus BM25 over English whitespace tokens and CJK n-grams.
 * No cloud embeddings and no on-device vector model.
 */
object AgentIndex {
    private const val DefaultPassageChars = 600
    private const val Bm25K1 = 1.2f
    private const val Bm25B = 0.75f

    fun chunkPage(
        title: String,
        text: String,
        url: String = "",
        outline: String = "",
        passageChars: Int = DefaultPassageChars,
    ): List<AgentPassage> {
        val body = text.trim()
        if (body.isBlank() && title.isBlank()) return emptyList()
        val outlined = chunkFromOutline(outline, passageChars)
        val chunks = outlined.ifEmpty { chunkPersonaKnowledgeText(body.ifBlank { title }, passageChars) }
        return chunks.mapIndexed { index, chunk ->
            AgentPassage(
                url = url,
                title = title,
                heading = headingOf(chunk, title, index),
                text = chunk,
                ordinal = index,
            )
        }
    }

    fun rank(
        query: String,
        passages: List<AgentPassage>,
        minScore: Float = 0.15f,
        limit: Int = 8,
    ): List<ScoredAgentPassage> {
        if (query.isBlank() || passages.isEmpty()) return emptyList()
        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty()) return emptyList()
        val docs = passages.map { tokenize(it.text) }
        val scores = bm25(queryTokens, docs)
        val max = scores.maxOrNull() ?: return emptyList()
        if (max <= 0f) return emptyList()
        return passages.mapIndexed { index, passage ->
            ScoredAgentPassage(passage, scores[index] / max)
        }
            .filter { it.score >= minScore }
            .sortedByDescending { it.score }
            .take(limit)
    }

    fun rankTexts(
        query: String,
        texts: List<String>,
        minScore: Float = 0.15f,
        limit: Int = 6,
    ): List<Int> {
        val ranked = rank(
            query = query,
            passages = texts.mapIndexed { index, text ->
                AgentPassage(text = text, ordinal = index)
            },
            minScore = minScore,
            limit = limit,
        )
        return ranked.map { it.passage.ordinal }.filter { it >= 0 }
    }

    fun tokenize(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val tokens = ArrayList<String>()
        val latin = StringBuilder()
        val cjk = StringBuilder()
        fun flushLatin() {
            if (latin.isNotEmpty()) {
                tokens += latin.toString().lowercase()
                latin.clear()
            }
        }
        fun flushCjk() {
            if (cjk.isEmpty()) return
            val run = cjk.toString()
            run.forEach { char -> tokens += char.toString() }
            if (run.length >= 2) {
                for (index in 0 until run.length - 1) {
                    tokens += run.substring(index, index + 2)
                }
            }
            cjk.clear()
        }
        text.forEach { char ->
            when {
                char.isLatinTokenChar() -> {
                    flushCjk()
                    latin.append(char)
                }
                char.isCjkChar() -> {
                    flushLatin()
                    cjk.append(char)
                }
                else -> {
                    flushLatin()
                    flushCjk()
                }
            }
        }
        flushLatin()
        flushCjk()
        return tokens.filter { it.isNotBlank() }
    }

    internal fun contentHash(text: String): String {
        val normalized = text.trim().replace(Regex("\\s+"), " ")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { byte -> "%02x".format(byte) }
    }

    internal fun simhash(text: String): Long {
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return 0L
        val votes = IntArray(64)
        tokens.forEach { token ->
            var hash = token.hashCode().toLong()
            hash = hash xor (hash shl 13)
            hash = hash xor (hash ushr 7)
            hash = hash xor (hash shl 17)
            for (bit in 0 until 64) {
                if ((hash ushr bit) and 1L == 1L) votes[bit] += 1 else votes[bit] -= 1
            }
        }
        var result = 0L
        for (bit in 0 until 64) {
            if (votes[bit] >= 0) result = result or (1L shl bit)
        }
        return result
    }

    internal fun hamming(left: Long, right: Long): Int = java.lang.Long.bitCount(left xor right)

    private fun bm25(queryTokens: List<String>, docs: List<List<String>>): FloatArray {
        val n = docs.size
        if (n == 0) return floatArrayOf()
        val df = HashMap<String, Int>()
        docs.forEach { doc ->
            doc.toHashSet().forEach { token -> df[token] = (df[token] ?: 0) + 1 }
        }
        val avgdl = docs.map { it.size.toFloat() }.average().toFloat().coerceAtLeast(1f)
        val querySet = queryTokens.groupingBy { it }.eachCount()
        return FloatArray(n) { index ->
            val doc = docs[index]
            val tfMap = doc.groupingBy { it }.eachCount()
            val dl = doc.size.toFloat().coerceAtLeast(1f)
            var score = 0f
            querySet.forEach { (token, qf) ->
                val tf = tfMap[token] ?: return@forEach
                val documentFrequency = df[token] ?: 0
                val idf = ln(((n - documentFrequency + 0.5) / (documentFrequency + 0.5)) + 1.0).toFloat()
                val denom = tf + Bm25K1 * (1f - Bm25B + Bm25B * (dl / avgdl))
                score += qf * idf * ((tf * (Bm25K1 + 1f)) / denom)
            }
            score
        }
    }

    private fun chunkFromOutline(outline: String, passageChars: Int): List<String> {
        if (outline.isBlank()) return emptyList()
        val sections = ArrayList<String>()
        val buffer = StringBuilder()
        fun flush() {
            val chunk = buffer.toString().trim()
            if (chunk.isNotBlank()) sections += chunk
            buffer.clear()
        }
        outline.lineSequence().forEach { raw ->
            val line = raw.replace(Regex("""@e\d+"""), "").trim()
            if (line.isBlank()) return@forEach
            val heading = line.startsWith("#") || Regex("""^h[1-6]\b""", RegexOption.IGNORE_CASE).containsMatchIn(line)
            if (heading && buffer.isNotEmpty()) flush()
            if (buffer.isNotEmpty()) buffer.append('\n')
            buffer.append(line)
            if (buffer.length >= passageChars) flush()
        }
        flush()
        return sections.filter { it.length >= 8 }
    }

    private fun headingOf(chunk: String, title: String, index: Int): String {
        val first = chunk.lineSequence().firstOrNull().orEmpty().trim()
        if (first.startsWith("#")) return first.trimStart('#').trim()
        if (index == 0 && title.isNotBlank()) return title
        return first.take(48).ifBlank { "Passage ${index + 1}" }
    }
}

private fun Char.isLatinTokenChar(): Boolean =
    isLetterOrDigit() && this !in '\u3400'..'\u9FFF' && this !in '\uF900'..'\uFAFF'

private fun Char.isCjkChar(): Boolean =
    this in '\u3400'..'\u9FFF' || this in '\uF900'..'\uFAFF' || this in '\u3040'..'\u30FF'
