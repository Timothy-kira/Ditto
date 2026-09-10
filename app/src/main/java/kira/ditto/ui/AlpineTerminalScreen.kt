package kira.ditto.ui

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TerminalColors
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kira.ditto.runtime.AlpineTerminalLaunchSpec
import kira.ditto.ui.theme.AetherBackground
import kira.ditto.ui.theme.AetherOnSurface
import kira.ditto.ui.theme.AetherOnSurfaceVariant
import kira.ditto.ui.theme.AetherSurface
import kira.ditto.ui.theme.AetherSurfaceHigh
import kira.ditto.ui.theme.AetherSurfaceHigher
import java.io.File
import kotlin.math.roundToInt

@Composable
fun AlpineTerminalScreen(
    createLaunchSpec: suspend () -> Result<AlpineTerminalLaunchSpec>,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var launchSpec by remember { mutableStateOf<AlpineTerminalLaunchSpec?>(null) }
    var errorMessage by remember { mutableStateOf("") }
    var terminalView by remember { mutableStateOf<TerminalView?>(null) }
    var terminalSession by remember { mutableStateOf<TerminalSession?>(null) }
    var title by remember { mutableStateOf("Alpine") }
    var controlDown by remember { mutableStateOf(false) }
    var altDown by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val terminalTextSizePx = remember(context) { defaultTermuxTerminalTextSizePx(context) }
    val terminalTypeface = remember(context) { embeddedTerminalTypeface(context) }
    val isDarkTheme = isSystemInDarkTheme()
    val terminalBackgroundColor = remember(isDarkTheme) {
        if (isDarkTheme) AndroidColor.BLACK else AndroidColor.WHITE
    }
    val terminalTextColor = remember(isDarkTheme) {
        if (isDarkTheme) AndroidColor.WHITE else AndroidColor.BLACK
    }
    val latestCreateLaunchSpec by rememberUpdatedState(createLaunchSpec)

    // Configure terminal color scheme for current theme
    LaunchedEffect(isDarkTheme) {
        val colorScheme = TerminalColors.COLOR_SCHEME
        colorScheme.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND] = terminalBackgroundColor
        colorScheme.mDefaultColors[TextStyle.COLOR_INDEX_FOREGROUND] = terminalTextColor
        colorScheme.setCursorColorForBackground()
    }

    LaunchedEffect(Unit) {
        val result = latestCreateLaunchSpec()
        result.fold(
            onSuccess = { launchSpec = it },
            onFailure = { errorMessage = it.message ?: "Unable to start Alpine terminal." },
        )
    }

    DisposableEffect(terminalSession) {
        val session = terminalSession
        onDispose {
            session?.finishIfRunning()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AetherBackground)
            .imePadding()
            .navigationBarsPadding(),
    ) {
        TerminalTopBar(
            title = title,
            onBack = onBack,
        )

        val spec = launchSpec
        when {
            spec == null && errorMessage.isBlank() -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = AetherOnSurface,
                    )
                }
            }

            errorMessage.isNotBlank() -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    Text(
                        text = errorMessage,
                        color = AetherOnSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 24.dp),
                    )
                }
            }

            spec != null -> {
                AlpineTerminalEmulator(
                    spec = spec,
                    backgroundColor = terminalBackgroundColor,
                    textSizePx = terminalTextSizePx,
                    typeface = terminalTypeface,
                    onTitleChanged = { newTitle ->
                        val normalized = newTitle.ifBlank { "Alpine" }
                        if (title != normalized) {
                            title = normalized
                        }
                    },
                    onSessionReady = { view, session ->
                        terminalView = view
                        terminalSession = session
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            }
        }

        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1f)) {
            TerminalExtraKeysBar(
                terminalView = terminalView,
                terminalSession = terminalSession,
                controlDown = controlDown,
                altDown = altDown,
                onControlDownChange = { controlDown = it },
                onAltDownChange = { altDown = it },
            )
        }
    }
}

@Composable
private fun AlpineTerminalEmulator(
    spec: AlpineTerminalLaunchSpec,
    backgroundColor: Int,
    textSizePx: Int,
    typeface: Typeface,
    onTitleChanged: (String) -> Unit,
    onSessionReady: (TerminalView, TerminalSession) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestOnTitleChanged by rememberUpdatedState(onTitleChanged)
    val latestOnSessionReady by rememberUpdatedState(onSessionReady)

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            val view = TerminalView(viewContext, null).apply {
                setBackgroundColor(backgroundColor)
                setTextSize(textSizePx)
                setTypeface(typeface)
                isFocusable = true
                isFocusableInTouchMode = true
            }
            val coalescer = TerminalInvalidateCoalescer(view)
            val sessionClient = AetherTerminalSessionClient(
                context = viewContext,
                invalidateTerminal = coalescer::request,
                updateTitle = { newTitle ->
                    view.post { latestOnTitleChanged(newTitle) }
                },
                onCursorStateChange = { enabled ->
                    view.post { view.setTerminalCursorBlinkerState(enabled, true) }
                },
            )
            val session = TerminalSession(
                spec.executable,
                spec.workingDirectory,
                spec.arguments,
                spec.environment,
                2_000,
                sessionClient,
            )
            view.setTerminalViewClient(AetherTerminalViewClient(view))
            view.attachSession(session)
            view.post {
                latestOnSessionReady(view, session)
                view.updateSize()
                view.requestFocus()
                showKeyboard(view.context, view)
            }
            view
        },
        update = {},
    )
}

@Composable
private fun TerminalTopBar(
    title: String,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .background(AetherBackground.copy(alpha = 0.86f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .padding(end = 12.dp)
                .background(AetherSurface, CircleShape)
                .clickable(onClick = onBack)
                .padding(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back",
                tint = AetherOnSurface,
                modifier = Modifier.padding(2.dp),
            )
        }
        Text(text = title, color = AetherOnSurface)
    }
}

@Composable
private fun TerminalExtraKeysBar(
    terminalView: TerminalView?,
    terminalSession: TerminalSession?,
    controlDown: Boolean,
    altDown: Boolean,
    onControlDownChange: (Boolean) -> Unit,
    onAltDownChange: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AetherSurfaceHigh)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ExtraKeyRow {
            ExtraKey("ESC") { terminalView.sendKey(KeyEvent.KEYCODE_ESCAPE, controlDown, altDown) }
            ExtraKey("TAB") { terminalView.sendKey(KeyEvent.KEYCODE_TAB, controlDown, altDown) }
            ExtraKey("CTRL", active = controlDown) { onControlDownChange(!controlDown) }
            ExtraKey("ALT", active = altDown) { onAltDownChange(!altDown) }
            ExtraKey("-") { terminalView.sendText("-", controlDown, altDown) }
            ExtraKey("/") { terminalView.sendText("/", controlDown, altDown) }
            ExtraKey("|") { terminalView.sendText("|", controlDown, altDown) }
            ExtraKey("HOME") { terminalView.sendKey(KeyEvent.KEYCODE_MOVE_HOME, controlDown, altDown) }
            ExtraKey("END") { terminalView.sendKey(KeyEvent.KEYCODE_MOVE_END, controlDown, altDown) }
            ExtraKey("PGUP") { terminalView.sendKey(KeyEvent.KEYCODE_PAGE_UP, controlDown, altDown) }
            ExtraKey("PGDN") { terminalView.sendKey(KeyEvent.KEYCODE_PAGE_DOWN, controlDown, altDown) }
        }
        ExtraKeyRow {
            ExtraKey("KEYB") { terminalView?.let { showKeyboard(it.context, it) } }
            ExtraKey("BKSP") { terminalView.sendKey(KeyEvent.KEYCODE_DEL, controlDown, altDown) }
            ExtraKey("DEL") { terminalView.sendKey(KeyEvent.KEYCODE_FORWARD_DEL, controlDown, altDown) }
            ExtraKey("INS") { terminalView.sendKey(KeyEvent.KEYCODE_INSERT, controlDown, altDown) }
            ExtraKey("LEFT") { terminalView.sendKey(KeyEvent.KEYCODE_DPAD_LEFT, controlDown, altDown) }
            ExtraKey("DOWN") { terminalView.sendKey(KeyEvent.KEYCODE_DPAD_DOWN, controlDown, altDown) }
            ExtraKey("UP") { terminalView.sendKey(KeyEvent.KEYCODE_DPAD_UP, controlDown, altDown) }
            ExtraKey("RIGHT") { terminalView.sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, controlDown, altDown) }
            ExtraKey("ENTER") { terminalSession?.write("\r") }
        }
    }
}

@Composable
private fun ExtraKeyRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
        Spacer(Modifier.width(1.dp))
    }
}

@Composable
private fun ExtraKey(
    label: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .heightIn(min = 32.dp)
            .background(
                color = if (active) AetherOnSurfaceVariant.copy(alpha = 0.28f) else AetherSurfaceHigher,
                shape = RoundedCornerShape(6.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (active) AetherOnSurface else AetherOnSurfaceVariant,
            fontSize = 12.sp,
        )
    }
}

private class AetherTerminalSessionClient(
    private val context: Context,
    private val invalidateTerminal: () -> Unit,
    private val updateTitle: (String) -> Unit,
    private val onCursorStateChange: (Boolean) -> Unit = {},
) : TerminalSessionClient {
    override fun onTextChanged(changedSession: TerminalSession) {
        invalidateTerminal()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        updateTitle(changedSession.title.orEmpty())
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        invalidateTerminal()
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        if (text.isNotEmpty()) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            session?.write(bytes, 0, bytes.size)
        }
    }

    override fun onBell(session: TerminalSession) = Unit

    override fun onColorsChanged(session: TerminalSession) {
        invalidateTerminal()
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
        onCursorStateChange(state)
    }

    override fun setTerminalShellPid(session: TerminalSession, pid: Int) = Unit

    override fun getTerminalCursorStyle(): Int? = TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK

    override fun logError(tag: String, message: String) {
        Log.e(tag, message)
    }

    override fun logWarn(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun logInfo(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun logDebug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun logVerbose(tag: String, message: String) {
        Log.v(tag, message)
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.e(tag, e.message, e)
    }
}

private class AetherTerminalViewClient(
    private val terminalView: TerminalView,
) : TerminalViewClient {
    override fun onScale(scale: Float): Float = scale.coerceIn(0.7f, 1.6f)

    override fun onSingleTapUp(e: MotionEvent) {
        showKeyboard(terminalView.context, terminalView)
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = true
    override fun isTerminalViewSelected(): Boolean = terminalView.hasFocus()
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false
    override fun readControlKey(): Boolean = false
    override fun readAltKey(): Boolean = false
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
    override fun onEmulatorSet() = Unit
    override fun logError(tag: String, message: String) {
        Log.e(tag, message)
    }

    override fun logWarn(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun logInfo(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun logDebug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun logVerbose(tag: String, message: String) {
        Log.v(tag, message)
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.e(tag, e.message, e)
    }
}

private class TerminalInvalidateCoalescer(
    private val view: TerminalView,
) {
    private val invalidate = Runnable { view.onScreenUpdated() }

    fun request() {
        view.removeCallbacks(invalidate)
        view.postOnAnimation(invalidate)
    }
}

private fun showKeyboard(
    context: Context,
    view: android.view.View,
) {
    view.post {
        view.requestFocus()
        val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }
}

private fun defaultTermuxTerminalTextSizePx(context: Context): Int {
    val dipInPixels = context.resources.displayMetrics.density
    val minFontSize = (4f * dipInPixels).toInt()
    var fontSize = (12f * dipInPixels).roundToInt()
    if (fontSize % 2 == 1) fontSize--
    return fontSize.coerceIn(minFontSize, 256)
}

private fun embeddedTerminalTypeface(context: Context): Typeface =
    runCatching {
        Typeface.createFromAsset(context.assets, "fonts/JetBrainsMono-Regular.ttf")
    }.getOrElse {
        systemTerminalTypeface()
    }

private fun systemTerminalTypeface(): Typeface {
    val systemMono = File("/system/fonts/DroidSansMono.ttf")
    return if (systemMono.isFile && systemMono.length() > 0L) {
        Typeface.createFromFile(systemMono)
    } else {
        Typeface.MONOSPACE
    }
}

private fun TerminalView?.sendKey(
    keyCode: Int,
    controlDown: Boolean,
    altDown: Boolean,
) {
    val view = this ?: return
    val keyMod = buildKeyMod(controlDown, altDown)
    if (!view.handleKeyCode(keyCode, keyMod)) {
        val metaState = buildMetaState(controlDown, altDown)
        val event = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, metaState)
        view.onKeyDown(keyCode, event)
    }
    view.requestFocus()
}

private fun TerminalView?.sendText(
    text: String,
    controlDown: Boolean,
    altDown: Boolean,
) {
    val view = this ?: return
    text.codePoints().forEach { codePoint ->
        view.inputCodePoint(TerminalView.KEY_EVENT_SOURCE_VIRTUAL_KEYBOARD, codePoint, controlDown, altDown)
    }
    view.requestFocus()
}

private fun buildKeyMod(
    controlDown: Boolean,
    altDown: Boolean,
): Int {
    var keyMod = 0
    if (controlDown) keyMod = keyMod or KeyHandler.KEYMOD_CTRL
    if (altDown) keyMod = keyMod or KeyHandler.KEYMOD_ALT
    return keyMod
}

private fun buildMetaState(
    controlDown: Boolean,
    altDown: Boolean,
): Int {
    var metaState = 0
    if (controlDown) metaState = metaState or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
    if (altDown) metaState = metaState or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
    return metaState
}
