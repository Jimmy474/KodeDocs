import java.io.File

class PreProcessor {

    companion object{
        const val KODEDOCS_PREFIX = "kodedocs"
        val REGION_PREFIX_REGEX = """^//\s*#region""".toRegex()
        val END_REGION_PREFIX_REGEX = """^//\s*#endregion""".toRegex()
        val REGION_NAME_REGEX = "[a-zA-Z_][a-zA-Z0-9_]*".toRegex()

    }

    fun process(input: File): String {
        val rawContent = input.readText()
        
        val regex = Regex("""```$KODEDOCS_PREFIX\s+([^`]*)```""")
        val processedContent = rawContent.replace(regex) { matchResult ->
            val args = parseArgs(matchResult.groupValues[1])

            val file = args["file"] ?: throw IllegalArgumentException("File argument is required for kodedocs ${matchResult.value} in file ${input.path}")
            val include = args["include"]?.split(",") ?: emptyList()
            val exclude = args["exclude"]?.split(",") ?: emptyList()
            val lines = args["lines"]?.split(",") ?: emptyList()
            val lineNumbers = args["lineNumbers"]?.toIntOrNull() ?: 0

            val codeFile = File(file)
            if (!codeFile.exists()) throw IllegalArgumentException("File $file not found in ${input.name}")
            val codeContent = codeFile.readText()

            val language = detectLanguage(codeFile.extension)

            "```$language\n${processKodeDocs(codeContent, include, exclude, lines, codeFile.path)}\n```\n{lineNumbers=\"$lineNumbers\"}\n".also {
//                println("Processed\n\n$it\n----------------------------------------\n")
            }
        }
        return processedContent
    }

    @Suppress("d")
    fun processKodeDocs(input: String, include: List<String>, exclude: List<String>, lineStrings: List<String>, filePath: String = "unknown"): String{
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

    fun parseArgs(input: String): Map<String, String> {
        val regex = Regex("""(\w+)\s*:\s*("[^"]+"|\S+)""")
        return regex.findAll(input).associate {
            val key = it.groupValues[1]
            val value = it.groupValues[2].trim('"')
            key to value
        }
    }

    fun detectLanguage(extension: String) = when(extension){
        "kt" -> "kotlin"
        "java" -> "java"
        "js" -> "javascript"
        "py" -> "python"
        "cs" -> "csharp"
        "cpp" -> "cpp"
        "rb" -> "ruby"
        "swift" -> "swift"
        "go" -> "go"
        "rs" -> "rust"
        "php" -> "php"
        "scala" -> "scala"
        "ktm" -> "kotlin"
        "kts" -> "kotlin"
        "groovy" -> "groovy"
        "html" -> "html"
        "css" -> "css"
        "sql" -> "sql"
        "xml" -> "xml"
        "yaml" -> "yaml"
        "toml" -> "toml"
        "json" -> "json"
        "sh" -> "shell"
        "bat" -> "batch"
        "ps1" -> "powershell"
        "psm1" -> "powershell"
        "psd1" -> "powershell"
        "lua" -> "lua"
        "ts" -> "typescript"
        "tsx" -> "typescript"
        "jsx" -> "javascript"
        "dart" -> "dart"
        "r" -> "r"
        "c" -> "c"
        "md" -> "markdown"
        else -> "text"
    }
}