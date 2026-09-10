package kira.ditto.data

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

internal class LoopbackOAuthCallbackServer(
    private val socket: ServerSocket,
    private val productName: String,
) {
    val localPort: Int get() = socket.localPort

    fun waitForCode(timeoutMillis: Long): String {
        val deadline = System.currentTimeMillis() + timeoutMillis.coerceAtLeast(1_000L)
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) error("登录超时，请重试。")
            socket.soTimeout = remaining.toInt().coerceAtLeast(1)
            val client = try {
                socket.accept()
            } catch (_: SocketTimeoutException) {
                error("登录超时，请重试。")
            }
            val callback = try {
                handleCallbackClient(client, productName)
            } catch (_: IOException) {
                null
            } finally {
                runCatching { client.close() }
            }
            if (callback == null) continue
            if (callback.code.isNotBlank()) return callback.code
            if (callback.error.isNotBlank()) error(callback.error)
        }
    }

    fun close() {
        runCatching { socket.close() }
    }
}

private fun handleCallbackClient(client: Socket, productName: String): GmailOAuthCallback? {
    client.soTimeout = 10_000
    val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.US_ASCII))
    val requestLine = reader.readLine().orEmpty()
    while (true) {
        val header = reader.readLine() ?: break
        if (header.isEmpty()) break
    }
    val callback = gmailOAuthCallbackFromRequestLine(requestLine)
    val html = when {
        callback.code.isNotBlank() -> "$productName connected. You can return to Aether."
        callback.error.isNotBlank() -> "$productName authorization failed: ${callback.error}"
        else -> "Waiting for sign-in."
    }
    val body = "<html><body><p>$html</p></body></html>".toByteArray(Charsets.UTF_8)
    val response = (
        "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        ).toByteArray(Charsets.US_ASCII) + body
    runCatching { client.getOutputStream().apply { write(response); flush() } }
        .onFailure { error ->
            if (callback.code.isBlank() && error is IOException) {
                return if (callback.isIgnorable) null else throw error
            }
        }
    return if (callback.isIgnorable) null else callback
}
