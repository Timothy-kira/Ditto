package kira.ditto.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kira.ditto.R
import kira.ditto.ui.theme.AetherOnSurface
import kira.ditto.ui.theme.AetherSurface
import org.json.JSONObject

@Composable
internal fun CronCapsuleCard(
    toolInvocation: ChatToolInvocation,
    info: CronToolInfo,
    topPadding: Dp = 6.dp,
) {
    val names = subagentPersonNames()
    val seed = "cron:${toolInvocation.id}:${info.kind}"
    val people = remember(toolInvocation.id, info.kind, names) {
        listOf(
            SubagentPersonUi(
                index = 0,
                name = pickStableName(seed, names),
                avatar = subagentAvatarSpec(seed),
                member = null,
                sourceToolCallId = toolInvocation.id,
            ),
        )
    }
    val failed = !toolInvocation.isRunning &&
        toolInvocation.outputJson.contains("error", ignoreCase = true) &&
        !toolInvocation.outputJson.contains("\"id\"")
    val title = when (info.kind) {
        CronToolKind.Create -> {
            val caption = subagentDisplayCaption(info.prompt).ifBlank {
                info.cron.ifBlank { stringResource(R.string.cron_card_created) }
            }
            if (toolInvocation.isRunning) {
                stringResource(R.string.cron_card_scheduling, caption)
            } else {
                stringResource(R.string.cron_card_created)
            }
        }
        CronToolKind.List -> if (toolInvocation.isRunning) {
            stringResource(R.string.cron_card_listing)
        } else {
            stringResource(R.string.cron_card_listed)
        }
        CronToolKind.Delete -> if (toolInvocation.isRunning) {
            stringResource(R.string.cron_card_deleting)
        } else {
            stringResource(R.string.cron_card_deleted)
        }
    }
    var sheetVisible by rememberSaveable(toolInvocation.id) { mutableStateOf(false) }

    AetherCapsuleBorderedSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPadding),
        shape = RoundedCornerShape(20.dp),
    ) {
        SubagentCollapsedHeader(
            people = people,
            title = title,
            failed = failed,
            isRunning = toolInvocation.isRunning,
            onClick = { sheetVisible = true },
        )
    }
    if (sheetVisible) {
        CronDetailSheet(
            toolInvocation = toolInvocation,
            info = info,
            onDismiss = { sheetVisible = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CronDetailSheet(
    toolInvocation: ChatToolInvocation,
    info: CronToolInfo,
    onDismiss: () -> Unit,
) {
    val output = remember(toolInvocation.outputJson) {
        cronToolOutputText(toolInvocation.outputJson)
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, top = 36.dp, end = 24.dp, bottom = 30.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (info.prompt.isNotBlank()) {
                    SubagentTextSection(
                        label = stringResource(R.string.cron_card_prompt_label),
                        text = info.prompt,
                    )
                }
                if (info.cron.isNotBlank()) {
                    SubagentTextSection(
                        label = stringResource(R.string.cron_card_schedule_label),
                        text = info.cron,
                    )
                }
                if (info.jobId.isNotBlank()) {
                    SubagentTextSection(
                        label = stringResource(R.string.cron_card_id_label),
                        text = info.jobId,
                    )
                }
                if (output.isNotBlank()) {
                    SubagentTextSection(
                        label = stringResource(R.string.subagent_card_result_label),
                        text = output,
                    )
                }
            }
            AetherSheetDragHandleScrim()
        }
    }
}

private fun cronToolOutputText(outputJson: String): String {
    if (outputJson.isBlank()) return ""
    val output = runCatching { JSONObject(outputJson) }.getOrNull()
    return toolInvocationResultText(output, outputJson, "")
}
