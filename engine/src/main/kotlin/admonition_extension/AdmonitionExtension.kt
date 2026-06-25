package admonition_extension

import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.data.MutableDataHolder

class AdmonitionExtension: Parser.ParserExtension, HtmlRenderer.HtmlRendererExtension {
    override fun parserOptions(options: MutableDataHolder) {}

    override fun extend(parserBuilder: Parser.Builder) {
        parserBuilder.customBlockParserFactory(AdmonitionBlockParser.Factory())
    }

    override fun rendererOptions(options: MutableDataHolder) {}

    override fun extend(htmlRendererBuilder: HtmlRenderer.Builder, rendererType: String) {
        htmlRendererBuilder.nodeRendererFactory{
            AdmonitionBlockRenderer()
        }
    }

    companion object {
        fun create() = AdmonitionExtension()
    }
}