package nomathexpectation.chatexchange

//? if >= 26.1 {
import eu.pb4.placeholders.api.ArgumentParser
//?}
import eu.pb4.placeholders.api.PlaceholderResult
import eu.pb4.placeholders.api.Placeholders
//? if >= 26.1 {
import net.minecraft.resources.Identifier
//?} else {
/*import net.minecraft.resources.ResourceLocation
*///?}

/**
 * TextPlaceholderAPI placeholders exposed by ChatExchange for other mods (e.g. StyledChat).
 *
 * `%chatexchange:isbroadcast%` — resolves to `"TRUE"` when the context player's chat is being
 * broadcast (i.e. `chat` config on and the player is not opted out via `broadcastme`), otherwise
 * to the empty string. Since the `@bc` prefix trigger was removed, this value is exactly equal to
 * the broadcast decision, so it never disagrees with reality.
 *
 * placeholder-api 3.x (26.x) uses the typed `registerServer`; 2.x (<= 1.21.x) only exposes the
 * generic `register`, which on the server side is equivalent.
 */
fun registerChatExchangePlaceholders() {
    //? if >= 26.1 {
    Placeholders.registerServer(
        Identifier.fromNamespaceAndPath(ChatExchange.MOD_ID, "isbroadcast"),
        ArgumentParser.STRING,
    ) { ctx, _ ->
        val player = ctx.serverPlayer()
    //?} else {
    /*Placeholders.register(
        //? if >= 1.21 {
        ResourceLocation.fromNamespaceAndPath(ChatExchange.MOD_ID, "isbroadcast")
        //?} else {
        /*ResourceLocation(ChatExchange.MOD_ID, "isbroadcast")
        *///?}
    ) { ctx, _ ->
        val player = ctx.player()
    *///?}
        val broadcasting = player != null
            && ChatExchangeConfig.chat.get()
            && !ctx.server().chatExchangeData.isIgnoredPlayer(player.uuid)

        PlaceholderResult.value(if (broadcasting) "TRUE" else "")
    }
}
