import com.vladsch.flexmark.ext.anchorlink.AnchorLinkExtension
import com.vladsch.flexmark.ext.attributes.AttributesExtension
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.data.MutableDataHolder
import com.vladsch.flexmark.util.data.MutableDataSet
import kotlinx.serialization.Serializable

class MarkDownParser {
    private val options: MutableDataHolder = MutableDataSet().apply {
        set(Parser.EXTENSIONS, listOf(AttributesExtension.create(), AnchorLinkExtension.create()))
    }
    private val parser = Parser.builder(options).build()
    private val renderer = HtmlRenderer.builder(options).build()

    data class ParsedResult(val html: String, val headings: List<Heading>)
    @Serializable
    data class Heading(val level: Int, val text: String, val id: String)

    fun parse(markdown: String): ParsedResult {
        val document = parser.parse(markdown)
        val html = renderer.render(document)
        
        val headings = mutableListOf<Heading>()
        val headingRegex = Regex("""<h([1-6])><a(?:\s+href="#([^"]*)"\s+id="\2")?>(.*?)</a></h\1>""", RegexOption.DOT_MATCHES_ALL)
        
        headingRegex.findAll(html).forEach { match ->
            val level = match.groupValues[1].toInt()
            val id = match.groupValues[2]
            val text = match.groupValues[3].replace(Regex("<.*?>"), "").trim()
            headings.add(Heading(level, text, id))
        }
        
        return ParsedResult(html, headings)
    }
}