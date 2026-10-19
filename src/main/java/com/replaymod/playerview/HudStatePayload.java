package com.replaymod.playerview;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Carrier for an encoded {@link HudStateCodec} payload, so the recorder can store it as a regular custom payload
 * packet in the replay. The bytes are opaque here: they are decoded later with the replay's registries, which item
 * stacks need since 1.20.5.
 */
public record HudStatePayload(byte[] data) implements CustomPayload {
    public static final Identifier CHANNEL = Identifier.of("replaymod", "hud_state");
    public static final CustomPayload.Id<HudStatePayload> ID = new CustomPayload.Id<>(CHANNEL);
    public static final PacketCodec<? super PacketByteBuf, HudStatePayload> CODEC = PacketCodec.ofStatic(
            (buf, payload) -> buf.writeBytes(payload.data()),
            buf -> {
                byte[] bytes = new byte[buf.readableBytes()];
                buf.readBytes(bytes);
                return new HudStatePayload(bytes);
            }
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
