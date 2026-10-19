package com.replaymod.replay.mixin;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;

/** Menu layout and drag state, read while recording and set when a recorded menu is replayed. */
@Mixin(HandledScreen.class)
public interface HandledScreenAccessor {
    @Accessor("x")
    int replayMod$getX();

    @Accessor("y")
    int replayMod$getY();

    @Accessor("cursorDragging")
    boolean replayMod$isCursorDragging();

    @Accessor("cursorDragging")
    void replayMod$setCursorDragging(boolean dragging);

    @Accessor("heldButtonType")
    int replayMod$getHeldButtonType();

    @Accessor("heldButtonType")
    void replayMod$setHeldButtonType(int button);

    @Accessor("cursorDragSlots")
    Set<Slot> replayMod$getCursorDragSlots();
}
