package com.replaymod.playerview;

import com.replaymod.replay.ReplayHandler;
import com.replaymod.replay.ReplayModReplay;

/**
 * First-person POV mode: the replay is played back as the recording player.
 *
 * Normally the replay's local player is a free camera, and the recorded player is shown as a separate entity. In POV
 * mode the local player <em>is</em> the recording player: it keeps their entity id and game mode, receives every packet
 * their client received, runs the normal player tick with viewer input disabled, and is positioned from the recorded
 * camera every frame. Minecraft then draws the first-person view with its own code, so HUD, overlays, hand, effects
 * and anything else derived from the local player come out as they did originally.
 *
 * Switching modes restarts the replay at the current time, because the world is set up differently from the very first
 * packet. Quick mode is not supported, as it does not replay the player's own packets.
 */
public final class PovMode {
    private static volatile boolean enabled;

    private PovMode() {}

    /** Whether POV mode is selected; takes effect when the replay (re)starts. */
    public static boolean isEnabled() {
        return enabled;
    }

    /** Whether a replay is currently running in POV mode. */
    public static boolean isActive() {
        return enabled && ReplayModReplay.instance != null && ReplayModReplay.instance.getReplayHandler() != null;
    }

    /**
     * Switches between POV and free camera mode and restarts the replay at the current time.
     *
     * @return null on success, otherwise why it could not be switched
     */
    public static String toggle() {
        ReplayHandler replayHandler = ReplayModReplay.instance.getReplayHandler();
        if (replayHandler == null) return "No replay is open";
        if (replayHandler.isQuickMode()) return "POV mode needs Quick Mode to be turned off";
        if (!replayHandler.getReplaySender().isAsyncMode()) return "not while a camera path is playing or rendering";
        enabled = !enabled;
        replayHandler.restartAtCurrentTime();
        return null;
    }
}
