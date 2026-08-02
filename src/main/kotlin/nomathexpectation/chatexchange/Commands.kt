package nomathexpectation.chatexchange

import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import net.minecraft.commands.CommandBuildContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.ComponentArgument
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentUtils
import net.minecraft.network.chat.ResolutionContext
import net.minecraft.network.chat.contents.PlainTextContents
import net.minecraft.server.permissions.LevelBasedPermissionSet
import net.minecraft.world.entity.Entity
import org.apache.logging.log4j.LogManager
import kotlin.jvm.optionals.getOrNull

private val logger = LogManager.getLogger(ChatExchange.MOD_ID)

internal var registries: CommandBuildContext? = null
    private set

fun String.parseJsonToComponent(commandSourceStack: CommandSourceStack? = null, entity: Entity? = null): Component {
    val ctx = registries
    val raw = if (ctx != null) {
        runCatching {
            ComponentArgument.textComponent(ctx).parse(StringReader(this))
        }.getOrElse { Component.translatableEscape("argument.component.invalid", this) }
    } else {
        Component.translatableEscape("argument.component.invalid", this)
    }
    return ComponentUtils.resolve(
        ResolutionContext.builder().apply {
            commandSourceStack?.let { withSource(it.withPermission(LevelBasedPermissionSet.GAMEMASTER)) }
            entity?.let { withEntityOverride(it) }
        }.build(),
        raw,
    )
}

fun registerCommands(dispatcher: com.mojang.brigadier.CommandDispatcher<CommandSourceStack>, buildContext: CommandBuildContext, environment: Commands.CommandSelection) {
    registries = buildContext

    if (environment != Commands.CommandSelection.DEDICATED) {
        return
    }

    val commandBuilder = Commands.literal("chatexchange")
        .then(
            Commands.literal("send").then(
                Commands.argument("message", StringArgumentType.greedyString()).executes { context ->
                    val message = StringArgumentType.getString(context, "message")

                    val name = ExchangeServer.componentToString(context.source.displayName)

                    val component = kotlin.runCatching {
                        ChatExchangeConfig.commandBroadcastFormat.get()
                            .parseJsonToComponent(context.source)
                    }.getOrElse {
                        logger.error(
                            "Unable to resolve component from command broadcast format. Using default.",
                            it
                        )
                        context.source.sendSystemMessage("chatexchange.const.exception".toExchangeServerTranslatedLiteral())
                        ChatExchangeConfig.commandBroadcastFormat.default
                            .parseJsonToComponent(context.source)
                    }.copy().append(message)

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
                    "chatexchange.command.chatexchange.status".toExchangeServerTranslatedLiteral(
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
                        context.source.sendSystemMessage("chatexchange.const.onlyPlayer".toExchangeServerTranslatedLiteral())
                        return@executes 0
                    }

                    val data = player.level().server.chatExchangeData
                    val toggle = BoolArgumentType.getBool(context, "toggle")
                    if (toggle) {
                        data.removeIgnoredPlayer(player.uuid)
                        player.sendSystemMessage("chatexchange.command.chatexchange.broadcastme.on".toExchangeServerTranslatedLiteral())
                    } else {
                        data.addIgnoredPlayer(player.uuid)
                        player.sendSystemMessage("chatexchange.command.chatexchange.broadcastme.off".toExchangeServerTranslatedLiteral())
                    }

                    1
                }
            ).executes { context ->
                val player = context.source.player ?: kotlin.run {
                    context.source.sendSystemMessage("chatexchange.const.onlyPlayer".toExchangeServerTranslatedLiteral())
                    return@executes 0
                }

                val data = player.level().server.chatExchangeData
                if (data.isIgnoredPlayer(player.uuid)) {
                    player.sendSystemMessage("chatexchange.command.chatexchange.broadcastme.isoff".toExchangeServerTranslatedLiteral())
                } else {
                    player.sendSystemMessage("chatexchange.command.chatexchange.broadcastme.ison".toExchangeServerTranslatedLiteral())
                }

                1
            }
        ).executes { context ->
            context.source.sendSystemMessage(
                "chatexchange.command.chatexchange.description".toExchangeServerTranslatedLiteral(
                    ChatExchangeConfig.broadcastTriggerPrefix.get().joinToString("/")
                )
            )
            1
        }
    val command = dispatcher.register(commandBuilder)

    val shortcutBuilder = Commands.literal("ce").redirect(command)
    dispatcher.register(shortcutBuilder)
}
