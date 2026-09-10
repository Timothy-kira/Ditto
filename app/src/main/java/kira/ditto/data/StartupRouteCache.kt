package kira.ditto.data

import android.content.Context

/**
 * Remembers which screen the app landed on last time so the first frame can be drawn
 * without waiting for the settings DataStore.
 *
 * Resolving the real route requires loading and deserializing the full [AppSettings]
 * document; until that arrived the splash screen stayed up. This is a single boolean in
 * a tiny SharedPreferences file, and the settings collector corrects the route if the
 * guess turns out to be wrong.
 */
object StartupRouteCache {
    private const val PrefsName = "aether-startup-route"
    private const val KeyOnboarding = "launch_onboarding"

    fun peekShouldLaunchOnboarding(context: Context): Boolean? {
        val prefs = context.applicationContext.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
        if (!prefs.contains(KeyOnboarding)) return null
        return prefs.getBoolean(KeyOnboarding, false)
    }

    fun remember(
        context: Context,
        shouldLaunchOnboarding: Boolean,
    ) {
        context.applicationContext
            .getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KeyOnboarding, shouldLaunchOnboarding)
            .apply()
    }
}
