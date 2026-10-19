package com.replaymod.playerview;

import com.replaymod.replay.mixin.GameRendererFovInvoker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.Camera;
import net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.function.Consumer;

/**
 * Records exactly what the local player's camera showed, once per rendered frame (at most about 60 times a second).
 *
 * Regular replays only know the player's position and rotation per tick, with rotation rounded to 1.4 degree steps,
 * and smooth them like any other player. This track lets first-person playback match the original frame for frame,
 * including field of view, view bobbing, nausea and portal swirl, freezing and the attack cooldown.
 */
public class CameraRecorder {
    private static final long MIN_INTERVAL_MS = 16;

    private CameraSample last;
    private long lastTime;

    public void frame(MinecraftClient mc, Consumer<CustomPayloadS2CPacket> save) {
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.gameRenderer == null) return;

        long now = System.currentTimeMillis();
        if (last != null && now - lastTime < MIN_INTERVAL_MS) return;

        float tickDelta = mc.getRenderTickCounter().getTickDelta(true);
        Camera camera = mc.gameRenderer.getCamera();
        Vec3d pos = camera.getPos();
        float fov = (float) ((GameRendererFovInvoker) mc.gameRenderer).replayMod$invokeGetFov(camera, tickDelta, true);

        // The same inputs GameRenderer.bobView derives from the player
        float speedDelta = player.horizontalSpeed - player.prevHorizontalSpeed;
        float bobPhase = -(player.horizontalSpeed + speedDelta * tickDelta);
        float bobAmount = MathHelper.lerp(tickDelta, player.prevStrideDistance, player.strideDistance);
        float nausea = MathHelper.lerp(tickDelta, player.prevNauseaIntensity, player.nauseaIntensity);

        boolean firstPerson = mc.options.getPerspective().isFirstPerson() && mc.getCameraEntity() == player;
        CameraSample sample = new CameraSample(
                pos.x, pos.y, pos.z, camera.getYaw(), camera.getPitch(), fov,
                bobPhase, bobAmount, nausea,
                player.getAttackCooldownProgress(0), player.getFrozenTicks(),
                firstPerson ? CameraSample.FLAG_FIRST_PERSON : 0);

        if (sample.sameAs(last)) return;
        save.accept(new CustomPayloadS2CPacket(new PlayerViewPayloads.Camera(sample.encode())));
        last = sample;
        lastTime = now;
    }
}
