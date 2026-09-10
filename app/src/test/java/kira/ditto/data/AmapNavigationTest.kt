package kira.ditto.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmapNavigationTest {
    @Test
    fun trafficLightsAcceptOnlyPlainDigits() {
        assertEquals(5, AmapNavigation.verifiedTrafficLightCount(JSONObject().put("traffic_lights", "5")))
        assertEquals(12, AmapNavigation.verifiedTrafficLightCount(JSONObject().put("traffic_lights", 12)))
        assertEquals(0, AmapNavigation.verifiedTrafficLightCount(JSONObject().put("traffic_lights", "5 extra")))
        assertEquals(0, AmapNavigation.verifiedTrafficLightCount(JSONObject().put("traffic_lights", "3.0")))
        assertEquals(0, AmapNavigation.verifiedTrafficLightCount(JSONObject().put("traffic_lights", "")))
        assertEquals(0, AmapNavigation.verifiedTrafficLightCount(JSONObject()))
        assertEquals(0, AmapNavigation.verifiedTrafficLightCount(null))
    }

    @Test
    fun parseDrivingRouteReadsCostPolylineAndLights() {
        val raw = JSONObject()
            .put("ok", true)
            .put(
                "route",
                JSONObject().put(
                    "paths",
                    JSONArray().put(
                        JSONObject()
                            .put("distance", "1524")
                            .put("polyline", "121.4,31.2;121.41,31.21")
                            .put(
                                "steps",
                                JSONArray()
                                    .put(JSONObject().put("instruction", "沿黄山路向东行驶"))
                                    .put(JSONObject().put("instruction", "右转进入金寨路"))
                                    .put(JSONObject().put("instruction", "到达目的地")),
                            )
                            .put(
                                "cost",
                                JSONObject()
                                    .put("duration", "312")
                                    .put("traffic_lights", "3"),
                            ),
                    ),
                ),
            )
        val route = AmapNavigation.parseRoute("driving", raw)
        requireNotNull(route)
        assertEquals(1524.0, route.distanceMeters, 0.01)
        assertEquals(312.0, route.durationSeconds, 0.01)
        assertEquals(3, route.trafficLights)
        assertTrue(route.polyline.contains("121.4"))
        assertEquals("约6分钟", AmapNavigation.formatDuration(route.durationSeconds))
        assertEquals("1.5km", AmapNavigation.formatDistance(route.distanceMeters))
        assertTrue(route.summary.contains("黄山路"))
        assertTrue(route.summary.contains("金寨路"))
    }

    @Test
    fun compactJsonRoundTripKeepsModes() {
        val payload = AmapNavPayload(
            ok = true,
            name = "莱蒂尔",
            origin = "117.280,31.861",
            dest = "121.400,31.200",
            routesByMode = mapOf(
                "driving" to AmapNavRoute(
                    mode = "driving",
                    distanceMeters = 1524.0,
                    durationSeconds = 312.0,
                    trafficLights = 3,
                    polyline = "121.4,31.2;121.41,31.21",
                ),
                "walking" to AmapNavRoute(
                    mode = "walking",
                    distanceMeters = 900.0,
                    durationSeconds = 720.0,
                    polyline = "121.4,31.2;121.401,31.201",
                ),
            ),
        )
        val parsed = AmapNavigation.parsePayload(payload.toCompactJson())
        requireNotNull(parsed)
        assertEquals("driving", parsed.preferredMode())
        assertEquals(2, parsed.routesByMode.size)
        assertEquals(3, parsed.routesByMode.getValue("driving").trafficLights)
        assertEquals("莱蒂尔", parsed.name)
    }

    @Test
    fun parseTransitSummarizesWalkAndBus() {
        val raw = JSONObject()
            .put("ok", true)
            .put(
                "route",
                JSONObject().put(
                    "transits",
                    JSONArray().put(
                        JSONObject()
                            .put("distance", "2400")
                            .put("duration", "900")
                            .put(
                                "segments",
                                JSONArray()
                                    .put(
                                        JSONObject().put(
                                            "walking",
                                            JSONObject()
                                                .put("distance", "400")
                                                .put(
                                                    "steps",
                                                    JSONArray().put(
                                                        JSONObject().put("instruction", "步行到万达广场站"),
                                                    ),
                                                ),
                                        ),
                                    )
                                    .put(
                                        JSONObject().put(
                                            "bus",
                                            JSONObject().put(
                                                "buslines",
                                                JSONArray().put(
                                                    JSONObject()
                                                        .put("name", "1号线")
                                                        .put("departure_stop", JSONObject().put("name", "万达广场"))
                                                        .put("arrival_stop", JSONObject().put("name", "大东门")),
                                                ),
                                            ),
                                        ),
                                    )
                                    .put(
                                        JSONObject().put(
                                            "walking",
                                            JSONObject()
                                                .put("distance", "200")
                                                .put(
                                                    "steps",
                                                    JSONArray().put(
                                                        JSONObject().put("instruction", "步行到达目的地"),
                                                    ),
                                                ),
                                        ),
                                    ),
                            ),
                    ),
                ),
            )
        val route = AmapNavigation.parseRoute("transit", raw)
        requireNotNull(route)
        assertTrue(route.summary.contains("万达广场"))
        assertTrue(route.summary.contains("1号线"))
        assertTrue(route.summary.contains("大东门"))
    }

    @Test
    fun staticMapUrlFramesPolylineNotDestination() {
        val url = AmapNavigation.staticMapUrl(
            key = "test-key",
            origin = "121.400000,31.200000",
            dest = "121.500000,31.300000",
            polyline = "121.400000,31.200000;121.500000,31.300000",
            width = 720,
            height = 214,
        )
        assertTrue(url.contains("size=720*214"))
        assertTrue(url.contains("location="))
        assertFalse(url.contains("location=121.500000%2C31.300000"))
        val center = AmapNavigation.frameCenter(
            origin = "121.400000,31.200000",
            dest = "121.500000,31.300000",
            polyline = "121.400000,31.200000;121.500000,31.300000",
        )
        assertEquals("121.450000,31.250000", center)
        assertTrue(url.contains(center.replace(",", "%2C")))
        assertTrue(AmapNavigation.DirectionShowFields.contains("navi"))
    }

    @Test
    fun compactJsonRoundTripKeepsSummaryAndTravelType() {
        val payload = AmapNavPayload(
            ok = true,
            name = "莱蒂尔",
            origin = "117.280,31.861",
            dest = "121.400,31.200",
            routesByMode = mapOf(
                "driving" to AmapNavRoute(
                    mode = "driving",
                    distanceMeters = 1524.0,
                    durationSeconds = 312.0,
                    trafficLights = 3,
                    polyline = "121.4,31.2;121.41,31.21",
                    summary = "沿黄山路向东，右转进入金寨路",
                ),
            ),
        )
        val parsed = AmapNavigation.parsePayload(payload.toCompactJson())
        requireNotNull(parsed)
        assertEquals("沿黄山路向东，右转进入金寨路", parsed.routesByMode.getValue("driving").summary)
        assertEquals(0, AmapNavigation.amapUriTravelType("driving"))
        assertEquals(1, AmapNavigation.amapUriTravelType("transit"))
        assertEquals(2, AmapNavigation.amapUriTravelType("walking"))
        assertEquals(3, AmapNavigation.amapUriTravelType("bicycling"))
        val place = parsed.destPlace()
        assertEquals("莱蒂尔", place.name)
        assertEquals(121.4, place.lng!!, 0.001)
        assertEquals(31.2, place.lat!!, 0.001)
    }

    @Test
    fun downsampleKeepsFirstAndLastPoints() {
        val polyline = (0..200).joinToString(";") { "$it.0,1.0" }
        val sampled = AmapNavigation.downsamplePolyline(polyline, maxPoints = 80)
        val points = AmapNavigation.parsePoints(sampled)
        assertEquals(80, points.size)
        assertEquals("0.0", points.first().first)
        assertEquals("200.0", points.last().first)
    }

    @Test
    fun userBubbleDoesNotAskTheModelToSearch() {
        assertEquals(
            "我要导航去「莱蒂尔」",
            amapPlaceNavigateInChatUserText(AmapPlace(id = "B001", name = "莱蒂尔")),
        )
        assertFalse(amapPlaceNavigateInChatUserText(AmapPlace(id = "B001", name = "莱蒂尔")).contains("打开去"))
    }

    @Test
    fun leafletRouteJsonKeepsGcjAndVisibleBounds() {
        val json = AmapNavigation.leafletRouteJson(
            origin = "121.400000,31.200000",
            dest = "121.410000,31.210000",
            polyline = "121.400000,31.200000;121.410000,31.210000",
            dark = true,
            padBottomPx = 80,
            viewWidthPx = 720,
            viewHeightPx = 200,
            animate = false,
        )
        val root = JSONObject(json)
        assertTrue(root.getBoolean("dark"))
        assertFalse(root.getBoolean("animate"))
        assertEquals(80, root.getInt("padBottom"))
        val path = root.getJSONArray("path")
        assertEquals(2, path.length())
        val first = path.getJSONArray(0)
        assertEquals(31.2, first.getDouble(0), 1e-6)
        assertEquals(121.4, first.getDouble(1), 1e-6)
        val origin = root.getJSONArray("origin")
        assertEquals(31.2, origin.getDouble(0), 1e-6)
        val bounds = AmapNavigation.paddedLeafletBounds(
            listOf(31.2 to 121.4, 31.21 to 121.41),
            viewWidthPx = 720,
            viewHeightPx = 200,
        )
        requireNotNull(bounds)
        val latSpan = bounds.second.first - bounds.first.first
        val lngSpan = bounds.second.second - bounds.first.second
        assertTrue(lngSpan > latSpan)
        val (wgsLng, wgsLat) = Gcj02.toWgs84(121.4, 31.2)
        assertTrue(kotlin.math.abs(wgsLat - 31.2) > 1e-5 || kotlin.math.abs(wgsLng - 121.4) > 1e-5)
    }
}
