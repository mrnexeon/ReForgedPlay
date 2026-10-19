package com.replaymod.replay.mixin;

import com.replaymod.playerview.PovMode;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * In POV mode the local player is the recording player, so the viewer's gameplay keys (attack, use, drop, inventory,
 * hotbar, swap hands, ...) must not act on it. Replay Mod's own keys are handled separately and keep working.
 */
@Mixin(MinecraftClient.class)
public abstract class Mixin_PovBlockGameplayInput {
    @Inject(method = "handleInputEvents", at = @At("HEAD"), cancellable = true)
    private void replayModPov_blockGameplayInput(CallbackInfo ci) {
        if (PovMode.isActive()) {
            ci.cancel();
        }
    }
}
