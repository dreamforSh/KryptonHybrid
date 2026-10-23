package com.xinian.KryptonHybrid.shared.network.compression;

import com.velocitypowered.natives.compression.VelocityCompressor;
import com.velocitypowered.natives.util.MoreByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import com.xinian.KryptonHybrid.shared.network.broadcast.BroadcastSerializationCache;
import com.xinian.KryptonHybrid.shared.network.stats.NetworkTrafficStats;
import com.xinian.KryptonHybrid.shared.network.util.ReservedFrameHeader;

public class MinecraftCompressEncoder extends MessageToByteEncoder<ByteBuf> {

  private int threshold;
  private final VelocityCompressor compressor;

  public MinecraftCompressEncoder(int threshold, VelocityCompressor compressor) {
    this.threshold = threshold;
    this.compressor = compressor;
  }

  @Override
  protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) throws Exception {
    FriendlyByteBuf wrappedBuf = new FriendlyByteBuf(out);
    int uncompressed = msg.readableBytes();
    int startWireIndex = out.writerIndex();
    Object currentPacket = BroadcastSerializationCache.pollCurrentPacket();
    Packet<?> packet = (currentPacket instanceof Packet<?> p) ? p : null;
    if (uncompressed < threshold) {
      // Under the threshold, there is nothing to do.
      wrappedBuf.writeVarInt(0);
      out.writeBytes(msg);
    } else {
      int start = msg.readerIndex();
      wrappedBuf.writeVarInt(uncompressed);
      ByteBuf compatibleIn = MoreByteBufUtils.ensureCompatible(ctx.alloc(), compressor, msg);
      try {
        compressor.deflate(compatibleIn, out);
      } finally {
        compatibleIn.release();
      }
      // Already-compressed data can grow: send it raw instead. Raw packets above the
      // threshold are accepted by vanilla's decoder and ours.
      if (out.writerIndex() - startWireIndex >= 1 + uncompressed) {
        out.writerIndex(startWireIndex);
        wrappedBuf.writeVarInt(0);
        out.writeBytes(msg, start, uncompressed);
      }
      msg.readerIndex(start + uncompressed);
    }
    int wire = out.writerIndex() - startWireIndex;
    NetworkTrafficStats.INSTANCE.recordEncode(uncompressed, wire);
    if (packet != null && wire > 0) {
      NetworkTrafficStats.INSTANCE.recordPacketWire(packet, wire);
    }
  }

  @Override
  protected ByteBuf allocateBuffer(ChannelHandlerContext ctx, ByteBuf msg, boolean preferDirect)
      throws Exception {
    // We allocate bytes to be compressed plus 1 byte. This covers two cases:
    //
    // - Compression
    //    According to https://github.com/ebiggers/libdeflate/blob/master/libdeflate.h#L103,
    //    if the data compresses well (and we do not have some pathological case) then the maximum
    //    size the compressed size will ever be is the input size minus one.
    // - Uncompressed
    //    This is fairly obvious - we will then have one more than the uncompressed size.
    //
    // Plus room in front for the frame length (see ReservedFrameHeader).
    int initialBufferSize = ReservedFrameHeader.SIZE + msg.readableBytes() + 1;
    return ReservedFrameHeader.reserve(MoreByteBufUtils.preferredBuffer(ctx.alloc(), compressor, initialBufferSize));
  }

  @Override
  public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
    compressor.close();
  }

  public void setThreshold(int threshold) {
    this.threshold = threshold;
  }
}
