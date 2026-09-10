package kira.ditto.ui

import kira.ditto.data.isAgentModeDisplayToolName

internal fun ChatToolInvocation.isAgentModeDisplayInvocation(): Boolean =
    isAgentModeDisplayToolName(toolName)
