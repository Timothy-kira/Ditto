package kira.ditto.ui

import kira.ditto.runtime.MultiplatformLocalRuntime
import kira.ditto.runtime.RuntimeFileSystem
import kira.ditto.runtime.RuntimeProcess
import kira.ditto.runtime.RuntimeProcessSpec
import kira.ditto.runtime.RuntimeSetupProgress
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SharedExtensionsSettingsTest {
    @Test
    fun preservesReadmeStructureAndResolvesRelativeAssets() {
        val markdown = """
            <h1>Package README</h1>
            <p><a href="https://example.com/project"><img src="/assets/status.svg" alt="Status"></a></p>
            <p>Use <strong>carefully</strong>.</p>
            <ul><li>First feature</li><li>Second feature</li></ul>
            <pre><code>pi install example</code></pre>
        """.trimIndent().sharedHtmlToMarkdown("https://pi.dev/packages/example")

        assertContains(markdown, "# Package README")
        assertContains(markdown, "[![Status](https://pi.dev/assets/status.svg)](https://example.com/project)")
        assertContains(markdown, "Use **carefully**.")
        assertContains(markdown, "- First feature")
        assertContains(markdown, "- Second feature")
        assertContains(markdown, "```\npi install example\n```")
    }

    @Test
    fun parsesBusyBoxUnzipListing() {
        val entries = parseSharedUnzipListing(
            """
                    142  2026-08-09 18:31   package.json
                      0  2026-08-09 18:31   src/
                   2048  2026-08-09 18:31   src/extension file.ts
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                SharedUnzipEntry(142, "package.json"),
                SharedUnzipEntry(0, "src/"),
                SharedUnzipEntry(2048, "src/extension file.ts"),
            ),
            entries,
        )
    }

    @Test
    fun rejectsUnexpectedUnzipListingLines() {
        assertFailsWith<IllegalArgumentException> {
            parseSharedUnzipListing("unexpected output")
        }
    }

    @Test
    fun omitsHostPeerDependenciesWhenInstallingExtensionDependencies() {
        assertEquals(
            "npm ci --omit=dev --omit=optional --legacy-peer-deps --no-audit --no-fund --prefer-offline",
            sharedExtensionNpmInstallCommand(hasLockfile = true),
        )
        assertEquals(
            "npm install --omit=dev --omit=optional --legacy-peer-deps --no-audit --no-fund --prefer-offline",
            sharedExtensionNpmInstallCommand(hasLockfile = false),
        )
    }

    @Test
    fun removesImportedExtensionWithoutRunningIt() = runTest {
        val runtime = ExtensionRemovalFakeRuntime()
        val installedPath = "/root/.aether/extensions/legacy-extension"
        runtime.files[installedPath] = byteArrayOf(1)

        removeSharedImportedExtension(runtime, installedPath)

        assertFalse(installedPath in runtime.files)
        assertEquals(
            listOf(installedPath to true),
            runtime.removals.filter { it.second },
        )
        assertTrue(
            "/root/.aether/.removed-preinstalled-extensions/legacy-extension" in runtime.files,
        )
    }
}

private class ExtensionRemovalFakeRuntime : MultiplatformLocalRuntime {
    override val homeDirectory = "/root"
    override val workspaceRoot = "/workspace"
    val files = mutableMapOf<String, ByteArray>()
    val removals = mutableListOf<Pair<String, Boolean>>()

    override val fileSystem: RuntimeFileSystem = object : RuntimeFileSystem {
        override suspend fun exists(path: String): Boolean = path in files
        override suspend fun createDirectories(path: String) = Unit
        override suspend fun read(path: String): ByteArray = files.getValue(path)
        override suspend fun write(path: String, content: ByteArray, executable: Boolean) {
            files[path] = content
        }
        override suspend fun remove(path: String, recursive: Boolean) {
            removals += path to recursive
            files.remove(path)
        }
        override suspend fun bindHostDirectory(hostPath: String, guestPath: String, readOnly: Boolean) = Unit
    }

    override suspend fun initialize(onProgress: (RuntimeSetupProgress) -> Unit) = Unit
    override suspend fun startProcess(spec: RuntimeProcessSpec): RuntimeProcess =
        error("Removing an imported extension must not start a runtime process")
}
