package kira.ditto.ui

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CharacterFadeTest {
    @Test
    fun bodyHighlightPutsWhiteOnNewestGlyphs() {
        val newest = characterFadeSpanColor(
            progress = 0f,
            settled = Color(0xFFF3F1EC),
            highlightLatest = true,
        )
        val settled = characterFadeSpanColor(
            progress = 1f,
            settled = Color(0xFFF3F1EC),
            highlightLatest = true,
        )
        assertEquals(Color.White, newest)
        assertEquals(Color(0xFFF3F1EC), settled)
    }

    @Test
    fun thinkingDoesNotUseWhiteHighlight() {
        val newest = characterFadeSpanColor(
            progress = 0f,
            settled = Color(0xFFB9B4AA),
            highlightLatest = false,
        )
        assertTrue(newest.alpha < 0.01f)
    }
}
