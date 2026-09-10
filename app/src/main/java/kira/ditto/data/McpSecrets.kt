package kira.ditto.data

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

enum class McpSecretKind {
    UrlQuery,
    Env,
    Header,
}

data class McpSecretSlot(
    val id: String,
    val serverId: String,
    val kind: McpSecretKind,
    val injectKey: String,
    val inlineValue: String = "",
) {
    val hasInlineValue: Boolean get() = inlineValue.isNotBlank()
}

fun interface McpSecretLookup {
    fun get(id: String): String?

    companion object {
        val None = McpSecretLookup { null }
    }
}

private val QuerySecretName = Regex("(?i)^(key|token|password|secret|apikey|api[_-]?key)$")
private val EnvSecretName = Regex("(?i).*(api[_-]?key|token|password|secret).*")
private val HeaderSecretName = Regex("(?i)^(authorization|x-api-key|api-key|api_key)$")

fun inferMcpSecretSlots(config: McpServerConfig): List<McpSecretSlot> {
    val serverId = config.id
    return when (val transport = config.transport) {
        is McpTransportConfig.StreamableHttp -> buildList {
            val params = parseQueryParams(transport.url)
            params.forEach { (name, value) ->
                if (QuerySecretName.matches(name)) {
                    add(
                        McpSecretSlot(
                            id = mcpSecretId(serverId, McpSecretKind.UrlQuery, name),
                            serverId = serverId,
                            kind = McpSecretKind.UrlQuery,
                            injectKey = name,
                            inlineValue = value,
                        ),
                    )
                }
            }
            val host = runCatching { URI(transport.url).host.orEmpty().lowercase() }.getOrDefault("")
            if (host == "mcp.amap.com" && params.keys.none { it.equals("key", ignoreCase = true) }) {
                add(
                    McpSecretSlot(
                        id = mcpSecretId(serverId, McpSecretKind.UrlQuery, "key"),
                        serverId = serverId,
                        kind = McpSecretKind.UrlQuery,
                        injectKey = "key",
                    ),
                )
            }
            transport.headers.forEach { header ->
                if (HeaderSecretName.matches(header.key.trim())) {
                    add(
                        McpSecretSlot(
                            id = mcpSecretId(serverId, McpSecretKind.Header, header.key.trim()),
                            serverId = serverId,
                            kind = McpSecretKind.Header,
                            injectKey = header.key.trim(),
                            inlineValue = header.value,
                        ),
                    )
                }
            }
        }
        is McpTransportConfig.StdIo -> transport.environment.mapNotNull { env ->
            val key = env.key.trim()
            if (!EnvSecretName.matches(key)) return@mapNotNull null
            McpSecretSlot(
                id = mcpSecretId(serverId, McpSecretKind.Env, key),
                serverId = serverId,
                kind = McpSecretKind.Env,
                injectKey = key,
                inlineValue = env.value,
            )
        }
        is McpTransportConfig.UpaManifest -> emptyList()
    }.distinctBy { it.id }
}

fun mcpSecretKindWire(kind: McpSecretKind): String = when (kind) {
    McpSecretKind.UrlQuery -> "url.query"
    McpSecretKind.Env -> "env"
    McpSecretKind.Header -> "header"
}

fun mcpSecretDraftKey(kind: McpSecretKind, injectKey: String): String =
    "${mcpSecretKindWire(kind)}.${secretIdToken(injectKey)}"

fun mcpSecretId(serverId: String, kind: McpSecretKind, injectKey: String): String =
    "mcp.${secretIdToken(serverId)}.${mcpSecretKindWire(kind)}.${secretIdToken(injectKey)}"

internal fun secretIdToken(raw: String): String {
    val mapped = raw.trim().replace(':', '.').replace('/', '-')
    return HostSecretStore.sanitize(mapped).ifBlank { "x" }
}

fun HostSecretStore.asLookup(): McpSecretLookup = McpSecretLookup { get(it) }

fun mcpSecretSlotFilled(slot: McpSecretSlot, lookup: McpSecretLookup): Boolean =
    slot.hasInlineValue || lookup.get(slot.id).orEmpty().isNotBlank()

fun missingMcpSecretSlots(
    servers: List<McpServerConfig>,
    selectedIds: Collection<String>,
    lookup: McpSecretLookup,
): List<McpSecretSlot> {
    val selected = selectedIds.toSet()
    return servers
        .filter { it.isEnabled && it.id in selected }
        .flatMap { inferMcpSecretSlots(it) }
        .filterNot { mcpSecretSlotFilled(it, lookup) }
}

fun applyMcpSecrets(config: McpServerConfig, lookup: McpSecretLookup): McpServerConfig {
    val slots = inferMcpSecretSlots(config)
    if (slots.isEmpty()) return config
    return when (val transport = config.transport) {
        is McpTransportConfig.StreamableHttp -> {
            var url = transport.url
            val headers = transport.headers.toMutableList()
            slots.forEach { slot ->
                val value = lookup.get(slot.id)?.takeIf { it.isNotBlank() } ?: slot.inlineValue
                if (value.isBlank()) return@forEach
                when (slot.kind) {
                    McpSecretKind.UrlQuery -> url = withQueryParam(url, slot.injectKey, value)
                    McpSecretKind.Header -> {
                        val index = headers.indexOfFirst { it.key.equals(slot.injectKey, ignoreCase = true) }
                        val item = McpKeyValue(slot.injectKey, value)
                        if (index >= 0) headers[index] = item else headers.add(item)
                    }
                    McpSecretKind.Env -> Unit
                }
            }
            config.copy(transport = transport.copy(url = url, headers = headers))
        }
        is McpTransportConfig.StdIo -> {
            val environment = transport.environment.toMutableList()
            slots.forEach { slot ->
                val value = lookup.get(slot.id)?.takeIf { it.isNotBlank() } ?: slot.inlineValue
                if (value.isBlank() || slot.kind != McpSecretKind.Env) return@forEach
                val index = environment.indexOfFirst { it.key == slot.injectKey }
                val item = McpKeyValue(slot.injectKey, value)
                if (index >= 0) environment[index] = item else environment.add(item)
            }
            config.copy(transport = transport.copy(environment = environment))
        }
        is McpTransportConfig.UpaManifest -> config
    }
}

fun extractInlineMcpSecrets(
    config: McpServerConfig,
    store: (id: String, value: String) -> Unit,
): McpServerConfig {
    val slots = inferMcpSecretSlots(config)
    if (slots.none { it.hasInlineValue }) return config
    slots.filter { it.hasInlineValue }.forEach { slot ->
        store(slot.id, slot.inlineValue)
    }
    return when (val transport = config.transport) {
        is McpTransportConfig.StreamableHttp -> {
            var url = transport.url
            val headers = transport.headers.map { header ->
                if (HeaderSecretName.matches(header.key.trim()) && header.value.isNotBlank()) {
                    header.copy(value = "")
                } else {
                    header
                }
            }
            slots.filter { it.kind == McpSecretKind.UrlQuery && it.hasInlineValue }.forEach { slot ->
                url = removeQueryParam(url, slot.injectKey)
            }
            config.copy(transport = transport.copy(url = url, headers = headers))
        }
        is McpTransportConfig.StdIo -> {
            val environment = transport.environment.map { env ->
                if (EnvSecretName.matches(env.key.trim()) && env.value.isNotBlank()) {
                    env.copy(value = "")
                } else {
                    env
                }
            }
            config.copy(transport = transport.copy(environment = environment))
        }
        is McpTransportConfig.UpaManifest -> config
    }
}

fun foldMcpSecretDrafts(
    config: McpServerConfig,
    drafts: Map<String, String>,
): McpServerConfig {
    val filled = drafts.filterValues { it.isNotBlank() }
    if (filled.isEmpty()) return config
    val slots = inferMcpSecretSlots(config)
    val lookup = McpSecretLookup { id ->
        val slot = slots.firstOrNull { it.id == id } ?: return@McpSecretLookup null
        filled[mcpSecretDraftKey(slot.kind, slot.injectKey)]?.takeIf { it.isNotBlank() }
    }
    return applyMcpSecrets(config, lookup)
}

internal fun parseMcpKeyValueLines(rawValue: String): List<McpKeyValue> =
    rawValue.lineSequence().mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return@mapNotNull null
        val separatorIndex = trimmed.indexOf('=')
        if (separatorIndex <= 0) return@mapNotNull null
        McpKeyValue(
            key = trimmed.substring(0, separatorIndex).trim(),
            value = trimmed.substring(separatorIndex + 1).trim(),
        )
    }.toList()

internal fun parseQueryParams(url: String): Map<String, String> {
    val query = url.substringAfter('?', missingDelimiterValue = "").substringBefore('#')
    if (query.isBlank()) return emptyMap()
    return query.split('&').mapNotNull { part ->
        if (part.isBlank()) return@mapNotNull null
        val name = decodeQuery(part.substringBefore('='))
        if (name.isBlank()) return@mapNotNull null
        val value = decodeQuery(part.substringAfter('=', ""))
        name to value
    }.toMap()
}

internal fun withQueryParam(url: String, name: String, value: String): String {
    val hash = url.substringAfter('#', missingDelimiterValue = "").let { if (it.isBlank() && !url.contains('#')) "" else "#$it" }
    val withoutHash = url.substringBefore('#')
    val base = withoutHash.substringBefore('?')
    val params = parseQueryParams(withoutHash).toMutableMap()
    params[name] = value
    val query = params.entries.joinToString("&") { (key, stored) ->
        "${encodeQuery(key)}=${encodeQuery(stored)}"
    }
    return if (query.isBlank()) base + hash else "$base?$query$hash"
}

internal fun removeQueryParam(url: String, name: String): String {
    val hash = url.substringAfter('#', missingDelimiterValue = "").let { if (it.isBlank() && !url.contains('#')) "" else "#$it" }
    val withoutHash = url.substringBefore('#')
    val base = withoutHash.substringBefore('?')
    val params = parseQueryParams(withoutHash).filterKeys { !it.equals(name, ignoreCase = true) }
    val query = params.entries.joinToString("&") { (key, stored) ->
        "${encodeQuery(key)}=${encodeQuery(stored)}"
    }
    return if (query.isBlank()) base + hash else "$base?$query$hash"
}

private fun decodeQuery(raw: String): String =
    runCatching { URLDecoder.decode(raw, Charsets.UTF_8.name()) }.getOrDefault(raw)

private fun encodeQuery(raw: String): String =
    URLEncoder.encode(raw, Charsets.UTF_8.name()).replace("+", "%20")
