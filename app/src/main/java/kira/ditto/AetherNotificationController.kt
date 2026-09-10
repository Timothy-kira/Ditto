package kira.ditto

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kira.ditto.data.SessionExecutionState
import kira.ditto.ui.ChatSession

private const val ForegroundChannelId = "aether_background_runs"
private const val CompletionChannelId = "aether_completed_runs"
const val ForegroundNotificationId = 1001

class AetherNotificationController(
    private val context: Context,
) {
    private val notificationManager = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(NotificationManager::class.java)
        val foregroundChannel = NotificationChannel(
            ForegroundChannelId,
            "Background tasks",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows active Aether sessions running in the background."
            setShowBadge(false)
        }
        val completionChannel = NotificationChannel(
            CompletionChannelId,
            "Task completion",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Alerts you when a background Aether session finishes."
        }
        manager.createNotificationChannel(foregroundChannel)
        manager.createNotificationChannel(completionChannel)
    }

    fun buildForegroundNotification(
        sessions: List<ChatSession>,
        executionStates: Map<String, SessionExecutionState>,
    ): Notification {
        val activeSessions = sessions.filter { executionStates[it.id]?.isRunning == true }
        val title = if (activeSessions.size == 1) {
            "Aether is running 1 task"
        } else {
            "Aether is running ${activeSessions.size} tasks"
        }
        val body = activeSessions
            .take(3)
            .joinToString(separator = ", ") { it.title.ifBlank { "Untitled chat" } }
            .ifBlank { "Keeping active sessions alive in the background." }

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentMutabilityFlags(),
        )

        return NotificationCompat.Builder(context, ForegroundChannelId)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(
                        activeSessions.joinToString(separator = "\n") { session ->
                            "- ${session.title.ifBlank { "Untitled chat" }}"
                        }.ifBlank { body }
                    )
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentIntent)
            .build()
    }

    fun notifyCompletion(
        sessionId: String,
        sessionTitle: String,
        summary: String,
        failed: Boolean,
    ) {
        if (!canPostUserNotifications()) return

        val contentIntent = PendingIntent.getActivity(
            context,
            sessionId.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentMutabilityFlags(),
        )

        val title = if (failed) {
            "Aether task finished with an issue"
        } else {
            "Aether task finished"
        }

        val notification = NotificationCompat.Builder(context, CompletionChannelId)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setContentTitle(title)
            .setContentText(sessionTitle.ifBlank { "Untitled chat" })
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    buildString {
                        append(sessionTitle.ifBlank { "Untitled chat" })
                        if (summary.isNotBlank()) {
                            append("\n")
                            append(summary)
                        }
                    }
                )
            )
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            notificationManager.notify(sessionId.hashCode(), notification)
        } catch (_: SecurityException) {
            // Notification permission can be revoked after the preflight check.
        }
    }

    private fun canPostUserNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun pendingIntentMutabilityFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
}
