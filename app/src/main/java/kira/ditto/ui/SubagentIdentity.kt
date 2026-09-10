package kira.ditto.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.intl.Locale
import kira.ditto.data.PersonaAvatarSpec
import kira.ditto.data.PhoneGuiStepUi
import kira.ditto.data.humation.fnv1a
import kira.ditto.data.KimiPhoneSubagentProfileName

internal enum class AgentModeCrewRole {
    Lead,
    Phone,
    Listen,
}

internal val SubagentPersonNamesZh = listOf(
    "苏晚", "林深", "顾言", "沈星", "江澄", "白序", "陆川", "叶知",
    "程予", "方岚", "夏河", "北乔", "南音", "清和", "闻舟", "顾西",
)

internal val SubagentPersonNamesEn = listOf(
    "Mira", "Jules", "Rowan", "Sage", "Quinn", "Eden", "Harlow", "Nico",
    "Wynn", "Ellis", "Remy", "Soren", "Ivy", "Arlo", "Pax", "Noor",
)

internal fun subagentIdentitySeed(
    toolCallId: String,
    memberKey: String,
    index: Int,
): String = listOf(toolCallId, memberKey, index.toString())
    .joinToString(":") { it.trim().ifBlank { "_" } }

internal fun subagentAvatarSpec(seed: String): PersonaAvatarSpec = PersonaAvatarSpec(seed = seed)

internal fun pickStableName(seed: String, names: List<String>): String {
    if (names.isEmpty()) return "Agent"
    return names[(fnv1a(seed) % names.size.toUInt()).toInt()]
}

/** Stable person-library name that skips names already used in this swarm / crew. */
internal fun assignPersonName(
    seed: String,
    names: List<String>,
    occupied: MutableSet<String>,
): String {
    if (names.isEmpty()) return "Agent"
    val start = (fnv1a(seed) % names.size.toUInt()).toInt()
    for (offset in names.indices) {
        val candidate = names[(start + offset) % names.size]
        if (candidate !in occupied) {
            occupied += candidate
            return candidate
        }
    }
    val fallback = names[start]
    occupied += fallback
    return fallback
}

internal fun pickStableDistinctNames(
    firstSeed: String,
    secondSeed: String,
    names: List<String>,
): Pair<String, String> {
    if (names.isEmpty()) return "Agent" to "Operator"
    val first = pickStableName(firstSeed, names)
    if (names.size == 1) return first to first
    var second = pickStableName(secondSeed, names)
    if (second == first) {
        val firstIndex = names.indexOf(first).coerceAtLeast(0)
        second = names[(firstIndex + 1) % names.size]
    }
    return first to second
}

internal fun agentModeCrewIdentityKey(
    conversationStateKey: String,
    lastUserMessageId: String,
): String {
    val conversation = conversationStateKey.trim().ifBlank { "agent-mode" }
    val turn = lastUserMessageId.trim()
    return if (turn.isBlank()) conversation else "$conversation:$turn"
}

internal fun phoneOperatorIdentitySeed(turnIdentityKey: String): String =
    subagentIdentitySeed(turnIdentityKey, "operator", 1)

internal fun phoneSubagentAvatarSeed(
    turnIdentityKey: String,
    toolCallId: String,
    profile: String,
    isSwarm: Boolean,
    memberKey: String,
    memberIndex: Int,
): String {
    if (isSwarm) {
        return subagentIdentitySeed(toolCallId, memberKey, memberIndex)
    }
    if (profile.equals(KimiPhoneSubagentProfileName, ignoreCase = true) && turnIdentityKey.isNotBlank()) {
        return phoneOperatorIdentitySeed(turnIdentityKey)
    }
    return subagentIdentitySeed(toolCallId, profile, 0)
}

internal fun phoneDeskOperatorPeople(
    identityKey: String,
    invocations: List<ChatToolInvocation>,
    names: List<String>,
    occupied: MutableSet<String> = linkedSetOf(),
): List<SubagentPersonUi> {
    if (invocations.isEmpty()) {
        val seed = phoneOperatorIdentitySeed(identityKey)
        return listOf(
            SubagentPersonUi(
                index = 0,
                name = assignPersonName(seed, names, occupied),
                avatar = subagentAvatarSpec(seed),
                member = null,
                crewRole = AgentModeCrewRole.Phone,
            ),
        )
    }
    val people = ArrayList<SubagentPersonUi>()
    invocations.forEach { invocation ->
        val info = parseSubagentLaunch(
            invocation.toolName,
            invocation.argumentsJson,
            invocation.toolKind,
        ) ?: return@forEach
        people += subagentPeopleForLaunch(
            identityKey = identityKey,
            toolCallId = invocation.id,
            info = info,
            members = swarmMemberViews(info, invocation.outputJson),
            names = names,
            occupied = occupied,
            startIndex = people.size,
        )
    }
    return people.ifEmpty {
        val seed = phoneOperatorIdentitySeed(identityKey)
        listOf(
            SubagentPersonUi(
                index = 0,
                name = assignPersonName(seed, names, occupied),
                avatar = subagentAvatarSpec(seed),
                member = null,
                crewRole = AgentModeCrewRole.Phone,
            ),
        )
    }
}

internal fun turnUsedListenStart(
    listening: Boolean,
    guiSteps: List<PhoneGuiStepUi> = emptyList(),
    invocations: List<ChatToolInvocation> = emptyList(),
): Boolean {
    if (listening) return true
    if (guiSteps.any { step -> step.action.equals("listen_start", ignoreCase = true) }) return true
    return invocations.any { invocation ->
        sequenceOf(
            invocation.argumentsJson,
            invocation.outputJson,
            invocation.guiStepsJson,
        ).any { haystack -> haystack.contains("listen_start", ignoreCase = true) }
    }
}

/** Lead + each phone operator + a listen person when this turn used listen_start. */
internal fun agentModeCrewPeople(
    identityKey: String,
    phoneInvocations: List<ChatToolInvocation>,
    includeListen: Boolean,
    names: List<String>,
): List<SubagentPersonUi> {
    val occupied = linkedSetOf<String>()
    val leadSeed = subagentIdentitySeed(identityKey, "computer", 0)
    val lead = SubagentPersonUi(
        index = 0,
        name = assignPersonName(leadSeed, names, occupied),
        avatar = subagentAvatarSpec(leadSeed),
        member = null,
        crewRole = AgentModeCrewRole.Lead,
    )
    val phones = phoneDeskOperatorPeople(
        identityKey = identityKey,
        invocations = phoneInvocations,
        names = names,
        occupied = occupied,
    ).mapIndexed { offset, person ->
        person.copy(
            index = offset + 1,
            crewRole = AgentModeCrewRole.Phone,
        )
    }
    val listen = if (includeListen) {
        val seed = subagentIdentitySeed(identityKey, "listen", 2)
        listOf(
            SubagentPersonUi(
                index = phones.size + 1,
                name = assignPersonName(seed, names, occupied),
                avatar = subagentAvatarSpec(seed),
                member = null,
                crewRole = AgentModeCrewRole.Listen,
            ),
        )
    } else {
        emptyList()
    }
    return listOf(lead) + phones + listen
}

internal fun subagentPeopleForLaunch(
    identityKey: String,
    toolCallId: String,
    info: SubagentLaunchInfo,
    members: List<SwarmMemberView>,
    names: List<String>,
    occupied: MutableSet<String> = linkedSetOf(),
    startIndex: Int = 0,
): List<SubagentPersonUi> {
    if (info.isSwarm && members.isNotEmpty()) {
        return members.map { member ->
            val seed = phoneSubagentAvatarSeed(
                turnIdentityKey = identityKey,
                toolCallId = toolCallId,
                profile = info.profile,
                isSwarm = true,
                memberKey = member.result?.agentId.orEmpty().ifBlank { member.item },
                memberIndex = member.index,
            )
            SubagentPersonUi(
                index = startIndex + member.index,
                name = assignPersonName(seed, names, occupied),
                avatar = subagentAvatarSpec(seed),
                member = member,
                crewRole = if (info.profile.equals(KimiPhoneSubagentProfileName, ignoreCase = true)) {
                    AgentModeCrewRole.Phone
                } else {
                    null
                },
                sourceToolCallId = toolCallId,
            )
        }
    }
    val seed = phoneSubagentAvatarSeed(
        turnIdentityKey = identityKey,
        toolCallId = toolCallId,
        profile = info.profile,
        isSwarm = false,
        memberKey = info.profile,
        memberIndex = 0,
    )
    return listOf(
        SubagentPersonUi(
            index = startIndex,
            name = assignPersonName(seed, names, occupied),
            avatar = subagentAvatarSpec(seed),
            member = null,
            crewRole = if (info.profile.equals(KimiPhoneSubagentProfileName, ignoreCase = true)) {
                AgentModeCrewRole.Phone
            } else {
                null
            },
            sourceToolCallId = toolCallId,
        ),
    )
}

@Composable
internal fun subagentPersonNames(): List<String> =
    if (Locale.current.language.startsWith("zh")) SubagentPersonNamesZh else SubagentPersonNamesEn
