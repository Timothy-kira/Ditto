package kira.ditto.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpaToolInterceptTest {
    @Test
    fun nativeA2uiRendersWithoutPluginBinding() {
        val payload = """
            {
              "createSurface": { "surfaceId": "weather", "catalogId": "a2ui.m3e.1.0" },
              "updateComponents": {
                "surfaceId": "weather",
                "components": [
                  {
                    "id": "root",
                    "component": { "Text": { "text": { "literalString": "18°" } } }
                  }
                ]
              },
              "beginRendering": { "surfaceId": "weather", "root": "root" }
            }
        """.trimIndent()
        val wrapped = """{"content":[{"type":"resource","mimeType":"application/a2ui+json","text":$payload}]}"""
        val cards = resolveA2uiChatCards(
            invocations = listOf(
                ChatToolInvocation(
                    id = "call-1",
                    toolName = "get_weather",
                    argumentsJson = "{}",
                    outputJson = wrapped,
                ),
            ),
            library = null,
            userRenderUi = true,
            disabledUiPlugins = emptySet(),
            mcpBindings = emptyMap(),
        )
        assertEquals(1, cards.size)
        assertEquals("weather", cards.single().state.surfaceId)
        assertEquals("Text", cards.single().state.components.getValue("root").type)
    }

    @Test
    fun unboundPluginLeavesPlainToolOutput() {
        val cards = resolveA2uiChatCards(
            invocations = listOf(
                ChatToolInvocation(
                    id = "call-2",
                    toolName = "get_weather",
                    argumentsJson = "{}",
                    outputJson = """{"now":{"temp":"18","text":"晴"}}""",
                ),
            ),
            library = null,
            userRenderUi = true,
            disabledUiPlugins = emptySet(),
            mcpBindings = emptyMap(),
        )
        assertTrue(cards.isEmpty())
    }
}
