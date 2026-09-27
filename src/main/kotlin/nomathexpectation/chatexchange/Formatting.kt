package nomathexpectation.chatexchange

//? if >= 26.1 {
import eu.pb4.placeholders.api.ParserContext
import eu.pb4.placeholders.api.ServerPlaceholderContext
//?} else {
/*import eu.pb4.placeholders.api.PlaceholderContext
*///?}
//? if >= 1.20.2 {
import eu.pb4.placeholders.api.node.DynamicTextNode
import eu.pb4.placeholders.api.parsers.TagLikeParser
//?} else {
/*import eu.pb4.placeholders.api.Placeholders
import eu.pb4.placeholders.api.parsers.PatternPlaceholderParser
import eu.pb4.placeholders.api.parsers.StaticPreParser
import eu.pb4.placeholders.api.parsers.TextParserV1
*///?}
import eu.pb4.placeholders.api.node.TextNode
import eu.pb4.placeholders.api.parsers.NodeParser
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
 *  - 1.20.1 (2.1.4): no builder — the parser chain is merged by hand, following StyledChat's
 *    2.1.4-era approach: `TextParserV1.DEFAULT` (quick-text) + `Placeholders.DEFAULT_PLACEHOLDER_PARSER`
 *    (`%...%` global placeholders) + `PatternPlaceholderParser` binding `${...}` to a
 *    `DynamicNodeCompat` lookup + `StaticPreParser`. Simplified Text Format tags (`<red>` etc.)
 *    remain unavailable on 1.20.1.
 */
object Formatting {
    /**
     * `${...}` local-variable lookup key (cf. StyledChat `ChatStyle.DYN_KEY`).
     * Bound via [ParserContext.with] to a `Function<String, Component>` that returns the
     * `Component` for a given local var name (e.g. `${player}`, `${name}`, `${message}`).
     * Values are returned as-is, i.e. **not re-parsed**, so external/user-supplied text
     * can never inject formatting tags.
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
        /*NodeParser.merge(
            TextParserV1.DEFAULT, // quick-text tags
            Placeholders.DEFAULT_PLACEHOLDER_PARSER, // %...% global placeholders (%player:*%, %server:*%, ...)
            PatternPlaceholderParser(PatternPlaceholderParser.PREDEFINED_PLACEHOLDER_PATTERN, DynamicNodeCompat::of), // ${...}
            StaticPreParser.INSTANCE,
        )
        *///?}

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
        //? if >= 26.1 {
        val ctx = ServerPlaceholderContext.of(source).asParserContext()
            .with(DYN_KEY, lookup)
        return PARSER.parseNode(TextNode.of(format)).toComponent(ctx)
        //?} else {
        /*//? if >= 1.20.2 {
        val ctx = PlaceholderContext.of(source).asParserContext()
            .with(DYN_KEY, lookup)
        return PARSER.parseNode(TextNode.of(format)).toText(ctx)
        //?} else {
        /*val ctx = PlaceholderContext.of(source).asParserContext()
            .with(DynamicNodeCompat.NODES, lookup)
        return PARSER.parseNode(TextNode.of(format)).toText(ctx)
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
        val vars = mapOf(
            "name" to Component.literal(fromName),
            "message" to Component.literal(message),
        )
        val lookup = Function<String, Component?> { key -> vars[key] ?: Component.empty() }
        //? if >= 26.1 {
        val ctx = ServerPlaceholderContext.of(server).asParserContext()
            .with(DYN_KEY, lookup)
        return PARSER.parseNode(TextNode.of(format)).toComponent(ctx)
        //?} else {
        /*//? if >= 1.20.2 {
        val ctx = PlaceholderContext.of(server).asParserContext()
            .with(DYN_KEY, lookup)
        return PARSER.parseNode(TextNode.of(format)).toText(ctx)
        //?} else {
        /*val ctx = PlaceholderContext.of(server).asParserContext()
            .with(DynamicNodeCompat.NODES, lookup)
        return PARSER.parseNode(TextNode.of(format)).toText(ctx)
        *///?}
        *///?}
    }
}
