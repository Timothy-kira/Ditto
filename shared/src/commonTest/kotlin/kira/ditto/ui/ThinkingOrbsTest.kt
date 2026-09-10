package kira.ditto.ui

import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThinkingOrbsTest {
    @Test
    fun orbHashIsDeterministicAndUnitRanged() {
        val samples = listOf(0.0 to 1.7, 3.0 to 5.2, 11.0 to 8.9, -4.5 to 2.3, 123.25 to 0.5)
        for ((x, y) in samples) {
            val first = orbHash(x, y)
            assertEquals(first, orbHash(x, y))
            assertTrue(first >= 0.0 && first < 1.0, "hash out of range: $first")
        }
        // Distinct inputs spread to distinct values for the orbit lanes.
        val lanes = (0 until 12).map { orbHash(it.toDouble(), 1.7) }.toSet()
        assertTrue(lanes.size >= 10, "orbit lane hash collapsed: $lanes")
    }

    @Test
    fun projectionWithoutRotationIsAxisAligned() {
        val projection = OrbProjection(yaw = 0.0, pitch = 0.0, centerX = 32.0, centerY = 32.0, scale = 2.0)
        val (x, y, z) = projection.project(3.0, 4.0, 5.0)
        assertEquals(38.0, x, 1e-9)
        assertEquals(24.0, y, 1e-9)
        assertEquals(5.0, z, 1e-9)
    }

    @Test
    fun projectionYawRotatesAroundYAxis() {
        val projection = OrbProjection(yaw = PI / 2.0, pitch = 0.0, centerX = 0.0, centerY = 0.0, scale = 1.0)
        // Right-handed yaw=90° maps +x onto -z (depth) and +z onto +x.
        val (x, _, z) = projection.project(1.0, 0.0, 0.0)
        assertEquals(0.0, x, 1e-9)
        assertEquals(-1.0, z, 1e-9)
        val (x2, _, z2) = projection.project(0.0, 0.0, 1.0)
        assertEquals(1.0, x2, 1e-9)
        assertEquals(0.0, z2, 1e-9)
    }

    @Test
    fun projectionPitchRotatesAroundXAxis() {
        val projection = OrbProjection(yaw = 0.0, pitch = PI / 2.0, centerX = 0.0, centerY = 0.0, scale = 1.0)
        // pitch=90° maps +y onto +z (depth) and +z onto screen-down (+y).
        val (_, y, z) = projection.project(0.0, 1.0, 0.0)
        assertEquals(0.0, y, 1e-9)
        assertEquals(1.0, z, 1e-9)
        val (_, y2, z2) = projection.project(0.0, 0.0, 1.0)
        assertEquals(1.0, y2, 1e-9)
        assertEquals(0.0, z2, 1e-9)
    }

    @Test
    fun depthFactorSpansZeroToOne() {
        assertEquals(0.0, orbDepthFactor(-10.0, 10.0), 1e-9)
        assertEquals(0.5, orbDepthFactor(0.0, 10.0), 1e-9)
        assertEquals(1.0, orbDepthFactor(10.0, 10.0), 1e-9)
    }

    @Test
    fun fibonacciSpherePointsAreDeterministicAndUnitLength() {
        val first = fibonacciSpherePoint(7, 150)
        assertEquals(first, fibonacciSpherePoint(7, 150))
        val (x, y, z) = first
        assertEquals(1.0, sqrt(x * x + y * y + z * z), 1e-9)
        // Points march monotonically from the north to the south pole.
        assertTrue(fibonacciSpherePoint(0, 150).second > 0.9)
        assertTrue(fibonacciSpherePoint(149, 150).second < -0.9)
    }

    @Test
    fun orbitsFrameIsDeterministicAndWellFormed() {
        val frame = orbitsFrame(sizePx = 96.0, seconds = 3.25, orbitCount = 7, ghostCount = 24, particleCount = 3)
        assertEquals(frame, orbitsFrame(sizePx = 96.0, seconds = 3.25, orbitCount = 7, ghostCount = 24, particleCount = 3))
        assertEquals(7 * (24 + 3), frame.size)
        assertSortedFilteredBounded(frame, sizePx = 96.0)
        // Time moves the particles.
        val later = orbitsFrame(sizePx = 96.0, seconds = 3.75, orbitCount = 7, ghostCount = 24, particleCount = 3)
        assertTrue(frame != later)
    }

    @Test
    fun globeFrameIsDeterministicAndWellFormed() {
        val frame = globeFrame(sizePx = 96.0, seconds = 1.5, latRings = 8, lonDensity = 22)
        assertEquals(frame, globeFrame(sizePx = 96.0, seconds = 1.5, latRings = 8, lonDensity = 22))
        assertTrue(frame.size > 50, "globe should produce a dense dot lattice, got ${frame.size}")
        assertSortedFilteredBounded(frame, sizePx = 96.0)
        // The scan meridian brightens part of the sphere above the dim baseline
        // (at this coarse lattice the brightest dot reaches alpha ≈ 0.74).
        assertTrue(frame.any { it.alpha > 0.65 }, "scan glow missing")
        assertTrue(frame.any { it.alpha < 0.6 }, "dim baseline missing")
    }

    @Test
    fun remainingOrbModesProduceDeterministicDots() {
        val size = 64.0
        val seconds = 2.4
        val frames = listOf(
            solvingFrame(size, seconds, latRings = 6, lonDensity = 16),
            listeningFrame(size, seconds),
            connectingFrame(size, seconds),
            weavingFrame(size, seconds),
            composingFrame(size, seconds),
            breathingFrame(size, seconds),
            shapingFrame(size, seconds),
        )
        frames.forEach { frame ->
            assertTrue(frame.isNotEmpty())
            assertTrue(frame.zipWithNext().all { (a, b) -> a.z <= b.z })
        }
        assertEquals(weavingFrame(size, seconds), weavingFrame(size, seconds))
        assertTrue(shapingFrame(size, 0.1) != shapingFrame(size, 1.4))
    }

    private fun assertSortedFilteredBounded(frame: List<OrbDot>, sizePx: Double) {
        assertTrue(frame.isNotEmpty())
        assertTrue(frame.zipWithNext().all { (a, b) -> a.z <= b.z }, "dots must be z-sorted")
        for (dot in frame) {
            assertTrue(dot.alpha >= OrbMinAlpha, "dim dot survived finalize: $dot")
            assertTrue(dot.alpha <= 1.0, "alpha out of range: $dot")
            assertTrue(dot.r >= OrbMinRadius, "radius floor violated: $dot")
            assertTrue(dot.x >= -1.0 && dot.x <= sizePx + 1.0, "x escapes canvas: $dot")
            assertTrue(dot.y >= -1.0 && dot.y <= sizePx + 1.0, "y escapes canvas: $dot")
            assertTrue(dot.white in -0.2..1.0, "white out of range: $dot")
        }
    }
}
