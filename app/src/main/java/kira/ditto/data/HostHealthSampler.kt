package kira.ditto.data

import android.Manifest
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import java.util.concurrent.TimeUnit

class HostHealthSampler(context: Context) {
    private val appContext = context.applicationContext
    private val store = StepHistoryStore(appContext)
    private val thread = HandlerThread("upa-step-sampler").apply { start() }
    private val handler = Handler(thread.looper)
    private val manager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var registered = false
    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_STEP_COUNTER || event.values.isEmpty()) return
            store.record(event.values[0].toLong())
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }
    private val tick = object : Runnable {
        override fun run() {
            sampleNowInternal()
            handler.postDelayed(this, SampleIntervalMs)
        }
    }

    fun start() {
        handler.removeCallbacks(tick)
        handler.post {
            registerCounter()
            sampleNowInternal()
        }
        handler.postDelayed(tick, SampleIntervalMs)
    }

    fun sampleNow() {
        handler.post { sampleNowInternal() }
    }

    private fun sampleNowInternal() {
        registerCounter()
        runCatching {
            val snapshot = HostHealthReader(appContext).snapshot(
                recordHistory = true,
                rangeStartMs = 0L,
                rangeEndMs = 0L,
            )
            if (!snapshot.isNull("stepsSinceBoot")) {
                store.record(snapshot.getLong("stepsSinceBoot"))
            }
        }
    }

    private fun registerCounter() {
        if (registered || !activityPermissionGranted()) return
        val sensor = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
        registered = manager.registerListener(
            listener,
            sensor,
            SensorManager.SENSOR_DELAY_NORMAL,
            TimeUnit.MINUTES.toMicros(5).toInt().coerceAtLeast(1),
            handler,
        )
    }

    private fun activityPermissionGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACTIVITY_RECOGNITION,
            ) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val SampleIntervalMs = 5L * 60L * 1000L

        @Volatile
        private var instance: HostHealthSampler? = null

        fun start(context: Context) {
            synchronized(this) {
                if (instance != null) return
                HostHealthSampler(context).also {
                    instance = it
                    it.start()
                }
            }
        }

        fun sampleNow(context: Context) {
            start(context)
            instance?.sampleNow()
        }
    }
}
