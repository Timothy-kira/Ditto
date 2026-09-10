package kira.ditto.ui

import kira.ditto.browser.humanizeBrowserSearchQuery
import kira.ditto.browser.parseBrowserActFollowUpFromUserText
import kira.ditto.browser.looksLikeBrowserPageResume
import kira.ditto.browser.sanitizeBrowserActUrl
import kira.ditto.data.KimiBrowserSubagentProfileName
import kira.ditto.data.KimiImageSubagentProfileName
import kira.ditto.data.KimiPhoneSubagentProfileName
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parsing helpers for ACP subagent/swarm tool calls. kimi-code emits exactly one
 * parent-level tool_call per Agent / AgentSwarm invocation — member-level
 * spawn/complete events never reach ACP — so everything the UI shows is derived
 * from the tool_call title, its rawInput JSON, and the final aggregated result.
 *
 * Title shapes (from the CLI's resolveExecution descriptions):
 *  - `Launching {profile} agent: {description}`
 *  - `Launching background {profile} agent: {description}`
 *  - `Launching agent swarm: {description}`
 *
 * rawInput streams in as accumulated JSON text; while the call is still being
 * composed it may not parse, in which case only the title-derived fields are
 * available and the card renders the header alone.
 */

internal data class SubagentLaunchInfo(
    val isSwarm: Boolean,
    val isBackground: Boolean,
    /** subagent_type (defaults to "coder" upstream); "swarm" for AgentSwarm. */
    val profile: String,
    val description: String,
    /** Full prompt for a single agent; the prompt template for a swarm. */
    val prompt: String = "",
    val model: String = "",
    /** Swarm item values; empty for single agents. */
    val items: List<String> = emptyList(),
    /** Number of resumed agents in a swarm (resume_agent_ids map size). */
    val resumedCount: Int = 0,
    /** False while rawInput is still streaming (partial / unparseable JSON). */
    val argumentsComplete: Boolean = false,
) {
    /** Total subagent count for a swarm; 1 for a single agent. */
    val agentCount: Int
        get() = if (isSwarm) items.size + resumedCount else 1
}

private const val SwarmTitlePrefix = "Launching agent swarm"
private const val BackgroundTitlePrefix = "Launching background "
private const val ForegroundTitlePrefix = "Launching "
private const val AgentTitleInfix = " agent: "
private const val SwarmTitleSuffix = ": "

/** Returns non-null when [toolName] (the ACP tool_call title) launches subagents. */
internal fun parseSubagentLaunch(
    toolName: String,
    argumentsJson: String,
    toolKind: String = "",
): SubagentLaunchInfo? {
    val title = toolName.trim()
    val kind = toolKind.trim().lowercase()
    val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
    val kindLooksAgent = kind == "agent" || kind == "agent_call"
    val nameIsSwarm = title.equals("AgentSwarm", ignoreCase = true) ||
        title.equals("agent_swarm", ignoreCase = true)
    val nameIsAgent = title.equals("Agent", ignoreCase = true)
    val isSwarmTitle = title.startsWith(SwarmTitlePrefix)
    val isBackground = title.startsWith(BackgroundTitlePrefix) ||
        arguments?.optBoolean("run_in_background") == true
    val isForegroundLaunch = title.startsWith(ForegroundTitlePrefix)
    val looksLikeSwarm = isSwarmTitle || nameIsSwarm ||
        (kindLooksAgent && (arguments?.has("items") == true || arguments?.has("prompt_template") == true))
    val looksLikeAgent = looksLikeSwarm || isBackground || isForegroundLaunch || nameIsAgent || kindLooksAgent
    if (!looksLikeAgent) return null
    val profileHint = arguments?.optString("subagent_type").orEmpty()
    if (profileHint.equals(TowerWorkerProfile, ignoreCase = true) ||
        title.contains(TowerWorkerProfile, ignoreCase = true)
    ) {
        return null
    }

    if (looksLikeSwarm) {
        val description = title
            .removePrefix(SwarmTitlePrefix)
            .removePrefix(SwarmTitleSuffix)
            .trim()
        val items = arguments?.optJSONArray("items")?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    add(array.optString(index))
                }
            }
        }.orEmpty()
        return SubagentLaunchInfo(
            isSwarm = true,
            isBackground = false,
            profile = arguments?.optString("subagent_type")?.trim()?.ifBlank { "coder" } ?: "coder",
            description = arguments?.optString("description")?.trim()?.ifBlank { description } ?: description,
            prompt = arguments?.optString("prompt_template")?.trim().orEmpty(),
            model = arguments?.optString("model")?.trim().orEmpty(),
            items = items,
            resumedCount = arguments?.optJSONObject("resume_agent_ids")?.length() ?: 0,
            argumentsComplete = arguments != null,
        )
    }

    val body = title
        .removePrefix(BackgroundTitlePrefix)
        .removePrefix(ForegroundTitlePrefix)
    val infixIndex = body.indexOf(AgentTitleInfix)
    val titleProfile = if (infixIndex >= 0) body.substring(0, infixIndex).trim() else body.trim()
    val titleDescription = if (infixIndex >= 0) body.substring(infixIndex + AgentTitleInfix.length).trim() else ""
    return SubagentLaunchInfo(
        isSwarm = false,
        isBackground = isBackground || arguments?.optBoolean("run_in_background") == true,
        profile = arguments?.optString("subagent_type")?.trim()?.ifBlank { null }
            ?: titleProfile.ifBlank { "coder" },
        description = arguments?.optString("description")?.trim()?.ifBlank { null } ?: titleDescription,
        prompt = arguments?.optString("prompt")?.trim().orEmpty(),
        model = arguments?.optString("model")?.trim().orEmpty(),
        argumentsComplete = arguments != null,
    )
}

/** One `<subagent>` section of an AgentSwarm result. */
internal data class SwarmMemberResult(
    val outcome: String,
    val item: String,
    val agentId: String,
    val body: String,
) {
    val succeeded: Boolean
        get() = outcome == "completed"
}

internal data class AgentSwarmResult(
    val summary: String,
    val members: List<SwarmMemberResult>,
)

private val SwarmSummaryRegex = Regex("<summary>([\\s\\S]*?)</summary>")
private val SwarmMemberRegex = Regex("<subagent\\b([^>]*)>([\\s\\S]*?)</subagent>")
private val SwarmAttributeRegex = Regex("""(\w+)="([^"]*)"""")

/**
 * Parses the `<agent_swarm_result>` XML emitted by AgentSwarm. Member bodies are
 * raw result text (not XML-escaped upstream), so this is a tolerant regex scan
 * rather than a strict XML parse; returns null when the payload is not a swarm
 * result at all, letting callers fall back to the raw text.
 */
internal fun parseAgentSwarmResult(text: String): AgentSwarmResult? {
    if (!text.contains("<agent_swarm_result")) return null
    val summary = SwarmSummaryRegex.find(text)?.groupValues?.get(1)?.trim().orEmpty()
    val members = SwarmMemberRegex.findAll(text).map { match ->
        val attributes = SwarmAttributeRegex.findAll(match.groupValues[1])
            .associate { it.groupValues[1] to xmlUnescape(it.groupValues[2]) }
        SwarmMemberResult(
            outcome = attributes["outcome"].orEmpty(),
            item = attributes["item"].orEmpty(),
            agentId = attributes["agent_id"].orEmpty(),
            body = match.groupValues[2].trim(),
        )
    }.toList()
    if (members.isEmpty() && summary.isEmpty()) return null
    return AgentSwarmResult(summary = summary, members = members)
}

private fun xmlUnescape(value: String): String = value
    .replace("&quot;", "\"")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&amp;", "&")

internal fun applySwarmPromptTemplate(
    template: String,
    item: String,
    index: Int,
): String {
    if (template.isBlank()) return item
    val ordinal = (index + 1).toString()
    return template
        .replace("{{item}}", item, ignoreCase = true)
        .replace("{item}", item, ignoreCase = true)
        .replace("{{index}}", ordinal, ignoreCase = true)
        .replace("{index}", ordinal, ignoreCase = true)
}

private val SubagentNamePrefixRegex = Regex("^[A-Za-z][A-Za-z0-9._-]{0,47}$")
private val AgentOrdinalItemRegex = Regex("""(?i)^agent\s*\d+$""")
private val SubagentFileNameRegex = Regex(
    """([\w.-]+\.(?:py|kt|kts|js|ts|tsx|jsx|java|go|rs|rb|php|cs|cpp|c|h|md|json|toml|yml|yaml))\b""",
)
private const val SubagentCaptionMaxChars = 32

/**
 * Capsule label for a subagent person: the persona/role key (explore, phone,
 * coder, …), never "子代理 1". Swarm items that themselves look like a role
 * name win over the shared swarm profile.
 */
internal fun subagentCapsuleName(profile: String, member: SwarmMemberView?): String {
    val role = profile.trim().ifBlank { "coder" }
    val item = member?.item.orEmpty().trim()
    if (item.isBlank() || item.matches(AgentOrdinalItemRegex)) return role
    val caption = subagentDisplayCaption(item)
    if (caption.matches(SubagentNamePrefixRegex)) return caption
    return role
}

/**
 * Capsule titles must stay short. Swarm items are often the full task prompt
 * (`scanner-agent:Create /workspace/scanner.py-the tokenizer...`); keep that
 * body for the detail sheet and show only a name / file / truncated first line.
 */
internal fun subagentDisplayCaption(raw: String, maxChars: Int = SubagentCaptionMaxChars): String {
    val firstLine = raw.trim().lineSequence().firstOrNull().orEmpty().trim()
    if (firstLine.isEmpty()) return ""
    if (firstLine.length <= maxChars) return firstLine

    val colon = firstLine.indexOf(':')
    if (colon in 1..48) {
        val prefix = firstLine.substring(0, colon).trim()
        if (prefix.matches(SubagentNamePrefixRegex)) return prefix
    }

    SubagentFileNameRegex.find(firstLine)
        ?.groupValues
        ?.get(1)
        ?.substringAfterLast('/')
        ?.takeIf { it.length in 3..64 }
        ?.let { return it }

    return firstLine.take(maxChars).trimEnd { !it.isLetterOrDigit() } + "…"
}

internal data class SwarmMemberView(
    val index: Int,
    val item: String,
    val prompt: String,
    val result: SwarmMemberResult?,
)

/** True when this invocation launches subagents (Agent / AgentSwarm tool call). */
internal fun ChatToolInvocation.isSubagentLaunch(): Boolean =
    parseSubagentLaunch(toolName, argumentsJson, toolKind) != null

internal fun ChatToolInvocation.isPhoneSubagent(): Boolean {
    val info = parseSubagentLaunch(toolName, argumentsJson, toolKind) ?: return false
    return info.profile.equals(KimiPhoneSubagentProfileName, ignoreCase = true)
}

internal fun ChatToolInvocation.isBrowserSubagent(): Boolean {
    val info = parseSubagentLaunch(toolName, argumentsJson, toolKind) ?: return false
    return info.profile.equals(KimiBrowserSubagentProfileName, ignoreCase = true)
}

internal fun ChatToolInvocation.isImageSubagent(): Boolean {
    val info = parseSubagentLaunch(toolName, argumentsJson, toolKind) ?: return false
    return info.profile.equals(KimiImageSubagentProfileName, ignoreCase = true)
}

internal fun ChatToolInvocation.isBrowserDeskSubagent(): Boolean =
    isBrowserSubagent() || isImageSubagent()

internal fun ChatToolInvocation.isBrowserSwarm(): Boolean {
    val info = parseSubagentLaunch(toolName, argumentsJson, toolKind) ?: return false
    return info.isSwarm && (
        info.profile.equals(KimiBrowserSubagentProfileName, ignoreCase = true) ||
            info.profile.equals(KimiImageSubagentProfileName, ignoreCase = true)
        )
}

internal fun ChatToolInvocation.isBrowserDeskSingle(): Boolean =
    isBrowserDeskSubagent() && !isBrowserSwarm()

internal const val AetherPunchedWebKey = "aether_punched_web"

internal data class PunchedWebAgentCall(
    val toolName: String,
    val argumentsJson: String,
    val toolKind: String,
)

internal fun isBuiltinKimiWebTool(toolName: String): Boolean {
    val compact = compactToolName(toolName)
    return compact == "websearch" || isBuiltinKimiFetchTool(toolName) || isRenderedWebToolName(toolName)
}

/**
 * `Searching: 时政新闻 2026年9月8日`, `Fetching: https://world.huanqiu.com/`.
 *
 * Once a browser agent is running, its WebSearch and FetchURL calls reach us under the agent's own
 * display name rather than the tool id, so every classifier keyed on "websearch"/"fetchurl" missed
 * them — which is why these chips kept appearing in the trace after they were supposedly silenced.
 * `Launching browser agent: …` is deliberately not in this list: that is a subagent starting, and
 * its chip is the only place the user can see it happen.
 */
internal fun isRenderedWebToolName(toolName: String): Boolean {
    val trimmed = toolName.trim()
    return trimmed.startsWith("Searching:", ignoreCase = true) ||
        trimmed.startsWith("Fetching:", ignoreCase = true)
}

internal fun isBuiltinKimiFetchTool(toolName: String): Boolean {
    val compact = compactToolName(toolName)
    return compact == "fetchurl" || compact == "fetchweburl" || compact == "fetch"
}

private fun compactToolName(toolName: String): String =
    toolName.trim().lowercase()
        .replace("_", "")
        .replace("-", "")
        .replace(" ", "")

internal fun isPunchedWebBrowserAgent(argumentsJson: String): Boolean {
    val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull() ?: return false
    return arguments.optString(AetherPunchedWebKey).isNotBlank()
}

internal fun ChatToolInvocation.isPunchedWebFetch(): Boolean {
    val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull() ?: return false
    return arguments.optString(AetherPunchedWebKey).equals("fetch", ignoreCase = true)
}

/** A WebSearch or FetchURL the host rewrote into a browser agent. */
internal fun ChatToolInvocation.isPunchedWebTool(): Boolean {
    val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull() ?: return false
    return arguments.optString(AetherPunchedWebKey).isNotBlank()
}

/** A call into the in-app browser host: search, fetch, page_read, page_recall, screenshots. */
internal fun ChatToolInvocation.isWebMcpHostTool(): Boolean =
    toolName.contains("webmcp", ignoreCase = true)

internal fun ChatToolInvocation.isSilentBrowserHostTool(
    siblings: List<ChatToolInvocation> = emptyList(),
): Boolean {
    if (isBuiltinKimiFetchTool(toolName)) return true
    // These are precisely what the browser card renders: the search field carries the query, the
    // page cards carry the fetches, and the card animates the page being read. Repeating them as a
    // column of "已使用 Searching: 科技新闻 2025年9月 AI 芯片 突破" chips is the same information told
    // worse, and it is what the user reads while the card sits half empty beside it. A native
    // Agent(subagent_type="browser") the model dispatched itself keeps its chip — that is a
    // subagent, not a rewritten web call.
    if (isPunchedWebTool()) return true
    if (isWebMcpHostTool()) return true
    // A search or fetch the running browser agent performed, under its own display name. The card
    // is showing this same work; the chip only competes with it.
    if (isRenderedWebToolName(toolName)) return true
    if (!isBuiltinKimiWebTool(toolName) || isBrowserDeskSubagent()) return false
    return siblings.hasNonFetchBrowserAgent(exceptId = id)
}

internal fun List<ChatToolInvocation>.hasNonFetchBrowserAgent(exceptId: String = ""): Boolean =
    any { sibling ->
        sibling.id != exceptId &&
            sibling.isBrowserDeskSubagent() &&
            !sibling.isPunchedWebFetch()
    }

internal fun List<ChatToolInvocation>.withoutRedundantFetchAgents(): List<ChatToolInvocation> =
    if (hasNonFetchBrowserAgent()) filterNot { it.isPunchedWebFetch() } else this

internal fun lastPunchedBrowserTopicId(invocations: List<ChatToolInvocation>): String {
    for (index in invocations.indices.reversed()) {
        val arguments = runCatching { JSONObject(invocations[index].argumentsJson) }.getOrNull()
            ?: continue
        if (arguments.optString(AetherPunchedWebKey).isBlank()) continue
        if (arguments.optString(AetherPunchedWebKey).equals("fetch", ignoreCase = true)) continue
        val topic = arguments.optString("topic_id")
            .ifBlank { arguments.optString("query") }
            .ifBlank { arguments.optString("description") }
            .trim()
        if (topic.isNotBlank() && !isPlaceholderBrowserTopic(topic)) return topic
    }
    return ""
}

internal fun pendingHasNativeBrowserSubagent(
    invocations: List<ChatToolInvocation>,
    exceptId: String = "",
): Boolean = invocations.any { invocation ->
    invocation.id != exceptId &&
        invocation.isRunning &&
        invocation.isBrowserSubagent() &&
        !isPunchedWebBrowserAgent(invocation.argumentsJson)
}

internal fun pendingHasPunchedBrowserAgent(
    invocations: List<ChatToolInvocation>,
    exceptId: String = "",
): Boolean = invocations.any { invocation ->
    invocation.id != exceptId &&
        isPunchedWebBrowserAgent(invocation.argumentsJson) &&
        !invocation.isPunchedWebFetch()
}

/**
 * Parent-level Kimi WebSearch / FetchURL become a browser subagent in the
 * conversation UI so the Gmail preview card attaches. Child sessions that
 * already have a live native browser agent are left alone.
 */
internal fun punchThroughWebToBrowserAgent(
    toolName: String,
    argumentsJson: String,
    toolKind: String,
    skipBecauseNativeBrowserChild: Boolean = false,
    topicIdHint: String = "",
    existingToolName: String = "",
    existingArgumentsJson: String = "",
    existingToolKind: String = "",
    operateUrl: String = "",
    lookupThenOperate: Boolean = false,
): PunchedWebAgentCall {
    if (existingToolName.isNotBlank() && isPunchedWebBrowserAgent(existingArgumentsJson)) {
        val incoming = runCatching { JSONObject(argumentsJson) }.getOrNull()
        val query = incoming?.let(::webToolQuery).orEmpty()
        if (query.isBlank() || isBuiltinKimiFetchTool(toolName.ifBlank { existingToolName })) {
            return PunchedWebAgentCall(
                toolName = existingToolName,
                argumentsJson = existingArgumentsJson,
                toolKind = existingToolKind.ifBlank { "agent" },
            )
        }
        return rewriteBuiltinWebAsBrowserAgent(
            toolName = toolName.ifBlank { existingToolName },
            argumentsJson = argumentsJson,
            topicIdHint = topicIdHint.ifBlank {
                runCatching { JSONObject(existingArgumentsJson) }.getOrNull()
                    ?.optString("topic_id").orEmpty()
            },
            operateUrl = operateUrl,
            lookupThenOperate = lookupThenOperate,
        )
    }
    if (!isBuiltinKimiWebTool(toolName)) {
        return PunchedWebAgentCall(toolName, argumentsJson, toolKind)
    }
    if (skipBecauseNativeBrowserChild) {
        return PunchedWebAgentCall(toolName, argumentsJson, toolKind)
    }
    return rewriteBuiltinWebAsBrowserAgent(
        toolName = toolName,
        argumentsJson = argumentsJson,
        topicIdHint = topicIdHint,
        operateUrl = operateUrl,
        lookupThenOperate = lookupThenOperate,
    )
}

private fun rewriteBuiltinWebAsBrowserAgent(
    toolName: String,
    argumentsJson: String,
    topicIdHint: String,
    operateUrl: String = "",
    lookupThenOperate: Boolean = false,
): PunchedWebAgentCall {
    val incoming = runCatching { JSONObject(argumentsJson) }.getOrDefault(JSONObject())
    val namedFetch = isBuiltinKimiFetchTool(toolName)
    val rawQuery = webToolQuery(incoming)
    val resolvedOperateUrl = sanitizeBrowserActUrl(operateUrl).ifBlank {
        parseBrowserActFollowUpFromUserText(rawQuery)?.url.orEmpty()
    }
    val isOperate = resolvedOperateUrl.isNotBlank()
    val isVerifyResume = looksLikeBrowserPageResume(rawQuery)
    val isLookup = lookupThenOperate && !isOperate && !isVerifyResume
    val isFetch = namedFetch || isOperate
    val query = when {
        isVerifyResume -> rawQuery
        isOperate -> resolvedOperateUrl
        isFetch -> rawQuery
        else -> humanizeBrowserSearchQuery(rawQuery).ifBlank { rawQuery }
    }
    val description = if (isVerifyResume) {
        "page"
    } else {
        punchedWebTopic(query, (isFetch && !isLookup) || isVerifyResume)
    }
    val hinted = topicIdHint.trim()
    val topicId = hinted.takeUnless(::isPlaceholderBrowserTopic).orEmpty().ifBlank { description }
    val prompt = when {
        isVerifyResume -> browserVerifyResumePrompt()
        isOperate -> browserOperateAgentPrompt(query.ifBlank { topicId })
        isLookup && isFetch -> browserLookupSequencePrompt(
            query = humanizeBrowserSearchQuery(incoming.optString("query")).ifBlank {
                hinted.takeUnless(::isPlaceholderBrowserTopic).orEmpty()
            }.ifBlank { query },
        )
        isLookup -> browserLookupSequencePrompt(query.ifBlank { topicId })
        isFetch ->
            "Open and read this URL in the built-in Gecko browser: ${query.ifBlank { topicId }}"
        else ->
            "Search the web for this query in the built-in Gecko browser using the user's search engine: ${query.ifBlank { topicId }}"
    }
    val punched = JSONObject(incoming.toString())
        .put("subagent_type", KimiBrowserSubagentProfileName)
        .put("description", description)
        .put("prompt", prompt)
        .put("topic_id", topicId)
        .put(AetherPunchedWebKey, if (isVerifyResume || (isFetch && !isLookup)) "fetch" else "search")
    if (query.isNotBlank() && !isVerifyResume) {
        if (isFetch && !isLookup) {
            if (punched.optString("url").isBlank() || isOperate) punched.put("url", query)
            punched.remove("query")
        } else {
            punched.put("query", if (isLookup && isFetch) {
                humanizeBrowserSearchQuery(incoming.optString("query")).ifBlank { query }
            } else {
                query
            })
            if (isLookup) punched.remove("url")
        }
    }
    return PunchedWebAgentCall(
        toolName = "Launching browser agent: $description",
        argumentsJson = punched.toString(),
        toolKind = "agent",
    )
}

private fun browserLookupSequencePrompt(query: String, candidateUrl: String = ""): String {
    val extra = sanitizeBrowserActUrl(candidateUrl).takeIf { it.isNotBlank() }?.let { url ->
        " After searching, you may open this candidate as one result to read: $url."
    }.orEmpty()
    return "Lookup sequence in the built-in Gecko browser for: $query. " +
        "1) Search first, once, with exactly that query. Do not add a year, a month, 最新, or extra keywords " +
        "of your own — the search engine already ranks by recency, and an invented date narrows the " +
        "results to the wrong period. Do not guess website paths such as /hackathon/ or /object/. " +
        "2) tabs_navigate 1-2 outbound result pages from the SERP and page_read them in Gecko. " +
        "Never HTTP-fetch articles. On reprint/WeChat pages, page_grep 阅读原文 or 报名 and open that href. " +
        "QR codes are not URLs. " +
        "3) The official/报名 URL must appear on a live page or harvested links. " +
        "Never invent URLs. Do not page_form, page_js, click 报名/register/submit, " +
        "or wait on empty drawers. Stop after facts and the official http(s) URL.$extra"
}

private fun browserVerifyResumePrompt(): String =
    "Stay on the current Gecko page. The user finished 人机验证 or signed in in the in-chat browser card. " +
        "page_snapshot and page_read now. Harvest 阅读原文 or 报名 links, then continue the page action. " +
        "Do not search, do not guess URLs, and do not leave this page unless a link on it says so."

private fun browserOperateAgentPrompt(url: String): String =
    "Open this URL in the built-in Gecko browser and complete the user's page action " +
        "(register, fill, apply, or book). Do not search again. URL: $url " +
        "Use page_form action=list then action=fill. Fill only facts the user already stated. " +
        "Never invent name, email, phone, or other fields. If a required field is missing, " +
        "stop and GUI_TASK_NEEDS_TEACHING. Do not submit captchas or payments. " +
        "Captcha or 人机验证: stop with GUI_TASK_NEEDS_TEACHING so the user can finish it in the browser card. " +
        "Login walls: passwords fill, else GUI_TASK_NEEDS_TEACHING so the user can tap 我已登录. " +
        "If page_form list is empty, wait_exhausted, js_budget, or form_unavailable: " +
        "stop with GUI_TASK_NEEDS_TEACHING. Do not loop page_js on SPA drawers or iframes."

internal fun isPlaceholderBrowserTopic(topic: String): Boolean {
    val trimmed = topic.trim()
    if (trimmed.isBlank()) return true
    val lower = trimmed.lowercase()
    return lower == "search" || lower == "page"
}

private fun webToolQuery(arguments: JSONObject): String =
    arguments.optString("query")
        .ifBlank { arguments.optString("text_query") }
        .ifBlank { arguments.optString("q") }
        .ifBlank { arguments.optString("url") }
        .ifBlank { arguments.optString("uri") }
        .trim()

private fun punchedWebTopic(query: String, isFetch: Boolean): String {
    val trimmed = query.trim()
    if (trimmed.isBlank()) return if (isFetch) "page" else "search"
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        val host = trimmed
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .substringBefore('/')
            .substringBefore('?')
        return host.ifBlank { trimmed }.take(48)
    }
    return trimmed.take(48)
}

internal const val AetherCoalescedWebKey = "aether_coalesced_web"

internal fun ChatToolInvocation.isCoalescedBrowserSwarm(): Boolean =
    runCatching { JSONObject(argumentsJson) }.getOrNull()?.optBoolean(AetherCoalescedWebKey) == true

/**
 * Two or more simultaneous browser/image Agent() calls are one swarm: the UI
 * should be AgentSwarm member bars, not N independent subagent cards.
 */
internal fun coalesceParallelBrowserAgents(
    invocations: List<ChatToolInvocation>,
): List<ChatToolInvocation> {
    val singles = invocations.filter { invocation ->
        invocation.isBrowserDeskSingle() && !invocation.isPunchedWebFetch()
    }
    if (singles.size < 2) return invocations
    val swarm = synthesizeBrowserSwarmInvocation(singles)
    val drop = singles.mapTo(hashSetOf()) { it.id }
    var replaced = false
    return buildList {
        for (invocation in invocations) {
            if (invocation.id == singles.first().id) {
                add(swarm)
                replaced = true
            } else if (invocation.id !in drop) {
                add(invocation)
            }
        }
        if (!replaced) add(0, swarm)
    }
}

internal fun synthesizeBrowserSwarmInvocation(
    members: List<ChatToolInvocation>,
): ChatToolInvocation {
    val infos = members.map { member ->
        parseSubagentLaunch(member.toolName, member.argumentsJson, member.toolKind)
    }
    val items = members.mapIndexed { index, member ->
        val fromInfo = infos[index]?.description?.trim().orEmpty()
        val fromArgs = runCatching { JSONObject(member.argumentsJson) }.getOrNull()
            ?.let(::webToolQuery)
            .orEmpty()
        fromInfo.ifBlank { fromArgs }.ifBlank { "search ${index + 1}" }
    }
    val profile = infos.firstOrNull()?.profile?.trim()?.ifBlank { null }
        ?: KimiBrowserSubagentProfileName
    val description = items.joinToString(" · ")
    val arguments = JSONObject()
        .put("subagent_type", profile)
        .put("description", description)
        .put(
            "prompt_template",
            "Research {{item}} in the built-in Gecko browser. Pass topic_id={{item}}.",
        )
        .put("items", JSONArray(items))
        .put(AetherCoalescedWebKey, true)
    val pending = members.any(::browserSwarmMemberPending)
    val failedCount = members.mapIndexed { index, member ->
        if (browserSwarmMemberPending(member)) return@mapIndexed false
        val info = infos[index]
        info != null && subagentCallFailed(info, member.outputJson)
    }.count { it }
    val output = buildString {
        val finished = members.indices.filter { index -> !browserSwarmMemberPending(members[index]) }
        if (finished.isEmpty()) return@buildString
        append("<agent_swarm_result>\n<summary>")
        if (pending) {
            append("running: ${members.size - finished.size}, completed: ")
            append(finished.size - failedCount)
        } else {
            append("completed: ")
            append(members.size - failedCount)
        }
        if (failedCount > 0) {
            append(", failed: ")
            append(failedCount)
        }
        append("</summary>\n")
        finished.forEach { index ->
            val member = members[index]
            val failed = infos[index]?.let { subagentCallFailed(it, member.outputJson) } == true
            append("<subagent item=\"")
            append(xmlAttrEscape(items[index]))
            append("\" outcome=\"")
            append(if (failed) "failed" else "completed")
            append("\" agent_id=\"")
            append(xmlAttrEscape(member.id))
            append("\">")
            append(xmlAttrEscape(member.outputJson.trim().take(280)))
            append("</subagent>\n")
        }
        append("</agent_swarm_result>")
    }
    val first = members.first()
    return first.copy(
        toolName = "Launching agent swarm: $description",
        argumentsJson = arguments.toString(),
        toolKind = "agent",
        isRunning = pending,
        outputJson = output,
        completedAtMillis = if (pending) {
            null
        } else {
            members.maxOf { it.completedAtMillis ?: 0L }.takeIf { it > 0L }
        },
        completedAtUptimeMillis = if (pending) {
            null
        } else {
            members.maxOf { it.completedAtUptimeMillis ?: 0L }.takeIf { it > 0L }
        },
    )
}

internal fun browserSwarmMemberPending(member: ChatToolInvocation): Boolean {
    if (member.isRunning) return true
    if (member.outputJson.isNotBlank()) return false
    return member.completedAtMillis == null
}

internal enum class BrowserSwarmHeaderKind {
    Working,
    MemberDone,
    AllDone,
}

internal data class BrowserSwarmHeaderState(
    val kind: BrowserSwarmHeaderKind,
    val personName: String = "",
    val headline: String = "",
)

internal fun browserSwarmHeaderState(
    personNames: List<String>,
    members: List<SwarmMemberView>,
    swarmRunning: Boolean,
    headline: String,
): BrowserSwarmHeaderState {
    val total = members.size.coerceAtLeast(1)
    val completedLabels = members.mapIndexedNotNull { index, member ->
        if (member.result == null) return@mapIndexedNotNull null
        subagentDisplayCaption(member.item).ifBlank {
            personNames.getOrNull(index)?.trim().orEmpty()
        }.ifBlank { null }
    }
    val awaiting = swarmRunning ||
        members.any { member -> member.result == null } ||
        completedLabels.size < total
    if (!awaiting && total > 1 && completedLabels.isNotEmpty()) {
        return BrowserSwarmHeaderState(kind = BrowserSwarmHeaderKind.AllDone)
    }
    if (completedLabels.isNotEmpty() && awaiting) {
        return BrowserSwarmHeaderState(
            kind = BrowserSwarmHeaderKind.MemberDone,
            personName = completedLabels.last(),
        )
    }
    return BrowserSwarmHeaderState(
        kind = BrowserSwarmHeaderKind.Working,
        headline = headline,
    )
}

private fun xmlAttrEscape(value: String): String = value
    .replace("&", "&amp;")
    .replace("\"", "&quot;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

internal fun browserSwarmTopicCaptions(items: List<String>): List<String> =
    items.map { item -> subagentDisplayCaption(item) }
        .filter { caption -> caption.isNotBlank() }
        .distinct()

internal fun browserSwarmHeadline(
    items: List<String>,
    description: String = "",
): String {
    val topics = browserSwarmTopicCaptions(items)
    if (topics.isEmpty()) return subagentDisplayCaption(description)
    return if (topics.size == 1) topics.single() else topics.take(3).joinToString(" · ")
}

internal fun swarmMemberViews(
    info: SubagentLaunchInfo,
    outputJson: String,
): List<SwarmMemberView> {
    val parsed = parseAgentSwarmResult(outputJson)
    val listed = when {
        info.items.isNotEmpty() -> info.items
        parsed != null && parsed.members.isNotEmpty() -> parsed.members.map { member ->
            member.item.ifBlank { member.agentId }.ifBlank { "agent ${member.outcome}" }
        }
        info.agentCount > 1 -> List(info.agentCount) { index -> "agent ${index + 1}" }
        else -> emptyList()
    }
    val extra = (info.agentCount - listed.size).coerceAtLeast(0)
    val items = listed + List(extra) { index -> "agent ${listed.size + index + 1}" }
    if (items.isEmpty()) return emptyList()
    return items.mapIndexed { index, item ->
        val member = parsed?.members?.getOrNull(index)
            ?: parsed?.members?.firstOrNull { it.item == item }
        SwarmMemberView(
            index = index,
            item = item,
            prompt = applySwarmPromptTemplate(info.prompt, item, index),
            result = member,
        )
    }
}

/**
 * Best-effort failure signal for a finished subagent call: swarm members report
 * per-agent outcomes; a single agent only tells us something went wrong by
 * producing no output at all.
 */
internal fun subagentCallFailed(info: SubagentLaunchInfo, outputJson: String): Boolean = when {
    outputJson.isBlank() -> true
    info.isSwarm -> parseAgentSwarmResult(outputJson)?.members?.any { !it.succeeded } == true
    else -> false
}
