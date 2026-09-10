package kira.ditto.data

import kira.ditto.data.chatdb.ChatMessageEntity
import kira.ditto.data.chatdb.ChatMessageSummaryEntity
import kira.ditto.ui.AttachmentKind
import kira.ditto.ui.BrowserInlineImage
import kira.ditto.ui.ChatAttachment
import kira.ditto.ui.ChatMessage
import kira.ditto.ui.ChatSession
import kira.ditto.ui.ChatToolInvocation
import kira.ditto.ui.ToolCallDiff
import kira.ditto.ui.MessageAuthor
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRepositorySerializationTest {
    @Test
    fun serializationKeepsInlineImageBytesAndWorkspacePath() {
        val serialized = serializeChatSessions(
            listOf(
                ChatSession(
                    id = "session-1",
                    title = "Image",
                    preview = "Image",
                    messages = listOf(
                        ChatMessage(
                            id = "user-1",
                            author = MessageAuthor.User,
                            text = "see attached",
                            attachments = listOf(
                                ChatAttachment(
                                    id = "attachment-1",
                                    uri = "content://image",
                                    name = "image.png",
                                    mimeType = "image/png",
                                    sizeBytes = 1_024,
                                    kind = AttachmentKind.Image,
                                    workspacePath = "/workspace/image.png",
                                    inlineBase64 = "a".repeat(120_000),
                                )
                            ),
                        )
                    ),
                )
            )
        )

        val attachment = JSONArray(serialized)
            .getJSONObject(0)
            .getJSONArray("messages")
            .getJSONObject(0)
            .getJSONArray("attachments")
            .getJSONObject(0)

        assertEquals("a".repeat(120_000), attachment.getString("inlineBase64"))
        assertEquals("/workspace/image.png", attachment.getString("workspacePath"))
    }

    @Test
    fun serializationKeepsLargeToolOutputJson() {
        val serialized = serializeChatSessions(
            listOf(
                ChatSession(
                    id = "session-1",
                    title = "Tool",
                    preview = "Tool",
                    messages = listOf(
                        ChatMessage(
                            id = "agent-1",
                            author = MessageAuthor.Agent,
                            text = "",
                            toolInvocations = listOf(
                                ChatToolInvocation(
                                    id = "tool-1",
                                    toolName = "bash",
                                    argumentsJson = JSONObject()
                                        .put("command", "yes")
                                        .toString(),
                                    outputJson = JSONObject()
                                        .put("ok", true)
                                        .put("stdout", "x".repeat(140_000))
                                        .toString(),
                                )
                            ),
                        )
                    ),
                )
            )
        )

        val outputJson = JSONArray(serialized)
            .getJSONObject(0)
            .getJSONArray("messages")
            .getJSONObject(0)
            .getJSONArray("toolInvocations")
            .getJSONObject(0)
            .getString("outputJson")
        val output = JSONObject(outputJson)

        assertTrue(output.getBoolean("ok"))
        assertEquals("x".repeat(140_000), output.getString("stdout"))
    }

    @Test
    fun summaryMappingDoesNotReadLegacyMessageJsonPayload() {
        val message = ChatMessageEntityMapper.summaryToChatMessage(
            ChatMessageSummaryEntity(
                sessionId = "session-1",
                id = "agent-1",
                position = 0,
                author = MessageAuthor.Agent.name,
                text = "Recovered from typed columns",
                createdAtMillis = 123L,
                isIncomplete = true,
            )
        )

        assertEquals("agent-1", message.id)
        assertEquals(MessageAuthor.Agent, message.author)
        assertEquals("Recovered from typed columns", message.text)
        assertEquals(123L, message.createdAtMillis)
        assertTrue(message.isIncomplete)
        assertTrue(message.toolInvocations.isEmpty())
        assertTrue(message.providerPayloadJson.isNullOrBlank())
    }

    @Test
    fun entityFallbackPreservesStoredIncompleteFlag() {
        val message = ChatMessageEntityMapper.toChatMessage(
            ChatMessageEntity(
                sessionId = "session-1",
                id = "agent-1",
                position = 0,
                messageJson = "{not-valid-json",
                author = MessageAuthor.Agent.name,
                text = "Partial response",
                isIncomplete = true,
            ),
            messageIndex = 0,
        )

        assertEquals("agent-1", message.id)
        assertEquals("Partial response", message.text)
        assertTrue(message.isIncomplete)
    }

    @Test
    fun entityMappingUsesStoredIncompleteColumnAsAuthority() {
        val message = ChatMessageEntityMapper.toChatMessage(
            ChatMessageEntity(
                sessionId = "session-1",
                id = "agent-1",
                position = 0,
                messageJson = "{\"id\":\"agent-1\",\"isIncomplete\":true}",
                author = MessageAuthor.Agent.name,
                text = "Complete response",
                isIncomplete = false,
            ),
            messageIndex = 0,
        )

        assertFalse(message.isIncomplete)
    }

    @Test
    fun messageJsonBatchFlushesBeforeJsonByteBudgetIsExceeded() {
        val limit = MessageJsonBatchByteLimit.toLong()

        assertFalse(shouldStartNewMessageJsonBatch(0L, limit + 1L))
        assertFalse(shouldStartNewMessageJsonBatch(limit - 1L, 1L))
        assertTrue(shouldStartNewMessageJsonBatch(limit - 1L, 2L))
        assertTrue(shouldStartNewMessageJsonBatch(limit, 1L))
    }

    @Test
    fun migrationParseResultMarksCorruptedJsonAsRecoverable() {
        val result = parseChatSessionsForMigration("{not-valid-json")

        assertTrue(result.recoveredFromCorruption)
        assertEquals(1, result.sessions.size)
        assertTrue(result.sessions.first().id.startsWith("corrupt-chat-state-"))
    }

    @Test
    fun migrationParseResultKeepsValidJsonSuccessful() {
        val result = parseChatSessionsForMigration(
            """
                [{"id":"session-1","title":"First","preview":"First","messages":[]}]
            """.trimIndent()
        )

        assertFalse(result.recoveredFromCorruption)
        assertEquals("session-1", result.sessions.single().id)
    }

    @Test
    fun sessionRoundTripPreservesIncompleteStreamingCheckpoint() {
        val serialized = serializeChatSessions(
            listOf(
                ChatSession(
                    id = "session-checkpoint",
                    title = "Checkpoint",
                    preview = "partial",
                    messages = listOf(
                        ChatMessage(
                            id = "agent-checkpoint",
                            author = MessageAuthor.Agent,
                            text = "partial output",
                            isIncomplete = true,
                            responseGroupId = "agent-group-turn-1",
                        )
                    ),
                )
            )
        )

        val restored = parseChatSessions(serialized).single().messages.single()

        assertTrue(restored.isIncomplete)
        assertEquals("partial output", restored.text)
        assertEquals("agent-group-turn-1", restored.responseGroupId)
    }

    @Test
    fun sessionRoundTripPreservesStoppedReconnectStatus() {
        val serialized = serializeChatSessions(
            listOf(
                ChatSession(
                    id = "session-reconnect",
                    title = "Reconnect",
                    preview = "Reconnect",
                    messages = listOf(
                        ChatMessage(
                            id = "agent-reconnect",
                            author = MessageAuthor.Agent,
                            text = "partial output",
                            statusText = "Reconnecting... 2/5",
                            statusDetail = "fetch failed: connect timed out (ETIMEDOUT)",
                        )
                    ),
                )
            )
        )

        val restored = parseChatSessions(serialized).single().messages.single()

        assertEquals("Reconnecting... 2/5", restored.statusText)
        assertEquals("fetch failed: connect timed out (ETIMEDOUT)", restored.statusDetail)
    }

    @Test
    fun sessionRoundTripPreservesChromeSelection() {
        val serialized = serializeChatSessions(
            listOf(
                ChatSession(
                    id = "session-chrome",
                    title = "Chrome",
                    preview = "Chrome",
                    messages = emptyList(),
                    chromeEnabled = true,
                )
            )
        )

        val reparsed = parseChatSessions(serialized)

        assertTrue(reparsed.single().chromeEnabled)
    }

    @Test
    fun migrationKeepsLegacyCurrentSessionWhenItExists() {
        val sessions = listOf(
            ChatSession(id = "session-1", title = "First", preview = "First", messages = emptyList()),
            ChatSession(id = "session-2", title = "Second", preview = "Second", messages = emptyList()),
        )

        val currentSessionId = resolveLegacyCurrentSessionIdForMigration(
            legacyCurrentSessionId = "session-2",
            legacySessions = sessions,
        )

        assertEquals("session-2", currentSessionId)
    }

    @Test
    fun migrationFallsBackToFirstSessionWhenLegacyCurrentSessionIsAbsent() {
        val sessions = listOf(
            ChatSession(id = "session-1", title = "First", preview = "First", messages = emptyList()),
            ChatSession(id = "session-2", title = "Second", preview = "Second", messages = emptyList()),
        )

        val currentSessionId = resolveLegacyCurrentSessionIdForMigration(
            legacyCurrentSessionId = null,
            legacySessions = sessions,
        )

        assertEquals("session-1", currentSessionId)
    }

    @Test
    fun migrationFallsBackToFirstSessionWhenLegacyCurrentSessionIsMissing() {
        val sessions = listOf(
            ChatSession(id = "session-1", title = "First", preview = "First", messages = emptyList()),
            ChatSession(id = "session-2", title = "Second", preview = "Second", messages = emptyList()),
        )

        val currentSessionId = resolveLegacyCurrentSessionIdForMigration(
            legacyCurrentSessionId = "missing-session",
            legacySessions = sessions,
        )

        assertEquals("session-1", currentSessionId)
    }

    @Test
    fun sessionRoundTripPreservesToolKindAndDiffs() {
        val serialized = serializeChatSessions(
            listOf(
                ChatSession(
                    id = "session-1",
                    title = "Diffs",
                    preview = "Diffs",
                    messages = listOf(
                        ChatMessage(
                            id = "agent-1",
                            author = MessageAuthor.Agent,
                            text = "",
                            toolInvocations = listOf(
                                ChatToolInvocation(
                                    id = "tool-1",
                                    toolName = "edit_file",
                                    argumentsJson = "{}",
                                    toolKind = "edit",
                                    diffs = listOf(
                                        ToolCallDiff(
                                            path = "/workspace/a.txt",
                                            oldText = "old",
                                            newText = "new",
                                        )
                                    ),
                                    guiStepsJson = """[{"id":"1-1","sequence":1,"action":"launch","label":"打开美团","thought":"打开美团","ok":true}]""",
                                )
                            ),
                        )
                    ),
                )
            )
        )

        val restored = parseChatSessions(serialized)
            .single().messages.single().toolInvocations.single()

        assertEquals("edit", restored.toolKind)
        assertEquals(1, restored.diffs.size)
        assertEquals("/workspace/a.txt", restored.diffs[0].path)
        assertEquals("old", restored.diffs[0].oldText)
        assertEquals("new", restored.diffs[0].newText)
        assertTrue(restored.guiStepsJson.contains("打开美团"))
    }

    @Test
    fun serializationKeepsKnowledgeCitations() {
        val serialized = serializeChatSessions(
            listOf(
                ChatSession(
                    id = "session-1",
                    title = "Persona",
                    preview = "Persona",
                    messages = listOf(
                        ChatMessage(
                            id = "user-1",
                            author = MessageAuthor.User,
                            text = "他今年几岁",
                            knowledgeCitations = listOf(
                                KnowledgeCitation(
                                    index = 1,
                                    sourceName = "凤翕晟_简.pdf",
                                    text = "凤翕晟今年 28 岁。",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val restored = parseChatSessions(serialized).single().messages.single()
        assertEquals(1, restored.knowledgeCitations.size)
        assertEquals(1, restored.knowledgeCitations.single().index)
        assertEquals("凤翕晟今年 28 岁。", restored.knowledgeCitations.single().text)
        assertEquals("凤翕晟_简.pdf", restored.knowledgeCitations.single().sourceName)
    }

    @Test
    fun serializationKeepsBrowserInlineImages() {
        val serialized = serializeChatSessions(
            listOf(
                ChatSession(
                    id = "session-1",
                    title = "Photos",
                    preview = "Photos",
                    messages = listOf(
                        ChatMessage(
                            id = "user-1",
                            author = MessageAuthor.User,
                            text = "西湖",
                            browserInlineImages = listOf(
                                BrowserInlineImage(
                                    url = "https://cdn.example/west-lake.jpg",
                                    alt = "西湖",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val restored = parseChatSessions(serialized).single().messages.single()
        assertEquals(1, restored.browserInlineImages.size)
        assertEquals("https://cdn.example/west-lake.jpg", restored.browserInlineImages.single().url)
        assertEquals("西湖", restored.browserInlineImages.single().alt)
    }
}
