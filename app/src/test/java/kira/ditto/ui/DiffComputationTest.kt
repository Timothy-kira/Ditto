package kira.ditto.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DiffComputationTest {
    @Test
    fun identicalInputsYieldOnlyContextLines() {
        val lines = computeDiffLines("a\nb\nc", "a\nb\nc")
        assertEquals(
            listOf(
                DiffLine(DiffLineKind.Context, "a"),
                DiffLine(DiffLineKind.Context, "b"),
                DiffLine(DiffLineKind.Context, "c"),
            ),
            lines,
        )
    }

    @Test
    fun changedMiddleLineSplitsIntoRemoveAndAdd() {
        val lines = computeDiffLines("a\nb\nc", "a\nx\nc")
        assertEquals(
            listOf(
                DiffLine(DiffLineKind.Context, "a"),
                DiffLine(DiffLineKind.Removed, "b"),
                DiffLine(DiffLineKind.Added, "x"),
                DiffLine(DiffLineKind.Context, "c"),
            ),
            lines,
        )
    }

    @Test
    fun insertionKeepsSurroundingContext() {
        val lines = computeDiffLines("a\nc", "a\nb\nc")
        assertEquals(
            listOf(
                DiffLine(DiffLineKind.Context, "a"),
                DiffLine(DiffLineKind.Added, "b"),
                DiffLine(DiffLineKind.Context, "c"),
            ),
            lines,
        )
    }

    @Test
    fun trailingNewlineDoesNotCreatePhantomLine() {
        val lines = computeDiffLines("a\n", "a\nb\n")
        assertEquals(
            listOf(
                DiffLine(DiffLineKind.Context, "a"),
                DiffLine(DiffLineKind.Added, "b"),
            ),
            lines,
        )
    }

    @Test
    fun emptyOldTextMarksEverythingAdded() {
        val lines = computeDiffLines("", "a\nb")
        assertEquals(
            listOf(
                DiffLine(DiffLineKind.Added, "a"),
                DiffLine(DiffLineKind.Added, "b"),
            ),
            lines,
        )
    }

    @Test
    fun emptyNewTextMarksEverythingRemoved() {
        val lines = computeDiffLines("a\nb", "")
        assertEquals(
            listOf(
                DiffLine(DiffLineKind.Removed, "a"),
                DiffLine(DiffLineKind.Removed, "b"),
            ),
            lines,
        )
    }

    @Test
    fun compactTokenCountFormatsThousands() {
        assertEquals("0", formatCompactTokenCount(0L))
        assertEquals("999", formatCompactTokenCount(999L))
        assertEquals("1k", formatCompactTokenCount(1_000L))
        assertEquals("128k", formatCompactTokenCount(128_000L))
        assertEquals("127.5k", formatCompactTokenCount(127_500L))
        assertEquals("2M", formatCompactTokenCount(2_000_000L))
    }
}
