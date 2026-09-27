package nomathexpectation.chatexchange.mixin;

import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
//? if >= 1.20.2 {
import net.minecraft.server.network.CommonListenerCookie;
//?}
import net.minecraft.server.players.PlayerList;
import nomathexpectation.chatexchange.ChatExchangeConfig;
import nomathexpectation.chatexchange.ExchangeServer;
import nomathexpectation.chatexchange.PlayerJoinEvent;
import nomathexpectation.chatexchange.PlayerLeaveEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    @Inject(
            method = "placeNewPlayer",
            at = @At("RETURN")
    )
    private void chatExchange$onPlaceNewPlayer(Connection connection, ServerPlayer player
            //? if >= 1.20.2 {
            , CommonListenerCookie cookie
            //?}
            , CallbackInfo ci) {
        if (!ChatExchangeConfig.INSTANCE.getJoinLeave().get()) {
            return;
        }
        var name = ExchangeServer.Companion.componentToString(player.getName());
        if (ChatExchangeConfig.INSTANCE.checkIgnoreBot(name)) {
            return;
        }
        ExchangeServer.Companion.sendEvent(new PlayerJoinEvent(name));
    }

    @Inject(
            method = "remove(Lnet/minecraft/server/level/ServerPlayer;)V",
            at = @At("HEAD")
    )
    private void chatExchange$onRemove(ServerPlayer player, CallbackInfo ci) {
        if (!ChatExchangeConfig.INSTANCE.getJoinLeave().get()) {
            return;
        }
        var name = ExchangeServer.Companion.componentToString(player.getName());
        if (ChatExchangeConfig.INSTANCE.checkIgnoreBot(name)) {
            return;
        }
        ExchangeServer.Companion.sendEvent(new PlayerLeaveEvent(name));
    }
}
