package com.replaymod.playerview;

import java.util.List;

/**
 * The recording player's cursor over the whole replay: position relative to the open menu's top-left corner (GUI
 * units) and pressed mouse buttons. Small and registry-free, so it is fully decoded while indexing.
 */
public final class CursorTrack {
    /** How long a click highlight stays visible after a button goes down. */
    public static final int CLICK_EFFECT_MS = 250;

    private final int[] times;
    private final float[] xs, ys;
    private final int[] buttons;
    /** For each entry, the time of the most recent button press at or before it, or Integer.MIN_VALUE. */
    private final int[] lastPress;

    CursorTrack(List<int[]> entries, List<float[]> positions) {
        int n = entries.size();
        times = new int[n];
        buttons = new int[n];
        lastPress = new int[n];
        xs = new float[n];
        ys = new float[n];
        int prevButtons = 0, press = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            times[i] = entries.get(i)[0];
            buttons[i] = entries.get(i)[1];
            xs[i] = positions.get(i)[0];
            ys[i] = positions.get(i)[1];
            if ((buttons[i] & ~prevButtons) != 0) press = times[i]; // a button went down
            lastPress[i] = press;
            prevButtons = buttons[i];
        }
    }

    public static final class Sample {
        public final float x, y;
        public final int buttons;
        /** 0..1 progress of the click highlight, or -1 if none is showing. */
        public final float clickProgress;

        Sample(float x, float y, int buttons, float clickProgress) {
            this.x = x;
            this.y = y;
            this.buttons = buttons;
            this.clickProgress = clickProgress;
        }
    }

    /** The cursor at a replay time, interpolated between samples; {@code null} before the first sample. */
    public Sample sample(float time) {
        int i = indexAt(time);
        if (i < 0) return null;
        float x = xs[i], y = ys[i];
        if (i + 1 < times.length && times[i + 1] > times[i]) {
            float t = (time - times[i]) / (times[i + 1] - times[i]);
            if (t > 0 && t < 1) {
                x += (xs[i + 1] - xs[i]) * t;
                y += (ys[i + 1] - ys[i]) * t;
            }
        }
        float click = -1;
        if (lastPress[i] != Integer.MIN_VALUE) {
            float since = time - lastPress[i];
            if (since >= 0 && since < CLICK_EFFECT_MS) click = since / CLICK_EFFECT_MS;
        }
        return new Sample(x, y, buttons[i], click);
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
