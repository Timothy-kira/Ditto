package kira.ditto.data

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object HourlyStepsJson {
    fun array(buckets: List<HourlyStepBucket>, source: String): JSONArray {
        val format = SimpleDateFormat("MM-dd HH:00", Locale.getDefault())
        return JSONArray().apply {
            buckets.forEach { bucket ->
                put(
                    JSONObject()
                        .put("hour", format.format(Date(bucket.startMs)))
                        .put("startMs", bucket.startMs)
                        .put("endMs", bucket.endMs)
                        .put("steps", bucket.steps)
                        .put("source", source),
                )
            }
        }
    }

    fun label(buckets: List<HourlyStepBucket>): String {
        if (buckets.isEmpty()) return ""
        return buckets.joinToString(" · ") { bucket ->
            "${hourOfDay(bucket.startMs)}点 ${bucket.steps}步"
        }
    }
}

fun localHourStart(epochMs: Long): Long {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = epochMs
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    return calendar.timeInMillis
}

fun hourOfDay(epochMs: Long): Int {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = epochMs
    return calendar.get(Calendar.HOUR_OF_DAY)
}
