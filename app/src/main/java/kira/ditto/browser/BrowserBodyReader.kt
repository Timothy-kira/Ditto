package kira.ditto.browser

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InterruptedIOException

internal data class BrowserBody(val bytes: ByteArray, val truncated: Boolean)

/** Bounds allocation and checks the request deadline between reads. The transport must also
 * cancel/close a blocked stream; an InputStream cannot impose its own socket timeout. */
internal fun readBrowserBody(
    stream: InputStream,
    maxBytes: Int,
    deadlineNanos: Long,
    now: () -> Long = System::nanoTime,
): BrowserBody {
    require(maxBytes > 0)
    val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
    val buffer = ByteArray(minOf(maxBytes, 8192))
    while (output.size() < maxBytes) {
        if (Thread.currentThread().isInterrupted || now() >= deadlineNanos) {
            throw InterruptedIOException("Browser body read cancelled or deadline exceeded")
        }
        val count = stream.read(buffer, 0, minOf(buffer.size, maxBytes - output.size()))
        if (count < 0) return BrowserBody(output.toByteArray(), false)
        if (count == 0) throw InterruptedIOException("Browser body stream made no progress")
        output.write(buffer, 0, count)
    }
    // Conservatively mark an exact-limit body: do not block on another byte just to detect EOF.
    return BrowserBody(output.toByteArray(), true)
}
