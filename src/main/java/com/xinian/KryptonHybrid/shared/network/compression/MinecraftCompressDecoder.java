package com.xinian.KryptonHybrid.shared.network.compression;

import com.velocitypowered.natives.compression.VelocityCompressor;
import com.xinian.KryptonHybrid.shared.network.security.DecompressionBombGuard;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import net.minecraft.network.FriendlyByteBuf;

import java.util.List;

import static com.google.common.base.Preconditions.checkState;
import static com.velocitypowered.natives.util.MoreByteBufUtils.ensureCompatible;

/**
 * Decompresses a Minecraft packet.
 */
public class MinecraftCompressDecoder extends MessageToMessageDecoder<ByteBuf> {

    private static final int VANILLA_MAXIMUM_UNCOMPRESSED_SIZE = 8 * 1024 * 1024; // 8MiB
    private static final int HARD_MAXIMUM_UNCOMPRESSED_SIZE = 128 * 1024 * 1024; // 128MiB

    private static final int UNCOMPRESSED_CAP =
            Boolean.getBoolean("krypton.permit-oversized-packets")
                    ? HARD_MAXIMUM_UNCOMPRESSED_SIZE : VANILLA_MAXIMUM_UNCOMPRESSED_SIZE;
    /**
     * One growth step of the Java fallback inflater, so a stream that exactly fills
     * the claimed size can still consume its trailer.
     */
    private static final int INFLATE_SLACK = 8192;
    private final VelocityCompressor compressor;
    private final boolean validate;
    private int threshold;

    public MinecraftCompressDecoder(int threshold, boolean validate, VelocityCompressor compressor) {
        this.threshold = threshold;
        this.compressor = compressor;
        this.validate = validate;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        FriendlyByteBuf bb = new FriendlyByteBuf(in);
        int claimedUncompressedSize = bb.readVarInt();
        if (claimedUncompressedSize == 0) {
            // Vanilla MC's CompressionDecoder does NOT validate the uncompressed
            // pass-through branch against the threshold; only the compressed branch
            // is validated.  We mirror that to avoid spurious disconnects when the
            // peer encoded a packet just under its (possibly higher) threshold while
            // we have a lower local threshold (race during setupCompression update,
            // BroadcastCompressedCache replay, etc.).  The hard cap below is the
            // real safety net.
            int actualUncompressedSize = in.readableBytes();
            checkState(actualUncompressedSize <= UNCOMPRESSED_CAP,
                    "Uncompressed pass-through size %s exceeds hard cap of %s",
                    actualUncompressedSize, UNCOMPRESSED_CAP);
            out.add(in.retain());
            return;
        }

        if (validate) {
            checkState(claimedUncompressedSize >= threshold, "Uncompressed size %s is less than"
                    + " threshold %s", claimedUncompressedSize, threshold);
            checkState(claimedUncompressedSize <= UNCOMPRESSED_CAP,
                    "Uncompressed size %s exceeds hard threshold of %s", claimedUncompressedSize,
                    UNCOMPRESSED_CAP);
        }

        // --- Decompression bomb guard (server side only: validate is false on the client,
        // whose server legitimately sends packets far above any ratio limit, e.g. uniform
        // light or map data) ---
        if (validate) {
            DecompressionBombGuard.validate(in.readableBytes(), claimedUncompressedSize, ctx.channel());
        }

        ByteBuf compatibleIn = ensureCompatible(ctx.alloc(), compressor, in);
        ByteBuf uncompressed = boundedBuffer(ctx.alloc(), claimedUncompressedSize);
        try {
            compressor.inflate(compatibleIn, uncompressed, claimedUncompressedSize);
            checkState(uncompressed.readableBytes() == claimedUncompressedSize,
                    "Uncompressed size %s does not match claimed size %s",
                    uncompressed.readableBytes(), claimedUncompressedSize);
            out.add(uncompressed);
        } catch (Exception e) {
            uncompressed.release();
            throw e;
        } finally {
            compatibleIn.release();
        }
    }

    /**
     * Like {@code MoreByteBufUtils.preferredBuffer}, but caps the capacity just above
     * the claimed size. Velocity's Java fallback inflater (used where no native
     * library is available) grows its destination 8 KiB at a time for as long as
     * input remains, so without a cap a small claimed size followed by a highly
     * compressible stream inflates without bound.
     */
    private ByteBuf boundedBuffer(ByteBufAllocator alloc, int claimedUncompressedSize) {
        int maxCapacity = (int) Math.min(Integer.MAX_VALUE, (long) claimedUncompressedSize + INFLATE_SLACK);
        return switch (compressor.preferredBufferType()) {
            case HEAP_REQUIRED, HEAP_PREFERRED -> alloc.heapBuffer(claimedUncompressedSize, maxCapacity);
            case DIRECT_PREFERRED, DIRECT_REQUIRED -> alloc.directBuffer(claimedUncompressedSize, maxCapacity);
        };
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        compressor.close();
    }

    public void setThreshold(int threshold) {
        this.threshold = threshold;
    }
}

