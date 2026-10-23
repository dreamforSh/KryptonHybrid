package com.xinian.KryptonHybrid.mixin.network.chunk;

import com.xinian.KryptonHybrid.shared.KryptonConfig;
import com.xinian.KryptonHybrid.shared.network.chunk.ChunkDataCodec;
import com.xinian.KryptonHybrid.shared.network.control.KryptonWireFormat;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Optimizes {@link ClientboundLevelChunkPacketData} serialization with
 * <strong>Biome delta encoding</strong>, <strong>Heightmap compression</strong> and,
 * for clients that support it, <strong>byte-aligned block data</strong>.
 *
 * <h3>Heightmap compression</h3>
 * <p>Replaces NBT-based heightmap serialization with a compact binary format
 * using XOR-delta encoding on the packed {@code long[]} arrays. Adjacent
 * heightmap columns have highly correlated heights, so XOR-delta produces
 * many near-zero longs that compress significantly better under the
 * connection-level Zstd/ZLIB layer.  The binary format also eliminates
 * ~40 bytes of NBT structural overhead (tag types, UTF-8 key prefixes,
 * 4-byte array lengths).</p>
 *
 * <h3>Biome delta encoding</h3>
 * <p>Separates biome data from block-state data in the section buffer.
 * Single-value biome sections (the common case for most overworld terrain)
 * are encoded as just 2 bytes ({@code flag + VarInt(biomeId)}) instead of
 * the vanilla PalettedContainer overhead. Grouping biome data together also
 * improves cross-section redundancy elimination by the compressor.</p>
 *
 * <h3>Wire format</h3>
 * <pre>
 *   [0x4B]  Krypton chunk-data marker (1 byte), or [0x4C] when the block data is
 *           byte-aligned (see {@link ChunkDataCodec#alignBlocks})
 *
 *   --- Heightmaps (binary + XOR-delta) ---
 *   VarInt  entry_count  (0 for clients that compute them, see ChunkDataCodec#computeHeightmaps)
 *   Per entry:
 *     UTF-8  key
 *     VarInt long_count
 *     long[] XOR-delta encoded
 *
 *   --- Block data (sections without biomes) ---
 *   VarInt  blocks_buffer_length
 *   byte[]  blocks_buffer  (per section: short + PalettedContainer&lt;BlockState&gt;,
 *                           byte-aligned with 0x4C)
 *
 *   --- Biome data (compact) ---
 *   VarInt  section_count
 *   Per section:
 *     0x01 + VarInt(biomeId)           — single-value section
 *     0x00 + VarInt(len) + raw bytes   — multi-value section
 *
 *   --- Block entities (unchanged) ---
 *   LIST_STREAM_CODEC encoded
 * </pre>
 *
 * <h3>Read-side compatibility</h3>
 * <p>On the read side, blocks and biome data are merged back into a standard
 * vanilla section buffer so that {@code getReadBuffer()} and
 * {@code LevelChunkSection.read()} work without modification.</p>
 *
 * <h3>Fallback</h3>
 * <p>If the incoming buffer does not start with {@code 0x4B}, the vanilla
 * constructor runs unmodified (vanilla server or optimization disabled).</p>
 */
@Mixin(ClientboundLevelChunkPacketData.class)
public abstract class ChunkDataOptMixin {

    @Shadow @Final private CompoundTag heightmaps;
    @Shadow @Final private byte[] buffer;
    @Shadow @Final private List<ClientboundLevelChunkPacketData.BlockEntityInfo> blockEntitiesData;

    // --- Write-side fields (populated at construction from LevelChunk) ---

    /** Number of sections in the chunk, stored at construction for use in write(). */
    @Unique private int krypton$sectionCount;

    /**
     * Extracts section metadata after the vanilla constructor has built the buffer.
     * Stores the section count so that {@code write()} can split the buffer later.
     */
    @Inject(
            method = "<init>(Lnet/minecraft/world/level/chunk/LevelChunk;)V",
            at = @At("RETURN")
    )
    private void krypton$captureSectionCount(LevelChunk chunk, CallbackInfo ci) {
        if (!KryptonConfig.chunkOptEnabled) return;
        this.krypton$sectionCount = chunk.getSections().length;
    }

    // ================================================================
    //  Write side
    // ================================================================

    /**
     * Replaces the vanilla {@code write()} with Krypton's optimized format
     * when chunk-data optimization is enabled.
     */
    @Inject(method = "write", at = @At("HEAD"), cancellable = true)
    private void krypton$writeOptimized(RegistryFriendlyByteBuf buf, CallbackInfo ci) {
        if (!KryptonConfig.chunkOptEnabled
                || !KryptonWireFormat.canWriteCurrentChunkData()
                || this.krypton$sectionCount <= 0) return;

        FriendlyByteBuf blocksBuf = new FriendlyByteBuf(Unpooled.buffer(this.buffer.length));
        FriendlyByteBuf biomesBuf = new FriendlyByteBuf(Unpooled.buffer(this.krypton$sectionCount * 4));
        FriendlyByteBuf alignedBuf = null;
        try {
            ChunkDataCodec.splitSectionBuffer(this.buffer, this.krypton$sectionCount, blocksBuf, biomesBuf);

            // Byte-aligned block data when the client supports it and every section has
            // the expected layout; otherwise the vanilla block data.
            FriendlyByteBuf blocks = blocksBuf;
            if (KryptonWireFormat.canWriteCurrentAlignedChunkBlocks()) {
                alignedBuf = new FriendlyByteBuf(Unpooled.buffer(blocksBuf.readableBytes() * 2));
                if (ChunkDataCodec.alignBlocks(blocksBuf.duplicate(), this.krypton$sectionCount, alignedBuf)) {
                    blocks = alignedBuf;
                }
            }

            buf.writeByte(blocks == alignedBuf ? ChunkDataCodec.ALIGNED_BLOCKS_MARKER : ChunkDataCodec.MARKER);
            if (KryptonWireFormat.canWriteCurrentClientHeightmaps()) {
                buf.writeVarInt(0); // no heightmaps: the client computes them from the blocks
            } else {
                ChunkDataCodec.writeHeightmaps(buf, this.heightmaps);
            }

            int blocksLen = blocks.readableBytes();
            buf.writeVarInt(blocksLen);
            buf.writeBytes(blocks, blocksLen);

            // Write compact biome data
            buf.writeVarInt(this.krypton$sectionCount);
            buf.writeBytes(biomesBuf, biomesBuf.readableBytes());
        } finally {
            blocksBuf.release();
            biomesBuf.release();
            if (alignedBuf != null) {
                alignedBuf.release();
            }
        }

        // --- Block entities (unchanged) ---
        ClientboundLevelChunkPacketData.BlockEntityInfo.LIST_STREAM_CODEC.encode(buf, this.blockEntitiesData);


        ci.cancel();
    }
}

