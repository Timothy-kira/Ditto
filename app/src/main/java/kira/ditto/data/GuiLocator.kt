package kira.ditto.data

import org.json.JSONObject

/**
 * Layered control identity for replay. Identity is the widget, not today's
 * hot-search text, like-count, video title, or theme color.
 */
data class GuiLocator(
    val resourceId: String = "",
    val klass: String = "",
    val hint: String = "",
    val staticDesc: String = "",
    val editable: Boolean = false,
    val region: String = "",
    val relative: String = "",
    val shape: String = "",
    val role: String = "",
) {
    fun isEmpty(): Boolean =
        resourceId.isBlank() &&
            klass.isBlank() &&
            hint.isBlank() &&
            staticDesc.isBlank() &&
            relative.isBlank() &&
            role.isBlank()

    fun recipe(): String = buildString {
        if (role.isNotBlank()) append(role)
        if (klass.isNotBlank()) {
            if (isNotEmpty()) append(" ")
            append(klass)
        }
        if (resourceId.isNotBlank()) {
            if (isNotEmpty()) append(" #")
            append(resourceId)
        }
        if (region.isNotBlank()) {
            if (isNotEmpty()) append(" @")
            append(region)
        }
        if (relative.isNotBlank()) {
            if (isNotEmpty()) append(" (")
            append(relative)
            append(")")
        }
        if (staticDesc.isNotBlank()) {
            if (isNotEmpty()) append(" ")
            append("「").append(staticDesc).append("」")
        }
    }.ifBlank { "" }

    fun toJson(): JSONObject = JSONObject()
        .put("resource_id", resourceId)
        .put("class", klass)
        .put("hint", hint)
        .put("static_desc", staticDesc)
        .put("editable", editable)
        .put("region", region)
        .put("relative", relative)
        .put("shape", shape)
        .put("role", role)

    companion object {
        val Empty = GuiLocator()

        fun fromJson(json: JSONObject?): GuiLocator {
            if (json == null) return Empty
            return GuiLocator(
                resourceId = json.optString("resource_id"),
                klass = json.optString("class"),
                hint = json.optString("hint"),
                staticDesc = json.optString("static_desc"),
                editable = json.optBoolean("editable"),
                region = json.optString("region"),
                relative = json.optString("relative"),
                shape = json.optString("shape"),
                role = json.optString("role"),
            )
        }
    }
}

internal object GuiLocatorCodec {
    fun regionFor(centerY: Int): String = when {
        centerY < 334 -> "top"
        centerY > 666 -> "bottom"
        else -> "middle"
    }

    fun isVolatileContent(label: String): Boolean {
        val value = label.trim()
        if (value.isBlank()) return false
        if (value.contains("热搜")) return true
        if (value.all { it.isDigit() || it == '.' || it == '万' || it == '亿' || it == 'w' || it == 'W' }) {
            return value.any { it.isDigit() }
        }
        if (value.length >= 12 && !isStaticChrome(value)) return true
        return false
    }

    fun isStaticChrome(label: String): Boolean {
        val value = label.trim()
        if (value.isBlank()) return false
        return StaticChrome.any { token ->
            value.equals(token, ignoreCase = true) ||
                (token.length >= 2 && value.contains(token, ignoreCase = true) && value.length <= token.length + 4)
        }
    }

    fun isSearchChrome(
        label: String,
        resourceId: String,
        klass: String,
        region: String,
        editable: Boolean,
    ): Boolean {
        val id = resourceId.lowercase()
        val k = klass.lowercase()
        if (id.contains("search")) return true
        if (k.contains("edittext") || k.contains("searchview") || k.contains("autocompletetext")) {
            return region == "top" || editable
        }
        if (editable && region == "top") return true
        if (region == "top" && (label.contains("热搜") || label.contains("搜索"))) return true
        if (label.contains("搜索") && !isVolatileContent(label)) return true
        return false
    }

    fun isLikeChrome(label: String, resourceId: String, region: String): Boolean {
        val id = resourceId.lowercase()
        if (id.contains("like") || id.contains("thumb") || id.contains("zan")) return true
        if (label.equals("点赞", ignoreCase = true) || label.equals("赞", ignoreCase = true)) return true
        if (region == "bottom" && (label.contains("赞") || id.contains("like"))) return true
        if (region == "bottom" && label.isNotBlank() && label.all { it.isDigit() || it == '.' || it == '万' || it == '亿' }) {
            return true
        }
        return false
    }

    fun capture(
        label: String,
        resourceId: String,
        klass: String = "",
        hint: String = "",
        desc: String = "",
        editable: Boolean = false,
        focused: Boolean = false,
        left: Int = 0,
        top: Int = 0,
        right: Int = 0,
        bottom: Int = 0,
    ): GuiLocator {
        val centerY = (top + bottom) / 2
        val region = regionFor(centerY)
        val inferredClass = klass.ifBlank {
            when {
                editable || focused -> "EditText"
                else -> ""
            }
        }
        val search = isSearchChrome(label, resourceId, inferredClass, region, editable || focused)
        val like = isLikeChrome(label, resourceId, region)
        val static = listOf(hint, desc, label)
            .firstOrNull { it.isNotBlank() && !isVolatileContent(it) && isStaticChrome(it) }
            .orEmpty()
            .ifBlank {
                if (!isVolatileContent(hint)) hint else ""
            }
        val role = when {
            search -> "search"
            like -> "like"
            isStaticChrome(label) -> "chrome"
            else -> ""
        }
        val relative = when {
            search -> "top chrome, typically the only EditText"
            like -> "bottom bar like control"
            region.isNotBlank() && inferredClass.isNotBlank() -> "$region $inferredClass"
            else -> ""
        }
        val shape = when {
            search -> "rounded search capsule, magnifier plus field"
            like -> "icon plus label like button"
            else -> ""
        }
        return GuiLocator(
            resourceId = resourceId,
            klass = inferredClass,
            hint = if (isVolatileContent(hint)) "" else hint,
            staticDesc = static,
            editable = editable || focused,
            region = region,
            relative = relative,
            shape = shape,
            role = role,
        )
    }

    fun resolve(nodes: List<AgentModeUiTree.RichNode>, locator: GuiLocator): AgentModeUiTree.RichNode? {
        if (locator.isEmpty() || nodes.isEmpty()) return null
        if (locator.resourceId.isNotBlank()) {
            nodes.firstOrNull { node ->
                node.id.equals(locator.resourceId, ignoreCase = true) ||
                    node.id.equals(locator.resourceId.substringAfterLast('/'), ignoreCase = true)
            }?.let { return it }
        }
        val static = locator.staticDesc.ifBlank { locator.hint }
        if (static.isNotBlank() && !isVolatileContent(static)) {
            nodes.firstOrNull { node ->
                listOf(node.label, node.text, node.desc, node.hint).any { field ->
                    field.equals(static, ignoreCase = true) ||
                        (static.length >= 2 && field.contains(static, ignoreCase = true) && !isVolatileContent(field))
                }
            }?.let { return it }
        }
        if (locator.klass.isNotBlank() && locator.region.isNotBlank()) {
            nodes.firstOrNull { node ->
                node.klass.equals(locator.klass, ignoreCase = true) &&
                    regionFor(node.centerY) == locator.region &&
                    (locator.editable.not() || node.editable || node.focused)
            }?.let { return it }
        }
        if (locator.role == "search") {
            nodes.firstOrNull { node ->
                isSearchChrome(
                    label = node.label,
                    resourceId = node.id,
                    klass = node.klass,
                    region = regionFor(node.centerY),
                    editable = node.editable || node.focused,
                )
            }?.let { return it }
        }
        if (locator.role == "like") {
            nodes.firstOrNull { node ->
                isLikeChrome(node.label.ifBlank { node.desc }, node.id, regionFor(node.centerY))
            }?.let { return it }
        }
        return null
    }

    fun resolveFromCompact(compact: String, locator: GuiLocator): AgentModeUiTree.RichNode? =
        resolve(nodesFromCompact(compact), locator)

    fun nodesFromCompact(compact: String): List<AgentModeUiTree.RichNode> {
        val bounds = Regex("""\((\d+),(\d+)\)-\((\d+),(\d+)\)""")
        return compact.lineSequence().mapNotNull { raw ->
            val line = raw.trim()
            val match = bounds.find(line) ?: return@mapNotNull null
            val prefix = line.substring(0, match.range.first).trim()
            val focused = prefix.endsWith(" focus") || prefix.contains(" focus ")
            val tap = prefix.contains(" tap")
            val label = prefix.removeSuffix(" focus").removeSuffix(" tap").trim()
            val after = line.substring(match.range.last + 1).trim()
            val id = after.removePrefix("#").trim()
            val left = match.groupValues[1].toInt()
            val top = match.groupValues[2].toInt()
            val right = match.groupValues[3].toInt()
            val bottom = match.groupValues[4].toInt()
            AgentModeUiTree.RichNode(
                index = 0,
                label = label,
                text = label,
                desc = "",
                hint = "",
                klass = if (focused) "EditText" else "",
                id = id,
                clickable = tap,
                longClickable = false,
                checkable = false,
                checked = false,
                selected = false,
                enabled = true,
                scrollable = false,
                focused = focused,
                editable = focused,
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                parent = -1,
                depth = 0,
            )
        }.toList()
    }

    private val StaticChrome = listOf(
        "返回", "关闭", "取消", "确定", "完成", "确认", "搜索", "发布", "发送", "保存",
        "首页", "我", "我的", "消息", "通讯录", "发现", "更多", "设置", "添加", "刷新",
        "分享", "筛选", "下一步", "上一步", "同意", "允许", "登录", "注册", "跳过",
        "知道了", "重试", "删除", "编辑", "复制", "粘贴", "全选", "提交", "申请",
        "点赞", "赞", "下一节", "上一节", "继续学习", "继续", "作业", "答题", "播放",
        "Search", "Like", "OK", "Done", "Send", "Save", "Home", "Me",
    )
}

internal object GuiSceneAbstractor {
    fun abstractScene(goal: String): String {
        val stripped = goal
            .replace(Regex("[\"“”‘’].*?[\"“”‘’]"), " ")
            .replace(Regex("https?://\\S+"), " ")
        val n = AgentModeSopStore.normalizeGoal(stripped)
        return when {
            n.contains("田") || n.contains("地块") -> "选择田块"
            (n.contains("搜") || n.contains("search")) &&
                (n.contains("赞") || n.contains("like")) -> "搜索并点赞"
            n.contains("赞") || n.contains("like") -> "点赞"
            n.contains("课") || n.contains("作业") || n.contains("答题") -> "看课做题"
            n.contains("搜") || n.contains("search") -> "搜索"
            n.contains("打开") || n.contains("open") -> "打开"
            else -> n.take(16).ifBlank { "未命名场景" }
        }
    }

    fun sceneGoal(appName: String, goal: String): String {
        val app = appName.trim().ifBlank { "app" }
        return "$app / ${abstractScene(goal)}"
    }
}
