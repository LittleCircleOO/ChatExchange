package nomathexpectation.chatexchange

import eu.pb4.placeholders.api.ArgumentParser
import eu.pb4.placeholders.api.PlaceholderResult
import eu.pb4.placeholders.api.Placeholders
import net.minecraft.resources.Identifier

/**
 * TextPlaceholderAPI placeholders exposed by ChatExchange for other mods (e.g. StyledChat).
 *
 * `%chatexchange:isbroadcast%` — resolves to `"TRUE"` when the context player's chat is being
 * broadcast (i.e. `chat` config on and the player is not opted out via `broadcastme`), otherwise
 * to the empty string. Since the `@bc` prefix trigger was removed, this value is exactly equal to
 * the broadcast decision, so it never disagrees with reality.
 */
fun registerChatExchangePlaceholders() {
    Placeholders.registerServer(
        Identifier.fromNamespaceAndPath(ChatExchange.MOD_ID, "isbroadcast"),
        ArgumentParser.STRING,
    ) { ctx, _ ->
        val player = ctx.serverPlayer()
        val broadcasting = player != null
            && ChatExchangeConfig.chat.get()
            && !ctx.server().chatExchangeData.isIgnoredPlayer(player.uuid)

        PlaceholderResult.value(if (broadcasting) "TRUE" else "")
    }
}
