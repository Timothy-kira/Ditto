package kira.ditto.data.chatdb

import android.app.Instrumentation
import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File

/**
 * Run migration tests on the SQLite the app actually ships.
 *
 * Room's `MigrationTestHelper` defaults to `FrameworkSQLiteOpenHelperFactory`, which opens the
 * database through Android's own SQLite. On this project that is the wrong engine: the app opens
 * every database with `BundledSQLiteDriver` (see `AndroidChatHistoryDatabaseFactory`), and several
 * shipped migrations use the JSON1 functions `json_valid` and `json_patch`. Bundled SQLite has
 * JSON1; the system SQLite on this device does not. So three migrations that work perfectly in
 * production failed in the tests, for a reason that had nothing to do with the migrations.
 *
 * The driver-based helper fixes the engine but changes two things: it binds to a single database
 * file rather than taking a name per call, and it hands back a `SQLiteConnection` instead of a
 * `SupportSQLiteDatabase`. This class absorbs both. It keeps one helper per database name, and wraps
 * the connection in a facade with the same `execSQL` / `query` / cursor surface the tests already
 * use - so the tests kept their existing bodies, and what changed is only what they run on.
 */
class BundledMigrationHelper(private val instrumentation: Instrumentation) {

    private val helpers = mutableMapOf<String, MigrationTestHelper>()

    private fun helperFor(name: String): MigrationTestHelper = helpers.getOrPut(name) {
        val file = File(instrumentation.targetContext.noBackupFilesDir, name)
        // The @Rule form deletes the file in `starting()`. These helpers are constructed on demand,
        // so a leftover database from a previous run would otherwise be migrated instead of a fresh
        // one at the requested version - a green test proving nothing.
        file.delete()
        File(file.parentFile, "$name-wal").delete()
        File(file.parentFile, "$name-shm").delete()
        MigrationTestHelper(
            instrumentation = instrumentation,
            file = file,
            driver = BundledSQLiteDriver(),
            databaseClass = ChatHistoryDatabase::class,
        )
    }

    fun createDatabase(name: String, version: Int): MigrationDatabase =
        MigrationDatabase(helperFor(name).createDatabase(version))

    fun runMigrationsAndValidate(
        name: String,
        version: Int,
        @Suppress("UNUSED_PARAMETER") validateDroppedTables: Boolean,
        vararg migrations: Migration,
    ): MigrationDatabase =
        MigrationDatabase(helperFor(name).runMigrationsAndValidate(version, migrations.toList()))

    fun closeAll() {
        helpers.clear()
    }
}

/** `SupportSQLiteDatabase`'s surface, as far as these tests use it, over a driver connection. */
class MigrationDatabase(private val connection: SQLiteConnection) : AutoCloseable {

    fun execSQL(sql: String) {
        connection.execSQL(sql)
    }

    fun query(sql: String): MigrationCursor = MigrationCursor(connection.prepare(sql))

    override fun close() {
        connection.close()
    }
}

/**
 * A cursor over a prepared statement.
 *
 * `count` is the one method that does not map directly: a statement is a forward-only stream, so
 * asking how many rows it will produce means draining it. The tests only use `count` to assert a
 * row total, and they do it before reading values, so draining and then replaying from a buffer
 * keeps both usages working.
 */
class MigrationCursor(private val statement: SQLiteStatement) : AutoCloseable {

    private val buffered = mutableListOf<List<Any?>>()
    private var drained = false
    private var position = -1

    private fun drain() {
        if (drained) return
        drained = true
        val columns = statement.getColumnCount()
        while (statement.step()) {
            buffered += (0 until columns).map { index ->
                if (statement.isNull(index)) null else statement.getText(index)
            }
        }
    }

    val count: Int
        get() {
            drain()
            return buffered.size
        }

    fun moveToFirst(): Boolean {
        drain()
        position = 0
        return buffered.isNotEmpty()
    }

    fun moveToNext(): Boolean {
        drain()
        position += 1
        return position < buffered.size
    }

    fun getString(index: Int): String = value(index).orEmpty()

    fun getInt(index: Int): Int = value(index)?.toIntOrNull() ?: 0

    fun getLong(index: Int): Long = value(index)?.toLongOrNull() ?: 0L

    private fun value(index: Int): String? {
        drain()
        val row = buffered.getOrNull(position) ?: return null
        return row.getOrNull(index) as? String
    }

    override fun close() {
        statement.close()
    }
}
