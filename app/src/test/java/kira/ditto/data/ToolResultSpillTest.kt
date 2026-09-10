package kira.ditto.data

import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolResultSpillTest {
    private val dir = Files.createTempDirectory("tool-spills").toFile()

    @After
    fun tearDown() {
        ToolResultSpill.detach()
        dir.deleteRecursively()
    }

    @Test
    fun shortResultsAreUntouched() {
        ToolResultSpill.attach(dir)
        assertEquals("short", ToolResultSpill.capWithSpill("short", 100))
        assertEquals(0, dir.listFiles()?.size ?: 0)
    }

    @Test
    fun overflowIsFetchableRatherThanLost() {
        ToolResultSpill.attach(dir)
        val text = (0 until 5_000).joinToString("") { "abcdefghij"[it % 10].toString() }
        val visible = ToolResultSpill.capWithSpill(text, 1_000)

        assertTrue(visible.length <= 1_200)
        val sha = Regex("\\[spill:([0-9a-f]{64}) ").find(visible)?.groupValues?.get(1)
        requireNotNull(sha) { "the marker must name the sha the model has to pass back" }
        val offset = Regex("offset=(\\d+)").find(visible)!!.groupValues[1].toInt()

        // The whole point: reading from the stated offset continues exactly where the visible part
        // stopped, with nothing skipped and nothing repeated.
        val head = visible.substringBefore("\n[spill:")
        val rest = ToolResultSpill.read(sha, head.length, 10_000)
        requireNotNull(rest)
        assertEquals(text, head + rest)
        assertTrue(offset > 0)
    }

    @Test
    fun theSameContentSpillsOnce() {
        ToolResultSpill.attach(dir)
        val text = "x".repeat(4_000)
        ToolResultSpill.capWithSpill(text, 500)
        ToolResultSpill.capWithSpill(text, 500)
        // Content-addressed: a page read twice must not cost two files, which is also what makes it
        // safe never to delete one on a single result's behalf.
        assertEquals(1, dir.listFiles()?.size ?: 0)
    }

    @Test
    fun withoutAStoreItDegradesToTruncation() {
        // The configuration during unit tests and before startup finishes. A shortened result is a
        // degraded answer; a thrown one is a broken turn.
        ToolResultSpill.detach()
        val text = "y".repeat(4_000)
        val visible = ToolResultSpill.capWithSpill(text, 500)
        assertEquals(500, visible.length)
        assertFalse(visible.contains("[spill:"))
    }

    @Test
    fun unknownOrMalformedShaReadsAsMissing() {
        ToolResultSpill.attach(dir)
        assertNull(ToolResultSpill.read("0".repeat(64), 0, 100))
        // Not a sha at all: refused before it can become a path.
        assertNull(ToolResultSpill.read("../../etc/passwd", 0, 100))
    }
}
