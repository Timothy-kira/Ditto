package kira.ditto.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class HostHealthReader(private val context: Context) {
    fun snapshot(
        recordHistory: Boolean = true,
        rangeStartMs: Long = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24),
        rangeEndMs: Long = System.currentTimeMillis(),
    ): JSONObject {
        val missing = JSONArray()
        val needActivity = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val activityGranted = !needActivity || isGranted(Manifest.permission.ACTIVITY_RECOGNITION)
        val bodyGranted = isGranted(Manifest.permission.BODY_SENSORS)
        if (needActivity && !activityGranted) missing.put(Manifest.permission.ACTIVITY_RECOGNITION)
        if (!bodyGranted) missing.put(Manifest.permission.BODY_SENSORS)

        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val stepSensor = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        val heartSensor = manager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        val readings = readSensors(
            manager = manager,
            stepSensor = stepSensor.takeIf { activityGranted },
            heartSensor = heartSensor.takeIf { bodyGranted },
        )
        val steps = readings.first?.toLong()
        val heartRate = readings.second
        if (recordHistory && steps != null) {
            StepHistoryStore(context).record(steps)
        }
        val payload = JSONObject()
            .put("source", "device_sensors")
            .put("hasStepCounter", stepSensor != null)
            .put("hasHeartRateSensor", heartSensor != null)
            .put("missingPermissions", missing)
        if (steps != null) payload.put("stepsSinceBoot", steps) else payload.put("stepsSinceBoot", JSONObject.NULL)
        if (heartRate != null) payload.put("heartRateBpm", heartRate.toDouble()) else payload.put("heartRateBpm", JSONObject.NULL)
        payload.put("stepsLabel", stepsLabel(stepSensor != null, activityGranted, steps))
        payload.put("heartRateLabel", heartRateLabel(heartSensor != null, bodyGranted, heartRate))
        if (rangeEndMs > rangeStartMs) {
            val store = StepHistoryStore(context)
            val samples = store.load()
            val buckets = StepHourBuckets.hourly(
                samples = samples,
                rangeStartMs = rangeStartMs,
                rangeEndMs = rangeEndMs,
                hourStartMs = ::localHourStart,
            )
            payload.put("hourlySteps", HourlyStepsJson.array(buckets, "device_sensors"))
            payload.put("hourlyLabel", HourlyStepsJson.label(buckets))
            payload.put("hourlySampleCount", samples.size)
            payload.put(
                "hourlyNote",
                if (buckets.isEmpty()) {
                    "传感器计步器只有开机累计值，没有系统小时历史。" +
                        "支付宝能按小时显示，是因为它自己持续采样累计值再做差值（或读运动健康 SDK）。" +
                        "Ditto 已开始同样采样，采样开始前的小时无法回溯。"
                } else {
                    "按小时步数来自本机对开机累计计步器的采样差值，不是系统回溯。"
                },
            )
        }
        payload.put(
            "note",
            "Host-only snapshot. Device sensors are a fallback; Huawei Health Kit is used when authorized.",
        )
        return payload
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun stepsLabel(hasSensor: Boolean, granted: Boolean, steps: Long?): String = when {
        steps != null -> "开机以来 $steps 步"
        !hasSensor -> "这台手机没有步数传感器"
        !granted -> "需要活动识别权限才能读步数"
        else -> "步数传感器暂无读数"
    }

    private fun heartRateLabel(hasSensor: Boolean, granted: Boolean, bpm: Float?): String = when {
        bpm != null -> "心率 ${bpm.toInt()} 次/分"
        !hasSensor -> "这台手机没有心率传感器"
        !granted -> "需要身体传感器权限才能读心率"
        else -> "心率传感器暂无读数"
    }

    private fun readSensors(
        manager: SensorManager,
        stepSensor: Sensor?,
        heartSensor: Sensor?,
    ): Pair<Float?, Float?> {
        if (stepSensor == null && heartSensor == null) return null to null
        val steps = AtomicReference<Float?>(null)
        val heart = AtomicReference<Float?>(null)
        val gotSteps = AtomicBoolean(false)
        val gotHeart = AtomicBoolean(false)
        val thread = HandlerThread("upa-host-health").apply { start() }
        val handler = Handler(thread.looper)
        val remaining = CountDownLatch(1)
        val pending = AtomicInteger(listOfNotNull(stepSensor, heartSensor).size)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.isEmpty()) return
                val first = when (event.sensor.type) {
                    Sensor.TYPE_STEP_COUNTER ->
                        gotSteps.compareAndSet(false, true).also { if (it) steps.set(event.values[0]) }
                    Sensor.TYPE_HEART_RATE ->
                        gotHeart.compareAndSet(false, true).also { if (it) heart.set(event.values[0]) }
                    else -> false
                }
                if (first && pending.decrementAndGet() <= 0) remaining.countDown()
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        return try {
            var registered = 0
            if (stepSensor != null) {
                if (manager.registerListener(
                        listener,
                        stepSensor,
                        SensorManager.SENSOR_DELAY_NORMAL,
                        handler,
                    )
                ) {
                    registered += 1
                } else {
                    pending.decrementAndGet()
                }
            }
            if (heartSensor != null) {
                if (manager.registerListener(
                        listener,
                        heartSensor,
                        SensorManager.SENSOR_DELAY_NORMAL,
                        handler,
                    )
                ) {
                    registered += 1
                } else {
                    pending.decrementAndGet()
                }
            }
            if (registered == 0) return null to null
            if (pending.get() > 0) remaining.await(1_600, TimeUnit.MILLISECONDS)
            steps.get() to heart.get()
        } catch (_: Throwable) {
            null to null
        } finally {
            runCatching { manager.unregisterListener(listener) }
            thread.quitSafely()
        }
    }
}
