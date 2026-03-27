package com.xinian.KryptonHybrid.mixin.network.chunk;

import com.google.common.collect.Lists;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import com.xinian.KryptonHybrid.shared.KryptonConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

/**
 * Read-side companion to {@link ChunkLightCompressMixin} for Forge 1.20.1.
 *
 * <p>Intercepts the {@code new ClientboundLightUpdatePacketData(buf, x, z)} constructor
 * call inside {@link ClientboundLevelChunkWithLightPacket#ClientboundLevelChunkWithLightPacket(FriendlyByteBuf)}
 * via {@code @Redirect}. If the buffer begins with the Krypton marker ({@code 0x4B}),
 * the compressed light data is decoded into a temporary vanilla-format buffer, which is
 * then handed to the original vanilla constructor. If the marker is absent (vanilla server
 * or optimization disabled), the vanilla constructor is called directly.</p>
 *
 * <h3>Difference from 1.19.2</h3>
 * <p>The {@code trustEdges} field was removed from {@link ClientboundLightUpdatePacketData}
 * in 1.20.1. This class therefore omits the {@code trustEdges} boolean from both the
 * compressed read and the vanilla re-serialization.</p>
 */
@Mixin(ClientboundLevelChunkWithLightPacket.class)
public abstract class ClientboundLevelChunkWithLightPacketMixin {

    private static final int  KRYPTON_MARKER = 0x4B; // 'K'
    private static final byte ENC_RAW        = 0x00;
    private static final byte ENC_UNIFORM    = 0x01;

    /**
     * Intercepts the {@code new ClientboundLightUpdatePacketData(buf, x, z)} call.
     * Decodes Krypton's compressed format when the marker is present; otherwise falls
     * back to the vanilla constructor unmodified.
     */
    @Redirect(
            method = "<init>(Lnet/minecraft/network/FriendlyByteBuf;)V",
            at = @At(
                    value = "NEW",
                    target = "net/minecraft/network/protocol/game/ClientboundLightUpdatePacketData"
            )
    )
    private ClientboundLightUpdatePacketData readLightData$krypton(FriendlyByteBuf buf, int x, int z) {
        if (!KryptonConfig.lightOptEnabled
                || buf.getUnsignedByte(buf.readerIndex()) != KRYPTON_MARKER) {
            return new ClientboundLightUpdatePacketData(buf, x, z);
        }
        buf.readByte();


        BitSet skyYMask      = buf.readBitSet();
        BitSet blockYMask    = buf.readBitSet();
        BitSet emptySky      = buf.readBitSet();
        BitSet emptyBlock    = buf.readBitSet();
        List<byte[]> skyUpd  = readCompressedList(buf);
        List<byte[]> blkUpd  = readCompressedList(buf);


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


    private static List<byte[]> readCompressedList(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<byte[]> list = Lists.newArrayListWithCapacity(count);
        for (int i = 0; i < count; i++) {
            byte encoding = buf.readByte();
            if (encoding == ENC_UNIFORM) {
                byte v = buf.readByte();
                byte[] arr = new byte[2048];
                Arrays.fill(arr, v);
                list.add(arr);
            } else {
                byte[] arr = new byte[2048];
                buf.readBytes(arr); // fixed 2048 bytes; matches fixed-size write
                list.add(arr);
            }
        }
        return list;
    }
}

