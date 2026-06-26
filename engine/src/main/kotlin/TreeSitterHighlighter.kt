
import dev.kreuzberg.treesitterlanguagepack.TreeSitterLanguagePack
import io.github.treesitter.jtreesitter.Parser
import io.github.treesitter.jtreesitter.Query
import io.github.treesitter.jtreesitter.QueryCursor
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.jvm.optionals.getOrNull

class ResourcePool<T>(private val factory: () -> T) {
    private val pool = ConcurrentLinkedQueue<T>()
    fun acquire(): T = pool.poll() ?: factory()
    fun release(item: T) { pool.offer(item) }
}

object TreeSitterHighlighter {

    private val log = LoggerFactory.getLogger(this::class.java)
    private val queries = ConcurrentHashMap<String, Query>()
    private val unavailableLanguages = ConcurrentHashMap.newKeySet<String>()
    private val parserPool = ResourcePool { Parser() }
    private val cursorPools = ConcurrentHashMap<String, ResourcePool<QueryCursor>>()
    private val cssClassCache = ConcurrentHashMap<String, String>()

    private val SPECIAL_CODES = mapOf(
        "[code highlight]" to "highlighted",
        "[code highlight error]" to "highlighted error",
        "[code highlight warning]" to "highlighted warning",
        "[code highlight info]" to "highlighted info",
        "[code focus]" to "focused",
        "[code add]" to "diff add",
        "[code remove]" to "diff remove"
    )

    fun clearMemory() {
        if (unavailableLanguages.isNotEmpty()) {
            log.info("Following languages could not be highlighted due to parsers not being available for them: ${
                unavailableLanguages.joinToString { "\u001b[91m$it\u001b[0m" }
            }")
            unavailableLanguages.clear()
        }
        queries.clear()
        cursorPools.clear()
        cssClassCache.clear()
    }

    private val fenceRegex = Regex(
        pattern = """^(?<fence>`{3,})((?<lang>[a-z-]*)?(?<nolinenumbers>:no-line-numbers)?)\n(?<code>[\s\S]*?)\n(\k<fence>)$""",
        options = setOf(RegexOption.MULTILINE)
    )

    fun highlightMarkdown(markdown: String, langAliases: Map<String, String>?): String {
        return fenceRegex.replace(markdown) { match ->
            val language = (match.groups["lang"]?.value?.takeIf { it.isNotBlank() } ?: "text").let {
                langAliases?.get(it) ?: it
            }
            val code = match.groups["code"]?.value ?: ""
            val (highlighted, hasFocused) = highlightCode(code, language)
            val useLineNumbers = match.groups["nolinenumbers"] == null

            buildHtml {
                div(
                    "kode-code-block",
                    "language-${language.escapeHtml()}",
                    if (useLineNumbers) "line-numbers-mode" else ""
                ) {
                    if (useLineNumbers) {
                        div("line-numbers") {
                            var lineCount = 1
                            for (char in code) { if (char == '\n') lineCount++ }
                            for (i in 1..lineCount) { span { +"$i" } }
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

        if (languageName == "text") return renderPlain(normalizedCode)

        if(!TreeSitterLanguagePack.hasLanguage(languageName)){
            unavailableLanguages.add(languageName)
            return renderPlain(normalizedCode)
        }
        val language = TreeSitterLanguagePack.getLanguage(languageName) ?: return renderPlain(normalizedCode)

        val query = queries.getOrPut(languageName) {
            val querySource = TreeSitterLanguagePack.getHighlightsQuery(languageName) ?: return renderPlain(normalizedCode)
            try {
                Query(language, querySource)
            } catch (e: Exception) {
                log.error("Error parsing query: ${e.message}")
                return renderPlain(normalizedCode)
            }
        }

        val cursorPool = cursorPools.getOrPut(languageName) { ResourcePool { QueryCursor(query) } }
        val parser = parserPool.acquire()
        val cursor = cursorPool.acquire()

        return try {
            parser.setLanguage(language)
            parser.parse(normalizedCode).getOrNull()?.use { tree ->
                val charClasses = arrayOfNulls<String>(normalizedCode.length)
                val size = charClasses.size
                cursor.findCaptures(tree.rootNode, QueryCursor.Options()).forEach { (_,match) ->
                    for (capture in match.captures()) {
                        val captureName = capture.name
                        val cssClass = cssClassCache.getOrPut(captureName) {
                            "ts-${captureName.replace('.', '-')}"
                        }

                        val node = capture.node()
                        val start = node.startByte.coerceAtMost(size)
                        val end = node.endByte.coerceAtMost(size)

                        if (start < end) {
                            Arrays.fill(charClasses, start, end, cssClass)
                        }
                    }
                }

                renderHighlightedHtml(normalizedCode, charClasses)
            } ?: renderPlain(normalizedCode)
        } finally {
            parserPool.release(parser)
            cursorPool.release(cursor)
        }
    }

    fun renderPlain(code: String): Pair<String, Boolean> {
        val sb = StringBuilder(code.length + code.length / 10)
        var i = 0
        val length = code.length
        while (i < length) {
            var lineEnd = i
            while (lineEnd < length && code[lineEnd] != '\n') lineEnd++

            val chunk = code.substring(i, lineEnd)
            sb.append("<span class=\"line\">").append(chunk.escapeHtml()).append("</span>\n")

            i = lineEnd
            if (i < length && code[i] == '\n') i++
        }
        return sb.toString().trimEnd('\n') to false
    }

    private fun renderHighlightedHtml(code: String, charClasses: Array<String?>): Pair<String, Boolean> {
        val sb = StringBuilder(code.length + code.length / 2)
        var hasFocusedLine = false
        var i = 0
        val length = code.length

        while (i < length) {
            var hasBracket = false
            var lineEnd = i

            while (lineEnd < length && code[lineEnd] != '\n') {
                if (code[lineEnd] == '[') hasBracket = true
                lineEnd++
            }

            var lineModifierClass = ""
            var matchedSpecialKey = ""

            if (hasBracket) {
                val lineStr = code.substring(i, lineEnd)
                for ((key, value) in SPECIAL_CODES) {
                    if (lineStr.contains(key)) {
                        lineModifierClass = " $value"
                        matchedSpecialKey = key
                        if (key == "[code focus]") hasFocusedLine = true
                        break
                    }
                }
            }

            sb.append("<span class=\"line").append(lineModifierClass).append("\">")

            while (i < lineEnd) {
                val currentClass = charClasses[i]
                val tokenStart = i

                while (i < lineEnd && charClasses[i] == currentClass) {
                    i++
                }

                val chunk = code.substring(tokenStart, i)
                val skip = matchedSpecialKey.isNotEmpty() && chunk.contains(matchedSpecialKey)

                if (!skip) {
                    if (currentClass != null) {
                        sb.append("<span class=\"").append(currentClass).append("\">")
                        sb.append(chunk.escapeHtml())
                        sb.append("</span>")
                    } else {
                        sb.append(chunk.escapeHtml())
                    }
                }
            }

            sb.append("</span>\n")

            if (i < length && code[i] == '\n') i++
        }

        return sb.toString().trimEnd('\n') to hasFocusedLine
    }

    class TagContext(val sb: StringBuilder = StringBuilder()) {
        operator fun String.unaryPlus() { sb.append(this) }
        fun tag(name: String, vararg classes: String, content: TagContext.() -> Unit = {}) {
            val classAttr = classes.filter { it.isNotEmpty() }.joinToString(" ")
            val classString = if (classAttr.isNotEmpty()) " class=\"$classAttr\"" else ""
            sb.append("<$name$classString>")
            this.content()
            sb.appendLine("</$name>")
        }
        fun div(vararg classes: String, content: TagContext.() -> Unit = {}) { tag("div", *classes, content = content) }
        fun span(vararg classes: String, content: TagContext.() -> Unit = {}) { tag("span", *classes, content = content) }
    }

    fun buildHtml(content: TagContext.() -> Unit): String {
        val context = TagContext()
        context.content()
        return context.sb.toString()
    }
}