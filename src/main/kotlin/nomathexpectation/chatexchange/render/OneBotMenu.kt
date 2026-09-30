//? if < 26.1 {
/*package nomathexpectation.chatexchange.render

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import nomathexpectation.chatexchange.convert.ForwardContent
import nomathexpectation.chatexchange.convert.OneBotSegment

/**
 * Fallback interactions for versions without the dialog feature: clicking a reply /
 * forward / raw-preview placeholder runs `/chatexchange view <id>`, which privately
 * sends the expanded content to the player. Nested forwards become clickable
 * placeholders again, limited to [MessageRenderer.MAX_EXPAND_DEPTH] expansion levels.
 * Placeholder visuals come from [MessageRenderer.placeholder]
 * (per-type `onebot.<type>Format` configs + localization keys).
 */
object OneBotInteractions {
    private const val PREVIEW_MAX_CHARS = 100

    fun replyPlaceholder(server: MinecraftServer, quoted: List<OneBotSegment>?, body: List<OneBotSegment>, level: Int): Component {
        val hover = quoted?.let {
            Component.literal(truncate(MessageRenderer.plainRender(server, it).string, PREVIEW_MAX_CHARS))
        } ?: Component.translatable("chatexchange.onebot.reply.unavailable")
        val id = OneBotPool.put(OneBotPool.Entry.Reply(quoted, body, level + 1))
        return MessageRenderer.placeholder(server, OneBotPlaceholder.REPLY)
            .withStyle { style ->
                style
                    .withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, hover))
                    .withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, "/chatexchange view $id"))
            }
    }

    fun forwardPlaceholder(server: MinecraftServer, content: ForwardContent, level: Int): Component {
        val display = MessageRenderer.placeholder(server, OneBotPlaceholder.FORWARD)
        if (!content.expandable || level >= MessageRenderer.MAX_EXPAND_DEPTH) {
            return content.summary?.let {
                display.withStyle { style ->
                    style.withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(it)))
                }
            } ?: display
        }
        val id = OneBotPool.put(OneBotPool.Entry.Forward(content, level + 1))
        return display
            .withStyle { style ->
                style
                    .withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, forwardHover(content)))
                    .withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, "/chatexchange view $id"))
            }
    }

    fun rawPreviewPlaceholder(server: MinecraftServer, display: MutableComponent, hover: Component?, raw: String): Component {
        val hoverText = hover ?: Component.literal(truncate(raw, PREVIEW_MAX_CHARS))
        val id = OneBotPool.put(OneBotPool.Entry.Raw(raw))
        return display
            .withStyle { style ->
                style
                    .withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, hoverText))
                    .withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, "/chatexchange view $id"))
            }
    }

    private fun forwardHover(content: ForwardContent): Component {
        return content.summary?.let { Component.literal(it) }
            ?: Component.translatable("chatexchange.onebot.forward.count", content.records.size)
    }

    private fun truncate(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max) + "…"
}

/**
 * LRU pool of viewable inbound contents (forward records, reply quotes, raw payloads)
 * addressed by a short numeric id used by `/chatexchange view`. Rendering runs on IO
 * (image downloads) and hops back to the server thread to send the result.
 */
object OneBotPool {
    sealed class Entry {
        class Forward(val content: ForwardContent, val level: Int) : Entry()
        class Reply(val quoted: List<OneBotSegment>?, val body: List<OneBotSegment>, val level: Int) : Entry()
        class Raw(val raw: String) : Entry()
    }

    private const val CAPACITY = 128
    private const val RAW_MAX_CHARS = 1500

    private val lock = Any()
    private val entries = LinkedHashMap<Int, Entry>(16, 0.75f, true)
    private var nextId = 0

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun put(entry: Entry): Int = synchronized(lock) {
        val id = nextId++
        entries[id] = entry
        while (entries.size > CAPACITY) {
            entries.remove(entries.keys.first())
        }
        id
    }

    fun get(id: Int): Entry? = synchronized(lock) { entries[id] }

    fun render(server: MinecraftServer, player: ServerPlayer, id: Int) {
        val entry = get(id) ?: return
        scope.launch {
            val messages = build(server, entry)
            server.execute {
                messages.forEach(player::sendSystemMessage)
            }
        }
    }

    private suspend fun build(server: MinecraftServer, entry: Entry): List<Component> = when (entry) {
        is Entry.Forward -> entry.content.records.map { record ->
            val name = record.nickname ?: record.userId ?: "?"
            Component.literal("$name: ").append(
                record.content?.let { MessageRenderer.render(server, it, entry.level) }
                    ?: Component.translatable("chatexchange.onebot.record.unavailable")
            )
        }
        is Entry.Reply -> buildList {
            if (entry.quoted != null) {
                add(
                    Component.translatable("chatexchange.onebot.reply.quoted").append(
                        MessageRenderer.render(server, entry.quoted, entry.level)
                    )
                )
            }
            add(
                Component.translatable("chatexchange.onebot.reply.body").append(
                    MessageRenderer.render(server, entry.body, entry.level)
                )
            )
        }
        is Entry.Raw -> listOf(Component.literal(entry.raw.take(RAW_MAX_CHARS)))
    }
}
*///?}
