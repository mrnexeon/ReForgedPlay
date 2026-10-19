package com.replaymod.replay.mixin;

import com.replaymod.playerview.CameraSample;
import com.replaymod.playerview.PlayerViewHud;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While spectating the recording player in first person, place the view exactly where their camera was, instead of
 * at their smoothed, tick-based position with rotation rounded to 1.4 degree steps.
 */
@Mixin(Camera.class)
public abstract class Mixin_PlayerViewCamera {
    @Shadow
    protected abstract void setPos(double x, double y, double z);

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Inject(method = "update", at = @At("RETURN"))
    private void replayModPlayerView_useRecordedCamera(BlockView area, Entity focusedEntity, boolean thirdPerson,
                                                      boolean inverseView, float tickDelta, CallbackInfo ci) {
        if (thirdPerson || focusedEntity == null || focusedEntity != PlayerViewHud.viewedPlayer()) return;
        CameraSample sample = PlayerViewHud.cameraSample();
        if (sample == null) return;
        setRotation(sample.yaw, sample.pitch);
        setPos(sample.x, sample.y, sample.z);
    }
}
