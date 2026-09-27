package nomathexpectation.chatexchange

import eu.pb4.placeholders.api.ParserContext
import eu.pb4.placeholders.api.node.TextNode
import net.minecraft.network.chat.Component
import java.util.function.Function

/**
 * 1.20.1 (placeholder-api 2.1.4) counterpart of `DynamicTextNode` + `Formatting.DYN_KEY`:
 * parsed as a `${key}` node via `PatternPlaceholderParser.PREDEFINED_PLACEHOLDER_PATTERN`,
 * resolved at render time from the per-call lookup bound into [ParserContext].
 *
 * Borrowed from StyledChat's 2.1.4-era `eu.pb4.styledchat.parser.DynamicNode`
 * (https://github.com/Patbox/StyledChat, 1.20 branch).
 */
class DynamicNodeCompat private constructor(
    private val key: String,
    private val fallback: Component,
) : TextNode {
    override fun toText(context: ParserContext, removeBackslashes: Boolean): Component {
        val lookup = context.get(NODES) ?: return fallback
        return lookup.apply(key) ?: fallback
    }

    override fun isDynamic(): Boolean = true

    companion object {
        val NODES: ParserContext.Key<Function<String, Component?>> =
            ParserContext.Key("chatexchange:dyn", null)

        fun of(key: String): DynamicNodeCompat = DynamicNodeCompat(key, Component.literal("\${$key}"))
    }
}
