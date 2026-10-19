package com.replaymod.playerview;

import com.replaymod.replaystudio.PacketData;
import com.replaymod.replaystudio.io.ReplayInputStream;
import com.replaymod.replaystudio.lib.viaversion.api.protocol.packet.State;
import com.replaymod.replaystudio.protocol.Packet;
import com.replaymod.replaystudio.protocol.PacketType;
import com.replaymod.replaystudio.replay.ReplayFile;
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.DynamicRegistryManager;
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
 * Built once per replay by scanning the packet stream for {@code replaymod:hud_state} payloads, independent of how the
 * replay is played back, so it works in quick mode (which only rebuilds world state) and makes seeking free.
 *
 * The scan only keeps the raw payloads. They are decoded on demand on the render thread, because item stacks can only
 * be decoded with the world's registries, which are not available while the replay is still loading.
 */
public final class HudTimeline {
    private static final Logger LOGGER = LogManager.getLogger();

    private final int[] times;
    private final byte[][] payloads;
    /** For each entry, the index of the closest full snapshot at or before it, or -1. */
    private final int[] lastFull;

    // Render-thread cache of the most recently requested state
    private DynamicRegistryManager cachedRegistries;
    private int cachedIndex = -1;
    private HudState cachedState;

    private HudTimeline(List<Integer> times, List<byte[]> payloads) {
        int n = times.size();
        this.times = new int[n];
        this.payloads = payloads.toArray(new byte[0][]);
        this.lastFull = new int[n];
        int full = -1;
        for (int i = 0; i < n; i++) {
            this.times[i] = times.get(i);
            if (HudStateCodec.isFull(this.payloads[i])) full = i;
            this.lastFull[i] = full;
        }
    }

    public int size() {
        return times.length;
    }

    /**
     * The state at the given replay time, or {@code null} if the replay has no HUD data that early.
     * Returns the same instance until the time moves to another entry, so callers can detect changes by identity.
     * Render thread only.
     */
    public HudState at(int time, DynamicRegistryManager registries) {
        int index = indexAt(time);
        if (index < 0 || lastFull[index] < 0) return null;

        if (registries != cachedRegistries) {
            cachedRegistries = registries;
            cachedIndex = -1;
            cachedState = null;
        }
        if (index == cachedIndex) return cachedState;

        HudState state;
        int start;
        if (cachedState != null && cachedIndex < index && cachedIndex >= lastFull[index]) {
            state = cachedState.copy(); // walk forward from where we are
            start = cachedIndex + 1;
        } else {
            state = new HudState(); // jump: start at the closest full snapshot
            start = lastFull[index];
        }
        for (int i = start; i <= index; i++) {
            RegistryByteBuf buf = new RegistryByteBuf(Unpooled.wrappedBuffer(payloads[i]), registries);
            try {
                // An entry may hold several payloads recorded in the same millisecond
                while (buf.isReadable() && HudStateCodec.read(buf, state)) {
                    // keep reading
                }
            } catch (Exception e) {
                LOGGER.debug("Skipping unreadable HUD state at {} ms", times[i], e);
            }
        }
        cachedIndex = index;
        cachedState = state;
        return state;
    }

    private int indexAt(int time) {
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
        return found;
    }

    /**
     * Scans the replay. Blocking; call from a background thread.
     *
     * @param cancelled checked between packets so a closed replay stops the scan early
     * @return the timeline, or {@code null} if cancelled
     */
    public static HudTimeline build(ReplayFile replayFile, BooleanSupplier cancelled) throws IOException {
        List<Integer> times = new ArrayList<>();
        List<byte[]> payloads = new ArrayList<>();

        try (ReplayInputStream in = replayFile.getPacketData(getPacketTypeRegistry(State.LOGIN))) {
            PacketData data;
            while ((data = in.readPacket()) != null) {
                try {
                    if (cancelled.getAsBoolean()) return null;
                    Packet packet = data.getPacket();
                    if (packet.getType() != PacketType.PluginMessage) continue;

                    PacketByteBuf buf = new PacketByteBuf(Unpooled.wrappedBuffer(packet.getBuf().nioBuffer()));
                    Identifier channel = buf.readIdentifier();
                    if (!HudStatePayload.CHANNEL.equals(channel)) continue;

                    byte[] bytes = new byte[buf.readableBytes()];
                    buf.readBytes(bytes);
                    int time = (int) data.getTime();
                    int last = times.size() - 1;
                    if (last >= 0 && times.get(last) == time && !HudStateCodec.isFull(bytes)) {
                        // Two deltas in the same millisecond: keep both, in order, by merging into one entry.
                        payloads.set(last, concat(payloads.get(last), bytes));
                    } else {
                        times.add(time);
                        payloads.add(bytes);
                    }
                } catch (Exception e) {
                    LOGGER.debug("Skipping unreadable packet while indexing HUD state", e);
                } finally {
                    data.release();
                }
            }
        }

        LOGGER.info("Indexed {} recorded HUD states", times.size());
        return new HudTimeline(times, payloads);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
