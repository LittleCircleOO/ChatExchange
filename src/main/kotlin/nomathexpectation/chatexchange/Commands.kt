package nomathexpectation.chatexchange

import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import nomathexpectation.chatexchange.image.ImagePool
import nomathexpectation.chatexchange.image.MapArt
import nomathexpectation.chatexchange.image.VirtualMapDisplay
//? if < 26.1 {
/*import nomathexpectation.chatexchange.image.ImageMenu
import nomathexpectation.chatexchange.render.OneBotPool
*///?}
import org.apache.logging.log4j.LogManager

private val logger = LogManager.getLogger(ChatExchange.MOD_ID)

fun registerCommands(dispatcher: com.mojang.brigadier.CommandDispatcher<CommandSourceStack>, environment: Commands.CommandSelection) {
    if (environment != Commands.CommandSelection.DEDICATED) {
        return
    }

    val commandBuilder = Commands.literal("chatexchange")
        .then(
            Commands.literal("send").then(
                Commands.argument("message", StringArgumentType.greedyString()).executes { context ->
                    val message = StringArgumentType.getString(context, "message")
                    val format = ChatExchangeConfig.commandBroadcastFormat

                    val name = ExchangeServer.componentToString(context.source.displayName)

                    val component = kotlin.runCatching {
                        Formatting.formatBroadcast(format.get(), context.source, message)
                    }.getOrElse {
                        logger.error(
                            "Unable to resolve component from command broadcast format. Using default.",
                            it
                        )
                        context.source.sendSystemMessage("chatexchange.const.exception".toTranslatableComponent())
                        Formatting.formatBroadcast(format.default, context.source, message)
                    }

                    logger.info(component.getStringWithLanguage(ExchangeServer.language))
                    ExchangeServer.sendEvent(
                        MessageEvent(name, message)
                    )
                    context.source.server.playerList.broadcastSystemMessage(component, false)

                    1
                }
            )
        ).then(
            Commands.literal("status").executes { context ->
                fun Boolean.toProperLiteral() =
                    (if (this) "chatexchange.const.enabled" else "chatexchange.const.disabled").toTranslatableComponent()

                context.source.sendSystemMessage(
                    "chatexchange.command.chatexchange.status".toTranslatableComponent(
                        ChatExchangeConfig.chat.get().toProperLiteral(),
                        ChatExchangeConfig.joinLeave.get().toProperLiteral(),
                        ChatExchangeConfig.death.get().toProperLiteral(),
                        ChatExchangeConfig.advancement.get().toProperLiteral()
                    )
                )

                1
            }
        ).then(
            Commands.literal("broadcastme").then(
                Commands.argument("toggle", BoolArgumentType.bool()).executes { context ->
                    val player = context.source.player ?: kotlin.run {
                        context.source.sendSystemMessage("chatexchange.const.onlyPlayer".toTranslatableComponent())
                        return@executes 0
                    }

                    val data = context.source.server.chatExchangeData
                    val toggle = BoolArgumentType.getBool(context, "toggle")
                    if (toggle) {
                        data.removeIgnoredPlayer(player.uuid)
                        player.sendSystemMessage("chatexchange.command.chatexchange.broadcastme.on".toTranslatableComponent())
                    } else {
                        data.addIgnoredPlayer(player.uuid)
                        player.sendSystemMessage("chatexchange.command.chatexchange.broadcastme.off".toTranslatableComponent())
                    }

                    1
                }
            ).executes { context ->
                val player = context.source.player ?: kotlin.run {
                    context.source.sendSystemMessage("chatexchange.const.onlyPlayer".toTranslatableComponent())
                    return@executes 0
                }

                val data = context.source.server.chatExchangeData
                if (data.isIgnoredPlayer(player.uuid)) {
                    player.sendSystemMessage("chatexchange.command.chatexchange.broadcastme.isoff".toTranslatableComponent())
                } else {
                    player.sendSystemMessage("chatexchange.command.chatexchange.broadcastme.ison".toTranslatableComponent())
                }

                1
            }
        ).then(imageCommand())
        //? if < 26.1 {
        /*.then(
            Commands.literal("view").then(
                Commands.argument("id", IntegerArgumentType.integer(0)).executes { context ->
                    val player = context.source.player ?: kotlin.run {
                        context.source.sendSystemMessage("chatexchange.const.onlyPlayer".toTranslatableComponent())
                        return@executes 0
                    }

                    val id = IntegerArgumentType.getInteger(context, "id")
                    if (OneBotPool.get(id) == null) {
                        player.sendSystemMessage("chatexchange.onebot.expired".toTranslatableComponent())
                        return@executes 0
                    }

                    OneBotPool.render(context.source.server, player, id)
                    1
                }
            )
        )
        *///?}
        .executes { context ->
            context.source.sendSystemMessage(
                "chatexchange.command.chatexchange.description".toTranslatableComponent()
            )
            1
        }
    val command = dispatcher.register(commandBuilder)

    dispatcher.register(Commands.literal("ce").redirect(command))

    // Top-level aliases: /bc <message> == /chatexchange send <message>;
    // /bcme <true|false> == /chatexchange broadcastme <true|false>.
    dispatcher.register(Commands.literal("bc").redirect(command.getChild("send")))
    dispatcher.register(Commands.literal("bcme").redirect(command.getChild("broadcastme")))
}

private fun imageCommand(): com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> {
    val builder = Commands.literal("image")
        .then(
            Commands.literal("preview").then(
                Commands.argument("id", IntegerArgumentType.integer(0)).executes { context ->
                    val player = context.source.player ?: kotlin.run {
                        context.source.sendSystemMessage("chatexchange.const.onlyPlayer".toTranslatableComponent())
                        return@executes 0
                    }

                    val id = IntegerArgumentType.getInteger(context, "id")
                    val entry = ImagePool.get(id)
                    if (entry == null) {
                        player.sendSystemMessage("chatexchange.image.expired".toTranslatableComponent())
                        return@executes 0
                    }

                    VirtualMapDisplay.show(player, entry)
                    1
                }
            )
        ).then(
            Commands.literal("map").then(
                Commands.argument("id", IntegerArgumentType.integer(0)).executes { context ->
                    val player = context.source.player ?: kotlin.run {
                        context.source.sendSystemMessage("chatexchange.const.onlyPlayer".toTranslatableComponent())
                        return@executes 0
                    }

                    if (!ChatExchangeConfig.imageMapArtEnabled.get()) {
                        player.sendSystemMessage("chatexchange.image.map_art.disabled".toTranslatableComponent())
                        return@executes 0
                    }

                    val id = IntegerArgumentType.getInteger(context, "id")
                    val entry = ImagePool.get(id)
                    if (entry == null) {
                        player.sendSystemMessage("chatexchange.image.expired".toTranslatableComponent())
                        return@executes 0
                    }

                    MapArt.giveTo(player, entry)
                    1
                }
            )
        )
        //? if < 26.1 {
        /*.then(
            Commands.literal("menu").then(
                Commands.argument("id", IntegerArgumentType.integer(0)).executes { context ->
                    val player = context.source.player ?: kotlin.run {
                        context.source.sendSystemMessage("chatexchange.const.onlyPlayer".toTranslatableComponent())
                        return@executes 0
                    }

                    val id = IntegerArgumentType.getInteger(context, "id")
                    val entry = ImagePool.get(id)
                    if (entry == null) {
                        player.sendSystemMessage("chatexchange.image.expired".toTranslatableComponent())
                        return@executes 0
                    }

                    ImageMenu.send(player, entry)
                    1
                }
            )
        )
        *///?}
    return builder
}
