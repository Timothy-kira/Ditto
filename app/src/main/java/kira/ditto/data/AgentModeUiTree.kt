package kira.ditto.data

import java.util.Locale

data class AgentModeUiTarget(
    val label: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val bounds: String get() = "($left,$top)-($right,$bottom)"
    val tapLine: String get() = "$label tap $bounds"
}

/**
 * Compact accessibility dump for the phone operator. Native apps have no HTML
 * DOM; this is the closest structured tree (text, tap targets, 0..1000 bounds).
 */
internal object AgentModeUiTree {
    private val NodeTag = Regex("""<node\b([^>]*)/?>""", RegexOption.IGNORE_CASE)
    private val Attr = Regex("""([\w-]+)="([^"]*)"""")
    private val Bounds = Regex("""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""")
    private val CompactBounds = Regex("""\((\d+),(\d+)\)-\((\d+),(\d+)\)""")
    private val DecorativeClasses = setOf(
        "imageview", "image", "view", "framelayout", "linearlayout", "relativelayout",
        "constraintlayout", "viewgroup", "recyclerview", "nestedscrollview", "scrollview",
        "horizontalscrollview", "viewpager", "viewpager2", "toolbar", "appbarlayout",
        "surfaceview", "textureview", "space", "viewstub", "composeview", "androidview",
        "cardview", "coordinatorlayout", "drawerlayout", "swiperefreshlayout",
    )
    private val ArtIdHints = listOf(
        "banner", "cover", "artwork", "thumbnail", "hero", "poster", "watermark",
    )

    data class CompactNode(
        val text: String,
        val desc: String,
        val klass: String,
        val id: String,
        val clickable: Boolean,
        val checkable: Boolean,
        val editable: Boolean,
        val focused: Boolean,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val ownLabel: String get() = text.ifBlank { desc }
        val fallbackLabel: String get() = ownLabel.ifBlank { id }.ifBlank { klass.ifBlank { "view" } }
    }

    fun compactFromUiautomatorXml(
        xml: String,
        width: Int,
        height: Int,
        maxLines: Int = DefaultDumpLimit,
    ): String {
        if (xml.isBlank() || !xml.contains("<node", ignoreCase = true)) return ""
        return formatCompact(parseXmlNodes(xml, width, height), maxLines)
    }

    fun formatCompact(nodes: List<CompactNode>, maxLines: Int = DefaultDumpLimit): String {
        val lines = ArrayList<String>(maxLines.coerceAtLeast(1))
        for (node in nodes) {
            if (lines.size >= maxLines) break
            if (!shouldEmitCompact(node)) continue
            val flags = buildString {
                if (node.clickable || node.checkable) append(" tap")
                if (node.focused) append(" focus")
            }
            val idSuffix = if (node.id.isNotBlank()) " #${node.id}" else ""
            lines += "${node.fallbackLabel}$flags (${node.left},${node.top})-(${node.right},${node.bottom})$idSuffix"
        }
        return lines.joinToString("\n")
    }

    fun shouldEmitCompact(
        klass: String,
        text: String,
        desc: String,
        id: String,
        clickable: Boolean,
        focused: Boolean,
    ): Boolean = shouldEmitCompact(
        CompactNode(
            text = text,
            desc = desc,
            klass = klass,
            id = id,
            clickable = clickable,
            checkable = false,
            editable = false,
            focused = focused,
            left = 0,
            top = 0,
            right = 0,
            bottom = 0,
        ),
    )

    fun clickTargetsFromCompact(
        compact: String,
        maxTargets: Int = DefaultTargetLimit,
        simplify: Boolean = false,
    ): List<AgentModeUiTarget> {
        val parsed = compact.lineSequence().mapNotNull(::parseCompactLine).toList()
        if (parsed.isEmpty()) return emptyList()
        val out = ArrayList<AgentModeUiTarget>(maxTargets.coerceAtLeast(1))
        val seen = HashSet<String>()
        for (node in parsed) {
            if (out.size >= maxTargets) break
            if (!node.tap && !node.focus) continue
            val label = effectiveCompactLabel(node, parsed)
            if (label.isBlank()) continue
            if (simplify && (isDecorativeLabel(label) || isFullBleedArt(node, label))) continue
            val key = "$label:${node.left},${node.top},${node.right},${node.bottom}"
            if (!seen.add(key)) continue
            out += AgentModeUiTarget(
                label = label,
                left = node.left,
                top = node.top,
                right = node.right,
                bottom = node.bottom,
            )
        }
        return out
    }

    fun shouldSimplifyRich(node: RichNode): Boolean {
        val label = node.label.ifBlank { node.text }
        if (isDecorativeLabel(label)) return true
        val area = (node.right - node.left).coerceAtLeast(0) * (node.bottom - node.top).coerceAtLeast(0)
        val klass = node.klass.substringAfterLast('.').lowercase()
        return area >= 500_000 && (klass.contains("image") || isDecorativeLabel(label))
    }

    fun isCluttered(targetCount: Int): Boolean = targetCount >= ClutteredTargetCount

    fun clickTargetsText(
        compact: String,
        maxTargets: Int = DefaultTargetLimit,
        simplify: Boolean = false,
    ): String = clickTargetsFromCompact(compact, maxTargets, simplify).joinToString("\n") { it.tapLine }

    fun isSparse(compact: String): Boolean =
        compact.lineSequence().count { it.isNotBlank() } < 2

    /**
     * Geometry/structure signature for chrome, not pixels.
     * Images, video surfaces, GIF/Lottie, and unnamed artwork are omitted so a
     * live feed does not look like the layout is still moving. Child counts are
     * ignored for the same reason (RecyclerView recycling).
     */
    data class LayoutNode(
        val klass: String,
        val id: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val clickable: Boolean,
        val childCount: Int,
        val label: String = "",
    )

    fun layoutFingerprint(nodes: List<LayoutNode>, windowKey: Int = 0): Long {
        val kept = nodes.filter { !isIgnoredMedia(it.klass, it.id, it.clickable, it.label) }
        var hash = FnvOffset
        hash = mix(hash, windowKey)
        hash = mix(hash, kept.size)
        for (node in kept) {
            hash = mix(hash, node.klass)
            hash = mix(hash, node.id)
            hash = mix(hash, node.left)
            hash = mix(hash, node.top)
            hash = mix(hash, node.right)
            hash = mix(hash, node.bottom)
            hash = mix(hash, if (node.clickable) 1 else 0)
        }
        return hash
    }

    fun isIgnoredMediaNode(node: RichNode): Boolean =
        isIgnoredMedia(
            klass = node.klass,
            id = node.id,
            interactive = node.clickable || node.longClickable || node.checkable || node.editable,
            label = node.label.ifBlank { node.text }.ifBlank { node.desc },
        )

    fun isIgnoredMedia(
        klass: String,
        id: String,
        interactive: Boolean,
        label: String = "",
    ): Boolean {
        val k = klass.substringAfterLast('.').lowercase()
        if (
            k.contains("surface") ||
            k.contains("texture") ||
            k.contains("video") ||
            k.contains("lottie") ||
            k.contains("gif") ||
            k.contains("animation")
        ) {
            return true
        }
        if (!looksLikeMediaClass(klass) && !looksLikeMediaId(id) && !looksLikeMediaId(label)) {
            return false
        }
        val name = label.ifBlank { id }
        if (
            interactive &&
            name.isNotBlank() &&
            !looksLikeArtId(name) &&
            !looksLikeArtId(id) &&
            !looksLikeMediaId(name) &&
            !looksLikeMediaId(id)
        ) {
            return false
        }
        return true
    }

    fun isOverlayClass(klass: String): Boolean {
        val k = klass.substringAfterLast('.').lowercase()
        return k.contains("dialog") ||
            k.contains("popup") ||
            k.contains("toast") ||
            k.contains("bottomsheet") ||
            k.contains("modal") ||
            k.contains("float")
    }

    fun isOverlayWindowType(windowType: Int): Boolean {
        // AccessibilityWindowInfo: TYPE_APPLICATION=1, TYPE_INPUT_METHOD=2.
        return windowType > 0 && windowType != 1 && windowType != 2
    }

    fun isScrimHint(value: String): Boolean {
        val n = value.trim().lowercase()
        if (n.isBlank()) return false
        return n.contains("scrim") ||
            n.contains("mask") ||
            n.contains("dim") ||
            n.contains("overlay") ||
            n.contains("barrier") ||
            n.contains("interstitial") ||
            n.contains("close_ad") ||
            n.contains("ad_close")
    }

    fun shouldKeepForCheapDump(node: RichNode): Boolean =
        node.isInteractive || isOverlayCandidate(node)

    fun isOverlayCandidate(node: RichNode): Boolean {
        if (isOverlayWindowType(node.windowType)) return true
        if (isOverlayClass(node.klass)) return true
        if (isScrimHint(node.id) || isScrimHint(node.label) || isScrimHint(node.desc)) return true
        return false
    }

    fun toLayoutNode(node: RichNode): LayoutNode = LayoutNode(
        klass = node.klass,
        id = node.id,
        left = node.left,
        top = node.top,
        right = node.right,
        bottom = node.bottom,
        clickable = node.clickable,
        childCount = 0,
        label = node.label.ifBlank { node.text }.ifBlank { node.desc },
    )

    private const val ClutteredTargetCount = 24
    private const val FnvOffset = -3750763034362895579L
    private const val FnvPrime = 1099511628211L

    private fun mix(hash: Long, value: Int): Long = (hash xor value.toLong()) * FnvPrime

    private fun mix(hash: Long, value: String): Long {
        var next = hash
        for (index in 0 until value.length) {
            next = (next xor value[index].code.toLong()) * FnvPrime
        }
        return next
    }

    fun findTarget(compact: String, expectedLabel: String): AgentModeUiTarget? {
        val expected = normalizeLabel(expectedLabel)
        if (compact.isBlank() || expected.isBlank()) return null
        return compact.lineSequence()
            .mapNotNull(::parseCompactTarget)
            .map { target -> target to labelScore(expected, normalizeLabel(target.label)) }
            .filter { (_, score) -> score > 0.0 }
            .maxWithOrNull(
                compareBy<Pair<AgentModeUiTarget, Double>> { it.second }
                    .thenBy { -(it.first.right - it.first.left) * (it.first.bottom - it.first.top) },
            )
            ?.first
    }

    private fun parseXmlNodes(xml: String, width: Int, height: Int): List<CompactNode> {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val nodes = ArrayList<CompactNode>(64)
        for (match in NodeTag.findAll(xml)) {
            val attrs = HashMap<String, String>()
            for (attr in Attr.findAll(match.groupValues[1])) {
                attrs[attr.groupValues[1]] = attr.groupValues[2]
            }
            val bounds = Bounds.find(attrs["bounds"].orEmpty())
            val nx1: Int
            val ny1: Int
            val nx2: Int
            val ny2: Int
            if (bounds != null) {
                nx1 = bounds.groupValues[1].toInt() * 1000 / w
                ny1 = bounds.groupValues[2].toInt() * 1000 / h
                nx2 = bounds.groupValues[3].toInt() * 1000 / w
                ny2 = bounds.groupValues[4].toInt() * 1000 / h
            } else {
                nx1 = 0
                ny1 = 0
                nx2 = 0
                ny2 = 0
            }
            nodes += CompactNode(
                text = attrs["text"].orEmpty(),
                desc = attrs["content-desc"].orEmpty(),
                klass = attrs["class"].orEmpty().substringAfterLast('.'),
                id = attrs["resource-id"].orEmpty().substringAfterLast('/'),
                clickable = attrs["clickable"] == "true",
                checkable = attrs["checkable"] == "true",
                editable = attrs["class"].orEmpty().contains("EditText", ignoreCase = true) ||
                    attrs["password"] == "true",
                focused = attrs["focused"] == "true",
                left = nx1,
                top = ny1,
                right = nx2,
                bottom = ny2,
            )
        }
        return nodes
    }

    private fun shouldEmitCompact(node: CompactNode): Boolean {
        if (node.focused || node.editable) return true
        if (node.ownLabel.isNotBlank()) return true
        return node.clickable || node.checkable
    }

    private fun isArtClass(klass: String): Boolean {
        val k = klass.substringAfterLast('.').lowercase()
        return k == "imageview" || k == "image" || k == "surfaceview" ||
            k == "textureview" || k == "space" || k == "viewstub"
    }

    private fun looksLikeMediaClass(klass: String): Boolean {
        val k = klass.substringAfterLast('.').lowercase()
        return k.contains("image") ||
            k.contains("surface") ||
            k.contains("texture") ||
            k.contains("video") ||
            k.contains("gif") ||
            k.contains("lottie") ||
            k == "videoview" ||
            (k.contains("player") && k.contains("view")) ||
            k.contains("animation")
    }

    private fun looksLikeMediaId(value: String): Boolean {
        val n = value.trim().lowercase()
        if (n.isBlank()) return false
        return n.contains("gif") ||
            n.contains("video") ||
            n.contains("player") ||
            n.contains("lottie") ||
            n.contains("artwork") ||
            n.contains("thumbnail") ||
            n.contains("poster") ||
            n.contains("banner") ||
            n.contains("cover") ||
            n.contains("audio") ||
            n.contains("sound") ||
            n.contains("_anim") ||
            n.endsWith("anim")
    }

    private fun nodeArea(node: RichNode): Int =
        (node.right - node.left).coerceAtLeast(0) * (node.bottom - node.top).coerceAtLeast(0)

    private fun intersectionArea(a: RichNode, b: RichNode): Int {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val width = (right - left).coerceAtLeast(0)
        val height = (bottom - top).coerceAtLeast(0)
        return width * height
    }

    private fun isAncestorOrSelf(candidate: RichNode, target: RichNode, nodes: List<RichNode>): Boolean {
        if (candidate.index == target.index) return true
        val byIndex = nodes.associateBy { it.index }
        var parent = target.parent
        var guard = 0
        while (parent >= 0 && guard < 64) {
            if (parent == candidate.index) return true
            parent = byIndex[parent]?.parent ?: -1
            guard += 1
        }
        return false
    }

    private fun isDecorativeLabel(label: String): Boolean {
        val trimmed = label.trim()
        if (trimmed.isBlank()) return true
        if (DecorativeClasses.contains(normalizeClass(trimmed))) return true
        if (looksLikeArtId(trimmed)) return true
        return false
    }

    private fun looksLikeArtId(id: String): Boolean {
        val n = id.trim().lowercase()
        if (n.isBlank()) return false
        if (n.startsWith("iv_") || n.startsWith("img_") || n.startsWith("image")) return true
        if (n.endsWith("_bg") || n.endsWith("_bg_iv")) return true
        return ArtIdHints.any { hint -> n.contains(hint) }
    }

    private fun normalizeClass(value: String): String = value.substringAfterLast('.').lowercase()

    private fun isFullBleedArt(node: ParsedCompactLine, label: String): Boolean {
        val area = (node.right - node.left).coerceAtLeast(0) * (node.bottom - node.top).coerceAtLeast(0)
        if (area < 500_000) return false
        return isDecorativeLabel(label) || isArtClass(node.label)
    }

    private fun effectiveCompactLabel(node: ParsedCompactLine, all: List<ParsedCompactLine>): String {
        if (!isDecorativeLabel(node.label)) return node.label
        val child = all
            .filter { candidate ->
                candidate !== node &&
                    node.contains(candidate) &&
                    !isDecorativeLabel(candidate.label)
            }
            .minByOrNull { candidate ->
                (candidate.right - candidate.left).coerceAtLeast(0) *
                    (candidate.bottom - candidate.top).coerceAtLeast(0)
            }
        return child?.label ?: node.label
    }

    private data class ParsedCompactLine(
        val label: String,
        val tap: Boolean,
        val focus: Boolean,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        fun contains(other: ParsedCompactLine): Boolean =
            left <= other.left &&
                top <= other.top &&
                right >= other.right &&
                bottom >= other.bottom &&
                (left < other.left || top < other.top || right > other.right || bottom > other.bottom)
    }

    private fun parseCompactLine(rawLine: String): ParsedCompactLine? {
        val line = rawLine.trim()
        val bounds = CompactBounds.find(line) ?: return null
        var label = line.substring(0, bounds.range.first).trim()
        val focus = label.endsWith(" focus")
        if (focus) label = label.removeSuffix(" focus").trim()
        val tap = label.endsWith(" tap")
        if (tap) label = label.removeSuffix(" tap").trim()
        if (label.isBlank()) return null
        return ParsedCompactLine(
            label = label,
            tap = tap,
            focus = focus,
            left = bounds.groupValues[1].toInt(),
            top = bounds.groupValues[2].toInt(),
            right = bounds.groupValues[3].toInt(),
            bottom = bounds.groupValues[4].toInt(),
        )
    }

    private fun parseCompactTarget(rawLine: String): AgentModeUiTarget? {
        val parsed = parseCompactLine(rawLine) ?: return null
        return AgentModeUiTarget(
            label = parsed.label,
            left = parsed.left,
            top = parsed.top,
            right = parsed.right,
            bottom = parsed.bottom,
        )
    }

    private fun labelScore(expected: String, actual: String): Double = when {
        expected == actual -> 1.0
        actual.contains(expected) || expected.contains(actual) -> 0.8
        else -> {
            val left = expected.toSet()
            val right = actual.toSet()
            if (left.isEmpty() || right.isEmpty()) 0.0 else {
                left.intersect(right).size.toDouble() / left.union(right).size
            }.takeIf { it >= 0.6 } ?: 0.0
        }
    }

    private fun normalizeLabel(value: String): String =
        value.trim().lowercase().replace(Regex("\\s+"), " ")

    data class RichNode(
        val index: Int,
        val label: String,
        val text: String,
        val desc: String,
        val hint: String,
        val klass: String,
        val id: String,
        val clickable: Boolean,
        val longClickable: Boolean,
        val checkable: Boolean,
        val checked: Boolean,
        val selected: Boolean,
        val enabled: Boolean,
        val scrollable: Boolean,
        val focused: Boolean,
        val editable: Boolean,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val parent: Int,
        val depth: Int,
        val drawingOrder: Int = 0,
        val password: Boolean = false,
        val packageName: String = "",
        val windowType: Int = 0,
        val windowIndex: Int = 0,
    ) {
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2
        val bounds: String get() = "($left,$top)-($right,$bottom)"
        val clickIndex: Int get() = index + 1
        val isInteractive: Boolean
            get() = clickable || checkable || editable || scrollable || focused ||
                longClickable || password

        fun identityKey(): String =
            "${packageName}|${id}|${klass}|${left / 8},${top / 8},${right / 8},${bottom / 8}"

        fun matches(query: String): Boolean {
            val q = query.trim()
            if (q.isBlank()) return true
            val hashed = q.removePrefix("#")
            val numbered = hashed.toIntOrNull()
            if (q.startsWith("#") && numbered != null) {
                if (numbered == clickIndex || numbered == index) return true
                if (id.equals(hashed, ignoreCase = true)) return true
            }
            if (q.equals(id, ignoreCase = true) || q.equals("#$id", ignoreCase = true)) return true
            if (label.contains(q, ignoreCase = true)) return true
            if (text.contains(q, ignoreCase = true)) return true
            if (desc.contains(q, ignoreCase = true)) return true
            if (hint.contains(q, ignoreCase = true)) return true
            if (klass.contains(q, ignoreCase = true)) return true
            return false
        }

        fun inRegion(region: String): Boolean {
            val r = region.trim().lowercase()
            if (r.isBlank()) return true
            return when (r) {
                "top" -> centerY < 334
                "middle" -> centerY in 334..666
                "bottom" -> centerY > 666
                "left" -> centerX < 500
                "right" -> centerX >= 500
                else -> true
            }
        }
    }

    data class PagedDump(
        val nodes: List<RichNode>,
        val offset: Int,
        val limit: Int,
        val total: Int,
    ) {
        val hasMore: Boolean get() = offset + nodes.size < total
    }

    fun pageRichNodes(
        nodes: List<RichNode>,
        query: String = "",
        region: String = "",
        offset: Int = 0,
        limit: Int = DefaultDumpLimit,
    ): PagedDump {
        val filtered = nodes.filter { it.matches(query) && it.inRegion(region) }
        val start = offset.coerceAtLeast(0)
        val pageLimit = limit.coerceIn(1, MaxDumpLimit)
        return PagedDump(
            nodes = filtered.drop(start).take(pageLimit),
            offset = start,
            limit = pageLimit,
            total = filtered.size,
        )
    }

    /** Short structural role for compact lines (P3-3). */
    fun roleOf(node: RichNode): String = when {
        node.editable || node.password -> "input"
        node.checkable -> "switch"
        node.scrollable -> "list"
        node.clickable || node.longClickable -> "btn"
        else -> "text"
    }

    /** 334/666 band marker reused from the region convention. */
    fun layoutBand(node: RichNode): String = when {
        node.centerY < 334 -> "top"
        node.centerY in 334..666 -> "mid"
        else -> "bot"
    }

    private fun intersectsViewport(node: RichNode): Boolean =
        node.right > 0 && node.bottom > 0 && node.left < 1000 && node.top < 1000

    /**
     * P3-2 deterministic four-pass pruning (no embedding model):
     * 1. viewport: drop subtrees fully outside [0,1000]^2 (overlays exempt),
     * 2. wrapper collapse: a Layout with no interaction/label passes children up,
     * 3. interaction penetration: a clickable container absorbs non-interactive
     *    child text/icons into its label,
     * 4. proximity merge: TextView label/hint adjacent to an EditText becomes
     *    the input's accessible name.
     * Card/list-item parents are kept (no leaf flattening) so sibling "+"
     * buttons stay attributable.
     */
    fun pruneRich(nodes: List<RichNode>): List<RichNode> {
        if (nodes.isEmpty()) return nodes
        val byIndex = nodes.associateBy { it.index }
        val kept = LinkedHashMap<Int, RichNode>()
        nodes.forEach { node -> kept[node.index] = node }

        // 1. Viewport crop: remove nodes (and their subtrees) fully off-screen.
        val dropped = mutableSetOf<Int>()
        nodes.forEach { node ->
            if (isOverlayWindowType(node.windowType)) return@forEach
            if (!intersectsViewport(node)) {
                collectSubtreeIndexes(node.index, nodes).forEach { dropped += it }
            }
        }
        dropped.forEach { kept.remove(it) }

        // 2-3. Wrapper collapse + interaction penetration, deepest first.
        nodes.filter { it.index in kept.keys }
            .sortedByDescending { it.depth }
            .forEach { node ->
                val parent = node.parent.takeIf { it >= 0 }?.let { kept[it] } ?: return@forEach
                val absorbable = !node.isInteractive &&
                    node.hint.isBlank() &&
                    node.password.not() &&
                    !isOverlayWindowType(node.windowType)
                if (absorbable && parent.isInteractive) {
                    // 3. Penetration: fold plain text/icon children into the
                    // interactive parent's label so they are not tappable crumbs.
                    if (node.label.isNotBlank() &&
                        !parent.label.contains(node.label, ignoreCase = true) &&
                        node.label != parent.label
                    ) {
                        kept[parent.index] = parent.copy(
                            label = (parent.label + " " + node.label).trim().take(96),
                        )
                    }
                    collectSubtreeIndexes(node.index, nodes).forEach { kept.remove(it) }
                }
            }
        nodes.filter { it.index in kept.keys }
            .sortedByDescending { it.depth }
            .forEach { node ->
                if (node.index !in kept.keys) return@forEach
                val isWrapper = node.klass.contains("Layout") &&
                    !node.isInteractive &&
                    node.label.isBlank() &&
                    node.text.isBlank() &&
                    node.desc.isBlank() &&
                    node.hint.isBlank()
                if (isWrapper) {
                    // 2. Collapse: transparent wrapper layouts are dropped; their
                    // children keep their own parent pointer for card grouping.
                    kept.remove(node.index)
                }
            }

        // 4. Proximity merge: label TextView immediately above/left of an EditText.
        val keptNodes = kept.values.toList()
        val inputs = keptNodes.filter { it.editable && it.hint.isBlank() }
        inputs.forEach { input ->
            val label = keptNodes.firstOrNull { candidate ->
                candidate.index != input.index &&
                    !candidate.isInteractive &&
                    candidate.klass.contains("TextView") &&
                    candidate.label.isNotBlank() &&
                    candidate.bottom <= input.top + 24 &&
                    candidate.bottom >= input.top - 120 &&
                    candidate.left < input.right
            } ?: return@forEach
            kept[input.index] = (kept[input.index] ?: input).copy(
                hint = label.label.take(48),
            )
            collectSubtreeIndexes(label.index, nodes).forEach { kept.remove(it) }
        }

        // Re-index densely with a stable (top, left) reading order.
        val ordered = kept.values.sortedWith(compareBy({ it.top }, { it.left }, { it.depth }))
        val remap = ordered.mapIndexed { newIndex, node -> node.index to newIndex }.toMap()
        return ordered.map { node ->
            node.copy(
                index = remap.getValue(node.index),
                parent = if (node.parent >= 0) remap[node.parent] ?: -1 else -1,
            )
        }
    }

    private fun collectSubtreeIndexes(root: Int, nodes: List<RichNode>): Set<Int> {
        val out = linkedSetOf(root)
        var frontier = listOf(root)
        while (frontier.isNotEmpty()) {
            val children = nodes.filter { it.parent in frontier }.map { it.index }
            out += children
            frontier = children
        }
        return out
    }

    /** True when the tree is structurally blind (Flutter/游戏/WebView single surface). */
    fun isStructureBlind(nodes: List<RichNode>): Boolean {
        if (nodes.isEmpty()) return true
        val interactive = nodes.count { it.isInteractive }
        val surfaceArea = nodes.count { node ->
            val k = node.klass.substringAfterLast('.').lowercase()
            (k == "surfaceview" || k == "textureview" || k == "webview") &&
                (node.right - node.left) * (node.bottom - node.top) >= 500 * 500
        }
        return interactive <= 2 && surfaceArea > 0
    }

    fun deltaLines(previous: List<RichNode>, current: List<RichNode>): List<String> {
        if (current.isEmpty()) return emptyList()
        if (previous.isEmpty()) return current.map(::compactLine).take(24)
        val diff = treeDiff(previous, current)
        if (!diff.changed) return emptyList()
        val prevByKey = previous.associateBy { it.identityKey() }
        val currByKey = current.associateBy { it.identityKey() }
        val lines = LinkedHashSet<String>()
        diff.added.forEach { label ->
            currByKey.values.firstOrNull { it.label == label || it.klass == label }?.let {
                lines += "+ " + compactLine(it)
            }
        }
        diff.removed.forEach { label ->
            prevByKey.values.firstOrNull { it.label == label || it.klass == label }?.let {
                lines += "- " + compactLine(it)
            }
        }
        diff.textChanged.forEach { label ->
            currByKey.values.firstOrNull { it.label == label || it.id == label || it.klass == label }?.let {
                lines += "~ " + compactLine(it)
            }
        }
        if (lines.isEmpty() && diff.fingerprintChanged) {
            lines += "~ layout changed"
        }
        return lines.take(24)
    }

    fun scrollSwipe(nodes: List<RichNode>): Pair<Pair<Int, Int>, Pair<Int, Int>> =
        directionalSwipe(nodes, "up")

    fun parseSwipeDirection(raw: String): String = when (
        raw.trim().lowercase(Locale.ROOT).replace('-', '_')
    ) {
        "left", "swipe_left", "left_swipe" -> "left"
        "right", "swipe_right", "right_swipe" -> "right"
        "up", "swipe_up", "up_swipe" -> "up"
        "down", "swipe_down", "down_swipe" -> "down"
        else -> ""
    }

    /**
     * Finger-direction swipe inside the first scrollable node (or the whole
     * screen). `left`/`right` cover ViewPager / story / tab strips; `up` is
     * the existing list-scroll default.
     */
    fun directionalSwipe(
        nodes: List<RichNode>,
        direction: String,
    ): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        val dir = parseSwipeDirection(direction).ifBlank { "up" }
        val scrollable = nodes.firstOrNull { it.scrollable }
        val left = (scrollable?.left ?: 80).coerceIn(40, 960)
        val right = (scrollable?.right ?: 920).coerceIn(40, 960)
        val top = (scrollable?.top ?: 220).coerceIn(40, 960)
        val bottom = (scrollable?.bottom ?: 780).coerceIn(40, 960)
        val cx = (scrollable?.centerX ?: 500).coerceIn(40, 960)
        val cy = (scrollable?.centerY ?: 500).coerceIn(40, 960)
        val insetX = ((right - left) / 8).coerceIn(40, 120)
        val insetY = ((bottom - top) / 8).coerceIn(40, 120)
        return when (dir) {
            "left" -> (right - insetX to cy) to (left + insetX to cy)
            "right" -> (left + insetX to cy) to (right - insetX to cy)
            "down" -> (cx to top + insetY) to (cx to bottom - insetY)
            else -> {
                val y1 = (bottom - 80).coerceAtLeast(top + 120)
                val y2 = (top + 80).coerceAtMost(y1 - 40)
                (cx to y1) to (cx to y2)
            }
        }
    }

    fun looksLikeSearch(node: RichNode): Boolean {
        val hay = listOf(node.label, node.hint, node.desc, node.id, node.text)
            .joinToString(" ")
            .lowercase(Locale.ROOT)
        return hay.contains("search") ||
            hay.contains("搜索") ||
            hay.contains("查找") ||
            hay.contains("搜一下") ||
            hay.contains("搜一搜") ||
            hay.contains("query")
    }

    /** Prefer a labeled search box, then the focused field, then a top EditText. */
    fun findSearchField(nodes: List<RichNode>): RichNode? {
        nodes.firstOrNull { it.focused && it.editable && !it.password && looksLikeSearch(it) }
            ?.let { return it }
        nodes.firstOrNull { it.editable && !it.password && looksLikeSearch(it) }?.let { return it }
        nodes.firstOrNull { it.focused && it.editable && !it.password }?.let { return it }
        nodes.firstOrNull { it.editable && !it.password && it.top < 400 }?.let { return it }
        return nodes.firstOrNull { it.clickable && looksLikeSearch(it) }
    }

    fun compactLine(node: RichNode): String {
        val flags = buildString {
            if (node.clickable || node.checkable) append(" tap")
            if (node.longClickable) append(" long")
            if (node.scrollable) append(" scroll")
            if (node.focused) append(" focus")
            if (node.password) append(" password")
        }
        val idSuffix = if (node.id.isNotBlank()) " #${node.id}" else ""
        val hintSuffix = if (node.hint.isNotBlank()) " hint=${node.hint.take(24)}" else ""
        return "#${node.clickIndex} [${roleOf(node)}] ${node.label}$flags$hintSuffix ${node.bounds}$idSuffix [${layoutBand(node)}]"
    }

    fun interactiveNodes(nodes: List<RichNode>): List<RichNode> =
        nodes.filter { it.isInteractive }

    data class TreeDiff(
        val added: List<String> = emptyList(),
        val removed: List<String> = emptyList(),
        val textChanged: List<String> = emptyList(),
        val checkedChanged: List<String> = emptyList(),
        val fingerprintChanged: Boolean = false,
        val scopedToTarget: Boolean = false,
        val targetPresent: Boolean = true,
        val targetChanged: Boolean = false,
        val blockingOverlay: Boolean = false,
        val blockingLabel: String = "",
        val blockingIndex: Int = -1,
    ) {
        val changed: Boolean
            get() = if (scopedToTarget) {
                targetChanged || blockingOverlay
            } else {
                fingerprintChanged ||
                    added.isNotEmpty() ||
                    removed.isNotEmpty() ||
                    textChanged.isNotEmpty() ||
                    checkedChanged.isNotEmpty() ||
                    blockingOverlay
            }

        fun toJson(): org.json.JSONObject = org.json.JSONObject()
            .put("added", org.json.JSONArray(added.take(12)))
            .put("removed", org.json.JSONArray(removed.take(12)))
            .put("text_changed", org.json.JSONArray(textChanged.take(12)))
            .put("checked_changed", org.json.JSONArray(checkedChanged.take(12)))
            .put("fingerprint_changed", fingerprintChanged)
            .put("changed", changed)
            .put("scoped_to_target", scopedToTarget)
            .put("target_present", targetPresent)
            .put("target_changed", targetChanged)
            .put("blocking_overlay", blockingOverlay)
            .put("blocking_label", blockingLabel)
            .put("blocking_index", blockingIndex)
    }

    fun treeDiff(
        previous: List<RichNode>,
        current: List<RichNode>,
        target: RichNode? = null,
    ): TreeDiff {
        val prevKept = previous.filter { !isIgnoredMediaNode(it) }
        val currKept = current.filter { !isIgnoredMediaNode(it) }
        if (previous.isEmpty()) {
        val located = target?.let { findSameControl(current, it) }
        val overlay = target?.let { blockingOverlay(located ?: it, current) }
        return TreeDiff(
                added = currKept.map { it.label.ifBlank { it.klass } }.filter { it.isNotBlank() }.distinct().take(12),
                scopedToTarget = target != null,
                targetPresent = target == null || located != null,
                targetChanged = false,
                blockingOverlay = overlay != null,
                blockingLabel = overlay?.label.orEmpty(),
                blockingIndex = overlay?.clickIndex ?: -1,
            )
        }
        val prevByKey = prevKept.associateBy { it.identityKey() }
        val currByKey = currKept.associateBy { it.identityKey() }
        val added = currByKey.keys.minus(prevByKey.keys).mapNotNull { key ->
            currByKey[key]?.label?.ifBlank { currByKey[key]?.klass }
        }.filter { it.isNotBlank() }.distinct()
        val removed = prevByKey.keys.minus(currByKey.keys).mapNotNull { key ->
            prevByKey[key]?.label?.ifBlank { prevByKey[key]?.klass }
        }.filter { it.isNotBlank() }.distinct()
        val textChanged = ArrayList<String>()
        val checkedChanged = ArrayList<String>()
        currByKey.forEach { (key, node) ->
            val before = prevByKey[key] ?: return@forEach
            if (before.text != node.text && (before.text.isNotBlank() || node.text.isNotBlank())) {
                textChanged += node.label.ifBlank { node.id }.ifBlank { node.klass }
            }
            if (before.checked != node.checked) {
                checkedChanged += node.label.ifBlank { node.id }.ifBlank { node.klass }
            }
        }
        val prevFp = layoutFingerprint(prevKept.map(::toLayoutNode))
        val currFp = layoutFingerprint(currKept.map(::toLayoutNode))
        val located = target?.let { findSameControl(current, it) }
        val overlay = target?.let { blockingOverlay(located ?: it, current) }
        val targetChanged = when {
            target == null -> false
            located == null -> true
            else -> controlChanged(target, located)
        }
        return TreeDiff(
            added = added.take(12),
            removed = removed.take(12),
            textChanged = textChanged.distinct().take(12),
            checkedChanged = checkedChanged.distinct().take(12),
            fingerprintChanged = prevFp != currFp,
            scopedToTarget = target != null,
            targetPresent = target == null || located != null,
            targetChanged = targetChanged,
            blockingOverlay = overlay != null,
            blockingLabel = overlay?.label.orEmpty(),
            blockingIndex = overlay?.clickIndex ?: -1,
        )
    }

    fun findSameControl(nodes: List<RichNode>, target: RichNode): RichNode? {
        nodes.firstOrNull { it.identityKey() == target.identityKey() }?.let { return it }
        if (target.id.isNotBlank()) {
            nodes.firstOrNull { node ->
                node.id.equals(target.id, ignoreCase = true) &&
                    (target.klass.isBlank() || node.klass.equals(target.klass, ignoreCase = true))
            }?.let { return it }
        }
        if (target.label.isNotBlank()) {
            nodes.firstOrNull { node ->
                node.label.equals(target.label, ignoreCase = true) &&
                    kotlin.math.abs(node.centerX - target.centerX) <= 80 &&
                    kotlin.math.abs(node.centerY - target.centerY) <= 80
            }?.let { return it }
        }
        return nodes.firstOrNull { it.clickIndex == target.clickIndex && it.klass.equals(target.klass, ignoreCase = true) }
    }

    fun controlChanged(before: RichNode, after: RichNode): Boolean {
        if (before.clickable != after.clickable) return true
        if (before.enabled != after.enabled) return true
        if (before.editable != after.editable) return true
        if (before.focused != after.focused && before.editable) return true
        val slack = 24
        return kotlin.math.abs(before.left - after.left) > slack ||
            kotlin.math.abs(before.top - after.top) > slack ||
            kotlin.math.abs(before.right - after.right) > slack ||
            kotlin.math.abs(before.bottom - after.bottom) > slack
    }

    fun blockingOverlay(target: RichNode, nodes: List<RichNode>): RichNode? {
        val targetArea = nodeArea(target).coerceAtLeast(1)
        return nodes.firstOrNull { node ->
            if (node.index == target.index && node.identityKey() == target.identityKey()) {
                return@firstOrNull false
            }
            if (isIgnoredMediaNode(node) && !isOverlayCandidate(node)) return@firstOrNull false
            if (isAncestorOrSelf(node, target, nodes)) return@firstOrNull false
            if (isAncestorOrSelf(target, node, nodes) && !isOverlayCandidate(node)) {
                return@firstOrNull false
            }
            val overlap = intersectionArea(target, node)
            if (overlap * 100 < targetArea * 35) return@firstOrNull false
            node.windowIndex > target.windowIndex ||
                isOverlayWindowType(node.windowType) ||
                (node.windowIndex == target.windowIndex && node.drawingOrder > target.drawingOrder) ||
                isOverlayClass(node.klass) ||
                isScrimHint(node.id) ||
                isScrimHint(node.label)
        }
    }

    fun snapshotId(
        displayId: Int,
        width: Int,
        height: Int,
        packageName: String,
        fingerprint: Long,
        nodeCount: Int,
    ): String {
        var hash = FnvOffset
        hash = mix(hash, displayId)
        hash = mix(hash, width)
        hash = mix(hash, height)
        hash = mix(hash, packageName)
        hash = mix(hash, fingerprint.toInt())
        hash = mix(hash, (fingerprint ushr 32).toInt())
        hash = mix(hash, nodeCount)
        return hash.toULong().toString(16)
    }

    fun wrapUntrustedGuiContent(body: String): String {
        val trimmed = body.trim()
        if (trimmed.isBlank()) return trimmed
        return "$UntrustedGuiOpen\n$trimmed\n$UntrustedGuiClose"
    }

    fun parseIndexQuery(query: String): Int? {
        val trimmed = query.trim()
        if (!trimmed.startsWith("#")) return null
        return trimmed.drop(1).toIntOrNull()
    }

    const val UntrustedGuiOpen = "<untrusted_gui_content>"
    const val UntrustedGuiClose = "</untrusted_gui_content>"

    const val DefaultDumpLimit = 400
    const val DefaultTargetLimit = 120
    const val MaxDumpLimit = 800
    const val CheapDumpQuery = "__aether_cheap__"
}

