package com.xinian.KryptonHybrid.shared.network.util;

import io.netty.buffer.ByteBuf;
import net.minecraft.util.Mth;

/**
 * Optimized VarInt utilities ??both size calculation and read/write fast paths.
 *
 * <h3>Size calculation</h3>
 * <p>Maps VarInt byte sizes to a lookup table corresponding to the number of bits in
 * the integer, from zero to 32.  Replaces the vanilla loop in
 * {@link net.minecraft.network.VarInt#getByteSize}.</p>
 *
 * <h3>Read fast path ({@link #readVarInt})</h3>
 * <p>When at least 4 bytes are readable, reads an {@code int} in little-endian order
 * and uses branchless bit-twiddling to extract the VarInt value in a single pass.
 * This is the same algorithm used in the Varint21FrameDecoder fast path (ported from
 * <a href="https://github.com/netty/netty/pull/14050">netty#14050</a>), applied to the
 * general {@code VarInt.read(ByteBuf)} hot path.</p>
 *
 * <h4>How it works</h4>
 * <ol>
 *   <li>Read 4 bytes as a little-endian int ({@code getIntLE}) ??no reader index
 *       change yet.</li>
 *   <li>Compute {@code atStop = ~raw & 0x808080}: each byte's MSB (continuation bit)
 *       is inverted.  The lowest set bit in {@code atStop} marks the first
 *       non-continuation byte ??the final byte of the VarInt.</li>
 *   <li>If {@code atStop == 0}, all 4 bytes have continuation bits set, so the VarInt
 *       is 5 bytes long ??fall through to the slow path (handles the 5th byte and
 *       the "VarInt too big" error).</li>
 *   <li>Advance the reader index by {@code (trailingZeros(atStop) + 1) / 8} ??the
 *       exact number of VarInt bytes consumed.</li>
 *   <li>Mask out bytes beyond the VarInt using
 *       {@code raw & (atStop ^ (atStop - 1))}.</li>
 *   <li>Strip continuation bits with two rounds of merge-shift:
 *       <ul>
 *         <li>Round 1: remove bit 7 from bytes 0 and 2 ?? *             {@code (p & 0x007F007F) | ((p & 0x7F00) >> 1)}</li>
 *         <li>Round 2: remove gap between the two 14-bit halves ?? *             {@code (p & 0x3FFF) | ((p & 0x3FFF0000) >> 2)}</li>
 *       </ul></li>
 * </ol>
 *
 * <p>The result is a fully decoded VarInt with zero branches for 1?? byte values
 * (the vast majority of Minecraft traffic).</p>
 */
public class VarIntUtil {
    public static final int MASK_7_BITS = 0xFFFFFFFF << 7;
    public static final int MASK_14_BITS = 0xFFFFFFFF << 14;
    public static final int MASK_21_BITS = 0xFFFFFFFF << 21;
    public static final int MASK_28_BITS = 0xFFFFFFFF << 28;
    private static final int[] VARINT_EXACT_BYTE_LENGTHS = new int[33];

    static {
        for (int i = 0; i <= 32; ++i) {
            VARINT_EXACT_BYTE_LENGTHS[i] = Mth.ceil((31d - (i - 1)) / 7d);
        }
        VARINT_EXACT_BYTE_LENGTHS[32] = 1; // Special case for 0.
    }

    public static int getVarIntLength(int value) {
        return VARINT_EXACT_BYTE_LENGTHS[Integer.numberOfLeadingZeros(value)];
    }

    /**
     * Reads a VarInt from the given buffer using a branchless bit-twiddling fast path
     * when at least 4 bytes are readable.
     *
     * <p>Falls back to a byte-at-a-time loop for the rare cases where fewer than 4
     * bytes are available or the VarInt is 5 bytes long.</p>
     *
     * @param buffer the buffer to read from
     * @return the decoded VarInt value
     * @throws RuntimeException if the VarInt exceeds 5 bytes
     */
    public static int readVarInt(ByteBuf buffer) {

        if (buffer.readableBytes() >= 4) {
            int raw = buffer.getIntLE(buffer.readerIndex());


            int atStop = ~raw & 0x808080;
            if (atStop != 0) {

                int bitsToKeep = Integer.numberOfTrailingZeros(atStop) + 1;
                buffer.skipBytes(bitsToKeep >> 3);


                int preserved = raw & (atStop ^ (atStop - 1));


                preserved = (preserved & 0x007F007F) | ((preserved & 0x00007F00) >> 1);

                preserved = (preserved & 0x00003FFF) | ((preserved & 0x3FFF0000) >> 2);
                return preserved;
            }

        }


        return readVarIntSlow(buffer);
    }

    /**
     * Byte-at-a-time VarInt reader for the slow path.
     * Handles buffers with fewer than 4 readable bytes and 5-byte VarInts.
     */
    private static int readVarIntSlow(ByteBuf buffer) {
        int value = 0;
        int bytesRead = 0;
        byte b;
        do {
            b = buffer.readByte();
            value |= (b & 0x7F) << (bytesRead++ * 7);
            if (bytesRead > 5) {
                throw new RuntimeException("VarInt too big");
            }
        } while ((b & 0x80) != 0);
        return value;
    }
}

