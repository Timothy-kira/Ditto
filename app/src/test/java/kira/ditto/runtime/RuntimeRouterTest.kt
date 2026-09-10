package kira.ditto.runtime

import kira.ditto.data.AppSettings
import kira.ditto.data.LocalRuntimeId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class RuntimeRouterTest {
    private val alpineRuntime = FakeRuntime(LocalRuntimeId.Alpine)
    private val router = RuntimeRouter(alpineRuntime = alpineRuntime)

    @Test
    fun defaultAndTermuxRequestsUseAlpine() {
        val settings = AppSettings(
            enabledRuntimeIds = setOf(LocalRuntimeId.Termux, LocalRuntimeId.Alpine),
            defaultRuntimeId = LocalRuntimeId.Termux,
            termuxSetupCompleted = true,
            alpineSetupCompleted = true,
        )

        assertSame(alpineRuntime, router.runtimeFor(settings, null))
        assertSame(alpineRuntime, router.runtimeFor(settings, "default"))
        assertSame(alpineRuntime, router.runtimeFor(settings, "termux"))
        assertSame(alpineRuntime, router.runtimeFor(settings, "alpine"))
    }

    @Test
    fun workspaceDirectoryAlwaysUsesAlpine() {
        val settings = AppSettings(
            enabledRuntimeIds = setOf(LocalRuntimeId.Alpine),
            defaultRuntimeId = LocalRuntimeId.Alpine,
            alpineSetupCompleted = true,
        )

        assertEquals(
            "/workspace",
            router.runtimeWorkspaceDirectory(
                settings = settings,
                termuxWorkspaceDirectory = "/data/data/com.termux/files/home/.aether/workspaces/session-1",
                environment = "termux",
            ),
        )
    }

    @Test
    fun prefixedRunIdsRouteToAlpine() {
        val alpine = router.runtimeForRunId("alpine:run-1")
        val termux = router.runtimeForRunId("termux:abc")

        assertSame(alpineRuntime, alpine?.first)
        assertEquals("run-1", alpine?.second)
        assertSame(alpineRuntime, termux?.first)
        assertEquals("abc", termux?.second)
        assertNull(router.runtimeForRunId("run-without-prefix"))
    }

    @Test
    fun shellToolPrefixesRunIdWithAlpine() = runBlocking {
        val settings = AppSettings(
            enabledRuntimeIds = setOf(LocalRuntimeId.Alpine),
            defaultRuntimeId = LocalRuntimeId.Alpine,
            alpineSetupCompleted = true,
        )
        val shellTool = RuntimeShellTool(router)

        val executeResult = shellTool.execute(settings, """{"command":"sleep 10","environment":"alpine"}""")
        val fetchResult = shellTool.fetch(settings, """{"run_id":"alpine:run-1"}""")
        val killResult = shellTool.kill(settings, """{"run_id":"alpine:run-1"}""")
        val executeJson = JSONObject(executeResult)
        val fetchJson = JSONObject(fetchResult)
        val killJson = JSONObject(killResult)

        assertTrue(executeJson.optBoolean("ok"))
        assertEquals("alpine:run-1", executeJson.optString("run_id"))
        assertEquals("alpine", executeJson.optString("runtime"))
        assertTrue(fetchJson.optBoolean("ok"))
        assertEquals("alpine:run-1", fetchJson.optString("run_id"))
        assertTrue(killJson.optBoolean("ok"))
        assertEquals("""{"command":"sleep 10"}""", alpineRuntime.lastExecuteArgumentsJson)
        assertEquals("""{"run_id":"run-1"}""", alpineRuntime.lastFetchArgumentsJson)
        assertEquals("""{"run_id":"run-1"}""", alpineRuntime.lastKillArgumentsJson)
    }
}

private class FakeRuntime(
    override val id: LocalRuntimeId,
) : LocalRuntime {
    var lastExecuteArgumentsJson: String = ""
    var lastFetchArgumentsJson: String = ""
    var lastKillArgumentsJson: String = ""

    override val displayName: String = id.displayName
    override val homeDirectory: String = "/home"
    override val workspaceRoot: String = "/workspace"
    override val managedCommandsDirectory: String = "/runs"

    override suspend fun inspectSetup(): LocalRuntimeSetupState =
        LocalRuntimeSetupState(id, LocalRuntimeIssue.Ready)

    override suspend fun execute(
        argumentsJson: String,
        onProgress: (suspend (String) -> Unit)?,
    ): String {
        lastExecuteArgumentsJson = argumentsJson
        return """{"ok":true,"run_id":"run-1"}"""
    }

    override suspend fun fetchExecution(argumentsJson: String): String {
        lastFetchArgumentsJson = argumentsJson
        return """{"ok":true,"run_id":"run-1"}"""
    }

    override suspend fun killExecution(argumentsJson: String): String {
        lastKillArgumentsJson = argumentsJson
        return """{"ok":true,"run_id":"run-1"}"""
    }

    override suspend fun killExecutionByRunId(runId: String, tailBytes: Int): String =
        """{"ok":true,"run_id":"$runId"}"""

    override suspend fun executeCommand(
        command: String,
        workingDirectory: String,
        awaitTimeoutMillis: Long,
    ): String = """{"ok":true}"""
}
