package nomathexpectation.chatexchange.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import nomathexpectation.chatexchange.ChatExchangeConfig;
import nomathexpectation.chatexchange.ExchangeServer;
import nomathexpectation.chatexchange.PlayerDieEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {

    @Inject(
            method = "die(Lnet/minecraft/world/damagesource/DamageSource;)V",
            at = @At("HEAD")
    )
    private void chatExchange$onDie(DamageSource source, CallbackInfo ci) {
        // ServerPlayer.die fully overrides die() without calling super.die(), so hooking
        // LivingEntity.die would never fire for server players. Target ServerPlayer.die directly.
        if (!ChatExchangeConfig.INSTANCE.getDeath().get()) {
            return;
        }
        ServerPlayer self = (ServerPlayer) (Object) this;
        var name = ExchangeServer.Companion.componentToString(self.getName());
        if (ChatExchangeConfig.INSTANCE.checkIgnoreBot(name)) {
            return;
        }
        var text = ExchangeServer.Companion.componentToString(source.getLocalizedDeathMessage(self));
        ExchangeServer.Companion.sendEvent(new PlayerDieEvent(name, text));
    }
}
