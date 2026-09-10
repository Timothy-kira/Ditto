package kira.ditto

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import kira.ditto.data.AppLanguage
import kira.ditto.data.appLanguageForTag
import kira.ditto.data.defaultAppLanguage

object AetherLocaleManager {
    fun apply(language: AppLanguage) {
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(language.languageTag),
        )
    }

    fun applyIfChanged(language: AppLanguage) {
        if (currentApplicationLanguage() == language) return
        apply(language)
    }

    fun currentApplicationLanguage(): AppLanguage? =
        AppCompatDelegate.getApplicationLocales().get(0)
            ?.toLanguageTag()
            ?.let(::appLanguageForTag)

    fun currentLanguage(): AppLanguage =
        currentApplicationLanguage() ?: defaultAppLanguage()
}
