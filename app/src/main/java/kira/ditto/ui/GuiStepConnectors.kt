package kira.ditto.ui

import kotlin.math.abs

internal data class GuiStepChipBox(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
    val centerX: Float get() = left + width / 2f
    val centerY: Float get() = top + height / 2f
}

internal enum class GuiStepCapsuleEdge {
    Left,
    Right,
    Top,
    Bottom,
}

internal data class GuiStepConnectorSpec(
    val points: List<Pair<Float, Float>>,
    val fromEdge: GuiStepCapsuleEdge,
    val toEdge: GuiStepCapsuleEdge,
    val cornerRadiusPx: Float = 0f,
) {
    val startX: Float get() = points.first().first
    val startY: Float get() = points.first().second
    val endX: Float get() = points.last().first
    val endY: Float get() = points.last().second
    val isStraight: Boolean get() = points.size == 2
    val elbowX: Float? get() = points.getOrNull(1)?.first?.takeIf { points.size == 3 }
    val elbowY: Float? get() = points.getOrNull(1)?.second?.takeIf { points.size == 3 }
}

internal fun guiStepConnectors(
    boxes: List<GuiStepChipBox>,
    rowOf: IntArray,
    outsetPx: Float,
    cornerRadiusPx: Float,
): List<GuiStepConnectorSpec> {
    if (boxes.size < 2) return emptyList()
    val gap = outsetPx.coerceAtLeast(0f)
    val radius = cornerRadiusPx.coerceAtLeast(0f)
    return buildList {
        for (index in 0 until boxes.lastIndex) {
            add(
                guiStepConnector(
                    from = boxes[index],
                    to = boxes[index + 1],
                    fromRow = rowOf.getOrElse(index) { 0 },
                    toRow = rowOf.getOrElse(index + 1) { 0 },
                    gap = gap,
                    cornerRadiusPx = radius,
                ),
            )
        }
    }
}

internal fun guiStepConnectors(
    boxes: List<GuiStepChipBox>,
    outsetPx: Float,
    cornerRadiusPx: Float,
): List<GuiStepConnectorSpec> {
    val rowOf = IntArray(boxes.size)
    var row = 0
    for (index in 1 until boxes.size) {
        val previous = boxes[index - 1]
        val current = boxes[index]
        val sameRow = abs(previous.centerY - current.centerY) <= maxOf(previous.height, current.height) * 0.55f
        if (!sameRow) row += 1
        rowOf[index] = row
    }
    return guiStepConnectors(boxes, rowOf, outsetPx, cornerRadiusPx)
}

internal fun connectorPort(
    box: GuiStepChipBox,
    edge: GuiStepCapsuleEdge,
    gap: Float,
): Pair<Float, Float> {
    val cx = box.centerX
    val cy = box.centerY
    return when (edge) {
        GuiStepCapsuleEdge.Left -> (box.left - gap) to cy
        GuiStepCapsuleEdge.Right -> (box.right + gap) to cy
        GuiStepCapsuleEdge.Top -> cx to (box.top - gap)
        GuiStepCapsuleEdge.Bottom -> cx to (box.bottom + gap)
    }
}

private fun wrapSide(rowIndex: Int): GuiStepCapsuleEdge =
    if (rowIndex % 2 == 0) GuiStepCapsuleEdge.Right else GuiStepCapsuleEdge.Left

private fun guiStepConnector(
    from: GuiStepChipBox,
    to: GuiStepChipBox,
    fromRow: Int,
    toRow: Int,
    gap: Float,
    cornerRadiusPx: Float,
): GuiStepConnectorSpec {
    if (fromRow == toRow) {
        val fromEdge = if (to.centerX >= from.centerX) {
            GuiStepCapsuleEdge.Right
        } else {
            GuiStepCapsuleEdge.Left
        }
        val toEdge = if (fromEdge == GuiStepCapsuleEdge.Right) {
            GuiStepCapsuleEdge.Left
        } else {
            GuiStepCapsuleEdge.Right
        }
        return GuiStepConnectorSpec(
            points = listOf(connectorPort(from, fromEdge, gap), connectorPort(to, toEdge, gap)),
            fromEdge = fromEdge,
            toEdge = toEdge,
        )
    }
    val side = wrapSide(fromRow)
    val start = connectorPort(from, side, gap)
    val end = connectorPort(to, side, gap)
    val gutterY = (from.bottom + to.top) / 2f
    val points = listOf(
        start,
        start.first to gutterY,
        end.first to gutterY,
        end,
    ).collapseColinear()
    return GuiStepConnectorSpec(
        points = points,
        fromEdge = side,
        toEdge = side,
        cornerRadiusPx = if (points.size > 2) cornerRadiusPx else 0f,
    )
}

private fun List<Pair<Float, Float>>.collapseColinear(): List<Pair<Float, Float>> {
    if (size <= 2) return this
    val collapsed = mutableListOf(first())
    for (index in 1 until lastIndex) {
        val previous = collapsed.last()
        val current = this[index]
        val next = this[index + 1]
        val sameX = abs(previous.first - current.first) < 0.5f && abs(current.first - next.first) < 0.5f
        val sameY = abs(previous.second - current.second) < 0.5f && abs(current.second - next.second) < 0.5f
        if (sameX || sameY) continue
        collapsed += current
    }
    collapsed += last()
    return collapsed
}
