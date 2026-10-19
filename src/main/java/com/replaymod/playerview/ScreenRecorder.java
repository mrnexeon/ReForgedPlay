package com.replaymod.playerview;

import com.replaymod.replay.mixin.HandledScreenAccessor;
import com.replaymod.replay.mixin.ScreenHandlerAccessor;
import io.netty.buffer.Unpooled;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.util.Window;
import net.minecraft.item.ItemStack;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registries;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.Property;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Records the menu the local player has open, and their cursor, once per rendered frame.
 *
 * Inventory-style menus ({@link HandledScreen}) are recorded, which covers the player inventory, containers and
 * workstations; the creative inventory is left to a later phase. The pause menu and chat are not handled screens, so
 * they are never recorded.
 */
public class ScreenRecorder {
    /** A full snapshot is written this often while a menu stays open, so seeking never needs to replay far. */
    private static final long FULL_SNAPSHOT_INTERVAL_MS = 5000;
    /** Cursor samples are written at most this often (about 60 per second); button changes always go through. */
    private static final long CURSOR_INTERVAL_MS = 16;

    private Screen currentScreen;
    private ScreenState last;
    private int session;
    private long lastFullTime;

    private float lastCursorX = Float.NaN, lastCursorY = Float.NaN;
    private int lastButtons = -1;
    private long lastCursorTime;

    /**
     * Captures this frame and hands any packets to record to {@code save}.
     */
    public void frame(MinecraftClient mc, Consumer<CustomPayloadS2CPacket> save) {
        Screen screen = mc.currentScreen;
        HandledScreen<?> handled = screen instanceof HandledScreen<?> && !(screen instanceof CreativeInventoryScreen)
                ? (HandledScreen<?>) screen : null;
        Identifier kind = handled != null ? kindOf(handled.getScreenHandler()) : null;

        if (handled == null || kind == null || mc.player == null) {
            if (last != null && last.open) {
                save.accept(encode(mc, buf -> ScreenStateCodec.writeClose(buf), PlayerViewPayloads.Screen::new));
                last = null;
                currentScreen = null;
                lastButtons = -1;
            }
            return;
        }

        long now = System.currentTimeMillis();
        boolean newMenu = handled != currentScreen || last == null;
        if (newMenu) session++;
        ScreenState cur = capture(handled, kind);

        if (newMenu || now - lastFullTime >= FULL_SNAPSHOT_INTERVAL_MS) {
            save.accept(encode(mc, buf -> ScreenStateCodec.writeOpen(buf, cur), PlayerViewPayloads.Screen::new));
            lastFullTime = now;
        } else {
            int flags = ScreenStateCodec.diff(last, cur);
            if (flags != 0) {
                ScreenState prev = last;
                save.accept(encode(mc, buf -> ScreenStateCodec.writeDelta(buf, prev, cur, flags),
                        PlayerViewPayloads.Screen::new));
            }
        }
        currentScreen = handled;
        last = cur;

        recordCursor(mc, (HandledScreenAccessor) handled, now, newMenu, save);
    }

    private void recordCursor(MinecraftClient mc, HandledScreenAccessor screen, long now, boolean force,
                              Consumer<CustomPayloadS2CPacket> save) {
        Window window = mc.getWindow();
        if (window.getWidth() <= 0 || window.getHeight() <= 0) return;
        float x = (float) (mc.mouse.getX() * window.getScaledWidth() / window.getWidth()) - screen.replayMod$getX();
        float y = (float) (mc.mouse.getY() * window.getScaledHeight() / window.getHeight()) - screen.replayMod$getY();

        long handle = window.getHandle();
        int buttons = 0;
        if (GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS) buttons |= 1;
        if (GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS) buttons |= 2;
        if (GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_MIDDLE) == GLFW.GLFW_PRESS) buttons |= 4;

        boolean moved = Math.abs(x - lastCursorX) > 0.01f || Math.abs(y - lastCursorY) > 0.01f
                || Float.isNaN(lastCursorX);
        boolean buttonsChanged = buttons != lastButtons;
        if (force || buttonsChanged || (moved && now - lastCursorTime >= CURSOR_INTERVAL_MS)) {
            save.accept(new CustomPayloadS2CPacket(PlayerViewPayloads.Cursor.of(x, y, buttons)));
            lastCursorX = x;
            lastCursorY = y;
            lastButtons = buttons;
            lastCursorTime = now;
        }
    }

    private ScreenState capture(HandledScreen<?> screen, Identifier kind) {
        ScreenHandler handler = screen.getScreenHandler();
        ScreenState s = new ScreenState();
        s.open = true;
        s.session = session;
        s.kind = kind;
        s.title = screen.getTitle();

        List<Slot> slots = handler.slots;
        s.slots = new ItemStack[slots.size()];
        boolean sameMenu = last != null && last.session == session && last.slots.length == slots.size();
        for (int i = 0; i < slots.size(); i++) {
            ItemStack live = slots.get(i).getStack();
            // Reuse the previous copy when unchanged, so we only copy stacks that actually changed.
            s.slots[i] = sameMenu && ItemStack.areEqual(last.slots[i], live) ? last.slots[i] : live.copy();
        }
        ItemStack cursor = handler.getCursorStack();
        s.cursorStack = sameMenu && ItemStack.areEqual(last.cursorStack, cursor) ? last.cursorStack : cursor.copy();

        List<Property> properties = ((ScreenHandlerAccessor) handler).replayMod$getProperties();
        s.properties = new int[properties.size()];
        for (int i = 0; i < properties.size(); i++) s.properties[i] = properties.get(i).get();

        HandledScreenAccessor accessor = (HandledScreenAccessor) screen;
        s.dragging = accessor.replayMod$isCursorDragging();
        s.dragButton = accessor.replayMod$getHeldButtonType();
        if (s.dragging) {
            List<Integer> ids = new ArrayList<>();
            for (Slot slot : accessor.replayMod$getCursorDragSlots()) ids.add(slot.id);
            ids.sort(null);
            s.dragSlots = ids.stream().mapToInt(Integer::intValue).toArray();
        }
        return s;
    }

    /** The recorded kind of a menu, or null if it can't be identified (unregistered modded menus). */
    private static Identifier kindOf(ScreenHandler handler) {
        if (handler instanceof PlayerScreenHandler) return ScreenState.PLAYER_INVENTORY;
        ScreenHandlerType<?> type = ((ScreenHandlerAccessor) handler).replayMod$getType();
        return type != null ? Registries.SCREEN_HANDLER.getId(type) : null;
    }

    private interface Writer {
        void write(RegistryByteBuf buf);
    }

    private static <P extends CustomPayload> CustomPayloadS2CPacket encode(
            MinecraftClient mc, Writer writer, java.util.function.Function<byte[], P> payload) {
        // CLOSE carries no items, and is the only payload written after the player may be gone
        DynamicRegistryManager registries = mc.player != null
                ? mc.player.getRegistryManager() : DynamicRegistryManager.EMPTY;
        RegistryByteBuf buf = new RegistryByteBuf(Unpooled.buffer(), registries);
        try {
            writer.write(buf);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return new CustomPayloadS2CPacket(payload.apply(bytes));
        } finally {
            buf.release();
        }
    }
}
