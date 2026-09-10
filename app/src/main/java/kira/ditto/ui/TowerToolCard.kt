package kira.ditto.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kira.ditto.R
import kira.ditto.ui.theme.AetherOnSurface
import kira.ditto.ui.theme.AetherOnSurfaceVariant
import kira.ditto.ui.theme.AetherSurface
import org.json.JSONObject

/**
 * Separate tower board: lead (the control tower) plus workers/reviewers.
 * Visual language matches swarm capsules, but this is not the AgentSwarm card.
 */
@Composable
internal fun TowerBoardCard(
    invocations: List<ChatToolInvocation>,
    topPadding: Dp = 6.dp,
) {
    val roster = remember(invocations) { parseTowerRoster(invocations) }
    val running = invocations.any(ChatToolInvocation::isRunning)
    val failed = !running && roster.any(TowerRosterMember::failed)
    val names = subagentPersonNames()
    val leadName = stringResource(R.string.tower_card_lead)
    val people = remember(roster, leadName, names, invocations.map { it.id }) {
        val occupied = linkedSetOf(leadName)
        val lead = SubagentPersonUi(
            index = 0,
            name = leadName,
            avatar = subagentAvatarSpec("tower-lead"),
            member = null,
        )
        val workers = roster.mapIndexed { index, member ->
            val seed = "tower:${member.name}:${member.kind}:${member.toolCallId}"
            SubagentPersonUi(
                index = index + 1,
                name = member.name.ifBlank { assignPersonName(seed, names, occupied) },
                avatar = subagentAvatarSpec(seed),
                member = null,
                sourceToolCallId = member.toolCallId,
            )
        }
        listOf(lead) + workers
    }
    val title = if (running) {
        stringResource(R.string.tower_card_count, people.size.coerceAtLeast(1))
    } else {
        stringResource(R.string.tower_card_count_completed)
    }
    var sheetVisible by rememberSaveable(invocations.joinToString { it.id }) { mutableStateOf(false) }

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
            isRunning = running,
            onClick = { sheetVisible = true },
        )
    }
    if (sheetVisible) {
        TowerDetailSheet(
            invocations = invocations,
            roster = roster,
            people = people,
            onDismiss = { sheetVisible = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TowerDetailSheet(
    invocations: List<ChatToolInvocation>,
    roster: List<TowerRosterMember>,
    people: List<SubagentPersonUi>,
    onDismiss: () -> Unit,
) {
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    val selectedPerson = people.getOrNull(selectedIndex.coerceIn(0, (people.size - 1).coerceAtLeast(0)))
        ?: people.firstOrNull()
    val selectedMember = selectedPerson?.sourceToolCallId?.let { id ->
        roster.firstOrNull { it.toolCallId == id }
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
                if (people.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        people.forEachIndexed { index, person ->
                            SwarmPersonCapsule(
                                person = person,
                                selected = index == selectedIndex,
                                onClick = { selectedIndex = index },
                            )
                        }
                    }
                }
                val person = selectedPerson
                if (person != null) {
                    Text(
                        text = person.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = AetherOnSurface,
                    )
                    val roleLabel = when {
                        selectedMember == null -> stringResource(R.string.tower_card_lead_role)
                        selectedMember.kind.equals("reviewer", ignoreCase = true) ->
                            stringResource(R.string.tower_card_reviewer)
                        else -> stringResource(R.string.tower_card_worker)
                    }
                    Text(
                        text = roleLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherOnSurfaceVariant,
                    )
                    if (selectedMember == null) {
                        val status = invocations.firstOrNull { isTowerToolName(it.toolName) &&
                            it.toolName.replace("_", "").equals("TowerStatus", ignoreCase = true)
                        }
                        val plan = invocations.firstOrNull { isTowerToolName(it.toolName) &&
                            it.toolName.replace("_", "").equals("TowerPlan", ignoreCase = true)
                        }
                        val init = invocations.firstOrNull { isTowerToolName(it.toolName) &&
                            it.toolName.replace("_", "").equals("TowerInit", ignoreCase = true)
                        }
                        val overview = listOfNotNull(
                            towerOutputText(init?.outputJson.orEmpty()).takeIf { it.isNotBlank() },
                            towerOutputText(plan?.outputJson.orEmpty()).takeIf { it.isNotBlank() },
                            towerOutputText(status?.outputJson.orEmpty()).takeIf { it.isNotBlank() },
                        ).firstOrNull().orEmpty()
                        if (overview.isNotBlank()) {
                            SubagentTextSection(
                                label = stringResource(R.string.tower_card_status_label),
                                text = overview,
                            )
                        } else {
                            SubagentTextSection(
                                label = stringResource(R.string.tower_card_status_label),
                                text = stringResource(R.string.tower_card_lead_hint),
                            )
                        }
                    } else {
                        if (selectedMember.missionId.isNotBlank()) {
                            SubagentTextSection(
                                label = stringResource(R.string.tower_card_mission_label),
                                text = selectedMember.missionId,
                            )
                        }
                        if (selectedMember.reviewTarget.isNotBlank()) {
                            SubagentTextSection(
                                label = stringResource(R.string.tower_card_review_label),
                                text = selectedMember.reviewTarget,
                            )
                        }
                        if (selectedMember.instructions.isNotBlank()) {
                            SubagentTextSection(
                                label = stringResource(R.string.subagent_card_prompt_label),
                                text = selectedMember.instructions,
                            )
                        }
                        val output = towerOutputText(selectedMember.output)
                        if (output.isNotBlank()) {
                            SubagentTextSection(
                                label = stringResource(R.string.subagent_card_result_label),
                                text = output,
                            )
                        } else if (selectedMember.running) {
                            SubagentTextSection(
                                label = stringResource(R.string.subagent_card_result_label),
                                text = stringResource(R.string.subagent_card_running),
                            )
                        }
                    }
                }
            }
            AetherSheetDragHandleScrim()
        }
    }
}

private fun towerOutputText(outputJson: String): String {
    if (outputJson.isBlank()) return ""
    val output = runCatching { JSONObject(outputJson) }.getOrNull()
    return toolInvocationResultText(output, outputJson, "")
}
