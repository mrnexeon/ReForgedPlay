package com.replaymod.playerview;

import com.replaymod.replaystudio.PacketData;
import com.replaymod.replaystudio.io.ReplayInputStream;
import com.replaymod.replaystudio.lib.viaversion.api.protocol.packet.State;
import com.replaymod.replaystudio.protocol.Packet;
import com.replaymod.replaystudio.protocol.PacketType;
import com.replaymod.replaystudio.replay.ReplayFile;
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static com.replaymod.core.versions.MCVer.getPacketTypeRegistry;

/**
 * Everything recorded about the recording player's view: HUD state, open menus and cursor, indexed by replay time.
 *
 * Built once per replay by a single scan of the packet stream, independent of how the replay is played back, so it
 * works in quick mode (which only rebuilds world state) and makes seeking free.
 */
public final class RecordedTracks {
    private static final Logger LOGGER = LogManager.getLogger();

    public final PayloadTimeline<HudState> hud;
    public final PayloadTimeline<ScreenState> screen;
    public final CursorTrack cursor;
    public final CameraTrack camera;

    private RecordedTracks(PayloadTimeline<HudState> hud, PayloadTimeline<ScreenState> screen, CursorTrack cursor,
                           CameraTrack camera) {
        this.hud = hud;
        this.screen = screen;
        this.cursor = cursor;
        this.camera = camera;
    }

    private static final PayloadTimeline.Codec<HudState> HUD_CODEC = new PayloadTimeline.Codec<HudState>() {
        @Override public HudState create() { return new HudState(); }
        @Override public HudState copy(HudState s) { return s.copy(); }
        @Override public boolean read(RegistryByteBuf buf, HudState s) { return HudStateCodec.read(buf, s); }
    };

    private static final PayloadTimeline.Codec<ScreenState> SCREEN_CODEC = new PayloadTimeline.Codec<ScreenState>() {
        @Override public ScreenState create() { return new ScreenState(); }
        @Override public ScreenState copy(ScreenState s) { return s.copy(); }
        @Override public boolean read(RegistryByteBuf buf, ScreenState s) { return ScreenStateCodec.read(buf, s); }
    };

    /**
     * Scans the replay. Blocking; call from a background thread.
     *
     * @param cancelled checked between packets so a closed replay stops the scan early
     * @return the tracks, or {@code null} if cancelled
     */
    public static RecordedTracks build(ReplayFile replayFile, BooleanSupplier cancelled) throws IOException {
        Raw hud = new Raw(), screen = new Raw();
        List<int[]> cursorEntries = new ArrayList<>();
        List<float[]> cursorPositions = new ArrayList<>();
        List<Integer> cameraTimes = new ArrayList<>();
        List<CameraSample> cameraSamples = new ArrayList<>();

        try (ReplayInputStream in = replayFile.getPacketData(getPacketTypeRegistry(State.LOGIN))) {
            PacketData data;
            while ((data = in.readPacket()) != null) {
                try {
                    if (cancelled.getAsBoolean()) return null;
                    Packet packet = data.getPacket();
                    if (packet.getType() != PacketType.PluginMessage) continue;

                    PacketByteBuf buf = new PacketByteBuf(Unpooled.wrappedBuffer(packet.getBuf().nioBuffer()));
                    Identifier channel = buf.readIdentifier();
                    int time = (int) data.getTime();
                    if (HudStatePayload.CHANNEL.equals(channel)) {
                        byte[] bytes = readRest(buf);
                        hud.add(time, bytes, HudStateCodec.isFull(bytes));
                    } else if (PlayerViewPayloads.SCREEN_CHANNEL.equals(channel)) {
                        byte[] bytes = readRest(buf);
                        screen.add(time, bytes, ScreenStateCodec.isFull(bytes));
                    } else if (PlayerViewPayloads.CURSOR_CHANNEL.equals(channel)) {
                        if (buf.readByte() != 1) continue; // newer cursor format
                        float x = buf.readFloat(), y = buf.readFloat();
                        int buttons = buf.readByte();
                        cursorEntries.add(new int[]{time, buttons});
                        cursorPositions.add(new float[]{x, y});
                    } else if (PlayerViewPayloads.CAMERA_CHANNEL.equals(channel)) {
                        CameraSample sample = CameraSample.decode(buf);
                        if (sample != null) {
                            cameraTimes.add(time);
                            cameraSamples.add(sample);
                        }
                    }
                } catch (Exception e) {
                    LOGGER.debug("Skipping unreadable packet while indexing the recording player's view", e);
                } finally {
                    data.release();
                }
            }
        }

        LOGGER.info("Indexed recording player's view: {} HUD states, {} menu states, {} cursor samples, {} camera samples",
                hud.times.size(), screen.times.size(), cursorEntries.size(), cameraTimes.size());
        return new RecordedTracks(
                new PayloadTimeline<>(HUD_CODEC, hud.times, hud.payloads, HudStateCodec::isFull),
                new PayloadTimeline<>(SCREEN_CODEC, screen.times, screen.payloads, ScreenStateCodec::isFull),
                new CursorTrack(cursorEntries, cursorPositions),
                new CameraTrack(cameraTimes, cameraSamples));
    }

    private static byte[] readRest(PacketByteBuf buf) {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        return bytes;
    }

    /** Raw payloads of one channel, in recording order. */
    private static final class Raw {
        final List<Integer> times = new ArrayList<>();
        final List<byte[]> payloads = new ArrayList<>();

        void add(int time, byte[] bytes, boolean full) {
            int last = times.size() - 1;
            if (last >= 0 && times.get(last) == time && !full) {
                // Several deltas in the same millisecond: merge them into one entry, in order
                byte[] prev = payloads.get(last);
                byte[] merged = new byte[prev.length + bytes.length];
                System.arraycopy(prev, 0, merged, 0, prev.length);
                System.arraycopy(bytes, 0, merged, prev.length, bytes.length);
                payloads.set(last, merged);
            } else {
                times.add(time);
                payloads.add(bytes);
            }
        }
    }
}
