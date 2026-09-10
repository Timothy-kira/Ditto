package kira.ditto.data

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.tan

internal const val AmapNavigationToolName = "aether_amap_navigation"

internal data class AmapNavRoute(
    val mode: String,
    val distanceMeters: Double = 0.0,
    val durationSeconds: Double = 0.0,
    val trafficLights: Int = 0,
    val polyline: String = "",
    val summary: String = "",
)

internal data class AmapNavPayload(
    val ok: Boolean,
    val name: String,
    val origin: String = "",
    val dest: String = "",
    val error: String = "",
    val routesByMode: Map<String, AmapNavRoute> = emptyMap(),
) {
    fun preferredMode(): String =
        AmapNavigation.DisplayModeOrder.firstOrNull { routesByMode.containsKey(it) }.orEmpty()

    fun destPlace(): AmapPlace {
        val point = AmapNavigation.parsePoints(dest).firstOrNull()
        return AmapPlace(
            id = "",
            name = name,
            lng = point?.first?.toDoubleOrNull(),
            lat = point?.second?.toDoubleOrNull(),
        )
    }

    fun toCompactJson(): String = JSONObject()
        .put("ok", ok)
        .put("name", name)
        .put("origin", origin)
        .put("dest", dest)
        .put("error", error)
        .put(
            "routesByMode",
            JSONObject().also { root ->
                routesByMode.forEach { (mode, route) ->
                    root.put(
                        mode,
                        JSONObject()
                            .put("mode", route.mode)
                            .put("distanceMeters", route.distanceMeters)
                            .put("durationSeconds", route.durationSeconds)
                            .put("trafficLights", route.trafficLights)
                            .put("polyline", route.polyline)
                            .put("summary", route.summary),
                    )
                }
            },
        )
        .toString()
}

internal object AmapNavigation {
    const val DirectionShowFields = "cost,polyline,tmcs,navi"
    val DisplayModeOrder = listOf("driving", "transit", "bicycling", "walking")
    private const val StaticMapMaxPoints = 80
    private val PointPattern = Regex("""(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)""")
    private val HtmlTagPattern = Regex("<[^>]+>")
    private val KeyManeuver = Regex("左转|右转|向左|向右|掉头|进入|到达|驶入|上桥|下桥")

    suspend fun fetch(context: Context, place: AmapPlace): AmapNavPayload {
        val destLng = place.lng
        val destLat = place.lat
        if (destLng == null || destLat == null) {
            return AmapNavPayload(ok = false, name = place.name, error = "destination")
        }
        val dest = String.format(Locale.US, "%.6f,%.6f", destLng, destLat)
        val origin = awaitDeviceCoordinates(context)?.amapLocation().orEmpty()
        if (origin.isBlank()) {
            return AmapNavPayload(ok = false, name = place.name, dest = dest, error = "gps")
        }
        if (AmapAuth.snapshot(HostSecretStore(context)).key.isBlank()) {
            return AmapNavPayload(ok = false, name = place.name, origin = origin, dest = dest, error = "key")
        }
        val args = JSONObject().put("origin", origin).put("destination", dest)
        val routes = coroutineScope {
            listOf(
                "driving" to "maps_direction_driving",
                "transit" to "maps_direction_transit_integrated",
                "bicycling" to "maps_direction_bicycling",
                "walking" to "maps_direction_walking",
            ).map { (mode, tool) ->
                async {
                    val raw = runCatching {
                        JSONObject(AmapMcpHost.execute(context, tool, args))
                    }.getOrNull()
                    parseRoute(mode, raw)?.let { mode to it }
                }
            }.awaitAll()
        }.filterNotNull().toMap()
        if (routes.isEmpty()) {
            return AmapNavPayload(
                ok = false,
                name = place.name,
                origin = origin,
                dest = dest,
                error = "route",
            )
        }
        return AmapNavPayload(
            ok = true,
            name = place.name,
            origin = origin,
            dest = dest,
            routesByMode = routes,
        )
    }

    fun parsePayload(raw: String): AmapNavPayload? {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val routesJson = root.optJSONObject("routesByMode") ?: JSONObject()
        val routes = linkedMapOf<String, AmapNavRoute>()
        DisplayModeOrder.forEach { mode ->
            val item = routesJson.optJSONObject(mode) ?: return@forEach
            val polyline = item.optString("polyline")
            val distance = jsonNumber(item, "distanceMeters", "distance")
            val duration = jsonNumber(item, "durationSeconds", "duration")
            val summary = item.optString("summary")
            if (polyline.isBlank() && distance <= 0.0 && duration <= 0.0 && summary.isBlank()) return@forEach
            routes[mode] = AmapNavRoute(
                mode = mode,
                distanceMeters = distance,
                durationSeconds = duration,
                trafficLights = item.optInt("trafficLights", 0),
                polyline = polyline,
                summary = summary,
            )
        }
        return AmapNavPayload(
            ok = root.optBoolean("ok", routes.isNotEmpty()),
            name = root.optString("name"),
            origin = root.optString("origin"),
            dest = root.optString("dest"),
            error = root.optString("error"),
            routesByMode = routes,
        )
    }

    fun parseRoute(mode: String, raw: JSONObject?): AmapNavRoute? {
        if (raw == null || raw.optBoolean("ok", true) == false) return null
        val path = firstPath(raw, mode) ?: return null
        val cost = path.optJSONObject("cost")
        val duration = jsonNumber(cost, "duration").takeIf { it > 0 }
            ?: jsonNumber(path, "duration")
        val distance = jsonNumber(path, "distance").takeIf { it > 0 }
            ?: jsonNumber(cost, "distance")
        val polyline = pathPolyline(path)
        val summary = summarizePath(mode, path)
        if (polyline.isBlank() && duration <= 0.0 && distance <= 0.0 && summary.isBlank()) return null
        return AmapNavRoute(
            mode = mode,
            distanceMeters = distance,
            durationSeconds = duration,
            trafficLights = if (mode == "driving") verifiedTrafficLightCount(cost) else 0,
            polyline = polyline,
            summary = summary,
        )
    }

    fun verifiedTrafficLightCount(cost: JSONObject?): Int {
        if (cost == null || !cost.has("traffic_lights") || cost.isNull("traffic_lights")) return 0
        val raw = cost.opt("traffic_lights")?.toString()?.trim().orEmpty()
        if (!raw.matches(Regex("""^\d+$"""))) return 0
        return raw.toInt()
    }

    fun formatDuration(seconds: Double): String {
        if (!seconds.isFinite() || seconds <= 0.0) return ""
        val minutes = kotlin.math.ceil(seconds / 60.0).toInt().coerceAtLeast(1)
        return "约${minutes}分钟"
    }

    fun formatDistance(meters: Double): String {
        if (!meters.isFinite() || meters <= 0.0) return ""
        return if (meters >= 1000) {
            val km = meters / 1000.0
            if (meters >= 10_000) "${km.toInt()}km" else String.format(Locale.US, "%.1fkm", km)
        } else {
            "${kotlin.math.round(meters).toInt()}m"
        }
    }

    fun amapUriTravelType(mode: String): Int = when (mode) {
        "transit" -> 1
        "walking" -> 2
        "bicycling" -> 3
        else -> 0
    }

    fun frameCenter(origin: String, dest: String, polyline: String): String {
        val points = buildList {
            addAll(parsePoints(polyline))
            addAll(parsePoints(origin))
            addAll(parsePoints(dest))
        }
        val lngs = points.mapNotNull { it.first.toDoubleOrNull() }
        val lats = points.mapNotNull { it.second.toDoubleOrNull() }
        if (lngs.isEmpty() || lats.isEmpty()) return dest.ifBlank { origin }
        val midLng = (lngs.minOrNull()!! + lngs.maxOrNull()!!) / 2.0
        val midLat = (lats.minOrNull()!! + lats.maxOrNull()!!) / 2.0
        return String.format(Locale.US, "%.6f,%.6f", midLng, midLat)
    }

    fun staticMapUrl(
        key: String,
        origin: String,
        dest: String,
        polyline: String,
        width: Int = 720,
        height: Int = 400,
    ): String {
        if (key.isBlank()) return ""
        val sampled = downsamplePolyline(polyline)
        val paths = if (sampled.isNotBlank()) {
            "8,0x2A7FFF,1,,:$sampled"
        } else {
            ""
        }
        val markers = listOfNotNull(
            origin.takeIf { it.contains(',') }?.let { "mid,0x34C759,起:$it" },
            dest.takeIf { it.contains(',') }?.let { "mid,0xFF3B30,终:$it" },
        ).joinToString("|")
        val location = frameCenter(origin, dest, sampled)
        val zoom = frameZoom(origin, dest, sampled, width, height)
        return buildString {
            append("https://restapi.amap.com/v3/staticmap?")
            append("size=${width.coerceIn(100, 1024)}*${height.coerceIn(100, 1024)}")
            append("&scale=2")
            if (location.contains(',')) append("&location=${enc(location)}")
            if (zoom != null) append("&zoom=$zoom")
            if (paths.isNotBlank()) append("&paths=${enc(paths)}")
            if (markers.isNotBlank()) append("&markers=${enc(markers)}")
            append("&key=${enc(key)}")
        }
    }

    fun downsamplePolyline(polyline: String, maxPoints: Int = StaticMapMaxPoints): String {
        val points = parsePoints(polyline)
        if (points.isEmpty()) return ""
        if (points.size <= maxPoints) return points.joinToString(";") { "${it.first},${it.second}" }
        val last = (maxPoints - 1).coerceAtLeast(1)
        val step = (points.size - 1).toDouble() / last
        return (0..last).map { index ->
            points[(index * step).toInt().coerceIn(0, points.lastIndex)]
        }.joinToString(";") { "${it.first},${it.second}" }
    }

    fun parsePoints(polyline: String): List<Pair<String, String>> {
        if (polyline.isBlank()) return emptyList()
        return PointPattern.findAll(polyline).map { match ->
            match.groupValues[1] to match.groupValues[2]
        }.toList()
    }

    fun leafletLatLngs(polyline: String): List<Pair<Double, Double>> =
        parsePoints(downsamplePolyline(polyline)).mapNotNull { (lngRaw, latRaw) ->
            val lng = lngRaw.toDoubleOrNull() ?: return@mapNotNull null
            val lat = latRaw.toDoubleOrNull() ?: return@mapNotNull null
            lat to lng
        }

    fun wgsLatLngs(polyline: String): List<Pair<Double, Double>> =
        parsePoints(downsamplePolyline(polyline)).mapNotNull { (lngRaw, latRaw) ->
            val lng = lngRaw.toDoubleOrNull() ?: return@mapNotNull null
            val lat = latRaw.toDoubleOrNull() ?: return@mapNotNull null
            val (wgsLng, wgsLat) = Gcj02.toWgs84(lng, lat)
            wgsLat to wgsLng
        }

    fun leafletRouteJson(
        origin: String,
        dest: String,
        polyline: String,
        dark: Boolean,
        padBottomPx: Int,
        viewWidthPx: Int,
        viewHeightPx: Int,
        animate: Boolean,
    ): String {
        val path = leafletLatLngs(polyline)
        val originPt = leafletLatLngs(origin).firstOrNull()
        val destPt = leafletLatLngs(dest).firstOrNull()
        val boundsPoints = buildList {
            addAll(path)
            originPt?.let { add(it) }
            destPt?.let { add(it) }
        }
        val bounds = paddedLeafletBounds(boundsPoints, viewWidthPx, viewHeightPx)
        return JSONObject()
            .put("dark", dark)
            .put("animate", animate)
            .put("padBottom", padBottomPx.coerceAtLeast(0))
            .put("path", JSONArray().also { array ->
                path.forEach { (lat, lng) -> array.put(JSONArray().put(lat).put(lng)) }
            })
            .put("origin", originPt?.let { JSONArray().put(it.first).put(it.second) } ?: JSONObject.NULL)
            .put("dest", destPt?.let { JSONArray().put(it.first).put(it.second) } ?: JSONObject.NULL)
            .put(
                "bounds",
                if (bounds == null) {
                    JSONObject.NULL
                } else {
                    JSONArray()
                        .put(JSONArray().put(bounds.first.first).put(bounds.first.second))
                        .put(JSONArray().put(bounds.second.first).put(bounds.second.second))
                },
            )
            .toString()
    }

    fun paddedLeafletBounds(
        points: List<Pair<Double, Double>>,
        viewWidthPx: Int,
        viewHeightPx: Int,
    ): Pair<Pair<Double, Double>, Pair<Double, Double>>? {
        if (points.isEmpty()) return null
        var minLat = points.minOf { it.first }
        var maxLat = points.maxOf { it.first }
        var minLng = points.minOf { it.second }
        var maxLng = points.maxOf { it.second }
        val latSpan = (maxLat - minLat).coerceAtLeast(1e-4)
        val lngSpan = (maxLng - minLng).coerceAtLeast(1e-4)
        minLat -= latSpan * 0.20
        maxLat += latSpan * 0.20
        minLng -= lngSpan * 0.20
        maxLng += lngSpan * 0.20
        val midLat = (minLat + maxLat) / 2.0
        val cosLat = kotlin.math.cos(Math.toRadians(midLat)).coerceAtLeast(0.2)
        val geoAspect = ((maxLng - minLng) * cosLat) / (maxLat - minLat).coerceAtLeast(1e-8)
        val viewAspect = viewWidthPx.toDouble() / viewHeightPx.coerceAtLeast(1).toDouble()
        if (geoAspect > viewAspect) {
            val neededLat = ((maxLng - minLng) * cosLat) / viewAspect.coerceAtLeast(0.2)
            val extra = ((neededLat - (maxLat - minLat)) / 2.0).coerceAtLeast(0.0)
            minLat -= extra
            maxLat += extra
        } else {
            val neededLng = (maxLat - minLat) * viewAspect / cosLat
            val extra = ((neededLng - (maxLng - minLng)) / 2.0).coerceAtLeast(0.0)
            minLng -= extra
            maxLng += extra
        }
        return (minLat to minLng) to (maxLat to maxLng)
    }

    internal fun summarizePath(mode: String, path: JSONObject): String {
        val text = if (mode == "transit") summarizeTransit(path) else compressDirections(stepInstructions(path))
        return text.take(160)
    }

    private fun frameZoom(
        origin: String,
        dest: String,
        polyline: String,
        width: Int,
        height: Int,
    ): Int? {
        val points = buildList {
            addAll(parsePoints(polyline))
            addAll(parsePoints(origin))
            addAll(parsePoints(dest))
        }
        val lngs = points.mapNotNull { it.first.toDoubleOrNull() }
        val lats = points.mapNotNull { it.second.toDoubleOrNull() }
        if (lngs.size < 2 || lats.size < 2) return null
        val lngSpan = ((lngs.maxOrNull()!! - lngs.minOrNull()!!).coerceAtLeast(1e-5)) * 1.22
        val minLat = lats.minOrNull()!!
        val maxLat = lats.maxOrNull()!!
        val w = width.coerceIn(100, 1024).toDouble()
        val h = height.coerceIn(100, 1024).toDouble()
        val lngZoom = ln(w * 360.0 / (lngSpan * 256.0)) / ln(2.0)
        val latZoom = mercatorZoom(minLat, maxLat, h)
        val zoom = min(lngZoom, latZoom).toInt()
        return zoom.coerceIn(3, 17)
    }

    private fun mercatorZoom(minLat: Double, maxLat: Double, height: Double): Double {
        fun latRad(lat: Double): Double {
            val clamped = lat.coerceIn(-85.0, 85.0)
            val rad = Math.toRadians(clamped)
            return ln(tan(Math.PI / 4.0 + rad / 2.0))
        }
        val span = (latRad(maxLat) - latRad(minLat)).coerceAtLeast(1e-6) * 1.22
        return ln(height / (256.0 * span / (2.0 * Math.PI))) / ln(2.0)
    }

    private fun firstPath(root: JSONObject, mode: String): JSONObject? {
        val route = root.optJSONObject("route")
            ?: root.optJSONObject("data")
            ?: root
        if (mode == "transit") {
            val transits = route.optJSONArray("transits") ?: return null
            return transits.optJSONObject(0)
        }
        val paths = route.optJSONArray("paths") ?: root.optJSONArray("paths") ?: return null
        return paths.optJSONObject(0)
    }

    private fun pathPolyline(path: JSONObject): String {
        val direct = path.optString("polyline")
        if (direct.contains(',')) return direct
        val parts = mutableListOf<String>()
        fun walk(value: Any?) {
            when (value) {
                is JSONObject -> {
                    val line = value.optString("polyline")
                    if (line.contains(',')) parts += line
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (key == "polyline") continue
                        walk(value.opt(key))
                    }
                }
                is JSONArray -> {
                    for (index in 0 until value.length()) walk(value.opt(index))
                }
            }
        }
        walk(path.opt("steps"))
        walk(path.opt("segments"))
        return parts.joinToString(";")
    }

    private fun stepInstructions(path: JSONObject): List<String> {
        val texts = mutableListOf<String>()
        fun addInstruction(holder: JSONObject?) {
            if (holder == null) return
            val direct = stripHtml(holder.optString("instruction"))
            val navi = stripHtml(holder.optJSONObject("navi")?.optString("instruction").orEmpty())
            val text = direct.ifBlank { navi }
            if (text.isNotBlank()) texts += text
        }
        fun walkSteps(value: Any?) {
            when (value) {
                is JSONArray -> {
                    for (index in 0 until value.length()) {
                        val item = value.opt(index)
                        if (item is JSONObject) addInstruction(item)
                        walkSteps(item)
                    }
                }
                is JSONObject -> {
                    value.optJSONArray("steps")?.let { walkSteps(it) }
                }
            }
        }
        walkSteps(path.opt("steps"))
        if (texts.isEmpty()) walkSteps(path)
        return texts
    }

    private fun summarizeTransit(path: JSONObject): String {
        val parts = mutableListOf<String>()
        val segments = path.optJSONArray("segments")
        if (segments == null) return compressDirections(stepInstructions(path))
        for (index in 0 until segments.length()) {
            val segment = segments.optJSONObject(index) ?: continue
            val walking = segment.optJSONObject("walking")
            val bus = lineSummary(segment.optJSONObject("bus")?.optJSONArray("buslines"), "乘")
            val railway = transitVehicleSummary(segment.optJSONObject("railway"))
            if (walking != null) {
                val steps = stepInstructions(walking)
                val instruction = when {
                    index == segments.length() - 1 -> steps.lastOrNull().orEmpty()
                    else -> steps.firstOrNull().orEmpty()
                }
                val distance = jsonNumber(walking, "distance")
                parts += when {
                    instruction.isNotBlank() -> instruction
                    distance > 0.0 -> "步行约${formatDistance(distance)}"
                    else -> "步行"
                }
            }
            if (bus.isNotBlank()) parts += bus
            if (railway.isNotBlank()) parts += railway
            segment.optJSONObject("taxi")?.let { taxi ->
                val distance = jsonNumber(taxi, "distance")
                parts += if (distance > 0.0) "打车约${formatDistance(distance)}" else "打车"
            }
        }
        return parts.map(::stripHtml).filter { it.isNotBlank() }.distinct().joinToString("，")
    }

    private fun transitVehicleSummary(vehicle: JSONObject?): String {
        if (vehicle == null) return ""
        val nested = lineSummary(
            vehicle.optJSONArray("spaces")
                ?: vehicle.optJSONArray("railways")
                ?: vehicle.optJSONArray("buslines"),
            "乘",
        )
        if (nested.isNotBlank()) return nested
        val name = vehicle.optString("name").ifBlank { vehicle.optString("trip") }
        val dep = jsonName(vehicle.opt("departure_stop")).ifBlank { jsonName(vehicle.opt("departure")) }
        val arr = jsonName(vehicle.opt("arrival_stop")).ifBlank { jsonName(vehicle.opt("arrival")) }
        if (name.isBlank() && dep.isBlank() && arr.isBlank()) return ""
        return buildString {
            if (name.isNotBlank()) append("乘").append(name)
            if (dep.isNotBlank()) append("从").append(dep)
            if (arr.isNotBlank()) append("到").append(arr)
        }
    }

    private fun lineSummary(lines: JSONArray?, verb: String): String {
        val line = lines?.optJSONObject(0) ?: return ""
        val name = line.optString("name").ifBlank { line.optString("trip") }
        val dep = jsonName(line.opt("departure_stop")).ifBlank { jsonName(line.opt("departure")) }
        val arr = jsonName(line.opt("arrival_stop")).ifBlank { jsonName(line.opt("arrival")) }
        if (name.isBlank() && dep.isBlank() && arr.isBlank()) return ""
        return buildString {
            if (name.isNotBlank()) append(verb).append(name)
            if (dep.isNotBlank()) append("从").append(dep)
            if (arr.isNotBlank()) append("到").append(arr)
        }
    }

    private fun compressDirections(texts: List<String>): String {
        val cleaned = texts.map(::stripHtml).filter { it.isNotBlank() }
        if (cleaned.isEmpty()) return ""
        val picked = mutableListOf<String>()
        cleaned.forEachIndexed { index, text ->
            if (index == 0 || index == cleaned.lastIndex || KeyManeuver.containsMatchIn(text)) {
                picked += text
            }
        }
        val unique = picked.distinct()
        val limited = if (unique.size <= 4) {
            unique
        } else {
            listOf(unique.first()) + unique.drop(1).dropLast(1).take(2) + unique.last()
        }
        return limited.joinToString("，")
    }

    private fun jsonName(value: Any?): String = when (value) {
        is JSONObject -> value.optString("name")
        null, JSONObject.NULL -> ""
        else -> value.toString().trim()
    }

    private fun stripHtml(raw: String): String = HtmlTagPattern.replace(raw, "").trim()

    private fun jsonNumber(holder: JSONObject?, vararg keys: String): Double {
        if (holder == null) return 0.0
        keys.forEach { key ->
            if (!holder.has(key) || holder.isNull(key)) return@forEach
            val value = holder.opt(key) ?: return@forEach
            val number = when (value) {
                is Number -> value.toDouble()
                else -> value.toString().trim().toDoubleOrNull()
            }
            if (number != null && number.isFinite() && number > 0.0) return number
        }
        return 0.0
    }

    private fun enc(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
}

internal fun amapPlaceNavigateInChatUserText(place: AmapPlace): String =
    "我要导航去「${place.name}」"
