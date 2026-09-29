package nomathexpectation.chatexchange.image

import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.Style
import nomathexpectation.chatexchange.ChatExchangeConfig

/**
 * Builds the interactive `[image]` placeholder component for one pooled image:
 * underlined text (config value = translation key, resolved per player language by
 * server-translations-api; falls back to the literal config string for custom text),
 * hover shows a small ASCII preview, click opens the preview dialog (26.x) or the
 * fallback button message (older versions).
 */
object ImagePlaceholder {
    fun build(entry: ImagePool.Entry): Component {
        val text = Component.translatable(ChatExchangeConfig.imagePlaceholderText.get())
        val style = Style.EMPTY
            .withUnderlined(true)
            .withHoverEvent(hoverEvent(entry))

        return text.withStyle(style.withClickEvent(clickEvent(entry)))
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
