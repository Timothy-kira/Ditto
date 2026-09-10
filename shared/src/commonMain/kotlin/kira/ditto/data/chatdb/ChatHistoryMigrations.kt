package kira.ditto.data.chatdb

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

val Migration1To2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `chat_workspace_file_refs` (
                `sessionId` TEXT NOT NULL,
                `messageId` TEXT NOT NULL,
                `path` TEXT NOT NULL,
                PRIMARY KEY(`sessionId`, `messageId`, `path`),
                FOREIGN KEY(`sessionId`, `messageId`) REFERENCES `chat_messages`(`sessionId`, `id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_chat_workspace_file_refs_path` ON `chat_workspace_file_refs` (`path`)",
        )
        connection.execSQL(
            "ALTER TABLE `chat_state_meta` ADD COLUMN `workspaceFileRefsComplete` INTEGER NOT NULL DEFAULT 0",
        )
    }
}

val Migration2To3 = object : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE `chat_messages` ADD COLUMN `hasUsageStatistics` INTEGER NOT NULL DEFAULT 0",
        )
        connection.execSQL(
            """
            UPDATE `chat_messages`
            SET `hasUsageStatistics` = 1
            WHERE `messageJson` LIKE '%"usageStatistics"%'
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_chat_messages_hasUsageStatistics` ON `chat_messages` (`hasUsageStatistics`)",
        )
    }
}

val Migration3To4 = object : Migration(3, 4) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE `chat_sessions` ADD COLUMN `chromeEnabled` INTEGER NOT NULL DEFAULT 0",
        )
    }
}

val Migration4To5 = object : Migration(4, 5) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE `chat_messages` ADD COLUMN `isIncomplete` INTEGER NOT NULL DEFAULT 0",
        )
        connection.execSQL(
            """
            UPDATE `chat_messages`
            SET `isIncomplete` = 1
            WHERE json_valid(`messageJson`) = 1
                AND json_extract(`messageJson`, '$.isIncomplete') = 1
            """.trimIndent(),
        )
    }
}

val Migration5To6 = object : Migration(5, 6) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `chat_agent_sessions` (
                `chatSessionId` TEXT NOT NULL,
                `piSessionId` TEXT NOT NULL,
                `jsonlPath` TEXT NOT NULL,
                `runtime` TEXT NOT NULL,
                `migrationVersion` INTEGER NOT NULL,
                `updatedAtMillis` INTEGER NOT NULL,
                PRIMARY KEY(`chatSessionId`),
                FOREIGN KEY(`chatSessionId`) REFERENCES `chat_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_chat_agent_sessions_piSessionId` ON `chat_agent_sessions` (`piSessionId`)",
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `chat_agent_message_refs` (
                `chatSessionId` TEXT NOT NULL,
                `aetherMessageId` TEXT NOT NULL,
                `piEntryId` TEXT NOT NULL,
                `ordinal` INTEGER NOT NULL,
                PRIMARY KEY(`chatSessionId`, `aetherMessageId`, `piEntryId`),
                FOREIGN KEY(`chatSessionId`, `aetherMessageId`) REFERENCES `chat_messages`(`sessionId`, `id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_chat_agent_message_refs_chatSessionId_piEntryId` ON `chat_agent_message_refs` (`chatSessionId`, `piEntryId`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_chat_agent_message_refs_chatSessionId_aetherMessageId` ON `chat_agent_message_refs` (`chatSessionId`, `aetherMessageId`)",
        )
    }
}

/** Remove legacy per-chat Skill/MCP activation state. Pi's SessionManager is now authoritative. */
val Migration6To7 = object : Migration(6, 7) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("PRAGMA foreign_keys=OFF")
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `chat_sessions_new` (
                `id` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `preview` TEXT NOT NULL,
                `hasCustomTitle` INTEGER NOT NULL,
                `agentModeEnabled` INTEGER NOT NULL,
                `chromeEnabled` INTEGER NOT NULL,
                `selectedModelKey` TEXT NOT NULL,
                `sortOrder` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO `chat_sessions_new` (
                `id`, `title`, `preview`, `hasCustomTitle`, `agentModeEnabled`,
                `chromeEnabled`, `selectedModelKey`, `sortOrder`
            )
            SELECT `id`, `title`, `preview`, `hasCustomTitle`, `agentModeEnabled`,
                `chromeEnabled`, `selectedModelKey`, `sortOrder`
            FROM `chat_sessions`
            """.trimIndent(),
        )
        connection.execSQL("DROP TABLE `chat_sessions`")
        connection.execSQL("ALTER TABLE `chat_sessions_new` RENAME TO `chat_sessions`")
        connection.execSQL("PRAGMA foreign_keys=ON")
    }
}

val Migration7To8 = object : Migration(7, 8) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `llm_usage_records` (
                `id` TEXT NOT NULL,
                `sessionId` TEXT,
                `source` TEXT NOT NULL,
                `providerId` TEXT NOT NULL,
                `modelId` TEXT NOT NULL,
                `agentId` TEXT,
                `inputTokens` INTEGER,
                `outputTokens` INTEGER,
                `totalTokens` INTEGER,
                `reasoningTokens` INTEGER,
                `cachedInputTokens` INTEGER,
                `requestCount` INTEGER NOT NULL,
                `usageSource` TEXT NOT NULL,
                `startedAtMillis` INTEGER NOT NULL,
                `completedAtMillis` INTEGER NOT NULL,
                `firstTokenAtMillis` INTEGER,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_llm_usage_records_sessionId` ON `llm_usage_records` (`sessionId`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_llm_usage_records_source` ON `llm_usage_records` (`source`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_llm_usage_records_completedAtMillis` ON `llm_usage_records` (`completedAtMillis`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_llm_usage_records_modelId` ON `llm_usage_records` (`modelId`)",
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `llm_usage_wire_cursors` (
                `wireKey` TEXT NOT NULL,
                `consumedCount` INTEGER NOT NULL,
                PRIMARY KEY(`wireKey`)
            )
            """.trimIndent(),
        )
    }
}

/**
 * Moves the message branches that dominate row size (tool invocations and their diffs,
 * reasoning traces, inline attachment bytes) out of `chat_messages.messageJson` into
 * `chat_message_payloads`, and adds the fingerprints the writer uses to skip unchanged
 * rows. Existing rows are split in place with SQLite's JSON1 functions.
 */
val Migration8To9 = object : Migration(8, 9) {
    private val payloadPaths = listOf(
        "toolInvocations",
        "reasoningTrace",
        "attachments",
        "tools",
        "responseBlocks",
    )

    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `chat_message_payloads` (
                `sessionId` TEXT NOT NULL,
                `messageId` TEXT NOT NULL,
                `payloadJson` TEXT NOT NULL,
                PRIMARY KEY(`sessionId`, `messageId`),
                FOREIGN KEY(`sessionId`, `messageId`) REFERENCES `chat_messages`(`sessionId`, `id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "ALTER TABLE `chat_messages` ADD COLUMN `contentHash` TEXT NOT NULL DEFAULT ''",
        )
        connection.execSQL(
            "ALTER TABLE `chat_messages` ADD COLUMN `payloadHash` TEXT NOT NULL DEFAULT ''",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_chat_sessions_sortOrder` ON `chat_sessions` (`sortOrder`)",
        )

        val jsonObjectArgs = payloadPaths.joinToString(", ") { path ->
            "'$path', json_extract(`messageJson`, '$.$path')"
        }
        val anyPayloadPresent = payloadPaths.joinToString(" OR ") { path ->
            "json_extract(`messageJson`, '$.$path') IS NOT NULL"
        }
        val payloadPathList = payloadPaths.joinToString(", ") { path -> "'$.$path'" }

        // json_valid() guards rows written by an older, non-strict encoder; those stay
        // whole rather than being silently emptied.
        connection.execSQL(
            """
            INSERT OR REPLACE INTO `chat_message_payloads` (`sessionId`, `messageId`, `payloadJson`)
            SELECT `sessionId`, `id`, json_patch('{}', json_object($jsonObjectArgs))
            FROM `chat_messages`
            WHERE json_valid(`messageJson`) AND ($anyPayloadPresent)
            """.trimIndent(),
        )
        connection.execSQL(
            """
            UPDATE `chat_messages`
            SET `messageJson` = json_remove(`messageJson`, $payloadPathList)
            WHERE json_valid(`messageJson`)
                AND `id` IN (SELECT `messageId` FROM `chat_message_payloads` WHERE `sessionId` = `chat_messages`.`sessionId`)
            """.trimIndent(),
        )
    }
}

/**
 * Adds the browser research ledger: pages, passages and citations.
 *
 * Pure `CREATE TABLE`; there is nothing to backfill, because before this migration browsed pages
 * only ever lived in memory for the length of a turn.
 */
val Migration9To10 = object : Migration(9, 10) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `browser_pages` (
                `sessionId` TEXT NOT NULL,
                `pageKey` TEXT NOT NULL,
                `url` TEXT NOT NULL,
                `canonical` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `contentHash` TEXT NOT NULL,
                `charCount` INTEGER NOT NULL,
                `indexedAtMillis` INTEGER NOT NULL,
                PRIMARY KEY(`sessionId`, `pageKey`),
                FOREIGN KEY(`sessionId`) REFERENCES `chat_sessions`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_browser_pages_sessionId` ON `browser_pages` (`sessionId`)",
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `browser_passages` (
                `sessionId` TEXT NOT NULL,
                `pageKey` TEXT NOT NULL,
                `ordinal` INTEGER NOT NULL,
                `heading` TEXT NOT NULL,
                `text` TEXT NOT NULL,
                PRIMARY KEY(`sessionId`, `pageKey`, `ordinal`),
                FOREIGN KEY(`sessionId`, `pageKey`) REFERENCES `browser_pages`(`sessionId`, `pageKey`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_browser_passages_sessionId_pageKey` " +
                "ON `browser_passages` (`sessionId`, `pageKey`)",
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `browser_citations` (
                `id` TEXT NOT NULL,
                `sessionId` TEXT NOT NULL,
                `messageId` TEXT NOT NULL,
                `topicId` TEXT NOT NULL,
                `pageKey` TEXT NOT NULL,
                `url` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `contentHash` TEXT NOT NULL,
                `passageOrdinal` INTEGER NOT NULL,
                `quote` TEXT NOT NULL,
                `quoteHash` TEXT NOT NULL,
                `confidence` TEXT NOT NULL,
                `kind` TEXT NOT NULL,
                `createdAtMillis` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`sessionId`) REFERENCES `chat_sessions`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_browser_citations_sessionId_messageId` " +
                "ON `browser_citations` (`sessionId`, `messageId`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_browser_citations_sessionId_topicId` " +
                "ON `browser_citations` (`sessionId`, `topicId`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_browser_citations_pageKey` " +
                "ON `browser_citations` (`pageKey`)",
        )
    }
}

/**
 * Rebuild the page ledger with the content hash inside the key.
 *
 * The v10 tables keyed pages and passages by path alone, so a re-read overwrote the version a
 * citation still pointed at, and a shorter re-read left the previous version's tail passages behind
 * for `[pN]` to resolve into. Both tables are caches of pages we can fetch again, so the migration
 * drops and recreates instead of trying to invent a hash for rows that never carried one -
 * `browser_citations` survives untouched, and its citations simply read as stale until re-resolved.
 */
val Migration10To11 = object : Migration(10, 11) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("DROP TABLE IF EXISTS `browser_passages`")
        connection.execSQL("DROP TABLE IF EXISTS `browser_pages`")
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `browser_pages` (
                `sessionId` TEXT NOT NULL,
                `pageKey` TEXT NOT NULL,
                `url` TEXT NOT NULL,
                `canonical` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `contentHash` TEXT NOT NULL,
                `charCount` INTEGER NOT NULL,
                `indexedAtMillis` INTEGER NOT NULL,
                PRIMARY KEY(`sessionId`, `pageKey`, `contentHash`),
                FOREIGN KEY(`sessionId`) REFERENCES `chat_sessions`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_browser_pages_sessionId` ON `browser_pages` (`sessionId`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_browser_pages_sessionId_pageKey` " +
                "ON `browser_pages` (`sessionId`, `pageKey`)",
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `browser_passages` (
                `sessionId` TEXT NOT NULL,
                `pageKey` TEXT NOT NULL,
                `contentHash` TEXT NOT NULL,
                `ordinal` INTEGER NOT NULL,
                `heading` TEXT NOT NULL,
                `text` TEXT NOT NULL,
                PRIMARY KEY(`sessionId`, `pageKey`, `contentHash`, `ordinal`),
                FOREIGN KEY(`sessionId`, `pageKey`, `contentHash`)
                    REFERENCES `browser_pages`(`sessionId`, `pageKey`, `contentHash`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_browser_passages_sessionId_pageKey_contentHash` " +
                "ON `browser_passages` (`sessionId`, `pageKey`, `contentHash`)",
        )
    }
}

/**
 * Add the browser memory tables, and the taint column that makes them safe to read back.
 *
 * `source` is on citations as well as the two new tables because a memory's trustworthiness has to
 * travel with it. Once a summary distilled from a page can be recalled into a later prompt, "which
 * of these facts did a web page write" stops being a curiosity and starts being the question that
 * decides whether a recalled fact may authorise an action. Existing citations backfill to
 * `TaskSummary`: they were distilled from pages, so that is what they are.
 */
val Migration11To12 = object : Migration(11, 12) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE `browser_citations` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'TaskSummary'",
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `browser_tasks` (
                `id` TEXT NOT NULL,
                `sessionId` TEXT NOT NULL,
                `messageId` TEXT NOT NULL,
                `topicId` TEXT NOT NULL,
                `goal` TEXT NOT NULL,
                `summary` TEXT NOT NULL,
                `tags` TEXT NOT NULL,
                `openTodos` TEXT NOT NULL,
                `salience` REAL NOT NULL,
                `source` TEXT NOT NULL,
                `uploadState` TEXT NOT NULL,
                `contentSha256` TEXT NOT NULL,
                `createdAtMillis` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`sessionId`) REFERENCES `chat_sessions`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_browser_tasks_sessionId_topicId` " +
                "ON `browser_tasks` (`sessionId`, `topicId`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_browser_tasks_uploadState` ON `browser_tasks` (`uploadState`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_browser_tasks_createdAtMillis` " +
                "ON `browser_tasks` (`createdAtMillis`)",
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `browser_origins` (
                `sessionId` TEXT NOT NULL,
                `origin` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `facts` TEXT NOT NULL,
                `lastConfirmedAtMillis` INTEGER NOT NULL,
                `confirmCount` INTEGER NOT NULL,
                `source` TEXT NOT NULL,
                `uploadState` TEXT NOT NULL,
                `contentSha256` TEXT NOT NULL,
                PRIMARY KEY(`sessionId`, `origin`),
                FOREIGN KEY(`sessionId`) REFERENCES `chat_sessions`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_browser_origins_origin` ON `browser_origins` (`origin`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_browser_origins_uploadState` " +
                "ON `browser_origins` (`uploadState`)",
        )
    }
}

/**
 * Pointer table from EverMe `aether_hash` back to Chat rows. Lookup, not search.
 */
val Migration12To13 = object : Migration(12, 13) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memory_original_pointers` (
                `hash` TEXT NOT NULL,
                `sessionId` TEXT NOT NULL,
                `messageIdsJson` TEXT NOT NULL,
                `createdAtMillis` INTEGER NOT NULL,
                PRIMARY KEY(`hash`),
                FOREIGN KEY(`sessionId`) REFERENCES `chat_sessions`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_memory_original_pointers_sessionId` " +
                "ON `memory_original_pointers` (`sessionId`)",
        )
    }
}

/**
 * Spaces become real, and every existing conversation lands in the default one.
 *
 * The default space is seeded here rather than lazily at first read so that `workspaceId` is never
 * a dangling reference - the column is populated in the same transaction that creates the row it
 * points at.
 */
val Migration13To14 = object : Migration(13, 14) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `workspaces` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `createdAtMillis` INTEGER NOT NULL,
                `sortOrder` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_workspaces_sortOrder` ON `workspaces` (`sortOrder`)",
        )
        connection.execSQL(
            "INSERT OR IGNORE INTO `workspaces` (`id`, `name`, `createdAtMillis`, `sortOrder`) " +
                "VALUES ('$DefaultWorkspaceId', '$DefaultWorkspaceName', 0, 0)",
        )
        connection.execSQL(
            "ALTER TABLE `chat_sessions` ADD COLUMN `workspaceId` TEXT NOT NULL " +
                "DEFAULT '$DefaultWorkspaceId'",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_chat_sessions_workspaceId` " +
                "ON `chat_sessions` (`workspaceId`)",
        )
    }
}

val ChatHistoryMigrations = arrayOf(
    Migration1To2,
    Migration2To3,
    Migration3To4,
    Migration4To5,
    Migration5To6,
    Migration6To7,
    Migration7To8,
    Migration8To9,
    Migration9To10,
    Migration10To11,
    Migration11To12,
    Migration12To13,
    Migration13To14,
)
