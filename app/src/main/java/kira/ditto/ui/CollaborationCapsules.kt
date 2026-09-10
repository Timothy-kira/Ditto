package kira.ditto.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal fun ChatToolInvocation.isCollaborationCapsule(): Boolean =
    isSubagentLaunch() || isCronTool() || isTowerRelated()

internal fun collectToolCollaborationInvocations(
    invocations: List<ChatToolInvocation>,
    hideBrowserSwarmUi: Boolean = false,
): List<ChatToolInvocation> =
    coalesceParallelBrowserAgents(
        invocations.distinctBy(ChatToolInvocation::id).filter { it.isCollaborationCapsule() },
    ).filterNot { invocation ->
        invocation.isBrowserDeskSingle() ||
            invocation.isCoalescedBrowserSwarm() ||
            invocation.isSilentBrowserHostTool(invocations) ||
            (hideBrowserSwarmUi && invocation.isBrowserSwarm())
    }

internal fun collectMessageCollaborationInvocations(
    messages: List<ChatMessage>,
    hideBrowserSwarmUi: Boolean = false,
): List<ChatToolInvocation> = collectToolCollaborationInvocations(
    messages.flatMap { message ->
        message.toolInvocations + message.reasoningTrace?.toolInvocations.orEmpty()
    },
    hideBrowserSwarmUi = hideBrowserSwarmUi,
)

@Composable
internal fun CollaborationCapsuleStack(
    invocations: List<ChatToolInvocation>,
    topPadding: Dp = 0.dp,
) {
    val tower = remember(invocations) { invocations.filter { it.isTowerRelated() } }
    val others = remember(invocations) { invocations.filterNot { it.isTowerRelated() } }
    if (tower.isNotEmpty()) {
        TowerBoardCard(invocations = tower, topPadding = topPadding)
    }
    others.forEach { invocation ->
        key(invocation.id) {
            ToolInvocationCard(toolInvocation = invocation, topPadding = topPadding)
        }
    }
}
