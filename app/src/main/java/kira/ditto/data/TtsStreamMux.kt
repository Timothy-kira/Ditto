package kira.ditto.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Multiplexes streaming text into sentence-level TTS requests and plays audio
 * in order. All network and AudioTrack work stays off the main thread.
 */
class TtsStreamMux(
    private val router: TtsEngineRouter,
    private val settings: AppSettings,
    context: Context? = null,
) {
    companion object {
        private const val TAG = "AetherTtsMux"
        private val SentenceBoundary = Regex("[。！？；\\n.!?;]")
        private const val MinSentenceLength = 4
    }

    private val appContext = context?.applicationContext
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var player: AudioTrackPlayer? = null
    private var textBuffer = StringBuilder()
    private var sentenceIndex = 0
    private var incoming = Channel<Any>(Channel.UNLIMITED)
    private var sentenceQueue = Channel<String>(Channel.UNLIMITED)
    private var audioQueue = Channel<ByteArray>(Channel.UNLIMITED)
    private var extractJob: Job? = null
    private var synthJob: Job? = null
    private var playbackJob: Job? = null
    @Volatile private var stopped = false

    var onPlaybackStart: (() -> Unit)? = null
    var onPlaybackEnd: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    fun start() {
        stopJobs(/* releasePlayer = */ true)
        if (!scope.isActive) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
        stopped = false
        textBuffer = StringBuilder()
        sentenceIndex = 0
        incoming = Channel(Channel.UNLIMITED)
        sentenceQueue = Channel(Channel.UNLIMITED)
        audioQueue = Channel(Channel.UNLIMITED)
        player = AudioTrackPlayer(appContext)
        startExtractLoop()
        startSynthLoop()
        startPlaybackLoop()
    }

    fun feed(delta: String) {
        if (stopped || delta.isEmpty()) return
        incoming.trySend(delta)
    }

    fun flush() {
        if (stopped) return
        incoming.trySend(Flush)
    }

    fun stop() {
        stopped = true
        incoming.close()
        sentenceQueue.close()
        audioQueue.close()
        stopJobs(releasePlayer = true)
        scope.cancel()
    }

    private fun startExtractLoop() {
        extractJob = scope.launch {
            try {
                for (item in incoming) {
                    if (stopped) break
                    when (item) {
                        Flush -> {
                            val remaining = textBuffer.toString().trim()
                            if (remaining.length >= MinSentenceLength) {
                                queueSentence(remaining)
                            }
                            textBuffer = StringBuilder()
                            sentenceQueue.close()
                            break
                        }
                        is String -> {
                            textBuffer.append(item)
                            extractAndQueueSentences()
                        }
                    }
                }
            } catch (_: Exception) {
                // channel closed
            }
        }
    }

    private fun startSynthLoop() {
        synthJob = scope.launch {
            try {
                for (sentence in sentenceQueue) {
                    if (stopped) break
                    router.synthesizeStream(settings, sentence)
                        .catch { error ->
                            Log.w(TAG, "TTS error", error)
                            onError?.invoke(error.message ?: "TTS 合成失败")
                        }
                        .collect { audioChunk ->
                            if (!stopped) audioQueue.send(audioChunk)
                        }
                }
            } catch (_: Exception) {
                // channel closed
            } finally {
                audioQueue.close()
            }
        }
    }

    private fun startPlaybackLoop() {
        playbackJob = scope.launch {
            val current = player ?: return@launch
            current.start()
            if (!current.isActive()) {
                onError?.invoke("无法打开扬声器")
                onPlaybackEnd?.invoke()
                return@launch
            }
            onPlaybackStart?.invoke()
            try {
                for (chunk in audioQueue) {
                    if (stopped) break
                    current.write(chunk)
                }
                if (!stopped) current.drain()
            } catch (_: Exception) {
                // channel closed
            }
            if (!stopped) {
                current.stop()
            }
            onPlaybackEnd?.invoke()
        }
    }

    private fun extractAndQueueSentences() {
        val text = textBuffer.toString()
        var lastEnd = 0
        for (match in SentenceBoundary.findAll(text)) {
            val end = match.range.last + 1
            val sentence = text.substring(lastEnd, end).trim()
            if (sentence.length >= MinSentenceLength) {
                queueSentence(sentence)
                lastEnd = end
            }
        }
        if (lastEnd > 0) {
            textBuffer = StringBuilder(text.substring(lastEnd))
        }
    }

    private fun queueSentence(sentence: String) {
        val index = sentenceIndex++
        Log.i(TAG, "queue sentence #$index: ${sentence.take(40)}")
        sentenceQueue.trySend(sentence)
    }

    private fun stopJobs(releasePlayer: Boolean) {
        extractJob?.cancel()
        synthJob?.cancel()
        playbackJob?.cancel()
        extractJob = null
        synthJob = null
        playbackJob = null
        if (releasePlayer) {
            player?.stop()
            player = null
        }
    }

    private object Flush
}
