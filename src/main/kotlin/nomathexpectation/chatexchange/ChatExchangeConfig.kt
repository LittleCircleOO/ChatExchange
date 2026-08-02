package nomathexpectation.chatexchange

import fuzs.forgeconfigapiport.fabric.api.v5.ConfigRegistry
import net.neoforged.fml.config.ModConfig
import net.neoforged.neoforge.common.ModConfigSpec

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
    val language: ModConfigSpec.ConfigValue<String> = builder.comment("The language the exchange server messages will be.", "Leave blank to use the language the game is using.")
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

    val ignoreBotRegex: ModConfigSpec.ConfigValue<String> = builder.comment("The regex to match and ignore the bot players.", "Leave blank to disable.")
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

    val spec: ModConfigSpec = builder.build()

    private var registered = false
    internal fun register() {
        if (registered) {
            error("Config is already registered!")
        }

        ConfigRegistry.INSTANCE.register(ChatExchange.MOD_ID, ModConfig.Type.COMMON, spec)

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
