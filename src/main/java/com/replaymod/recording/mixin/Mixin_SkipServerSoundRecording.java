package com.replaymod.recording.mixin;

import com.replaymod.recording.handler.ServerSoundGuard;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The client plays server sound packets via {@code ClientWorld.playSound} with the local player as source, which
 * {@link MixinWorldClient} would otherwise record a second time, so every server sound ended up twice in replays.
 */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class Mixin_SkipServerSoundRecording {
    @Inject(method = "onPlaySound", at = @At("HEAD"))
    private void replayModRecording_enterServerSound(PlaySoundS2CPacket packet, CallbackInfo ci) {
        // The first call happens on the network thread and is rescheduled to the main thread by throwing,
        // so only mark the main-thread call, which also reaches RETURN.
        if (MinecraftClient.getInstance().isOnThread()) {
            ServerSoundGuard.enter();
        }
    }

    @Inject(method = "onPlaySound", at = @At("RETURN"))
    private void replayModRecording_exitServerSound(PlaySoundS2CPacket packet, CallbackInfo ci) {
        ServerSoundGuard.exit();
    }
}
