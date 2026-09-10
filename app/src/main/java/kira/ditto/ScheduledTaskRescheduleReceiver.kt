package kira.ditto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ScheduledTaskRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action.orEmpty()
        if (action !in RescheduleActions) return
        val pendingResult = goAsync()
        context.aetherRuntime.rescheduleScheduledTasks(pendingResult)
    }

    private companion object {
        val RescheduleActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
