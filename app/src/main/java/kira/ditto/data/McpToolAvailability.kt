package kira.ditto.data

import org.json.JSONObject

/**
 * Which shipped MCP servers are currently usable — expressed at call time, never by editing the
 * declared tool list.
 *
 * The tool list is the very front of the system layer, ahead of everything the context folder works
 * to keep byte-stable. Adding or removing a server mid-conversation therefore invalidates the
 * prompt prefix from token zero. Worse, [acpMcpServersFingerprint] gates ACP session reuse
 * ([kira.ditto.runtime.AlpineRuntime] `resolveKimiSession`), so a change does not merely cost a
 * cache miss — it swaps the session out from under the conversation.
 *
 * The fix is the invariant Manus describes for logit masking, reached by the one route open to us:
 * we own every shipped server's `tools/list`, so the declaration can stay constant for the life of
 * a session and availability can be answered when a tool is actually called. The secret-backed
 * servers already did half of this — [GmailMcpHost] and friends return `input_required` when they
 * are not signed in — so this only adds the other half, the user's enable/disable toggle.
 */
object McpToolAvailability {
    /**
     * Shipped server ids the user has switched off.
     *
     * Held rather than looked up because the call arrives on the MCP transport thread, with no
     * coroutine scope to collect the settings flow from, and blocking there would stall a tool call
     * behind a DataStore read.
     */
    @Volatile
    private var disabledIds: Set<String> = emptySet()

    fun setDisabledServerIds(ids: Set<String>) {
        disabledIds = ids
    }

    fun isEnabled(serverId: String): Boolean = serverId !in disabledIds

    /**
     * Run [call] when the server is on; otherwise answer the way an unconfigured server does.
     *
     * `input_required` rather than an error: the model should treat "you need to switch this on"
     * as a thing the user can fix, exactly as it treats "you need to sign in", and not as a
     * malfunction to retry or route around.
     */
    inline fun gate(serverId: String, displayName: String, call: () -> String): String =
        if (isEnabled(serverId)) call() else shippedMcpDisabledJson(displayName)
}

/** The standard refusal for a shipped server the user has turned off. */
fun shippedMcpDisabledJson(displayName: String): String = JSONObject()
    .put("ok", false)
    .put("code", "input_required")
    .put(
        "reason",
        "$displayName is switched off for this conversation. Turn it on from the MCP picker in the composer to use it.",
    )
    .toString()
