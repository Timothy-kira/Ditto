package kira.ditto.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kira.ditto.R
import kira.ditto.browser.BrowserActivity
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kira.ditto.BuildConfig
import org.json.JSONObject

internal data class GmailAuthSnapshot(
    val email: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val clientId: String = "",
    val clientSecret: String = "",
    val expiryMillis: Long = 0L,
) {
    val isConnected: Boolean get() = refreshToken.isNotBlank() || accessToken.isNotBlank()
}

internal object GmailAuth {
    const val StoreId = "gmail.oauth"
    const val AuthPath = "/gmail-oauth"
    private val connectLock = Any()
    val Scopes: List<String> = listOf(
        "https://www.googleapis.com/auth/gmail.modify",
        "https://www.googleapis.com/auth/gmail.compose",
        "https://www.googleapis.com/auth/userinfo.email",
    )

    fun snapshot(store: HostSecretStore): GmailAuthSnapshot {
        val json = store.getJson(StoreId) ?: JSONObject()
        return GmailAuthSnapshot(
            email = json.optString("email"),
            accessToken = json.optString("access_token"),
            refreshToken = json.optString("refresh_token"),
            clientId = json.optString("client_id").ifBlank { bundledClientId() },
            clientSecret = json.optString("client_secret").ifBlank { bundledClientSecret() },
            expiryMillis = json.optLong("expiry_ms"),
        )
    }

    fun save(store: HostSecretStore, snapshot: GmailAuthSnapshot) {
        store.putJson(
            StoreId,
            JSONObject()
                .put("email", snapshot.email)
                .put("access_token", snapshot.accessToken)
                .put("refresh_token", snapshot.refreshToken)
                .put("client_id", snapshot.clientId)
                .put("client_secret", snapshot.clientSecret)
                .put("expiry_ms", snapshot.expiryMillis),
        )
    }

    fun disconnect(store: HostSecretStore) {
        store.delete(StoreId)
    }

    fun importJson(store: HostSecretStore, raw: String): GmailAuthSnapshot {
        val parsed = JSONObject(raw.trim())
        val current = snapshot(store)
        val next = current.copy(
            email = parsed.optString("email").ifBlank { current.email },
            accessToken = parsed.optString("access_token").ifBlank {
                parsed.optString("accessToken")
            }.ifBlank { current.accessToken },
            refreshToken = parsed.optString("refresh_token").ifBlank {
                parsed.optString("refreshToken")
            }.ifBlank { current.refreshToken },
            clientId = parsed.optString("client_id").ifBlank {
                parsed.optString("clientId")
            }.ifBlank { current.clientId },
            clientSecret = parsed.optString("client_secret").ifBlank {
                parsed.optString("clientSecret")
            }.ifBlank { current.clientSecret },
            expiryMillis = parsed.optLong("expiry_ms", parsed.optLong("expiry", current.expiryMillis)),
        )
        if (!next.isConnected) error("JSON 需要 refresh_token 或 access_token")
        save(store, next)
        return next
    }

    fun saveClient(store: HostSecretStore, clientId: String, clientSecret: String) {
        val current = snapshot(store)
        save(
            store,
            current.copy(
                clientId = clientId.trim().ifBlank { current.clientId },
                clientSecret = clientSecret.trim().ifBlank { current.clientSecret },
            ),
        )
    }

    fun bundledClientId(): String = BuildConfig.GMAIL_OAUTH_CLIENT_ID.trim()

    fun bundledClientSecret(): String = BuildConfig.GMAIL_OAUTH_CLIENT_SECRET.trim()

    fun accessToken(store: HostSecretStore, nowMillis: Long = System.currentTimeMillis()): String {
        val current = snapshot(store)
        if (current.accessToken.isNotBlank() &&
            (current.expiryMillis <= 0L || current.expiryMillis > nowMillis + 30_000L)
        ) {
            return current.accessToken
        }
        if (current.refreshToken.isBlank() || current.clientId.isBlank()) {
            return current.accessToken
        }
        val refreshed = refreshAccessToken(current)
        save(store, refreshed)
        return refreshed.accessToken
    }

    fun connectWithBrowser(
        context: Context,
        store: HostSecretStore,
        force: Boolean = false,
    ): GmailAuthSnapshot {
        synchronized(connectLock) {
            val already = snapshot(store)
            if (!force && already.isConnected && already.accessToken.isNotBlank()) return already
            val current = snapshot(store)
            val clientId = current.clientId
            if (clientId.isBlank()) {
                error("Google 网页登录未配置 OAuth 客户端。")
            }
            val verifier = pkceVerifier()
            val challenge = pkceChallenge(verifier)
            val callback = startCallbackServer()
            try {
                val redirect = "http://127.0.0.1:${callback.localPort}$AuthPath"
                val authUrl = buildString {
                    append("https://accounts.google.com/o/oauth2/v2/auth")
                    append("?client_id=").append(enc(clientId))
                    append("&redirect_uri=").append(enc(redirect))
                    append("&response_type=code")
                    append("&scope=").append(enc(Scopes.joinToString(" ")))
                    append("&code_challenge=").append(enc(challenge))
                    append("&code_challenge_method=S256")
                    append("&access_type=offline")
                    append("&prompt=consent")
                }
                openLoginChooser(context, authUrl)
                val code = try {
                    callback.waitForCode(180_000L)
                } catch (error: Throwable) {
                    error(gmailOAuthUserMessage(error))
                }
                val tokens = exchangeCode(
                    clientId = clientId,
                    clientSecret = current.clientSecret,
                    code = code,
                    redirectUri = redirect,
                    verifier = verifier,
                )
                val email = fetchEmail(tokens.optString("access_token")).ifBlank { current.email }
                val next = current.copy(
                    email = email,
                    accessToken = tokens.optString("access_token"),
                    refreshToken = tokens.optString("refresh_token").ifBlank { current.refreshToken },
                    expiryMillis = System.currentTimeMillis() + tokens.optLong("expires_in", 3600L) * 1000L,
                )
                save(store, next)
                return next
            } finally {
                runCatching { callback.close() }
            }
        }
    }

    private fun refreshAccessToken(current: GmailAuthSnapshot): GmailAuthSnapshot {
        val body = buildString {
            append("grant_type=refresh_token")
            append("&refresh_token=").append(enc(current.refreshToken))
            append("&client_id=").append(enc(current.clientId))
            if (current.clientSecret.isNotBlank()) {
                append("&client_secret=").append(enc(current.clientSecret))
            }
        }
        val tokens = postForm("https://oauth2.googleapis.com/token", body)
        return current.copy(
            accessToken = tokens.optString("access_token").ifBlank { current.accessToken },
            refreshToken = tokens.optString("refresh_token").ifBlank { current.refreshToken },
            expiryMillis = System.currentTimeMillis() + tokens.optLong("expires_in", 3600L) * 1000L,
        )
    }

    private fun exchangeCode(
        clientId: String,
        clientSecret: String,
        code: String,
        redirectUri: String,
        verifier: String,
    ): JSONObject {
        val body = buildString {
            append("grant_type=authorization_code")
            append("&code=").append(enc(code))
            append("&client_id=").append(enc(clientId))
            append("&redirect_uri=").append(enc(redirectUri))
            append("&code_verifier=").append(enc(verifier))
            if (clientSecret.isNotBlank()) {
                append("&client_secret=").append(enc(clientSecret))
            }
        }
        return postForm("https://oauth2.googleapis.com/token", body)
    }

    private fun fetchEmail(accessToken: String): String {
        if (accessToken.isBlank()) return ""
        val json = gmailGet("https://gmail.googleapis.com/gmail/v1/users/me/profile", accessToken)
        return json.optString("emailAddress")
    }

    private fun postForm(url: String, body: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        return connection.useHttp { conn ->
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val raw = conn.inputStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
            JSONObject(raw)
        }
    }

    internal fun gmailGet(url: String, accessToken: String): JSONObject =
        gmailRequest("GET", url, accessToken, null)

    internal fun gmailPost(url: String, accessToken: String, body: JSONObject): JSONObject =
        gmailRequest("POST", url, accessToken, body)

    private fun gmailRequest(
        method: String,
        url: String,
        accessToken: String,
        body: JSONObject?,
    ): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        return connection.useHttp { conn ->
            if (body != null) {
                conn.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.use { it.readBytes().toString(StandardCharsets.UTF_8) }.orEmpty()
            val parsed = runCatching { JSONObject(raw.ifBlank { "{}" }) }.getOrDefault(JSONObject())
            if (conn.responseCode !in 200..299) {
                val message = parsed.optJSONObject("error")?.optString("message")
                    ?.ifBlank { parsed.optString("error") }
                    .orEmpty()
                    .ifBlank { raw.take(400) }
                    .ifBlank { "HTTP ${conn.responseCode}" }
                error(message)
            }
            parsed
        }
    }

    private fun openLoginChooser(context: Context, url: String) {
        val app = context.applicationContext
        val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val inApp = Intent(app, BrowserActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra(BrowserActivity.ExtraUrl, url)
        }
        val chooser = Intent.createChooser(
            viewIntent,
            app.getString(R.string.settings_gmail_mcp_choose_browser),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(inApp))
        }
        Handler(Looper.getMainLooper()).post {
            runCatching { app.startActivity(chooser) }.getOrElse {
                app.startActivity(inApp)
            }
        }
    }

    private fun startCallbackServer(): LoopbackOAuthCallbackServer {
        val socket = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        return LoopbackOAuthCallbackServer(socket, "Gmail")
    }

    private fun pkceVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun pkceChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(StandardCharsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun enc(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}

internal data class GmailOAuthCallback(
    val path: String,
    val code: String,
    val error: String,
) {
    val isIgnorable: Boolean
        get() {
            val route = path.substringBefore('?').lowercase()
            return route.endsWith("favicon.ico") ||
                route.endsWith("favicon.png") ||
                (code.isBlank() && error.isBlank())
        }
}

internal fun gmailOAuthUserMessage(error: Throwable): String {
    val raw = error.message.orEmpty().trim()
    val lower = raw.lowercase()
    if (
        lower.contains("broken pipe") ||
        lower.contains("connection reset") ||
        lower.contains("epipe") ||
        lower.contains("connection abort")
    ) {
        return "登录回调被浏览器中断，请重试。"
    }
    return raw.ifBlank { "Gmail 登录失败。" }
}

internal fun gmailOAuthCallbackFromRequestLine(requestLine: String): GmailOAuthCallback {
    val path = requestLine.split(' ').getOrNull(1).orEmpty()
    val query = path.substringAfter('?', "")
    val params = query.split('&').mapNotNull { part ->
        if (part.isBlank()) return@mapNotNull null
        val key = URLDecoder.decode(part.substringBefore('='), Charsets.UTF_8.name())
        val value = URLDecoder.decode(part.substringAfter('=', ""), Charsets.UTF_8.name())
        if (key.isBlank()) null else key to value
    }.toMap()
    return GmailOAuthCallback(
        path = path,
        code = params["code"].orEmpty(),
        error = params["error"].orEmpty(),
    )
}

private inline fun <T> HttpURLConnection.useHttp(block: (HttpURLConnection) -> T): T {
    try {
        return block(this)
    } finally {
        disconnect()
    }
}
