package kira.ditto.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal object AmapMcpHost {
    private const val RestRoot = "https://restapi.amap.com"

    fun execute(context: Context, name: String, arguments: JSONObject): String {
        val tool = AmapMcp.canonicalToolName(name)
        if (tool.isBlank()) {
            return errorJson("unknown_tool", "Unknown Amap tool: $name")
        }
        val store = HostSecretStore(context.applicationContext)
        val key = AmapAuth.snapshot(store).key
        if (key.isBlank()) {
            runCatching { AmapAuth.openKeyConsole(context) }
            return JSONObject()
                .put("ok", false)
                .put("code", "input_required")
                .put(
                    "reason",
                    "Amap Key is missing. Open the Amap console, copy a Web 服务 Key, and paste it in Settings.",
                )
                .toString()
        }
        if (tool == "maps_around_search" || tool == "maps_text_search") {
            warmDeviceCoordinatesAsync(context.applicationContext)
        }
        return runCatching { dispatch(context.applicationContext, tool, arguments, key).put("ok", true).toString() }
            .getOrElse { error ->
                errorJson("amap_api", error.message ?: "Amap request failed")
            }
    }

    internal fun dispatch(tool: String, arguments: JSONObject, key: String): JSONObject =
        dispatch(context = null, tool = tool, arguments = arguments, key = key)

    internal fun dispatch(
        context: Context?,
        tool: String,
        arguments: JSONObject,
        key: String,
    ): JSONObject = when (tool) {
        "maps_geo" -> geo(arguments, key)
        "maps_regeocode" -> regeo(arguments, key)
        "maps_ip_location" -> ip(arguments, key)
        "maps_weather" -> weather(arguments, key)
        "maps_text_search" -> textSearch(context, arguments, key)
        "maps_around_search" -> aroundSearch(context, arguments, key)
        "maps_search_detail" -> searchDetail(arguments, key)
        "maps_direction_driving" -> direction(arguments, key, "/v5/direction/driving")
        "maps_direction_walking" -> direction(arguments, key, "/v5/direction/walking")
        "maps_direction_bicycling" -> direction(arguments, key, "/v4/direction/bicycling")
        "maps_direction_transit_integrated" -> transit(arguments, key)
        "maps_distance" -> distance(arguments, key)
        "maps_schema_personal_map" -> schemaPersonalMap(arguments)
        "maps_schema_navi" -> schemaNavi(arguments)
        "maps_schema_take_taxi" -> schemaTaxi(arguments)
        else -> error("Unknown Amap tool: $tool")
    }

    private fun geo(arguments: JSONObject, key: String): JSONObject {
        val address = arg(arguments, "address")
        require(address.isNotBlank()) { "address is required" }
        return amapGet(
            "/v3/geocode/geo",
            key,
            mapOf(
                "address" to address,
                "city" to arg(arguments, "city"),
            ),
        )
    }

    private fun regeo(arguments: JSONObject, key: String): JSONObject {
        val location = arg(arguments, "location")
        require(location.isNotBlank()) { "location is required" }
        return amapGet("/v3/geocode/regeo", key, mapOf("location" to location))
    }

    private fun ip(arguments: JSONObject, key: String): JSONObject =
        amapGet("/v3/ip", key, mapOf("ip" to arg(arguments, "ip")))

    private fun weather(arguments: JSONObject, key: String): JSONObject {
        val city = arg(arguments, "city")
        require(city.isNotBlank()) { "city is required" }
        return amapGet(
            "/v3/weather/weatherInfo",
            key,
            mapOf("city" to city, "extensions" to "all"),
        )
    }

    private fun textSearch(context: Context?, arguments: JSONObject, key: String): JSONObject {
        val keywords = arg(arguments, "keywords")
        require(keywords.isNotBlank()) { "keywords is required" }
        val diverted = AmapAroundSearch.divertTextSearch(arguments, deviceAmapLocation(context))
        if (diverted != null) {
            return aroundSearch(context, diverted, key).put("search_mode", "around_gps")
        }
        val offset = GmailCodec.intArg(arguments, 10, 25, "offset", "pageSize", "page_size")
        val raw = amapGet(
            "/v3/place/text",
            key,
            mapOf(
                "keywords" to keywords,
                "city" to arg(arguments, "city", "region"),
                "citylimit" to arg(arguments, "citylimit").ifBlank { "false" },
                "offset" to offset.toString(),
                "page" to "1",
                "extensions" to "all",
                "show_fields" to AmapPlaces.ShowFields,
            ),
        )
        return attachPois(raw).put("search_mode", "text")
    }

    private fun aroundSearch(context: Context?, arguments: JSONObject, key: String): JSONObject {
        val filled = AmapAroundSearch.withDeviceLocation(arguments, deviceAmapLocation(context))
        val location = arg(filled, "location")
        require(AmapAroundSearch.hasLngLat(location)) {
            "location is required; enable GPS or pass 经度,纬度"
        }
        val locationSource = if (AmapAroundSearch.hasLngLat(arg(arguments, "location"))) {
            "argument"
        } else {
            "device_gps"
        }
        val offset = GmailCodec.intArg(filled, 10, 25, "offset", "pageSize", "page_size")
        val raw = amapGet(
            "/v3/place/around",
            key,
            mapOf(
                "location" to location,
                "keywords" to arg(filled, "keywords"),
                "radius" to arg(filled, "radius").ifBlank { "3000" },
                "offset" to offset.toString(),
                "page" to "1",
                "extensions" to "all",
                "show_fields" to AmapPlaces.ShowFields,
            ),
        )
        return attachPois(raw)
            .put("location", location)
            .put("location_source", locationSource)
    }

    private fun searchDetail(arguments: JSONObject, key: String): JSONObject {
        val id = arg(arguments, "id", "poiid")
        require(id.isNotBlank()) { "id is required" }
        val raw = amapGet(
            "/v3/place/detail",
            key,
            mapOf(
                "id" to id,
                "extensions" to "all",
                "show_fields" to AmapPlaces.ShowFields,
            ),
        )
        return attachPois(raw)
    }

    private fun direction(arguments: JSONObject, key: String, path: String): JSONObject {
        val origin = arg(arguments, "origin")
        val destination = arg(arguments, "destination")
        require(origin.isNotBlank() && destination.isNotBlank()) { "origin and destination are required" }
        return amapGet(
            path,
            key,
            mapOf(
                "origin" to origin,
                "destination" to destination,
                "show_fields" to AmapNavigation.DirectionShowFields,
            ),
        )
    }

    private fun transit(arguments: JSONObject, key: String): JSONObject {
        val origin = arg(arguments, "origin")
        val destination = arg(arguments, "destination")
        require(origin.isNotBlank() && destination.isNotBlank()) { "origin and destination are required" }
        return amapGet(
            "/v5/direction/transit/integrated",
            key,
            mapOf(
                "origin" to origin,
                "destination" to destination,
                "city1" to arg(arguments, "city", "city1"),
                "city2" to arg(arguments, "cityd", "city2"),
                "show_fields" to AmapNavigation.DirectionShowFields,
            ),
        )
    }

    private fun distance(arguments: JSONObject, key: String): JSONObject {
        val origin = arg(arguments, "origin")
        val destination = arg(arguments, "destination")
        require(origin.isNotBlank() && destination.isNotBlank()) { "origin and destination are required" }
        return amapGet(
            "/v3/distance",
            key,
            mapOf(
                "origins" to origin,
                "destination" to destination,
                "type" to arg(arguments, "type").ifBlank { "1" },
            ),
        )
    }

    private fun schemaPersonalMap(arguments: JSONObject): JSONObject {
        val name = arg(arguments, "name").ifBlank { "行程" }
        val location = arg(arguments, "location")
        val extras = arg(arguments, "locations")
        val first = location.ifBlank { extras.split(';', '|', '\n').firstOrNull().orEmpty() }
        val encodedName = enc(name)
        val url = if (first.contains(',')) {
            "https://uri.amap.com/marker?position=${enc(first)}&name=$encodedName"
        } else {
            "https://uri.amap.com/marker?name=$encodedName"
        }
        return JSONObject().put("url", url).put("name", name)
    }

    private fun schemaNavi(arguments: JSONObject): JSONObject {
        val location = arg(arguments, "location")
        require(location.contains(',')) { "location is required" }
        val parts = location.split(',')
        val lng = parts.getOrNull(0)?.trim().orEmpty()
        val lat = parts.getOrNull(1)?.trim().orEmpty()
        val name = arg(arguments, "name")
        val android = "androidamap://navi?sourceApplication=aether&poiname=${enc(name)}&lat=$lat&lon=$lng&dev=0&style=2"
        val uri = "amapuri://route/plan/?dlat=$lat&dlon=$lng&dname=${enc(name)}&dev=0&t=0"
        return JSONObject()
            .put("url", android)
            .put("urls", JSONArray().put(android).put(uri))
    }

    private fun schemaTaxi(arguments: JSONObject): JSONObject {
        val origin = arg(arguments, "origin")
        val destination = arg(arguments, "destination")
        require(origin.contains(',') && destination.contains(',')) { "origin and destination are required" }
        val o = origin.split(',')
        val d = destination.split(',')
        val url = "amapuri://takeTaxi?sourceApplication=aether" +
            "&slat=${o.getOrNull(1)?.trim()}&slon=${o.getOrNull(0)?.trim()}" +
            "&sname=${enc(arg(arguments, "sname"))}" +
            "&dlat=${d.getOrNull(1)?.trim()}&dlon=${d.getOrNull(0)?.trim()}" +
            "&dname=${enc(arg(arguments, "dname"))}"
        return JSONObject().put("url", url)
    }

    private fun attachPois(raw: JSONObject): JSONObject {
        val pois = raw.optJSONArray("pois") ?: JSONArray()
        val compact = AmapPlaces.compactPois(pois)
        raw.put("pois", compact)
        raw.put("places", compact)
        return raw
    }

    private fun amapGet(path: String, key: String, params: Map<String, String>): JSONObject {
        val query = (params.filter { it.value.isNotBlank() } + ("key" to key) + ("output" to "json"))
            .entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val url = "$RestRoot$path?$query"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }
            val raw = stream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
            val json = runCatching { JSONObject(raw) }.getOrElse { JSONObject().put("raw", raw) }
            val status = json.optString("status")
            if (status == "0") {
                error(json.optString("info").ifBlank { "Amap error ${json.optString("infocode")}" })
            }
            json
        } finally {
            connection.disconnect()
        }
    }

    private fun deviceAmapLocation(context: Context?): String? {
        if (context == null) return null
        return awaitDeviceCoordinates(context)?.amapLocation()?.takeIf(AmapAroundSearch::hasLngLat)
    }

    private fun arg(arguments: JSONObject, vararg keys: String): String =
        GmailCodec.stringArg(arguments, *keys)

    private fun enc(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun errorJson(code: String, message: String): String =
        JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("reason", message)
            .toString()
}
