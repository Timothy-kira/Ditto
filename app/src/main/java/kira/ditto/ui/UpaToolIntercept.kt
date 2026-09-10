package kira.ditto.ui

import kira.ditto.a2ui.A2uiMessageProcessor
import kira.ditto.a2ui.A2uiSurfaceState
import kira.ditto.a2ui.compileUpaTreeToA2ui
import kira.ditto.a2ui.extractA2uiPayload
import kira.ditto.a2ui.looksLikeA2uiPayload
import kira.ditto.data.UpaPluginHostState
import kira.ditto.data.UpaPluginLibrary
import kira.ditto.upa.UpaManifest
import kira.ditto.upa.UpaNode
import kira.ditto.upa.UpaTemplate
import kira.ditto.upa.bindUpaTemplateFields
import kira.ditto.upa.declaresUiContent
import kira.ditto.upa.extractToolResultMime
import kira.ditto.upa.jsonObjectKeys
import kira.ditto.upa.matchesUpaMcpBind
import kira.ditto.upa.parseUpaJsonObject
import kira.ditto.upa.resolveUpaSurfaceTree
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject

data class A2uiChatCard(
    val cacheKey: String,
    val pluginId: String,
    val hostRenderer: String,
    val state: A2uiSurfaceState,
    val fields: Map<String, String> = emptyMap(),
    val boundServerIds: List<String> = emptyList(),
    val sourceTree: UpaNode? = null,
    val template: UpaTemplate? = null,
)

internal const val A2uiHostRendererBasic = "m3e.basic"

fun resolveA2uiChatCards(
    invocations: List<ChatToolInvocation>,
    library: UpaPluginLibrary?,
    userRenderUi: Boolean,
    disabledUiPlugins: Set<String>,
    mcpBindings: Map<String, List<String>>,
): List<A2uiChatCard> {
    if (!userRenderUi) return emptyList()
    val plugins = library?.installedManifestsBlocking().orEmpty()
    return invocations
        .filter { !it.isRunning && it.outputJson.isNotBlank() }
        .distinctBy { it.id }
        .mapNotNull { invocation ->
            resolveOneA2uiCard(
                invocation = invocation,
                library = library,
                plugins = plugins,
                userRenderUi = userRenderUi,
                disabledUiPlugins = disabledUiPlugins,
                mcpBindings = mcpBindings,
            )
        }
}

private fun resolveOneA2uiCard(
    invocation: ChatToolInvocation,
    library: UpaPluginLibrary?,
    plugins: List<UpaManifest>,
    userRenderUi: Boolean,
    disabledUiPlugins: Set<String>,
    mcpBindings: Map<String, List<String>>,
): A2uiChatCard? {
    extractA2uiPayload(invocation.outputJson)?.let { payload ->
        val processor = A2uiMessageProcessor()
        val state = processor.apply(payload) ?: return@let null
        return A2uiChatCard(
            cacheKey = "a2ui-native-${invocation.id}",
            pluginId = "",
            hostRenderer = A2uiHostRendererBasic,
            state = state,
        )
    }
    val payload = parseUpaJsonObject(unwrapToolJson(invocation.outputJson)) ?: return fallbackLegacyCard(
        invocation = invocation,
        library = library,
        userRenderUi = userRenderUi,
        disabledUiPlugins = disabledUiPlugins,
    )
    val mime = extractToolResultMime(payload)
    val keys = jsonObjectKeys(payload)
    val structured = (payload["structuredContent"] as? kotlinx.serialization.json.JsonObject) ?: payload
    val structuredKeys = jsonObjectKeys(structured) + keys
    plugins.forEach { manifest ->
        if (manifest.id in HostRetiredUpaPluginIds) return@forEach
        if (!UpaPluginHostState.rendersUi(manifest.id, userRenderUi) || manifest.id in disabledUiPlugins) {
            return@forEach
        }
        val bound = mcpBindings[manifest.id].orEmpty()
        if (bound.isEmpty()) return@forEach
        val binds = manifest.mcp?.bind.orEmpty().ifEmpty { return@forEach }
        val matched = binds.any { bind ->
            bound.any { serverId ->
                matchesUpaMcpBind(
                    bind = bind,
                    serverId = serverId,
                    toolName = invocation.toolName,
                    mimeType = mime,
                    payloadKeys = structuredKeys,
                )
            }
        }
        if (!matched) return@forEach
        if (!manifest.declaresUiContent()) return@forEach
        val resolved = resolveUpaSurfaceTree(manifest, structured)
            ?: resolveUpaSurfaceTree(manifest, payload)
            ?: return@forEach
        val (template, tree) = resolved
        val compiled = compileUpaTreeToA2ui(tree, surfaceId = "${manifest.id}:${template.id}")
        val processor = A2uiMessageProcessor()
        val state = processor.apply(compiled) ?: return@forEach
        val fields = bindUpaTemplateFields(template, structured).toMutableMap()
        return A2uiChatCard(
            cacheKey = "bind-${manifest.id}-${invocation.id}",
            pluginId = manifest.id,
            hostRenderer = A2uiHostRendererBasic,
            state = state,
            fields = fields,
            boundServerIds = bound,
            sourceTree = tree,
            template = template,
        )
    }
    return fallbackLegacyCard(invocation, library, userRenderUi, disabledUiPlugins)
}

private fun fallbackLegacyCard(
    invocation: ChatToolInvocation,
    library: UpaPluginLibrary?,
    userRenderUi: Boolean,
    disabledUiPlugins: Set<String>,
): A2uiChatCard? {
    if (!looksLikeUpaChatOutput(invocation.outputJson) && !looksLikeA2uiPayload(invocation.outputJson)) {
        return null
    }
    val surface = resolveUpaChatSurface(
        output = parseUpaToolOutput(invocation.outputJson),
        library = library,
        userRenderUi = userRenderUi,
        disabledUiPlugins = disabledUiPlugins,
    ) ?: return null
    val compiled = compileUpaTreeToA2ui(surface.tree, surfaceId = surface.cacheKey)
    val state = A2uiMessageProcessor().apply(compiled) ?: return null
    return A2uiChatCard(
        cacheKey = surface.cacheKey,
        pluginId = surface.pluginId,
        hostRenderer = A2uiHostRendererBasic,
        state = state,
        fields = surface.fields,
        sourceTree = surface.tree,
        template = surface.template,
    )
}

private fun unwrapToolJson(raw: String): String {
    val root = runCatching { JSONObject(raw) }.getOrNull() ?: return raw
    root.optJSONObject("structuredContent")?.let { return it.toString() }
    val content = root.optJSONArray("content") ?: return raw
    for (index in 0 until content.length()) {
        val text = content.optJSONObject(index)?.optString("text").orEmpty()
        if (text.startsWith("{")) return text
    }
    return raw
}
