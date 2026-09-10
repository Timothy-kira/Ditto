package kira.ditto.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import kira.ditto.browser.BrowserActivity

internal data class TokenPasteSnapshot(val token: String = "") {
    val isConnected: Boolean get() = token.isNotBlank()
}

internal object GithubAuth {
    const val StoreId = "github.token"
    const val ConsoleUrl = "https://github.com/settings/personal-access-tokens"

    fun snapshot(store: HostSecretStore): TokenPasteSnapshot =
        TokenPasteSnapshot(token = store.get(StoreId).orEmpty().trim())

    fun save(store: HostSecretStore, token: String): TokenPasteSnapshot =
        saveToken(store, StoreId, token)

    fun disconnect(store: HostSecretStore) {
        store.delete(StoreId)
    }

    fun openConsole(context: Context) = openTokenConsole(context, ConsoleUrl)
}

internal object HuggingFaceAuth {
    const val StoreId = "huggingface.token"
    const val ConsoleUrl = "https://huggingface.co/settings/tokens"

    fun snapshot(store: HostSecretStore): TokenPasteSnapshot =
        TokenPasteSnapshot(token = store.get(StoreId).orEmpty().trim())

    fun save(store: HostSecretStore, token: String): TokenPasteSnapshot =
        saveToken(store, StoreId, token)

    fun disconnect(store: HostSecretStore) {
        store.delete(StoreId)
    }

    fun openConsole(context: Context) = openTokenConsole(context, ConsoleUrl)
}

internal fun saveToken(store: HostSecretStore, storeId: String, token: String): TokenPasteSnapshot {
    val trimmed = normalizeAccessToken(token)
    if (trimmed.isBlank()) {
        store.delete(storeId)
        return TokenPasteSnapshot()
    }
    store.put(storeId, trimmed)
    val saved = store.get(storeId).orEmpty().trim()
    if (saved != trimmed) error("Token was not persisted")
    return TokenPasteSnapshot(saved)
}

internal fun normalizeAccessToken(raw: String): String {
    var value = raw.trim().trim('\uFEFF')
    if (value.length >= 2) {
        val quote = value.first()
        if ((quote == '"' || quote == '\'') && value.last() == quote) {
            value = value.substring(1, value.length - 1).trim()
        }
    }
    if ('\n' in value || '\r' in value) {
        value = value.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty()
    }
    val labeled = Regex(
        "(?i)(?:token|pat|secret|密钥)\\s*[:：]\\s*(.+)",
    ).find(value)
    if (labeled != null) {
        value = labeled.groupValues[1].trim()
    }
    return value.replace(Regex("\\s+"), "")
}

internal fun openTokenConsole(context: Context, url: String) {
    val app = context.applicationContext
    val inApp = Intent(app, BrowserActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        putExtra(BrowserActivity.ExtraUrl, url)
    }
    val external = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { app.startActivity(inApp) }.getOrElse {
        app.startActivity(external)
    }
}
