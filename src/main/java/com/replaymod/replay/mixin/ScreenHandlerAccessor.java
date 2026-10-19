package com.replaymod.replay.mixin;

import net.minecraft.screen.Property;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** Menu type and progress properties (furnace fuel and progress, brewing time, ...). */
@Mixin(ScreenHandler.class)
public interface ScreenHandlerAccessor {
    @Accessor("properties")
    List<Property> replayMod$getProperties();

    /** The menu type, or null for the player's own inventory. Unlike {@code getType()} this never throws. */
    @Accessor("type")
    ScreenHandlerType<?> replayMod$getType();
}
