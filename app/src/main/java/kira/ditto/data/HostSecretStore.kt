package kira.ditto.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

class HostSecretStore(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "host-secrets")
    private val lock = Any()

    fun put(id: String, plaintext: String) {
        val key = sanitize(id)
        require(key.isNotBlank()) { "invalid secret id" }
        synchronized(lock) {
            dir.mkdirs()
            val ivAndCipher = encrypt(plaintext.toByteArray(Charsets.UTF_8))
            val file = File(dir, "$key.bin")
            file.outputStream().use { stream ->
                stream.write(ivAndCipher)
                stream.flush()
                runCatching { stream.fd.sync() }
            }
        }
    }

    fun get(id: String): String? {
        val file = File(dir, "${sanitize(id)}.bin")
        return synchronized(lock) {
            if (!file.isFile) return@synchronized null
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@synchronized null
            if (bytes.size <= GcmIvBytes) return@synchronized null
            runCatching { String(decrypt(bytes), Charsets.UTF_8) }.getOrNull()
                ?.takeIf { it.isNotBlank() }
        }
    }

    fun has(id: String): Boolean = synchronized(lock) {
        File(dir, "${sanitize(id)}.bin").isFile
    }

    fun delete(id: String) {
        synchronized(lock) {
            File(dir, "${sanitize(id)}.bin").delete()
        }
    }

    fun putJson(id: String, value: JSONObject) = put(id, value.toString())

    fun getJson(id: String): JSONObject? =
        get(id)?.let { runCatching { JSONObject(it) }.getOrNull() }

    companion object {
        const val FoodLuckinToken = "food.luckin.token"
        const val FoodMcdToken = "food.mcd.token"
        const val FoodLuckinName = "food.luckin.name"
        const val FoodMcdName = "food.mcd.name"
        const val KaggleAccounts = "kaggle.accounts"
        private const val AndroidKeyStore = "AndroidKeyStore"
        private const val KeyAlias = "ditto.host.secrets.aes"
        private const val GcmIvBytes = 12
        private val IdPattern = Regex("^[a-zA-Z0-9._-]+$")

        fun sanitize(id: String): String = id.trim().takeIf { IdPattern.matches(it) }.orEmpty()

        fun redact(text: String): String {
            var value = text
            value = Regex("(?i)(bearer)\\s+\\S+").replace(value, "$1 ***")
            value = Regex("(?i)(\"(?:key|token|password|secret|authorization)\"\\s*:\\s*\")([^\"]*)(\")")
                .replace(value, "$1***$3")
            value = Regex("(?i)(kaggle[_-]?key|access[_-]?token)\\s*[:=]\\s*\\S+").replace(value, "$1=***")
            return value
        }
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(AndroidKeyStore).apply { load(null) }
        store.getKey(KeyAlias, null)?.let { return it as SecretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, AndroidKeyStore)
        generator.init(
            KeyGenParameterSpec.Builder(
                KeyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        require(iv.size == GcmIvBytes) { "unexpected GCM IV size ${iv.size}" }
        return iv + cipher.doFinal(plain)
    }

    private fun decrypt(packed: ByteArray): ByteArray {
        val iv = packed.copyOfRange(0, GcmIvBytes)
        val body = packed.copyOfRange(GcmIvBytes, packed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(body)
    }
}

data class KaggleAccount(
    val id: String,
    val username: String,
    val selected: Boolean,
) {
    fun toPublicJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("username", username)
        .put("selected", selected)
}

class KaggleAccountBook(private val store: HostSecretStore) {
    fun list(): List<KaggleAccount> {
        val root = store.getJson(HostSecretStore.KaggleAccounts) ?: return emptyList()
        val selected = root.optJSONArray("selectedIds") ?: JSONArray()
        val selectedIds = (0 until selected.length()).map { selected.optString(it) }.toSet()
        val accounts = root.optJSONArray("accounts") ?: JSONArray()
        return (0 until accounts.length()).mapNotNull { index ->
            val item = accounts.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id")
            val username = item.optString("username")
            if (id.isBlank() || username.isBlank()) return@mapNotNull null
            KaggleAccount(id = id, username = username, selected = id in selectedIds)
        }
    }

    fun activeId(): String = store.getJson(HostSecretStore.KaggleAccounts)?.optString("activeId").orEmpty()

    fun tokenFor(id: String): String? {
        val root = store.getJson(HostSecretStore.KaggleAccounts) ?: return null
        val accounts = root.optJSONArray("accounts") ?: return null
        for (index in 0 until accounts.length()) {
            val item = accounts.optJSONObject(index) ?: continue
            if (item.optString("id") == id) {
                return item.optString("token").trim().takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    fun upsert(username: String, token: String, makeActive: Boolean = true) {
        val name = username.trim()
        val secret = token.trim()
        require(name.isNotBlank() && secret.isNotBlank()) { "username and token required" }
        val root = store.getJson(HostSecretStore.KaggleAccounts) ?: JSONObject()
        val accounts = root.optJSONArray("accounts") ?: JSONArray()
        val existing = (0 until accounts.length()).firstOrNull { index ->
            accounts.optJSONObject(index)?.optString("username").equals(name, ignoreCase = true)
        }
        val id = if (existing != null) {
            accounts.getJSONObject(existing).optString("id").ifBlank { name.lowercase() }
        } else {
            name.lowercase().replace(Regex("[^a-z0-9._-]+"), "-")
        }
        val next = JSONObject().put("id", id).put("username", name).put("token", secret)
        if (existing != null) {
            accounts.put(existing, next)
        } else {
            accounts.put(next)
        }
        val selected = root.optJSONArray("selectedIds") ?: JSONArray()
        val selectedIds = (0 until selected.length()).map { selected.optString(it) }.toMutableSet()
        selectedIds += id
        root.put("accounts", accounts)
        root.put("selectedIds", JSONArray(selectedIds.toList()))
        if (makeActive || root.optString("activeId").isBlank()) {
            root.put("activeId", id)
        }
        store.putJson(HostSecretStore.KaggleAccounts, root)
    }

    fun setActive(id: String) {
        val root = store.getJson(HostSecretStore.KaggleAccounts) ?: return
        if (list().none { it.id == id }) return
        root.put("activeId", id)
        store.putJson(HostSecretStore.KaggleAccounts, root)
    }

    fun setSelected(ids: Collection<String>) {
        val root = store.getJson(HostSecretStore.KaggleAccounts) ?: return
        val allowed = list().map { it.id }.toSet()
        root.put("selectedIds", JSONArray(ids.filter { it in allowed }))
        store.putJson(HostSecretStore.KaggleAccounts, root)
    }

    fun toggleSelected(id: String) {
        val current = list().filter { it.selected }.map { it.id }.toMutableSet()
        if (id in current) current.remove(id) else current.add(id)
        setSelected(current)
    }

    fun remove(id: String) {
        val root = store.getJson(HostSecretStore.KaggleAccounts) ?: return
        val accounts = root.optJSONArray("accounts") ?: JSONArray()
        val next = JSONArray()
        for (index in 0 until accounts.length()) {
            val item = accounts.optJSONObject(index) ?: continue
            if (item.optString("id") != id) next.put(item)
        }
        val selected = (0 until (root.optJSONArray("selectedIds")?.length() ?: 0))
            .map { root.getJSONArray("selectedIds").optString(it) }
            .filter { it != id }
        root.put("accounts", next)
        root.put("selectedIds", JSONArray(selected))
        if (root.optString("activeId") == id) {
            root.put("activeId", next.optJSONObject(0)?.optString("id").orEmpty())
        }
        store.putJson(HostSecretStore.KaggleAccounts, root)
    }
}
