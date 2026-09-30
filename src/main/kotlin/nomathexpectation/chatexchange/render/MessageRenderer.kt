package nomathexpectation.chatexchange.render

//? if >= 26.1 {
import java.net.URI
//?}
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.MinecraftServer
import nomathexpectation.chatexchange.ChatExchangeConfig
import nomathexpectation.chatexchange.Formatting
import nomathexpectation.chatexchange.convert.FaceTable
import nomathexpectation.chatexchange.convert.ForwardContent
import nomathexpectation.chatexchange.convert.Nicknames
import nomathexpectation.chatexchange.convert.OneBotParser
import nomathexpectation.chatexchange.convert.OneBotSegment
import nomathexpectation.chatexchange.convert.ParsedContent
import nomathexpectation.chatexchange.convert.pokeMessageOf
import nomathexpectation.chatexchange.convert.splitReplyPrefix
import nomathexpectation.chatexchange.image.CICode
import nomathexpectation.chatexchange.image.ImagePlaceholder
import nomathexpectation.chatexchange.image.ImagePool

/**
 * Rendered inbound message: [component] is the `${message}` body for the normal receive
 * format; [poke] is non-null for poke-only messages, which the caller broadcasts with
 * the dedicated poke format (or drops entirely when disabled).
 */
class RenderedMessage(
    val component: Component,
    val poke: PokeInfo?,
)

/** Poke action/target pieces for the dedicated poke format variables. */
class PokeInfo(
    val action: Component,
    val target: Component,
)

/**
 * Bracket-style OneBot placeholders. Each type pairs its localization key with its
 * dedicated format config (`onebot.<type>Format`), so key names and config names share
 * the same type token.
 */
enum class OneBotPlaceholder(val key: String, val format: () -> String) {
    IMAGE("chatexchange.onebot.image", { ChatExchangeConfig.onebotImageFormat.get() }),
    VOICE("chatexchange.onebot.voice", { ChatExchangeConfig.onebotVoiceFormat.get() }),
    VIDEO("chatexchange.onebot.video", { ChatExchangeConfig.onebotVideoFormat.get() }),
    LINK("chatexchange.onebot.link", { ChatExchangeConfig.onebotLinkFormat.get() }),
    REPLY("chatexchange.onebot.reply", { ChatExchangeConfig.onebotReplyFormat.get() }),
    FORWARD("chatexchange.onebot.forward", { ChatExchangeConfig.onebotForwardFormat.get() }),
    FLASH("chatexchange.onebot.flash", { ChatExchangeConfig.onebotFlashFormat.get() }),
    UNSUPPORTED("chatexchange.onebot.unsupported", { ChatExchangeConfig.onebotUnsupportedFormat.get() }),
}

/**
 * Inbound message renderer. Dispatches between the legacy plain-text/CICode format and
 * the OneBot 11 segment-array format now emitted by NMEBoot, and renders OneBot segments
 * to Minecraft components. [level] is the expansion depth of the content being rendered
 * (0 = chat message, 1+ = inside a dialog/view); forward/reply interactions stop being
 * clickable from level [MAX_EXPAND_DEPTH] on to avoid unbounded nesting.
 *
 * Bracket-style placeholders (image/voice/video/link/reply/forward/flash/unsupported)
 * follow the image-placeholder pattern: the inner text is a localization key resolved
 * per player language, while brackets and extra styling come from the per-type
 * `onebot.<type>Format` config template (`${placeholder}` variable, named after the same
 * type token as the key); hover/click interactions are attached automatically by
 * [OneBotInteractions] where applicable.
 */
object MessageRenderer {
    const val MAX_EXPAND_DEPTH = 2

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun buildMessage(server: MinecraftServer, content: String): RenderedMessage {
        return when (val parsed = OneBotParser.parse(content)) {
            is ParsedContent.Legacy -> RenderedMessage(ImagePool.buildMessage(server, parsed.text), null)
            is ParsedContent.OneBot -> {
                val poke = pokeMessageOf(parsed.segments)
                if (poke != null) {
                    RenderedMessage(Component.literal(""), pokeInfo(poke))
                } else {
                    RenderedMessage(render(server, parsed.segments, 0), null)
                }
            }
        }
    }

    private fun pokeInfo(poke: OneBotSegment.Poke): PokeInfo {
        val action = poke.name?.let { Component.literal(it) }
            ?: Component.translatable("chatexchange.onebot.poke.action")
        val target = poke.qq?.let { Component.literal(Nicknames.get(it) ?: it) }
            ?: Component.translatable("chatexchange.onebot.poke.someone")
        return PokeInfo(action, target)
    }

    suspend fun render(server: MinecraftServer, segments: List<OneBotSegment>, level: Int): Component {
        val first = segments.firstOrNull()
        if (first is OneBotSegment.Reply) {
            val split = splitReplyPrefix(segments)
            val root = Component.literal("")
            root.append(OneBotInteractions.replyPlaceholder(server, split.quoted, split.body, level))
            root.append(renderSegments(server, split.body, level))
            return root
        }
        return renderSegments(server, segments, level)
    }

    private suspend fun renderSegments(server: MinecraftServer, segments: List<OneBotSegment>, level: Int): Component {
        val root = Component.literal("")
        var i = 0
        while (i < segments.size) {
            val segment = segments[i]
            when (segment) {
                is OneBotSegment.Text -> root.append(ImagePool.buildMessage(server, segment.text))
                is OneBotSegment.Face -> FaceTable.emoji(segment.id)?.let { root.append(Component.literal(it)) }
                is OneBotSegment.Image -> root.append(renderImage(server, segment))
                is OneBotSegment.Record -> root.append(mediaPlaceholder(server, OneBotPlaceholder.VOICE, mediaUrl(segment)))
                is OneBotSegment.Video -> root.append(mediaPlaceholder(server, OneBotPlaceholder.VIDEO, mediaUrl(segment)))
                is OneBotSegment.At -> root.append(renderAt(segment))
                is OneBotSegment.RockPaperScissors -> root.append(Component.literal(FaceTable.rockPaperScissors(segment.value)))
                is OneBotSegment.Dice -> root.append(Component.literal(FaceTable.dice(segment.value)))
                is OneBotSegment.Share -> root.append(linkPlaceholder(server, segment))
                is OneBotSegment.Forward -> root.append(
                    OneBotInteractions.forwardPlaceholder(server, ForwardContent.fromForwardId(segment.id), level)
                )
                is OneBotSegment.ForwardNode -> {
                    val nodes = mutableListOf<OneBotSegment.ForwardNode>()
                    while (i < segments.size && segments[i] is OneBotSegment.ForwardNode) {
                        nodes.add(segments[i] as OneBotSegment.ForwardNode)
                        i++
                    }
                    root.append(OneBotInteractions.forwardPlaceholder(server, ForwardContent.fromNodes(nodes), level))
                    continue
                }
                is OneBotSegment.JsonCard -> root.append(renderJsonCard(server, segment, level))
                is OneBotSegment.Xml -> root.append(unsupportedPlaceholder(server, "xml", segment.data))
                is OneBotSegment.Unknown -> root.append(unsupportedPlaceholder(server, segment.type, segment.data))
                is OneBotSegment.Poke, is OneBotSegment.Contact, is OneBotSegment.Location, is OneBotSegment.Reply -> {}
            }
            i++
        }
        return root
    }

    /**
     * Non-interactive rendering for dialog bodies: images/media become plain placeholders,
     * nested forwards become [合并转发] placeholders (collected into [nestedOut] so the
     * caller can offer them as buttons).
     */
    fun plainRender(server: MinecraftServer, segments: List<OneBotSegment>, nestedOut: MutableList<ForwardContent>? = null): Component {
        val root = Component.literal("")
        var i = 0
        while (i < segments.size) {
            val segment = segments[i]
            when (segment) {
                is OneBotSegment.Text -> root.append(Component.literal(segment.text))
                is OneBotSegment.Face -> FaceTable.emoji(segment.id)?.let { root.append(Component.literal(it)) }
                is OneBotSegment.Image ->
                    root.append(placeholder(server, if (segment.flash) OneBotPlaceholder.FLASH else OneBotPlaceholder.IMAGE))
                is OneBotSegment.Record -> root.append(placeholder(server, OneBotPlaceholder.VOICE))
                is OneBotSegment.Video -> root.append(placeholder(server, OneBotPlaceholder.VIDEO))
                is OneBotSegment.At -> root.append(renderAt(segment))
                is OneBotSegment.RockPaperScissors -> root.append(Component.literal(FaceTable.rockPaperScissors(segment.value)))
                is OneBotSegment.Dice -> root.append(Component.literal(FaceTable.dice(segment.value)))
                is OneBotSegment.Share -> root.append(plainLink(server, segment))
                is OneBotSegment.Forward -> {
                    val content = ForwardContent.fromForwardId(segment.id)
                    nestedOut?.add(content)
                    root.append(placeholder(server, OneBotPlaceholder.FORWARD))
                }
                is OneBotSegment.ForwardNode -> {
                    val nodes = mutableListOf<OneBotSegment.ForwardNode>()
                    while (i < segments.size && segments[i] is OneBotSegment.ForwardNode) {
                        nodes.add(segments[i] as OneBotSegment.ForwardNode)
                        i++
                    }
                    val content = ForwardContent.fromNodes(nodes)
                    nestedOut?.add(content)
                    root.append(placeholder(server, OneBotPlaceholder.FORWARD))
                    continue
                }
                is OneBotSegment.JsonCard -> root.append(plainJsonCard(server, segment))
                is OneBotSegment.Xml, is OneBotSegment.Unknown ->
                    root.append(placeholder(server, OneBotPlaceholder.UNSUPPORTED))
                is OneBotSegment.Poke, is OneBotSegment.Contact, is OneBotSegment.Location, is OneBotSegment.Reply -> {}
            }
            i++
        }
        return root
    }

    /**
     * Builds a bracket-style placeholder from the type's `onebot.<type>Format` config
     * (`${placeholder}` = localized name of the type's key), optionally appending [suffix]
     * (e.g. a link title or forward summary). Mirrors `ImagePlaceholder.build`.
     */
    fun placeholder(server: MinecraftServer, type: OneBotPlaceholder, suffix: Component? = null): MutableComponent {
        val name = Component.translatable(type.key)
        val component = Formatting.formatVars(type.format(), server, mapOf("placeholder" to name)).copy()
        if (suffix != null) {
            component.append(suffix)
        }
        return component
    }

    private suspend fun renderImage(server: MinecraftServer, segment: OneBotSegment.Image): Component {
        if (segment.flash) {
            return placeholder(server, OneBotPlaceholder.FLASH)
        }
        val source = imageSource(segment) ?: return placeholder(server, OneBotPlaceholder.IMAGE)
        val entry = ImagePool.acquire(source) ?: return placeholder(server, OneBotPlaceholder.IMAGE)
        return ImagePlaceholder.build(entry, server)
    }

    private fun imageSource(segment: OneBotSegment.Image): CICode.ImageSource? {
        mediaUrl(segment)?.let { return CICode.ImageSource.Http(it) }
        val file = segment.file ?: return null
        return when {
            file.startsWith("base64://", ignoreCase = true) ->
                CICode.ImageSource.Inline(file.removePrefix("base64://"))
            else -> null
        }
    }

    /** Resolves a usable http(s) URL from a media segment (`url`, or `file` when it is one). */
    private fun mediaUrl(segment: OneBotSegment.Image): String? {
        segment.url?.let { if (it.startsWith("http://", true) || it.startsWith("https://", true)) return it }
        segment.file?.let { if (it.startsWith("http://", true) || it.startsWith("https://", true)) return it }
        return null
    }

    private fun mediaUrl(segment: OneBotSegment.Record): String? =
        listOf(segment.url, segment.file).firstOrNull { it != null && (it.startsWith("http://", true) || it.startsWith("https://", true)) }

    private fun mediaUrl(segment: OneBotSegment.Video): String? =
        listOf(segment.url, segment.file).firstOrNull { it != null && (it.startsWith("http://", true) || it.startsWith("https://", true)) }

    private fun mediaPlaceholder(server: MinecraftServer, type: OneBotPlaceholder, url: String?): Component {
        if (url == null) {
            return placeholder(server, type)
        }
        return placeholder(server, type)
            .withStyle { style ->
                style
                    .withHoverEvent(hoverText(Component.literal(url)))
                    .withClickEvent(openUrl(url))
            }
    }

    private fun linkPlaceholder(server: MinecraftServer, segment: OneBotSegment.Share): Component {
        val title = segment.title?.takeIf { it.isNotBlank() }?.let { Component.literal(it) }
        val url = segment.url?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
            ?: return placeholder(server, OneBotPlaceholder.LINK, title)
        return placeholder(server, OneBotPlaceholder.LINK, title)
            .withStyle { style ->
                style
                    .withHoverEvent(hoverText(Component.literal(url)))
                    .withClickEvent(openUrl(url))
            }
    }

    private fun plainLink(server: MinecraftServer, segment: OneBotSegment.Share): Component {
        val title = segment.title?.takeIf { it.isNotBlank() }?.let { Component.literal(it) }
        return placeholder(server, OneBotPlaceholder.LINK, title)
    }

    private fun renderAt(segment: OneBotSegment.At): Component {
        if (segment.qq == "all") {
            return Component.literal("@").append(Component.translatable("chatexchange.onebot.everyone"))
        }
        return Component.literal("@" + (Nicknames.get(segment.qq) ?: segment.qq))
    }

    private fun renderJsonCard(server: MinecraftServer, segment: OneBotSegment.JsonCard, level: Int): Component {
        val obj = segment.data?.let {
            runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull()
        }
        ForwardContent.fromJsonCard(obj)?.let {
            return OneBotInteractions.forwardPlaceholder(server, it, level)
        }

        val title = ForwardContent.cardTitle(obj)
        val raw = segment.data ?: ""
        return if (title != null) {
            val jump = ForwardContent.cardJumpUrl(obj)?.takeIf {
                it.startsWith("http://", true) || it.startsWith("https://", true)
            }
            if (jump != null) {
                placeholder(server, OneBotPlaceholder.LINK, Component.literal(title))
                    .withStyle { style ->
                        style
                            .withHoverEvent(hoverText(Component.literal(jump)))
                            .withClickEvent(openUrl(jump))
                    }
            } else {
                val display = placeholder(server, OneBotPlaceholder.LINK, Component.literal(title))
                OneBotInteractions.rawPreviewPlaceholder(server, display, Component.literal(title), raw)
            }
        } else {
            unsupportedPlaceholder(server, "json", raw)
        }
    }

    private fun plainJsonCard(server: MinecraftServer, segment: OneBotSegment.JsonCard): Component {
        val obj = segment.data?.let {
            runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull()
        }
        ForwardContent.fromJsonCard(obj)?.let { return placeholder(server, OneBotPlaceholder.FORWARD) }
        val title = ForwardContent.cardTitle(obj)
            ?: return placeholder(server, OneBotPlaceholder.UNSUPPORTED)
        return plainLink(server, OneBotSegment.Share(null, title, null))
    }

    private fun unsupportedPlaceholder(server: MinecraftServer, type: String, raw: String?): Component {
        val display = placeholder(server, OneBotPlaceholder.UNSUPPORTED)
        val preview = raw?.take(100)?.let { Component.literal("$type: $it") } ?: Component.literal(type)
        return OneBotInteractions.rawPreviewPlaceholder(server, display, preview, raw ?: "")
    }

    private fun hoverText(component: Component): HoverEvent {
        //? if >= 26.1 {
        return HoverEvent.ShowText(component)
        //?} else {
        /*return HoverEvent(HoverEvent.Action.SHOW_TEXT, component)
        *///?}
    }

    private fun openUrl(url: String): ClickEvent {
        //? if >= 26.1 {
        return ClickEvent.OpenUrl(URI.create(url))
        //?} else {
        /*return ClickEvent(ClickEvent.Action.OPEN_URL, url)
        *///?}
    }
}
