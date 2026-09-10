package kira.ditto.data.chatdb

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A space the user can see, and the unit that owns files on disk.
 *
 * Replaces the hidden "single vs independent workspace" setting, which asked a phone user to pick
 * a project layout they have no way to reason about - and whose two options were "every
 * conversation sees the last one's leftovers" and "nothing is ever kept". A space is the thing the
 * user already thinks in: conversations that belong together, with the files they produced.
 *
 * One space maps to one directory root, which is exactly what Kimi Code CLI calls a workspace, so
 * `session/new` registers it in `workspaces.json` on its own. Conversations inside a space share
 * that directory on purpose; across spaces they cannot see each other at all.
 */
@Entity(
    tableName = "workspaces",
    indices = [Index(value = ["sortOrder"])],
)
data class WorkspaceEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val createdAtMillis: Long,
    val sortOrder: Long,
)

@Entity(
    tableName = "chat_sessions",
    indices = [Index(value = ["sortOrder"]), Index(value = ["workspaceId"])],
)
data class ChatSessionEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val preview: String,
    val hasCustomTitle: Boolean,
    val agentModeEnabled: Boolean,
    val chromeEnabled: Boolean,
    val selectedModelKey: String,
    val sortOrder: Long,
    /**
     * No foreign key on purpose: a space is deleted by deleting its conversations first, and a
     * cascade here would make "delete the space, keep the chats" impossible to offer later.
     */
    val workspaceId: String = DefaultWorkspaceId,
)

@Entity(
    tableName = "chat_agent_sessions",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["chatSessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["piSessionId"], unique = true)],
)
data class ChatAgentSessionEntity(
    @PrimaryKey
    val chatSessionId: String,
    val piSessionId: String,
    val jsonlPath: String,
    val runtime: String,
    val migrationVersion: Int = 1,
    val updatedAtMillis: Long = 0L,
)

@Entity(
    tableName = "chat_agent_message_refs",
    primaryKeys = ["chatSessionId", "aetherMessageId", "piEntryId"],
    foreignKeys = [
        ForeignKey(
            entity = ChatMessageEntity::class,
            parentColumns = ["sessionId", "id"],
            childColumns = ["chatSessionId", "aetherMessageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["chatSessionId", "piEntryId"]), Index(value = ["chatSessionId", "aetherMessageId"])],
)
data class ChatAgentMessageRefEntity(
    val chatSessionId: String,
    val aetherMessageId: String,
    val piEntryId: String,
    val ordinal: Int = 0,
)

@Entity(
    tableName = "chat_messages",
    primaryKeys = ["sessionId", "id"],
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sessionId", "position"], unique = true),
        Index(value = ["sessionId", "responseGroupId"]),
        Index(value = ["sessionId", "author"]),
        Index(value = ["hasUsageStatistics"]),
    ],
)
data class ChatMessageEntity(
    val sessionId: String,
    val id: String,
    val position: Int,
    /**
     * The message document with the heavy branches ([ChatMessagePayloadEntity]) stripped out.
     * Keeping this row small is what makes the write-time diff cheap: text edits and status
     * changes no longer drag megabytes of tool output through the write path.
     */
    val messageJson: String,
    val author: String = "UNKNOWN",
    val text: String = "",
    val createdAtMillis: Long? = null,
    val responseGroupId: String? = null,
    val displayKind: String? = null,
    val messageSchemaVersion: Int = 1,
    val hasUsageStatistics: Boolean = false,
    val isIncomplete: Boolean = false,
    /** Hash of the light document, used to skip unchanged rows on save. */
    val contentHash: String = "",
    /** Hash of the payload row, so an unchanged payload is never rewritten. */
    val payloadHash: String = "",
)

/**
 * Out-of-row storage for the parts of a message that dwarf everything else: tool
 * invocations (including their diffs), reasoning traces, and inline attachment bytes.
 */
@Entity(
    tableName = "chat_message_payloads",
    primaryKeys = ["sessionId", "messageId"],
    foreignKeys = [
        ForeignKey(
            entity = ChatMessageEntity::class,
            parentColumns = ["sessionId", "id"],
            childColumns = ["sessionId", "messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ChatMessagePayloadEntity(
    val sessionId: String,
    val messageId: String,
    val payloadJson: String,
)

data class ChatMessageSummaryEntity(
    val sessionId: String,
    val id: String,
    val position: Int,
    val author: String = "UNKNOWN",
    val text: String = "",
    val createdAtMillis: Long? = null,
    val responseGroupId: String? = null,
    val displayKind: String? = null,
    val messageSchemaVersion: Int = 1,
    val messageJsonLength: Int? = null,
    val isIncomplete: Boolean = false,
    val payloadHash: String = "",
)

/** Just enough of a stored row to decide whether it needs rewriting. */
data class ChatMessageDigestEntity(
    val id: String,
    val position: Int,
    val contentHash: String,
    val payloadHash: String,
)

data class ChatSessionMessageStatsEntity(
    val sessionId: String,
    val messageCount: Int,
    val lastMessageAtMillis: Long?,
)

@Entity(
    tableName = "chat_workspace_file_refs",
    primaryKeys = ["sessionId", "messageId", "path"],
    foreignKeys = [
        ForeignKey(
            entity = ChatMessageEntity::class,
            parentColumns = ["sessionId", "id"],
            childColumns = ["sessionId", "messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["path"])],
)
data class ChatWorkspaceFileRefEntity(
    val sessionId: String,
    val messageId: String,
    val path: String,
)

@Entity(
    tableName = "chat_state_meta",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["currentSessionId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index(value = ["currentSessionId"])],
)
data class ChatStateMetaEntity(
    @PrimaryKey
    val id: String = ChatStateMetaEntityId,
    val currentSessionId: String?,
    val roomMigrationComplete: Boolean,
    val workspaceFileRefsComplete: Boolean,
)

const val ChatStateMetaEntityId = "default"

/**
 * One *version* of a page the browser read, kept so a citation can still be resolved later.
 *
 * The in-memory `BrowserResearchGraph` is task-scoped: it is cleared the moment a turn ends, so a
 * citation written yesterday had nothing left to point at. Long-running research needs the pages to
 * outlive the turn, and `normalizeBrowsedUrl` is what keys them - the same key the graph uses.
 *
 * `contentHash` is part of the identity, not a field beside it. `pageKey` is a path; the same path
 * serves different bytes on different days. Keyed on the path alone, a re-read replaces the row a
 * citation is still pointing at, and the citation silently starts describing text that is no longer
 * there. Content-addressing the version is the whole fix - a re-read adds a row instead of eating
 * one, and [BrowserCitationEntity.contentHash] is a reference to a version rather than a note about
 * one.
 */
@Entity(
    tableName = "browser_pages",
    primaryKeys = ["sessionId", "pageKey", "contentHash"],
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sessionId"]), Index(value = ["sessionId", "pageKey"])],
)
data class BrowserPageEntity(
    val sessionId: String,
    val pageKey: String,
    val url: String,
    val canonical: String,
    val title: String,
    val contentHash: String,
    val charCount: Int,
    val indexedAtMillis: Long,
)

/**
 * One chunk of a stored page version, addressed by its position so a citation can name it.
 *
 * The version belongs in the key for a sharper reason than the page's own: chunking is not stable
 * across re-reads. A shorter second version produces fewer chunks, so keyed on the path alone the
 * high-ordinal rows of the first version are never overwritten and never deleted - they simply stay,
 * and `[p7]` resolves to a paragraph from a page that no longer says it. Not stale: wrong, quietly.
 */
@Entity(
    tableName = "browser_passages",
    primaryKeys = ["sessionId", "pageKey", "contentHash", "ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = BrowserPageEntity::class,
            parentColumns = ["sessionId", "pageKey", "contentHash"],
            childColumns = ["sessionId", "pageKey", "contentHash"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sessionId", "pageKey", "contentHash"])],
)
data class BrowserPassageEntity(
    val sessionId: String,
    val pageKey: String,
    val contentHash: String,
    val ordinal: Int,
    val heading: String,
    val text: String,
)

/**
 * "This answer used this source", at whatever precision the resolver could establish.
 *
 * Indexed by message so a bubble can list its own sources, and by topic so a later turn can ask
 * what a line of research has already established and where each piece came from.
 */
@Entity(
    tableName = "browser_citations",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sessionId", "messageId"]),
        Index(value = ["sessionId", "topicId"]),
        Index(value = ["pageKey"]),
    ],
)
data class BrowserCitationEntity(
    @PrimaryKey
    val id: String,
    val sessionId: String,
    val messageId: String,
    val topicId: String,
    val pageKey: String,
    val url: String,
    val title: String,
    val contentHash: String,
    val passageOrdinal: Int,
    val quote: String,
    val quoteHash: String,
    /** Name of a `CitationConfidence` value: Page, Passage or Quote. */
    val confidence: String,
    /** Name of a `CitationKind` value: Web or Image. */
    val kind: String,
    /**
     * Name of a `MemorySource` value.
     *
     * A taint label, not a provenance note: a citation distilled from a page carries whatever that
     * page's author wanted it to carry, so anything downstream that could act on a memory has to be
     * able to see where it came from without re-deriving it.
     */
    val source: String = "TaskSummary",
    val createdAtMillis: Long,
)

/**
 * One finished piece of browser research, remembered as a conclusion rather than a list of pages.
 *
 * The URLs are deliberately absent. `browser_citations` already answers "which pages did this topic
 * lean on" by `(sessionId, topicId)`, and storing them again here would give the same question two
 * answers that drift apart. What lives here is what that table cannot say: what the research
 * concluded, what is still open, and whether it is worth keeping.
 */
@Entity(
    tableName = "browser_tasks",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sessionId", "topicId"]),
        Index(value = ["uploadState"]),
        Index(value = ["createdAtMillis"]),
    ],
)
data class BrowserTaskEntity(
    @PrimaryKey
    val id: String,
    val sessionId: String,
    val messageId: String,
    val topicId: String,
    val goal: String,
    val summary: String,
    /** Comma separated, two or three of them. Also the key the recurrence gate counts on. */
    val tags: String,
    val openTodos: String,
    val salience: Double,
    /** Name of a `MemorySource` value. */
    val source: String,
    /** Name of a `MemoryUploadState` value. */
    val uploadState: String,
    /**
     * Hash of what was uploaded.
     *
     * Standing in for an upsert we do not have: EverMe accepts content and extracts from it, with no
     * client-side entry id and no delete. So "send this once" has to be enforced here, before the
     * wire, because there is no after.
     */
    val contentSha256: String,
    val createdAtMillis: Long,
)

/** What operating a particular site taught us, keyed by host because that is what it is about. */
@Entity(
    tableName = "browser_origins",
    primaryKeys = ["sessionId", "origin"],
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["origin"]), Index(value = ["uploadState"])],
)
data class BrowserOriginEntity(
    val sessionId: String,
    val origin: String,
    val title: String,
    /** Needs a login, has a captcha, where the article body lives - the durable operating facts. */
    val facts: String,
    val lastConfirmedAtMillis: Long,
    /** One sighting can be a signed-out session. The upload waits for the second. */
    val confirmCount: Int,
    val source: String,
    val uploadState: String,
    val contentSha256: String,
)

@Entity(
    tableName = "llm_usage_records",
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["source"]),
        Index(value = ["completedAtMillis"]),
        Index(value = ["modelId"]),
    ],
)
data class LlmUsageRecordEntity(
    @PrimaryKey
    val id: String,
    val sessionId: String? = null,
    val source: String,
    val providerId: String,
    val modelId: String,
    val agentId: String? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val totalTokens: Long? = null,
    val reasoningTokens: Long? = null,
    val cachedInputTokens: Long? = null,
    val requestCount: Int = 1,
    val usageSource: String,
    val startedAtMillis: Long = 0L,
    val completedAtMillis: Long = 0L,
    val firstTokenAtMillis: Long? = null,
)

@Entity(tableName = "llm_usage_wire_cursors")
data class LlmUsageWireCursorEntity(
    @PrimaryKey
    val wireKey: String,
    val consumedCount: Int,
)

/**
 * Hash pointer from an EverMe `aether_hash` back to Chat rows.
 *
 * Lookup, not search: the sidecar writes the same sha256 of normalized spoken JSON,
 * and [read_original] resolves these ids. EverMe has no client entry id.
 */
@Entity(
    tableName = "memory_original_pointers",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sessionId"])],
)
data class MemoryOriginalPointerEntity(
    @PrimaryKey
    val hash: String,
    val sessionId: String,
    val messageIdsJson: String,
    val createdAtMillis: Long,
)

data class ChatSessionSnapshot(
    val session: ChatSessionEntity,
    val messages: List<ChatMessageEntity>,
    val workspaceFileRefs: List<ChatWorkspaceFileRefEntity> = emptyList(),
)
