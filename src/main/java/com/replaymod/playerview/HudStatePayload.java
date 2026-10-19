package com.replaymod.playerview;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Carrier for an encoded {@link HudStateCodec} payload, so the recorder can store it as a regular
 * custom payload packet in the replay. It is only ever written, never received over a real connection.
 */
public final class HudStatePayload implements CustomPayload {
    public static final Identifier ID = new Identifier("replaymod", "hud_state");

    private final byte[] data;

    public HudStatePayload(byte[] data) {
        this.data = data;
    }

    @Override
    public void write(PacketByteBuf buf) {
        buf.writeBytes(data);
    }

    @Override
    public Identifier id() {
        return ID;
    }
}
