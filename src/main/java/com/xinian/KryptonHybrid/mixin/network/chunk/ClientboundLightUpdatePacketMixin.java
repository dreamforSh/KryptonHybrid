package com.xinian.KryptonHybrid.mixin.network.chunk;

import com.google.common.collect.Lists;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import com.xinian.KryptonHybrid.shared.KryptonConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

/**
 * Read-side Mixin for {@link ClientboundLightUpdatePacket} (Forge 1.20.1).
 *
 * <p>{@link ClientboundLightUpdatePacket} is the standalone light-update packet sent when
 * lighting changes after the chunk has already been loaded on the client. It uses the same
 * {@link ClientboundLightUpdatePacketData} internally, so its {@code write()} path is also
 * intercepted by {@link ChunkLightCompressMixin} and will emit Krypton-compressed data.</p>
 *
 * <p>This Mixin intercepts the {@code new ClientboundLightUpdatePacketData(buf, x, z)} call
 * inside the network constructor of {@link ClientboundLightUpdatePacket} via {@code @Redirect},
 * decodes the Krypton format when the marker byte {@code 0x4B} is present, and re-serialises
 * the decompressed data into a vanilla-format buffer before passing it to the original
 * vanilla constructor.</p>
 *
 * <p>1.20.1 specific: the {@code trustEdges} field was removed from
 * {@link ClientboundLightUpdatePacketData}; this class therefore omits it entirely.</p>
 */
@Mixin(ClientboundLightUpdatePacket.class)
public abstract class ClientboundLightUpdatePacketMixin {

    private static final int  KRYPTON_MARKER = 0x4B; // 'K'
    private static final byte ENC_RAW        = 0x00;
    private static final byte ENC_UNIFORM    = 0x01;

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


        BitSet skyYMask     = buf.readBitSet();
        BitSet blockYMask   = buf.readBitSet();
        BitSet emptySky     = buf.readBitSet();
        BitSet emptyBlock   = buf.readBitSet();
        List<byte[]> skyUpd = readCompressedList(buf);
        List<byte[]> blkUpd = readCompressedList(buf);

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

