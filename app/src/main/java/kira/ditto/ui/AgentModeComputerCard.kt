package kira.ditto.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
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
import kira.ditto.data.PhoneDeskHandoffState
import kira.ditto.data.PhoneDeskPhase
import kira.ditto.data.PhoneGuiStepUi
import kira.ditto.data.PhoneSettlementUiState
import kira.ditto.data.extractListenTranscript
import kira.ditto.data.parsePhoneGuiSteps
import kira.ditto.data.resolveAgentModeListenBodyText
import kira.ditto.ui.theme.AetherOnSurfaceVariant
import kotlinx.coroutines.delay

internal val LocalPreviewLiveSurfaceEnabled = compositionLocalOf { true }

internal enum class AgentModeComputerPhase {
    Booting,
    Dispatching,
    Working,
    Summarizing,
    ReviewingEverMe,
    LearningSteps,
    Ready,
    Idle,
    Failed,
}

/**
 * Dedicated Agent Mode capsule. The virtual phone is mounted as soon as Agent
 * Mode starts; Kimi Code CLI's native subagent capsule owns task dispatch.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AgentModeComputerCard(
    identityKey: String,
    phase: AgentModeComputerPhase,
    isRunning: Boolean,
    topPadding: Dp = 6.dp,
    initiallyExpanded: Boolean = false,
    autoExpand: Boolean = false,
    expandedKey: String = identityKey,
    transcript: (@Composable () -> Unit)? = null,
    handoff: PhoneDeskHandoffState = PhoneDeskHandoffState(),
    settlement: PhoneSettlementUiState = PhoneSettlementUiState(),
    showCollapseChevron: Boolean = false,
    showPhonePreview: Boolean = false,
    showOperator: Boolean = true,
    showCrewCapsules: Boolean = true,
    showGuiSteps: Boolean = true,
    teachingActive: Boolean = false,
    dispatchTask: String = "",
    collapsePreview: Boolean = false,
    onExpandedChange: (Boolean) -> Unit = {},
    phoneSubagentInvocations: List<ChatToolInvocation> = emptyList(),
    listening: Boolean = false,
    listenTranscript: String = "",
    listenVisualOnly: Boolean = false,
    preview: @Composable () -> Unit,
) {
    val liveListening = listening ||
        LocalAgentModeListening.current ||
        handoff.phase == PhoneDeskPhase.Listening
    val liveTranscript = listenTranscript
        .ifBlank { LocalAgentModeListenTranscript.current }
        .ifBlank {
            phoneSubagentInvocations.asSequence()
                .map { invocation -> extractListenTranscript(invocation.outputJson) }
                .firstOrNull { text -> text.isNotBlank() }
                .orEmpty()
        }
    val liveVisualOnly = listenVisualOnly || LocalAgentModeListenVisualOnly.current
    val names = subagentPersonNames()
    val stepsByTool = LocalPhoneGuiStepsByToolCall.current
    val allGuiSteps = remember(phoneSubagentInvocations, stepsByTool) {
        phoneSubagentInvocations.flatMap { invocation ->
            stepsByTool[invocation.id].orEmpty().ifEmpty {
                parsePhoneGuiSteps(invocation.guiStepsJson)
            }
        }
    }
    val includeListen = turnUsedListenStart(
        listening = liveListening,
        guiSteps = allGuiSteps,
        invocations = phoneSubagentInvocations,
    )
    val people = remember(identityKey, phoneSubagentInvocations, includeListen, names) {
        agentModeCrewPeople(
            identityKey = identityKey,
            phoneInvocations = phoneSubagentInvocations,
            includeListen = includeListen,
            names = names,
        )
    }
    val lead = people.first { person -> person.crewRole == AgentModeCrewRole.Lead }
    val operator = people.firstOrNull { person -> person.crewRole == AgentModeCrewRole.Phone }
        ?: people.getOrElse(1) { lead }
    val listenPerson = people.firstOrNull { person -> person.crewRole == AgentModeCrewRole.Listen }
    val failed = phase == AgentModeComputerPhase.Failed
    val reviewingEverMe = phase == AgentModeComputerPhase.ReviewingEverMe
    val learning = phase == AgentModeComputerPhase.LearningSteps || teachingActive
    val showOperatorNow = showOperator || learning
    val busy = (
        isRunning ||
            phase == AgentModeComputerPhase.Dispatching ||
            phase == AgentModeComputerPhase.Summarizing ||
            reviewingEverMe ||
            learning ||
            handoff.phase != PhoneDeskPhase.Idle ||
            settlement.isActive
        ) && !failed
    val listenName = listenPerson?.name ?: operator.name
    val title = when {
        reviewingEverMe -> stringResource(R.string.agent_mode_reviewing_everme)
        learning -> stringResource(R.string.agent_mode_learning_steps, operator.name)
        phase == AgentModeComputerPhase.Dispatching -> stringResource(
            R.string.agent_mode_lead_dispatching,
            lead.name,
            operator.name,
        )
        phase == AgentModeComputerPhase.Summarizing -> stringResource(R.string.agent_mode_summarizing_progress)
        else -> when {
        !showOperatorNow -> stringResource(
            when (phase) {
                AgentModeComputerPhase.Failed -> R.string.agent_mode_computer_failed
                AgentModeComputerPhase.Idle -> R.string.agent_mode_computer_idle
                AgentModeComputerPhase.Ready, AgentModeComputerPhase.Booting ->
                    R.string.agent_mode_computer_ready
                AgentModeComputerPhase.Working -> R.string.agent_mode_computer_working
                else -> R.string.agent_mode_computer_working
            },
            lead.name,
        )
        settlement.isActive && handoff.phase == PhoneDeskPhase.Idle ->
            settlement.headline.ifBlank { stringResource(R.string.phone_desk_settling) }
        busy && handoff.phase == PhoneDeskPhase.Idle -> stringResource(
            R.string.agent_mode_computer_working,
            lead.name,
        )
        busy && (
            handoff.phase == PhoneDeskPhase.Dispatching ||
                handoff.phase == PhoneDeskPhase.Working
            ) -> stringResource(
            R.string.phone_desk_dispatching,
            lead.name,
            operator.name,
        )
        busy && (handoff.phase == PhoneDeskPhase.Listening || liveListening) -> stringResource(
            R.string.phone_desk_listening,
            listenName,
        )
        else -> when (handoff.phase) {
            PhoneDeskPhase.Reporting -> stringResource(
                R.string.phone_desk_reporting,
                operator.name,
                lead.name,
            )
            PhoneDeskPhase.Teaching -> stringResource(
                R.string.phone_desk_teaching,
                operator.name,
            )
            PhoneDeskPhase.Listening -> stringResource(
                R.string.phone_desk_listening,
                listenName,
            )
            PhoneDeskPhase.Idle -> stringResource(
                when (phase) {
                    AgentModeComputerPhase.Booting -> R.string.agent_mode_computer_ready
                    AgentModeComputerPhase.Working -> R.string.agent_mode_computer_working
                    AgentModeComputerPhase.Ready -> R.string.agent_mode_computer_ready
                    AgentModeComputerPhase.Idle -> R.string.agent_mode_computer_idle
                    AgentModeComputerPhase.Failed -> R.string.agent_mode_computer_failed
                    else -> R.string.agent_mode_computer_working
                },
                lead.name,
            )
            PhoneDeskPhase.Dispatching, PhoneDeskPhase.Working -> stringResource(
                R.string.phone_desk_dispatching,
                lead.name,
                operator.name,
            )
        }
        }
    }
    var expanded by remember(expandedKey) { mutableStateOf(initiallyExpanded) }
    var liveSurfaceEnabled by remember(expandedKey) { mutableStateOf(initiallyExpanded) }
    val defaultPersonIndex = people.indexOfFirst { person ->
        person.crewRole == AgentModeCrewRole.Phone
    }.takeIf { it >= 0 } ?: 0
    var selectedIndex by rememberSaveable(identityKey) { mutableIntStateOf(defaultPersonIndex) }
    var selectedGuiStep by remember(identityKey, selectedIndex) {
        mutableStateOf<PhoneGuiStepUi?>(null)
    }
    LaunchedEffect(people.size, selectedIndex) {
        if (selectedIndex >= people.size) {
            selectedIndex = defaultPersonIndex.coerceAtMost((people.size - 1).coerceAtLeast(0))
        }
    }
    LaunchedEffect(autoExpand) {
        if (autoExpand && !collapsePreview) expanded = true
    }
    LaunchedEffect(collapsePreview) {
        if (collapsePreview) expanded = false
    }
    LaunchedEffect(expanded) {
        onExpandedChange(expanded)
        if (expanded) {
            delay(320)
            liveSurfaceEnabled = true
        } else {
            liveSurfaceEnabled = false
        }
    }

    val selectedPerson = people.getOrNull(selectedIndex.coerceIn(0, (people.size - 1).coerceAtLeast(0)))
        ?: people.firstOrNull()
    val phoneSteps = remember(selectedPerson?.sourceToolCallId, stepsByTool, phoneSubagentInvocations) {
        val toolCallId = selectedPerson?.sourceToolCallId ?: return@remember emptyList()
        val live = stepsByTool[toolCallId].orEmpty()
        val invocation = phoneSubagentInvocations.firstOrNull { invocation -> invocation.id == toolCallId }
        val persisted = parsePhoneGuiSteps(invocation?.guiStepsJson.orEmpty())
        if (invocation?.isRunning == true) live.ifEmpty { persisted } else persisted.ifEmpty { live }
    }

    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(expanded, selectedIndex) {
        if (expanded) bringIntoViewRequester.bringIntoView()
    }

    val baseModifier = Modifier
        .fillMaxWidth()
        .padding(top = topPadding)
    AetherCapsuleBorderedSurface(
        modifier = baseModifier,
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.bringIntoViewRequester(bringIntoViewRequester)) {
                SubagentCollapsedHeader(
                    people = if (showCrewCapsules) people else listOf(lead),
                    title = title,
                    failed = failed,
                    isRunning = busy,
                    onClick = { expanded = !expanded },
                    showCollapseChevron = showCollapseChevron,
                    expanded = expanded,
                    showEverMeMark = reviewingEverMe,
                )
            }
            CompositionLocalProvider(LocalPreviewLiveSurfaceEnabled provides liveSurfaceEnabled) {
                PreviewCardExpandingBody(expanded = expanded) {
                    if (showCrewCapsules && people.size > 1) {
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
                    if (showPhonePreview) {
                        preview()
                    }
                    if (showCrewCapsules || showGuiSteps || transcript != null) {
                        val person = selectedPerson
                        if (person != null) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                when (person.crewRole) {
                                    AgentModeCrewRole.Lead -> {
                                        if (dispatchTask.isNotBlank() || busy) {
                                            PhoneDeskDispatchBody(
                                                task = dispatchTask.takeIf { showOperatorNow }.orEmpty(),
                                                operatorName = operator.name,
                                                showWaiting = !showPhonePreview,
                                            )
                                        }
                                        transcript?.invoke()
                                    }
                                    AgentModeCrewRole.Phone -> {
                                        if (!showPhonePreview && (dispatchTask.isNotBlank() || busy) &&
                                            (!showGuiSteps || phoneSteps.isEmpty())
                                        ) {
                                            PhoneDeskDispatchBody(
                                                task = dispatchTask.takeIf { showOperatorNow }.orEmpty(),
                                                operatorName = person.name,
                                            )
                                        }
                                        if (showGuiSteps && phoneSteps.isNotEmpty()) {
                                            PhoneGuiZAxis(
                                                steps = phoneSteps,
                                                selectedStepId = selectedGuiStep?.id,
                                                onStepClick = { step ->
                                                    selectedGuiStep =
                                                        if (selectedGuiStep?.id == step.id) null else step
                                                },
                                            )
                                            selectedGuiStep?.let { step ->
                                                SubagentTextSection(
                                                    label = stringResource(R.string.phone_gui_step_thought_title),
                                                    text = step.thought,
                                                )
                                            }
                                        }
                                    }
                                    AgentModeCrewRole.Listen -> {
                                        AgentModeListenBody(
                                            personName = person.name,
                                            listening = liveListening,
                                            transcript = liveTranscript,
                                            visualOnly = liveVisualOnly,
                                        )
                                    }
                                    null -> {
                                        transcript?.invoke()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PreviewCardExpandingBody(
    expanded: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn(animationSpec = tween(220)) + expandVertically(
            animationSpec = tween(280),
            expandFrom = Alignment.Top,
        ),
        exit = fadeOut(animationSpec = tween(180)) + shrinkVertically(
            animationSpec = tween(240),
            shrinkTowards = Alignment.Top,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
internal fun AgentModeListenBody(
    personName: String,
    listening: Boolean,
    transcript: String = "",
    visualOnly: Boolean = false,
) {
    SubagentTextSection(
        label = stringResource(R.string.phone_desk_listening, personName),
        text = resolveAgentModeListenBodyText(
            transcript = transcript,
            listening = listening,
            visualOnly = visualOnly,
            waitingText = stringResource(R.string.agent_mode_listen_waiting),
            visualOnlyText = stringResource(R.string.agent_mode_listen_visual_only),
        ),
    )
}

@Composable
private fun PhoneDeskDispatchBody(
    task: String,
    operatorName: String,
    showWaiting: Boolean = true,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (task.isNotBlank()) {
            SubagentTextSection(
                label = stringResource(R.string.phone_desk_task_label, operatorName),
                text = task,
            )
        }
        if (showWaiting) {
            Text(
                text = stringResource(R.string.phone_desk_waiting_preview),
                style = MaterialTheme.typography.bodySmall,
                color = AetherOnSurfaceVariant,
            )
        }
    }
}
