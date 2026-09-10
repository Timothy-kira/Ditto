package kira.ditto.data

import android.graphics.Bitmap

data class SpotifyNowPlaying(
    val title: String = "",
    val artist: String = "",
    val isPlaying: Boolean = false,
    val cover: Bitmap? = null,
    val coverToken: String = "",
    val imageUri: String = "",
    val hasNotificationAccess: Boolean = false,
    val hasActiveSession: Boolean = false,
)
