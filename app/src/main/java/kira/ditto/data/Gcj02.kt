package kira.ditto.data

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WGS-84(Android 定位)→ GCJ-02(高德瓦片/POI)坐标转换。
 * 标准公开算法;中国境外不偏移。
 */
internal object Gcj02 {
    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    fun fromWgs84(lng: Double, lat: Double): Pair<Double, Double> {
        if (outOfChina(lng, lat)) return lat to lng
        var dLat = transformLat(lng - 105.0, lat - 35.0)
        var dLng = transformLng(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * Math.PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * Math.PI)
        dLng = (dLng * 180.0) / (A / sqrtMagic * cos(radLat) * Math.PI)
        return (lat + dLat) to (lng + dLng)
    }

    /** GCJ-02(高德) → WGS-84(Leaflet/OSM). Returns lng to lat. */
    fun toWgs84(lng: Double, lat: Double): Pair<Double, Double> {
        if (outOfChina(lng, lat)) return lng to lat
        val (shiftedLat, shiftedLng) = fromWgs84(lng, lat)
        return (lng * 2.0 - shiftedLng) to (lat * 2.0 - shiftedLat)
    }

    /** Amap around/text search wants GCJ-02 `lng,lat` with at most 6 decimal places. */
    fun amapLngLat(wgsLng: Double, wgsLat: Double): String {
        val (gcjLat, gcjLng) = fromWgs84(wgsLng, wgsLat)
        return String.format(java.util.Locale.US, "%.6f,%.6f", gcjLng, gcjLat)
    }

    private fun outOfChina(lng: Double, lat: Double): Boolean =
        lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * Math.PI) + 40.0 * sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * Math.PI) + 320.0 * sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * Math.PI) + 40.0 * sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * Math.PI) + 300.0 * sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return ret
    }
}
