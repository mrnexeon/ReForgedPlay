package com.replaymod.recording.mixin;

import com.replaymod.recording.handler.RecordingEventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Records sounds the local player makes itself: eating, drinking, getting hurt, dying, footsteps, swimming.
 *
 * {@code ClientPlayerEntity.playSound} plays these directly with {@code playLocalSound}, bypassing the
 * {@code ClientWorld.playSound} hook in {@link MixinWorldClient}, and the server sends them to everyone except the
 * player who made them, so without this they are missing from replays.
 */
@Mixin(ClientPlayerEntity.class)
public abstract class Mixin_RecordLocalPlayerSounds {
    @Inject(method = "playSound(Lnet/minecraft/sound/SoundEvent;FF)V", at = @At("HEAD"))
    private void replayModRecording_recordOwnSound(SoundEvent sound, float volume, float pitch, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity self = (ClientPlayerEntity) (Object) this;
        if (self != mc.player || mc.worldRenderer == null) return;
        RecordingEventHandler handler =
                ((RecordingEventHandler.RecordingEventSender) mc.worldRenderer).getRecordingEventHandler();
        if (handler == null) return;
        handler.onPacket(new PlaySoundS2CPacket(
                Registries.SOUND_EVENT.getEntry(sound),
                self.getSoundCategory(),
                self.getX(), self.getY(), self.getZ(),
                volume, pitch,
                ThreadLocalRandom.current().nextLong()
        ));
    }
}
