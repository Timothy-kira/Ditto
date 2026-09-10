package kira.ditto.runtime

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/** Adapts the legacy Android Alpine runtime to the shared file manager surface. */
class AndroidAlpineFileManagerRuntime(
    private val alpine: AlpineRuntime,
) : MultiplatformLocalRuntime {
    override val homeDirectory: String = alpine.homeDirectory
    override val workspaceRoot: String = "/"
    override val fileSystem: RuntimeFileSystem = AndroidAlpineFileSystem(alpine)

    override suspend fun isReady(): Boolean = alpine.inspectSetup().isReady

    override suspend fun initialize(onProgress: (RuntimeSetupProgress) -> Unit) {
        check(alpine.initialize().isReady) { "Alpine runtime is not ready." }
    }

    override suspend fun reset() {
        alpine.reset()
    }

    override suspend fun startProcess(spec: RuntimeProcessSpec): RuntimeProcess {
        val command = buildString {
            append(shellQuote(spec.executable))
            spec.arguments.forEach { append(' ').append(shellQuote(it)) }
        }
        val process = alpine.startManagedProcess(command, spec.workingDirectory, spec.redirectErrorStream)
        return AndroidAlpineProcess(process)
    }

    internal suspend fun listDirectory(path: String): List<AndroidAlpineFileEntry> = withContext(Dispatchers.IO) {
        val directory = alpine.resolveGuestPath(path)
        require(directory.isDirectory) { "Not a directory: $path" }
        directory.listFiles().orEmpty().map { file ->
            val isSymbolicLink = Files.isSymbolicLink(file.toPath())
            AndroidAlpineFileEntry(
                name = file.name,
                path = if (path == "/") "/${file.name}" else "${path.trimEnd('/')}/${file.name}",
                isDirectory = file.isDirectory && !isSymbolicLink,
                isSymbolicLink = isSymbolicLink,
                size = file.length(),
                modifiedAtMillis = file.lastModified(),
            )
        }
    }

    internal suspend fun move(source: String, destination: String) = withContext(Dispatchers.IO) {
        val sourceFile = alpine.resolveGuestPath(source)
        val destinationFile = alpine.resolveGuestPath(destination)
        require(!destinationFile.exists()) { "An item already exists at $destination" }
        destinationFile.parentFile?.mkdirs()
        runCatching {
            Files.move(sourceFile.toPath(), destinationFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(sourceFile.toPath(), destinationFile.toPath())
        }
        Unit
    }

    internal suspend fun importFile(destination: String, input: InputStream) = withContext(Dispatchers.IO) {
        val destinationFile = alpine.resolveGuestPath(destination)
        require(!destinationFile.exists()) { "An item already exists at $destination" }
        destinationFile.parentFile?.mkdirs()
        try {
            destinationFile.outputStream().use(input::copyTo)
        } catch (error: Throwable) {
            destinationFile.delete()
            throw error
        }
        Unit
    }
}

internal data class AndroidAlpineFileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val isSymbolicLink: Boolean,
    val size: Long,
    val modifiedAtMillis: Long,
)

private class AndroidAlpineFileSystem(
    private val alpine: AlpineRuntime,
) : RuntimeFileSystem {
    private suspend fun resolve(path: String): File = alpine.resolveGuestPath(path)

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) { resolve(path).exists() }

    override suspend fun createDirectories(path: String) {
        withContext(Dispatchers.IO) { resolve(path).apply { check(mkdirs() || isDirectory) { "Unable to create directory: $path" } } }
    }

    override suspend fun read(path: String): ByteArray = withContext(Dispatchers.IO) { resolve(path).readBytes() }

    override suspend fun read(path: String, maximumBytes: Long): ByteArray = withContext(Dispatchers.IO) {
        require(maximumBytes >= 0)
        val file = resolve(path)
        require(file.length() <= maximumBytes) { "File exceeds the allowed size." }
        file.readBytes()
    }

    override suspend fun readPrefix(path: String, maximumBytes: Long): ByteArray = withContext(Dispatchers.IO) {
        require(maximumBytes >= 0)
        resolve(path).inputStream().use { it.readNBytes(maximumBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) }
    }

    override suspend fun write(path: String, content: ByteArray, executable: Boolean) = withContext(Dispatchers.IO) {
        resolve(path).apply {
            parentFile?.mkdirs()
            writeBytes(content)
            setExecutable(executable)
        }
        Unit
    }

    override suspend fun remove(path: String, recursive: Boolean) = withContext(Dispatchers.IO) {
        require(path.trimEnd('/').isNotEmpty()) { "Refusing to remove the Alpine root." }
        val file = resolve(path)
        check(if (recursive) file.deleteRecursively() else file.delete()) { "Unable to remove $path" }
    }

    override suspend fun bindHostDirectory(hostPath: String, guestPath: String, readOnly: Boolean) {
        throw UnsupportedOperationException("Android file manager does not create bind mounts.")
    }
}

private class AndroidAlpineProcess(
    private val process: Process,
) : RuntimeProcess {
    override val pid: Int = process.hashCode()
    override val stdout: Flow<ByteArray> = process.inputStream.asByteFlow()
    override val stderr: Flow<ByteArray> = process.errorStream.asByteFlow()

    override suspend fun writeStdin(bytes: ByteArray) = withContext(Dispatchers.IO) {
        process.outputStream.write(bytes)
        process.outputStream.flush()
    }

    override suspend fun closeStdin() = withContext(Dispatchers.IO) { process.outputStream.close() }

    override suspend fun awaitExit(): RuntimeProcessExit = withContext(Dispatchers.IO) {
        process.waitFor()
        RuntimeProcessExit(process.exitValue())
    }

    override suspend fun signal(signal: RuntimeProcessSignal) {
        when (signal) {
            RuntimeProcessSignal.Interrupt, RuntimeProcessSignal.Terminate -> process.destroy()
            RuntimeProcessSignal.Kill -> process.destroyForcibly()
        }
    }
}

private fun java.io.InputStream.asByteFlow(): Flow<ByteArray> = flow {
    val bytes = withContext(Dispatchers.IO) { use { it.readBytes() } }
    var offset = 0
    while (offset < bytes.size) {
        val end = minOf(offset + 16 * 1024, bytes.size)
        emit(bytes.copyOfRange(offset, end))
        offset = end
    }
}

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
