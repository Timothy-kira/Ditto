package kira.ditto.agentmode

import android.app.UiAutomation
import android.graphics.Rect
import android.os.Bundle
import android.os.Build
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.atomic.AtomicReference
import kira.ditto.data.AgentModeUiTree

/**
 * Resident UiAutomation for the Shizuku Agent Mode process. Used for SET_TEXT,
 * compact tree dumps, and hiding the IME on the virtual display without spawning
 * `uiautomator dump` for every gesture.
 */
internal class AgentModeUiAutomation {
    private val lock = Any()
    private val automation = AtomicReference<UiAutomation?>(null)
    @Volatile
    private var looper: Looper? = null

    fun attachLooper(value: Looper) {
        looper = value
    }

    fun ensureConnected(): UiAutomation? {
        automation.get()?.let { return it }
        synchronized(lock) {
            automation.get()?.let { return it }
            val created = connectLocked(looper) ?: return null
            automation.set(created)
            return created
        }
    }

    fun disconnect() {
        synchronized(lock) {
            val current = automation.getAndSet(null) ?: return
            runCatching {
                val disconnect = current.javaClass.methods.firstOrNull {
                    it.name == "disconnect" && it.parameterTypes.isEmpty()
                }
                disconnect?.invoke(current)
            }
        }
    }

    fun setFocusedText(displayId: Int, text: String): Boolean {
        val node = focusedEditable(displayId) ?: return false
        return try {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } finally {
            node.recycle()
        }
    }

    /** IME search/send on the focused field. Returns false so callers can inject ENTER. */
    fun submitFocusedIme(displayId: Int): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val node = focusedEditable(displayId) ?: return false
        return try {
            // AccessibilityNodeInfo.ACTION_IME_ENTER (API 30) = 0x00200000
            node.performAction(0x00200000)
        } finally {
            node.recycle()
        }
    }

    fun focusedText(displayId: Int): String? {
        val node = focusedEditable(displayId) ?: return null
        return try {
            node.text?.toString() ?: node.contentDescription?.toString()
        } finally {
            node.recycle()
        }
    }

    fun packageVisibleOnDisplay(packageName: String, displayId: Int): Boolean {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return false
        val auto = ensureConnected() ?: return false
        return windowsOnDisplayStrict(auto, displayId).any { window ->
            windowRootPackage(window) == pkg
        }
    }

    fun waitUntilPackageVisibleOnDisplay(
        packageName: String,
        displayId: Int,
        timeoutMs: Long,
    ): Boolean {
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (packageVisibleOnDisplay(packageName, displayId)) return true
            android.os.SystemClock.sleep(50L)
        }
        return packageVisibleOnDisplay(packageName, displayId)
    }

    fun imeWindowVisible(displayId: Int): Boolean {
        val ids = imeDisplayIds()
        return displayId in ids
    }

    fun imeDisplayIds(): List<Int> {
        val auto = ensureConnected() ?: return emptyList()
        return windowsOnAllDisplays(auto).mapNotNull { ref ->
            if (!isImeWindow(ref.window)) return@mapNotNull null
            ref.displayId
        }.distinct()
    }

    private fun isImeWindow(window: AccessibilityWindowInfo): Boolean {
        if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return true
        val title = if (Build.VERSION.SDK_INT >= 24) {
            window.title?.toString().orEmpty()
        } else {
            ""
        }
        if (
            title.contains("InputMethod", ignoreCase = true) ||
            title.contains("Input Method", ignoreCase = true)
        ) {
            return true
        }
        val pkg = runCatching {
            val root = window.root ?: return@runCatching ""
            try {
                root.packageName?.toString().orEmpty()
            } finally {
                root.recycle()
            }
        }.getOrNull().orEmpty()
        return pkg.contains("inputmethod", ignoreCase = true) ||
            pkg.contains("ime.", ignoreCase = true) ||
            pkg.endsWith(".ime") ||
            pkg.contains("baidu.input", ignoreCase = true) ||
            pkg.contains("sogou", ignoreCase = true) ||
            pkg.contains("iflytek", ignoreCase = true) ||
            pkg.contains("com.huawei.ohos.inputmethod")
    }

    fun dumpCompactTree(displayId: Int, width: Int, height: Int): String {
        val auto = ensureConnected() ?: return ""
        val roots = windowsOnDisplay(auto, displayId).mapNotNull { it.root }
            .ifEmpty { listOfNotNull(runCatching { auto.rootInActiveWindow }.getOrNull()) }
        val lines = ArrayList<String>(AgentModeUiTree.DefaultDumpLimit)
        try {
            roots.forEach { root ->
                collectCompact(root, width.coerceAtLeast(1), height.coerceAtLeast(1), lines, AgentModeUiTree.DefaultDumpLimit)
            }
        } finally {
            roots.forEach { runCatching { it.recycle() } }
        }
        return lines.joinToString("\n")
    }

    fun layoutFingerprint(displayId: Int): Long {
        val auto = ensureConnected() ?: return 0L
        val windows = windowsOnDisplay(auto, displayId)
        val roots = windows.mapNotNull { it.root }
            .ifEmpty { listOfNotNull(runCatching { auto.rootInActiveWindow }.getOrNull()) }
        val nodes = ArrayList<AgentModeUiTree.LayoutNode>(128)
        var windowKey = windows.size
        windows.forEach { window ->
            windowKey = 31 * windowKey + window.type
        }
        try {
            roots.forEach { root -> collectLayoutNodes(root, nodes, 400) }
        } finally {
            roots.forEach { runCatching { it.recycle() } }
        }
        if (nodes.isEmpty()) return 0L
        return AgentModeUiTree.layoutFingerprint(nodes, windowKey)
    }

    /** Reused when the layout fingerprint is unchanged between two dumps (P3-6). */
    private var lastFullDumpFingerprint: Long = 0L
    private var lastFullDumpNodes: List<AgentModeUiTree.RichNode> = emptyList()

    fun dumpRichNodesIncremental(
        displayId: Int,
        width: Int,
        height: Int,
        maxNodes: Int = AgentModeUiTree.MaxDumpLimit,
        interactiveOnly: Boolean = false,
    ): List<AgentModeUiTree.RichNode> {
        val fingerprint = runCatching { layoutFingerprint(displayId) }.getOrDefault(0L)
        if (fingerprint != 0L &&
            fingerprint == lastFullDumpFingerprint &&
            lastFullDumpNodes.isNotEmpty()
        ) {
            return lastFullDumpNodes
        }
        val nodes = dumpRichNodes(displayId, width, height, maxNodes, interactiveOnly)
        if (fingerprint != 0L) {
            lastFullDumpFingerprint = fingerprint
            lastFullDumpNodes = nodes
        }
        return nodes
    }

    fun waitUntilLayoutStable(displayId: Int, quietMs: Int, timeoutMs: Int): Boolean {
        val quiet = quietMs.coerceIn(16, 400)
        val timeout = timeoutMs.coerceIn(80, 2_000)
        val first = runCatching { layoutFingerprint(displayId) }.getOrDefault(0L)
        android.os.SystemClock.sleep(minOf(quiet, timeout, 80).toLong())
        val second = runCatching { layoutFingerprint(displayId) }.getOrDefault(0L)
        return first != 0L && first == second
    }

    fun dumpRichNodes(
        displayId: Int,
        width: Int,
        height: Int,
        maxNodes: Int = AgentModeUiTree.MaxDumpLimit,
        interactiveOnly: Boolean = false,
    ): List<AgentModeUiTree.RichNode> {
        val auto = ensureConnected() ?: return emptyList()
        val windows = windowsOnDisplay(auto, displayId)
        val out = ArrayList<AgentModeUiTree.RichNode>(256)
        val limit = maxNodes.coerceIn(1, AgentModeUiTree.MaxDumpLimit)
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        if (windows.isNotEmpty()) {
            windows.forEachIndexed { windowIndex, window ->
                val root = window.root ?: return@forEachIndexed
                try {
                    collectRich(
                        node = root,
                        width = w,
                        height = h,
                        out = out,
                        parent = -1,
                        depth = 0,
                        maxNodes = limit,
                        interactiveOnly = interactiveOnly,
                        windowType = window.type,
                        windowIndex = windowIndex,
                    )
                } finally {
                    runCatching { root.recycle() }
                }
            }
        } else {
            val root = runCatching { auto.rootInActiveWindow }.getOrNull() ?: return emptyList()
            try {
                collectRich(
                    node = root,
                    width = w,
                    height = h,
                    out = out,
                    parent = -1,
                    depth = 0,
                    maxNodes = limit,
                    interactiveOnly = interactiveOnly,
                    windowType = 0,
                    windowIndex = 0,
                )
            } finally {
                runCatching { root.recycle() }
            }
        }
        return AgentModeUiTree.pruneRich(out)
    }

    fun hasWindowOnDisplay(displayId: Int): Boolean {
        val auto = ensureConnected() ?: return false
        return windowsOnDisplayStrict(auto, displayId).isNotEmpty()
    }

    fun clickByQuery(displayId: Int, query: String): Boolean {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return false
        val auto = ensureConnected() ?: return false
        val numbered = AgentModeUiTree.parseIndexQuery(trimmed)
        if (numbered != null) {
            return clickByWalkedIndex(displayId, numbered)
        }
        val roots = windowsForMutation(auto, displayId).mapNotNull { it.root }
        try {
            for (root in roots) {
                findNode(root, query.trim())?.let { node ->
                    return try {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    } finally {
                        node.recycle()
                    }
                }
            }
        } finally {
            roots.forEach { runCatching { it.recycle() } }
        }
        return false
    }

    private fun clickByWalkedIndex(displayId: Int, clickIndex: Int): Boolean {
        val wanted = if (clickIndex >= 1) clickIndex - 1 else clickIndex
        if (wanted < 0) return false
        val auto = ensureConnected() ?: return false
        val roots = windowsForMutation(auto, displayId).mapNotNull { it.root }
        var current = 0
        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (current == wanted) return AccessibilityNodeInfo.obtain(node)
            current += 1
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                val found = try {
                    walk(child)
                } finally {
                    child.recycle()
                }
                if (found != null) return found
            }
            return null
        }
        try {
            for (root in roots) {
                walk(root)?.let { node ->
                    return try {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    } finally {
                        node.recycle()
                    }
                }
            }
        } finally {
            roots.forEach { runCatching { it.recycle() } }
        }
        return false
    }

    fun clickRichNode(displayId: Int, target: AgentModeUiTree.RichNode): Boolean {
        val auto = ensureConnected() ?: return false
        val roots = windowsForMutation(auto, displayId).mapNotNull { it.root }
        try {
            for (root in roots) {
                findNodeByRich(root, target)?.let { node ->
                    return try {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    } finally {
                        node.recycle()
                    }
                }
            }
        } finally {
            roots.forEach { runCatching { it.recycle() } }
        }
        return false
    }

    fun waitForLabel(displayId: Int, label: String, timeoutMs: Int): Boolean {
        val wanted = label.trim()
        if (wanted.isBlank()) return false
        val timeout = timeoutMs.coerceIn(80, 8_000)
        val deadline = android.os.SystemClock.uptimeMillis() + timeout
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            val auto = ensureConnected() ?: return false
            val roots = windowsOnDisplay(auto, displayId).mapNotNull { it.root }
                .ifEmpty { listOfNotNull(runCatching { auto.rootInActiveWindow }.getOrNull()) }
            try {
                if (roots.any { containsLabel(it, wanted) }) return true
            } finally {
                roots.forEach { runCatching { it.recycle() } }
            }
            android.os.SystemClock.sleep(50L)
        }
        return false
    }

    private fun collectLayoutNodes(
        node: AccessibilityNodeInfo,
        out: MutableList<AgentModeUiTree.LayoutNode>,
        maxNodes: Int,
    ) {
        if (out.size >= maxNodes) return
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.isEmpty) {
            val klass = node.className?.toString().orEmpty().substringAfterLast('.')
            val id = node.viewIdResourceName.orEmpty().substringAfterLast('/')
            val label = node.text?.toString().orEmpty().ifBlank {
                node.contentDescription?.toString().orEmpty()
            }
            if (
                !AgentModeUiTree.isIgnoredMedia(
                    klass = klass,
                    id = id,
                    interactive = node.isClickable || node.isLongClickable || node.isCheckable || node.isEditable,
                    label = label,
                )
            ) {
                out += AgentModeUiTree.LayoutNode(
                    klass = klass,
                    id = id,
                    left = bounds.left,
                    top = bounds.top,
                    right = bounds.right,
                    bottom = bounds.bottom,
                    clickable = node.isClickable,
                    childCount = node.childCount,
                    label = label,
                )
            }
        }
        for (index in 0 until node.childCount) {
            if (out.size >= maxNodes) return
            val child = node.getChild(index) ?: continue
            try {
                collectLayoutNodes(child, out, maxNodes)
            } finally {
                child.recycle()
            }
        }
    }

    private fun focusedEditable(displayId: Int): AccessibilityNodeInfo? {
        val auto = ensureConnected() ?: return null
        val roots = windowsForMutation(auto, displayId).mapNotNull { it.root }
        try {
            for (root in roots) {
                findFocusedEditable(root)?.let { return it }
            }
        } finally {
            roots.forEach { node -> runCatching { node.recycle() } }
        }
        return null
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused && (node.isEditable || node.isPassword || node.className?.contains("EditText") == true)) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = findFocusedEditable(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun collectCompact(
        node: AccessibilityNodeInfo,
        width: Int,
        height: Int,
        lines: MutableList<String>,
        maxLines: Int,
    ) {
        if (lines.size >= maxLines) return
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val klass = node.className?.toString().orEmpty().substringAfterLast('.')
        val id = node.viewIdResourceName.orEmpty().substringAfterLast('/')
        val clickable = node.isClickable
        val focused = node.isFocused
        if (
            AgentModeUiTree.shouldEmitCompact(
                klass = klass,
                text = text,
                desc = desc,
                id = id,
                clickable = clickable || node.isCheckable,
                focused = focused,
            )
        ) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val nx1 = bounds.left * 1000 / width
            val ny1 = bounds.top * 1000 / height
            val nx2 = bounds.right * 1000 / width
            val ny2 = bounds.bottom * 1000 / height
            val label = text.ifBlank { desc }.ifBlank { id }.ifBlank { klass.ifBlank { "view" } }
            val flags = buildString {
                if (clickable || node.isCheckable) append(" tap")
                if (focused) append(" focus")
            }
            val idSuffix = if (id.isNotBlank()) " #$id" else ""
            lines += "$label$flags ($nx1,$ny1)-($nx2,$ny2)$idSuffix"
        }
        for (index in 0 until node.childCount) {
            if (lines.size >= maxLines) return
            val child = node.getChild(index) ?: continue
            try {
                collectCompact(child, width, height, lines, maxLines)
            } finally {
                child.recycle()
            }
        }
    }

    private fun collectRich(
        node: AccessibilityNodeInfo,
        width: Int,
        height: Int,
        out: MutableList<AgentModeUiTree.RichNode>,
        parent: Int,
        depth: Int,
        maxNodes: Int,
        interactiveOnly: Boolean,
        windowType: Int,
        windowIndex: Int,
    ) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val hint = if (android.os.Build.VERSION.SDK_INT >= 26) {
            node.hintText?.toString().orEmpty()
        } else {
            ""
        }
        val klass = node.className?.toString().orEmpty().substringAfterLast('.')
        val id = node.viewIdResourceName.orEmpty().substringAfterLast('/')
        val candidate = AgentModeUiTree.RichNode(
            index = out.size,
            label = text.ifBlank { desc }.ifBlank { id }.ifBlank { klass.ifBlank { "view" } },
            text = text,
            desc = desc,
            hint = hint,
            klass = klass,
            id = id,
            clickable = node.isClickable,
            longClickable = node.isLongClickable,
            checkable = node.isCheckable,
            checked = node.isChecked,
            selected = node.isSelected,
            enabled = node.isEnabled,
            scrollable = node.isScrollable,
            focused = node.isFocused,
            editable = node.isEditable,
            left = bounds.left * 1000 / width.coerceAtLeast(1),
            top = bounds.top * 1000 / height.coerceAtLeast(1),
            right = bounds.right * 1000 / width.coerceAtLeast(1),
            bottom = bounds.bottom * 1000 / height.coerceAtLeast(1),
            parent = parent,
            depth = depth,
            drawingOrder = if (android.os.Build.VERSION.SDK_INT >= 24) node.drawingOrder else 0,
            password = node.isPassword,
            packageName = node.packageName?.toString().orEmpty(),
            windowType = windowType,
            windowIndex = windowIndex,
        )
        val overlayWindow = AgentModeUiTree.isOverlayWindowType(windowType)
        val keep = if (!interactiveOnly) {
            out.size < maxNodes
        } else {
            overlayWindow || AgentModeUiTree.shouldKeepForCheapDump(candidate)
        }
        val selfIndex = if (keep && (out.size < maxNodes || overlayWindow && interactiveOnly)) {
            val index = out.size
            out += candidate.copy(index = index)
            index
        } else {
            parent
        }
        if (!interactiveOnly && out.size >= maxNodes) return
        for (index in 0 until node.childCount) {
            if (!interactiveOnly && out.size >= maxNodes) return
            val child = node.getChild(index) ?: continue
            try {
                collectRich(
                    node = child,
                    width = width,
                    height = height,
                    out = out,
                    parent = selfIndex,
                    depth = depth + 1,
                    maxNodes = maxNodes,
                    interactiveOnly = interactiveOnly,
                    windowType = windowType,
                    windowIndex = windowIndex,
                )
            } finally {
                child.recycle()
            }
        }
    }

    private fun findNodeByRich(
        node: AccessibilityNodeInfo,
        target: AgentModeUiTree.RichNode,
    ): AccessibilityNodeInfo? {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val id = node.viewIdResourceName.orEmpty().substringAfterLast('/')
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val label = text.ifBlank { desc }.ifBlank { id }
        val idMatch = target.id.isNotBlank() && id.equals(target.id, ignoreCase = true)
        val labelMatch = target.label.isNotBlank() && label.equals(target.label, ignoreCase = true)
        if (idMatch || (labelMatch && target.id.isBlank())) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = findNodeByRich(child, target)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun findNode(node: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        if (query.isBlank()) return null
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val id = node.viewIdResourceName.orEmpty().substringAfterLast('/')
        val needle = query.removePrefix("#")
        if (
            text.equals(query, ignoreCase = true) ||
            desc.equals(query, ignoreCase = true) ||
            id.equals(query, ignoreCase = true) ||
            id.equals(needle, ignoreCase = true) ||
            text.contains(query, ignoreCase = true) ||
            desc.contains(query, ignoreCase = true) ||
            (needle.isNotBlank() && id.contains(needle, ignoreCase = true))
        ) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = findNode(child, query)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun containsLabel(node: AccessibilityNodeInfo, label: String): Boolean {
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        if (text.contains(label, ignoreCase = true) || desc.contains(label, ignoreCase = true)) {
            return true
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = containsLabel(child, label)
            child.recycle()
            if (found) return true
        }
        return false
    }

    private fun windowsOnDisplay(auto: UiAutomation, displayId: Int): List<AccessibilityWindowInfo> {
        val matching = windowsOnDisplayStrict(auto, displayId)
        if (matching.isNotEmpty()) return matching
        if (displayId == Display.DEFAULT_DISPLAY) {
            return windowsOnAllDisplays(auto).map { it.window }
        }
        return emptyList()
    }
    private fun windowsForMutation(
        auto: UiAutomation,
        displayId: Int,
    ): List<AccessibilityWindowInfo> = windowsOnDisplayStrict(auto, displayId)

    /**
     * Windows whose Accessibility display id equals [displayId]. Never falls
     * back to the real screen: a Huawei virtual display can report WeChat as a
     * task while the only painted window is still the boot earth wallpaper.
     */
    private fun windowsOnDisplayStrict(
        auto: UiAutomation,
        displayId: Int,
    ): List<AccessibilityWindowInfo> {
        return windowsOnAllDisplays(auto)
            .filter { ref -> ref.displayId == displayId }
            .map { it.window }
    }

    private data class WindowOnDisplay(
        val displayId: Int,
        val window: AccessibilityWindowInfo,
    )

    companion object {
        private const val LogTag = "AetherUiAuto"

        /**
         * Pure P0-2 policy: given (displayId, matchedOnTargetDisplay, allDisplays),
         * mutation paths only ever see the strict target-display set. Read-only
         * dumps may fall back to the default display only when the requested
         * display *is* the default display.
         */
        internal fun selectWindowsForMutation(
            displayId: Int,
            strict: List<AccessibilityWindowInfo>,
        ): List<AccessibilityWindowInfo> = strict

        internal fun selectWindowsForRead(
            displayId: Int,
            strict: List<AccessibilityWindowInfo>,
            allDisplays: List<AccessibilityWindowInfo>,
        ): List<AccessibilityWindowInfo> = when {
            strict.isNotEmpty() -> strict
            displayId == Display.DEFAULT_DISPLAY -> allDisplays
            else -> emptyList()
        }
    }

    private fun windowRootPackage(window: AccessibilityWindowInfo): String {
        return runCatching {
            val root = window.root ?: return@runCatching ""
            try {
                root.packageName?.toString().orEmpty()
            } finally {
                root.recycle()
            }
        }.getOrNull().orEmpty()
    }

    private fun windowsOnAllDisplays(auto: UiAutomation): List<WindowOnDisplay> {
        val viaAll = runCatching {
            val method = auto.javaClass.methods.firstOrNull { candidate ->
                candidate.name == "getWindowsOnAllDisplays" && candidate.parameterTypes.isEmpty()
            } ?: return@runCatching emptyList()
            val sparse = method.invoke(auto)
            if (sparse !is android.util.SparseArray<*>) return@runCatching emptyList()
            val out = ArrayList<WindowOnDisplay>()
            for (index in 0 until sparse.size()) {
                val list = sparse.valueAt(index) as? List<*> ?: continue
                list.forEach { item ->
                    if (item is AccessibilityWindowInfo) {
                        val reported = windowDisplayId(item)
                        out += WindowOnDisplay(
                            displayId = reported,
                            window = item,
                        )
                    }
                }
            }
            out
        }.getOrNull().orEmpty()
        if (viaAll.isNotEmpty()) return viaAll
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            auto.javaClass.methods.firstOrNull { method ->
                method.name == "getWindows" && method.parameterTypes.isEmpty()
            }?.invoke(auto) as? List<AccessibilityWindowInfo>
        }.getOrNull().orEmpty().map { window ->
            WindowOnDisplay(displayId = windowDisplayId(window), window = window)
        }
    }

    private fun windowDisplayId(window: AccessibilityWindowInfo): Int {
        return runCatching {
            val method = window.javaClass.methods.firstOrNull {
                it.name == "getDisplayId" && it.parameterTypes.isEmpty()
            }
            method?.invoke(window) as? Int
        }.getOrNull() ?: Display.DEFAULT_DISPLAY
    }

    private fun connectLocked(preferredLooper: Looper?): UiAutomation? {
        return runCatching {
            val connectionClass = Class.forName("android.app.UiAutomationConnection")
            val connection = connectionClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            val interfaceClass = Class.forName("android.app.IUiAutomationConnection")
            val constructor = UiAutomation::class.java.getDeclaredConstructor(
                Looper::class.java,
                interfaceClass,
            ).apply { isAccessible = true }
            val looper = preferredLooper ?: Looper.getMainLooper() ?: Looper.myLooper()
                ?: error("UiAutomation requires a Looper")
            val created = constructor.newInstance(looper, connection) as UiAutomation
            val dontSuppress = runCatching {
                UiAutomation::class.java.getField("FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES").getInt(null)
            }.getOrDefault(0)
            val connectWithFlags = created.javaClass.methods.firstOrNull { method ->
                method.name == "connect" && method.parameterTypes.size == 1
            }
            if (connectWithFlags != null) {
                connectWithFlags.invoke(created, dontSuppress)
            } else {
                created.javaClass.getMethod("connect").invoke(created)
            }
            created
        }.onFailure { error ->
            Log.w(LogTag, "UiAutomation connect failed: ${error.message?.take(180)}")
        }.getOrNull()
    }
}
