package kira.ditto.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AsrEngineRouter(
    private val context: Context,
    private val loadProviderConfigs: () -> List<LlmProviderConfig>,
) {
    suspend fun snapshot(): AsrCapabilitySnapshot = AsrCapabilityProbe.probe(context)

    fun snapshotBlocking(): AsrCapabilitySnapshot = AsrCapabilityProbe.probeBlocking(context)

    fun resolvedKind(
        settings: AppSettings,
        snapshot: AsrCapabilitySnapshot = snapshotBlocking(),
    ): AsrEngineKind? {
        val key = settings.defaultAsrModelKey.trim()
        val catalog = loadProviderConfigs()
            .availableModelOptions(includeDisabledModels = true)
        val catalogHasModel = catalog.any { it.key == key }
        return resolveAsrEngineKind(key, snapshot, catalogHasModel)
    }

    fun isComposerReady(settings: AppSettings): Boolean = resolvedKind(settings) != null

    fun catalogOption(settings: AppSettings): ProviderModelOption? {
        val key = settings.defaultAsrModelKey.trim()
        if (key.isBlank() || AsrDeviceModelKeys.isDeviceKey(key)) return null
        return loadProviderConfigs()
            .availableModelOptions(includeDisabledModels = true)
            .findModelOption(key)
    }

    suspend fun transcribePcmChunk(
        settings: AppSettings,
        pcm: ByteArray,
        language: String?,
        playbackPfd: ParcelFileDescriptor? = null,
    ): String? = withContext(Dispatchers.IO) {
        if (pcm.isEmpty()) return@withContext null
        val snapshot = snapshot()
        val kind = resolvedKind(settings, snapshot) ?: return@withContext null
        when (kind) {
            AsrEngineKind.MlKit -> {
                playbackPfd?.let { GoogleMlKitAsrEngine.transcribePfd(context, it) }
                    ?: HmsFileAsrEngine.transcribeWavFile(
                        context,
                        AsrWav.pcm16leMonoToWav(pcm),
                        language,
                    )
            }
            AsrEngineKind.Hms -> HmsFileAsrEngine.transcribeWavFile(
                context,
                AsrWav.pcm16leMonoToWav(pcm),
                language,
            )
            AsrEngineKind.Cloud -> catalogOption(settings)?.let { option ->
                CloudAsrEngine.transcribe(option, pcm, language).trim().takeIf { it.isNotEmpty() }
            }
            AsrEngineKind.System -> HmsFileAsrEngine.transcribeWavFile(
                context,
                AsrWav.pcm16leMonoToWav(pcm),
                language,
            ) ?: catalogOption(settings)?.let { CloudAsrEngine.transcribe(it, pcm, language).trim().takeIf { it.isNotEmpty() } }
        }
    }
}

internal fun postOnMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        block()
    } else {
        Handler(Looper.getMainLooper()).post(block)
    }
}
