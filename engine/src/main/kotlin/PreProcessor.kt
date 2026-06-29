import java.io.File

object PreProcessor {
    val REGION_NAME_REGEX = "[a-zA-Z_][a-zA-Z0-9_]*".toRegex()
    val REF_REGEX = Regex("""^<<<\s+@/(?<path>.*?)(?<region>#.*)?$""", RegexOption.MULTILINE)

    private val languageMap = mapOf(
        "kt" to "kotlin",
        "js" to "javascript",
        "py" to "python",
        "cs" to "csharp",
        "rb" to "ruby",
        "rs" to "rust",
        "ktm" to "kotlin",
        "kts" to "kotlin",
        "sh" to "shell",
        "bat" to "batch",
        "ps1" to "powershell",
        "psm1" to "powershell",
        "psd1" to "powershell",
        "ts" to "typescript",
        "tsx" to "typescript",
        "jsx" to "javascript",
        "md" to "markdown"
    )

    fun process(rootDir: File, input: File): String {
        val rawContent = input.readText()
        if (!rawContent.contains("<<< @/")) return rawContent

        return REF_REGEX.replace(rawContent) { matchResult ->
            val file = matchResult.groups["path"]?.value ?: throw IllegalArgumentException("File argument is required for kodedocs ${matchResult.value} in file ${input.path}")
            val include = matchResult.groups["region"]?.value?.substring(1)?.let { listOf(it) } ?: emptyList()

            val codeFile = rootDir.resolve(file)
            if (!codeFile.exists()) throw IllegalArgumentException("File $file not found in ${input.name}")

            val language = languageMap[codeFile.extension.lowercase()] ?: codeFile.extension

            buildString {
                append("```").append(language).append('\n')
                append(processKodeDocs(codeFile, include, emptyList(), emptyList()))
                append("\n```")
            }
        }
    }

    fun processKodeDocs(input: File, include: List<String>, exclude: List<String>, lineStrings: List<String>): String {
        var lineNumber = 0
        input.useLines { lines ->
            lines.forEach { line ->
                lineNumber++
                val trimmedStart = line.trimStart()
                if (trimmedStart.startsWith("//")) {
                    if (trimmedStart.contains("#region")) {
                        val name = trimmedStart.substringAfter("#region").trim()
                        if (name.isEmpty()) throw IllegalArgumentException("Region name is missing in ${input.name}:$lineNumber")
                        if (!REGION_NAME_REGEX.matches(name)) throw IllegalArgumentException("Invalid region name '$name' in ${input.name}:$lineNumber. Valid Region name Regex: $REGION_NAME_REGEX")
                    } else if (trimmedStart.contains("#endregion")) {
                        val name = trimmedStart.substringAfter("#endregion").trim()
                        if (name.isEmpty()) throw IllegalArgumentException("Region name is missing in ${input.name}:$lineNumber")
                        if (!REGION_NAME_REGEX.matches(name)) throw IllegalArgumentException("Invalid region name '$name' in ${input.name}:$lineNumber. Valid Region name Regex: $REGION_NAME_REGEX")
                    }
                }
            }
        }

        val highlightLineSet = lineStrings.flatMap {
            val range = it.split("-").map { part -> part.trim().toInt() }
            (range[0]..(range.getOrNull(1) ?: range[0])).toList()
        }.toSet()

        val buffer = mutableListOf<String>()
        var skipping = 0
        var including = 0
        var finalLineNumber = 0
        var minIndent = Int.MAX_VALUE

        input.useLines { lines ->
            lines.forEach { line ->
                val trimmedStart = line.trimStart()
                var isRegionMarker = false

                if (trimmedStart.startsWith("//")) {
                    if (trimmedStart.contains("#region")) {
                        val name = trimmedStart.substringAfter("#region").trim()
                        if (name in exclude) skipping++ else if (name in include) including++
                        isRegionMarker = true
                    } else if (trimmedStart.contains("#endregion")) {
                        val name = trimmedStart.substringAfter("#endregion").trim()
                        if (name in exclude) skipping-- else if (name in include) including--
                        isRegionMarker = true
                    }
                }

                if (!isRegionMarker && skipping == 0 && (include.isEmpty() || including > 0)) {
                    if (line.isNotBlank()) {
                        val indent = line.indexOfFirst { !it.isWhitespace() }
                        if (indent < minIndent) minIndent = indent
                    }

                    buffer.add(line)
                    if (highlightLineSet.contains(++finalLineNumber)) buffer.add(" // [!code highlight]")
                }
            }
        }

        return buildString {
            buffer.forEach { line ->
                if (line.isNotBlank())
                    append(line.drop(minIndent.coerceAtMost(line.length - line.trimStart().length)))
                else append(line)
                append('\n')
            }
        }.trimEnd('\n')
    }
}