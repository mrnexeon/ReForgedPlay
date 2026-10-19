package com.replaymod.replay.mixin;

import com.replaymod.playerview.MenuPlayer;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * The inventory screen draws {@code client.player}, which in a replay is the camera. While a recorded inventory is
 * replayed, draw the recording player instead.
 */
@Mixin(InventoryScreen.class)
public abstract class Mixin_MenuPlayerInventoryEntity {
    @ModifyArg(
            method = "drawBackground",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screen/ingame/InventoryScreen;drawEntity(Lnet/minecraft/client/gui/DrawContext;IIIIIFFFLnet/minecraft/entity/LivingEntity;)V"),
            index = 9,
            require = 0 // cosmetic: if this ever stops matching, the menu still works
    )
    private LivingEntity replayModPlayerView_drawRecordingPlayer(LivingEntity entity) {
        LivingEntity override = MenuPlayer.drawingFor();
        return override != null ? override : entity;
    }
}
