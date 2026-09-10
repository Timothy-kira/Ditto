package kira.ditto.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import kira.ditto.R
import kira.ditto.data.McpServerConfig
import kira.ditto.data.McpTransportConfig
import kira.ditto.upa.isUpaMcpServerId

fun isKaggleBrand(icon: String, id: String = ""): Boolean {
    if (id.contains("kaggle.cli.card")) return true
    return icon.trim().equals("kaggle", ignoreCase = true)
}

fun upaBrandImageVector(icon: String): ImageVector = when (
    icon.trim().lowercase().replace('_', '-')
) {
    "cloud-sun", "cloud", "sun", "weather" -> Icons.Rounded.Cloud
    "map-pin", "map", "place", "pin", "location" -> Icons.Rounded.Place
    "heart", "health", "activity" -> Icons.Rounded.Favorite
    "utensils", "coffee", "food", "luckin", "mcd" -> Icons.Rounded.LocalCafe
    "kaggle", "chart", "quota" -> Icons.Rounded.QueryStats
    else -> LucideIcons.Plug
}

@Composable
fun McpServerConfig.composerIcon(): ImageVector {
    if (isKaggleBrand(icon, id)) {
        return ImageVector.vectorResource(R.drawable.ic_kaggle)
    }
    if (kira.ditto.data.SpotifyMcp.isShippedServerId(id)) {
        return ImageVector.vectorResource(R.drawable.ic_spotify)
    }
    if (icon.isNotBlank() || isUpaMcpServerId(id)) {
        return upaBrandImageVector(icon)
    }
    return if (transport is McpTransportConfig.StdIo) {
        Icons.Rounded.Terminal
    } else {
        Icons.Rounded.Cloud
    }
}
