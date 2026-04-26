package com.xinian.KryptonHybrid.shared.network.chunk;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;

import java.util.Set;

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
 */
public final class ChunkDataCodec {

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
            VarInt.read(buf);
        } else if (bpe <= globalThreshold) {
            // LinearPalette or HashMapPalette: VarInt(size) + size × VarInt(entry)
            int paletteSize = VarInt.read(buf);
            for (int i = 0; i < paletteSize; i++) {
                VarInt.read(buf);
            }
        }
        // else: GlobalPalette — no palette data

        // Storage: VarInt(longCount) + longCount × 8 bytes
        int longCount = VarInt.read(buf);
        buf.skipBytes(longCount * 8);

        return buf.readerIndex() - start;
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
                    int biomeId = VarInt.read(src);
                    VarInt.read(src); // skip storage length (always 0)
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
                    int biomeId = VarInt.read(biomesSrc);
                    // Reconstruct single-value PalettedContainer:
                    // byte(0) + VarInt(biomeId) + VarInt(0)
                    out.writeByte(0);
                    out.writeVarInt(biomeId);
                    out.writeVarInt(0);
                } else {
                    // BIOME_RAW: VarInt(length) + raw bytes
                    int biomeLen = VarInt.read(biomesSrc);
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

