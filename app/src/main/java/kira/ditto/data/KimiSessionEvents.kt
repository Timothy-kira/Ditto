package kira.ditto.data

import kira.ditto.ui.ChatMessage
import kira.ditto.ui.MessageAuthor
import kira.ditto.ui.MessageDisplayKind
import kira.ditto.ui.ToolCallDiff
import org.json.JSONArray
import org.json.JSONObject

/** One todo-plan entry from an ACP plan_update (full-replacement projection). */
data class SessionPlanEntry(
    val content: String,
    val priority: String,
    val status: String,
)

/** Context-window usage reported by ACP usage_update / final prompt usage. */
data class SessionContextUsage(
    val usedTokens: Long,
    val windowTokens: Long,
) {
    /** Context occupancy in [0, 1], or null when the window size is unknown. */
    val fraction: Float?
        get() = if (windowTokens > 0L && usedTokens >= 0L) {
            (usedTokens.toFloat() / windowTokens.toFloat()).coerceIn(0f, 1f)
        } else {
            null
        }
}

/** A slash command advertised by the agent via available_commands_update. */
data class AgentSlashCommand(
    val name: String,
    val description: String,
    val argumentHint: String = "",
)

/** Agent-reported session config (mode / model / thinking), last-write-wins. */
data class SessionAgentConfig(
    val modeId: String? = null,
    val modelId: String? = null,
    val thinkingLevel: String? = null,
)

private fun nonBlank(value: String?): String? = value?.trim()?.takeIf(String::isNotEmpty)

internal fun normalizeSessionPlanStatus(raw: String): String {
    val key = raw.trim().lowercase().replace(' ', '_').replace('-', '_')
    return when (key) {
        "done", "complete", "completed", "finished" -> "completed"
        "in_progress", "inprogress", "doing", "active", "running" -> "in_progress"
        "cancelled", "canceled" -> "cancelled"
        else -> "pending"
    }
}

internal fun sessionPlanEntryIsCompleted(status: String): Boolean =
    normalizeSessionPlanStatus(status) == "completed"

internal fun sessionPlanEntryIsInProgress(status: String): Boolean =
    normalizeSessionPlanStatus(status) == "in_progress"

internal fun sessionPlanEntryIsOpen(status: String): Boolean {
    val normalized = normalizeSessionPlanStatus(status)
    return normalized != "completed" && normalized != "cancelled"
}

/** Task titles only — status ticks must not move the in-thread checklist. */
internal fun sessionPlanContentFingerprint(entries: List<SessionPlanEntry>): String =
    entries.joinToString("\u0001") { it.content.trim() }

internal data class SessionPlanPlacement(
    val entries: List<SessionPlanEntry>,
    val messageId: String?,
    val groupId: String?,
    val contentFingerprint: String,
)

internal fun nextSessionPlanPlacement(
    previousEntries: List<SessionPlanEntry>,
    previousFingerprint: String,
    previousMessageId: String?,
    previousGroupId: String?,
    nextEntries: List<SessionPlanEntry>,
    activeResponseGroupId: String?,
    messages: List<ChatMessage>,
): SessionPlanPlacement {
    if (nextEntries.isEmpty()) {
        return SessionPlanPlacement(
            entries = emptyList(),
            messageId = null,
            groupId = null,
            contentFingerprint = "",
        )
    }
    val fingerprint = sessionPlanContentFingerprint(nextEntries)
    if (fingerprint == previousFingerprint && previousFingerprint.isNotEmpty()) {
        return SessionPlanPlacement(
            entries = nextEntries,
            messageId = previousMessageId,
            groupId = previousGroupId,
            contentFingerprint = previousFingerprint,
        )
    }
    // First sighting of an already-visible plan (upgrade): pin to the last
    // assistant turn, not the turn that happens to be running now.
    val pinToCurrentTurn = previousFingerprint.isNotEmpty() || previousEntries.isEmpty()
    val (messageId, groupId) = resolveSessionPlanAnchor(
        activeResponseGroupId = activeResponseGroupId.takeIf { pinToCurrentTurn },
        messages = messages,
    )
    return SessionPlanPlacement(
        entries = nextEntries,
        messageId = messageId,
        groupId = groupId,
        contentFingerprint = fingerprint,
    )
}

internal fun resolveSessionPlanAnchor(
    activeResponseGroupId: String?,
    messages: List<ChatMessage>,
): Pair<String?, String?> {
    val standard = messages.filter { it.displayKind == MessageDisplayKind.Standard }
    val lastAgent = standard.lastOrNull { it.author == MessageAuthor.Agent }
    val lastUser = standard.lastOrNull { it.author == MessageAuthor.User }
    val groupId = activeResponseGroupId?.takeIf { it.isNotBlank() }
        ?: lastAgent?.responseGroupId?.takeIf { !it.isNullOrBlank() }
    val messageId = if (!activeResponseGroupId.isNullOrBlank()) {
        standard.lastOrNull {
            it.author == MessageAuthor.Agent && it.responseGroupId == activeResponseGroupId
        }?.id ?: lastUser?.id ?: lastAgent?.id
    } else {
        lastAgent?.id ?: lastUser?.id
    }
    return messageId to groupId
}

internal fun chatMessageHostsSessionPlan(
    message: ChatMessage,
    planAnchorMessageId: String?,
    planAnchorGroupId: String?,
): Boolean {
    if (message.displayKind != MessageDisplayKind.Standard) return false
    if (!planAnchorMessageId.isNullOrBlank() && message.id == planAnchorMessageId) return true
    if (
        !planAnchorGroupId.isNullOrBlank() &&
        message.author == MessageAuthor.Agent &&
        message.responseGroupId == planAnchorGroupId
    ) {
        return true
    }
    return false
}

internal fun sessionPlanHostKey(
    messages: List<ChatMessage>,
    planAnchorMessageId: String?,
    planAnchorGroupId: String?,
): String? {
    val host = messages.lastOrNull {
        chatMessageHostsSessionPlan(it, planAnchorMessageId, planAnchorGroupId)
    }
    if (host != null) return sessionPlanItemKey(host)
    val lastAgent = messages.lastOrNull {
        it.displayKind == MessageDisplayKind.Standard && it.author == MessageAuthor.Agent
    } ?: return null
    return sessionPlanItemKey(lastAgent)
}

private fun sessionPlanItemKey(message: ChatMessage): String =
    message.responseGroupId.takeIf { !it.isNullOrBlank() && message.author == MessageAuthor.Agent }
        ?: message.id

/** plan_update payload: entries/items/todos, plus v2 nested plan.entries. Full replace. */
internal fun parseSessionPlanEntries(payload: JSONObject): List<SessionPlanEntry> {
    val entries = firstSessionPlanArray(payload) ?: return emptyList()
    return buildList {
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            val content = listOf("content", "title", "text", "description", "task")
                .map { entry.optString(it) }
                .firstOrNull { it.isNotBlank() }
                ?: continue
            add(
                SessionPlanEntry(
                    content = content,
                    priority = entry.optString("priority").ifBlank { "medium" },
                    status = normalizeSessionPlanStatus(entry.optString("status")),
                ),
            )
        }
    }
}

/**
 * kimi-code's TodoList/SetTodoList/TodoWrite often arrives as a tool_call
 * (arguments or content JSON) instead of, or before, ACP `sessionUpdate: plan`.
 */
internal fun parseSessionPlanEntriesFromTool(
    toolName: String,
    toolKind: String,
    argumentsJson: String,
    outputJson: String?,
): List<SessionPlanEntry>? {
    val looksTodo = "$toolName $toolKind".contains("todo", ignoreCase = true) ||
        toolKind.equals("plan", ignoreCase = true)
    if (!looksTodo) return null
    val fromOutput = outputJson?.let(::parseJsonObjectLoose)?.let(::parseSessionPlanEntries).orEmpty()
    if (fromOutput.isNotEmpty()) return fromOutput
    val fromArguments = parseJsonObjectLoose(argumentsJson)?.let(::parseSessionPlanEntries).orEmpty()
    return fromArguments.takeIf { it.isNotEmpty() }
}

private fun firstSessionPlanArray(payload: JSONObject): JSONArray? {
    sequenceOf("entries", "items", "todos").forEach { key ->
        payload.optJSONArray(key)?.let { return it }
    }
    payload.optJSONObject("plan")?.let { nested ->
        sequenceOf("entries", "items", "todos").forEach { key ->
            nested.optJSONArray(key)?.let { return it }
        }
    }
    payload.optJSONObject("display")?.optJSONArray("items")?.let { return it }
    payload.optJSONObject("todo_list")?.let { nested ->
        sequenceOf("entries", "items", "todos").forEach { key ->
            nested.optJSONArray(key)?.let { return it }
        }
    }
    return null
}

private fun parseJsonObjectLoose(raw: String?): JSONObject? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty() || text == "{}") return null
    runCatching { JSONObject(text) }.getOrNull()?.let { return it }
    val start = text.indexOf('{')
    if (start < 0) return null
    return runCatching { JSONObject(text.substring(start)) }.getOrNull()
}

/** usage_update payload: {"used": Long, "size": Long}; -1 means unknown. */
internal fun parseSessionContextUsage(payload: JSONObject): SessionContextUsage? {
    val used = payload.optLong("used", -1L)
    val size = payload.optLong("size", -1L)
    if (used < 0L && size < 0L) return null
    // used=-1 means "unknown". Do not coerce it to 0 or the capsule ring clears.
    if (used <= 0L) return null
    return SessionContextUsage(
        usedTokens = used,
        windowTokens = size.coerceAtLeast(0L),
    )
}

/** Keep a previous occupancy when a later usage_update is empty or unknown. */
internal fun mergeSessionContextUsage(
    previous: SessionContextUsage?,
    incoming: SessionContextUsage,
): SessionContextUsage {
    val used = incoming.usedTokens.takeIf { it > 0L }
        ?: previous?.usedTokens?.takeIf { it > 0L }
        ?: incoming.usedTokens
    val window = incoming.windowTokens.takeIf { it > 0L }
        ?: previous?.windowTokens?.takeIf { it > 0L }
        ?: incoming.windowTokens
    return SessionContextUsage(usedTokens = used, windowTokens = window)
}

/**
 * available_commands_update payload: {"commands": [<raw ACP command>]}. The CLI
 * sends the full command set each time, so callers replace rather than merge.
 */
internal fun parseAgentSlashCommands(payload: JSONObject): List<AgentSlashCommand> {
    val commands = payload.optJSONArray("commands") ?: return emptyList()
    return buildList {
        for (index in 0 until commands.length()) {
            val command = commands.optJSONObject(index) ?: continue
            val name = command.optString("name").trim().removePrefix("/")
            if (name.isBlank()) continue
            add(
                AgentSlashCommand(
                    name = name,
                    description = command.optString("description"),
                    argumentHint = command.optJSONObject("input")?.optString("hint").orEmpty(),
                ),
            )
        }
    }.distinctBy { it.name.lowercase() }
}

private fun JSONObject.firstNonBlankString(vararg keys: String): String? {
    keys.forEach { key ->
        if (has(key) && !isNull(key)) {
            nonBlank(optString(key))?.let { return it }
        }
    }
    return null
}

/**
 * config_option_update payload: {"update": <raw update>}. The raw shape carries
 * a config id plus its new value; known ids (mode/model/thinking) are projected
 * onto [SessionAgentConfig], everything else leaves the config untouched. The
 * agent replay is authoritative, so the new value always overwrites.
 */
internal fun SessionAgentConfig.applyConfigOptionUpdate(update: JSONObject): SessionAgentConfig {
    val configId = update.firstNonBlankString("configId", "config_id", "id", "key")
        ?.lowercase()
        ?: return this
    val value = update.firstNonBlankString("value", "currentValue", "newValue", "modeId")
    return when (configId) {
        "mode" -> copy(modeId = value ?: modeId)
        "model" -> copy(modelId = value ?: modelId)
        "thinking", "reasoning" -> copy(thinkingLevel = value ?: thinkingLevel)
        else -> this
    }
}

/** current_mode_update payload: {"modeId": String}. */
internal fun parseCurrentModeId(payload: JSONObject): String? =
    nonBlank(payload.optString("modeId").ifBlank { payload.optString("currentModeId") })

/**
 * session_info_update payload: {"title": String|null}. Returns null when the
 * agent cleared the title (caller reverts to the derived title).
 */
internal fun parseSessionInfoTitle(payload: JSONObject): String? =
    kira.ditto.data.kimi.KimiAcpProtocol.jsonDisplayString(payload, "title")
        .takeIf(kira.ditto.data.kimi.KimiAcpProtocol::isUsableDisplayText)

/** tool event payload "diffs": [{path, oldText, newText}] serialized for AgentToolEvent. */
internal fun toolDiffsJson(payload: JSONObject): String? {
    val diffs = payload.optJSONArray("diffs") ?: return null
    if (diffs.length() == 0) return null
    return diffs.toString()
}

/** Parses the diffs JSON carried by AgentToolEvent.diffsJson. */
internal fun parseToolDiffs(diffsJson: String?): List<ToolCallDiff> {
    if (diffsJson.isNullOrBlank()) return emptyList()
    val array = runCatching { JSONArray(diffsJson) }.getOrNull() ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val diff = array.optJSONObject(index) ?: continue
            add(
                ToolCallDiff(
                    path = diff.optString("path"),
                    oldText = diff.optString("oldText"),
                    newText = diff.optString("newText"),
                ),
            )
        }
    }
}
