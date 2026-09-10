package kira.ditto.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentToolParsingTest {
    @Test
    fun parsesForegroundAgentTitleAndArguments() {
        val info = parseSubagentLaunch(
            toolName = "Launching explore agent: scan the repo",
            argumentsJson = """{"prompt":"look around","subagent_type":"explore","description":"scan the repo","model":"secondary"}""",
        )!!

        assertFalse(info.isSwarm)
        assertFalse(info.isBackground)
        assertEquals("explore", info.profile)
        assertEquals("scan the repo", info.description)
        assertEquals("look around", info.prompt)
        assertEquals("secondary", info.model)
        assertTrue(info.argumentsComplete)
        assertEquals(1, info.agentCount)
    }

    @Test
    fun parsesBackgroundAgentTitle() {
        val info = parseSubagentLaunch(
            toolName = "Launching background coder agent: fix the flaky test",
            argumentsJson = """{"prompt":"fix it","run_in_background":true,"description":"fix the flaky test"}""",
        )!!

        assertTrue(info.isBackground)
        assertEquals("coder", info.profile)
        assertEquals("fix the flaky test", info.description)
    }

    @Test
    fun titleOnlyParsingSurvivesStreamingPartialJson() {
        val info = parseSubagentLaunch(
            toolName = "Launching build agent: compile everything",
            argumentsJson = """{"prompt":"compile the proj""",
        )!!

        assertFalse(info.argumentsComplete)
        assertEquals("build", info.profile)
        assertEquals("compile everything", info.description)
        assertEquals("", info.prompt)
    }

    @Test
    fun parsesSwarmTitleItemsAndTemplate() {
        val info = parseSubagentLaunch(
            toolName = "Launching agent swarm: review PRs",
            argumentsJson = """{"description":"review PRs","prompt_template":"Review {{item}}","items":["PR-1","PR-2","PR-3"],"subagent_type":"reviewer"}""",
        )!!

        assertTrue(info.isSwarm)
        assertEquals("review PRs", info.description)
        assertEquals("Review {{item}}", info.prompt)
        assertEquals(listOf("PR-1", "PR-2", "PR-3"), info.items)
        assertEquals("reviewer", info.profile)
        assertEquals(3, info.agentCount)
    }

    @Test
    fun swarmAgentCountIncludesResumedAgents() {
        val info = parseSubagentLaunch(
            toolName = "Launching agent swarm: continue",
            argumentsJson = """{"description":"continue","items":["a"],"resume_agent_ids":{"id1":"go","id2":"go"}}""",
        )!!
        assertEquals(3, info.agentCount)
    }

    @Test
    fun rejectsNonSubagentToolTitles() {
        assertNull(parseSubagentLaunch("Bash", "{}"))
        assertNull(parseSubagentLaunch("Creating a goal", "{}"))
        assertNull(parseSubagentLaunch("Launchpad", "{}"))
        assertNull(parseSubagentLaunch("", "{}"))
    }

    @Test
    fun parsesAgentToolNameAndKindWithoutLaunchingPrefix() {
        val agent = parseSubagentLaunch(
            toolName = "Agent",
            argumentsJson = """{"prompt":"look","subagent_type":"explore","description":"scan"}""",
            toolKind = "agent_call",
        )!!
        assertEquals("explore", agent.profile)
        assertEquals("scan", agent.description)
        assertFalse(agent.isSwarm)

        val swarm = parseSubagentLaunch(
            toolName = "AgentSwarm",
            argumentsJson = """{"description":"review PRs","prompt_template":"Review {{item}}","items":["a","b"]}""",
            toolKind = "agent",
        )!!
        assertTrue(swarm.isSwarm)
        assertEquals(2, swarm.agentCount)
        assertEquals("review PRs", swarm.description)
    }

    @Test
    fun swarmResultParsesSummaryAndMembers() {
        val text = """
            <agent_swarm_result>
            <summary>completed: 2, failed: 1</summary>
            <resume_hint>Call AgentSwarm with resume_agent_ids.</resume_hint>
            <subagent agent_id="a1" item="PR-1" outcome="completed">Looks good.</subagent>
            <subagent agent_id="a2" item="PR-2 &amp; PR-3" outcome="completed">Ship it &lt;now&gt;.</subagent>
            <subagent agent_id="a3" item="PR-4" outcome="failed">boom: missing token</subagent>
            </agent_swarm_result>
        """.trimIndent()

        val result = parseAgentSwarmResult(text)!!
        assertEquals("completed: 2, failed: 1", result.summary)
        assertEquals(3, result.members.size)
        assertEquals("PR-1", result.members[0].item)
        assertEquals("a1", result.members[0].agentId)
        assertEquals("Looks good.", result.members[0].body)
        assertTrue(result.members[0].succeeded)
        assertEquals("PR-2 & PR-3", result.members[1].item)
        // Member bodies are raw text upstream (never XML-escaped): kept verbatim.
        assertEquals("Ship it &lt;now&gt;.", result.members[1].body)
        assertFalse(result.members[2].succeeded)
        assertEquals("failed", result.members[2].outcome)
    }

    @Test
    fun swarmResultRejectsNonSwarmPayloads() {
        assertNull(parseAgentSwarmResult("plain agent output"))
        assertNull(parseAgentSwarmResult(""))
        assertNull(parseAgentSwarmResult("<agent_swarm_result></agent_swarm_result>"))
    }

    @Test
    fun failureDetectionUsesSwarmOutcomesAndBlankOutput() {
        val swarm = parseSubagentLaunch(
            "Launching agent swarm: x",
            """{"description":"x","items":["a","b"]}""",
        )!!
        assertTrue(subagentCallFailed(swarm, ""))
        assertFalse(
            subagentCallFailed(
                swarm,
                "<agent_swarm_result><summary>completed: 2</summary>" +
                    "<subagent item=\"a\" outcome=\"completed\">ok</subagent>" +
                    "<subagent item=\"b\" outcome=\"completed\">ok</subagent></agent_swarm_result>",
            ),
        )
        assertTrue(
            subagentCallFailed(
                swarm,
                "<agent_swarm_result><summary>failed: 1</summary>" +
                    "<subagent item=\"a\" outcome=\"failed\">boom</subagent></agent_swarm_result>",
            ),
        )

        val single = parseSubagentLaunch("Launching coder agent: y", """{"prompt":"p"}""")!!
        assertNotNull(single)
        assertTrue(subagentCallFailed(single, ""))
        assertFalse(subagentCallFailed(single, "done"))
    }

    @Test
    fun subagentDisplayCaptionKeepsShortItemsAndStripsTaskBodies() {
        assertEquals("PR-1", subagentDisplayCaption("PR-1"))
        assertEquals(
            "scanner-agent",
            subagentDisplayCaption(
                "scanner-agent:Create /workspace/scanner.py-the tokenizer/lexer stage. " +
                    "It must provide a function scan_lines(lines) that takes a list of raw text lines.",
            ),
        )
        assertEquals(
            "parser.py",
            subagentDisplayCaption(
                "Create /workspace/parser.py and implement the second pipeline stage with full tests.",
            ),
        )
        val truncated = subagentDisplayCaption(
            "Write a complete markdown tokenizer that handles headings, bullets, and fenced code.",
        )
        assertTrue(truncated.endsWith("…"))
        assertTrue(truncated.length <= 33)
    }

    @Test
    fun swarmPromptTemplateSubstitutesItemAndIndex() {
        assertEquals("Review PR-1", applySwarmPromptTemplate("Review {{item}}", "PR-1", 0))
        assertEquals("Task 2: foo", applySwarmPromptTemplate("Task {index}: {item}", "foo", 1))
        assertEquals("plain-item", applySwarmPromptTemplate("", "plain-item", 0))
    }

    @Test
    fun subagentIdentityIsStableForTheSameMember() {
        val seed = subagentIdentitySeed("call-1", "PR-1", 0)
        assertEquals(seed, subagentIdentitySeed("call-1", "PR-1", 0))
        assertEquals(
            pickStableName(seed, SubagentPersonNamesZh),
            pickStableName(seed, SubagentPersonNamesZh),
        )
        assertEquals("call-1:PR-1:0", seed)
        assertTrue(pickStableName(seed, SubagentPersonNamesZh) in SubagentPersonNamesZh)
        assertEquals(subagentAvatarSpec(seed).seed, seed)
    }

    @Test
    fun agentModeCrewNamesChangeWithTheConversationTurn() {
        val first = agentModeCrewIdentityKey("session-a", "user-1")
        val second = agentModeCrewIdentityKey("session-a", "user-2")
        val otherChat = agentModeCrewIdentityKey("session-b", "user-1")
        assertTrue(first != second)
        assertTrue(first != otherChat)
        val (leadA, opA) = pickStableDistinctNames(
            subagentIdentitySeed(first, "computer", 0),
            subagentIdentitySeed(first, "operator", 1),
            SubagentPersonNamesZh,
        )
        val (leadB, opB) = pickStableDistinctNames(
            subagentIdentitySeed(second, "computer", 0),
            subagentIdentitySeed(second, "operator", 1),
            SubagentPersonNamesZh,
        )
        assertTrue(leadA != opA)
        assertTrue(leadB != opB)
        assertTrue(leadA != leadB || opA != opB)
    }

    @Test
    fun pickStableDistinctNamesNeverCollidesAcrossSeeds() {
        repeat(80) { index ->
            val (first, second) = pickStableDistinctNames("lead-$index", "operator-$index", SubagentPersonNamesZh)
            assertTrue(first != second)
        }
    }

    @Test
    fun swarmMemberViewsEmitsOneCapsulePerItem() {
        val info = parseSubagentLaunch(
            toolName = "Launching agent swarm: review PRs",
            argumentsJson = """{"description":"review PRs","prompt_template":"Review {{item}}","items":["PR-1","PR-2","PR-3"]}""",
        )!!
        val members = swarmMemberViews(info, "")
        assertEquals(3, members.size)
        assertEquals("PR-1", members[0].item)
        assertEquals("Review PR-1", members[0].prompt)
        assertEquals("Review PR-3", members[2].prompt)
        assertEquals(null, members[0].result)
    }

    @Test
    fun swarmMemberViewsPadsResumedAgents() {
        val info = parseSubagentLaunch(
            toolName = "Launching agent swarm: continue",
            argumentsJson = """{"description":"continue","items":["a"],"resume_agent_ids":{"id1":"go","id2":"go"}}""",
        )!!
        val members = swarmMemberViews(info, "")
        assertEquals(3, members.size)
        assertEquals("a", members[0].item)
        assertEquals("agent 2", members[1].item)
        assertEquals("agent 3", members[2].item)
    }

    @Test
    fun swarmMemberViewsBindsParsedResults() {
        val info = parseSubagentLaunch(
            "Launching agent swarm: x",
            """{"description":"x","prompt_template":"Do {item}","items":["a","b"]}""",
        )!!
        val members = swarmMemberViews(
            info,
            """<agent_swarm_result><summary>ok</summary>
                <subagent item="a" outcome="completed">done a</subagent>
                <subagent item="b" outcome="failed">boom</subagent></agent_swarm_result>""",
        )
        assertEquals(2, members.size)
        assertEquals("Do a", members[0].prompt)
        assertEquals("done a", members[0].result?.body)
        assertEquals(false, members[1].result?.succeeded)
    }

    @Test
    fun detectsPhoneSubagentFromNativeAgentTool() {
        val phone = ChatToolInvocation(
            id = "1",
            toolName = "Launching phone agent: open WeChat",
            argumentsJson = """{"subagent_type":"phone","prompt":"open WeChat","description":"open WeChat"}""",
            isRunning = true,
        )
        val coder = ChatToolInvocation(
            id = "2",
            toolName = "Launching coder agent: scan",
            argumentsJson = """{"subagent_type":"coder","prompt":"scan"}""",
        )
        assertTrue(phone.isPhoneSubagent())
        assertTrue(phone.isSubagentLaunch())
        assertFalse(coder.isPhoneSubagent())
        assertTrue(coder.isSubagentLaunch())
    }

    @Test
    fun detectsBrowserSubagentFromNativeAgentTool() {
        val browser = ChatToolInvocation(
            id = "1",
            toolName = "Launching browser agent: search photos",
            argumentsJson = """{"subagent_type":"browser","prompt":"find photos","description":"search photos"}""",
            isRunning = true,
        )
        val coder = ChatToolInvocation(
            id = "2",
            toolName = "Launching coder agent: scan",
            argumentsJson = """{"subagent_type":"coder","prompt":"scan"}""",
        )
        assertTrue(browser.isBrowserSubagent())
        assertTrue(browser.isSubagentLaunch())
        assertFalse(coder.isBrowserSubagent())
        assertFalse(browser.isPhoneSubagent())
    }

    @Test
    fun browserSwarmHeadlineUsesTopicItemsNotGenericAgentCount() {
        assertEquals("本帮菜 推荐", browserSwarmHeadline(listOf("本帮菜 推荐"), "上海美食"))
        assertEquals(
            "本帮菜 · 小笼包 · 生煎",
            browserSwarmHeadline(listOf("本帮菜", "小笼包", "生煎"), "上海美食"),
        )
        val swarm = ChatToolInvocation(
            id = "swarm-1",
            toolName = "AgentSwarm",
            argumentsJson = """{"items":["本帮菜","小笼包"],"subagent_type":"browser","description":"上海美食"}""",
            toolKind = "agent",
        )
        assertTrue(swarm.isBrowserSubagent())
        val info = parseSubagentLaunch(swarm.toolName, swarm.argumentsJson, swarm.toolKind)
        requireNotNull(info)
        assertTrue(info.isSwarm)
        assertEquals("browser", info.profile)
        assertEquals("本帮菜 · 小笼包", browserSwarmHeadline(info.items, info.description))
    }

    @Test
    fun phoneCapsuleSeedMatchesDeskOperatorSeed() {
        val turnKey = agentModeCrewIdentityKey("session-a", "user-1")
        val capsule = phoneSubagentAvatarSeed(
            turnIdentityKey = turnKey,
            toolCallId = "tool-call-xyz",
            profile = "phone",
            isSwarm = false,
            memberKey = "phone",
            memberIndex = 0,
        )
        assertEquals(phoneOperatorIdentitySeed(turnKey), capsule)
        assertEquals(subagentIdentitySeed(turnKey, "operator", 1), capsule)
        val swarm = phoneSubagentAvatarSeed(
            turnIdentityKey = turnKey,
            toolCallId = "swarm-1",
            profile = "phone",
            isSwarm = true,
            memberKey = "美团",
            memberIndex = 0,
        )
        assertEquals(subagentIdentitySeed("swarm-1", "美团", 0), swarm)
        assertTrue(capsule != swarm)
    }

    @Test
    fun capsuleNameUsesRoleInsteadOfOrdinal() {
        assertEquals("explore", subagentCapsuleName("explore", null))
        assertEquals("phone", subagentCapsuleName("phone", null))
        assertEquals("coder", subagentCapsuleName("", null))
    }

    @Test
    fun displayNamesUsePersonLibraryAndDoNotRepeatInSwarm() {
        val occupied = linkedSetOf<String>()
        val names = SubagentPersonNamesZh
        val first = assignPersonName("seed-a", names, occupied)
        val second = assignPersonName("seed-b", names, occupied)
        assertTrue(first in names)
        assertTrue(second in names)
        assertTrue(first != second)

        val people = phoneDeskOperatorPeople(
            identityKey = "session-a:user-1",
            invocations = listOf(
                ChatToolInvocation(
                    id = "swarm-1",
                    toolName = "AgentSwarm",
                    argumentsJson = """{"items":["美团","携程","学习通","钉钉"],"subagent_type":"phone"}""",
                    toolKind = "agent",
                ),
            ),
            names = names,
        )
        assertEquals(4, people.size)
        assertEquals(4, people.map { it.name }.toSet().size)
        people.forEach { person ->
            assertTrue(person.name in names)
            assertFalse(person.name.equals("phone", ignoreCase = true))
            assertFalse(person.name.equals("coder", ignoreCase = true))
        }

        val lone = phoneDeskOperatorPeople(
            identityKey = "session-a:user-1",
            invocations = emptyList(),
            names = names,
        )
        assertEquals(1, lone.size)
        assertTrue(lone.single().name in names)
        assertFalse(lone.single().name.equals("phone", ignoreCase = true))
    }

    @Test
    fun crewPeopleStayStableAndAddListenOnlyAfterListenStart() {
        val names = SubagentPersonNamesZh
        val turnKey = agentModeCrewIdentityKey("session-a", "user-1")
        val phone = ChatToolInvocation(
            id = "phone-1",
            toolName = "Launching phone agent: 看课",
            argumentsJson = """{"subagent_type":"phone","prompt":"看课","description":"看课"}""",
            toolKind = "agent",
        )
        val withoutListen = agentModeCrewPeople(
            identityKey = turnKey,
            phoneInvocations = listOf(phone),
            includeListen = false,
            names = names,
        )
        val withListen = agentModeCrewPeople(
            identityKey = turnKey,
            phoneInvocations = listOf(phone),
            includeListen = true,
            names = names,
        )
        val withListenAgain = agentModeCrewPeople(
            identityKey = turnKey,
            phoneInvocations = listOf(phone),
            includeListen = true,
            names = names,
        )
        assertEquals(2, withoutListen.size)
        assertEquals(AgentModeCrewRole.Lead, withoutListen.first().crewRole)
        assertEquals(AgentModeCrewRole.Phone, withoutListen.last().crewRole)
        assertFalse(withoutListen.any { person -> person.crewRole == AgentModeCrewRole.Listen })
        assertEquals(3, withListen.size)
        assertEquals(AgentModeCrewRole.Listen, withListen.last().crewRole)
        assertEquals(withListen.map { it.name }, withListenAgain.map { it.name })
        assertEquals(withListen.map { it.name }.toSet().size, withListen.size)
        withListen.forEach { person -> assertTrue(person.name in names) }
        assertTrue(
            turnUsedListenStart(
                listening = false,
                guiSteps = listOf(
                    kira.ditto.data.PhoneGuiStepUi(
                        id = "1",
                        sequence = 1,
                        action = "listen_start",
                        label = "听课",
                        thought = "",
                    ),
                ),
            ),
        )
        assertTrue(
            turnUsedListenStart(
                listening = false,
                invocations = listOf(phone.copy(outputJson = """{"ok":true,"action":"listen_start"}""")),
            ),
        )
        assertFalse(turnUsedListenStart(listening = false, invocations = listOf(phone)))
        assertTrue(turnUsedListenStart(listening = true))
    }

    @Test
    fun towerWorkerIsNotASwarmOrAgentCapsule() {
        assertNull(
            parseSubagentLaunch(
                toolName = "Launching tower-worker agent: M1",
                argumentsJson = """{"subagent_type":"tower-worker","prompt":"build","description":"M1"}""",
            ),
        )
        assertNull(
            parseSubagentLaunch(
                toolName = "Agent",
                argumentsJson = """{"subagent_type":"tower-worker","prompt":"build"}""",
                toolKind = "agent",
            ),
        )
        val invocation = ChatToolInvocation(
            id = "t1",
            toolName = "Launching tower-worker agent: M1",
            argumentsJson = """{"subagent_type":"tower-worker","prompt":"build","description":"M1"}""",
        )
        assertFalse(invocation.isSubagentLaunch())
        assertTrue(invocation.isTowerRelated())
    }

    @Test
    fun punchesWebSearchAndFetchUrlIntoBrowserSubagent() {
        val search = punchThroughWebToBrowserAgent(
            toolName = "WebSearch",
            argumentsJson = """{"query":"上海小笼包"}""",
            toolKind = "search",
        )
        val searchInfo = parseSubagentLaunch(search.toolName, search.argumentsJson, search.toolKind)!!
        assertEquals("browser", searchInfo.profile)
        assertEquals("上海小笼包", searchInfo.description)
        assertTrue(search.toolName.startsWith("Launching browser agent:"))
        assertEquals("agent", search.toolKind)
        val searchInvocation = ChatToolInvocation(
            id = "s1",
            toolName = search.toolName,
            argumentsJson = search.argumentsJson,
            isRunning = true,
            toolKind = search.toolKind,
        )
        assertTrue(searchInvocation.isBrowserDeskSubagent())
        assertTrue(isPunchedWebBrowserAgent(search.argumentsJson))
        assertTrue(pendingHasPunchedBrowserAgent(listOf(searchInvocation)))

        val fetch = punchThroughWebToBrowserAgent(
            toolName = "FetchURL",
            argumentsJson = """{"url":"https://www.dianping.com/shop/1"}""",
            toolKind = "",
            skipBecauseNativeBrowserChild = pendingHasPunchedBrowserAgent(listOf(searchInvocation)),
            topicIdHint = lastPunchedBrowserTopicId(listOf(searchInvocation)),
        )
        assertEquals("FetchURL", fetch.toolName)
        assertFalse(isPunchedWebBrowserAgent(fetch.argumentsJson))
        val fetchInvocation = ChatToolInvocation(
            id = "f1",
            toolName = fetch.toolName,
            argumentsJson = fetch.argumentsJson,
            toolKind = fetch.toolKind,
        )
        assertFalse(fetchInvocation.isBrowserDeskSubagent())
        assertTrue(fetchInvocation.isSilentBrowserHostTool(listOf(searchInvocation)))
        assertTrue(
            collectToolCollaborationInvocations(listOf(searchInvocation, fetchInvocation)).isEmpty(),
        )
    }

    @Test
    fun punchesStandaloneFetchUrlWhenNoSearchAgentExists() {
        val fetch = punchThroughWebToBrowserAgent(
            toolName = "FetchURL",
            argumentsJson = """{"url":"https://www.dianping.com/shop/1"}""",
            toolKind = "",
        )
        val fetchInfo = parseSubagentLaunch(fetch.toolName, fetch.argumentsJson, fetch.toolKind)!!
        assertEquals("browser", fetchInfo.profile)
        assertEquals("dianping.com", fetchInfo.description)
        assertTrue(org.json.JSONObject(fetch.argumentsJson).optString("query").isBlank())
        assertEquals(
            "https://www.dianping.com/shop/1",
            org.json.JSONObject(fetch.argumentsJson).optString("url"),
        )
        val standalone = ChatToolInvocation(
            id = "f1",
            toolName = fetch.toolName,
            argumentsJson = fetch.argumentsJson,
            isRunning = true,
            toolKind = fetch.toolKind,
        )
        // The browser card renders this fetch as a page card with its own reading animation, so
        // the trace no longer prints "已使用 Fetching: …" beside it. It is still a browser agent.
        assertTrue(standalone.isSilentBrowserHostTool())
        assertTrue(standalone.isBrowserDeskSubagent())
        assertEquals(listOf("f1"), listOf(standalone).withoutRedundantFetchAgents().map { it.id })
    }

    @Test
    fun punchedFetchIsHiddenWhenSearchAgentAlreadyExists() {
        val search = punchThroughWebToBrowserAgent(
            toolName = "WebSearch",
            argumentsJson = """{"query":"GPT6 最新消息"}""",
            toolKind = "search",
        )
        val fetch = punchThroughWebToBrowserAgent(
            toolName = "FetchURL",
            argumentsJson = """{"url":"https://www.pcmag.com/news/gpt-6"}""",
            toolKind = "",
        )
        val searchInvocation = ChatToolInvocation(
            id = "s1",
            toolName = search.toolName,
            argumentsJson = search.argumentsJson,
            isRunning = true,
            toolKind = search.toolKind,
        )
        val fetchInvocation = ChatToolInvocation(
            id = "f1",
            toolName = fetch.toolName,
            argumentsJson = fetch.argumentsJson,
            toolKind = fetch.toolKind,
        )
        assertTrue(fetchInvocation.isPunchedWebFetch())
        assertTrue(fetchInvocation.isSilentBrowserHostTool(listOf(searchInvocation)))
        assertEquals(
            listOf("s1"),
            listOf(searchInvocation, fetchInvocation).withoutRedundantFetchAgents().map { it.id },
        )
    }

    @Test
    fun extraWebSearchStaysHostToolAfterPunchedSearchAgent() {
        val search = punchThroughWebToBrowserAgent(
            toolName = "WebSearch",
            argumentsJson = """{"query":"GPT6 最新消息"}""",
            toolKind = "search",
        )
        val searchInvocation = ChatToolInvocation(
            id = "s1",
            toolName = search.toolName,
            argumentsJson = search.argumentsJson,
            isRunning = true,
            toolKind = search.toolKind,
        )
        val extra = punchThroughWebToBrowserAgent(
            toolName = "WebSearch",
            argumentsJson = """{"query":"GPT-6 Astra OpenAI September 2026 release features"}""",
            toolKind = "search",
            skipBecauseNativeBrowserChild = pendingHasPunchedBrowserAgent(listOf(searchInvocation)),
        )
        assertEquals("WebSearch", extra.toolName)
        val extraInvocation = ChatToolInvocation(
            id = "s2",
            toolName = extra.toolName,
            argumentsJson = extra.argumentsJson,
            toolKind = extra.toolKind,
        )
        assertTrue(extraInvocation.isSilentBrowserHostTool(listOf(searchInvocation)))
    }

    @Test
    fun punchReplacesPlaceholderSearchTopicWithQuery() {
        val punched = punchThroughWebToBrowserAgent(
            toolName = "WebSearch",
            argumentsJson = """{"query":"GPT6 最新消息"}""",
            toolKind = "search",
            topicIdHint = "search",
        )
        assertEquals("GPT6", org.json.JSONObject(punched.argumentsJson).optString("topic_id"))
        assertEquals("GPT6", org.json.JSONObject(punched.argumentsJson).optString("query"))
    }

    @Test
    fun doesNotPunchWebSearchUnderLiveNativeBrowserAgent() {
        val native = ChatToolInvocation(
            id = "a1",
            toolName = "Launching browser agent: 西湖",
            argumentsJson = """{"subagent_type":"browser","description":"西湖","prompt":"search"}""",
            isRunning = true,
            toolKind = "agent",
        )
        assertTrue(native.isBrowserSubagent())
        assertFalse(isPunchedWebBrowserAgent(native.argumentsJson))
        assertTrue(pendingHasNativeBrowserSubagent(listOf(native)))
        val skipped = punchThroughWebToBrowserAgent(
            toolName = "WebSearch",
            argumentsJson = """{"query":"西湖门票"}""",
            toolKind = "search",
            skipBecauseNativeBrowserChild = true,
        )
        assertEquals("WebSearch", skipped.toolName)
        assertEquals("search", skipped.toolKind)
    }

    @Test
    fun keepsPunchedTitleOnEmptyToolUpdate() {
        val first = punchThroughWebToBrowserAgent(
            toolName = "WebSearch",
            argumentsJson = """{"query":"杭州"}""",
            toolKind = "",
        )
        val update = punchThroughWebToBrowserAgent(
            toolName = "",
            argumentsJson = "{}",
            toolKind = "",
            existingToolName = first.toolName,
            existingArgumentsJson = first.argumentsJson,
            existingToolKind = first.toolKind,
        )
        assertEquals(first.toolName, update.toolName)
        assertEquals(first.argumentsJson, update.argumentsJson)
        assertTrue(parseSubagentLaunch(update.toolName, update.argumentsJson, update.toolKind)!!.profile == "browser")
    }

    @Test
    fun coalescesParallelBrowserAgentsIntoOneSwarm() {
        val first = punchThroughWebToBrowserAgent(
            toolName = "WebSearch",
            argumentsJson = """{"query":"本帮菜"}""",
            toolKind = "search",
        )
        val second = punchThroughWebToBrowserAgent(
            toolName = "WebSearch",
            argumentsJson = """{"query":"小笼包"}""",
            toolKind = "search",
        )
        val invocations = listOf(
            ChatToolInvocation(
                id = "s1",
                toolName = first.toolName,
                argumentsJson = first.argumentsJson,
                isRunning = true,
                toolKind = first.toolKind,
            ),
            ChatToolInvocation(
                id = "s2",
                toolName = second.toolName,
                argumentsJson = second.argumentsJson,
                isRunning = true,
                toolKind = second.toolKind,
            ),
        )
        assertTrue(invocations.all { it.isBrowserDeskSingle() })
        val coalesced = coalesceParallelBrowserAgents(invocations)
        assertEquals(1, coalesced.size)
        val swarm = coalesced.single()
        assertTrue(swarm.isBrowserSwarm())
        assertFalse(swarm.isBrowserDeskSingle())
        val info = parseSubagentLaunch(swarm.toolName, swarm.argumentsJson, swarm.toolKind)!!
        assertTrue(info.isSwarm)
        assertEquals(listOf("本帮菜", "小笼包"), info.items)
        assertTrue(org.json.JSONObject(swarm.argumentsJson).optBoolean(AetherCoalescedWebKey))
        val collected = collectToolCollaborationInvocations(invocations)
        assertTrue(collected.isEmpty())
        assertTrue(collectToolCollaborationInvocations(invocations, hideBrowserSwarmUi = true).isEmpty())
    }

    @Test
    fun singleBrowserAgentIsNotASwarmCapsule() {
        val punched = punchThroughWebToBrowserAgent(
            toolName = "WebSearch",
            argumentsJson = """{"query":"西湖"}""",
            toolKind = "search",
        )
        val invocation = ChatToolInvocation(
            id = "s1",
            toolName = punched.toolName,
            argumentsJson = punched.argumentsJson,
            isRunning = true,
            toolKind = punched.toolKind,
        )
        assertEquals(listOf(invocation), coalesceParallelBrowserAgents(listOf(invocation)))
        assertTrue(collectToolCollaborationInvocations(listOf(invocation)).isEmpty())
        val coder = ChatToolInvocation(
            id = "c1",
            toolName = "Launching coder agent: scan",
            argumentsJson = """{"subagent_type":"coder","prompt":"scan"}""",
            toolKind = "agent",
        )
        val mixed = collectToolCollaborationInvocations(listOf(invocation, coder))
        assertEquals(listOf("c1"), mixed.map { it.id })
    }

    @Test
    fun realBrowserAgentSwarmStaysInCapsulesUnlessBrowserPreviewHidesIt() {
        val swarm = ChatToolInvocation(
            id = "swarm-1",
            toolName = "Launching agent swarm: topics",
            argumentsJson = """{"subagent_type":"browser","description":"topics","items":["西湖","门票"],"prompt_template":"Research {{item}}"}""",
            isRunning = true,
            toolKind = "agent",
        )
        assertTrue(swarm.isBrowserSwarm())
        assertFalse(swarm.isCoalescedBrowserSwarm())
        assertEquals(listOf("swarm-1"), collectToolCollaborationInvocations(listOf(swarm)).map { it.id })
        assertTrue(collectToolCollaborationInvocations(listOf(swarm), hideBrowserSwarmUi = true).isEmpty())
    }

    @Test
    fun coalescedSwarmStaysRunningWhileAMemberIsStillQueued() {
        val done = ChatToolInvocation(
            id = "s1",
            toolName = "Launching browser agent: 西湖门票",
            argumentsJson = """{"subagent_type":"browser","description":"西湖门票","query":"西湖门票"}""",
            outputJson = "GUI_TASK_SUCCEEDED: 80 元",
            isRunning = false,
            completedAtMillis = 2L,
            toolKind = "agent",
        )
        val queued = ChatToolInvocation(
            id = "s2",
            toolName = "Launching browser agent: 开放时间",
            argumentsJson = """{"subagent_type":"browser","description":"开放时间","query":"开放时间"}""",
            outputJson = "",
            isRunning = false,
            toolKind = "agent",
        )
        val swarm = synthesizeBrowserSwarmInvocation(listOf(done, queued))
        assertTrue(swarm.isRunning)
        assertTrue(browserSwarmMemberPending(queued))
        assertFalse(browserSwarmMemberPending(done))
        val parsed = parseAgentSwarmResult(swarm.outputJson)
        requireNotNull(parsed)
        assertEquals(listOf("s1"), parsed.members.map { it.agentId })
    }

    @Test
    fun swarmHeaderShowsMemberDoneUntilEveryMemberFinishes() {
        val running = browserSwarmHeaderState(
            personNames = listOf("Alice", "Bob"),
            members = listOf(
                SwarmMemberView(
                    index = 0,
                    item = "西湖门票",
                    prompt = "",
                    result = SwarmMemberResult(
                        outcome = "completed",
                        item = "西湖门票",
                        agentId = "s1",
                        body = "80",
                    ),
                ),
                SwarmMemberView(
                    index = 1,
                    item = "开放时间",
                    prompt = "",
                    result = null,
                ),
            ),
            swarmRunning = true,
            headline = "西湖门票 · 开放时间",
        )
        assertEquals(BrowserSwarmHeaderKind.MemberDone, running.kind)
        assertEquals("西湖门票", running.personName)
        val done = browserSwarmHeaderState(
            personNames = listOf("Alice", "Bob"),
            members = listOf(
                SwarmMemberView(
                    index = 0,
                    item = "西湖门票",
                    prompt = "",
                    result = SwarmMemberResult(
                        outcome = "completed",
                        item = "西湖门票",
                        agentId = "s1",
                        body = "80",
                    ),
                ),
                SwarmMemberView(
                    index = 1,
                    item = "开放时间",
                    prompt = "",
                    result = SwarmMemberResult(
                        outcome = "completed",
                        item = "开放时间",
                        agentId = "s2",
                        body = "6:00",
                    ),
                ),
            ),
            swarmRunning = false,
            headline = "西湖门票 · 开放时间",
        )
        assertEquals(BrowserSwarmHeaderKind.AllDone, done.kind)
    }

    @Test
    fun operateFollowUpQueryPunchesWebSearchAsFetch() {
        val punched = punchThroughWebToBrowserAgent(
            toolName = "WebSearch",
            argumentsJson =
                """{"query":"帮我打开官网报名\nhttps://www.nvidia.com/dgx-spark-hackathon"}""",
            toolKind = "search",
        )
        val json = org.json.JSONObject(punched.argumentsJson)
        assertEquals("fetch", json.optString(AetherPunchedWebKey))
        assertEquals(
            "https://www.nvidia.com/dgx-spark-hackathon",
            json.optString("url"),
        )
        assertTrue(json.optString("query").isBlank())
        val prompt = json.optString("prompt")
        assertTrue(prompt.contains("page_form"))
        assertTrue(prompt.contains("Never invent"))
        assertFalse(prompt.startsWith("Search the web"))
    }

    @Test
    fun operateUrlThisTurnRewritesUnrelatedSearchIntoFetch() {
        val punched = punchThroughWebToBrowserAgent(
            toolName = "WebSearch",
            argumentsJson = """{"query":"NVIDIA hackathon"}""",
            toolKind = "search",
            operateUrl = "https://www.nvidia.com/dgx-spark-hackathon",
        )
        val json = org.json.JSONObject(punched.argumentsJson)
        assertEquals("fetch", json.optString(AetherPunchedWebKey))
        assertEquals(
            "https://www.nvidia.com/dgx-spark-hackathon",
            json.optString("url"),
        )
        assertTrue(json.optString("prompt").contains("page_form"))
    }

    @Test
    fun lookupFetchStaysReadOnlyWhenNotOperateTurn() {
        val fetch = punchThroughWebToBrowserAgent(
            toolName = "FetchURL",
            argumentsJson = """{"url":"https://www.nvidia.com/dgx-spark-hackathon"}""",
            toolKind = "",
        )
        val prompt = org.json.JSONObject(fetch.argumentsJson).optString("prompt")
        assertTrue(prompt.startsWith("Open and read this URL"))
        assertFalse(prompt.contains("page_form"))
    }

    @Test
    fun lookupThenOperateSearchUsesSearchReadOfficialSequence() {
        val punched = punchThroughWebToBrowserAgent(
            toolName = "WebSearch",
            argumentsJson = """{"query":"帮我搜索第三届 NVIDIA DGX Spark 黑客松 的信息，并打开官网帮我报名"}""",
            toolKind = "search",
            lookupThenOperate = true,
        )
        val json = org.json.JSONObject(punched.argumentsJson)
        assertEquals("search", json.optString(AetherPunchedWebKey))
        assertEquals("第三届 NVIDIA DGX Spark 黑客松", json.optString("query"))
        val prompt = json.optString("prompt")
        assertTrue(prompt.contains("Search first"))
        assertTrue(prompt.contains("page_read"))
        assertTrue(prompt.contains("Never invent URLs"))
        assertTrue(prompt.contains("Do not page_form"))
        assertTrue(prompt.contains("阅读原文"))
        assertTrue(prompt.contains("tabs_navigate"))
        assertFalse(prompt.contains("action=fill"))
        assertFalse(prompt.startsWith("Search the web"))
    }

    @Test
    fun lookupThenOperateFetchStillSearchesFirst() {
        val punched = punchThroughWebToBrowserAgent(
            toolName = "FetchURL",
            argumentsJson = """{"url":"https://developer.nvidia.cn/hackathon/made-up"}""",
            toolKind = "",
            lookupThenOperate = true,
            topicIdHint = "第三届 NVIDIA DGX Spark 黑客松",
        )
        val json = org.json.JSONObject(punched.argumentsJson)
        assertEquals("search", json.optString(AetherPunchedWebKey))
        val prompt = json.optString("prompt")
        assertTrue(prompt.contains("Search first"))
        assertFalse(prompt.contains("https://developer.nvidia.cn/hackathon/made-up"))
        assertTrue(prompt.contains("Do not page_form"))
        assertTrue(prompt.contains("阅读原文"))
        assertFalse(prompt.contains("action=fill"))
    }

    @Test
    fun verifyContinueStaysOnCurrentGeckoPage() {
        val punched = punchThroughWebToBrowserAgent(
            toolName = "WebSearch",
            argumentsJson = """{"query":"人机验证已完成，请从当前页面继续。不要换搜索词，不要猜网址。"}""",
            toolKind = "search",
            lookupThenOperate = true,
        )
        val json = org.json.JSONObject(punched.argumentsJson)
        assertEquals("fetch", json.optString(AetherPunchedWebKey))
        val prompt = json.optString("prompt")
        assertTrue(prompt.contains("Stay on the current Gecko page"))
        assertTrue(prompt.contains("人机验证"))
        assertFalse(prompt.contains("Search first"))
        assertFalse(prompt.contains("action=fill"))
    }
}
