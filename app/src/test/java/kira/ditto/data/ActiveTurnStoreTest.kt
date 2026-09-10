package kira.ditto.data

import kira.ditto.ui.ChatMessage
import kira.ditto.ui.ChatToolInvocation
import kira.ditto.ui.MessageAuthor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveTurnStoreTest {
    @Test
    fun processDeathFillsEmptyAbandonedTurn() {
        val messages = listOf(
            ChatMessage(id = "u1", author = MessageAuthor.User, text = "帮我搜索黑客松"),
            ChatMessage(
                id = "a1",
                author = MessageAuthor.Agent,
                text = "",
                toolInvocations = listOf(
                    ChatToolInvocation(id = "t1", toolName = "WebSearch", argumentsJson = "{}"),
                ),
                isIncomplete = true,
            ),
        )
        val settled = settleAbandonedTurnMessages(
            messages = messages,
            interruptedText = "刚才应用被系统回收，这一轮中断了。请再发一次同样的问题。",
            nowMillis = 42L,
        )
        assertFalse(settled.any { it.isIncomplete })
        assertEquals(
            "刚才应用被系统回收，这一轮中断了。请再发一次同样的问题。",
            settled.last().text,
        )
        assertEquals("agent-interrupted-42", settled.last().id)
    }

    @Test
    fun processDeathKeepsStreamedAssistantText() {
        val messages = listOf(
            ChatMessage(id = "u1", author = MessageAuthor.User, text = "西湖门票"),
            ChatMessage(
                id = "a1",
                author = MessageAuthor.Agent,
                text = "门票约 80 元。",
                isIncomplete = true,
            ),
        )
        val settled = settleAbandonedTurnMessages(messages, "interrupted", nowMillis = 1L)
        assertEquals(2, settled.size)
        assertEquals("门票约 80 元。", settled.last().text)
        assertTrue(settled.none { it.id.startsWith("agent-interrupted-") })
    }
}
