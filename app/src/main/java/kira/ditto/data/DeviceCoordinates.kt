package kira.ditto.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class DeviceCoordinates(
    val latitude: Double,
    val longitude: Double,
    val elapsedRealtimeNanos: Long = 0L,
    val accuracyMeters: Float = Float.MAX_VALUE,
) {
    fun qweatherLocation(): String = "$longitude,$latitude"

    fun amapLocation(): String = Gcj02.amapLngLat(longitude, latitude)

    fun isFresh(maxAgeMs: Long): Boolean {
        if (elapsedRealtimeNanos <= 0L) return false
        val ageMs = (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000L
        return ageMs in 0 until maxAgeMs
    }
}

fun hasLocationPermission(context: Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    return fine || coarse
}

fun readDeviceCoordinates(context: Context): DeviceCoordinates? {
    if (!hasLocationPermission(context)) return null
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    return bestLastKnown(context, manager)
}

@SuppressLint("MissingPermission")
fun awaitDeviceCoordinates(
    context: Context,
    timeoutMs: Long = 10_000L,
): DeviceCoordinates? {
    val cached = readDeviceCoordinates(context)
    if (cached != null && cached.isFresh(90_000L) && cached.accuracyMeters <= 80f) return cached
    if (!hasLocationPermission(context)) return cached
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return cached
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    val providers = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            manager.isProviderEnabled(LocationManager.FUSED_PROVIDER)
        ) {
            add(LocationManager.FUSED_PROVIDER)
        }
        if (fine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            add(LocationManager.GPS_PROVIDER)
        }
        if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            add(LocationManager.NETWORK_PROVIDER)
        }
    }.distinct()
    if (providers.isEmpty()) return cached
    val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(1_000L)
    for (provider in providers) {
        val remaining = deadline - SystemClock.elapsedRealtime()
        if (remaining <= 200L) break
        val slice = remaining.coerceAtMost(4_000L)
        currentLocation(context, manager, provider, slice)?.let { return it }
        bestLastKnown(context, manager)?.let { return it }
    }
    return bestLastKnown(context, manager) ?: cached
}

fun warmDeviceCoordinatesAsync(context: Context) {
    if (!GpsWarmStarted.compareAndSet(false, true)) return
    Thread(
        { awaitDeviceCoordinates(context.applicationContext, 15_000L) },
        "upa-gps-warm",
    ).apply {
        isDaemon = true
        start()
    }
}

@SuppressLint("MissingPermission")
private fun bestLastKnown(context: Context, manager: LocationManager): DeviceCoordinates? {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    val providers = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
        if (fine) add(LocationManager.GPS_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
        add(LocationManager.PASSIVE_PROVIDER)
    }.distinct()
    val best = providers.mapNotNull { provider ->
        runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
    }.minWithOrNull(
        compareBy<Location> { location ->
            val ageMs = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
            when {
                ageMs < 30_000L -> 0
                ageMs < 120_000L -> 1
                else -> 2
            }
        }.thenBy { it.accuracy },
    ) ?: return null
    return best.toCoordinates()
}

@SuppressLint("MissingPermission")
private fun currentLocation(
    context: Context,
    manager: LocationManager,
    provider: String,
    timeoutMs: Long,
): DeviceCoordinates? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    val latch = CountDownLatch(1)
    var result: DeviceCoordinates? = null
    val cancel = CancellationSignal()
    val executor = ContextCompat.getMainExecutor(context)
    val deliver: (Location?) -> Unit = { location ->
        if (location != null) result = location.toCoordinates()
        latch.countDown()
    }
    val start = Runnable {
        try {
            manager.getCurrentLocation(provider, cancel, executor, deliver)
        } catch (_: Throwable) {
            latch.countDown()
        }
    }
    if (Looper.myLooper() == Looper.getMainLooper()) {
        start.run()
    } else {
        Handler(Looper.getMainLooper()).post(start)
    }
    if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
        runCatching { cancel.cancel() }
    }
    return result
}

private fun Location.toCoordinates(): DeviceCoordinates? {
    if (!latitude.isFinite() || !longitude.isFinite()) return null
    return DeviceCoordinates(
        latitude = latitude,
        longitude = longitude,
        elapsedRealtimeNanos = elapsedRealtimeNanos,
        accuracyMeters = if (hasAccuracy()) accuracy else Float.MAX_VALUE,
    )
}

private val GpsWarmStarted = AtomicBoolean(false)
