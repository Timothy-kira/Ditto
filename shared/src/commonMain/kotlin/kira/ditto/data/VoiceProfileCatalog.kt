package kira.ditto.data

/**
 * Catalog of available TTS voice profiles, grouped by provider.
 */
data class VoiceProfile(
    val id: String,
    val name: String,
    val description: String,
    val language: String,
    val gender: VoiceGender = VoiceGender.Unknown,
)

enum class VoiceGender { Male, Female, Neutral, Unknown }

object VoiceProfileCatalog {

    private val stepFunVoices = listOf(
        VoiceProfile("zixinnansheng", "自信男声", "真诚亲和，有活力", "zh", VoiceGender.Male),
        VoiceProfile("elegantgentle-female", "气质温婉", "真诚温柔，亲和力强", "zh", VoiceGender.Female),
        VoiceProfile("livelybreezy-female", "活力轻快", "有感染力、说服力和亲和力", "zh", VoiceGender.Female),
        VoiceProfile("cixingnansheng", "磁性男声", "沉稳有力", "zh", VoiceGender.Male),
        VoiceProfile("lively-girl", "Lively Girl", "英文女声，亲和有活力", "en", VoiceGender.Female),
        VoiceProfile("magnetic-voiced-male", "Magnetic Male", "英文男声，沉稳厚重", "en", VoiceGender.Male),
        VoiceProfile("soft-spoken-gentleman", "Soft Gentleman", "英文男声，沉稳温柔", "en", VoiceGender.Male),
        VoiceProfile("vibrant-youth", "Vibrant Youth", "英文男声，温柔亲和", "en", VoiceGender.Male),
    )

    private val openAiVoices = listOf(
        VoiceProfile("alloy", "Alloy", "Neutral and balanced", "en", VoiceGender.Neutral),
        VoiceProfile("echo", "Echo", "Warm and conversational", "en", VoiceGender.Male),
        VoiceProfile("fable", "Fable", "Expressive and dynamic", "en", VoiceGender.Neutral),
        VoiceProfile("onyx", "Onyx", "Deep and authoritative", "en", VoiceGender.Male),
        VoiceProfile("nova", "Nova", "Friendly and upbeat", "en", VoiceGender.Female),
        VoiceProfile("shimmer", "Shimmer", "Clear and pleasant", "en", VoiceGender.Female),
    )

    fun voicesForProvider(piProviderId: String, baseUrl: String): List<VoiceProfile> {
        val isStepFun = piProviderId == "stepfun" ||
            baseUrl.contains("stepfun.com", ignoreCase = true) ||
            baseUrl.contains("stepfun.ai", ignoreCase = true)
        return if (isStepFun) stepFunVoices else openAiVoices
    }

    fun defaultVoiceId(piProviderId: String, baseUrl: String): String {
        val voices = voicesForProvider(piProviderId, baseUrl)
        return voices.firstOrNull()?.id ?: "alloy"
    }

    fun findVoice(voiceId: String): VoiceProfile? =
        (stepFunVoices + openAiVoices).firstOrNull { it.id == voiceId }
}
