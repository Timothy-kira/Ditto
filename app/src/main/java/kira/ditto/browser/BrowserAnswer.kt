package kira.ditto.browser

import kira.ditto.data.AppSettings
import kira.ditto.data.LlmProviderConfig
import kira.ditto.data.SessionExecutionManager
import kira.ditto.data.SessionTurnRequest
import kira.ditto.runtime.DittoWebSearchHit
import kira.ditto.ui.AssistantResponseBlock
import kira.ditto.ui.ChatMessage
import kira.ditto.ui.MessageAuthor
import java.util.UUID

/**
 * The answer Ditto gives for a search, streamed into the browser's own surface.
 *
 * The session id is deliberately one the chat store has never heard of. `appendAgentMessage`
 * skips any session it cannot find, so the turn runs and streams normally but commits nothing:
 * a search answer is not a conversation, and it has no business showing up in the drawer.
 */
internal data class BrowserAnswerState(
    val query: String = "",
    val running: Boolean = false,
    val blocks: List<AssistantResponseBlock> = emptyList(),
    val responseGroupId: String? = null,
    val messageIdPrefix: String? = null,
    val failure: String = "",
) {
    val hasContent: Boolean get() = blocks.isNotEmpty()
}

internal object BrowserAnswer {
    fun newSessionId(): String = "browser-answer-${UUID.randomUUID()}"

    fun start(
        executions: SessionExecutionManager,
        sessionId: String,
        settings: AppSettings,
        providerConfigs: List<LlmProviderConfig>,
        query: String,
        hits: List<DittoWebSearchHit>,
    ) {
        executions.startTurn(
            SessionTurnRequest(
                sessionId = sessionId,
                settings = settings,
                requestMessages = listOf(
                    ChatMessage(
                        id = "browser-answer-ask-${System.currentTimeMillis()}",
                        author = MessageAuthor.User,
                        text = prompt(query, hits),
                        createdAtMillis = System.currentTimeMillis(),
                    ),
                ),
                // No skills, no MCP, no agent mode: the results are already in the prompt, so the
                // model has nothing to go fetch and every tool would only add latency.
                selectedSkillIds = emptyList(),
                activeSkills = emptyList(),
                activeMcpServerIds = emptyList(),
                agentModeEnabled = false,
                chromeEnabled = false,
                providerConfigs = providerConfigs,
            ),
        )
    }

    internal fun prompt(query: String, hits: List<DittoWebSearchHit>): String = buildString {
        append("用户在浏览器里搜索：").append(query).append("\n\n")
        if (hits.isEmpty()) {
            append("这次没有拿到搜索结果。请说明这一点，不要凭印象编造事实。")
            return@buildString
        }
        append("以下是搜索引擎返回的结果：\n\n")
        hits.take(8).forEachIndexed { index, hit ->
            append(index + 1).append(". ").append(hit.title.ifBlank { hit.url }).append('\n')
            append("   ").append(hit.url).append('\n')
            if (hit.snippet.isNotBlank()) append("   ").append(hit.snippet.trim()).append('\n')
            append('\n')
        }
        append(
            "请基于以上结果直接回答这次搜索，用中文，先给结论再给要点。" +
                "引用具体事实时标注来源域名。结果不足以回答的部分，直说不知道，不要补全。",
        )
    }
}
