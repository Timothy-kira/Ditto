package kira.ditto.data

import org.json.JSONObject

internal data class PixelCrop(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

/**
 * Screenshot crop and observe=som helpers. Coordinates are 0..1000 of the
 * virtual phone, independent of the JPEG pixel size.
 */
internal object AgentModeCapture {
    fun observeMode(arguments: JSONObject): String {
        if (!arguments.has("observe")) return ""
        return when (val raw = arguments.opt("observe")) {
            is Boolean -> if (raw) "full" else ""
            is Number -> if (raw.toInt() != 0) "full" else ""
            is String -> when (val value = raw.trim().lowercase()) {
                "true", "1" -> "full"
                "false", "0", "" -> ""
                "ax", "text", "image", "full", "som", "delta" -> value
                else -> ""
            }
            null -> ""
            else -> if (arguments.optBoolean("observe", false)) "full" else ""
        }
    }

    fun wantsObserve(arguments: JSONObject): Boolean = observeMode(arguments).isNotBlank()

    fun wantsJpeg(arguments: JSONObject): Boolean =
        observeMode(arguments) in setOf("full", "image", "som")

    fun wantsSom(arguments: JSONObject): Boolean = observeMode(arguments) == "som"

    fun wantsTextTree(arguments: JSONObject): Boolean = observeMode(arguments) == "text"

    fun wantsDeltaTree(arguments: JSONObject): Boolean = observeMode(arguments) == "delta"

    fun pixelCrop(imageWidth: Int, imageHeight: Int, arguments: JSONObject): PixelCrop? {
        if (imageWidth <= 1 || imageHeight <= 1) return null
        val normalized = normalizedCrop(arguments) ?: return null
        val left = (normalized[0] * imageWidth / 1000).coerceIn(0, imageWidth - 1)
        val top = (normalized[1] * imageHeight / 1000).coerceIn(0, imageHeight - 1)
        val right = (normalized[2] * imageWidth / 1000).coerceIn(left + 1, imageWidth)
        val bottom = (normalized[3] * imageHeight / 1000).coerceIn(top + 1, imageHeight)
        val width = (right - left).coerceAtLeast(1)
        val height = (bottom - top).coerceAtLeast(1)
        if (width >= imageWidth && height >= imageHeight && left == 0 && top == 0) return null
        return PixelCrop(left = left, top = top, width = width, height = height)
    }

    fun normalizedCrop(arguments: JSONObject): IntArray? {
        val hasExplicit = arguments.has("crop_left") ||
            arguments.has("crop_top") ||
            arguments.has("crop_right") ||
            arguments.has("crop_bottom")
        val region = arguments.optString("crop_region").ifBlank {
            if (hasExplicit) "" else arguments.optString("region")
        }.trim().lowercase()
        if (hasExplicit) {
            return intArrayOf(
                arguments.optInt("crop_left", 0).coerceIn(0, 1000),
                arguments.optInt("crop_top", 0).coerceIn(0, 1000),
                arguments.optInt("crop_right", 1000).coerceIn(0, 1000),
                arguments.optInt("crop_bottom", 1000).coerceIn(0, 1000),
            )
        }
        return when (region) {
            "top" -> intArrayOf(0, 0, 1000, 334)
            "middle" -> intArrayOf(0, 334, 1000, 666)
            "bottom" -> intArrayOf(0, 666, 1000, 1000)
            "left" -> intArrayOf(0, 0, 500, 1000)
            "right" -> intArrayOf(500, 0, 1000, 1000)
            else -> null
        }
    }
}
