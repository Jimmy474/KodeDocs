import java.io.File

object PreProcessor {

    const val KODEDOCS_PREFIX = "kodedocs"
    val REGION_PREFIX_REGEX = """^//\s*#region""".toRegex()
    val END_REGION_PREFIX_REGEX = """^//\s*#endregion""".toRegex()
    val REGION_NAME_REGEX = "[a-zA-Z_][a-zA-Z0-9_]*".toRegex()
    val ARGS_REGEX = Regex("""(\w+)\s*:\s*("[^"]+"|\S+)""")

    val KD_REGEX = Regex("""```$KODEDOCS_PREFIX\s+([^`]*)```""")
    val REF_REGEX = Regex("""^<<<\s+@/(?<path>.*?)(?<region>#.*)?$""", RegexOption.MULTILINE)
    private val languageMap = mapOf(
        "kt" to "kotlin",
        "java" to "java",
        "js" to "javascript",
        "py" to "python",
        "cs" to "csharp",
        "cpp" to "cpp",
        "rb" to "ruby",
        "swift" to "swift",
        "go" to "go",
        "rs" to "rust",
        "php" to "php",
        "scala" to "scala",
        "ktm" to "kotlin",
        "kts" to "kotlin",
        "groovy" to "groovy",
        "html" to "html",
        "css" to "css",
        "sql" to "sql",
        "xml" to "xml",
        "yaml" to "yaml",
        "toml" to "toml",
        "json" to "json",
        "sh" to "shell",
        "bat" to "batch",
        "ps1" to "powershell",
        "psm1" to "powershell",
        "psd1" to "powershell",
        "lua" to "lua",
        "ts" to "typescript",
        "tsx" to "typescript",
        "jsx" to "javascript",
        "dart" to "dart",
        "r" to "r",
        "c" to "c",
        "md" to "markdown",
    )

    fun process(rootDir: File, input: File): String {
        val rawContent = input.readText()

        val processedContent = rawContent.replace(REF_REGEX) { matchResult ->
//            val args = parseArgs(matchResult.groupValues[1])

//            val file = args["file"] ?: throw IllegalArgumentException("File argument is required for kodedocs ${matchResult.value} in file ${input.path}")
//            val include = args["include"]?.split(",") ?: emptyList()
//            val exclude = args["exclude"]?.split(",") ?: emptyList()
//            val lines = args["lines"]?.split(",") ?: emptyList()
//            val lineNumbers = args["lineNumbers"]?.toIntOrNull() ?: 0

            val file = matchResult.groups["path"]?.value ?: throw IllegalArgumentException("File argument is required for kodedocs ${matchResult.value} in file ${input.path}")
            val include = matchResult.groups["region"]?.value?.substring(1)?.let{ listOf(it) } ?: emptyList()

            val codeFile = rootDir.resolve(file)
            if (!codeFile.exists()) throw IllegalArgumentException("File $file not found in ${input.name}")
            val codeContent = codeFile.readText()

            val language = detectLanguage(codeFile.extension)

            "```$language\n${processKodeDocs(codeContent, include, emptyList(), emptyList(), codeFile.path)}\n```"
//            "```$language\n${processKodeDocs(codeContent, include, exclude, lines, codeFile.path)}\n```\n{lineNumbers=\"$lineNumbers\"}\n"
        }
        return processedContent
    }

    private fun processKodeDocs(input: String, include: List<String>, exclude: List<String>, lineStrings: List<String>, filePath: String = "unknown"): String{
        val output = StringBuilder()
        var skipping = 0
        var including = 0
        var isRegionMarker: Boolean
        val lines = lineStrings.flatMap {
            val range = it.split("-").map { it.trim().toInt() }
            (range[0]..(range.getOrNull(1) ?: range[0])).toList()
        }.toSet()

        var finalLineNumber = 0
        input.lines().forEachIndexed { index, line ->
            val lineNumber = index + 1
            isRegionMarker = false

            if (!line.isBlank() && !line.all { it.isWhitespace() }) {
                val content = line.trim()

                if (END_REGION_PREFIX_REGEX.containsMatchIn(content)) {
                    val name = END_REGION_PREFIX_REGEX.replace(content, "").trim()
                    if (name.isEmpty()) throw IllegalArgumentException("Region name is missing in $filePath:$lineNumber")

                    if (REGION_NAME_REGEX.matches(name)) {
                        if (name in exclude) skipping--
                        else if (name in include) including--
                        isRegionMarker = true
                    } else {
                        throw IllegalArgumentException("Invalid region name '$name' in $filePath:$lineNumber. Valid Region name Regex: $REGION_NAME_REGEX")
                    }
                } else if (REGION_PREFIX_REGEX.containsMatchIn(content)) {
                    val name = REGION_PREFIX_REGEX.replace(content, "").trim()
                    if (name.isEmpty()) throw IllegalArgumentException("Region name is missing in $filePath:$lineNumber")

                    if (REGION_NAME_REGEX.matches(name)) {
                        if (name in exclude) skipping++
                        else if (name in include) including++
                        isRegionMarker = true
                    } else {
                        throw IllegalArgumentException("Invalid region name '$name' in $filePath:$lineNumber. Valid Region name Regex: $REGION_NAME_REGEX")
                    }
                }
            }

            if(!isRegionMarker && skipping == 0 && ((include.isNotEmpty() && including > 0) || (include.isEmpty() && including == 0))){
                output.append(line.let{
                    if(lines.contains(++finalLineNumber)) "$it // [!code highlight]" else it
                }).append("\n")
            }
        }
        return output.toString().trimIndent()
    }

    private fun parseArgs(input: String): Map<String, String> {
        return ARGS_REGEX.findAll(input).associate {
            val key = it.groupValues[1]
            val value = it.groupValues[2].trim('"')
            key to value
        }
    }

    private fun detectLanguage(extension: String) = languageMap[extension.lowercase()] ?: "text"
}