package kira.ditto.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kira.ditto.R
import kira.ditto.browser.BrowserDeskState
import kira.ditto.browser.BrowserTaskState

/** Status of the latest desk receipt for this topic; never parsed from a tool's output JSON. */
internal fun browserExecutionStatus(
    deskState: BrowserDeskState,
    topic: String = "",
): BrowserTaskState? {
    if (deskState.userTakeover &&
        (topic.isBlank() || deskState.uiPreviewTopicId.isBlank() || deskState.uiPreviewTopicId == topic)
    ) {
        return BrowserTaskState.WaitingUser
    }
    val latest = deskState.tasks
        .filter { topic.isBlank() || it.topic.isBlank() || it.topic == topic }
        .maxByOrNull { it.updatedAtMillis }
        ?: return null
    return latest.state.takeUnless { it == BrowserTaskState.Running }
}

@Composable
internal fun BrowserExecutionSummary(deskState: BrowserDeskState, topic: String = "") {
    val label = when (browserExecutionStatus(deskState, topic)) {
        BrowserTaskState.WaitingUser -> R.string.browser_execution_waiting_user
        BrowserTaskState.NeedsVerification -> R.string.browser_execution_needs_verification
        BrowserTaskState.Failed -> R.string.browser_execution_failed
        BrowserTaskState.Completed -> R.string.browser_execution_completed
        else -> return
    }
    Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
    }
}
