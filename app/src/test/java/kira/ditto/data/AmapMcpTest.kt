package kira.ditto.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmapMcpTest {
    @Test
    fun acpServerIsLoopbackNamedAmap() {
        val server = AmapMcp.toAcpServer()
        requireNotNull(server)
        assertEquals("amap", server.getString("name"))
        assertEquals("http", server.getString("type"))
        assertTrue(server.getString("url").contains("/mcp/aether-amap"))
    }

    @Test
    fun listToolsIncludesOfficialAmapNames() {
        val names = (0 until AmapMcp.listToolsResult().getJSONArray("tools").length()).map {
            AmapMcp.listToolsResult().getJSONArray("tools").getJSONObject(it).getString("name")
        }
        assertEquals(AmapMcp.ToolNames, names)
        assertTrue(names.contains("maps_text_search"))
        assertTrue(names.contains("maps_schema_navi"))
        assertTrue(names.contains("maps_weather"))
    }

    @Test
    fun matchesPrefixedToolNames() {
        assertTrue(AmapMcp.matchesToolName("maps_text_search"))
        assertTrue(AmapMcp.matchesToolName("mcp__amap__maps_around_search"))
        assertEquals("maps_direction_bicycling", AmapMcp.canonicalToolName("maps_bicycling"))
        assertFalse(AmapMcp.matchesToolName("search_threads"))
    }

    @Test
    fun compactPoiKeepsPhotosRatingCostAndDishes() {
        val poi = JSONObject()
            .put("id", "B001")
            .put("name", "莱蒂尔")
            .put("address", "某路")
            .put("location", "121.4,31.2")
            .put("distance", "320")
            .put("type", "餐饮服务;冷饮店")
            .put("typecode", "050302")
            .put("photos", JSONArray().put("http://img.example/a.jpg").put("https://img.example/b.jpg"))
            .put(
                "business",
                JSONObject()
                    .put("rating", 4.8)
                    .put("cost", "25")
                    .put("keytag", "珍珠奶茶")
                    .put("tag", "芋泥波波;餐饮服务"),
            )
        val compact = AmapPlaces.compactPoi(poi)
        val photos = compact.getJSONArray("photos")
        assertEquals(2, photos.length())
        assertTrue(photos.getString(0).startsWith("https://"))
        assertEquals("4.8", compact.getString("rating"))
        assertEquals("¥25", compact.getString("cost"))
        assertTrue(compact.getString("dishes").contains("珍珠奶茶"))
        assertEquals("320米", compact.getString("distance"))
        val place = AmapPlaces.placeFromJson(compact)
        requireNotNull(place)
        assertEquals("莱蒂尔", place.name)
        assertEquals(121.4, place.lng!!, 0.0001)
        assertEquals("珍珠奶茶、芋泥波波", place.dishes)
        assertEquals("餐饮服务;冷饮店", place.type)
        assertEquals("050302", place.typecode)
        assertTrue(isAmapFoodPlace(place))
    }

    @Test
    fun aroundSearchLocationIsOptionalAndNearbyTextSearchDivertsToGps() {
        val tools = AmapMcp.listToolsResult().getJSONArray("tools")
        val around = (0 until tools.length()).map { tools.getJSONObject(it) }
            .first { it.getString("name") == "maps_around_search" }
        val aroundDesc = around.getString("description")
        assertEquals(0, around.getJSONObject("inputSchema").getJSONArray("required").length())
        assertTrue(aroundDesc.contains("按距离分段"))
        assertTrue(aroundDesc.contains("不要用表格"))
        assertFalse(aroundDesc.contains("分点列出店名、评分、人均、招牌菜"))
        val textSearch = (0 until tools.length()).map { tools.getJSONObject(it) }
            .first { it.getString("name") == "maps_text_search" }
        assertFalse(textSearch.getString("description").contains("分点列出店名、评分、人均、招牌菜"))
        assertTrue(AmapAroundSearch.looksNearby("周边奶茶店"))
        assertEquals("奶茶店", AmapAroundSearch.stripNearby("周边奶茶店"))
        val diverted = AmapAroundSearch.divertTextSearch(
            JSONObject().put("keywords", "附近奶茶").put("city", "合肥"),
            "117.280,31.861",
        )
        requireNotNull(diverted)
        assertEquals("奶茶", diverted.getString("keywords"))
        assertEquals("117.280,31.861", diverted.getString("location"))
        assertEquals("", diverted.getString("city"))
        val filled = AmapAroundSearch.withDeviceLocation(JSONObject().put("keywords", "奶茶"), "117.280,31.861")
        assertEquals("117.280,31.861", filled.getString("location"))
        val kept = AmapAroundSearch.withDeviceLocation(
            JSONObject().put("location", "121.4,31.2"),
            "117.280,31.861",
        )
        assertEquals("121.4,31.2", kept.getString("location"))
        assertEquals(null, AmapAroundSearch.divertTextSearch(JSONObject().put("keywords", "合肥奶茶店"), "117.280,31.861"))
    }

    @Test
    fun schemaNaviBuildsDeepLink() {
        val json = AmapMcpHost.dispatch(
            "maps_schema_navi",
            JSONObject().put("location", "121.4,31.2").put("name", "莱蒂尔"),
            "unused",
        )
        assertTrue(json.getString("url").contains("androidamap://navi"))
        assertTrue(json.getString("url").contains("lat=31.2"))
    }
}
