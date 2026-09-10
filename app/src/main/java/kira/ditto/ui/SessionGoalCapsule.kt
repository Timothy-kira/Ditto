package kira.ditto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kira.ditto.R
import kira.ditto.data.SessionGoalSnapshot
import kira.ditto.ui.theme.AetherError
import kira.ditto.ui.theme.AetherOnSurface
import kira.ditto.ui.theme.AetherOnSurfaceVariant
import kira.ditto.ui.theme.AetherOutlineSoft
import kira.ditto.ui.theme.AetherPrimary
import kira.ditto.ui.theme.AetherSecondary
import kira.ditto.ui.theme.AetherSurface
import kotlin.math.roundToInt

/**
 * CLI-style goal strip: sits on the composer (not the message list). Tap the
 * body to open the full objective; pause/resume and delete stay on the strip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalSupervisionBar(
    snapshot: SessionGoalSnapshot,
    supervisorName: String,
    onGoalControl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sheetVisible by remember { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf(false) }
    val statusColor = goalStatusColor(snapshot.status)
    val paused = snapshot.status.equals("paused", ignoreCase = true) ||
        snapshot.status.equals("blocked", ignoreCase = true)
    AetherCapsuleSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { sheetVisible = true }
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Flag,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = stringResource(
                        R.string.goal_supervising,
                        supervisorName.ifBlank { snapshot.objective },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AetherOnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            IconButton(
                onClick = { onGoalControl(if (paused) "resume" else "pause") },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                    contentDescription = stringResource(
                        if (paused) R.string.goal_resume else R.string.goal_pause,
                    ),
                    tint = AetherOnSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(
                onClick = { confirmCancel = true },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = stringResource(R.string.goal_delete),
                    tint = AetherError,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }

    if (sheetVisible) {
        ModalBottomSheet(
            onDismissRequest = { sheetVisible = false },
            containerColor = AetherSurface,
            contentColor = AetherOnSurface,
            tonalElevation = 0.dp,
            dragHandle = null,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 640.dp),
            ) {
                GoalDetailSheetContent(snapshot = snapshot)
                AetherSheetDragHandleScrim()
            }
        }
    }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text(stringResource(R.string.goal_cancel_confirm_title)) },
            text = { Text(stringResource(R.string.goal_cancel_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmCancel = false
                        onGoalControl("cancel")
                    },
                ) {
                    Text(stringResource(R.string.goal_delete), color = AetherError)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancel = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun GoalDetailSheetContent(snapshot: SessionGoalSnapshot) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 28.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = stringResource(R.string.goal_objective_label),
            style = MaterialTheme.typography.labelSmall,
            color = AetherOnSurfaceVariant,
        )
        Text(
            text = snapshot.objective.ifBlank { "—" },
            style = MaterialTheme.typography.bodyLarge,
            color = AetherOnSurface,
        )
        if (snapshot.completionCriterion.isNotBlank()) {
            Text(
                text = stringResource(R.string.goal_criterion_label),
                style = MaterialTheme.typography.labelSmall,
                color = AetherOnSurfaceVariant,
            )
            Text(
                text = snapshot.completionCriterion,
                style = MaterialTheme.typography.bodyMedium,
                color = AetherOnSurface,
            )
        }
        GoalStatusChip(status = snapshot.status, color = goalStatusColor(snapshot.status))
        snapshot.tokenFraction?.let { fraction ->
            GoalBudgetRow(
                label = stringResource(
                    R.string.goal_progress_tokens,
                    formatCompactTokenCount(snapshot.tokensUsed),
                    formatCompactTokenCount(snapshot.tokenBudget ?: 0L),
                ),
                fraction = fraction,
            )
        }
        snapshot.turnFraction?.let { fraction ->
            GoalBudgetRow(
                label = stringResource(
                    R.string.goal_progress_turns,
                    snapshot.turnsUsed.toString(),
                    (snapshot.turnBudget ?: 0L).toString(),
                ),
                fraction = fraction,
            )
        }
        snapshot.wallClockFraction?.let { fraction ->
            GoalBudgetRow(
                label = stringResource(
                    R.string.goal_progress_time,
                    formatGoalDuration(snapshot.wallClockMs),
                    formatGoalDuration(snapshot.wallClockBudgetMs ?: 0L),
                ),
                fraction = fraction,
            )
        }
    }
}

@Composable
private fun GoalStatusChip(
    status: String,
    color: Color,
) {
    Text(
        text = goalStatusLabel(status),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun goalStatusLabel(status: String): String = when (status) {
    "active" -> stringResource(R.string.goal_status_active)
    "paused" -> stringResource(R.string.goal_status_paused)
    "blocked" -> stringResource(R.string.goal_status_blocked)
    "complete" -> stringResource(R.string.goal_status_complete)
    else -> status
}

@Composable
private fun goalStatusColor(status: String): Color = when (status) {
    "complete" -> AetherSecondary
    "blocked" -> AetherError
    "paused" -> AetherOnSurfaceVariant
    else -> AetherPrimary
}

@Composable
private fun GoalBudgetRow(
    label: String,
    fraction: Float,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = AetherOnSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(112.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(3.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(AetherOutlineSoft.copy(alpha = 0.62f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(3.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(AetherPrimary),
            )
        }
        Text(
            text = "${(fraction.coerceIn(0f, 1f) * 100f).roundToInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = AetherOnSurfaceVariant,
        )
    }
}

/** Compact duration for goal wall-clock budgets: 42s, 3m05s, 1h07m. */
internal fun formatGoalDuration(durationMs: Long): String {
    val totalSeconds = (durationMs.coerceAtLeast(0L) + 500L) / 1_000L
    if (totalSeconds < 60L) return "${totalSeconds}s"
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    if (minutes < 60L) return "${minutes}m${seconds.toString().padStart(2, '0')}s"
    val hours = minutes / 60L
    return "${hours}h${(minutes % 60L).toString().padStart(2, '0')}m"
}
