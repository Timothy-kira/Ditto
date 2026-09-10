package kira.ditto.browser

import java.net.URI
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Shared across batches. Host entries exist only while requests reference them. */
internal class BrowserFetchLimits(private val maximum: Int = 8, private val perHost: Int = 2) {
    private data class Host(val permits: Semaphore, var users: Int = 0)
    private val global = Semaphore(maximum, true)
    private val hosts = HashMap<String, Host>()

    fun <T> withPermit(url: String, timeoutMs: Long, block: () -> T): T? {
        val hostKey = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return null
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(1))
        val host = synchronized(hosts) {
            hosts.getOrPut(hostKey) { Host(Semaphore(perHost, true)) }.also { it.users++ }
        }
        var hostAcquired = false
        var globalAcquired = false
        try {
            hostAcquired = host.permits.tryAcquire((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
            if (!hostAcquired) return null
            globalAcquired = global.tryAcquire((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
            if (!globalAcquired) return null
            return block()
        } finally {
            if (globalAcquired) global.release()
            if (hostAcquired) host.permits.release()
            synchronized(hosts) {
                host.users--
                if (host.users == 0) hosts.remove(hostKey)
            }
        }
    }
}
