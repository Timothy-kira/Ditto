package kira.ditto.data.kimi

import kira.ditto.data.AgentModeLeadReminder
import kira.ditto.data.kimiPromptHasDeskLead
import kira.ditto.data.visibleUserMessageText
import org.json.JSONArray
import org.json.JSONObject

private val FileMentionRegex =
    Regex("""(?<=^|\s)@(?:"([^"]+)"|'([^']+)'|([^\s@]+))""")

data class MappedAcpEvent(
    val name: String,
    val payload: JSONObject,
)

/** A diff content block carried by tool_call / tool_call_update updates. */
data class AcpDiffBlock(
    val path: String,
    val oldText: String,
    val newText: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("path", path)
        .put("oldText", oldText)
        .put("newText", newText)
}

data class AcpPlanEntry(
    val content: String,
    val priority: String,
    val status: String,
)

data class AcpAvailableCommand(
    val name: String,
    val description: String,
    val raw: JSONObject,
)

data class AcpListedSession(
    val sessionId: String,
    val title: String,
    val cwd: String,
    val updatedAtMillis: Long? = null,
    /** True when kimi-code stored this as a subagent/fork child of another session. */
    val isChild: Boolean = false,
)

data class AcpToolCallInfo(
    val id: String,
    val title: String,
    val kind: String,
    val status: String,
    val isUpdate: Boolean,
    val rawInput: Any?,
    val output: Any?,
    val diffs: List<AcpDiffBlock>,
)

/**
 * Complete model of every session/update kind emitted by kimi acp. Unknown kinds
 * are preserved verbatim via [AcpSessionEvent.Unknown] so the protocol layer is
 * forward-compatible instead of silently dropping new update types.
 */
sealed class AcpSessionEvent {
    abstract val kind: String

    data class AgentMessageChunk(val text: String, val content: JSONObject?) : AcpSessionEvent() {
        override val kind: String = "agent_message_chunk"
    }

    data class AgentThoughtChunk(val text: String, val content: JSONObject?) : AcpSessionEvent() {
        override val kind: String = "agent_thought_chunk"
    }

    data class UserMessageChunk(val text: String, val content: JSONObject?) : AcpSessionEvent() {
        override val kind: String = "user_message_chunk"
    }

    data class ToolCall(val info: AcpToolCallInfo) : AcpSessionEvent() {
        override val kind: String = if (info.isUpdate) "tool_call_update" else "tool_call"
    }

    data class Plan(val entries: List<AcpPlanEntry>) : AcpSessionEvent() {
        override val kind: String = "plan"
    }

    data class AvailableCommands(val commands: List<AcpAvailableCommand>) : AcpSessionEvent() {
        override val kind: String = "available_commands_update"
    }

    data class CurrentMode(val modeId: String) : AcpSessionEvent() {
        override val kind: String = "current_mode_update"
    }

    /** Raw config option payload (model/thinking/mode changes). */
    data class ConfigOptions(val raw: JSONObject) : AcpSessionEvent() {
        override val kind: String = "config_option_update"
    }

    data class Usage(val used: Long, val size: Long) : AcpSessionEvent() {
        override val kind: String = "usage_update"
    }

    data class SessionInfo(val title: String?) : AcpSessionEvent() {
        override val kind: String = "session_info_update"
    }

    data class Unknown(val unknownKind: String, val raw: JSONObject) : AcpSessionEvent() {
        override val kind: String = unknownKind
    }

    /**
     * Projects this event onto the legacy (name, payload) channel consumed by the
     * UI. Returns null only for intentionally empty no-op updates (blank text
     * deltas); every update kind maps to an event.
     */
    fun toMappedEvent(): MappedAcpEvent? = when (this) {
        is AgentMessageChunk -> chunkEvent("assistant_text_delta", text, content)
        is AgentThoughtChunk -> chunkEvent("assistant_reasoning_delta", text, content)
        is UserMessageChunk -> chunkEvent("user_message_chunk", text, content)
        is ToolCall -> {
            // ACP tool_call_update carries only status/output — title and
            // rawInput are present on the initial tool_call only. Do NOT
            // fabricate fallbacks here: blank fields let the execution layer
            // preserve the values from the start event instead of overwriting
            // them with "tool"/{} (which broke subagent capsule parsing).
            val payload = JSONObject().apply {
                put("id", info.id)
                put("name", info.title)
                info.rawInput?.let { put("arguments", it) }
                put("kind", info.kind)
                put("status", info.status)
                info.output?.let { put("output", it) }
                if (info.diffs.isNotEmpty()) {
                    put("diffs", JSONArray().apply { info.diffs.forEach { put(it.toJson()) } })
                }
            }
            val name = when (info.status) {
                "completed", "failed" -> "tool_call_end"
                else -> "tool_call_start"
            }
            MappedAcpEvent(name, payload)
        }
        is Plan -> MappedAcpEvent(
            "plan_update",
            JSONObject().put(
                "entries",
                JSONArray().apply {
                    entries.forEach { entry ->
                        put(
                            JSONObject()
                                .put("content", entry.content)
                                .put("priority", entry.priority)
                                .put("status", entry.status),
                        )
                    }
                },
            ),
        )
        is AvailableCommands -> MappedAcpEvent(
            "available_commands_update",
            JSONObject().put(
                "commands",
                JSONArray().apply { commands.forEach { put(it.raw) } },
            ),
        )
        is CurrentMode -> MappedAcpEvent(
            "current_mode_update",
            JSONObject().put("modeId", modeId),
        )
        is ConfigOptions -> MappedAcpEvent(
            "config_option_update",
            JSONObject().put("update", raw),
        )
        is Usage -> MappedAcpEvent(
            "usage_update",
            JSONObject().put("used", used).put("size", size),
        )
        is SessionInfo -> MappedAcpEvent(
            "session_info_update",
            JSONObject().put("title", title ?: JSONObject.NULL),
        )
        is Unknown -> MappedAcpEvent(
            "unknown_session_update",
            JSONObject().put("kind", unknownKind).put("update", raw),
        )
    }

    private fun chunkEvent(name: String, text: String, content: JSONObject?): MappedAcpEvent? {
        val nonTextContent = content?.takeIf { it.optString("type") != "text" }
        if (text.isEmpty() && nonTextContent == null) return null
        return MappedAcpEvent(
            name,
            JSONObject().apply {
                put("delta", text)
                nonTextContent?.let { put("content", it) }
            },
        )
    }
}

data class AcpPermissionOption(
    val optionId: String,
    val name: String,
    val kind: String,
)

data class AcpPermissionRequest(
    val sessionId: String,
    val toolCall: JSONObject?,
    val options: List<AcpPermissionOption>,
    val raw: JSONObject,
)

data class AcpElicitationOption(
    val value: String,
    val label: String,
)

data class AcpElicitationQuestion(
    val id: String,
    /** Short header (the CLI sends `header ?? question`); falls back to [body]. */
    val title: String,
    /** Full question body (schema `description`); blank when the CLI sent none. */
    val body: String,
    val multiSelect: Boolean,
    val required: Boolean,
    val options: List<AcpElicitationOption>,
    val schema: JSONObject,
) {
    /** Longest available question text for display above the options. */
    val displayText: String
        get() = body.ifBlank { title }.ifBlank { id }
}

data class AcpElicitationRequest(
    val sessionId: String,
    val message: String,
    val schema: JSONObject,
    val questions: List<AcpElicitationQuestion>,
    val raw: JSONObject,
)

object KimiAcpProtocol {
    const val PROTOCOL_VERSION = 1
    const val CLIENT_NAME = "Ditto"

    fun clientCapabilities(): JSONObject =
        JSONObject()
            .put("terminal", true)
            .put(
                "fs",
                JSONObject()
                    .put("readTextFile", true)
                    .put("writeTextFile", true),
            )
            .put("elicitation", JSONObject().put("form", true))

    /**
     * ACP `session/prompt` is incremental: only this turn's user content is sent.
     * Persona instructions and RAG slices are hidden user messages that appear
     * after the last assistant turn, so they must be merged with the visible
     * question instead of taking only the last user message.
     */
    fun turnUserContent(messages: JSONArray?): JSONArray {
        if (messages == null || messages.length() == 0) return JSONArray()
        var lastAssistantIndex = -1
        for (index in 0 until messages.length()) {
            if (messages.optJSONObject(index)?.optString("role") == "assistant") {
                lastAssistantIndex = index
            }
        }
        val combined = JSONArray()
        for (index in lastAssistantIndex + 1 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            if (message.optString("role") != "user") continue
            appendUserContentParts(combined, message)
        }
        if (combined.length() > 0) return combined
        for (index in messages.length() - 1 downTo 0) {
            val message = messages.optJSONObject(index) ?: continue
            if (message.optString("role") != "user") continue
            val fallback = JSONArray()
            appendUserContentParts(fallback, message)
            return fallback
        }
        return JSONArray()
    }

    private fun appendUserContentParts(target: JSONArray, message: JSONObject) {
        val source = message.optJSONArray("content")
        if (source == null) {
            val text = message.optString("text")
            if (text.isNotEmpty()) {
                target.put(JSONObject().put("type", "text").put("text", text))
            }
            return
        }
        for (partIndex in 0 until source.length()) {
            val part = source.optJSONObject(partIndex) ?: continue
            when (part.optString("type")) {
                "text" -> {
                    val text = part.optString("text")
                    if (text.isNotEmpty()) {
                        target.put(JSONObject().put("type", "text").put("text", text))
                    }
                }
                "image" -> target.put(
                    JSONObject()
                        .put("type", "image")
                        .put(
                            "source",
                            JSONObject()
                                .put("kind", "base64")
                                .put("media_type", part.optString("mime_type").ifBlank {
                                    part.optJSONObject("source")?.optString("media_type").orEmpty()
                                })
                                .put(
                                    "data",
                                    part.optString("data").ifBlank {
                                        part.optJSONObject("source")?.optString("data").orEmpty()
                                    },
                                ),
                        ),
                )
                "resource_link" -> {
                    val uri = part.optString("uri")
                    if (uri.isNotBlank()) {
                        target.put(
                            JSONObject()
                                .put("type", "resource_link")
                                .put("uri", uri)
                                .put(
                                    "name",
                                    part.optString("name").ifBlank {
                                        uri.substringAfterLast('/').ifBlank { uri }
                                    },
                                ),
                        )
                    }
                }
            }
        }
    }

    fun firstTextBlock(prompt: JSONArray): String {
        for (index in 0 until prompt.length()) {
            val part = prompt.optJSONObject(index) ?: continue
            if (part.optString("type") != "text") continue
            val text = part.optString("text")
            if (text.isNotEmpty()) return text
        }
        return ""
    }

    /**
     * Agent Mode desk-lead is injected once on a brand-new ACP session.
     * Browser SOP lives on the `browser` subagent profile and is loaded
     * only when that agent is spawned — never prepended to the parent prompt.
     */
    fun ensureDeskLeadPromptBlocks(
        prompt: JSONArray,
        createdNewSession: Boolean,
        agentModeEnabled: Boolean,
        fallbackLead: String = AgentModeLeadReminder,
    ): JSONArray {
        if (!agentModeEnabled) return prompt
        if (kimiPromptHasDeskLead(firstTextBlock(prompt))) return prompt
        if (!createdNewSession) return prompt
        val lead = fallbackLead
        if (lead.isBlank()) return prompt
        val out = JSONArray()
        out.put(JSONObject().put("type", "text").put("text", lead))
        for (index in 0 until prompt.length()) {
            out.put(prompt.get(index))
        }
        return out
    }

    internal const val RestoredHistoryLead =
        "The previous live session was not available. Continue this chat from the recent turns below. Do not greet as if this is a new conversation."

    private const val RestoreKeepUserTurns = 3
    private const val RestoreToolStubChars = 12_000

    /**
     * The recent turns of a conversation whose live session is gone, as plain text.
     *
     * Recent turns only. Older wording comes back through EverMe recall and `read_original`.
     * This path must not invent node ids: a second id space here would point at nothing.
     */
    fun priorConversationCompactText(
        messages: JSONArray?,
        maxChars: Int = Int.MAX_VALUE,
    ): String {
        if (messages == null || messages.length() == 0) return ""
        var lastAssistantIndex = -1
        for (index in 0 until messages.length()) {
            if (messages.optJSONObject(index)?.optString("role") == "assistant") {
                lastAssistantIndex = index
            }
        }
        if (lastAssistantIndex < 0) return ""
        val closed = ArrayList<JSONObject>()
        for (index in 0..lastAssistantIndex) {
            closed += messages.optJSONObject(index) ?: continue
        }
        val turns = splitRestoreTurns(closed)
        if (turns.isEmpty()) return ""
        val keepCount = RestoreKeepUserTurns.coerceAtMost(turns.size)
        val kept = turns.takeLast(keepCount)
        val sections = ArrayList<String>()
        if (turns.size > keepCount) {
            // Said, rather than tabulated. Older turns are in EverMe; read_original plus the
            // aether_hash recovers the exact wording without inventing ids.
            sections += "${turns.size - keepCount} earlier turns are not shown here; " +
                "EverMe recall reaches them, and read_original plus the aether_hash recovers the exact wording."
        }
        kept.forEach { turn ->
            turn.forEach { message ->
                val label = when (message.optString("role")) {
                    "user" -> "User"
                    "assistant" -> "Assistant"
                    else -> "Tool"
                }
                val text = stubRestoreText(messageTextForCompact(message))
                if (text.isNotBlank()) sections += "$label: $text"
            }
        }
        val body = sections.joinToString("\n\n")
        if (maxChars != Int.MAX_VALUE && body.length > maxChars) {
            return "…" + body.takeLast(maxChars - 1)
        }
        return body
    }

    fun ensureRestoredHistoryPromptBlocks(
        prompt: JSONArray,
        messages: JSONArray?,
        createdNewSession: Boolean,
    ): JSONArray {
        if (!createdNewSession) return prompt
        val compact = priorConversationCompactText(messages)
        if (compact.isBlank()) return prompt
        val out = JSONArray()
        out.put(JSONObject().put("type", "text").put("text", "$RestoredHistoryLead\n\n$compact"))
        for (index in 0 until prompt.length()) {
            out.put(prompt.get(index))
        }
        return out
    }

    private fun messageTextForCompact(message: JSONObject): String {
        val content = message.optJSONArray("content")
        val raw = if (content != null) {
            buildString {
                for (index in 0 until content.length()) {
                    val part = content.optJSONObject(index) ?: continue
                    if (part.optString("type") != "text") continue
                    val text = part.optString("text")
                    if (text.isEmpty()) continue
                    if (isNotEmpty()) append("\n")
                    append(text)
                }
            }
        } else {
            message.optString("text")
        }
        return if (message.optString("role") == "user") {
            visibleUserTextFromReplay(raw).ifBlank { raw.visibleUserMessageText() }
        } else {
            raw.trim()
        }
    }

    private fun splitRestoreTurns(messages: List<JSONObject>): List<List<JSONObject>> {
        val turns = ArrayList<MutableList<JSONObject>>()
        var current: MutableList<JSONObject>? = null
        for (message in messages) {
            if (message.optString("role") == "user") {
                val next = ArrayList<JSONObject>()
                turns += next
                next += message
                current = next
            } else {
                val bucket = current ?: ArrayList<JSONObject>().also { turns += it }
                bucket += message
                current = bucket
            }
        }
        return turns
    }

    private fun stubRestoreText(text: String): String {
        if (text.length <= RestoreToolStubChars) return text
        val preview = text.take(400).replace(Regex("""\s+"""), " ").trim()
        return "[Stored long turn text; ${text.length} chars]\npreview: $preview"
    }

    fun promptBlocksFromUserContent(content: JSONArray): JSONArray {
        val blocks = JSONArray()
        val seenLinkUris = linkedSetOf<String>()
        for (index in 0 until content.length()) {
            val part = content.optJSONObject(index) ?: continue
            when (part.optString("type")) {
                "text" -> {
                    val text = part.optString("text")
                    if (text.isNotEmpty()) {
                        blocks.put(JSONObject().put("type", "text").put("text", text))
                    }
                }
                "image" -> {
                    val source = part.optJSONObject("source")
                    val mimeType = source?.optString("media_type")
                        ?.ifBlank { null }
                        ?: part.optString("mimeType").ifBlank { part.optString("mime_type") }
                            .ifBlank { "image/png" }
                    val data = source?.optString("data")?.ifBlank { null }
                        ?: part.optString("data")
                    if (data.isNotBlank()) {
                        blocks.put(
                            JSONObject()
                                .put("type", "image")
                                .put("mimeType", mimeType)
                                .put("data", data),
                        )
                    }
                }
                "resource_link" -> {
                    val uri = normalizeFileUri(part.optString("uri"))
                    if (uri.isNotBlank() && seenLinkUris.add(uri)) {
                        blocks.put(
                            JSONObject()
                                .put("type", "resource_link")
                                .put("uri", uri)
                                .put(
                                    "name",
                                    part.optString("name").ifBlank {
                                        uri.substringAfterLast('/').ifBlank { uri }
                                    },
                                ),
                        )
                    }
                }
                "resource" -> {
                    val resource = part.optJSONObject("resource")
                    if (resource != null) {
                        blocks.put(JSONObject().put("type", "resource").put("resource", resource))
                    }
                }
            }
        }
        val originalLength = blocks.length()
        for (index in 0 until originalLength) {
            val block = blocks.optJSONObject(index) ?: continue
            if (block.optString("type") != "text") continue
            for (path in extractFileMentionPaths(block.optString("text"))) {
                val link = fileResourceLinkBlock(path)
                val uri = link.optString("uri")
                if (uri.isNotBlank() && seenLinkUris.add(uri)) {
                    blocks.put(link)
                }
            }
        }
        return blocks
    }

    /** Builds a resource_link prompt block for a workspace file (@-style reference). */
    fun fileResourceLinkBlock(path: String, name: String? = null): JSONObject {
        val normalized = path.trim().removePrefix("file://")
        val guestPath = when {
            normalized.startsWith("/") -> normalized
            else -> "/workspace/${normalized.trimStart('/')}"
        }
        return JSONObject()
            .put("type", "resource_link")
            .put("uri", "file://$guestPath")
            .put("name", name ?: guestPath.substringAfterLast('/').ifBlank { guestPath })
    }

    /** `@path` / `@"path with spaces"` tokens after whitespace or at start of text. */
    fun extractFileMentionPaths(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val matches = FileMentionRegex.findAll(text)
        return matches.mapNotNull { match ->
            match.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim()
        }.filter { it.isNotBlank() }.distinct().toList()
    }

    fun parseSessionList(result: JSONObject): List<AcpListedSession> {
        val sessions = result.optJSONArray("sessions")
            ?: result.optJSONArray("items")
            ?: JSONArray()
        return buildList {
            for (index in 0 until sessions.length()) {
                val item = sessions.optJSONObject(index) ?: continue
                val sessionId = jsonDisplayString(item, "sessionId", "id")
                if (sessionId.isBlank()) continue
                add(
                    AcpListedSession(
                        sessionId = sessionId,
                        title = jsonDisplayString(item, "title", "name"),
                        cwd = jsonDisplayString(item, "cwd"),
                        updatedAtMillis = sessionUpdatedAtMillis(item),
                        isChild = isChildListedSession(item),
                    ),
                )
            }
        }
    }

    /**
     * kimi-code marks subagent sessions with `parent_session_id` /
     * `child_session_kind=child` on summary.custom or summary.metadata.
     * ACP `session/list` currently projects only sessionId/cwd/title/updatedAt,
     * but keep reading nested bags and title prefixes so children stay out of
     * the conversation drawer if the wire shape grows.
     */
    fun isChildListedSession(item: JSONObject): Boolean {
        val nested = listOfNotNull(
            item,
            item.optJSONObject("_meta"),
            item.optJSONObject("metadata"),
            item.optJSONObject("custom"),
        )
        val parentId = nested.firstNotNullOfOrNull { bag ->
            jsonDisplayString(
                bag,
                "parent_session_id",
                "parentSessionId",
                "parentId",
                "parent_id",
            ).takeIf(::isUsableDisplayText)
        }.orEmpty()
        val childKind = nested.firstNotNullOfOrNull { bag ->
            jsonDisplayString(bag, "child_session_kind", "childSessionKind")
                .takeIf(::isUsableDisplayText)
        }.orEmpty()
        if (parentId.isNotBlank() || childKind.equals("child", ignoreCase = true)) {
            return true
        }
        val title = jsonDisplayString(item, "title", "name")
        return title.startsWith("Child: ") ||
            title.startsWith("Launching ") ||
            title.startsWith("Launching background ")
    }

    fun jsonDisplayString(item: JSONObject, vararg keys: String): String {
        keys.forEach { key ->
            if (!item.has(key) || item.isNull(key)) return@forEach
            val raw = item.opt(key)
            val text = when {
                raw == null || raw == JSONObject.NULL -> ""
                raw is String -> raw.trim()
                else -> raw.toString().trim()
            }
            if (isUsableDisplayText(text)) return text
        }
        return ""
    }

    fun isUsableDisplayText(value: String): Boolean {
        val text = value.trim()
        return text.isNotEmpty() &&
            !text.equals("null", ignoreCase = true) &&
            !text.equals("undefined", ignoreCase = true)
    }

    fun visibleUserTextFromReplay(text: String): String {
        val stripped = text.visibleUserMessageText()
        if (stripped.isEmpty()) return ""
        if (!looksLikeExpandedAgentPrompt(stripped) && !looksLikeExpandedAgentPrompt(text)) {
            return stripped
        }
        val source = stripped.ifBlank { text.trim() }
        val firstVisible = source
            .split(Regex("\n{2,}"))
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && !looksLikePromptScaffold(it) && it.length <= 800 }
            ?: source.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() && it.length <= 400 }
            .orEmpty()
        return firstVisible.visibleUserMessageText()
    }

    private fun looksLikeExpandedAgentPrompt(text: String): Boolean {
        val n = text.lowercase()
        if (
            n.contains("<system-reminder") ||
            n.contains("<plugin_session") ||
            n.contains("everme memory") ||
            n.contains("everme_profile")
        ) {
            return true
        }
        if (text.length < 600) return false
        val markers = listOf(
            "agents.md",
            "you are a",
            "<system",
            "available skills",
            "hidden instruction",
            "yolo mode",
            "# identity",
            "do not mention these instructions",
            "system prompt",
            "<skill",
            "permission mode",
            "working directory",
            "follow these instructions",
        )
        return markers.count { n.contains(it) } >= 2 || text.length > 4_000
    }

    private fun looksLikePromptScaffold(text: String): Boolean {
        val n = text.lowercase()
        return n.contains("agents.md") ||
            n.contains("<system") ||
            n.contains("available skills") ||
            n.contains("system prompt") ||
            n.contains("do not mention") ||
            n.contains("everme") ||
            n.contains("plugin_session") ||
            n.contains("agent mode is on") ||
            n.contains("you are the desk lead") ||
            n.startsWith("you are ") ||
            n.startsWith("# ")
    }

    fun parseSessionListCursor(result: JSONObject): String? =
        result.optString("nextCursor").ifBlank { result.optString("cursor") }.takeIf { it.isNotBlank() }

    private fun sessionUpdatedAtMillis(item: JSONObject): Long? {
        listOf("updatedAt", "updated_at", "mtime", "modifiedAt").forEach { key ->
            if (!item.has(key) || item.isNull(key)) return@forEach
            val numeric = item.optLong(key, Long.MIN_VALUE)
            if (numeric > 0L) {
                return if (numeric < 10_000_000_000L) numeric * 1000L else numeric
            }
            val text = item.optString(key)
            if (text.isNotBlank()) {
                val parsed = text.toLongOrNull()
                if (parsed != null && parsed > 0L) {
                    return if (parsed < 10_000_000_000L) parsed * 1000L else parsed
                }
            }
        }
        return null
    }

    private fun normalizeFileUri(uri: String): String {
        val trimmed = uri.trim()
        if (trimmed.isBlank()) return ""
        if (trimmed.startsWith("file://")) return trimmed
        val path = if (trimmed.startsWith("/")) trimmed else "/workspace/${trimmed.trimStart('/')}"
        return "file://$path"
    }

    fun sessionUpdateKind(update: JSONObject): String =
        update.optString("sessionUpdate").ifBlank { update.optString("session_update") }

    /**
     * TodoList often arrives as a tool_call (rawInput/content JSON or
     * display.kind=todo_list) rather than `sessionUpdate: plan`. Emit a plan
     * event so the top capsule can switch.
     */
    fun planUpdateFromToolCall(update: JSONObject): MappedAcpEvent? {
        val kind = sessionUpdateKind(update)
        if (kind != "tool_call" && kind != "tool_call_update") return null
        val entries = parseTodoToolPlanEntries(update)
        if (entries.isEmpty()) return null
        return AcpSessionEvent.Plan(entries).toMappedEvent()
    }

    /** Parses any session/update payload; unknown kinds are preserved, never dropped. */
    fun parseSessionUpdate(update: JSONObject): AcpSessionEvent =
        when (val kind = sessionUpdateKind(update)) {
            "agent_message_chunk" ->
                AcpSessionEvent.AgentMessageChunk(contentText(update.optJSONObject("content")), update.optJSONObject("content"))
            "agent_thought_chunk" ->
                AcpSessionEvent.AgentThoughtChunk(contentText(update.optJSONObject("content")), update.optJSONObject("content"))
            "user_message_chunk" ->
                AcpSessionEvent.UserMessageChunk(contentText(update.optJSONObject("content")), update.optJSONObject("content"))
            "tool_call" -> AcpSessionEvent.ToolCall(parseToolCallInfo(update, isUpdate = false))
            "tool_call_update" -> AcpSessionEvent.ToolCall(parseToolCallInfo(update, isUpdate = true))
            "plan", "plan_update" -> AcpSessionEvent.Plan(parseFlexiblePlanEntries(update))
            "available_commands_update" ->
                AcpSessionEvent.AvailableCommands(parseAvailableCommands(update.optJSONArray("availableCommands")))
            "current_mode_update" -> AcpSessionEvent.CurrentMode(
                update.optString("currentModeId").ifBlank { update.optString("modeId") },
            )
            "config_option_update" -> AcpSessionEvent.ConfigOptions(update)
            "usage_update" -> AcpSessionEvent.Usage(
                used = update.optLong("used", -1L),
                size = update.optLong("size", -1L),
            )
            "session_info_update" -> AcpSessionEvent.SessionInfo(
                if (update.has("title") && !update.isNull("title")) update.optString("title") else null,
            )
            else -> AcpSessionEvent.Unknown(kind, update)
        }

    fun mapSessionUpdate(update: JSONObject): MappedAcpEvent? =
        parseSessionUpdate(update).toMappedEvent()

    fun sessionUpdateText(update: JSONObject): String =
        update.optJSONObject("content")?.optString("text").orEmpty()

    fun isCronFireSessionUpdate(update: JSONObject): Boolean {
        if (update.optString("sessionUpdate") != "user_message_chunk") return false
        val text = sessionUpdateText(update)
        return text.contains("<cron-fire", ignoreCase = true) ||
            text.contains("cron-fire", ignoreCase = true)
    }

    fun extractCronFireJobId(text: String): String {
        val match = Regex(
            """jobId\s*=\s*"([^"]+)"""",
            RegexOption.IGNORE_CASE,
        ).find(text) ?: return ""
        return match.groupValues.getOrNull(1).orEmpty().trim()
    }

    fun extractCronFirePrompt(text: String): String {
        val tagged = Regex(
            """<prompt>([\s\S]*?)</prompt>""",
            RegexOption.IGNORE_CASE,
        ).find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        if (tagged.isNotBlank()) return tagged
        val inner = Regex(
            """<cron-fire\b[^>]*>([\s\S]*?)</cron-fire>""",
            RegexOption.IGNORE_CASE,
        ).find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        return inner.replace(Regex("""</?prompt>""", RegexOption.IGNORE_CASE), "").trim()
    }

    fun isIdleTurnSignal(update: JSONObject): Boolean =
        when (update.optString("sessionUpdate")) {
            "agent_message_chunk",
            "agent_thought_chunk",
            "tool_call",
            "tool_call_update",
            "user_message_chunk",
            -> true
            else -> false
        }

    fun isBufferedIdleTurnUpdate(update: JSONObject): Boolean =
        when (update.optString("sessionUpdate")) {
            "agent_message_chunk", "agent_thought_chunk", "tool_call" -> true
            else -> false
        }

    /**
     * Parent ACP `session/update` is silent while a nested Agent tool (phone
     * subagent) thinks or waits on MCP. Track in-flight tool ids so the turn
     * watchdog does not treat that silence as a stall.
     */
    fun applyToolCallFlight(update: JSONObject, inFlight: MutableSet<String>) {
        val kind = update.optString("sessionUpdate")
        if (kind != "tool_call" && kind != "tool_call_update") return
        val id = update.optString("toolCallId")
        if (id.isBlank()) return
        val status = update.optString("status").ifBlank { "in_progress" }
        if (isTerminalToolStatus(status)) inFlight.remove(id) else inFlight.add(id)
    }

    fun isTerminalToolStatus(status: String): Boolean {
        val normalized = status.trim().lowercase()
        return normalized == "completed" ||
            normalized == "failed" ||
            normalized == "cancelled" ||
            normalized == "canceled" ||
            normalized == "rejected"
    }

    fun mapStopReason(stopReason: String): String = when (stopReason) {
        "end_turn" -> "completed"
        "cancelled" -> "cancelled"
        else -> stopReason.ifBlank { "completed" }
    }

    fun parseUsage(result: JSONObject?): JSONObject? {
        val usage = result?.optJSONObject("usage") ?: return null
        val reportedInput = usagePositiveLong(
            usage,
            "input_tokens",
            "inputTokens",
            "prompt_tokens",
            "promptTokens",
            "used",
        )
        val output = usagePositiveLong(
            usage,
            "output_tokens",
            "outputTokens",
            "completion_tokens",
            "completionTokens",
        )
        val reasoning = usagePositiveLong(
            usage,
            "reasoning_tokens",
            "reasoningTokens",
            "thought_tokens",
            "thoughtTokens",
        )
        // Where the cached prefix is reported, and what it means, differs by provider:
        //  - flat `cached_input_tokens` (Kimi's wire format) sits *beside* the input count
        //  - OpenAI-compatible providers, StepFun included, nest it under
        //    `prompt_tokens_details.cached_tokens` and count it *inside* `prompt_tokens`
        //  - Anthropic reports `cache_read_input_tokens`, also beside the input count
        // Reading only the flat keys made every OpenAI-shaped turn report a 0% cache hit, which is
        // what the token-mix chart was drawing.
        val flatCached = usagePositiveLong(
            usage,
            "cached_input_tokens",
            "cachedInputTokens",
            "cached_tokens",
            "cache_read_input_tokens",
            "cacheReadInputTokens",
        )
        val nestedCached = listOfNotNull(
            usage.optJSONObject("prompt_tokens_details"),
            usage.optJSONObject("promptTokensDetails"),
            usage.optJSONObject("input_tokens_details"),
        ).firstNotNullOfOrNull { details ->
            usagePositiveLong(details, "cached_tokens", "cachedTokens")
        }
        val cached = flatCached ?: nestedCached
        // The rest of the app treats input and cached as disjoint and adds them back together, so
        // an inclusive count has to have the cached part taken out of it exactly once.
        val input = if (flatCached == null && nestedCached != null && reportedInput != null) {
            (reportedInput - nestedCached).coerceAtLeast(0L).takeIf { it > 0L }
        } else {
            reportedInput
        }
        val total = usagePositiveLong(usage, "total_tokens", "totalTokens")
            ?: listOfNotNull(input, output).takeIf { it.isNotEmpty() }?.sum()
        if (input == null && output == null && total == null && reasoning == null && cached == null) {
            return null
        }
        return JSONObject().apply {
            input?.let { put("input_tokens", it) }
            output?.let { put("output_tokens", it) }
            cached?.let { put("cached_input_tokens", it) }
            reasoning?.let { put("reasoning_tokens", it) }
            total?.let { put("total_tokens", it) }
        }
    }

    private fun usagePositiveLong(source: JSONObject, vararg keys: String): Long? {
        keys.forEach { key ->
            if (source.has(key) && !source.isNull(key)) {
                val value = source.optLong(key)
                if (value > 0L) return value
            }
        }
        return null
    }

    fun parsePermissionRequest(params: JSONObject): AcpPermissionRequest {
        val options = buildList {
            val rawOptions = params.optJSONArray("options") ?: JSONArray()
            for (index in 0 until rawOptions.length()) {
                val option = rawOptions.optJSONObject(index) ?: continue
                val optionId = option.optString("optionId")
                if (optionId.isBlank()) continue
                add(
                    AcpPermissionOption(
                        optionId = optionId,
                        name = option.optString("name"),
                        kind = option.optString("kind"),
                    ),
                )
            }
        }
        return AcpPermissionRequest(
            sessionId = params.optString("sessionId"),
            toolCall = params.optJSONObject("toolCall"),
            options = options,
            raw = params,
        )
    }

    fun permissionQuestionText(toolCall: JSONObject?): String {
        if (toolCall == null) return ""
        val direct = jsonDisplayString(toolCall, "question", "prompt", "message")
        if (direct.isNotBlank()) return direct
        val content = toolCall.optJSONArray("content") ?: return ""
        val texts = buildList {
            for (index in 0 until content.length()) {
                val block = content.optJSONObject(index) ?: continue
                extractContentText(block).takeIf(String::isNotBlank)?.let(::add)
            }
        }
        return texts.joinToString("\n\n")
    }

    private fun extractContentText(block: JSONObject): String {
        jsonDisplayString(block, "text", "question").takeIf(String::isNotBlank)?.let { return it }
        val nested = block.optJSONObject("content") ?: return ""
        return jsonDisplayString(nested, "text", "question")
    }

    fun permissionOutcome(optionId: String = "approve_always"): JSONObject =
        JSONObject().put(
            "outcome",
            JSONObject()
                .put("outcome", "selected")
                .put("optionId", optionId),
        )

    fun permissionCancelledOutcome(): JSONObject =
        JSONObject().put(
            "outcome",
            JSONObject().put("outcome", "cancelled"),
        )

    /**
     * Safe default when the UI cannot answer: prefer the least destructive reject
     * option (reject_once > reject > plan_reject_and_exit > any *reject*), and fall
     * back to the protocol-level cancelled outcome when no reject option exists.
     */
    fun safePermissionOutcome(options: List<AcpPermissionOption>): JSONObject =
        safeRejectOptionId(options)?.let(::permissionOutcome) ?: permissionCancelledOutcome()

    fun safeRejectOptionId(options: List<AcpPermissionOption>): String? {
        options.firstOrNull { it.optionId == "reject_once" }?.let { return it.optionId }
        options.firstOrNull { it.optionId == "reject" }?.let { return it.optionId }
        options.firstOrNull { it.optionId == "plan_reject_and_exit" }?.let { return it.optionId }
        return options.firstOrNull { it.optionId.contains("reject") }?.optionId
    }

    fun parseElicitationRequest(params: JSONObject): AcpElicitationRequest {
        val schema = params.optJSONObject("requestedSchema")
            ?: params.optJSONObject("schema")
            ?: JSONObject()
        val required = buildSet {
            val requiredArray = schema.optJSONArray("required") ?: JSONArray()
            for (index in 0 until requiredArray.length()) {
                requiredArray.optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
        }
        val message = params.optString("message")
        val parsed = buildList {
            val properties = schema.optJSONObject("properties") ?: JSONObject()
            val keys = properties.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val property = properties.optJSONObject(key) ?: continue
                add(parseElicitationQuestion(key, property, key in required))
            }
        }
        val questions = parsed.map { question ->
            // The CLI titles each field with the short `header` and joins the
            // full question texts into the form-level message. For a
            // single-question form the message IS that question's text, so
            // recover it as the body when the schema carried none.
            if (parsed.size == 1 && question.body.isBlank() && message.isNotBlank()) {
                question.copy(body = message)
            } else {
                question
            }
        }
        return AcpElicitationRequest(
            sessionId = params.optString("sessionId"),
            message = message,
            schema = schema,
            questions = questions,
            raw = params,
        )
    }

    private fun parseElicitationQuestion(
        id: String,
        property: JSONObject,
        required: Boolean,
    ): AcpElicitationQuestion {
        val singleOptions = property.optJSONArray("oneOf")
        val items = property.optJSONObject("items")
        val multiOptions = items?.optJSONArray("anyOf") ?: items?.optJSONArray("oneOf")
        val isMulti = property.optString("type") == "array" && multiOptions != null
        val options = when {
            singleOptions != null -> parseElicitationOptions(singleOptions)
            isMulti && multiOptions != null -> parseElicitationOptions(multiOptions)
            else -> emptyList()
        }
        val minItems = property.optLong("minItems", 0L)
        val header = firstNonBlank(
            property.optString("header"),
            property.optString("title"),
        )
        val question = firstNonBlank(
            property.optString("question"),
            property.optString("prompt"),
            property.optString("description"),
            property.optString("body"),
        )
        return AcpElicitationQuestion(
            id = id,
            title = header.ifBlank { question },
            body = question,
            multiSelect = isMulti,
            required = required || (isMulti && minItems >= 1),
            options = options,
            schema = property,
        )
    }

    private fun firstNonBlank(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() }.orEmpty()

    private fun parseElicitationOptions(rawOptions: JSONArray): List<AcpElicitationOption> =
        buildList {
            for (index in 0 until rawOptions.length()) {
                val option = rawOptions.optJSONObject(index) ?: continue
                val value = option.optString("const").ifBlank { option.optString("value") }
                if (value.isBlank()) continue
                add(
                    AcpElicitationOption(
                        value = value,
                        label = option.optString("title").ifBlank { value },
                    ),
                )
            }
        }

    fun elicitationAccepted(content: JSONObject): JSONObject =
        JSONObject()
            .put("action", "accept")
            .put("content", content)

    fun elicitationCancelled(): JSONObject =
        JSONObject().put("action", "cancel")

    private fun parseToolCallInfo(update: JSONObject, isUpdate: Boolean): AcpToolCallInfo =
        AcpToolCallInfo(
            id = update.optString("toolCallId"),
            title = update.optString("title"),
            kind = update.optString("kind"),
            status = update.optString("status").ifBlank { "in_progress" },
            isUpdate = isUpdate,
            rawInput = update.opt("rawInput"),
            output = extractToolOutput(update),
            diffs = extractDiffs(update.optJSONArray("content")),
        )

    private fun parseFlexiblePlanEntries(root: JSONObject?): List<AcpPlanEntry> {
        if (root == null) return emptyList()
        val array = firstPlanArray(root) ?: return emptyList()
        return parsePlanEntryArray(array)
    }

    private fun parseTodoToolPlanEntries(update: JSONObject): List<AcpPlanEntry> {
        val display = update.optJSONObject("display")
            ?: update.optJSONObject("_meta")?.optJSONObject("display")
            ?: update.optJSONObject("_meta")?.optJSONObject("kimiCode")?.optJSONObject("display")
        val fromDisplay = parseFlexiblePlanEntries(display)
        if (fromDisplay.isNotEmpty()) return fromDisplay
        if (!looksLikeTodoTool(update)) return emptyList()
        val fromRoot = parseFlexiblePlanEntries(update)
        if (fromRoot.isNotEmpty()) return fromRoot
        val rawInput = when (val raw = update.opt("rawInput")) {
            is JSONObject -> raw
            is String -> parseJsonObjectAllowingPreviewPrefix(raw)
            null, JSONObject.NULL -> null
            else -> parseJsonObjectAllowingPreviewPrefix(raw.toString())
        }
        val fromInput = parseFlexiblePlanEntries(rawInput)
        if (fromInput.isNotEmpty()) return fromInput
        return parsePlanEntriesFromToolContent(update)
    }

    private fun looksLikeTodoTool(update: JSONObject): Boolean {
        val title = update.optString("title").ifBlank { update.optString("name") }
        val kind = update.optString("kind")
        return "$title $kind".contains("todo", ignoreCase = true) ||
            kind.equals("plan", ignoreCase = true)
    }

    private fun parsePlanEntriesFromToolContent(update: JSONObject): List<AcpPlanEntry> {
        val content = update.optJSONArray("content") ?: return emptyList()
        for (index in 0 until content.length()) {
            val item = content.optJSONObject(index) ?: continue
            val nested = item.optJSONObject("content")
            val text = when (item.optString("type")) {
                "content" -> nested?.optString("text").orEmpty()
                else -> item.optString("text").ifBlank { nested?.optString("text").orEmpty() }
            }
            val parsed = parseJsonObjectAllowingPreviewPrefix(text) ?: continue
            val entries = parseFlexiblePlanEntries(parsed)
            if (entries.isNotEmpty()) return entries
        }
        return emptyList()
    }

    private fun firstPlanArray(root: JSONObject): JSONArray? {
        sequenceOf("entries", "items", "todos").forEach { key ->
            root.optJSONArray(key)?.let { return it }
        }
        root.optJSONObject("plan")?.let { nested ->
            sequenceOf("entries", "items", "todos").forEach { key ->
                nested.optJSONArray(key)?.let { return it }
            }
        }
        root.optJSONObject("todo_list")?.let { nested ->
            sequenceOf("entries", "items", "todos").forEach { key ->
                nested.optJSONArray(key)?.let { return it }
            }
        }
        root.optJSONObject("display")?.optJSONArray("items")?.let { return it }
        return null
    }

    private fun parsePlanEntryArray(rawEntries: JSONArray): List<AcpPlanEntry> {
        return buildList {
            for (index in 0 until rawEntries.length()) {
                val entry = rawEntries.optJSONObject(index) ?: continue
                val content = listOf("content", "title", "text", "description", "task")
                    .map { entry.optString(it) }
                    .firstOrNull { it.isNotBlank() }
                    ?: continue
                add(
                    AcpPlanEntry(
                        content = content,
                        priority = entry.optString("priority").ifBlank { "medium" },
                        status = kira.ditto.data.normalizeSessionPlanStatus(entry.optString("status")),
                    ),
                )
            }
        }
    }

    private fun parseAvailableCommands(rawCommands: JSONArray?): List<AcpAvailableCommand> {
        if (rawCommands == null) return emptyList()
        return buildList {
            for (index in 0 until rawCommands.length()) {
                val command = rawCommands.optJSONObject(index) ?: continue
                add(
                    AcpAvailableCommand(
                        name = command.optString("name"),
                        description = command.optString("description"),
                        raw = command,
                    ),
                )
            }
        }
    }

    private fun extractDiffs(content: JSONArray?): List<AcpDiffBlock> {
        if (content == null) return emptyList()
        return buildList {
            for (index in 0 until content.length()) {
                val item = content.optJSONObject(index) ?: continue
                if (item.optString("type") != "diff") continue
                add(
                    AcpDiffBlock(
                        path = item.optString("path"),
                        oldText = item.optString("oldText"),
                        newText = item.optString("newText"),
                    ),
                )
            }
        }
    }

    private fun contentText(content: JSONObject?): String {
        if (content == null) return ""
        return when (content.optString("type")) {
            "text" -> content.optString("text")
            else -> content.optString("text")
        }
    }

    internal fun extractToolOutput(update: JSONObject): Any? {
        fun preferStructured(value: Any?): Any? {
            val json = when (value) {
                is JSONObject -> value
                is String -> parseJsonObjectAllowingPreviewPrefix(value)
                else -> null
            } ?: return value
            json.optJSONObject("structuredContent")?.let { structured ->
                if (
                    structured.has("_upa") ||
                    structured.has("needLogin") ||
                    structured.has("accountName") ||
                    structured.has("pois") ||
                    structured.has("places") ||
                    structured.has("mapUrl")
                ) {
                    return structured
                }
            }
            if (json.has("_upa")) return json
            return json
        }
        val raw = update.opt("rawOutput")
        if (raw != null && raw != JSONObject.NULL) {
            return preferStructured(raw)
        }
        val content = update.optJSONArray("content") ?: return preferStructured(update.opt("output"))
        val texts = buildList {
            for (index in 0 until content.length()) {
                val item = content.optJSONObject(index) ?: continue
                val nested = item.optJSONObject("content")
                val text = when (item.optString("type")) {
                    "content" -> nested?.optString("text").orEmpty()
                    "text" -> item.optString("text")
                    else -> item.optString("text").ifBlank {
                        nested?.optString("text").orEmpty()
                    }
                }.trim()
                if (text.isNotBlank()) add(text)
            }
        }
        if (texts.isEmpty()) return content
        val joined = texts.joinToString("\n")
        val parsed = parseJsonObjectAllowingPreviewPrefix(joined)
        if (parsed != null) return preferStructured(parsed)
        return JSONObject()
            .put(
                "content",
                JSONArray().put(
                    JSONObject().put("type", "text").put("text", joined),
                ),
            )
    }
}

private fun parseJsonObjectAllowingPreviewPrefix(raw: String): JSONObject? {
    val text = raw.trim()
    runCatching { JSONObject(text) }.getOrNull()?.let { return it }
    val start = text.indexOf('{')
    if (start <= 0) return null
    return runCatching { JSONObject(text.substring(start)) }.getOrNull()
}
