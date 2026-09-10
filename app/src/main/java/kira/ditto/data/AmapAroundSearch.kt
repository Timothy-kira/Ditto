package kira.ditto.data

import org.json.JSONObject

internal object AmapAroundSearch {
    private val NearbyPattern = Regex("附近|周边|旁边|周围|nearby|around", RegexOption.IGNORE_CASE)

    fun looksNearby(text: String): Boolean = NearbyPattern.containsMatchIn(text)

    fun stripNearby(keywords: String): String {
        val stripped = keywords.replace(NearbyPattern, " ").replace(Regex("[\\s,，]+"), " ").trim()
        return stripped.ifBlank { keywords.trim() }
    }

    fun hasLngLat(location: String): Boolean {
        val parts = location.split(',')
        if (parts.size < 2) return false
        val lng = parts[0].trim().toDoubleOrNull()
        val lat = parts[1].trim().toDoubleOrNull()
        return lng != null && lat != null
    }

    fun withDeviceLocation(arguments: JSONObject, deviceLocation: String?): JSONObject {
        val current = GmailCodec.stringArg(arguments, "location")
        if (hasLngLat(current) || deviceLocation.isNullOrBlank() || !hasLngLat(deviceLocation)) {
            return arguments
        }
        return JSONObject(arguments.toString()).put("location", deviceLocation)
    }

    fun divertTextSearch(arguments: JSONObject, deviceLocation: String?): JSONObject? {
        val keywords = GmailCodec.stringArg(arguments, "keywords")
        if (!looksNearby(keywords) || deviceLocation.isNullOrBlank() || !hasLngLat(deviceLocation)) {
            return null
        }
        return JSONObject(arguments.toString())
            .put("keywords", stripNearby(keywords))
            .put("location", deviceLocation)
            .put("city", "")
            .put("region", "")
            .put("citylimit", "false")
    }
}
