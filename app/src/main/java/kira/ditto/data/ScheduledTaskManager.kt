package kira.ditto.data

class ScheduledTaskManager(
    private val repository: ScheduledTaskRepository,
    private val scheduler: ScheduledTaskScheduler,
) {
    val scheduledTasks = repository.scheduledTasks

    suspend fun snapshot(): List<ScheduledTask> = repository.snapshot()

    suspend fun findTask(taskId: String): ScheduledTask? = repository.findTask(taskId)

    suspend fun upsertTask(task: ScheduledTask): ScheduledTask {
        val saved = repository.upsertTask(task)
        scheduler.schedule(saved)
        return saved
    }

    suspend fun removeTask(taskId: String) {
        repository.removeTask(taskId)
        scheduler.cancel(taskId)
    }

    suspend fun setTaskEnabled(
        taskId: String,
        enabled: Boolean,
    ): ScheduledTask? {
        val now = System.currentTimeMillis()
        val updated = repository.updateTask(taskId) { task ->
            task.copy(
                isEnabled = enabled,
                updatedAtMillis = now,
            ).withNextRunAfter(now)
        }
        if (updated == null || !updated.isEnabled) {
            scheduler.cancel(taskId)
        } else {
            scheduler.schedule(updated)
        }
        return updated
    }

    suspend fun markTriggeredAndScheduleNext(
        taskId: String,
        startedAtMillis: Long = System.currentTimeMillis(),
    ): ScheduledTask? {
        val updated = repository.updateTask(taskId) { task ->
            task.copy(
                lastRunStartedAtMillis = startedAtMillis,
                updatedAtMillis = startedAtMillis,
            ).withNextRunAfter(startedAtMillis)
        }
        if (updated == null || !updated.isEnabled) {
            scheduler.cancel(taskId)
        } else {
            scheduler.schedule(updated)
        }
        return updated
    }

    suspend fun rescheduleAll(
        keepDueTasks: Boolean = true,
    ) {
        scheduler.sync(repository.refreshNextRuns(keepDueTasks = keepDueTasks))
    }
}
