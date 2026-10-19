package com.replaymod.playerview;

import com.replaymod.core.events.PostRenderCallback;
import com.replaymod.core.events.PreRenderCallback;
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
import net.minecraft.util.math.Vec3d;
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
 * Makes first-person playback of the recording player look like their original gameplay.
 *
 * While the replay viewer spectates the recording player in first person, every frame:
 * <ul>
 *     <li>the view follows the recorded camera exactly (position, rotation, field of view, view bobbing), see
 *     {@link #cameraSample()} and the camera and field-of-view mixins;</li>
 *     <li>nausea and portal swirl, freezing and the attack cooldown come from the recording;</li>
 *     <li>the spectated player and the replay camera report the recorded effects, armor and food, so night vision,
 *     darkness, hearts and the food bar look right;</li>
 *     <li>the HUD (hotbar, hearts, armor, food, XP, air, effect icons) is drawn from the recording, with the recorded
 *     game mode reported only while the HUD is drawn;</li>
 *     <li>the open menu and the cursor are drawn after the HUD.</li>
 * </ul>
 * In vanilla, some of these are read from the spectated player and some from {@code client.player}, which in a replay
 * is the {@link CameraEntity}; this class covers both.
 */
public class PlayerViewHud extends EventRegistrations {
    private static final Logger LOGGER = LogManager.getLogger();

    private static PlayerViewHud instance;

    private final MinecraftClient mc = MinecraftClient.getInstance();

    /** Identifies the replay a background index belongs to; replaced on every open and cleared on close. */
    private volatile Object indexToken;
    private volatile RecordedTracks tracks;
    private final MenuPlayer menuPlayer = new MenuPlayer();

    // Per frame, render thread only. Set before the world is drawn; kept through the following ticks, which also
    // read some of it (attack cooldown, effects), until the next frame recomputes it.
    private PlayerEntity frameView;
    private CameraEntity frameCamera;
    private HudState frameState;
    private int frameTime;
    private CameraSample frameCameraSample;
    /** The camera whose nausea and freezing we set, so they can be cleared when the view changes. */
    private CameraEntity effectsCamera;

    // Only while the HUD is drawn: the recorded game mode is reported and the camera's XP is swapped in
    private HudState hudActive;
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
        return hud != null ? hud.hudActive : null;
    }

    /** The recorded HUD state for this frame while the recording player is spectated in first person. */
    public static HudState frameState() {
        PlayerViewHud hud = instance;
        return hud != null ? hud.frameState : null;
    }

    /** The recorded camera for this frame, or {@code null} to leave the view to the replay. */
    public static CameraSample cameraSample() {
        PlayerViewHud hud = instance;
        return hud != null ? hud.frameCameraSample : null;
    }

    /** The spectated recording player this frame, or {@code null}. */
    public static Entity viewedPlayer() {
        PlayerViewHud hud = instance;
        return hud != null ? hud.frameView : null;
    }

    /** Whether the entity is the spectated recording player or the replay camera while recorded state applies. */
    public static boolean isSubject(Object entity) {
        PlayerViewHud hud = instance;
        return hud != null && hud.frameState != null && (entity == hud.frameView || entity == hud.frameCamera);
    }

    public static HungerManager activeHungerManager() {
        PlayerViewHud hud = instance;
        return hud != null && hud.frameState != null ? hud.derivedHunger : null;
    }

    public static Collection<StatusEffectInstance> activeEffects() {
        PlayerViewHud hud = instance;
        return hud != null && hud.frameState != null ? hud.derivedEffects : null;
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
        tracks = null;
        ReplayFile replayFile = replayHandler.getReplayFile();
        Thread thread = new Thread(() -> {
            try {
                RecordedTracks built = RecordedTracks.build(replayFile, () -> indexToken != token);
                if (built != null && indexToken == token) {
                    tracks = built;
                }
            } catch (Throwable t) {
                LOGGER.error("Failed to index the recording player's view", t);
            }
        }, "replaymod-player-view-index");
        thread.setDaemon(true);
        thread.start();
    }

    { on(ReplayClosedCallback.EVENT, this::onReplayClosed); }
    private void onReplayClosed(ReplayHandler replayHandler) {
        indexToken = null;
        tracks = null;
        restoreHud();
        clearFrame();
        menuPlayer.reset();
        appliedState = null;
        appliedEntity = null;
        derivedFor = null;
    }

    { on(PreRenderCallback.EVENT, this::onPreRender); }
    private void onPreRender() {
        restoreHud();
        PlayerEntity previousView = frameView;
        computeFrame();
        if (frameView == null && previousView != null) {
            menuPlayer.reset();
        }
    }

    // Safety net: never let the recorded game mode or XP leak past the frame, e.g. if another mod cancels the HUD.
    { on(PostRenderCallback.EVENT, this::restoreHud); }

    private void computeFrame() {
        ReplayHandler replayHandler = ReplayModReplay.instance.getReplayHandler();
        RecordedTracks tracks = this.tracks;
        CameraEntity camera = replayHandler != null ? replayHandler.getCameraEntity() : null;
        Entity view = mc.getCameraEntity();
        boolean pov = PovMode.isActive();
        if (replayHandler == null || tracks == null || mc.world == null || camera == null
                || !(view instanceof PlayerEntity) || (view == camera && !pov)
                || !mc.options.getPerspective().isFirstPerson()) {
            clearFrame();
            return;
        }

        int time = replayHandler.getReplaySender().currentTimeStamp();
        HudState state = tracks.hud.at(time, mc.world.getRegistryManager());
        if (state == null || state.entityId == HudState.UNKNOWN_ENTITY || state.entityId != view.getId()) {
            clearFrame(); // spectating someone else
            return;
        }

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

        if (pov && state.selectedSlot >= 0 && state.selectedSlot < PlayerInventory.getHotbarSize()) {
            // Every frame, so nothing the viewer does (scroll wheel) can change the recorded slot
            player.getInventory().selectedSlot = state.selectedSlot;
        }

        CameraSample sample = tracks.camera.sample(time);
        if (sample != null && !sample.isFirstPerson()) sample = null; // recorded in third person: leave the view
        if (sample != null) {
            if (pov) placePovPlayer(camera, sample);
            applyCameraEffects(player, camera, sample);
        } else {
            clearCameraEffects();
        }

        frameView = player;
        frameCamera = camera;
        frameState = state;
        frameTime = time;
        frameCameraSample = sample;
    }

    /**
     * In POV mode the local player is the recording player; put it exactly where their camera was, after the tick,
     * so the hand, overlays (water, fire, suffocation), sounds and block outline all line up.
     */
    private static void placePovPlayer(CameraEntity player, CameraSample sample) {
        double feetY = sample.y - player.getStandingEyeHeight();
        player.setPosition(sample.x, feetY, sample.z);
        player.prevX = player.lastRenderX = sample.x;
        player.prevY = player.lastRenderY = feetY;
        player.prevZ = player.lastRenderZ = sample.z;
        player.setYaw(sample.yaw);
        player.setPitch(sample.pitch);
        player.prevYaw = sample.yaw;
        player.prevPitch = sample.pitch;
        player.setHeadYaw(sample.yaw);
        player.setVelocity(Vec3d.ZERO);
    }

    private void clearFrame() {
        clearCameraEffects();
        frameView = null;
        frameCamera = null;
        frameState = null;
        frameCameraSample = null;
    }

    /**
     * View bobbing is computed from the spectated player, nausea and freezing from {@code client.player} (the camera).
     * Both are reset from the recording every frame, after the entities ticked.
     */
    private void applyCameraEffects(PlayerEntity player, CameraEntity camera, CameraSample sample) {
        player.horizontalSpeed = -sample.bobPhase;
        player.prevHorizontalSpeed = -sample.bobPhase;
        player.strideDistance = sample.bobAmount;
        player.prevStrideDistance = sample.bobAmount;

        if (effectsCamera != null && effectsCamera != camera) clearCameraEffects();
        camera.nauseaIntensity = sample.nausea;
        camera.prevNauseaIntensity = sample.nausea;
        camera.setFrozenTicks(sample.frozenTicks);
        effectsCamera = camera;
    }

    private void clearCameraEffects() {
        CameraEntity camera = effectsCamera;
        if (camera == null) return;
        camera.nauseaIntensity = 0;
        camera.prevNauseaIntensity = 0;
        camera.setFrozenTicks(0);
        effectsCamera = null;
    }

    private void onRenderGuiPre(RenderGuiEvent.Pre event) {
        restoreHud();
        HudState state = frameState;
        CameraEntity camera = frameCamera;
        if (state == null || camera == null) return;

        savedXpLevel = camera.experienceLevel;
        savedXpProgress = camera.experienceProgress;
        savedTotalXp = camera.totalExperience;
        camera.experienceLevel = state.xpLevel;
        camera.experienceProgress = state.xpProgress;
        camera.totalExperience = state.totalXp;
        hudActive = state;
    }

    private void onRenderGuiPost(RenderGuiEvent.Post event) {
        // Restore first: the menu must see the replay's real game mode (the inventory screen would otherwise switch
        // to the creative inventory for players recorded in creative mode).
        restoreHud();

        PlayerEntity view = frameView;
        RecordedTracks tracks = this.tracks;
        if (view == null || tracks == null || mc.world == null) {
            menuPlayer.reset();
            return;
        }
        ScreenState screen = tracks.screen.at(frameTime, mc.world.getRegistryManager());
        float tickDelta = event.getPartialTick().getTickDelta(false);
        CursorTrack.Sample cursor = tracks.cursor.sample(frameTime);
        menuPlayer.render(event.getGuiGraphics(), screen, cursor, tickDelta, view);
    }

    private void restoreHud() {
        if (hudActive == null) return;
        CameraEntity camera = frameCamera;
        if (camera != null) {
            camera.experienceLevel = savedXpLevel;
            camera.experienceProgress = savedXpProgress;
            camera.totalExperience = savedTotalXp;
        }
        hudActive = null;
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
