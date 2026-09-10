package kira.ditto.data

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Narrow listener used only to qualify for [android.media.session.MediaSessionManager]
 * active sessions. Notification bodies are ignored.
 */
class SpotifyMediaSessionListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        SpotifyNowPlayingStore.onListenerConnected(this)
    }

    override fun onListenerDisconnected() {
        SpotifyNowPlayingStore.onListenerDisconnected()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = Unit

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = Unit
}
