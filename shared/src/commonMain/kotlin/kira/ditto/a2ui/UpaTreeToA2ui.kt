package kira.ditto.a2ui

import kira.ditto.upa.UpaNode
import kira.ditto.upa.upaNodeFlag
import kira.ditto.upa.upaNodeText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun compileUpaTreeToA2ui(
    tree: UpaNode,
    surfaceId: String,
    catalogId: String = A2uiBasicCatalogId,
): String {
    val components = mutableListOf<String>()
    fun emit(node: UpaNode): String {
        val childIds = node.children.map(::emit)
        val (type, props) = mapUpaNode(node)
        val childrenJson = if (childIds.isEmpty()) {
            ""
        } else {
            ""","children":[${childIds.joinToString(",") { "\"$it\"" }}]"""
        }
        components += """{"id":"${node.id}","component":{"$type":$props}$childrenJson}"""
        return node.id
    }
    val rootId = emit(tree)
    val joined = components.joinToString(",")
    return """
        {
          "createSurface": { "surfaceId": ${jsonQuote(surfaceId)}, "catalogId": ${jsonQuote(catalogId)} },
          "updateComponents": {
            "surfaceId": ${jsonQuote(surfaceId)},
            "components": [$joined]
          },
          "beginRendering": { "surfaceId": ${jsonQuote(surfaceId)}, "root": ${jsonQuote(rootId)} }
        }
    """.trimIndent()
}

private fun mapUpaNode(node: UpaNode): Pair<String, String> {
    val visible = upaNodeFlag(node, "visible", true)
    val hiddenProps = if (visible) "" else ""","visible":false"""
    return when (node.type) {
        "group", "scaffold", "bubble", "sheet" -> {
            val title = upaNodeText(node, "title")
            "Card" to """{"title":{"literalString":${jsonQuote(title)}}$hiddenProps}"""
        }
        "text", "markdown", "badge" -> {
            val role = upaNodeText(node, "role")
            val variant = when (role) {
                "hero" -> "headlineLarge"
                "title" -> "titleLarge"
                "caption" -> "bodySmall"
                else -> "bodyMedium"
            }
            val text = upaNodeText(node, "text") + upaNodeText(node, "suffix")
            "Text" to """{"text":{"literalString":${jsonQuote(text)}},"variant":${jsonQuote(variant)}$hiddenProps}"""
        }
        "row" -> {
            val title = upaNodeText(node, "title")
            val subtitle = upaNodeText(node, "subtitle")
            "Row" to """{"title":{"literalString":${jsonQuote(title)}},"subtitle":{"literalString":${jsonQuote(subtitle)}}$hiddenProps}"""
        }
        "action" -> {
            val label = upaNodeText(node, "label")
            val href = upaNodeText(node, "href").ifBlank { upaNodeText(node, "url") }
            val event = node.events["click"].orEmpty()
            "Button" to """{"label":{"literalString":${jsonQuote(label)}},"href":${jsonQuote(href)},"event":${jsonQuote(event)}$hiddenProps}"""
        }
        "image" -> {
            val src = upaNodeText(node, "src").ifBlank { upaNodeText(node, "url") }
            "Image" to """{"url":{"literalString":${jsonQuote(src)}}$hiddenProps}"""
        }
        "icon" -> "Icon" to """{"name":${jsonQuote(upaNodeText(node, "name").ifBlank { upaNodeText(node, "icon") })}$hiddenProps}"""
        "list" -> "List" to """{}$hiddenProps"""
        "input" -> "TextField" to """{"label":{"literalString":${jsonQuote(upaNodeText(node, "label"))}},"value":{"literalString":${jsonQuote(upaNodeText(node, "value").ifBlank { upaNodeText(node, "text") })}}$hiddenProps}"""
        "toggle" -> "CheckBox" to """{"label":{"literalString":${jsonQuote(upaNodeText(node, "label"))}},"checked":${upaNodeFlag(node, "checked", false)}$hiddenProps}"""
        "select" -> "ChoicePicker" to """{"label":{"literalString":${jsonQuote(upaNodeText(node, "label"))}}$hiddenProps}"""
        "html" -> "Text" to """{"text":{"literalString":${jsonQuote(upaNodeText(node, "text"))}}$hiddenProps}"""
        else -> "Column" to """{}$hiddenProps"""
    }
}

private fun jsonQuote(value: String): String = JsonPrimitive(value).toString()

fun a2uiLiteralString(props: JsonObject, vararg keys: String): String {
    keys.forEach { key ->
        val node = props[key] ?: return@forEach
        when (node) {
            is JsonPrimitive -> if (node.content.isNotBlank()) return node.content
            is JsonObject -> {
                (node["literalString"] as? JsonPrimitive)?.contentOrNull?.let { return it }
                (node["literal"] as? JsonPrimitive)?.contentOrNull?.let { return it }
            }
            else -> Unit
        }
    }
    return ""
}

fun a2uiString(props: JsonObject, vararg keys: String): String {
    keys.forEach { key ->
        val node = props[key] ?: return@forEach
        when (node) {
            is JsonPrimitive -> if (node.content.isNotBlank()) return node.content
            is JsonObject -> a2uiLiteralString(node, "literalString", "literal").takeIf { it.isNotBlank() }?.let { return it }
            else -> Unit
        }
    }
    return ""
}
