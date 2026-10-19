package com.replaymod.playerview;

import com.replaymod.replay.mixin.HandledScreenAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreens;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Draws the menu the recording player had open, and their cursor, on top of the replay.
 *
 * The menu is a real vanilla screen built for the recorded menu type and filled from the recording, but it is never
 * opened: it receives no input and sends nothing. It is drawn with the recorded mouse position, so vanilla draws the
 * hovered slot highlight, the item on the cursor and tooltips exactly as the player saw them.
 */
public class MenuPlayer {
    private static final Logger LOGGER = LogManager.getLogger();

    private static LivingEntity drawingFor;

    private final MinecraftClient mc = MinecraftClient.getInstance();

    private HandledScreen<?> screen;
    private ScreenHandler handler;
    private int session = -1;
    private PlayerEntity owner;
    private int width, height;
    private ScreenState applied;
    private ScreenState failed;

    /** The player whose recorded menu is being drawn right now (for the inventory screen's player model). */
    public static LivingEntity drawingFor() {
        return drawingFor;
    }

    public void reset() {
        screen = null;
        handler = null;
        session = -1;
        owner = null;
        applied = null;
    }

    public void render(DrawContext context, ScreenState state, CursorTrack.Sample cursor, float tickDelta,
                       PlayerEntity player) {
        if (state == null || !state.open) {
            reset();
            return;
        }
        if (state == failed) return; // already logged, don't retry every frame

        int scaledWidth = mc.getWindow().getScaledWidth();
        int scaledHeight = mc.getWindow().getScaledHeight();
        if (screen == null || state.session != session || player != owner) {
            reset();
            if (!build(state, player)) {
                failed = state;
                return;
            }
            session = state.session;
            owner = player;
            width = -1;
        }
        if (scaledWidth != width || scaledHeight != height) {
            screen.init(mc, scaledWidth, scaledHeight);
            width = scaledWidth;
            height = scaledHeight;
        }
        if (state != applied) {
            apply(state);
            applied = state;
        }

        HandledScreenAccessor layout = (HandledScreenAccessor) screen;
        float cursorX = layout.replayMod$getX() + (cursor != null ? cursor.x : -1000);
        float cursorY = layout.replayMod$getY() + (cursor != null ? cursor.y : -1000);
        int mouseX = Math.round(cursorX);
        int mouseY = Math.round(cursorY);

        drawingFor = player;
        try {
            screen.render(context, mouseX, mouseY, tickDelta);
        } catch (Exception e) {
            LOGGER.error("Failed to draw recorded menu {}", state.kind, e);
            failed = state;
            reset();
            return;
        } finally {
            drawingFor = null;
        }
        if (cursor != null) {
            CursorRenderer.draw(context, cursorX, cursorY, cursor);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean build(ScreenState state, PlayerEntity player) {
        try {
            if (ScreenState.PLAYER_INVENTORY.equals(state.kind)) {
                // The inventory screen always uses the player's own inventory menu
                InventoryScreen inventoryScreen = new InventoryScreen(player);
                screen = inventoryScreen;
                handler = inventoryScreen.getScreenHandler();
                return true;
            }
            ScreenHandlerType type = Registries.SCREEN_HANDLER.get(state.kind);
            if (type == null) {
                LOGGER.warn("Recorded menu type {} is not known here; it will not be shown", state.kind);
                return false;
            }
            Optional<HandledScreens.Provider> provider = (Optional) HandledScreens.getScreenFactory(type);
            if (provider.isEmpty()) {
                LOGGER.warn("No screen registered for recorded menu type {}; it will not be shown", state.kind);
                return false;
            }
            // A separate inventory object, so filling the menu never touches the player's real inventory
            PlayerInventory inventory = new PlayerInventory(player);
            ScreenHandler created = type.create(0, inventory);
            Screen created2 = provider.get().create(created, inventory, state.title);
            if (!(created2 instanceof HandledScreen)) return false;
            screen = (HandledScreen<?>) created2;
            handler = created;
            return true;
        } catch (Exception e) {
            LOGGER.warn("Could not rebuild recorded menu {}; it will not be shown", state.kind, e);
            reset();
            return false;
        }
    }

    private void apply(ScreenState state) {
        List<ItemStack> stacks = new ArrayList<>(handler.slots.size());
        for (int i = 0; i < handler.slots.size(); i++) {
            // Copies, because recorded states must stay unchanged and menus may modify their stacks
            stacks.add(i < state.slots.length ? state.slots[i].copy() : ItemStack.EMPTY);
        }
        handler.updateSlotStacks(handler.getRevision(), stacks, state.cursorStack.copy());
        for (int i = 0; i < state.properties.length; i++) {
            handler.setProperty(i, state.properties[i]);
        }

        HandledScreenAccessor drag = (HandledScreenAccessor) screen;
        Set<Slot> dragSlots = drag.replayMod$getCursorDragSlots();
        dragSlots.clear();
        drag.replayMod$setCursorDragging(state.dragging);
        drag.replayMod$setHeldButtonType(state.dragButton);
        if (state.dragging) {
            for (int id : state.dragSlots) {
                if (id >= 0 && id < handler.slots.size()) dragSlots.add(handler.getSlot(id));
            }
        }
    }
}
