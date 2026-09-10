package kira.ditto.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.scheduledTaskDataStore by preferencesDataStore(name = "aether_scheduled_tasks")

class ScheduledTaskRepository(
    private val context: Context,
) {
    val scheduledTasks: Flow<List<ScheduledTask>> = context.scheduledTaskDataStore.data.map { preferences ->
        parseScheduledTasks(preferences[TASKS_JSON].orEmpty())
    }

    suspend fun snapshot(): List<ScheduledTask> = scheduledTasks.first()

    suspend fun findTask(taskId: String): ScheduledTask? =
        snapshot().firstOrNull { it.id == taskId }

    suspend fun upsertTask(task: ScheduledTask): ScheduledTask {
        var saved = task
        context.scheduledTaskDataStore.edit { preferences ->
            val current = parseScheduledTasks(preferences[TASKS_JSON].orEmpty()).toMutableList()
            val existingIndex = current.indexOfFirst { it.id == task.id }
            val now = System.currentTimeMillis()
            saved = task.copy(
                updatedAtMillis = now,
            ).withNextRunAfter(now)
            if (existingIndex >= 0) {
                current[existingIndex] = saved
            } else {
                current.add(saved)
            }
            preferences[TASKS_JSON] = serializeScheduledTasks(current)
        }
        return saved
    }

    suspend fun refreshNextRuns(
        afterMillis: Long = System.currentTimeMillis(),
        keepDueTasks: Boolean = true,
    ): List<ScheduledTask> {
        var refreshedTasks = emptyList<ScheduledTask>()
        context.scheduledTaskDataStore.edit { preferences ->
            refreshedTasks = refreshScheduledTaskNextRuns(
                tasks = parseScheduledTasks(preferences[TASKS_JSON].orEmpty()),
                afterMillis = afterMillis,
                keepDueTasks = keepDueTasks,
            )
            preferences[TASKS_JSON] = serializeScheduledTasks(refreshedTasks)
        }
        return refreshedTasks
    }

    suspend fun updateTask(
        taskId: String,
        transform: (ScheduledTask) -> ScheduledTask,
    ): ScheduledTask? {
        var updatedTask: ScheduledTask? = null
        context.scheduledTaskDataStore.edit { preferences ->
            val current = parseScheduledTasks(preferences[TASKS_JSON].orEmpty())
            val updated = current.map { task ->
                if (task.id == taskId) {
                    transform(task).also { updatedTask = it }
                } else {
                    task
                }
            }
            preferences[TASKS_JSON] = serializeScheduledTasks(updated)
        }
        return updatedTask
    }

    suspend fun removeTask(taskId: String) {
        context.scheduledTaskDataStore.edit { preferences ->
            val current = parseScheduledTasks(preferences[TASKS_JSON].orEmpty())
            preferences[TASKS_JSON] = serializeScheduledTasks(current.filterNot { it.id == taskId })
        }
    }

    private companion object {
        val TASKS_JSON = stringPreferencesKey("tasks_json")
    }
}
