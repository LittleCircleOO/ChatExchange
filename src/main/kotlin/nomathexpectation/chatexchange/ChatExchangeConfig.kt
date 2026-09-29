package nomathexpectation.chatexchange

// ForgeConfigAPIPort vendors the config spec at different packages per MC generation:
//   26.x        -> fuzs.forgeconfigapiport.fabric.api.v5.ConfigRegistry + net.neoforged.neoforge.common.ModConfigSpec
//   1.20.2-1.21.x -> ...fabric.api.neoforge.v4.NeoForgeConfigRegistry + net.neoforged.neoforge.common.ModConfigSpec
//   1.20.1      -> ...api.config.v2.ForgeConfigRegistry + net.minecraftforge.common.ForgeConfigSpec (aliased below)
// The Kotlin import alias lets the rest of the file use `ModConfigSpec` uniformly.
//? if >= 26.1 {
import fuzs.forgeconfigapiport.fabric.api.v5.ConfigRegistry
import net.neoforged.fml.config.ModConfig
import net.neoforged.neoforge.common.ModConfigSpec
//?} else {
/*//? if >= 1.20.2 {
import fuzs.forgeconfigapiport.fabric.api.neoforge.v4.NeoForgeConfigRegistry
import net.neoforged.fml.config.ModConfig
import net.neoforged.neoforge.common.ModConfigSpec
//?} else {
/*import fuzs.forgeconfigapiport.api.config.v2.ForgeConfigRegistry
import net.minecraftforge.fml.config.ModConfig
import net.minecraftforge.common.ForgeConfigSpec as ModConfigSpec
*///?}
*///?}

object ChatExchangeConfig {
    private val builder = ModConfigSpec.Builder()

    val host: ModConfigSpec.ConfigValue<String> = builder.comment("The host to bind the exchange server to.")
        .translation("chatexchange.config.host")
        .worldRestart()
        .define("host", "0.0.0.0")
    val port: ModConfigSpec.IntValue = builder.comment("The port to bind the exchange server to.")
        .translation("chatexchange.config.port")
        .worldRestart()
        .defineInRange("port", 9002, 0, 65535)
    val token: ModConfigSpec.ConfigValue<String> =
        builder.comment("The token to authenticate with the exchange server.", "Leave blank to disable authentication.")
            .translation("chatexchange.config.token")
            .worldRestart()
            .define("token", "")
    val language: ModConfigSpec.ConfigValue<String> = builder.comment("The language messages will be translated to when forwarded to external exchange clients.", "Leave blank to use the language the game is using.", "Does not affect in-game command feedback, which follows each player's client language.", "Missing keys can be supplied or overridden via packs in config/chatexchange_resourcepacks/.")
        .translation("chatexchange.config.language")
        .worldRestart()
        .define("language", "")
    val maxSafeReadBytesPerEvent: ModConfigSpec.ConfigValue<Int> = builder.comment("The max bytes for each event to be read from client.", "Clients who send events that exceed this limit will result in immediate disconnection.")
        .translation("chatexchange.config.maxSafeReadBytesPerEvent")
        .worldRestart()
        .defineInRange("maxSafeReadBytesPerEvent", 1024 * 1024, 1, Int.MAX_VALUE)
    val maxConnectionsPerAddress: ModConfigSpec.ConfigValue<Int> = builder.comment("The max client connections for each address.")
        .translation("chatexchange.config.maxConnectionsPerAddress")
        .worldRestart()
        .defineInRange("maxConnectionsPerAddress", 5, 1, Int.MAX_VALUE)

    val mixinMode: ModConfigSpec.BooleanValue = builder.comment("Legacy: on Fabric the chat Mixin is always used (no ServerChatEvent exists). Kept for config-file compatibility only.")
        .translation("chatexchange.config.mixinMode")
        .define("mixinMode", true)

    val ignoreBotRegex: ModConfigSpec.ConfigValue<String> = builder.comment("The regex (full match, i.e. implicitly anchored to the whole player name) to match and ignore the bot players.", "Example: [Bb][Oo][Tt]_.* ignores names starting with Bot_.", "Leave blank to disable.")
        .translation("chatexchange.config.ignoreBotRegex")
        .define("ignoreBotRegex", "") { it: Any? ->
            kotlin.runCatching {
                val str = it as String
                if (str.isBlank()) {
                    return@runCatching true
                }

                str.toRegex()
                true
            }.getOrDefault(false)
        }
    val chat: ModConfigSpec.BooleanValue = builder.comment("Whether to broadcast player chatting.", "Players can also broadcast their message by prefixing @broadcast.")
        .translation("chatexchange.config.chat")
        .define("chat", true)
    val joinLeave: ModConfigSpec.BooleanValue = builder.comment("Whether to broadcast player joining and leaving.")
        .translation("chatexchange.config.joinLeave")
        .define("joinLeave", true)
    val death: ModConfigSpec.BooleanValue = builder.comment("Whether to broadcast player deaths.")
        .translation("chatexchange.config.death")
        .define("death", true)
    val advancement: ModConfigSpec.BooleanValue = builder.comment("Whether to broadcast player advancements.")
        .translation("chatexchange.config.advancement")
        .define("advancement", true)

    val commandBroadcastFormat: ModConfigSpec.ConfigValue<String> = builder.comment("The message format when player broadcast message through system chat.", "Uses Simplified Text Format. Local vars: player (display name), message (broadcast body). Will not prepend broadcast prefix.")
        .translation("chatexchange.config.commandBroadcastFormat")
        .define("commandBroadcastFormat", $$"""<${player}> ${message}""") { it: Any? ->
            Formatting.validate(it as? String)
        }
    val receiveMessageFormat: ModConfigSpec.ConfigValue<String> = builder.comment("The message format when receiving message from outside.", "Uses Simplified Text Format. Local vars: name (external sender), message.")
        .translation("chatexchange.config.receiveMessageFormat")
        .define("receiveMessageFormat", $$"""<${name}> ${message}""") { it: Any? ->
            Formatting.validate(it as? String)
        }

    val imageEnabled: ModConfigSpec.BooleanValue = builder.push("image").comment("Whether to render images referenced by inbound messages (CICode with http(s)/base64 urls).", "When disabled, image codes are forwarded as plain text.")
        .translation("chatexchange.config.image.enabled")
        .define("enabled", true)
    val imagePlaceholderText: ModConfigSpec.ConfigValue<String> = builder.comment("Translation key (or literal text) shown as the clickable image placeholder in chat.", "Translation keys are resolved per player language via server translations; unknown keys fall back to the literal value.")
        .translation("chatexchange.config.image.placeholderText")
        .define("placeholderText", "chatexchange.image.placeholder")
    val imageActionPreviewText: ModConfigSpec.ConfigValue<String> = builder.comment("Translation key (or literal text) for the high-resolution preview action button.")
        .translation("chatexchange.config.image.actionPreviewText")
        .define("actionPreviewText", "chatexchange.image.action.preview")
    val imageActionMapArtText: ModConfigSpec.ConfigValue<String> = builder.comment("Translation key (or literal text) for the map art creation action button.")
        .translation("chatexchange.config.image.actionMapArtText")
        .define("actionMapArtText", "chatexchange.image.action.map")
    val imageHoverPreviewWidth: ModConfigSpec.IntValue = builder.comment("Max width in characters of the ASCII preview shown when hovering the placeholder.", "Values above ~40 may wrap in the chat box and break the art alignment.")
        .translation("chatexchange.config.image.hoverPreviewWidth")
        .defineInRange("hoverPreviewWidth", 40, 1, 128)
    val imageHoverPreviewHeight: ModConfigSpec.IntValue = builder.comment("Max height in characters of the ASCII preview shown when hovering the placeholder.")
        .translation("chatexchange.config.image.hoverPreviewHeight")
        .defineInRange("hoverPreviewHeight", 40, 1, 128)
    val imageDialogPreviewWidth: ModConfigSpec.IntValue = builder.comment("Max width in characters of the ASCII preview shown in the dialog (26.x only).")
        .translation("chatexchange.config.image.dialogPreviewWidth")
        .defineInRange("dialogPreviewWidth", 58, 1, 128)
    val imageDialogPreviewHeight: ModConfigSpec.IntValue = builder.comment("Max height in characters of the ASCII preview shown in the dialog (26.x only).")
        .translation("chatexchange.config.image.dialogPreviewHeight")
        .defineInRange("dialogPreviewHeight", 44, 1, 128)
    val imageDialogBodyWidth: ModConfigSpec.IntValue = builder.comment("Fixed width in GUI pixels of the dialog preview text body (26.x only).", "0 = auto: art width in chars * dialogCharWidth + 40px padding, clamped to 1200.")
        .translation("chatexchange.config.image.dialogBodyWidth")
        .defineInRange("dialogBodyWidth", 0, 0, 1200)
    val imageDialogCharWidth: ModConfigSpec.IntValue = builder.comment("Advance width in GUI pixels of one block glyph, used by the auto dialog body width formula (26.x only).", "8 fits clients whose font provides the U+2588 glyph (unifont-style resource packs); vanilla clients render it as the 6px missing-glyph box, where 8 only adds harmless slack.")
        .translation("chatexchange.config.image.dialogCharWidth")
        .defineInRange("dialogCharWidth", 8, 4, 16)
    val imageVirtualPreviewSeconds: ModConfigSpec.IntValue = builder.comment("How long the virtual map preview entity stays in front of the player, in seconds.")
        .translation("chatexchange.config.image.virtualPreviewSeconds")
        .defineInRange("virtualPreviewSeconds", 30, 1, 600)
    val imageMapArtEnabled: ModConfigSpec.BooleanValue = builder.comment("Whether players can create real map art items from received images.", "Map art is created lazily on first request; identical images share one map id.")
        .translation("chatexchange.config.image.mapArtEnabled")
        .define("mapArtEnabled", true)
    val imageCacheSize: ModConfigSpec.IntValue = builder.comment("How many recent images to keep in memory (LRU). Older placeholders stop working.")
        .translation("chatexchange.config.image.cacheSize")
        .defineInRange("cacheSize", 64, 1, 1024)
    val imageMaxDownloadBytes: ModConfigSpec.IntValue = builder.comment("Max size of a downloaded/inline image, in bytes.")
        .translation("chatexchange.config.image.maxDownloadBytes")
        .defineInRange("maxDownloadBytes", 8 * 1024 * 1024, 1024, Int.MAX_VALUE)
    val imageDownloadTimeoutSeconds: ModConfigSpec.IntValue = builder.comment("Download connect/read timeout for http(s) images, in seconds.")
        .translation("chatexchange.config.image.downloadTimeoutSeconds")
        .defineInRange("downloadTimeoutSeconds", 10, 1, 120)
    val imageUrlAllowlist: ModConfigSpec.ConfigValue<String> = builder.comment("Comma-separated host allowlist for image downloads (suffix match).", "Leave blank to allow any host.")
        .translation("chatexchange.config.image.urlAllowlist")
        .define("urlAllowlist", "")
        .also { builder.pop() }

    val spec: ModConfigSpec = builder.build()

    private var registered = false
    internal fun register() {
        if (registered) {
            error("Config is already registered!")
        }

        //? if >= 26.1 {
        ConfigRegistry.INSTANCE.register(ChatExchange.MOD_ID, ModConfig.Type.COMMON, spec)
        //?} else {
        /*//? if >= 1.20.2 {
        NeoForgeConfigRegistry.INSTANCE.register(ChatExchange.MOD_ID, ModConfig.Type.COMMON, spec)
        //?} else {
        /*ForgeConfigRegistry.INSTANCE.register(ChatExchange.MOD_ID, ModConfig.Type.COMMON, spec)
        *///?}
        *///?}

        registered = true
    }

    fun checkIgnoreBot(name: String): Boolean {
        val regexStr = ignoreBotRegex.get()
        if (regexStr.isBlank()) {
            return false
        }

        val regex = regexStr.toRegex()
        return regex.matches(name)
    }
}
