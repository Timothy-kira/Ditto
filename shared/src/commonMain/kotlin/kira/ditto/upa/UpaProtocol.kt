package kira.ditto.upa

import kira.ditto.data.AetherUpaPluginGitHubOwner
import kira.ditto.data.AetherUpaPluginGitHubRepo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.builtins.ListSerializer

const val UpaProtocolVersion = "0.2"
const val UpaLegacyProtocolVersion = "0.1"
val UpaSupportedProtocolVersions = setOf(UpaLegacyProtocolVersion, UpaProtocolVersion)
const val UpaComposeHostId = "ditto-upa-0.1"
const val UpaA2uiCatalogId = "a2ui.m3e.1.0"
const val UpaBasicRendererPackId = "aether.ui.m3e.basic"
const val UpaMcpServerIdPrefix = "upa:"

fun upaMcpServerId(pluginId: String): String = "$UpaMcpServerIdPrefix$pluginId"

fun isUpaMcpServerId(serverId: String): Boolean = serverId.startsWith(UpaMcpServerIdPrefix)

private val UpaJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
}

enum class UpaCapability(val wire: String) {
    UiSurface("ui.surface"),
    UiTemplate("ui.template"),
    UiCatalog("ui.catalog"),
    HtmlCompat("html.compat"),
    A2uiIngest("a2ui.ingest"),
    Tools("tools"),
    Resources("resources"),
    Prompts("prompts"),
    A2a("a2a"),
    McpBridge("mcp.bridge"),
    McpImport("mcp.import"),
    ;

    companion object {
        fun fromWire(value: String): UpaCapability? = entries.firstOrNull { it.wire == value }
    }
}

enum class UpaPermission(
    val wire: String,
    val scope: UpaPermissionScope,
    val access: UpaPermissionAccess,
) {
    Network("network", UpaPermissionScope.Guest, UpaPermissionAccess.Read),
    StorageRead("storage.read", UpaPermissionScope.Guest, UpaPermissionAccess.Read),
    StorageWrite("storage.write", UpaPermissionScope.Guest, UpaPermissionAccess.Write),
    Clipboard("clipboard", UpaPermissionScope.Guest, UpaPermissionAccess.Write),
    HtmlSandbox("html.sandbox", UpaPermissionScope.Guest, UpaPermissionAccess.Read),
    HostAppsRead("host.apps.read", UpaPermissionScope.Host, UpaPermissionAccess.Read),
    HostHealthRead("host.health.read", UpaPermissionScope.Host, UpaPermissionAccess.Read),
    HostLocationRead("host.location.read", UpaPermissionScope.Host, UpaPermissionAccess.Read),
    HostCalendarRead("host.calendar.read", UpaPermissionScope.Host, UpaPermissionAccess.Read),
    HostStorageRead("host.storage.read", UpaPermissionScope.Host, UpaPermissionAccess.Read),
    ;

    companion object {
        fun fromWire(value: String): UpaPermission? = entries.firstOrNull { it.wire == value }
    }
}

enum class UpaSlot(val wire: String) {
    Bubble("bubble"),
    Page("page"),
    Sheet("sheet"),
    Composer("composer"),
    ToolStatus("tool-status"),
    ;

    companion object {
        fun fromWire(value: String): UpaSlot? = entries.firstOrNull { it.wire == value }
    }
}

enum class UpaNodeType(val wire: String) {
    Scaffold("scaffold"),
    Group("group"),
    Row("row"),
    Action("action"),
    Text("text"),
    Markdown("markdown"),
    Badge("badge"),
    Bubble("bubble"),
    ToolStatus("tool-status"),
    Composer("composer"),
    Input("input"),
    Toggle("toggle"),
    Select("select"),
    Image("image"),
    Icon("icon"),
    List("list"),
    Sheet("sheet"),
    Html("html"),
    Slot("slot"),
    ;

    companion object {
        fun fromWire(value: String): UpaNodeType? = entries.firstOrNull { it.wire == value }
    }
}

enum class UpaSourceKind {
    Github,
    Url,
    Local,
}

@Serializable
data class UpaSource(
    val kind: String,
    val repo: String = "",
    val ref: String = "",
    val path: String = "",
    val uri: String = "",
)

@Serializable
data class UpaComposeRuntime(
    val minHost: String = UpaComposeHostId,
)

@Serializable
data class UpaHtmlRuntime(
    val entry: String = "",
    val mode: String = "",
    val sandbox: String = "isolated",
)

@Serializable
data class UpaRuntimes(
    val compose: UpaComposeRuntime? = UpaComposeRuntime(),
    val html: UpaHtmlRuntime? = null,
)

@Serializable
data class UpaStyle(
    val tone: String = "",
    val color: String = "",
)

@Serializable
data class UpaTheme(
    val seed: String = "",
)

@Serializable
data class UpaCatalogElement(
    val type: String,
    val description: String = "",
    val html: String = "",
    val propsSchema: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class UpaCatalog(
    val id: String,
    val elements: List<UpaCatalogElement> = emptyList(),
)

object UpaCatalogListSerializer : JsonTransformingSerializer<List<UpaCatalog>>(
    ListSerializer(UpaCatalog.serializer()),
) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        val array = element as? JsonArray ?: return JsonArray(emptyList())
        return JsonArray(
            array.map { item ->
                when (item) {
                    is JsonPrimitive -> buildJsonObject { put("id", item.content) }
                    else -> item
                }
            },
        )
    }
}

@Serializable
data class UpaNode(
    val id: String,
    val type: String,
    val props: JsonObject = JsonObject(emptyMap()),
    val bind: String = "",
    val style: UpaStyle? = null,
    val children: List<UpaNode> = emptyList(),
    val events: Map<String, String> = emptyMap(),
)

@Serializable
data class UpaSurface(
    val id: String,
    val slot: String,
    val title: String = "",
    val catalogId: String = "",
    val theme: UpaTheme? = null,
    val entry: String = "",
    val tree: UpaNode? = null,
)

@Serializable
data class UpaTemplateField(
    val id: String,
    val nodeId: String = "",
    val prop: String = "text",
    val from: String = "",
)

@Serializable
data class UpaTemplateMatch(
    val keys: List<String> = emptyList(),
    val mimeTypes: List<String> = emptyList(),
)

@Serializable
data class UpaTemplate(
    val id: String,
    val surface: String = "",
    val interactive: Boolean = true,
    val fields: List<UpaTemplateField> = emptyList(),
    val match: UpaTemplateMatch? = null,
    val transitions: JsonObject = JsonObject(emptyMap()),
    val tree: UpaNode? = null,
)

@Serializable
data class UpaToolUi(
    val surface: String = "",
    val slot: String = "",
)

@Serializable
data class UpaTool(
    val name: String,
    val description: String = "",
    val inputSchema: JsonObject = JsonObject(emptyMap()),
    val ui: UpaToolUi? = null,
)

@Serializable
data class UpaResource(
    val uri: String,
    val name: String = "",
    val mimeType: String = "",
    val description: String = "",
)

@Serializable
data class UpaPrompt(
    val name: String,
    val description: String = "",
    val arguments: List<JsonElement> = emptyList(),
)

@Serializable
data class UpaAgent(
    val id: String,
    val name: String = "",
    val endpoint: String = "",
    val inputModes: List<String> = emptyList(),
    val outputModes: List<String> = emptyList(),
)

@Serializable
data class UpaMcpBindMatch(
    val keys: List<String> = emptyList(),
)

@Serializable
data class UpaMcpBind(
    val servers: List<String> = emptyList(),
    val tools: List<String> = emptyList(),
    val mimeTypes: List<String> = emptyList(),
    val match: UpaMcpBindMatch? = null,
)

@Serializable
data class UpaMcpBridge(
    val export: Boolean = false,
    @SerialName("import")
    val importIds: List<String> = emptyList(),
    val bind: List<UpaMcpBind> = emptyList(),
)

@Serializable
data class UpaUiPolicy(
    val present: Boolean = false,
)

@Serializable
data class UpaManifest(
    val upa: String,
    val id: String,
    val name: String,
    @SerialName("displayName")
    val displayName: String = "",
    val icon: String = "",
    val version: String,
    val releasedAt: String = "",
    val description: String = "",
    val license: String = "",
    val source: UpaSource? = null,
    val capabilities: List<String> = emptyList(),
    val permissions: List<String> = emptyList(),
    val ui: UpaUiPolicy? = null,
    val runtimes: UpaRuntimes = UpaRuntimes(),
    @Serializable(with = UpaCatalogListSerializer::class)
    val catalogs: List<UpaCatalog> = emptyList(),
    val rendererPacks: List<String> = emptyList(),
    val surfaces: List<UpaSurface> = emptyList(),
    val templates: List<UpaTemplate> = emptyList(),
    val tools: List<UpaTool> = emptyList(),
    val resources: List<UpaResource> = emptyList(),
    val prompts: List<UpaPrompt> = emptyList(),
    val agents: List<UpaAgent> = emptyList(),
    val mcp: UpaMcpBridge? = null,
    val memory: UpaMemoryPolicy? = null,
)

@Serializable
data class UpaMemoryPolicy(
    val everme: UpaEverMeMemory? = null,
)

@Serializable
data class UpaEverMeMemory(
    val trajectory: Boolean = false,
)

fun UpaManifest.inAppDisplayName(): String = resolveUpaDisplayName(id, name, displayName)

fun UpaManifest.inAppIcon(): String = resolveUpaIcon(id, icon)

fun resolveUpaDisplayName(id: String, name: String, displayName: String): String =
    displayName.trim().ifBlank {
        when (id) {
            "health.apps.card" -> "健康"
            "kaggle.cli.card" -> "Kaggle"
            else -> name.trim()
        }
    }

fun resolveUpaIcon(id: String, icon: String): String =
    icon.trim().ifBlank {
        when (id) {
            "health.apps.card" -> "heart"
            "kaggle.cli.card" -> "kaggle"
            else -> ""
        }
    }

fun UpaManifest.allowsEverMeTrajectory(): Boolean =
    memory?.everme?.trajectory == true

fun UpaManifest.exportsMcpTools(): Boolean = false

fun UpaManifest.declaresUiContent(): Boolean {
    if (ui != null) return ui.present
    return capabilities.contains(UpaCapability.UiSurface.wire) &&
        surfaces.isNotEmpty() &&
        templates.isNotEmpty()
}

fun UpaTool.encodedInputSchema(): String {
    val raw = inputSchema.toString()
    return if (raw.isBlank() || raw == "null") """{"type":"object"}""" else raw
}

data class UpaInstallRef(
    val kind: UpaSourceKind,
    val owner: String = "",
    val repo: String = "",
    val ref: String = "",
    val path: String = "",
    val uri: String = "",
) {
    fun display(): String = when (kind) {
        UpaSourceKind.Github -> buildString {
            append(owner)
            append('/')
            append(repo)
            if (ref.isNotBlank()) {
                append('@')
                append(ref)
            }
            if (path.isNotBlank() && path != ".") {
                append(':')
                append(path.trim('/'))
            }
        }
        UpaSourceKind.Url, UpaSourceKind.Local -> uri.ifBlank { path }
    }

    fun archiveHint(): String = when (kind) {
        UpaSourceKind.Github -> {
            val gitRef = ref.ifBlank { "HEAD" }
            val sub = path.trim('/').ifBlank { "." }
            "$owner/$repo@$gitRef → $sub/upa.json"
        }
        UpaSourceKind.Url -> uri
        UpaSourceKind.Local -> path.ifBlank { uri }
    }
}

sealed class UpaManifestResult {
    data class Ok(val manifest: UpaManifest) : UpaManifestResult()
    data class Err(val message: String) : UpaManifestResult()
}

fun parseUpaManifest(text: String): UpaManifestResult {
    val manifest = runCatching { UpaJson.decodeFromString(UpaManifest.serializer(), text) }
        .getOrElse { return UpaManifestResult.Err("upa.json 无法解析：${it.message ?: "invalid json"}") }
    return validateUpaManifest(manifest)
}

fun validateUpaManifest(manifest: UpaManifest): UpaManifestResult {
    if (manifest.upa !in UpaSupportedProtocolVersions) {
        return UpaManifestResult.Err("需要 upa ${UpaSupportedProtocolVersions.joinToString("/")}，实际是 ${manifest.upa}")
    }
    if (!manifest.id.matches(Regex("^[a-z0-9][a-z0-9._-]*$"))) {
        return UpaManifestResult.Err("id 必须是小写点分标识")
    }
    if (manifest.name.isBlank()) {
        return UpaManifestResult.Err("name 不能为空")
    }
    if (!manifest.version.matches(Regex("""^\d+\.\d+\.\d+([.-][A-Za-z0-9.]+)?$"""))) {
        return UpaManifestResult.Err("version 必须是 semver")
    }
    if (manifest.releasedAt.isNotBlank() && parseReleasedAtEpochMs(manifest.releasedAt) == null) {
        return UpaManifestResult.Err("releasedAt 必须是 UTC ISO-8601，例如 2026-08-24T10:00:00Z")
    }
    manifest.capabilities.forEach { value ->
        if (UpaCapability.fromWire(value) == null) {
            return UpaManifestResult.Err("未知 capability：$value")
        }
    }
    manifest.permissions.forEach { value ->
        if (isForbiddenHostMutation(value)) {
            return UpaManifestResult.Err("虚拟机外只允许只读权限，不能申请删除或写入：$value")
        }
        if (UpaPermission.fromWire(value) == null) {
            return UpaManifestResult.Err("未知 permission：$value")
        }
    }
    manifest.catalogs.forEach { catalog ->
        if (catalog.id.isBlank()) return UpaManifestResult.Err("catalog id 不能为空")
        catalog.elements.forEach { element ->
            if (!NodeTypePattern.matches(element.type)) {
                return UpaManifestResult.Err("非法 catalog type：${element.type}")
            }
            if (element.html.isNotBlank() && !element.html.matches(Regex("^[a-z]+-[a-z0-9-]+$"))) {
                return UpaManifestResult.Err("自定义元素名必须含连字符：${element.html}")
            }
        }
    }
    val surfaceIds = mutableSetOf<String>()
    manifest.surfaces.forEach { surface ->
        if (!surfaceIds.add(surface.id)) {
            return UpaManifestResult.Err("重复 surface id：${surface.id}")
        }
        if (UpaSlot.fromWire(surface.slot) == null) {
            return UpaManifestResult.Err("未知 slot：${surface.slot}")
        }
        surface.tree?.let { tree ->
            validateTree(tree, catalogTypes(manifest))?.let { return UpaManifestResult.Err(it) }
        }
    }
    val surfaceById = manifest.surfaces.associateBy { it.id }
    val templateIds = mutableSetOf<String>()
    manifest.templates.forEach { template ->
        if (!templateIds.add(template.id)) {
            return UpaManifestResult.Err("重复 template id：${template.id}")
        }
        if (template.surface.isNotBlank() && template.surface !in surfaceById) {
            return UpaManifestResult.Err("template ${template.id} 引用了不存在的 surface：${template.surface}")
        }
        val tree = template.tree ?: surfaceById[template.surface]?.tree
        template.tree?.let { extra ->
            validateTree(extra, catalogTypes(manifest))?.let { return UpaManifestResult.Err(it) }
        }
        val nodeIds = tree?.let { collectNodeIds(it) }.orEmpty()
        val fieldIds = mutableSetOf<String>()
        template.fields.forEach { field ->
            if (!fieldIds.add(field.id)) {
                return UpaManifestResult.Err("template ${template.id} 重复字段：${field.id}")
            }
            if (field.nodeId.isNotBlank() && nodeIds.isNotEmpty() && field.nodeId !in nodeIds) {
                return UpaManifestResult.Err("template ${template.id} 字段 ${field.id} 找不到节点 ${field.nodeId}")
            }
        }
    }
    val hasCustomHtml = manifest.catalogs.any { catalog ->
        catalog.elements.any { it.html.isNotBlank() }
    }
    if (manifest.permissions.contains(UpaPermission.HtmlSandbox.wire).not()) {
        if (hasHtmlNode(manifest) || hasCustomHtml) {
            return UpaManifestResult.Err("html 节点或自定义元素需要 permissions 包含 html.sandbox")
        }
    }
    if (manifest.ui?.present == true) {
        val hasLayout = manifest.surfaces.isNotEmpty() && manifest.templates.isNotEmpty()
        val hasA2ui = manifest.catalogs.any { catalog ->
            catalog.id.isNotBlank()
        } || manifest.rendererPacks.isNotEmpty()
        if (!hasLayout && !hasA2ui) {
            return UpaManifestResult.Err("ui.present 为 true 时必须提供 surfaces+templates 或 A2UI catalog")
        }
    }
    return UpaManifestResult.Ok(manifest)
}

fun parseUpaInstallRef(raw: String): UpaInstallRef? {
    val value = normalizeUpaInstallInput(raw)
    if (value.isBlank()) return null
    if (value.startsWith("github:", ignoreCase = true)) {
        val body = value.substringAfter(':').trim()
        parseGithubColonRef(body)?.let { return it }
        return parseOfficialUpaPluginShorthand(body)
    }
    parseGithubWebRef(value)?.let { return it }
    parseRawGithubusercontentRef(value)?.let { return it }
    parseJsdelivrGithubRef(value)?.let { return it }
    if (value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)) {
        return UpaInstallRef(kind = UpaSourceKind.Url, uri = value)
    }
    parseGithubColonRef(value)?.let { return it }
    parseOfficialUpaPluginShorthand(value)?.let { return it }
    return UpaInstallRef(kind = UpaSourceKind.Local, path = value)
}

private fun parseGithubWebRef(value: String): UpaInstallRef? {
    val githubWeb = Regex(
        """^https?://(?:www\.)?github\.com/([^/]+)/([^/]+?)(?:\.git)?(?:/(tree|blob|raw)/([^/]+)(?:/(.*))?)?/?$""",
        RegexOption.IGNORE_CASE,
    ).matchEntire(value) ?: return null
    val owner = githubWeb.groupValues[1]
    val repo = githubWeb.groupValues[2].removeSuffix(".git")
    val kind = githubWeb.groupValues[3].lowercase()
    val gitRef = githubWeb.groupValues[4]
    val subPath = githubWeb.groupValues[5]
        .removeSuffix("/upa.json")
        .trim('/')
    if (kind.isNotBlank() && kind !in setOf("tree", "blob", "raw")) return null
    if (gitRef.isBlank() && subPath.isNotBlank()) return null
    val ignored = subPath.substringBefore('/').lowercase()
    if (ignored in setOf("issues", "pulls", "actions", "releases", "wiki", "commit", "commits")) {
        return null
    }
    return UpaInstallRef(
        kind = UpaSourceKind.Github,
        owner = owner,
        repo = repo,
        ref = gitRef,
        path = subPath,
        uri = value,
    )
}

private fun parseRawGithubusercontentRef(value: String): UpaInstallRef? {
    val match = Regex(
        """^https?://raw\.githubusercontent\.com/([^/]+)/([^/]+)/(.+)$""",
        RegexOption.IGNORE_CASE,
    ).matchEntire(value) ?: return null
    val owner = match.groupValues[1]
    val repo = match.groupValues[2].removeSuffix(".git")
    val rest = match.groupValues[3].trim('/')
    val (gitRef, path) = splitGithubRefAndPath(rest)
    return UpaInstallRef(
        kind = UpaSourceKind.Github,
        owner = owner,
        repo = repo,
        ref = gitRef,
        path = path.removeSuffix("/upa.json").trim('/'),
        uri = value,
    )
}

private fun parseJsdelivrGithubRef(value: String): UpaInstallRef? {
    val match = Regex(
        """^https?://(?:cdn|fastly|gcore)\.jsdelivr\.net/gh/([^/]+)/([^@/]+)@([^/]+)/(.*)$""",
        RegexOption.IGNORE_CASE,
    ).matchEntire(value) ?: return null
    return UpaInstallRef(
        kind = UpaSourceKind.Github,
        owner = match.groupValues[1],
        repo = match.groupValues[2].removeSuffix(".git"),
        ref = match.groupValues[3],
        path = match.groupValues[4].removeSuffix("/upa.json").trim('/'),
        uri = value,
    )
}

private fun splitGithubRefAndPath(rest: String): Pair<String, String> {
    val refsHeads = "refs/heads/"
    val refsTags = "refs/tags/"
    when {
        rest.startsWith(refsHeads, ignoreCase = true) -> {
            val after = rest.substring(refsHeads.length)
            val slash = after.indexOf('/')
            return if (slash < 0) after to "" else after.substring(0, slash) to after.substring(slash + 1)
        }
        rest.startsWith(refsTags, ignoreCase = true) -> {
            val after = rest.substring(refsTags.length)
            val slash = after.indexOf('/')
            return if (slash < 0) after to "" else after.substring(0, slash) to after.substring(slash + 1)
        }
        else -> {
            val slash = rest.indexOf('/')
            return if (slash < 0) rest to "" else rest.substring(0, slash) to rest.substring(slash + 1)
        }
    }
}

private fun normalizeUpaInstallInput(raw: String): String {
    var value = raw.trim().substringBefore('#').trim()
    if (value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)) {
        val host = hostFromUrl(value)
        if (host == "github.com" || host == "www.github.com") {
            value = value.substringBefore('?').trim()
        }
    }
    return value.trim().trimEnd('/')
}

private fun parseOfficialUpaPluginShorthand(value: String): UpaInstallRef? {
    if (value.contains('/') || value.contains("://")) return null
    val plugin = value.substringBefore('@').trim()
    val ref = value.substringAfter('@', "").trim()
    if (!plugin.matches(Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$"))) return null
    return UpaInstallRef(
        kind = UpaSourceKind.Github,
        owner = AetherUpaPluginGitHubOwner,
        repo = AetherUpaPluginGitHubRepo,
        ref = ref.ifBlank { "main" },
        path = plugin,
    )
}

private fun parseGithubColonRef(body: String): UpaInstallRef? {
    val slash = body.indexOf('/')
    if (slash <= 0) return null
    val owner = body.substring(0, slash).trim()
    val rest = body.substring(slash + 1)
    if (!owner.matches(Regex("^[A-Za-z0-9_.-]+$"))) return null
    val repoEnd = rest.indexOfFirst { it == '@' || it == ':' }.let { index ->
        if (index < 0) rest.length else index
    }
    val repo = rest.substring(0, repoEnd).trim().removeSuffix(".git")
    if (owner.isBlank() || repo.isBlank() || !repo.matches(Regex("^[A-Za-z0-9_.-]+$"))) return null
    var cursor = repoEnd
    var ref = ""
    var path = ""
    if (cursor < rest.length && rest[cursor] == '@') {
        val refStart = cursor + 1
        val colon = rest.indexOf(':', refStart)
        if (colon < 0) {
            ref = rest.substring(refStart).trim()
            cursor = rest.length
        } else {
            ref = rest.substring(refStart, colon).trim()
            cursor = colon
        }
    }
    if (cursor < rest.length && rest[cursor] == ':') {
        path = rest.substring(cursor + 1).trim().trim('/')
    }
    return UpaInstallRef(
        kind = UpaSourceKind.Github,
        owner = owner,
        repo = repo,
        ref = ref,
        path = path,
    )
}

private val NodeTypePattern = Regex("^[a-z][a-z0-9.-]*$")
private val SeedColorPattern = Regex("^#([0-9A-Fa-f]{3}|[0-9A-Fa-f]{6})$")
private val AllowedTones = setOf("primary", "accent", "success", "warning", "danger", "neutral")

private fun catalogTypes(manifest: UpaManifest): Set<String> =
    manifest.catalogs.flatMap { catalog -> catalog.elements.map { it.type } }.toSet()

private fun validateTree(
    node: UpaNode,
    extraTypes: Set<String>,
    seen: MutableSet<String> = mutableSetOf(),
): String? {
    if (node.id.isBlank()) return "节点 id 不能为空"
    if (!seen.add(node.id)) return "重复节点 id：${node.id}"
    if (!NodeTypePattern.matches(node.type)) return "非法节点 type：${node.type}"
    if (UpaNodeType.fromWire(node.type) == null && node.type !in extraTypes) {
        return "未知节点 type：${node.type}（请写入 catalogs[].elements）"
    }
    node.style?.let { style ->
        if (style.tone.isNotBlank() && style.tone !in AllowedTones) {
            return "未知 tone：${style.tone}"
        }
        if (style.color.isNotBlank() && !SeedColorPattern.matches(style.color)) {
            return "color 必须是 #RGB 或 #RRGGBB 种子色"
        }
    }
    node.children.forEach { child ->
        validateTree(child, extraTypes, seen)?.let { return it }
    }
    return null
}

private fun hasHtmlNode(manifest: UpaManifest): Boolean {
    fun walk(node: UpaNode?): Boolean {
        if (node == null) return false
        if (node.type == UpaNodeType.Html.wire) return true
        return node.children.any { walk(it) }
    }
    return manifest.surfaces.any { walk(it.tree) } || manifest.templates.any { walk(it.tree) }
}

private fun collectNodeIds(node: UpaNode): Set<String> = buildSet {
    add(node.id)
    node.children.forEach { addAll(collectNodeIds(it)) }
}

fun jsonPointer(root: JsonElement, pointer: String): JsonElement? {
    if (pointer.isEmpty()) return root
    val normalized = if (pointer.startsWith("/")) pointer else "/$pointer"
    var current = root
    if (normalized == "/") return root
    normalized.substring(1).split('/').forEach { raw ->
        if (raw.isEmpty()) return current
        val token = raw.replace("~1", "/").replace("~0", "~")
        current = when (current) {
            is JsonObject -> current[token] ?: return null
            is JsonArray -> {
                val index = token.toIntOrNull() ?: return null
                current.getOrNull(index) ?: return null
            }
            else -> return null
        }
    }
    return current
}

fun scoreUpaTemplateMatch(
    template: UpaTemplate,
    payload: JsonObject,
    mimeType: String = "",
): Int {
    val match = template.match
    var score = 0
    if (match != null) {
        if (match.mimeTypes.any { it.equals(mimeType, ignoreCase = true) }) {
            score += 3
        }
        if (match.keys.isNotEmpty()) {
            val missing = match.keys.any { key -> !jsonMatchValuePresent(payload, key) }
            if (missing) return 0
            score += match.keys.size
        }
        return score
    }
    val fieldHits = template.fields.count { field ->
        jsonPointer(payload, field.from.ifBlank { "/${field.id}" }) != null || payload[field.id] != null
    }
    return if (fieldHits == template.fields.size && template.fields.isNotEmpty()) fieldHits else 0
}

fun matchUpaTemplate(
    templates: List<UpaTemplate>,
    payload: JsonObject,
    mimeType: String = "",
    templateId: String = "",
): UpaTemplate? {
    if (templateId.isNotBlank()) {
        return templates.firstOrNull { it.id == templateId }
    }
    val hinted = (payload["_upa"] as? JsonObject)
        ?.get("templateId")
        ?.jsonPrimitive
        ?.contentOrNull
        .orEmpty()
    if (hinted.isNotBlank()) {
        return templates.firstOrNull { it.id == hinted }
    }
    val scored = templates
        .map { it to scoreUpaTemplateMatch(it, payload, mimeType) }
        .filter { it.second > 0 }
        .sortedByDescending { it.second }
    if (scored.isEmpty()) return null
    if (scored.size >= 2 && scored[0].second == scored[1].second) return null
    return scored[0].first
}

fun bindUpaTemplateFields(
    template: UpaTemplate,
    payload: JsonObject,
): Map<String, String> = buildMap {
    template.fields.forEach { field ->
        val pointer = field.from.ifBlank { "/${field.id}" }
        val value = jsonPointer(payload, pointer) ?: payload[field.id] ?: return@forEach
        if (value is JsonNull) return@forEach
        if (value is JsonPrimitive && value.content.isBlank()) return@forEach
        if (value is JsonPrimitive && value.booleanOrNull == false) return@forEach
        put(
            field.id,
            when (value) {
                is JsonPrimitive -> value.content
                else -> value.toString()
            },
        )
    }
}

fun parseUpaJsonObject(raw: String): JsonObject? =
    runCatching { UpaJson.parseToJsonElement(raw).jsonObject }.getOrNull()

fun bindUpaTemplateFieldsJson(template: UpaTemplate, rawJson: String): Map<String, String> {
    val payload = parseUpaJsonObject(rawJson) ?: return emptyMap()
    return bindUpaTemplateFields(template, payload)
}

fun matchUpaTemplateJson(
    templates: List<UpaTemplate>,
    rawJson: String,
    templateId: String = "",
): UpaTemplate? {
    val payload = parseUpaJsonObject(rawJson) ?: JsonObject(emptyMap())
    return matchUpaTemplate(templates, payload, templateId = templateId)
}

fun upaNodeText(node: UpaNode, key: String): String =
    (node.props[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

fun upaNodeFlag(node: UpaNode, key: String, default: Boolean = true): Boolean =
    (node.props[key] as? JsonPrimitive)?.booleanOrNull ?: default

fun defaultUpaProp(type: String): String = when (type) {
    "row" -> "subtitle"
    "action" -> "label"
    else -> "text"
}

fun applyUpaFields(
    root: UpaNode,
    template: UpaTemplate,
    fields: Map<String, String>,
): UpaNode {
    val byNodeId = template.fields.groupBy { field -> field.nodeId.ifBlank { field.id } }
    fun walk(node: UpaNode): UpaNode {
        val props = node.props.toMutableMap()
        val bindId = node.bind
        if (bindId.isNotBlank()) {
            fields[bindId]?.let { value ->
                val prop = template.fields.firstOrNull { it.id == bindId }?.prop
                    ?.ifBlank { null }
                    ?: defaultUpaProp(node.type)
                props[prop] = JsonPrimitive(value)
            }
        }
        byNodeId[node.id].orEmpty().forEach { field ->
            val value = fields[field.id] ?: return@forEach
            val prop = field.prop.ifBlank { defaultUpaProp(node.type) }
            props[prop] = JsonPrimitive(value)
        }
        return node.copy(
            props = JsonObject(props),
            children = node.children.map(::walk),
        )
    }
    return walk(root)
}

fun pruneUpaTreeToFields(root: UpaNode, template: UpaTemplate): UpaNode {
    val keep = template.fields.map { field -> field.nodeId.ifBlank { field.id } }.toSet()
    if (keep.isEmpty()) return root
    fun interactive(node: UpaNode): Boolean =
        node.type == "action" || node.events.isNotEmpty()
    fun needed(node: UpaNode): Boolean =
        node.id in keep || interactive(node) || node.children.any(::needed)
    fun walk(node: UpaNode): UpaNode =
        node.copy(children = node.children.filter(::needed).map(::walk))
    return walk(root)
}

fun upaNodeProps(vararg pairs: Pair<String, String>): JsonObject =
    JsonObject(pairs.filter { it.second.isNotBlank() || it.first == "text" || it.first == "src" || it.first == "href" || it.first == "label" || it.first == "subtitle" || it.first == "title" }.associate { (key, value) -> key to JsonPrimitive(value) })

fun foodOrderLoginTree(title: String, hint: String, loginUrl: String, label: String): UpaNode =
    UpaNode(
        id = "root",
        type = "group",
        children = listOfNotNull(
            UpaNode(id = "title", type = "text", props = upaNodeProps("text" to title, "role" to "title")),
            hint.takeIf { it.isNotBlank() }?.let {
                UpaNode(id = "hint", type = "text", props = upaNodeProps("text" to it, "role" to "caption"))
            },
            UpaNode(
                id = "login-btn",
                type = "action",
                props = upaNodeProps("label" to label, "href" to loginUrl),
                events = mapOf("click" to "open:$loginUrl"),
            ),
        ),
    )

fun foodOrderAccountTree(title: String, accountName: String, brand: String = ""): UpaNode =
    UpaNode(
        id = "root",
        type = "group",
        children = listOf(
            UpaNode(id = "title", type = "text", props = upaNodeProps("text" to title, "role" to "title")),
            UpaNode(id = "account", type = "text", props = upaNodeProps("text" to accountName)),
            UpaNode(
                id = "logout-btn",
                type = "action",
                props = upaNodeProps("label" to "退出登录"),
                events = mapOf("click" to "food.logout:${brand.ifBlank { "all" }}"),
            ),
        ),
    )

fun foodOrderProductsTree(
    title: String,
    hint: String,
    items: List<Pair<String, String>>,
): UpaNode =
    UpaNode(
        id = "root",
        type = "group",
        children = buildList {
            add(UpaNode(id = "title", type = "text", props = upaNodeProps("text" to title, "role" to "title")))
            if (hint.isNotBlank()) {
                add(UpaNode(id = "hint", type = "text", props = upaNodeProps("text" to hint, "role" to "caption")))
            }
            items.forEachIndexed { index, (name, price) ->
                add(
                    UpaNode(
                        id = "item$index",
                        type = "text",
                        props = upaNodeProps("text" to name, "role" to "title"),
                    ),
                )
                if (price.isNotBlank()) {
                    add(
                        UpaNode(
                            id = "item$index-meta",
                            type = "row",
                            props = upaNodeProps("title" to "价格", "subtitle" to price),
                        ),
                    )
                }
            }
        },
    )

fun jsonMatchValuePresent(payload: JsonObject, key: String): Boolean {
    val value = jsonPointer(payload, "/$key") ?: payload[key] ?: return false
    if (value is JsonNull) return false
    if (value is JsonPrimitive) {
        if (value.booleanOrNull == false) return false
        if (value.content.isBlank()) return false
    }
    if (value is JsonArray && value.isEmpty()) return false
    if (value is JsonObject && value.isEmpty()) return false
    return true
}

fun applyUpaTransition(
    root: UpaNode,
    template: UpaTemplate,
    name: String,
): UpaNode {
    val ops = template.transitions[name] as? JsonArray ?: return root
    var current = root
    ops.forEach { element ->
        val op = element as? JsonObject ?: return@forEach
        if (op["op"]?.jsonPrimitive?.contentOrNull != "replace") return@forEach
        val path = op["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val value = op["value"] ?: return@forEach
        current = replaceUpaPath(current, path, value)
    }
    return current
}

fun resolveUpaSurfaceTree(
    manifest: UpaManifest,
    payload: JsonObject,
    templateId: String = "",
): Pair<UpaTemplate, UpaNode>? {
    if (!manifest.declaresUiContent()) return null
    val template = matchUpaTemplate(manifest.templates, payload, templateId = templateId)
        ?: return null
    val surface = manifest.surfaces.firstOrNull { it.id == template.surface }
        ?: manifest.surfaces.firstOrNull()
    val tree = template.tree ?: surface?.tree ?: return null
    val fields = bindUpaTemplateFields(template, payload)
    if (fields.isEmpty()) return null
    return template to pruneUpaTreeToFields(applyUpaFields(tree, template, fields), template)
}

private fun replaceUpaPath(node: UpaNode, path: String, value: JsonElement): UpaNode {
    val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
    if (parts.isEmpty()) return node
    return replaceUpaWalk(node, parts, value)
}

private fun replaceUpaWalk(node: UpaNode, parts: List<String>, value: JsonElement): UpaNode {
    if (parts.isEmpty()) return node
    return when (parts[0]) {
        "children" -> {
            if (parts.size < 2) return node
            val childId = parts[1]
            val rest = parts.drop(2)
            node.copy(
                children = node.children.map { child ->
                    if (child.id != childId) child
                    else if (rest.isEmpty()) child
                    else replaceUpaWalk(child, rest, value)
                },
            )
        }
        "props" -> {
            if (parts.size < 2) return node
            val props = node.props.toMutableMap()
            props[parts[1]] = value
            node.copy(props = JsonObject(props))
        }
        else -> node
    }
}

fun globMatches(pattern: String, value: String): Boolean {
    val needle = pattern.trim()
    if (needle.isBlank() || needle == "*") return true
    if (!needle.contains('*') && !needle.contains('?')) {
        return needle.equals(value, ignoreCase = true)
    }
    val regex = buildString {
        append('^')
        needle.forEach { ch ->
            when (ch) {
                '*' -> append(".*")
                '?' -> append('.')
                '.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|' -> {
                    append('\\')
                    append(ch)
                }
                else -> append(ch)
            }
        }
        append('$')
    }
    return Regex(regex, RegexOption.IGNORE_CASE).matches(value)
}

fun matchesUpaMcpBind(
    bind: UpaMcpBind,
    serverId: String,
    toolName: String,
    mimeType: String,
    payloadKeys: Set<String>,
): Boolean {
    val serverAliases = listOf(serverId, serverId.substringAfterLast(':'), serverId.substringAfterLast('/'))
        .filter { it.isNotBlank() }
        .distinct()
    val serversOk = bind.servers.isEmpty() ||
        bind.servers.any { pattern -> serverAliases.any { alias -> globMatches(pattern, alias) } }
    val toolAliases = listOf(
        toolName,
        toolName.substringAfterLast('/'),
        toolName.substringAfterLast(':'),
    ).filter { it.isNotBlank() }.distinct()
    val toolsOk = bind.tools.isEmpty() ||
        bind.tools.any { pattern -> toolAliases.any { alias -> globMatches(pattern, alias) } }
    val mimeOk = bind.mimeTypes.isEmpty() ||
        mimeType.isBlank() ||
        bind.mimeTypes.any { it.equals(mimeType, ignoreCase = true) }
    val requiredKeys = bind.match?.keys.orEmpty()
    val keysOk = requiredKeys.isEmpty() || requiredKeys.all { key -> key in payloadKeys }
    return serversOk && toolsOk && mimeOk && keysOk
}

fun jsonObjectKeys(payload: JsonObject): Set<String> = payload.keys

fun extractToolResultMime(payload: JsonObject): String {
    payload["mimeType"]?.let { (it as? JsonPrimitive)?.contentOrNull }?.takeIf { it.isNotBlank() }?.let { return it }
    val content = payload["content"] as? JsonArray ?: return ""
    content.forEach { item ->
        val obj = item as? JsonObject ?: return@forEach
        obj["mimeType"]?.let { (it as? JsonPrimitive)?.contentOrNull }?.takeIf { it.isNotBlank() }?.let { return it }
        obj["resource"]?.let { resource ->
            val nested = resource as? JsonObject ?: return@let
            nested["mimeType"]?.let { (it as? JsonPrimitive)?.contentOrNull }?.takeIf { it.isNotBlank() }?.let { return it }
        }
    }
    return ""
}

