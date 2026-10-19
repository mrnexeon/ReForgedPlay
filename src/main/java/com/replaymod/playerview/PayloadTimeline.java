package com.replaymod.playerview;

import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.DynamicRegistryManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.function.Predicate;

/**
 * A recorded state over the whole replay, indexed by replay time, built from full snapshots and deltas.
 *
 * Only the raw payloads are kept. They are decoded on demand on the render thread, because item stacks can only be
 * decoded with the world's registries, which are not available while the replay is still loading.
 */
public final class PayloadTimeline<S> {
    private static final Logger LOGGER = LogManager.getLogger();

    /** How a state is created, copied and updated from one payload. */
    public interface Codec<S> {
        S create();

        S copy(S state);

        /** Applies one payload; returns false if it was skipped (newer format). */
        boolean read(RegistryByteBuf buf, S state);
    }

    private final Codec<S> codec;
    private final int[] times;
    private final byte[][] payloads;
    /** For each entry, the index of the closest full snapshot at or before it, or -1. */
    private final int[] lastFull;

    // Render-thread cache of the most recently requested state
    private DynamicRegistryManager cachedRegistries;
    private int cachedIndex = -1;
    private S cachedState;

    PayloadTimeline(Codec<S> codec, List<Integer> times, List<byte[]> payloads, Predicate<byte[]> isFull) {
        this.codec = codec;
        int n = times.size();
        this.times = new int[n];
        this.payloads = payloads.toArray(new byte[0][]);
        this.lastFull = new int[n];
        int full = -1;
        for (int i = 0; i < n; i++) {
            this.times[i] = times.get(i);
            if (isFull.test(this.payloads[i])) full = i;
            this.lastFull[i] = full;
        }
    }

    public int size() {
        return times.length;
    }

    /**
     * The state at the given replay time, or {@code null} if there is no data that early.
     * Returns the same instance until the time moves to another entry, so callers can detect changes by identity.
     * Render thread only.
     */
    public S at(int time, DynamicRegistryManager registries) {
        int index = indexAt(time);
        if (index < 0 || lastFull[index] < 0) return null;

        if (registries != cachedRegistries) {
            cachedRegistries = registries;
            cachedIndex = -1;
            cachedState = null;
        }
        if (index == cachedIndex) return cachedState;

        S state;
        int start;
        if (cachedState != null && cachedIndex < index && cachedIndex >= lastFull[index]) {
            state = codec.copy(cachedState); // walk forward from where we are
            start = cachedIndex + 1;
        } else {
            state = codec.create(); // jump: start at the closest full snapshot
            start = lastFull[index];
        }
        for (int i = start; i <= index; i++) {
            RegistryByteBuf buf = new RegistryByteBuf(Unpooled.wrappedBuffer(payloads[i]), registries);
            try {
                // An entry may hold several payloads recorded in the same millisecond
                while (buf.isReadable() && codec.read(buf, state)) {
                    // keep reading
                }
            } catch (Exception e) {
                LOGGER.debug("Skipping unreadable recorded state at {} ms", times[i], e);
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
}
