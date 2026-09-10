package kira.ditto.data

import android.content.Context
import android.os.ParcelFileDescriptor
import kira.ditto.agentmode.IAetherAgentModeService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class AgentModeListenSession(
    @Suppress("unused") private val context: Context,
    private val router: AsrEngineRouter,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val active = AtomicBoolean(false)
    private val listenId = AtomicReference("")
    private val startedAt = AtomicLong(0L)
    private val durationSec = AtomicInteger(0)
    private val language = AtomicReference<String?>(null)
    private val transcripts = mutableListOf<String>()
    private val currentPartial = AtomicReference("")
    private val pcmBuffer = ByteArrayOutputStream()
    private var readerJob: Job? = null
    private var capturePfd: ParcelFileDescriptor? = null
    private var settingsRef: AppSettings? = null
    private var streamSession: StepAudioStreamSession? = null
    private var publishJob: Job? = null
    var onTranscriptChanged: ((String) -> Unit)? = null

    val isActive: Boolean get() = active.get()

    suspend fun start(
        settings: AppSettings,
        service: IAetherAgentModeService,
        requestedDurationSec: Int,
        languageTag: String?,
    ): JSONObject = mutex.withLock {
        stopLocked(service)
        val pfd = runCatching {
            service.startInternalAudioCapture(AsrChunkAssembler.SampleRateHz)
        }.getOrElse { throwable ->
            return unavailable(throwable)
        } ?: return unavailable(null)
        capturePfd = pfd
        settingsRef = settings
        language.set(languageTag?.trim()?.takeIf { it.isNotEmpty() } ?: "zh")
        durationSec.set(requestedDurationSec.coerceIn(0, 24 * 60 * 60))
        listenId.set(UUID.randomUUID().toString())
        startedAt.set(System.currentTimeMillis())
        transcripts.clear()
        currentPartial.set("")
        pcmBuffer.reset()
        streamSession = openStreamIfNeeded(settings)
        active.set(true)
        publishTranscript()
        publishJob?.cancel()
        publishJob = scope.launch {
            while (isActive && active.get()) {
                publishTranscript()
                delay(400)
            }
        }
        readerJob = scope.launch(Dispatchers.IO) { readLoop(pfd, settings) }
        JSONObject()
            .put("ok", true)
            .put("listening", true)
            .put("listen_id", listenId.get())
            .put("duration_sec", durationSec.get())
            .put("engine", router.resolvedKind(settings)?.name.orEmpty())
            .put("stream", streamSession != null)
    }

    fun status(): JSONObject {
        val elapsed = elapsedSec()
        return JSONObject()
            .put("ok", true)
            .put("listening", active.get())
            .put("listen_id", listenId.get())
            .put("elapsed_sec", elapsed)
            .put("duration_sec", durationSec.get())
            .put("transcript", displayTranscript())
    }

    suspend fun stop(service: IAetherAgentModeService?): JSONObject = mutex.withLock {
        stopLocked(service)
    }

    private suspend fun stopLocked(service: IAetherAgentModeService?): JSONObject {
        active.set(false)
        publishJob?.cancel()
        publishJob = null
        readerJob?.cancel()
        readerJob = null
        runCatching { service?.stopInternalAudioCapture() }
        runCatching { capturePfd?.close() }
        capturePfd = null
        val leftover = synchronized(pcmBuffer) { pcmBuffer.toByteArray() }.also { pcmBuffer.reset() }
        val settings = settingsRef
        val live = streamSession
        streamSession = null
        if (live != null) {
            if (leftover.isNotEmpty()) live.appendPcm(leftover)
            val text = runCatching { live.finish() }.getOrDefault("")
            if (text.isNotBlank()) {
                synchronized(transcripts) {
                    transcripts.clear()
                    transcripts += text
                }
            }
        } else if (settings != null && leftover.isNotEmpty()) {
            val text = runCatching {
                router.transcribePcmChunk(settings, leftover, language.get())
            }.getOrNull()
            if (!text.isNullOrBlank()) synchronized(transcripts) { transcripts += text }
        }
        val elapsed = elapsedSec()
        val transcript = displayTranscript()
        publishTranscript()
        return JSONObject()
            .put("ok", true)
            .put("listening", false)
            .put("listen_id", listenId.get())
            .put("elapsed_sec", elapsed)
            .put("transcript", transcript)
    }

    private suspend fun openStreamIfNeeded(settings: AppSettings): StepAudioStreamSession? {
        if (router.resolvedKind(settings) != AsrEngineKind.Cloud) return null
        val option = router.catalogOption(settings) ?: return null
        if (!isStepAudioStreamModel(option.modelId)) return null
        return runCatching {
            StepAudioStreamClient().open(
                option = option,
                language = language.get() ?: "zh",
                onEvent = { event, display ->
                    event.errorMessage?.let { currentPartial.set("") }
                    currentPartial.set(display)
                    if (event.completedTranscript != null) {
                        synchronized(transcripts) {
                            transcripts.clear()
                            if (display.isNotBlank()) transcripts += display
                        }
                    }
                    publishTranscript()
                },
            )
        }.getOrNull()
    }

    private suspend fun readLoop(pfd: ParcelFileDescriptor, settings: AppSettings) {
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { stream ->
            val chunk = ByteArray(3_200)
            while (active.get()) {
                val read = stream.read(chunk)
                if (read <= 0) break
                val bytes = if (read == chunk.size) chunk.copyOf() else chunk.copyOf(read)
                val live = streamSession
                if (live != null) {
                    live.appendPcm(bytes)
                } else {
                    synchronized(pcmBuffer) { pcmBuffer.write(bytes) }
                    val buffered = synchronized(pcmBuffer) { pcmBuffer.size() }
                    if (AsrChunkAssembler.shouldFlush(buffered)) {
                        val pcm = synchronized(pcmBuffer) {
                            val captured = pcmBuffer.toByteArray()
                            pcmBuffer.reset()
                            captured
                        }
                        val text = runCatching {
                            router.transcribePcmChunk(settings, pcm, language.get())
                        }.getOrNull()
                        if (!text.isNullOrBlank()) {
                            synchronized(transcripts) { transcripts += text }
                            publishTranscript()
                        }
                    }
                }
                val limit = durationSec.get()
                if (limit > 0 && elapsedSec() >= limit) {
                    active.set(false)
                    break
                }
            }
        }
    }

    private fun displayTranscript(): String {
        val live = currentPartial.get()
        if (live.isNotBlank()) return live
        return AsrChunkAssembler.joinTranscripts(synchronized(transcripts) { transcripts.toList() })
    }

    private fun elapsedSec(): Int {
        val start = startedAt.get()
        if (start <= 0L) return 0
        return ((System.currentTimeMillis() - start) / 1_000L).toInt().coerceAtLeast(0)
    }

    private fun publishTranscript() {
        onTranscriptChanged?.invoke(displayTranscript())
    }

    private fun unavailable(throwable: Throwable?): JSONObject = JSONObject()
        .put("ok", false)
        .put("code", "ASR_CAPTURE_UNAVAILABLE")
        .put("visual_only", true)
        .put(
            "errmsg",
            "Internal playback capture is not available on this device. This is visual-only watching, not a task failure. Do not ask the user for screen recording.",
        )
        .put("detail", throwable?.message.orEmpty())
}
