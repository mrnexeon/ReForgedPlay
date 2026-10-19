package com.replaymod.playerview;

import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;

/**
 * What the recording player's camera showed in one frame.
 *
 * <pre>
 * replaymod:camera payload: byte version (1), double x, y, z, float yaw, pitch, fov, bobPhase, bobAmount, nausea,
 * attackCooldown, varint frozenTicks, byte flags (bit 0: first-person view of the player themselves)
 * </pre>
 */
public final class CameraSample {
    public static final int VERSION = 1;
    public static final int FLAG_FIRST_PERSON = 1;

    public final double x, y, z;
    public final float yaw, pitch;
    /** The world field of view in degrees, as {@code GameRenderer.getFov} returned it. */
    public final float fov;
    /** View bobbing inputs: walk phase and stride amount, as {@code GameRenderer.bobView} computes them. */
    public final float bobPhase, bobAmount;
    /** Nausea and nether portal swirl intensity. */
    public final float nausea;
    public final float attackCooldown;
    public final int frozenTicks;
    public final int flags;

    public CameraSample(double x, double y, double z, float yaw, float pitch, float fov, float bobPhase,
                        float bobAmount, float nausea, float attackCooldown, int frozenTicks, int flags) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.fov = fov;
        this.bobPhase = bobPhase;
        this.bobAmount = bobAmount;
        this.nausea = nausea;
        this.attackCooldown = attackCooldown;
        this.frozenTicks = frozenTicks;
        this.flags = flags;
    }

    public boolean isFirstPerson() {
        return (flags & FLAG_FIRST_PERSON) != 0;
    }

    /** Whether two samples are close enough that recording the second adds nothing. */
    public boolean sameAs(CameraSample o) {
        return o != null && x == o.x && y == o.y && z == o.z && yaw == o.yaw && pitch == o.pitch && fov == o.fov
                && bobPhase == o.bobPhase && bobAmount == o.bobAmount && nausea == o.nausea
                && attackCooldown == o.attackCooldown && frozenTicks == o.frozenTicks && flags == o.flags;
    }

    public byte[] encode() {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer(64));
        try {
            buf.writeByte(VERSION);
            buf.writeDouble(x);
            buf.writeDouble(y);
            buf.writeDouble(z);
            buf.writeFloat(yaw);
            buf.writeFloat(pitch);
            buf.writeFloat(fov);
            buf.writeFloat(bobPhase);
            buf.writeFloat(bobAmount);
            buf.writeFloat(nausea);
            buf.writeFloat(attackCooldown);
            buf.writeVarInt(frozenTicks);
            buf.writeByte(flags);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    /** Decodes a payload, or returns null for a newer format version. */
    public static CameraSample decode(PacketByteBuf buf) {
        if (buf.readByte() != VERSION) return null;
        return new CameraSample(buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readFloat(), buf.readFloat(), buf.readVarInt(), buf.readByte());
    }
}
