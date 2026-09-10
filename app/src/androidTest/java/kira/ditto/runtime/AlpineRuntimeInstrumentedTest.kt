package kira.ditto.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kira.ditto.data.pi.PiKernelBridge
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlpineRuntimeInstrumentedTest {
    @Test
    fun memoryUpgradePreservesHistoryAndDeploysCurrentSidecar() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = java.io.File(context.filesDir, "runtimes/alpine/rootfs/root")
        val memory = java.io.File(root, ".kimi-code/session-memory").apply { mkdirs() }
        val sentinel = java.io.File.createTempFile("upgrade-regression-", ".txt", memory)
        sentinel.writeText("durable-history-must-survive")
        try {
            val runtime = AlpineRuntime(context)
            val setup = runtime.initialize()
            assertEquals(setup.detail, LocalRuntimeIssue.Ready, setup.issue)
            assertTrue("Upgrade removed durable history", sentinel.isFile)
            assertEquals("durable-history-must-survive", sentinel.readText())
            for (name in listOf("index.mjs", "cards.mjs", "mcp.mjs", "selftest.mjs", "everme.mjs")) {
                val installed = java.io.File(root, ".kimi-code-mobile/node_modules/@aether/session-memory/$name")
                val expected = context.assets.open("runtimes/session-memory/$name").use { it.readBytes() }
                assertTrue("Stale sidecar: $name", installed.readBytes().contentEquals(expected))
            }
            val main = java.io.File(root, ".kimi-code-mobile/node_modules/@moonshot-ai/kimi-code/dist/main.mjs")
            if (main.isFile) {
                val text = main.readText()
                assertTrue("foldForModel must stay mounted", text.contains("__aetherFoldForModel(messages)"))
                assertTrue(
                    "shouldCompact must not stay disabled",
                    !text.contains("shouldCompact(usedSize) {\n\t\t\treturn false;"),
                )
                assertTrue("/compact must reach deps.agent.compact", text.contains("deps.agent.compact"))
                assertTrue(
                    "/compact must wait and hide the English ACK",
                    text.contains("AETHER_COMPACT_DONE"),
                )
            }
            val result = JSONObject(runtime.executeCommand(
                command = "node /root/.kimi-code-mobile/node_modules/@aether/session-memory/selftest.mjs",
                workingDirectory = runtime.homeDirectory,
                awaitTimeoutMillis = 30_000L,
            ))
            assertTrue(result.optString("errmsg"), result.optBoolean("ok"))
            assertTrue(result.optString("stdout"), result.optString("stdout").contains("session-memory selftest ok"))
        } finally {
            sentinel.delete()
        }
    }

    @Test
    fun alpineRuntimeStartsShellFromAppProcess() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = AlpineRuntime(context)

        val setup = runtime.initialize()
        assertEquals(setup.detail, LocalRuntimeIssue.Ready, setup.issue)

        val result = JSONObject(
            runtime.executeCommand(
                command = "echo AETHER_ALPINE_APP_PROCESS_OK; cat /etc/alpine-release; uname -m; pwd",
                workingDirectory = runtime.homeDirectory,
                awaitTimeoutMillis = 30_000L,
            )
        )

        assertTrue(result.optString("errmsg"), result.optBoolean("ok"))
        val stdout = result.optString("stdout")
        assertTrue(stdout, stdout.contains("AETHER_ALPINE_APP_PROCESS_OK"))
        assertTrue(
            stdout,
            stdout.contains("aarch64") || stdout.contains("x86_64"),
        )
        assertTrue(stdout, stdout.contains("/root"))
    }

    @Test
    fun pythonPackageProfileInstallsAndRuns() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = AlpineRuntime(context)

        val setup = runtime.initialize()
        assertEquals(setup.detail, LocalRuntimeIssue.Ready, setup.issue)

        val profile = runtime.installPackageProfile("python")
        assertEquals(profile.detail, LocalRuntimeIssue.Ready, profile.issue)

        val result = JSONObject(
            runtime.executeCommand(
                command = "python3 --version && python3 - <<'PY'\nprint('AETHER_ALPINE_PYTHON_OK')\nPY",
                workingDirectory = runtime.homeDirectory,
                awaitTimeoutMillis = 30_000L,
            )
        )

        assertTrue(result.optString("errmsg"), result.optBoolean("ok"))
        val stdout = result.optString("stdout")
        assertTrue(stdout, stdout.contains("Python"))
        assertTrue(stdout, stdout.contains("AETHER_ALPINE_PYTHON_OK"))
    }

    @Test
    fun piBridgeStartsWithSupportedNodeAndReportsPinnedVersions() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bridge = PiKernelBridge(AlpineRuntime(context))

        try {
            val ping = bridge.ping()
            assertEquals("2.0.0-alpha.0", ping.getString("bridge_version"))
            assertEquals("0.83.0", ping.getString("pi_ai_version"))
            assertEquals("0.83.0", ping.getString("pi_agent_core_version"))
            val nodeVersion = ping.getString("node_version").removePrefix("v")
            val major = nodeVersion.substringBefore('.').toInt()
            val minor = nodeVersion.substringAfter('.').substringBefore('.').toInt()
            assertTrue(nodeVersion, major > 22 || major == 22 && minor >= 19)
        } finally {
            bridge.stop()
        }
    }
}
