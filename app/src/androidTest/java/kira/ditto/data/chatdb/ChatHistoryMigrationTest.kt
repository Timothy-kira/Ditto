package kira.ditto.data.chatdb

import androidx.test.platform.app.InstrumentationRegistry
import kira.ditto.data.ChatMessageEntityMapper
import kira.ditto.ui.MessageAuthor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHistoryMigrationTest {
    /**
     * Opened with bundled SQLite, the engine the app itself uses.
     *
     * The framework helper this replaced ran on Android's own SQLite, which on this device has no
     * JSON1 - so `Migration4To5` and `Migration8To9` failed on `json_valid` / `json_patch` while
     * working perfectly in production.
     */
    private val helper = BundledMigrationHelper(InstrumentationRegistry.getInstrumentation())

    @Test
    fun migrate1To7PreservesDataAndAddsCurrentSchema() {
        helper.createDatabase(TEST_DATABASE, 1).apply {
            execSQL(
                """
                INSERT INTO chat_sessions (
                    id, title, preview, hasCustomTitle, selectedSkillIdsJson,
                    activeSkillsJson, activeMcpServerIdsJson, agentModeEnabled,
                    selectedModelKey, sortOrder
                ) VALUES ('session-1', 'Title', 'Preview', 0, '[]', '[]', '[]', 0, '', 0)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO chat_messages (
                    sessionId, id, position, messageJson, author, text,
                    createdAtMillis, responseGroupId, displayKind, messageSchemaVersion
                ) VALUES (
                    'session-1', 'message-1', 0,
                    '{"usageStatistics":{"inputTokens":1},"isIncomplete":true}',
                    'assistant', 'With usage', NULL, NULL, NULL, 1
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO chat_messages (
                    sessionId, id, position, messageJson, author, text,
                    createdAtMillis, responseGroupId, displayKind, messageSchemaVersion
                ) VALUES (
                    'session-1', 'message-2', 1, '{}',
                    'user', 'Without usage', NULL, NULL, NULL, 1
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO chat_messages (
                    sessionId, id, position, messageJson, author, text,
                    createdAtMillis, responseGroupId, displayKind, messageSchemaVersion
                ) VALUES (
                    'session-1', 'message-3', 2,
                    '{"note":"\"isIncomplete\":true","isIncomplete":false}',
                    'assistant', 'Complete with lookalike text', NULL, NULL, NULL, 1
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO chat_state_meta (id, currentSessionId, roomMigrationComplete)
                VALUES ('singleton', 'session-1', 1)
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            7,
            true,
            *ChatHistoryMigrations,
        ).use { database ->
            database.query(
                "SELECT id, chromeEnabled FROM chat_sessions WHERE id = 'session-1'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("session-1", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
            }

            database.query(
                "SELECT id, hasUsageStatistics, isIncomplete FROM chat_messages ORDER BY position",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("message-1", cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
                assertEquals(1, cursor.getInt(2))
                assertTrue(cursor.moveToNext())
                assertEquals("message-2", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
                assertEquals(0, cursor.getInt(2))
                assertTrue(cursor.moveToNext())
                assertEquals("message-3", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
                assertEquals(0, cursor.getInt(2))
            }

            database.query("PRAGMA table_info(chat_workspace_file_refs)").use { cursor ->
                assertTrue(cursor.count > 0)
            }

            database.query(
                "SELECT workspaceFileRefsComplete FROM chat_state_meta WHERE id = 'singleton'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test
    fun migrate4To5LeavesMalformedMessageJsonForMapperRecovery() {
        val malformedJson = "{not-valid-json"
        helper.createDatabase(MALFORMED_JSON_DATABASE, 4).apply {
            execSQL(
                """
                INSERT INTO chat_sessions (
                    id, title, preview, hasCustomTitle, selectedSkillIdsJson,
                    activeSkillsJson, activeMcpServerIdsJson, agentModeEnabled,
                    chromeEnabled, selectedModelKey, sortOrder
                ) VALUES ('session-1', 'Title', 'Preview', 0, '[]', '[]', '[]', 0, 0, '', 0)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO chat_messages (
                    sessionId, id, position, messageJson, author, text,
                    createdAtMillis, responseGroupId, displayKind, messageSchemaVersion,
                    hasUsageStatistics
                ) VALUES (
                    'session-1', 'message-1', 0, '{"isIncomplete":true}',
                    'assistant', 'Incomplete response', 1001, 'group-1', NULL, 1, 0
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO chat_messages (
                    sessionId, id, position, messageJson, author, text,
                    createdAtMillis, responseGroupId, displayKind, messageSchemaVersion,
                    hasUsageStatistics
                ) VALUES (
                    'session-1', 'message-2', 1, '$malformedJson',
                    'assistant', 'Partial response', 1002, 'group-2', NULL, 1, 0
                )
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            MALFORMED_JSON_DATABASE,
            7,
            true,
            *ChatHistoryMigrations,
        ).use { database ->
            database.query(
                "SELECT id, isIncomplete FROM chat_messages ORDER BY position",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("message-1", cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
                assertTrue(cursor.moveToNext())
                assertEquals("message-2", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
            }

            database.query(
                """
                SELECT sessionId, id, position, messageJson, author, text,
                    createdAtMillis, responseGroupId, displayKind, messageSchemaVersion,
                    hasUsageStatistics, isIncomplete
                FROM chat_messages
                WHERE id = 'message-2'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                val recovered = ChatMessageEntityMapper.toChatMessage(
                    entity = ChatMessageEntity(
                        sessionId = cursor.getString(0),
                        id = cursor.getString(1),
                        position = cursor.getInt(2),
                        messageJson = cursor.getString(3),
                        author = cursor.getString(4),
                        text = cursor.getString(5),
                        createdAtMillis = cursor.getLong(6),
                        responseGroupId = cursor.getString(7),
                        displayKind = cursor.getString(8),
                        messageSchemaVersion = cursor.getInt(9),
                        hasUsageStatistics = cursor.getInt(10) != 0,
                        isIncomplete = cursor.getInt(11) != 0,
                    ),
                    messageIndex = 1,
                )

                assertEquals("message-2", recovered.id)
                assertEquals(MessageAuthor.Agent, recovered.author)
                assertEquals("Partial response", recovered.text)
                assertEquals(1002L, recovered.createdAtMillis)
                assertEquals("group-2", recovered.responseGroupId)
                assertEquals(malformedJson, recovered.providerPayloadJson)
                assertFalse(recovered.isIncomplete)
            }
        }
    }

    @Test
    fun migrate7To8CreatesUsageTables() {
        helper.createDatabase(USAGE_DATABASE, 7).apply {
            execSQL(
                """
                INSERT INTO chat_sessions (
                    id, title, preview, hasCustomTitle, agentModeEnabled,
                    chromeEnabled, selectedModelKey, sortOrder
                ) VALUES ('session-1', 'Title', 'Preview', 0, 0, 0, '', 0)
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            USAGE_DATABASE,
            8,
            true,
            *ChatHistoryMigrations,
        ).use { database ->
            database.query("PRAGMA table_info(llm_usage_records)").use { cursor ->
                assertTrue(cursor.count > 0)
            }
            database.query("PRAGMA table_info(llm_usage_wire_cursors)").use { cursor ->
                assertTrue(cursor.count > 0)
            }
        }
    }

    @Test
    fun migrate8To9SplitsPayloadAndAddsSortOrderIndex() {
        helper.createDatabase(PAYLOAD_DATABASE, 8).apply {
            execSQL(
                """
                INSERT INTO chat_sessions (
                    id, title, preview, hasCustomTitle, agentModeEnabled,
                    chromeEnabled, selectedModelKey, sortOrder
                ) VALUES ('session-1', 'Title', 'Preview', 0, 0, 0, '', 0)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO chat_messages (
                    sessionId, id, position, messageJson, author, text,
                    createdAtMillis, responseGroupId, displayKind, messageSchemaVersion,
                    hasUsageStatistics, isIncomplete
                ) VALUES (
                    'session-1', 'message-1', 0,
                    '{"text":"hi","toolInvocations":[{"id":"t1"}]}',
                    'assistant', 'hi', 1001, NULL, NULL, 1, 0, 0
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO chat_messages (
                    sessionId, id, position, messageJson, author, text,
                    createdAtMillis, responseGroupId, displayKind, messageSchemaVersion,
                    hasUsageStatistics, isIncomplete
                ) VALUES (
                    'session-1', 'message-2', 1, '{"text":"plain"}',
                    'user', 'plain', 1002, NULL, NULL, 1, 0, 0
                )
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            PAYLOAD_DATABASE,
            9,
            true,
            *ChatHistoryMigrations,
        ).use { database ->
            database.query("PRAGMA table_info(chat_message_payloads)").use { cursor ->
                assertTrue(cursor.count > 0)
            }
            database.query(
                "SELECT payloadJson FROM chat_message_payloads WHERE messageId = 'message-1'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.getString(0).contains("toolInvocations"))
            }
            database.query(
                "SELECT messageJson FROM chat_messages WHERE id = 'message-1'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(!cursor.getString(0).contains("toolInvocations"))
            }
            database.query(
                "SELECT COUNT(*) FROM chat_message_payloads WHERE messageId = 'message-2'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    /**
     * The research ledger is pure new tables, so what matters is that a v9 database with real rows
     * survives the migration and that the citation tables are usable afterwards - including the
     * cascade from chat_sessions, which is what stops the ledger outliving its own conversation.
     */
    @Test
    fun migrate9To10AddsTheBrowserResearchLedger() {
        helper.createDatabase(LEDGER_DATABASE, 9).apply {
            execSQL(
                """
                INSERT INTO chat_sessions (
                    id, title, preview, hasCustomTitle, agentModeEnabled,
                    chromeEnabled, selectedModelKey, sortOrder
                ) VALUES ('session-1', 'Title', 'Preview', 0, 0, 0, '', 0)
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            LEDGER_DATABASE,
            10,
            true,
            *ChatHistoryMigrations,
        ).use { database ->
            database.execSQL(
                """
                INSERT INTO browser_pages (
                    sessionId, pageKey, url, canonical, title, contentHash, charCount, indexedAtMillis
                ) VALUES (
                    'session-1', 'news.sina.cn/detail', 'https://news.sina.cn/detail',
                    'news.sina.cn/detail', 'News', 'hash-1', 4213, 1000
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO browser_passages (sessionId, pageKey, ordinal, heading, text)
                VALUES ('session-1', 'news.sina.cn/detail', 3, 'Heading', 'passage text')
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO browser_citations (
                    id, sessionId, messageId, topicId, pageKey, url, title, contentHash,
                    passageOrdinal, quote, quoteHash, confidence, kind, createdAtMillis
                ) VALUES (
                    'c1', 'session-1', 'message-1', 'topic-1', 'news.sina.cn/detail',
                    'https://news.sina.cn/detail', 'News', 'hash-1',
                    3, 'shared sentence', 'hash-q', 'Quote', 'Web', 2000
                )
                """.trimIndent(),
            )

            database.query(
                "SELECT passageOrdinal, quote, confidence FROM browser_citations WHERE id = 'c1'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(3, cursor.getInt(0))
                assertEquals("shared sentence", cursor.getString(1))
                assertEquals("Quote", cursor.getString(2))
            }

            // A passage is addressed by (session, page, ordinal); that is the anchor a citation
            // stores, so it has to round-trip.
            database.query(
                "SELECT text FROM browser_passages " +
                    "WHERE sessionId = 'session-1' AND pageKey = 'news.sina.cn/detail' AND ordinal = 3",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("passage text", cursor.getString(0))
            }

            database.execSQL("PRAGMA foreign_keys = ON")
            database.execSQL("DELETE FROM chat_sessions WHERE id = 'session-1'")
            listOf("browser_citations", "browser_pages", "browser_passages").forEach { table ->
                database.query("SELECT COUNT(*) FROM $table").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("$table should cascade with its session", 0, cursor.getInt(0))
                }
            }
        }
    }

    /**
     * 10 -> 11 puts the content hash inside the page and passage keys.
     *
     * The two assertions that matter are the two bugs the key change exists to kill: a re-read must
     * not evict the version a citation still points at, and the tail passages of a longer earlier
     * version must not be visible when reading the shorter later one. Both were silent under the v10
     * keys - the first served text the answer never saw, the second served it under the right
     * ordinal.
     */
    @Test
    fun migrate10To11VersionsThePageLedger() {
        helper.createDatabase(VERSION_DATABASE, 9).apply {
            execSQL(
                """
                INSERT INTO chat_sessions (
                    id, title, preview, hasCustomTitle, agentModeEnabled,
                    chromeEnabled, selectedModelKey, sortOrder
                ) VALUES ('session-1', 'Title', 'Preview', 0, 0, 0, '', 0)
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(VERSION_DATABASE, 10, true, *ChatHistoryMigrations).use { database ->
            database.execSQL(
                """
                INSERT INTO browser_pages (
                    sessionId, pageKey, url, canonical, title, contentHash, charCount, indexedAtMillis
                ) VALUES (
                    'session-1', 'news.sina.cn/detail', 'https://news.sina.cn/detail',
                    'news.sina.cn/detail', 'News', 'hash-1', 4213, 1000
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO browser_citations (
                    id, sessionId, messageId, topicId, pageKey, url, title, contentHash,
                    passageOrdinal, quote, quoteHash, confidence, kind, createdAtMillis
                ) VALUES (
                    'c1', 'session-1', 'message-1', 'topic-1', 'news.sina.cn/detail',
                    'https://news.sina.cn/detail', 'News', 'hash-1',
                    3, 'shared sentence', 'hash-q', 'Quote', 'Web', 2000
                )
                """.trimIndent(),
            )
            database.close()
        }

        helper.runMigrationsAndValidate(VERSION_DATABASE, 11, true, *ChatHistoryMigrations).use { database ->
            // The page cache is rebuilt because v10 rows carry no version identity to salvage.
            // Citations are not cached and must survive; they simply read as stale until re-resolved.
            database.query("SELECT COUNT(*) FROM browser_pages").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            database.query("SELECT contentHash FROM browser_citations WHERE id = 'c1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("hash-1", cursor.getString(0))
            }

            listOf("hash-1" to 900, "hash-2" to 400).forEach { (hash, chars) ->
                database.execSQL(
                    """
                    INSERT INTO browser_pages (
                        sessionId, pageKey, url, canonical, title, contentHash, charCount, indexedAtMillis
                    ) VALUES (
                        'session-1', 'news.sina.cn/detail', 'https://news.sina.cn/detail',
                        'news.sina.cn/detail', 'News', '$hash', $chars, 1000
                    )
                    """.trimIndent(),
                )
            }
            database.query(
                "SELECT COUNT(*) FROM browser_pages WHERE pageKey = 'news.sina.cn/detail'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("a re-read adds a version, it does not eat one", 2, cursor.getInt(0))
            }

            // The long first read chunked to four passages; the shorter second read to two.
            (0..3).forEach { ordinal ->
                database.execSQL(
                    "INSERT INTO browser_passages (sessionId, pageKey, contentHash, ordinal, heading, text) " +
                        "VALUES ('session-1', 'news.sina.cn/detail', 'hash-1', $ordinal, 'H', 'old $ordinal')",
                )
            }
            (0..1).forEach { ordinal ->
                database.execSQL(
                    "INSERT INTO browser_passages (sessionId, pageKey, contentHash, ordinal, heading, text) " +
                        "VALUES ('session-1', 'news.sina.cn/detail', 'hash-2', $ordinal, 'H', 'new $ordinal')",
                )
            }
            database.query(
                "SELECT COUNT(*) FROM browser_passages WHERE contentHash = 'hash-2'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("[p2] and [p3] belong to the old version only", 2, cursor.getInt(0))
            }
            database.query(
                "SELECT text FROM browser_passages WHERE contentHash = 'hash-1' AND ordinal = 3",
            ).use { cursor ->
                assertTrue("the version a citation names is still readable", cursor.moveToFirst())
                assertEquals("old 3", cursor.getString(0))
            }

            // Deleting a version takes its passages with it, and nothing else.
            database.execSQL("PRAGMA foreign_keys = ON")
            database.execSQL("DELETE FROM browser_pages WHERE contentHash = 'hash-1'")
            database.query("SELECT COUNT(*) FROM browser_passages").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2, cursor.getInt(0))
            }
        }
    }

    @Test
    fun migrate12To13AddsOriginalPointerTable() {
        helper.createDatabase(POINTER_DATABASE, 12).apply {
            execSQL(
                """
                INSERT INTO chat_sessions (
                    id, title, preview, hasCustomTitle, agentModeEnabled,
                    chromeEnabled, selectedModelKey, sortOrder
                ) VALUES ('session-1', 'Title', 'Preview', 0, 0, 0, '', 0)
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(POINTER_DATABASE, 13, true, *ChatHistoryMigrations).use { database ->
            database.execSQL(
                """
                INSERT INTO memory_original_pointers (hash, sessionId, messageIdsJson, createdAtMillis)
                VALUES ('abc', 'session-1', '["m1"]', 1)
                """.trimIndent(),
            )
            database.query("SELECT sessionId FROM memory_original_pointers WHERE hash = 'abc'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("session-1", cursor.getString(0))
            }
        }
    }

    @Test
    fun migrate13To14PutsExistingConversationsInTheDefaultSpace() {
        helper.createDatabase(WORKSPACE_DATABASE, 13).apply {
            execSQL(
                """
                INSERT INTO chat_sessions (
                    id, title, preview, hasCustomTitle, agentModeEnabled,
                    chromeEnabled, selectedModelKey, sortOrder
                ) VALUES ('session-1', 'Title', 'Preview', 0, 0, 0, '', 0)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO chat_sessions (
                    id, title, preview, hasCustomTitle, agentModeEnabled,
                    chromeEnabled, selectedModelKey, sortOrder
                ) VALUES ('session-2', 'Other', 'Preview', 0, 0, 0, '', 1)
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(WORKSPACE_DATABASE, 14, true, *ChatHistoryMigrations).use { database ->
            // The default space must exist before anything points at it.
            database.query("SELECT name FROM workspaces WHERE id = '$DefaultWorkspaceId'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(DefaultWorkspaceName, cursor.getString(0))
            }
            // No conversation may be orphaned by the upgrade.
            database.query(
                "SELECT COUNT(*) FROM chat_sessions WHERE workspaceId <> '$DefaultWorkspaceId'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            database.query("SELECT COUNT(*) FROM chat_sessions").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2, cursor.getInt(0))
            }
        }
    }

    private companion object {
        const val TEST_DATABASE = "chat-history-migration-test"
        const val MALFORMED_JSON_DATABASE = "chat-history-malformed-json-migration-test"
        const val USAGE_DATABASE = "chat-history-usage-migration-test"
        const val PAYLOAD_DATABASE = "chat-history-payload-migration-test"
        const val LEDGER_DATABASE = "chat-history-ledger-migration-test"
        const val VERSION_DATABASE = "chat-history-page-version-migration-test"
        const val POINTER_DATABASE = "chat-history-original-pointer-migration-test"
        const val WORKSPACE_DATABASE = "chat-history-workspace-migration-test"
    }
}
