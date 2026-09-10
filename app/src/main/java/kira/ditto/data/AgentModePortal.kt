package kira.ditto.data

import org.json.JSONArray
import org.json.JSONObject

internal data class GuiSnapshot(
    val id: String,
    val displayId: Int,
    val width: Int,
    val height: Int,
    val packageName: String,
    val fingerprint: Long,
    val nodes: List<AgentModeUiTree.RichNode>,
)

internal object AgentModePortal {
    fun parseNodes(array: JSONArray?): List<AgentModeUiTree.RichNode> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val node = array.optJSONObject(index) ?: continue
                val dumpIndex = if (node.has("index")) {
                    node.optInt("index")
                } else {
                    val numbered = node.optInt("i", index)
                    if (numbered >= 1) numbered - 1 else numbered
                }
                add(
                    AgentModeUiTree.RichNode(
                        index = dumpIndex,
                        label = node.optString("label"),
                        text = node.optString("text"),
                        desc = node.optString("desc"),
                        hint = node.optString("hint"),
                        klass = node.optString("class"),
                        id = node.optString("id"),
                        clickable = node.optBoolean("clickable"),
                        longClickable = node.optBoolean("long_clickable"),
                        checkable = node.optBoolean("checkable"),
                        checked = node.optBoolean("checked"),
                        selected = node.optBoolean("selected"),
                        enabled = node.optBoolean("enabled", true),
                        scrollable = node.optBoolean("scrollable"),
                        focused = node.optBoolean("focused"),
                        editable = node.optBoolean("editable"),
                        left = node.optInt("left"),
                        top = node.optInt("top"),
                        right = node.optInt("right"),
                        bottom = node.optInt("bottom"),
                        parent = node.optInt("parent", -1),
                        depth = node.optInt("depth"),
                        drawingOrder = node.optInt("drawing_order"),
                        password = node.optBoolean("password"),
                        packageName = node.optString("package_name"),
                        windowType = node.optInt("window_type"),
                        windowIndex = node.optInt("window_index"),
                    ),
                )
            }
        }
    }

    fun toNodeJson(node: AgentModeUiTree.RichNode): JSONObject = JSONObject()
        .put("i", node.clickIndex)
        .put("index", node.index)
        .put("label", node.label)
        .put("text", node.text)
        .put("desc", node.desc)
        .put("hint", node.hint)
        .put("class", node.klass)
        .put("id", node.id)
        .put("clickable", node.clickable)
        .put("long_clickable", node.longClickable)
        .put("checkable", node.checkable)
        .put("checked", node.checked)
        .put("selected", node.selected)
        .put("enabled", node.enabled)
        .put("scrollable", node.scrollable)
        .put("focused", node.focused)
        .put("editable", node.editable)
        .put("password", node.password)
        .put("package_name", node.packageName)
        .put("left", node.left)
        .put("top", node.top)
        .put("right", node.right)
        .put("bottom", node.bottom)
        .put("bounds", node.bounds)
        .put("x", node.centerX)
        .put("y", node.centerY)
        .put("parent", node.parent)
        .put("depth", node.depth)
        .put("drawing_order", node.drawingOrder)
        .put("window_type", node.windowType)
        .put("window_index", node.windowIndex)

    fun interactiveJson(nodes: List<AgentModeUiTree.RichNode>): JSONArray {
        val array = JSONArray()
        AgentModeUiTree.interactiveNodes(nodes).forEach { node ->
            array.put(
                toNodeJson(node)
                    .put("kind", if (node.editable) "input" else "tap"),
            )
        }
        return array
    }

    data class SnapshotDumpSpec(
        val dumpTree: Boolean,
        val waitStable: Boolean,
        val cheap: Boolean,
        val query: String,
        val limit: Int,
    )

    fun snapshotDumpSpec(action: String, skipCapture: Boolean): SnapshotDumpSpec {
        val normalized = action.trim().lowercase()
        if (normalized == "wait_idle") {
            return SnapshotDumpSpec(
                dumpTree = false,
                waitStable = true,
                cheap = false,
                query = "",
                limit = 0,
            )
        }
        if (normalized == "dump_tree" || normalized == "list_targets" || normalized == "targets" ||
            normalized == "click_targets"
        ) {
            return SnapshotDumpSpec(
                dumpTree = true,
                waitStable = false,
                cheap = false,
                query = "",
                limit = AgentModeUiTree.MaxDumpLimit,
            )
        }
        if (skipCapture) {
            return SnapshotDumpSpec(
                dumpTree = true,
                waitStable = false,
                cheap = true,
                query = AgentModeUiTree.CheapDumpQuery,
                limit = AgentModeUiTree.DefaultTargetLimit,
            )
        }
        return SnapshotDumpSpec(
            dumpTree = true,
            waitStable = false,
            cheap = false,
            query = "",
            limit = AgentModeUiTree.MaxDumpLimit,
        )
    }

    fun shouldSkipRelaunch(targetPackage: String, visiblePackage: String): Boolean {
        val target = targetPackage.trim()
        val visible = visiblePackage.trim()
        return target.isNotEmpty() && target.equals(visible, ignoreCase = true)
    }

    fun clickQueryForNode(query: String, target: AgentModeUiTree.RichNode?): String {
        if (target == null) return query
        if (target.id.isNotBlank()) return target.id
        if (target.label.isNotBlank()) return target.label
        return query
    }

    fun resolveTarget(
        nodes: List<AgentModeUiTree.RichNode>,
        query: String,
        previous: AgentModeUiTree.RichNode? = null,
    ): AgentModeUiTree.RichNode? {
        previous?.let { AgentModeUiTree.findSameControl(nodes, it) }?.let { return it }
        findByIndexQuery(nodes, query)?.let { return it }
        return nodes.firstOrNull { it.matches(query) }
    }

    fun nodesFromReceipt(result: JSONObject): List<AgentModeUiTree.RichNode> {
        parseNodes(result.optJSONArray("nodes")).takeIf { it.isNotEmpty() }?.let { return it }
        return parseNodes(result.optJSONArray("interactive"))
    }

    fun clickTargetsText(nodes: List<AgentModeUiTree.RichNode>): String =
        AgentModeUiTree.interactiveNodes(nodes).joinToString("\n") { node ->
            AgentModeUiTree.compactLine(node)
        }

    fun buildSnapshot(
        displayId: Int,
        width: Int,
        height: Int,
        packageName: String,
        nodes: List<AgentModeUiTree.RichNode>,
    ): GuiSnapshot {
        val fingerprint = AgentModeUiTree.layoutFingerprint(
            nodes.map(AgentModeUiTree::toLayoutNode),
        )
        return GuiSnapshot(
            id = AgentModeUiTree.snapshotId(
                displayId = displayId,
                width = width,
                height = height,
                packageName = packageName,
                fingerprint = fingerprint,
                nodeCount = nodes.size,
            ),
            displayId = displayId,
            width = width,
            height = height,
            packageName = packageName,
            fingerprint = fingerprint,
            nodes = nodes,
        )
    }

    fun findByIndexQuery(nodes: List<AgentModeUiTree.RichNode>, query: String): AgentModeUiTree.RichNode? {
        val numbered = AgentModeUiTree.parseIndexQuery(query) ?: return null
        return nodes.firstOrNull { node ->
            node.clickIndex == numbered || node.index == numbered
        }
    }

    fun shouldCompactReceipt(action: String, skipCapture: Boolean): Boolean {
        val normalized = action.trim().lowercase()
        if (normalized in setOf(
                "dump_tree",
                "list_targets",
                "targets",
                "click_targets",
                "screenshot",
                "start",
            )
        ) {
            return false
        }
        return skipCapture
    }

    fun stripBulkyFieldsForModel(result: JSONObject): JSONObject {
        val receipt = result.optString("receipt")
        val compact = receipt == "compact" ||
            (!result.optBoolean("ok", true) && receipt != "full")
        val deltaOnly = result.optBoolean("delta_budget") ||
            result.optJSONObject("tree_diff")?.optBoolean("changed") == false
        if (!compact && !deltaOnly) return result
        result.remove("interactive")
        result.remove("click_targets")
        result.remove("click_targets_text")
        result.remove("ui_tree")
        return result
    }

    fun attachReceipt(
        result: JSONObject,
        snapshot: GuiSnapshot,
        previous: GuiSnapshot?,
        usedBackend: String = "",
        dispatched: Boolean = true,
        verification: JSONObject? = null,
        target: AgentModeUiTree.RichNode? = null,
        compact: Boolean = false,
    ): JSONObject {
        val diff = AgentModeUiTree.treeDiff(previous?.nodes.orEmpty(), snapshot.nodes, target)
        result.put("snapshot_id", snapshot.id)
        result.put("tree_diff", diff.toJson())
        result.put("windows", windowsJson(snapshot.nodes))
        result.put("receipt", if (compact) "compact" else "full")
        val interactive = interactiveJson(snapshot.nodes)
        val targetsText = clickTargetsText(snapshot.nodes)
        result.put("interactive", interactive)
        result.put("click_targets", interactive)
        result.put("click_targets_text", AgentModeUiTree.wrapUntrustedGuiContent(targetsText))
        result.put("click_target_count", interactive.length())
        result.put("cluttered", AgentModeUiTree.isCluttered(interactive.length()))
        if (structureBlind(snapshot.nodes)) {
            result.put("structure_blind", true)
            result.put(
                "structure_hint",
                "This page exposes almost no accessibility structure (Flutter/game/WebView surface). " +
                    "Use observe=som or coordinate fallback instead of relying on the tree.",
            )
        }
        val deltaLines = AgentModeUiTree.deltaLines(previous?.nodes.orEmpty(), snapshot.nodes)
        if (deltaLines.isNotEmpty()) {
            result.put("ui_delta", JSONArray(deltaLines))
            result.put("ui_delta_text", AgentModeUiTree.wrapUntrustedGuiContent(deltaLines.joinToString("\n")))
        }
        result.put("dispatched", dispatched)
        result.put(
            "planning",
            "tree_diff is informational. Replan the current segment on STALLED/MACRO_MISMATCH/" +
                "CAPTURE_TIMEOUT/DISPLAY_BUSY/SCENE_NOT_SOP/PROTECTED_WINDOW, not after every gesture.",
        )
        val blocked = !result.optBoolean("ok", true) ||
            result.optString("code") in AgentModeSafety.InterruptCodes
        result.put("consistent", if (blocked || diff.blockingOverlay) "no" else "yes")
        if (blocked || diff.blockingOverlay) {
            if (!result.has("degraded_to")) {
                result.put("degraded_to", AgentModeSafety.ReplanDegradedTo)
            }
        }
        if (blocked) {
            GuiFailureSignals.attach(
                result = result,
                action = result.optString("action"),
                snapshot = snapshot,
                previous = previous,
            )
        }
        if (usedBackend.isNotBlank()) result.put("used_backend", usedBackend)
        if (verification != null) result.put("verification", verification)
        if (snapshot.packageName.isNotBlank() && !result.has("package_name")) {
            result.put("package_name", snapshot.packageName)
        }
        val uiTree = result.optString("ui_tree")
        if (uiTree.isNotBlank() && !uiTree.contains(AgentModeUiTree.UntrustedGuiOpen)) {
            result.put("ui_tree", AgentModeUiTree.wrapUntrustedGuiContent(uiTree))
        } else if (uiTree.isBlank() && targetsText.isNotBlank()) {
            result.put("ui_tree", AgentModeUiTree.wrapUntrustedGuiContent(targetsText))
        }
        val stdout = result.optString("stdout")
        if (stdout.isNotBlank() && stdout != "ok" && !stdout.contains(AgentModeUiTree.UntrustedGuiOpen)) {
            if (stdout.contains(" tap ") || stdout.contains("(")) {
                result.put("stdout", AgentModeUiTree.wrapUntrustedGuiContent(stdout))
            }
        }
        return result
    }

    private fun windowsJson(nodes: List<AgentModeUiTree.RichNode>): JSONArray {
        val packages = nodes.map { it.packageName }.filter { it.isNotBlank() }.distinct()
        val array = JSONArray()
        packages.forEachIndexed { index, pkg ->
            array.put(
                JSONObject()
                    .put("id", index)
                    .put("package", pkg)
                    .put("focused", nodes.any { it.packageName == pkg && it.focused }),
            )
        }
        return array
    }

    /**
     * P3-4 structure-blind detection: Flutter/game/WebView pages collapse to a
     * single Surface with almost no interactive nodes. The receipt nudges the
     * agent towards observe=som or coordinate fallback instead of pretending a
     * synthetic tree exists.
     */
    fun structureBlind(nodes: List<AgentModeUiTree.RichNode>): Boolean =
        AgentModeUiTree.isStructureBlind(nodes)
}
