package kira.ditto.agentmode

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import java.util.concurrent.atomic.AtomicBoolean

internal class AgentModeInternalAudioCapture(
    @Suppress("unused") private val context: Context,
) {
    private val lock = Any()
    private var record: AudioRecord? = null
    private var writer: ParcelFileDescriptor? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)

    fun start(sampleRateHz: Int): ParcelFileDescriptor {
        stop()
        val rate = if (sampleRateHz > 0) sampleRateHz else 16_000
        val minBuf = AudioRecord.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) error("ASR_CAPTURE_UNAVAILABLE: AudioRecord buffer unavailable.")
        val source = MediaRecorder.AudioSource.REMOTE_SUBMIX
        val created = runCatching {
            AudioRecord(
                source,
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2,
            )
        }.getOrNull()
        if (created == null || created.state != AudioRecord.STATE_INITIALIZED) {
            created?.release()
            error("ASR_CAPTURE_UNAVAILABLE: playback capture is not available.")
        }
        val pipes = ParcelFileDescriptor.createPipe()
        val readEnd = pipes[0]
        val writeEnd = pipes[1]
        synchronized(lock) {
            record = created
            writer = writeEnd
            running.set(true)
            thread = Thread({
                try {
                    created.startRecording()
                    val buffer = ByteArray(minBuf.coerceAtLeast(3_200))
                    ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { out ->
                        while (running.get()) {
                            val read = created.read(buffer, 0, buffer.size)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    running.set(false)
                    runCatching { created.stop() }
                    runCatching { created.release() }
                }
            }, "aether-internal-audio").also { it.start() }
        }
        return readEnd
    }

    fun stop() {
        running.set(false)
        synchronized(lock) {
            runCatching { thread?.join(400) }
            thread = null
            record = null
            writer = null
        }
    }
}
