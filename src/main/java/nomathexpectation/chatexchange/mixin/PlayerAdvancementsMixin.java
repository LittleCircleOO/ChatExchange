package nomathexpectation.chatexchange.mixin;

import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import nomathexpectation.chatexchange.ChatExchangeConfig;
import nomathexpectation.chatexchange.ExchangeServer;
import nomathexpectation.chatexchange.PlayerAdvancementEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsMixin {

    @Shadow
    private ServerPlayer player;

    @Inject(method = "award", at = @At("RETURN"))
    private void chatExchange$onAdvancementAward(AdvancementHolder holder, String criterion, CallbackInfoReturnable<Boolean> cir) {
        // award() returns true when a criterion is newly granted (progress.grantProgress succeeded).
        // Combined with the advancement now being complete (isDone()), that means it was just earned:
        // an already-complete advancement cannot grant a brand-new criterion, so result==true && done == newly completed.
        // This avoids hooking the announce call, which lives inside a lambda and is unreachable from award() bytecode.
        if (!cir.getReturnValueZ()) {
            return;
        }
        if (!((PlayerAdvancements) (Object) this).getOrStartProgress(holder).isDone()) {
            return;
        }
        if (!ChatExchangeConfig.INSTANCE.getAdvancement().get()) {
            return;
        }
        Advancement advancement = holder.value();
        Optional<DisplayInfo> displayOpt = advancement.display();
        if (displayOpt.isEmpty() || !displayOpt.get().shouldAnnounceChat()) {
            return;
        }
        var name = ExchangeServer.Companion.componentToString(player.getName());
        if (ChatExchangeConfig.INSTANCE.checkIgnoreBot(name)) {
            return;
        }
        Component title = displayOpt.get().getTitle();
        var advancementName = ExchangeServer.Companion.componentToString(title);
        ExchangeServer.Companion.sendEvent(new PlayerAdvancementEvent(name, advancementName));
    }
}
