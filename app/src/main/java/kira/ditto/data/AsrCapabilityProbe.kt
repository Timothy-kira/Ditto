package kira.ditto.data

import android.content.Context
import android.os.Build
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AsrCapabilityProbe {
    suspend fun probe(context: Context): AsrCapabilitySnapshot = withContext(Dispatchers.Default) {
        AsrCapabilitySnapshot(
            hms = probeHms(context),
            mlKit = probeGoogleMlKit(context),
            system = probeSystem(context),
        )
    }

    fun probeBlocking(context: Context): AsrCapabilitySnapshot = AsrCapabilitySnapshot(
        hms = probeHms(context),
        mlKit = probeGoogleMlKit(context),
        system = probeSystem(context),
    )

    private fun probeHms(context: Context): AsrAvailability {
        val recognizer = runCatching {
            Class.forName("com.huawei.hms.mlsdk.asr.MLAsrRecognizer")
        }.isSuccess
        if (!recognizer) return AsrAvailability.Unavailable
        val hmsCore = runCatching {
            val info = context.packageManager.getPackageInfo("com.huawei.hwid", 0)
            info != null
        }.getOrDefault(false)
        return if (hmsCore) AsrAvailability.Available else AsrAvailability.Downloadable
    }

    private fun probeGoogleMlKit(context: Context): AsrAvailability {
        if (Build.VERSION.SDK_INT < 31) return AsrAvailability.Unavailable
        val present = runCatching {
            Class.forName("com.google.mlkit.genai.speechrecognition.SpeechRecognizer")
        }.isSuccess
        if (!present) return AsrAvailability.Unavailable
        return GoogleMlKitAsrEngine.probe(context)
    }

    private fun probeSystem(context: Context): AsrAvailability {
        val available = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)
        val onDevice = if (Build.VERSION.SDK_INT >= 31) {
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
        } else {
            false
        }
        return if (available || onDevice) AsrAvailability.Available else AsrAvailability.Unavailable
    }
}
