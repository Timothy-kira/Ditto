package kira.ditto.a2ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

const val A2uiMimeType = "application/a2ui+json"
const val A2uiLegacyMimeType = "application/json+a2ui"
const val A2uiBasicCatalogId = "a2ui.m3e.1.0"

private val A2uiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

data class A2uiComponent(
    val id: String,
    val type: String,
    val props: JsonObject = JsonObject(emptyMap()),
    val children: List<String> = emptyList(),
)

data class A2uiSurfaceState(
    val surfaceId: String,
    val catalogId: String = A2uiBasicCatalogId,
    val components: Map<String, A2uiComponent> = emptyMap(),
    val rootIds: List<String> = emptyList(),
    val dataModel: JsonElement = JsonObject(emptyMap()),
)

class A2uiMessageProcessor {
    private val surfaces = linkedMapOf<String, A2uiSurfaceState>()

    fun apply(raw: String): A2uiSurfaceState? {
        val element = runCatching { A2uiJson.parseToJsonElement(raw) }.getOrNull() ?: return last()
        return apply(element)
    }

    fun apply(element: JsonElement): A2uiSurfaceState? {
        when (element) {
            is JsonArray -> element.forEach { item ->
                (item as? JsonObject)?.let(::applyMessage)
            }
            is JsonObject -> {
                if (looksLikeEnvelope(element)) {
                    applyMessage(element)
                } else {
                    listOf("createSurface", "updateComponents", "updateDataModel", "beginRendering")
                        .forEach { key ->
                            val nested = element[key]
                            if (nested is JsonObject) applyMessage(JsonObject(mapOf(key to nested)))
                            if (nested is JsonArray) nested.forEach { item ->
                                (item as? JsonObject)?.let(::applyMessage)
                            }
                        }
                    if (element["components"] is JsonArray || element["surfaceId"] is JsonPrimitive) {
                        applyMessage(element)
                    }
                }
            }
            else -> Unit
        }
        return last()
    }

    fun surface(id: String): A2uiSurfaceState? = surfaces[id]

    fun last(): A2uiSurfaceState? = surfaces.values.lastOrNull()

    fun all(): List<A2uiSurfaceState> = surfaces.values.toList()

    private fun looksLikeEnvelope(json: JsonObject): Boolean =
        json.containsKey("createSurface") ||
            json.containsKey("updateComponents") ||
            json.containsKey("updateDataModel") ||
            json.containsKey("beginRendering") ||
            json.containsKey("components")

    private fun applyMessage(message: JsonObject) {
        message["createSurface"]?.let { payload ->
            val obj = payload as? JsonObject ?: return@let
            val id = string(obj, "surfaceId", "id").ifBlank { "surface" }
            val catalog = string(obj, "catalogId").ifBlank { A2uiBasicCatalogId }
            surfaces[id] = A2uiSurfaceState(surfaceId = id, catalogId = catalog)
        }
        val update = message["updateComponents"] as? JsonObject
        val componentsPayload = (update?.get("components") as? JsonArray)
            ?: (message["components"] as? JsonArray)
        if (componentsPayload != null) {
            val surfaceId = string(update ?: message, "surfaceId", "id").ifBlank {
                surfaces.keys.lastOrNull().orEmpty().ifBlank { "surface" }
            }
            val current = surfaces[surfaceId] ?: A2uiSurfaceState(surfaceId = surfaceId)
            val parsed = componentsPayload.mapNotNull(::parseComponent)
            val byId = current.components.toMutableMap()
            parsed.forEach { byId[it.id] = it }
            val roots = parsed.firstOrNull()?.id?.let { listOf(it) } ?: current.rootIds
            surfaces[surfaceId] = current.copy(
                components = byId,
                rootIds = if (current.rootIds.isEmpty()) listOfNotNull(parsed.firstOrNull()?.id) else current.rootIds.ifEmpty { roots },
            )
        }
        message["updateDataModel"]?.let { payload ->
            val obj = payload as? JsonObject ?: return@let
            val surfaceId = string(obj, "surfaceId").ifBlank { surfaces.keys.lastOrNull().orEmpty() }
            if (surfaceId.isBlank()) return@let
            val current = surfaces[surfaceId] ?: A2uiSurfaceState(surfaceId = surfaceId)
            val value = obj["value"] ?: obj["data"] ?: JsonNull
            surfaces[surfaceId] = current.copy(dataModel = value)
        }
        message["beginRendering"]?.let { payload ->
            val obj = payload as? JsonObject ?: return@let
            val surfaceId = string(obj, "surfaceId").ifBlank { surfaces.keys.lastOrNull().orEmpty() }
            val root = string(obj, "root", "rootId")
            if (surfaceId.isBlank()) return@let
            val current = surfaces[surfaceId] ?: A2uiSurfaceState(surfaceId = surfaceId)
            surfaces[surfaceId] = current.copy(
                rootIds = listOf(root).filter { it.isNotBlank() }.ifEmpty { current.rootIds },
            )
        }
    }

    private fun parseComponent(element: JsonElement): A2uiComponent? {
        val obj = element as? JsonObject ?: return null
        val id = string(obj, "id").ifBlank { return null }
        val component = obj["component"] as? JsonObject
        val type: String
        val props: JsonObject
        if (component != null && component.isNotEmpty()) {
            val entry = component.entries.first()
            type = entry.key
            props = (entry.value as? JsonObject) ?: JsonObject(emptyMap())
        } else {
            type = string(obj, "type", "componentType").ifBlank { "Card" }
            props = (obj["props"] as? JsonObject) ?: JsonObject(emptyMap())
        }
        val children = ((obj["children"] as? JsonArray) ?: (props["children"] as? JsonArray))
            ?.mapNotNull { child ->
                when (child) {
                    is JsonPrimitive -> child.contentOrNull
                    is JsonObject -> string(child, "id", "componentId").takeIf { it.isNotBlank() }
                    else -> null
                }
            }
            .orEmpty()
        return A2uiComponent(id = id, type = type, props = props, children = children)
    }

    private fun string(obj: JsonObject, vararg keys: String): String {
        keys.forEach { key ->
            obj[key]?.let { (it as? JsonPrimitive)?.contentOrNull }?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return ""
    }
}

fun looksLikeA2uiPayload(raw: String): Boolean {
    if (raw.contains(A2uiMimeType) || raw.contains(A2uiLegacyMimeType)) return true
    return raw.contains("\"createSurface\"") ||
        raw.contains("\"updateComponents\"") ||
        raw.contains("\"beginRendering\"")
}

fun extractA2uiPayload(raw: String): String? {
    val root = runCatching { A2uiJson.parseToJsonElement(raw) }.getOrNull() ?: return if (looksLikeA2uiPayload(raw)) raw else null
    if (root is JsonArray) return raw
    val obj = root as? JsonObject ?: return if (looksLikeA2uiPayload(raw)) raw else null
    if (looksLikeA2uiEnvelope(obj)) return raw
    val content = obj["content"] as? JsonArray
    content?.forEach { item ->
        val block = item as? JsonObject ?: return@forEach
        val mime = (block["mimeType"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (mime.equals(A2uiMimeType, true) || mime.equals(A2uiLegacyMimeType, true)) {
            val text = when (val payload = block["text"] ?: (block["resource"] as? JsonObject)?.let { it["text"] ?: it["blob"] }) {
                is JsonPrimitive -> payload.contentOrNull
                is JsonObject, is JsonArray -> payload.toString()
                else -> null
            }
            if (!text.isNullOrBlank()) return text
        }
        val nestedText = (block["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (looksLikeA2uiPayload(nestedText)) return nestedText
    }
    obj["structuredContent"]?.let { structured ->
        if (structured is JsonObject && looksLikeA2uiEnvelope(structured)) {
            return structured.toString()
        }
    }
    return if (looksLikeA2uiEnvelope(obj)) raw else null
}

private fun looksLikeA2uiEnvelope(obj: JsonObject): Boolean =
    obj.containsKey("createSurface") ||
        obj.containsKey("updateComponents") ||
        obj.containsKey("updateDataModel") ||
        obj.containsKey("beginRendering") ||
        (obj.containsKey("components") && obj.containsKey("surfaceId"))
