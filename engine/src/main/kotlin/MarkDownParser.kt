
import admonition_extension.AdmonitionExtension
import com.vladsch.flexmark.ext.anchorlink.AnchorLink
import com.vladsch.flexmark.ext.anchorlink.AnchorLinkExtension
import com.vladsch.flexmark.ext.attributes.AttributesExtension
import com.vladsch.flexmark.ext.emoji.EmojiExtension
import com.vladsch.flexmark.ext.tables.TablesExtension
import com.vladsch.flexmark.ext.toc.SimTocExtension
import com.vladsch.flexmark.ext.toc.TocExtension
import com.vladsch.flexmark.ext.typographic.TypographicExtension
import com.vladsch.flexmark.ext.yaml.front.matter.AbstractYamlFrontMatterVisitor
import com.vladsch.flexmark.ext.yaml.front.matter.YamlFrontMatterExtension
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.data.MutableDataHolder
import com.vladsch.flexmark.util.data.MutableDataSet
import kotlinx.serialization.Serializable

object MarkDownParser {
    private val options: MutableDataHolder = MutableDataSet().set(
        Parser.EXTENSIONS,
        listOf(
            AttributesExtension.create(),
            AnchorLinkExtension.create(),
            YamlFrontMatterExtension.create(),
            AdmonitionExtension.create(),
            TablesExtension.create(),
            TypographicExtension.create(),
            EmojiExtension.create(),
            TocExtension.create(),
            SimTocExtension.create(),
        )
    )

    private val parser = Parser.builder(options).build()
    private val renderer = HtmlRenderer.builder(options).build()

    data class ParsedResult(val html: String, val headings: List<Heading>, val metadata: Map<String, List<String>>)
    @Serializable
    data class Heading(val level: Int, val text: String, val id: String)

    fun parse(markdown: String): ParsedResult {
        val document = parser.parse(markdown)
        val frontMatterVisitor = AbstractYamlFrontMatterVisitor().apply { visit(document) }
        val html = renderer.render(document)

        val headings = mutableListOf<Heading>()

        var node = document.firstChild

        while (node != null) {
            if (node is com.vladsch.flexmark.ast.Heading) {
                node.firstChild?.let {
                    if(it is AnchorLink){
                        headings.add(Heading(node.level,it.firstChild?.chars?.toString() ?: node.text.toString(), node.anchorRefId))
                    }
                }
            }
            node = node.next
        }
        
        return ParsedResult(html, headings, frontMatterVisitor.data)
    }
}