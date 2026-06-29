
import com.vladsch.flexmark.ast.FencedCodeBlock
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.html.HtmlWriter
import com.vladsch.flexmark.html.renderer.NodeRenderer
import com.vladsch.flexmark.html.renderer.NodeRendererContext
import com.vladsch.flexmark.html.renderer.NodeRenderingHandler
import com.vladsch.flexmark.util.data.MutableDataHolder

class HighlightedCodeBlockRenderer(private val langAliases: Map<String, String>) : NodeRenderer {

    override fun getNodeRenderingHandlers(): Set<NodeRenderingHandler<*>> {
        return setOf(NodeRenderingHandler(FencedCodeBlock::class.java, ::renderCodeBlock))
    }

    private fun renderCodeBlock(node: FencedCodeBlock, context: NodeRendererContext, html: HtmlWriter) {
        val info = node.info.toString().trim()

        val rawLanguage = info.split(Regex("\\s+|:")).firstOrNull()?.takeIf { it.isNotBlank() } ?: "text"
        val language = langAliases[rawLanguage] ?: rawLanguage
        val useLineNumbers = !info.contains("no-line-numbers")

        val code = node.contentChars.toString()
        val highlighted = TreeSitterHighlighter.highlightCode(code, language)

        html.line()
        html.raw("<div class=\"kode-code-block language-").raw(language)
        if (useLineNumbers) html.raw(" line-numbers-mode")
        html.raw("\">\n")

        if (useLineNumbers) {
            html.raw("<div class=\"line-numbers\">\n")

            var lineCount = 1
            for (i in code.indices) {
                if (code[i] == '\n') lineCount++
            }

            val sb = StringBuilder(lineCount * 16)
            for (i in 1..lineCount) {
                sb.append("<span>").append(i).append("</span>\n")
            }

            html.raw(sb.toString())
            html.raw("</div>\n")
        }

        html.raw("<pre><code class=\"kode-code\">")
        html.raw(highlighted)
        html.raw("</code></pre>\n</div>\n")
    }
}

class HighlightExtension(private val aliases: Map<String, String>) : HtmlRenderer.HtmlRendererExtension {
    override fun rendererOptions(holder: MutableDataHolder) {}
    override fun extend(htmlRendererBuilder: HtmlRenderer.Builder, rendererType: String) {
        htmlRendererBuilder.nodeRendererFactory { HighlightedCodeBlockRenderer(aliases) }
    }

    companion object {
        fun create(aliases: Map<String, String> = emptyMap()) = HighlightExtension(aliases)
    }
}