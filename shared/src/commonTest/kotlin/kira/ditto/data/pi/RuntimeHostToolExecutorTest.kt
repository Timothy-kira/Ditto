package kira.ditto.data.pi

import kira.ditto.runtime.MultiplatformLocalRuntime
import kira.ditto.runtime.RuntimeFileSystem
import kira.ditto.runtime.RuntimeProcess
import kira.ditto.runtime.RuntimeProcessExit
import kira.ditto.runtime.RuntimeProcessSignal
import kira.ditto.runtime.RuntimeProcessSpec
import kira.ditto.runtime.RuntimeSetupProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class RuntimeHostToolExecutorTest {
    @Test
    fun readsRelativePathWithOffsetAndLineNumbers() = runTest {
        val runtime = HostToolFakeRuntime()
        runtime.files["/workspace/docs/readme.txt"] = "zero\none\ntwo\nthree".encodeToByteArray()

        val result = RuntimeHostToolExecutor(runtime).execute(
            "read",
            args {
                put("path", "docs/../docs/readme.txt")
                put("offset", 1)
                put("limit", 2)
                put("showLineNumbers", true)
            },
        ).json()

        assertEquals("one\ntwo\n", result.string("content"))
        assertEquals(
            "Showing lines 2-3 of 4 from docs/../docs/readme.txt.\n\n" +
                "2: one\n3: two\n4: \n\nOutput was truncated.",
            result.string("stdout"),
        )
        assertEquals("docs/../docs/readme.txt", result.string("path"))
        assertEquals("alpine", result.string("runtime"))
        assertEquals("4", result["total_line_count"]!!.jsonPrimitive.content)
        assertTrue(result["truncated"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun writeUsesExistingParentAndExpandsHomePath() = runTest {
        val runtime = HostToolFakeRuntime().apply { directories += "/root/notes" }

        val result = RuntimeHostToolExecutor(runtime).execute(
            "write",
            args {
                put("path", "~/notes/today.txt")
                put("content", "hello")
            },
        ).json()

        assertEquals("hello", runtime.files.getValue("/root/notes/today.txt").decodeToString())
        assertEquals("~/notes/today.txt", result.string("path"))
        assertTrue(result["created"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("Created ~/notes/today.txt (5 bytes).", result.string("stdout"))
    }

    @Test
    fun writeDoesNotSilentlyCreateMissingParent() = runTest {
        val runtime = HostToolFakeRuntime()

        val result = RuntimeHostToolExecutor(runtime).execute(
            "write",
            args {
                put("path", "~/missing/today.txt")
                put("content", "hello")
            },
        )

        assertTrue(result.isError)
        assertTrue(result.json().string("errmsg").contains("Parent directory not found"))
        assertFalse("/root/missing/today.txt" in runtime.files)
    }

    @Test
    fun editRequiresExactlyOneMatch() = runTest {
        val runtime = HostToolFakeRuntime()
        runtime.files["/workspace/file.txt"] = "old and old".encodeToByteArray()

        val result = RuntimeHostToolExecutor(runtime).execute(
            "edit",
            args {
                put("path", "file.txt")
                put("oldText", "old")
                put("newText", "new")
            },
        )

        assertTrue(result.isError)
        assertTrue(result.json().string("errmsg").contains("matched multiple locations"))
        assertEquals("old and old", runtime.files.getValue("/workspace/file.txt").decodeToString())
    }

    @Test
    fun editAppliesNonOverlappingBatchAgainstOriginalContent() = runTest {
        val runtime = HostToolFakeRuntime()
        runtime.files["/workspace/file.txt"] = "alpha beta gamma".encodeToByteArray()

        val result = RuntimeHostToolExecutor(runtime).execute(
            "edit",
            buildJsonObject {
                put("path", "file.txt")
                put("edits", Json.parseToJsonElement(
                    """[{"oldText":"alpha","newText":"A"},{"oldText":"gamma","newText":"G"}]""",
                ))
            },
        ).json()

        assertEquals("A beta G", runtime.files.getValue("/workspace/file.txt").decodeToString())
        assertEquals("2", result["applied_edits"]!!.jsonPrimitive.content)
        assertEquals("Applied 2 precise edits to file.txt.", result.string("stdout"))
    }

    @Test
    fun bashReturnsSeparateOutputAndExitStatus() = runTest {
        val runtime = HostToolFakeRuntime().apply {
            nextProcess = HostToolFakeProcess(
                stdoutChunks = listOf("hello ", "world\n"),
                stderrChunks = listOf("warning\n"),
                exitCode = 7,
            )
        }

        val result = RuntimeHostToolExecutor(runtime).execute(
            "bash",
            args {
                put("command", "printf test")
                put("working_directory", "/workspace/project/../project")
            },
        )
        val json = result.json()

        assertTrue(result.isError)
        assertEquals("hello world\n", json.string("stdout"))
        assertEquals("warning\n", json.string("stderr"))
        assertEquals("failed", json.string("status"))
        assertEquals("alpine", json.string("runtime"))
        assertEquals("7", json["exit_code"]!!.jsonPrimitive.content)
        assertEquals("/workspace/project", runtime.lastSpec!!.workingDirectory)
        assertEquals(listOf("-lc", "printf test"), runtime.lastSpec!!.arguments)
        assertFalse(runtime.nextProcess.stdinOpen)
    }

    @Test
    fun grepUsesAndroidDefaultsAndReportsTruncation() = runTest {
        val runtime = HostToolFakeRuntime().apply {
            files["/workspace/file.txt"] = byteArrayOf()
            nextProcess = HostToolFakeProcess(
                stdoutChunks = listOf("file.txt:1:Hit\nfile.txt:3:Hit\n"),
            )
        }

        val result = RuntimeHostToolExecutor(runtime).execute(
            "grep",
            args {
                put("path", "file.txt")
                put("pattern", "Hit")
                put("maxResults", 1)
            },
        ).json()

        assertEquals("true", result["case_sensitive"]!!.jsonPrimitive.content)
        assertEquals("2", result["match_count"]!!.jsonPrimitive.content)
        assertTrue(result["truncated"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("file.txt:1:Hit\n\nShowing first 1 matches.", result.string("stdout"))
        assertFalse(runtime.lastSpec!!.arguments.last().contains(" -i "))
    }

    @Test
    fun definitionsExposeOnlyTheBuiltInIosRuntime() {
        val definitions = RuntimeHostToolExecutor(HostToolFakeRuntime()).definitions
        assertTrue(definitions.isEmpty())
    }

    private fun args(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject(block)
}

private class HostToolFakeRuntime : MultiplatformLocalRuntime {
    override val homeDirectory = "/root"
    override val workspaceRoot = "/workspace"
    val files = mutableMapOf<String, ByteArray>()
    val directories = mutableSetOf("/", "/root", "/workspace")
    var nextProcess = HostToolFakeProcess()
    var lastSpec: RuntimeProcessSpec? = null

    override val fileSystem: RuntimeFileSystem = object : RuntimeFileSystem {
        override suspend fun exists(path: String) = path in files || path in directories
        override suspend fun createDirectories(path: String) { directories += path }
        override suspend fun read(path: String) = files.getValue(path)
        override suspend fun write(path: String, content: ByteArray, executable: Boolean) {
            files[path] = content
        }
        override suspend fun remove(path: String, recursive: Boolean) { files.remove(path) }
        override suspend fun bindHostDirectory(hostPath: String, guestPath: String, readOnly: Boolean) = Unit
    }

    override suspend fun initialize(onProgress: (RuntimeSetupProgress) -> Unit) = Unit

    override suspend fun startProcess(spec: RuntimeProcessSpec): RuntimeProcess {
        lastSpec = spec
        return nextProcess
    }
}

private class HostToolFakeProcess(
    private val stdoutChunks: List<String> = emptyList(),
    private val stderrChunks: List<String> = emptyList(),
    private val exitCode: Int = 0,
    waitForSignal: Boolean = false,
) : RuntimeProcess {
    override val pid = 7
    private val deferredExit = if (waitForSignal) CompletableDeferred<RuntimeProcessExit>() else null
    override val stdout: Flow<ByteArray> = flow {
        stdoutChunks.forEach { emit(it.encodeToByteArray()) }
        deferredExit?.await()
    }
    override val stderr: Flow<ByteArray> = flow {
        stderrChunks.forEach { emit(it.encodeToByteArray()) }
        deferredExit?.await()
    }
    var stdinOpen = true
    val signals = mutableListOf<RuntimeProcessSignal>()

    override suspend fun writeStdin(bytes: ByteArray) = Unit
    override suspend fun closeStdin() { stdinOpen = false }
    override suspend fun awaitExit() = deferredExit?.await() ?: RuntimeProcessExit(exitCode)
    override suspend fun signal(signal: RuntimeProcessSignal) {
        signals += signal
        deferredExit?.complete(RuntimeProcessExit(143, signal))
    }
}

private fun SharedHostToolResult.json(): JsonObject =
    Json.parseToJsonElement(outputJson).jsonObject

private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

private fun JsonArray.strings(): List<String> = map { it.jsonPrimitive.content }
