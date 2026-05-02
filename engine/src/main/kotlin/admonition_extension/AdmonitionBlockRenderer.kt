package admonition_extension

import com.vladsch.flexmark.html.HtmlWriter
import com.vladsch.flexmark.html.renderer.NodeRenderer
import com.vladsch.flexmark.html.renderer.NodeRendererContext
import com.vladsch.flexmark.html.renderer.NodeRenderingHandler

class AdmonitionBlockRenderer: NodeRenderer {
    override fun getNodeRenderingHandlers(): Set<NodeRenderingHandler<*>> {
        return setOf(NodeRenderingHandler(AdmonitionBlock::class.java, this::render))
    }

    private fun render(node: AdmonitionBlock, context: NodeRendererContext, html: HtmlWriter) {
        val collapsable = node.isCollapsable?.let{
            " collapsable ${if(it) "open" else "closed"}"
        } ?: ""

        html.attr("class", "admonition ${node.type}$collapsable").withAttr().tagLine("div"){
            html.attr("class", "admonition-title").withAttr().tagIndent("div"){
                html.text(node.title)
            }
            html.attr("class", "admonition-content").withAttr().tagIndent("div"){
                html.attr("class", "admonition-content-inner").withAttr().tagLineIndent("div"){
                    context.renderChildren(node)
                }
            }
        }
    }
}