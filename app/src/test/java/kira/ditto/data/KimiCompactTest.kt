package kira.ditto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KimiCompactTest {
    @Test
    fun classifiesBusyAndFailedAcks() {
        assertEquals(KimiCompactReply.Busy, classifyKimiCompactReply(AetherCompactBusySentinel))
        assertEquals(KimiCompactReply.Busy, classifyKimiCompactReply(KimiCompactBusyAck))
        assertEquals(
            KimiCompactReply.Failed,
            classifyKimiCompactReply("Cannot compact while a turn is active."),
        )
        assertEquals(KimiCompactReply.Done, classifyKimiCompactReply(AetherCompactDoneSentinel))
        assertEquals(KimiCompactReply.Done, classifyKimiCompactReply(KimiCompactStartedAck))
    }

    @Test
    fun englishStartedAckIsHiddenAsCompactReceipt() {
        assertTrue(looksLikeKimiCompactAck(KimiCompactStartedAck))
        assertTrue(looksLikeKimiCompactAck(AetherCompactDoneSentinel))
        assertTrue(!looksLikeKimiCompactAck("Context compacted"))
    }
}
