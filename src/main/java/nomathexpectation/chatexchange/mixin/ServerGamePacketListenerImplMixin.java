package nomathexpectation.chatexchange.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import nomathexpectation.chatexchange.ChatExchangeConfig;
import nomathexpectation.chatexchange.ChatExchangeConfigKt;
import nomathexpectation.chatexchange.ChatExchangeDataKt;
import nomathexpectation.chatexchange.CommandsKt;
import nomathexpectation.chatexchange.ExchangeServer;
import nomathexpectation.chatexchange.MessageEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
    @Shadow
    public ServerPlayer player;

    @Unique
    private static final Logger chatExchange$LOGGER = LogManager.getLogger();

    @Inject(
            method = "handleChat(Lnet/minecraft/network/protocol/game/ServerboundChatPacket;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void chatExchange$onHandleChat(ServerboundChatPacket packet, CallbackInfo ci) {
        // On Fabric there is no ServerChatEvent, so the Mixin is the only chat path.
        // mixinMode is therefore ignored here (kept in the config schema only for file-compatibility).

        var data = ChatExchangeDataKt.getChatExchangeData(player.level().getServer());
        var string = packet.message();
        if ((!ChatExchangeConfig.INSTANCE.getChat().get() || data.isIgnoredPlayer(player.getUUID())) && !ChatExchangeConfigKt.startsWithBroadcastPrefix(string)) {
            return;
        }

        ci.cancel();

        var newString = ChatExchangeConfigKt.removeBroadcastPrefix(string);
        var playerName = ExchangeServer.Companion.componentToString(player.getName());
        ExchangeServer.Companion.sendEvent(new MessageEvent(playerName, newString));

        var format = ChatExchangeConfig.INSTANCE.getCommandBroadcastFormat();
        Component component;
        try {
            component = CommandsKt.parseJsonToComponent(format.get(), player.createCommandSourceStack(), null);
        } catch (Exception e) {
            chatExchange$LOGGER.warn("Failed to format message from command broadcast format. Using default.", e);
            component = CommandsKt.parseJsonToComponent(format.getDefault(), player.createCommandSourceStack(), null);
        }
        component = component.copy().append(newString);
        player.level().getServer().getPlayerList().broadcastSystemMessage(component, false);
    }
}
