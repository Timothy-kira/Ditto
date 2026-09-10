package kira.ditto.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Routes TTS requests to the appropriate engine based on the selected model.
 */
class TtsEngineRouter(
    private val loadProviderConfigs: () -> List<LlmProviderConfig>,
) {
    fun resolveOption(settings: AppSettings): ProviderModelOption? {
        val key = settings.defaultTtsModelKey.trim()
        if (key.isBlank()) return null
        return loadProviderConfigs()
            .availableModelOptions(includeDisabledModels = true)
            .findModelOption(key)
    }

    fun isReady(settings: AppSettings): Boolean = resolveOption(settings) != null

    fun synthesizeStream(
        settings: AppSettings,
        text: String,
    ): Flow<ByteArray> {
        val option = resolveOption(settings)
            ?: return flow { throw TtsException("未配置 TTS 模型") }
        val voice = settings.ttsVoiceId.ifBlank {
            VoiceProfileCatalog.defaultVoiceId(option.piProviderId, option.baseUrl)
        }
        val isStepFun = option.piProviderId == "stepfun" ||
            looksLikeStepFunHost(option.baseUrl)
        return when {
            isStepFun ->
                StepAudioTtsEngine.synthesizeStream(
                    option.copy(modelId = resolveStepAudioTtsModelId(option.modelId)),
                    text,
                    voice,
                )
            else ->
                OpenAiTtsEngine.synthesizeStream(option, text, voice)
        }
    }
}

class TtsException(message: String, val httpCode: Int = 0) : Exception(message)
