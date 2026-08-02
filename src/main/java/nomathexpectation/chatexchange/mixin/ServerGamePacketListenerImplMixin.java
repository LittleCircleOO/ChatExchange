package nomathexpectation.chatexchange.mixin;

import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import nomathexpectation.chatexchange.ChatExchangeConfig;
import nomathexpectation.chatexchange.ChatExchangeDataKt;
import nomathexpectation.chatexchange.ExchangeServer;
import nomathexpectation.chatexchange.MessageEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
    @Shadow
    public ServerPlayer player;

    @Inject(
            method = "handleChat(Lnet/minecraft/network/protocol/game/ServerboundChatPacket;)V",
            at = @At("HEAD")
            // Not cancellable: vanilla chat validation, the signed-message (last-seen) state machine,
            // and in-game display must all run normally. We only forward externally as a non-blocking
            // side-effect. Cancelling handleChat previously desynced the last-seen-messages validator.
    )
    private void chatExchange$onHandleChat(ServerboundChatPacket packet, CallbackInfo ci) {
        if (!ChatExchangeConfig.INSTANCE.getChat().get()) {
            return;
        }
        var data = ChatExchangeDataKt.getChatExchangeData(player.level().getServer());
        if (data.isIgnoredPlayer(player.getUUID())) {
            return;
        }

        var playerName = ExchangeServer.Companion.componentToString(player.getName());
        ExchangeServer.Companion.sendEvent(new MessageEvent(playerName, packet.message()));
        // No ci.cancel(), no formatting, no broadcastSystemMessage: vanilla handles the rest.
    }
}
