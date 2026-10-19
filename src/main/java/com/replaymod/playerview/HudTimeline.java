package com.replaymod.playerview;

import com.replaymod.replaystudio.PacketData;
import com.replaymod.replaystudio.io.ReplayInputStream;
import com.replaymod.replaystudio.lib.viaversion.api.protocol.packet.State;
import com.replaymod.replaystudio.protocol.Packet;
import com.replaymod.replaystudio.protocol.PacketType;
import com.replaymod.replaystudio.replay.ReplayFile;
import io.netty.buffer.Unpooled;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static com.replaymod.core.versions.MCVer.getPacketTypeRegistry;

/**
 * The recording player's HUD state over the whole replay, indexed by replay time.
 *
 * Built once per replay by scanning the packet stream, independent of how the replay is played back, so it works in
 * quick mode (which only rebuilds world state) and makes seeking free.
 *
 * New replays carry {@code replaymod:hud_state} payloads written on the recording client. For older replays the
 * timeline falls back to what the server sent the player (health, food, XP, inventory sync), which is less complete.
 */
public final class HudTimeline {
    private static final Logger LOGGER = LogManager.getLogger();

    private final int[] times;
    private final HudState[] states;
    private final boolean fromClientRecording;

    private HudTimeline(List<Integer> times, List<HudState> states, boolean fromClientRecording) {
        this.times = times.stream().mapToInt(Integer::intValue).toArray();
        this.states = states.toArray(new HudState[0]);
        this.fromClientRecording = fromClientRecording;
    }

    /** The state at the given replay time, or {@code null} if the replay has no HUD data that early. */
    public HudState at(int time) {
        int lo = 0, hi = times.length - 1, found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (times[mid] <= time) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found < 0 ? null : states[found];
    }

    public boolean isFromClientRecording() {
        return fromClientRecording;
    }

    public int size() {
        return times.length;
    }

    /**
     * Scans the replay. Blocking; call from a background thread.
     *
     * @param cancelled checked between packets so a closed replay stops the scan early
     */
    public static HudTimeline build(ReplayFile replayFile, BooleanSupplier cancelled) throws IOException {
        Track client = new Track();
        Track legacy = new Track();
        HudState clientState = new HudState();
        HudState legacyState = new HudState();

        try (ReplayInputStream in = replayFile.getPacketData(getPacketTypeRegistry(State.LOGIN))) {
            PacketData data;
            while ((data = in.readPacket()) != null) {
                try {
                    if (cancelled.getAsBoolean()) return null;
                    Packet packet = data.getPacket();
                    int time = (int) data.getTime();
                    PacketType type = packet.getType();
                    if (type == null) continue;

                    if (type == PacketType.PluginMessage) {
                        PacketByteBuf buf = wrap(packet);
                        Identifier channel = buf.readIdentifier();
                        if (HudStatePayload.ID.equals(channel) && HudStateCodec.read(buf, clientState)) {
                            client.add(time, clientState.copy());
                        }
                    } else if (client.isEmpty() && applyLegacy(type, wrap(packet), legacyState)) {
                        // Once client-recorded data shows up the server packets are no longer needed.
                        legacy.add(time, legacyState.copy());
                    }
                } catch (Exception e) {
                    // A packet we fail to parse should not cost us the rest of the timeline.
                    LOGGER.debug("Skipping unreadable packet while indexing HUD state", e);
                } finally {
                    data.release();
                }
            }
        }

        boolean useClient = !client.isEmpty();
        Track chosen = useClient ? client : legacy;
        LOGGER.info("Indexed {} HUD states ({})", chosen.times.size(),
                useClient ? "recorded on client" : "from server packets");
        return new HudTimeline(chosen.times, chosen.states, useClient);
    }

    private static PacketByteBuf wrap(Packet packet) {
        return new PacketByteBuf(Unpooled.wrappedBuffer(packet.getBuf().nioBuffer()));
    }

    /** Applies a server-sent packet that describes the recording player. Returns whether anything changed. */
    private static boolean applyLegacy(PacketType type, PacketByteBuf buf, HudState s) {
        if (type == PacketType.JoinGame) {
            s.entityId = buf.readInt();
            buf.readBoolean(); // hardcore
            int dimensions = buf.readVarInt();
            for (int i = 0; i < dimensions; i++) buf.readIdentifier();
            buf.readVarInt(); // max players
            buf.readVarInt(); // view distance
            buf.readVarInt(); // simulation distance
            buf.readBoolean(); // reduced debug info
            buf.readBoolean(); // show death screen
            buf.readBoolean(); // limited crafting
            readSpawnInfoGameMode(buf, s);
            return true;
        } else if (type == PacketType.Respawn) {
            readSpawnInfoGameMode(buf, s);
            return true;
        } else if (type == PacketType.NotifyClient) {
            int reason = buf.readUnsignedByte();
            float value = buf.readFloat();
            if (reason == 3) { // game mode changed
                s.gameMode = (int) value;
                return true;
            }
            return false;
        } else if (type == PacketType.UpdateHealth) {
            s.health = buf.readFloat();
            s.food = buf.readVarInt();
            s.saturation = buf.readFloat();
            return true;
        } else if (type == PacketType.SetExperience) {
            s.xpProgress = buf.readFloat();
            s.xpLevel = buf.readVarInt();
            s.totalXp = buf.readVarInt();
            return true;
        } else if (type == PacketType.ChangeHeldItem) {
            s.selectedSlot = buf.readByte();
            return true;
        } else if (type == PacketType.WindowItems) {
            int window = buf.readUnsignedByte();
            if (window != 0) return false;
            buf.readVarInt(); // revision
            int count = buf.readVarInt();
            for (int i = 0; i < count; i++) {
                ItemStack stack = buf.readItemStack();
                int slot = handlerToInventorySlot(i);
                if (slot >= 0) s.slots[slot] = stack;
            }
            return true;
        } else if (type == PacketType.SetSlot) {
            int window = buf.readByte();
            buf.readVarInt(); // revision
            int handlerSlot = buf.readShort();
            ItemStack stack = buf.readItemStack();
            int slot;
            if (window == 0) {
                slot = handlerToInventorySlot(handlerSlot);
            } else if (window == -2) {
                slot = handlerSlot; // direct player inventory index
            } else {
                return false;
            }
            if (slot < 0 || slot >= HudState.SLOT_COUNT) return false;
            s.slots[slot] = stack;
            return true;
        }
        return false;
    }

    private static void readSpawnInfoGameMode(PacketByteBuf buf, HudState s) {
        buf.readIdentifier(); // dimension type
        buf.readIdentifier(); // dimension
        buf.readLong(); // hashed seed
        s.gameMode = buf.readUnsignedByte();
    }

    /**
     * Maps a slot of the player's inventory screen (window 0) to a {@code PlayerInventory} index:
     * 0 crafting result, 1-4 crafting grid, 5-8 armor (head first), 9-35 main, 36-44 hotbar, 45 offhand.
     */
    static int handlerToInventorySlot(int handlerSlot) {
        if (handlerSlot >= 36 && handlerSlot <= 44) return handlerSlot - 36;
        if (handlerSlot >= 9 && handlerSlot <= 35) return handlerSlot;
        if (handlerSlot >= 5 && handlerSlot <= 8) return 36 + (8 - handlerSlot);
        if (handlerSlot == 45) return 40;
        return -1;
    }

    private static final class Track {
        final List<Integer> times = new ArrayList<>();
        final List<HudState> states = new ArrayList<>();

        void add(int time, HudState state) {
            int last = times.size() - 1;
            if (last >= 0 && times.get(last) == time) {
                states.set(last, state); // several updates in the same millisecond: keep the newest
            } else {
                times.add(time);
                states.add(state);
            }
        }

        boolean isEmpty() {
            return times.isEmpty();
        }
    }
}
