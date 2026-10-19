package com.replaymod.replay.mixin;

import com.replaymod.playerview.PlayerViewHud;
import net.minecraft.entity.player.HungerManager;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** While the recording player's HUD is drawn, report their recorded food and saturation. */
@Mixin(PlayerEntity.class)
public abstract class Mixin_PlayerViewHud_Player {
    @Inject(method = "getHungerManager", at = @At("HEAD"), cancellable = true)
    private void replayModPlayerView_getHungerManager(CallbackInfoReturnable<HungerManager> ci) {
        if (PlayerViewHud.isSubject(this)) {
            ci.setReturnValue(PlayerViewHud.activeHungerManager());
        }
    }
}
