package nomathexpectation.chatexchange

//? if >= 26.1 {
import eu.pb4.placeholders.api.ParserContext
import eu.pb4.placeholders.api.ServerPlaceholderContext
//?} else {
/*import eu.pb4.placeholders.api.PlaceholderContext
*///?}
//? if >= 1.20.2 {
import eu.pb4.placeholders.api.node.DynamicTextNode
import eu.pb4.placeholders.api.node.TextNode
import eu.pb4.placeholders.api.parsers.NodeParser
import eu.pb4.placeholders.api.parsers.TagLikeParser
//?} else {
/*import eu.pb4.placeholders.api.parsers.NodeParser
import eu.pb4.placeholders.api.parsers.TextParserV1
*///?}
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import java.util.function.Function

/**
 * Chat formatting backed by TextPlaceholderAPI (Simplified Text Format + placeholders).
 *
 * See `doc/MIGRATION_TO_TEXTPLACEHOLDERAPI.md` for the full design. This replaces the former
 * JSON-text-component formatting (`parseJsonToComponent` + `ComponentUtils.resolve`).
 *
 * Version notes:
 *  - 26.x (placeholder-api 3.x): full builder chain with `serverPlaceholders()`.
 *  - 1.20.2–1.21.x (2.4.x): same builder chain, but `globalPlaceholders()` (no server-scoped
 *    variant yet) and `PlaceholderContext` instead of `ServerPlaceholderContext`.
 *  - 1.20.1 (2.1.4): no builder/Simplified Text Format — `${...}` local vars are substituted
 *    before parsing and only quick-text tags (`TextParserV1`) are applied. STF tags and
 *    `%player:*%`/`%server:*%` placeholders are not available on 1.20.1.
 */
object Formatting {
    /**
     * `${...}` local-variable lookup key (cf. StyledChat `ChatStyle.DYN_KEY`).
     * Bound via [ParserContext.with] to a `Function<String, Component>` that returns the
     * `Component` for a given local var name (e.g. `${player}`, `${name}`, `${message}`).
     * Values are returned as-is (DirectComponentNode), i.e. **not re-parsed**, so external/
     * user-supplied text can never inject formatting tags (not on 1.20.1, see above).
     */
    //? if >= 1.20.2 {
    val DYN_KEY = DynamicTextNode.key("chatexchange")
    //?}

    private val PARSER: NodeParser =
        //? if >= 1.20.2 {
        NodeParser.builder()
            .simplifiedTextFormat() // <red>...</red>, <lang:...>, <hover:...>, ...
            .quickText()
            //? if >= 26.1 {
            .serverPlaceholders() // %player:*% / %server:*% (only resolve when a player/server context exists)
            //?} else {
            /*.globalPlaceholders()
            *///?}
            .placeholders(TagLikeParser.PLACEHOLDER_USER, DYN_KEY) // ${...}
            .staticPreParsing()
            .build()
        //?} else {
        /*TextParserV1.DEFAULT
        *///?}

    /**
     * Pure syntactic validation (parses to a `TextNode` tree; needs no holder lookup).
     * Replaces the former `testJson` (which depended on the not-yet-ready `registries` global).
     * On 1.20.1 there is nothing to validate syntactically (plain string substitution), so
     * only null-ness is checked.
     */
    @JvmStatic
    fun validate(input: String?): Boolean = input != null && runCatching {
        //? if >= 1.20.2 {
        PARSER.parseNode(TextNode.of(input))
        //?} else {
        /*input
        *///?}
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
        //? if >= 26.1 {
        val vars = mapOf(
            "player" to source.displayName,
            "message" to Component.literal(message),
        )
        val lookup = Function<String, Component?> { key -> vars[key] ?: Component.empty() }
        val ctx = ServerPlaceholderContext.of(source).asParserContext()
            .with(DYN_KEY, lookup)
        return PARSER.parseNode(TextNode.of(format)).toComponent(ctx)
        //?} else {
        /*//? if >= 1.20.2 {
        val vars = mapOf(
            "player" to source.displayName,
            "message" to Component.literal(message),
        )
        val lookup = Function<String, Component?> { key -> vars[key] ?: Component.empty() }
        val ctx = PlaceholderContext.of(source).asParserContext()
            .with(DYN_KEY, lookup)
        return PARSER.parseNode(TextNode.of(format)).toText(ctx)
        //?} else {
        /*val substituted = format
            .replace("\${player}", source.displayName.string)
            .replace("\${message}", message)
        return PARSER.parseText(substituted, PlaceholderContext.of(source).asParserContext())
        *///?}
        *///?}
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
        //? if >= 26.1 {
        val vars = mapOf(
            "name" to Component.literal(fromName),
            "message" to Component.literal(message),
        )
        val lookup = Function<String, Component?> { key -> vars[key] ?: Component.empty() }
        val ctx = ServerPlaceholderContext.of(server).asParserContext()
            .with(DYN_KEY, lookup)
        return PARSER.parseNode(TextNode.of(format)).toComponent(ctx)
        //?} else {
        /*//? if >= 1.20.2 {
        val vars = mapOf(
            "name" to Component.literal(fromName),
            "message" to Component.literal(message),
        )
        val lookup = Function<String, Component?> { key -> vars[key] ?: Component.empty() }
        val ctx = PlaceholderContext.of(server).asParserContext()
            .with(DYN_KEY, lookup)
        return PARSER.parseNode(TextNode.of(format)).toText(ctx)
        //?} else {
        /*val substituted = format
            .replace("\${name}", fromName)
            .replace("\${message}", message)
        return PARSER.parseText(substituted, PlaceholderContext.of(server).asParserContext())
        *///?}
        *///?}
    }
}
