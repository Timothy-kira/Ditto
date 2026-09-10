package kira.ditto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class GmailAuthTest {
    @Test
    fun parsesAuthorizationCodeFromGetLine() {
        val callback = gmailOAuthCallbackFromRequestLine(
            "GET /gmail-oauth?code=4%2F0Abc&scope=mail HTTP/1.1",
        )
        assertEquals("4/0Abc", callback.code)
        assertEquals("", callback.error)
        assertFalse(callback.isIgnorable)
    }

    @Test
    fun ignoresFaviconAndEmptyRequests() {
        assertTrue(
            gmailOAuthCallbackFromRequestLine("GET /favicon.ico HTTP/1.1").isIgnorable,
        )
        assertTrue(
            gmailOAuthCallbackFromRequestLine("GET /gmail-oauth HTTP/1.1").isIgnorable,
        )
    }

    @Test
    fun mapsBrokenPipeToChineseRetry() {
        assertEquals(
            "登录回调被浏览器中断，请重试。",
            gmailOAuthUserMessage(IOException("Broken pipe")),
        )
    }
}
