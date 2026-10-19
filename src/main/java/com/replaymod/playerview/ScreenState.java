package com.replaymod.playerview;

import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.Arrays;

/**
 * The menu the recording player had open at one moment: which kind of menu, its title, every slot, the item held on
 * the cursor, the menu's progress properties (furnace fuel and smelting progress, ...) and an in-progress drag.
 *
 * Instances stored in a timeline are never mutated after they were added.
 */
public final class ScreenState {
    /** Kind used for the player's own inventory screen, which has no registered menu type. */
    public static final Identifier PLAYER_INVENTORY = Identifier.of("replaymod", "player_inventory");

    public boolean open;
    /** Increases every time a menu is opened, so playback knows when to build a new screen. */
    public int session;
    public Identifier kind = PLAYER_INVENTORY;
    public Text title = Text.empty();
    public ItemStack[] slots = new ItemStack[0];
    public ItemStack cursorStack = ItemStack.EMPTY;
    public int[] properties = new int[0];
    public boolean dragging;
    public int dragButton;
    public int[] dragSlots = new int[0];

    public ScreenState copy() {
        ScreenState c = new ScreenState();
        c.open = open;
        c.session = session;
        c.kind = kind;
        c.title = title;
        c.slots = Arrays.copyOf(slots, slots.length); // stacks are never mutated once captured
        c.cursorStack = cursorStack;
        c.properties = Arrays.copyOf(properties, properties.length);
        c.dragging = dragging;
        c.dragButton = dragButton;
        c.dragSlots = Arrays.copyOf(dragSlots, dragSlots.length);
        return c;
    }
}
