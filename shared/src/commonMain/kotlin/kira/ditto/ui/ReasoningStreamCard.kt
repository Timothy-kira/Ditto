package kira.ditto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kira.ditto.ui.theme.AetherOnSurfaceVariant
import kira.ditto.ui.theme.AetherSurfaceHigh

private const val ReasoningStreamPreviewMaxChars = 8_000

@Composable
fun ReasoningStreamCard(
    text: String,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 168.dp,
    followLatest: Boolean = true,
) {
    val truncated = text.length > ReasoningStreamPreviewMaxChars
    val display = if (!truncated) {
        text
    } else {
        "...\n" + text.takeLast(ReasoningStreamPreviewMaxChars).trimStart()
    }
    val scrollState = rememberScrollState()
    // 流式期间跟随最新输出；完成后保持当前视口，用户可手动滚动。
    LaunchedEffect(display, followLatest) {
        if (followLatest) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(AetherSurfaceHigh)
            // A fixed viewport keeps streaming reasoning from repeatedly
            // resizing (and therefore jumping) the outer conversation list.
            .height(maxHeight)
            .verticalScroll(scrollState)
            .padding(14.dp),
    ) {
        Text(
            text = display,
            style = MaterialTheme.typography.bodyMedium,
            color = AetherOnSurfaceVariant,
        )
    }
}
