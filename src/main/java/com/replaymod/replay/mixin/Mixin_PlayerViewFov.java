package com.replaymod.replay.mixin;

import com.replaymod.playerview.CameraSample;
import com.replaymod.playerview.PlayerViewHud;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While spectating the recording player in first person, use the field of view they saw: their FOV setting plus
 * sprinting, speed effects, bow drawing, the spyglass and being under water. The hand keeps its own fixed FOV.
 */
@Mixin(GameRenderer.class)
public abstract class Mixin_PlayerViewFov {
    @Inject(method = "getFov", at = @At("HEAD"), cancellable = true)
    private void replayModPlayerView_useRecordedFov(Camera camera, float tickDelta, boolean changingFov,
                                                   CallbackInfoReturnable<Double> ci) {
        if (!changingFov || camera.getFocusedEntity() != PlayerViewHud.viewedPlayer()) return;
        CameraSample sample = PlayerViewHud.cameraSample();
        if (sample != null) {
            ci.setReturnValue((double) sample.fov);
        }
    }
}
