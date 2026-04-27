package com.xinian.KryptonHybrid.mixin.network.chunk;

import com.google.common.collect.Lists;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import com.xinian.KryptonHybrid.shared.network.chunk.ChunkDataCodec;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

/**
 * Read-side companion to {@link ChunkLightCompressMixin} and {@link ChunkDataOptMixin}.
 *
 * <p>Intercepts both {@code new ClientboundLevelChunkPacketData(buf, x, z)} and
 * {@code new ClientboundLightUpdatePacketData(buf, x, z)} constructor calls inside the
 * {@link ClientboundLevelChunkWithLightPacket} buffer-reading constructor via
 * {@code @Redirect}. If the buffer begins with the Krypton marker ({@code 0x4B}),
 * the compressed data is decoded into a temporary vanilla-format buffer, which is
 * then handed to the original vanilla constructor. If the marker is absent (vanilla
 * server or optimization disabled), the vanilla constructor is called directly.</p>
 */
@Mixin(ClientboundLevelChunkWithLightPacket.class)
public abstract class ClientboundLevelChunkWithLightPacketMixin {

    @Unique private static final int  KRYPTON_MARKER = 0x4B; // 'K'
    @Unique private static final byte ENC_RAW        = 0x00;
    @Unique private static final byte ENC_UNIFORM    = 0x01;

    // ================================================================
    //  Chunk data read-side (biome delta + heightmap XOR-delta)
    // ================================================================

    /**
     * Intercepts the {@code new ClientboundLevelChunkPacketData(buf, x, z)} call.
     * Decodes Krypton's optimized chunk-data format when the marker is present;
     * otherwise falls back to the vanilla constructor unmodified.
     */
    @Redirect(
            method = "<init>(Lnet/minecraft/network/RegistryFriendlyByteBuf;)V",
            at = @At(
                    value = "NEW",
                    target = "net/minecraft/network/protocol/game/ClientboundLevelChunkPacketData"
            )
    )
    private ClientboundLevelChunkPacketData readChunkData$krypton(RegistryFriendlyByteBuf buf, int x, int z) {
        if (buf.getUnsignedByte(buf.readerIndex()) != KRYPTON_MARKER) {
            return new ClientboundLevelChunkPacketData(buf, x, z);
        }
        buf.readByte(); // consume 0x4B marker

        // --- Heightmaps (XOR-delta → CompoundTag) ---
        CompoundTag heightmaps = ChunkDataCodec.readHeightmaps(buf);

        // --- Blocks-only buffer ---
        int blocksLen = buf.readVarInt();
        if (blocksLen > 2097152) {
            throw new RuntimeException("Chunk Packet trying to allocate too much memory on read.");
        }
        byte[] blocksData = new byte[blocksLen];
        buf.readBytes(blocksData);

        // --- Biome data (skip-scan to find total byte length, then bulk-read) ---
        int sectionCount = buf.readVarInt();
        ByteBuf peekSlice = buf.slice(); // independent reader index, shares backing data
        for (int i = 0; i < sectionCount; i++) {
            byte flag = peekSlice.readByte();
            if (flag == ChunkDataCodec.BIOME_SINGLE_VALUE) {
                VarInt.read(peekSlice);
            } else {
                int len = VarInt.read(peekSlice);
                peekSlice.skipBytes(len);
            }
        }
        int biomesLen = peekSlice.readerIndex();
        byte[] biomesData = new byte[biomesLen];
        buf.readBytes(biomesData);

        // --- Merge blocks + biomes into vanilla section buffer ---
        byte[] vanillaBuffer = ChunkDataCodec.mergeSectionBuffer(blocksData, biomesData, sectionCount);

        // --- Block entities: decode from original buf, re-encode to vanilla buf ---
        List<ClientboundLevelChunkPacketData.BlockEntityInfo> blockEntities =
                ClientboundLevelChunkPacketData.BlockEntityInfo.LIST_STREAM_CODEC.decode(buf);

        // --- Build a vanilla-format RegistryFriendlyByteBuf ---
        RegistryFriendlyByteBuf vanillaBuf = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), buf.registryAccess(), buf.getConnectionType());
        try {
            vanillaBuf.writeNbt(heightmaps);
            vanillaBuf.writeVarInt(vanillaBuffer.length);
            vanillaBuf.writeBytes(vanillaBuffer);
            ClientboundLevelChunkPacketData.BlockEntityInfo.LIST_STREAM_CODEC.encode(vanillaBuf, blockEntities);
            return new ClientboundLevelChunkPacketData(vanillaBuf, x, z);
        } finally {
            vanillaBuf.release();
        }
    }

    // ================================================================
    //  Light data read-side (uniform-RLE)
    // ================================================================

    /**
     * Intercepts the {@code new ClientboundLightUpdatePacketData(buf, x, z)} call.
     * Decodes Krypton's compressed format when the marker is present; otherwise falls
     * back to the vanilla constructor unmodified.
     */
    @Redirect(
            method = "<init>(Lnet/minecraft/network/RegistryFriendlyByteBuf;)V",
            at = @At(
                    value = "NEW",
                    target = "net/minecraft/network/protocol/game/ClientboundLightUpdatePacketData"
            )
    )
    private ClientboundLightUpdatePacketData readLightData$krypton(FriendlyByteBuf buf, int x, int z) {
        if (buf.getUnsignedByte(buf.readerIndex()) != KRYPTON_MARKER) {
            return new ClientboundLightUpdatePacketData(buf, x, z);
        }
        buf.readByte();


        BitSet skyYMask      = buf.readBitSet();
        BitSet blockYMask    = buf.readBitSet();
        BitSet emptySky      = buf.readBitSet();
        BitSet emptyBlock    = buf.readBitSet();
        List<byte[]> skyUpd  = krypton$readCompressedList(buf);
        List<byte[]> blkUpd  = krypton$readCompressedList(buf);


        FriendlyByteBuf vanillaBuf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            vanillaBuf.writeBitSet(skyYMask);
            vanillaBuf.writeBitSet(blockYMask);
            vanillaBuf.writeBitSet(emptySky);
            vanillaBuf.writeBitSet(emptyBlock);
            vanillaBuf.writeCollection(skyUpd, (packetBuf, bytes) -> packetBuf.writeByteArray(bytes));
            vanillaBuf.writeCollection(blkUpd, (packetBuf, bytes) -> packetBuf.writeByteArray(bytes));
            return new ClientboundLightUpdatePacketData(vanillaBuf, x, z);
        } finally {
            vanillaBuf.release();
        }
    }


    @Unique
    private static List<byte[]> krypton$readCompressedList(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<byte[]> list = Lists.newArrayListWithCapacity(count);
        for (int i = 0; i < count; i++) {
            byte encoding = buf.readByte();
            if (encoding == ENC_UNIFORM) {
                byte v = buf.readByte();
                byte[] arr = new byte[2048];
                Arrays.fill(arr, v);
                list.add(arr);
            } else if (encoding == ENC_RAW) {
                byte[] arr = new byte[2048];
                buf.readBytes(arr); // fixed 2048 bytes; matches fixed-size write
                list.add(arr);
            } else {
                throw new IllegalArgumentException("Unknown light data encoding: " + encoding);
            }
        }
        return list;
    }
}
