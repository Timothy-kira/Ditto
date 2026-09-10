package kira.ditto.data

import android.content.Context
import kira.ditto.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

internal data class SpotifyAuthSnapshot(
    val displayName: String = "",
    val appRemote: Boolean = false,
    val clientId: String = "",
) {
    val isConnected: Boolean get() = appRemote || displayName.isNotBlank()
}

internal object SpotifyAuth {
    const val StoreId = "spotify.oauth"
    private val connectLock = Any()

    fun snapshot(store: HostSecretStore): SpotifyAuthSnapshot {
        val json = store.getJson(StoreId) ?: JSONObject()
        return SpotifyAuthSnapshot(
            displayName = json.optString("display_name"),
            appRemote = json.optBoolean("app_remote") || SpotifyAppRemoteClient.isConnected(),
            clientId = json.optString("client_id").ifBlank { bundledClientId() },
        )
    }

    fun save(store: HostSecretStore, snapshot: SpotifyAuthSnapshot) {
        store.putJson(
            StoreId,
            JSONObject()
                .put("display_name", snapshot.displayName)
                .put("app_remote", snapshot.appRemote)
                .put("client_id", snapshot.clientId),
        )
    }

    fun disconnect(store: HostSecretStore) {
        SpotifyAppRemoteClient.disconnect()
        store.delete(StoreId)
    }

    fun bundledClientId(): String = BuildConfig.SPOTIFY_OAUTH_CLIENT_ID.trim()

    fun connectWithBrowser(
        context: Context,
        store: HostSecretStore,
        force: Boolean = false,
    ): SpotifyAuthSnapshot {
        synchronized(connectLock) {
            val already = snapshot(store)
            if (!force && already.appRemote && SpotifyAppRemoteClient.isConnected()) return already
            if (force) SpotifyAppRemoteClient.disconnect()
            SpotifyAppRemoteClient.ensureConnected(context, showAuth = true)
            val next = SpotifyAuthSnapshot(
                displayName = "Spotify",
                appRemote = true,
                clientId = bundledClientId(),
            )
            save(store, next)
            return next
        }
    }
}

internal fun spotifyStringArg(arguments: JSONObject, vararg keys: String): String {
    keys.forEach { key ->
        val value = arguments.optString(key).trim()
        if (value.isNotBlank()) return value
    }
    return ""
}

internal fun spotifyIntArg(arguments: JSONObject, default: Int, max: Int, vararg keys: String): Int {
    keys.forEach { key ->
        if (!arguments.has(key) || arguments.isNull(key)) return@forEach
        val value = arguments.optInt(key, default)
        return value.coerceIn(1, max)
    }
    return default.coerceIn(1, max)
}

internal fun spotifyNumberArg(
    arguments: JSONObject,
    default: Int,
    min: Int,
    max: Int,
    vararg keys: String,
): Int {
    keys.forEach { key ->
        if (!arguments.has(key) || arguments.isNull(key)) return@forEach
        return arguments.optInt(key, default).coerceIn(min, max)
    }
    return default.coerceIn(min, max)
}

internal fun spotifyBoolArg(arguments: JSONObject, default: Boolean, vararg keys: String): Boolean {
    keys.forEach { key ->
        if (arguments.has(key) && !arguments.isNull(key)) return arguments.optBoolean(key, default)
    }
    return default
}

internal fun spotifyStringList(arguments: JSONObject, vararg keys: String): List<String> {
    keys.forEach { key ->
        val array = arguments.optJSONArray(key)
        if (array != null) {
            return (0 until array.length()).mapNotNull { index ->
                array.optString(index).trim().takeIf { it.isNotBlank() }
            }
        }
        val raw = arguments.optString(key).trim()
        if (raw.startsWith("[")) {
            val parsed = runCatching { JSONArray(raw) }.getOrNull()
            if (parsed != null) {
                return (0 until parsed.length()).mapNotNull { index ->
                    parsed.optString(index).trim().takeIf { it.isNotBlank() }
                }
            }
        }
        if (raw.isNotBlank()) {
            return raw.split(',').map { it.trim() }.filter { it.isNotBlank() }
        }
    }
    return emptyList()
}
