package kira.ditto.runtime

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

internal const val AlpineTermJobsGuestPath = "/tmp/aether-term-jobs"
internal const val AlpineTermDaemonGuestPath = "/usr/local/bin/aether-termd"

internal val AlpineTermDaemonScript = """
#!/bin/sh
JOBS='$AlpineTermJobsGuestPath'
mkdir -p "${'$'}JOBS"
while true; do
  for killf in "${'$'}JOBS"/*.kill; do
    [ -e "${'$'}killf" ] || break
    [ -f "${'$'}killf" ] || continue
    id=${'$'}(basename "${'$'}killf" .kill)
    if [ -f "${'$'}JOBS/${'$'}id.pid" ]; then
      pid=${'$'}(cat "${'$'}JOBS/${'$'}id.pid")
      kill "${'$'}pid" 2>/dev/null
      kill -9 "${'$'}pid" 2>/dev/null
    fi
    rm -f "${'$'}killf"
  done
  found=0
  for req in "${'$'}JOBS"/*.req; do
    [ -e "${'$'}req" ] || break
    [ -f "${'$'}req" ] || continue
    id=${'$'}(basename "${'$'}req" .req)
    cmd="${'$'}JOBS/${'$'}id.cmd"
    mv "${'$'}req" "${'$'}JOBS/${'$'}id.claimed" 2>/dev/null || continue
    found=1
    (
      if [ -f "${'$'}cmd" ]; then
        /bin/sh "${'$'}cmd" >"${'$'}JOBS/${'$'}id.out" 2>&1
        echo ${'$'}? >"${'$'}JOBS/${'$'}id.exit"
      else
        echo 127 >"${'$'}JOBS/${'$'}id.exit"
      fi
    ) &
    echo ${'$'}! >"${'$'}JOBS/${'$'}id.pid"
  done
  if [ "${'$'}found" -eq 0 ]; then
    sleep 0.03 2>/dev/null || sleep 1
  fi
done
""".trimIndent() + "\n"

internal interface AlpineProcessHandle {
    val inputStream: InputStream
    val isAlive: Boolean
    fun waitFor(): Int
    fun waitFor(timeout: Long, unit: TimeUnit): Boolean
    fun destroy()
    fun destroyForcibly()
}

internal class JavaProcessHandle(
    private val process: Process,
) : AlpineProcessHandle {
    override val inputStream: InputStream get() = process.inputStream
    override val isAlive: Boolean get() = process.isAlive
    override fun waitFor(): Int = process.waitFor()
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = process.waitFor(timeout, unit)
    override fun destroy() {
        process.destroy()
    }
    override fun destroyForcibly() {
        process.destroyForcibly()
    }
}

internal class DaemonJobHandle(
    private val outFile: File,
    private val exitFile: File,
    private val killFile: File,
) : AlpineProcessHandle {
    override val inputStream: InputStream = TailingFileInputStream(outFile, exitFile)
    override val isAlive: Boolean get() = !exitFile.exists()

    override fun waitFor(): Int {
        if (!waitFor(10, TimeUnit.MINUTES)) {
            destroy()
            error("Alpine term job timed out after 10 minutes.")
        }
        return readExitCode()
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        val deadlineNs = System.nanoTime() + unit.toNanos(timeout)
        while (!exitFile.exists()) {
            if (System.nanoTime() >= deadlineNs) return false
            Thread.sleep(20)
        }
        return true
    }

    override fun destroy() {
        runCatching { killFile.writeText("1") }
    }

    override fun destroyForcibly() {
        destroy()
    }

    private fun readExitCode(): Int =
        exitFile.readText().trim().toIntOrNull() ?: -1
}

internal class TailingFileInputStream(
    private val file: File,
    private val exitFile: File,
) : InputStream() {
    private var raf: RandomAccessFile? = null
    private var pos = 0L

    override fun read(): Int {
        val buf = ByteArray(1)
        val n = read(buf, 0, 1)
        return if (n <= 0) -1 else buf[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len <= 0) return 0
        while (true) {
            ensureOpen()
            val handle = raf ?: return -1
            val size = file.length()
            if (pos < size) {
                handle.seek(pos)
                val n = handle.read(b, off, minOf(len, (size - pos).toInt().coerceAtLeast(0)))
                if (n > 0) {
                    pos += n
                    return n
                }
            }
            if (exitFile.exists() && pos >= file.length()) {
                close()
                return -1
            }
            Thread.sleep(15)
        }
    }

    override fun close() {
        runCatching { raf?.close() }
        raf = null
    }

    private fun ensureOpen() {
        if (raf != null) return
        if (!file.exists()) return
        raf = RandomAccessFile(file, "r")
    }
}

internal fun writeAlpineTermJob(
    jobsDir: File,
    jobId: String,
    workingDirectory: String,
    commandLine: String,
    extraEnvironment: Map<String, String>,
): DaemonJobHandle {
    jobsDir.mkdirs()
    val cmdFile = File(jobsDir, "$jobId.cmd")
    val outFile = File(jobsDir, "$jobId.out")
    val reqFile = File(jobsDir, "$jobId.req")
    val cwd = workingDirectory.ifBlank { "/workspace" }
    cmdFile.writeText(
        buildString {
            appendLine("#!/bin/sh")
            appendLine("cd ${termShellQuote(cwd)} || cd /workspace")
            extraEnvironment.forEach { (name, value) ->
                if (!name.matches(Regex("^[A-Za-z_][A-Za-z0-9_]*$"))) return@forEach
                appendLine("export $name=${termShellQuote(value)}")
            }
            append("exec /bin/sh -c ")
            appendLine(termShellQuote(commandLine))
        },
    )
    outFile.writeBytes(ByteArray(0))
    File(jobsDir, "$jobId.req.tmp").let { tmp ->
        tmp.writeText("1")
        if (!tmp.renameTo(reqFile)) {
            tmp.copyTo(reqFile, overwrite = true)
            tmp.delete()
        }
    }
    return DaemonJobHandle(
        outFile = outFile,
        exitFile = File(jobsDir, "$jobId.exit"),
        killFile = File(jobsDir, "$jobId.kill"),
    )
}

private fun termShellQuote(value: String): String =
    "'" + value.replace("'", "'\"'\"'") + "'"
