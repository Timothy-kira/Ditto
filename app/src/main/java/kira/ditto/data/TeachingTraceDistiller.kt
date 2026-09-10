package kira.ditto.data

/**
 * Turns a live agent or teaching trace into a reusable method, not a coordinate tape.
 *
 * - Repair: backing out of a wrong page the agent already entered.
 * - Method: chrome identity (search field, like, next lesson) replayed by
 *   layered locators, never today's hot word or x/y.
 * - Instance: picking a user-specific row. Replay looks up this user's item.
 */
internal object TeachingTraceDistiller {
    const val PickSlot = "pick"
    const val QuerySlot = "query"
    const val ConfirmSlot = "confirm"
    const val KindRepair = "repair"
    const val KindMethod = "method"
    const val KindInstance = "instance"

    private val DistillSources = setOf(
        AgentModeSopProvenance.UserTeaching.storageValue,
        AgentModeSopProvenance.KimiAgent.storageValue,
    )

    private val TapLike = setOf("tap", "click_node")

    data class Result(
        val repair: List<AgentModeSopStep>,
        val replay: List<AgentModeSopStep>,
        val everMeSummary: String,
    )

    fun distill(steps: List<AgentModeSopStep>): Result {
        val candidates = dropNoops(
            steps.filter { step ->
                step.source in DistillSources && step.resultOk
            },
        )
        if (candidates.isEmpty()) {
            return Result(repair = emptyList(), replay = emptyList(), everMeSummary = "")
        }
        val seen = linkedSetOf<String>()
        val classified = candidates.map { step ->
            val from = step.uiFingerprint
            if (from.isNotBlank()) seen += from
            val kind = classify(
                action = step.action,
                label = step.guiLabel,
                fromFingerprint = from,
                toFingerprint = step.toFingerprint,
                previouslySeen = seen - setOf(from),
                pageTargetCount = step.pageTargetCount,
                resourceId = step.resourceId,
                locator = step.locatorSpec,
            )
            if (step.toFingerprint.isNotBlank()) seen += step.toFingerprint
            annotate(step.withKind(kind), kind)
        }
        val lastRepair = classified.indexOfLast { it.kind == KindRepair }
        val repair = if (lastRepair >= 0) classified.take(lastRepair + 1) else emptyList()
        val replay = classified.drop(lastRepair + 1).map { step ->
            when (step.kind) {
                KindInstance -> step.stripCoordinates().copy(
                    inputSlot = PickSlot,
                    guiLabel = "",
                    target = "",
                    locator = "",
                    locatorSpec = GuiLocator.Empty,
                    semantic = "选择当前用户的目标（如田块），按本次任务的 target/item 在当前页匹配",
                )
                KindMethod -> step.stripCoordinates().copy(
                    locator = step.locator.ifBlank {
                        step.locatorSpec.recipe().ifBlank {
                            step.resourceId.ifBlank { step.guiLabel }
                        }
                    },
                    guiLabel = staticLabel(step),
                )
                else -> step.stripCoordinates()
            }
        }
        return Result(
            repair = repair,
            replay = replay,
            everMeSummary = summarize(repair, replay),
        )
    }

    fun classify(
        action: String,
        label: String,
        fromFingerprint: String,
        toFingerprint: String,
        previouslySeen: Set<String>,
        pageTargetCount: Int,
        resourceId: String = "",
        locator: GuiLocator = GuiLocator.Empty,
    ): String {
        val normalized = label.trim()
        if (isRepairAction(action, normalized, fromFingerprint, toFingerprint, previouslySeen)) {
            return KindRepair
        }
        if (action in TapLike && isInstancePick(normalized, pageTargetCount, resourceId, locator)) {
            return KindInstance
        }
        return KindMethod
    }

    fun isRepairAction(
        action: String,
        label: String,
        fromFingerprint: String,
        toFingerprint: String,
        previouslySeen: Set<String>,
    ): Boolean {
        if (action == "back") return true
        if (RepairLabels.any { label.equals(it, ignoreCase = true) || label.contains(it) }) {
            return true
        }
        if (
            toFingerprint.isNotBlank() &&
            fromFingerprint.isNotBlank() &&
            toFingerprint != fromFingerprint &&
            toFingerprint in previouslySeen
        ) {
            return true
        }
        return false
    }

    fun isInstancePick(
        label: String,
        pageTargetCount: Int,
        resourceId: String = "",
        locator: GuiLocator = GuiLocator.Empty,
    ): Boolean {
        if (isChromeControl(label, resourceId, locator)) return false
        if (label.isBlank()) return pageTargetCount >= InstanceListTargetCount
        if (isChromeLabel(label) || isConfirmLabel(label)) return false
        if (InstanceHints.any { label.contains(it) }) return true
        if (GuiLocatorCodec.isVolatileContent(label) && !isChromeLabel(label)) return true
        if (label.any { it.isDigit() } && label.length >= 2 && locator.role != "like") return true
        return pageTargetCount >= InstanceListTargetCount
    }

    fun isChromeLabel(label: String): Boolean {
        val value = label.trim()
        if (value.isBlank()) return false
        return ChromeLabels.any { token ->
            value.equals(token, ignoreCase = true) ||
                (token.length >= 2 && value.contains(token, ignoreCase = true) &&
                    !GuiLocatorCodec.isVolatileContent(value))
        }
    }

    fun isConfirmLabel(label: String): Boolean =
        ConfirmLabels.any { label.equals(it, ignoreCase = true) }

    private fun isChromeControl(label: String, resourceId: String, locator: GuiLocator): Boolean {
        if (locator.role == "search" || locator.role == "like" || locator.role == "chrome") return true
        if (GuiLocatorCodec.isSearchChrome(label, resourceId, locator.klass, locator.region, locator.editable)) {
            return true
        }
        if (GuiLocatorCodec.isLikeChrome(label, resourceId, locator.region)) return true
        return isChromeLabel(label)
    }

    private fun dropNoops(steps: List<AgentModeSopStep>): List<AgentModeSopStep> {
        val kept = ArrayList<AgentModeSopStep>(steps.size)
        for (step in steps) {
            if (step.action == "launch") {
                kept += step
                continue
            }
            val fingerprintChanged = step.uiFingerprint.isNotBlank() &&
                step.toFingerprint.isNotBlank() &&
                step.uiFingerprint != step.toFingerprint
            val slot = step.action == "text" ||
                step.inputSlot == QuerySlot ||
                step.inputSlot == "text" ||
                step.inputSlot == PickSlot
            val chrome = isChromeControl(step.guiLabel, step.resourceId, step.locatorSpec)
            val wait = step.action == "wait_for_label"
            val effective = fingerprintChanged || slot || chrome || wait || step.action == "swipe"
            if (step.action in TapLike && !effective) continue
            val prev = kept.lastOrNull()
            val sameControl = prev != null &&
                prev.action == step.action &&
                prev.resourceId == step.resourceId &&
                prev.guiLabel == step.guiLabel &&
                prev.locatorSpec.role == step.locatorSpec.role &&
                prev.locatorSpec.resourceId == step.locatorSpec.resourceId &&
                prev.uiFingerprint == step.uiFingerprint
            if (sameControl && !fingerprintChanged && step.action in TapLike) continue
            kept += step
        }
        return kept
    }

    private fun annotate(step: AgentModeSopStep, kind: String): AgentModeSopStep {
        val captured = if (step.locatorSpec.isEmpty()) {
            GuiLocatorCodec.capture(
                label = step.guiLabel,
                resourceId = step.resourceId,
            )
        } else {
            step.locatorSpec
        }
        val locator = when {
            captured.recipe().isNotBlank() -> captured.recipe()
            step.resourceId.isNotBlank() -> step.resourceId
            else -> staticLabel(step)
        }
        val semantic = when {
            kind == KindRepair -> "纠正：从错误页退回（${step.guiLabel.ifBlank { step.action }}）"
            kind == KindInstance -> "选择当前用户的列表项"
            step.action == "text" || step.inputSlot == QuerySlot || step.inputSlot == "text" ->
                "在已聚焦输入框粘贴本次任务的 query"
            step.action == "search" -> "在搜索框输入本次 query 并回车提交"
            step.action == "swipe" || step.action.startsWith("swipe_") || step.action == "fling" ->
                "按页面方向滑动，而不是复用这次的坐标"
            captured.role == "search" ->
                "点击顶栏搜索控件（常见为 EditText/SearchView，resource-id 含 search；占位热词会变，不要用热词去匹配）"
            captured.role == "like" ->
                "点赞：底栏赞按钮（desc=点赞 或 id 含 like），不要匹配点赞数"
            isConfirmLabel(step.guiLabel) -> "点击确认控件「${step.guiLabel}」"
            staticLabel(step).isNotBlank() -> "点击「${staticLabel(step)}」"
            locator.isNotBlank() -> "点击 $locator"
            else -> step.action
        }
        val slot = when {
            kind == KindInstance -> PickSlot
            step.action == "text" || step.inputSlot == "text" || step.inputSlot == QuerySlot -> QuerySlot
            isConfirmLabel(step.guiLabel) -> ConfirmSlot
            else -> step.inputSlot
        }
        return step.copy(
            kind = kind,
            locator = locator,
            locatorSpec = captured,
            semantic = semantic,
            inputSlot = slot,
        )
    }

    private fun staticLabel(step: AgentModeSopStep): String {
        val spec = step.locatorSpec
        val candidate = spec.staticDesc.ifBlank { spec.hint }.ifBlank { step.guiLabel }
        return if (GuiLocatorCodec.isVolatileContent(candidate)) "" else candidate
    }

    private fun summarize(repair: List<AgentModeSopStep>, replay: List<AgentModeSopStep>): String {
        val method = replay.filter { it.kind == KindMethod }.map { step ->
            step.semantic.ifBlank { staticLabel(step) }
        }.filter { it.isNotBlank() }
        val picks = replay.count { it.kind == KindInstance || it.inputSlot == PickSlot }
        val repaired = repair.map { it.guiLabel.ifBlank { it.action } }.filter { it.isNotBlank() }
        return buildString {
            if (repaired.isNotEmpty()) {
                append("纠正：从错误页")
                append(repaired.joinToString(" → "))
                append("退回。")
            }
            if (method.isNotEmpty()) {
                append("方法：")
                append(method.joinToString(" → "))
                append("。")
            }
            if (picks > 0) {
                append("有 $picks 步是选当前用户的目标（如田块），不要复用这次点的坐标或那一块的名字。")
            }
        }.trim()
    }

    private fun AgentModeSopStep.withKind(kind: String): AgentModeSopStep = copy(kind = kind)

    private fun AgentModeSopStep.stripCoordinates(): AgentModeSopStep = copy(
        x = null,
        y = null,
        x1 = null,
        y1 = null,
        x2 = null,
        y2 = null,
    )

    private val RepairLabels = listOf(
        "返回", "关闭", "取消", "收起", "退出", "Navigate up", "Back", "Close", "Cancel",
    )

    private val ConfirmLabels = listOf(
        "确定", "完成", "确认", "提交", "发布", "发送", "保存", "OK", "Done", "Send", "Save",
    )

    private val ChromeLabels = listOf(
        "返回", "关闭", "取消", "确定", "完成", "确认", "搜索", "发布", "发送", "保存",
        "首页", "我", "我的", "消息", "通讯录", "发现", "更多", "设置", "添加", "刷新",
        "分享", "筛选", "下一步", "上一步", "同意", "允许", "登录", "注册", "跳过",
        "知道了", "重试", "删除", "编辑", "复制", "粘贴", "全选", "提交", "申请",
        "点赞", "赞", "下一节", "上一节", "继续学习", "继续", "作业", "答题", "播放",
        "Search", "Like", "OK", "Done", "Send", "Save", "Home", "Me",
    )

    private val InstanceHints = listOf("田", "地块", "地号", "亩", "plot", "field", "地")

    private const val InstanceListTargetCount = 6
}
