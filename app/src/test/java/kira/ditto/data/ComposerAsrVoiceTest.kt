package kira.ditto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerAsrVoiceTest {
    @Test
    fun silenceIsRejectedEvenIfModelHallucinates() {
        assertEquals(
            "",
            composerAsrAcceptTranscript("你好请问有什么可以帮助您的", hadVoice = false),
        )
        assertEquals(
            "",
            composerAsrAcceptTranscript("你好请问有什么可以帮助您的", hadVoice = true),
        )
        assertEquals(
            "嗯。",
            composerAsrAcceptTranscript("嗯。", hadVoice = true),
        )
        assertEquals(
            "把灯关掉",
            composerAsrAcceptTranscript("把灯关掉", hadVoice = true),
        )
        assertEquals(
            "去西湖",
            composerAsrAcceptTranscript("去西湖去西湖去西湖", hadVoice = true),
        )
        assertEquals("你好", composerAsrCollapseRepeats("你好你好你好"))
    }

    @Test
    fun greetingHallucinationsAreDetected() {
        assertTrue(composerAsrLooksLikeHallucination("你好请问有什么可以帮助您的"))
        assertTrue(composerAsrLooksLikeHallucination("嗯"))
        assertTrue(composerAsrLooksLikeHallucination("谢谢观看"))
        assertFalse(composerAsrLooksLikeHallucination("把明天的会议改到三点"))
        assertFalse(composerAsrLooksLikeHallucination("你好请问明天开会改到三点"))
        assertEquals(
            "你好请问明天开会改到三点",
            composerAsrAcceptTranscript("你好请问明天开会改到三点", hadVoice = true),
        )
    }

    @Test
    fun speechGateNeedsRelativeEnergyAndDuration() {
        assertFalse(composerAsrHadSpeech(peakRelative = 0.10f, voicedMs = 80L))
        assertFalse(composerAsrHadSpeech(peakRelative = 0.90f, voicedMs = 80L))
        assertTrue(composerAsrHadSpeech(peakRelative = 0.40f, voicedMs = 200L))
        assertTrue(composerAsrHadSpeech(peakRelative = 0.22f, voicedMs = 160L))
    }

    @Test
    fun relativeWindowMapsQuietSpeechToHighAndFlatNoiseToLow() {
        val floor = List(12) { 0.02f }
        val quietSpeech = composerAsrRelativeFromWindow(0.07f, floor + 0.07f)
        val louder = composerAsrRelativeFromWindow(0.09f, floor + listOf(0.05f, 0.07f, 0.09f))
        assertTrue(quietSpeech > 0.55f)
        assertTrue(louder > quietSpeech - 0.05f)
        val flat = composerAsrRelativeFromWindow(0.021f, List(12) { 0.02f })
        assertTrue(flat < 0.20f)
        val sustained = composerAsrRelativeFromWindow(0.08f, List(12) { 0.08f })
        assertTrue(sustained > 0.40f)
    }

    @Test
    fun visualLevelFollowsRelativeNotAbsolute() {
        val hush = composerAsrVisualLevel(0.08f)
        val crest = composerAsrVisualLevel(0.85f)
        assertTrue(crest > hush + 0.35f)
        assertTrue(composerAsrVisualLevel(0f) >= 0.08f)
        assertEquals(1f, composerAsrVisualLevel(1f), 0.001f)
    }

    @Test
    fun quietTalkerStillCountsAsSpeechAgainstSilenceFloor() {
        val gate = ComposerAsrVoiceGate()
        val silence = ByteArray(1_280)
        repeat(8) { gate.onPcm(silence) }
        assertFalse(gate.hadSpeech())
        val quiet = pcmWithAmplitude(bytes = 3_200, amplitude = 280)
        repeat(6) { gate.onPcm(quiet) }
        assertTrue(gate.lastRelative() > 0.45f)
        assertTrue(gate.hadSpeech())
        assertTrue(pcm16LeRmsRaw(quiet) < 0.14f)
    }

    @Test
    fun sustainedSpeechWithoutSilenceFloorStillCounts() {
        val gate = ComposerAsrVoiceGate()
        val speech = pcmWithAmplitude(bytes = 3_200, amplitude = 400)
        repeat(8) { gate.onPcm(speech) }
        assertTrue(gate.hadSpeech())
        assertFalse(gate.isDigitalSilence())
    }

    private fun pcmWithAmplitude(bytes: Int, amplitude: Int): ByteArray {
        val pcm = ByteArray(bytes)
        var index = 0
        while (index + 1 < pcm.size) {
            pcm[index] = (amplitude and 0xff).toByte()
            pcm[index + 1] = ((amplitude shr 8) and 0xff).toByte()
            index += 2
        }
        return pcm
    }
}
