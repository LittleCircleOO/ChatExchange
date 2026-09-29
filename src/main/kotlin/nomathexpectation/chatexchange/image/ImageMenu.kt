//? if < 26.1 {
/*package nomathexpectation.chatexchange.image

import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.server.level.ServerPlayer
import nomathexpectation.chatexchange.ChatExchangeConfig

/**
 * Fallback interaction for versions without the dialog feature: clicking the
 * placeholder sends a private message to the player containing clickable action
 * buttons mirroring the dialog buttons (preview / create map art).
 */
object ImageMenu {
    fun send(player: ServerPlayer, entry: ImagePool.Entry) {
        val root = Component.literal("")

        root.append(actionButton(ChatExchangeConfig.imageActionPreviewText.get(), "/chatexchange image preview ${entry.id}"))
        if (ChatExchangeConfig.imageMapArtEnabled.get()) {
            root.append(Component.literal("  "))
            root.append(actionButton(ChatExchangeConfig.imageActionMapArtText.get(), "/chatexchange image map ${entry.id}"))
        }

        player.sendSystemMessage(root)
    }

    private fun actionButton(translationKey: String, command: String): Component {
        return Component.translatable(translationKey).withStyle(
            Style.EMPTY
                .withUnderlined(true)
                .withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
        )
    }
}
*///?}
