package kira.ditto.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kira.ditto.R
import kira.ditto.data.SessionPlanEntry
import kira.ditto.data.sessionPlanEntryIsCompleted
import kira.ditto.data.kimi.PendingElicitationRequest
import kira.ditto.data.kimi.PendingElicitationQuestion
import kira.ditto.data.kimi.PendingElicitationOption
import kira.ditto.data.kimi.PendingPermissionOption
import kira.ditto.data.kimi.PendingPermissionRequest
import kira.ditto.ui.theme.AetherError
import kira.ditto.ui.theme.AetherOnPrimary
import kira.ditto.ui.theme.AetherOnSurface
import kira.ditto.ui.theme.AetherOnSurfaceVariant
import kira.ditto.ui.theme.AetherOutlineSoft
import kira.ditto.ui.theme.AetherPrimary
import kira.ditto.ui.theme.AetherScrim
import kira.ditto.ui.theme.AetherSecondary
import kira.ditto.ui.theme.AetherSurface
import kira.ditto.ui.theme.AetherSurfaceHigh
import kira.ditto.ui.theme.AetherSurfaceHigher
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

private const val MaxVisiblePermissionCards = 8
private const val MaxVisibleElicitationCards = 8
private const val NestedDiffPrefetchItemCount = 12
private const val DiffCollapsedLineThreshold = 200
private const val DiffCollapsedEdgeLines = 5
private const val DiffLcsMaxCells = 250_000

// ---------------------------------------------------------------------------
// Permission request cards
// ---------------------------------------------------------------------------

/**
 * Floating stack of pending agent permission requests above the composer.
 * At most [MaxVisiblePermissionCards] cards are shown; the rest collapse into
 * a "+N more" line. Answering any option resolves that request only.
 */
@Composable
fun AgentPermissionRequestStack(
    requests: List<PendingPermissionRequest>,
    onAnswer: (requestId: String, optionId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = requests.isNotEmpty(),
        modifier = modifier,
        enter = fadeIn(animationSpec = tween(200)) + expandVertically(
            animationSpec = tween(240),
            expandFrom = Alignment.Bottom,
        ),
        exit = fadeOut(animationSpec = tween(160)) + shrinkVertically(
            animationSpec = tween(200),
            shrinkTowards = Alignment.Bottom,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp)
                .animateContentSize(animationSpec = tween(240)),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            requests.take(MaxVisiblePermissionCards).forEach { request ->
                PermissionRequestCard(
                    request = request,
                    onAnswer = { optionId -> onAnswer(request.requestId, optionId) },
                )
            }
            val remaining = requests.size - MaxVisiblePermissionCards
            if (remaining > 0) {
                Text(
                    text = stringResource(R.string.chat_permission_requests_more, remaining),
                    style = MaterialTheme.typography.labelSmall,
                    color = AetherOnSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun PermissionRequestCard(
    request: PendingPermissionRequest,
    onAnswer: (optionId: String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(12.dp, RoundedCornerShape(20.dp), ambientColor = AetherScrim, spotColor = AetherScrim)
            .clip(RoundedCornerShape(20.dp))
            .background(AetherSurface.copy(alpha = 0.98f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = permissionToolKindIcon(request.toolCallKind),
                contentDescription = null,
                tint = AetherOnSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                val question = request.questionText.trim()
                val title = question.ifBlank {
                    request.toolCallTitle.ifBlank {
                        stringResource(R.string.chat_permission_request_title)
                    }
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AetherOnSurface,
                )
                val subtitle = when {
                    question.isNotBlank() &&
                        request.toolCallTitle.isNotBlank() &&
                        !request.toolCallTitle.equals(question, ignoreCase = true) ->
                        request.toolCallTitle
                    request.toolCallKind.isNotBlank() && question.isBlank() -> request.toolCallKind
                    else -> ""
                }
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = AetherOnSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            request.options.forEach { option ->
                PermissionOptionButton(
                    option = option,
                    onClick = { onAnswer(option.optionId) },
                )
            }
        }
    }
}

private fun permissionToolKindIcon(kind: String): ImageVector = when (kind.trim().lowercase()) {
    "read" -> Icons.Rounded.Description
    "edit" -> Icons.Rounded.Edit
    "delete" -> Icons.Rounded.Delete
    "move" -> Icons.Rounded.DriveFileMove
    "search" -> Icons.Rounded.Search
    "execute" -> Icons.Rounded.Terminal
    "think" -> Icons.Rounded.Psychology
    "fetch" -> Icons.Rounded.Cloud
    "switch_mode" -> Icons.Rounded.SwapHoriz
    else -> Icons.Rounded.Build
}

/**
 * Pill button for one permission option. Visual weight follows the ACP option
 * kind: allow_always is filled primary, reject_* gets an error outline, and
 * everything else (including plan_review choices like plan_opt_<i>) is a
 * neutral tonal pill. The label is the CLI-provided [option name] verbatim.
 */
@Composable
private fun PermissionOptionButton(
    option: PendingPermissionOption,
    onClick: () -> Unit,
) {
    val kind = option.kind.trim().lowercase()
    val filled = kind == "allow_always"
    val destructive = kind.startsWith("reject")
    val backgroundColor = when {
        filled -> AetherPrimary
        destructive -> Color.Transparent
        else -> AetherSurfaceHigher
    }
    val contentColor = when {
        filled -> AetherOnPrimary
        destructive -> AetherError
        else -> AetherOnSurface
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(backgroundColor)
            .then(
                if (destructive) {
                    Modifier.border(1.dp, AetherError.copy(alpha = 0.72f), RoundedCornerShape(999.dp))
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = option.name,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------------
// Elicitation request cards
// ---------------------------------------------------------------------------

private val ElicitationCardEnter = fadeIn(animationSpec = tween(200)) +
    slideInVertically(animationSpec = tween(240)) { it / 6 } +
    scaleIn(animationSpec = tween(240), initialScale = 0.96f) +
    expandVertically(animationSpec = tween(240), expandFrom = Alignment.Bottom)

private val ElicitationCardExit = fadeOut(animationSpec = tween(160)) +
    slideOutVertically(animationSpec = tween(200)) { it / 6 } +
    scaleOut(animationSpec = tween(200), targetScale = 0.96f) +
    shrinkVertically(animationSpec = tween(200), shrinkTowards = Alignment.Bottom)

internal const val ElicitationOtherSentinel = "__aether_other__"

internal fun isElicitationOtherChoice(label: String, value: String = ""): Boolean {
    val text = label.trim()
    val token = value.trim()
    return text.equals("其他", ignoreCase = true) ||
        text.equals("其它", ignoreCase = true) ||
        text.equals("Other", ignoreCase = true) ||
        token.equals("other", ignoreCase = true) ||
        token == ElicitationOtherSentinel
}

internal fun elicitationOptionsWithOther(
    options: List<PendingElicitationOption>,
    otherLabel: String,
): List<PendingElicitationOption> {
    if (options.any { isElicitationOtherChoice(it.label, it.value) }) return options
    return options + PendingElicitationOption(value = ElicitationOtherSentinel, label = otherLabel)
}

data class ElicitationOtherPending(
    val requestId: String,
    val request: PendingElicitationRequest,
    val questionId: String,
    val selections: Map<String, Set<String>>,
)

/**
 * Floating stack of pending agent elicitation forms above the composer,
 * mirroring [AgentPermissionRequestStack]. Every pending request gets its own
 * card; at most [MaxVisibleElicitationCards] are shown and the rest collapse
 * into a "+N more" line. Answering or skipping resolves that request only and
 * animates its card out; departing cards stay composed until their exit
 * animation finishes, so rapid consecutive answers cannot glitch the stack.
 */
@Composable
fun AgentElicitationRequestStack(
    requests: List<PendingElicitationRequest>,
    onSubmit: (requestId: String, answers: JSONObject) -> Unit,
    onSkip: (requestId: String) -> Unit,
    onSelectOther: (ElicitationOtherPending?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val visibilityStates = remember { mutableStateMapOf<String, MutableTransitionState<Boolean>>() }
    val retainedRequests = remember { mutableStateMapOf<String, PendingElicitationRequest>() }

    LaunchedEffect(requests) {
        val liveIds = requests.mapTo(hashSetOf()) { it.requestId }
        requests.forEach { request ->
            retainedRequests[request.requestId] = request
            visibilityStates.getOrPut(request.requestId) {
                MutableTransitionState(initialState = false)
            }.targetState = true
        }
        visibilityStates.forEach { (requestId, state) ->
            if (requestId !in liveIds) state.targetState = false
        }
    }
    // Purge cards whose exit animation has fully settled.
    LaunchedEffect(Unit) {
        while (true) {
            snapshotFlow {
                visibilityStates.entries.any { it.value.isIdle && !it.value.targetState }
            }.first { settled -> settled }
            visibilityStates.entries
                .filter { it.value.isIdle && !it.value.targetState }
                .map { it.key }
                .forEach { requestId ->
                    visibilityStates.remove(requestId)
                    retainedRequests.remove(requestId)
                }
        }
    }

    AnimatedVisibility(
        visible = requests.isNotEmpty(),
        modifier = modifier,
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(160)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp)
                .animateContentSize(animationSpec = tween(240)),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            var activeShown = 0
            visibilityStates.entries.toList().forEach { (requestId, state) ->
                val request = retainedRequests[requestId] ?: return@forEach
                val show = if (state.targetState) {
                    activeShown += 1
                    activeShown <= MaxVisibleElicitationCards
                } else {
                    // Departing card: keep it composed until the exit settles.
                    true
                }
                if (show) {
                    key(requestId) {
                        AnimatedVisibility(
                            visibleState = state,
                            enter = ElicitationCardEnter,
                            exit = ElicitationCardExit,
                        ) {
                            ElicitationRequestCard(
                                request = request,
                                onSubmit = { answers -> onSubmit(requestId, answers) },
                                onSkip = { onSkip(requestId) },
                                onSelectOther = { pending ->
                                    onSelectOther(pending)
                                },
                            )
                        }
                    }
                }
            }
            val remaining = requests.size - MaxVisibleElicitationCards
            if (remaining > 0) {
                Text(
                    text = stringResource(R.string.chat_elicitation_requests_more, remaining),
                    style = MaterialTheme.typography.labelSmall,
                    color = AetherOnSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
    }
}

/**
 * One elicitation form card. Single-select questions render as radio rows,
 * multi-select as checkboxes; the submit button stays disabled until every
 * required question has an answer. Skipping cancels with a null answer.
 */
@Composable
private fun ElicitationRequestCard(
    request: PendingElicitationRequest,
    onSubmit: (answers: JSONObject) -> Unit,
    onSkip: () -> Unit,
    onSelectOther: (ElicitationOtherPending?) -> Unit,
) {
    var selections by remember(request.requestId) {
        mutableStateOf<Map<String, Set<String>>>(emptyMap())
    }
    val otherLabel = stringResource(R.string.chat_elicitation_other)

    fun pendingOther(next: Map<String, Set<String>>): ElicitationOtherPending? {
        val question = request.questions.firstOrNull { item ->
            val options = elicitationOptionsWithOther(item.options, otherLabel)
            next[item.id].orEmpty().any { value ->
                val option = options.firstOrNull { it.value == value }
                isElicitationOtherChoice(option?.label.orEmpty(), value)
            }
        } ?: return null
        return ElicitationOtherPending(
            requestId = request.requestId,
            request = request,
            questionId = question.id,
            selections = next,
        )
    }

    val allRequiredAnswered = request.questions.all { question ->
        if (!question.required) return@all true
        val chosen = selections[question.id].orEmpty()
        chosen.isNotEmpty() && pendingOther(selections)?.questionId != question.id
    }

    fun toggle(question: PendingElicitationQuestion, value: String) {
        val current = selections[question.id].orEmpty()
        val nextChoice = if (question.multiSelect) {
            if (value in current) current - value else current + value
        } else {
            setOf(value)
        }
        val next = selections + (question.id to nextChoice)
        selections = next
        onSelectOther(pendingOther(next))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(12.dp, RoundedCornerShape(20.dp), ambientColor = AetherScrim, spotColor = AetherScrim)
            .clip(RoundedCornerShape(20.dp))
            .background(AetherSurface.copy(alpha = 0.98f))
            .heightIn(max = 440.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val singleQuestion = request.questions.singleOrNull()
        val showMessage = request.message.isNotBlank() &&
            request.questions.none { it.displayText == request.message } &&
            (singleQuestion == null || singleQuestion.displayText != request.message)
        if (showMessage) {
            Text(
                text = request.message,
                style = MaterialTheme.typography.titleSmall,
                color = AetherOnSurface,
            )
        }
        request.questions.forEachIndexed { index, question ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "${index + 1}. ${question.displayText.ifBlank { request.message }.ifBlank { question.title }}",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = AetherOnSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (question.required) {
                        Text(
                            text = stringResource(R.string.chat_elicitation_required),
                            style = MaterialTheme.typography.labelSmall,
                            color = AetherError,
                        )
                    }
                }
                elicitationOptionsWithOther(question.options, otherLabel).forEach { option ->
                    val selected = selections[question.id].orEmpty().contains(option.value)
                    val optionBackground by animateColorAsState(
                        targetValue = if (selected) AetherPrimary.copy(alpha = 0.14f) else Color.Transparent,
                        animationSpec = tween(180),
                        label = "elicitation_option_background",
                    )
                    val optionContentColor by animateColorAsState(
                        targetValue = if (selected) AetherPrimary else AetherOnSurface,
                        animationSpec = tween(180),
                        label = "elicitation_option_content",
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(optionBackground)
                            .clickable { toggle(question, option.value) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (question.multiSelect) {
                            Checkbox(checked = selected, onCheckedChange = null)
                        } else {
                            RadioButton(selected = selected, onClick = null)
                        }
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = optionContentColor,
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onSkip) {
                Text(
                    text = stringResource(R.string.chat_elicitation_skip),
                    color = AetherOnSurfaceVariant,
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (allRequiredAnswered) AetherPrimary else AetherSurfaceHigher)
                    .then(
                        if (allRequiredAnswered) {
                            Modifier.clickable { onSubmit(buildElicitationAnswers(request, selections)) }
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            ) {
                Text(
                    text = stringResource(R.string.chat_elicitation_submit),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (allRequiredAnswered) AetherOnPrimary else AetherOnSurfaceVariant,
                )
            }
        }
    }
}

internal fun buildElicitationAnswers(
    request: PendingElicitationRequest,
    selections: Map<String, Set<String>>,
    otherText: String = "",
): JSONObject {
    val answers = JSONObject()
    val custom = otherText.trim()
    request.questions.forEach { question ->
        val selected = selections[question.id].orEmpty()
        if (selected.isEmpty()) return@forEach
        val resolved = selected.map { value ->
            val option = question.options.firstOrNull { it.value == value }
            val other = isElicitationOtherChoice(option?.label.orEmpty(), value) ||
                isElicitationOtherChoice(option?.label.orEmpty(), option?.value.orEmpty())
            if (other) custom else option?.label ?: value
        }.filter { it.isNotBlank() }
        if (resolved.isEmpty()) return@forEach
        if (question.multiSelect) {
            val array = JSONArray()
            resolved.forEach { array.put(it) }
            answers.put(question.id, array)
        } else {
            answers.put(question.id, resolved.first())
        }
    }
    return answers
}

@Composable
fun McpSecretPromptCard(
    prompt: PendingMcpSecretPromptUi,
    onSubmit: (Map<String, String>) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var values by remember(prompt.sessionId, prompt.slots.map { it.id }) {
        mutableStateOf(prompt.slots.associate { it.id to TextFieldValue("") })
    }
    val ready = prompt.slots.all { slot -> values[slot.id]?.text.orEmpty().isNotBlank() }
    AnimatedVisibility(
        visible = prompt.slots.isNotEmpty(),
        modifier = modifier,
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(160)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(12.dp, RoundedCornerShape(20.dp), ambientColor = AetherScrim, spotColor = AetherScrim)
                .clip(RoundedCornerShape(20.dp))
                .background(AetherSurface.copy(alpha = 0.98f))
                .heightIn(max = 440.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .padding(bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.mcp_secret_prompt_title),
                style = MaterialTheme.typography.titleSmall,
                color = AetherOnSurface,
            )
            Text(
                text = stringResource(R.string.mcp_secret_prompt_body),
                style = MaterialTheme.typography.bodySmall,
                color = AetherOnSurfaceVariant,
            )
            prompt.slots.forEach { slot ->
                val serverLabel = slot.serverName.ifBlank { slot.serverId }
                Text(
                    text = serverLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = AetherOnSurfaceVariant,
                )
                ChatGptTextField(
                    label = mcpSecretSlotLabel(
                        kind = slot.kind,
                        injectKey = slot.injectKey,
                        serverHint = slot.serverHint.ifBlank { slot.serverName },
                    ),
                    value = values[slot.id] ?: TextFieldValue(""),
                    isSecret = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    onValueChange = { next ->
                        values = values + (slot.id to next)
                    },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onSkip) {
                    Text(
                        text = stringResource(R.string.mcp_secret_prompt_skip),
                        color = AetherOnSurfaceVariant,
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (ready) AetherPrimary else AetherSurfaceHigher)
                        .then(
                            if (ready) {
                                Modifier.clickable {
                                    onSubmit(values.mapValues { it.value.text.trim() })
                                }
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                ) {
                    Text(
                        text = stringResource(R.string.mcp_secret_prompt_continue),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (ready) AetherOnPrimary else AetherOnSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Session todo-plan checklist
// ---------------------------------------------------------------------------

/** Renders the agent's latest full-replacement todo plan (plan_update). */
@Composable
fun SessionPlanChecklist(
    entries: List<SessionPlanEntry>,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) return
    val completedCount = entries.count { sessionPlanEntryIsCompleted(it.status) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(AetherSurfaceHigh.copy(alpha = 0.72f))
            .animateContentSize(animationSpec = tween(240))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.List,
                contentDescription = null,
                tint = AetherOnSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = stringResource(R.string.chat_todo_progress_title),
                style = MaterialTheme.typography.labelLarge,
                color = AetherOnSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.chat_plan_progress, completedCount, entries.size),
                style = MaterialTheme.typography.labelSmall,
                color = AetherOnSurfaceVariant,
            )
        }
        entries.forEach { entry ->
            SessionPlanEntryRow(entry)
        }
    }
}

@Composable
private fun SessionPlanEntryRow(entry: SessionPlanEntry) {
    val completed = sessionPlanEntryIsCompleted(entry.status)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        when {
            completed -> Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = AetherOnSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
            else -> Icon(
                imageVector = Icons.Rounded.RadioButtonUnchecked,
                contentDescription = null,
                tint = AetherOnSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(15.dp),
            )
        }
        Text(
            text = entry.content,
            style = MaterialTheme.typography.bodySmall,
            color = if (completed) AetherOnSurfaceVariant else AetherOnSurface,
            textDecoration = if (completed) TextDecoration.LineThrough else null,
            modifier = if (completed) Modifier.alpha(0.62f) else Modifier,
        )
    }
}

internal fun formatCompactTokenCount(tokens: Long): String {
    val safe = tokens.coerceAtLeast(0L)
    return when {
        safe >= 1_000_000L -> trimCompactDecimal(safe / 1_000_000f) + "M"
        safe >= 1_000L -> trimCompactDecimal(safe / 1_000f) + "k"
        else -> safe.toString()
    }
}

private fun trimCompactDecimal(value: Float): String {
    val rounded = (value * 10f).roundToInt() / 10f
    return if (rounded == rounded.toLong().toFloat()) {
        rounded.toLong().toString()
    } else {
        rounded.toString()
    }
}

// ---------------------------------------------------------------------------
// Tool-call diff rendering
// ---------------------------------------------------------------------------

internal enum class DiffLineKind {
    Context,
    Added,
    Removed,
}

internal data class DiffLine(
    val kind: DiffLineKind,
    val text: String,
)

private fun splitDiffLines(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val lines = text.split('\n')
    // "a\n" should diff as one line, not "a" plus a phantom empty line.
    return if (lines.last().isEmpty()) lines.dropLast(1) else lines
}

/**
 * Line-level diff between [oldText] and [newText]. Common prefixes/suffixes
 * are trimmed first; the differing middle is aligned with an LCS table when
 * it is small enough ([DiffLcsMaxCells]), otherwise the middle degrades to
 * "remove old block, add new block" to stay cheap on pathological inputs.
 */
internal fun computeDiffLines(oldText: String, newText: String): List<DiffLine> {
    val oldLines = splitDiffLines(oldText)
    val newLines = splitDiffLines(newText)
    var start = 0
    while (start < oldLines.size && start < newLines.size && oldLines[start] == newLines[start]) {
        start++
    }
    var oldEnd = oldLines.size
    var newEnd = newLines.size
    while (oldEnd > start && newEnd > start && oldLines[oldEnd - 1] == newLines[newEnd - 1]) {
        oldEnd--
        newEnd--
    }
    return buildList {
        for (index in 0 until start) {
            add(DiffLine(DiffLineKind.Context, oldLines[index]))
        }
        addAll(diffMiddle(oldLines.subList(start, oldEnd), newLines.subList(start, newEnd)))
        for (index in oldEnd until oldLines.size) {
            add(DiffLine(DiffLineKind.Context, oldLines[index]))
        }
    }
}

private fun diffMiddle(oldLines: List<String>, newLines: List<String>): List<DiffLine> {
    if (oldLines.isEmpty()) return newLines.map { DiffLine(DiffLineKind.Added, it) }
    if (newLines.isEmpty()) return oldLines.map { DiffLine(DiffLineKind.Removed, it) }
    if (oldLines.size.toLong() * newLines.size.toLong() > DiffLcsMaxCells) {
        return oldLines.map { DiffLine(DiffLineKind.Removed, it) } +
            newLines.map { DiffLine(DiffLineKind.Added, it) }
    }
    val rows = oldLines.size
    val columns = newLines.size
    val table = Array(rows + 1) { IntArray(columns + 1) }
    for (i in rows - 1 downTo 0) {
        for (j in columns - 1 downTo 0) {
            table[i][j] = if (oldLines[i] == newLines[j]) {
                table[i + 1][j + 1] + 1
            } else {
                maxOf(table[i + 1][j], table[i][j + 1])
            }
        }
    }
    val result = ArrayList<DiffLine>(rows + columns)
    var i = 0
    var j = 0
    while (i < rows && j < columns) {
        when {
            oldLines[i] == newLines[j] -> {
                result.add(DiffLine(DiffLineKind.Context, oldLines[i]))
                i++
                j++
            }
            table[i + 1][j] >= table[i][j + 1] -> {
                result.add(DiffLine(DiffLineKind.Removed, oldLines[i]))
                i++
            }
            else -> {
                result.add(DiffLine(DiffLineKind.Added, newLines[j]))
                j++
            }
        }
    }
    while (i < rows) {
        result.add(DiffLine(DiffLineKind.Removed, oldLines[i]))
        i++
    }
    while (j < columns) {
        result.add(DiffLine(DiffLineKind.Added, newLines[j]))
        j++
    }
    return result
}

/** Per-file diff views for a tool invocation's [ToolCallDiff] list. */
@Composable
fun ToolCallDiffSection(
    diffs: List<ToolCallDiff>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        diffs.forEach { diff ->
            ToolCallFileDiff(diff)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolCallFileDiff(diff: ToolCallDiff) {
    val lines = remember(diff.oldText, diff.newText) {
        computeDiffLines(diff.oldText, diff.newText)
    }
    var expanded by rememberSaveable(diff.path, lines.size) { mutableStateOf(false) }
    val collapsible = lines.size > DiffCollapsedLineThreshold
    Column(modifier = Modifier.fillMaxWidth()) {
        if (diff.path.isNotBlank()) {
            Row(
                modifier = Modifier.padding(bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Description,
                    contentDescription = null,
                    tint = AetherOnSurfaceVariant,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = diff.path,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = AetherOnSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(AetherSurfaceHigh),
        ) {
            if (!collapsible || expanded) {
                // Bounded-height LazyColumn keeps very large diffs out of a
                // fully-measured Column; only visible rows are composed.
                // The nested prefetch count lets the outer transcript warm up a
                // screenful of diff rows while it prefetches this card, so the rows
                // are not composed for the first time during the scroll that reveals them.
                LazyColumn(
                    state = rememberLazyListState(
                        prefetchStrategy = remember { LazyListPrefetchStrategy(NestedDiffPrefetchItemCount) },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp)
                        .padding(vertical = 6.dp),
                ) {
                    itemsIndexed(lines, key = { index, _ -> index }) { _, line ->
                        DiffLineRow(line)
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                ) {
                    lines.take(DiffCollapsedEdgeLines).forEach { DiffLineRow(it) }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded = true }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.chat_diff_expand_all, lines.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = AetherPrimary,
                        )
                    }
                    lines.takeLast(DiffCollapsedEdgeLines).forEach { DiffLineRow(it) }
                }
            }
        }
        if (collapsible && expanded) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = false }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.chat_diff_collapse),
                    style = MaterialTheme.typography.labelMedium,
                    color = AetherPrimary,
                )
            }
        }
    }
}

@Composable
private fun DiffLineRow(line: DiffLine) {
    val (prefix, accent, background) = when (line.kind) {
        DiffLineKind.Added -> Triple("+", AetherSecondary, AetherSecondary.copy(alpha = 0.13f))
        DiffLineKind.Removed -> Triple("-", AetherError, AetherError.copy(alpha = 0.11f))
        DiffLineKind.Context -> Triple(" ", AetherOnSurfaceVariant, Color.Transparent)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .padding(horizontal = 10.dp, vertical = 1.dp),
    ) {
        Text(
            text = prefix,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = accent,
            modifier = Modifier.width(14.dp),
        )
        Text(
            text = line.text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = when (line.kind) {
                DiffLineKind.Context -> AetherOnSurface.copy(alpha = 0.72f)
                else -> AetherOnSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
