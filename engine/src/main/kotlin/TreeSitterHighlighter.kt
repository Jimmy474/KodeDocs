
import dev.kreuzberg.treesitterlanguagepack.TreeSitterLanguagePack
import io.github.treesitter.jtreesitter.Parser
import io.github.treesitter.jtreesitter.Query
import io.github.treesitter.jtreesitter.QueryCursor
import java.util.concurrent.ConcurrentHashMap
import kotlin.jvm.optionals.getOrNull

class TreeSitterHighlighter {

    object HighlighterCache {
        val queries = ConcurrentHashMap<String, Query>()
    }

    private val fenceRegex = Regex(
        pattern = """^```([^\n`]*)\n([\s\S]*?)\n```$""",
        options = setOf(RegexOption.MULTILINE)
    )

    fun highlightMarkdown(markdown: String): String {
        return fenceRegex.replace(markdown) { match ->
            val info = match.groupValues[1].trim()
            val language = info.split(Regex("""\s+""")).firstOrNull()?.takeIf { it.isNotBlank() } ?: "text"
            val code = match.groupValues[2]
            val (highlighted, hasFocused) = highlightCode(code, language)
            val useLineNumbers = true
            buildHtml {
                div(
                    "kode-code-block",
                    "language-${escapeHtml(language)}",
                    if (useLineNumbers) "line-numbers-mode" else ""
                ) {
                    if (useLineNumbers) {
                        div("line-numbers") {
                            buildList<String> {
                                repeat(code.lines().size) {
                                    span { +"${it + 1}" }
                                }
                            }.joinToString("\n")
                        }
                    }
                    tag("pre", if (hasFocused) " has-focused" else "") {
                        tag("code", "kode-code") { +highlighted }
                    }
                }
            }
        }
    }

    fun highlightCode(code: String, languageName: String): Pair<String, Boolean> {
        val normalizedCode = code.replace("\r\n", "\n")
        val language = TreeSitterLanguagePack.getLanguage(languageName) ?: return normalizedCode to false

        val query = HighlighterCache.queries.getOrPut(languageName) {
            val querySource = TreeSitterLanguagePack.getHighlightsQuery(languageName) ?: return normalizedCode to false
            Query(language, querySource)
        }

        return Parser(language).use { parser ->
            parser.parse(normalizedCode).getOrNull()?.use { tree ->
                QueryCursor(query).use { cursor ->
                    val charClasses = arrayOfNulls<String>(normalizedCode.length)
                    val matches = cursor.findMatches(tree.rootNode, QueryCursor.Options())

                    for (match in matches) {
                        for (capture in match.captures()) {
                            val cssClass = "ts-${capture.name.replace(".", "-")}"
                            val start = capture.node().startByte
                            val end = capture.node().endByte

                            for (i in start until end) {
                                if (i in charClasses.indices) {
                                    charClasses[i] = cssClass
                                }
                            }
                        }
                    }

                    renderLinesToHtml(extractLinesFromCharMap(normalizedCode, charClasses))
                }
            } ?: (escapeHtml(normalizedCode) to false)
        }
    }

    private fun extractLinesFromCharMap(code: String, charClasses: Array<String?>): List<HighlightLine> {
        val lines = mutableListOf<HighlightLine>()
        var currentLine = mutableListOf<HighlightToken>()

        var i = 0
        while (i < code.length) {
            val currentClass = charClasses[i]
            val startIndex = i

            while (i < code.length && charClasses[i] == currentClass && code[i] != '\n') {
                i++
            }

            val chunk = code.substring(startIndex, i)
            if (chunk.isNotEmpty()) {
                currentLine.add(HighlightToken(chunk, currentClass))
            }

            if (i < code.length && code[i] == '\n') {
                lines.add(HighlightLine(currentLine))
                currentLine = mutableListOf()
                i++
            }
        }

        if (currentLine.isNotEmpty() || lines.isEmpty()) {
            lines.add(HighlightLine(currentLine))
        }

        return lines
    }

    private fun renderLinesToHtml(lines: List<HighlightLine>): Pair<String, Boolean> {
        val sb = StringBuilder()
        var hasFocusedLine = false

        val specialCodes = mapOf(
            "[code highlight]" to "highlighted",
            "[code highlight error]" to "highlighted error",
            "[code highlight warning]" to "highlighted warning",
            "[code highlight info]" to "highlighted info",
            "[code focus]" to "focused",
            "[code add]" to "diff add",
            "[code remove]" to "diff remove"
        )

        for (line in lines) {
            var lineModifierClass = ""
            val filteredTokens = mutableListOf<HighlightToken>()

            for (token in line.tokens) {
                specialCodes.entries.firstOrNull { token.text.contains(it.key) }?.let{
                    lineModifierClass = " ${it.value}"
                    if(it.key == "[code focus]") hasFocusedLine = true
                } ?: filteredTokens.add(token)
            }

            sb.append("<span class=\"line").append(lineModifierClass).append("\">")

            for (token in filteredTokens) {
                token.cssClass?.let { sb.append("<span class=\"").append(it).append("\">") }
                sb.append(escapeHtml(token.text))
                token.cssClass?.let { sb.append("</span>") }
            }
            sb.append("</span>\n")
        }

        return sb.toString().trimEnd('\n') to hasFocusedLine
    }

    private fun escapeHtml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#x27;")
    }

    class TagContext(val sb: StringBuilder = StringBuilder()) {
        operator fun String.unaryPlus() {
            sb.append(this)
        }

        fun tag(name: String, vararg classes: String, content: TagContext.() -> Unit = {}) {
            val classAttr = classes.filter { it.isNotEmpty() }.joinToString(" ")
            val classString = if (classAttr.isNotEmpty()) " class=\"$classAttr\"" else ""

            sb.append("<$name$classString>")
            this.content()
            sb.appendLine("</$name>")
        }

        fun div(vararg classes: String, content: TagContext.() -> Unit = {}) {
            tag("div", *classes, content = content)
        }

        fun span(vararg classes: String, content: TagContext.() -> Unit = {}) {
            tag("span", *classes, content = content)
        }
    }

    fun buildHtml(content: TagContext.() -> Unit): String {
        val context = TagContext()
        context.content()
        return context.sb.toString()
    }

    data class HighlightToken(val text: String, val cssClass: String?)
    data class HighlightLine(val tokens: List<HighlightToken>)
}