package kira.ditto.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

val PiBuiltinSlashCommands = listOf(
    "status" to "查看当前会话状态",
    "usage" to "查看用量与配额",
    "mcp" to "查看 MCP 服务器",
    "tasks" to "查看后台任务",
    "help" to "显示可用命令",
    "clear" to "清空当前会话",
    "init" to "初始化项目上下文",
    "model" to "切换模型",
    "permissions" to "查看或更改权限模式",
    "yolo" to "切换需要时询问（Ask When Needed）",
    "ask-when-needed" to "切换需要时询问权限模式",
    "never-ask" to "切换从不询问权限模式",
    "auto" to "切换从不询问权限模式",
    "plan" to "切换到计划模式",
    "config" to "查看或更改配置",
    "skills" to "查看可用技能",
    "cost" to "查看本次会话费用",
    "export" to "导出当前会话",
    "copy" to "复制最近回复",
    "diff" to "查看未提交改动",
    "undo" to "撤销上一步改动",
    "resume" to "恢复会话",
    "login" to "登录提供商",
    "logout" to "退出登录",
    "doctor" to "诊断运行环境",
)

data class SlashCommandSuggestion(
    val command: String,
    val description: String,
    val icon: SlashCommandIcon = SlashCommandIcon.Command,
    val argumentHint: String = "",
)

enum class SlashCommandIcon { Command, Skill, Extension }

const val CompactSlashCommand = "/compact"

fun isCompactSlashCommand(input: String): Boolean {
    val trimmed = input.trim()
    if (trimmed.isEmpty() || trimmed.first() != '/') return false
    val token = trimmed.drop(1).takeWhile { !it.isWhitespace() }
    return token.equals("compact", ignoreCase = true)
}

fun compactSlashInstruction(input: String): String {
    val trimmed = input.trim()
    val token = trimmed.drop(1).takeWhile { !it.isWhitespace() }
    return trimmed.drop(1 + token.length).trim()
}

data class FileMentionSuggestion(
    val insertToken: String,
    val name: String,
    val guestPath: String,
)

fun slashCommandSuggestions(
    input: String,
    extensionCommands: List<SlashCommandSuggestion> = emptyList(),
    agentCommands: List<SlashCommandSuggestion> = emptyList(),
): List<SlashCommandSuggestion> {
    if (input.isEmpty() || input.first() != '/') return emptyList()
    val commandToken = input.drop(1).takeWhile { !it.isWhitespace() }
    val merged = PiBuiltinSlashCommands.map { (name, description) ->
        SlashCommandSuggestion("/$name", description)
    } + agentCommands + extensionCommands.map { it.copy(icon = SlashCommandIcon.Extension) }
    val exactCommand = merged.any { it.command.removePrefix("/").equals(commandToken, ignoreCase = true) }
    if (input.drop(1 + commandToken.length).trimStart().isNotEmpty() && exactCommand) return emptyList()
    val prefix = commandToken.lowercase()
    return merged
        .filter {
            val candidate = it.command.removePrefix("/").lowercase()
            candidate.startsWith(prefix) || prefix.startsWith(candidate)
        }
        .distinctBy { it.command.lowercase() }
        .take(50)
}

fun fileMentionQuery(input: String): String? {
    val at = input.lastIndexOf('@')
    if (at < 0) return null
    if (at > 0 && !input[at - 1].isWhitespace()) return null
    val after = input.substring(at + 1)
    if (after.any { it.isWhitespace() }) return null
    return after
}

fun fileMentionSuggestions(
    input: String,
    files: List<FileMentionSuggestion>,
): List<FileMentionSuggestion> {
    val query = fileMentionQuery(input) ?: return emptyList()
    val needle = query.lowercase()
    return files
        .filter { file ->
            needle.isEmpty() ||
                file.name.lowercase().contains(needle) ||
                file.guestPath.lowercase().contains(needle) ||
                file.insertToken.removePrefix("@").lowercase().contains(needle)
        }
        .take(40)
}

/** Empty `@` should list plugins, not the whole workspace. Files need `/`, `.`, or 2+ chars. */
fun workspaceFileMentionLooksSpecific(query: String): Boolean {
    if (query.contains('/') || query.contains('.')) return true
    return query.length >= 2
}

fun pluginMentionQueryMatches(
    query: String,
    id: String,
    displayName: String,
    actionLabel: String,
): Boolean {
    if (query.isEmpty()) return true
    val needle = query.lowercase()
    return actionLabel.lowercase().contains(needle) ||
        displayName.lowercase().contains(needle) ||
        id.lowercase().contains(needle)
}

fun slashDisplayName(command: String): String = command.removePrefix("/")
    .replace('-', ' ')
    .replace(':', ' ')
    .trim()
    .replaceFirstChar { it.titlecase() }

fun slashHighlightedName(command: String, input: String): AnnotatedString = buildAnnotatedString {
    val label = slashDisplayName(command)
    val query = input.drop(1).takeWhile { !it.isWhitespace() }.replace('-', ' ')
    val match = label.lowercase().indexOf(query.lowercase().trim())
    if (match >= 0 && query.isNotBlank()) {
        append(label.substring(0, match))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(label.substring(match, (match + query.length).coerceAtMost(label.length)))
        }
        append(label.drop((match + query.length).coerceAtMost(label.length)))
    } else append(label)
}
