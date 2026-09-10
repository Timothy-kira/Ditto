package kira.ditto.data.chatdb

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Reads and writes the browser research ledger: pages, their passages, and what an answer cited.
 *
 * Everything is scoped by session. Nothing here is on a hot path - the writes happen once when a
 * turn's answer is complete, and the reads happen when a later turn asks what has already been
 * established.
 */
@Dao
interface BrowserCitationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPages(pages: List<BrowserPageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPassages(passages: List<BrowserPassageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCitations(citations: List<BrowserCitationEntity>)

    @Query("SELECT * FROM browser_citations WHERE sessionId = :sessionId ORDER BY createdAtMillis")
    suspend fun citationsForSession(sessionId: String): List<BrowserCitationEntity>

    @Query(
        "SELECT * FROM browser_citations WHERE sessionId = :sessionId AND messageId = :messageId " +
            "ORDER BY createdAtMillis",
    )
    suspend fun citationsForMessage(sessionId: String, messageId: String): List<BrowserCitationEntity>

    @Query(
        "SELECT * FROM browser_citations WHERE sessionId = :sessionId AND topicId = :topicId " +
            "ORDER BY createdAtMillis",
    )
    suspend fun citationsForTopic(sessionId: String, topicId: String): List<BrowserCitationEntity>

    /**
     * The version of this page we read most recently.
     *
     * This is what "is the citation still current?" compares against - never what a citation
     * resolves through, because a citation names its own version by hash.
     */
    @Query(
        "SELECT * FROM browser_pages WHERE sessionId = :sessionId AND pageKey = :pageKey " +
            "ORDER BY indexedAtMillis DESC LIMIT 1",
    )
    suspend fun latestPage(sessionId: String, pageKey: String): BrowserPageEntity?

    /** The exact version a citation points at, or null once it has been collected. */
    @Query(
        "SELECT * FROM browser_pages WHERE sessionId = :sessionId AND pageKey = :pageKey " +
            "AND contentHash = :contentHash",
    )
    suspend fun pageVersion(sessionId: String, pageKey: String, contentHash: String): BrowserPageEntity?

    /** Newest first - the ordering the version cap is applied in. */
    @Query(
        "SELECT * FROM browser_pages WHERE sessionId = :sessionId AND pageKey = :pageKey " +
            "ORDER BY indexedAtMillis DESC",
    )
    suspend fun pageVersions(sessionId: String, pageKey: String): List<BrowserPageEntity>

    @Query("SELECT DISTINCT pageKey FROM browser_pages WHERE sessionId = :sessionId")
    suspend fun pageKeys(sessionId: String): List<String>

    /**
     * The version hashes something still points at - git's reachability rule, in one query.
     *
     * Collecting a version a citation still names would hand-build the exact bug the versioning is
     * here to prevent, so this is the set the cap is never allowed to touch.
     */
    @Query(
        "SELECT DISTINCT contentHash FROM browser_citations WHERE sessionId = :sessionId " +
            "AND pageKey = :pageKey",
    )
    suspend fun citedContentHashes(sessionId: String, pageKey: String): List<String>

    /** Passages cascade with the version, so this one statement collects both. */
    @Query(
        "DELETE FROM browser_pages WHERE sessionId = :sessionId AND pageKey = :pageKey " +
            "AND contentHash = :contentHash",
    )
    suspend fun deletePageVersion(sessionId: String, pageKey: String, contentHash: String)

    @Query(
        "SELECT * FROM browser_passages WHERE sessionId = :sessionId AND pageKey = :pageKey " +
            "AND contentHash = :contentHash AND ordinal = :ordinal",
    )
    suspend fun passage(
        sessionId: String,
        pageKey: String,
        contentHash: String,
        ordinal: Int,
    ): BrowserPassageEntity?

    @Query(
        "SELECT * FROM browser_passages WHERE sessionId = :sessionId AND pageKey = :pageKey " +
            "AND contentHash = :contentHash ORDER BY ordinal",
    )
    suspend fun passages(
        sessionId: String,
        pageKey: String,
        contentHash: String,
    ): List<BrowserPassageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTasks(tasks: List<BrowserTaskEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOrigins(origins: List<BrowserOriginEntity>)

    @Query("SELECT * FROM browser_tasks WHERE sessionId = :sessionId ORDER BY createdAtMillis")
    suspend fun tasksForSession(sessionId: String): List<BrowserTaskEntity>

    /**
     * Recent tasks across every session - the window the recurrence gate counts tags in.
     *
     * Deliberately not session-scoped: "this subject keeps coming up" is a statement about the user,
     * and a user who researches the same thing in three separate conversations has shown more
     * interest than one who did it three times in one.
     */
    @Query(
        "SELECT * FROM browser_tasks WHERE createdAtMillis >= :sinceMillis " +
            "ORDER BY createdAtMillis DESC LIMIT :limit",
    )
    suspend fun recentTasks(sinceMillis: Long, limit: Int = 200): List<BrowserTaskEntity>

    @Query("SELECT * FROM browser_tasks WHERE uploadState = :state ORDER BY createdAtMillis LIMIT :limit")
    suspend fun tasksAwaitingUpload(state: String = "Pending", limit: Int = 20): List<BrowserTaskEntity>

    /** Guards against sending the same content twice, which is the only guard we get. */
    @Query("SELECT COUNT(*) FROM browser_tasks WHERE contentSha256 = :hash AND uploadState = 'Uploaded'")
    suspend fun uploadedTaskCount(hash: String): Int

    @Query("UPDATE browser_tasks SET uploadState = :state WHERE id = :id")
    suspend fun setTaskUploadState(id: String, state: String)

    @Query("SELECT * FROM browser_origins WHERE origin = :origin ORDER BY lastConfirmedAtMillis DESC LIMIT 1")
    suspend fun origin(origin: String): BrowserOriginEntity?

    /** The dossier injected into a subagent prompt when it is about to touch these hosts. */
    @Query("SELECT * FROM browser_origins WHERE origin IN (:origins) ORDER BY confirmCount DESC")
    suspend fun originsIn(origins: List<String>): List<BrowserOriginEntity>

    @Query("UPDATE browser_origins SET uploadState = :state WHERE sessionId = :sessionId AND origin = :origin")
    suspend fun setOriginUploadState(sessionId: String, origin: String, state: String)

    /**
     * Re-writing a turn's citations replaces them rather than layering on top.
     *
     * A redone or edited answer cites a different set; without this the stale set would survive
     * beside the new one and the ledger would slowly stop meaning anything.
     */
    @Query("DELETE FROM browser_citations WHERE sessionId = :sessionId AND messageId = :messageId")
    suspend fun deleteCitationsForMessage(sessionId: String, messageId: String)
}
