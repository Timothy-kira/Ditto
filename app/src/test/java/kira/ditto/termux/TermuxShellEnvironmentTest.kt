package kira.ditto.termux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxShellEnvironmentTest {
    @Test
    fun dispatchEnvironmentIncludesTermuxAndCommonUserPathsWithoutStartupCapture() {
        val script = StringBuilder().also { builder ->
            appendTermuxShellEnvironment(
                builder = builder,
                includeShellStartupPath = false,
            )
        }.toString()

        assertTrue(script.contains("export PREFIX='${TermuxContract.PrefixDirectory}'"))
        assertTrue(script.contains("\$PREFIX/bin"))
        assertTrue(script.contains("\$HOME/.local/bin"))
        assertTrue(script.contains("\$HOME/.cargo/bin"))
        assertFalse(script.contains("aether_capture_shell_path"))
    }

    @Test
    fun managedRunnerEnvironmentCapturesLoginAndInteractiveShellPath() {
        val script = StringBuilder().also { builder ->
            appendTermuxShellEnvironment(
                builder = builder,
                includeShellStartupPath = true,
            )
        }.toString()

        assertTrue(script.contains("timeout 3 \"\$PREFIX/bin/bash\" \"\$mode\""))
        assertTrue(script.contains("aether_capture_shell_path -lc"))
        assertTrue(script.contains("aether_capture_shell_path -ic"))
    }

    @Test
    fun onlyAetherWorkspaceDirectoriesArePreparedAutomatically() {
        assertTrue(
            shouldPrepareTermuxWorkingDirectory("${TermuxContract.HomeDirectory}/.aether/workspace")
        )
        assertTrue(
            shouldPrepareTermuxWorkingDirectory("${TermuxContract.HomeDirectory}/.aether/workspaces/session")
        )
        assertFalse(shouldPrepareTermuxWorkingDirectory(TermuxContract.HomeDirectory))
        assertFalse(shouldPrepareTermuxWorkingDirectory("${TermuxContract.HomeDirectory}/projects/demo"))
    }
}
