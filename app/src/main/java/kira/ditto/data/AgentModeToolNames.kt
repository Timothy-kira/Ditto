package kira.ditto.data

fun isAgentModeDisplayToolName(toolName: String): Boolean {
    val n = toolName.lowercase().replace('-', '_')
    return n == "agent_display" ||
        n.endsWith("_agent_display") ||
        "agent_display" in n
}
