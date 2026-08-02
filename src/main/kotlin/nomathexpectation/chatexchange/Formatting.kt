package nomathexpectation.chatexchange

import eu.pb4.placeholders.api.ParserContext
import eu.pb4.placeholders.api.ServerPlaceholderContext
import eu.pb4.placeholders.api.node.DynamicTextNode
import eu.pb4.placeholders.api.node.TextNode
import eu.pb4.placeholders.api.parsers.NodeParser
import eu.pb4.placeholders.api.parsers.TagLikeParser
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import java.util.function.Function

/**
 * Chat formatting backed by TextPlaceholderAPI (Simplified Text Format + placeholders).
 *
 * See `doc/MIGRATION_TO_TEXTPLACEHOLDERAPI.md` for the full design. This replaces the former
 * JSON-text-component formatting (`parseJsonToComponent` + `ComponentUtils.resolve`).
 */
object Formatting {
    /**
     * `${...}` local-variable lookup key (cf. StyledChat `ChatStyle.DYN_KEY`).
     * Bound via [ParserContext.with] to a `Function<String, Component>` that returns the
     * `Component` for a given local var name (e.g. `${player}`, `${name}`, `${message}`).
     * Values are returned as-is (DirectComponentNode), i.e. **not re-parsed**, so external/
     * user-supplied text can never inject formatting tags.
     */
    val DYN_KEY = DynamicTextNode.key("chatexchange")

    private val PARSER: NodeParser = NodeParser.builder()
        .simplifiedTextFormat() // <red>...</red>, <lang:...>, <hover:...>, ...
        .quickText()
        .serverPlaceholders() // %player:*% / %server:*% (only resolve when a player/server context exists)
        .placeholders(TagLikeParser.PLACEHOLDER_USER, DYN_KEY) // ${...}
        .staticPreParsing()
        .build()

    /**
     * Pure syntactic validation (parses to a `TextNode` tree; needs no holder lookup).
     * Replaces the former `testJson` (which depended on the not-yet-ready `registries` global).
     */
    @JvmStatic
    fun validate(input: String?): Boolean = input != null && runCatching {
        PARSER.parseNode(TextNode.of(input))
        true
    }.getOrDefault(false)

    /**
     * Broadcast path (prefix `@bc` + `/chatexchange send`).
     *
     * Local vars: `${player}` = the source's display name, `${message}` = the broadcast body.
     * A real player source additionally enables `%player:*%` placeholders; a console source
     * resolves `${player}` to its display name (fixes the former `@s`-empty-on-console issue).
     */
    @JvmStatic
    fun formatBroadcast(format: String, source: CommandSourceStack, message: String): Component {
        val vars = mapOf(
            "player" to source.displayName,
            "message" to Component.literal(message),
        )
        val lookup = Function<String, Component?> { key -> vars[key] ?: Component.empty() }
        val ctx = ServerPlaceholderContext.of(source).asParserContext()
            .with(DYN_KEY, lookup)
        return PARSER.parseNode(TextNode.of(format)).toComponent(ctx)
    }

    /**
     * External receive path (message arriving from a TCP client).
     *
     * Local vars: `${name}` = the external sender's name ([MessageEvent.from]),
     * `${message}` = the message body ([MessageEvent.content]). There is no in-game player
     * entity, so `%player:*%` cannot resolve; the sender is expressed solely as `${name}`.
     */
    @JvmStatic
    fun formatReceive(format: String, server: MinecraftServer, fromName: String, message: String): Component {
        val vars = mapOf(
            "name" to Component.literal(fromName),
            "message" to Component.literal(message),
        )
        val lookup = Function<String, Component?> { key -> vars[key] ?: Component.empty() }
        val ctx = ServerPlaceholderContext.of(server).asParserContext()
            .with(DYN_KEY, lookup)
        return PARSER.parseNode(TextNode.of(format)).toComponent(ctx)
    }
}
