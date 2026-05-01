package admonition_extension

import com.vladsch.flexmark.util.ast.Block
import com.vladsch.flexmark.util.sequence.BasedSequence

class AdmonitionBlock(val type: String, val customTitle: String?) : Block() {
    override fun getSegments(): Array<out BasedSequence> = EMPTY_SEGMENTS
}