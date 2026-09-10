package kira.ditto.termux

import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

private const val DefaultSearchResultLimit = 200
private const val DefaultLsEntryLimit = 200

class TermuxFilesystemTool(
    private val commandExecutor: ShellCommandExecutor,
    private val homeDirectory: String = TermuxContract.HomeDirectory,
) {
    constructor(
        bashTool: TermuxBashTool,
    ) : this(
        commandExecutor = object : ShellCommandExecutor {
            override suspend fun executeCommand(
                command: String,
                workingDirectory: String,
            ): String = bashTool.executeCommand(command, workingDirectory)
        },
        homeDirectory = TermuxContract.HomeDirectory,
    )

    suspend fun executeRead(argumentsJson: String): String {
        val arguments = parseArguments(argumentsJson) ?: return invalidArguments("Arguments were not valid JSON.")
        val path = arguments.optString("path").trim()
        val offset = arguments.optInt("offset", 0)
        val limit = arguments.takeIf { it.has("limit") }?.optInt("limit")
        val showLineNumbers = arguments.optBoolean("showLineNumbers", false) ||
            arguments.optBoolean("show_line_numbers", false)
        val workingDirectory = normalizeTermuxPath(
            arguments.optString("workingDirectory").trim()
                .ifBlank { arguments.optString("working_directory").trim() }
                .ifBlank { homeDirectory }
        )
        val resolvedPath = normalizeTermuxPath(path)

        if (path.isBlank()) return invalidArguments("Missing required 'path' argument.")
        if (offset < 0) return invalidArguments("'offset' must be 0 or greater.")
        if (limit != null && limit <= 0) return invalidArguments("'limit' must be greater than 0 when provided.")

        val commandSummary = buildReadCommandSummary(path, offset, limit, showLineNumbers)
        val execution = runStructuredScript(
            commandSummary = commandSummary,
            path = path,
            workingDirectory = workingDirectory,
            script = buildReadScript(resolvedPath, offset, limit),
        )
        execution.errorJson?.let { return it }

        val values = execution.values
        val content = decodeBase64(values["content_b64"].orEmpty())
        val totalLineCount = values["total_line_count"]?.toIntOrNull() ?: 0
        val startLine = values["start_line"]?.toIntOrNull() ?: 0
        val endLine = values["end_line"]?.toIntOrNull() ?: 0
        val returnedLineCount = values["returned_line_count"]?.toIntOrNull() ?: 0
        val truncated = values["truncated"].toBoolean()

        return JSONObject().apply {
            put("ok", true)
            put("command", commandSummary)
            put("path", path)
            put("content", content)
            put("offset", offset)
            put("limit", limit ?: JSONObject.NULL)
            put("show_line_numbers", showLineNumbers)
            put("total_line_count", totalLineCount)
            put("start_line", startLine)
            put("end_line", endLine)
            put("returned_line_count", returnedLineCount)
            put("truncated", truncated)
            put(
                "stdout",
                buildReadStdout(
                    path = path,
                    content = content,
                    startLine = startLine,
                    endLine = endLine,
                    totalLineCount = totalLineCount,
                    truncated = truncated,
                    showLineNumbers = showLineNumbers,
                )
            )
            if (execution.stderr.isNotBlank()) {
                put("stderr", execution.stderr)
            }
        }.toString()
    }

    suspend fun executeWrite(argumentsJson: String): String {
        val arguments = parseArguments(argumentsJson) ?: return invalidArguments("Arguments were not valid JSON.")
        val path = arguments.optString("path").trim()
        val content = arguments.optString("content")
        val workingDirectory = normalizeTermuxPath(
            arguments.optString("workingDirectory").trim()
                .ifBlank { arguments.optString("working_directory").trim() }
                .ifBlank { homeDirectory }
        )
        val resolvedPath = normalizeTermuxPath(path)

        if (path.isBlank()) return invalidArguments("Missing required 'path' argument.")
        if (!arguments.has("content")) return invalidArguments("Missing required 'content' argument.")

        val commandSummary = "write $path"
        val execution = runStructuredScript(
            commandSummary = commandSummary,
            path = path,
            workingDirectory = workingDirectory,
            script = buildWriteScript(resolvedPath, content),
        )
        execution.errorJson?.let { return it }

        val values = execution.values
        val created = values["created"].toBoolean()
        val bytesWritten = values["bytes_written"]?.toLongOrNull() ?: 0L

        return JSONObject().apply {
            put("ok", true)
            put("command", commandSummary)
            put("path", path)
            put("created", created)
            put("bytes_written", bytesWritten)
            put(
                "stdout",
                if (created) {
                    "Created $path ($bytesWritten bytes)."
                } else {
                    "Overwrote $path ($bytesWritten bytes)."
                }
            )
            if (execution.stderr.isNotBlank()) {
                put("stderr", execution.stderr)
            }
        }.toString()
    }

    suspend fun executeEdit(argumentsJson: String): String {
        val arguments = parseArguments(argumentsJson) ?: return invalidArguments("Arguments were not valid JSON.")
        val path = arguments.optString("path").trim()
        val workingDirectory = normalizeTermuxPath(
            arguments.optString("workingDirectory").trim()
                .ifBlank { arguments.optString("working_directory").trim() }
                .ifBlank { homeDirectory }
        )
        val resolvedPath = normalizeTermuxPath(path)

        if (path.isBlank()) return invalidArguments("Missing required 'path' argument.")

        val parsedBatchEdits = parseEdits(arguments.optJSONArray("edits"))
        val hasSingleEdit = arguments.has("oldText") || arguments.has("newText")
        val edits = if (parsedBatchEdits.isNotEmpty()) {
            parsedBatchEdits
        } else if (hasSingleEdit) {
            if (!arguments.has("oldText") || !arguments.has("newText")) {
                return invalidArguments("Single edit mode requires both 'oldText' and 'newText'.")
            }
            listOf(
                TextEdit(
                    oldText = arguments.optString("oldText"),
                    newText = arguments.optString("newText"),
                )
            )
        } else {
            return invalidArguments(
                "Provide either 'oldText'/'newText' for one edit or a non-empty 'edits' array for multiple edits."
            )
        }

        if (edits.isEmpty()) {
            return invalidArguments("'edits' must contain at least one replacement.")
        }
        if (edits.any { it.oldText.isEmpty() }) {
            return invalidArguments("Each edit requires a non-empty 'oldText'.")
        }

        val commandSummary = "edit $path (${edits.size} edit${if (edits.size == 1) "" else "s"})"
        val execution = runStructuredScript(
            commandSummary = commandSummary,
            path = path,
            workingDirectory = workingDirectory,
            script = buildEditScript(resolvedPath, edits),
        )
        execution.errorJson?.let { return it }

        val values = execution.values
        val appliedEdits = values["applied_edits"]?.toIntOrNull() ?: edits.size
        val bytesWritten = values["bytes_written"]?.toLongOrNull() ?: 0L

        return JSONObject().apply {
            put("ok", true)
            put("command", commandSummary)
            put("path", path)
            put("applied_edits", appliedEdits)
            put("bytes_written", bytesWritten)
            put("stdout", "Applied $appliedEdits precise edit${if (appliedEdits == 1) "" else "s"} to $path.")
            if (execution.stderr.isNotBlank()) {
                put("stderr", execution.stderr)
            }
        }.toString()
    }

    suspend fun executeGrep(argumentsJson: String): String {
        val arguments = parseArguments(argumentsJson) ?: return invalidArguments("Arguments were not valid JSON.")
        val path = arguments.optString("path").trim()
        val pattern = arguments.optString("pattern")
        val isRegex = arguments.optBoolean("isRegex", false)
        val caseSensitive = arguments.optBoolean("caseSensitive", true)
        val maxResults = arguments.optInt("maxResults", DefaultSearchResultLimit)
        val workingDirectory = normalizeTermuxPath(
            arguments.optString("workingDirectory").trim()
                .ifBlank { arguments.optString("working_directory").trim() }
                .ifBlank { homeDirectory }
        )
        val resolvedPath = normalizeTermuxPath(path)

        if (path.isBlank()) return invalidArguments("Missing required 'path' argument.")
        if (pattern.isEmpty()) return invalidArguments("Missing required 'pattern' argument.")
        if (maxResults <= 0) return invalidArguments("'maxResults' must be greater than 0.")

        val commandSummary = "grep ${if (isRegex) "regex" else "text"} ${quoteSummary(pattern)} in $path"
        val execution = runStructuredScript(
            commandSummary = commandSummary,
            path = path,
            workingDirectory = workingDirectory,
            script = buildGrepScript(
                path = resolvedPath,
                pattern = pattern,
                isRegex = isRegex,
                caseSensitive = caseSensitive,
                maxResults = maxResults,
            ),
        )
        execution.errorJson?.let { return it }

        val values = execution.values
        val matchCount = values["match_count"]?.toIntOrNull() ?: 0
        val truncated = values["truncated"].toBoolean()
        val matches = decodeBase64(values["matches_b64"].orEmpty())

        return JSONObject().apply {
            put("ok", true)
            put("command", commandSummary)
            put("path", path)
            put("pattern", pattern)
            put("is_regex", isRegex)
            put("case_sensitive", caseSensitive)
            put("match_count", matchCount)
            put("truncated", truncated)
            put("matches", matches)
            put("stdout", buildSearchStdout(matches, matchCount, truncated, maxResults))
            if (execution.stderr.isNotBlank()) {
                put("stderr", execution.stderr)
            }
        }.toString()
    }

    suspend fun executeFind(argumentsJson: String): String {
        val arguments = parseArguments(argumentsJson) ?: return invalidArguments("Arguments were not valid JSON.")
        val path = arguments.optString("path").trim()
        val pattern = arguments.optString("pattern")
        val type = arguments.optString("type").trim().ifBlank { "any" }
        val caseSensitive = arguments.optBoolean("caseSensitive", true)
        val maxDepth = arguments.takeIf { it.has("maxDepth") }?.optInt("maxDepth")
        val maxResults = arguments.optInt("maxResults", DefaultSearchResultLimit)
        val workingDirectory = normalizeTermuxPath(
            arguments.optString("workingDirectory").trim()
                .ifBlank { arguments.optString("working_directory").trim() }
                .ifBlank { homeDirectory }
        )
        val resolvedPath = normalizeTermuxPath(path)

        if (path.isBlank()) return invalidArguments("Missing required 'path' argument.")
        if (pattern.isEmpty()) return invalidArguments("Missing required 'pattern' argument.")
        if (type !in setOf("any", "file", "directory")) {
            return invalidArguments("'type' must be 'any', 'file', or 'directory'.")
        }
        if (maxDepth != null && maxDepth < 0) {
            return invalidArguments("'maxDepth' must be 0 or greater when provided.")
        }
        if (maxResults <= 0) return invalidArguments("'maxResults' must be greater than 0.")

        val commandSummary = "find ${quoteSummary(pattern)} in $path"
        val execution = runStructuredScript(
            commandSummary = commandSummary,
            path = path,
            workingDirectory = workingDirectory,
            script = buildFindScript(
                path = resolvedPath,
                pattern = pattern,
                type = type,
                caseSensitive = caseSensitive,
                maxDepth = maxDepth,
                maxResults = maxResults,
            ),
        )
        execution.errorJson?.let { return it }

        val values = execution.values
        val matchCount = values["match_count"]?.toIntOrNull() ?: 0
        val truncated = values["truncated"].toBoolean()
        val matches = decodeBase64(values["matches_b64"].orEmpty())

        return JSONObject().apply {
            put("ok", true)
            put("command", commandSummary)
            put("path", path)
            put("pattern", pattern)
            put("type", type)
            put("case_sensitive", caseSensitive)
            if (maxDepth != null) {
                put("max_depth", maxDepth)
            }
            put("match_count", matchCount)
            put("truncated", truncated)
            put("matches", matches)
            put("stdout", buildSearchStdout(matches, matchCount, truncated, maxResults))
            if (execution.stderr.isNotBlank()) {
                put("stderr", execution.stderr)
            }
        }.toString()
    }

    suspend fun executeLs(argumentsJson: String): String {
        val arguments = parseArguments(argumentsJson) ?: return invalidArguments("Arguments were not valid JSON.")
        val path = arguments.optString("path").trim()
        val recursive = arguments.optBoolean("recursive", false)
        val includeHidden = arguments.optBoolean("includeHidden", false)
        val maxDepth = arguments.takeIf { it.has("maxDepth") }?.optInt("maxDepth")
        val maxEntries = arguments.optInt("maxEntries", DefaultLsEntryLimit)
        val workingDirectory = normalizeTermuxPath(
            arguments.optString("workingDirectory").trim()
                .ifBlank { arguments.optString("working_directory").trim() }
                .ifBlank { homeDirectory }
        )
        val resolvedPath = normalizeTermuxPath(path)

        if (path.isBlank()) return invalidArguments("Missing required 'path' argument.")
        if (maxDepth != null && maxDepth < 0) {
            return invalidArguments("'maxDepth' must be 0 or greater when provided.")
        }
        if (maxEntries <= 0) return invalidArguments("'maxEntries' must be greater than 0.")

        val commandSummary = "ls $path"
        val execution = runStructuredScript(
            commandSummary = commandSummary,
            path = path,
            workingDirectory = workingDirectory,
            script = buildLsScript(
                path = resolvedPath,
                recursive = recursive,
                includeHidden = includeHidden,
                maxDepth = maxDepth,
                maxEntries = maxEntries,
            ),
        )
        execution.errorJson?.let { return it }

        val values = execution.values
        val entryCount = values["entry_count"]?.toIntOrNull() ?: 0
        val truncated = values["truncated"].toBoolean()
        val listing = decodeBase64(values["listing_b64"].orEmpty())

        return JSONObject().apply {
            put("ok", true)
            put("command", commandSummary)
            put("path", path)
            put("recursive", recursive)
            put("include_hidden", includeHidden)
            if (maxDepth != null) {
                put("max_depth", maxDepth)
            }
            put("entry_count", entryCount)
            put("truncated", truncated)
            put("listing", listing)
            put(
                "stdout",
                if (listing.isBlank()) {
                    "Directory is empty."
                } else if (truncated) {
                    "$listing\n\nShowing first $maxEntries entries."
                } else {
                    listing
                }
            )
            if (execution.stderr.isNotBlank()) {
                put("stderr", execution.stderr)
            }
        }.toString()
    }

    private suspend fun runStructuredScript(
        commandSummary: String,
        path: String,
        workingDirectory: String,
        script: String,
    ): StructuredScriptExecution {
        val raw = parseArguments(commandExecutor.executeCommand(script, workingDirectory))
            ?: return StructuredScriptExecution(
                errorJson = buildToolError(
                    commandSummary = commandSummary,
                    path = path,
                    message = "Termux returned unreadable command output.",
                )
            )

        if (!raw.optBoolean("ok")) {
            return StructuredScriptExecution(
                errorJson = buildToolError(
                    commandSummary = commandSummary,
                    path = path,
                    message = raw.optString("errmsg").ifBlank { "Tool execution failed." },
                    hint = raw.optString("hint"),
                    stdout = raw.optString("stdout"),
                    stderr = raw.optString("stderr"),
                )
            )
        }

        val values = parseKeyValueOutput(raw.optString("stdout"))
        if (values.isEmpty() && raw.optString("stdout").isNotBlank()) {
            return StructuredScriptExecution(
                errorJson = buildToolError(
                    commandSummary = commandSummary,
                    path = path,
                    message = "Tool returned an unreadable payload.",
                    stdout = raw.optString("stdout"),
                    stderr = raw.optString("stderr"),
                )
            )
        }

        return StructuredScriptExecution(
            values = values,
            stderr = raw.optString("stderr"),
        )
    }

    private fun buildReadScript(
        path: String,
        offset: Int,
        limit: Int?,
    ): String = buildString {
        appendCommonShellPreamble(this)
        appendLine("path=\"\$(decode_b64 '${encodeBase64(path)}')\"")
        appendLine("offset=$offset")
        appendLine("limit=${limit ?: -1}")
        appendLine("if [ ! -e \"\$path\" ]; then")
        appendLine("  printf 'File not found: %s\\n' \"\$path\" >&2")
        appendLine("  exit 20")
        appendLine("fi")
        appendLine("if [ ! -f \"\$path\" ]; then")
        appendLine("  printf 'Path is not a file: %s\\n' \"\$path\" >&2")
        appendLine("  exit 21")
        appendLine("fi")
        appendLine("total_lines=\$(awk 'END { print NR }' \"\$path\")")
        appendLine("total_lines=\${total_lines:-0}")
        appendLine("if [ \"\$total_lines\" -eq 0 ] || [ \"\$offset\" -ge \"\$total_lines\" ]; then")
        appendLine("  start_line=0")
        appendLine("  end_line=0")
        appendLine("  returned_line_count=0")
        appendLine("  content=''")
        appendLine("else")
        appendLine("  start_line=\$((offset + 1))")
        appendLine("  if [ \"\$limit\" -gt 0 ]; then")
        appendLine("    end_line=\$((offset + limit))")
        appendLine("    if [ \"\$end_line\" -gt \"\$total_lines\" ]; then")
        appendLine("      end_line=\"\$total_lines\"")
        appendLine("    fi")
        appendLine("  else")
        appendLine("    end_line=\"\$total_lines\"")
        appendLine("  fi")
        appendLine("  returned_line_count=\$((end_line - start_line + 1))")
        appendLine("  content=\"\$(sed -n \"\${start_line},\${end_line}p\" -- \"\$path\"; printf '\\037')\"")
        appendLine("  content=\"\${content%\$'\\037'}\"")
        appendLine("fi")
        appendLine("truncated=false")
        appendLine("if [ \"\$offset\" -gt 0 ] || { [ \"\$end_line\" -gt 0 ] && [ \"\$end_line\" -lt \"\$total_lines\" ]; }; then")
        appendLine("  truncated=true")
        appendLine("fi")
        appendLine("emit_kv total_line_count \"\$total_lines\"")
        appendLine("emit_kv start_line \"\$start_line\"")
        appendLine("emit_kv end_line \"\$end_line\"")
        appendLine("emit_kv returned_line_count \"\$returned_line_count\"")
        appendLine("emit_kv truncated \"\$truncated\"")
        appendLine("emit_kv content_b64 \"\$(encode_b64 \"\$content\")\"")
    }

    private fun buildWriteScript(
        path: String,
        content: String,
    ): String = buildString {
        appendCommonShellPreamble(this)
        appendLine("path=\"\$(decode_b64 '${encodeBase64(path)}')\"")
        appendLine("content=\"\$(decode_b64 '${encodeBase64(content)}')\"")
        appendLine("parent_dir=\$(dirname -- \"\$path\")")
        appendLine("if [ ! -d \"\$parent_dir\" ]; then")
        appendLine("  printf 'Parent directory not found: %s\\n' \"\$parent_dir\" >&2")
        appendLine("  exit 22")
        appendLine("fi")
        appendLine("created=false")
        appendLine("if [ ! -e \"\$path\" ]; then")
        appendLine("  created=true")
        appendLine("fi")
        appendLine("printf '%s' \"\$content\" > \"\$path\"")
        appendLine("bytes_written=\$(wc -c < \"\$path\" | tr -d '[:space:]')")
        appendLine("emit_kv created \"\$created\"")
        appendLine("emit_kv bytes_written \"\$bytes_written\"")
    }

    private fun buildEditScript(
        path: String,
        edits: List<TextEdit>,
    ): String = buildString {
        appendCommonShellPreamble(this)
        appendLine("path=\"\$(decode_b64 '${encodeBase64(path)}')\"")
        appendLine("if [ ! -e \"\$path\" ]; then")
        appendLine("  printf 'File not found: %s\\n' \"\$path\" >&2")
        appendLine("  exit 20")
        appendLine("fi")
        appendLine("if [ ! -f \"\$path\" ]; then")
        appendLine("  printf 'Path is not a file: %s\\n' \"\$path\" >&2")
        appendLine("  exit 21")
        appendLine("fi")
        appendLine("read_file() {")
        appendLine("  local file_path=\"\$1\"")
        appendLine("  local value")
        appendLine("  value=\"\$(cat -- \"\$file_path\"; printf '\\037')\"")
        appendLine("  printf '%s' \"\${value%\$'\\037'}\"")
        appendLine("}")
        appendLine("find_exact_position() {")
        appendLine("  local haystack=\"\$1\"")
        appendLine("  local needle=\"\$2\"")
        appendLine("  local rest=\"\$haystack\"")
        appendLine("  local offset=0")
        appendLine("  local count=0")
        appendLine("  local found=-1")
        appendLine("  if [[ -z \"\$needle\" ]]; then")
        appendLine("    return 2")
        appendLine("  fi")
        appendLine("  while [[ \"\$rest\" == *\"\$needle\"* ]]; do")
        appendLine("    local prefix=\"\${rest%%\"\$needle\"*}\"")
        appendLine("    local position=\$((offset + \${#prefix}))")
        appendLine("    found=\"\$position\"")
        appendLine("    count=\$((count + 1))")
        appendLine("    offset=\$((position + \${#needle}))")
        appendLine("    rest=\"\${haystack:\$offset}\"")
        appendLine("  done")
        appendLine("  if [ \"\$count\" -eq 1 ]; then")
        appendLine("    printf '%s\\n' \"\$found\"")
        appendLine("    return 0")
        appendLine("  fi")
        appendLine("  if [ \"\$count\" -eq 0 ]; then")
        appendLine("    return 3")
        appendLine("  fi")
        appendLine("  return 4")
        appendLine("}")
        appendLine("content=\"\$(read_file \"\$path\")\"")
        appendLine("declare -a olds")
        appendLine("declare -a news")
        edits.forEach { edit ->
            appendLine("olds+=('${encodeBase64(edit.oldText)}')")
            appendLine("news+=('${encodeBase64(edit.newText)}')")
        }
        appendLine("edit_count=\${#olds[@]}")
        appendLine("declare -a starts")
        appendLine("declare -a ends")
        appendLine("declare -a replacements")
        appendLine("for ((i=0; i<edit_count; i++)); do")
        appendLine("  old_text=\"\$(decode_b64 \"\${olds[i]}\")\"")
        appendLine("  new_text=\"\$(decode_b64 \"\${news[i]}\")\"")
        appendLine("  if ! position=\$(find_exact_position \"\$content\" \"\$old_text\"); then")
        appendLine("    status=\$?")
        appendLine("    if [ \"\$status\" -eq 3 ]; then")
        appendLine("      printf 'Edit %s did not match any text.\\n' \"\$((i + 1))\" >&2")
        appendLine("    elif [ \"\$status\" -eq 4 ]; then")
        appendLine("      printf 'Edit %s matched multiple locations. Make oldText more specific.\\n' \"\$((i + 1))\" >&2")
        appendLine("    else")
        appendLine("      printf 'Edit %s is invalid.\\n' \"\$((i + 1))\" >&2")
        appendLine("    fi")
        appendLine("    exit 30")
        appendLine("  fi")
        appendLine("  starts[i]=\"\$position\"")
        appendLine("  ends[i]=\$((position + \${#old_text}))")
        appendLine("  replacements[i]=\"\$new_text\"")
        appendLine("done")
        appendLine("declare -a order")
        appendLine("for ((i=0; i<edit_count; i++)); do")
        appendLine("  order[i]=\"\$i\"")
        appendLine("done")
        appendLine("for ((i=0; i<edit_count; i++)); do")
        appendLine("  for ((j=i+1; j<edit_count; j++)); do")
        appendLine("    if [ \"\${starts[\${order[j]}]}\" -lt \"\${starts[\${order[i]}]}\" ]; then")
        appendLine("      temp=\"\${order[i]}\"")
        appendLine("      order[i]=\"\${order[j]}\"")
        appendLine("      order[j]=\"\$temp\"")
        appendLine("    fi")
        appendLine("  done")
        appendLine("done")
        appendLine("for ((i=1; i<edit_count; i++)); do")
        appendLine("  previous=\"\${order[i-1]}\"")
        appendLine("  current=\"\${order[i]}\"")
        appendLine("  if [ \"\${starts[\$current]}\" -lt \"\${ends[\$previous]}\" ]; then")
        appendLine("    printf 'Requested edits overlap.\\n' >&2")
        appendLine("    exit 31")
        appendLine("  fi")
        appendLine("done")
        appendLine("result=''")
        appendLine("cursor=0")
        appendLine("for index in \"\${order[@]}\"; do")
        appendLine("  start=\"\${starts[\$index]}\"")
        appendLine("  end=\"\${ends[\$index]}\"")
        appendLine("  result+=\"\${content:\$cursor:\$((start - cursor))}\"")
        appendLine("  result+=\"\${replacements[\$index]}\"")
        appendLine("  cursor=\"\$end\"")
        appendLine("done")
        appendLine("result+=\"\${content:\$cursor}\"")
        appendLine("printf '%s' \"\$result\" > \"\$path\"")
        appendLine("bytes_written=\$(wc -c < \"\$path\" | tr -d '[:space:]')")
        appendLine("emit_kv applied_edits \"\$edit_count\"")
        appendLine("emit_kv bytes_written \"\$bytes_written\"")
    }

    private fun buildGrepScript(
        path: String,
        pattern: String,
        isRegex: Boolean,
        caseSensitive: Boolean,
        maxResults: Int,
    ): String = buildString {
        appendCommonShellPreamble(this)
        appendLine("path=\"\$(decode_b64 '${encodeBase64(path)}')\"")
        appendLine("pattern=\"\$(decode_b64 '${encodeBase64(pattern)}')\"")
        appendLine("max_results=$maxResults")
        appendLine("if [ ! -e \"\$path\" ]; then")
        appendLine("  printf 'Path not found: %s\\n' \"\$path\" >&2")
        appendLine("  exit 20")
        appendLine("fi")
        appendLine("pattern_file=\$(mktemp)")
        appendLine("trap 'rm -f \"\$pattern_file\"' EXIT")
        appendLine("printf '%s' \"\$pattern\" > \"\$pattern_file\"")
        appendLine("search_cmd=(grep -n -H -I)")
        if (!caseSensitive) {
            appendLine("search_cmd+=(-i)")
        }
        if (isRegex) {
            appendLine("search_cmd+=(-E -f \"\$pattern_file\")")
        } else {
            appendLine("search_cmd+=(-F -f \"\$pattern_file\")")
        }
        appendLine("if [ -d \"\$path\" ]; then")
        appendLine("  search_cmd+=(-r \"\$path\")")
        appendLine("else")
        appendLine("  search_cmd+=(\"\$path\")")
        appendLine("fi")
        appendLine("set +e")
        appendLine("all_results=\"\$(\"\${search_cmd[@]}\" 2>/dev/null)\"")
        appendLine("search_status=\$?")
        appendLine("set -e")
        appendLine("if [ \"\$search_status\" -gt 1 ]; then")
        appendLine("  printf 'grep failed for %s\\n' \"\$path\" >&2")
        appendLine("  exit 40")
        appendLine("fi")
        appendLine("if [ -n \"\$all_results\" ]; then")
        appendLine("  match_count=\$(printf '%s' \"\$all_results\" | awk 'END { print NR }')")
        appendLine("  limited_results=\"\$(printf '%s' \"\$all_results\" | head -n \"\$max_results\"; printf '\\037')\"")
        appendLine("  limited_results=\"\${limited_results%\$'\\037'}\"")
        appendLine("else")
        appendLine("  match_count=0")
        appendLine("  limited_results=''")
        appendLine("fi")
        appendLine("truncated=false")
        appendLine("if [ \"\$match_count\" -gt \"\$max_results\" ]; then")
        appendLine("  truncated=true")
        appendLine("fi")
        appendLine("emit_kv match_count \"\$match_count\"")
        appendLine("emit_kv truncated \"\$truncated\"")
        appendLine("emit_kv matches_b64 \"\$(encode_b64 \"\$limited_results\")\"")
    }

    private fun buildFindScript(
        path: String,
        pattern: String,
        type: String,
        caseSensitive: Boolean,
        maxDepth: Int?,
        maxResults: Int,
    ): String = buildString {
        appendCommonShellPreamble(this)
        appendLine("path=\"\$(decode_b64 '${encodeBase64(path)}')\"")
        appendLine("pattern=\"\$(decode_b64 '${encodeBase64(pattern)}')\"")
        appendLine("max_results=$maxResults")
        appendLine("if [ ! -e \"\$path\" ]; then")
        appendLine("  printf 'Path not found: %s\\n' \"\$path\" >&2")
        appendLine("  exit 20")
        appendLine("fi")
        appendLine("if [ ! -d \"\$path\" ]; then")
        appendLine("  printf 'Path is not a directory: %s\\n' \"\$path\" >&2")
        appendLine("  exit 21")
        appendLine("fi")
        appendLine("find_cmd=(find \"\$path\" -mindepth 1)")
        if (maxDepth != null) {
            appendLine("find_cmd+=(-maxdepth $maxDepth)")
        }
        when (type) {
            "file" -> appendLine("find_cmd+=(-type f)")
            "directory" -> appendLine("find_cmd+=(-type d)")
        }
        appendLine("if [ ${if (caseSensitive) "true" else "false"} = true ]; then")
        appendLine("  find_cmd+=(-name \"\$pattern\")")
        appendLine("else")
        appendLine("  find_cmd+=(-iname \"\$pattern\")")
        appendLine("fi")
        appendLine("set +e")
        appendLine("all_results=\"\$(\"\${find_cmd[@]}\" | LC_ALL=C sort)\"")
        appendLine("search_status=\$?")
        appendLine("set -e")
        appendLine("if [ \"\$search_status\" -ne 0 ]; then")
        appendLine("  printf 'find failed for %s\\n' \"\$path\" >&2")
        appendLine("  exit 41")
        appendLine("fi")
        appendLine("if [ -n \"\$all_results\" ]; then")
        appendLine("  match_count=\$(printf '%s' \"\$all_results\" | awk 'END { print NR }')")
        appendLine("  limited_results=\"\$(printf '%s' \"\$all_results\" | head -n \"\$max_results\"; printf '\\037')\"")
        appendLine("  limited_results=\"\${limited_results%\$'\\037'}\"")
        appendLine("else")
        appendLine("  match_count=0")
        appendLine("  limited_results=''")
        appendLine("fi")
        appendLine("truncated=false")
        appendLine("if [ \"\$match_count\" -gt \"\$max_results\" ]; then")
        appendLine("  truncated=true")
        appendLine("fi")
        appendLine("emit_kv match_count \"\$match_count\"")
        appendLine("emit_kv truncated \"\$truncated\"")
        appendLine("emit_kv matches_b64 \"\$(encode_b64 \"\$limited_results\")\"")
    }

    private fun buildLsScript(
        path: String,
        recursive: Boolean,
        includeHidden: Boolean,
        maxDepth: Int?,
        maxEntries: Int,
    ): String = buildString {
        appendCommonShellPreamble(this)
        appendLine("path=\"\$(decode_b64 '${encodeBase64(path)}')\"")
        appendLine("max_entries=$maxEntries")
        appendLine("if [ ! -e \"\$path\" ]; then")
        appendLine("  printf 'Path not found: %s\\n' \"\$path\" >&2")
        appendLine("  exit 20")
        appendLine("fi")
        appendLine("if [ -f \"\$path\" ]; then")
        appendLine("  listing=\"\$path\"")
        appendLine("  entry_count=1")
        appendLine("  truncated=false")
        appendLine("else")
        appendLine("  find_cmd=(find \"\$path\" -mindepth 1)")
        val effectiveMaxDepth = when {
            maxDepth != null -> maxDepth
            recursive -> 5
            else -> 1
        }
        appendLine("  find_cmd+=(-maxdepth $effectiveMaxDepth)")
        if (!includeHidden) {
            appendLine("  find_cmd+=(! -path '*/.*' ! -name '.*')")
        }
        appendLine("  set +e")
        appendLine("  all_results=\"\$(\"\${find_cmd[@]}\" | LC_ALL=C sort | while IFS= read -r item; do")
        appendLine("    if [ -d \"\$item\" ]; then")
        appendLine("      printf '%s/\\n' \"\$item\"")
        appendLine("    else")
        appendLine("      printf '%s\\n' \"\$item\"")
        appendLine("    fi")
        appendLine("  done)\"")
        appendLine("  search_status=\$?")
        appendLine("  set -e")
        appendLine("  if [ \"\$search_status\" -ne 0 ]; then")
        appendLine("    printf 'ls failed for %s\\n' \"\$path\" >&2")
        appendLine("    exit 42")
        appendLine("  fi")
        appendLine("  if [ -n \"\$all_results\" ]; then")
        appendLine("    entry_count=\$(printf '%s' \"\$all_results\" | awk 'END { print NR }')")
        appendLine("    listing=\"\$(printf '%s' \"\$all_results\" | head -n \"\$max_entries\"; printf '\\037')\"")
        appendLine("    listing=\"\${listing%\$'\\037'}\"")
        appendLine("  else")
        appendLine("    entry_count=0")
        appendLine("    listing=''")
        appendLine("  fi")
        appendLine("  truncated=false")
        appendLine("  if [ \"\$entry_count\" -gt \"\$max_entries\" ]; then")
        appendLine("    truncated=true")
        appendLine("  fi")
        appendLine("fi")
        appendLine("emit_kv entry_count \"\$entry_count\"")
        appendLine("emit_kv truncated \"\$truncated\"")
        appendLine("emit_kv listing_b64 \"\$(encode_b64 \"\$listing\")\"")
    }

    private fun buildReadCommandSummary(
        path: String,
        offset: Int,
        limit: Int?,
        showLineNumbers: Boolean,
    ): String = buildString {
        append("read ")
        append(path)
        if (offset > 0 || limit != null) {
            append(" (offset=")
            append(offset)
            if (limit != null) {
                append(", limit=")
                append(limit)
            }
            append(')')
        }
        if (showLineNumbers) {
            append(" [line numbers]")
        }
    }

    private fun buildReadStdout(
        path: String,
        content: String,
        startLine: Int,
        endLine: Int,
        totalLineCount: Int,
        truncated: Boolean,
        showLineNumbers: Boolean,
    ): String {
        if (totalLineCount == 0) {
            return "$path is empty."
        }
        val header = if (startLine > 0 && endLine >= startLine) {
            "Showing lines $startLine-$endLine of $totalLineCount from $path."
        } else {
            "Showing $path."
        }
        val displayContent = if (showLineNumbers) {
            addLineNumbers(
                content = content,
                startLine = startLine,
            )
        } else {
            content
        }
        return buildString {
            append(header)
            if (displayContent.isNotBlank()) {
                append("\n\n")
                append(displayContent)
            }
            if (truncated) {
                append("\n\nOutput was truncated.")
            }
        }
    }

    private fun addLineNumbers(
        content: String,
        startLine: Int,
    ): String {
        if (content.isBlank()) return content
        val firstLineNumber = startLine.takeIf { it > 0 } ?: 1
        return content
            .split('\n')
            .mapIndexed { index, line ->
                "${firstLineNumber + index}: $line"
            }
            .joinToString("\n")
    }

    private fun buildSearchStdout(
        matches: String,
        matchCount: Int,
        truncated: Boolean,
        maxResults: Int,
    ): String {
        if (matchCount == 0) {
            return "No matches."
        }
        return buildString {
            append(matches)
            if (truncated) {
                append("\n\nShowing first $maxResults matches.")
            }
        }
    }

    private fun parseEdits(edits: JSONArray?): List<TextEdit> {
        if (edits == null) return emptyList()
        return buildList {
            for (index in 0 until edits.length()) {
                val item = edits.optJSONObject(index) ?: continue
                if (!item.has("oldText") || !item.has("newText")) continue
                add(
                    TextEdit(
                        oldText = item.optString("oldText"),
                        newText = item.optString("newText"),
                    )
                )
            }
        }
    }

    private fun buildToolError(
        commandSummary: String,
        path: String,
        message: String,
        hint: String = "",
        stdout: String = "",
        stderr: String = "",
    ): String = JSONObject().apply {
        put("ok", false)
        put("command", commandSummary)
        put("path", path)
        put("errmsg", message)
        put("stdout", stdout)
        put("stderr", stderr)
        if (hint.isNotBlank()) {
            put("hint", hint)
        }
    }.toString()

    private fun invalidArguments(message: String): String = JSONObject().apply {
        put("ok", false)
        put("errmsg", message)
    }.toString()

    private fun parseArguments(argumentsJson: String): JSONObject? =
        runCatching { JSONObject(argumentsJson) }.getOrNull()

    private fun parseKeyValueOutput(stdout: String): Map<String, String> = buildMap {
        stdout.lineSequence()
            .filter { it.isNotBlank() }
            .forEach { line ->
                val separatorIndex = line.indexOf('=')
                if (separatorIndex <= 0) return@forEach
                put(
                    line.substring(0, separatorIndex),
                    line.substring(separatorIndex + 1),
                )
            }
    }

    private fun encodeBase64(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decodeBase64(value: String): String =
        if (value.isBlank()) "" else String(Base64.getDecoder().decode(value), Charsets.UTF_8)

    private fun quoteSummary(value: String): String =
        "\"" + value.replace("\"", "\\\"") + "\""

    private fun normalizeTermuxPath(path: String): String = when {
        path == "~" -> homeDirectory
        path.startsWith("~/") -> homeDirectory + path.removePrefix("~")
        else -> path
    }

    private fun appendCommonShellPreamble(builder: StringBuilder) {
        builder.appendLine("set -euo pipefail")
        builder.appendLine("decode_b64() {")
        builder.appendLine("  printf '%s' \"\$1\" | base64 -d")
        builder.appendLine("}")
        builder.appendLine("encode_b64() {")
        builder.appendLine("  printf '%s' \"\$1\" | base64 | tr -d '\\n'")
        builder.appendLine("}")
        builder.appendLine("emit_kv() {")
        builder.appendLine("  printf '%s=%s\\n' \"\$1\" \"\$2\"")
        builder.appendLine("}")
    }
}

fun interface ShellCommandExecutor {
    suspend fun executeCommand(
        command: String,
        workingDirectory: String,
    ): String
}

private data class TextEdit(
    val oldText: String,
    val newText: String,
)

private data class StructuredScriptExecution(
    val values: Map<String, String> = emptyMap(),
    val stderr: String = "",
    val errorJson: String? = null,
)
