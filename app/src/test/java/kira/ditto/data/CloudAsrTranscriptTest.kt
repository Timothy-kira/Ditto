package kira.ditto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudAsrTranscriptTest {
    @Test
    fun parseReadsCommonSchemas() {
        assertEquals("你好", parseCloudAsrTranscript("""{"text":"你好"}"""))
        assertEquals("你好", parseCloudAsrTranscript("""{"result":{"text":"你好"}}"""))
        assertEquals("你好", parseCloudAsrTranscript("""{"results":[{"text":"你好"}]}"""))
        assertEquals("你好", parseCloudAsrTranscript("""{"data":{"text":"你好"}}"""))
        assertEquals("plain", parseCloudAsrTranscript("plain"))
        assertEquals("", parseCloudAsrTranscript("{}"))
        assertEquals("", parseCloudAsrTranscript(""))
    }

    @Test
    fun failedFetchMarkdownMentionsStatus() {
        val body = kira.ditto.browser.browserFetchFailedMarkdown(
            "https://example.com/x",
            "timeout",
            "load timed out",
        )
        assertTrue(body.contains("status: failed"))
        assertTrue(body.contains("timeout"))
    }
}
