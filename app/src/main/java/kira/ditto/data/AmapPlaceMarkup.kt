package kira.ditto.data

internal const val AmapPlaceUrlScheme = "aether-amap:"
internal const val AmapPlaceMarkerPrefix = "[[amap:"
internal const val AmapPlaceCardsPrefix = "[[amap-cards:"
internal const val AmapPlaceCardClusterSize = 3

internal val AmapPlaceSearchTools = setOf(
    "maps_text_search",
    "maps_around_search",
    "maps_search_detail",
)

internal data class AmapPlaceMarkerMatch(
    val id: String,
    val endExclusive: Int,
)

internal fun amapPlaceUrl(id: String): String = "$AmapPlaceUrlScheme$id"

internal fun parseAmapPlaceUrl(url: String): String? {
    val trimmed = url.trim()
    if (!trimmed.startsWith(AmapPlaceUrlScheme, ignoreCase = true)) return null
    return trimmed.substring(AmapPlaceUrlScheme.length).trim().takeIf { it.isNotBlank() }
}

internal fun parseAmapPlaceMarker(text: String, startIndex: Int): AmapPlaceMarkerMatch? {
    if (startIndex !in text.indices) return null
    if (text.startsWith(AmapPlaceCardsPrefix, startIndex)) return null
    if (!text.startsWith(AmapPlaceMarkerPrefix, startIndex)) return null
    val close = text.indexOf("]]", startIndex + AmapPlaceMarkerPrefix.length)
    if (close <= startIndex + AmapPlaceMarkerPrefix.length) return null
    val id = text.substring(startIndex + AmapPlaceMarkerPrefix.length, close).trim()
    if (id.isBlank()) return null
    return AmapPlaceMarkerMatch(id = id, endExclusive = close + 2)
}

internal data class AmapPlaceCardsMatch(
    val startIndex: Int,
    val ids: List<String>,
)

internal fun parseAmapPlaceCardsMarker(text: String): AmapPlaceCardsMatch? {
    val trimmed = text.trim()
    if (!trimmed.startsWith(AmapPlaceCardsPrefix) || !trimmed.endsWith("]]")) return null
    val inner = trimmed.substring(AmapPlaceCardsPrefix.length, trimmed.length - 2)
    if (inner.isBlank()) return null
    val pipe = inner.indexOf('|')
    val startIndex: Int
    val idsPart: String
    if (pipe >= 0) {
        startIndex = inner.substring(0, pipe).toIntOrNull() ?: 1
        idsPart = inner.substring(pipe + 1)
    } else {
        startIndex = 1
        idsPart = inner
    }
    val ids = idsPart.split(',').map { it.trim() }.filter { it.isNotBlank() }
    if (ids.isEmpty()) return null
    return AmapPlaceCardsMatch(startIndex = startIndex.coerceAtLeast(1), ids = ids)
}

internal fun amapPlaceCardsToken(startIndex: Int, ids: List<String>): String =
    "$AmapPlaceCardsPrefix$startIndex|${ids.joinToString(",")}]]"

internal fun amapPlacesFromToolResults(results: List<Pair<String, String>>): List<AmapPlace> {
    if (results.isEmpty()) return emptyList()
    val out = linkedMapOf<String, AmapPlace>()
    results.forEach { (toolName, outputJson) ->
        val tool = AmapMcp.canonicalToolName(toolName)
        if (tool !in AmapPlaceSearchTools) return@forEach
        AmapPlaces.placesFromToolJson(outputJson).forEach { place ->
            out.putIfAbsent(place.id, place)
        }
    }
    return out.values.toList()
}

internal fun AmapPlace.markerToken(): String = "$AmapPlaceMarkerPrefix$id]]"

internal fun AmapPlace.metaBits(): List<String> = buildList {
    if (rating.isNotBlank()) add("评分 $rating")
    if (cost.isNotBlank()) add("人均 $cost")
    if (dishes.isNotBlank()) add("招牌 $dishes")
    if (distance.isNotBlank()) add(distance)
}

internal data class AmapPlaceAnswer(
    val markdown: String,
    val cards: List<AmapPlace>,
)

internal fun injectAmapPlaceMarkup(markdown: String, places: List<AmapPlace>): String =
    prepareAmapPlaceAnswer(markdown, places).markdown

internal fun prepareAmapPlaceAnswer(
    markdown: String,
    places: List<AmapPlace>,
    streaming: Boolean = false,
): AmapPlaceAnswer {
    if (markdown.isBlank() || places.isEmpty()) return AmapPlaceAnswer(markdown, emptyList())
    if (markdown.contains(AmapPlaceCardsPrefix)) {
        return AmapPlaceAnswer(markdown, cardsFromExistingMarkers(markdown, places))
    }
    val candidates = places
        .filter { it.name.trim().length >= 2 }
        .sortedByDescending { it.name.length }
    if (candidates.isEmpty()) return AmapPlaceAnswer(markdown, emptyList())
    val claimed = linkedMapOf<String, AmapPlace>()
    val pending = ArrayList<AmapPlace>()
    val emitted = ArrayList<AmapPlace>()
    val lines = markdown.split('\n')
    val out = ArrayList<String>(lines.size + 8)
    fun claimNew(text: String): List<AmapPlace> {
        val found = ArrayList<AmapPlace>()
        for (place in candidates) {
            if (place.id in claimed) continue
            if (text.contains(place.markerToken()) || indexOfPlaceName(text, place.name) >= 0) {
                claimed[place.id] = place
                found += place
            }
        }
        return found
    }
    fun enqueue(found: List<AmapPlace>, fromList: Boolean) {
        pending += found
        if (fromList && streaming) {
            while (pending.size >= AmapPlaceCardClusterSize) {
                flushPending(out, pending, emitted, AmapPlaceCardClusterSize)
            }
        }
    }
    fun flushSection() {
        if (pending.isEmpty()) return
        if (streaming) {
            while (pending.size >= AmapPlaceCardClusterSize) {
                flushPending(out, pending, emitted, AmapPlaceCardClusterSize)
            }
            return
        }
        flushPending(out, pending, emitted)
    }
    var inFence = false
    var index = 0
    var lastWasPlaceList = false
    fun skipMetaFollow(after: List<AmapPlace>) {
        while (index < lines.size) {
            val follow = lines[index].trim()
            if (follow.isBlank()) break
            if (!looksLikePlaceMetaLine(follow, after.ifEmpty { claimed.values })) break
            index++
        }
    }
    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trimStart()
        if (trimmed.startsWith("```")) {
            if (lastWasPlaceList) flushSection()
            lastWasPlaceList = false
            inFence = !inFence
            out += line
            index++
            continue
        }
        if (inFence) {
            out += line
            index++
            continue
        }
        if (trimmed.isBlank()) {
            val remainingIsBlank = lines.drop(index + 1).all { it.isBlank() }
            if (pending.isNotEmpty()) {
                val splitIntroAndList = !streaming && !lastWasPlaceList && !remainingIsBlank
                if (!splitIntroAndList && (!streaming || !remainingIsBlank || pending.size >= AmapPlaceCardClusterSize)) {
                    flushSection()
                }
            }
            lastWasPlaceList = false
            out += line
            index++
            continue
        }
        if (trimmed.startsWith("![")) {
            val photoPlace = candidates.firstOrNull { place ->
                trimmed.contains("![${place.name}]") || place.photos.any { url -> trimmed.contains(url) }
            }
            if (photoPlace != null) {
                if (photoPlace.id !in claimed) {
                    claimed[photoPlace.id] = photoPlace
                    enqueue(listOf(photoPlace), fromList = lastWasPlaceList)
                }
                index++
                continue
            }
            out += line
            index++
            continue
        }
        val table = takeMarkdownTableLines(lines, index)
        if (table != null) {
            val tablePlaces = placesInTable(table, candidates).filter { it.id !in claimed }
            if (tablePlaces.isNotEmpty()) {
                tablePlaces.forEach { place -> claimed[place.id] = place }
                enqueue(tablePlaces, fromList = false)
                flushPending(out, pending, emitted)
                index += table.size
                lastWasPlaceList = false
                continue
            }
            out += table
            index += table.size
            continue
        }
        if (looksLikeListLine(line)) {
            val found = claimNew(line)
            out += line
            if (found.isNotEmpty()) {
                enqueue(found, fromList = true)
                lastWasPlaceList = true
                index++
                skipMetaFollow(found)
                continue
            }
            lastWasPlaceList = false
            index++
            continue
        }
        if (looksLikePlaceMetaLine(trimmed, claimed.values)) {
            index++
            continue
        }
        lastWasPlaceList = false
        var rewritten = line
        val paragraphFound = ArrayList<AmapPlace>()
        for (place in candidates) {
            val marker = place.markerToken()
            if (rewritten.contains(marker)) {
                if (place.id !in claimed) {
                    claimed[place.id] = place
                    paragraphFound += place
                }
                continue
            }
            val at = indexOfPlaceName(rewritten, place.name)
            if (at < 0) continue
            rewritten = rewritten.substring(0, at) + marker + rewritten.substring(at + place.name.length)
            if (place.id !in claimed) {
                claimed[place.id] = place
                paragraphFound += place
            }
        }
        out += rewritten
        enqueue(paragraphFound, fromList = false)
        index++
    }
    if (pending.isNotEmpty()) {
        if (!streaming) {
            flushPending(out, pending, emitted)
        } else {
            while (pending.size >= AmapPlaceCardClusterSize) {
                flushPending(out, pending, emitted, AmapPlaceCardClusterSize)
            }
        }
    }
    return AmapPlaceAnswer(
        markdown = out.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trimEnd(),
        cards = emitted,
    )
}

private fun flushPending(
    out: MutableList<String>,
    pending: MutableList<AmapPlace>,
    emitted: MutableList<AmapPlace>,
    limit: Int = pending.size,
) {
    var left = limit.coerceAtMost(pending.size)
    while (left > 0 && pending.isNotEmpty()) {
        val takeCount = minOf(AmapPlaceCardClusterSize, left, pending.size)
        val take = pending.take(takeCount)
        repeat(takeCount) { pending.removeAt(0) }
        out += amapPlaceCardsToken(emitted.size + 1, take.map { it.id })
        emitted += take
        left -= takeCount
    }
}

private fun cardsFromExistingMarkers(markdown: String, places: List<AmapPlace>): List<AmapPlace> {
    val byId = places.associateBy { it.id }
    val cards = ArrayList<AmapPlace>()
    markdown.lineSequence().forEach { line ->
        parseAmapPlaceCardsMarker(line)?.ids?.forEach { id ->
            byId[id]?.let { cards += it }
        }
    }
    return cards
}

private fun looksLikePlaceMetaLine(text: String, places: Collection<AmapPlace>): Boolean {
    val trimmed = text.trim()
    if (trimmed.isEmpty() || looksLikeListLine(trimmed)) return false
    if (trimmed.startsWith("![")) {
        return places.any { place ->
            trimmed.contains(place.name) || place.photos.any { url -> trimmed.contains(url) }
        }
    }
    if (listOf("评分", "人均", "招牌", "距离", "地址").any { prefix -> trimmed.startsWith(prefix) }) {
        return true
    }
    return places.any { place ->
        (place.address.isNotBlank() && (trimmed == place.address || trimmed.startsWith(place.address))) ||
            (place.rating.isNotBlank() && trimmed.contains(place.rating) && trimmed.length <= 24) ||
            (place.cost.isNotBlank() && (trimmed == place.cost || trimmed == "人均 ${place.cost}")) ||
            (place.distance.isNotBlank() && trimmed == place.distance) ||
            (place.dishes.isNotBlank() && (trimmed == place.dishes || trimmed == "招牌 ${place.dishes}"))
    }
}

private fun looksLikeListLine(line: String): Boolean {
    val trimmed = line.trimStart()
    return trimmed.startsWith("- ") ||
        trimmed.startsWith("* ") ||
        trimmed.startsWith("+ ") ||
        trimmed.matches(Regex("^\\d+[.)]\\s+.*"))
}

internal fun takeMarkdownTableLines(lines: List<String>, start: Int): List<String>? {
    if (start !in lines.indices || !isPipeTableRow(lines[start])) return null
    val block = ArrayList<String>()
    var index = start
    while (index < lines.size && isPipeTableRow(lines[index])) {
        block += lines[index]
        index++
    }
    return block.takeIf { it.size >= 2 }
}

internal fun placesInTable(
    tableLines: List<String>,
    candidates: List<AmapPlace>,
): List<AmapPlace> {
    val rows = tableLines.map(::splitMarkdownTableRow).filter { cells ->
        cells.isNotEmpty() && !isTableSeparatorCells(cells) && cells.any { it.isNotBlank() }
    }
    val dataRows = if (rows.isNotEmpty() && looksLikePlaceTableHeader(rows.first(), candidates)) {
        rows.drop(1)
    } else {
        rows
    }
    if (dataRows.isEmpty()) return emptyList()
    val found = linkedMapOf<String, AmapPlace>()
    dataRows.forEach { row ->
        val haystack = row.joinToString(" ")
        val place = candidates.firstOrNull { candidate ->
            candidate.id !in found && haystack.contains(candidate.name)
        } ?: return@forEach
        found[place.id] = place
    }
    return found.values.toList()
}

private fun isPipeTableRow(line: String): Boolean {
    val trimmed = line.trim()
    return trimmed.startsWith("|") && trimmed.count { it == '|' } >= 2
}

private fun splitMarkdownTableRow(line: String): List<String> {
    val trimmed = line.trim()
    if (!trimmed.contains('|')) return emptyList()
    val inner = trimmed.trim('|')
    return inner.split('|').map { it.trim() }
}

private fun isTableSeparatorCells(cells: List<String>): Boolean =
    cells.isNotEmpty() && cells.all { cell ->
        val compact = cell.replace(":", "").replace(" ", "")
        compact.isNotEmpty() && compact.all { it == '-' }
    }

private fun looksLikePlaceTableHeader(cells: List<String>, candidates: List<AmapPlace>): Boolean {
    val haystack = cells.joinToString(" ")
    if (candidates.any { haystack.contains(it.name) }) return false
    val hints = listOf(
        "店名", "名称", "地点", "店铺", "商家", "门店",
        "评分", "地址", "距离", "人均", "招牌", "推荐",
        "name", "rating", "address", "distance",
    )
    return hints.any { hint -> haystack.contains(hint, ignoreCase = true) }
}

private fun indexOfPlaceName(text: String, name: String): Int {
    var from = 0
    while (from <= text.length - name.length) {
        val at = text.indexOf(name, from)
        if (at < 0) return -1
        val markerStart = text.lastIndexOf(AmapPlaceMarkerPrefix, at)
        val markerEnd = if (markerStart >= 0) text.indexOf("]]", markerStart) else -1
        val insideMarker = markerStart >= 0 && markerEnd > at
        val insideImage = text.lastIndexOf("](", at).let { link ->
            link >= 0 && text.lastIndexOf("![", link) >= 0 && text.indexOf(')', link) > at
        }
        if (!insideMarker && !insideImage) return at
        from = at + name.length
    }
    return -1
}
