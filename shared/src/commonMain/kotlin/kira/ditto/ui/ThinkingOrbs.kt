package kira.ditto.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kira.ditto.platform.LocalReduceMotion
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Compose port of the `thinking-orbs` canvas engine (orbits/globe modes).
 *
 * The engine is fully deterministic: orbit parameters come from a stable hash
 * ([orbHash]) instead of randomness, so every frame is a pure function of
 * (size, mode, seconds) and recomposition never reshuffles the layout. Each
 * frame recomputes a few hundred dots and paints them far-to-near (painter's
 * algorithm, sorted by z); dots below [OrbMinAlpha] are dropped.
 */

enum class ThinkingOrbMode(
    /** Base clock multiplier from the 64px preset of the upstream engine. */
    internal val baseSpeed: Double,
) {
    /** "working": tilted orbit lanes with bright particles. */
    Working(baseSpeed = 1.885),

    /** "searching": lat/lon dot sphere with a sweeping scan meridian. */
    Searching(baseSpeed = 2.015),

    /** "solving": latitude bands scramble, then click back into place. */
    Solving(baseSpeed = 1.62),

    /** "listening": a waveform rolls through concentric rings. */
    Listening(baseSpeed = 1.12),

    /** "connecting": a constellation wires itself together. */
    Connecting(baseSpeed = 1.38),

    /** "weaving": three strands plait around the sphere. */
    Weaving(baseSpeed = 1.74),

    /** "composing": an undulating multi-band sash. */
    Composing(baseSpeed = 1.28),

    /** "breathing": a ring slowly morphing. */
    Breathing(baseSpeed = 0.62),

    /** "shaping": dotted outline morphing circle → triangle → square. */
    Shaping(baseSpeed = 0.94),

    /** Alias of [Working] kept for existing call sites. */
    Orbits(baseSpeed = 1.885),

    /** Alias of [Searching] kept for existing call sites. */
    Globe(baseSpeed = 2.015),
}

/** One drawable dot in canvas pixel space (already projected and z-sorted). */
internal data class OrbDot(
    val x: Double,
    val y: Double,
    val z: Double,
    val r: Double,
    /** 0 = darkest ink, 1 = lightest ink; mapped through the theme in the painter. */
    val white: Double,
    val alpha: Double,
)

internal const val OrbMinAlpha = 0.02
internal const val OrbMinRadius = 0.3
private const val OrbRadiusScaleReference = 300.0
private const val OrbRadiusScalePow = 0.6

/** Stable 2D hash in [0, 1); mirrors the engine's `E(x, y)`. */
internal fun orbHash(x: Double, y: Double): Double {
    val value = sin(x * 12.9898 + y * 78.233) * 43758.5453
    return value - floor(value)
}

/** Depth factor in [0, 1]: 0 at the far pole, 1 at the near pole. */
internal fun orbDepthFactor(z: Double, radius: Double): Double = (z / radius + 1.0) / 2.0

/** Evenly spaced point on the unit sphere (golden-angle spiral); the engine's `J(i, n)`. */
internal fun fibonacciSpherePoint(index: Int, count: Int): Triple<Double, Double, Double> {
    val golden = PI * (3.0 - sqrt(5.0))
    val y = 1.0 - 2.0 * (index + 0.5) / count
    val radius = sqrt(1.0 - y * y)
    val theta = index * golden
    return Triple(radius * cos(theta), y, radius * sin(theta))
}

/** Shortest signed angular distance a-b in (-π, π]; the engine's `et(a, b)`. */
private fun angularDistance(a: Double, b: Double): Double = atan2(sin(a - b), cos(a - b))

/** JS `Math.round` (half up), used where the engine rounds dot counts. */
private fun jsRound(value: Double): Int = floor(value + 0.5).toInt()

/** Dot radius scale: `(sizePx / 300)^0.6` — the engine's `$(n, p)`. */
private fun orbRadiusScale(sizePx: Double): Double =
    Math.pow(sizePx / OrbRadiusScaleReference, OrbRadiusScalePow)

/**
 * Camera projection: rotate around the Y axis by [yaw], then around the X axis
 * by [pitch]; no perspective division. Returns (screenX, screenY, depthZ) with
 * the depth left unscaled (unit-space), exactly like the engine's `_`.
 */
internal class OrbProjection(
    yaw: Double,
    pitch: Double,
    private val centerX: Double,
    private val centerY: Double,
    private val scale: Double,
) {
    private val sinPitch = sin(pitch)
    private val cosPitch = cos(pitch)
    private val sinYaw = sin(yaw)
    private val cosYaw = cos(yaw)

    fun project(x: Double, y: Double, z: Double): Triple<Double, Double, Double> {
        val e = x * cosYaw + z * sinYaw
        val l = -x * sinYaw + z * cosYaw
        val r = y * cosPitch - l * sinPitch
        val w = y * sinPitch + l * cosPitch
        return Triple(centerX + e * scale, centerY - r * scale, w)
    }
}

private fun List<OrbDot>.finalizeFrame(): List<OrbDot> = filter { it.alpha >= OrbMinAlpha }
    .map { if (it.r < OrbMinRadius) it.copy(r = OrbMinRadius) else it }
    .sortedBy { it.z }

/**
 * Orbits mode frame: [orbitCount] randomly tilted orbit lanes, each with
 * [ghostCount] faint lane dots and [particleCount] bright particles circling
 * at their own angular velocity. Pure function of (sizePx, seconds).
 */
internal fun orbitsFrame(
    sizePx: Double,
    seconds: Double,
    orbitCount: Int,
    ghostCount: Int,
    particleCount: Int,
): List<OrbDot> {
    if (sizePx <= 0.0 || orbitCount <= 0) return emptyList()
    val center = sizePx / 2.0
    val sphereRadius = sizePx / 2.0 * 0.82
    val camera = OrbProjection(yaw = 0.12 * seconds, pitch = 0.3, centerX = center, centerY = center, scale = 1.0)
    val radiusScale = orbRadiusScale(sizePx)
    val dots = ArrayList<OrbDot>(orbitCount * (ghostCount + particleCount))
    for (orbit in 0 until orbitCount) {
        val laneRandom = orbHash(orbit.toDouble(), 1.7)
        val phaseRandom = orbHash(orbit.toDouble(), 5.2)
        val speedRandom = orbHash(orbit.toDouble(), 8.9)
        val orbitRadius = sphereRadius * (0.45 + 0.52 * laneRandom)
        val normalAngle = laneRandom * 2.0 * PI
        val polar = acos(2.0 * phaseRandom - 1.0)
        val nx = sin(polar) * cos(normalAngle)
        val ny = cos(polar)
        val nz = sin(polar) * sin(normalAngle)
        // Orthonormal basis (u, w) spanning the orbit plane; v = (0,0,0)-ish z axis term.
        var ux = -ny
        var uy = nx
        val uz = 0.0
        val norm = max(1e-6, sqrt(ux * ux + uy * uy))
        ux /= norm
        uy /= norm
        val wx = ny * uz - nz * uy
        val wy = nz * ux - nx * uz
        val wz = nx * uy - ny * ux
        val angularVelocity = (0.25 + 0.55 * speedRandom) * if (speedRandom > 0.5) 1.0 else -1.0
        fun lanePoint(theta: Double): Triple<Double, Double, Double> {
            val cosTheta = cos(theta)
            val sinTheta = sin(theta)
            return camera.project(
                (ux * cosTheta + wx * sinTheta) * orbitRadius,
                (uy * cosTheta + wy * sinTheta) * orbitRadius,
                (uz * cosTheta + wz * sinTheta) * orbitRadius,
            )
        }
        for (ghost in 0 until ghostCount) {
            val theta = ghost.toDouble() / ghostCount.coerceAtLeast(1) * 2.0 * PI
            val (x, y, z) = lanePoint(theta)
            val depth = orbDepthFactor(z, orbitRadius)
            dots += OrbDot(
                x = x,
                y = y,
                z = z,
                r = 0.9 * radiusScale,
                white = 0.72,
                alpha = 0.5 * (0.4 + 0.6 * depth),
            )
        }
        for (particle in 0 until particleCount) {
            val theta = seconds * angularVelocity + particle.toDouble() / particleCount.coerceAtLeast(1) * 2.0 * PI + phaseRandom * 6.0
            val (x, y, z) = lanePoint(theta)
            val depth = orbDepthFactor(z, orbitRadius)
            dots += OrbDot(
                x = x,
                y = y,
                z = z,
                r = (1.2 + 1.6 * depth) * radiusScale,
                white = 0.3 - 0.22 * depth,
                alpha = 1.0,
            )
        }
    }
    return dots.finalizeFrame()
}

/**
 * Globe mode frame: a lat/lon dot sphere with a scan meridian sweeping at
 * [scanMultiplier] speed; dots near the meridian brighten and grow.
 * Defaults match the engine's resolved 64px preset.
 */
internal fun globeFrame(
    sizePx: Double,
    seconds: Double,
    latRings: Int,
    lonDensity: Int,
    scanMultiplier: Double = 4.08,
    dimBase: Double = 0.45,
): List<OrbDot> {
    if (sizePx <= 0.0 || latRings <= 0) return emptyList()
    val center = sizePx / 2.0
    val sphereRadius = sizePx / 2.0 * 0.82
    val pitch = 0.4 + 0.06 * sin(0.35 * seconds)
    val camera = OrbProjection(yaw = 0.5 * seconds, pitch = pitch, centerX = center, centerY = center, scale = sphereRadius)
    val scanLongitude = seconds * (0.5 + 1.2 * scanMultiplier)
    val radiusScale = orbRadiusScale(sizePx)
    val dots = ArrayList<OrbDot>(latRings * lonDensity)
    for (ring in 0..latRings) {
        val latitude = -PI / 2.0 + ring.toDouble() / latRings * PI
        val ringRadius = cos(latitude)
        val ringY = sin(latitude)
        val ringDots = max(1, jsRound(kotlin.math.abs(ringRadius) * lonDensity))
        for (index in 0 until ringDots) {
            val longitude = index.toDouble() / ringDots * 2.0 * PI
            val (x, y, z) = camera.project(
                ringRadius * cos(longitude),
                ringY,
                ringRadius * sin(longitude),
            )
            val depth = (z + 1.0) / 2.0
            val scanDistance = angularDistance(longitude + seconds * 0.5, scanLongitude)
            val scanGlow = exp(-(scanDistance * scanDistance) / 0.18) * max(0.0, z)
            dots += OrbDot(
                x = x,
                y = y,
                z = z,
                r = (0.69 + 1.955 * depth + 1.0 * scanGlow) * radiusScale,
                white = 0.62 - 0.54 * depth,
                alpha = dimBase + (1.0 - dimBase) * min(1.0, scanGlow),
            )
        }
    }
    return dots.finalizeFrame()
}

internal fun solvingFrame(
    sizePx: Double,
    seconds: Double,
    latRings: Int,
    lonDensity: Int,
): List<OrbDot> {
    if (sizePx <= 0.0) return emptyList()
    val center = sizePx / 2.0
    val sphereRadius = sizePx / 2.0 * 0.82
    val camera = OrbProjection(yaw = 0.22 * seconds, pitch = 0.38, centerX = center, centerY = center, scale = sphereRadius)
    val radiusScale = orbRadiusScale(sizePx)
    val cycle = (sin(seconds * 1.35) + 1.0) / 2.0
    val scramble = (1.0 - cycle) * 0.55
    val rings = latRings.coerceAtLeast(3)
    val dots = ArrayList<OrbDot>(rings * lonDensity)
    for (ring in 0..rings) {
        val latitude = -PI / 2.0 + ring.toDouble() / rings * PI
        val ringRadius = cos(latitude)
        val ringY = sin(latitude)
        val ringDots = max(1, jsRound(kotlin.math.abs(ringRadius) * lonDensity.coerceAtLeast(8)))
        val phase = orbHash(ring.toDouble(), 3.1) * 2.0 * PI
        for (index in 0 until ringDots) {
            val baseLon = index.toDouble() / ringDots * 2.0 * PI
            val jitter = scramble * sin(baseLon * 3.0 + seconds * 4.2 + phase)
            val longitude = baseLon + jitter
            val (x, y, z) = camera.project(
                ringRadius * cos(longitude),
                ringY + scramble * 0.18 * sin(baseLon * 2.0 + seconds),
                ringRadius * sin(longitude),
            )
            val depth = (z + 1.0) / 2.0
            dots += OrbDot(
                x = x,
                y = y,
                z = z,
                r = (0.85 + 1.4 * depth) * radiusScale,
                white = 0.58 - 0.42 * depth,
                alpha = 0.35 + 0.65 * depth,
            )
        }
    }
    return dots.finalizeFrame()
}

internal fun listeningFrame(sizePx: Double, seconds: Double): List<OrbDot> {
    if (sizePx <= 0.0) return emptyList()
    val center = sizePx / 2.0
    val radiusScale = orbRadiusScale(sizePx)
    val rings = 6
    val dotsPerRing = 28
    val dots = ArrayList<OrbDot>(rings * dotsPerRing)
    for (ring in 1..rings) {
        val baseRadius = sizePx / 2.0 * (0.18 + 0.12 * ring)
        for (index in 0 until dotsPerRing) {
            val theta = index.toDouble() / dotsPerRing * 2.0 * PI
            val wave = 0.14 * sin(theta * 3.0 - seconds * 3.4 + ring * 0.7)
            val radius = baseRadius * (1.0 + wave)
            val x = center + radius * cos(theta)
            val y = center + radius * sin(theta)
            val depth = 0.35 + 0.1 * ring
            dots += OrbDot(
                x = x,
                y = y,
                z = depth,
                r = (1.05 + 0.35 * (wave + 0.14)) * radiusScale,
                white = 0.22 + 0.12 * ring,
                alpha = 0.28 + 0.1 * ring + 0.25 * (wave + 0.14),
            )
        }
    }
    return dots.finalizeFrame()
}

internal fun connectingFrame(sizePx: Double, seconds: Double): List<OrbDot> {
    if (sizePx <= 0.0) return emptyList()
    val center = sizePx / 2.0
    val sphereRadius = sizePx / 2.0 * 0.78
    val camera = OrbProjection(yaw = 0.18 * seconds, pitch = 0.42, centerX = center, centerY = center, scale = sphereRadius)
    val radiusScale = orbRadiusScale(sizePx)
    val count = 22
    val progress = ((sin(seconds * 0.9) + 1.0) / 2.0)
    val dots = ArrayList<OrbDot>(count * 6)
    val nodes = Array(count) { index -> fibonacciSpherePoint(index, count) }
    for (index in 0 until count) {
        val (px, py, pz) = nodes[index]
        val (x, y, z) = camera.project(px, py, pz)
        val depth = orbDepthFactor(z, 1.0)
        dots += OrbDot(x, y, z, (1.35 + 0.8 * depth) * radiusScale, 0.7 - 0.4 * depth, 0.45 + 0.5 * depth)
        val neighbor = (index + 1 + (index % 3)) % count
        if (index.toDouble() / count <= progress + 0.08) {
            val (qx, qy, qz) = nodes[neighbor]
            val steps = 5
            for (step in 1 until steps) {
                val t = step.toDouble() / steps
                val (sx, sy, sz) = camera.project(
                    px + (qx - px) * t,
                    py + (qy - py) * t,
                    pz + (qz - pz) * t,
                )
                dots += OrbDot(sx, sy, sz, 0.7 * radiusScale, 0.55, 0.22 + 0.35 * progress)
            }
        }
    }
    return dots.finalizeFrame()
}

internal fun weavingFrame(sizePx: Double, seconds: Double): List<OrbDot> {
    if (sizePx <= 0.0) return emptyList()
    val center = sizePx / 2.0
    val sphereRadius = sizePx / 2.0 * 0.8
    val camera = OrbProjection(yaw = 0.28 * seconds, pitch = 0.36, centerX = center, centerY = center, scale = sphereRadius)
    val radiusScale = orbRadiusScale(sizePx)
    val strands = 3
    val samples = 42
    val dots = ArrayList<OrbDot>(strands * samples)
    for (strand in 0 until strands) {
        val phase = strand * 2.0 * PI / strands
        for (sample in 0 until samples) {
            val t = sample.toDouble() / samples * 2.0 * PI + seconds * 1.15
            val y = cos(t)
            val radius = sin(t)
            val twist = t * 1.5 + phase
            val (x, yProj, z) = camera.project(
                radius * cos(twist),
                y * 0.92,
                radius * sin(twist),
            )
            val depth = orbDepthFactor(z, 1.0)
            dots += OrbDot(
                x = x,
                y = yProj,
                z = z,
                r = (0.95 + 0.9 * depth) * radiusScale,
                white = 0.3 + 0.18 * strand + 0.2 * depth,
                alpha = 0.3 + 0.6 * depth,
            )
        }
    }
    return dots.finalizeFrame()
}

internal fun composingFrame(sizePx: Double, seconds: Double): List<OrbDot> {
    if (sizePx <= 0.0) return emptyList()
    val center = sizePx / 2.0
    val radiusScale = orbRadiusScale(sizePx)
    val bands = 5
    val samples = 36
    val dots = ArrayList<OrbDot>(bands * samples)
    for (band in 0 until bands) {
        val y = center + (band - (bands - 1) / 2.0) * (sizePx * 0.12)
        for (sample in 0 until samples) {
            val t = sample.toDouble() / (samples - 1)
            val x = sizePx * 0.12 + t * sizePx * 0.76
            val wave = sin(t * 2.0 * PI * 2.0 + seconds * 2.4 + band * 0.7) * sizePx * 0.035
            val z = 0.4 + 0.1 * band
            dots += OrbDot(
                x = x,
                y = y + wave,
                z = z,
                r = (1.05 + 0.25 * sin(t * PI)) * radiusScale,
                white = 0.25 + 0.12 * band,
                alpha = 0.35 + 0.12 * band,
            )
        }
    }
    return dots.finalizeFrame()
}

internal fun breathingFrame(sizePx: Double, seconds: Double): List<OrbDot> {
    if (sizePx <= 0.0) return emptyList()
    val center = sizePx / 2.0
    val radiusScale = orbRadiusScale(sizePx)
    val breath = (sin(seconds * 1.6) + 1.0) / 2.0
    val radius = sizePx / 2.0 * (0.42 + 0.22 * breath)
    val count = 48
    val dots = ArrayList<OrbDot>(count + 12)
    for (index in 0 until count) {
        val theta = index.toDouble() / count * 2.0 * PI
        dots += OrbDot(
            x = center + radius * cos(theta),
            y = center + radius * sin(theta),
            z = 0.5,
            r = (1.1 + 0.5 * breath) * radiusScale,
            white = 0.35 + 0.25 * breath,
            alpha = 0.4 + 0.4 * breath,
        )
    }
    val inner = 8
    for (index in 0 until inner) {
        val (px, py, pz) = fibonacciSpherePoint(index, inner)
        dots += OrbDot(
            x = center + px * radius * 0.35,
            y = center - py * radius * 0.35,
            z = pz,
            r = 0.9 * radiusScale,
            white = 0.55,
            alpha = 0.25 + 0.35 * breath,
        )
    }
    return dots.finalizeFrame()
}

internal fun shapingFrame(sizePx: Double, seconds: Double): List<OrbDot> {
    if (sizePx <= 0.0) return emptyList()
    val center = sizePx / 2.0
    val radiusScale = orbRadiusScale(sizePx)
    val radius = sizePx / 2.0 * 0.72
    val phase = (seconds * 0.35) % 3.0
    val from = floor(phase).toInt()
    val t = phase - from
    val eased = t * t * (3.0 - 2.0 * t)
    fun shapePoint(shape: Int, index: Int, count: Int): Pair<Double, Double> {
        val u = index.toDouble() / count
        return when (shape % 3) {
            0 -> {
                val theta = u * 2.0 * PI
                Pair(cos(theta), sin(theta))
            }
            1 -> {
                val edge = (u * 3.0)
                val i = floor(edge).toInt()
                val f = edge - i
                val verts = arrayOf(Pair(0.0, -1.0), Pair(0.866, 0.5), Pair(-0.866, 0.5))
                val a = verts[i % 3]
                val b = verts[(i + 1) % 3]
                Pair(a.first + (b.first - a.first) * f, a.second + (b.second - a.second) * f)
            }
            else -> {
                val edge = (u * 4.0)
                val i = floor(edge).toInt()
                val f = edge - i
                val verts = arrayOf(Pair(-0.85, -0.85), Pair(0.85, -0.85), Pair(0.85, 0.85), Pair(-0.85, 0.85))
                val a = verts[i % 4]
                val b = verts[(i + 1) % 4]
                Pair(a.first + (b.first - a.first) * f, a.second + (b.second - a.second) * f)
            }
        }
    }
    val count = 56
    val dots = ArrayList<OrbDot>(count)
    for (index in 0 until count) {
        val a = shapePoint(from, index, count)
        val b = shapePoint(from + 1, index, count)
        val px = a.first + (b.first - a.first) * eased
        val py = a.second + (b.second - a.second) * eased
        dots += OrbDot(
            x = center + px * radius,
            y = center + py * radius,
            z = 0.5,
            r = 1.15 * radiusScale,
            white = 0.4,
            alpha = 0.72,
        )
    }
    return dots.finalizeFrame()
}

/**
 * Monochrome thinking-orbs animation. Drives a frame clock with
 * [withFrameNanos] while visible and not [paused]; leaving the composition
 * cancels the loop, and [paused] freezes the last computed frame. `white` ink
 * maps to light dots on dark themes and dark dots on light themes.
 */
@Composable
fun ThinkingOrbs(
    mode: ThinkingOrbMode,
    size: Dp,
    modifier: Modifier = Modifier,
    speedMultiplier: Float = 1f,
    paused: Boolean = false,
) {
    val darkTheme = isSystemInDarkTheme()
    val reduceMotion = LocalReduceMotion.current
    var clockSeconds by remember { mutableDoubleStateOf(0.0) }
    val running = !paused && !reduceMotion
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var lastFrameNanos = Long.MIN_VALUE
        while (true) {
            withFrameNanos { frameNanos ->
                if (lastFrameNanos != Long.MIN_VALUE) {
                    clockSeconds += (frameNanos - lastFrameNanos) / 1_000_000_000.0
                }
                lastFrameNanos = frameNanos
            }
        }
    }

    // Dot density follows the requested size relative to the 64dp reference
    // preset, clamped so tiny capsules stay cheap and large ones stay sparse.
    val densityScale = (size.value / 64f).coerceIn(0.4f, 1.6f)
    val orbitCount = remember(densityScale) { max(4, jsRound(12.0 * densityScale)) }
    val ghostCount = remember(densityScale) { max(6, jsRound(40.0 * densityScale)) }
    val latRings = remember(densityScale) { max(2, jsRound(11.0 * sqrt(densityScale.toDouble()))) }
    val lonDensity = remember(densityScale) { max(2, jsRound(29.0 * sqrt(densityScale.toDouble()))) }

    Canvas(modifier.size(size)) {
        // Read the frame clock inside the draw scope so each frame only
        // invalidates the draw, not the whole composition.
        val engineSeconds = clockSeconds * mode.baseSpeed * speedMultiplier.toDouble().coerceAtLeast(0.0)
        val canvasSize = min(this.size.width, this.size.height).toDouble()
        if (canvasSize <= 0.0) return@Canvas
        val offsetX = (this.size.width - canvasSize) / 2.0
        val offsetY = (this.size.height - canvasSize) / 2.0
        val dots = when (mode) {
            ThinkingOrbMode.Working, ThinkingOrbMode.Orbits -> orbitsFrame(
                sizePx = canvasSize,
                seconds = engineSeconds,
                orbitCount = orbitCount,
                ghostCount = ghostCount,
                particleCount = 3,
            )
            ThinkingOrbMode.Searching, ThinkingOrbMode.Globe -> globeFrame(
                sizePx = canvasSize,
                seconds = engineSeconds,
                latRings = latRings,
                lonDensity = lonDensity,
            )
            ThinkingOrbMode.Solving -> solvingFrame(canvasSize, engineSeconds, latRings, lonDensity)
            ThinkingOrbMode.Listening -> listeningFrame(canvasSize, engineSeconds)
            ThinkingOrbMode.Connecting -> connectingFrame(canvasSize, engineSeconds)
            ThinkingOrbMode.Weaving -> weavingFrame(canvasSize, engineSeconds)
            ThinkingOrbMode.Composing -> composingFrame(canvasSize, engineSeconds)
            ThinkingOrbMode.Breathing -> breathingFrame(canvasSize, engineSeconds)
            ThinkingOrbMode.Shaping -> shapingFrame(canvasSize, engineSeconds)
        }
        for (dot in dots) {
            val ink = (if (darkTheme) dot.white else 1.0 - dot.white).coerceIn(0.0, 1.0)
            val gray = (ink * 255.0).toInt().coerceIn(0, 255)
            drawCircle(
                color = Color(
                    red = gray,
                    green = gray,
                    blue = gray,
                    alpha = (dot.alpha.coerceIn(0.0, 1.0) * 255.0).toInt().coerceIn(0, 255),
                ),
                radius = dot.r.toFloat(),
                center = androidx.compose.ui.geometry.Offset(
                    x = (offsetX + dot.x).toFloat(),
                    y = (offsetY + dot.y).toFloat(),
                ),
            )
        }
    }
}

/** Convenience default size used by the subagent capsule card (npm 20px inline). */
val ThinkingOrbsCapsuleSize = 20.dp

/** Avatar / detail-sheet size (npm 64px preset). */
val ThinkingOrbsAvatarSize = 64.dp
