package kira.ditto.data

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streams PCM audio to [AudioTrack].
 *
 * [write] must run off the main thread. It never holds a lock across the
 * blocking audio write, so [stop] from the UI thread cannot ANR.
 */
class AudioTrackPlayer(
    context: Context? = null,
    private val sampleRateHz: Int = SAMPLE_RATE,
) {
    companion object {
        private const val TAG = "AetherTts"
        const val SAMPLE_RATE = 24000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private val CandidateRates = intArrayOf(24000, 22050, 16000, 44100, 48000)
    }

    private val appContext = context?.applicationContext
    private val gate = Any()
    @Volatile private var audioTrack: AudioTrack? = null
    private val stopped = AtomicBoolean(false)
    private val playing = AtomicBoolean(false)
    private var focusRequest: AudioFocusRequest? = null
    private var preambleConsumed = false

    fun start() {
        if (stopped.get()) return
        synchronized(gate) {
            if (audioTrack != null) return
            val track = buildTrack() ?: run {
                Log.e(TAG, "AudioTrack failed to initialize")
                return
            }
            requestAudioFocus()
            try {
                track.play()
            } catch (error: Exception) {
                Log.e(TAG, "AudioTrack.play failed", error)
                runCatching { track.release() }
                abandonAudioFocus()
                return
            }
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                Log.e(TAG, "AudioTrack not playing, state=${track.playState}")
                runCatching { track.release() }
                abandonAudioFocus()
                return
            }
            audioTrack = track
            playing.set(true)
            Log.i(TAG, "AudioTrack started rate=${track.sampleRate} buf=${track.bufferSizeInFrames}")
        }
    }

    fun write(chunk: ByteArray) {
        if (stopped.get() || chunk.isEmpty()) return
        val pcm = consumePreamble(chunk)
        if (pcm.isEmpty()) return
        val track = audioTrack ?: return
        var offset = 0
        while (offset < pcm.size && !stopped.get()) {
            val written = try {
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    Log.w(TAG, "AudioTrack not playing during write, state=${track.playState}")
                    break
                }
                track.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_NON_BLOCKING)
            } catch (error: Exception) {
                Log.w(TAG, "AudioTrack write aborted", error)
                break
            }
            when {
                written < 0 -> {
                    Log.w(TAG, "AudioTrack write error: $written")
                    break
                }
                written == 0 -> SystemClock.sleep(8)
                else -> offset += written
            }
        }
    }

    fun drain() {
        val track = audioTrack ?: return
        val bytesPerSec = (track.sampleRate * 2).coerceAtLeast(1)
        val bufferBytes = track.bufferSizeInFrames.coerceAtLeast(1) * 2
        val waitMs = ((bufferBytes * 1000L) / bytesPerSec) + 80L
        val deadline = SystemClock.elapsedRealtime() + waitMs
        while (!stopped.get() && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(16)
        }
    }

    fun stop() {
        stopped.set(true)
        playing.set(false)
        val track = synchronized(gate) {
            val current = audioTrack
            audioTrack = null
            current
        }
        if (track != null) {
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.release() }
        }
        abandonAudioFocus()
        Log.i(TAG, "AudioTrack stopped")
    }

    fun isActive(): Boolean = playing.get() && !stopped.get()

    private fun buildTrack(): AudioTrack? {
        val preferred = sampleRateHz
        val rates = (listOf(preferred) + CandidateRates.filter { it != preferred })
        for (rate in rates) {
            val minBuf = AudioTrack.getMinBufferSize(rate, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (minBuf <= 0) continue
            val bufferSize = (minBuf * 4).coerceAtLeast(8192)
            val track = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(rate)
                            .setEncoding(AUDIO_FORMAT)
                            .setChannelMask(CHANNEL_CONFIG)
                            .build(),
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            }.getOrNull()
            if (track != null && track.state == AudioTrack.STATE_INITIALIZED) {
                if (rate != preferred) {
                    Log.w(TAG, "AudioTrack using ${rate}Hz instead of ${preferred}Hz")
                }
                return track
            }
            runCatching { track?.release() }
        }
        return null
    }

    private fun consumePreamble(chunk: ByteArray): ByteArray {
        if (preambleConsumed) return chunk
        preambleConsumed = true
        if (chunk.size >= 4 && chunk[0] == '{'.code.toByte()) {
            Log.w(TAG, "TTS body looks like JSON, not PCM")
            return ByteArray(0)
        }
        if (chunk.size >= 12 &&
            chunk[0] == 'R'.code.toByte() &&
            chunk[1] == 'I'.code.toByte() &&
            chunk[2] == 'F'.code.toByte() &&
            chunk[3] == 'F'.code.toByte()
        ) {
            val dataOffset = indexOfDataChunk(chunk)
            if (dataOffset >= 0 && dataOffset < chunk.size) {
                Log.i(TAG, "stripped WAV header at $dataOffset")
                return chunk.copyOfRange(dataOffset, chunk.size)
            }
        }
        return chunk
    }

    private fun indexOfDataChunk(bytes: ByteArray): Int {
        var i = 12
        while (i + 8 <= bytes.size) {
            val id0 = bytes[i].toInt().toChar()
            val id1 = bytes[i + 1].toInt().toChar()
            val id2 = bytes[i + 2].toInt().toChar()
            val id3 = bytes[i + 3].toInt().toChar()
            val size = (bytes[i + 4].toInt() and 0xff) or
                ((bytes[i + 5].toInt() and 0xff) shl 8) or
                ((bytes[i + 6].toInt() and 0xff) shl 16) or
                ((bytes[i + 7].toInt() and 0xff) shl 24)
            if (id0 == 'd' && id1 == 'a' && id2 == 't' && id3 == 'a') {
                return i + 8
            }
            val step = 8 + size.coerceAtLeast(0)
            if (step <= 0) break
            i += step
        }
        return 44.coerceAtMost(bytes.size)
    }

    private fun requestAudioFocus() {
        val context = appContext ?: return
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { }
            .build()
        focusRequest = request
        val result = manager.requestAudioFocus(request)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "audio focus not granted: $result")
        }
    }

    private fun abandonAudioFocus() {
        val context = appContext ?: return
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val request = focusRequest ?: return
        focusRequest = null
        runCatching { manager.abandonAudioFocusRequest(request) }
    }
}
