//? if >= 26.1 {
package nomathexpectation.chatexchange.image

import net.minecraft.core.Holder
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.server.dialog.ActionButton
import net.minecraft.server.dialog.CommonButtonData
import net.minecraft.server.dialog.CommonDialogData
import net.minecraft.server.dialog.DialogAction
import net.minecraft.server.dialog.MultiActionDialog
import net.minecraft.server.dialog.action.StaticAction
import net.minecraft.server.dialog.body.PlainMessage
import nomathexpectation.chatexchange.ChatExchangeConfig
import java.util.Optional

/**
 * Builds the client-local preview dialog (26.x dialog feature) embedded into the
 * placeholder's click event: large ASCII art body plus action buttons for the
 * virtual entity preview and (if enabled) lazy map art creation.
 */
object ImageDialog {
    fun build(entry: ImagePool.Entry): ClickEvent.ShowDialog {
        val actions = mutableListOf(
            actionButton(ChatExchangeConfig.imageActionPreviewText.get(), "/chatexchange image preview ${entry.id}")
        )
        if (ChatExchangeConfig.imageMapArtEnabled.get()) {
            actions.add(
                actionButton(ChatExchangeConfig.imageActionMapArtText.get(), "/chatexchange image map ${entry.id}")
            )
        }

        val exitAction = ActionButton(
            CommonButtonData(Component.translatable("chatexchange.image.dialog.close"), 150),
            Optional.empty(),
        )

        val common = CommonDialogData(
            Component.translatable("chatexchange.image.dialog.title"),
            Optional.empty(),
            true,
            false,
            DialogAction.CLOSE,
            listOf(PlainMessage(entry.dialogArt, dialogWidth(entry))),
            listOf(),
        )

        val dialog = MultiActionDialog(common, actions, Optional.of(exitAction), if (actions.size > 1) 2 else 1)
        return ClickEvent.ShowDialog(Holder.direct(dialog))
    }

    private fun actionButton(translationKey: String, command: String): ActionButton {
        return ActionButton(
            CommonButtonData(Component.translatable(translationKey), 150),
            Optional.of(StaticAction(ClickEvent.RunCommand(command))),
        )
    }

    private fun dialogWidth(entry: ImagePool.Entry): Int {
        val configured = ChatExchangeConfig.imageDialogBodyWidth.get()
        if (configured > 0) {
            return configured
        }
        // Glyph advance depends on the client font: 8px with a unifont-style glyph,
        // 6px as the vanilla missing-glyph box. 8 adds only harmless slack on vanilla.
        return (entry.dialogArtWidthChars * ChatExchangeConfig.imageDialogCharWidth.get() + 40).coerceIn(200, 1200)
    }
}
//?}
