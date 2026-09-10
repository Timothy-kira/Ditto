package kira.ditto.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import kira.ditto.browser.BrowserActivity

internal data class AmapAuthSnapshot(
    val key: String = "",
) {
    val isConnected: Boolean get() = key.isNotBlank()
}

internal object AmapAuth {
    const val StoreId = "amap.key"
    const val KeyConsoleUrl = "https://console.amap.com/dev/key/app"
    const val KeyHelpUrl = "https://lbs.amap.com/api/webservice/create-project-and-key"

    fun snapshot(store: HostSecretStore): AmapAuthSnapshot =
        AmapAuthSnapshot(key = store.get(StoreId).orEmpty().trim())

    fun normalizeKey(raw: String): String {
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
        val labeled = Regex("(?i)(?:api[_-]?key|key|密钥)\\s*[:：]\\s*(.+)").find(value)
        if (labeled != null) {
            value = labeled.groupValues[1].trim()
        }
        return value.replace(Regex("\\s+"), "")
    }

    fun save(store: HostSecretStore, key: String): AmapAuthSnapshot {
        val trimmed = normalizeKey(key)
        if (trimmed.isBlank()) {
            disconnect(store)
            return snapshot(store)
        }
        store.put(StoreId, trimmed)
        val saved = snapshot(store)
        if (saved.key != trimmed) {
            error("Amap Key was not persisted")
        }
        return saved
    }

    fun disconnect(store: HostSecretStore) {
        store.delete(StoreId)
    }

    fun openKeyConsole(context: Context) {
        val app = context.applicationContext
        val inApp = Intent(app, BrowserActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(BrowserActivity.ExtraUrl, KeyConsoleUrl)
        }
        val external = Intent(Intent.ACTION_VIEW, Uri.parse(KeyConsoleUrl))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(inApp) }.getOrElse {
            app.startActivity(external)
        }
    }
}
