package com.replaymod.playerview;

import com.replaymod.core.events.PostRenderCallback;
import com.replaymod.replay.ReplayHandler;
import com.replaymod.replay.ReplayModReplay;
import com.replaymod.replay.camera.CameraEntity;
import com.replaymod.replay.events.ReplayClosedCallback;
import com.replaymod.replay.events.ReplayOpenedCallback;
import com.replaymod.replaystudio.replay.ReplayFile;
import de.johni0702.minecraft.gui.utils.EventRegistrations;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.HungerManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Shows the recording player's HUD (hotbar, hearts, armor, food, XP, air, effect icons) while the replay viewer
 * spectates that player in first person.
 *
 * In 1.21.1 the HUD reads hotbar, health, armor, food and air from the spectated player, and XP and effect icons from
 * {@code client.player}, which in a replay is the {@link CameraEntity}. While the HUD is drawn this class therefore
 * <ul>
 *     <li>fills the spectated player's inventory, selected slot, health, absorption and air from the recording,</li>
 *     <li>exposes the recorded state via {@link #activeState()}: mixins use it to report the recorded armor, food and
 *     effects for both entities, and the recorded game mode instead of spectator,</li>
 *     <li>temporarily sets the camera's XP fields, restoring them right after.</li>
 * </ul>
 */
public class PlayerViewHud extends EventRegistrations {
    private static final Logger LOGGER = LogManager.getLogger();

    private static PlayerViewHud instance;

    private final MinecraftClient mc = MinecraftClient.getInstance();

    /** Identifies the replay a background index belongs to; replaced on every open and cleared on close. */
    private volatile Object indexToken;
    private volatile HudTimeline timeline;

    // Render-thread only state
    private HudState active;
    private CameraEntity activeCamera;
    private PlayerEntity activeView;
    private int savedXpLevel, savedTotalXp;
    private float savedXpProgress;

    private HudState appliedState;
    private Entity appliedEntity;
    private HudState derivedFor;
    private HungerManager derivedHunger;
    private List<StatusEffectInstance> derivedEffects = Collections.emptyList();

    /** The recorded state while the HUD is being drawn for the recording player, otherwise {@code null}. */
    public static HudState activeState() {
        PlayerViewHud hud = instance;
        return hud != null ? hud.active : null;
    }

    /** Whether the HUD is being drawn for the recording player and the entity is one the HUD reads from. */
    public static boolean isSubject(Object entity) {
        PlayerViewHud hud = instance;
        return hud != null && hud.active != null && (entity == hud.activeView || entity == hud.activeCamera);
    }

    public static HungerManager activeHungerManager() {
        PlayerViewHud hud = instance;
        return hud != null && hud.active != null ? hud.derivedHunger : null;
    }

    public static Collection<StatusEffectInstance> activeEffects() {
        PlayerViewHud hud = instance;
        return hud != null && hud.active != null ? hud.derivedEffects : null;
    }

    public static StatusEffectInstance activeEffect(RegistryEntry<StatusEffect> effect) {
        Collection<StatusEffectInstance> effects = activeEffects();
        if (effects == null) return null;
        for (StatusEffectInstance instance : effects) {
            if (instance.getEffectType().value() == effect.value()) return instance;
        }
        return null;
    }

    @Override
    public void register() {
        super.register();
        instance = this;
        NeoForge.EVENT_BUS.addListener(this::onRenderGuiPre);
        NeoForge.EVENT_BUS.addListener(this::onRenderGuiPost);
    }

    { on(ReplayOpenedCallback.EVENT, this::onReplayOpened); }
    private void onReplayOpened(ReplayHandler replayHandler) {
        Object token = new Object();
        indexToken = token;
        timeline = null;
        ReplayFile replayFile = replayHandler.getReplayFile();
        Thread thread = new Thread(() -> {
            try {
                HudTimeline built = HudTimeline.build(replayFile, () -> indexToken != token);
                if (built != null && indexToken == token) {
                    timeline = built;
                }
            } catch (Throwable t) {
                LOGGER.error("Failed to index the recording player's HUD state", t);
            }
        }, "replaymod-hud-index");
        thread.setDaemon(true);
        thread.start();
    }

    { on(ReplayClosedCallback.EVENT, this::onReplayClosed); }
    private void onReplayClosed(ReplayHandler replayHandler) {
        indexToken = null;
        timeline = null;
        restore();
        appliedState = null;
        appliedEntity = null;
        derivedFor = null;
    }

    // Safety net: never let the recorded state leak past the frame, e.g. if another mod cancels the HUD.
    { on(PostRenderCallback.EVENT, this::restore); }

    private void onRenderGuiPre(RenderGuiEvent.Pre event) {
        restore();

        ReplayHandler replayHandler = ReplayModReplay.instance.getReplayHandler();
        HudTimeline timeline = this.timeline;
        if (replayHandler == null || timeline == null || mc.world == null) return;

        CameraEntity camera = replayHandler.getCameraEntity();
        Entity view = mc.getCameraEntity();
        if (camera == null || !(view instanceof PlayerEntity) || view == camera) return;
        if (!mc.options.getPerspective().isFirstPerson()) return;

        HudState state = timeline.at(replayHandler.getReplaySender().currentTimeStamp(), mc.world.getRegistryManager());
        if (state == null || state.entityId == HudState.UNKNOWN_ENTITY || state.entityId != view.getId()) return;

        PlayerEntity player = (PlayerEntity) view;
        if (state != appliedState || view != appliedEntity) {
            applyToPlayer(player, state);
            appliedState = state;
            appliedEntity = view;
        }
        if (state != derivedFor) {
            derive(state);
            derivedFor = state;
        }

        savedXpLevel = camera.experienceLevel;
        savedXpProgress = camera.experienceProgress;
        savedTotalXp = camera.totalExperience;
        camera.experienceLevel = state.xpLevel;
        camera.experienceProgress = state.xpProgress;
        camera.totalExperience = state.totalXp;

        activeCamera = camera;
        activeView = player;
        active = state;
    }

    private void onRenderGuiPost(RenderGuiEvent.Post event) {
        restore();
    }

    private void restore() {
        if (active == null) return;
        CameraEntity camera = activeCamera;
        camera.experienceLevel = savedXpLevel;
        camera.experienceProgress = savedXpProgress;
        camera.totalExperience = savedTotalXp;
        active = null;
        activeCamera = null;
        activeView = null;
    }

    /**
     * Writes the recorded inventory and vitals onto the spectated player entity. Stacks are copied because the
     * entity ticks its inventory, which may mutate stacks, and timeline states must stay unchanged.
     */
    private static void applyToPlayer(PlayerEntity player, HudState state) {
        PlayerInventory inventory = player.getInventory();
        for (int i = 0; i < inventory.main.size() && i < 36; i++) {
            inventory.main.set(i, state.slots[i].copy());
        }
        for (int i = 0; i < inventory.armor.size() && i < 4; i++) {
            inventory.armor.set(i, state.slots[36 + i].copy());
        }
        if (!inventory.offHand.isEmpty()) {
            inventory.offHand.set(0, state.slots[40].copy());
        }
        if (state.selectedSlot >= 0 && state.selectedSlot < PlayerInventory.getHotbarSize()) {
            inventory.selectedSlot = state.selectedSlot;
        }
        player.setAbsorptionAmount(state.absorption);
        player.setHealth(state.health);
        player.setAir(state.air);
    }

    private void derive(HudState state) {
        HungerManager hunger = new HungerManager();
        hunger.setFoodLevel(state.food);
        hunger.setSaturationLevel(state.saturation);
        derivedHunger = hunger;

        List<StatusEffectInstance> effects = new ArrayList<>(state.effects.size());
        for (HudState.Effect e : state.effects) {
            Optional<RegistryEntry.Reference<StatusEffect>> type = Registries.STATUS_EFFECT.getEntry(e.id);
            if (type.isEmpty()) continue;
            effects.add(new StatusEffectInstance(type.get(), e.infinite ? StatusEffectInstance.INFINITE : e.duration,
                    e.amplifier, e.ambient, true, e.showIcon));
        }
        derivedEffects = Collections.unmodifiableList(effects);
    }
}
