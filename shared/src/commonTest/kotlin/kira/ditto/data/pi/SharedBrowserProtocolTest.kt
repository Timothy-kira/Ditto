package kira.ditto.data.pi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SharedBrowserProtocolTest {
    @Test
    fun exposesUnifiedBrowserDefinition() {
        val definition = sharedBrowserToolDefinitions(enabled = true).single().jsonObject
        assertEquals(BrowserToolName, definition["name"]?.jsonPrimitive?.content)

        val actions = definition["parameters"]
            ?.jsonObject
            ?.get("properties")
            ?.jsonObject
            ?.get("action")
            ?.jsonObject
            ?.get("enum")
            ?.jsonArray
            ?.map { it.jsonPrimitive.content }
            .orEmpty()

        assertTrue("click" in actions)
        assertTrue("get_backbone" in actions)
        assertTrue("wait_for_dom_stable" in actions)
        assertTrue("hover" in actions)
        assertTrue("scroll_and_collect" in actions)
        assertTrue("set_user_agent" in actions)
        assertTrue("set_viewport" in actions)
        assertTrue("new_tab" in actions)
        assertTrue("close_tab" in actions)
        assertTrue("list_tabs" in actions)
        assertFalse("tap" in actions)
    }

    @Test
    fun selectorAndTextAreSafelyEncodedInScripts() {
        val click = browserClickScript("button[data-name=\"a'b\"]", null, null)
        val type = browserTypeScript("#prompt", "line 1\n'line 2'")

        assertTrue(click.contains("button[data-name=\\\"a'b\\\"]"))
        assertTrue(type.contains("line 1\\n'line 2'"))
        assertFalse(type.contains("line 1\n'line 2'"))
    }

    @Test
    fun advancedBrowserScriptsUseInteractionAndQuietCollectionPrimitives() {
        assertTrue(browserHoverScript("button", null, null).contains("pointerenter"))
        assertTrue(browserScrollAndCollectScript(10, 600).contains("scrollBy"))
        assertTrue(browserSetViewportScript(390, 844).contains("meta[name=\"viewport\"]"))
    }
}
