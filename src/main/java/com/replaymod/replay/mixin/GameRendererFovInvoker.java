package com.replaymod.replay.mixin;

import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Reads the world field of view the player saw this frame, including sprint, potions, bows, spyglass and water. */
@Mixin(GameRenderer.class)
public interface GameRendererFovInvoker {
    @Invoker("getFov")
    double replayMod$invokeGetFov(Camera camera, float tickDelta, boolean changingFov);
}
