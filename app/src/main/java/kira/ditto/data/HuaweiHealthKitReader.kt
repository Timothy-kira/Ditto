package kira.ditto.data

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.result.ActivityResult
import com.huawei.hmf.tasks.Task
import com.huawei.hmf.tasks.Tasks
import com.huawei.hms.hihealth.HiHealthOptions
import com.huawei.hms.hihealth.HuaweiHiHealth
import com.huawei.hms.hihealth.data.DataType
import com.huawei.hms.hihealth.data.Field
import com.huawei.hms.hihealth.data.SamplePoint
import com.huawei.hms.hihealth.data.SampleSet
import com.huawei.hms.hihealth.options.ReadOptions
import com.huawei.hms.support.api.entity.auth.Scope
import com.huawei.hms.support.hwid.HuaweiIdAuthManager
import com.huawei.hms.support.hwid.request.HuaweiIdAuthParams
import com.huawei.hms.support.hwid.request.HuaweiIdAuthParamsHelper
import com.huawei.hms.support.hwid.result.AuthHuaweiId
import kira.ditto.BuildConfig
import java.security.MessageDigest
import java.util.Calendar
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

class HuaweiHealthKitReader(
    private val context: Context,
    private val activityProvider: () -> Activity? = { HuaweiHealthAuthBridge.activity() },
) {
    fun snapshot(
        rangeStartMs: Long = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24),
        rangeEndMs: Long = System.currentTimeMillis(),
    ): JSONObject {
        val payload = JSONObject()
            .put("source", "huawei_health_kit")
            .put("access", "read-only")
            .put("packageName", HuaweiHealthPackage)
            .put("appInstalled", isInstalled(HuaweiHealthPackage))
            .put("appIdConfigured", BuildConfig.HUAWEI_APP_ID.isNotBlank())
            .put("signingSha256", signingSha256())
        if (!isHuaweiFamily()) {
            return payload.put("available", false).put("reason", "not_huawei_device")
        }
        if (BuildConfig.HUAWEI_APP_ID.isBlank()) {
            return payload
                .put("available", false)
                .put("authorized", false)
                .put(
                    "reason",
                    "missing_app_id: 在华为开发者联盟为 ${context.packageName} 开通运动健康服务，" +
                        "把 App ID 写入 Aether/local.properties 的 huawei.appId，并登记 SHA-256 ${signingSha256()}",
                )
        }
        return try {
            val account = ensureAuthorized()
            if (account == null) {
                return payload
                    .put("available", true)
                    .put("authorized", false)
                    .put("reason", "user_auth_required")
            }
            val options = hiHealthOptions()
            val signed = HuaweiIdAuthManager.getExtendedAuthResult(options) ?: account
            val controller = HuaweiHiHealth.getDataController(context, signed)
            val todaySteps = readToday(controller.readTodaySummation(DataType.DT_CONTINUOUS_STEPS_DELTA))
            val todayCalories = readToday(controller.readTodaySummation(DataType.DT_CONTINUOUS_CALORIES_BURNT))
            val todayDistance = readToday(controller.readTodaySummation(DataType.DT_CONTINUOUS_DISTANCE_DELTA))
            val heart = readLatestHeart(controller)
            val sleep = readRecentSleep(controller)
            val hourly = readHourlySteps(controller, rangeStartMs, rangeEndMs)
            payload
                .put("available", true)
                .put("authorized", true)
                .put("todaySteps", todaySteps ?: JSONObject.NULL)
                .put("todayCaloriesKcal", todayCalories ?: JSONObject.NULL)
                .put("todayDistanceMeters", todayDistance ?: JSONObject.NULL)
                .put("heartRateBpm", heart ?: JSONObject.NULL)
                .put("sleep", sleep)
                .put("hourlySteps", HourlyStepsJson.array(hourly, "huawei_health_kit"))
                .put("hourlyLabel", HourlyStepsJson.label(hourly))
            if (todaySteps != null) {
                payload.put("stepsLabel", "今日 $todaySteps 步")
            }
            val sleepHours = sleep.optDouble("hours", Double.NaN)
            if (!sleepHours.isNaN() && sleepHours > 0) {
                payload.put("sleepHours", sleepHours)
                payload.put("sleepLabel", "睡眠 ${"%.1f".format(sleepHours)} 小时")
            }
            if (heart != null) {
                payload.put("heartRateLabel", "心率 ${heart.toInt()} 次/分")
            }
            payload
        } catch (error: Throwable) {
            payload
                .put("available", true)
                .put("authorized", false)
                .put("reason", error.message ?: error.javaClass.simpleName)
        }
    }

    private fun ensureAuthorized(): AuthHuaweiId? {
        val params = HuaweiIdAuthParamsHelper(HuaweiIdAuthParams.DEFAULT_AUTH_REQUEST_PARAM)
            .setAccessToken()
            .setScopeList(readScopes())
            .createParams()
        val service = HuaweiIdAuthManager.getService(context, params)
        val silent = runCatching {
            awaitTask(service.silentSignIn(), 8)
        }.getOrNull()
        if (silent != null) return silent
        val activity = activityProvider() ?: return null
        val host = HuaweiHealthAuthBridge.host ?: return null
        val latch = CountDownLatch(1)
        val result = arrayOfNulls<ActivityResult>(1)
        Handler(Looper.getMainLooper()).post {
            host.requestAuthorization(service.signInIntent) { authResult ->
                result[0] = authResult
                latch.countDown()
            }
        }
        if (!latch.await(90, TimeUnit.SECONDS)) return null
        val activityResult = result[0] ?: return null
        if (activityResult.resultCode != Activity.RESULT_OK) return null
        return awaitTask(HuaweiIdAuthManager.parseAuthResultFromIntent(activityResult.data), 15)
    }

    private fun readToday(task: Task<SampleSet>): Double? {
        val set = runCatching { awaitTask(task, 12) }.getOrNull() ?: return null
        return extractNumber(set)
    }

    private fun readHourlySteps(
        controller: com.huawei.hms.hihealth.DataController,
        rangeStartMs: Long,
        rangeEndMs: Long,
    ): List<HourlyStepBucket> {
        if (rangeEndMs <= rangeStartMs) return emptyList()
        val options = ReadOptions.Builder()
            .read(DataType.DT_CONTINUOUS_STEPS_DELTA)
            .setTimeRange(rangeStartMs, rangeEndMs, TimeUnit.MILLISECONDS)
            .build()
        val reply = runCatching { awaitTask(controller.read(options), 12) }.getOrNull() ?: return emptyList()
        val totals = linkedMapOf<Long, Long>()
        reply.sampleSets.forEach { set ->
            set.samplePoints.forEach { point ->
                val steps = extractNumber(point)?.toLong() ?: return@forEach
                if (steps <= 0L) return@forEach
                val bucket = localHourStart(point.getStartTime(TimeUnit.MILLISECONDS))
                if (bucket + StepHourBuckets.HourMs > rangeStartMs && bucket < rangeEndMs) {
                    totals[bucket] = (totals[bucket] ?: 0L) + steps
                }
            }
        }
        return totals.entries.map { (start, steps) ->
            HourlyStepBucket(
                startMs = start,
                endMs = start + StepHourBuckets.HourMs,
                steps = steps,
            )
        }
    }

    private fun readLatestHeart(controller: com.huawei.hms.hihealth.DataController): Double? {
        val end = System.currentTimeMillis()
        val start = end - TimeUnit.DAYS.toMillis(1)
        val options = ReadOptions.Builder()
            .read(DataType.DT_INSTANTANEOUS_HEART_RATE)
            .setTimeRange(start, end, TimeUnit.MILLISECONDS)
            .build()
        val reply = runCatching { awaitTask(controller.read(options), 12) }.getOrNull() ?: return null
        return reply.sampleSets.asSequence()
            .mapNotNull(::extractNumber)
            .lastOrNull()
    }

    private fun readRecentSleep(controller: com.huawei.hms.hihealth.DataController): JSONObject {
        val end = System.currentTimeMillis()
        val start = endOfYesterdayMorning()
        val options = ReadOptions.Builder()
            .read(DataType.DT_CONTINUOUS_SLEEP)
            .setTimeRange(start, end, TimeUnit.MILLISECONDS)
            .build()
        val reply = runCatching { awaitTask(controller.read(options), 12) }.getOrNull()
        val points = JSONArray()
        var totalMs = 0L
        reply?.sampleSets?.forEach { set ->
            set.samplePoints.forEach { point ->
                val duration = (point.getEndTime(TimeUnit.MILLISECONDS) - point.getStartTime(TimeUnit.MILLISECONDS))
                    .coerceAtLeast(0L)
                totalMs += duration
                points.put(
                    JSONObject()
                        .put("start", point.getStartTime(TimeUnit.MILLISECONDS))
                        .put("end", point.getEndTime(TimeUnit.MILLISECONDS))
                        .put("durationMs", duration),
                )
            }
        }
        return JSONObject()
            .put("hours", if (totalMs > 0) totalMs / 3_600_000.0 else JSONObject.NULL)
            .put("segments", points)
    }

    private fun extractNumber(set: SampleSet): Double? {
        val point = set.samplePoints.lastOrNull() ?: return null
        return extractNumber(point)
    }

    private fun extractNumber(point: SamplePoint): Double? {
        val fields = listOf(
            Field.FIELD_STEPS_DELTA,
            Field.FIELD_STEPS,
            Field.FIELD_BPM,
            Field.FIELD_CALORIES,
            Field.FIELD_CALORIES_TOTAL,
            Field.FIELD_DISTANCE,
            Field.FIELD_DISTANCE_DELTA,
        )
        fields.forEach { field ->
            val value = runCatching { point.getFieldValue(field) }.getOrNull() ?: return@forEach
            runCatching { value.asIntValue().toDouble() }.getOrNull()?.let { return it }
            runCatching { value.asLongValue().toDouble() }.getOrNull()?.let { return it }
            runCatching { value.asFloatValue().toDouble() }.getOrNull()?.let { return it }
            runCatching { value.asDoubleValue() }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun <T> awaitTask(task: Task<T>, seconds: Long): T =
        Tasks.await(task, seconds, TimeUnit.SECONDS)

    private fun hiHealthOptions(): HiHealthOptions {
        val builder = HiHealthOptions.builder()
        readDataTypes().forEach { type ->
            builder.addDataType(type, HiHealthOptions.ACCESS_READ)
        }
        return builder.build()
    }

    private fun readScopes(): List<Scope> = listOf(
        HuaweiHiHealth.SCOPE_HEALTHKIT_HUAWEIHEALTH_LINK,
        HuaweiHiHealth.SCOPE_HEALTHKIT_STEP_READ,
        HuaweiHiHealth.SCOPE_HEALTHKIT_CALORIES_READ,
        HuaweiHiHealth.SCOPE_HEALTHKIT_DISTANCE_READ,
        HuaweiHiHealth.SCOPE_HEALTHKIT_HEARTHEALTH_READ,
        HuaweiHiHealth.SCOPE_HEALTHKIT_HEARTRATE_READ,
        HuaweiHiHealth.SCOPE_HEALTHKIT_SLEEP_READ,
        HuaweiHiHealth.SCOPE_HEALTHKIT_STRESS_READ,
        HuaweiHiHealth.SCOPE_HEALTHKIT_ACTIVITY_RECORD_READ,
        HuaweiHiHealth.SCOPE_HEALTHKIT_ACTIVITY_READ,
    )

    private fun readDataTypes(): List<DataType> = listOf(
        DataType.DT_CONTINUOUS_STEPS_DELTA,
        DataType.DT_CONTINUOUS_CALORIES_BURNT,
        DataType.DT_CONTINUOUS_DISTANCE_DELTA,
        DataType.DT_INSTANTANEOUS_HEART_RATE,
        DataType.DT_CONTINUOUS_SLEEP,
    )

    private fun isHuaweiFamily(): Boolean {
        val blob = listOf(Build.MANUFACTURER, Build.BRAND, Build.FINGERPRINT)
            .joinToString(" ")
            .lowercase()
        return blob.contains("huawei") || blob.contains("honor")
    }

    private fun isInstalled(packageName: String): Boolean =
        runCatching {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrDefault(false)

    private fun signingSha256(): String {
        val bytes = if (Build.VERSION.SDK_INT >= 28) {
            val info = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
            info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNATURES,
            ).signatures?.firstOrNull()?.toByteArray()
        } ?: return ""
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(":") { "%02X".format(it) }
    }

    private fun endOfYesterdayMorning(): Long {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -1)
        calendar.set(Calendar.HOUR_OF_DAY, 18)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    companion object {
        private const val HuaweiHealthPackage = "com.huawei.health"
    }
}
