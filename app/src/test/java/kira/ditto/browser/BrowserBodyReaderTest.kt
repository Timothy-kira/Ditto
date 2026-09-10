package kira.ditto.browser

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InterruptedIOException
import org.junit.Assert.*
import org.junit.Test

class BrowserBodyReaderTest {
    @Test fun boundsAnUnendingResponse() {
        var consumed = 0
        val stream = object : InputStream() {
            override fun read(): Int { consumed++; return 65 }
        }
        val result = readBrowserBody(stream, 100, Long.MAX_VALUE)
        assertEquals(100, consumed)
        assertEquals(100, result.bytes.size)
        assertTrue(result.truncated)
    }

    @Test fun preservesCompleteBody() {
        val bytes = "中文正文".toByteArray()
        val result = readBrowserBody(ByteArrayInputStream(bytes), 100, Long.MAX_VALUE)
        assertArrayEquals(bytes, result.bytes)
        assertFalse(result.truncated)
    }

    @Test(expected = InterruptedIOException::class)
    fun expiredDeadlineDoesNotRead() {
        readBrowserBody(object : InputStream() {
            override fun read(): Int = error("Must not read after deadline")
        }, 100, 10) { 10 }
    }
}
