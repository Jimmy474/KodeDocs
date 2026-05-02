package admonition_extension

import com.vladsch.flexmark.util.ast.Block
import com.vladsch.flexmark.util.sequence.BasedSequence

class AdmonitionBlock(val type: String, val title: String, val isCollapsable: Boolean?) : Block() {
    override fun getSegments(): Array<out BasedSequence> = EMPTY_SEGMENTS
}