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
 * Optimizes {@link ClientboundLevelChunkPacketData} serialization with two
 * complementary techniques: <strong>Biome delta encoding</strong> and
 * <strong>Heightmap compression</strong>.
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
 *   [0x4B]  Krypton chunk-data marker (1 byte)
 *
 *   --- Heightmaps (binary + XOR-delta) ---
 *   VarInt  entry_count
 *   Per entry:
 *     UTF-8  key
 *     VarInt long_count
 *     long[] XOR-delta encoded
 *
 *   --- Block data (sections without biomes) ---
 *   VarInt  blocks_buffer_length
 *   byte[]  blocks_buffer  (per section: short + PalettedContainer&lt;BlockState&gt;)
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

    /** Krypton chunk-data marker byte. Never collides with vanilla NBT (starts with TAG_Compound = 0x0A). */
    @Unique private static final int KRYPTON_MARKER = 0x4B;

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

        buf.writeByte(KRYPTON_MARKER);

        // --- Heightmaps (binary + XOR-delta) ---
        ChunkDataCodec.writeHeightmaps(buf, this.heightmaps);

        // --- Split section buffer into blocks + biomes ---
        FriendlyByteBuf blocksBuf = new FriendlyByteBuf(Unpooled.buffer(this.buffer.length));
        FriendlyByteBuf biomesBuf = new FriendlyByteBuf(Unpooled.buffer(this.krypton$sectionCount * 4));
        try {
            ChunkDataCodec.splitSectionBuffer(this.buffer, this.krypton$sectionCount, blocksBuf, biomesBuf);

            // Write blocks-only buffer
            int blocksLen = blocksBuf.readableBytes();
            buf.writeVarInt(blocksLen);
            buf.writeBytes(blocksBuf, blocksLen);

            // Write compact biome data
            buf.writeVarInt(this.krypton$sectionCount);
            buf.writeBytes(biomesBuf, biomesBuf.readableBytes());
        } finally {
            blocksBuf.release();
            biomesBuf.release();
        }

        // --- Block entities (unchanged) ---
        ClientboundLevelChunkPacketData.BlockEntityInfo.LIST_STREAM_CODEC.encode(buf, this.blockEntitiesData);


        ci.cancel();
    }
}

