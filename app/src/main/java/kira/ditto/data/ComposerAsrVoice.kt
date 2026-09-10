package kira.ditto.data

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.sqrt

internal const val ComposerAsrLiveChunkMs = 1_400L
internal val ComposerAsrFullTranscribeMinBytes =
    AsrChunkAssembler.SampleRateHz * AsrChunkAssembler.BytesPerSample
internal const val ComposerAsrRelativeVoice = 0.28f
internal const val ComposerAsrRelativePeak = 0.22f
internal const val ComposerAsrMinVoicedMs = 160L
internal const val ComposerAsrWindowSize = 18
internal const val ComposerAsrQuietMaxRms = 0.028f
internal const val ComposerAsrEnergyVoiceRms = 0.03f
internal const val ComposerAsrDigitalSilenceRms = 0.018f

internal fun pcm16LeRmsRaw(buffer: ByteArray): Float {
    if (buffer.size < 4) return 0f
    var sum = 0.0
    var samples = 0
    var index = 0
    while (index + 1 < buffer.size) {
        val sample = (buffer[index].toInt() and 0xff) or (buffer[index + 1].toInt() shl 8)
        val signed = sample.toShort().toInt()
        sum += signed.toDouble() * signed.toDouble()
        samples += 1
        index += 2
    }
    if (samples == 0) return 0f
    return (kotlin.math.sqrt(sum / samples) / 4_000.0).toFloat().coerceAtLeast(0f)
}

/** Map a 0–1 relative envelope to bar height; sqrt so small relative jumps still read. */
internal fun composerAsrVisualLevel(relative: Float): Float {
    val shaped = sqrt(relative.coerceIn(0f, 1f))
    return 0.08f + shaped * 0.92f
}

/**
 * Normalize [current] against recent RMS so a quiet talker still fills the range,
 * while a flat noise floor stays near zero.
 */
internal fun composerAsrRelativeFromWindow(current: Float, recent: List<Float>): Float {
    if (recent.isEmpty()) return 0f
    val values = ArrayList<Float>(recent.size + 1)
    values.addAll(recent)
    if (recent.none { it == current }) values.add(current)
    val sorted = values.sorted()
    val min = sorted.first()
    val max = sorted.last()
    val mean = values.average().toFloat()
    val dynamic = max - min
    val quiet = max < ComposerAsrQuietMaxRms && mean < ComposerAsrQuietMaxRms * 0.85f
    if (quiet) {
        return ((current - min) / 0.045f).coerceIn(0f, 0.18f)
    }
    val span = dynamic.coerceAtLeast(max * 0.10f).coerceAtLeast(0.003f)
    val local = ((current - min) / span).coerceIn(0f, 1f)
    if (dynamic < max * 0.08f) {
        return (0.42f + 0.50f * local).coerceIn(0.42f, 1f)
    }
    return local
}

internal fun composerAsrHadSpeech(peakRelative: Float, voicedMs: Long): Boolean =
    voicedMs >= ComposerAsrMinVoicedMs && peakRelative >= ComposerAsrRelativePeak

private val ComposerAsrFillerOnly = setOf(
    "嗯", "啊", "呃", "哦", "唔", "嗯嗯", "啊啊",
    "谢谢观看", "谢谢收看", "字幕", "谢谢",
)

private val ComposerAsrGreetingNeedles = listOf(
    "请问有什么可以",
    "有什么可以帮助您",
    "谢谢观看",
    "谢谢收看",
)

internal fun composerAsrLooksLikeHallucination(text: String): Boolean {
    val trimmed = text.trim().trimEnd('.', '。', '!', '！', '?', '？', ',', '，', '、', '…')
    if (trimmed.isEmpty()) return true
    if (trimmed in ComposerAsrFillerOnly) return true
    return composerAsrLooksLikeGreetingHallucination(trimmed)
}

internal fun composerAsrLooksLikeGreetingHallucination(text: String): Boolean {
    val trimmed = text.trim().trimEnd('.', '。', '!', '！', '?', '？', ',', '，', '、', '…')
    return ComposerAsrGreetingNeedles.any { trimmed.contains(it) }
}

internal fun composerAsrAcceptTranscript(
    text: String,
    hadVoice: Boolean,
): String {
    if (!hadVoice) return ""
    val trimmed = composerAsrCollapseRepeats(text.trim())
    if (trimmed.isEmpty()) return ""
    if (composerAsrLooksLikeGreetingHallucination(trimmed)) return ""
    return trimmed
}

internal fun composerAsrCollapseRepeats(text: String): String {
    var result = text.trim()
    if (result.isEmpty()) return result
    val pattern = Regex("(.{2,12}?)\\1+")
    var previous = ""
    var guard = 0
    while (previous != result && guard < 8) {
        previous = result
        result = pattern.replace(result) { match -> match.groupValues[1] }
        guard++
    }
    return result
}

internal class ComposerAsrVoiceGate {
    private val lock = Any()
    private val window = ArrayDeque<Float>(ComposerAsrWindowSize + 2)
    private val lastRelative = AtomicReference(0f)
    private val peakRelative = AtomicReference(0f)
    private val peakRaw = AtomicReference(0f)
    private val voicedMs = AtomicLong(0L)

    fun onPcm(pcm: ByteArray): Float {
        val rms = pcm16LeRmsRaw(pcm)
        val relative = synchronized(lock) {
            window.addLast(rms)
            while (window.size > ComposerAsrWindowSize) window.removeFirst()
            composerAsrRelativeFromWindow(rms, window.toList())
        }
        lastRelative.set(relative)
        peakRelative.updateAndGet { current -> max(current, relative) }
        peakRaw.updateAndGet { current -> max(current, rms) }
        if (relative >= ComposerAsrRelativeVoice || rms >= ComposerAsrEnergyVoiceRms) {
            voicedMs.addAndGet(AsrChunkAssembler.pcmDurationMs(pcm.size).coerceAtLeast(1L))
        }
        return relative
    }

    fun lastRelative(): Float = lastRelative.get()

    fun peak(): Float = peakRelative.get()

    fun peakRaw(): Float = peakRaw.get()

    fun voicedMillis(): Long = voicedMs.get()

    fun hadSpeech(): Boolean =
        composerAsrHadSpeech(peak(), voicedMillis()) ||
            (peakRaw() >= ComposerAsrEnergyVoiceRms && voicedMillis() >= ComposerAsrMinVoicedMs)

    fun isDigitalSilence(): Boolean = peakRaw() < ComposerAsrDigitalSilenceRms

    fun relativeOf(pcm: ByteArray): Float {
        val rms = pcm16LeRmsRaw(pcm)
        return synchronized(lock) {
            composerAsrRelativeFromWindow(rms, window.toList())
        }
    }
}
