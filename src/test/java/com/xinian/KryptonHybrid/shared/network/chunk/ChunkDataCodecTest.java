package com.xinian.KryptonHybrid.shared.network.chunk;

import com.xinian.KryptonHybrid.shared.network.util.VarIntUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Round trips of {@link ChunkDataCodec}'s byte-aligned block data on vanilla-format sections. */
class ChunkDataCodecTest {
    /** Every block-state bit width vanilla sends: single value, linear, hash map, global. */
    private static final int[] BITS = {0, 4, 5, 6, 7, 8, 15};

    @Test
    void alignedRoundTripRestoresTheVanillaSectionBuffer() {
        Random random = new Random(1);
        for (int trial = 0; trial < 20; trial++) {
            int sections = 24;
            ByteBuf vanilla = Unpooled.buffer();
            for (int s = 0; s < sections; s++) {
                writeSection(vanilla, BITS[random.nextInt(BITS.length)], random);
            }
            byte[] original = ByteBufUtil.getBytes(vanilla);

            // Server: split off the biomes, align the blocks.
            FriendlyByteBuf blocks = buf();
            FriendlyByteBuf biomes = buf();
            ChunkDataCodec.splitSectionBuffer(original, sections, blocks, biomes);
            ByteBuf aligned = Unpooled.buffer();
            assertTrue(ChunkDataCodec.alignBlocks(blocks.duplicate(), sections, aligned));

            // Client: restore the blocks, merge the biomes back.
            ByteBuf restored = Unpooled.buffer();
            ChunkDataCodec.unalignBlocks(aligned, sections, restored);
            assertFalse(aligned.isReadable(), "all aligned data consumed");
            assertArrayEquals(ByteBufUtil.getBytes(blocks), ByteBufUtil.getBytes(restored));
            assertArrayEquals(original, ChunkDataCodec.mergeSectionBuffer(
                    ByteBufUtil.getBytes(restored), ByteBufUtil.getBytes(biomes), sections));
        }
    }

    @Test
    void fiveToEightBitSectionsStoreOneBytePerIndex() {
        Random random = new Random(2);
        for (int bits = 5; bits <= 8; bits++) {
            int[] indices = indices(bits, random);
            ByteBuf blocks = Unpooled.buffer();
            writeBlocks(blocks, bits, indices, palette(1 << bits));

            ByteBuf aligned = Unpooled.buffer();
            assertTrue(ChunkDataCodec.alignBlocks(blocks, 1, aligned));
            skipHeaderAndPalette(aligned, bits);
            assertEquals(4096, aligned.readableBytes(), "no length, one byte per index");
            for (int i = 0; i < 4096; i++) {
                assertEquals(indices[i], aligned.readUnsignedByte());
            }
        }
    }

    @Test
    void fourBitSectionsAreLittleEndianLongs() {
        int[] indices = indices(4, new Random(3));
        ByteBuf blocks = Unpooled.buffer();
        writeBlocks(blocks, 4, indices, palette(16));

        ByteBuf aligned = Unpooled.buffer();
        assertTrue(ChunkDataCodec.alignBlocks(blocks, 1, aligned));
        skipHeaderAndPalette(aligned, 4);
        assertEquals(256, VarIntUtil.readVarInt(aligned));
        for (int i = 0; i < 4096; i += 2) {
            assertEquals(indices[i] | indices[i + 1] << 4, aligned.readUnsignedByte(), "indices in byte order");
        }
    }

    @Test
    void unexpectedLongCountIsNotAligned() {
        ByteBuf blocks = Unpooled.buffer();
        blocks.writeShort(4096);
        blocks.writeByte(5);
        VarIntUtil.writeVarInt(blocks, 1);
        VarIntUtil.writeVarInt(blocks, 7);
        VarIntUtil.writeVarInt(blocks, 10); // 5 bits need 342 longs
        blocks.writeZero(80);

        assertFalse(ChunkDataCodec.alignBlocks(blocks, 1, Unpooled.buffer()));
    }

    @Test
    void indexWiderThanTheSectionIsRejected() {
        ByteBuf aligned = Unpooled.buffer();
        aligned.writeShort(4096);
        aligned.writeByte(5);
        VarIntUtil.writeVarInt(aligned, 1);
        VarIntUtil.writeVarInt(aligned, 7);
        aligned.writeZero(4095);
        aligned.writeByte(32); // needs 6 bits

        assertThrows(IllegalArgumentException.class,
                () -> ChunkDataCodec.unalignBlocks(aligned, 1, Unpooled.buffer()));
    }

    @Test
    void truncatedAlignedDataIsRejected() {
        ByteBuf blocks = Unpooled.buffer();
        writeBlocks(blocks, 6, indices(6, new Random(4)), palette(64));
        ByteBuf aligned = Unpooled.buffer();
        assertTrue(ChunkDataCodec.alignBlocks(blocks, 1, aligned));

        ByteBuf truncated = aligned.slice(0, aligned.readableBytes() - 1);
        assertThrows(IndexOutOfBoundsException.class,
                () -> ChunkDataCodec.unalignBlocks(truncated, 1, Unpooled.buffer()));
    }

    @Test
    void computedHeightmapsMatchATopDownScan() {
        Random random = new Random(5);
        for (int trial = 0; trial < 10; trial++) {
            int sections = 24;
            int[][] world = terrain(sections, random);
            byte[] vanilla = vanillaSections(world);

            long[][] computed = ChunkDataCodec.computeHeightmaps(vanilla, sections, ChunkDataCodecTest::flags);

            for (int map = 0; map < 2; map++) {
                int flag = map == 0 ? ChunkDataCodec.HEIGHTMAP_WORLD_SURFACE : ChunkDataCodec.HEIGHTMAP_MOTION_BLOCKING;
                int[] expected = referenceHeights(world, flag);
                assertEquals(37, computed[map].length, "9 bits per column, 7 per long");
                for (int column = 0; column < 256; column++) {
                    assertEquals(expected[column], column(computed[map], column, 9), "column " + column);
                }
            }
        }
    }

    @Test
    void heightmapBitsFollowTheWorldHeight() {
        // One section: 16 blocks high, so ceillog2(17) = 5 bits, 12 columns per long.
        int[][] world = new int[1][4096];
        world[0][(15 << 8) | 3] = 6; // top of the world, column x=3 z=0
        long[][] computed = ChunkDataCodec.computeHeightmaps(vanillaSections(world), 1, ChunkDataCodecTest::flags);

        assertEquals(22, computed[0].length);
        assertEquals(16, column(computed[0], 3, 5));
        assertEquals(0, column(computed[0], 4, 5), "empty column");
    }

    // ------------------------------------------------------------------
    //  Heightmap helpers
    // ------------------------------------------------------------------

    /** Test block states: 0 air, 1 cave air, then counted by WORLD_SURFACE and/or MOTION_BLOCKING. */
    private static int flags(int stateId) {
        if (stateId <= 1) {
            return 0;
        }
        return switch (stateId % 3) {
            case 0 -> ChunkDataCodec.HEIGHTMAP_WORLD_SURFACE | ChunkDataCodec.HEIGHTMAP_MOTION_BLOCKING;
            case 1 -> ChunkDataCodec.HEIGHTMAP_WORLD_SURFACE; // like a flower
            default -> ChunkDataCodec.HEIGHTMAP_MOTION_BLOCKING;
        };
    }

    /** Global state ids per section: ground, scattered blocks above, empty columns, one block at the top. */
    private static int[][] terrain(int sections, Random random) {
        int[][] world = new int[sections][4096];
        for (int column = 0; column < 256; column++) {
            if (column % 37 == 0) {
                continue; // empty column
            }
            int ground = 40 + random.nextInt(80);
            for (int y = 0; y < sections * 16; y++) {
                int id = y < ground ? 3 + 3 * random.nextInt(4) // solid
                        : y < ground + 3 ? (random.nextInt(4) == 0 ? 4 + random.nextInt(60) : random.nextInt(2))
                        : random.nextInt(200) == 0 ? 2 + random.nextInt(20) : 0;
                world[y >> 4][((y & 15) << 8) | column] = id;
            }
        }
        // A section with more than 256 distinct states uses the global palette.
        for (int i = 0; i < 4096; i++) {
            world[2][i] = 3 + i % 600;
        }
        world[sections - 1][(15 << 8) | 5] = 3; // block at the very top: height = world height
        return world;
    }

    private static int[] referenceHeights(int[][] world, int flag) {
        int[] heights = new int[256];
        for (int column = 0; column < 256; column++) {
            for (int y = world.length * 16 - 1; y >= 0; y--) {
                if ((flags(world[y >> 4][((y & 15) << 8) | column]) & flag) != 0) {
                    heights[column] = y + 1;
                    break;
                }
            }
        }
        return heights;
    }

    /** Encodes sections the way vanilla does: single value, 4-8 bit palette, or 15-bit global ids. */
    private static byte[] vanillaSections(int[][] world) {
        ByteBuf out = Unpooled.buffer();
        for (int[] section : world) {
            int[] palette = java.util.Arrays.stream(section).distinct().toArray();
            if (palette.length == 1) {
                writeBlocks(out, 0, new int[4096], palette);
            } else {
                int bits = ceilLog2(palette.length);
                if (bits > 8) {
                    writeBlocks(out, 15, section, palette);
                } else {
                    java.util.Map<Integer, Integer> index = new java.util.HashMap<>();
                    for (int i = 0; i < palette.length; i++) {
                        index.put(palette[i], i);
                    }
                    int[] indices = java.util.Arrays.stream(section).map(index::get).toArray();
                    writeBlocks(out, bits, indices, palette);
                }
            }
            out.writeByte(0); // single-value biome
            VarIntUtil.writeVarInt(out, 0);
            VarIntUtil.writeVarInt(out, 0);
        }
        return ByteBufUtil.getBytes(out);
    }

    private static int column(long[] packed, int column, int bits) {
        int valuesPerLong = 64 / bits;
        return (int) ((packed[column / valuesPerLong] >>> ((column % valuesPerLong) * bits)) & ((1L << bits) - 1));
    }

    // ------------------------------------------------------------------
    //  Vanilla section format
    // ------------------------------------------------------------------

    /** One vanilla section: block count, block-state container, biome container. */
    private static void writeSection(ByteBuf out, int bits, Random random) {
        int paletteSize = bits == 0 ? 1 : bits <= 8 ? 1 + random.nextInt(1 << bits) : 0;
        writeBlocks(out, bits, indices(bits == 0 ? 0 : bits <= 8 ? ceilLog2(paletteSize) : bits, random),
                palette(paletteSize));
        if (random.nextBoolean()) {
            out.writeByte(0);
            VarIntUtil.writeVarInt(out, random.nextInt(64));
            VarIntUtil.writeVarInt(out, 0);
        } else {
            out.writeByte(2);
            VarIntUtil.writeVarInt(out, 4);
            for (int i = 0; i < 4; i++) {
                VarIntUtil.writeVarInt(out, random.nextInt(64));
            }
            long[] packed = pack(indices(2, random, 64), 2);
            VarIntUtil.writeVarInt(out, packed.length);
            for (long l : packed) {
                out.writeLong(l);
            }
        }
    }

    private static void writeBlocks(ByteBuf out, int bits, int[] indices, int[] palette) {
        out.writeShort(4096);
        out.writeByte(bits);
        if (bits == 0) {
            VarIntUtil.writeVarInt(out, palette[0]);
            VarIntUtil.writeVarInt(out, 0);
            return;
        }
        if (bits <= 8) {
            VarIntUtil.writeVarInt(out, palette.length);
            for (int id : palette) {
                VarIntUtil.writeVarInt(out, id);
            }
        }
        long[] packed = pack(indices, bits);
        VarIntUtil.writeVarInt(out, packed.length);
        for (long l : packed) {
            out.writeLong(l);
        }
    }

    private static void skipHeaderAndPalette(ByteBuf aligned, int bits) {
        aligned.skipBytes(2);
        assertEquals(bits, aligned.readUnsignedByte());
        int size = VarIntUtil.readVarInt(aligned);
        for (int i = 0; i < size; i++) {
            VarIntUtil.readVarInt(aligned);
        }
    }

    /** Vanilla {@code SimpleBitStorage} packing: no index spans two longs. */
    private static long[] pack(int[] values, int bits) {
        int valuesPerLong = 64 / bits;
        long[] out = new long[(values.length + valuesPerLong - 1) / valuesPerLong];
        for (int i = 0; i < values.length; i++) {
            out[i / valuesPerLong] |= (long) values[i] << ((i % valuesPerLong) * bits);
        }
        return out;
    }

    private static int[] indices(int bits, Random random) {
        return indices(bits, random, 4096);
    }

    /** Indices below {@code 2^bits}, in runs like real terrain. */
    private static int[] indices(int bits, Random random, int count) {
        int[] values = new int[count];
        if (bits == 0) {
            return values;
        }
        int value = 0;
        for (int i = 0; i < count; i++) {
            if (random.nextInt(8) == 0) {
                value = random.nextInt(1 << bits);
            }
            values[i] = value;
        }
        values[count - 1] = (1 << bits) - 1; // the widest index always occurs
        return values;
    }

    private static int[] palette(int size) {
        int[] palette = new int[size];
        for (int i = 0; i < size; i++) {
            palette[i] = 1 + i * 37;
        }
        return palette;
    }

    private static int ceilLog2(int n) {
        return Math.max(4, 32 - Integer.numberOfLeadingZeros(n - 1));
    }

    private static FriendlyByteBuf buf() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }
}
