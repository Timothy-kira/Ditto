package kira.ditto.upa

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class UpaProtocolTest {
    @Test
    fun parsesExampleWeatherManifest() {
        val result = parseUpaManifest(
            """
            {
              "upa": "0.1",
              "id": "example.weather.card",
              "name": "Weather Card",
              "version": "1.0.0",
              "capabilities": ["ui.surface", "tools"],
              "surfaces": [
                {
                  "id": "card",
                  "slot": "bubble",
                  "tree": {
                    "id": "root",
                    "type": "group",
                    "children": [
                      { "id": "title", "type": "text", "props": { "text": "Weather", "role": "title" } }
                    ]
                  }
                }
              ]
            }
            """.trimIndent(),
        )
        val ok = assertIs<UpaManifestResult.Ok>(result)
        assertEquals("example.weather.card", ok.manifest.id)
        assertEquals(UpaSlot.Bubble.wire, ok.manifest.surfaces.single().slot)
        assertEquals("group", ok.manifest.surfaces.single().tree?.type)
    }

    @Test
    fun parsesInAppDisplayNameAndIcon() {
        val result = parseUpaManifest(
            """
            {
              "upa": "0.1",
              "id": "example.weather.card",
              "name": "Weather Card",
              "displayName": "天气",
              "icon": "cloud-sun",
              "version": "1.0.0"
            }
            """.trimIndent(),
        )
        val ok = assertIs<UpaManifestResult.Ok>(result)
        assertEquals("天气", ok.manifest.inAppDisplayName())
        assertEquals("cloud-sun", ok.manifest.inAppIcon())
        assertEquals("健康", resolveUpaDisplayName("health.apps.card", "Health Apps", ""))
        assertEquals("Kaggle", resolveUpaDisplayName("kaggle.cli.card", "Kaggle CLI", ""))
        assertEquals("heart", resolveUpaIcon("health.apps.card", ""))
        assertEquals("kaggle", resolveUpaIcon("kaggle.cli.card", ""))
        assertEquals("Weather Card", resolveUpaDisplayName("example.weather.card", "Weather Card", ""))
    }

    @Test
    fun rejectsUnknownNodeType() {
        val result = parseUpaManifest(
            """
            {
              "upa": "0.1",
              "id": "bad.plugin",
              "name": "Bad",
              "version": "1.0.0",
              "surfaces": [
                {
                  "id": "x",
                  "slot": "bubble",
                  "tree": { "id": "root", "type": "iframe" }
                }
              ]
            }
            """.trimIndent(),
        )
        assertIs<UpaManifestResult.Err>(result)
    }

    @Test
    fun acceptsRegisteredCustomElement() {
        val result = parseUpaManifest(
            """
            {
              "upa": "0.1",
              "id": "example.weather.card",
              "name": "Weather",
              "version": "1.0.0",
              "permissions": ["html.sandbox"],
              "catalogs": [
                {
                  "id": "example.weather.widgets",
                  "elements": [
                    { "type": "weather-gauge", "html": "weather-gauge" }
                  ]
                }
              ],
              "surfaces": [
                {
                  "id": "card",
                  "slot": "bubble",
                  "tree": {
                    "id": "root",
                    "type": "weather-gauge",
                    "style": { "tone": "accent", "color": "#3B82F6" }
                  }
                }
              ]
            }
            """.trimIndent(),
        )
        val ok = assertIs<UpaManifestResult.Ok>(result)
        assertEquals("weather-gauge", ok.manifest.surfaces.single().tree?.type)
        assertEquals("#3B82F6", ok.manifest.surfaces.single().tree?.style?.color)
    }

    @Test
    fun htmlNodeRequiresSandboxPermission() {
        val result = parseUpaManifest(
            """
            {
              "upa": "0.1",
              "id": "html.plugin",
              "name": "Html",
              "version": "1.0.0",
              "surfaces": [
                {
                  "id": "x",
                  "slot": "page",
                  "tree": { "id": "root", "type": "html", "props": { "html": "<div></div>" } }
                }
              ]
            }
            """.trimIndent(),
        )
        val err = assertIs<UpaManifestResult.Err>(result)
        assertTrue(err.message.contains("html.sandbox"))
    }

    @Test
    fun parsesGithubInstallRefs() {
        val short = parseUpaInstallRef("github:acme/weather@v1:plugins/card")
        assertEquals("acme", short?.owner)
        assertEquals("weather", short?.repo)
        assertEquals("v1", short?.ref)
        assertEquals("plugins/card", short?.path)

        val bare = parseUpaInstallRef("acme/weather")
        assertEquals("acme", bare?.owner)
        assertEquals("weather", bare?.repo)

        val web = parseUpaInstallRef("https://github.com/acme/weather/tree/main/plugins/card")
        assertEquals("acme", web?.owner)
        assertEquals("weather", web?.repo)
        assertEquals("main", web?.ref)
        assertEquals("plugins/card", web?.path)

        val webQuery = parseUpaInstallRef(
            "https://www.github.com/Timothy-kira/upa-plugin/tree/main/amap-maps?tab=readme-ov-file",
        )
        assertEquals("Timothy-kira", webQuery?.owner)
        assertEquals("upa-plugin", webQuery?.repo)
        assertEquals("main", webQuery?.ref)
        assertEquals("amap-maps", webQuery?.path)

        val official = parseUpaInstallRef("amap-maps")
        assertEquals("Timothy-kira", official?.owner)
        assertEquals("upa-plugin", official?.repo)
        assertEquals("main", official?.ref)
        assertEquals("amap-maps", official?.path)

        val repoRoot = parseUpaInstallRef("https://github.com/Timothy-kira/upa-plugin")
        assertEquals("Timothy-kira", repoRoot?.owner)
        assertEquals("upa-plugin", repoRoot?.repo)
        assertEquals("", repoRoot?.path)

        val raw = parseUpaInstallRef(
            "https://raw.githubusercontent.com/Timothy-kira/upa-plugin/main/amap-maps/upa.json",
        )
        assertEquals("Timothy-kira", raw?.owner)
        assertEquals("upa-plugin", raw?.repo)
        assertEquals("main", raw?.ref)
        assertEquals("amap-maps", raw?.path)

        val jsdelivr = parseUpaInstallRef(
            "https://cdn.jsdelivr.net/gh/Timothy-kira/upa-plugin@main/amap-maps/upa.json",
        )
        assertEquals("Timothy-kira", jsdelivr?.owner)
        assertEquals("upa-plugin", jsdelivr?.repo)
        assertEquals("main", jsdelivr?.ref)
        assertEquals("amap-maps", jsdelivr?.path)

        assertNull(parseUpaInstallRef("   "))
    }

    @Test
    fun parsesInteractiveTemplateAndMatchesLocalJson() {
        val result = parseUpaManifest(
            """
            {
              "upa": "0.1",
              "id": "example.weather.card",
              "name": "Weather",
              "version": "1.0.0",
              "capabilities": ["ui.surface", "ui.template"],
              "surfaces": [
                {
                  "id": "card",
                  "slot": "bubble",
                  "tree": {
                    "id": "root",
                    "type": "group",
                    "children": [
                      { "id": "title", "type": "text", "bind": "city", "props": { "text": "" } },
                      { "id": "row-temp", "type": "row", "props": { "subtitle": "" } },
                      {
                        "id": "toggle-detail",
                        "type": "action",
                        "props": { "label": "明细" },
                        "events": { "click": "transition:expand" }
                      }
                    ]
                  }
                }
              ],
              "templates": [
                {
                  "id": "weather.now",
                  "surface": "card",
                  "interactive": true,
                  "fields": [
                    { "id": "city", "nodeId": "title", "prop": "text" },
                    { "id": "summary", "nodeId": "row-temp", "prop": "subtitle", "from": "/current/summary" }
                  ],
                  "match": { "keys": ["city"], "mimeTypes": ["application/vnd.upa.weather+json"] }
                }
              ]
            }
            """.trimIndent(),
        )
        val ok = assertIs<UpaManifestResult.Ok>(result)
        val template = ok.manifest.templates.single()
        assertEquals("weather.now", template.id)
        assertTrue(template.interactive)
        assertEquals("transition:expand", ok.manifest.surfaces.single().tree?.children?.last()?.events?.get("click"))

        val payload = buildJsonObject {
            put("city", "东京")
            put(
                "current",
                buildJsonObject {
                    put("summary", "18° · 多云")
                },
            )
        }
        val matched = matchUpaTemplate(ok.manifest.templates, payload)
        assertEquals("weather.now", matched?.id)
        val fields = bindUpaTemplateFields(template, payload)
        assertEquals("东京", fields["city"])
        assertEquals("18° · 多云", fields["summary"])
    }

    @Test
    fun vendorFillUsesTemplateIdWithoutRequiringRawKeys() {
        val templates = listOf(
            UpaTemplate(
                id = "weather.now",
                interactive = true,
                fields = listOf(UpaTemplateField(id = "city"), UpaTemplateField(id = "summary")),
            ),
        )
        val filled = bindUpaTemplateFields(
            templates.single(),
            buildJsonObject {
                put("city", "东京")
                put("summary", "18° · 多云")
            },
        )
        assertEquals("东京", filled["city"])
        assertEquals("weather.now", matchUpaTemplate(templates, buildJsonObject {}, templateId = "weather.now")?.id)
    }

    @Test
    fun uiPresentRequiresSurfacesAndTemplates() {
        val missing = parseUpaManifest(
            """
            {
              "upa": "0.1",
              "id": "example.weather.card",
              "name": "Weather",
              "version": "1.0.0",
              "ui": { "present": true }
            }
            """.trimIndent(),
        )
        assertIs<UpaManifestResult.Err>(missing)

        val ok = parseUpaManifest(
            """
            {
              "upa": "0.1",
              "id": "example.weather.card",
              "name": "Weather",
              "version": "1.0.0",
              "ui": { "present": true },
              "surfaces": [
                {
                  "id": "card",
                  "slot": "bubble",
                  "tree": { "id": "root", "type": "group", "children": [
                    { "id": "title", "type": "text", "props": { "text": "" } }
                  ] }
                }
              ],
              "templates": [
                {
                  "id": "weather.now",
                  "surface": "card",
                  "fields": [{ "id": "city", "nodeId": "title", "prop": "text" }]
                }
              ]
            }
            """.trimIndent(),
        )
        val manifest = assertIs<UpaManifestResult.Ok>(ok).manifest
        assertTrue(manifest.declaresUiContent())
        val tree = applyUpaFields(
            manifest.surfaces.single().tree!!,
            manifest.templates.single(),
            mapOf("city" to "东京"),
        )
        assertEquals("东京", (tree.children.single().props["text"] as JsonPrimitive).content)
    }

    @Test
    fun textOnlyPluginDoesNotDeclareUi() {
        val parsed = parseUpaManifest(
            """
            {
              "upa": "0.1",
              "id": "example.tools.only",
              "name": "Tools",
              "version": "1.0.0",
              "ui": { "present": false },
              "tools": [{ "name": "ping" }]
            }
            """.trimIndent(),
        )
        val manifest = assertIs<UpaManifestResult.Ok>(parsed).manifest
        assertEquals(false, manifest.declaresUiContent())
    }

    @Test
    fun everMeTrajectoryDefaultsToDenied() {
        val omitted = parseUpaManifest(
            """
            {
              "upa": "0.1",
              "id": "example.weather.card",
              "name": "Weather",
              "version": "1.0.0"
            }
            """.trimIndent(),
        )
        val denied = parseUpaManifest(
            """
            {
              "upa": "0.1",
              "id": "example.weather.card",
              "name": "Weather",
              "version": "1.0.0",
              "memory": { "everme": { "trajectory": false } }
            }
            """.trimIndent(),
        )
        val allowed = parseUpaManifest(
            """
            {
              "upa": "0.1",
              "id": "example.weather.card",
              "name": "Weather",
              "version": "1.0.0",
              "memory": { "everme": { "trajectory": true } }
            }
            """.trimIndent(),
        )
        assertEquals(false, assertIs<UpaManifestResult.Ok>(omitted).manifest.allowsEverMeTrajectory())
        assertEquals(false, assertIs<UpaManifestResult.Ok>(denied).manifest.allowsEverMeTrajectory())
        assertEquals(true, assertIs<UpaManifestResult.Ok>(allowed).manifest.allowsEverMeTrajectory())
    }

    @Test
    fun rejectsTemplateFieldThatPointsAtMissingNode() {
        val result = parseUpaManifest(
            """
            {
              "upa": "0.1",
              "id": "bad.plugin",
              "name": "Bad",
              "version": "1.0.0",
              "surfaces": [
                {
                  "id": "card",
                  "slot": "bubble",
                  "tree": { "id": "root", "type": "group" }
                }
              ],
              "templates": [
                {
                  "id": "t",
                  "surface": "card",
                  "fields": [{ "id": "city", "nodeId": "missing" }]
                }
              ]
            }
            """.trimIndent(),
        )
        val err = assertIs<UpaManifestResult.Err>(result)
        assertTrue(err.message.contains("找不到节点"))
    }

    @Test
    fun blankAndFalseMatchKeysDoNotSelectTemplate() {
        val templates = listOf(
            UpaTemplate(
                id = "food.login",
                match = UpaTemplateMatch(keys = listOf("needLogin")),
            ),
            UpaTemplate(
                id = "food.account",
                match = UpaTemplateMatch(keys = listOf("accountName")),
            ),
        )
        assertNull(
            matchUpaTemplate(
                templates,
                buildJsonObject {
                    put("needLogin", false)
                    put("loginQr", "")
                },
            ),
        )
        assertEquals(
            "food.login",
            matchUpaTemplate(
                templates,
                buildJsonObject { put("needLogin", true) },
            )?.id,
        )
        assertEquals(
            "food.account",
            matchUpaTemplate(
                templates,
                buildJsonObject { put("accountName", "瑞幸账号") },
            )?.id,
        )
    }

    @Test
    fun pruneKeepsOnlyTemplateNodes() {
        val tree = UpaNode(
            id = "root",
            type = "group",
            children = listOf(
                UpaNode(id = "title", type = "text", props = buildJsonObject { put("text", "登录") }),
                UpaNode(
                    id = "row-amount",
                    type = "row",
                    props = buildJsonObject {
                        put("title", "金额")
                        put("subtitle", "")
                    },
                ),
                UpaNode(
                    id = "item0-meta",
                    type = "row",
                    props = buildJsonObject { put("title", "价格") },
                ),
            ),
        )
        val template = UpaTemplate(
            id = "food.login",
            fields = listOf(UpaTemplateField(id = "title", nodeId = "title")),
        )
        val pruned = pruneUpaTreeToFields(tree, template)
        assertEquals(listOf("title"), pruned.children.map { it.id })
    }

    @Test
    fun pruneKeepsInteractiveActionNodes() {
        val tree = UpaNode(
            id = "root",
            type = "group",
            children = listOf(
                UpaNode(id = "title", type = "text", props = buildJsonObject { put("text", "已登录") }),
                UpaNode(
                    id = "logout-btn",
                    type = "action",
                    props = buildJsonObject { put("label", "退出登录") },
                    events = mapOf("click" to "tool:food.logout"),
                ),
            ),
        )
        val template = UpaTemplate(
            id = "food.account",
            fields = listOf(UpaTemplateField(id = "title", nodeId = "title")),
        )
        val pruned = pruneUpaTreeToFields(tree, template)
        assertEquals(listOf("title", "logout-btn"), pruned.children.map { it.id })
    }

    @Test
    fun parsesV02BindAndStringCatalogs() {
        val result = parseUpaManifest(
            """
            {
              "upa": "0.2",
              "id": "example.weather.card",
              "name": "Weather Card",
              "version": "1.0.0",
              "ui": { "present": true },
              "catalogs": ["a2ui.m3e.1.0"],
              "rendererPacks": ["aether.ui.m3e.basic"],
              "surfaces": [
                {
                  "id": "card",
                  "slot": "bubble",
                  "tree": { "id": "root", "type": "group", "children": [
                    { "id": "title", "type": "text", "props": { "text": "" } }
                  ] }
                }
              ],
              "templates": [
                { "id": "weather.now", "surface": "card", "fields": [
                  { "id": "title", "nodeId": "title", "from": "/now/text" }
                ] }
              ],
              "mcp": {
                "bind": [{
                  "servers": ["*"],
                  "tools": ["get_weather", "weather.*"],
                  "mimeTypes": ["application/json"],
                  "match": { "keys": ["now"] }
                }]
              }
            }
            """.trimIndent(),
        )
        val ok = assertIs<UpaManifestResult.Ok>(result)
        assertEquals(listOf("a2ui.m3e.1.0"), ok.manifest.catalogs.map { it.id })
        assertEquals(listOf("aether.ui.m3e.basic"), ok.manifest.rendererPacks)
        val bind = ok.manifest.mcp!!.bind.single()
        assertTrue(
            matchesUpaMcpBind(
                bind = bind,
                serverId = "qweather",
                toolName = "get_weather",
                mimeType = "application/json",
                payloadKeys = setOf("now"),
            ),
        )
        assertTrue(globMatches("weather.*", "weather.now"))
        assertEquals(false, ok.manifest.exportsMcpTools())
        assertTrue(
            matchesUpaMcpBind(
                bind = bind,
                serverId = "qweather",
                toolName = "get_weather",
                mimeType = "",
                payloadKeys = setOf("now"),
            ),
        )
        assertTrue(
            matchesUpaMcpBind(
                bind = bind,
                serverId = "custom",
                toolName = "weather.now",
                mimeType = "",
                payloadKeys = setOf("now", "obsTime"),
            ),
        )
    }

    @Test
    fun uiPresentAllowsA2uiCatalogWithoutSurfaces() {
        val result = parseUpaManifest(
            """
            {
              "upa": "0.2",
              "id": "example.a2ui.card",
              "name": "A2UI",
              "version": "1.0.0",
              "ui": { "present": true },
              "catalogs": ["a2ui.m3e.1.0"]
            }
            """.trimIndent(),
        )
        assertIs<UpaManifestResult.Ok>(result)
    }

    @Test
    fun matchesOfficialAmapMapsBind() {
        val result = parseUpaManifest(
            """
            {
              "upa": "0.2",
              "id": "example.maps.card",
              "name": "Amap Maps",
              "version": "1.0.0",
              "ui": { "present": true },
              "rendererPacks": ["aether.ui.m3e.basic", "aether.ui.map"],
              "mcp": {
                "bind": [{
                  "servers": ["*"],
                  "tools": ["maps_text_search", "maps_around_search", "maps_search_detail"],
                  "match": { "keys": ["pois"] }
                }]
              }
            }
            """.trimIndent(),
        )
        val ok = assertIs<UpaManifestResult.Ok>(result)
        val bind = ok.manifest.mcp!!.bind.single()
        assertTrue(
            matchesUpaMcpBind(
                bind = bind,
                serverId = "amap-maps",
                toolName = "maps_text_search",
                mimeType = "",
                payloadKeys = setOf("pois"),
            ),
        )
        assertTrue(
            matchesUpaMcpBind(
                bind = bind,
                serverId = "amap",
                toolName = "maps_around_search",
                mimeType = "",
                payloadKeys = setOf("pois", "count"),
            ),
        )
        assertTrue(
            matchesUpaMcpBind(
                bind = bind,
                serverId = "custom",
                toolName = "maps_search_detail",
                mimeType = "",
                payloadKeys = setOf("pois"),
            ),
        )
        assertTrue(!matchesUpaMcpBind(
            bind = bind,
            serverId = "amap",
            toolName = "maps_text_search",
            mimeType = "",
            payloadKeys = setOf("status"),
        ))
    }
}
