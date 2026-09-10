package kira.ditto.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kira.ditto.R
import kira.ditto.audio.OboeMicCapture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ComposerAsrSession(
    private val context: Context,
    private val router: AsrEngineRouter,
) {
    private val running = AtomicBoolean(false)
    private val mic = AtomicReference<MicSpeechSession?>(null)
    private val capture = AtomicReference<OboeMicCapture?>(null)
    private val stream = AtomicReference<StepAudioStreamSession?>(null)
    private val settingsRef = AtomicReference<AppSettings?>(null)
    private val languageRef = AtomicReference<String?>(null)
    private val errorSink = AtomicReference<((String) -> Unit)?>(null)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val epoch = AtomicInteger(0)
    private val streamOpenJob = AtomicReference<Job?>(null)
    private val streamingEnabled = AtomicBoolean(false)
    private val attachLock = Any()
    private val pendingPcm = ByteArrayOutputStream()
    private val chunkBuffer = ByteArrayOutputStream()
    private val chunkMutex = Mutex()
    private val liveTranscripts = mutableListOf<String>()
    private val lastDisplay = AtomicReference("")
    private val voiceGate = AtomicReference(ComposerAsrVoiceGate())
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.Main.immediate)

    val isRunning: Boolean get() = running.get()

    suspend fun start(
        settings: AppSettings,
        language: String? = null,
        onRms: (Float) -> Unit,
        onPartial: (String) -> Unit,
        onError: (String) -> Unit = {},
    ): Boolean = withContext(Dispatchers.Main.immediate) {
        if (!running.compareAndSet(false, true)) return@withContext false
        settingsRef.set(settings)
        languageRef.set(language)
        errorSink.set(onError)
        lastDisplay.set("")
        synchronized(liveTranscripts) { liveTranscripts.clear() }
        synchronized(pendingPcm) { pendingPcm.reset() }
        synchronized(chunkBuffer) { chunkBuffer.reset() }
        voiceGate.set(ComposerAsrVoiceGate())
        val kind = router.resolvedKind(settings)
        val option = router.catalogOption(settings)
        if (kind == AsrEngineKind.Cloud || option != null) {
            return@withContext startCloud(settings, language, option, onRms, onPartial, onError)
        }
        if (kind != null) {
            val micKind = if (kind == AsrEngineKind.MlKit) {
                AsrEngineKind.System
            } else {
                kind
            }
            val session = MicSpeechSession(
                context = context,
                engineKind = micKind,
                language = language,
                onRms = onRms,
                onPartial = onPartial,
            )
            mic.set(session)
            session.start()
            return@withContext true
        }
        startCloud(settings, language, option = null, onRms, onPartial, onError)
    }

    suspend fun stop(): String {
        val openJob = streamOpenJob.get()
        if (openJob != null && stream.get() == null) {
            Log.i("AetherAsr", "stop waiting for realtime handshake")
            withTimeoutOrNull(4_000L) { openJob.join() }
        }
        streamOpenJob.getAndSet(null)?.cancel()
        running.set(false)
        val live: StepAudioStreamSession?
        synchronized(attachLock) {
            live = stream.getAndSet(null)
        }
        epoch.incrementAndGet()
        val recorder = capture.getAndSet(null)
        val leftoverChunk = synchronized(chunkBuffer) {
            chunkBuffer.toByteArray().also { chunkBuffer.reset() }
        }
        val pcm = recorder?.stop() ?: ByteArray(0)
        val gate = voiceGate.get()
        val hadVoice = gate.hadSpeech()
        val pendingText = lastDisplay.get().trim()
        val keepGoing = hadVoice || pendingText.isNotBlank() || !gate.isDigitalSilence()
        Log.i(
            "AetherAsr",
            "stop pcm=${pcm.size} peakRaw=${gate.peakRaw()} rel=${gate.peak()} voicedMs=${gate.voicedMillis()} had=$hadVoice keep=$keepGoing live=${live != null} pending=${pendingText.length}",
        )
        if (live != null) {
            val settings = settingsRef.getAndSet(null)
            running.set(false)
            if (!keepGoing && pcm.size < 320) {
                live.close()
                Log.i("AetherAsr", "stop drop live silence pcm=${pcm.size}")
                return ""
            }
            val full = if (pcm.size >= ComposerAsrFullTranscribeMinBytes) {
                composerAsrAcceptTranscript(
                    transcribeRecorded(settings, pcm),
                    hadVoice = true,
                )
            } else {
                ""
            }
            if (full.isNotBlank()) {
                live.close()
                return full
            }
            val streamed = composerAsrAcceptTranscript(
                runCatching { live.finish() }.getOrDefault(""),
                hadVoice = true,
            )
            if (streamed.isNotBlank()) return streamed
            val displayed = composerAsrAcceptTranscript(pendingText, hadVoice = true)
            if (displayed.isNotBlank()) return displayed
            if (pcm.size < 320) return ""
            return composerAsrAcceptTranscript(
                transcribeRecorded(settings, pcm),
                hadVoice = true,
            )
        }
        if (recorder != null) {
            running.set(false)
            val settings = settingsRef.getAndSet(null)
            if (!keepGoing && pcm.size < 320) {
                Log.i("AetherAsr", "stop drop recorder silence pcm=${pcm.size}")
                return ""
            }
            val full = if (pcm.size >= ComposerAsrFullTranscribeMinBytes) {
                composerAsrAcceptTranscript(
                    transcribeRecorded(settings, pcm),
                    hadVoice = true,
                )
            } else {
                ""
            }
            if (full.isNotBlank()) return full
            val displayed = composerAsrAcceptTranscript(pendingText, hadVoice = true)
            if (displayed.isNotBlank()) {
                if (leftoverChunk.isNotEmpty() && settings != null && gate.relativeOf(leftoverChunk) >= ComposerAsrRelativeVoice) {
                    val extra = composerAsrAcceptTranscript(
                        transcribeRecorded(settings, leftoverChunk),
                        hadVoice = true,
                    )
                    if (extra.isNotBlank()) {
                        return AsrChunkAssembler.joinTranscripts(listOf(displayed, extra))
                    }
                }
                return displayed
            }
            if (pcm.size < 320) return ""
            Log.i("AetherAsr", "stop fallback transcribe pcm=${pcm.size} keep=$keepGoing")
            return composerAsrAcceptTranscript(
                transcribeRecorded(settings, pcm),
                hadVoice = true,
            )
        }
        val session = mic.getAndSet(null) ?: run {
            running.set(false)
            return ""
        }
        return try {
            session.stop()
        } finally {
            session.cancel()
            running.set(false)
        }
    }

    fun cancel() {
        epoch.incrementAndGet()
        streamOpenJob.getAndSet(null)?.cancel()
        supervisor.cancelChildren()
        stream.getAndSet(null)?.close()
        capture.getAndSet(null)?.stop()
        mic.getAndSet(null)?.cancel()
        running.set(false)
    }

    private suspend fun transcribeRecorded(settings: AppSettings?, pcm: ByteArray): String {
        if (settings == null) return ""
        return try {
            router.transcribePcmChunk(settings, pcm, languageRef.get()).orEmpty()
        } catch (error: Throwable) {
            notifyError(error.message)
            ""
        }
    }

    private fun startCloud(
        settings: AppSettings,
        language: String?,
        option: ProviderModelOption?,
        onRms: (Float) -> Unit,
        onPartial: (String) -> Unit,
        onError: (String) -> Unit,
    ): Boolean {
        val lang = language?.takeIf { it.isNotBlank() } ?: "zh"
        val wantStream = option != null && isStepAudioStreamModel(option.modelId)
        streamingEnabled.set(wantStream)
        val myEpoch = epoch.incrementAndGet()
        val recorder = OboeMicCapture(
            onRms = { onRms(voiceGate.get().lastRelative()) },
            onPcm = { pcm ->
                voiceGate.get().onPcm(pcm)
                val live = synchronized(attachLock) { stream.get() }
                if (live != null) {
                    live.appendPcm(pcm)
                    return@OboeMicCapture
                }
                if (streamingEnabled.get()) {
                    synchronized(pendingPcm) { pendingPcm.write(pcm) }
                    return@OboeMicCapture
                }
                enqueueLiveChunk(pcm, settings, lang, onPartial)
            },
            collectPcm = true,
        )
        if (!recorder.start()) {
            failStart(onError, context.getString(R.string.composer_asr_mic_failed))
            return false
        }
        capture.set(recorder)
        val streamOption = option.takeIf { wantStream } ?: return true
        streamOpenJob.getAndSet(null)?.cancel()
        streamOpenJob.set(
            scope.launch(Dispatchers.IO) {
                val session = try {
                    StepAudioStreamClient().open(
                        option = streamOption,
                        language = lang,
                        vadThreshold = 0.5,
                        useServerVad = false,
                        onEvent = { event, display ->
                            if (epoch.get() == myEpoch && running.get()) {
                                val allowLive = voiceGate.get().hadSpeech() ||
                                    (!composerAsrLooksLikeHallucination(display) && display.trim().length >= 2)
                                val accepted = composerAsrAcceptTranscript(
                                    display,
                                    hadVoice = allowLive,
                                )
                                if (accepted.isNotBlank()) {
                                    lastDisplay.set(accepted)
                                    mainHandler.post {
                                        if (running.get() && epoch.get() == myEpoch) onPartial(accepted)
                                    }
                                }
                                if (!event.errorMessage.isNullOrBlank() && lastDisplay.get().isBlank()) {
                                    Log.e("AetherAsr", "realtime event error: ${event.errorMessage}")
                                    streamingEnabled.set(false)
                                    synchronized(attachLock) {
                                        stream.getAndSet(null)?.close()
                                    }
                                }
                            }
                        },
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    Log.e("AetherAsr", "realtime stream open failed: ${error.message}")
                    null
                }
                if (session == null) {
                    Log.w("AetherAsr", "realtime stream did not open, fallback chunks running=${running.get()}")
                    streamingEnabled.set(false)
                    if (running.get()) drainPendingIntoChunks(settings, lang, onPartial)
                    return@launch
                }
                ensureActive()
                synchronized(attachLock) {
                    if (epoch.get() != myEpoch || !running.get() || !isActive) {
                        session.close()
                        return@launch
                    }
                    val buffered = synchronized(pendingPcm) {
                        pendingPcm.toByteArray().also { pendingPcm.reset() }
                    }
                    if (buffered.isNotEmpty()) session.appendPcm(buffered)
                    stream.set(session)
                    Log.i("AetherAsr", "realtime stream attached pending=${buffered.size}")
                }
            },
        )
        return true
    }

    private fun drainPendingIntoChunks(
        settings: AppSettings,
        language: String,
        onPartial: (String) -> Unit,
    ) {
        val buffered = synchronized(pendingPcm) {
            pendingPcm.toByteArray().also { pendingPcm.reset() }
        }
        if (buffered.isNotEmpty()) enqueueLiveChunk(buffered, settings, language, onPartial)
    }

    private fun enqueueLiveChunk(
        pcm: ByteArray,
        settings: AppSettings,
        language: String,
        onPartial: (String) -> Unit,
    ) {
        val toFlush = synchronized(chunkBuffer) {
            chunkBuffer.write(pcm)
            if (!AsrChunkAssembler.shouldFlush(chunkBuffer.size(), ComposerAsrLiveChunkMs)) {
                return
            }
            chunkBuffer.toByteArray().also { chunkBuffer.reset() }
        }
        if (voiceGate.get().relativeOf(toFlush) < ComposerAsrRelativeVoice) return
        scope.launch(Dispatchers.IO) {
            chunkMutex.withLock {
                if (!running.get()) return@withLock
                val text = runCatching {
                    router.transcribePcmChunk(settings, toFlush, language)
                }.getOrNull().orEmpty()
                val accepted = composerAsrAcceptTranscript(
                    text,
                    hadVoice = voiceGate.get().hadSpeech() || text.trim().length >= 2,
                )
                if (accepted.isBlank() || !running.get()) return@withLock
                val display = synchronized(liveTranscripts) {
                    liveTranscripts += accepted
                    AsrChunkAssembler.joinTranscripts(liveTranscripts)
                }
                lastDisplay.set(display)
                mainHandler.post {
                    if (running.get()) onPartial(display)
                }
            }
        }
    }

    private fun failStart(onError: (String) -> Unit, message: String) {
        running.set(false)
        settingsRef.set(null)
        notifyError(onError, message)
    }

    private fun notifyError(message: String?) {
        notifyError(errorSink.get(), message)
    }

    private fun notifyError(onError: ((String) -> Unit)?, message: String?) {
        val text = message?.trim().orEmpty()
        if (text.isEmpty()) return
        val sink = onError ?: return
        postOnMain { sink(text) }
    }
}
