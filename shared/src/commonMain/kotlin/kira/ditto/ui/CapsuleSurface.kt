package kira.ditto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import kira.ditto.ui.theme.AetherOnSurfaceVariant
import kira.ditto.ui.theme.AetherOutlineSoft
import kira.ditto.ui.theme.AetherSurface

/** Soft halo tint used by the top model capsule and every surface reusing its material. */
val AetherCapsuleShadowColor = Color(0x14000000)

/** Default stadium shape of the top model capsule. */
val AetherCapsuleShape = RoundedCornerShape(999.dp)

/** Corner shape used when the capsule material is applied to rectangular popup panels. */
val AetherCapsulePanelShape = RoundedCornerShape(24.dp)

/** Hairline stroke that separates the capsule body from the page without any cast shadow. */
private val AetherCapsuleBorderColor
    @Composable get() = AetherOutlineSoft.copy(alpha = 0.65f)

/**
 * The shared material of the top model-switch capsule: a blurred halo shadow
 * (offset down 4.dp, 14.dp unbounded blur) behind a near-opaque [AetherSurface]
 * body. The capsule itself uses [AetherCapsuleShape]; popup panels reuse the
 * same material with [AetherCapsulePanelShape].
 *
 * Note: the shadow layer uses `matchParentSize()`, which does not participate
 * in measuring, so [content] must contain at least one normally measured child.
 */
@Composable
fun AetherCapsuleSurface(
    modifier: Modifier = Modifier,
    shape: Shape = AetherCapsuleShape,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier) {
        Box(
            modifier = Modifier.matchParentSize()
                .offset(y = 4.dp)
                .blur(14.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                .clip(shape)
                .background(AetherCapsuleShadowColor),
        )
        Box(
            modifier = Modifier
                .clip(shape)
                .background(AetherSurface.copy(alpha = 0.96f)),
            content = content,
        )
    }
}

/**
 * Shadow-free variant of the capsule material used by tall cards (subagent
 * capsule): on large surfaces the halo/elevation shadow pools at the bottom
 * corners and reads as dirty gray square corners, so this variant uses a
 * hairline border instead. Small capsules keep [AetherCapsuleSurface].
 */
@Composable
fun AetherCapsuleBorderedSurface(
    modifier: Modifier = Modifier,
    shape: Shape = AetherCapsuleShape,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(AetherSurface.copy(alpha = 0.96f))
            .border(0.5.dp, AetherCapsuleBorderColor, shape),
        content = content,
    )
}

/**
 * Top drag-handle overlay used by reasoning and subagent modal sheets:
 * a 56×5.dp pill sitting on a white-to-transparent fade so content can
 * scroll underneath without covering the handle.
 */
@Composable
fun BoxScope.AetherSheetDragHandleScrim() {
    Box(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .height(48.dp)
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.00f to AetherSurface,
                        0.58f to AetherSurface,
                        1.00f to AetherSurface.copy(alpha = 0f),
                    ),
                ),
            ),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 10.dp)
                .width(56.dp)
                .height(5.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(AetherOnSurfaceVariant.copy(alpha = 0.16f)),
        )
    }
}
