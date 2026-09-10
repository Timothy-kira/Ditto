package kira.ditto.ui

import androidx.compose.ui.unit.dp
import kira.ditto.data.AmapPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmapPlaceUiTest {
    @Test
    fun photoLoadsSettledWaitsForEveryUrl() {
        val place = AmapPlace(
            id = "B001",
            name = "莱蒂尔",
            photos = listOf("https://a.jpg", "https://b.jpg"),
        )
        assertFalse(place.photoLoadsSettled(emptyMap()))
        assertFalse(
            place.photoLoadsSettled(
                mapOf("https://a.jpg" to MarkdownImageLoadResult(error = "x")),
            ),
        )
        assertTrue(
            place.photoLoadsSettled(
                mapOf(
                    "https://a.jpg" to MarkdownImageLoadResult(error = "x"),
                    "https://b.jpg" to MarkdownImageLoadResult(error = "y"),
                ),
            ),
        )
        assertTrue(AmapPlace(id = "B002", name = "银泰城").photoLoadsSettled(emptyMap()))
    }

    @Test
    fun carouselUsesTwoAndHalfSquareEvenForOnePhoto() {
        val maxWidth = 360.dp
        val gap = 8.dp
        val expected = ((360f - 16f) / 2.5f).toInt()
        assertEquals(expected, amapPlaceCarouselItemWidth(maxWidth, gap).value.toInt())
        assertEquals(expected, amapPlaceCarouselHeight(maxWidth, gap).value.toInt())
        assertEquals(
            amapPlaceCarouselItemWidth(maxWidth, gap),
            amapPlaceCarouselItemWidth(maxWidth, gap),
        )
        assertEquals(
            amapPlaceCarouselItemWidth(maxWidth, gap).value.toInt(),
            ((360f - 16f) / 2.5f).toInt(),
        )
        assertTrue(amapPlaceCarouselUsesExactSquares(1))
        assertTrue(amapPlaceCarouselUsesExactSquares(2))
        assertFalse(amapPlaceCarouselUsesExactSquares(3))
        assertEquals(
            amapPlaceCarouselItemWidth(maxWidth, gap),
            amapPlaceCarouselHeight(maxWidth, gap),
        )
    }

    @Test
    fun parseMarkdownCreatesAmapCardBlocks() {
        val blocks = parseMarkdownBlocks(
            """
            前面

            [[amap-cards:4|B004,B005,B006]]

            后面
            """.trimIndent(),
        )
        val cards = blocks.filterIsInstance<MarkdownBlock.AmapCards>().single()
        assertEquals(4, cards.startIndex)
        assertEquals(listOf("B004", "B005", "B006"), cards.placeIds)
    }
}
