package kira.ditto.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowDown
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.hypot
import kira.ditto.R
import kira.ditto.data.KimiBrowserSubagentProfileName
import kira.ditto.data.KimiPhoneSubagentProfileName
import kira.ditto.data.PersonaAvatarSpec
import kira.ditto.data.PhoneGuiStepUi
import kira.ditto.data.extractListenTranscript
import kira.ditto.data.parsePhoneGuiSteps
import kira.ditto.data.resolveAgentModeListenBodyText
import kira.ditto.ui.theme.AetherError
import kira.ditto.ui.theme.AetherOnSurface
import kira.ditto.ui.theme.AetherOnSurfaceVariant
import kira.ditto.ui.theme.AetherPrimary
import kira.ditto.ui.theme.AetherSecondary
import kira.ditto.ui.theme.AetherSurface
import kira.ditto.ui.theme.AetherSurfaceHigh
import org.json.JSONObject

private const val SwarmItemsCollapsedCount = 8
private const val CollapsedAvatarLimit = 4
private val SubagentAvatarSize = 28.dp
private val SubagentAvatarOverlap = 16.dp

internal val LocalPhoneGuiStepsByToolCall =
    compositionLocalOf { emptyMap<String, List<PhoneGuiStepUi>>() }
internal val LocalAgentModeTurnIdentityKey = compositionLocalOf { "" }
internal val LocalAgentModeListening = compositionLocalOf { false }
internal val LocalAgentModeListenTranscript = compositionLocalOf { "" }
internal val LocalAgentModeListenVisualOnly = compositionLocalOf { false }

internal data class SubagentPersonUi(
    val index: Int,
    val name: String,
    val avatar: PersonaAvatarSpec,
    val member: SwarmMemberView?,
    val crewRole: AgentModeCrewRole? = null,
    val sourceToolCallId: String? = null,
)

/**
 * Capsule card for ACP subagent launches (Agent / AgentSwarm tool calls).
 *
 * The conversation only owns the compact summary. Tapping it opens the same
 * bottom-sheet disclosure pattern as reasoning, so long subagent prompts and
 * results never expand inside the assistant's answer body.
 */
@Composable
internal fun SubagentCapsuleCard(
    toolInvocation: ChatToolInvocation,
    info: SubagentLaunchInfo,
    topPadding: Dp = 6.dp,
) {
    val members = remember(info, toolInvocation.outputJson) {
        swarmMemberViews(info, toolInvocation.outputJson)
    }
    val failed = !toolInvocation.isRunning && subagentCallFailed(info, toolInvocation.outputJson)
    val turnIdentityKey = LocalAgentModeTurnIdentityKey.current
    val listeningNow = LocalAgentModeListening.current
    val listenTranscript = LocalAgentModeListenTranscript.current
        .ifBlank { extractListenTranscript(toolInvocation.outputJson) }
    val listenVisualOnly = LocalAgentModeListenVisualOnly.current
    val personNames = subagentPersonNames()
    val isPhone = info.profile.equals(KimiPhoneSubagentProfileName, ignoreCase = true)
    val isBrowser = info.profile.equals(KimiBrowserSubagentProfileName, ignoreCase = true)
    val liveSteps = LocalPhoneGuiStepsByToolCall.current[toolInvocation.id].orEmpty()
    val persistedSteps = remember(toolInvocation.guiStepsJson) {
        parsePhoneGuiSteps(toolInvocation.guiStepsJson)
    }
    val guiSteps = if (isPhone) {
        if (toolInvocation.isRunning) liveSteps.ifEmpty { persistedSteps } else persistedSteps.ifEmpty { liveSteps }
    } else {
        emptyList()
    }
    val includeListen = isPhone && turnUsedListenStart(
        listening = listeningNow,
        guiSteps = guiSteps,
        invocations = listOf(toolInvocation),
    )
    val people = remember(
        members,
        toolInvocation.id,
        info.profile,
        info.isSwarm,
        turnIdentityKey,
        personNames,
        isPhone,
        includeListen,
    ) {
        if (isPhone && turnIdentityKey.isNotBlank()) {
            agentModeCrewPeople(
                identityKey = turnIdentityKey,
                phoneInvocations = listOf(toolInvocation),
                includeListen = includeListen,
                names = personNames,
            )
        } else {
            subagentPeopleForLaunch(
                identityKey = turnIdentityKey,
                toolCallId = toolInvocation.id,
                info = info,
                members = members,
                names = personNames,
            )
        }
    }

    var sheetVisible by rememberSaveable(toolInvocation.id) { mutableStateOf(false) }

    val collapsedTitle = if (isBrowser && info.isSwarm) {
        browserSwarmHeaderTitle(
            personNames = people.map { person -> person.name },
            members = members,
            swarmRunning = toolInvocation.isRunning,
            swarmCount = people.size.coerceAtLeast(info.agentCount).coerceAtLeast(1),
            headline = browserSwarmHeadline(info.items, info.description),
            failed = !toolInvocation.isRunning && subagentCallFailed(info, toolInvocation.outputJson),
        )
    } else if (info.isSwarm) {
        if (toolInvocation.isRunning) {
            stringResource(R.string.subagent_card_count, people.size.coerceAtLeast(info.agentCount))
        } else {
            stringResource(R.string.subagent_card_count_completed)
        }
    } else if (isPhone) {
        val personName = people.firstOrNull { person -> person.crewRole == AgentModeCrewRole.Phone }?.name
            .orEmpty()
            .ifBlank { people.firstOrNull()?.name.orEmpty() }
        if (toolInvocation.isRunning) {
            val action = guiSteps.lastOrNull()?.label?.trim().orEmpty()
                .ifBlank { info.description.trim() }
            when {
                personName.isNotBlank() && action.isNotBlank() ->
                    stringResource(R.string.agent_mode_phone_activity, personName, action)
                personName.isNotBlank() ->
                    stringResource(R.string.agent_mode_computer_working, personName)
                action.isNotBlank() -> action
                else -> subagentDisplayCaption(info.description)
            }
        } else {
            stringResource(R.string.agent_mode_team_task_completed)
        }
    } else if (isBrowser) {
        val desk by kira.ditto.browser.BrowserDesk.state.collectAsState()
        val personName = people.firstOrNull()?.name.orEmpty()
        val action = subagentDisplayCaption(
            kira.ditto.browser.browserDeskCaption(
                description = info.description,
                prompt = info.prompt,
                deskDetail = desk.activities.lastOrNull()?.detail.orEmpty(),
                deskTitle = desk.preview.title,
            ),
        )
        if (toolInvocation.isRunning) {
            when {
                personName.isNotBlank() && action.isNotBlank() ->
                    stringResource(R.string.agent_mode_phone_activity, personName, action)
                personName.isNotBlank() ->
                    stringResource(R.string.agent_mode_computer_working, personName)
                action.isNotBlank() -> action
                else -> stringResource(R.string.agent_mode_computer_working, personName.ifBlank { stringResource(R.string.chrome_label) })
            }
        } else {
            stringResource(R.string.agent_mode_team_task_completed)
        }
    } else {
        people.firstOrNull { person -> person.crewRole == AgentModeCrewRole.Phone }?.name
            .orEmpty()
            .ifBlank {
                people.firstOrNull()?.name.orEmpty().ifBlank {
                    subagentDisplayCaption(info.description)
                }
            }
    }

    val cardModifier = Modifier
        .fillMaxWidth()
        .padding(top = topPadding)
    AetherCapsuleBorderedSurface(
        modifier = cardModifier,
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            SubagentCollapsedHeader(
                people = people,
                title = collapsedTitle,
                failed = failed,
                isRunning = toolInvocation.isRunning,
                onClick = { sheetVisible = true },
            )
        }
    }

    if (sheetVisible) {
        SubagentDetailSheet(
            toolInvocation = toolInvocation,
            info = info,
            people = people,
            failed = failed,
            guiSteps = guiSteps,
            listening = listeningNow,
            listenTranscript = listenTranscript,
            listenVisualOnly = listenVisualOnly,
            onDismiss = { sheetVisible = false },
        )
    }
}

@Composable
internal fun browserSwarmHeaderTitle(
    personNames: List<String>,
    members: List<SwarmMemberView>,
    swarmRunning: Boolean,
    swarmCount: Int,
    headline: String,
    failed: Boolean,
    failedPersonName: String = personNames.firstOrNull().orEmpty(),
): String {
    val state = browserSwarmHeaderState(
        personNames = personNames,
        members = members,
        swarmRunning = swarmRunning,
        headline = headline,
    )
    return when (state.kind) {
        BrowserSwarmHeaderKind.AllDone -> if (failed) {
            stringResource(R.string.agent_mode_computer_failed, failedPersonName)
        } else {
            stringResource(R.string.browser_desk_swarm_completed)
        }
        BrowserSwarmHeaderKind.MemberDone ->
            stringResource(R.string.browser_preview_tab_done, state.personName)
        BrowserSwarmHeaderKind.Working -> {
            val label = stringResource(R.string.browser_desk_swarm_count, swarmCount)
            if (state.headline.isNotBlank()) "$label · ${state.headline}" else label
        }
    }
}

@Composable
internal fun SubagentCollapsedHeader(
    people: List<SubagentPersonUi>,
    title: String,
    failed: Boolean,
    isRunning: Boolean,
    onClick: () -> Unit,
    showCollapseChevron: Boolean = false,
    expanded: Boolean = true,
    showEverMeMark: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AnimatedContent(
            targetState = people,
            transitionSpec = {
                (slideInHorizontally(tween(280)) { width -> width / 3 } + fadeIn(tween(280)))
                    .togetherWith(
                        slideOutHorizontally(tween(180)) { width -> -width / 3 } + fadeOut(tween(180)),
                    )
                    .using(SizeTransform(clip = false))
            },
            contentKey = { visible -> visible.joinToString { person -> "${person.index}:${person.name}" } },
            label = "subagent_header_avatars",
        ) { visiblePeople ->
            SubagentAvatarStack(people = visiblePeople)
        }
        AnimatedContent(
            targetState = title,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                (slideInHorizontally(tween(240)) { width -> width / 4 } + fadeIn(tween(220)))
                    .togetherWith(
                        slideOutHorizontally(tween(160)) { width -> -width / 4 } + fadeOut(tween(140)),
                    )
                    .using(SizeTransform(clip = false))
            },
            label = "subagent_header_title",
        ) { visibleTitle ->
            Text(
                text = visibleTitle,
                style = MaterialTheme.typography.bodyMedium,
                color = AetherOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showEverMeMark) {
            Image(
                painter = painterResource(R.drawable.everme_mark),
                contentDescription = stringResource(R.string.agent_mode_reviewing_everme),
                modifier = Modifier.size(18.dp),
            )
        } else if (isRunning) {
            LemniscateBloomLoader(size = 18.dp)
        } else if (failed) {
            Icon(
                imageVector = Icons.Rounded.Error,
                contentDescription = null,
                tint = AetherError,
                modifier = Modifier.size(17.dp),
            )
        }
        if (showCollapseChevron) {
            val rotation by animateFloatAsState(
                targetValue = if (expanded) 0f else 180f,
                animationSpec = tween(220),
                label = "subagent_header_chevron",
            )
            Icon(
                imageVector = Icons.Rounded.KeyboardDoubleArrowDown,
                contentDescription = stringResource(
                    if (expanded) R.string.agent_work_collapse else R.string.agent_work_expand,
                ),
                tint = AetherOnSurfaceVariant,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { rotationZ = rotation },
            )
        }
    }
}

@Composable
private fun SubagentInlineSingleBody(
    toolInvocation: ChatToolInvocation,
    info: SubagentLaunchInfo,
    failed: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SubagentMetaSection(info)
        if (info.prompt.isNotBlank()) {
            SubagentTextSection(
                label = if (info.isSwarm) {
                    stringResource(R.string.subagent_card_prompt_template_label)
                } else {
                    stringResource(R.string.subagent_card_prompt_label)
                },
                text = info.prompt,
            )
        }
        SubagentResultSection(
            toolInvocation = toolInvocation,
            info = info,
        )
        if (failed && !toolInvocation.isRunning) {
            Icon(
                imageVector = Icons.Rounded.Error,
                contentDescription = null,
                tint = AetherError,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

@Composable
private fun SubagentInlineMemberBody(
    toolInvocation: ChatToolInvocation,
    info: SubagentLaunchInfo,
    person: SubagentPersonUi,
    failed: Boolean,
) {
    val member = person.member
    val memberRunning = toolInvocation.isRunning && member?.result == null
    val memberFailed = member?.result?.succeeded == false ||
        (!toolInvocation.isRunning && member?.result == null && failed)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (member != null && member.prompt.isNotBlank()) {
            SubagentTextSection(
                label = stringResource(R.string.subagent_card_member_prompt),
                text = member.prompt,
            )
        } else if (info.prompt.isNotBlank()) {
            SubagentTextSection(
                label = stringResource(R.string.subagent_card_prompt_label),
                text = info.prompt,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.subagent_card_member_activity),
                style = MaterialTheme.typography.labelMedium,
                color = AetherOnSurfaceVariant,
            )
            val memberResult = member?.result
            when {
                memberRunning -> Text(
                    text = stringResource(R.string.subagent_card_running),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AetherOnSurfaceVariant,
                )
                memberResult != null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(AetherSurfaceHigh)
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = swarmOutcomeLabel(memberResult.outcome),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = if (memberResult.succeeded) AetherSecondary else AetherError,
                        )
                        if (memberResult.body.isNotBlank()) {
                            SelectionContainer {
                                Text(
                                    text = memberResult.body,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AetherOnSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                else -> SubagentTextPanel(
                    text = subagentResultText(
                        toolInvocation.outputJson,
                        stringResource(R.string.subagent_card_no_result),
                    ),
                )
            }
        }
        if (memberFailed) {
            Icon(
                imageVector = Icons.Rounded.Error,
                contentDescription = null,
                tint = AetherError,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

@Composable
internal fun SubagentAvatarStack(people: List<SubagentPersonUi>) {
    val visible = people.take(CollapsedAvatarLimit)
    val stackWidth = SubagentAvatarSize + SubagentAvatarOverlap * (visible.size - 1).coerceAtLeast(0)
    Box(
        modifier = Modifier
            .width(stackWidth)
            .height(SubagentAvatarSize),
    ) {
        visible.forEachIndexed { index, person ->
            Box(
                modifier = Modifier
                    .offset(x = SubagentAvatarOverlap * index)
                    .zIndex(index.toFloat())
                    .border(2.dp, AetherSurface, CircleShape)
                    .clip(CircleShape),
            ) {
                HumationAvatarImage(
                    spec = person.avatar,
                    size = SubagentAvatarSize,
                    contentDescription = person.name,
                )
            }
        }
    }
}

/** Lifecycle state of one row in the subagent member list. */
private enum class SubagentMemberState { Running, Done, Failed }

@Composable
private fun SubagentMemberRow(
    person: SubagentPersonUi,
    callRunning: Boolean,
    callFailed: Boolean,
    onClick: () -> Unit,
) {
    val member = person.member
    val state = when {
        member == null -> when {
            callRunning -> SubagentMemberState.Running
            callFailed -> SubagentMemberState.Failed
            else -> SubagentMemberState.Done
        }
        member.result != null -> if (member.result.succeeded) {
            SubagentMemberState.Done
        } else {
            SubagentMemberState.Failed
        }
        callRunning -> SubagentMemberState.Running
        callFailed -> SubagentMemberState.Failed
        else -> SubagentMemberState.Done
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HumationAvatarImage(
            spec = person.avatar,
            size = SubagentAvatarSize,
            contentDescription = person.name,
        )
        Text(
            text = person.name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = AetherOnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Crossfade(
            targetState = state,
            animationSpec = tween(320),
            label = "subagent_member_state",
        ) { current ->
            when (current) {
                SubagentMemberState.Running -> LemniscateBloomLoader(
                    size = 20.dp,
                    // Golden-ratio spread so rows never orbit in lockstep.
                    phaseOffset = (person.index * 0.618f) % 1f,
                )
                SubagentMemberState.Done -> Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = AetherOnSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                SubagentMemberState.Failed -> Icon(
                    imageVector = Icons.Rounded.Error,
                    contentDescription = null,
                    tint = AetherError,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun subagentCardTitle(info: SubagentLaunchInfo): String = when {
    info.isSwarm -> stringResource(
        R.string.subagent_card_title_swarm,
        subagentDisplayCaption(info.description),
        info.agentCount,
    )
    else -> subagentDisplayCaption(info.description).ifBlank {
        info.prompt.trim().take(32)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubagentDetailSheet(
    toolInvocation: ChatToolInvocation,
    info: SubagentLaunchInfo,
    people: List<SubagentPersonUi>,
    failed: Boolean,
    guiSteps: List<PhoneGuiStepUi> = emptyList(),
    listening: Boolean = false,
    listenTranscript: String = "",
    listenVisualOnly: Boolean = false,
    initialPersonIndex: Int = -1,
    onDismiss: () -> Unit,
) {
    val initialIndex = initialPersonIndex.takeIf { it >= 0 }
        ?: people.indexOfFirst { person -> person.crewRole == AgentModeCrewRole.Phone }
            .takeIf { it >= 0 } ?: 0
    var selectedIndex by rememberSaveable(toolInvocation.id, people.size) { mutableIntStateOf(initialIndex) }
    val selectedPerson = people.getOrNull(selectedIndex.coerceIn(0, (people.size - 1).coerceAtLeast(0)))
        ?: people.firstOrNull()
    var selectedGuiStep by remember(toolInvocation.id, selectedPerson?.index) {
        mutableStateOf<PhoneGuiStepUi?>(null)
    }
    val memberFailed = selectedPerson?.member?.result?.succeeded == false ||
        (!toolInvocation.isRunning && selectedPerson?.member?.result == null && failed)
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
                    val showGuiSteps = guiSteps.isNotEmpty() &&
                        (person.crewRole == AgentModeCrewRole.Phone || person.crewRole == null)
                    val listenBodyText = resolveAgentModeListenBodyText(
                        transcript = listenTranscript,
                        listening = listening,
                        visualOnly = listenVisualOnly,
                        waitingText = stringResource(R.string.agent_mode_listen_waiting),
                        visualOnlyText = stringResource(R.string.agent_mode_listen_visual_only),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        SubagentSheetHeader(
                            avatar = person.avatar,
                            name = person.name,
                            title = when (person.crewRole) {
                                AgentModeCrewRole.Lead -> subagentDisplayCaption(info.description)
                                    .ifBlank { info.prompt.trim().take(32) }
                                AgentModeCrewRole.Listen -> if (listenTranscript.isNotBlank()) {
                                    stringResource(R.string.phone_desk_listening, person.name)
                                } else {
                                    listenBodyText
                                }
                                else -> if (info.isSwarm) {
                                    subagentDisplayCaption(
                                        person.member?.item.orEmpty().ifBlank { info.description },
                                    )
                                } else {
                                    subagentCardTitle(info)
                                }
                            },
                            failed = if (info.isSwarm && person.crewRole != AgentModeCrewRole.Listen) {
                                memberFailed
                            } else {
                                failed && person.crewRole != AgentModeCrewRole.Listen
                            },
                        )
                        when (person.crewRole) {
                            AgentModeCrewRole.Lead -> SubagentInlineSingleBody(
                                toolInvocation = toolInvocation,
                                info = info,
                                failed = failed,
                            )
                            AgentModeCrewRole.Listen -> SubagentTextPanel(text = listenBodyText)
                            AgentModeCrewRole.Phone -> {
                                if (person.member != null) {
                                    SubagentMetaSection(info)
                                    SubagentInlineMemberBody(
                                        toolInvocation = toolInvocation,
                                        info = info,
                                        person = person,
                                        failed = memberFailed,
                                    )
                                } else if (guiSteps.isEmpty()) {
                                    SubagentInlineSingleBody(
                                        toolInvocation = toolInvocation,
                                        info = info,
                                        failed = failed,
                                    )
                                }
                            }
                            null -> {
                                if (person.member != null) {
                                    SubagentMetaSection(info)
                                    SubagentInlineMemberBody(
                                        toolInvocation = toolInvocation,
                                        info = info,
                                        person = person,
                                        failed = memberFailed,
                                    )
                                } else {
                                    SubagentInlineSingleBody(
                                        toolInvocation = toolInvocation,
                                        info = info,
                                        failed = failed,
                                    )
                                }
                            }
                        }
                        if (showGuiSteps) {
                            PhoneGuiZAxis(
                                steps = guiSteps,
                                selectedStepId = selectedGuiStep?.id,
                                onStepClick = { step ->
                                    selectedGuiStep = if (selectedGuiStep?.id == step.id) null else step
                                },
                            )
                            selectedGuiStep?.let { step ->
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = stringResource(R.string.phone_gui_step_thought_title),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = AetherOnSurfaceVariant,
                                    )
                                    SelectionContainer {
                                        Text(
                                            text = step.thought,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(18.dp))
                                                .background(AetherSurfaceHigh)
                                                .padding(14.dp),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = AetherOnSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            AetherSheetDragHandleScrim()
        }
    }
}

@Composable
internal fun SwarmPersonCapsule(
    person: SubagentPersonUi,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .border(
                1.dp,
                if (selected) AetherPrimary.copy(alpha = 0.55f) else AetherOnSurfaceVariant.copy(alpha = 0.18f),
                shape,
            )
            .background(if (selected) AetherOnSurface.copy(alpha = 0.06f) else Color.Transparent)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HumationAvatarImage(
            spec = person.avatar,
            size = 22.dp,
            contentDescription = person.name,
        )
        Text(
            text = person.name,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = AetherOnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubagentMemberDetailSheet(
    toolInvocation: ChatToolInvocation,
    info: SubagentLaunchInfo,
    person: SubagentPersonUi,
    member: SwarmMemberView,
    memberRunning: Boolean,
    memberFailed: Boolean,
    onDismiss: () -> Unit,
) {
    val title = subagentDisplayCaption(member.item).ifBlank { info.description }
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
                SubagentSheetHeader(
                    avatar = person.avatar,
                    name = person.name,
                    title = title,
                    failed = memberFailed,
                )
                SubagentMetaSection(info)
                if (member.prompt.isNotBlank()) {
                    SubagentTextSection(
                        label = stringResource(R.string.subagent_card_member_prompt),
                        text = member.prompt,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.subagent_card_member_activity),
                        style = MaterialTheme.typography.labelMedium,
                        color = AetherOnSurfaceVariant,
                    )
                    val memberResult = member.result
                    when {
                        memberRunning -> Text(
                            text = stringResource(R.string.subagent_card_running),
                            style = MaterialTheme.typography.bodyMedium,
                            color = AetherOnSurfaceVariant,
                        )
                        memberResult != null -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(AetherSurfaceHigh)
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = swarmOutcomeLabel(memberResult.outcome),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = if (memberResult.succeeded) AetherSecondary else AetherError,
                                )
                                if (memberResult.body.isNotBlank()) {
                                    SelectionContainer {
                                        Text(
                                            text = memberResult.body,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = AetherOnSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        else -> SubagentTextPanel(
                            text = subagentResultText(
                                toolInvocation.outputJson,
                                stringResource(R.string.subagent_card_no_result),
                            ),
                        )
                    }
                }
            }
            AetherSheetDragHandleScrim()
        }
    }
}

@Composable
private fun SubagentSheetHeader(
    avatar: PersonaAvatarSpec,
    name: String,
    title: String,
    failed: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HumationAvatarImage(
            spec = avatar,
            size = 36.dp,
            contentDescription = name,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                color = AetherOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = AetherOnSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (failed) {
            Icon(
                imageVector = Icons.Rounded.Error,
                contentDescription = null,
                tint = AetherError,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

@Composable
private fun SubagentMetaSection(info: SubagentLaunchInfo) {
    if (info.model.isBlank()) return
    Text(
        text = stringResource(R.string.subagent_card_model_label) + ": " + info.model,
        style = MaterialTheme.typography.labelMedium,
        color = AetherOnSurfaceVariant,
    )
}

@Composable
internal fun SubagentTextSection(
    label: String,
    text: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = AetherOnSurfaceVariant,
        )
        SelectionContainer {
            Text(
                text = text,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(AetherSurfaceHigh)
                    .padding(14.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = AetherOnSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SubagentItemsSection(items: List<String>) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    val visibleItems = if (showAll) items else items.take(SwarmItemsCollapsedCount)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.subagent_card_items_label, items.size),
            style = MaterialTheme.typography.labelMedium,
            color = AetherOnSurfaceVariant,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(AetherSurfaceHigh)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            visibleItems.forEach { item ->
                Text(
                    text = "• $item",
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                )
            }
            if (!showAll && items.size > SwarmItemsCollapsedCount) {
                Text(
                    text = stringResource(R.string.subagent_card_show_all_items, items.size),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = AetherPrimary,
                    modifier = Modifier.clickable { showAll = true },
                )
            }
        }
    }
}

@Composable
private fun SubagentResultSection(
    toolInvocation: ChatToolInvocation,
    info: SubagentLaunchInfo,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.subagent_card_result_label),
            style = MaterialTheme.typography.labelMedium,
            color = AetherOnSurfaceVariant,
        )
        when {
            toolInvocation.isRunning -> Text(
                text = stringResource(R.string.subagent_card_running),
                style = MaterialTheme.typography.bodyMedium,
                color = AetherOnSurfaceVariant,
            )
            info.isSwarm -> SubagentSwarmResultContent(outputJson = toolInvocation.outputJson)
            else -> SubagentTextPanel(
                text = subagentResultText(toolInvocation.outputJson, stringResource(R.string.subagent_card_no_result)),
            )
        }
    }
}

@Composable
private fun SubagentSwarmResultContent(outputJson: String) {
    val noResultLabel = stringResource(R.string.subagent_card_no_result)
    val resultText = remember(outputJson, noResultLabel) { subagentResultText(outputJson, noResultLabel) }
    val result = remember(resultText) { parseAgentSwarmResult(resultText) }
    if (result == null) {
        SubagentTextPanel(text = resultText)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (result.summary.isNotBlank()) {
            Text(
                text = result.summary,
                style = MaterialTheme.typography.labelMedium,
                color = AetherOnSurfaceVariant,
            )
        }
        result.members.forEach { member ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(AetherSurfaceHigh)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = if (member.succeeded) Icons.Rounded.CheckCircle else Icons.Rounded.Error,
                        contentDescription = null,
                        tint = if (member.succeeded) AetherOnSurfaceVariant else AetherError,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = subagentDisplayCaption(
                            member.item.ifBlank { member.agentId }.ifBlank { member.outcome },
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = AetherOnSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = swarmOutcomeLabel(member.outcome),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (member.succeeded) AetherSecondary else AetherError,
                    )
                }
                if (member.body.isNotBlank()) {
                    SelectionContainer {
                        Text(
                            text = member.body,
                            style = MaterialTheme.typography.bodySmall,
                            color = AetherOnSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun swarmOutcomeLabel(outcome: String): String = when (outcome) {
    "completed" -> stringResource(R.string.subagent_card_outcome_completed)
    "failed" -> stringResource(R.string.subagent_card_outcome_failed)
    "aborted" -> stringResource(R.string.subagent_card_outcome_aborted)
    else -> outcome
}

@Composable
private fun SubagentTextPanel(text: String) {
    SelectionContainer {
        Text(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(AetherSurfaceHigh)
                .padding(14.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = AetherOnSurfaceVariant,
        )
    }
}

/** Display text of a finished call's output, tolerant of JSON or plain text. */
private fun subagentResultText(outputJson: String, noResultLabel: String): String {
    val output = runCatching { JSONObject(outputJson) }.getOrNull()
    return toolInvocationResultText(output, outputJson, noResultLabel)
}

private fun Path.addGuiStepConnector(spec: GuiStepConnectorSpec) {
    val points = spec.points
    if (points.isEmpty()) return
    moveTo(points.first().first, points.first().second)
    if (points.size == 1) return
    if (points.size == 2 || spec.cornerRadiusPx <= 0f) {
        for (index in 1 until points.size) {
            lineTo(points[index].first, points[index].second)
        }
        return
    }
    for (index in 1 until points.lastIndex) {
        val previous = points[index - 1]
        val current = points[index]
        val next = points[index + 1]
        val d1x = current.first - previous.first
        val d1y = current.second - previous.second
        val d2x = next.first - current.first
        val d2y = next.second - current.second
        val len1 = hypot(d1x, d1y)
        val len2 = hypot(d2x, d2y)
        val radius = minOf(spec.cornerRadiusPx, len1 / 2f, len2 / 2f)
        if (radius < 1f || len1 < 1f || len2 < 1f) {
            lineTo(current.first, current.second)
            continue
        }
        lineTo(
            current.first - d1x / len1 * radius,
            current.second - d1y / len1 * radius,
        )
        quadraticTo(
            current.first,
            current.second,
            current.first + d2x / len2 * radius,
            current.second + d2y / len2 * radius,
        )
    }
    lineTo(points.last().first, points.last().second)
}

@Composable
internal fun PhoneGuiZAxis(
    steps: List<PhoneGuiStepUi>,
    selectedStepId: String? = null,
    onStepClick: (PhoneGuiStepUi) -> Unit,
) {
    val connectorColor = AetherOnSurfaceVariant.copy(alpha = 0.45f)
    val connectors = remember { arrayOf(emptyList<GuiStepConnectorSpec>()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.phone_gui_steps_label),
            style = MaterialTheme.typography.labelMedium,
            color = AetherOnSurfaceVariant,
        )
        Layout(
            modifier = Modifier
                .fillMaxWidth()
                .drawWithContent {
                    drawContent()
                    val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 7f), 0f)
                    val stroke = Stroke(
                        width = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                        pathEffect = dash,
                    )
                    connectors[0].forEach { spec ->
                        val path = Path().apply { addGuiStepConnector(spec) }
                        drawPath(path = path, color = connectorColor, style = stroke)
                    }
                },
            content = {
                steps.forEachIndexed { index, step ->
                    key(step.id) {
                        PhoneGuiStepChip(
                            index = step.sequence.takeIf { it > 0 } ?: (index + 1),
                            step = step,
                            selected = step.id == selectedStepId,
                            onClick = { onStepClick(step) },
                        )
                    }
                }
            },
        ) { measurables, constraints ->
            val gapX = 8.dp.roundToPx()
            val gapY = 14.dp.roundToPx()
            val outset = 3.dp.toPx()
            val cornerRadius = 10.dp.toPx()
            val maxW = constraints.maxWidth
            val chipMaxW = minOf((maxW * 2) / 3, 240.dp.roundToPx()).coerceAtLeast(1)
            val placeables = measurables.map { measurable ->
                measurable.measure(
                    constraints.copy(
                        minWidth = 0,
                        minHeight = 0,
                        maxWidth = chipMaxW,
                    ),
                )
            }
            data class PackedRow(val indices: MutableList<Int>, var width: Int, var height: Int)
            val rows = mutableListOf<PackedRow>()
            var current = PackedRow(mutableListOf(), 0, 0)
            placeables.forEachIndexed { index, placeable ->
                val nextWidth = if (current.indices.isEmpty()) {
                    placeable.width
                } else {
                    current.width + gapX + placeable.width
                }
                if (current.indices.isNotEmpty() && nextWidth > maxW) {
                    rows += current
                    current = PackedRow(mutableListOf(), 0, 0)
                }
                if (current.indices.isEmpty()) {
                    current.width = placeable.width
                    current.height = placeable.height
                } else {
                    current.width += gapX + placeable.width
                    current.height = maxOf(current.height, placeable.height)
                }
                current.indices += index
            }
            if (current.indices.isNotEmpty()) rows += current
            val positions = Array(placeables.size) { 0 to 0 }
            var totalHeight = 0
            rows.forEachIndexed { rowIndex, row ->
                val rtl = rowIndex % 2 == 1
                val visual = if (rtl) row.indices.asReversed() else row.indices
                var x = if (rtl) (maxW - row.width).coerceAtLeast(0) else 0
                visual.forEach { index ->
                    val placeable = placeables[index]
                    val y = totalHeight + (row.height - placeable.height) / 2
                    positions[index] = x to y
                    x += placeable.width + gapX
                }
                totalHeight += row.height + gapY
            }
            if (rows.isNotEmpty()) totalHeight -= gapY
            val boxes = placeables.mapIndexed { index, placeable ->
                val (x, y) = positions[index]
                GuiStepChipBox(
                    left = x.toFloat(),
                    top = y.toFloat(),
                    width = placeable.width.toFloat(),
                    height = placeable.height.toFloat(),
                )
            }
            connectors[0] = guiStepConnectors(
                boxes = boxes,
                rowOf = IntArray(placeables.size) { index ->
                    rows.indexOfFirst { row -> index in row.indices }.coerceAtLeast(0)
                },
                outsetPx = outset,
                cornerRadiusPx = cornerRadius,
            )
            layout(maxW, totalHeight.coerceAtLeast(0)) {
                placeables.forEachIndexed { index, placeable ->
                    val (x, y) = positions[index]
                    placeable.placeRelative(x, y)
                }
            }
        }
    }
}

@Composable
private fun PhoneGuiStepChip(
    index: Int,
    step: PhoneGuiStepUi,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (step.ok) AetherOnSurface else AetherError
    val borderAlpha = if (selected) 0.55f else 0.22f
    Row(
        modifier = Modifier
            .widthIn(max = 240.dp)
            .clip(RoundedCornerShape(999.dp))
            .border(1.dp, AetherOnSurfaceVariant.copy(alpha = borderAlpha), RoundedCornerShape(999.dp))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = index.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = tint.copy(alpha = 0.72f),
        )
        Icon(
            imageVector = phoneGuiStepIcon(step.action),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = step.label,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            modifier = Modifier.weight(1f, fill = false),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun phoneGuiStepIcon(action: String) = when {
    action == "launch" -> Icons.Rounded.OpenInNew
    action == "tap" || action == "click_node" -> Icons.Rounded.TouchApp
    action == "swipe" || action == "fling" || action == "swipe_up" || action == "swipe_down" ->
        Icons.Rounded.SwapVert
    action == "swipe_left" || action == "swipe_right" -> Icons.Rounded.SwapHoriz
    action == "search" -> Icons.Rounded.Search
    action == "text" || action == "clear_text" || action == "key" -> Icons.Rounded.Keyboard
    action == "back" -> Icons.AutoMirrored.Rounded.ArrowBack
    action == "home" -> Icons.Rounded.Home
    action == "wait_for_label" -> Icons.Rounded.HourglassEmpty
    action == "dump_tree" || action == "list_targets" -> Icons.Rounded.List
    else -> Icons.Rounded.PlayArrow
}
