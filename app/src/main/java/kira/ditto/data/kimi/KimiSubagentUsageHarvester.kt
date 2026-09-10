package kira.ditto.data.kimi

import java.io.File
import kira.ditto.data.KimiBrowserSubagentProfileName
import kira.ditto.data.KimiImageSubagentProfileName
import kira.ditto.data.KimiPhoneSubagentProfileName
import kira.ditto.data.LlmUsageSources
import org.json.JSONObject

data class KimiAgentWire(
    val kimiSessionId: String,
    val agentId: String,
    val wireFile: File,
    val source: String,
)

object KimiSubagentUsageHarvester {
    private const val MainAgentId = "main"
    private const val ClassifyPeekBytes = 256 * 1024

    /**
     * Every agent's wire file, main agent included.
     *
     * The main agent used to be filtered out here on the assumption that the main turn's usage
     * arrives with the ACP `session/prompt` response. It does not - `parseUsage` finds no `usage`
     * object on that response, so every main turn was recorded as an estimate while the subagents
     * beside it had real provider numbers. The main agent writes the same `usage.record` lines as
     * any other agent; nothing was missing except a call to read them.
     */
    fun listAgentWires(
        sessionsRoot: File,
        kimiSessionId: String? = null,
        includeMain: Boolean = true,
    ): List<KimiAgentWire> {
        if (!sessionsRoot.isDirectory) return emptyList()
        val sessionDirs = if (kimiSessionId.isNullOrBlank()) {
            listSessionDirectories(sessionsRoot)
        } else {
            findSessionDirectory(sessionsRoot, kimiSessionId)?.let(::listOf).orEmpty()
        }
        return sessionDirs.flatMap { sessionDir ->
            val sessionId = sessionDir.name
            val agentsDir = File(sessionDir, "agents")
            if (!agentsDir.isDirectory) return@flatMap emptyList()
            val state = readStateJson(sessionDir)
            agentsDir.listFiles()
                .orEmpty()
                .filter { dir ->
                    if (!dir.isDirectory) return@filter false
                    if (dir.name.equals(MainAgentId, ignoreCase = true)) includeMain else true
                }
                .mapNotNull { agentDir ->
                    val wire = File(agentDir, "wire.jsonl")
                    if (!wire.isFile) return@mapNotNull null
                    val isMain = agentDir.name.equals(MainAgentId, ignoreCase = true)
                    KimiAgentWire(
                        kimiSessionId = sessionId,
                        agentId = agentDir.name,
                        wireFile = wire,
                        // The main agent is the conversation turn itself; classifySource's
                        // profile/peek heuristics are about telling subagents apart and would only
                        // mislabel it.
                        source = if (isMain) {
                            LlmUsageSources.Turn
                        } else {
                            classifySource(agentDir.name, state, wire)
                        },
                    )
                }
        }
    }

    fun listNonMainAgentWires(
        sessionsRoot: File,
        kimiSessionId: String? = null,
    ): List<KimiAgentWire> {
        if (!sessionsRoot.isDirectory) return emptyList()
        val sessionDirs = if (kimiSessionId.isNullOrBlank()) {
            listSessionDirectories(sessionsRoot)
        } else {
            findSessionDirectory(sessionsRoot, kimiSessionId)?.let(::listOf).orEmpty()
        }
        return sessionDirs.flatMap { sessionDir ->
            val sessionId = sessionDir.name
            val agentsDir = File(sessionDir, "agents")
            if (!agentsDir.isDirectory) return@flatMap emptyList()
            val state = readStateJson(sessionDir)
            agentsDir.listFiles()
                .orEmpty()
                .filter { it.isDirectory && !it.name.equals(MainAgentId, ignoreCase = true) }
                .mapNotNull { agentDir ->
                    val wire = File(agentDir, "wire.jsonl")
                    if (!wire.isFile) return@mapNotNull null
                    KimiAgentWire(
                        kimiSessionId = sessionId,
                        agentId = agentDir.name,
                        wireFile = wire,
                        source = classifySource(agentDir.name, state, wire),
                    )
                }
        }
    }

    fun findSessionDirectory(sessionsRoot: File, kimiSessionId: String): File? {
        if (kimiSessionId.isBlank() || !sessionsRoot.isDirectory) return null
        sessionsRoot.listFiles().orEmpty().forEach { workDir ->
            if (!workDir.isDirectory) return@forEach
            val sessionDir = File(workDir, kimiSessionId)
            if (sessionDir.isDirectory) return sessionDir
        }
        return null
    }

    fun listSessionDirectories(sessionsRoot: File): List<File> {
        if (!sessionsRoot.isDirectory) return emptyList()
        return sessionsRoot.listFiles().orEmpty()
            .filter { it.isDirectory }
            .flatMap { workDir ->
                workDir.listFiles().orEmpty().filter { child ->
                    child.isDirectory && File(child, "agents").isDirectory
                }
            }
    }

    internal fun classifySource(agentId: String, state: JSONObject?, wireFile: File): String {
        val profile = profileName(state, agentId)
        when {
            profile.equals(KimiPhoneSubagentProfileName, ignoreCase = true) ->
                return LlmUsageSources.Phone
            profile.equals(KimiBrowserSubagentProfileName, ignoreCase = true) ->
                return LlmUsageSources.Browser
            profile.equals(KimiImageSubagentProfileName, ignoreCase = true) ->
                return LlmUsageSources.Image
        }
        val peek = peekWire(wireFile)
        return when {
            peekLooksLikePhone(peek) -> LlmUsageSources.Phone
            peekLooksLikeImage(peek) -> LlmUsageSources.Image
            peekLooksLikeBrowser(peek) -> LlmUsageSources.Browser
            else -> LlmUsageSources.Subagent
        }
    }

    internal fun profileName(state: JSONObject?, agentId: String): String? {
        if (state == null || agentId.isBlank()) return null
        val agents = state.optJSONObject("agents") ?: return null
        val meta = agents.optJSONObject(agentId) ?: return null
        val nested = listOfNotNull(meta, meta.optJSONObject("metadata"), meta.optJSONObject("custom"))
        nested.forEach { bag ->
            sequenceOf("profileName", "profile", "name", "agentType", "subagent_type", "type")
                .map { bag.optString(it).trim() }
                .firstOrNull { it.isNotBlank() && !it.equals("sub", ignoreCase = true) }
                ?.let { return it }
        }
        return null
    }

    private fun readStateJson(sessionDir: File): JSONObject? {
        val file = File(sessionDir, "state.json")
        if (!file.isFile) return null
        return runCatching { JSONObject(file.readText()) }.getOrNull()
    }

    private fun peekWire(wireFile: File): String {
        if (!wireFile.isFile) return ""
        return wireFile.inputStream().use { stream ->
            val buffer = ByteArray(ClassifyPeekBytes)
            val read = stream.read(buffer)
            if (read <= 0) "" else String(buffer, 0, read, Charsets.UTF_8)
        }
    }

    private fun peekLooksLikePhone(peek: String): Boolean =
        peek.contains("mcp__phone_app") ||
            peek.contains("mcp__agent_display") ||
            containsSubagentType(peek, "phone")

    private fun peekLooksLikeImage(peek: String): Boolean {
        if (containsSubagentType(peek, "image")) return true
        if (!peek.contains("mcp__webmcp__search_images")) return false
        return !peek.containsOtherWebmcpTools()
    }

    private fun peekLooksLikeBrowser(peek: String): Boolean =
        containsSubagentType(peek, "browser") ||
            peek.contains("tabs_navigate") ||
            peek.contains("mcp__webmcp__")

    private fun String.containsOtherWebmcpTools(): Boolean {
        var index = 0
        while (true) {
            val found = indexOf("mcp__webmcp__", index)
            if (found < 0) return false
            if (!regionMatches(found, "mcp__webmcp__search_images", 0, "mcp__webmcp__search_images".length)) {
                return true
            }
            index = found + "mcp__webmcp__".length
        }
    }

    private fun containsSubagentType(peek: String, type: String): Boolean =
        peek.contains("\"subagent_type\":\"$type\"") ||
            peek.contains("\"subagent_type\": \"$type\"")
}
