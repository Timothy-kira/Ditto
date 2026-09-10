package kira.ditto.data.kimi

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KimiAcpFileBridgeTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var workspace: File
    private lateinit var bridge: KimiAcpFileBridge

    private fun hostFor(guest: String): File =
        File(workspace, guest.removePrefix("/workspace").trimStart('/')).canonicalFile

    @Before
    fun setUp() {
        workspace = temporaryFolder.newFolder("workspace")
        bridge = KimiAcpFileBridge(resolveHostFile = ::hostFor)
        bridge.registerSession("s1", "/workspace")
    }

    @Test
    fun readTextFileReadsUtf8AndStripsBom() {
        val target = File(workspace, "notes.txt")
        target.writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "你好\nsecond".toByteArray(Charsets.UTF_8))

        val result = bridge.readTextFile("s1", "/workspace/notes.txt")

        assertEquals("你好\nsecond", result.getString("content"))
    }

    @Test
    fun readTextFileAppliesLineWindowAndLimit() {
        val target = File(workspace, "lines.txt")
        target.writeText((1..10).joinToString("\n") { "line-$it" })

        val result = bridge.readTextFile("s1", "/workspace/lines.txt", line = 3, limit = 2)

        assertEquals("line-3\nline-4", result.getString("content"))
    }

    @Test
    fun readTextFileTruncatesBeyondByteCap() {
        val small = KimiAcpFileBridge(
            resolveHostFile = ::hostFor,
            maxReadBytes = 8,
        )
        small.registerSession("s1", "/workspace")
        val target = File(workspace, "big.txt")
        target.writeText("0123456789abcdef")

        val result = small.readTextFile("s1", "/workspace/big.txt")

        assertTrue(result.getString("content").length <= 9)
        assertTrue(result.getString("content").startsWith("0123"))
    }

    @Test
    fun readTextFileRejectsPathOutsideSessionRoots() {
        assertFails("outside session root") {
            bridge.readTextFile("s1", "/etc/passwd")
        }
        assertFails("dot-dot escape") {
            bridge.readTextFile("s1", "/workspace/../secret.txt")
        }
        assertFails("unknown session") {
            bridge.readTextFile("nope", "/workspace/notes.txt")
        }
    }

    @Test
    fun writeTextFileCreatesAndOverwritesAtomically() {
        val target = File(workspace, "dir/out.txt")
        bridge.writeTextFile("s1", "/workspace/dir/out.txt", "v1")
        assertEquals("v1", target.readText())

        bridge.writeTextFile("s1", "/workspace/dir/out.txt", "v2-更长")
        assertEquals("v2-更长", target.readText())

        assertTrue(
            "no staging files left behind",
            File(workspace, "dir").listFiles()?.none { it.name.startsWith(".aether-acp-") } == true,
        )
    }

    @Test
    fun writeTextFileRejectsEscapeAndKeepsOriginal() {
        val target = File(workspace, "keep.txt")
        target.writeText("original")

        assertFails("escape") {
            bridge.writeTextFile("s1", "/workspace/../../tmp/evil.txt", "bad")
        }
        assertEquals("original", target.readText())
    }

    @Test
    fun kimiSessionPlanDocumentIsAllowedAndEtcIsStillRejected() {
        val sessions = temporaryFolder.newFolder("kimi-sessions")
        val fullBridge = KimiAcpFileBridge(
            resolveHostFile = { guest ->
                when {
                    guest.startsWith(KimiCodeSessionsGuestRoot) -> File(
                        sessions,
                        guest.removePrefix(KimiCodeSessionsGuestRoot).trimStart('/'),
                    ).canonicalFile
                    else -> hostFor(guest)
                }
            },
        )
        fullBridge.registerSession(
            sessionId = "s1",
            cwd = "/workspace",
            additionalDirectories = mergeKimiAcpAdditionalDirectories(emptyList()),
        )
        val planGuest =
            "$KimiCodeSessionsGuestRoot/wd_workspace_1/session_abc/agents/main/plans/kid-flash.md"
        fullBridge.writeTextFile("s1", planGuest, "# Plan\n\nDo the thing.")
        assertEquals(
            "# Plan\n\nDo the thing.",
            fullBridge.readTextFile("s1", planGuest).getString("content"),
        )
        assertTrue(isKimiSessionPlanDocumentPath(planGuest))
        assertFails("etc passwd") {
            fullBridge.readTextFile("s1", "/etc/passwd")
        }
        assertFails("dot-dot escape from workspace") {
            fullBridge.readTextFile("s1", "/workspace/../secret.txt")
        }
        assertFails("dot-dot escape from sessions root") {
            fullBridge.readTextFile(
                "s1",
                "$KimiCodeSessionsGuestRoot/../.ssh/id_rsa",
            )
        }
    }

    @Test
    fun mergeAlwaysIncludesKimiSessionsRoot() {
        assertEquals(
            listOf(KimiCodeSessionsGuestRoot),
            mergeKimiAcpAdditionalDirectories(emptyList()),
        )
        assertEquals(
            listOf(KimiCodeSessionsGuestRoot, "/extra"),
            mergeKimiAcpAdditionalDirectories(listOf("/extra")),
        )
    }

    @Test
    fun additionalDirectoriesExtendAllowedRoots() {
        val extra = temporaryFolder.newFolder("extra")
        val fullBridge = KimiAcpFileBridge(
            resolveHostFile = { guest ->
                when {
                    guest.startsWith("/extra") -> File(extra, guest.removePrefix("/extra")).canonicalFile
                    else -> hostFor(guest)
                }
            },
        )
        fullBridge.registerSession("s1", "/workspace", listOf("/extra"))
        File(extra, "x.txt").writeText("extra-content")

        assertEquals("extra-content", fullBridge.readTextFile("s1", "/extra/x.txt").getString("content"))
    }

    private fun assertFails(reason: String, block: () -> Unit) {
        try {
            block()
            fail("Expected AcpFsException for $reason")
        } catch (expected: AcpFsException) {
            // expected
        }
    }
}
