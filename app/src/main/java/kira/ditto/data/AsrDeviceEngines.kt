package kira.ditto.data

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal object GoogleMlKitAsrEngine {
    fun probe(context: Context): AsrAvailability {
        return runCatching {
            val recognizerClass = Class.forName("com.google.mlkit.genai.speechrecognition.SpeechRecognizer")
            val getClient = recognizerClass.methods.firstOrNull { method ->
                method.name == "getClient" && method.parameterTypes.size == 1
            } ?: return AsrAvailability.Unavailable
            val client = getClient.invoke(null, context) ?: return AsrAvailability.Unavailable
            val checkStatus = client.javaClass.methods.firstOrNull { it.name == "checkStatus" }
                ?: return AsrAvailability.Downloadable
            val task = checkStatus.invoke(client) ?: return AsrAvailability.Downloadable
            val status = awaitTask(task, 2_500L) ?: return AsrAvailability.Downloadable
            when (status.toString().uppercase()) {
                "AVAILABLE" -> AsrAvailability.Available
                "DOWNLOADABLE" -> AsrAvailability.Downloadable
                else -> AsrAvailability.Unavailable
            }
        }.getOrDefault(AsrAvailability.Unavailable)
    }

    suspend fun transcribePfd(context: Context, pfd: ParcelFileDescriptor): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val audioSourceClass = Class.forName("com.google.mlkit.genai.common.audio.AudioSource")
                val fromPfd = audioSourceClass.getMethod("fromPfd", ParcelFileDescriptor::class.java)
                val audioSource = fromPfd.invoke(null, pfd)
                val requestClass = Class.forName(
                    "com.google.mlkit.genai.speechrecognition.SpeechRecognizerRequest",
                )
                val builder = requestClass.getMethod("builder").invoke(null)
                builder.javaClass.methods.first { it.name == "setAudioSource" }.invoke(builder, audioSource)
                val request = builder.javaClass.methods.first { it.name == "build" }.invoke(builder)
                val recognizerClass = Class.forName("com.google.mlkit.genai.speechrecognition.SpeechRecognizer")
                val client = recognizerClass.methods.first {
                    it.name == "getClient" && it.parameterTypes.size == 1
                }.invoke(null, context)
                val run = client.javaClass.methods.first { method ->
                    method.name == "runInference" || method.name == "recognize" || method.name == "startRecognition"
                }
                val task = run.invoke(client, request)
                val result = awaitTask(task, 90_000L) ?: return@runCatching null
                extractText(result)
            }.getOrNull()?.takeIf { it.isNotBlank() }
        }

    private fun awaitTask(task: Any, timeoutMs: Long): Any? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Any?>(null)
        runCatching {
            val add = task.javaClass.methods.first { it.name == "addOnCompleteListener" }
            val listenerClass = Class.forName("com.google.android.gms.tasks.OnCompleteListener")
            val listener = java.lang.reflect.Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass),
            ) { _, method, args ->
                if (method.name == "onComplete") {
                    val completed = args?.firstOrNull()
                    val isSuccessful = completed?.javaClass?.methods
                        ?.firstOrNull { it.name == "isSuccessful" }
                        ?.invoke(completed) as? Boolean
                    if (isSuccessful == true) {
                        result.set(
                            completed.javaClass.methods.first { it.name == "getResult" }.invoke(completed),
                        )
                    }
                    latch.countDown()
                }
                null
            }
            add.invoke(task, listener)
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return result.get()
    }

    private fun extractText(result: Any?): String {
        if (result == null) return ""
        val textMethods = listOf("getTranscription", "getText", "transcription", "text")
        for (name in textMethods) {
            val value = runCatching {
                result.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
                    ?.invoke(result)
            }.getOrNull()
            if (value is String && value.isNotBlank()) return value
        }
        return result.toString()
    }
}

internal object HmsFileAsrEngine {
    fun transcribeWavFile(context: Context, wavBytes: ByteArray, language: String?): String? {
        val cache = context.cacheDir.resolve("asr-chunk-${System.nanoTime()}.wav")
        return try {
            cache.writeBytes(wavBytes)
            transcribeUri(context, android.net.Uri.fromFile(cache), language)
        } finally {
            cache.delete()
        }
    }

    private fun transcribeUri(context: Context, uri: android.net.Uri, language: String?): String? {
        return runCatching {
            val engineClass = Class.forName("com.huawei.hms.mlsdk.aft.cloud.MLRemoteAftEngine")
            val engine = engineClass.getMethod("getInstance").invoke(null)
            engineClass.methods.firstOrNull { it.name == "init" }?.invoke(engine, context)
            val result = AtomicReference("")
            val latch = CountDownLatch(1)
            val listenerClass = Class.forName("com.huawei.hms.mlsdk.aft.cloud.MLRemoteAftListener")
            val listener = java.lang.reflect.Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass),
            ) { _, method, args ->
                when (method.name) {
                    "onResult" -> {
                        val payload = args?.getOrNull(1)
                        val text = payload?.javaClass?.methods
                            ?.firstOrNull { it.name == "getText" && it.parameterCount == 0 }
                            ?.invoke(payload) as? String
                        if (!text.isNullOrBlank()) result.set(text)
                        latch.countDown()
                    }
                    "onError", "onInitComplete" -> Unit
                    else -> Unit
                }
                null
            }
            engineClass.methods.firstOrNull { it.name == "setAftListener" }?.invoke(engine, listener)
            val settingClass = Class.forName("com.huawei.hms.mlsdk.aft.cloud.MLRemoteAftSetting")
            val settingBuilder = settingClass.getDeclaredClasses().firstOrNull { it.simpleName == "Factory" }
                ?.getConstructor()?.newInstance()
            val setting = settingBuilder?.javaClass?.methods?.firstOrNull { it.name == "create" }?.invoke(settingBuilder)
            val shortRecognize = engineClass.methods.first { it.name == "shortRecognize" }
            if (setting != null && shortRecognize.parameterTypes.size >= 2) {
                shortRecognize.invoke(engine, uri, setting)
            } else {
                engineClass.methods.first { it.name == "shortRecognize" }.invoke(engine, uri)
            }
            latch.await(70, TimeUnit.SECONDS)
            result.get().trim().takeIf { it.isNotEmpty() }
        }.getOrNull()
    }
}

internal class MicSpeechSession(
    private val context: Context,
    private val engineKind: AsrEngineKind,
    private val language: String?,
    private val onRms: (Float) -> Unit,
    private val onPartial: (String) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var systemRecognizer: SpeechRecognizer? = null
    private var hmsRecognizer: Any? = null
    private val finished = CompletableDeferred<String>()
    private val fellBackToSystem = AtomicBoolean(false)

    fun start() {
        mainHandler.post {
            when (engineKind) {
                AsrEngineKind.Hms -> startHms()
                AsrEngineKind.System, AsrEngineKind.MlKit, AsrEngineKind.Cloud -> startSystem()
            }
        }
    }

    suspend fun stop(): String {
        mainHandler.post {
            runCatching { systemRecognizer?.stopListening() }
            runCatching {
                hmsRecognizer?.javaClass?.methods
                    ?.firstOrNull { it.name == "stopRecognizing" }
                    ?.invoke(hmsRecognizer)
            }
        }
        return withTimeoutOrNull(8_000L) { finished.await() }.orEmpty()
    }

    fun cancel() {
        mainHandler.post {
            runCatching { systemRecognizer?.cancel() }
            runCatching { systemRecognizer?.destroy() }
            runCatching {
                hmsRecognizer?.javaClass?.methods?.firstOrNull { it.name == "destroy" }?.invoke(hmsRecognizer)
            }
            systemRecognizer = null
            hmsRecognizer = null
            if (!finished.isCompleted) finished.complete("")
        }
    }

    private fun startSystem() {
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        systemRecognizer = recognizer
        var latest = ""
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) {
                emitRms(speechRmsToLevel(rmsdB))
            }
            override fun onBufferReceived(buffer: ByteArray?) {
                if (buffer != null && buffer.size >= 4) emitRms(pcm16LeRms(buffer))
            }
            override fun onEndOfSpeech() = Unit
            override fun onError(error: Int) {
                if (!finished.isCompleted) finished.complete(latest)
            }
            override fun onResults(results: Bundle?) {
                latest = results.bestTranscript().ifBlank { latest }
                if (!finished.isCompleted) finished.complete(latest)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                latest = partialResults.bestTranscript().ifBlank { latest }
                if (latest.isNotBlank()) emitPartial(latest)
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, asrSpeechLanguageTag(language))
        }
        recognizer.startListening(intent)
    }

    private fun startHms() {
        val created = runCatching {
            val clazz = Class.forName("com.huawei.hms.mlsdk.asr.MLAsrRecognizer")
            clazz.getMethod("createAsrRecognizer", Context::class.java).invoke(null, context)
        }.getOrNull()
        if (created == null) {
            startSystem()
            return
        }
        hmsRecognizer = created
        var latest = ""
        val listenerClass = Class.forName("com.huawei.hms.mlsdk.asr.MLAsrListener")
        val listener = java.lang.reflect.Proxy.newProxyInstance(
            listenerClass.classLoader,
            arrayOf(listenerClass),
        ) { _, method, args ->
            when (method.name) {
                "onStartingOfSpeech", "onStartListening" -> Unit
                "onVoiceDataReceived" -> emitRms(hmsVoiceLevel(args))
                "onRmsChanged" -> {
                    val rms = (args?.firstOrNull() as? Number)?.toFloat()
                    if (rms != null) emitRms(speechRmsToLevel(rms))
                }
                "onRecognizingResults" -> {
                    val text = (args?.firstOrNull() as? Bundle).bestHmsText()
                    if (text.isNotBlank()) {
                        latest = text
                        emitPartial(text)
                    }
                }
                "onResults" -> {
                    latest = (args?.firstOrNull() as? Bundle).bestHmsText().ifBlank { latest }
                    if (!finished.isCompleted) finished.complete(latest)
                }
                "onError" -> {
                    val text = latest
                    mainHandler.post { onHmsError(text) }
                }
                else -> Unit
            }
            null
        }
        created.javaClass.methods.first { it.name == "setAsrListener" }.invoke(created, listener)
        val intent = Intent("com.huawei.hms.mlsdk.asr.ACTION_HMS_ASR_SPEECH").apply {
            putExtra("LANGUAGE", asrSpeechLanguageTag(language))
            putExtra("FEATURE", HmsAsrFeatureWordFlux)
        }
        created.javaClass.methods.first { it.name == "startRecognizing" }.invoke(created, intent)
    }

    private fun onHmsError(latest: String) {
        if (fellBackToSystem.get() || latest.isNotBlank()) {
            if (!finished.isCompleted) finished.complete(latest)
            return
        }
        if (!fellBackToSystem.compareAndSet(false, true)) {
            if (!finished.isCompleted) finished.complete(latest)
            return
        }
        runCatching {
            hmsRecognizer?.javaClass?.methods?.firstOrNull { it.name == "destroy" }?.invoke(hmsRecognizer)
        }
        hmsRecognizer = null
        startSystem()
    }

    private fun Bundle?.bestTranscript(): String {
        val list = this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        return list.firstOrNull().orEmpty()
    }

    private fun emitRms(level: Float) {
        val normalized = level.coerceIn(0.06f, 1f)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onRms(normalized)
        } else {
            mainHandler.post { onRms(normalized) }
        }
    }

    private fun emitPartial(text: String) {
        if (text.isBlank()) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onPartial(text)
        } else {
            mainHandler.post { onPartial(text) }
        }
    }

    private fun Bundle?.bestHmsText(): String {
        if (this == null) return ""
        val preferred = hmsAsrTextFromKeys { key -> getString(key) }
        if (preferred.isNotBlank()) return preferred
        return keySet().firstNotNullOfOrNull { key ->
            getString(key)?.takeIf { it.isNotBlank() }
        }.orEmpty()
    }
}

internal const val HmsAsrFeatureWordFlux = 11

internal fun hmsAsrTextFromKeys(lookup: (String) -> String?): String {
    val keys = listOf(
        "results_recognizing",
        "results_recognized",
        "RESULTS_RECOGNIZING",
        "RESULTS_RECOGNIZED",
        "RESULTS",
        "results",
    )
    for (key in keys) {
        val value = lookup(key)?.trim().orEmpty()
        if (value.isNotBlank()) return value
    }
    return ""
}

internal fun speechRmsToLevel(rmsdB: Float): Float {
    if (rmsdB > 20f) return (rmsdB / 100f).coerceIn(0.06f, 1f)
    return ((rmsdB + 2f) / 12f).coerceIn(0.06f, 1f)
}

internal fun pcm16LeRms(buffer: ByteArray): Float =
    pcm16LeRmsRaw(buffer).coerceIn(0.06f, 1f)

internal fun hmsVoiceLevel(args: Array<out Any>?): Float {
    val data = args?.getOrNull(0) as? ByteArray
    if (data != null && data.size >= 4) return pcm16LeRms(data)
    val energy = args?.getOrNull(1) as? Number
    if (energy != null) {
        val value = energy.toFloat()
        return if (value > 1f) (value / 100f).coerceIn(0.06f, 1f) else value.coerceIn(0.06f, 1f)
    }
    return 0.08f
}
