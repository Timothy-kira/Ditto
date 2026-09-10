package kira.ditto.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import kira.ditto.MainActivity
import kira.ditto.ScheduledTaskAlarmReceiver
import kotlin.math.max

class ScheduledTaskScheduler(
    private val context: Context,
    private val diagnosticLogger: AetherDiagnosticLogger = AetherDiagnosticLogger.NoOp,
) {
    private val alarmManager: AlarmManager =
        context.getSystemService(AlarmManager::class.java)

    fun sync(tasks: List<ScheduledTask>) {
        tasks.forEach(::schedule)
    }

    fun schedule(task: ScheduledTask) {
        cancel(task.id)
        val triggerAtMillis = task.nextRunAtMillis ?: return
        if (!task.isEnabled) return
        val pendingIntent = taskPendingIntent(task.id, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        try {
            val scheduleMode = scheduleAlarm(triggerAtMillis, pendingIntent)
            diagnosticLogger.event(
                category = "scheduled_task",
                event = "alarm_scheduled",
                details = mapOf(
                    "task_id" to task.id,
                    "trigger_at_millis" to triggerAtMillis,
                    "schedule_mode" to scheduleMode,
                    "can_schedule_exact" to canScheduleExactAlarms(),
                ),
            )
        } catch (throwable: Throwable) {
            diagnosticLogger.exception(
                category = "scheduled_task",
                event = "alarm_schedule_failed",
                throwable = throwable,
                details = mapOf("task_id" to task.id),
            )
        }
    }

    fun cancel(taskId: String) {
        val pendingIntent = taskPendingIntent(taskId, PendingIntent.FLAG_NO_CREATE) ?: return
        alarmManager.cancel(pendingIntent)
    }

    fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun scheduleAlarm(
        triggerAtMillis: Long,
        pendingIntent: PendingIntent,
    ): String {
        val normalizedTriggerAtMillis = max(triggerAtMillis, System.currentTimeMillis() + 1_000L)
        if (canScheduleExactAlarms()) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        normalizedTriggerAtMillis,
                        pendingIntent,
                    )
                } else {
                    alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        normalizedTriggerAtMillis,
                        pendingIntent,
                    )
                }
                return "exact"
            } catch (securityException: SecurityException) {
                diagnosticLogger.exception(
                    category = "scheduled_task",
                    event = "exact_alarm_denied",
                    throwable = securityException,
                )
            }
        }

        val showIntent = PendingIntent.getActivity(
            context,
            ShowScheduledTasksRequestCode,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(normalizedTriggerAtMillis, showIntent),
                pendingIntent,
            )
            return "alarm_clock"
        } catch (throwable: Throwable) {
            diagnosticLogger.exception(
                category = "scheduled_task",
                event = "alarm_clock_schedule_failed",
                throwable = throwable,
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                normalizedTriggerAtMillis,
                pendingIntent,
            )
        } else {
            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                normalizedTriggerAtMillis,
                pendingIntent,
            )
        }
        return "allow_while_idle"
    }

    private fun taskPendingIntent(
        taskId: String,
        updateFlag: Int,
    ): PendingIntent? = PendingIntent.getBroadcast(
        context,
        taskId.hashCode(),
        Intent(context, ScheduledTaskAlarmReceiver::class.java).apply {
            action = ActionRunScheduledTask
            putExtra(ExtraTaskId, taskId)
        },
        PendingIntent.FLAG_IMMUTABLE or updateFlag,
    )

    companion object {
        const val ActionRunScheduledTask = "kira.ditto.action.RUN_SCHEDULED_TASK"
        const val ExtraTaskId = "task_id"
        private const val ShowScheduledTasksRequestCode = 8_731
    }
}
