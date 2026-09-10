package kira.ditto.audio

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.annotation.Keep
import kira.ditto.data.AsrChunkAssembler
import kira.ditto.data.pcm16LeRmsRaw
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

@Keep
class OboeMicCapture(
    private val onRms: (Float) -> Unit,
    private val onPcm: (ByteArray) -> Unit,
    private val collectPcm: Boolean = false,
) {
    private val running = AtomicBoolean(false)
    private val collected = ByteArrayOutputStream()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var workerThread: HandlerThread? = null
    private var worker: Handler? = null

    fun start(): Boolean {
        if (!available) return false
        if (!running.compareAndSet(false, true)) return false
        synchronized(collected) { collected.reset() }
        val thread = HandlerThread("oboe-mic").also { it.start() }
        workerThread = thread
        worker = Handler(thread.looper)
        val started = runCatching { nativeStart(AsrChunkAssembler.SampleRateHz) }.getOrDefault(false)
        if (!started) {
            running.set(false)
            runCatching { nativeStop() }
            thread.quitSafely()
            workerThread = null
            worker = null
            return false
        }
        android.util.Log.i(
            "AetherAsr",
            "oboe started nativeRate=${runCatching { nativeSampleRate() }.getOrDefault(-1)}",
        )
        return true
    }

    fun stop(): ByteArray {
        running.set(false)
        runCatching { nativeStop() }
        workerThread?.quitSafely()
        runCatching { workerThread?.join(400) }
        workerThread = null
        worker = null
        return synchronized(collected) { collected.toByteArray() }
    }

    @Keep
    fun onNativePcm(bytes: ByteArray) {
        if (!running.get() || bytes.isEmpty()) return
        val payload = bytes
        val handler = worker
        if (handler == null) {
            dispatchPcm(payload)
            return
        }
        handler.post { dispatchPcm(payload) }
    }

    private fun dispatchPcm(bytes: ByteArray) {
        if (collectPcm) {
            synchronized(collected) { collected.write(bytes) }
        }
        onPcm(bytes)
        val level = pcm16LeRmsRaw(bytes)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onRms(level)
        } else {
            mainHandler.post { onRms(level) }
        }
    }

    private external fun nativeStart(sampleRate: Int): Boolean

    private external fun nativeSampleRate(): Int

    private external fun nativeStop()

    companion object {
        val available: Boolean = try {
            System.loadLibrary("ditto_oboe")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
