package kira.ditto.agentmode.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class AdbMdns(
    context: Context,
    private val serviceType: String,
) {
    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(NsdManager::class.java)
    private val port = AtomicInteger(-1)
    private var multicastLock: WifiManager.MulticastLock? = null
    private var started = false

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            runCatching {
                @Suppress("DEPRECATION")
                nsdManager.resolveService(
                    serviceInfo,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            if (serviceInfo.port > 0) {
                                port.set(serviceInfo.port)
                            }
                        }
                    },
                )
            }
        }
    }

    fun discover(timeoutMillis: Long): Int {
        start()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            val found = port.get()
            if (found > 0) {
                stop()
                return found
            }
            CountDownLatch(1).await(200, TimeUnit.MILLISECONDS)
        }
        stop()
        return port.get()
    }

    private fun start() {
        if (started) return
        started = true
        multicastLock = runCatching {
            appContext.getSystemService(WifiManager::class.java)
                ?.createMulticastLock("aether-adb-mdns")
                ?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }.getOrNull()
        runCatching {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }
    }

    private fun stop() {
        if (!started) return
        started = false
        runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    companion object {
        const val TlsConnect = "_adb-tls-connect._tcp"
        const val TlsPairing = "_adb-tls-pairing._tcp"
    }
}
