package com.replaymod.replay.mixin;

import com.replaymod.playerview.HudState;
import com.replaymod.playerview.PlayerViewHud;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.registry.entry.RegistryEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;

/**
 * While the recording player is spectated in first person, report their recorded armor and effects for the spectated
 * player and the replay camera, which is where the HUD, lighting (night vision, darkness) and fog read them from.
 */
@Mixin(LivingEntity.class)
public abstract class Mixin_PlayerViewHud_Living {
    @Inject(method = "getArmor", at = @At("HEAD"), cancellable = true)
    private void replayModPlayerView_getArmor(CallbackInfoReturnable<Integer> ci) {
        if (PlayerViewHud.isSubject(this)) {
            HudState state = PlayerViewHud.frameState();
            if (state != null) ci.setReturnValue(state.armor);
        }
    }

    @Inject(method = "getStatusEffects", at = @At("HEAD"), cancellable = true)
    private void replayModPlayerView_getStatusEffects(CallbackInfoReturnable<Collection<StatusEffectInstance>> ci) {
        if (PlayerViewHud.isSubject(this)) {
            ci.setReturnValue(PlayerViewHud.activeEffects());
        }
    }

    @Inject(method = "hasStatusEffect", at = @At("HEAD"), cancellable = true)
    private void replayModPlayerView_hasStatusEffect(RegistryEntry<StatusEffect> effect, CallbackInfoReturnable<Boolean> ci) {
        if (PlayerViewHud.isSubject(this)) {
            ci.setReturnValue(PlayerViewHud.activeEffect(effect) != null);
        }
    }

    @Inject(method = "getStatusEffect", at = @At("HEAD"), cancellable = true)
    private void replayModPlayerView_getStatusEffect(RegistryEntry<StatusEffect> effect, CallbackInfoReturnable<StatusEffectInstance> ci) {
        if (PlayerViewHud.isSubject(this)) {
            ci.setReturnValue(PlayerViewHud.activeEffect(effect));
        }
    }
}
