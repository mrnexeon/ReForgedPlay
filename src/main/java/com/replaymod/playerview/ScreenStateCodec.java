package com.replaymod.playerview;

import net.minecraft.item.ItemStack;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.text.TextCodecs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Binary format of the {@code replaymod:screen} payload.
 *
 * <pre>
 * byte    version (currently 1)
 * byte    OPEN (full snapshot), DELTA or CLOSE
 * OPEN:   varint session, identifier kind, text title, varint n + n item stacks, item stack cursor,
 *         varint n + n varint properties, drag block
 * DELTA:  varint flags, then per flag: changed slots (varint n + n x (varint index, item stack)),
 *         cursor stack, changed properties (varint n + n x (varint index, varint value)), drag block
 * CLOSE:  nothing
 * drag:   boolean dragging, varint button, varint n + n varint slot ids
 * </pre>
 *
 * OPEN and CLOSE fully describe the state, so playback can start from either when seeking.
 */
public final class ScreenStateCodec {
    public static final int VERSION = 1;

    public static final int OPEN = 0;
    public static final int DELTA = 1;
    public static final int CLOSE = 2;

    public static final int FLAG_SLOTS = 1;
    public static final int FLAG_CURSOR_STACK = 1 << 1;
    public static final int FLAG_PROPERTIES = 1 << 2;
    public static final int FLAG_DRAG = 1 << 3;

    private ScreenStateCodec() {}

    /** Sections that differ between two states of the same menu session. */
    public static int diff(ScreenState prev, ScreenState cur) {
        int flags = 0;
        if (prev.slots.length != cur.slots.length) {
            flags |= FLAG_SLOTS;
        } else {
            for (int i = 0; i < cur.slots.length; i++) {
                if (!ItemStack.areEqual(prev.slots[i], cur.slots[i])) {
                    flags |= FLAG_SLOTS;
                    break;
                }
            }
        }
        if (!ItemStack.areEqual(prev.cursorStack, cur.cursorStack)) flags |= FLAG_CURSOR_STACK;
        if (!Arrays.equals(prev.properties, cur.properties)) flags |= FLAG_PROPERTIES;
        if (prev.dragging != cur.dragging || prev.dragButton != cur.dragButton
                || !Arrays.equals(prev.dragSlots, cur.dragSlots)) {
            flags |= FLAG_DRAG;
        }
        return flags;
    }

    public static void writeOpen(RegistryByteBuf buf, ScreenState s) {
        buf.writeByte(VERSION);
        buf.writeByte(OPEN);
        buf.writeVarInt(s.session);
        buf.writeIdentifier(s.kind);
        TextCodecs.REGISTRY_PACKET_CODEC.encode(buf, s.title);
        buf.writeVarInt(s.slots.length);
        for (ItemStack stack : s.slots) {
            ItemStack.OPTIONAL_PACKET_CODEC.encode(buf, stack);
        }
        ItemStack.OPTIONAL_PACKET_CODEC.encode(buf, s.cursorStack);
        buf.writeVarInt(s.properties.length);
        for (int p : s.properties) buf.writeVarInt(p);
        writeDrag(buf, s);
    }

    public static void writeDelta(RegistryByteBuf buf, ScreenState prev, ScreenState cur, int flags) {
        buf.writeByte(VERSION);
        buf.writeByte(DELTA);
        buf.writeVarInt(flags);
        if ((flags & FLAG_SLOTS) != 0) {
            List<Integer> changed = new ArrayList<>();
            for (int i = 0; i < cur.slots.length; i++) {
                if (i >= prev.slots.length || !ItemStack.areEqual(prev.slots[i], cur.slots[i])) changed.add(i);
            }
            buf.writeVarInt(cur.slots.length);
            buf.writeVarInt(changed.size());
            for (int i : changed) {
                buf.writeVarInt(i);
                ItemStack.OPTIONAL_PACKET_CODEC.encode(buf, cur.slots[i]);
            }
        }
        if ((flags & FLAG_CURSOR_STACK) != 0) ItemStack.OPTIONAL_PACKET_CODEC.encode(buf, cur.cursorStack);
        if ((flags & FLAG_PROPERTIES) != 0) {
            List<Integer> changed = new ArrayList<>();
            for (int i = 0; i < cur.properties.length; i++) {
                if (i >= prev.properties.length || prev.properties[i] != cur.properties[i]) changed.add(i);
            }
            buf.writeVarInt(cur.properties.length);
            buf.writeVarInt(changed.size());
            for (int i : changed) {
                buf.writeVarInt(i);
                buf.writeVarInt(cur.properties[i]);
            }
        }
        if ((flags & FLAG_DRAG) != 0) writeDrag(buf, cur);
    }

    public static void writeClose(RegistryByteBuf buf) {
        buf.writeByte(VERSION);
        buf.writeByte(CLOSE);
    }

    private static void writeDrag(RegistryByteBuf buf, ScreenState s) {
        buf.writeBoolean(s.dragging);
        buf.writeVarInt(s.dragButton);
        buf.writeVarInt(s.dragSlots.length);
        for (int id : s.dragSlots) buf.writeVarInt(id);
    }

    private static void readDrag(RegistryByteBuf buf, ScreenState s) {
        s.dragging = buf.readBoolean();
        s.dragButton = buf.readVarInt();
        int n = buf.readVarInt();
        s.dragSlots = new int[n];
        for (int i = 0; i < n; i++) s.dragSlots[i] = buf.readVarInt();
    }

    /** Whether an encoded payload fully describes the state (OPEN or CLOSE), without decoding it. */
    public static boolean isFull(byte[] data) {
        return data.length >= 2 && data[0] == VERSION && (data[1] == OPEN || data[1] == CLOSE);
    }

    /**
     * Applies one payload to {@code s}.
     *
     * @return false if the payload uses a newer format version and was skipped
     */
    public static boolean read(RegistryByteBuf buf, ScreenState s) {
        int version = buf.readByte();
        if (version != VERSION) return false;
        int type = buf.readByte();
        if (type == OPEN) {
            s.open = true;
            s.session = buf.readVarInt();
            s.kind = buf.readIdentifier();
            s.title = TextCodecs.REGISTRY_PACKET_CODEC.decode(buf);
            int n = buf.readVarInt();
            s.slots = new ItemStack[n];
            for (int i = 0; i < n; i++) s.slots[i] = ItemStack.OPTIONAL_PACKET_CODEC.decode(buf);
            s.cursorStack = ItemStack.OPTIONAL_PACKET_CODEC.decode(buf);
            int p = buf.readVarInt();
            s.properties = new int[p];
            for (int i = 0; i < p; i++) s.properties[i] = buf.readVarInt();
            readDrag(buf, s);
        } else if (type == DELTA) {
            int flags = buf.readVarInt();
            if ((flags & FLAG_SLOTS) != 0) {
                int size = buf.readVarInt();
                if (size != s.slots.length) s.slots = resize(s.slots, size);
                int n = buf.readVarInt();
                for (int k = 0; k < n; k++) {
                    int i = buf.readVarInt();
                    ItemStack stack = ItemStack.OPTIONAL_PACKET_CODEC.decode(buf);
                    if (i >= 0 && i < s.slots.length) s.slots[i] = stack;
                }
            }
            if ((flags & FLAG_CURSOR_STACK) != 0) s.cursorStack = ItemStack.OPTIONAL_PACKET_CODEC.decode(buf);
            if ((flags & FLAG_PROPERTIES) != 0) {
                int size = buf.readVarInt();
                if (size != s.properties.length) s.properties = Arrays.copyOf(s.properties, size);
                int n = buf.readVarInt();
                for (int k = 0; k < n; k++) {
                    int i = buf.readVarInt();
                    int value = buf.readVarInt();
                    if (i >= 0 && i < s.properties.length) s.properties[i] = value;
                }
            }
            if ((flags & FLAG_DRAG) != 0) readDrag(buf, s);
        } else if (type == CLOSE) {
            s.open = false;
            s.slots = new ItemStack[0];
            s.cursorStack = ItemStack.EMPTY;
            s.properties = new int[0];
            s.dragging = false;
            s.dragSlots = new int[0];
        }
        return true;
    }

    private static ItemStack[] resize(ItemStack[] slots, int size) {
        ItemStack[] r = Arrays.copyOf(slots, size);
        for (int i = slots.length; i < size; i++) r[i] = ItemStack.EMPTY;
        return r;
    }
}
