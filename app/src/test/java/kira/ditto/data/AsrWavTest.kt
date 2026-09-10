package kira.ditto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrWavTest {
    @Test
    fun pcmToWavWritesRiffHeaderAndPayload() {
        val pcm = ByteArray(320)
        val wav = AsrWav.pcm16leMonoToWav(pcm, sampleRateHz = 16_000)
        assertEquals(44 + pcm.size, wav.size)
        assertEquals("RIFF", wav.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals("WAVE", wav.copyOfRange(8, 12).toString(Charsets.US_ASCII))
        assertTrue(wav.copyOfRange(44, wav.size).contentEquals(pcm))
    }

    @Test
    fun pcm16LeRmsRisesWithLouderSamples() {
        val quiet = ByteArray(32)
        val loud = ByteArray(32)
        for (index in loud.indices step 2) {
            loud[index] = 0x00.toByte()
            loud[index + 1] = 0x40.toByte()
        }
        assertTrue(pcm16LeRms(loud) > pcm16LeRms(quiet))
        val pcmArgs = arrayOf<Any>(loud, 0f)
        assertTrue(hmsVoiceLevel(pcmArgs) > hmsVoiceLevel(arrayOf(quiet, 0f)))
    }

    @Test
    fun hmsAsrTextPrefersRecognizingKeys() {
        val values = mapOf(
            "RESULTS" to "",
            "results_recognizing" to "你好",
            "results_recognized" to "你好。",
        )
        assertEquals("你好", hmsAsrTextFromKeys { values[it] })
        assertEquals("完成", hmsAsrTextFromKeys { key ->
            if (key == "results_recognized") "完成" else null
        })
        assertEquals(11, HmsAsrFeatureWordFlux)
    }
}
