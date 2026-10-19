package com.replaymod.playerview;

import net.minecraft.util.math.MathHelper;

import java.util.List;

/**
 * The recording player's camera over the whole replay, interpolated by time so playback matches the original at any
 * frame rate and replay speed.
 */
public final class CameraTrack {
    /** Never interpolate across a jump this large (teleports, respawns, dimension changes). */
    private static final double MAX_INTERPOLATED_DISTANCE_SQ = 8 * 8;
    /** Never interpolate across a gap this long (the game was paused or lagging). */
    private static final int MAX_INTERPOLATED_GAP_MS = 250;

    private final int[] times;
    private final CameraSample[] samples;

    CameraTrack(List<Integer> times, List<CameraSample> samples) {
        this.times = times.stream().mapToInt(Integer::intValue).toArray();
        this.samples = samples.toArray(new CameraSample[0]);
    }

    public int size() {
        return times.length;
    }

    /** The camera at a replay time, or {@code null} before the first sample. */
    public CameraSample sample(float time) {
        int i = indexAt(time);
        if (i < 0) return null;
        CameraSample a = samples[i];
        if (i + 1 >= times.length) return a;
        CameraSample b = samples[i + 1];
        int gap = times[i + 1] - times[i];
        if (gap <= 0 || gap > MAX_INTERPOLATED_GAP_MS || a.flags != b.flags) return a;
        double dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z;
        if (dx * dx + dy * dy + dz * dz > MAX_INTERPOLATED_DISTANCE_SQ) return a;

        float t = (time - times[i]) / gap;
        if (t <= 0) return a;
        if (t >= 1) return b;
        return new CameraSample(
                a.x + dx * t, a.y + dy * t, a.z + dz * t,
                a.yaw + MathHelper.wrapDegrees(b.yaw - a.yaw) * t, // shortest way round
                MathHelper.lerp(t, a.pitch, b.pitch),
                MathHelper.lerp(t, a.fov, b.fov),
                MathHelper.lerp(t, a.bobPhase, b.bobPhase),
                MathHelper.lerp(t, a.bobAmount, b.bobAmount),
                MathHelper.lerp(t, a.nausea, b.nausea),
                MathHelper.lerp(t, a.attackCooldown, b.attackCooldown),
                t < 0.5f ? a.frozenTicks : b.frozenTicks,
                a.flags);
    }

    private int indexAt(float time) {
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
