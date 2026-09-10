package kira.ditto.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import kira.ditto.data.chatdb.ChatHistoryDao
import kira.ditto.data.chatdb.ChatHistoryDatabase
import kira.ditto.data.chatdb.AndroidChatHistoryDatabaseFactory
import kira.ditto.data.chatdb.ChatAgentMessageRefEntity
import kira.ditto.data.chatdb.ChatAgentSessionEntity
import kira.ditto.data.chatdb.ChatMessageEntity
import kira.ditto.data.chatdb.ChatMessageSummaryEntity
import kira.ditto.data.chatdb.readFullMessageJson
import kira.ditto.data.chatdb.splitForStorage
import kira.ditto.data.chatdb.upsertMessagesWithPayloads
import kira.ditto.data.chatdb.ChatSessionEntity
import kira.ditto.data.chatdb.ChatSessionMessageStatsEntity
import kira.ditto.data.chatdb.ChatStateMetaEntity
import kira.ditto.data.chatdb.ChatWorkspaceFileRefEntity
import kira.ditto.data.chatdb.LlmUsageDao
import kira.ditto.data.chatdb.LlmUsageRecordEntity
import kira.ditto.data.kimi.mergeTokensByDisplayModelId

import kira.ditto.ui.AttachmentKind
import kira.ditto.ui.AttachmentWorkspaceState
import kira.ditto.ui.BrowserInlineImage
import kira.ditto.ui.ChatAttachment
import kira.ditto.ui.ChatBranchGroup
import kira.ditto.browser.browserDeskPreviewFromPersistJson
import kira.ditto.browser.toPersistJson
import kira.ditto.ui.ChatMessage
import kira.ditto.ui.ChatSession
import kira.ditto.ui.ChatToolInvocation
import kira.ditto.ui.ToolCallDiff
import kira.ditto.ui.ChatUsageStatistics
import kira.ditto.ui.MessageAuthor
import kira.ditto.ui.MessageDisplayKind
import kira.ditto.ui.ReasoningSummaryChunk
import kira.ditto.ui.ReasoningTrace
import kira.ditto.ui.syncActiveBranches
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

private const val DraftSessionId = "draft"
internal const val MessageJsonBatchByteLimit = 64 * 1024
private const val MaxMessagesPerJsonBatch = 8
private const val WorkspaceFileRefQueryChunkSize = 500

/** How many reads of the same page we keep. Anything a citation still names survives this. */
internal const val MaxBrowserPageVersions = 3

/** How far back the recurrence gate looks when deciding a topic is a line of interest. */
internal const val BrowserMemoClusterWindowMillis = 14L * 24L * 60L * 60L * 1000L

private const val BrowserOriginFactLimit = 5

/**
 * How many trailing messages of the active conversation are hydrated up front. Older
 * messages are fetched a page at a time when the user scrolls to the top, so opening a
 * thousand-message thread costs the same as opening a short one.
 */
const val ChatMessageWindowSize = 60
const val ChatMessagePageSize = 40

/** How many models the statistics page charts. */
private const val UsageModelBreakdownLimit = 8

/** Detailed usage rows older than this only contribute to the SQL aggregates. */
const val UsageDetailWindowMillis = 30L * 24L * 60L * 60L * 1000L

internal fun shouldStartNewMessageJsonBatch(currentBytes: Long, nextBytes: Long): Boolean =
    currentBytes > 0L && nextBytes > MessageJsonBatchByteLimit - currentBytes


internal val Context.chatDataStore by preferencesDataStore(name = "aether_chats")

data class PersistedChatState(
    val sessions: List<ChatSession> = emptyList(),
    val currentSessionId: String = DraftSessionId,
)

data class ChatUsageStatisticsSnapshot(
    val sessionId: String,
    val statistics: ChatUsageStatistics,
    val source: String = LlmUsageSources.Turn,
    val modelId: String = "",
    val providerId: String = "",
)

data class AssistantResponseCheckpointTarget(
    val sessionId: String,
    val responseGroupId: String,
)

data class AssistantResponseCheckpoint(
    val target: AssistantResponseCheckpointTarget,
    val fromPosition: Int,
    val messages: List<ChatMessage>,
) {
    init {
        require(fromPosition >= 0) { "fromPosition must be non-negative" }
        require(messages.isNotEmpty()) { "messages must not be empty" }
        require(messages.all { it.responseGroupId == target.responseGroupId }) {
            "checkpoint messages must belong to the target response"
        }
    }
}

enum class PersistedChatWriteIntent {
    SyncSnapshot,
    DeleteSession,
    ReplaceFromImport,
}

interface ChatStatePersistence {
    val chatState: Flow<PersistedChatState>

    suspend fun updateChatState(
        sessions: List<ChatSession>,
        currentSessionId: String,
        writeIntent: PersistedChatWriteIntent = PersistedChatWriteIntent.SyncSnapshot,
    )

    suspend fun upsertAssistantResponseCheckpoints(
        checkpoints: List<AssistantResponseCheckpoint>,
    )
}

private data class AssistantResponseCheckpointUpsert(
    val sessionId: String,
    val responseGroupId: String,
    val fromPosition: Int,
    val messages: List<ChatMessage>,
)

// BundledSQLiteDriver databases use Room's connection-based transaction API.
private suspend fun <R> ChatHistoryDatabase.withTransaction(
    block: suspend () -> R,
): R = useWriterConnection { connection ->
    connection.immediateTransaction {
        block()
    }
}

class ChatRepository(
    private val context: Context,
    private val database: ChatHistoryDatabase = AndroidChatHistoryDatabaseFactory.getInstance(context),
) : ChatStatePersistence {
    private val chatHistoryDao: ChatHistoryDao = database.chatHistoryDao()
    private val browserCitationDao: kira.ditto.data.chatdb.BrowserCitationDao = database.browserCitationDao()
    private val llmUsageDao: LlmUsageDao = database.llmUsageDao()
    val usageStore: LlmUsageStore = RoomLlmUsageStore()
    private val restoredMessageCache = mutableMapOf<ChatMessageCacheKey, LoadedChatMessage>()
    private val restoredMessageCacheMutex = Mutex()

    @OptIn(ExperimentalCoroutinesApi::class)
    override val chatState: Flow<PersistedChatState> = flow {
        migrateLegacyChatStateIfNeeded()
        emitAll(
            combine(
                chatHistoryDao.observeSessions(),
                chatHistoryDao.observeMeta(),
            ) { sessionRows, meta ->
                val currentSessionId = meta?.currentSessionId ?: DraftSessionId
                SessionListState(
                    rows = sessionRows,
                    currentSessionId = currentSessionId
                        .takeIf { id -> id == DraftSessionId || sessionRows.any { it.id == id } }
                        ?: sessionRows.firstOrNull()?.id
                        ?: DraftSessionId,
                )
            }.flatMapLatest { state ->
                val sessionIds = state.rows.map { it.id }
                val currentSessionId = state.currentSessionId.takeIf { it != DraftSessionId }
                if (sessionIds.isEmpty()) {
                    clearRestoredMessageCache()
                    flowOf(
                        PersistedChatState(
                            sessions = emptyList(),
                            currentSessionId = state.currentSessionId,
                        )
                    )
                } else {
                    combine(
                        chatHistoryDao.observeMessageStatsForSessions(sessionIds),
                        chatHistoryDao.observeAgentSessions(),
                        if (currentSessionId == null) {
                            clearRestoredMessageCache()
                            flowOf(emptyList())
                        } else {
                            chatHistoryDao.observeLatestMessageSummaries(
                                sessionId = currentSessionId,
                                limit = ChatMessageWindowSize,
                            ).mapLatest { newestFirst ->
                                val summaries = newestFirst.asReversed()
                                restoredMessageCacheMutex.withLock {
                                    restoredMessageCache.keys.retainAll(summaries.mapTo(mutableSetOf()) { it.cacheKey })
                                    database.withTransaction {
                                        chatHistoryDao.getMessagesForSummariesSafely(
                                            summaries = summaries,
                                            restoredMessageCache = restoredMessageCache,
                                        )
                                    }
                                }
                            }
                        },
                    ) { stats, agentSessions, currentMessages ->
                        val statsBySessionId = stats.associateBy { it.sessionId }
                        val agentBySessionId = agentSessions.associateBy { it.chatSessionId }
                        val currentMessagesBySessionId = currentMessages.groupBy { it.sessionId }
                        val sessions = state.rows.map { session ->
                            session.toChatSession(
                                messages = currentMessagesBySessionId[session.id].orEmpty(),
                                stats = statsBySessionId[session.id],
                                agent = agentBySessionId[session.id],
                            )
                        }
                        PersistedChatState(
                            sessions = sessions,
                            currentSessionId = state.currentSessionId,
                        )
                    }
                }
            }
        )
    }

    override suspend fun updateChatState(
        sessions: List<ChatSession>,
        currentSessionId: String,
        writeIntent: PersistedChatWriteIntent,
    ) {
        migrateLegacyChatStateIfNeeded()
        replaceChatStateBatched(
            sessions = sessions,
            currentSessionId = currentSessionId,
            migrationComplete = true,
            writeIntent = writeIntent,
        )
        context.chatDataStore.edit { preferences ->
            preferences.remove(SESSIONS_JSON)
            preferences.remove(CURRENT_SESSION_ID)
            preferences[ROOM_MIGRATION_COMPLETE] = true
        }
    }

    suspend fun updateChatState(
        sessions: List<ChatSession>,
        currentSessionId: String,
    ) {
        updateChatState(
            sessions = sessions,
            currentSessionId = currentSessionId,
            writeIntent = PersistedChatWriteIntent.SyncSnapshot,
        )
    }

    /**
     * The spaces that exist, with the default one guaranteed present.
     *
     * The migration seeds it, but a database created fresh at version 14 never runs that
     * migration - so the guarantee is restated here rather than assumed.
     */
    suspend fun listWorkspaces(): List<kira.ditto.data.chatdb.WorkspaceEntity> {
        val existing = chatHistoryDao.listWorkspaces()
        if (existing.any { it.id == kira.ditto.data.chatdb.DefaultWorkspaceId }) return existing
        val default = kira.ditto.data.chatdb.WorkspaceEntity(
            id = kira.ditto.data.chatdb.DefaultWorkspaceId,
            name = kira.ditto.data.chatdb.DefaultWorkspaceName,
            createdAtMillis = System.currentTimeMillis(),
            sortOrder = 0,
        )
        chatHistoryDao.upsertWorkspaces(listOf(default))
        return listOf(default) + existing
    }

    suspend fun upsertWorkspace(workspace: kira.ditto.data.chatdb.WorkspaceEntity) {
        chatHistoryDao.upsertWorkspaces(listOf(workspace))
    }

    /**
     * Remove a space. Conversations are re-homed rather than deleted: losing a space should not
     * silently lose what was said in it, and the default space always exists to receive them.
     */
    suspend fun deleteWorkspace(workspaceId: String) {
        if (workspaceId == kira.ditto.data.chatdb.DefaultWorkspaceId) return
        chatHistoryDao.moveSessionsToWorkspace(
            fromWorkspaceId = workspaceId,
            toWorkspaceId = kira.ditto.data.chatdb.DefaultWorkspaceId,
        )
        chatHistoryDao.deleteWorkspace(workspaceId)
    }

    suspend fun getSessionWithMessages(sessionId: String): ChatSession? {
        migrateLegacyChatStateIfNeeded()
        return restoredMessageCacheMutex.withLock {
            database.withTransaction {
                val session = chatHistoryDao.getSession(sessionId) ?: return@withTransaction null
                val summaries = chatHistoryDao.getMessageSummariesForSession(sessionId)
                val messages = chatHistoryDao.getMessagesForSummariesSafely(
                    summaries = summaries,
                    restoredMessageCache = restoredMessageCache,
                )
                session.toChatSession(
                    messages = messages,
                    agent = chatHistoryDao.getAgentSession(sessionId),
                )
            }
        }
    }

    /**
     * Loads a session with only its newest [limit] messages. This is what the UI opens a
     * conversation with; [getSessionWithMessages] stays available for the paths that
     * genuinely need every message (model requests, export, token accounting).
     */
    suspend fun getSessionWindow(
        sessionId: String,
        limit: Int = ChatMessageWindowSize,
    ): ChatSession? {
        migrateLegacyChatStateIfNeeded()
        return restoredMessageCacheMutex.withLock {
            database.withTransaction {
                val session = chatHistoryDao.getSession(sessionId) ?: return@withTransaction null
                val summaries = chatHistoryDao
                    .getLatestMessageSummaries(sessionId = sessionId, limit = limit)
                    .asReversed()
                val messages = chatHistoryDao.getMessagesForSummariesSafely(
                    summaries = summaries,
                    restoredMessageCache = restoredMessageCache,
                )
                session.toChatSession(
                    messages = messages,
                    stats = chatHistoryDao.getMessageStatsForSessions(listOf(sessionId)).firstOrNull(),
                    agent = chatHistoryDao.getAgentSession(sessionId),
                )
            }
        }
    }

    /**
     * Keyset page of the messages immediately before [beforePosition], oldest first.
     * Returns an empty list once the caller has reached the start of the conversation.
     */
    suspend fun loadOlderMessages(
        sessionId: String,
        beforePosition: Int,
        limit: Int = ChatMessagePageSize,
    ): List<ChatMessage> {
        if (beforePosition <= 0) return emptyList()
        migrateLegacyChatStateIfNeeded()
        return restoredMessageCacheMutex.withLock {
            database.withTransaction {
                val summaries = chatHistoryDao.getMessageSummariesBefore(
                    sessionId = sessionId,
                    beforePosition = beforePosition,
                    limit = limit,
                ).asReversed()
                chatHistoryDao.getMessagesForSummariesSafely(
                    summaries = summaries,
                    restoredMessageCache = restoredMessageCache,
                ).map { it.message }
            }
        }
    }

    suspend fun getSessionsWithMessages(): List<ChatSession> {
        migrateLegacyChatStateIfNeeded()
        return restoredMessageCacheMutex.withLock {
            database.withTransaction {
                val sessions = chatHistoryDao.getSessions()
                val sessionIds = sessions.map { it.id }
                if (sessionIds.isEmpty()) return@withTransaction emptyList()
                val summariesBySessionId = chatHistoryDao.getMessageSummariesForSessions(sessionIds)
                    .groupBy { it.sessionId }
                sessions.map { session ->
                    val messages = chatHistoryDao.getMessagesForSummariesSafely(
                        summaries = summariesBySessionId[session.id].orEmpty(),
                        restoredMessageCache = restoredMessageCache,
                    )
                    session.toChatSession(messages = messages)
                }
            }
        }
    }

    suspend fun getUsageStatisticsSnapshot(): List<ChatUsageStatisticsSnapshot> {
        migrateLegacyChatStateIfNeeded()
        usageStore.backfillMessagesIfNeeded()
        return usageStore.list().map { it.toSnapshot() }
    }


    suspend fun upsertSessionSnapshot(
        session: ChatSession,
        sortOrder: Long,
    ) {
        migrateLegacyChatStateIfNeeded()
        database.withTransaction {
            chatHistoryDao.upsertSession(session.toSessionEntity(sortOrder))
        }
    }

    suspend fun upsertAgentSessionMetadata(
        chatSessionId: String,
        piSessionId: String,
        jsonlPath: String,
        runtime: String,
        migrationVersion: Int = 1,
    ) {
        if (chatSessionId.isBlank() || piSessionId.isBlank() || jsonlPath.isBlank()) return
        migrateLegacyChatStateIfNeeded()
        database.withTransaction {
            chatHistoryDao.upsertAgentSession(
                ChatAgentSessionEntity(
                    chatSessionId = chatSessionId,
                    piSessionId = piSessionId,
                    jsonlPath = jsonlPath,
                    runtime = runtime,
                    migrationVersion = migrationVersion,
                    updatedAtMillis = System.currentTimeMillis(),
                )
            )
        }
    }

    suspend fun getAgentSessionMetadata(chatSessionId: String): ChatAgentSessionEntity? {
        migrateLegacyChatStateIfNeeded()
        return database.withTransaction { chatHistoryDao.getAgentSession(chatSessionId) }
    }

    suspend fun getAgentMessageEntryIds(chatSessionId: String, messageId: String): List<String> {
        migrateLegacyChatStateIfNeeded()
        return database.withTransaction {
            chatHistoryDao.getAgentMessageRefs(chatSessionId, messageId).map { it.piEntryId }
        }
    }

    suspend fun upsertAgentMessageRefs(
        chatSessionId: String,
        aetherMessageIds: List<String>,
        piEntryIds: List<String>,
    ) {
        if (aetherMessageIds.isEmpty() || piEntryIds.isEmpty()) return
        migrateLegacyChatStateIfNeeded()
        val refs = aetherMessageIds.flatMap { messageId ->
            piEntryIds.mapIndexed { ordinal, entryId ->
                ChatAgentMessageRefEntity(
                    chatSessionId = chatSessionId,
                    aetherMessageId = messageId,
                    piEntryId = entryId,
                    ordinal = ordinal,
                )
            }
        }
        database.withTransaction { chatHistoryDao.upsertAgentMessageRefs(refs) }
    }

    /**
     * Store a turn's browsed pages and the citations resolved from its answer.
     *
     * Written once per turn, after the answer is complete. Citations for [messageId] are replaced
     * rather than appended so a redone answer does not leave its predecessor's sources behind.
     */
    suspend fun recordBrowserCitations(
        sessionId: String,
        messageId: String,
        pages: List<kira.ditto.data.chatdb.BrowserPageEntity>,
        passages: List<kira.ditto.data.chatdb.BrowserPassageEntity>,
        citations: List<kira.ditto.data.chatdb.BrowserCitationEntity>,
    ) {
        if (sessionId.isBlank() || sessionId == DraftSessionId) return
        migrateLegacyChatStateIfNeeded()
        database.withTransaction {
            if (pages.isNotEmpty()) browserCitationDao.upsertPages(pages)
            if (passages.isNotEmpty()) browserCitationDao.upsertPassages(passages)
            browserCitationDao.deleteCitationsForMessage(sessionId, messageId)
            if (citations.isNotEmpty()) browserCitationDao.upsertCitations(citations)
            // After the citations, never before: the versions this turn just referenced have to be
            // reachable when the cap decides what to collect.
            collectBrowserPageVersions(sessionId, pages.map { it.pageKey }.distinct())
        }
    }

    /**
     * Cap how many versions of a page we keep, without ever dropping one something points at.
     *
     * Two rules, and the second outranks the first. Keep the [MaxBrowserPageVersions] most recent
     * reads of a page, because older text has no reader. But keep any version a citation still
     * names, however old - collecting a referenced version is exactly the bug versioning exists to
     * prevent, and doing it on a timer instead of by accident does not make it a different bug.
     * This is git's reachability rule: an object with a ref pointing at it is not garbage.
     *
     * Runs once per settlement rather than on every write - the sweep is cheap in a batch and
     * wasteful per row.
     */
    private suspend fun collectBrowserPageVersions(sessionId: String, pageKeys: List<String>) {
        pageKeys.forEach { pageKey ->
            val versions = browserCitationDao.pageVersions(sessionId, pageKey)
            if (versions.size <= MaxBrowserPageVersions) return@forEach
            val reachable = browserCitationDao.citedContentHashes(sessionId, pageKey).toSet()
            versions.drop(MaxBrowserPageVersions)
                .filterNot { it.contentHash in reachable }
                .forEach { stale ->
                    browserCitationDao.deletePageVersion(sessionId, pageKey, stale.contentHash)
                }
        }
    }

    /**
     * Record what a finished piece of browser research concluded, and decide whether it may leave.
     *
     * The decision is made here rather than at the upload site because it depends on the whole
     * ledger, not on this one memo: the recurrence condition asks how often the memo's tags have
     * come up across every recent task. Writing the verdict into `uploadState` at record time also
     * means the uploader has nothing to judge - it drains a queue, which is the only shape that
     * stays correct when the send is irreversible.
     *
     * @return the row as stored, so the caller can see whether it was queued
     */
    suspend fun recordBrowserTaskMemo(
        sessionId: String,
        messageId: String,
        topicId: String,
        goal: String,
        memo: kira.ditto.browser.BrowserTaskMemo,
        nowMillis: Long = System.currentTimeMillis(),
    ): kira.ditto.data.chatdb.BrowserTaskEntity? {
        if (sessionId.isBlank() || sessionId == DraftSessionId || memo.summary.isBlank()) return null
        migrateLegacyChatStateIfNeeded()
        return database.withTransaction {
            val since = nowMillis - BrowserMemoClusterWindowMillis
            val occurrences = HashMap<String, Int>()
            browserCitationDao.recentTasks(since).forEach { task ->
                task.tags.split(',').forEach { tag ->
                    val trimmed = tag.trim()
                    if (trimmed.isNotBlank()) occurrences[trimmed] = (occurrences[trimmed] ?: 0) + 1
                }
            }
            // This memo counts toward its own cluster: two sightings means two, and the one in hand
            // is one of them.
            memo.tags.forEach { occurrences[it] = (occurrences[it] ?: 0) + 1 }

            val fingerprint = kira.ditto.browser.AgentIndex.contentHash(
                memo.summary + "|" + memo.tags.joinToString(","),
            )
            val alreadySent = browserCitationDao.uploadedTaskCount(fingerprint) > 0
            val state = when {
                alreadySent -> kira.ditto.browser.MemoryUploadState.Skipped
                kira.ditto.browser.shouldUploadMemo(memo, occurrences) ->
                    kira.ditto.browser.MemoryUploadState.Pending
                else -> kira.ditto.browser.MemoryUploadState.Skipped
            }
            val row = kira.ditto.data.chatdb.BrowserTaskEntity(
                id = "$sessionId:$messageId:$topicId",
                sessionId = sessionId,
                messageId = messageId,
                topicId = topicId,
                goal = goal.take(200),
                summary = memo.summary,
                tags = memo.tags.joinToString(","),
                openTodos = memo.openTodos,
                salience = memo.salience,
                source = memo.source.name,
                uploadState = state.name,
                contentSha256 = fingerprint,
                createdAtMillis = nowMillis,
            )
            browserCitationDao.upsertTasks(listOf(row))
            if (memo.origins.isNotEmpty()) {
                browserCitationDao.upsertOrigins(memo.origins.map { fact ->
                    val existing = browserCitationDao.origin(fact.origin)
                    val confirmations = (existing?.confirmCount ?: 0) + 1
                    kira.ditto.data.chatdb.BrowserOriginEntity(
                        sessionId = sessionId,
                        origin = fact.origin,
                        title = existing?.title.orEmpty(),
                        facts = mergeOriginFacts(existing?.facts.orEmpty(), fact.fact),
                        lastConfirmedAtMillis = nowMillis,
                        confirmCount = confirmations,
                        source = kira.ditto.browser.MemorySource.OriginDossier.name,
                        uploadState = if (kira.ditto.browser.shouldUploadOriginFact(confirmations)) {
                            kira.ditto.browser.MemoryUploadState.Pending.name
                        } else {
                            kira.ditto.browser.MemoryUploadState.Skipped.name
                        },
                        contentSha256 = kira.ditto.browser.AgentIndex.contentHash(fact.origin + "|" + fact.fact),
                    )
                })
            }
            row
        }
    }

    /** What we know about operating these hosts, for injection before the agent touches them. */
    suspend fun getBrowserOriginDossier(
        origins: List<String>,
    ): List<kira.ditto.data.chatdb.BrowserOriginEntity> {
        if (origins.isEmpty()) return emptyList()
        migrateLegacyChatStateIfNeeded()
        return database.withTransaction { browserCitationDao.originsIn(origins.distinct()) }
    }

    suspend fun getBrowserTasksAwaitingUpload(): List<kira.ditto.data.chatdb.BrowserTaskEntity> {
        migrateLegacyChatStateIfNeeded()
        return database.withTransaction {
            browserCitationDao.tasksAwaitingUpload(kira.ditto.browser.MemoryUploadState.Pending.name)
        }
    }

    /**
     * Record what happened to an upload attempt.
     *
     * A failure stays `Pending` so the next settlement retries it: a row wrongly marked uploaded is
     * a memory silently lost, while a retry costs one request. [giveUp] is the escape from that -
     * after enough failures the row becomes `Skipped` so it stops consuming a request every turn.
     */
    suspend fun markBrowserTaskUploaded(id: String, uploaded: Boolean, giveUp: Boolean = false) {
        migrateLegacyChatStateIfNeeded()
        val state = when {
            uploaded -> kira.ditto.browser.MemoryUploadState.Uploaded
            giveUp -> kira.ditto.browser.MemoryUploadState.Skipped
            else -> kira.ditto.browser.MemoryUploadState.Pending
        }
        database.withTransaction { browserCitationDao.setTaskUploadState(id, state.name) }
    }

    /** Keep a few distinct facts per host rather than the last one written. */
    private fun mergeOriginFacts(existing: String, addition: String): String {
        val facts = (existing.split(" | ") + addition)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        return facts.takeLast(BrowserOriginFactLimit).joinToString(" | ")
    }

    suspend fun getBrowserCitationsForSession(
        sessionId: String,
    ): List<kira.ditto.data.chatdb.BrowserCitationEntity> {
        if (sessionId.isBlank() || sessionId == DraftSessionId) return emptyList()
        migrateLegacyChatStateIfNeeded()
        return database.withTransaction { browserCitationDao.citationsForSession(sessionId) }
    }

    /** Primary-key lookup from an EverMe `aether_hash` to the Chat rows it names. */
    suspend fun readOriginalMessagesByHash(hash: String): List<ChatMessage> {
        val safe = hash.trim()
        if (safe.isEmpty()) return emptyList()
        migrateLegacyChatStateIfNeeded()
        return database.withTransaction {
            val pointer = chatHistoryDao.getMemoryOriginalPointer(safe) ?: return@withTransaction emptyList()
            val ids = kira.ditto.data.chatdb.decodeMessageIdsJson(pointer.messageIdsJson)
            if (ids.isEmpty()) return@withTransaction emptyList()
            chatHistoryDao.getMessagesByIds(pointer.sessionId, ids).mapIndexed { index, entity ->
                ChatMessageEntityMapper.toChatMessage(entity, index)
            }
        }
    }

    suspend fun upsertMessageSnapshot(
        sessionId: String,
        message: ChatMessage,
        position: Int,
    ) {
        require(position >= 0) { "position must be non-negative" }
        migrateLegacyChatStateIfNeeded()
        invalidateRestoredMessage(sessionId = sessionId, messageId = message.id)
        database.withTransaction {
            val messageEntity = ChatMessageEntityMapper.toEntity(
                sessionId = sessionId,
                position = position,
                message = message,
            )
            chatHistoryDao.upsertMessagesWithPayloads(listOf(messageEntity))
            replaceWorkspaceFileRefsForMessagesInTransaction(
                sessionId = sessionId,
                messages = listOf(message),
            )
        }
    }

    suspend fun deleteSessionById(sessionId: String) {
        migrateLegacyChatStateIfNeeded()
        invalidateRestoredSession(sessionId)
        database.withTransaction {
            chatHistoryDao.deleteSession(sessionId)
            val meta = chatHistoryDao.getMeta()
            if (meta?.currentSessionId == sessionId) {
                chatHistoryDao.upsertMeta(meta.copy(currentSessionId = null))
            }
        }
    }

    suspend fun getUnreferencedWorkspaceFilePathsForDeletedSession(sessionId: String): List<String> {
        migrateLegacyChatStateIfNeeded()
        return database.withTransaction {
            if (chatHistoryDao.getMeta()?.workspaceFileRefsComplete != true) {
                return@withTransaction emptyList()
            }
            val candidatePaths = chatHistoryDao.getWorkspaceFilePathsForSession(sessionId).normalizedWorkspaceFilePaths()
            if (candidatePaths.isEmpty()) {
                emptyList()
            } else {
                val referencedPaths = getWorkspaceFileRefsForPathsChunked(candidatePaths)
                    .asSequence()
                    .filterNot { ref -> ref.sessionId == sessionId }
                    .map { ref -> ref.path }
                    .toSet()
                candidatePaths.filterNot(referencedPaths::contains)
            }
        }
    }

    suspend fun getUnreferencedWorkspaceFilePathsForDeletedMessages(
        sessionId: String,
        messageIds: List<String>,
    ): List<String> {
        migrateLegacyChatStateIfNeeded()
        val safeMessageIds = messageIds.map(String::trim).filter(String::isNotEmpty).distinct()
        if (safeMessageIds.isEmpty()) return emptyList()
        val safeMessageIdSet = safeMessageIds.toSet()
        return database.withTransaction {
            if (chatHistoryDao.getMeta()?.workspaceFileRefsComplete != true) {
                return@withTransaction emptyList()
            }
            val candidatePaths = getWorkspaceFilePathsForMessagesChunked(
                sessionId = sessionId,
                messageIds = safeMessageIds,
            ).normalizedWorkspaceFilePaths()
            if (candidatePaths.isEmpty()) {
                emptyList()
            } else {
                val referencedPaths = getWorkspaceFileRefsForPathsChunked(candidatePaths)
                    .asSequence()
                    .filterNot { ref -> ref.sessionId == sessionId && ref.messageId in safeMessageIdSet }
                    .map { ref -> ref.path }
                    .toSet()
                candidatePaths.filterNot(referencedPaths::contains)
            }
        }
    }

    suspend fun replaceMessagesFromPosition(
        sessionId: String,
        fromPosition: Int,
        messages: List<ChatMessage>,
    ) {
        require(fromPosition >= 0) { "fromPosition must be non-negative" }
        migrateLegacyChatStateIfNeeded()
        invalidateRestoredMessagesFromPosition(sessionId = sessionId, fromPosition = fromPosition)
        database.withTransaction {
            replaceMessagesFromPositionInTransaction(sessionId, fromPosition, messages)
        }
    }

    private suspend fun replaceMessagesFromPositionInTransaction(
        sessionId: String,
        fromPosition: Int,
        messages: List<ChatMessage>,
    ) {
        chatHistoryDao.deleteWorkspaceFileRefsFromPosition(sessionId, fromPosition)
        chatHistoryDao.deleteMessagesFromPosition(sessionId, fromPosition)
        messages.forEach { message ->
            chatHistoryDao.deleteWorkspaceFileRefsForMessage(sessionId, message.id)
        }
        chatHistoryDao.upsertMessagesChunked(
            sessionId = sessionId,
            messages = messages,
            startPosition = fromPosition,
        )
    }

    override suspend fun upsertAssistantResponseCheckpoints(
        checkpoints: List<AssistantResponseCheckpoint>,
    ) {
        if (checkpoints.isEmpty()) return
        val upserts = checkpoints
            .associateBy { it.target }
            .values
            .map { checkpoint ->
                AssistantResponseCheckpointUpsert(
                    sessionId = checkpoint.target.sessionId,
                    responseGroupId = checkpoint.target.responseGroupId,
                    fromPosition = checkpoint.fromPosition,
                    messages = checkpoint.messages,
                )
            }

        upserts.forEach { upsert ->
            invalidateRestoredMessagesFromPosition(
                sessionId = upsert.sessionId,
                fromPosition = upsert.fromPosition,
            )
        }
        database.withTransaction {
            upserts.forEach { upsert ->
                if (chatHistoryDao.getSession(upsert.sessionId) == null) return@forEach
                val previousMessageCount = chatHistoryDao.getMessageCountForResponseGroup(
                    sessionId = upsert.sessionId,
                    responseGroupId = upsert.responseGroupId,
                    fromPosition = upsert.fromPosition,
                )
                val previousSessionMessageCount = chatHistoryDao.getMessageCountForSession(upsert.sessionId)
                val canTailUpsert = previousMessageCount > 0 &&
                    upsert.messages.size >= previousMessageCount
                if (canTailUpsert) {
                    val fromIndex = (previousMessageCount - 1).coerceAtLeast(0)
                    val tail = upsert.messages.subList(fromIndex, upsert.messages.size)
                    chatHistoryDao.upsertMessagesWithPayloads(
                        tail.mapIndexed { index, message ->
                            ChatMessageEntityMapper.toEntity(
                                sessionId = upsert.sessionId,
                                position = upsert.fromPosition + fromIndex + index,
                                message = message,
                            )
                        }
                    )
                    replaceWorkspaceFileRefsForMessagesInTransaction(
                        sessionId = upsert.sessionId,
                        messages = tail,
                    )
                    return@forEach
                }
                val parkedMessageIds = chatHistoryDao.getMessageIdsToParkOutsideResponseGroup(
                    sessionId = upsert.sessionId,
                    responseGroupId = upsert.responseGroupId,
                    fromPosition = upsert.fromPosition,
                    toPosition = previousSessionMessageCount,
                )
                chatHistoryDao.parkMessagesFromPositionOutsideResponseGroup(
                    sessionId = upsert.sessionId,
                    responseGroupId = upsert.responseGroupId,
                    fromPosition = upsert.fromPosition,
                    toPosition = previousSessionMessageCount,
                )
                chatHistoryDao.deleteWorkspaceFileRefsForResponseGroup(
                    sessionId = upsert.sessionId,
                    responseGroupId = upsert.responseGroupId,
                    fromPosition = upsert.fromPosition,
                )
                chatHistoryDao.deleteMessagesForResponseGroup(
                    sessionId = upsert.sessionId,
                    responseGroupId = upsert.responseGroupId,
                    fromPosition = upsert.fromPosition,
                )
                chatHistoryDao.upsertMessagesWithPayloads(
                    upsert.messages.mapIndexed { index, message ->
                        ChatMessageEntityMapper.toEntity(
                            sessionId = upsert.sessionId,
                            position = upsert.fromPosition + index,
                            message = message,
                        )
                    }
                )
                if (parkedMessageIds.isNotEmpty()) {
                    chatHistoryDao.restoreParkedMessagesOutsideResponseGroup(
                        sessionId = upsert.sessionId,
                        responseGroupId = upsert.responseGroupId,
                        fromPosition = upsert.fromPosition,
                        toPosition = previousSessionMessageCount,
                        checkpointEndPosition = upsert.fromPosition + upsert.messages.size,
                        parkedMessageIds = parkedMessageIds,
                        positionDelta = upsert.messages.size - previousMessageCount,
                    )
                }
                replaceWorkspaceFileRefsForMessagesInTransaction(
                    sessionId = upsert.sessionId,
                    messages = upsert.messages,
                )
            }
        }
    }

    /**
     * Reconciles the stored tail of a session against [messages] by fingerprint, writing
     * only the rows that actually changed. The previous behaviour — delete every message
     * of the session and reinsert all of them — made every turn cost O(session size) in
     * write amplification, which is what made long conversations slow to a crawl.
     *
     * Rows below [startPosition] are never touched: they are history the caller does not
     * have in memory because it only holds a window.
     */
    private suspend fun ChatHistoryDao.syncMessagesFromPosition(
        sessionId: String,
        messages: List<ChatMessage>,
        startPosition: Int,
    ) {
        val storedDigests = getMessageDigestsFromPosition(sessionId, startPosition)
            .associateBy { it.id }
        val desiredIds = messages.mapTo(mutableSetOf()) { it.id }
        val removedIds = storedDigests.keys.filterNot(desiredIds::contains)
        if (removedIds.isNotEmpty()) {
            removedIds.chunked(WorkspaceFileRefQueryChunkSize).forEach { chunk ->
                deleteWorkspaceFileRefsForMessages(sessionId, chunk)
                deleteMessagesByIds(sessionId, chunk)
            }
        }

        val changed = ArrayList<ChatMessage>(messages.size)
        val changedPositions = ArrayList<Int>(messages.size)
        messages.forEachIndexed { index, message ->
            val position = startPosition + index
            val stored = storedDigests[message.id]
            if (stored == null || stored.position != position) {
                changed += message
                changedPositions += position
                return@forEachIndexed
            }
            // Same slot: only rewrite when the serialized content differs.
            val (light, payload) = ChatMessageEntityMapper
                .toEntity(sessionId = sessionId, position = position, message = message)
                .splitForStorage()
            if (light.contentHash != stored.contentHash || light.payloadHash != stored.payloadHash) {
                changed += message
                changedPositions += position
            }
        }
        if (changed.isEmpty()) return
        // Workspace refs are keyed by path, so a rewritten message must drop its old rows
        // rather than merge into them.
        changed.map { it.id }.chunked(WorkspaceFileRefQueryChunkSize).forEach { chunk ->
            deleteWorkspaceFileRefsForMessages(sessionId, chunk)
        }
        upsertMessagesChunked(
            sessionId = sessionId,
            messages = changed,
            positionOf = { index -> changedPositions[index] },
        )
    }

    private suspend fun ChatHistoryDao.upsertMessagesChunked(
        sessionId: String,
        messages: List<ChatMessage>,
        startPosition: Int = 0,
        positionOf: (Int) -> Int = { index -> startPosition + index },
    ) {
        if (messages.isEmpty()) return

        val messageEntities = ArrayList<ChatMessageEntity>()
        val workspaceFileRefs = ArrayList<ChatWorkspaceFileRefEntity>()
        var estimatedJsonBytes = 0L

        suspend fun flushBatch() {
            if (messageEntities.isEmpty()) return
            upsertMessagesWithPayloads(messageEntities)
            if (workspaceFileRefs.isNotEmpty()) {
                upsertWorkspaceFileRefs(workspaceFileRefs)
            }
            messageEntities.clear()
            workspaceFileRefs.clear()
            estimatedJsonBytes = 0L
        }

        messages.forEachIndexed { index, message ->
            val messageJson = message.toJson().toString()
            val nextJsonBytes = messageJson.length.toLong() * 2L
            if (
                messageEntities.isNotEmpty() &&
                (messageEntities.size >= MaxMessagesPerJsonBatch ||
                    shouldStartNewMessageJsonBatch(estimatedJsonBytes, nextJsonBytes))
            ) {
                flushBatch()
            }
            messageEntities += ChatMessageEntityMapper.toEntity(
                sessionId = sessionId,
                position = positionOf(index),
                message = message,
                messageJson = messageJson,
            )
            workspaceFileRefs.addAll(message.toWorkspaceFileRefs(sessionId))
            estimatedJsonBytes += nextJsonBytes
        }
        flushBatch()
    }

    private suspend fun replaceChatStateBatched(
        sessions: List<ChatSession>,
        currentSessionId: String,
        migrationComplete: Boolean,
        writeIntent: PersistedChatWriteIntent = PersistedChatWriteIntent.SyncSnapshot,
    ) {
        clearRestoredMessageCache()
        database.withTransaction {
            val safeCurrentSessionId = currentSessionId
                .takeIf { id -> id == DraftSessionId || sessions.any { it.id == id } }
                ?: sessions.firstOrNull()?.id
                ?: DraftSessionId
            if (sessions.isEmpty()) {
                if (writeIntent == PersistedChatWriteIntent.SyncSnapshot && chatHistoryDao.getSessions().isNotEmpty()) {
                    return@withTransaction
                }
                chatHistoryDao.upsertMeta(
                    ChatStateMetaEntity(
                        currentSessionId = null,
                        roomMigrationComplete = migrationComplete,
                        workspaceFileRefsComplete = true,
                    )
                )
                chatHistoryDao.deleteAllWorkspaceFileRefs()
                chatHistoryDao.deleteAllMessages()
                chatHistoryDao.deleteAllSessions()
                return@withTransaction
            }

            val sessionEntities = sessions.mapIndexed { index, session -> session.toSessionEntity(index.toLong()) }
            val sessionIds = sessionEntities.map { it.id }
            chatHistoryDao.upsertSessions(sessionEntities)
            chatHistoryDao.deleteWorkspaceFileRefsExceptSessions(sessionIds)
            chatHistoryDao.deleteSessionsExcept(sessionIds)
            chatHistoryDao.deleteMessagesExceptSessions(sessionIds)
            val existingWorkspaceFileRefsComplete = chatHistoryDao.getMeta()?.workspaceFileRefsComplete ?: true
            val existingMessageCounts = chatHistoryDao.getMessageStatsForSessions(sessionIds)
                .associate { stats -> stats.sessionId to stats.messageCount }
            chatHistoryDao.upsertMeta(
                ChatStateMetaEntity(
                    currentSessionId = safeCurrentSessionId.toStoredCurrentSessionId(),
                    roomMigrationComplete = migrationComplete,
                    workspaceFileRefsComplete = existingWorkspaceFileRefsComplete,
                )
            )

            sessions.forEach { session ->
                val existingMessageCount = existingMessageCounts[session.id] ?: 0
                val syncedMessages = syncActiveBranches(session.messages)
                val isMetadataOnlySnapshot = writeIntent != PersistedChatWriteIntent.ReplaceFromImport &&
                    session.id != safeCurrentSessionId &&
                    syncedMessages.isEmpty() &&
                    existingMessageCount > 0
                if (isMetadataOnlySnapshot) return@forEach
                if (writeIntent == PersistedChatWriteIntent.ReplaceFromImport) {
                    chatHistoryDao.deleteWorkspaceFileRefsForSession(session.id)
                    chatHistoryDao.deleteMessagesForSession(session.id)
                    chatHistoryDao.upsertMessagesChunked(
                        sessionId = session.id,
                        messages = syncedMessages,
                    )
                    return@forEach
                }
                chatHistoryDao.syncMessagesFromPosition(
                    sessionId = session.id,
                    messages = syncedMessages,
                    // A windowed session only carries its tail; everything before the
                    // window stays on disk untouched.
                    startPosition = session.loadedFromPosition,
                )
            }
        }
    }

    private suspend fun replaceWorkspaceFileRefsForMessagesInTransaction(
        sessionId: String,
        messages: List<ChatMessage>,
    ) {
        if (messages.isEmpty()) return
        messages.forEach { message ->
            chatHistoryDao.deleteWorkspaceFileRefsForMessage(sessionId, message.id)
        }
        val refs = messages.toWorkspaceFileRefs(sessionId)
        if (refs.isNotEmpty()) {
            chatHistoryDao.upsertWorkspaceFileRefs(refs)
        }
    }

    private suspend fun getWorkspaceFilePathsForMessagesChunked(
        sessionId: String,
        messageIds: List<String>,
    ): List<String> = messageIds.chunked(WorkspaceFileRefQueryChunkSize).flatMap { chunk ->
        chatHistoryDao.getWorkspaceFilePathsForMessages(sessionId = sessionId, messageIds = chunk)
    }

    private suspend fun getWorkspaceFileRefsForPathsChunked(paths: List<String>): List<ChatWorkspaceFileRefEntity> =
        paths.chunked(WorkspaceFileRefQueryChunkSize).flatMap { chunk ->
            chatHistoryDao.getWorkspaceFileRefsForPaths(chunk)
        }

    private fun Collection<String>.normalizedWorkspaceFilePaths(): List<String> =
        map(String::trim).filter(String::isNotEmpty).distinct().sorted()

    // TODO(Room v2): remove legacy DataStore chat import.
    private suspend fun migrateLegacyChatStateIfNeeded() = migrationMutex.withLock {
        val preferences = context.chatDataStore.data.first()
        val legacySessionsJson = preferences[SESSIONS_JSON].orEmpty()
        val legacyMigrationComplete = preferences[ROOM_MIGRATION_COMPLETE] == true
        val legacyCurrentSessionId = preferences[CURRENT_SESSION_ID]
        val roomMeta = chatHistoryDao.getMeta()

        if (roomMeta?.roomMigrationComplete == true) {
            rebuildWorkspaceFileRefsIfNeeded()
            if (legacySessionsJson.isNotBlank() || preferences[CURRENT_SESSION_ID] != null) {
                clearLegacyChatState()
            }
            return@withLock
        }

        if (legacyMigrationComplete && legacySessionsJson.isBlank()) {
            markRoomMigrationCompletePreservingExistingState()
            if (preferences[CURRENT_SESSION_ID] != null) {
                clearLegacyChatState()
            }
            return@withLock
        }

        if (legacySessionsJson.isBlank()) {
            markRoomMigrationCompletePreservingExistingState()
            clearLegacyChatState()
            return@withLock
        }

        val existingSessions = chatHistoryDao.getSessions()
        if (existingSessions.isNotEmpty()) {
            markRoomMigrationCompletePreservingExistingState()
            clearLegacyChatState()
            return@withLock
        }

        val legacyParseResult = parseChatSessionsForMigration(legacySessionsJson)
        val legacySessions = legacyParseResult.sessions
        if (legacyParseResult.recoveredFromCorruption) {
            // TODO(Room v2): remove with legacy DataStore chat import.
            replaceChatStateBatched(
                sessions = legacySessions,
                currentSessionId = resolveLegacyCurrentSessionIdForMigration(
                    legacyCurrentSessionId = legacyCurrentSessionId,
                    legacySessions = legacySessions,
                ),
                migrationComplete = true,
            )
            clearLegacyChatState()
            return@withLock
        }
        if (legacySessions.isNotEmpty()) {
            replaceChatStateBatched(
                sessions = legacySessions,
                currentSessionId = resolveLegacyCurrentSessionIdForMigration(
                    legacyCurrentSessionId = legacyCurrentSessionId,
                    legacySessions = legacySessions,
                ),
                migrationComplete = true,
            )
            clearLegacyChatState()
            return@withLock
        }

        markRoomMigrationCompletePreservingExistingState()
        clearLegacyChatState()
    }

    private suspend fun markRoomMigrationCompletePreservingExistingState() {
        val existingSessions = chatHistoryDao.getSessions()
        val existingMeta = chatHistoryDao.getMeta()
        val existingSessionIds = existingSessions.mapTo(mutableSetOf()) { it.id }
        val currentSessionId = existingMeta
            ?.currentSessionId
            ?.takeIf { id -> id in existingSessionIds }

        database.withTransaction {
            chatHistoryDao.upsertMeta(
                ChatStateMetaEntity(
                    currentSessionId = currentSessionId,
                    roomMigrationComplete = true,
                    workspaceFileRefsComplete = existingMeta?.workspaceFileRefsComplete ?: existingSessions.isEmpty(),
                )
            )
        }
        rebuildWorkspaceFileRefsIfNeeded()
    }

    private suspend fun rebuildWorkspaceFileRefsIfNeeded() {
        database.withTransaction {
            val existingMeta = chatHistoryDao.getMeta() ?: return@withTransaction
            if (existingMeta.workspaceFileRefsComplete) return@withTransaction
            val refs = chatHistoryDao.getSessions()
                .map { session -> session.id }
                .chunked(WorkspaceFileRefQueryChunkSize)
                .flatMap { sessionIds -> chatHistoryDao.getMessageSummariesForSessions(sessionIds) }
                .flatMap { summary -> summary.toWorkspaceFileRefs(chatHistoryDao) }
            chatHistoryDao.deleteAllWorkspaceFileRefs()
            if (refs.isNotEmpty()) {
                chatHistoryDao.upsertWorkspaceFileRefs(refs)
            }
            chatHistoryDao.upsertMeta(existingMeta.copy(workspaceFileRefsComplete = true))
        }
    }

    private suspend fun ChatMessageSummaryEntity.toWorkspaceFileRefs(
        dao: ChatHistoryDao,
    ): List<ChatWorkspaceFileRefEntity> {
        val message = dao.loadMessageEntityInChunks(this)
            ?.let { entity -> ChatMessageEntityMapper.toChatMessage(entity, entity.position) }
            ?: ChatMessageEntityMapper.summaryToChatMessage(this)
        return message.collectWorkspaceFilePathsForIndex().map { path ->
            ChatWorkspaceFileRefEntity(
                sessionId = sessionId,
                messageId = id,
                path = path,
            )
        }.distinctBy { ref -> Triple(ref.sessionId, ref.messageId, ref.path) }
    }

    private suspend fun clearLegacyChatState() {
        context.chatDataStore.edit { data ->
            data.remove(SESSIONS_JSON)
            data.remove(CURRENT_SESSION_ID)
            data[ROOM_MIGRATION_COMPLETE] = true
        }
    }

    private suspend fun clearRestoredMessageCache() {
        restoredMessageCacheMutex.withLock {
            restoredMessageCache.clear()
        }
    }

    private suspend fun invalidateRestoredMessage(
        sessionId: String,
        messageId: String,
    ) {
        removeRestoredMessagesFromCache { cacheKey ->
            cacheKey.sessionId == sessionId && cacheKey.id == messageId
        }
    }

    private suspend fun invalidateRestoredSession(sessionId: String) {
        removeRestoredMessagesFromCache { cacheKey ->
            cacheKey.sessionId == sessionId
        }
    }

    private suspend fun invalidateRestoredMessagesFromPosition(
        sessionId: String,
        fromPosition: Int,
    ) {
        removeRestoredMessagesFromCache { cacheKey ->
            cacheKey.sessionId == sessionId && cacheKey.position >= fromPosition
        }
    }

    private suspend fun removeRestoredMessagesFromCache(
        shouldRemove: (ChatMessageCacheKey) -> Boolean,
    ) {
        restoredMessageCacheMutex.withLock {
            val iterator = restoredMessageCache.keys.iterator()
            while (iterator.hasNext()) {
                if (shouldRemove(iterator.next())) {
                    iterator.remove()
                }
            }
        }
    }

    private inner class RoomLlmUsageStore : LlmUsageStore {
        override suspend fun insert(records: List<LlmUsageRecord>) {
            if (records.isEmpty()) return
            database.withTransaction {
                llmUsageDao.insertRecords(records.map { it.toEntity() })
            }
        }

        override suspend fun list(): List<LlmUsageRecord> =
            database.withTransaction {
                llmUsageDao.getAllRecords().map { it.toRecord() }
            }

        override suspend fun listSince(sinceMillis: Long): List<LlmUsageRecord> =
            database.withTransaction {
                llmUsageDao.getRecordsSince(sinceMillis).map { it.toRecord() }
            }

        override suspend fun totals(): LlmUsageTotals = database.withTransaction {
            val totals = llmUsageDao.getUsageTotals() ?: return@withTransaction LlmUsageTotals()
            LlmUsageTotals(
                recordCount = totals.recordCount,
                sessionCount = totals.sessionCount,
                turnCount = totals.turnCount,
                totalTokens = totals.totalTokens,
                inputTokens = totals.inputTokens,
                outputTokens = totals.outputTokens,
                reasoningTokens = totals.reasoningTokens,
                cachedInputTokens = totals.cachedInputTokens,
                largestTurnTokens = totals.largestTurnTokens?.takeIf { it > 0L },
                tokensBySource = llmUsageDao.getTokensBySource().map {
                    LlmUsageGroupTotal(key = it.groupKey, tokens = it.tokens)
                },
                tokensByModel = mergeTokensByDisplayModelId(
                    items = llmUsageDao.getTokensByModel(limit = UsageModelBreakdownLimit * 4).map {
                        it.groupKey to it.tokens
                    },
                    limit = UsageModelBreakdownLimit,
                ).map { (key, tokens) -> LlmUsageGroupTotal(key = key, tokens = tokens) },
            )
        }

        override suspend fun cursor(wireKey: String): Int? =
            database.withTransaction {
                llmUsageDao.getConsumedCount(wireKey)
            }

        override suspend fun commitHarvest(
            wireKey: String,
            consumedCount: Int,
            records: List<LlmUsageRecord>,
        ) {
            database.withTransaction {
                llmUsageDao.commitHarvest(
                    records = records.map { it.toEntity() },
                    wireKey = wireKey,
                    consumedCount = consumedCount,
                )
            }
        }

        override suspend fun deleteEstimatedRecords(sessionId: String, source: String): Int {
            if (sessionId.isBlank() || sessionId == DraftSessionId) return 0
            return database.withTransaction { llmUsageDao.deleteEstimatedRecords(sessionId, source) }
        }

        override suspend fun reclassifyWireRecords(idPrefix: String, source: String) {
            if (idPrefix.isBlank() || source.isBlank()) return
            database.useWriterConnection { connection ->
                connection.usePrepared(
                    "UPDATE llm_usage_records SET source = ? WHERE instr(id, ?) = 1",
                ) { statement ->
                    statement.bindText(1, source)
                    statement.bindText(2, idPrefix)
                    statement.step()
                }
            }
        }

        override suspend fun backfillMessagesIfNeeded(): Int {
            migrateLegacyChatStateIfNeeded()
            return database.withTransaction {
                if ((llmUsageDao.getConsumedCount(MessageUsageBackfillCursorKey) ?: 0) > 0) {
                    return@withTransaction 0
                }
                val records = chatHistoryDao.getUsageStatisticsMessageSummaries().mapNotNull { summary ->
                    val entity = try {
                        chatHistoryDao.loadMessageEntityInChunks(summary)
                    } catch (throwable: Exception) {
                        if (throwable is CancellationException) throw throwable
                        null
                    } ?: return@mapNotNull null
                    val statistics = entity.messageJson.parseUsageStatisticsOrNull()
                        ?: return@mapNotNull null
                    LlmUsageRecord(
                        id = "turn:${summary.sessionId}:${summary.id}",
                        sessionId = summary.sessionId,
                        source = LlmUsageSources.Turn,
                        providerId = "",
                        modelId = "",
                        inputTokens = statistics.inputTokens,
                        outputTokens = statistics.outputTokens,
                        totalTokens = statistics.totalTokens,
                        reasoningTokens = statistics.reasoningTokens,
                        cachedInputTokens = statistics.cachedInputTokens,
                        requestCount = statistics.requestCount.coerceAtLeast(1),
                        usageSource = statistics.tokenUsageSource.ifBlank { "unavailable" },
                        startedAtMillis = statistics.startedAtMillis,
                        completedAtMillis = statistics.completedAtMillis,
                        firstTokenAtMillis = statistics.firstTokenAtMillis,
                    )
                }
                llmUsageDao.commitMessageBackfill(
                    records = records.map { it.toEntity() },
                    cursorKey = MessageUsageBackfillCursorKey,
                )
                records.size
            }
        }
    }

    private companion object {
        val SESSIONS_JSON = stringPreferencesKey("sessions_json")
        val CURRENT_SESSION_ID = stringPreferencesKey("current_session_id")
        val ROOM_MIGRATION_COMPLETE = booleanPreferencesKey("room_migration_complete")
        val migrationMutex = Mutex()
    }
}

private fun LlmUsageRecord.toSnapshot(): ChatUsageStatisticsSnapshot =
    ChatUsageStatisticsSnapshot(
        sessionId = sessionId.orEmpty(),
        source = source,
        modelId = modelId,
        providerId = providerId,
        statistics = ChatUsageStatistics(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            totalTokens = totalTokens,
            reasoningTokens = reasoningTokens,
            cachedInputTokens = cachedInputTokens,
            requestCount = requestCount,
            tokenUsageSource = usageSource,
            startedAtMillis = startedAtMillis,
            firstTokenAtMillis = firstTokenAtMillis,
            completedAtMillis = completedAtMillis,
        ),
    )

private fun LlmUsageRecord.toEntity(): LlmUsageRecordEntity =
    LlmUsageRecordEntity(
        id = id,
        sessionId = sessionId,
        source = source,
        providerId = providerId,
        modelId = modelId,
        agentId = agentId,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        totalTokens = totalTokens,
        reasoningTokens = reasoningTokens,
        cachedInputTokens = cachedInputTokens,
        requestCount = requestCount,
        usageSource = usageSource,
        startedAtMillis = startedAtMillis,
        completedAtMillis = completedAtMillis,
        firstTokenAtMillis = firstTokenAtMillis,
    )

private fun LlmUsageRecordEntity.toRecord(): LlmUsageRecord =
    LlmUsageRecord(
        id = id,
        sessionId = sessionId,
        source = source,
        providerId = providerId,
        modelId = modelId,
        agentId = agentId,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        totalTokens = totalTokens,
        reasoningTokens = reasoningTokens,
        cachedInputTokens = cachedInputTokens,
        requestCount = requestCount,
        usageSource = usageSource,
        startedAtMillis = startedAtMillis,
        completedAtMillis = completedAtMillis,
        firstTokenAtMillis = firstTokenAtMillis,
    )

internal fun resolveLegacyCurrentSessionIdForMigration(
    legacyCurrentSessionId: String?,
    legacySessions: List<ChatSession>,
): String {
    if (legacySessions.isEmpty()) return legacyCurrentSessionId ?: DraftSessionId
    val firstSessionId = legacySessions.first().id
    return legacyCurrentSessionId
        ?.takeIf { id -> id == DraftSessionId || legacySessions.any { it.id == id } }
        ?: firstSessionId.takeIf { it.isNotBlank() }
        ?: DraftSessionId
}

private fun String?.toStoredCurrentSessionId(): String? = this
    ?.takeUnless { it.isBlank() || it == DraftSessionId }

private suspend fun ChatHistoryDao.getMessagesForSummariesSafely(
    summaries: List<ChatMessageSummaryEntity>,
    restoredMessageCache: MutableMap<ChatMessageCacheKey, LoadedChatMessage>,
): List<LoadedChatMessage> = summaries.map { summary ->
    val cacheKey = summary.cacheKey
    restoredMessageCache[cacheKey] ?: run {
        val message = try {
            loadMessageEntityInChunks(summary)?.let { entity ->
                ChatMessageEntityMapper.toChatMessage(entity, entity.position)
            }
        } catch (throwable: Exception) {
            if (throwable is CancellationException) throw throwable
            null
        } ?: ChatMessageEntityMapper.summaryToChatMessage(summary)

        LoadedChatMessage(
            sessionId = summary.sessionId,
            position = summary.position,
            message = message,
        ).also { loadedMessage -> restoredMessageCache[cacheKey] = loadedMessage }
    }
}

private suspend fun ChatHistoryDao.loadMessageEntityInChunks(
    summary: ChatMessageSummaryEntity,
): ChatMessageEntity? = readFullMessageJson(
    sessionId = summary.sessionId,
    messageId = summary.id,
    hasPayload = summary.payloadHash.isNotEmpty(),
)?.let { messageJson -> summary.toMessageEntity(messageJson) }

private fun ChatMessageSummaryEntity.toMessageEntity(messageJson: String): ChatMessageEntity = ChatMessageEntity(
    sessionId = sessionId,
    id = id,
    position = position,
    messageJson = messageJson,
    author = author,
    text = text,
    createdAtMillis = createdAtMillis,
    responseGroupId = responseGroupId,
    displayKind = displayKind,
    messageSchemaVersion = messageSchemaVersion,
    isIncomplete = isIncomplete,
)

private val ChatMessageSummaryEntity.cacheKey: ChatMessageCacheKey
    get() = ChatMessageCacheKey(
        sessionId = sessionId,
        id = id,
        position = position,
        author = author,
        text = text,
        createdAtMillis = createdAtMillis,
        responseGroupId = responseGroupId,
        displayKind = displayKind,
        messageSchemaVersion = messageSchemaVersion,
        messageJsonLength = messageJsonLength,
        isIncomplete = isIncomplete,
        payloadHash = payloadHash,
    )

private data class ChatMessageCacheKey(
    val sessionId: String,
    val id: String,
    val position: Int,
    val author: String,
    val text: String,
    val createdAtMillis: Long?,
    val responseGroupId: String?,
    val displayKind: String?,
    val messageSchemaVersion: Int,
    val messageJsonLength: Int?,
    val isIncomplete: Boolean,
    val payloadHash: String,
)

private data class LoadedChatMessage(
    val sessionId: String,
    val position: Int,
    val message: ChatMessage,
)

private data class SessionListState(
    val rows: List<ChatSessionEntity>,
    val currentSessionId: String,
)

private fun ChatSession.toSessionEntity(sortOrder: Long): ChatSessionEntity = ChatSessionEntity(
    id = id,
    title = title,
    preview = preview,
    hasCustomTitle = hasCustomTitle,
    agentModeEnabled = agentModeEnabled,
    chromeEnabled = chromeEnabled,
    selectedModelKey = selectedModelKey,
    sortOrder = sortOrder,
    workspaceId = workspaceId,
)

private fun List<ChatMessage>.toWorkspaceFileRefs(sessionId: String): List<ChatWorkspaceFileRefEntity> =
    flatMap { message -> message.toWorkspaceFileRefs(sessionId) }
        .distinctBy { ref -> Triple(ref.sessionId, ref.messageId, ref.path) }

private fun ChatMessage.toWorkspaceFileRefs(sessionId: String): List<ChatWorkspaceFileRefEntity> =
    collectWorkspaceFilePathsForIndex().map { path ->
        ChatWorkspaceFileRefEntity(
            sessionId = sessionId,
            messageId = id,
            path = path,
        )
    }

private fun ChatMessage.collectWorkspaceFilePathsForIndex(): List<String> =
    (attachments
        .map { attachment -> attachment.workspacePath.trim() }
        .filter(String::isNotEmpty) +
        branchGroup
            ?.branches
            .orEmpty()
            .flatMap { branch -> branch.flatMap { it.collectWorkspaceFilePathsForIndex() } })
        .distinct()



private fun ChatSessionEntity.toChatSession(
    messages: List<LoadedChatMessage>,
    stats: ChatSessionMessageStatsEntity? = null,
    agent: ChatAgentSessionEntity? = null,
): ChatSession {
    val ordered = messages.sortedBy { it.position }
    val orderedMessages = ordered.map { it.message }
    val loadedFromPosition = ordered.firstOrNull()?.position ?: 0
    val remoteMachineId = agent
        ?.takeIf { isRemoteAgentPath(it.jsonlPath) }
        ?.let { parseRemoteMachineIdFromAgentPath(it.jsonlPath) }
        .orEmpty()
    val remoteKimiSessionId = agent
        ?.takeIf { isRemoteAgentPath(it.jsonlPath) && !it.piSessionId.startsWith("remote-pending-") }
        ?.piSessionId
        .orEmpty()
    val remoteCwd = agent
        ?.takeIf { isRemoteAgentPath(it.jsonlPath) }
        ?.let { parseRemoteCwdFromAgentPath(it.jsonlPath) }
        .orEmpty()
    return ChatSession(
        id = id,
        title = title,
        preview = preview,
        hasCustomTitle = hasCustomTitle,
        messages = orderedMessages,
        messageCount = stats?.messageCount ?: (loadedFromPosition + orderedMessages.size),
        loadedFromPosition = loadedFromPosition,
        lastMessageAtMillis = stats?.lastMessageAtMillis ?: orderedMessages.maxOfOrNull { it.createdAtMillis },
        selectedSkillIds = emptyList(),
        activeSkills = emptyList(),
        activeMcpServerIds = emptyList(),
        agentModeEnabled = agentModeEnabled,
        chromeEnabled = chromeEnabled,
        selectedModelKey = selectedModelKey,
        remoteMachineId = remoteMachineId,
        remoteKimiSessionId = remoteKimiSessionId,
        remoteCwd = remoteCwd,
        workspaceId = workspaceId,
    )
}

internal fun parseChatSessions(rawValue: String): List<ChatSession> =
    parseChatSessionsForMigration(rawValue).sessions

internal data class LegacyChatSessionsParseResult(
    val sessions: List<ChatSession>,
    val recoveredFromCorruption: Boolean,
)

internal fun parseChatSessionsForMigration(rawValue: String): LegacyChatSessionsParseResult {
    if (rawValue.isBlank()) {
        return LegacyChatSessionsParseResult(
            sessions = emptyList(),
            recoveredFromCorruption = false,
        )
    }

    return runCatching {
        val sessions = JSONArray(rawValue)
        LegacyChatSessionsParseResult(
            sessions = buildList {
                for (sessionIndex in 0 until sessions.length()) {
                    val session = checkNotNull(sessions.optJSONObject(sessionIndex)) {
                        "Invalid chat session at index $sessionIndex"
                    }
                    add(
                        ChatSession(
                            id = session.optString("id").ifBlank { "session-$sessionIndex" },
                            title = session.optString("title"),
                            preview = session.optString("preview"),
                            hasCustomTitle = session.optBoolean("hasCustomTitle", false),
                            messages = parseMessages(session.optJSONArrayOrThrow("messages", sessionIndex)),
                            selectedSkillIds = emptyList(),
                            activeSkills = emptyList(),
                            activeMcpServerIds = emptyList(),
                            agentModeEnabled = session.optBoolean("agentModeEnabled", false),
                            chromeEnabled = session.optBoolean("chromeEnabled", false),
                            selectedModelKey = session.optString("selectedModelKey"),
                            remoteMachineId = session.optString("remoteMachineId"),
                            remoteKimiSessionId = session.optString("remoteKimiSessionId"),
                            remoteCwd = session.optString("remoteCwd"),
                        )
                    )
                }
            },
            recoveredFromCorruption = false,
        )
    }.getOrElse { throwable ->
        LegacyChatSessionsParseResult(
            sessions = listOf(corruptedChatStateSession(rawValue, throwable)),
            recoveredFromCorruption = true,
        )
    }
}

private fun corruptedChatStateSession(
    rawValue: String,
    throwable: Throwable,
): ChatSession = ChatSession(
    id = "corrupt-chat-state-${rawValue.hashCode()}",
    title = "Chat storage needs recovery",
    preview = "Stored chat data could not be parsed.",
    hasCustomTitle = true,
    messages = listOf(
        ChatMessage(
            id = "agent-corrupt-chat-state-${rawValue.hashCode()}",
            author = MessageAuthor.Agent,
            text = "Aether could not read the stored chat history (${throwable.javaClass.simpleName}). " +
                "The app is showing this recovery placeholder instead of hiding the conversation list.",
            createdAtMillis = 0L,
            providerPayloadJson = rawValue,
        )
    ),
)

internal fun serializeChatSessions(sessions: List<ChatSession>): String = buildString {
    append('[')
    sessions.forEachIndexed { index, session ->
        if (index > 0) append(',')
        append(session.toJson().toString())
    }
    append(']')
}

internal fun ChatSession.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("title", title)
    put("preview", preview)
    put("hasCustomTitle", hasCustomTitle)
    put("agentModeEnabled", agentModeEnabled)
    put("chromeEnabled", chromeEnabled)
    put("selectedModelKey", selectedModelKey)
    put("remoteMachineId", remoteMachineId)
    put("remoteKimiSessionId", remoteKimiSessionId)
    put("remoteCwd", remoteCwd)
    put("messages", JSONArray().apply { syncActiveBranches(messages).forEach { put(it.toJson()) } })
}

private fun parseMessages(messages: JSONArray?): List<ChatMessage> {
    if (messages == null) return emptyList()

    return buildList {
        for (messageIndex in 0 until messages.length()) {
            val message = checkNotNull(messages.optJSONObject(messageIndex)) {
                "Invalid chat message at index $messageIndex"
            }
            add(parseMessage(message, messageIndex))
        }
    }
}

internal fun parseMessage(message: JSONObject, messageIndex: Int): ChatMessage = ChatMessage(
    id = message.optString("id").ifBlank { "message-$messageIndex" },
    author = if (message.optString("author") == MessageAuthor.User.name) {
        MessageAuthor.User
    } else {
        MessageAuthor.Agent
    },
    text = message.optString("text"),
    createdAtMillis = message.optLong("createdAtMillis").takeIf { it > 0L }
        ?: timestampFromMessageId(message.optString("id")),
    attachments = parseAttachments(message.optJSONArray("attachments")),
    toolInvocations = parseToolInvocations(message.optJSONArray("toolInvocations")),
    thoughtDurationMillis = if (message.has("thoughtDurationMillis")) {
        message.optLong("thoughtDurationMillis")
    } else {
        null
    },
    reasoningTrace = parseReasoningTrace(message.optJSONObject("reasoningTrace")),
    branchGroup = parseBranchGroup(message.optJSONObject("branchGroup")),
    responseGroupId = message.optString("responseGroupId").ifBlank { null },
    assistantActionsHidden = message.optBoolean("assistantActionsHidden"),
    isIncomplete = message.optBoolean("isIncomplete"),
    statusText = message.optString("statusText"),
    statusDetail = message.optString("statusDetail"),
    providerPayloadJson = message.optString("providerPayloadJson"),
    displayKind = parseMessageDisplayKind(message.optString("displayKind")),
    usageStatistics = parseUsageStatistics(message.optJSONObject("usageStatistics")),
    knowledgeCitations = parseKnowledgeCitations(message.optJSONArray("knowledgeCitations")),
    browserInlineImages = parseBrowserInlineImages(message.optJSONArray("browserInlineImages")),
    browserPreviewsByTopic = parseBrowserPreviewsByTopic(message.optJSONObject("browserPreviewsByTopic")),
)

private fun parseBrowserPreviewsByTopic(
    json: JSONObject?,
): Map<String, kira.ditto.browser.BrowserDeskPreview> {
    if (json == null || json.length() == 0) return emptyMap()
    val result = LinkedHashMap<String, kira.ditto.browser.BrowserDeskPreview>()
    json.keys().forEach { key ->
        val preview = json.optJSONObject(key)
            ?.let { browserDeskPreviewFromPersistJson(it) }
            ?: return@forEach
        result[key] = preview
    }
    return result
}

private fun parseMessageDisplayKind(value: String): MessageDisplayKind =
    MessageDisplayKind.entries.firstOrNull { it.name == value } ?: MessageDisplayKind.Standard

internal fun ChatMessage.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("author", author.name)
    put("text", text)
    if (createdAtMillis > 0L) {
        put("createdAtMillis", createdAtMillis)
    }
    thoughtDurationMillis?.let { put("thoughtDurationMillis", it) }
    reasoningTrace?.let { put("reasoningTrace", it.toJson()) }
    branchGroup?.let { put("branchGroup", it.toJson()) }
    responseGroupId?.let { put("responseGroupId", it) }
    if (assistantActionsHidden) {
        put("assistantActionsHidden", true)
    }
    if (isIncomplete) {
        put("isIncomplete", true)
    }
    statusText.takeIf { it.isNotBlank() }?.let { put("statusText", it) }
    statusDetail.takeIf { it.isNotBlank() }?.let { put("statusDetail", it) }
    providerPayloadJson.takeIf { it.isNotBlank() }?.let {
        put("providerPayloadJson", it)
    }
    if (displayKind != MessageDisplayKind.Standard) {
        put("displayKind", displayKind.name)
    }
    usageStatistics?.let { put("usageStatistics", it.toJson()) }
    if (knowledgeCitations.isNotEmpty()) {
        put(
            "knowledgeCitations",
            JSONArray().apply {
                knowledgeCitations.forEach { citation ->
                    put(
                        JSONObject().apply {
                            put("index", citation.index)
                            put("sourceName", citation.sourceName)
                            put("text", citation.text)
                            citation.url.takeIf { it.isNotBlank() }?.let { put("url", it) }
                        },
                    )
                }
            },
        )
    }
    if (browserInlineImages.isNotEmpty()) {
        put(
            "browserInlineImages",
            JSONArray().apply {
                browserInlineImages.forEach { image ->
                    put(
                        JSONObject().apply {
                            put("url", image.url)
                            image.alt.takeIf { it.isNotBlank() }?.let { put("alt", it) }
                            image.topicId.takeIf { it.isNotBlank() }?.let { put("topicId", it) }
                        },
                    )
                }
            },
        )
    }
    if (browserPreviewsByTopic.isNotEmpty()) {
        put(
            "browserPreviewsByTopic",
            JSONObject().apply {
                browserPreviewsByTopic.forEach { (topicId, preview) ->
                    put(topicId, preview.toPersistJson())
                }
            },
        )
    }
    put("toolInvocations", JSONArray().apply { toolInvocations.forEach { put(it.toJson()) } })
    put("attachments", JSONArray().apply { attachments.forEach { put(it.toJson()) } })
}

private fun parseKnowledgeCitations(array: JSONArray?): List<KnowledgeCitation> {
    if (array == null || array.length() == 0) return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val json = array.optJSONObject(index) ?: continue
            val citationIndex = json.optInt("index")
            val text = json.optString("text").trim()
            val url = json.optString("url").trim()
            if (citationIndex !in 1..999) continue
            if (text.isBlank() && url.isBlank()) continue
            add(
                KnowledgeCitation(
                    index = citationIndex,
                    sourceName = json.optString("sourceName"),
                    text = text.ifBlank { url },
                    url = url,
                ),
            )
        }
    }
}

private fun parseBrowserInlineImages(array: JSONArray?): List<BrowserInlineImage> {
    if (array == null || array.length() == 0) return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val json = array.optJSONObject(index) ?: continue
            val url = json.optString("url").trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) continue
            add(
                BrowserInlineImage(
                    url = url,
                    alt = json.optString("alt").trim(),
                    topicId = json.optString("topicId").trim(),
                ),
            )
        }
    }
}

private fun String.parseUsageStatisticsOrNull(): ChatUsageStatistics? =
    runCatching {
        parseUsageStatistics(JSONObject(this).optJSONObject("usageStatistics"))
    }.getOrNull()

private fun parseUsageStatistics(json: JSONObject?): ChatUsageStatistics? {
    if (json == null) return null
    return ChatUsageStatistics(
        inputTokens = json.optionalLong("inputTokens"),
        outputTokens = json.optionalLong("outputTokens"),
        totalTokens = json.optionalLong("totalTokens"),
        reasoningTokens = json.optionalLong("reasoningTokens"),
        cachedInputTokens = json.optionalLong("cachedInputTokens"),
        requestCount = json.optInt("requestCount", 1).coerceAtLeast(1),
        tokenUsageSource = json.optString("tokenUsageSource").ifBlank { "unavailable" },
        startedAtMillis = json.optLong("startedAtMillis"),
        firstTokenAtMillis = json.optionalLong("firstTokenAtMillis"),
        completedAtMillis = json.optLong("completedAtMillis"),
    )
}

private fun ChatUsageStatistics.toJson(): JSONObject = JSONObject().apply {
    inputTokens?.let { put("inputTokens", it) }
    outputTokens?.let { put("outputTokens", it) }
    totalTokens?.let { put("totalTokens", it) }
    reasoningTokens?.let { put("reasoningTokens", it) }
    cachedInputTokens?.let { put("cachedInputTokens", it) }
    put("requestCount", requestCount)
    put("tokenUsageSource", tokenUsageSource)
    if (startedAtMillis > 0L) put("startedAtMillis", startedAtMillis)
    firstTokenAtMillis?.let { put("firstTokenAtMillis", it) }
    if (completedAtMillis > 0L) put("completedAtMillis", completedAtMillis)
}

private fun JSONObject.optionalLong(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null

private fun parseBranchGroup(json: JSONObject?): ChatBranchGroup? {
    if (json == null) return null
    val branchesJson = json.optJSONArray("branches") ?: return null
    val branches = buildList {
        for (index in 0 until branchesJson.length()) {
            add(parseMessages(branchesJson.optJSONArray(index)))
        }
    }.filter { it.isNotEmpty() }
    if (branches.size <= 1) return null
    return ChatBranchGroup(
        branches = branches,
        selectedIndex = json.optInt("selectedIndex", 0).coerceIn(0, branches.lastIndex),
    )
}

private fun ChatBranchGroup.toJson(): JSONObject = JSONObject().apply {
    val safeSelectedIndex = selectedIndex.coerceIn(0, branches.lastIndex.coerceAtLeast(0))
    put("selectedIndex", safeSelectedIndex)
    put(
        "branches",
        JSONArray().apply {
            branches.forEach { branch ->
                put(JSONArray().apply { branch.forEach { put(it.toJson()) } })
            }
        },
    )
}

private fun parseAttachments(attachments: JSONArray?): List<ChatAttachment> {
    if (attachments == null) return emptyList()

    return buildList {
        for (attachmentIndex in 0 until attachments.length()) {
            val attachment = attachments.optJSONObject(attachmentIndex) ?: continue
            val mimeType = attachment.optString("mimeType")
            val workspacePath = attachment.optString("workspacePath")
            val inlineBase64 = attachment.optString("inlineBase64")
            val kind = AttachmentKind.fromStored(
                value = attachment.optString("kind"),
                mimeType = mimeType,
            )
            val hasVisualFallback = kind == AttachmentKind.Image && inlineBase64.isNotBlank()
            add(
                ChatAttachment(
                    id = attachment.optString("id").ifBlank { "attachment-$attachmentIndex" },
                    uri = attachment.optString("uri"),
                    name = attachment.optString("name").ifBlank { "Attachment ${attachmentIndex + 1}" },
                    mimeType = mimeType,
                    sizeBytes = if (attachment.has("sizeBytes")) attachment.optLong("sizeBytes") else null,
                    kind = kind,
                    workspacePath = workspacePath,
                    workspaceState = if (workspacePath.isBlank() && !hasVisualFallback) {
                        AttachmentWorkspaceState.Failed
                    } else {
                        AttachmentWorkspaceState.Ready
                    },
                    workspaceError = if (workspacePath.isBlank() && !hasVisualFallback) {
                        "This attachment is missing its workspace copy."
                    } else {
                        ""
                    },
                    inlineBase64 = inlineBase64,
                )
            )
        }
    }
}

private fun ChatAttachment.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("uri", uri)
    put("name", name)
    put("mimeType", mimeType)
    put("kind", kind.name)
    put("workspacePath", workspacePath)
    sizeBytes?.let { put("sizeBytes", it) }
    inlineBase64.takeIf { it.isNotBlank() }?.let {
        put("inlineBase64", it)
    }
}

private fun parseReasoningTrace(json: JSONObject?): ReasoningTrace? {
    if (json == null) return null
    val id = json.optString("id").ifBlank { "reasoning-${json.optString("startedAtMillis")}" }
    return ReasoningTrace(
        id = id,
        rawText = json.optString("rawText"),
        chunks = parseReasoningSummaryChunks(json.optJSONArray("chunks")),
        toolInvocations = parseToolInvocations(json.optJSONArray("toolInvocations")),
        latestStatusText = json.optString("latestStatusText"),
        startedAtMillis = json.optLong("startedAtMillis"),
        completedAtMillis = if (json.has("completedAtMillis")) {
            json.optLong("completedAtMillis")
        } else {
            null
        },
    )
}

private fun ReasoningTrace.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("rawText", if (hasSummary) "" else rawText)
    put("latestStatusText", latestStatusText)
    put("startedAtMillis", startedAtMillis)
    completedAtMillis?.let { put("completedAtMillis", it) }
    put("chunks", JSONArray().apply { chunks.forEach { put(it.toJson()) } })
    put("toolInvocations", JSONArray().apply { toolInvocations.forEach { put(it.toJson()) } })
}

private fun parseReasoningSummaryChunks(chunks: JSONArray?): List<ReasoningSummaryChunk> {
    if (chunks == null) return emptyList()
    return buildList {
        for (index in 0 until chunks.length()) {
            val chunk = chunks.optJSONObject(index) ?: continue
            add(
                ReasoningSummaryChunk(
                    id = chunk.optString("id").ifBlank { "reasoning-summary-$index" },
                    title = chunk.optString("title"),
                    detail = chunk.optString("detail"),
                    rawText = chunk.optString("rawText"),
                    isPending = chunk.optBoolean("isPending"),
                    createdAtMillis = chunk.optLong("createdAtMillis"),
                    timelineOrder = chunk.optLong("timelineOrder"),
                )
            )
        }
    }
}

private fun ReasoningSummaryChunk.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("title", title)
    put("detail", detail)
    put(
        "rawText",
        if (title.isNotBlank() || detail.isNotBlank()) "" else rawText,
    )
    put("isPending", isPending)
    put("createdAtMillis", createdAtMillis)
    put("timelineOrder", timelineOrder)
}

private fun parseToolInvocations(toolInvocations: JSONArray?): List<ChatToolInvocation> {
    if (toolInvocations == null) return emptyList()

    return buildList {
        for (toolIndex in 0 until toolInvocations.length()) {
            val toolInvocation = toolInvocations.optJSONObject(toolIndex) ?: continue
            add(
                ChatToolInvocation(
                    id = toolInvocation.optString("id").ifBlank { "tool-$toolIndex" },
                    toolName = toolInvocation.optString("toolName"),
                    argumentsJson = toolInvocation.optString("argumentsJson"),
                    outputJson = toolInvocation.optString("outputJson"),
                    isRunning = toolInvocation.optBoolean("isRunning"),
                    startedAtUptimeMillis = toolInvocation.optLong("startedAtUptimeMillis"),
                    completedAtUptimeMillis = if (toolInvocation.has("completedAtUptimeMillis")) {
                        toolInvocation.optLong("completedAtUptimeMillis")
                    } else {
                        null
                    },
                    startedAtMillis = toolInvocation.optLong("startedAtMillis"),
                    completedAtMillis = if (toolInvocation.has("completedAtMillis")) {
                        toolInvocation.optLong("completedAtMillis")
                    } else {
                        null
                    },
                    timelineOrder = toolInvocation.optLong("timelineOrder"),
                    toolKind = toolInvocation.optString("toolKind"),
                    diffs = parseToolCallDiffs(toolInvocation.optJSONArray("diffs")),
                    guiStepsJson = toolInvocation.optString("guiStepsJson"),
                )
            )
        }
    }
}

private fun parseToolCallDiffs(diffs: JSONArray?): List<ToolCallDiff> {
    if (diffs == null) return emptyList()
    return buildList {
        for (index in 0 until diffs.length()) {
            val diff = diffs.optJSONObject(index) ?: continue
            add(
                ToolCallDiff(
                    path = diff.optString("path"),
                    oldText = diff.optString("oldText"),
                    newText = diff.optString("newText"),
                )
            )
        }
    }
}

private fun ChatToolInvocation.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("toolName", toolName)
    put("argumentsJson", argumentsJson)
    put("outputJson", outputJson)
    put("isRunning", isRunning)
    put("startedAtUptimeMillis", startedAtUptimeMillis)
    completedAtUptimeMillis?.let { put("completedAtUptimeMillis", it) }
    put("startedAtMillis", startedAtMillis)
    completedAtMillis?.let { put("completedAtMillis", it) }
    put("timelineOrder", timelineOrder)
    toolKind.takeIf(String::isNotBlank)?.let { put("toolKind", it) }
    guiStepsJson.takeIf(String::isNotBlank)?.let { put("guiStepsJson", it) }
    if (diffs.isNotEmpty()) {
        put("diffs", JSONArray().apply {
            diffs.forEach { diff ->
                put(
                    JSONObject()
                        .put("path", diff.path)
                        .put("oldText", diff.oldText)
                        .put("newText", diff.newText)
                )
            }
        })
    }
}

private fun parseStringList(rawValue: String): List<String> = runCatching {
    parseStringList(JSONArray(rawValue))
}.getOrDefault(emptyList())


private fun JSONObject.optJSONArrayOrThrow(
    key: String,
    sessionIndex: Int,
): JSONArray? {
    if (!has(key) || isNull(key)) {
        return null
    }
    return checkNotNull(optJSONArray(key)) {
        "Invalid $key array for chat session at index $sessionIndex"
    }
}

private fun parseStringList(array: JSONArray?): List<String> {
    if (array == null) return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val value = array.optString(index).trim()
            if (value.isNotEmpty()) {
                add(value)
            }
        }
    }
}

internal fun timestampFromMessageId(messageId: String): Long {
    val timestamp = messageId.substringAfterLast('-', missingDelimiterValue = "")
    return timestamp.toLongOrNull()?.takeIf { it > 0L } ?: 0L
}
