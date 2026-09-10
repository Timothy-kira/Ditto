package kira.ditto.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer

internal val PageStackTransitionDuration = 320
internal val PageStackExitDuration = 240
internal val PageStackTransitionEasing = CubicBezierEasing(0.22f, 0.84f, 0.18f, 1f)

/**
 * Rasterize each page-stack child to a GPU layer so the 1/3 slide + fade
 * translates a texture instead of re-recording the whole settings tree.
 * Visual timing and travel distance stay the same.
 */
internal fun Modifier.gpuOffscreenLayer(): Modifier = graphicsLayer {
    compositingStrategy = CompositingStrategy.Offscreen
}

/**
 * The offscreen layer is what makes the slide cheap, but keeping it after the transition
 * settles forces every subsequent frame of the resting page through an extra render
 * target. Enable it only while the page stack is actually animating.
 */
@Composable
internal fun AnimatedVisibilityScope.pageStackGpuLayer(): Modifier {
    val pageTransition = transition
    return Modifier
        .fillMaxSize()
        .clipToBounds()
        .graphicsLayer {
            compositingStrategy = if (pageTransition.isRunning) {
                CompositingStrategy.Offscreen
            } else {
                CompositingStrategy.Auto
            }
        }
}

internal fun pageStackContentTransform(isForward: Boolean): ContentTransform {
    val enter = slideInHorizontally(
        animationSpec = tween(PageStackTransitionDuration, easing = PageStackTransitionEasing),
        initialOffsetX = { width -> if (isForward) width / 3 else -width / 3 },
    ) + fadeIn(tween(PageStackTransitionDuration, easing = PageStackTransitionEasing))
    val exit = slideOutHorizontally(
        animationSpec = tween(PageStackExitDuration, easing = PageStackTransitionEasing),
        targetOffsetX = { width -> if (isForward) -width / 3 else width / 3 },
    ) + fadeOut(tween(PageStackExitDuration, easing = PageStackTransitionEasing))
    return ContentTransform(
        targetContentEnter = enter,
        initialContentExit = exit,
        targetContentZIndex = if (isForward) 1f else 0f,
        sizeTransform = SizeTransform(clip = false) { _, _ -> snap() },
    )
}
