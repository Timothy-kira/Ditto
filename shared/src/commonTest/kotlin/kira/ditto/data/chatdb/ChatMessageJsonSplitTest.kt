package kira.ditto.data.chatdb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatMessageJsonSplitTest {
    @Test
    fun splitsHeavyBranchesIntoPayloadAndMergesThemBack() {
        val original = """{"id":"m1","text":"hello","toolInvocations":[{"id":"t1"}],"reasoningTrace":{"chunks":[]},"attachments":[{"id":"a1"}],"author":"Agent"}"""
        val split = ChatMessageJsonSplit.split(original)
        assertTrue(split.payloadJson != null)
        assertTrue("toolInvocations" !in split.lightJson)
        assertTrue("toolInvocations" in split.payloadJson!!)
        val merged = ChatMessageJsonSplit.merge(split.lightJson, split.payloadJson)
        val again = ChatMessageJsonSplit.split(merged)
        assertEquals(split.lightJson, again.lightJson)
        assertEquals(split.payloadJson, again.payloadJson)
    }

    @Test
    fun leavesSmallMessagesUnsplit() {
        val original = """{"id":"m2","text":"hi"}"""
        val split = ChatMessageJsonSplit.split(original)
        assertNull(split.payloadJson)
        assertEquals(original, ChatMessageJsonSplit.merge(split.lightJson, split.payloadJson))
    }

    @Test
    fun contentHashIsStableAndLengthPrefixed() {
        val first = chatContentHash("same")
        val second = chatContentHash("same")
        val other = chatContentHash("different")
        assertEquals(first, second)
        assertTrue(first.startsWith("4:"))
        assertTrue(first != other)
    }
}
