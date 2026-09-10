package kira.ditto.data

internal data class GuiFlowSegment(
    val sceneKey: String,
    val steps: List<AgentModeSopStep>,
    val methodOnly: Boolean,
    val slots: List<String>,
)

internal object GuiFlowSegmenter {
    fun segment(steps: List<AgentModeSopStep>): List<GuiFlowSegment> {
        val launches = steps.filter { PhoneUiMcp.normalizeAction(it.action) == "launch" }
        val rest = steps.filter { PhoneUiMcp.normalizeAction(it.action) != "launch" }
        if (rest.isEmpty()) {
            if (launches.isEmpty()) return emptyList()
            return listOf(toSegment(launches))
        }
        val groups = ArrayList<MutableList<AgentModeSopStep>>()
        var current = ArrayList<AgentModeSopStep>()
        var lastPage = ""
        rest.forEach { step ->
            val page = step.uiFingerprint.ifBlank { lastPage }
            val pageChanged = page.isNotBlank() && lastPage.isNotBlank() && page != lastPage &&
                PhoneUiMcp.normalizeAction(step.action) !in setOf("text", "search", "clear_text", "undo", "key")
            val startsInstance = isInstance(step) && current.any { isMethod(it) } && current.none { isInstance(it) }
            val startsLike = step.locatorSpec.role == "like" && current.any { isInstance(it) || isMethod(it) } &&
                current.none { it.locatorSpec.role == "like" } && current.size >= 2
            if (current.isNotEmpty() && (pageChanged || startsInstance || startsLike)) {
                groups += current
                current = ArrayList()
            }
            current += step
            if (page.isNotBlank()) lastPage = page
            if (step.toFingerprint.isNotBlank()) lastPage = step.toFingerprint
        }
        if (current.isNotEmpty()) groups += current
        if (groups.isEmpty()) return emptyList()
        if (launches.isNotEmpty()) {
            groups[0] = (launches + groups[0]).toMutableList()
        }
        return groups.map(::toSegment)
    }

    private fun toSegment(steps: List<AgentModeSopStep>): GuiFlowSegment {
        val slots = steps.map { it.inputSlot }.filter { it.isNotBlank() }.distinct()
        val methodOnly = steps.none { isInstance(it) } && steps.any { isMethod(it) || isChrome(it) }
        return GuiFlowSegment(
            sceneKey = sceneKey(steps),
            steps = steps,
            methodOnly = methodOnly || (steps.size == 1 && PhoneUiMcp.normalizeAction(steps.first().action) == "launch"),
            slots = slots,
        )
    }

    fun sceneKey(steps: List<AgentModeSopStep>): String {
        val roles = steps.map { it.locatorSpec.role }.filter { it.isNotBlank() }
        if (roles.any { it == "compose" }) return "compose"
        if (roles.any { it == "comment" }) return "comment"
        if (steps.any { it.locatorSpec.role == "search" || it.inputSlot == TeachingTraceDistiller.QuerySlot }) {
            return "search"
        }
        val typed = steps.any {
            val action = PhoneUiMcp.normalizeAction(it.action)
            action == "text" || action == "type_text" || action == "search"
        }
        if (typed && steps.none { isInstance(it) } && roles.none { it == "compose" || it == "comment" }) {
            return "search"
        }
        if (steps.any { it.locatorSpec.role == "like" }) return "like"
        if (steps.any { isInstance(it) }) return "open_item"
        if (steps.any { it.inputSlot == TeachingTraceDistiller.ConfirmSlot }) return "confirm"
        val recipe = steps.firstOrNull { !it.locatorSpec.isEmpty() }?.locatorSpec?.role.orEmpty()
        if (recipe.isNotBlank()) return recipe.replace(Regex("[^a-z0-9_]+"), "_").take(24)
        val label = steps.firstOrNull { it.guiLabel.isNotBlank() }?.guiLabel.orEmpty()
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
        return label.take(24).ifBlank { "flow" }
    }

    private fun isInstance(step: AgentModeSopStep): Boolean =
        step.kind == TeachingTraceDistiller.KindInstance ||
            step.inputSlot == TeachingTraceDistiller.PickSlot

    private fun isMethod(step: AgentModeSopStep): Boolean =
        step.kind == TeachingTraceDistiller.KindMethod ||
            (step.kind.isBlank() && !isInstance(step) && PhoneUiMcp.normalizeAction(step.action) != "launch")

    private fun isChrome(step: AgentModeSopStep): Boolean =
        step.locatorSpec.role.isNotBlank() || TeachingTraceDistiller.isChromeLabel(step.guiLabel)
}

/**
 * Nothing but app launches.
 *
 * Separate from [GuiFlowSegment.methodOnly], which is broader: a tap on a search bar is method-only
 * (it addresses a durable affordance) but still a GUI action that can miss. A launch resolves a
 * package name, so replaying it proves nothing the first run did not.
 */
internal fun GuiFlowSegment.isLaunchOnly(): Boolean =
    steps.isNotEmpty() && steps.all { PhoneUiMcp.normalizeAction(it.action) == "launch" }
