package com.replaymod.playerview;

import net.minecraft.item.ItemStack;
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Binary format of the {@code replaymod:hud_state} payload.
 *
 * <pre>
 * byte    version (currently 1)
 * (item stacks use the registry-aware 1.21 item codec, so encoding and decoding need registries)
 * varint  flags (which sections follow, see FLAG_*)
 * ...     one block per set flag, in flag order
 * </pre>
 *
 * A payload with {@link #FLAG_FULL} carries every section and fully describes the state; other payloads only carry
 * what changed since the previous payload.
 */
public final class HudStateCodec {
    public static final int VERSION = 1;

    public static final int FLAG_FULL = 1;
    public static final int FLAG_ENTITY = 1 << 1;
    public static final int FLAG_GAME_MODE = 1 << 2;
    public static final int FLAG_SELECTED_SLOT = 1 << 3;
    public static final int FLAG_HEALTH = 1 << 4;
    public static final int FLAG_FOOD = 1 << 5;
    public static final int FLAG_XP = 1 << 6;
    public static final int FLAG_ARMOR = 1 << 7;
    public static final int FLAG_AIR = 1 << 8;
    public static final int FLAG_INVENTORY = 1 << 9;
    public static final int FLAG_EFFECTS = 1 << 10;

    private static final int ALL_SECTIONS = FLAG_ENTITY | FLAG_GAME_MODE | FLAG_SELECTED_SLOT | FLAG_HEALTH | FLAG_FOOD
            | FLAG_XP | FLAG_ARMOR | FLAG_AIR | FLAG_INVENTORY | FLAG_EFFECTS;

    private HudStateCodec() {}

    /** Returns the sections that differ between {@code prev} and {@code cur}; all sections if {@code prev} is null. */
    public static int diff(HudState prev, HudState cur) {
        if (prev == null) return FLAG_FULL | ALL_SECTIONS;
        int flags = 0;
        if (prev.entityId != cur.entityId) flags |= FLAG_ENTITY;
        if (prev.gameMode != cur.gameMode) flags |= FLAG_GAME_MODE;
        if (prev.selectedSlot != cur.selectedSlot) flags |= FLAG_SELECTED_SLOT;
        if (prev.health != cur.health || prev.maxHealth != cur.maxHealth || prev.absorption != cur.absorption) {
            flags |= FLAG_HEALTH;
        }
        if (prev.food != cur.food || prev.saturation != cur.saturation) flags |= FLAG_FOOD;
        if (prev.xpLevel != cur.xpLevel || prev.xpProgress != cur.xpProgress || prev.totalXp != cur.totalXp) {
            flags |= FLAG_XP;
        }
        if (prev.armor != cur.armor) flags |= FLAG_ARMOR;
        if (prev.air != cur.air || prev.maxAir != cur.maxAir) flags |= FLAG_AIR;
        for (int i = 0; i < HudState.SLOT_COUNT; i++) {
            if (!ItemStack.areEqual(prev.slots[i], cur.slots[i])) {
                flags |= FLAG_INVENTORY;
                break;
            }
        }
        if (!HudState.Effect.sameForHud(prev.effects, cur.effects)) flags |= FLAG_EFFECTS;
        return flags;
    }

    /**
     * Writes the sections named in {@code flags}. For {@link #FLAG_INVENTORY} without {@link #FLAG_FULL}, only slots
     * that differ from {@code prev} are written.
     */
    public static void write(RegistryByteBuf buf, HudState prev, HudState cur, int flags) {
        if ((flags & FLAG_FULL) != 0) flags |= ALL_SECTIONS;
        buf.writeByte(VERSION);
        buf.writeVarInt(flags);
        if ((flags & FLAG_ENTITY) != 0) buf.writeInt(cur.entityId);
        if ((flags & FLAG_GAME_MODE) != 0) buf.writeVarInt(cur.gameMode);
        if ((flags & FLAG_SELECTED_SLOT) != 0) buf.writeVarInt(cur.selectedSlot);
        if ((flags & FLAG_HEALTH) != 0) {
            buf.writeFloat(cur.health);
            buf.writeFloat(cur.maxHealth);
            buf.writeFloat(cur.absorption);
        }
        if ((flags & FLAG_FOOD) != 0) {
            buf.writeVarInt(cur.food);
            buf.writeFloat(cur.saturation);
        }
        if ((flags & FLAG_XP) != 0) {
            buf.writeVarInt(cur.xpLevel);
            buf.writeFloat(cur.xpProgress);
            buf.writeVarInt(cur.totalXp);
        }
        if ((flags & FLAG_ARMOR) != 0) buf.writeVarInt(cur.armor);
        if ((flags & FLAG_AIR) != 0) {
            buf.writeVarInt(cur.air);
            buf.writeVarInt(cur.maxAir);
        }
        if ((flags & FLAG_INVENTORY) != 0) {
            boolean full = (flags & FLAG_FULL) != 0 || prev == null;
            List<Integer> changed = new ArrayList<>();
            for (int i = 0; i < HudState.SLOT_COUNT; i++) {
                if (full || !ItemStack.areEqual(prev.slots[i], cur.slots[i])) changed.add(i);
            }
            buf.writeVarInt(changed.size());
            for (int i : changed) {
                buf.writeVarInt(i);
                ItemStack.OPTIONAL_PACKET_CODEC.encode(buf, cur.slots[i]);
            }
        }
        if ((flags & FLAG_EFFECTS) != 0) {
            buf.writeVarInt(cur.effects.size());
            for (HudState.Effect e : cur.effects) {
                buf.writeIdentifier(e.id);
                buf.writeVarInt(e.amplifier);
                buf.writeVarInt(e.duration);
                buf.writeByte((e.ambient ? 1 : 0) | (e.showIcon ? 2 : 0) | (e.infinite ? 4 : 0));
            }
        }
    }

    /** Whether an encoded payload is a full snapshot, without decoding it. */
    public static boolean isFull(byte[] data) {
        if (data.length < 2 || data[0] != VERSION) return false;
        PacketByteBuf buf = new PacketByteBuf(Unpooled.wrappedBuffer(data));
        buf.readByte();
        return (buf.readVarInt() & FLAG_FULL) != 0;
    }

    /**
     * Applies one payload to {@code state}.
     *
     * @return false if the payload uses a newer format version and was skipped
     */
    public static boolean read(RegistryByteBuf buf, HudState state) {
        int version = buf.readByte();
        if (version != VERSION) return false;
        int flags = buf.readVarInt();
        if ((flags & FLAG_ENTITY) != 0) state.entityId = buf.readInt();
        if ((flags & FLAG_GAME_MODE) != 0) state.gameMode = buf.readVarInt();
        if ((flags & FLAG_SELECTED_SLOT) != 0) state.selectedSlot = buf.readVarInt();
        if ((flags & FLAG_HEALTH) != 0) {
            state.health = buf.readFloat();
            state.maxHealth = buf.readFloat();
            state.absorption = buf.readFloat();
        }
        if ((flags & FLAG_FOOD) != 0) {
            state.food = buf.readVarInt();
            state.saturation = buf.readFloat();
        }
        if ((flags & FLAG_XP) != 0) {
            state.xpLevel = buf.readVarInt();
            state.xpProgress = buf.readFloat();
            state.totalXp = buf.readVarInt();
        }
        if ((flags & FLAG_ARMOR) != 0) state.armor = buf.readVarInt();
        if ((flags & FLAG_AIR) != 0) {
            state.air = buf.readVarInt();
            state.maxAir = buf.readVarInt();
        }
        if ((flags & FLAG_INVENTORY) != 0) {
            int n = buf.readVarInt();
            for (int k = 0; k < n; k++) {
                int slot = buf.readVarInt();
                ItemStack stack = ItemStack.OPTIONAL_PACKET_CODEC.decode(buf);
                if (slot >= 0 && slot < HudState.SLOT_COUNT) state.slots[slot] = stack;
            }
        }
        if ((flags & FLAG_EFFECTS) != 0) {
            int n = buf.readVarInt();
            List<HudState.Effect> effects = new ArrayList<>(n);
            for (int k = 0; k < n; k++) {
                Identifier id = buf.readIdentifier();
                int amplifier = buf.readVarInt();
                int duration = buf.readVarInt();
                int bits = buf.readByte();
                effects.add(new HudState.Effect(id, amplifier, duration,
                        (bits & 1) != 0, (bits & 2) != 0, (bits & 4) != 0));
            }
            state.effects = effects;
        }
        state.recordedOnClient = true;
        return true;
    }
}
