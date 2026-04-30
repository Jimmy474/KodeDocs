import java.io.File

class PreProcessor {
    data class ProcessedResult(val content: String, val metadata: Map<String, Any>)

    companion object{
        const val KODEDOCS_PREFIX = "kodedocs"
        val REGION_PREFIX_REGEX = """^//\s*#region""".toRegex()
        val END_REGION_PREFIX_REGEX = """^//\s*#endregion""".toRegex()
        val REGION_NAME_REGEX = "[a-zA-Z_][a-zA-Z0-9_]*".toRegex()

    }

    fun process(input: File): ProcessedResult {
        val rawContent = input.readText()
        val (metadata, contentWithoutFrontmatter) = extractFrontmatter(rawContent,input.path)
        
        val regex = Regex("""```$KODEDOCS_PREFIX\s+([^`]*)```""")
        val processedContent = contentWithoutFrontmatter.replace(regex) { matchResult ->
            val args = parseArgs(matchResult.groupValues[1])

            val file = args["file"] ?: throw IllegalArgumentException("File argument is required for kodedocs ${matchResult.value} in file ${input.path}")
            val include = args["include"]?.split(",") ?: emptyList()
            val exclude = args["exclude"]?.split(",") ?: emptyList()
            val codeFile = File(file)
            if (!codeFile.exists()) throw IllegalArgumentException("File $file not found in ${input.name}")
            val codeContent = codeFile.readText()

            val language = detectLanguage(codeFile.extension)

            "```$language\n${processKodeDocs(codeContent, include, exclude, codeFile.path)}\n```"
        }
        return ProcessedResult(processedContent, metadata)
    }

    private fun extractFrontmatter(content: String, path: String): Pair<Map<String, Any>, String> {
        if (!content.startsWith("---")) throw IllegalArgumentException("The File at $path does not start with \"---\"")
        
        val endOfFrontmatter = content.indexOf("---", 3)
        if (endOfFrontmatter == -1) throw IllegalArgumentException("The File at $path does not have an enclosing \"---\"")
        
        val frontmatterText = content.substring(3, endOfFrontmatter).trim()
        val remainingContent = content.substring(endOfFrontmatter + 3).trim()
        
        val metadata = mutableMapOf<String, Any>()
        var currentList: MutableList<String>? = null
        
        frontmatterText.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            
            if (trimmed.startsWith("- ")) {
                currentList?.add(trimmed.removePrefix("- ").trim())
            } else if (trimmed.contains(":")) {
                val parts = trimmed.split(":", limit = 2)
                val key = parts[0].trim()
                val value = parts[1].trim()
                
                if (value.isEmpty()) {
                    currentList = mutableListOf()
                    metadata[key] = currentList
                } else {
                    metadata[key] = value
                    currentList = null
                }
            }
        }
        
        return metadata to remainingContent
    }

    @Suppress("d")
    fun processKodeDocs(input: String, include: List<String>, exclude: List<String>, filePath: String = "unknown"): String{
        val output = StringBuilder()
        var skipping = 0
        var including = 0
        var isRegionMarker: Boolean

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
                output.append(line).append("\n")
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