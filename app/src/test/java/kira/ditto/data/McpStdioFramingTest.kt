package kira.ditto.data

import org.junit.Assert.assertTrue
import org.junit.Test

class McpStdioFramingTest {
    @Test
    fun requestForwarderUsesNewlineDelimitedJsonByDefault() {
        val script = StringBuilder().also(::appendMcpStdioRequestForwarder).toString()

        assertTrue(script.contains("AETHER_MCP_STDIO_FRAMING:-newline"))
        assertTrue(script.contains("printf '%s\\n' \"\$payload\""))
        assertTrue(script.contains("Content-Length: %s\\r\\n\\r\\n%s"))
    }

    @Test
    fun responseCollectorAcceptsNewlineAndLegacyContentLengthFrames() {
        val script = StringBuilder().also(::appendMcpStdioResponseCollector).toString()

        assertTrue(script.contains("while IFS= read -r first_line"))
        assertTrue(script.contains("Content-Length:*"))
        assertTrue(script.contains("payload=\"\$first_line\""))
        assertTrue(script.contains("dd bs=1 count=\"\$content_length\""))
    }
}
