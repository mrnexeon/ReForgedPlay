package com.replaymod.playerview;

import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Payload carriers for the menu ({@code replaymod:screen}) and cursor ({@code replaymod:cursor}) tracks.
 * Like {@link HudStatePayload}, they hold opaque bytes which are only ever written into replays by the recorder.
 */
public final class PlayerViewPayloads {
    public static final Identifier SCREEN_CHANNEL = Identifier.of("replaymod", "screen");
    public static final Identifier CURSOR_CHANNEL = Identifier.of("replaymod", "cursor");

    private PlayerViewPayloads() {}

    /** Whether a custom payload channel belongs to the recording player's view and must not reach the replay client. */
    public static boolean isPlayerViewChannel(Identifier channel) {
        return HudStatePayload.CHANNEL.equals(channel) || SCREEN_CHANNEL.equals(channel) || CURSOR_CHANNEL.equals(channel);
    }

    private static byte[] readRest(PacketByteBuf buf) {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        return bytes;
    }

    public record Screen(byte[] data) implements CustomPayload {
        public static final CustomPayload.Id<Screen> ID = new CustomPayload.Id<>(SCREEN_CHANNEL);
        public static final PacketCodec<? super PacketByteBuf, Screen> CODEC = PacketCodec.ofStatic(
                (buf, payload) -> buf.writeBytes(payload.data()),
                buf -> new Screen(readRest(buf)));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public record Cursor(byte[] data) implements CustomPayload {
        public static final CustomPayload.Id<Cursor> ID = new CustomPayload.Id<>(CURSOR_CHANNEL);
        public static final PacketCodec<? super PacketByteBuf, Cursor> CODEC = PacketCodec.ofStatic(
                (buf, payload) -> buf.writeBytes(payload.data()),
                buf -> new Cursor(readRest(buf)));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }

        /**
         * Cursor format: byte version (1), float x, float y (GUI units relative to the menu's top-left corner),
         * byte pressed mouse buttons (bit 0 left, bit 1 right, bit 2 middle).
         */
        public static Cursor of(float x, float y, int buttons) {
            PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer(10));
            try {
                buf.writeByte(1);
                buf.writeFloat(x);
                buf.writeFloat(y);
                buf.writeByte(buttons);
                return new Cursor(readRest(buf));
            } finally {
                buf.release();
            }
        }
    }
}
