package kira.ditto.data

import kira.ditto.runtime.LocalRuntime
import kira.ditto.runtime.LocalRuntimeIssue
import kira.ditto.runtime.LocalRuntimeSetupState
import kira.ditto.runtime.RuntimeRouter
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillRuntimeMirrorTest {
    private val temporaryRoots = mutableListOf<File>()

    @After
    fun cleanUp() {
        temporaryRoots.forEach(File::deleteRecursively)
    }

    @Test
    fun syncMirrorsOnlyToAlpineWithoutGuestCommands() = runBlocking {
        val alpine = MirrorFakeRuntime(LocalRuntimeId.Alpine)
        val source = temporarySkillRoot("large-skill")
        repeat(200) { index -> source.resolve("references/$index.md").apply {
            parentFile?.mkdirs()
            writeText("reference $index")
        } }
        val skill = installedSkill("Large Skill", source)

        val paths = SkillRuntimeMirror(RuntimeRouter(alpine)).sync(listOf(skill))

        assertEquals(listOf(runtimeSkillPath(skill)), paths)
        assertEquals(1, alpine.mirrorCalls)
        assertEquals(0, alpine.commandCalls)
        assertEquals(setOf("large-skill"), alpine.mirroredDirectories.keys)
    }

    @Test
    fun syncCachesSuccessfulSignatureAndFailsOpenWhenMirrorIsUnavailable() = runBlocking {
        val alpine = MirrorFakeRuntime(LocalRuntimeId.Alpine)
        val skill = installedSkill("cached", temporarySkillRoot("cached"))
        val mirror = SkillRuntimeMirror(RuntimeRouter(alpine))

        assertTrue(mirror.sync(listOf(skill)).isNotEmpty())
        assertTrue(mirror.sync(listOf(skill)).isNotEmpty())
        assertEquals(1, alpine.mirrorCalls)

        val unavailableAlpine = MirrorFakeRuntime(LocalRuntimeId.Alpine, mirrorResult = false)
        val unavailableMirror = SkillRuntimeMirror(RuntimeRouter(unavailableAlpine))
        assertTrue(unavailableMirror.sync(listOf(skill)).isEmpty())
        assertEquals(1, unavailableAlpine.mirrorCalls)
    }

    private fun temporarySkillRoot(id: String): File =
        Files.createTempDirectory("aether-skill-$id").toFile().also { root ->
            temporaryRoots += root
            root.resolve("SKILL.md").writeText("---\nname: $id\ndescription: test\n---\n")
        }

    private fun installedSkill(id: String, root: File): InstalledSkill = InstalledSkill(
        id = id,
        name = id,
        description = "test",
        skillRootPath = root.absolutePath,
        skillMdPath = root.resolve("SKILL.md").absolutePath,
        checksumSha256 = "checksum-$id",
        updatedAtMillis = 1234L,
    )
}

private class MirrorFakeRuntime(
    override val id: LocalRuntimeId,
    private val mirrorResult: Boolean = true,
) : LocalRuntime {
    var mirrorCalls = 0
    var commandCalls = 0
    var mirroredDirectories: Map<String, File> = emptyMap()

    override val displayName: String = id.displayName
    override val homeDirectory: String = "/home"
    override val workspaceRoot: String = "/workspace"
    override val managedCommandsDirectory: String = "/runs"

    override suspend fun inspectSetup(): LocalRuntimeSetupState =
        LocalRuntimeSetupState(id, LocalRuntimeIssue.Ready)

    override suspend fun execute(argumentsJson: String, onProgress: (suspend (String) -> Unit)?): String =
        """{"ok":true}"""

    override suspend fun fetchExecution(argumentsJson: String): String = """{"ok":true}"""

    override suspend fun killExecution(argumentsJson: String): String = """{"ok":true}"""

    override suspend fun killExecutionByRunId(runId: String, tailBytes: Int): String = """{"ok":true}"""

    override suspend fun executeCommand(
        command: String,
        workingDirectory: String,
        awaitTimeoutMillis: Long,
    ): String {
        commandCalls += 1
        return """{"ok":true}"""
    }

    override suspend fun replaceHostDirectories(
        guestRootPath: String,
        signature: String,
        directories: Map<String, File>,
    ): Boolean {
        mirrorCalls += 1
        mirroredDirectories = directories
        return mirrorResult
    }
}
