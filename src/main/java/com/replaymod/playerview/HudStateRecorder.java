package com.replaymod.playerview;

import io.netty.buffer.Unpooled;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Captures the local player's HUD state once per tick while recording and turns changes into
 * {@code replaymod:hud_state} packets.
 */
public class HudStateRecorder {
    /** A full snapshot is written this often (in ticks) so seeking never needs to replay far. */
    private static final int FULL_SNAPSHOT_INTERVAL = 200;

    private HudState last;
    private int ticksSinceFull;

    /**
     * Captures the current state and returns a packet describing what changed, or {@code null} if nothing did.
     */
    public CustomPayloadS2CPacket tick(ClientPlayerEntity player, ClientPlayerInteractionManager interactionManager) {
        HudState cur = capture(player, interactionManager);

        int flags;
        ticksSinceFull++;
        if (last == null || ticksSinceFull >= FULL_SNAPSHOT_INTERVAL || last.entityId != cur.entityId) {
            flags = HudStateCodec.FLAG_FULL;
            ticksSinceFull = 0;
        } else {
            flags = HudStateCodec.diff(last, cur);
            if (flags == 0) return null;
        }

        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        try {
            HudStateCodec.write(buf, last, cur, flags);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            last = cur;
            return new CustomPayloadS2CPacket(new HudStatePayload(bytes));
        } finally {
            buf.release();
        }
    }

    private HudState capture(ClientPlayerEntity player, ClientPlayerInteractionManager interactionManager) {
        HudState s = new HudState();
        s.entityId = player.getId();
        s.gameMode = interactionManager != null ? interactionManager.getCurrentGameMode().getId() : 0;

        PlayerInventory inventory = player.getInventory();
        s.selectedSlot = inventory.selectedSlot;
        for (int i = 0; i < HudState.SLOT_COUNT; i++) {
            ItemStack live = inventory.getStack(i);
            // Reuse the previous copy when unchanged, so we only copy stacks that actually changed.
            if (last != null && ItemStack.areEqual(last.slots[i], live)) {
                s.slots[i] = last.slots[i];
            } else {
                s.slots[i] = live.copy();
            }
        }

        s.health = player.getHealth();
        s.maxHealth = player.getMaxHealth();
        s.absorption = player.getAbsorptionAmount();
        s.food = player.getHungerManager().getFoodLevel();
        s.saturation = player.getHungerManager().getSaturationLevel();
        s.xpLevel = player.experienceLevel;
        s.xpProgress = player.experienceProgress;
        s.totalXp = player.totalExperience;
        s.armor = player.getArmor();
        s.air = player.getAir();
        s.maxAir = player.getMaxAir();

        List<HudState.Effect> effects = new ArrayList<>();
        for (StatusEffectInstance e : player.getStatusEffects()) {
            Identifier id = Registries.STATUS_EFFECT.getId(e.getEffectType());
            if (id == null) continue;
            effects.add(new HudState.Effect(id, e.getAmplifier(), e.getDuration(),
                    e.isAmbient(), e.shouldShowIcon(), e.isInfinite()));
        }
        effects.sort(Comparator.comparing(e -> e.id.toString()));
        s.effects = effects;

        s.recordedOnClient = true;
        return s;
    }
}
