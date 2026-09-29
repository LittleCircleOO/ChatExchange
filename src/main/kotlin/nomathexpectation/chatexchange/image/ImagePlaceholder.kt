package nomathexpectation.chatexchange.image

import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.Style
import net.minecraft.server.MinecraftServer
import nomathexpectation.chatexchange.ChatExchangeConfig
import nomathexpectation.chatexchange.Formatting

/**
 * Builds the interactive image placeholder component for one pooled image.
 * The visual format comes from `imagePlaceholderFormat` (Simplified Text Format with a
 * `${image}` dynamic variable holding the localized name resolved from `imagePlaceholderText`
 * per player language via server-translations-api — same `${...}` style as the other format
 * strings); hover/click interactions are attached by the mod.
 */
object ImagePlaceholder {
    fun build(entry: ImagePool.Entry, server: MinecraftServer): Component {
        val name = Component.translatable(ChatExchangeConfig.imagePlaceholderText.get())
        val format = ChatExchangeConfig.imagePlaceholderFormat.get()

        return Formatting.formatVars(format, server, mapOf("image" to name))
            .copy()
            .withStyle { style ->
                style
                    .withHoverEvent(hoverEvent(entry))
                    .withClickEvent(clickEvent(entry))
            }
    }

    private fun hoverEvent(entry: ImagePool.Entry): HoverEvent {
        //? if >= 26.1 {
        return HoverEvent.ShowText(entry.hoverArt)
        //?} else {
        /*return HoverEvent(HoverEvent.Action.SHOW_TEXT, entry.hoverArt)
        *///?}
    }

    private fun clickEvent(entry: ImagePool.Entry): ClickEvent {
        //? if >= 26.1 {
        return ImageDialog.build(entry)
        //?} else {
        /*return ClickEvent(ClickEvent.Action.RUN_COMMAND, "/chatexchange image menu ${entry.id}")
        *///?}
    }
}
