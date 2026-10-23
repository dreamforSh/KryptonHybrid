package com.xinian.KryptonHybrid.shared.network.util;

import io.netty.buffer.ByteBuf;

/**
 * Lets the compression encoders leave room for the frame length in front of their
 * output, so {@code Varint21LengthFieldPrepender} can write the length in place instead
 * of allocating a new buffer and copying the whole frame into it.
 *
 * <p>The encoder writes {@link #SIZE} marker bytes and starts its output after them
 * ({@link #reserve}). The prepender recognises the marked room ({@link #prependLength});
 * any buffer without it, or produced by another handler, takes the normal path. A
 * handler that only reads the readable bytes never sees the room.</p>
 */
public final class ReservedFrameHeader {
    /** A VarInt21 frame length takes at most three bytes. */
    public static final int SIZE = 3;
    private static final byte MARK = (byte) 0xFF;

    private ReservedFrameHeader() {}

    /** Marks {@link #SIZE} bytes at the start of an empty buffer as room for the length. */
    public static ByteBuf reserve(ByteBuf buf) {
        buf.writeByte(MARK).writeByte(MARK).writeByte(MARK);
        buf.readerIndex(SIZE);
        return buf;
    }

    /**
     * Writes the VarInt length of {@code buf}'s readable bytes directly in front of them,
     * if the buffer carries reserved room. Returns {@code false} (buffer untouched) when it
     * does not, or when the frame is too large for a three-byte length.
     */
    public static boolean prependLength(ByteBuf buf) {
        if (buf.readerIndex() != SIZE
                || buf.getByte(0) != MARK || buf.getByte(1) != MARK || buf.getByte(2) != MARK) {
            return false;
        }
        int length = buf.readableBytes();
        int lengthBytes;
        if (length < (1 << 7)) {
            lengthBytes = 1;
        } else if (length < (1 << 14)) {
            lengthBytes = 2;
        } else if (length < (1 << 21)) {
            lengthBytes = 3;
        } else {
            return false;
        }
        int index = SIZE - lengthBytes;
        int value = length;
        for (int i = index; i < SIZE - 1; i++) {
            buf.setByte(i, (value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.setByte(SIZE - 1, value);
        buf.readerIndex(index);
        return true;
    }
}
