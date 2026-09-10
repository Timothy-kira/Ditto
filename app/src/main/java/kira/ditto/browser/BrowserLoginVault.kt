package kira.ditto.browser

import android.content.Context
import kira.ditto.data.HostSecretStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.UUID

data class SavedBrowserLogin(
    val id: String,
    val origin: String,
    val username: String,
    val password: String,
)

internal interface BrowserLoginBlobStore {
    fun read(): String
    fun write(raw: String)
}

class BrowserLoginVault internal constructor(private val blob: BrowserLoginBlobStore) {
    constructor(context: Context) : this(EncryptedBrowserLoginBlobStore(context))

    private val lock = Any()

    fun list(): List<SavedBrowserLogin> = synchronized(lock) {
        parse(blob.read())
    }

    fun find(origin: String): List<SavedBrowserLogin> {
        val host = originHost(origin)
        return list().filter { originHost(it.origin) == host }
    }

    fun findById(id: String): SavedBrowserLogin? = list().firstOrNull { it.id == id }

    fun pick(origin: String, username: String = ""): SavedBrowserLogin? {
        val matches = find(origin)
        if (matches.isEmpty()) return null
        val wanted = username.trim()
        if (wanted.isNotBlank()) {
            return matches.firstOrNull { it.username.equals(wanted, ignoreCase = true) }
                ?: matches.firstOrNull { it.username.contains(wanted, ignoreCase = true) }
        }
        return matches.maxByOrNull { it.username.length }
    }

    fun save(origin: String, username: String, password: String): SavedBrowserLogin = synchronized(lock) {
        val items = parse(blob.read()).toMutableList()
        val host = originHost(origin)
        val existing = items.indexOfFirst {
            originHost(it.origin) == host && it.username == username
        }
        val login = SavedBrowserLogin(
            id = if (existing >= 0) items[existing].id else UUID.randomUUID().toString(),
            origin = origin.ifBlank { host },
            username = username,
            password = password,
        )
        if (existing >= 0) items[existing] = login else items.add(login)
        write(items)
        login
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        val items = parse(blob.read())
        val next = items.filterNot { it.id == id }
        if (next.size == items.size) return false
        write(next)
        true
    }

    fun clear() = synchronized(lock) {
        write(emptyList())
    }

    fun toPublicJson(): JSONArray {
        val array = JSONArray()
        list().forEach { login ->
            array.put(login.toPublicJson())
        }
        return array
    }

    private fun write(items: List<SavedBrowserLogin>) {
        val array = JSONArray()
        items.forEach { login ->
            array.put(
                JSONObject()
                    .put("id", login.id)
                    .put("origin", login.origin)
                    .put("username", login.username)
                    .put("password", login.password),
            )
        }
        blob.write(array.toString())
    }

    companion object {
        private const val Alphabet = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789!@#\$%^&*-_"

        fun inMemory(initial: String = "[]"): BrowserLoginVault {
            val store = object : BrowserLoginBlobStore {
                @Volatile
                private var raw = initial
                override fun read(): String = raw
                override fun write(raw: String) {
                    this.raw = raw
                }
            }
            return BrowserLoginVault(store)
        }

        fun generatePassword(length: Int = 18): String {
            val n = length.coerceIn(10, 64)
            val random = SecureRandom()
            return buildString(n) {
                repeat(n) { append(Alphabet[random.nextInt(Alphabet.length)]) }
            }
        }

        fun originHost(value: String): String {
            val trimmed = value.trim()
            val authority = trimmed
                .removePrefix("https://")
                .removePrefix("http://")
                .substringBefore('/')
            return authority.lowercase()
        }
    }

    private fun parse(raw: String): List<SavedBrowserLogin> {
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    SavedBrowserLogin(
                        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                        origin = item.optString("origin"),
                        username = item.optString("username"),
                        password = item.optString("password"),
                    ),
                )
            }
        }
    }
}

fun SavedBrowserLogin.toPublicJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("origin", origin)
    .put("username", username)

private class EncryptedBrowserLoginBlobStore(context: Context) : BrowserLoginBlobStore {
    private val secrets = HostSecretStore(context.applicationContext)
    private val legacy = File(context.applicationContext.filesDir, "browser/logins.json")

    override fun read(): String {
        secrets.get(SecretId)?.let { return it }
        if (!legacy.isFile) return "[]"
        val raw = runCatching { legacy.readText() }.getOrDefault("[]")
        runCatching { secrets.put(SecretId, raw) }
            .onSuccess { runCatching { legacy.delete() } }
        return raw
    }

    override fun write(raw: String) {
        runCatching { secrets.put(SecretId, raw) }
            .onSuccess {
                if (legacy.isFile) runCatching { legacy.delete() }
            }
            .onFailure {
                legacy.parentFile?.mkdirs()
                legacy.writeText(raw)
            }
    }

    companion object {
        private const val SecretId = "browser.logins"
    }
}
