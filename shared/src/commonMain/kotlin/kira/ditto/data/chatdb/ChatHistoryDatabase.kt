package kira.ditto.data.chatdb

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor

@Database(
    entities = [
        ChatSessionEntity::class,
        ChatAgentSessionEntity::class,
        ChatAgentMessageRefEntity::class,
        ChatMessageEntity::class,
        ChatMessagePayloadEntity::class,
        ChatWorkspaceFileRefEntity::class,
        ChatStateMetaEntity::class,
        BrowserPageEntity::class,
        BrowserPassageEntity::class,
        BrowserCitationEntity::class,
        BrowserTaskEntity::class,
        BrowserOriginEntity::class,
        LlmUsageRecordEntity::class,
        LlmUsageWireCursorEntity::class,
        MemoryOriginalPointerEntity::class,
        WorkspaceEntity::class,
    ],
    version = 14,
    exportSchema = true,
)
@ConstructedBy(ChatHistoryDatabaseConstructor::class)
abstract class ChatHistoryDatabase : RoomDatabase() {
    abstract fun chatHistoryDao(): ChatHistoryDao
    abstract fun browserCitationDao(): BrowserCitationDao
    abstract fun llmUsageDao(): LlmUsageDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object ChatHistoryDatabaseConstructor : RoomDatabaseConstructor<ChatHistoryDatabase> {
    override fun initialize(): ChatHistoryDatabase
}
