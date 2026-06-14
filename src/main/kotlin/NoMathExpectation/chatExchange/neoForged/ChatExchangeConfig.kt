package NoMathExpectation.chatExchange.neoForged

import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.fml.config.ModConfig
import net.neoforged.fml.event.config.ModConfigEvent
import net.neoforged.neoforge.common.ModConfigSpec
import thedarkcolour.kotlinforforge.neoforge.forge.LOADING_CONTEXT
import thedarkcolour.kotlinforforge.neoforge.forge.runWhenOn

@EventBusSubscriber(
    modid = ChatExchange.ID,
)
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

    val mixinMode: ModConfigSpec.BooleanValue = builder.comment("Whether to use mixin instead of event to listen to server chats.", "If the exchange server isn't sending server chat, try turn this on.")
        .translation("chatexchange.config.mixinMode")
        .define("mixinMode", false)

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

    val broadcastTriggerPrefix: ModConfigSpec.ConfigValue<MutableList<out String>> = builder.comment("The prefix to recognize to trigger broadcast in chat message.")
        .translation("chatexchange.config.broadcastTriggerPrefix")
        .defineListAllowEmpty(
            "broadcastTriggerPrefix",
            { mutableListOf("@广播", "@bc", "@broadcast") },
            { "@broadcast" },
            { true }
        )
    val broadcastPrefix: ModConfigSpec.ConfigValue<String> = builder.comment("The prefix to prepend when player broadcast message through player chat.")
        .translation("chatexchange.config.broadcastPrefix")
        .define("broadcastPrefix", "[]") { it: Any? ->
            testJson(it as? String)
        }
    val commandBroadcastFormat: ModConfigSpec.ConfigValue<String> = builder.comment("The message format when player broadcast message through system chat.", "Will not prepend broadcast prefix.")
        .translation("chatexchange.config.commandBroadcastFormat")
        .define("commandBroadcastFormat", """["<", {"selector": "@s"}, "> "]""") { it: Any? ->
            testJson(it as? String)
        }
    val receiveMessageFormat: ModConfigSpec.ConfigValue<String> = builder.comment("The message format when receiving message from outside.")
        .translation("chatexchange.config.receiveMessageFormat")
        .define("receiveMessageFormat", $$"""["<", "$name", "> "]""") { it: Any? ->
            testJson(it as? String)
        }

    private fun testJson(text: String?) = runCatching {
        text?.parseJsonToComponent() ?: return@runCatching false
        true
    }.getOrDefault(false)

    val spec: ModConfigSpec = builder.build()

    private var registered = false
    internal fun register() {
        if (registered) {
            error("Config is already registered!")
        }

        val modContainer = LOADING_CONTEXT.activeContainer
        modContainer.registerConfig(ModConfig.Type.COMMON, spec)
        runWhenOn(Dist.CLIENT) {
            registerOnClient()
        }

        registered = true
    }

    @SubscribeEvent
    fun onConfig(event: ModConfigEvent) {
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

fun String.startsWithBroadcastPrefix() =
    ChatExchangeConfig.broadcastTriggerPrefix
        .get()
        .any { startsWith(it) }

fun String.removeBroadcastPrefix() = run {
    ChatExchangeConfig.broadcastTriggerPrefix
        .get()
        .forEach {
            if (startsWith(it)) {
                return@run removePrefix(it)
            }
        }
    this
}.trimStart()