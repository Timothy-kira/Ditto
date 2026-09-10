package kira.ditto.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AetherAppExtensionsTest {
    @Test
    fun disabledExtensionIdsBecomeStableLoadFilters() {
        val options = loadOptionsForIds(
            setOf(
                "package:npm:demo-extension@1.0.0",
                "import:aether:/root/.aether/extensions/global-skills-mod",
            )
        )

        assertEquals(
            setOf("npm:demo-extension@1.0.0"),
            options.disabledPackageSources,
        )
        assertEquals(
            setOf("/root/.aether/extensions/global-skills-mod"),
            options.disabledExtensionPaths,
        )
    }

    @Test
    fun unknownOrBlankExtensionIdsStayEnabledByDefault() {
        val options = loadOptionsForIds(setOf("", "not-an-extension-id"))

        assertTrue(options.disabledExtensionPaths.isEmpty())
        assertTrue(options.disabledPackageSources.isEmpty())
    }

    @Test
    fun migratesAndroidHostPathsToStableGuestPaths() {
        val hostPath = "/data/user/0/com.kira.ditto/files/runtimes/alpine/rootfs" +
            "/root/.aether/extensions/pi-mcp-adapter"

        assertEquals(
            "import:aether:/root/.aether/extensions/pi-mcp-adapter",
            normalizeExtensionStateId("import:aether:$hostPath"),
        )
        assertEquals(
            setOf("/root/.aether/extensions/pi-mcp-adapter"),
            loadOptionsForIds(setOf("import:aether:$hostPath")).disabledExtensionPaths,
        )
    }

    @Test
    fun parsesExtensionSnapshotAndOrdersSlots() {
        val snapshot = parseAetherAppExtensionSnapshot(
            JSONObject(
                """
                {
                  "api_version": 2,
                  "version": 7,
                  "extensions": [
                    {"id":"demo:1","name":"Demo","path":"/demo/index.ts"}
                  ],
                  "components": [
                    {
                      "id":"demo:1:tray",
                      "extension_id":"demo:1",
                      "extension_name":"Demo",
                      "target":"chat.composer.actionTray",
                      "mode":"wrap",
                      "order":4,
                      "tree":{"type":"core"}
                    }
                  ],
                  "settings": [
                    {"id":"demo:1:settings","local_id":"settings","extension_id":"demo:1","extension_name":"Demo","title":"Settings","sections":[],"categories":[{"id":"general","title":"General","sections":[]}]}
                  ],
                  "surfaces": [
                    {
                      "id":"demo:1:later",
                      "extension_id":"demo:1",
                      "extension_name":"Demo",
                      "slot":"chat.composer.top",
                      "order":20,
                      "tree":{"type":"text","text":"Later"}
                    },
                    {
                      "id":"demo:1:first",
                      "extension_id":"demo:1",
                      "extension_name":"Demo",
                      "slot":"chat.composer.top",
                      "order":1,
                      "tree":{"type":"text","text":"First"}
                    }
                  ],
                  "event_names":["before_send"],
                  "tool_titles":[
                    {
                      "id":"demo:1:web_search-1",
                      "extension_id":"demo:1",
                      "extension_name":"Demo",
                      "tool_name":"web_search",
                      "running_title":"Searching the web",
                      "completed_title":"Searched the web",
                      "priority":200,
                      "sequence":1
                    }
                  ],
                  "errors":[]
                }
                """.trimIndent()
            )
        )

        assertEquals(7L, snapshot.version)
        assertEquals("Demo", snapshot.extensions.single().name)
        assertEquals(
            listOf("demo:1:first", "demo:1:later"),
            snapshot.surfacesAt("chat.composer.top").map { it.id },
        )
        assertEquals(
            "wrap",
            snapshot.componentsAt("chat.composer.actionTray").single().mode,
        )
        assertEquals("general", snapshot.settings.single().categories.single().id)
        assertTrue("before_send" in snapshot.eventNames)
        assertEquals("Searching the web", snapshot.toolTitles.single().runningTitle)
        assertEquals(200, snapshot.toolTitles.single().priority)
    }

    @Test
    fun rejectedReloadSurfacesDistinctFactoryErrors() {
        val response = JSONObject(
            """
            {
              "reloaded": false,
              "errors": [
                {"error":"Factory failed"},
                {"error":"Factory failed"},
                {"error":"Cleanup failed"}
              ]
            }
            """.trimIndent()
        )

        assertEquals(
            "Factory failed; Cleanup failed",
            response.extensionReloadError(),
        )
    }

    @Test
    fun successfulReloadHasNoReloadError() {
        assertEquals(
            "",
            JSONObject().put("reloaded", true).extensionReloadError(),
        )
    }
}
