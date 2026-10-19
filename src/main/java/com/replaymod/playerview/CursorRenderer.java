package com.replaymod.playerview;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;

/**
 * Draws the recorded mouse cursor. Minecraft never draws a cursor itself (it is the operating system's pointer), so
 * without this the cursor would be invisible in replays and in exported videos.
 */
final class CursorRenderer {
    /** Classic arrow pointer: X = outline, W = fill, . = transparent. The tip is the top-left pixel. */
    private static final String[] ARROW = {
            "X...........",
            "XX..........",
            "XWX.........",
            "XWWX........",
            "XWWWX.......",
            "XWWWWX......",
            "XWWWWWX.....",
            "XWWWWWWX....",
            "XWWWWWWWX...",
            "XWWWWWWWWX..",
            "XWWWWWWWWWX.",
            "XWWWWWWXXXXX",
            "XWWWXWWX....",
            "XWWXXWWX....",
            "XWX..XWWX...",
            "XX...XWWX...",
            "X.....XWWX..",
            "......XWWX..",
            ".......XX...",
    };

    private static final int OUTLINE = 0xFF000000;
    private static final int FILL = 0xFFFFFFFF;
    private static final int FILL_PRESSED = 0xFFC8C8C8;
    /** Arrow pixels per GUI unit. */
    private static final float SCALE = 0.5f;

    private CursorRenderer() {}

    static void draw(DrawContext context, float x, float y, CursorTrack.Sample cursor) {
        MatrixStack matrices = context.getMatrices();
        matrices.push();
        matrices.translate(x, y, 500); // above items, the held stack and tooltips

        if (cursor.clickProgress >= 0) {
            drawClickRing(context, cursor.clickProgress);
        }

        matrices.scale(SCALE, SCALE, 1);
        int fill = cursor.buttons != 0 ? FILL_PRESSED : FILL;
        for (int row = 0; row < ARROW.length; row++) {
            String line = ARROW[row];
            int col = 0;
            while (col < line.length()) {
                char c = line.charAt(col);
                if (c == '.') {
                    col++;
                    continue;
                }
                int start = col;
                while (col < line.length() && line.charAt(col) == c) col++; // one fill call per run
                context.fill(start, row, col, row + 1, c == 'X' ? OUTLINE : fill);
            }
        }
        matrices.pop();
    }

    /** An expanding, fading square ring around the cursor tip right after a click. */
    private static void drawClickRing(DrawContext context, float progress) {
        int radius = 2 + Math.round(progress * 5);
        int alpha = Math.round((1 - progress) * 160);
        int color = (alpha << 24) | 0xFFFFFF;
        context.fill(-radius, -radius, radius, -radius + 1, color);
        context.fill(-radius, radius - 1, radius, radius, color);
        context.fill(-radius, -radius + 1, -radius + 1, radius - 1, color);
        context.fill(radius - 1, -radius + 1, radius, radius - 1, color);
    }
}
