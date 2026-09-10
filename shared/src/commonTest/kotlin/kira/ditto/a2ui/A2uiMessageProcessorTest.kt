package kira.ditto.a2ui

import kira.ditto.upa.UpaNode
import kira.ditto.upa.upaNodeProps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class A2uiMessageProcessorTest {
    @Test
    fun processesCreateSurfaceAndComponents() {
        val payload = """
            {
              "createSurface": { "surfaceId": "weather", "catalogId": "a2ui.m3e.1.0" },
              "updateComponents": {
                "surfaceId": "weather",
                "components": [
                  {
                    "id": "root",
                    "component": { "Card": { "title": { "literalString": "Tokyo" } } },
                    "children": ["temp"]
                  },
                  {
                    "id": "temp",
                    "component": { "Text": { "text": { "literalString": "18°" }, "variant": "headlineLarge" } }
                  }
                ]
              },
              "beginRendering": { "surfaceId": "weather", "root": "root" }
            }
        """.trimIndent()
        val state = A2uiMessageProcessor().apply(payload)
        assertNotNull(state)
        assertEquals("weather", state.surfaceId)
        assertEquals(listOf("root"), state.rootIds)
        assertEquals("Card", state.components.getValue("root").type)
        assertEquals(listOf("temp"), state.components.getValue("root").children)
        assertEquals("temp", state.components.getValue("temp").id)
    }

    @Test
    fun compilesUpaGroupToCardAndHeroText() {
        val tree = UpaNode(
            id = "root",
            type = "group",
            children = listOf(
                UpaNode(
                    id = "temp",
                    type = "text",
                    props = upaNodeProps("text" to "18", "role" to "hero", "suffix" to "°"),
                ),
                UpaNode(
                    id = "go",
                    type = "action",
                    props = upaNodeProps("label" to "打开"),
                ),
            ),
        )
        val compiled = compileUpaTreeToA2ui(tree, surfaceId = "card")
        val state = A2uiMessageProcessor().apply(compiled)
        assertNotNull(state)
        assertEquals("Card", state.components.getValue("root").type)
        assertEquals("Text", state.components.getValue("temp").type)
        assertEquals("Button", state.components.getValue("go").type)
        assertTrue(compiled.contains("headlineLarge"))
    }

    @Test
    fun extractsA2uiMimeFromMcpContent() {
        val raw = """
            {"content":[{"type":"resource","mimeType":"application/a2ui+json","text":{"createSurface":{"surfaceId":"s1"}}}]}
        """.trimIndent()
        assertTrue(looksLikeA2uiPayload(raw))
        val extracted = extractA2uiPayload(raw)
        assertNotNull(extracted)
    }
}
