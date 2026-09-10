package kira.ditto.data.pi

import kira.ditto.runtime.MultiplatformLocalRuntime
import kira.ditto.runtime.RuntimeProcessExit
import kira.ditto.runtime.RuntimeProcessSpec
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val DefaultSearchResultLimit = 200
private const val DefaultLsEntryLimit = 200
private const val AlpineRuntimeName = "alpine"

data class SharedHostToolResult(
    val outputJson: String,
    val isError: Boolean = false,
)

interface SharedHostToolExecutor {
    val definitions: JsonArray
    suspend fun execute(name: String, arguments: JsonObject): SharedHostToolResult
}

interface SharedSessionAwareHostToolExecutor : SharedHostToolExecutor {
    fun definitions(sessionId: String): JsonArray
}

class RuntimeHostToolExecutor(
    private val runtime: MultiplatformLocalRuntime,
) : SharedHostToolExecutor {
    // Pi Coding Agent owns read/bash/edit/write/grep/find/ls on every platform.
    // The legacy executor remains available to migrate old callers, but it is
    // deliberately not exposed as a model Host tool.
    override val definitions: JsonArray = JsonArray(emptyList())

    override suspend fun execute(name: String, arguments: JsonObject): SharedHostToolResult = try {
        when (name) {
            "read" -> read(arguments)
            "write" -> write(arguments)
            "edit" -> edit(arguments)
            "grep" -> grep(arguments)
            "find" -> find(arguments)
            "ls" -> ls(arguments)
            "bash" -> bash(arguments)
            else -> toolError("Unknown tool '$name'.", errorKey = "error")
        }
    } catch (cancellationException: CancellationException) {
        throw cancellationException
    } catch (error: Throwable) {
        toolError(
            message = error.message ?: "Host tool failed.",
            includeRuntime = name in RuntimeToolNames,
        )
    }

    private suspend fun read(arguments: JsonObject): SharedHostToolResult {
        val rawPath = arguments.requiredString("path")
        val offset = arguments.int("offset") ?: 0
        val limit = arguments.int("limit")
        if (offset < 0) return invalidArguments("'offset' must be 0 or greater.")
        if (limit != null && limit <= 0) {
            return invalidArguments("'limit' must be greater than 0 when provided.")
        }

        val resolvedPath = resolvePath(rawPath, arguments)
        if (!runtime.fileSystem.exists(resolvedPath)) {
            return fileToolError(
                command = buildReadCommandSummary(rawPath, offset, limit, showLineNumbers = false),
                path = rawPath,
                message = "File not found: $rawPath",
            )
        }
        val text = runtime.fileSystem.read(resolvedPath).decodeToString()
        val lineStarts = text.lineStarts()
        val totalLineCount = lineStarts.size
        val returnedLineCount = if (offset >= totalLineCount) {
            0
        } else {
            minOf(limit ?: Int.MAX_VALUE, totalLineCount - offset)
        }
        val startLine = if (returnedLineCount == 0) 0 else offset + 1
        val endLine = if (returnedLineCount == 0) 0 else offset + returnedLineCount
        val content = if (returnedLineCount == 0) {
            ""
        } else {
            val startIndex = lineStarts[offset]
            val endIndex = lineStarts.getOrNull(offset + returnedLineCount) ?: text.length
            text.substring(startIndex, endIndex)
        }
        val showLineNumbers = arguments.boolean("showLineNumbers") ||
            arguments.boolean("show_line_numbers")
        val truncated = offset > 0 || (endLine > 0 && endLine < totalLineCount)
        val commandSummary = buildReadCommandSummary(rawPath, offset, limit, showLineNumbers)

        return toolSuccess {
            put("runtime", AlpineRuntimeName)
            put("command", commandSummary)
            put("path", rawPath)
            put("content", content)
            put("offset", offset)
            put("limit", limit?.let(::JsonPrimitive) ?: JsonNull)
            put("show_line_numbers", showLineNumbers)
            put("total_line_count", totalLineCount)
            put("start_line", startLine)
            put("end_line", endLine)
            put("returned_line_count", returnedLineCount)
            put("truncated", truncated)
            put(
                "stdout",
                buildReadStdout(
                    path = rawPath,
                    content = content,
                    startLine = startLine,
                    endLine = endLine,
                    totalLineCount = totalLineCount,
                    truncated = truncated,
                    showLineNumbers = showLineNumbers,
                ),
            )
        }
    }

    private suspend fun write(arguments: JsonObject): SharedHostToolResult {
        val rawPath = arguments.requiredString("path")
        if (!arguments.containsKey("content")) {
            return invalidArguments("Missing required 'content' argument.")
        }
        val content = arguments.string("content")
        val resolvedPath = resolvePath(rawPath, arguments)
        val parentDirectory = resolvedPath.substringBeforeLast('/').ifBlank { "/" }
        if (!runtime.fileSystem.exists(parentDirectory)) {
            return fileToolError(
                command = "write $rawPath",
                path = rawPath,
                message = "Parent directory not found: $parentDirectory",
            )
        }
        val created = !runtime.fileSystem.exists(resolvedPath)
        val bytes = content.encodeToByteArray()
        runtime.fileSystem.write(resolvedPath, bytes)

        return toolSuccess {
            put("runtime", AlpineRuntimeName)
            put("command", "write $rawPath")
            put("path", rawPath)
            put("created", created)
            put("bytes_written", bytes.size)
            put(
                "stdout",
                if (created) {
                    "Created $rawPath (${bytes.size} bytes)."
                } else {
                    "Overwrote $rawPath (${bytes.size} bytes)."
                },
            )
        }
    }

    private suspend fun edit(arguments: JsonObject): SharedHostToolResult {
        val rawPath = arguments.requiredString("path")
        val batchEdits = arguments.parseEdits()
        val hasSingleEdit = arguments.containsKey("oldText") || arguments.containsKey("newText")
        val edits = when {
            batchEdits.isNotEmpty() -> batchEdits
            hasSingleEdit && (!arguments.containsKey("oldText") || !arguments.containsKey("newText")) -> {
                return invalidArguments("Single edit mode requires both 'oldText' and 'newText'.")
            }
            hasSingleEdit -> listOf(TextEdit(arguments.string("oldText"), arguments.string("newText")))
            else -> return invalidArguments(
                "Provide either 'oldText'/'newText' for one edit or a non-empty 'edits' array for multiple edits.",
            )
        }
        if (edits.any { it.oldText.isEmpty() }) {
            return invalidArguments("Each edit requires a non-empty 'oldText'.")
        }

        val resolvedPath = resolvePath(rawPath, arguments)
        if (!runtime.fileSystem.exists(resolvedPath)) {
            return fileToolError(
                command = editCommandSummary(rawPath, edits.size),
                path = rawPath,
                message = "File not found: $rawPath",
            )
        }
        val current = runtime.fileSystem.read(resolvedPath).decodeToString()
        val positioned = mutableListOf<PositionedEdit>()
        edits.forEachIndexed { index, edit ->
            val positions = current.nonOverlappingIndicesOf(edit.oldText)
            if (positions.isEmpty()) {
                return fileToolError(
                    command = editCommandSummary(rawPath, edits.size),
                    path = rawPath,
                    message = "Edit ${index + 1} did not match any text.",
                )
            }
            if (positions.size > 1) {
                return fileToolError(
                    command = editCommandSummary(rawPath, edits.size),
                    path = rawPath,
                    message = "Edit ${index + 1} matched multiple locations. Make oldText more specific.",
                )
            }
            positioned += PositionedEdit(
                start = positions.single(),
                end = positions.single() + edit.oldText.length,
                replacement = edit.newText,
            )
        }
        val sorted = positioned.sortedBy(PositionedEdit::start)
        if (sorted.zipWithNext().any { (left, right) -> right.start < left.end }) {
            return fileToolError(
                command = editCommandSummary(rawPath, edits.size),
                path = rawPath,
                message = "Requested edits overlap.",
            )
        }
        var updated = current
        sorted.asReversed().forEach { edit ->
            updated = updated.replaceRange(edit.start, edit.end, edit.replacement)
        }
        val bytes = updated.encodeToByteArray()
        runtime.fileSystem.write(resolvedPath, bytes)

        return toolSuccess {
            put("runtime", AlpineRuntimeName)
            put("command", editCommandSummary(rawPath, edits.size))
            put("path", rawPath)
            put("applied_edits", edits.size)
            put("bytes_written", bytes.size)
            put(
                "stdout",
                "Applied ${edits.size} precise edit${if (edits.size == 1) "" else "s"} to $rawPath.",
            )
        }
    }

    private suspend fun grep(arguments: JsonObject): SharedHostToolResult {
        val rawPath = arguments.requiredString("path")
        val pattern = arguments.requiredString("pattern", allowBlank = false)
        val isRegex = arguments.boolean("isRegex")
        val caseSensitive = arguments.boolean("caseSensitive", default = true)
        val maxResults = arguments.int("maxResults") ?: DefaultSearchResultLimit
        if (maxResults <= 0) return invalidArguments("'maxResults' must be greater than 0.")
        val resolvedPath = resolvePath(rawPath, arguments)
        if (!runtime.fileSystem.exists(resolvedPath)) {
            return fileToolError(
                command = grepCommandSummary(pattern, isRegex, rawPath),
                path = rawPath,
                message = "Path not found: $rawPath",
            )
        }
        val flags = buildString {
            append("-r -n -H -I ")
            if (!caseSensitive) append("-i ")
            append(if (isRegex) "-E" else "-F")
        }
        val output = executeCommand(
            command = "grep $flags -- ${pattern.shellQuote()} ${shellPath(rawPath).shellQuote()}",
            workingDirectory = resolveWorkingDirectory(arguments),
        )
        if (output.exit.exitCode > 1) {
            return fileToolError(
                command = grepCommandSummary(pattern, isRegex, rawPath),
                path = rawPath,
                message = "grep failed for $rawPath",
                stdout = output.stdout,
                stderr = output.stderr,
            )
        }
        val allMatches = output.stdout.trimEnd('\n', '\r')
        val lines = allMatches.nonEmptyLines()
        val limitedMatches = lines.take(maxResults).joinToString("\n")
        val truncated = lines.size > maxResults

        return toolSuccess {
            put("runtime", AlpineRuntimeName)
            put("command", grepCommandSummary(pattern, isRegex, rawPath))
            put("path", rawPath)
            put("pattern", pattern)
            put("is_regex", isRegex)
            put("case_sensitive", caseSensitive)
            put("match_count", lines.size)
            put("truncated", truncated)
            put("matches", limitedMatches)
            put("stdout", buildSearchStdout(limitedMatches, lines.size, truncated, maxResults))
            if (output.stderr.isNotBlank()) put("stderr", output.stderr)
        }
    }

    private suspend fun find(arguments: JsonObject): SharedHostToolResult {
        val rawPath = arguments.requiredString("path")
        val pattern = arguments.requiredString("pattern", allowBlank = false)
        val type = arguments.string("type").trim().ifBlank { "any" }
        val caseSensitive = arguments.boolean("caseSensitive", default = true)
        val maxDepth = arguments.int("maxDepth")
        val maxResults = arguments.int("maxResults") ?: DefaultSearchResultLimit
        if (type !in setOf("any", "file", "directory")) {
            return invalidArguments("'type' must be 'any', 'file', or 'directory'.")
        }
        if (maxDepth != null && maxDepth < 0) {
            return invalidArguments("'maxDepth' must be 0 or greater when provided.")
        }
        if (maxResults <= 0) return invalidArguments("'maxResults' must be greater than 0.")
        val resolvedPath = resolvePath(rawPath, arguments)
        if (!runtime.fileSystem.exists(resolvedPath)) {
            return fileToolError(
                command = findCommandSummary(pattern, rawPath),
                path = rawPath,
                message = "Path not found: $rawPath",
            )
        }
        val findCommand = buildString {
            append("find ")
            append(shellPath(rawPath).shellQuote())
            append(" -mindepth 1")
            if (maxDepth != null) append(" -maxdepth $maxDepth")
            when (type) {
                "file" -> append(" -type f")
                "directory" -> append(" -type d")
            }
            append(if (caseSensitive) " -name " else " -iname ")
            append(pattern.shellQuote())
            append(" -print | LC_ALL=C sort")
        }
        val shellPath = shellPath(rawPath).shellQuote()
        val command = "if [ ! -d $shellPath ]; then exit 21; fi; $findCommand"
        val output = executeCommand(command, resolveWorkingDirectory(arguments))
        if (output.exit.exitCode != 0) {
            return fileToolError(
                command = findCommandSummary(pattern, rawPath),
                path = rawPath,
                message = "find failed for $rawPath",
                stdout = output.stdout,
                stderr = output.stderr,
            )
        }
        val lines = output.stdout.trimEnd('\n', '\r').nonEmptyLines()
        val matches = lines.take(maxResults).joinToString("\n")
        val truncated = lines.size > maxResults

        return toolSuccess {
            put("runtime", AlpineRuntimeName)
            put("command", findCommandSummary(pattern, rawPath))
            put("path", rawPath)
            put("pattern", pattern)
            put("type", type)
            put("case_sensitive", caseSensitive)
            if (maxDepth != null) put("max_depth", maxDepth)
            put("match_count", lines.size)
            put("truncated", truncated)
            put("matches", matches)
            put("stdout", buildSearchStdout(matches, lines.size, truncated, maxResults))
            if (output.stderr.isNotBlank()) put("stderr", output.stderr)
        }
    }

    private suspend fun ls(arguments: JsonObject): SharedHostToolResult {
        val rawPath = arguments.requiredString("path")
        val recursive = arguments.boolean("recursive")
        val includeHidden = arguments.boolean("includeHidden")
        val maxDepth = arguments.int("maxDepth")
        val maxEntries = arguments.int("maxEntries") ?: DefaultLsEntryLimit
        if (maxDepth != null && maxDepth < 0) {
            return invalidArguments("'maxDepth' must be 0 or greater when provided.")
        }
        if (maxEntries <= 0) return invalidArguments("'maxEntries' must be greater than 0.")
        val resolvedPath = resolvePath(rawPath, arguments)
        if (!runtime.fileSystem.exists(resolvedPath)) {
            return fileToolError(
                command = "ls $rawPath",
                path = rawPath,
                message = "Path not found: $rawPath",
            )
        }
        val effectiveMaxDepth = maxDepth ?: if (recursive) 5 else 1
        val path = shellPath(rawPath).shellQuote()
        val hiddenFilter = if (includeHidden) "" else " ! -path '*/.*' ! -name '.*'"
        val command = buildString {
            append("if [ -f $path ]; then printf '%s\\n' $path; ")
            append("elif [ -d $path ]; then ")
            append("find $path -mindepth 1 -maxdepth $effectiveMaxDepth$hiddenFilter -print")
            append(" | while IFS= read -r item; do ")
            append("if [ -d \"\$item\" ]; then printf '%s/\\n' \"\$item\"; ")
            append("else printf '%s\\n' \"\$item\"; fi; done | LC_ALL=C sort; ")
            append("else exit 20; fi")
        }
        val output = executeCommand(command, resolveWorkingDirectory(arguments))
        if (output.exit.exitCode != 0) {
            return fileToolError(
                command = "ls $rawPath",
                path = rawPath,
                message = "ls failed for $rawPath",
                stdout = output.stdout,
                stderr = output.stderr,
            )
        }
        val entries = output.stdout.trimEnd('\n', '\r').nonEmptyLines()
        val listing = entries.take(maxEntries).joinToString("\n")
        val truncated = entries.size > maxEntries

        return toolSuccess {
            put("runtime", AlpineRuntimeName)
            put("command", "ls $rawPath")
            put("path", rawPath)
            put("recursive", recursive)
            put("include_hidden", includeHidden)
            if (maxDepth != null) put("max_depth", maxDepth)
            put("entry_count", entries.size)
            put("truncated", truncated)
            put("listing", listing)
            put(
                "stdout",
                when {
                    listing.isBlank() -> "Directory is empty."
                    truncated -> "$listing\n\nShowing first $maxEntries entries."
                    else -> listing
                },
            )
            if (output.stderr.isNotBlank()) put("stderr", output.stderr)
        }
    }

    private suspend fun bash(arguments: JsonObject): SharedHostToolResult {
        val command = arguments.requiredString("command", allowBlank = false)
        val workingDirectory = resolveWorkingDirectory(arguments)
        val output = executeCommand(command, workingDirectory)
        val ok = output.exit.exitCode == 0
        return SharedHostToolResult(
            outputJson = buildJsonObject {
                put("ok", ok)
                put("runtime", AlpineRuntimeName)
                put("command", command)
                put("working_directory", workingDirectory)
                put("status", if (ok) "completed" else "failed")
                put("stdout", output.stdout)
                put("stderr", output.stderr)
                put("exit_code", output.exit.exitCode)
                if (!ok) put("errmsg", "Alpine command exited with code ${output.exit.exitCode}.")
            }.toString(),
            isError = !ok,
        )
    }

    private suspend fun executeCommand(command: String, workingDirectory: String): ProcessOutput = coroutineScope {
        val process = runtime.startProcess(processSpec(command, workingDirectory))
        process.closeStdin()
        val stdout = async { process.stdout.toList().flattenBytes().decodeToString() }
        val stderr = async { process.stderr.toList().flattenBytes().decodeToString() }
        val exit = process.awaitExit()
        ProcessOutput(exit, stdout.await(), stderr.await())
    }

    private fun processSpec(command: String, workingDirectory: String) = RuntimeProcessSpec(
        executable = "/bin/sh",
        arguments = listOf("-lc", command),
        environment = mapOf(
            "HOME" to runtime.homeDirectory,
            "AETHER_WORKSPACE" to runtime.workspaceRoot,
        ),
        workingDirectory = workingDirectory,
    )

    private fun resolvePath(rawPath: String, arguments: JsonObject): String =
        normalizeGuestPath(rawPath, resolveWorkingDirectory(arguments))

    private fun shellPath(rawPath: String): String = when {
        rawPath == "~" -> runtime.homeDirectory
        rawPath.startsWith("~/") -> runtime.homeDirectory + rawPath.removePrefix("~")
        else -> rawPath
    }

    private fun resolveWorkingDirectory(arguments: JsonObject): String {
        val raw = arguments.string("working_directory")
            .ifBlank { arguments.string("workingDirectory") }
            .ifBlank { runtime.workspaceRoot }
        return normalizeGuestPath(raw, runtime.workspaceRoot)
    }

    private fun normalizeGuestPath(rawPath: String, workingDirectory: String): String {
        val expanded = when {
            rawPath == "~" -> runtime.homeDirectory
            rawPath.startsWith("~/") -> runtime.homeDirectory + rawPath.removePrefix("~")
            rawPath.startsWith('/') -> rawPath
            else -> "${workingDirectory.trimEnd('/')}/$rawPath"
        }
        val parts = mutableListOf<String>()
        expanded.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts += part
            }
        }
        return "/" + parts.joinToString("/")
    }
}

private fun sharedRuntimeHostToolDefinitions(): JsonArray = buildJsonArray {
    add(
        toolDefinition(
            name = "read",
            description = "Read a text file from the built-in local runtime with optional line-based offset and limit. path accepts ~ or ~/... for the runtime's home directory.",
            executionMode = "parallel",
            required = listOf("path"),
            "path" to stringProperty("The file path to read."),
            "offset" to integerProperty("Optional zero-based line offset to start reading from."),
            "limit" to integerProperty("Optional maximum number of lines to return."),
            "showLineNumbers" to booleanProperty("Whether stdout should prefix each returned line with its original 1-based line number."),
            "show_line_numbers" to booleanProperty("Alias of showLineNumbers."),
            "workingDirectory" to stringProperty("Optional working directory used to resolve relative paths."),
            "working_directory" to stringProperty("Alias of workingDirectory."),
        ),
    )
    add(
        toolDefinition(
            name = "edit",
            description = "Precisely edit a text file in the built-in local runtime using exact oldText/newText replacements. For multiple edits use only edits[]. path accepts ~ or ~/... for the runtime's home directory.",
            executionMode = "sequential",
            required = listOf("path"),
            "path" to stringProperty("The file path to edit."),
            "oldText" to stringProperty("For a single edit only, the exact text to replace. Omit this when using edits[]."),
            "newText" to stringProperty("For a single edit only, the replacement text. Omit this when using edits[]."),
            "edits" to buildJsonObject {
                put("type", "array")
                put("description", "For multiple edits only, a list of non-overlapping precise replacements.")
                put("items", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("oldText", stringProperty("The exact text to replace."))
                        put("newText", stringProperty("The replacement text."))
                    })
                    put("required", buildJsonArray {
                        add(JsonPrimitive("oldText"))
                        add(JsonPrimitive("newText"))
                    })
                    put("additionalProperties", false)
                })
            },
            "workingDirectory" to stringProperty("Optional working directory used to resolve relative paths."),
            "working_directory" to stringProperty("Alias of workingDirectory."),
        ),
    )
    add(
        toolDefinition(
            name = "write",
            description = "Create a new text file or completely overwrite an existing text file in the built-in local runtime. path accepts ~ or ~/... for the runtime's home directory.",
            executionMode = "sequential",
            required = listOf("path", "content"),
            "path" to stringProperty("The file path to create or overwrite."),
            "content" to stringProperty("The full file contents to write."),
            "workingDirectory" to stringProperty("Optional working directory used to resolve relative paths."),
            "working_directory" to stringProperty("Alias of workingDirectory."),
        ),
    )
    add(
        toolDefinition(
            name = "grep",
            description = "Search for text or a regex pattern inside a file or directory tree in the built-in local runtime. path accepts ~ or ~/... for the runtime's home directory.",
            executionMode = "parallel",
            required = listOf("path", "pattern"),
            "path" to stringProperty("The file or directory path to search."),
            "pattern" to stringProperty("The text or regex pattern to search for."),
            "isRegex" to booleanProperty("Whether pattern should be treated as a regex."),
            "caseSensitive" to booleanProperty("Whether the search should be case-sensitive."),
            "maxResults" to integerProperty("Optional maximum number of matches to return."),
            "workingDirectory" to stringProperty("Optional working directory used to resolve relative paths."),
            "working_directory" to stringProperty("Alias of workingDirectory."),
        ),
    )
    add(
        toolDefinition(
            name = "find",
            description = "Find files or directories by glob pattern in the built-in local runtime. path accepts ~ or ~/... for the runtime's home directory.",
            executionMode = "parallel",
            required = listOf("path", "pattern"),
            "path" to stringProperty("The directory path to search in."),
            "pattern" to stringProperty("The glob pattern to match, such as *.kt."),
            "type" to stringProperty("Optional match type: any, file, or directory."),
            "caseSensitive" to booleanProperty("Whether the glob match should be case-sensitive."),
            "maxDepth" to integerProperty("Optional maximum search depth."),
            "maxResults" to integerProperty("Optional maximum number of results to return."),
            "workingDirectory" to stringProperty("Optional working directory used to resolve relative paths."),
            "working_directory" to stringProperty("Alias of workingDirectory."),
        ),
    )
    add(
        toolDefinition(
            name = "ls",
            description = "List the contents of a directory or inspect a file path in the built-in local runtime. path accepts ~ or ~/... for the runtime's home directory.",
            executionMode = "parallel",
            required = listOf("path"),
            "path" to stringProperty("The file or directory path to list."),
            "recursive" to booleanProperty("Whether to list recursively."),
            "includeHidden" to booleanProperty("Whether to include hidden files and directories."),
            "maxDepth" to integerProperty("Optional maximum recursion depth."),
            "maxEntries" to integerProperty("Optional maximum number of entries to return."),
            "workingDirectory" to stringProperty("Optional working directory used to resolve relative paths."),
            "working_directory" to stringProperty("Alias of workingDirectory."),
        ),
    )
    add(
        toolDefinition(
            name = "bash",
            description = "Execute a bash command in the built-in local runtime and wait for it to exit.",
            executionMode = "sequential",
            required = listOf("command"),
            "command" to stringProperty("The bash command or script to execute."),
            "working_directory" to stringProperty("Optional working directory inside the local runtime."),
            "workingDirectory" to stringProperty("Alias of working_directory."),
        ),
    )
}

private fun toolDefinition(
    name: String,
    description: String,
    executionMode: String,
    required: List<String>,
    vararg properties: Pair<String, JsonObject>,
): JsonObject = buildJsonObject {
    put("name", name)
    put("description", description)
    put("parameters", buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            properties.forEach { (propertyName, definition) -> put(propertyName, definition) }
        })
        put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
        put("additionalProperties", false)
    })
    put("execution_mode", executionMode)
}

private fun stringProperty(description: String): JsonObject = property("string", description)
private fun integerProperty(description: String): JsonObject = property("integer", description)
private fun booleanProperty(description: String): JsonObject = property("boolean", description)

private fun property(type: String, description: String): JsonObject = buildJsonObject {
    put("type", type)
    put("description", description)
}

private fun toolSuccess(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): SharedHostToolResult =
    SharedHostToolResult(
        outputJson = buildJsonObject {
            put("ok", true)
            block()
        }.toString(),
    )

private fun invalidArguments(message: String): SharedHostToolResult = toolError(message)

private fun toolError(
    message: String,
    includeRuntime: Boolean = false,
    errorKey: String = "errmsg",
): SharedHostToolResult = SharedHostToolResult(
    outputJson = buildJsonObject {
        put("ok", false)
        if (includeRuntime) put("runtime", AlpineRuntimeName)
        put(errorKey, message)
    }.toString(),
    isError = true,
)

private fun fileToolError(
    command: String,
    path: String,
    message: String,
    stdout: String = "",
    stderr: String = "",
): SharedHostToolResult = SharedHostToolResult(
    outputJson = buildJsonObject {
        put("ok", false)
        put("runtime", AlpineRuntimeName)
        put("command", command)
        put("path", path)
        put("errmsg", message)
        put("stdout", stdout)
        put("stderr", stderr)
    }.toString(),
    isError = true,
)

private fun JsonObject.requiredString(name: String, allowBlank: Boolean = true): String {
    if (!containsKey(name)) throw IllegalArgumentException("Missing required '$name' argument.")
    val value = string(name).let { raw ->
        if (name == "path" || name == "command") raw.trim() else raw
    }
    return value.also {
        if (!allowBlank && value.isEmpty()) {
            throw IllegalArgumentException("Missing required '$name' argument.")
        }
        if (name == "path" && value.isBlank()) {
            throw IllegalArgumentException("Missing required 'path' argument.")
        }
    }
}

private fun JsonObject.string(name: String): String =
    get(name)?.jsonPrimitive?.contentOrNull.orEmpty()

private fun JsonObject.int(name: String): Int? = get(name)?.jsonPrimitive?.intOrNull

private fun JsonObject.boolean(name: String, default: Boolean = false): Boolean =
    get(name)?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: default

private fun JsonObject.parseEdits(): List<TextEdit> =
    ((get("edits") as? JsonArray)?.mapNotNull { item ->
        val edit = item as? JsonObject ?: return@mapNotNull null
        if (!edit.containsKey("oldText") || !edit.containsKey("newText")) return@mapNotNull null
        TextEdit(edit.string("oldText"), edit.string("newText"))
    }).orEmpty()

private fun String.lineStarts(): List<Int> {
    if (isEmpty()) return emptyList()
    return buildList {
        add(0)
        for (index in this@lineStarts.indices) {
            if (
                this@lineStarts[index] == '\n' &&
                index + 1 < this@lineStarts.length
            ) {
                add(index + 1)
            }
        }
    }
}

private fun String.nonOverlappingIndicesOf(needle: String): List<Int> = buildList {
    var fromIndex = 0
    while (fromIndex <= this@nonOverlappingIndicesOf.length - needle.length) {
        val index = this@nonOverlappingIndicesOf.indexOf(needle, fromIndex)
        if (index < 0) break
        add(index)
        fromIndex = index + needle.length
    }
}

private fun String.nonEmptyLines(): List<String> = if (isBlank()) emptyList() else lines()

private fun String.shellQuote(): String = "'" + replace("'", "'\\''") + "'"

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
    if (showLineNumbers) append(" [line numbers]")
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
    if (totalLineCount == 0) return "$path is empty."
    val header = if (startLine > 0 && endLine >= startLine) {
        "Showing lines $startLine-$endLine of $totalLineCount from $path."
    } else {
        "Showing $path."
    }
    val displayContent = if (showLineNumbers) {
        if (content.isBlank()) content else content.split('\n').mapIndexed { index, line ->
            "${(startLine.takeIf { it > 0 } ?: 1) + index}: $line"
        }.joinToString("\n")
    } else {
        content
    }
    return buildString {
        append(header)
        if (displayContent.isNotBlank()) {
            append("\n\n")
            append(displayContent)
        }
        if (truncated) append("\n\nOutput was truncated.")
    }
}

private fun buildSearchStdout(
    matches: String,
    matchCount: Int,
    truncated: Boolean,
    maxResults: Int,
): String = when {
    matchCount == 0 -> "No matches."
    truncated -> "$matches\n\nShowing first $maxResults matches."
    else -> matches
}

private fun editCommandSummary(path: String, count: Int): String =
    "edit $path ($count edit${if (count == 1) "" else "s"})"

private fun grepCommandSummary(pattern: String, isRegex: Boolean, path: String): String =
    "grep ${if (isRegex) "regex" else "text"} ${pattern.quoteSummary()} in $path"

private fun findCommandSummary(pattern: String, path: String): String =
    "find ${pattern.quoteSummary()} in $path"

private fun String.quoteSummary(): String = "\"" + replace("\"", "\\\"") + "\""

private fun List<ByteArray>.flattenBytes(maximumBytes: Int = 8 * 1024 * 1024): ByteArray {
    val size = sumOf(ByteArray::size).coerceAtMost(maximumBytes)
    val output = ByteArray(size)
    var offset = 0
    for (chunk in this) {
        if (offset >= size) break
        val count = minOf(chunk.size, size - offset)
        chunk.copyInto(output, offset, 0, count)
        offset += count
    }
    return output
}

private data class TextEdit(val oldText: String, val newText: String)
private data class PositionedEdit(val start: Int, val end: Int, val replacement: String)
private data class ProcessOutput(
    val exit: RuntimeProcessExit,
    val stdout: String,
    val stderr: String,
)

private val RuntimeToolNames = setOf(
    "read",
    "write",
    "edit",
    "grep",
    "find",
    "ls",
    "bash",
)
