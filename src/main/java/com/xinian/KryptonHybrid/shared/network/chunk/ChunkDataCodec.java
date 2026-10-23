package com.xinian.KryptonHybrid.shared.network.chunk;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.network.FriendlyByteBuf;
import com.xinian.KryptonHybrid.shared.network.util.VarIntUtil;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;

import java.util.Set;
import java.util.function.IntUnaryOperator;

/**
 * Codec for Krypton's optimised chunk-data wire format.
 *
 * <h3>Heightmap binary format (replaces NBT)</h3>
 * <pre>
 *   VarInt  entry_count
 *   Per entry:
 *     UTF-8  key              (FriendlyByteBuf.writeUtf / readUtf)
 *     VarInt long_count
 *     VarLong[] ZigZag(XOR-delta)  (first long raw, subsequent = raw[i] ^ raw[i-1])
 * </pre>
 * <p>XOR-delta exploits the spatial correlation of heightmap columns (adjacent columns
 * have similar heights), producing many near-zero longs that compress significantly
 * better under the connection-level Zstd/ZLIB layer.  The binary format itself removes
 * ~40 bytes of NBT structural overhead per chunk.</p>
 *
 * <h3>Biome delta encoding</h3>
 * <p>Section biome data is extracted from the vanilla section buffer and written as a
 * compact per-section stream.  Single-value biome sections (the common case: flat terrain,
 * ocean, plains) are encoded as 2 bytes ({@code flag + VarInt(biomeId)}) instead of the
 * vanilla PalettedContainer format ({@code byte(0) + VarInt(id) + VarInt(0)} = 3+ bytes).
 * More importantly, because biome data is grouped together rather than interleaved with
 * block-state data, the connection-level compressor can exploit cross-section biome
 * redundancy far more effectively.</p>
 *
 * <h3>Byte-aligned block data</h3>
 * <p>Vanilla packs block-state palette indices into longs, so indices wider than 4 bits
 * straddle byte boundaries and the same block pattern turns into different bytes
 * depending on where it starts; the compressor finds few matches. With
 * {@link #ALIGNED_BLOCKS_MARKER} the block data of each section is rewritten before
 * compression ({@link #alignBlocks}) and restored on the client ({@link #unalignBlocks}):</p>
 * <pre>
 *   bits 0, &gt; 8      unchanged (single value, global palette)
 *   bits 1 - 4       VarInt(long_count) + longs little-endian (indices in byte order)
 *   bits 5 - 8       4096 bytes, one palette index each (no length)
 * </pre>
 * <p>Short block count and palette stay as in vanilla.</p>
 *
 * <h3>Client-computed heightmaps</h3>
 * <p>For clients that compute them, the server writes an empty heightmap list; the client
 * derives {@code WORLD_SURFACE} and {@code MOTION_BLOCKING} from the block data
 * ({@link #computeHeightmaps}), as vanilla does for chunks loaded without them.</p>
 */
public final class ChunkDataCodec {

    /** Chunk data in Krypton's format with vanilla block data. Also marks Krypton light data. */
    public static final int MARKER = 0x4B;
    /** Chunk data in Krypton's format with byte-aligned block data. */
    public static final int ALIGNED_BLOCKS_MARKER = 0x4C;

    /** Block states per section. */
    private static final int SECTION_ENTRIES = 4096;

    /** {@link #computeHeightmaps} flag: the block state counts for {@code WORLD_SURFACE}. */
    public static final int HEIGHTMAP_WORLD_SURFACE = 1;
    /** {@link #computeHeightmaps} flag: the block state counts for {@code MOTION_BLOCKING}. */
    public static final int HEIGHTMAP_MOTION_BLOCKING = 2;

    /** Marker for a single-value (uniform) biome section. */
    public static final byte BIOME_SINGLE_VALUE = 0x01;
    /** Marker for a multi-value (raw PalettedContainer bytes) biome section. */
    public static final byte BIOME_RAW = 0x00;

    /**
     * Threshold for biome PalettedContainer: bits-per-entry values above this use
     * the global palette (no palette data written).
     * See {@link net.minecraft.world.level.chunk.PalettedContainer.Strategy#SECTION_BIOMES}.
     */
    private static final int BIOME_GLOBAL_THRESHOLD = 3;

    /**
     * Threshold for block-state PalettedContainer: bits-per-entry values above this
     * use the global palette (no palette data written).
     * See {@link net.minecraft.world.level.chunk.PalettedContainer.Strategy#SECTION_STATES}.
     */
    private static final int STATES_GLOBAL_THRESHOLD = 8;

    private ChunkDataCodec() {}

    // ======================================================================
    //  Heightmap encoding
    // ======================================================================

    /**
     * Writes heightmaps in Krypton's compact binary format with XOR-delta encoding.
     *
     * @param buf        destination buffer
     * @param heightmaps the vanilla {@link CompoundTag} containing heightmap entries
     */
    public static void writeHeightmaps(FriendlyByteBuf buf, CompoundTag heightmaps) {
        Set<String> keys = heightmaps.getAllKeys();
        buf.writeVarInt(keys.size());
        for (String key : keys) {
            buf.writeUtf(key);
            long[] data = heightmaps.getLongArray(key);
            buf.writeVarInt(data.length);
            // XOR-delta + ZigZag VarLong: correlated heightmaps usually produce
            // small deltas, so avoid paying a fixed 8 bytes per long.
            long prev = 0;
            for (long v : data) {
                buf.writeVarLong(zigZag(v ^ prev));
                prev = v;
            }
        }
    }

    /**
     * Reads heightmaps from Krypton's compact binary format with XOR-delta decoding.
     *
     * @param buf source buffer
     * @return a {@link CompoundTag} identical to the vanilla heightmaps tag
     */
    public static CompoundTag readHeightmaps(FriendlyByteBuf buf) {
        CompoundTag tag = new CompoundTag();
        int count = buf.readVarInt();
        for (int i = 0; i < count; i++) {
            String key = buf.readUtf();
            int len = buf.readVarInt();
            long[] data = new long[len];
            // XOR-delta decode: raw[0] = delta[0], raw[i] = delta[i] ^ raw[i-1]
            long prev = 0;
            for (int j = 0; j < len; j++) {
                long delta = unZigZag(buf.readVarLong());
                data[j] = delta ^ prev;
                prev = data[j];
            }
            tag.put(key, new LongArrayTag(data));
        }
        return tag;
    }

    private static long zigZag(long value) {
        return (value << 1) ^ (value >> 63);
    }

    private static long unZigZag(long value) {
        return (value >>> 1) ^ -(value & 1L);
    }

    // ======================================================================
    //  Client-computed heightmaps
    // ======================================================================

    /**
     * Computes the {@code WORLD_SURFACE} and {@code MOTION_BLOCKING} heightmaps from a vanilla
     * section buffer (what {@link #mergeSectionBuffer} returns): per column, one above the
     * highest block whose state counts for the heightmap, relative to the bottom of the world,
     * or 0 when none does. The result is in vanilla's raw format ({@code Heightmap#getRawData}):
     * {@code ceillog2(height + 1)} bits per column, index {@code x + z * 16}, no value spanning
     * two longs.
     *
     * @param sections     vanilla section buffer
     * @param sectionCount number of sections; the world is {@code sectionCount * 16} blocks high
     * @param stateFlags   {@code HEIGHTMAP_*} flags of a global block-state id
     * @return {@code {worldSurface, motionBlocking}}
     */
    public static long[][] computeHeightmaps(byte[] sections, int sectionCount, IntUnaryOperator stateFlags) {
        ByteBuf buf = Unpooled.wrappedBuffer(sections);
        int[] starts = new int[sectionCount];
        for (int s = 0; s < sectionCount; s++) {
            starts[s] = buf.readerIndex();
            buf.skipBytes(2); // nonEmptyBlockCount
            skipPalettedContainer(buf, STATES_GLOBAL_THRESHOLD);
            skipPalettedContainer(buf, BIOME_GLOBAL_THRESHOLD);
        }

        Int2IntOpenHashMap flagsById = new Int2IntOpenHashMap();
        flagsById.defaultReturnValue(-1);
        IntUnaryOperator flags = id -> {
            int f = flagsById.get(id);
            if (f < 0) {
                f = stateFlags.applyAsInt(id);
                flagsById.put(id, f);
            }
            return f;
        };

        int[] worldSurface = new int[256];
        int[] motionBlocking = new int[256];
        int unresolved = 2 * 256;
        // Top-down: the first block a heightmap counts sets its column.
        for (int s = sectionCount - 1; s >= 0 && unresolved > 0; s--) {
            buf.readerIndex(starts[s] + 2);
            int bits = buf.readUnsignedByte();
            int[] paletteFlags;
            if (bits == 0) {
                paletteFlags = new int[] {flags.applyAsInt(VarIntUtil.readVarInt(buf))};
            } else if (bits <= STATES_GLOBAL_THRESHOLD) {
                paletteFlags = new int[VarIntUtil.readVarInt(buf)];
                for (int i = 0; i < paletteFlags.length; i++) {
                    paletteFlags[i] = flags.applyAsInt(VarIntUtil.readVarInt(buf));
                }
            } else {
                paletteFlags = null; // global palette: the indices are state ids
            }
            if (paletteFlags != null && !anySet(paletteFlags)) {
                continue; // nothing in this section counts, e.g. all air
            }

            long[] data = new long[VarIntUtil.readVarInt(buf)];
            for (int i = 0; i < data.length; i++) {
                data[i] = buf.readLong();
            }
            int valuesPerLong = bits == 0 ? 0 : 64 / bits;
            long mask = (1L << bits) - 1;
            for (int y = 15; y >= 0 && unresolved > 0; y--) {
                int height = s * 16 + y + 1;
                for (int column = 0; column < 256; column++) {
                    if (worldSurface[column] != 0 && motionBlocking[column] != 0) {
                        continue;
                    }
                    int f;
                    if (bits == 0) {
                        f = paletteFlags[0];
                    } else {
                        int i = (y << 8) | column; // (y * 16 + z) * 16 + x, column = z * 16 + x
                        int value = (int) ((data[i / valuesPerLong] >>> ((i % valuesPerLong) * bits)) & mask);
                        f = paletteFlags == null ? flags.applyAsInt(value)
                                : value < paletteFlags.length ? paletteFlags[value] : 0;
                    }
                    if ((f & HEIGHTMAP_WORLD_SURFACE) != 0 && worldSurface[column] == 0) {
                        worldSurface[column] = height;
                        unresolved--;
                    }
                    if ((f & HEIGHTMAP_MOTION_BLOCKING) != 0 && motionBlocking[column] == 0) {
                        motionBlocking[column] = height;
                        unresolved--;
                    }
                }
            }
        }

        int heightBits = 32 - Integer.numberOfLeadingZeros(sectionCount * 16); // ceillog2(height + 1)
        return new long[][] {packColumns(worldSurface, heightBits), packColumns(motionBlocking, heightBits)};
    }

    private static boolean anySet(int[] flags) {
        for (int f : flags) {
            if (f != 0) {
                return true;
            }
        }
        return false;
    }

    /** Packs 256 column values like vanilla's {@code SimpleBitStorage}. */
    private static long[] packColumns(int[] values, int bits) {
        int valuesPerLong = 64 / bits;
        long[] packed = new long[(values.length + valuesPerLong - 1) / valuesPerLong];
        for (int i = 0; i < values.length; i++) {
            packed[i / valuesPerLong] |= (long) values[i] << ((i % valuesPerLong) * bits);
        }
        return packed;
    }

    // ======================================================================
    //  PalettedContainer scanner (skips over serialised containers)
    // ======================================================================

    /**
     * Advances the reader index of {@code buf} past one serialised
     * {@link net.minecraft.world.level.chunk.PalettedContainer}, returning the
     * number of bytes consumed.
     *
     * @param buf             the buffer positioned at the start of a PalettedContainer
     * @param globalThreshold bits-per-entry above which the global palette is used
     *                        (8 for block states, 3 for biomes)
     * @return bytes consumed
     */
    public static int skipPalettedContainer(ByteBuf buf, int globalThreshold) {
        int start = buf.readerIndex();
        int bpe = buf.readUnsignedByte();

        if (bpe == 0) {
            // SingleValuePalette: one VarInt (the palette entry)
            VarIntUtil.readVarInt(buf);
        } else if (bpe <= globalThreshold) {
            // LinearPalette or HashMapPalette: VarInt(size) + size ? VarInt(entry)
            int paletteSize = VarIntUtil.readVarInt(buf);
            for (int i = 0; i < paletteSize; i++) {
                VarIntUtil.readVarInt(buf);
            }
        }
        // else: GlobalPalette ??no palette data

        // Storage: VarInt(longCount) + longCount ? 8 bytes
        int longCount = VarIntUtil.readVarInt(buf);
        buf.skipBytes(longCount * 8);

        return buf.readerIndex() - start;
    }

    // ======================================================================
    //  Byte-aligned block data
    // ======================================================================

    /**
     * Rewrites a blocks-only buffer (see {@link #splitSectionBuffer}) into the byte-aligned
     * layout. Returns {@code false}, with {@code out} partly written, when a section does
     * not have the expected layout; the caller then sends the vanilla block data.
     *
     * @param blocks       blocks-only data, read from its reader index (consumed)
     * @param sectionCount number of sections
     * @param out          receives the byte-aligned data
     */
    public static boolean alignBlocks(ByteBuf blocks, int sectionCount, ByteBuf out) {
        for (int s = 0; s < sectionCount; s++) {
            out.writeShort(blocks.readShort()); // nonEmptyBlockCount
            int bits = blocks.readUnsignedByte();
            out.writeByte(bits);
            copyPalette(blocks, out, bits);

            int longCount = VarIntUtil.readVarInt(blocks);
            if (bits == 0 || bits > STATES_GLOBAL_THRESHOLD) {
                VarIntUtil.writeVarInt(out, longCount);
                out.writeBytes(blocks, longCount * 8);
            } else if (bits <= 4) {
                VarIntUtil.writeVarInt(out, longCount);
                for (int i = 0; i < longCount; i++) {
                    out.writeLongLE(blocks.readLong());
                }
            } else {
                if (longCount != longsFor(bits)) {
                    return false;
                }
                int valuesPerLong = 64 / bits;
                long mask = (1L << bits) - 1;
                long word = 0;
                for (int i = 0; i < SECTION_ENTRIES; i++) {
                    int slot = i % valuesPerLong;
                    if (slot == 0) {
                        word = blocks.readLong();
                    }
                    out.writeByte((int) ((word >>> (slot * bits)) & mask));
                }
            }
        }
        return true;
    }

    /**
     * Restores vanilla block data from the byte-aligned layout written by {@link #alignBlocks}.
     *
     * @param aligned      byte-aligned data, read from its reader index (consumed)
     * @param sectionCount number of sections
     * @param out          receives the vanilla blocks-only data
     * @throws IllegalArgumentException if an index does not fit the section's bit width
     */
    public static void unalignBlocks(ByteBuf aligned, int sectionCount, ByteBuf out) {
        for (int s = 0; s < sectionCount; s++) {
            out.writeShort(aligned.readShort()); // nonEmptyBlockCount
            int bits = aligned.readUnsignedByte();
            out.writeByte(bits);
            copyPalette(aligned, out, bits);

            if (bits == 0 || bits > STATES_GLOBAL_THRESHOLD) {
                int longCount = VarIntUtil.readVarInt(aligned);
                VarIntUtil.writeVarInt(out, longCount);
                out.writeBytes(aligned, longCount * 8);
            } else if (bits <= 4) {
                int longCount = VarIntUtil.readVarInt(aligned);
                VarIntUtil.writeVarInt(out, longCount);
                for (int i = 0; i < longCount; i++) {
                    out.writeLong(aligned.readLongLE());
                }
            } else {
                int valuesPerLong = 64 / bits;
                int longCount = longsFor(bits);
                VarIntUtil.writeVarInt(out, longCount);
                long word = 0;
                for (int i = 0; i < SECTION_ENTRIES; i++) {
                    int value = aligned.readUnsignedByte();
                    if (value >>> bits != 0) {
                        throw new IllegalArgumentException(
                                "Block index " + value + " does not fit in " + bits + " bits");
                    }
                    int slot = i % valuesPerLong;
                    word |= (long) value << (slot * bits);
                    if (slot == valuesPerLong - 1 || i == SECTION_ENTRIES - 1) {
                        out.writeLong(word);
                        word = 0;
                    }
                }
            }
        }
    }

    /** Copies a block-state palette: one entry for a single value, a list for 1 - 8 bits. */
    private static void copyPalette(ByteBuf src, ByteBuf out, int bits) {
        if (bits == 0) {
            VarIntUtil.writeVarInt(out, VarIntUtil.readVarInt(src));
        } else if (bits <= STATES_GLOBAL_THRESHOLD) {
            int paletteSize = VarIntUtil.readVarInt(src);
            VarIntUtil.writeVarInt(out, paletteSize);
            for (int i = 0; i < paletteSize; i++) {
                VarIntUtil.writeVarInt(out, VarIntUtil.readVarInt(src));
            }
        }
    }

    /** Longs vanilla uses for a section's indices at {@code bits} bits (no index spans two longs). */
    private static int longsFor(int bits) {
        int valuesPerLong = 64 / bits;
        return (SECTION_ENTRIES + valuesPerLong - 1) / valuesPerLong;
    }

    // ======================================================================
    //  Section-buffer splitting / merging
    // ======================================================================

    /**
     * Splits a vanilla section buffer into a blocks-only buffer and a compact biome
     * stream.  The section buffer format per section is:
     * {@code short(nonEmptyBlockCount) + PalettedContainer<BlockState> + PalettedContainer<Biome>}.
     *
     * @param vanillaBuffer the full vanilla section buffer
     * @param sectionCount  number of sections in the chunk
     * @param blocksOut     receives the blocks-only data (short + block-state PC per section)
     * @param biomesOut     receives the biome stream (per section: flag + data)
     */
    public static void splitSectionBuffer(byte[] vanillaBuffer, int sectionCount,
                                          FriendlyByteBuf blocksOut, FriendlyByteBuf biomesOut) {
        ByteBuf src = Unpooled.wrappedBuffer(vanillaBuffer);
        try {
            for (int s = 0; s < sectionCount; s++) {
                // ---- Block data: short + PalettedContainer<BlockState> ----
                int blockStart = src.readerIndex();
                src.skipBytes(2); // nonEmptyBlockCount (short)
                skipPalettedContainer(src, STATES_GLOBAL_THRESHOLD);
                int blockSize = src.readerIndex() - blockStart;
                // Copy block data to blocksOut
                blocksOut.writeBytes(vanillaBuffer, blockStart, blockSize);

                // ---- Biome data: PalettedContainer<Biome> ----
                int biomeStart = src.readerIndex();
                int biomeBpe = src.getUnsignedByte(biomeStart); // peek

                if (biomeBpe == 0) {
                    // Single-value biome: byte(0) + VarInt(biomeId) + VarInt(0)
                    src.readByte(); // skip bpe=0
                    int biomeId = VarIntUtil.readVarInt(src);
                    VarIntUtil.readVarInt(src); // skip storage length (always 0)
                    biomesOut.writeByte(BIOME_SINGLE_VALUE);
                    biomesOut.writeVarInt(biomeId);
                } else {
                    // Multi-value biome: copy raw PalettedContainer bytes
                    skipPalettedContainer(src, BIOME_GLOBAL_THRESHOLD);
                    int biomeSize = src.readerIndex() - biomeStart;
                    src.readerIndex(biomeStart); // reset to copy
                    biomesOut.writeByte(BIOME_RAW);
                    biomesOut.writeVarInt(biomeSize);
                    byte[] biomeBytes = new byte[biomeSize];
                    src.readBytes(biomeBytes);
                    biomesOut.writeBytes(biomeBytes);
                }
            }
        } finally {
            src.release();
        }
    }

    /**
     * Merges a blocks-only buffer and a compact biome stream back into a vanilla
     * section buffer suitable for {@code LevelChunkSection.read()}.
     *
     * @param blocksData    blocks-only section data
     * @param biomesData    compact biome stream
     * @param sectionCount  number of sections
     * @return the reconstructed vanilla section buffer as a byte[]
     */
    public static byte[] mergeSectionBuffer(byte[] blocksData, byte[] biomesData, int sectionCount) {
        ByteBuf blocksSrc = Unpooled.wrappedBuffer(blocksData);
        ByteBuf biomesSrc = Unpooled.wrappedBuffer(biomesData);
        FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer(blocksData.length + sectionCount * 8));

        try {
            for (int s = 0; s < sectionCount; s++) {
                // ---- Block data ----
                int blockStart = blocksSrc.readerIndex();
                blocksSrc.skipBytes(2); // short
                skipPalettedContainer(blocksSrc, STATES_GLOBAL_THRESHOLD);
                int blockSize = blocksSrc.readerIndex() - blockStart;
                blocksSrc.readerIndex(blockStart);
                out.writeBytes(blocksSrc, blockSize);

                // ---- Biome data ----
                byte biomeFlag = biomesSrc.readByte();
                if (biomeFlag == BIOME_SINGLE_VALUE) {
                    int biomeId = VarIntUtil.readVarInt(biomesSrc);
                    // Reconstruct single-value PalettedContainer:
                    // byte(0) + VarInt(biomeId) + VarInt(0)
                    out.writeByte(0);
                    out.writeVarInt(biomeId);
                    out.writeVarInt(0);
                } else {
                    // BIOME_RAW: VarInt(length) + raw bytes
                    int biomeLen = VarIntUtil.readVarInt(biomesSrc);
                    out.writeBytes(biomesSrc, biomeLen);
                }
            }

            byte[] result = new byte[out.readableBytes()];
            out.readBytes(result);
            return result;
        } finally {
            blocksSrc.release();
            biomesSrc.release();
            out.release();
        }
    }
}

