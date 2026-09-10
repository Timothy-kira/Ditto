package kira.ditto.browser

import android.content.Context
import kira.ditto.data.BrowserPreferences
import kira.ditto.runtime.DittoWebSearchHit
import kira.ditto.runtime.DittoWebSearchMapper
import org.json.JSONObject

internal object GeckoKimiWeb {
    fun search(
        context: Context,
        prefs: BrowserPreferences,
        vault: BrowserLoginVault,
        history: BrowserHistoryStore,
        query: String,
        limit: Int,
    ): List<DittoWebSearchHit> {
        var lastError: Throwable? = null
        repeat(2) {
            try {
                return searchOnce(context, prefs, vault, history, query, limit)
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("search failed")
    }

    private fun searchOnce(
        context: Context,
        prefs: BrowserPreferences,
        vault: BrowserLoginVault,
        history: BrowserHistoryStore,
        query: String,
        limit: Int,
    ): List<DittoWebSearchHit> {
        val navigated = call(
            context,
            prefs,
            vault,
            history,
            "tabs_navigate",
            JSONObject()
                .put("url", query)
                .put("query", query)
                .put("topic_id", query),
        )
        requireOk(navigated, "navigate failed")
        if (isSeedBrowserUrl(navigated.optString("url")) ||
            isMissingBrowserPage(
                navigated.optString("url"),
                navigated.optString("title"),
                navigated.optString("tree"),
                requestedUrl = navigated.optString("url"),
            )
        ) {
            return emptyList()
        }
        val harvestedHits = DittoWebSearchMapper.fromHarvest(navigated.optJSONArray("hits"), limit)
        val harvested = if (harvestedHits.isEmpty()) {
            harvestUntil(context, prefs, vault, history, kind = "links", limit = limit.coerceIn(4, 12))
        } else {
            JSONObject().put("hits", navigated.optJSONArray("hits")).put("url", navigated.optString("url"))
        }
        val hits = harvestedHits.ifEmpty {
            DittoWebSearchMapper.fromHarvest(harvested.optJSONArray("hits"), limit)
        }.ifEmpty {
            DittoWebSearchMapper.fromSnapshotTree(
                tree = navigated.optString("tree"),
                limit = limit,
            )
        }
        val url = harvested.optString("url").ifBlank { navigated.optString("url") }
        val resolved = if (hits.isNotEmpty()) {
            hits
        } else {
            val snap = call(
                context,
                prefs,
                vault,
                history,
                "page_snapshot",
                JSONObject().put("limit", 80).put("region", "page"),
            )
            DittoWebSearchMapper.fromSnapshotTree(
                tree = snap.optString("tree"),
                limit = limit,
            )
        }
        BrowserDesk.recordSearch(
            query = query,
            url = url,
            hits = resolved.map { hit ->
                BrowserDeskHit(
                    title = hit.title,
                    url = hit.url,
                    snippet = hit.snippet,
                )
            },
        )
        val articleUrls = resolved
            .map { it.url }
            .filter(::isPrefetchableBrowserUrl)
            .take(3)
        BrowserPageReader.prefetch(
            context = context,
            prefs = prefs,
            vault = vault,
            history = history,
            urls = articleUrls,
            query = query,
        )
        val withContent = resolved.map { hit ->
            val snippet = BrowserResearchGraph.passagesSnippet(hit.url, query)
            if (snippet.isBlank()) hit else hit.copy(content = snippet)
        }
        if (withContent.isNotEmpty()) return withContent
        val title = navigated.optString("title").ifBlank { query }
        if (!url.startsWith("http://") && !url.startsWith("https://")) return emptyList()
        if (isNonContentBrowserHost(url)) return emptyList()
        return listOf(
            DittoWebSearchHit(
                title = title,
                url = url,
                snippet = navigated.optString("tree").replace("\\s+".toRegex(), " ").trim().take(280),
                siteName = DittoWebSearchMapper.hostLabel(url),
            ),
        )
    }

    private fun harvestUntil(
        context: Context,
        prefs: BrowserPreferences,
        vault: BrowserLoginVault,
        history: BrowserHistoryStore,
        kind: String,
        limit: Int,
        timeoutMs: Long = 3_500L,
    ): JSONObject {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = JSONObject()
        while (true) {
            last = call(
                context,
                prefs,
                vault,
                history,
                "page_harvest",
                JSONObject().put("kind", kind).put("limit", limit),
            )
            val count = when (kind) {
                "images", "image" -> last.optJSONArray("images")?.length() ?: 0
                else -> last.optJSONArray("hits")?.length() ?: 0
            }
            if (last.optBoolean("ok", true) && count > 0) return last
            if (System.currentTimeMillis() >= deadline) return last
            Thread.sleep(250)
        }
    }

    private fun requireOk(json: JSONObject, fallback: String) {
        if (json.optBoolean("ok", true)) return
        val code = json.optString("code")
        error(
            code.takeIf { it == "not_found" }
                ?: json.optString("errmsg").ifBlank { code }.ifBlank { fallback },
        )
    }

    private fun call(
        context: Context,
        prefs: BrowserPreferences,
        vault: BrowserLoginVault,
        history: BrowserHistoryStore,
        tool: String,
        arguments: JSONObject,
    ): JSONObject {
        val raw = WebMcpHost.execute(
            context = context,
            prefs = prefs,
            vault = vault,
            history = history,
            name = tool,
            arguments = arguments,
        )
        val json = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        return json.optJSONObject("structuredContent") ?: json
    }
}
