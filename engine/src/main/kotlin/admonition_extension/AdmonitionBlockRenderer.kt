package admonition_extension

import com.vladsch.flexmark.html.HtmlWriter
import com.vladsch.flexmark.html.renderer.NodeRenderer
import com.vladsch.flexmark.html.renderer.NodeRendererContext
import com.vladsch.flexmark.html.renderer.NodeRenderingHandler
import java.util.Locale.getDefault

class AdmonitionBlockRenderer: NodeRenderer {
    override fun getNodeRenderingHandlers(): Set<NodeRenderingHandler<*>> {
        return setOf(NodeRenderingHandler(AdmonitionBlock::class.java, this::render))
    }

    private fun render(node: AdmonitionBlock, context: NodeRendererContext, html: HtmlWriter) {
        html.line()
        html.attr("class", "admonition ${node.type}")
        html.withAttr().tag("div")

        html.attr("class", "admonition-title")
        html.withAttr().tag("div")
        html.text(node.customTitle ?: node.type.replaceFirstChar { if (it.isLowerCase()) it.titlecase(getDefault()) else it.toString() })
        html.tag("/div")

        html.attr("class", "admonition-content")
        html.withAttr().tag("div")
        context.renderChildren(node)
        html.tag("/div")

        html.tag("/div")
        html.line()
    }
}