package kira.ditto.data.humation

import org.json.JSONArray
import org.json.JSONObject

data class HumationViewBox(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)

data class HumationOffset(
    val x: Double,
    val y: Double,
)

data class HumationLayerSlot(
    val id: String,
    val order: Int,
    val offset: HumationOffset,
)

data class HumationLayerFragment(
    val layerSlot: String,
    val svg: String? = null,
    val svgPath: String? = null,
    val transform: String? = null,
)

data class HumationPart(
    val id: String,
    val name: String,
    val selectionSlot: String,
    val aliases: List<String>,
    val layers: List<HumationLayerFragment>,
    val sourceGroupId: String = "",
    val sourcePartId: String = "",
    val deprecated: Boolean = false,
)

data class HumationSelectionSlot(
    val id: String,
    val label: String,
)

data class HumationColorSlot(
    val id: String,
    val label: String,
    val default: String,
    val allowTransparent: Boolean = false,
)

data class HumationAlias(
    val alias: String,
    val targetId: String,
)

data class HumationManifest(
    val templateId: String,
    val defaultsSelections: Map<String, String>,
    val defaultsColors: Map<String, String>,
    val defaultsBackground: String,
    val defaultsCrop: String,
    val crops: Map<String, HumationViewBox>,
    val selectionSlots: List<HumationSelectionSlot>,
    val colorSlots: List<HumationColorSlot>,
    val layerSlots: List<HumationLayerSlot>,
    val parts: List<HumationPart>,
    val aliases: List<HumationAlias>,
) {
    fun partsForSlot(slotId: String): List<HumationPart> =
        parts.filter { it.selectionSlot == slotId && !it.deprecated }
            .sortedBy { it.sourcePartId.ifBlank { it.id } }
}

data class HumationAvatarOptions(
    val seed: String? = null,
    val selections: Map<String, String> = emptyMap(),
    val colors: Map<String, String> = emptyMap(),
    val background: String? = null,
    val crop: String? = null,
)

data class HumationAvatarState(
    val template: String,
    val selections: Map<String, String>,
    val colors: Map<String, String>,
    val background: String,
    val crop: String,
)

fun parseHumationManifest(raw: String): HumationManifest {
    val json = JSONObject(raw)
    val template = json.optJSONObject("template") ?: JSONObject()
    val defaults = json.optJSONObject("defaults") ?: JSONObject()
    return HumationManifest(
        templateId = template.optString("id"),
        defaultsSelections = defaults.optJSONObject("selections").toStringMap(),
        defaultsColors = defaults.optJSONObject("colors").toStringMap().mapValues { normalizeHex(it.value) },
        defaultsBackground = defaults.optString("background", "F6F5F4"),
        defaultsCrop = defaults.optString("crop", "avatar"),
        crops = json.optJSONObject("crops").toViewBoxes(),
        selectionSlots = json.optJSONArray("selectionSlots").toObjectList { item ->
            HumationSelectionSlot(id = item.optString("id"), label = item.optString("label"))
        },
        colorSlots = json.optJSONArray("colors").toObjectList { item ->
            HumationColorSlot(
                id = item.optString("id"),
                label = item.optString("label"),
                default = normalizeHex(item.optString("default")),
                allowTransparent = item.optBoolean("allowTransparent"),
            )
        },
        layerSlots = json.optJSONArray("layerSlots").toObjectList { item ->
            val offset = item.optJSONObject("offset") ?: JSONObject()
            HumationLayerSlot(
                id = item.optString("id"),
                order = item.optInt("order"),
                offset = HumationOffset(offset.optDouble("x"), offset.optDouble("y")),
            )
        },
        parts = json.optJSONArray("parts").toObjectList { item ->
            val source = item.optJSONObject("source") ?: JSONObject()
            HumationPart(
                id = item.optString("id"),
                name = item.optString("name"),
                selectionSlot = item.optString("selectionSlot"),
                aliases = item.optJSONArray("aliases").toStringList(),
                layers = item.optJSONArray("layers").toObjectList { layer ->
                    HumationLayerFragment(
                        layerSlot = layer.optString("layerSlot"),
                        svg = layer.optString("svg").takeIf { it.isNotBlank() },
                        svgPath = layer.optString("svgPath").takeIf { it.isNotBlank() },
                        transform = layer.optString("transform").takeIf { it.isNotBlank() },
                    )
                },
                sourceGroupId = source.optString("groupId"),
                sourcePartId = source.optString("partId"),
                deprecated = item.optBoolean("deprecated"),
            )
        },
        aliases = json.optJSONArray("aliases").toObjectList { item ->
            HumationAlias(alias = item.optString("alias"), targetId = item.optString("targetId"))
        },
    )
}

fun resolveHumationState(
    manifest: HumationManifest,
    options: HumationAvatarOptions,
): HumationAvatarState {
    val selections = manifest.defaultsSelections.toMutableMap()
    val seed = options.seed
    if (seed != null) {
        for (slot in manifest.selectionSlots) {
            val slotParts = manifest.parts.filter { it.selectionSlot == slot.id }
            if (slotParts.isEmpty()) continue
            val hash = fnv1a("${seed}:${slot.id}")
            selections[slot.id] = slotParts[(hash % slotParts.size.toUInt()).toInt()].id
        }
    }
    for ((slotId, value) in options.selections) {
        if (value.isBlank()) continue
        val partId = resolvePartId(value, manifest, slotId)
        val part = manifest.parts.firstOrNull { it.id == partId }
            ?: error("Unknown part: $value")
        require(part.selectionSlot == slotId) { "Part $value is not selectable in slot $slotId" }
        selections[slotId] = partId
    }
    val colors = manifest.defaultsColors.toMutableMap()
    for ((key, color) in options.colors) {
        if (color.isBlank()) continue
        colors[key] = normalizeHex(color)
    }
    val background = options.background?.takeIf { it.isNotBlank() } ?: manifest.defaultsBackground
    return HumationAvatarState(
        template = manifest.templateId,
        selections = selections,
        colors = colors,
        background = if (background == "transparent") background else normalizeHex(background),
        crop = options.crop?.takeIf { it.isNotBlank() } ?: manifest.defaultsCrop,
    )
}

fun renderHumationSvg(
    manifest: HumationManifest,
    options: HumationAvatarOptions,
    loadSvg: (String) -> String,
): String {
    val state = resolveHumationState(manifest, options)
    val viewBox = manifest.crops[state.crop] ?: manifest.crops[manifest.defaultsCrop]
        ?: error("Unknown crop: ${state.crop}")
    val fragments = collectFragments(manifest, state, loadSvg)
    val cssVariables = formatCssVariables(state.colors)
    val bgRect = if (state.background == "transparent") {
        ""
    } else {
        "<rect x=\"${formatNumber(viewBox.x)}\" y=\"${formatNumber(viewBox.y)}\" " +
            "width=\"${formatNumber(viewBox.width)}\" height=\"${formatNumber(viewBox.height)}\" " +
            "fill=\"#${state.background}\" />"
    }
    val content = fragments.joinToString("")
    val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"${formatNumber(viewBox.width)}\" " +
        "height=\"${formatNumber(viewBox.height)}\" viewBox=\"${formatNumber(viewBox.x)} " +
        "${formatNumber(viewBox.y)} ${formatNumber(viewBox.width)} ${formatNumber(viewBox.height)}\" " +
        "style=\"${escapeAttr(cssVariables)}\">$bgRect$content</svg>"
    return applyCssVariables(svg, state.colors)
}

internal fun fnv1a(input: String): UInt {
    var hash = 0x811c9dc5u
    for (ch in input) {
        hash = hash xor ch.code.toUInt()
        hash *= 0x01000193u
    }
    return hash
}

internal fun resolvePartId(input: String, manifest: HumationManifest, slotId: String?): String {
    if (manifest.parts.any { it.id == input }) return input
    if (slotId != null) {
        val scoped = manifest.aliases.firstOrNull { it.alias == "$slotId-$input" }
        if (scoped != null) return scoped.targetId
    }
    val alias = manifest.aliases.firstOrNull { it.alias == input }
    if (alias != null) return alias.targetId
    error("Unknown part: $input")
}

private data class ResolvedFragment(
    val part: HumationPart,
    val fragment: HumationLayerFragment,
    val order: Int,
    val offset: HumationOffset,
    val svg: String,
)

private fun collectFragments(
    manifest: HumationManifest,
    state: HumationAvatarState,
    loadSvg: (String) -> String,
): List<String> {
    return state.selections.values.map { partId ->
        manifest.parts.firstOrNull { it.id == partId } ?: error("Unknown selected part: $partId")
    }.flatMap { part ->
        part.layers.map { fragment ->
            val layerSlot = manifest.layerSlots.firstOrNull { it.id == fragment.layerSlot }
                ?: error("Unknown layer slot: ${fragment.layerSlot}")
            val svg = fragment.svg
                ?: fragment.svgPath?.let(loadSvg)
                ?: error("Missing SVG for part: ${part.id}")
            ResolvedFragment(part, fragment, layerSlot.order, layerSlot.offset, svg)
        }
    }.sortedBy { it.order }.map { resolved ->
        renderFragment(resolved)
    }
}

private fun renderFragment(resolved: ResolvedFragment): String {
    val content = stripSvgWrapper(resolved.svg)
    val transform = if (resolved.fragment.transform.isNullOrBlank()) {
        "translate(${formatNumber(resolved.offset.x)}, ${formatNumber(resolved.offset.y)})"
    } else {
        "translate(${formatNumber(resolved.offset.x)}, ${formatNumber(resolved.offset.y)}) ${resolved.fragment.transform}"
    }
    val attributes = buildList {
        add("data-hm-layer-slot=\"${escapeAttr(resolved.fragment.layerSlot)}\"")
        add("data-hm-part-id=\"${escapeAttr(resolved.part.id)}\"")
        add("data-hm-selection-slot=\"${escapeAttr(resolved.part.selectionSlot)}\"")
        if (resolved.part.sourceGroupId.isNotBlank()) {
            add("data-hm-source-group-id=\"${escapeAttr(resolved.part.sourceGroupId)}\"")
        }
        if (resolved.part.sourcePartId.isNotBlank()) {
            add("data-hm-source-part-id=\"${escapeAttr(resolved.part.sourcePartId)}\"")
        }
        add("transform=\"${escapeAttr(transform)}\"")
    }.joinToString(" ")
    return "<g $attributes>$content</g>"
}

private fun formatCssVariables(colors: Map<String, String>): String =
    colors.entries.sortedBy { it.key }.joinToString(";") { (key, color) ->
        "--hm-$key:#${normalizeHex(color)}"
    }

private fun applyCssVariables(svg: String, colors: Map<String, String>): String {
    val pattern = Regex("""var\(\s*--hm-([A-Za-z0-9-]+)(?:\s*,\s*([^)]+))?\s*\)""")
    return pattern.replace(svg) { match ->
        val key = match.groupValues[1]
        val fallback = match.groupValues.getOrNull(2)?.trim().orEmpty()
        val hex = colors[key]
        when {
            !hex.isNullOrBlank() -> "#$hex"
            fallback.isNotBlank() -> fallback
            else -> match.value
        }
    }
}

internal fun normalizeHex(color: String): String = color.trim().removePrefix("#").uppercase()

private fun stripSvgWrapper(svg: String): String =
    svg.replace(Regex("<svg[^>]*>"), "").replace(Regex("</svg>\\s*$"), "")

internal fun formatNumber(value: Double): String {
    val asLong = value.toLong()
    return if (value == asLong.toDouble()) {
        asLong.toString()
    } else {
        value.toString().trimEnd('0').trimEnd('.')
    }
}

private fun escapeAttr(value: String): String =
    value.replace("&", "&amp;").replace("\"", "&quot;")

private fun JSONObject?.toStringMap(): Map<String, String> {
    if (this == null) return emptyMap()
    return keys().asSequence().associateWith { key -> optString(key) }
}

private fun JSONObject?.toViewBoxes(): Map<String, HumationViewBox> {
    if (this == null) return emptyMap()
    return keys().asSequence().associateWith { key ->
        val item = optJSONObject(key) ?: JSONObject()
        HumationViewBox(
            x = item.optDouble("x"),
            y = item.optDouble("y"),
            width = item.optDouble("width"),
            height = item.optDouble("height"),
        )
    }
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optString(index).takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}

private fun <T> JSONArray?.toObjectList(parse: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            add(parse(item))
        }
    }
}
