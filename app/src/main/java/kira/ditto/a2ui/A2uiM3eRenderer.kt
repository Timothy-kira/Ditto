package kira.ditto.a2ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import android.graphics.BitmapFactory
import java.net.URL
import kira.ditto.ui.theme.AetherOnSurface
import kira.ditto.ui.theme.AetherOnSurfaceVariant
import kira.ditto.ui.theme.AetherSurfaceHigh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull

@Composable
fun A2uiSurfaceView(
    state: A2uiSurfaceState,
    modifier: Modifier = Modifier,
    onUserAction: (name: String, componentId: String) -> Unit = { _, _ -> },
) {
    val roots = state.rootIds.ifEmpty { state.components.keys.take(1) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        roots.forEach { id ->
            A2uiComponentView(
                id = id,
                state = state,
                onUserAction = onUserAction,
            )
        }
    }
}

@Composable
private fun A2uiComponentView(
    id: String,
    state: A2uiSurfaceState,
    onUserAction: (name: String, componentId: String) -> Unit,
) {
    val component = state.components[id] ?: return
    val visible = (component.props["visible"] as? JsonPrimitive)?.booleanOrNull ?: true
    if (!visible) return
    when (component.type) {
        "Card" -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = AetherSurfaceHigh.copy(alpha = 0.86f)),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val title = a2uiLiteralString(component.props, "title")
                    if (title.isNotBlank()) {
                        Text(title, style = MaterialTheme.typography.titleMedium, color = AetherOnSurface)
                    }
                    A2uiChildren(component, state, onUserAction)
                }
            }
        }
        "Column" -> Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            A2uiChildren(component, state, onUserAction)
        }
        "Row" -> {
            val title = a2uiLiteralString(component.props, "title")
            val subtitle = a2uiLiteralString(component.props, "subtitle")
            if (title.isNotBlank() || subtitle.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (title.isNotBlank()) {
                        Text(title, style = MaterialTheme.typography.bodyMedium, color = AetherOnSurface)
                    }
                    if (subtitle.isNotBlank()) {
                        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = AetherOnSurfaceVariant)
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    A2uiChildren(component, state, onUserAction)
                }
            }
        }
        "List" -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            A2uiChildren(component, state, onUserAction)
        }
        "Text" -> {
            val text = a2uiLiteralString(component.props, "text", "value")
            if (text.isNotBlank()) {
                val variant = a2uiString(component.props, "variant")
                Text(
                    text = text,
                    color = AetherOnSurface,
                    style = when (variant) {
                        "headlineLarge" -> MaterialTheme.typography.headlineLarge
                        "titleLarge" -> MaterialTheme.typography.titleLarge
                        "bodySmall" -> MaterialTheme.typography.bodySmall
                        else -> MaterialTheme.typography.bodyMedium
                    },
                )
            }
            A2uiChildren(component, state, onUserAction)
        }
        "Button" -> {
            val label = a2uiLiteralString(component.props, "label").ifBlank { "OK" }
            val href = a2uiString(component.props, "href", "url")
            val event = a2uiString(component.props, "event")
            val uriHandler = LocalUriHandler.current
            Button(
                onClick = {
                    if (href.isNotBlank()) {
                        runCatching { uriHandler.openUri(href) }
                    } else if (event.isNotBlank()) {
                        onUserAction(event, component.id)
                    } else {
                        onUserAction("click", component.id)
                    }
                },
            ) {
                Text(label)
            }
        }
        "CheckBox" -> {
            val label = a2uiLiteralString(component.props, "label")
            val checked = (component.props["checked"] as? JsonPrimitive)?.booleanOrNull ?: false
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = checked, onCheckedChange = { onUserAction("toggle", component.id) })
                if (label.isNotBlank()) Text(label, color = AetherOnSurface)
            }
        }
        "TextField" -> {
            val label = a2uiLiteralString(component.props, "label")
            val value = a2uiLiteralString(component.props, "value")
            OutlinedTextField(
                value = value,
                onValueChange = {},
                readOnly = true,
                label = if (label.isNotBlank()) ({ Text(label) }) else null,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        "Slider" -> {
            val value = (component.props["value"] as? JsonPrimitive)?.floatOrNull ?: 0.5f
            Slider(value = value, onValueChange = {}, modifier = Modifier.fillMaxWidth())
        }
        "Divider" -> HorizontalDivider()
        "Image" -> {
            val url = a2uiLiteralString(component.props, "url", "src")
            if (url.isNotBlank()) {
                A2uiRemoteImage(url = url)
            }
        }
        "Icon" -> Text(
            text = a2uiString(component.props, "name").ifBlank { "•" },
            style = MaterialTheme.typography.titleMedium,
            color = AetherOnSurface,
        )
        "Tabs" -> {
            var selected by remember { mutableIntStateOf(0) }
            val childIds = component.children
            TabRow(selectedTabIndex = selected.coerceIn(0, (childIds.size - 1).coerceAtLeast(0))) {
                childIds.forEachIndexed { index, childId ->
                    Tab(
                        selected = selected == index,
                        onClick = { selected = index },
                        text = { Text(state.components[childId]?.id ?: childId) },
                    )
                }
            }
            childIds.getOrNull(selected)?.let { child ->
                A2uiComponentView(child, state, onUserAction)
            }
        }
        "Modal" -> A2uiChildren(component, state, onUserAction)
        "ChoicePicker", "DateTimeInput" -> {
            val label = a2uiLiteralString(component.props, "label", "value")
            if (label.isNotBlank()) {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = AetherOnSurface)
            }
            A2uiChildren(component, state, onUserAction)
        }
        else -> A2uiChildren(component, state, onUserAction)
    }
}

@Composable
private fun A2uiChildren(
    component: A2uiComponent,
    state: A2uiSurfaceState,
    onUserAction: (name: String, componentId: String) -> Unit,
) {
    component.children.forEach { childId ->
        A2uiComponentView(childId, state, onUserAction)
    }
}

@Composable
private fun A2uiRemoteImage(url: String) {
    var bitmap by remember(url) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(url) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                URL(url).openStream().use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }
    }
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 180.dp)
                .clip(RoundedCornerShape(12.dp)),
        )
    }
}
