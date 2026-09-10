package kira.ditto.browser

import kira.ditto.data.chatdb.BrowserTaskEntity
import kira.ditto.runtime.LocalRuntime
import org.json.JSONObject

/**
 * Send a research memo to EverMe, through the SDK that already ships in the offline bundle.
 *
 * Two things shape everything here.
 *
 * **The write cannot be undone.** EverMe's SDK offers `savePersonalMemory` and `saveAgentMemory`;
 * both take content and let the server extract from it. There is no client-side entry id, no
 * upsert, and no delete. So every idea that depends on "we can fix it later" - dedupe by upsert,
 * supersede an entry, retract a bad one - is unavailable, and its work has to happen before the
 * request goes out. That is why the gate lives in [shouldUploadMemo] and the once-only guard lives
 * in `contentSha256`, and why this class only ever drains a queue somebody else decided to fill.
 *
 * **The SDK is in the bundle, the MCP server is not.** `@everme/memory-mcp` is fetched with
 * `npx -y` on first use and needs the network to even start; `@everme/agent-sdk` is already on disk
 * under the managed plugin. Going through the SDK directly means the upload path has the same
 * offline behaviour as the rest of the runtime.
 */
object BrowserMemoryUploader {

    private const val PluginRoot = "/root/.kimi-code/plugins/managed/everme"
    private const val EnvFile = "/root/.kimi-code/everme.env"
    private const val OkMarker = "ditto_everme_upload_ok"

    /**
     * The node program that does the write.
     *
     * The memo travels in an environment variable rather than inside the source, so no amount of
     * quoting in a summary can change what the program does. The credentials come from `everme.env`
     * the same way every other EverMe call in this runtime gets them, and are never echoed - the
     * SDK's own `redactError` is what turns a failure into something safe to log.
     */
    private val UploadScript = """
        import { createClient, resolveConfig, assertConfigUsable, savePersonalMemory, redactError }
          from "@everme/agent-sdk";
        try {
          const memo = JSON.parse(process.env.DITTO_MEMO_JSON || "{}");
          if (!memo.text) { console.log("ditto_everme_upload_skip: empty"); process.exit(0); }
          const cfg = resolveConfig({});
          assertConfigUsable(cfg, { requireAgentId: false });
          const client = createClient(cfg);
          await savePersonalMemory(client, {
            conversationId: memo.conversationId,
            messages: [{ role: "user", content: memo.text, timestamp: Date.now() }],
          });
          console.log("$OkMarker");
        } catch (error) {
          console.log("ditto_everme_upload_failed: " + redactError(error));
          process.exit(1);
        }
    """.trimIndent()

    /**
     * What the user's memory should actually say.
     *
     * Written as a sentence rather than a record dump because the receiving end extracts facts from
     * prose, and because this text may be read back to the user months from now. The open item is
     * included when there is one - "what we did not finish" is often the more useful half when
     * research is picked back up.
     */
    fun memoryText(task: BrowserTaskEntity): String = buildString {
        append("在一次网页调研中确认：").append(task.summary)
        if (task.tags.isNotBlank()) append("。主题：").append(task.tags.replace(",", "、"))
        if (task.openTodos.isNotBlank()) append("。尚未解决：").append(task.openTodos)
        if (task.goal.isNotBlank()) append("。起因是我问过：").append(task.goal.take(80))
        append("。")
    }

    /**
     * Send one memo. Returns true only on the success marker.
     *
     * Anything else - a non-zero exit, a missing token, a runtime that is not up - returns false and
     * leaves the row [MemoryUploadState.Pending] for a later attempt. Failing closed is the only
     * safe direction: a retried upload costs a duplicate, while a row wrongly marked uploaded is a
     * memory silently lost.
     */
    suspend fun upload(runtime: LocalRuntime, task: BrowserTaskEntity): Boolean {
        val payload = JSONObject()
            .put("conversationId", "ditto-browser-" + task.sessionId)
            .put("text", memoryText(task))
            .toString()
        val command = buildString {
            append("set -a; . ").append(EnvFile).append("; set +a; ")
            append("DITTO_MEMO_JSON=").append(shellQuote(payload)).append(' ')
            append("NODE_PATH=").append(PluginRoot).append("/node_modules ")
            append("node --input-type=module -e ").append(shellQuote(UploadScript))
        }
        val output = runCatching {
            runtime.executeCommand(command = command, awaitTimeoutMillis = 60_000L)
        }.getOrElse { return false }
        return OkMarker in output
    }

    /** Single-quote for sh, closing and reopening around any embedded quote. */
    private fun shellQuote(raw: String): String =
        "'" + raw.replace("'", "'\\''") + "'"
}
