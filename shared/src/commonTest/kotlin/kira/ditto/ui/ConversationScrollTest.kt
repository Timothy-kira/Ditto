package kira.ditto.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class ConversationScrollTest {
    @Test
    fun followStepMovesAFractionThenSlowsNearTheEnd() {
        assertEquals(22f, streamFollowStepPx(100f), 0.01f)
        assertEquals(1f, streamFollowStepPx(2f), 0.01f)
        assertEquals(0f, streamFollowStepPx(0f), 0.01f)
        assertEquals(-300f, conversationKeepItemTopDeltaPx(400, 100), 0.01f)
        assertEquals(0f, conversationKeepItemTopDeltaPx(null, 100), 0.01f)
        assertEquals(
            500f,
            conversationKeepItemTopDeltaPx(
                previousOffset = 0,
                currentOffset = 0,
                previousSize = 400,
                currentSize = 900,
            ),
            0.01f,
        )
        assertEquals(
            200f,
            conversationKeepItemTopDeltaPx(
                previousOffset = 10,
                currentOffset = 10,
                previousSize = 100,
                currentSize = 300,
            ),
            0.01f,
        )
    }
}
