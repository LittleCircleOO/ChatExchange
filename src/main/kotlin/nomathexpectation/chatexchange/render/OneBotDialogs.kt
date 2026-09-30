//? if >= 26.1 {
package nomathexpectation.chatexchange.render

import net.minecraft.core.Holder
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.MinecraftServer
import net.minecraft.server.dialog.ActionButton
import net.minecraft.server.dialog.CommonButtonData
import net.minecraft.server.dialog.CommonDialogData
import net.minecraft.server.dialog.DialogAction
import net.minecraft.server.dialog.MultiActionDialog
import net.minecraft.server.dialog.action.StaticAction
import net.minecraft.server.dialog.body.PlainMessage
import nomathexpectation.chatexchange.convert.ForwardContent
import nomathexpectation.chatexchange.convert.OneBotSegment
import java.util.Optional

/**
 * Dialog-based interactions (26.x dialog feature) for inbound OneBot messages: reply
 * quotes, merged forwards and raw-previews embed a `MultiActionDialog` directly into the
 * placeholder's click event, so no server-side state or commands are involved. Nested
 * forwards found in dialog bodies become additional buttons, limited to
 * [MessageRenderer.MAX_EXPAND_DEPTH] expansion levels. Placeholder visuals come from
 * [MessageRenderer.placeholder] (per-type `onebot.<type>Format` configs + localization keys).
 */
object OneBotInteractions {
    private const val DIALOG_BODY_WIDTH = 420
    private const val PREVIEW_MAX_CHARS = 100
    private const val RAW_MAX_CHARS = 1500

    fun replyPlaceholder(server: MinecraftServer, quoted: List<OneBotSegment>?, body: List<OneBotSegment>, level: Int): Component {
        val hover = quoted?.let {
            Component.literal(truncate(MessageRenderer.plainRender(server, it).string, PREVIEW_MAX_CHARS))
        } ?: Component.translatable("chatexchange.onebot.reply.unavailable")
        return MessageRenderer.placeholder(server, OneBotPlaceholder.REPLY)
            .withStyle { style ->
                style
                    .withHoverEvent(HoverEvent.ShowText(hover))
                    .withClickEvent(ClickEvent.ShowDialog(Holder.direct(replyDialog(server, quoted, body, level + 1))))
            }
    }

    fun forwardPlaceholder(server: MinecraftServer, content: ForwardContent, level: Int): Component {
        val display = MessageRenderer.placeholder(server, OneBotPlaceholder.FORWARD)
        if (!content.expandable || level >= MessageRenderer.MAX_EXPAND_DEPTH) {
            return content.summary?.let {
                display.withStyle { style -> style.withHoverEvent(HoverEvent.ShowText(Component.literal(it))) }
            } ?: display
        }
        return display
            .withStyle { style ->
                style
                    .withHoverEvent(HoverEvent.ShowText(forwardHover(content)))
                    .withClickEvent(ClickEvent.ShowDialog(Holder.direct(forwardDialog(server, content, level + 1))))
            }
    }

    fun rawPreviewPlaceholder(server: MinecraftServer, display: MutableComponent, hover: Component?, raw: String): Component {
        val hoverText = hover ?: Component.literal(truncate(raw, PREVIEW_MAX_CHARS))
        return display
            .withStyle { style ->
                style
                    .withHoverEvent(HoverEvent.ShowText(hoverText))
                    .withClickEvent(ClickEvent.ShowDialog(Holder.direct(rawDialog(raw))))
            }
    }

    private fun replyDialog(server: MinecraftServer, quoted: List<OneBotSegment>?, body: List<OneBotSegment>, level: Int): MultiActionDialog {
        val nested = if (level < MessageRenderer.MAX_EXPAND_DEPTH) mutableListOf<ForwardContent>() else null
        val text = Component.literal("")
        if (quoted != null) {
            text.append(Component.translatable("chatexchange.onebot.reply.quoted"))
            text.append(Component.literal("\n"))
            text.append(MessageRenderer.plainRender(server, quoted, nested))
            text.append(Component.literal("\n\n"))
        }
        text.append(Component.translatable("chatexchange.onebot.reply.body"))
        text.append(Component.literal("\n"))
        text.append(MessageRenderer.plainRender(server, body, nested))
        return dialog("chatexchange.onebot.reply.dialog.title", text, nestedButtons(server, nested, level))
    }

    private fun forwardDialog(server: MinecraftServer, content: ForwardContent, level: Int): MultiActionDialog {
        val nested = if (level < MessageRenderer.MAX_EXPAND_DEPTH) mutableListOf<ForwardContent>() else null
        val text = Component.literal("")
        content.records.forEachIndexed { index, record ->
            if (index > 0) {
                text.append(Component.literal("\n"))
            }
            val name = record.nickname ?: record.userId ?: "?"
            text.append(Component.literal("$name: "))
            text.append(
                record.content?.let { MessageRenderer.plainRender(server, it, nested) }
                    ?: Component.translatable("chatexchange.onebot.record.unavailable")
            )
        }
        return dialog("chatexchange.onebot.forward.dialog.title", text, nestedButtons(server, nested, level))
    }

    private fun rawDialog(raw: String): MultiActionDialog {
        return dialog(
            "chatexchange.onebot.raw.dialog.title",
            Component.literal(truncate(raw, RAW_MAX_CHARS)),
            emptyList(),
        )
    }

    private fun nestedButtons(server: MinecraftServer, nested: MutableList<ForwardContent>?, level: Int): List<ActionButton> {
        if (nested == null || nested.isEmpty()) {
            return emptyList()
        }
        return nested.map { content ->
            val label = MessageRenderer.placeholder(server, OneBotPlaceholder.FORWARD, content.summary?.let { Component.literal(" $it") })
            if (level < MessageRenderer.MAX_EXPAND_DEPTH) {
                ActionButton(
                    CommonButtonData(label, 150),
                    Optional.of(StaticAction(ClickEvent.ShowDialog(Holder.direct(forwardDialog(server, content, level + 1))))),
                )
            } else {
                ActionButton(CommonButtonData(label, 150), Optional.empty())
            }
        }
    }

    private fun forwardHover(content: ForwardContent): Component {
        return content.summary?.let { Component.literal(it) }
            ?: Component.translatable("chatexchange.onebot.forward.count", content.records.size)
    }

    private fun dialog(titleKey: String, body: Component, actions: List<ActionButton>): MultiActionDialog {
        val exitAction = ActionButton(
            CommonButtonData(Component.translatable("chatexchange.image.dialog.close"), 150),
            Optional.empty(),
        )
        val common = CommonDialogData(
            Component.translatable(titleKey),
            Optional.empty(),
            true,
            false,
            DialogAction.CLOSE,
            listOf(PlainMessage(body, DIALOG_BODY_WIDTH)),
            listOf(),
        )
        return MultiActionDialog(common, actions, Optional.of(exitAction), 1)
    }

    private fun truncate(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max) + "…"
}
//?}
