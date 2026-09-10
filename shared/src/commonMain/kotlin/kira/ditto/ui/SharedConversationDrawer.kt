package kira.ditto.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kira.ditto.shared.resources.Res
import kira.ditto.shared.resources.app_name
import kira.ditto.shared.resources.common_chat
import kira.ditto.shared.resources.common_delete
import kira.ditto.shared.resources.common_export
import kira.ditto.shared.resources.common_fork
import kira.ditto.shared.resources.common_new_chat
import kira.ditto.shared.resources.common_rename
import kira.ditto.shared.resources.common_search
import kira.ditto.shared.resources.search_no_chats_match
import kira.ditto.shared.resources.settings_title
import kira.ditto.shared.resources.chat_no_conversations_yet
import kira.ditto.ui.theme.AetherOnSurface
import kira.ditto.ui.theme.AetherOnSurfaceVariant
import kira.ditto.ui.theme.AetherBackground
import kira.ditto.ui.theme.AetherScrim
import kira.ditto.ui.theme.AetherSidebarBackground
import kira.ditto.ui.theme.AetherSurface
import kira.ditto.ui.theme.AetherSurfaceHigh
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

data class SharedConversationSummary(
    val id: String,
    val title: String,
    val indicator: SharedConversationIndicator = SharedConversationIndicator.None,
)

enum class SharedConversationIndicator {
    None,
    Working,
    UnviewedComplete,
}

private val SharedDrawerOverlayFadeHeight = 24.dp
private val SharedDrawerBottomFadeHeight = 42.dp
private val SharedDrawerEndFadeWidth = 28.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AetherConversationDrawer(
    sessions: List<SharedConversationSummary>,
    selectedSessionId: String,
    onNewChat: () -> Unit,
    /** Long-press on the new-chat button. Unbound by default, so hosts opt in. */
    onNewChatLongPress: (() -> Unit)? = null,
    onSessionSelected: (String) -> Unit,
    onRenameSession: (String, String) -> Unit,
    onExportSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onForkSession: (String) -> Unit = {},
    onSettingsSelected: () -> Unit,
    permanent: Boolean = false,
    extraContent: @Composable ((dismissSearch: () -> Unit) -> Unit) = {},
    headerContent: @Composable () -> Unit = {},
    footerContent: @Composable () -> Unit = {},
    navigationContent: @Composable ((dismissSearch: () -> Unit) -> Unit) = {},
) {
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var overlayBodyHeightPx by remember { mutableIntStateOf(0) }
    var footerHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val overlayBodyHeight = with(density) {
        if (overlayBodyHeightPx > 0) overlayBodyHeightPx.toDp() else 248.dp
    }
    val listBottomPadding = SharedDrawerBottomFadeHeight + 72.dp + with(density) { footerHeightPx.toDp() }
    val filteredSessions = remember(sessions, searchQuery) {
        val query = searchQuery.trim().lowercase()
        if (query.isBlank()) sessions else sessions.filter { it.title.lowercase().contains(query) }
    }
    val listState = rememberLazyListState()
    val dismissSearch = {
        searchExpanded = false
        searchQuery = ""
    }
    LaunchedEffect(selectedSessionId) {
        if (searchQuery.isNotBlank() || searchExpanded) {
            searchExpanded = false
            searchQuery = ""
        }
        val index = sessions.indexOfFirst { it.id == selectedSessionId }
        listState.scrollToItem(if (index >= 0) index else 0)
    }
    val drawerBackground = AetherSidebarBackground

    ModalDrawerSheet(
        modifier = Modifier.fillMaxHeight().width(if (permanent) 320.dp else 322.dp),
        drawerContainerColor = drawerBackground,
        drawerShape = if (permanent) RoundedCornerShape(0.dp) else {
            RoundedCornerShape(topEnd = 30.dp, bottomEnd = 30.dp)
        },
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(bottom = 18.dp)) {
            if (filteredSessions.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(
                            start = 16.dp,
                            end = 16.dp,
                            top = overlayBodyHeight + 10.dp,
                            bottom = listBottomPadding,
                        ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(
                            if (sessions.isEmpty()) {
                                Res.string.chat_no_conversations_yet
                            } else {
                                Res.string.search_no_chats_match
                            }
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AetherOnSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                    extraContent(dismissSearch)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = overlayBodyHeight + 10.dp,
                        bottom = listBottomPadding,
                    ),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(filteredSessions, key = { it.id }) { session ->
                        SharedDrawerSessionRow(
                            session = session,
                            selected = session.id == selectedSessionId,
                            onClick = {
                                dismissSearch()
                                onSessionSelected(session.id)
                            },
                            onRename = { onRenameSession(session.id, it) },
                            onExport = { onExportSession(session.id) },
                            onDelete = { onDeleteSession(session.id) },
                            onFork = { onForkSession(session.id) },
                        )
                    }
                    item(key = "aether-extra-drawer-content") {
                        Column(modifier = Modifier.padding(top = 10.dp)) {
                            extraContent(dismissSearch)
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(drawerBackground)
                        .onSizeChanged { overlayBodyHeightPx = it.height },
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(top = 18.dp)
                            .statusBarsPadding(),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(Res.string.app_name),
                                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Normal),
                                color = AetherOnSurface,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                HeaderCircleButton(
                                    icon = LucideIcons.Search,
                                    contentDescription = stringResource(Res.string.common_search),
                                    onClick = {
                                        if (searchExpanded || searchQuery.isNotBlank()) dismissSearch()
                                        else searchExpanded = true
                                    },
                                    size = 46.dp,
                                    containerColor = Color.Transparent,
                                    showHalo = false,
                                )
                                HeaderCircleButton(
                                    icon = LucideIcons.Settings,
                                    contentDescription = stringResource(Res.string.settings_title),
                                    onClick = {
                                        dismissSearch()
                                        onSettingsSelected()
                                    },
                                    size = 46.dp,
                                    containerColor = Color.Transparent,
                                    showHalo = false,
                                )
                            }
                        }
                        AnimatedVisibility(visible = searchExpanded || searchQuery.isNotBlank()) {
                            Column {
                                Spacer(modifier = Modifier.height(16.dp))
                                SharedDrawerSearchField(searchQuery) { searchQuery = it }
                            }
                        }
                        val searching = searchExpanded || searchQuery.isNotBlank()
                        Spacer(modifier = Modifier.height(if (searching) 10.dp else 12.dp))
                        AnimatedVisibility(
                            visible = !searching,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically(),
                        ) {
                            Column {
                                navigationContent(dismissSearch)
                            }
                        }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    ) {
                        headerContent()
                    }
                }
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(SharedDrawerOverlayFadeHeight)
                        .background(sharedDrawerOverlayTailGradient(drawerBackground)),
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .navigationBarsPadding(),
            ) {
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(SharedDrawerBottomFadeHeight)
                        .background(sharedDrawerOverlayBottomGradient(drawerBackground)),
                )
            }

            if (permanent) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(SharedDrawerEndFadeWidth)
                        .background(sharedDrawerEndFadeGradient()),
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
                    .offset(y = (-86).dp)
                    .onSizeChanged { footerHeightPx = it.height },
            ) {
                footerContent()
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 18.dp, bottom = 18.dp)
                    .shadow(18.dp, RoundedCornerShape(999.dp), ambientColor = AetherScrim, spotColor = AetherScrim)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0xFFAD7BF9))
                    .combinedClickable(
                        onClick = {
                            dismissSearch()
                            onNewChat()
                        },
                        onLongClick = onNewChatLongPress?.let { handler ->
                            {
                                dismissSearch()
                                handler()
                            }
                        },
                    )
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(LucideIcons.SquarePen, null, tint = Color.White, modifier = Modifier.size(17.dp))
                Text(
                    text = stringResource(Res.string.common_chat),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun SharedDrawerSearchField(value: String, onValueChange: (String) -> Unit) {
    Row(
        modifier = Modifier
            .shadow(12.dp, RoundedCornerShape(24.dp), ambientColor = AetherScrim, spotColor = AetherScrim)
            .clip(RoundedCornerShape(24.dp))
            .background(AetherSurface)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(LucideIcons.Search, null, tint = AetherOnSurfaceVariant, modifier = Modifier.size(18.dp))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isBlank()) {
                Text(
                    stringResource(Res.string.common_search),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AetherOnSurfaceVariant,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = AetherOnSurface),
                cursorBrush = SolidColor(AetherOnSurface),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SharedDrawerSessionRow(
    session: SharedConversationSummary,
    selected: Boolean,
    onClick: () -> Unit,
    onRename: (String) -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onFork: () -> Unit = {},
) {
    val newChatTitle = stringResource(Res.string.common_new_chat)
    var menuExpanded by remember { mutableStateOf(false) }
    var isRenaming by remember { mutableStateOf(false) }
    var renameFieldHadFocus by remember { mutableStateOf(false) }
    var renameFocusRequest by remember { mutableIntStateOf(0) }
    var titleValue by remember(session.id, session.title, newChatTitle) {
        mutableStateOf(session.title.ifBlank { newChatTitle })
    }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    fun commitRename() {
        titleValue.trim().takeIf { it.isNotBlank() && it != session.title }?.let(onRename)
        isRenaming = false
        keyboard?.hide()
    }

    LaunchedEffect(renameFocusRequest, isRenaming) {
        if (renameFocusRequest > 0 && isRenaming) {
            delay(260)
            if (isRenaming) {
                focusRequester.requestFocus()
                keyboard?.show()
            }
        }
    }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(if (selected) AetherSurfaceHigh else Color.Transparent)
                .then(
                    if (isRenaming) Modifier else Modifier.combinedClickable(
                        onClick = onClick,
                        onLongClick = { menuExpanded = true },
                    )
                )
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isRenaming) {
                BasicTextField(
                    value = titleValue,
                    onValueChange = { titleValue = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = AetherOnSurface,
                        fontWeight = FontWeight.Medium,
                    ),
                    cursorBrush = SolidColor(AetherOnSurface),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commitRename() }),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .onFocusChanged { state ->
                            if (state.isFocused) {
                                renameFieldHadFocus = true
                            } else if (renameFieldHadFocus && isRenaming) {
                                commitRename()
                            }
                        }
                )
            } else {
                Text(
                    text = session.title.ifBlank { newChatTitle },
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    ),
                    color = AetherOnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (session.indicator != SharedConversationIndicator.None) {
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (session.indicator == SharedConversationIndicator.Working) {
                                Color(0xFF22C55E)
                            } else {
                                Color(0xFF3B82F6)
                            }
                        )
                )
            }
        }
        SharedDrawerActionMenu(
            expanded = menuExpanded,
            onDismiss = { menuExpanded = false },
            onRename = {
                menuExpanded = false
                titleValue = session.title.ifBlank { newChatTitle }
                renameFieldHadFocus = false
                isRenaming = true
                renameFocusRequest += 1
            },
            onExport = {
                menuExpanded = false
                onExport()
            },
            onFork = {
                menuExpanded = false
                onFork()
            },
            onDelete = {
                menuExpanded = false
                onDelete()
            },
        )
    }
}

@Composable
private fun SharedDrawerActionMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onFork: () -> Unit,
    onDelete: () -> Unit,
) {
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = expanded
    if (!visibility.currentState && !visibility.targetState) return
    Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(0, 34),
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        AnimatedVisibility(
            visibleState = visibility,
            enter = fadeIn() + scaleIn(initialScale = 0.92f),
            exit = fadeOut() + scaleOut(targetScale = 0.96f),
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 188.dp, max = 220.dp)
                    .shadow(18.dp, RoundedCornerShape(24.dp), ambientColor = AetherScrim, spotColor = AetherScrim)
                    .clip(RoundedCornerShape(24.dp))
                    .background(AetherSurface)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                SharedDrawerActionRow(stringResource(Res.string.common_rename), onRename)
                SharedDrawerActionRow(stringResource(Res.string.common_fork), onFork)
                SharedDrawerActionRow(stringResource(Res.string.common_export), onExport)
                SharedDrawerActionRow(stringResource(Res.string.common_delete), onDelete, destructive = true)
            }
        }
    }
}

@Composable
private fun SharedDrawerActionRow(
    label: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (destructive) Color(0xFFB42318) else AetherOnSurface,
        )
    }
}

@Composable
fun SharedDrawerNavRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) AetherSurfaceHigh else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AetherOnSurface,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            ),
            color = AetherOnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun sharedDrawerOverlayTailGradient(baseColor: Color): Brush = Brush.verticalGradient(
    colorStops = arrayOf(
        0.0f to baseColor,
        0.55f to baseColor.copy(alpha = 0.55f),
        1.0f to Color.Transparent,
    )
)

private fun sharedDrawerOverlayBottomGradient(baseColor: Color): Brush = Brush.verticalGradient(
    colorStops = arrayOf(
        0.0f to Color.Transparent,
        0.18f to baseColor.copy(alpha = 0.18f),
        0.42f to baseColor.copy(alpha = 0.52f),
        0.72f to baseColor.copy(alpha = 0.92f),
        1.0f to baseColor.copy(alpha = 0.98f),
    )
)

private fun sharedDrawerEndFadeGradient(): Brush = Brush.horizontalGradient(
    colorStops = arrayOf(
        0.0f to Color.Transparent,
        0.45f to AetherBackground.copy(alpha = 0.35f),
        1.0f to AetherBackground,
    )
)
