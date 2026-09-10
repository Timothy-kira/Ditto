package kira.ditto.runtime

import kira.ditto.browser.BrowserDesk
import kira.ditto.browser.BrowserSearchDeepBudget
import kira.ditto.browser.BrowserSearchSoftBudget
import kira.ditto.browser.browserFetchFailedMarkdown
import kira.ditto.browser.isNonContentBrowserHost
import kira.ditto.browser.isPlaceholderExampleHost
import kira.ditto.browser.isSearchEngineRedirectHop
import kira.ditto.browser.looksLikeDomainLabel
import kira.ditto.browser.WebSearchAdmit
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.BindException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

internal const val DittoWebSearchPort = 18791
internal const val DittoWebSearchPath = "/search"
internal const val DittoWebFetchPath = "/fetch"
internal const val DittoWebSearchUrl = "http://127.0.0.1:$DittoWebSearchPort$DittoWebSearchPath"
internal const val DittoWebFetchUrl = "http://127.0.0.1:$DittoWebSearchPort$DittoWebFetchPath"
internal const val DittoWebSearchApiKey = "ditto-local"
private const val MaxHttpBodyBytes = 1_048_576
private const val ClientSoTimeoutMillis = 120_000

data class DittoWebSearchHit(
    val title: String,
    val url: String,
    val snippet: String,
    val siteName: String = "",
    val content: String = "",
)

object DittoWebSearchMapper {
    fun queryFromRequest(body: JSONObject): String =
        body.optString("text_query").ifBlank { body.optString("query") }.trim()

    fun moonshotResponse(
        hits: List<DittoWebSearchHit>,
        alreadyRead: (String) -> Boolean = { false },
    ): JSONObject =
        JSONObject().put(
            "search_results",
            JSONArray().apply {
                hits.forEach { hit ->
                    val snippet = if (alreadyRead(hit.url) && !hit.snippet.startsWith("[already read]")) {
                        "[already read] ${hit.snippet}".trim()
                    } else {
                        hit.snippet
                    }
                    put(
                        JSONObject()
                            .put("title", hit.title)
                            .put("url", hit.url)
                            .put("snippet", snippet)
                            .put("site_name", hit.siteName)
                            .put("content", hit.content)
                            .put("date", "")
                            .put("icon", "")
                            .put("mime", ""),
                    )
                }
            },
        )

    fun limitFromRequest(body: JSONObject): Int =
        body.optInt("limit", 5).coerceIn(1, 12)

    fun fetchUrlFromRequest(body: JSONObject): String =
        body.optString("url").ifBlank { body.optString("uri") }.trim()

    fun fromSnapshotTree(tree: String, limit: Int = 8): List<DittoWebSearchHit> {
        if (tree.isBlank()) return emptyList()
        val hits = LinkedHashMap<String, DittoWebSearchHit>()
        val urlPattern = Regex("""https?://[^\s\"]+""")
        val titlePattern = Regex("\"([^\"]+)\"")
        val citeHostPattern = Regex(
            """(?:cite\s+)?((?:www\.)?[a-z0-9.-]+\.[a-z]{2,}(?:/[^\s\"]*)?)""",
            RegexOption.IGNORE_CASE,
        )
        tree.lineSequence().forEach { line ->
            val urls = urlPattern.findAll(line).map { match -> unwrapRedirect(match.value) }.toList()
                .ifEmpty {
                    val cite = citeHostPattern.find(line)?.groupValues?.getOrNull(1).orEmpty()
                    if (cite.contains('.') && !cite.contains(' ')) {
                        listOf(unwrapRedirect("https://$cite"))
                    } else {
                        emptyList()
                    }
                }
            urls.forEach { url ->
                if (!isHttpUrl(url) || isDroppedSearchUrl(url) || hits.containsKey(url)) return@forEach
                val title = titlePattern.find(line)?.groupValues?.getOrNull(1)
                    ?.trim()
                    .orEmpty()
                    .ifBlank { hostOf(url) }
                if (title.length < 2) return@forEach
                hits[url] = DittoWebSearchHit(
                    title = title.take(180),
                    url = url,
                    snippet = line.replace(Regex("""@e\d+"""), "").replace("\\s+".toRegex(), " ").trim().take(280),
                    siteName = hostOf(url),
                )
            }
        }
        return hits.values.take(limit)
    }

    fun fromHarvest(hits: JSONArray?, limit: Int = 8): List<DittoWebSearchHit> {
        if (hits == null || hits.length() == 0) return emptyList()
        val resolved = LinkedHashMap<String, DittoWebSearchHit>()
        for (index in 0 until hits.length()) {
            val item = hits.optJSONObject(index) ?: continue
            val url = unwrapRedirect(item.optString("url").ifBlank { item.optString("href") })
            if (!isHttpUrl(url) || isDroppedSearchUrl(url) || resolved.containsKey(url)) continue
            val title = item.optString("title").trim()
            if (title.length < 2 || looksLikeDomainLabel(title, url)) continue
            val snippet = item.optString("snippet").ifBlank { item.optString("text") }.trim()
                .let { text ->
                    if (text.isBlank() || text.equals(title, ignoreCase = true) || looksLikeDomainLabel(text, url)) {
                        ""
                    } else {
                        text.take(280)
                    }
                }
            resolved[url] = DittoWebSearchHit(
                title = title.take(180),
                url = url,
                snippet = snippet,
                siteName = hostOf(url),
            )
            if (resolved.size >= limit) break
        }
        return resolved.values.toList()
    }

    fun fromTavily(payload: JSONObject): List<DittoWebSearchHit> {
        val results = payload.optJSONArray("results") ?: return emptyList()
        return buildList {
            for (index in 0 until results.length()) {
                val item = results.optJSONObject(index) ?: continue
                val url = item.optString("url").trim()
                if (url.isBlank() || !isHttpUrl(url) || isDroppedSearchUrl(url)) continue
                add(
                    DittoWebSearchHit(
                        title = item.optString("title").ifBlank { url },
                        url = url,
                        snippet = item.optString("content").ifBlank {
                            item.optString("snippet")
                        },
                        siteName = hostOf(url),
                    ),
                )
            }
        }
    }

    fun fromHtml(html: String): List<DittoWebSearchHit> {
        val hits = LinkedHashMap<String, DittoWebSearchHit>()
        val hrefRegex = Regex(
            """href\s*=\s*["']([^"']+)["'][^>]*>([\s\S]*?)</a>""",
            RegexOption.IGNORE_CASE,
        )
        hrefRegex.findAll(html).forEach { match ->
            val rawHref = decodeHtml(match.groupValues[1].trim())
            val url = unwrapRedirect(rawHref)
            if (!isHttpUrl(url) || isDroppedSearchUrl(url) || hits.containsKey(url)) return@forEach
            val title = decodeHtml(stripTags(match.groupValues[2])).trim()
                .replace(Regex("\\s+"), " ")
            if (title.length < 2) return@forEach
            hits[url] = DittoWebSearchHit(
                title = title.take(180),
                url = url,
                snippet = nearbySnippet(html, match.range.first).ifBlank { title },
                siteName = hostOf(url),
            )
        }
        return hits.values.take(8)
    }

    private fun nearbySnippet(html: String, start: Int): String {
        val window = html.substring(start, (start + 900).coerceAtMost(html.length))
        val snippet = stripTags(window)
            .replace(Regex("\\s+"), " ")
            .trim()
        return snippet.take(280)
    }

    private fun unwrapRedirect(rawHref: String): String {
        val decoded = rawHref.replace("&amp;", "&")
        val uri = runCatching { java.net.URI(decoded) }.getOrNull()
        val query = uri?.rawQuery.orEmpty()
        if (query.isNotBlank()) {
            query.split("&").forEach { pair ->
                val eq = pair.indexOf('=')
                if (eq <= 0) return@forEach
                val key = pair.substring(0, eq)
                if (key != "uddg" && key != "q" && key != "u" && key != "url") return@forEach
                val value = pair.substring(eq + 1)
                val unwrapped = runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }
                    .getOrDefault(value)
                if (isHttpUrl(unwrapped) && !isDroppedSearchUrl(unwrapped)) return unwrapped
                val fromBase64 = decodeBingBase64Url(unwrapped)
                if (isHttpUrl(fromBase64) && !isDroppedSearchUrl(fromBase64)) return fromBase64
            }
        }
        listOf("uddg=", "u=", "url=").forEach { key ->
            val marker = decoded.indexOf(key, ignoreCase = true)
            if (marker >= 0) {
                val value = decoded.substring(marker + key.length).substringBefore("&")
                val unwrapped = runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }
                    .getOrDefault(value)
                if (isHttpUrl(unwrapped) && !isDroppedSearchUrl(unwrapped)) return unwrapped
                val fromBase64 = decodeBingBase64Url(unwrapped)
                if (isHttpUrl(fromBase64) && !isDroppedSearchUrl(fromBase64)) return fromBase64
            }
        }
        return decoded
    }

    private fun decodeBingBase64Url(value: String): String {
        val payload = value.removePrefix("a1").removePrefix("a2")
        if (payload.length < 8) return ""
        val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
        val bytes = runCatching {
            java.util.Base64.getUrlDecoder().decode(padded)
        }.recoverCatching {
            java.util.Base64.getDecoder().decode(padded)
        }.getOrNull() ?: return ""
        return bytes.toString(Charsets.UTF_8).trim()
    }

    private fun isHttpUrl(value: String): Boolean =
        value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)

    private fun isDroppedSearchUrl(url: String): Boolean {
        if (isPlaceholderExampleHost(url)) return true
        if (isSearchEngineRedirectHop(url)) return false
        return isNonContentBrowserHost(url)
    }

    private fun hostOf(url: String): String =
        runCatching { URL(url).host.orEmpty().removePrefix("www.") }.getOrDefault("")

    fun hostLabel(url: String): String = hostOf(url)

    private fun stripTags(value: String): String =
        value.replace(Regex("<[^>]+>"), " ")

    private fun decodeHtml(value: String): String =
        value.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
}

class DittoWebSearchGateway {
    private val started = AtomicBoolean(false)
    private val workers = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "ditto-websearch-worker").apply { isDaemon = true }
    }
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    @Volatile
    private var searchExecutor: ((String, Int) -> List<DittoWebSearchHit>)? = null
    @Volatile
    private var fetchExecutor: ((String) -> String)? = null

    fun attachExecutors(
        search: (String, Int) -> List<DittoWebSearchHit>,
        fetch: (String) -> String,
    ) {
        searchExecutor = search
        fetchExecutor = fetch
    }

    fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        try {
            val socket = ServerSocket(DittoWebSearchPort, 32, InetAddress.getByName("127.0.0.1"))
            serverSocket = socket
            acceptThread = Thread(
                {
                    while (!socket.isClosed) {
                        val client = runCatching { socket.accept() }.getOrNull() ?: continue
                        client.soTimeout = ClientSoTimeoutMillis
                        workers.execute { handleClient(client) }
                    }
                },
                "ditto-websearch-accept",
            ).apply {
                isDaemon = true
                start()
            }
            verifyLoopbackHealth()
        } catch (_: BindException) {
            started.set(false)
        } catch (error: Throwable) {
            started.set(false)
            throw error
        }
    }

    fun stop() {
        started.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            val input = BufferedInputStream(client.getInputStream())
            val request = readHttpRequest(input) ?: run {
                writeHttp(client, 400, JSONObject().put("error", "invalid request").toString())
                return
            }
            if (request.method == "GET" && request.path.startsWith("/health")) {
                writeHttp(client, 200, JSONObject().put("ok", true).toString())
                return
            }
            if (request.method != "POST" ||
                (!request.path.startsWith(DittoWebSearchPath) && !request.path.startsWith(DittoWebFetchPath))
            ) {
                writeHttp(client, 404, JSONObject().put("error", "not found").toString())
                return
            }
            if (!request.hasValidLocalToken()) {
                writeHttp(client, 401, JSONObject().put("error", "unauthorized").toString())
                return
            }
            val body = runCatching { JSONObject(request.body.ifBlank { "{}" }) }.getOrElse {
                JSONObject()
            }
            if (request.path.startsWith(DittoWebFetchPath)) {
                val url = DittoWebSearchMapper.fetchUrlFromRequest(body)
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    writeHttp(client, 400, JSONObject().put("error", "url is required").toString())
                    return
                }
                if (isPlaceholderExampleHost(url)) {
                    writeHttp(
                        client,
                        200,
                        browserFetchFailedMarkdown(
                            url,
                            "placeholder",
                            "This is an IANA documentation placeholder, not a real source. Pick a different URL.",
                        ),
                        contentType = "text/markdown; charset=utf-8",
                    )
                    return
                }
                val markdown = dittoWebFetchBody(
                    url = url,
                    result = runCatching { fetchExecutor?.invoke(url).orEmpty() },
                )
                writeHttp(client, 200, markdown, contentType = "text/markdown; charset=utf-8")
                return
            }
            val query = kira.ditto.browser.humanizeBrowserSearchQuery(
                DittoWebSearchMapper.queryFromRequest(body),
            ).ifBlank { DittoWebSearchMapper.queryFromRequest(body) }
            if (query.isBlank()) {
                writeHttp(client, 400, JSONObject().put("error", "text_query is required").toString())
                return
            }
            when (val admit = BrowserDesk.admitWebSearchAfterWait(query)) {
                WebSearchAdmit.WaitForFirstHop -> {
                    writeHttp(
                        client,
                        200,
                        DittoWebSearchMapper.moonshotResponse(listOf(dittoSearchLoopHit())).toString(),
                    )
                    return
                }
                WebSearchAdmit.Similar -> {
                    writeHttp(
                        client,
                        200,
                        DittoWebSearchMapper.moonshotResponse(
                            listOf(dittoSearchSimilarHit(BrowserDesk.lastAdmittedQuery())),
                        ).toString(),
                    )
                    return
                }
                WebSearchAdmit.Budget -> {
                    writeHttp(
                        client,
                        200,
                        DittoWebSearchMapper.moonshotResponse(
                            listOf(dittoSearchBudgetHit(BrowserDesk.searchCount())),
                        ).toString(),
                    )
                    return
                }
                WebSearchAdmit.Allow -> Unit
            }
            val count = BrowserDesk.searchCount()
            val hits = dittoWebSearchHits(
                result = runCatching {
                    searchExecutor?.invoke(query, DittoWebSearchMapper.limitFromRequest(body)).orEmpty()
                },
                searchCount = count,
                budget = if (BrowserDesk.isDeepSearchThisTurn()) {
                    BrowserSearchDeepBudget
                } else {
                    BrowserSearchSoftBudget
                },
            )
            BrowserDesk.markWebSearchReturned()
            writeHttp(
                client,
                200,
                DittoWebSearchMapper.moonshotResponse(
                    hits,
                    alreadyRead = { url -> BrowserDesk.lookupRead(url) != null },
                ).toString(),
            )
        }
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val body: String,
        val headers: Map<String, String> = emptyMap(),
    ) {
        fun hasValidLocalToken(): Boolean {
            val authorization = headers["authorization"].orEmpty()
            val bearer = authorization.removePrefix("Bearer ").trim()
            val apiKey = headers["x-api-key"].orEmpty().ifBlank {
                headers["api-key"].orEmpty()
            }
            val presented = bearer.ifBlank { apiKey }
            if (presented.isBlank()) return true
            return presented == DittoWebSearchApiKey
        }
    }

    private fun readHttpRequest(input: BufferedInputStream): HttpRequest? {
        val headerBytes = ByteArray(8 * 1024)
        val header = StringBuilder()
        var headerEnd = -1
        var total = 0
        while (total < headerBytes.size) {
            val byte = input.read()
            if (byte < 0) break
            headerBytes[total] = byte.toByte()
            total += 1
            if (total >= 4 &&
                headerBytes[total - 4] == '\r'.code.toByte() &&
                headerBytes[total - 3] == '\n'.code.toByte() &&
                headerBytes[total - 2] == '\r'.code.toByte() &&
                headerBytes[total - 1] == '\n'.code.toByte()
            ) {
                headerEnd = total
                break
            }
        }
        if (headerEnd < 0) return null
        header.append(String(headerBytes, 0, headerEnd, Charsets.US_ASCII))
        val lines = header.toString().split("\r\n")
        val requestLine = lines.firstOrNull()?.split(' ') ?: return null
        if (requestLine.size < 2) return null
        val headers = lines.drop(1).filter { it.contains(':') }.associate { line ->
            val name = line.substringBefore(':').trim().lowercase()
            val value = line.substringAfter(':').trim()
            name to value
        }
        val contentLength = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        if (contentLength > MaxHttpBodyBytes) return null
        val bodyBytes = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(bodyBytes, read, contentLength - read)
            if (n < 0) break
            read += n
        }
        return HttpRequest(
            method = requestLine[0].uppercase(),
            path = requestLine[1],
            body = String(bodyBytes, 0, read, Charsets.UTF_8),
            headers = headers,
        )
    }

    private fun verifyLoopbackHealth() {
        runCatching {
            val connection = URL("http://127.0.0.1:$DittoWebSearchPort/health").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 800
                connection.readTimeout = 800
                connection.requestMethod = "GET"
                connection.responseCode
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun writeHttp(
        socket: Socket,
        status: Int,
        body: String,
        contentType: String = "application/json; charset=utf-8",
    ) {
        val payload = body.toByteArray(Charsets.UTF_8)
        val reason = if (status == 200) "OK" else "Error"
        val header = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${payload.size}\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.US_ASCII)
        BufferedOutputStream(socket.getOutputStream()).use { output ->
            output.write(header)
            output.write(payload)
            output.flush()
        }
    }
}

internal fun dittoWebFetchBody(url: String, result: Result<String>): String {
    val markdown = result.getOrElse { error ->
        browserFetchFailedMarkdown(url, "error", error.message ?: "fetch failed")
    }
    if (markdown.isBlank()) {
        return browserFetchFailedMarkdown(url, "empty", "page had no readable text")
    }
    return markdown
}

internal fun dittoSearchBudgetHit(searchCount: Int): DittoWebSearchHit =
    DittoWebSearchHit(
        title = "Search budget",
        url = "about:search-budget",
        snippet = "STOP. This turn already ran $searchCount searches. Do not search again. " +
            "Answer now with the sources you already have.",
        siteName = "aether",
    )

internal fun dittoSearchLoopHit(): DittoWebSearchHit =
    DittoWebSearchHit(
        title = "Lookup loop",
        url = "about:lookup-loop",
        snippet = "Lookup loop: wait for the first query to finish. If that report is insufficient, " +
            "expand once with AgentSwarm subagent_type=\"browser\", at most 3 items that are " +
            "distinct angles (official source, price, hours, reviews) — not paraphrases. " +
            "Do not fire parallel WebSearch on the first hop.",
        siteName = "aether",
    )

internal fun dittoSearchSimilarHit(existingQuery: String): DittoWebSearchHit =
    DittoWebSearchHit(
        title = "Lookup angle",
        url = "about:lookup-similar",
        snippet = "This query is too close to \"${existingQuery.ifBlank { "the previous search" }}\". " +
            "Pick a different angle (official source, price, opening hours, reviews), " +
            "not a paraphrase of the same keywords.",
        siteName = "aether",
    )

internal fun dittoWebSearchHits(
    result: Result<List<DittoWebSearchHit>>,
    searchCount: Int,
    budget: Int = BrowserSearchSoftBudget,
): List<DittoWebSearchHit> {
    if (searchCount > budget) {
        return listOf(dittoSearchBudgetHit(searchCount))
    }
    val hits = result.getOrElse { error ->
        listOf(
            DittoWebSearchHit(
                title = "Search failed",
                url = "about:search-failed",
                snippet = error.message ?: "search failed",
                siteName = "aether",
            ),
        )
    }
    if (searchCount < budget) return hits
    return hits + dittoSearchBudgetHit(searchCount)
}
