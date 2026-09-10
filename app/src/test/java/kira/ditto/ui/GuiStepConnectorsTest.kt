package kira.ditto.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuiStepConnectorsTest {
    @Test
    fun sameRowLtrConnectsOuterEdgesWithoutCrossingChips() {
        val boxes = listOf(
            GuiStepChipBox(left = 0f, top = 0f, width = 40f, height = 20f),
            GuiStepChipBox(left = 60f, top = 0f, width = 40f, height = 20f),
        )
        val spec = guiStepConnectors(boxes, outsetPx = 3f, cornerRadiusPx = 10f).single()
        assertEquals(GuiStepCapsuleEdge.Right, spec.fromEdge)
        assertEquals(GuiStepCapsuleEdge.Left, spec.toEdge)
        assertEquals(43f, spec.startX, 0.01f)
        assertEquals(10f, spec.startY, 0.01f)
        assertEquals(57f, spec.endX, 0.01f)
        assertEquals(10f, spec.endY, 0.01f)
        assertTrue(spec.isStraight)
        assertTrue(spec.startX >= 40f)
        assertTrue(spec.endX <= 60f)
    }

    @Test
    fun wrapToNextRowUsesReturnSideU() {
        val boxes = listOf(
            GuiStepChipBox(left = 0f, top = 0f, width = 40f, height = 20f),
            GuiStepChipBox(left = 80f, top = 40f, width = 40f, height = 20f),
        )
        val spec = guiStepConnectors(
            boxes = boxes,
            rowOf = intArrayOf(0, 1),
            outsetPx = 3f,
            cornerRadiusPx = 10f,
        ).single()
        assertEquals(GuiStepCapsuleEdge.Right, spec.fromEdge)
        assertEquals(GuiStepCapsuleEdge.Right, spec.toEdge)
        assertEquals(4, spec.points.size)
        assertEquals(43f, spec.startX, 0.01f)
        assertEquals(10f, spec.startY, 0.01f)
        assertEquals(123f, spec.endX, 0.01f)
        assertEquals(50f, spec.endY, 0.01f)
        assertEquals(43f, spec.points[1].first, 0.01f)
        assertEquals(30f, spec.points[1].second, 0.01f)
        assertEquals(123f, spec.points[2].first, 0.01f)
        assertEquals(30f, spec.points[2].second, 0.01f)
        assertEquals(10f, spec.cornerRadiusPx, 0.01f)
        assertNull(spec.elbowX)
        assertTrue(spec.points.none { (x, y) -> x > 0f && x < 40f && y > 0f && y < 20f })
        assertTrue(spec.points.none { (x, y) -> x > 80f && x < 120f && y > 40f && y < 60f })
    }

    @Test
    fun rtlRowWrapsOnLeftReturnSide() {
        val boxes = listOf(
            GuiStepChipBox(left = 80f, top = 0f, width = 40f, height = 20f),
            GuiStepChipBox(left = 0f, top = 40f, width = 40f, height = 20f),
        )
        val spec = guiStepConnectors(
            boxes = boxes,
            rowOf = intArrayOf(1, 2),
            outsetPx = 3f,
            cornerRadiusPx = 10f,
        ).single()
        assertEquals(GuiStepCapsuleEdge.Left, spec.fromEdge)
        assertEquals(GuiStepCapsuleEdge.Left, spec.toEdge)
        assertEquals(4, spec.points.size)
        assertEquals(77f, spec.startX, 0.01f)
        assertEquals(-3f, spec.endX, 0.01f)
        assertEquals(10f, spec.cornerRadiusPx, 0.01f)
    }

    @Test
    fun stackedChipsOnWrapSideStayOutsideCapsules() {
        val boxes = listOf(
            GuiStepChipBox(left = 0f, top = 0f, width = 40f, height = 20f),
            GuiStepChipBox(left = 0f, top = 40f, width = 40f, height = 20f),
        )
        val spec = guiStepConnectors(
            boxes = boxes,
            rowOf = intArrayOf(0, 1),
            outsetPx = 3f,
            cornerRadiusPx = 10f,
        ).single()
        assertEquals(GuiStepCapsuleEdge.Right, spec.fromEdge)
        assertEquals(GuiStepCapsuleEdge.Right, spec.toEdge)
        assertEquals(43f, spec.startX, 0.01f)
        assertEquals(43f, spec.endX, 0.01f)
        assertTrue(spec.startX >= 40f)
        assertTrue(spec.endX >= 40f)
    }

    @Test
    fun rtlRowConnectsLeftThenRightOuterEdges() {
        val boxes = listOf(
            GuiStepChipBox(left = 80f, top = 0f, width = 40f, height = 20f),
            GuiStepChipBox(left = 0f, top = 0f, width = 40f, height = 20f),
        )
        val spec = guiStepConnectors(boxes, outsetPx = 3f, cornerRadiusPx = 10f).single()
        assertEquals(GuiStepCapsuleEdge.Left, spec.fromEdge)
        assertEquals(GuiStepCapsuleEdge.Right, spec.toEdge)
        assertEquals(77f, spec.startX, 0.01f)
        assertEquals(43f, spec.endX, 0.01f)
        assertTrue(spec.isStraight)
    }
}
