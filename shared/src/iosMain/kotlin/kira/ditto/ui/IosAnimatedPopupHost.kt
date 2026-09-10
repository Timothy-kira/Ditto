package kira.ditto.ui

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos

/** Keeps the iOS popup window mounted until its exit transition completes. */
@Composable
internal fun SharedAnimatedPopupHost(
    visible: Boolean,
    content: @Composable (MutableTransitionState<Boolean>) -> Unit,
) {
    val transitionState = remember { MutableTransitionState(false) }
    var mounted by remember { mutableStateOf(false) }

    LaunchedEffect(visible) {
        if (visible) {
            mounted = true
            withFrameNanos { }
            withFrameNanos { }
            transitionState.targetState = true
        } else {
            transitionState.targetState = false
        }
    }
    LaunchedEffect(visible, transitionState.currentState, transitionState.isIdle) {
        if (!visible && transitionState.isIdle && !transitionState.currentState) {
            mounted = false
        }
    }

    if (mounted) content(transitionState)
}
