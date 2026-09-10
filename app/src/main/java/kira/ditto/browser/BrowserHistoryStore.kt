package kira.ditto.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class BrowserVisit(
    val url: String,
    val title: String,
    val at: Long,
    val visits: Int = 1,
)

class BrowserHistoryStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "browser/history.json")
    private val lock = Any()

    fun record(url: String, title: String = "") {
        val normalized = url.trim()
        if (normalized.isBlank() || normalized.startsWith("about:")) return
        if (isNonContentBrowserHost(normalized)) return
        synchronized(lock) {
            val items = parse(readRaw()).toMutableList()
            val index = items.indexOfFirst { it.url == normalized }
            val now = System.currentTimeMillis()
            if (index >= 0) {
                val previous = items.removeAt(index)
                items.add(
                    0,
                    previous.copy(
                        title = title.ifBlank { previous.title },
                        at = now,
                        visits = previous.visits + 1,
                    ),
                )
            } else {
                items.add(0, BrowserVisit(url = normalized, title = title, at = now, visits = 1))
            }
            write(items.take(MaxEntries))
        }
    }

    fun updateTitle(url: String, title: String) {
        val normalized = url.trim()
        val trimmedTitle = title.trim()
        if (normalized.isBlank() || trimmedTitle.isBlank()) return
        synchronized(lock) {
            val items = parse(readRaw()).toMutableList()
            val index = items.indexOfFirst { it.url == normalized }
            if (index < 0) return
            val previous = items[index]
            if (previous.title == trimmedTitle) return
            items[index] = previous.copy(title = trimmedTitle)
            write(items)
        }
    }

    fun search(query: String, limit: Int = 20): List<BrowserVisit> = synchronized(lock) {
        rankHistory(parse(readRaw()), query).take(limit.coerceIn(1, 50))
    }

    fun recent(limit: Int = 20): List<BrowserVisit> = synchronized(lock) {
        parse(readRaw()).take(limit.coerceIn(1, 50))
    }

    fun clear() {
        synchronized(lock) {
            if (file.exists()) file.delete()
        }
    }

    fun toJson(items: List<BrowserVisit>): JSONArray {
        val array = JSONArray()
        items.forEach { visit ->
            array.put(
                JSONObject()
                    .put("url", visit.url)
                    .put("title", visit.title)
                    .put("at", visit.at)
                    .put("visits", visit.visits),
            )
        }
        return array
    }

    private fun readRaw(): String {
        if (!file.isFile) return "[]"
        return runCatching { file.readText() }.getOrDefault("[]")
    }

    private fun write(items: List<BrowserVisit>) {
        file.parentFile?.mkdirs()
        file.writeText(toJson(items).toString())
    }

    private fun parse(raw: String): List<BrowserVisit> {
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    BrowserVisit(
                        url = item.optString("url"),
                        title = item.optString("title"),
                        at = item.optLong("at"),
                        visits = item.optInt("visits", 1).coerceAtLeast(1),
                    ),
                )
            }
        }
    }

    companion object {
        const val MaxEntries = 400
    }
}

fun rankHistory(items: List<BrowserVisit>, query: String): List<BrowserVisit> {
    val needle = query.trim().lowercase()
    if (needle.isBlank()) return items
    return items.mapNotNull { visit ->
        val url = visit.url.lowercase()
        val title = visit.title.lowercase()
        val score = when {
            title == needle || url == needle -> 100
            title.startsWith(needle) -> 80
            url.contains(needle) && title.contains(needle) -> 70
            title.contains(needle) -> 60
            url.contains(needle) -> 40
            else -> 0
        }
        if (score == 0) null else score to visit
    }.sortedWith(compareByDescending<Pair<Int, BrowserVisit>> { it.first }.thenByDescending { it.second.at })
        .map { it.second }
}
