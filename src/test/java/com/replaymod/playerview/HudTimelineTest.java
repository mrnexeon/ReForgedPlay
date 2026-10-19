package com.replaymod.playerview;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class HudTimelineTest {
    @Test
    public void mapsInventoryScreenSlotsToPlayerInventory() {
        assertEquals(-1, HudTimeline.handlerToInventorySlot(0)); // crafting result
        assertEquals(-1, HudTimeline.handlerToInventorySlot(4)); // crafting grid
        assertEquals(39, HudTimeline.handlerToInventorySlot(5)); // helmet
        assertEquals(36, HudTimeline.handlerToInventorySlot(8)); // boots
        assertEquals(9, HudTimeline.handlerToInventorySlot(9)); // first main slot
        assertEquals(35, HudTimeline.handlerToInventorySlot(35)); // last main slot
        assertEquals(0, HudTimeline.handlerToInventorySlot(36)); // first hotbar slot
        assertEquals(8, HudTimeline.handlerToInventorySlot(44)); // last hotbar slot
        assertEquals(40, HudTimeline.handlerToInventorySlot(45)); // offhand
        assertEquals(-1, HudTimeline.handlerToInventorySlot(46));
    }
}
