package com.replaymod.playerview;

import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Everything the recording player's HUD showed at one moment.
 *
 * Instances stored in a {@link HudTimeline} are never mutated after they were added; the recorder and the
 * timeline builder work on their own mutable instance and store {@link #copy() copies}.
 */
public final class HudState {
    /** Player inventory slots in {@code PlayerInventory} order: 0-35 main, 36-39 armor (feet first), 40 offhand. */
    public static final int SLOT_COUNT = 41;
    /** Entity id is not known (old replays before the first join packet). */
    public static final int UNKNOWN_ENTITY = Integer.MIN_VALUE;

    public int entityId = UNKNOWN_ENTITY;
    /** {@code GameMode} id; survival by default so old replays still show their status bars. */
    public int gameMode = 0;
    public int selectedSlot;
    public final ItemStack[] slots = new ItemStack[SLOT_COUNT];
    public float health = 20;
    public float maxHealth = 20;
    public float absorption;
    public int food = 20;
    public float saturation = 5;
    public int xpLevel;
    public float xpProgress;
    public int totalXp;
    public int armor;
    public int air = 300;
    public int maxAir = 300;
    public List<Effect> effects = new ArrayList<>();

    /** Whether this state came from client-side recording ({@code replaymod:hud_state}) rather than server packets. */
    public boolean recordedOnClient;

    public HudState() {
        Arrays.fill(slots, ItemStack.EMPTY);
    }

    public HudState copy() {
        HudState c = new HudState();
        c.entityId = entityId;
        c.gameMode = gameMode;
        c.selectedSlot = selectedSlot;
        System.arraycopy(slots, 0, c.slots, 0, SLOT_COUNT); // stacks are never mutated once captured
        c.health = health;
        c.maxHealth = maxHealth;
        c.absorption = absorption;
        c.food = food;
        c.saturation = saturation;
        c.xpLevel = xpLevel;
        c.xpProgress = xpProgress;
        c.totalXp = totalXp;
        c.armor = armor;
        c.air = air;
        c.maxAir = maxAir;
        c.effects = new ArrayList<>(effects);
        c.recordedOnClient = recordedOnClient;
        return c;
    }

    /**
     * A status effect as shown in the HUD.
     * Durations above {@link #BLINK_THRESHOLD} ticks only matter for the inventory screen, so they are not compared
     * when deciding whether the effect list changed (see {@link #sameForHud}).
     */
    public static final class Effect {
        public static final int BLINK_THRESHOLD = 200;

        public final Identifier id;
        public final int amplifier;
        public final int duration;
        public final boolean ambient;
        public final boolean showIcon;
        public final boolean infinite;

        public Effect(Identifier id, int amplifier, int duration, boolean ambient, boolean showIcon, boolean infinite) {
            this.id = id;
            this.amplifier = amplifier;
            this.duration = duration;
            this.ambient = ambient;
            this.showIcon = showIcon;
            this.infinite = infinite;
        }

        private int hudDuration() {
            return infinite || duration > BLINK_THRESHOLD ? -1 : duration;
        }

        public boolean sameForHud(Effect o) {
            return id.equals(o.id) && amplifier == o.amplifier && ambient == o.ambient
                    && showIcon == o.showIcon && infinite == o.infinite && hudDuration() == o.hudDuration();
        }

        public static boolean sameForHud(List<Effect> a, List<Effect> b) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) {
                if (!a.get(i).sameForHud(b.get(i))) return false;
            }
            return true;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Effect)) return false;
            Effect e = (Effect) o;
            return amplifier == e.amplifier && duration == e.duration && ambient == e.ambient
                    && showIcon == e.showIcon && infinite == e.infinite && id.equals(e.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, amplifier, duration, ambient, showIcon, infinite);
        }
    }
}
