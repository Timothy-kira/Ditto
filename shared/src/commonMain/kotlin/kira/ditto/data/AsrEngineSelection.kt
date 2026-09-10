package kira.ditto.data

object AsrDeviceModelKeys {
    const val Auto = "device:auto"
    const val Hms = "device:hms"
    const val MlKit = "device:mlkit"
    const val System = "device:system"

    val All: List<String> = listOf(Auto, Hms, MlKit, System)

    fun isDeviceKey(key: String): Boolean = key.trim().startsWith("device:")
}

enum class AsrAvailability {
    Available,
    Downloadable,
    Unavailable,
}

enum class AsrEngineKind {
    Hms,
    MlKit,
    System,
    Cloud,
}

data class AsrCapabilitySnapshot(
    val hms: AsrAvailability = AsrAvailability.Unavailable,
    val mlKit: AsrAvailability = AsrAvailability.Unavailable,
    val system: AsrAvailability = AsrAvailability.Unavailable,
) {
    fun forKind(kind: AsrEngineKind): AsrAvailability = when (kind) {
        AsrEngineKind.Hms -> hms
        AsrEngineKind.MlKit -> mlKit
        AsrEngineKind.System -> system
        AsrEngineKind.Cloud -> AsrAvailability.Available
    }

    fun firstReadyDevice(): AsrEngineKind? = listOf(
        AsrEngineKind.Hms,
        AsrEngineKind.MlKit,
        AsrEngineKind.System,
    ).firstOrNull { forKind(it).isUsable }
}

val AsrAvailability.isUsable: Boolean
    get() = this == AsrAvailability.Available || this == AsrAvailability.Downloadable

fun resolveAsrEngineKind(
    key: String,
    snapshot: AsrCapabilitySnapshot,
    catalogHasModel: Boolean,
): AsrEngineKind? {
    val trimmed = key.trim()
    if (trimmed.isBlank()) return null
    return when (trimmed) {
        AsrDeviceModelKeys.Auto -> snapshot.firstReadyDevice()
        AsrDeviceModelKeys.Hms -> AsrEngineKind.Hms.takeIf { snapshot.hms.isUsable }
        AsrDeviceModelKeys.MlKit -> AsrEngineKind.MlKit.takeIf { snapshot.mlKit.isUsable }
        AsrDeviceModelKeys.System -> AsrEngineKind.System.takeIf { snapshot.system.isUsable }
        else -> if (catalogHasModel) AsrEngineKind.Cloud else null
    }
}

fun normalizeAsrModelKey(
    key: String,
    catalogOptions: List<ProviderModelOption>,
): String {
    val trimmed = key.trim()
    if (trimmed.isBlank()) return ""
    if (AsrDeviceModelKeys.isDeviceKey(trimmed)) {
        return if (trimmed in AsrDeviceModelKeys.All) trimmed else AsrDeviceModelKeys.Auto
    }
    return if (catalogOptions.any { it.key == trimmed }) trimmed else ""
}

object AsrChunkAssembler {
    const val DefaultChunkMs = 50_000L
    const val SampleRateHz = 16_000
    const val BytesPerSample = 2

    fun pcmDurationMs(byteCount: Int, sampleRateHz: Int = SampleRateHz): Long {
        val bytesPerSecond = sampleRateHz.toLong() * BytesPerSample
        if (bytesPerSecond <= 0L || byteCount <= 0) return 0L
        return (byteCount * 1_000L) / bytesPerSecond
    }

    fun shouldFlush(
        bufferedBytes: Int,
        maxMs: Long = DefaultChunkMs,
        sampleRateHz: Int = SampleRateHz,
    ): Boolean = pcmDurationMs(bufferedBytes, sampleRateHz) >= maxMs

    fun joinTranscripts(parts: List<String>): String {
        val cleaned = parts.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return ""
        return cleaned.reduce(::mergeOverlappingTranscripts)
    }

    fun mergeOverlappingTranscripts(left: String, right: String): String {
        val a = left.trim()
        val b = right.trim()
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        if (a == b) return a
        if (b.startsWith(a)) return b
        if (a.endsWith(b)) return a
        if (a.length >= 4 && b.contains(a)) return b
        if (b.length >= 4 && a.contains(b)) return a
        val minOverlap = if (isCjkChar(a.last()) && isCjkChar(b.first())) 2 else 4
        val maxOverlap = minOf(a.length, b.length)
        for (len in maxOverlap downTo minOverlap) {
            if (a.regionMatches(a.length - len, b, 0, len, ignoreCase = true)) {
                return a + b.substring(len)
            }
        }
        return "$a $b"
    }
}

private fun isCjkChar(character: Char): Boolean = character in '\u4e00'..'\u9fff'
