package com.replaymod.recording.handler;

/**
 * Marks when the client is playing a sound because the server sent a sound packet.
 *
 * The client plays those with itself as the source, which looks exactly like a sound the player caused locally. The
 * server packet is already recorded, so the client-sound hook must not record it a second time.
 */
public final class ServerSoundGuard {
    private static final ThreadLocal<Boolean> HANDLING = ThreadLocal.withInitial(() -> false);

    private ServerSoundGuard() {}

    public static void enter() {
        HANDLING.set(true);
    }

    public static void exit() {
        HANDLING.set(false);
    }

    public static boolean isHandlingServerSound() {
        return HANDLING.get();
    }
}
