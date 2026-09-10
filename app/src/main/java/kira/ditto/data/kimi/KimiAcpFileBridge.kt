package kira.ditto.data.kimi

import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

private const val DefaultMaxReadBytes = 8L * 1024 * 1024
private const val DefaultMaxReadLines = 20_000

/** Guest path of kimi-code session storage (plan files, todos, agent state). */
const val KimiCodeSessionsGuestRoot = "/root/.kimi-code/sessions"

private const val KimiPlanDocumentMarker = "/agents/main/plans/"

/**
 * Merges the caller's extra roots with the kimi-code sessions directory.
 * Aligns with CLI `--add-dir` so Plan mode can write agents/main/plans markdown
 * without opening all of `/root`.
 */
fun mergeKimiAcpAdditionalDirectories(additional: List<String>): List<String> =
    (listOf(KimiCodeSessionsGuestRoot) + additional)
        .map(KimiAcpFileBridge::normalizeGuestPath)
        .filter { path -> path.isNotBlank() && path != "/" }
        .distinct()

/** True for kimi-code session plan markdown under agents/main/plans. */
fun isKimiSessionPlanDocumentPath(path: String): Boolean {
    val guest = KimiAcpFileBridge.normalizeGuestPath(path)
    if (!guest.startsWith("$KimiCodeSessionsGuestRoot/")) return false
    val markerIndex = guest.indexOf(KimiPlanDocumentMarker)
    if (markerIndex < 0) return false
    val name = guest.substring(markerIndex + KimiPlanDocumentMarker.length)
    return name.endsWith(".md", ignoreCase = true) &&
        name.isNotBlank() &&
        !name.startsWith('.') &&
        !name.contains('/')
}

/** Signals an fs/read_text_file or fs/write_text_file failure; mapped to a JSON-RPC error. */
class AcpFsException(message: String) : Exception(message)

private data class AcpSessionRoot(
    val guestPath: String,
    val hostRoot: File,
)

/**
 * Serves ACP fs/read_text_file and fs/write_text_file directly on the Android
 * host filesystem (bypassing the proot shell). Every path is POSIX-normalized,
 * confined to the session cwd + additionalDirectories at both the guest level
 * (prefix check) and the host level (canonical containment), reads are truncated
 * to a byte/line budget, and writes go through a temp file + atomic rename.
 */
class KimiAcpFileBridge(
    private val resolveHostFile: (guestPath: String) -> File,
    private val maxReadBytes: Long = DefaultMaxReadBytes,
    private val maxReadLines: Int = DefaultMaxReadLines,
) {
    private val sessionRoots = ConcurrentHashMap<String, List<AcpSessionRoot>>()

    fun registerSession(
        sessionId: String,
        cwd: String,
        additionalDirectories: List<String> = emptyList(),
    ) {
        if (sessionId.isBlank()) return
        val roots = (listOf(cwd) + additionalDirectories)
            .filter(String::isNotBlank)
            .map(::normalizeGuestPath)
            .distinct()
            .mapNotNull { guest ->
                runCatching { AcpSessionRoot(guest, resolveHostFile(guest).canonicalFile) }.getOrNull()
            }
        if (roots.isEmpty()) {
            sessionRoots.remove(sessionId)
        } else {
            sessionRoots[sessionId] = roots
        }
    }

    fun copySessionRoots(fromSessionId: String, toSessionId: String) {
        val roots = sessionRoots[fromSessionId] ?: return
        sessionRoots[toSessionId] = roots
    }

    fun unregisterSession(sessionId: String) {
        sessionRoots.remove(sessionId)
    }

    fun readTextFile(
        sessionId: String,
        path: String,
        line: Int? = null,
        limit: Int? = null,
    ): JSONObject {
        val host = resolveChecked(sessionId, path)
        if (!host.isFile) throw AcpFsException("File not found: $path")
        val length = host.length()
        val readLength = minOf(length, maxReadBytes + 1).toInt()
        val bytes = ByteArray(readLength)
        host.inputStream().buffered().use { input ->
            var offset = 0
            while (offset < bytes.size) {
                val read = input.read(bytes, offset, bytes.size - offset)
                if (read < 0) break
                offset += read
            }
        }
        var text = String(bytes, Charsets.UTF_8)
        if (text.firstOrNull()?.code == 0xFEFF) text = text.substring(1) // strip UTF-8 BOM
        val lines = text.split('\n')
        val startIndex = ((line ?: 1) - 1).coerceIn(0, lines.size)
        val maxLines = (limit ?: maxReadLines).coerceAtMost(maxReadLines).coerceAtLeast(0)
        val endIndex = (startIndex + maxLines).coerceAtMost(lines.size)
        val content = lines.subList(startIndex, endIndex)
            .joinToString("\n") { it.trimEnd('\r') }
        return JSONObject().put("content", content)
    }

    fun writeTextFile(sessionId: String, path: String, content: String): JSONObject {
        val host = resolveChecked(sessionId, path)
        val parent = host.parentFile ?: throw AcpFsException("Invalid file path: $path")
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw AcpFsException("Unable to create directory for: $path")
        }
        val bytes = content.toByteArray(Charsets.UTF_8)
        val temp = runCatching { File.createTempFile(".aether-acp-", ".tmp", parent) }
            .getOrElse { throw AcpFsException("Unable to stage file write: $path") }
        try {
            FileOutputStream(temp).use { output -> output.write(bytes) }
            if (!temp.renameTo(host)) {
                // Android rename(2) overwrites in place; this fallback covers
                // filesystems where renameTo refuses an existing destination.
                if (host.exists() && !host.delete()) {
                    throw AcpFsException("Unable to replace existing file: $path")
                }
                if (!temp.renameTo(host)) {
                    FileOutputStream(host).use { output -> output.write(bytes) }
                    temp.delete()
                }
            }
        } catch (error: AcpFsException) {
            temp.delete()
            throw error
        } catch (error: Throwable) {
            temp.delete()
            throw AcpFsException("Unable to write file: $path")
        }
        return JSONObject()
    }

    private fun resolveChecked(sessionId: String, path: String): File {
        val roots = sessionRoots[sessionId]
            ?: throw AcpFsException("Unknown ACP session for fs request: $sessionId")
        if (path.isBlank()) throw AcpFsException("File path is missing.")
        val guest = normalizeGuestPath(path)
        val root = roots.firstOrNull { candidate ->
            guest == candidate.guestPath || guest.startsWith(candidate.guestPath.trimEnd('/') + "/")
        } ?: throw AcpFsException("Path is outside the session workspace: $path")
        val host = runCatching { resolveHostFile(guest).canonicalFile }
            .getOrElse { throw AcpFsException("Invalid file path: $path") }
        val rootPath = root.hostRoot.path
        if (host.path != rootPath && !host.path.startsWith(rootPath + File.separator)) {
            throw AcpFsException("Path escapes the session workspace: $path")
        }
        return host
    }

    companion object {
        /** Lexical POSIX normalization: resolves "." / ".." without touching the host FS. */
        fun normalizeGuestPath(raw: String): String {
            val segments = ArrayDeque<String>()
            raw.trim().split('/').forEach { segment ->
                when (segment) {
                    "", "." -> Unit
                    ".." -> if (segments.isNotEmpty()) segments.removeLast()
                    else -> segments.addLast(segment)
                }
            }
            return "/" + segments.joinToString("/")
        }
    }
}
