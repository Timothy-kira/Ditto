package kira.ditto.data

import android.content.Context
import kira.ditto.upa.UpaManifest
import kira.ditto.upa.UpaManifestResult
import kira.ditto.upa.inAppDisplayName
import kira.ditto.upa.inAppIcon
import kira.ditto.upa.parseUpaManifest
import kira.ditto.upa.resolveUpaDisplayName
import kira.ditto.upa.resolveUpaIcon
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class UpaPluginLibrary(
    context: Context,
) {
    internal val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "upa-plugins")
    private val indexFile = File(root, "index.json")
    private val lock = Mutex()
    private val manifestCache = java.util.concurrent.ConcurrentHashMap<String, UpaManifest>()

    suspend fun list(): List<InstalledUpaPlugin> = withContext(Dispatchers.IO) {
        lock.withLock { readIndexLocked() }
    }

    suspend fun install(
        plugin: InstalledUpaPlugin,
        rawJson: String,
        extraFiles: Map<String, String> = emptyMap(),
    ): InstalledUpaPlugin = withContext(Dispatchers.IO) {
        lock.withLock {
            root.mkdirs()
            val dir = File(root, plugin.id)
            dir.mkdirs()
            File(dir, "mcp").deleteRecursively()
            File(dir, "upa.json").writeText(rawJson)
            extraFiles.forEach { (relative, contents) ->
                val target = File(dir, relative)
                target.parentFile?.mkdirs()
                target.writeText(contents)
            }
            manifestCache.remove(plugin.id)
            val next = readIndexLocked().filterNot { it.id == plugin.id } + plugin
            writeIndexLocked(next.sortedBy { it.name.lowercase() })
            plugin
        }
    }

    fun pluginDirectory(id: String): File = File(root, id)

    suspend fun readManifest(id: String): UpaManifest? = withContext(Dispatchers.IO) {
        readManifestBlocking(id)
    }

    fun readManifestBlocking(id: String): UpaManifest? {
        manifestCache[id]?.let { return it }
        val file = File(File(root, id), "upa.json")
        if (!file.isFile) return null
        val parsed = parseUpaManifest(file.readText())
        val manifest = (parsed as? UpaManifestResult.Ok)?.manifest ?: return null
        manifestCache[id] = manifest
        return manifest
    }

    fun installedManifestsBlocking(): List<UpaManifest> {
        if (!root.isDirectory) return emptyList()
        return root.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { readManifestBlocking(it.name) }
            .orEmpty()
    }

    suspend fun exportedMcpServers(): List<McpServerConfig> = emptyList()

    suspend fun uninstall(id: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            File(root, id).deleteRecursively()
            manifestCache.remove(id)
            writeIndexLocked(readIndexLocked().filterNot { it.id == id })
        }
    }

    private fun readIndexLocked(): List<InstalledUpaPlugin> {
        if (!indexFile.isFile) return emptyList()
        val raw = runCatching { indexFile.readText() }.getOrNull().orEmpty()
        if (raw.isBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            runCatching { array.getJSONObject(index).toInstalled() }.getOrNull()
        }
    }

    private fun writeIndexLocked(plugins: List<InstalledUpaPlugin>) {
        root.mkdirs()
        val array = JSONArray()
        plugins.forEach { array.put(it.toJson()) }
        indexFile.writeText(array.toString())
    }

    private fun InstalledUpaPlugin.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("version", version)
        .put("description", description)
        .put("source", source)
        .put("installedAtEpochMs", installedAtEpochMs)
        .put("everMeTrajectory", everMeTrajectory)
        .put("warningCount", warningCount)
        .put("toolNames", JSONArray(toolNames))
        .put("displayName", displayName)
        .put("icon", icon)
        .put("releasedAt", releasedAt)
        .put("sourceRaw", sourceRaw)

    private fun JSONObject.toInstalled(): InstalledUpaPlugin {
        val tools = optJSONArray("toolNames") ?: JSONArray()
        val pluginId = getString("id")
        val manifest = readManifestBlocking(pluginId)
        return InstalledUpaPlugin(
            id = pluginId,
            name = optString("name").ifBlank { manifest?.name.orEmpty() },
            version = optString("version"),
            description = optString("description"),
            source = optString("source"),
            installedAtEpochMs = optLong("installedAtEpochMs"),
            everMeTrajectory = optBoolean("everMeTrajectory"),
            toolNames = (0 until tools.length()).map { tools.optString(it) }.filter { it.isNotBlank() },
            warningCount = optInt("warningCount"),
            displayName = resolveUpaDisplayName(
                pluginId,
                optString("name").ifBlank { manifest?.name.orEmpty() },
                optString("displayName").ifBlank { manifest?.displayName.orEmpty() },
            ),
            icon = resolveUpaIcon(
                pluginId,
                optString("icon").ifBlank { manifest?.icon.orEmpty() },
            ),
            releasedAt = optString("releasedAt").ifBlank { manifest?.releasedAt.orEmpty() },
            sourceRaw = optString("sourceRaw"),
        )
    }
}
