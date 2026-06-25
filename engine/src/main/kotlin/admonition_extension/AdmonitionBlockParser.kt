package admonition_extension

import com.vladsch.flexmark.parser.block.*
import com.vladsch.flexmark.parser.core.ParagraphParser
import com.vladsch.flexmark.util.ast.Block
import com.vladsch.flexmark.util.data.DataHolder

class AdmonitionBlockParser(type: String, title: String, isCollapsable: Boolean?): AbstractBlockParser() {

    private val block = AdmonitionBlock(type,title,isCollapsable)

    override fun getBlock(): Block = block
    override fun isContainer() = true
    override fun canContain(state: ParserState, blockParser: BlockParser, block: Block) = blockParser !is AdmonitionBlockParser

    override fun tryContinue(state: ParserState): BlockContinue {
        val line = state.line.toString()

        if(line.isBlank()) return BlockContinue.atIndex(state.lineEndIndex)

        if (line.trim() == ":::") {
            return BlockContinue.finished()
        }

        return BlockContinue.atIndex(state.index)
    }

    override fun closeBlock(state: ParserState) {
        block.setCharsFromContent()
    }

    class Factory: CustomBlockParserFactory {
        override fun apply(options: DataHolder): BlockParserFactory {
            return object : AbstractBlockParserFactory(options) {
                override fun tryStart(state: ParserState, matched: MatchedBlockParser): BlockStart? {
                    val line = state.line.toString().trim()
                    return if (matched.blockParser is AdmonitionBlockParser || !line.startsWith(":::")) {
                        BlockStart.none()
                    } else {
                        Regex("""^:::\s*(info|warning|tip|danger|success|custom)(?:\s+\|\s+(\w+))?(?:\s+\[(open|close)])?$""").matchEntire(line)?.let{ result ->
                            val type = result.groupValues[1]
                            val rawTitle = result.groupValues.getOrNull(2)
                            val title = rawTitle
                                ?.takeIf { it.isNotBlank() }
                                ?.substringAfter("|")
                                ?.trim()
                                ?: type
                            val isCollapsable = result.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }?.let{
                                it == "open"
                            }

                            BlockStart.of(AdmonitionBlockParser(type,title,isCollapsable)).atIndex(state.line.length)
                        } ?: BlockStart.none()
                    }

                }
            }
        }

        override fun getAfterDependents(): Set<Class<*>>? = null
        override fun getBeforeDependents(): Set<Class<*>> = setOf(ParagraphParser::class.java)
        override fun affectsGlobalScope(): Boolean = false

    }
}
