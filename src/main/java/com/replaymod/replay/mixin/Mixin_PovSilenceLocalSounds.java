package com.replaymod.replay.mixin;

import com.replaymod.playerview.PovMode;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.sound.SoundEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * In POV mode the replayed player runs its normal tick, which would play its own eating, drinking and hurt sounds
 * locally. The recorder already saved those sounds, so playing them again would double them.
 */
@Mixin(ClientPlayerEntity.class)
public abstract class Mixin_PovSilenceLocalSounds {
    @Inject(method = "playSound(Lnet/minecraft/sound/SoundEvent;FF)V", at = @At("HEAD"), cancellable = true)
    private void replayModPov_silenceLocalSound(SoundEvent sound, float volume, float pitch, CallbackInfo ci) {
        if (PovMode.isActive()) {
            ci.cancel();
        }
    }
}
