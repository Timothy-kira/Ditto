package kira.ditto.ui

import kira.ditto.data.SharedActiveSkillContext
import kira.ditto.data.SharedInstalledSkill
import kira.ditto.data.platformDynamicPromptValues

internal fun buildSharedPiAgentInstructions(
    configuredPrompt: String,
    workspaceDirectory: String,
    availableSkills: List<SharedInstalledSkill>,
    activeSkills: List<SharedActiveSkillContext>,
): String = buildString {
    expandSharedDynamicPromptPlaceholders(configuredPrompt).trim().takeIf(String::isNotBlank)?.let {
        append(it)
        append("\n\n")
    }
    append(
        "You are running inside Aether on iOS. Pi AI provides model and provider access, " +
            "Pi Agent Core runs the agent loop, and Pi Coding Agent owns tools, Skills, Extensions, sessions, retry, and compaction. " +
            "Use available tools instead of guessing about local state. " +
            "The default workspace for this chat is $workspaceDirectory. " +
            "User-uploaded files are under attachments/. Use read when inspection is needed; read returns image content for supported image files. " +
            "When linking a local file for the user to preview or share, use a Markdown link with a file:// target and the absolute path, " +
            "for example [report.pdf](file:///absolute/path/report.pdf). Do not use another URI scheme for local file links. " +
            "Only claim commands or actions that were actually performed."
    )
}

private val SharedDynamicPromptPlaceholderRegex = Regex("""\{\{\s*([A-Za-z0-9_-]+)\s*\}\}""")

internal fun expandSharedDynamicPromptPlaceholders(
    prompt: String,
    values: Map<String, String> = platformDynamicPromptValues(),
): String {
    if (!prompt.contains("{{")) return prompt
    return SharedDynamicPromptPlaceholderRegex.replace(prompt) { match ->
        values[match.groupValues[1].lowercase()] ?: match.value
    }
}
