package kira.ditto.data

import org.json.JSONArray
import org.json.JSONObject

internal const val AmapBrowserUserAgent =
    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
internal const val AmapCdnReferer = "https://www.amap.com/"

internal fun looksLikeAmapCdn(url: String): Boolean {
    val lower = url.lowercase()
    return "autonavi" in lower || "amap.com" in lower || "amap." in lower
}

internal data class AmapPlace(
    val id: String,
    val name: String,
    val address: String = "",
    val lng: Double? = null,
    val lat: Double? = null,
    val photos: List<String> = emptyList(),
    val rating: String = "",
    val cost: String = "",
    val dishes: String = "",
    val distance: String = "",
    val city: String = "",
    val type: String = "",
    val typecode: String = "",
)

internal object AmapPlaces {
    const val ShowFields = "business,photos"
    private const val PhotoLimitPerPlace = 8

    fun collectPhotos(poi: JSONObject): List<String> {
        val urls = linkedSetOf<String>()
        fun add(raw: String) {
            raw.split(',', ';', '|').forEach { piece ->
                val url = piece.trim().replace("http://", "https://")
                if (url.startsWith("https://")) urls += url
            }
        }
        fun addFrom(value: Any?) {
            when (value) {
                null, JSONObject.NULL -> Unit
                is JSONArray -> {
                    for (index in 0 until value.length()) addFrom(value.opt(index))
                }
                is JSONObject -> {
                    add(value.optString("url"))
                    add(value.optString("src"))
                    add(value.optString("pic"))
                    add(value.optString("cover"))
                    add(value.optString("image"))
                    add(value.optString("photourl"))
                    add(value.optString("photo_url"))
                    addFrom(value.opt("photo"))
                    addFrom(value.opt("photos"))
                    addFrom(value.opt("photo_urls"))
                    addFrom(value.opt("urls"))
                    addFrom(value.opt("list"))
                }
                else -> add(value.toString())
            }
        }
        addFrom(poi.opt("photos"))
        addFrom(poi.opt("photo"))
        add(poi.optString("photo_url"))
        add(poi.optString("photourl"))
        addFrom(poi.opt("photo_urls"))
        add(poi.optString("image"))
        add(poi.optString("pic"))
        add(poi.optString("cover"))
        poi.optJSONObject("business")?.let { business ->
            add(business.optString("image"))
            addFrom(business.opt("photos"))
        }
        return urls.take(PhotoLimitPerPlace)
    }

    fun compactPoi(poi: JSONObject): JSONObject {
        val location = poi.optString("location")
        val parts = location.split(',')
        val lng = parts.getOrNull(0)?.trim()?.toDoubleOrNull()
        val lat = parts.getOrNull(1)?.trim()?.toDoubleOrNull()
        val photos = JSONArray().apply {
            collectPhotos(poi).forEach(::put)
        }
        return JSONObject()
            .put("id", poi.optString("id").ifBlank { poi.optString("poiid") })
            .put("name", poi.optString("name"))
            .put("address", poi.optString("address"))
            .put("location", location)
            .put("lng", lng ?: JSONObject.NULL)
            .put("lat", lat ?: JSONObject.NULL)
            .put("city", poi.optString("cityname").ifBlank { poi.optString("city") })
            .put("type", poi.optString("type"))
            .put("typecode", poi.optString("typecode"))
            .put("tel", poi.optString("tel"))
            .put("rating", collectRating(poi))
            .put("cost", collectCost(poi))
            .put("dishes", collectDishes(poi))
            .put("distance", collectDistance(poi))
            .put("photos", photos)
    }

    fun compactPois(raw: JSONArray, limit: Int = 20): JSONArray {
        val out = JSONArray()
        val cap = raw.length().coerceAtMost(limit)
        for (index in 0 until cap) {
            val poi = raw.optJSONObject(index) ?: continue
            if (poi.optString("name").isBlank()) continue
            out.put(compactPoi(poi))
        }
        return out
    }

    fun placeFromJson(json: JSONObject): AmapPlace? {
        val name = json.optString("name").trim()
        if (name.isBlank()) return null
        val location = json.optString("location")
        val parts = location.split(',')
        val photosRaw = json.optJSONArray("photos") ?: JSONArray()
        val photos = (0 until photosRaw.length()).mapNotNull { index ->
            photosRaw.optString(index).trim().takeIf { it.startsWith("https://") }
        }
        return AmapPlace(
            id = json.optString("id").ifBlank { json.optString("poiid") }.ifBlank { name },
            name = name,
            address = json.optString("address"),
            lng = json.opt("lng")?.toString()?.toDoubleOrNull()
                ?: parts.getOrNull(0)?.trim()?.toDoubleOrNull(),
            lat = json.opt("lat")?.toString()?.toDoubleOrNull()
                ?: parts.getOrNull(1)?.trim()?.toDoubleOrNull(),
            photos = photos.ifEmpty { collectPhotos(json) },
            rating = json.optString("rating").ifBlank { collectRating(json) },
            cost = json.optString("cost").ifBlank { collectCost(json) },
            dishes = json.optString("dishes").ifBlank { collectDishes(json) },
            distance = json.optString("distance").ifBlank { collectDistance(json) },
            city = json.optString("city"),
            type = json.optString("type"),
            typecode = json.optString("typecode"),
        )
    }

    internal fun collectRating(poi: JSONObject): String {
        val business = poi.optJSONObject("business")
        val bizExt = poi.optJSONObject("biz_ext")
        return jsonText(business, "rating")
            .ifBlank { jsonText(bizExt, "rating") }
            .ifBlank { jsonText(poi, "rating") }
    }

    internal fun collectCost(poi: JSONObject): String {
        val business = poi.optJSONObject("business")
        val bizExt = poi.optJSONObject("biz_ext")
        return formatAmapCost(
            jsonText(business, "cost", "cost_avg", "price")
                .ifBlank { jsonText(bizExt, "cost") }
                .ifBlank { jsonText(poi, "cost") },
        )
    }

    internal fun collectDishes(poi: JSONObject): String {
        val business = poi.optJSONObject("business")
        val bizExt = poi.optJSONObject("biz_ext")
        val generic = setOf(
            "餐饮", "餐饮服务", "冷饮店", "奶茶店", "茶座", "甜品店",
            "食品饮料", "购物", "生活服务", "美食", "小吃", "快餐",
        )
        val tokens = linkedSetOf<String>()
        listOf(
            jsonText(business, "keytag"),
            jsonText(business, "rectag"),
            jsonText(business, "tag"),
            jsonText(business, "recommend"),
            jsonText(poi, "tag"),
            jsonText(bizExt, "tag"),
        ).forEach { raw ->
            tokenizeAmapDishes(raw).forEach { token ->
                if (token !in generic && !token.startsWith("餐饮")) tokens += token
            }
        }
        return tokens.take(4).joinToString("、")
    }

    internal fun collectDistance(poi: JSONObject): String = formatAmapDistance(jsonText(poi, "distance"))

    fun placesFromToolJson(raw: String): List<AmapPlace> {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val structured = root.optJSONObject("structuredContent") ?: root
        val arrays = listOf("pois", "places")
            .mapNotNull { key -> structured.optJSONArray(key) }
        val found = arrays.firstOrNull { it.length() > 0 } ?: structured.optJSONArray("pois")
        if (found == null) {
            if (structured.optString("name").isNotBlank() && structured.optString("id").isNotBlank()) {
                return listOfNotNull(placeFromJson(structured))
            }
            return emptyList()
        }
        return (0 until found.length()).mapNotNull { index ->
            found.optJSONObject(index)?.let(::placeFromJson)
        }
    }
}

internal fun isAmapFoodPlace(place: AmapPlace): Boolean {
    val typecode = place.typecode.trim()
    if (typecode.startsWith("05")) return true
    val hay = "${place.type} ${place.name}"
    return AmapFoodMarkers.any { marker -> hay.contains(marker) }
}

internal fun amapPlaceAskDishesPrompt(place: AmapPlace): String =
    "「${place.name}」招牌菜有什么"

internal fun amapPlaceAskIntroPrompt(place: AmapPlace): String =
    "仔细介绍下「${place.name}」"

internal fun amapPlaceOpenInChatNaviPrompt(place: AmapPlace): String =
    amapPlaceNavigateInChatUserText(place)

private val AmapFoodMarkers = listOf(
    "餐饮", "美食", "冷饮", "奶茶", "茶座", "茶艺", "小吃", "快餐",
    "餐厅", "火锅", "咖啡", "甜品", "面包", "烧烤", "面馆",
    "食堂", "酒吧", "饮品", "蛋糕", "糕饼", "烤串", "麻辣烫",
    "汉堡", "披萨", "寿司", "日料", "西餐", "料理", "小龙虾",
    "串串", "冒菜", "米粉", "拉面", "饺子", "烤鱼",
)

internal fun formatAmapCost(raw: String): String {
    val trimmed = raw.trim().trim('¥', '￥').removeSuffix("元").trim()
    if (trimmed.isBlank() || trimmed == "0" || trimmed == "0.0" || trimmed == "0.00") return ""
    val number = trimmed.toDoubleOrNull()
    return if (number != null) {
        val shown = if (number % 1.0 == 0.0) number.toInt().toString() else trimmed
        "¥$shown"
    } else {
        raw.trim()
    }
}

internal fun formatAmapDistance(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return ""
    val meters = trimmed.removeSuffix("米").removeSuffix("m").removeSuffix("M").trim().toDoubleOrNull()
        ?: return trimmed
    if (meters <= 0) return ""
    return if (meters >= 1000) {
        val km = meters / 1000.0
        if (km % 1.0 == 0.0) "${km.toInt()}公里" else String.format(java.util.Locale.US, "%.1f公里", km)
    } else {
        "${meters.toInt()}米"
    }
}

private fun tokenizeAmapDishes(raw: String): List<String> {
    if (raw.isBlank() || raw == "[]") return emptyList()
    val parts = raw.split(Regex("[;；,，、|/]+")).map { it.trim() }.filter { it.length in 2..16 }
    return parts.ifEmpty { listOf(raw.trim()).filter { it.length in 2..16 } }
}

private fun jsonText(holder: JSONObject?, vararg keys: String): String {
    if (holder == null) return ""
    keys.forEach { key ->
        if (!holder.has(key) || holder.isNull(key)) return@forEach
        val text = holder.opt(key)?.toString()?.trim().orEmpty()
        if (text.isNotBlank() && text != "[]" && !text.equals("null", ignoreCase = true)) return text
    }
    return ""
}
