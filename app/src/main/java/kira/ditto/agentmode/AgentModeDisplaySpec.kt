package kira.ditto.agentmode

import kotlin.math.roundToInt

internal const val AgentModeDefaultMaxShortEdge = 1080
private const val AgentModeMinDensityDpi = 120

data class AgentModeDisplaySpec(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
)

/**
 * Fit a virtual display to this phone's aspect ratio, capped at 1080p on the
 * short edge. Never upscales a panel that is already 1080-wide or narrower.
 */
internal fun scaleAgentModeDisplaySpec(
    physicalWidth: Int,
    physicalHeight: Int,
    densityDpi: Int,
    maxShortEdge: Int = AgentModeDefaultMaxShortEdge,
): AgentModeDisplaySpec {
    val width = physicalWidth.coerceAtLeast(1)
    val height = physicalHeight.coerceAtLeast(1)
    val shortEdge = minOf(width, height)
    val longEdge = maxOf(width, height)
    val portrait = height >= width
    val dpi = densityDpi.coerceAtLeast(AgentModeMinDensityDpi)
    val cap = maxShortEdge.coerceAtLeast(2)
    if (shortEdge <= cap) {
        return AgentModeDisplaySpec(
            width = evenDimension(width),
            height = evenDimension(height),
            densityDpi = dpi,
        )
    }
    val scale = cap.toDouble() / shortEdge.toDouble()
    val newShort = evenDimension(cap)
    val newLong = evenDimension((longEdge * scale).roundToInt().coerceAtLeast(1))
    val newDpi = (dpi * newShort.toDouble() / shortEdge.toDouble())
        .roundToInt()
        .coerceAtLeast(AgentModeMinDensityDpi)
    return if (portrait) {
        AgentModeDisplaySpec(width = newShort, height = newLong, densityDpi = newDpi)
    } else {
        AgentModeDisplaySpec(width = newLong, height = newShort, densityDpi = newDpi)
    }
}

private fun evenDimension(value: Int): Int {
    val n = value.coerceAtLeast(2)
    return n and 1.inv()
}
