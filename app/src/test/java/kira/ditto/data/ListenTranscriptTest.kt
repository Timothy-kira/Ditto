package kira.ditto.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ListenTranscriptTest {
    @Test
    fun extractListenTranscriptReadsDirectAndNestedFields() {
        assertEquals(
            "第一节讲函数",
            extractListenTranscript("""{"ok":true,"transcript":"第一节讲函数"}"""),
        )
        assertEquals(
            "第二节",
            extractListenTranscript("""{"structuredContent":{"transcript":"第二节"}}"""),
        )
        assertEquals(
            "更长的转写",
            extractListenTranscript(
                """[{"transcript":"短"},{"transcript":"更长的转写"}]""",
            ),
        )
        assertEquals("", extractListenTranscript("""{"ok":true}"""))
    }

    @Test
    fun resolveAgentModeListenBodyTextPrefersTranscriptThenWaiting() {
        assertEquals(
            "课上讲了导数",
            resolveAgentModeListenBodyText(
                transcript = "课上讲了导数",
                listening = false,
                visualOnly = true,
                waitingText = "正在听课…",
                visualOnlyText = "视觉盯课 / 无内录",
            ),
        )
        assertEquals(
            "正在听课…",
            resolveAgentModeListenBodyText(
                transcript = "",
                listening = true,
                visualOnly = false,
                waitingText = "正在听课…",
                visualOnlyText = "视觉盯课 / 无内录",
            ),
        )
        assertEquals(
            "视觉盯课 / 无内录",
            resolveAgentModeListenBodyText(
                transcript = "",
                listening = false,
                visualOnly = true,
                waitingText = "正在听课…",
                visualOnlyText = "视觉盯课 / 无内录",
            ),
        )
        assertEquals(
            "正在听课…",
            resolveAgentModeListenBodyText(
                transcript = "",
                listening = false,
                visualOnly = false,
                waitingText = "正在听课…",
                visualOnlyText = "视觉盯课 / 无内录",
            ),
        )
    }
}
