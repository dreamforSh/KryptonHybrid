package com.xinian.KryptonHybrid.mixin.network.pipeline;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import com.xinian.KryptonHybrid.shared.KryptonConfig;
import com.xinian.KryptonHybrid.shared.network.control.PacketControlState;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.BundleDelimiterPacket;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import com.xinian.KryptonHybrid.shared.network.broadcast.BroadcastSerializationCache;
import com.xinian.KryptonHybrid.shared.network.broadcast.BundleEncodeContext;
import com.xinian.KryptonHybrid.shared.network.stats.NetworkTrafficStats;
import com.xinian.KryptonHybrid.shared.network.control.PacketControlContext;
import net.neoforged.neoforge.common.extensions.ICommonPacketListener;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects into {@link PacketEncoder} to:
 * <ol>
 *   <li>Record per-packet-type and per-mod traffic stats for {@code /krypton stats}.</li>
 *   <li>Implement the <strong>Broadcast Serialization Cache</strong>: when the
 *       same {@link Packet} object instance is encoded on the same Netty I/O thread
 *       (common in broadcast scenarios), the serialized bytes are cached on first encode
 *       and reused for subsequent encodes, skipping all VarInt/NBT/collection
 *       serialization work.</li>
 * </ol>
 *
 * <p>The same packet can encode differently per connection: Krypton wire formats
 * depend on the peer's hello flags (light data, for example) and NeoForge codecs on
 * the connection type. Cached bytes are therefore tagged with the encoding signature
 * of the connection that produced them and only reused for connections with the same
 * signature.</p>
 */
@Mixin(PacketEncoder.class)
public class PacketEncoderMixin {

    /** Below this, serializing again is about as cheap as copying cached bytes. */
    @Unique
    private static final int kryptonfnp$MIN_CACHED_BYTES = 64;
    @Unique
    private static final int kryptonfnp$MAX_CACHED_BYTES = 65536;

    /** Encoding signature not known yet (a known one is never negative). */
    @Unique
    private static final int kryptonfnp$NO_SIGNATURE = -1;

    @Shadow @Final private ProtocolInfo<?> protocolInfo;

    /**
     * Encoding signature of this encoder's connection. A PLAY encoder is created after
     * configuration, when the hello flags and the connection type are final.
     */
    @Unique
    private int kryptonfnp$signature = kryptonfnp$NO_SIGNATURE;

    /**
     * HEAD inject: check the broadcast serialization cache before encoding.
     * If the same Packet instance was already encoded on this thread, write the
     * cached bytes directly and cancel the vanilla encode.
     */
    @Inject(method = "encode", at = @At("HEAD"), cancellable = true)
    private void kryptonfnp$cacheHitEncode(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf out, CallbackInfo ci) {
        PacketControlContext.setCurrentChannel(ctx.channel());

        // Track bundle-delimiter scope for downstream forced-compression decisions.
        if (packet instanceof BundleDelimiterPacket<?>) {
            BundleEncodeContext.onDelimiter();
        }

        if (!kryptonfnp$shouldUseBroadcastCache(ctx, packet)) {
            return;
        }
        int signature = kryptonfnp$signature(ctx);
        if (signature == kryptonfnp$NO_SIGNATURE) {
            return;
        }
        byte[] cached = BroadcastSerializationCache.get(packet, signature);
        if (cached != null) {
            out.writeBytes(cached);
            NetworkTrafficStats.INSTANCE.recordPacket(packet, cached.length);
            PacketControlContext.clearCurrentChannel();
            BroadcastSerializationCache.setCurrentPacket(packet);
            ci.cancel();
        }
    }

    /**
     * TAIL inject: record traffic stats and populate the broadcast serialization
     * cache for future same-instance encodes on this Netty I/O thread.
     *
     * <p>Also publishes the packet in a thread-local slot for the downstream compression
     * encoder, which runs synchronously when {@code out} is written on. It is set only
     * after a successful encode, so a failed one never leaves a stale packet behind for
     * an unrelated buffer.</p>
     */
    @Inject(method = "encode", at = @At("TAIL"))
    private void kryptonfnp$trackAndCachePacket(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf out, CallbackInfo ci) {
        int bytes = out.readableBytes();
        NetworkTrafficStats.INSTANCE.recordPacket(packet, bytes);

        if (bytes >= kryptonfnp$MIN_CACHED_BYTES && bytes < kryptonfnp$MAX_CACHED_BYTES
                && kryptonfnp$shouldUseBroadcastCache(ctx, packet)) {
            int signature = kryptonfnp$signature(ctx);
            if (signature != kryptonfnp$NO_SIGNATURE) {
                byte[] serialized = new byte[bytes];
                out.getBytes(out.readerIndex(), serialized);
                BroadcastSerializationCache.put(packet, signature, serialized);
            }
        }

        PacketControlContext.clearCurrentChannel();
        BroadcastSerializationCache.setCurrentPacket(packet);
    }

    /**
     * Only server-to-client PLAY packets are broadcast. The protocol comes from this
     * encoder's own {@link ProtocolInfo}, so the cache works whether or not the security
     * handlers (which track their own connection phase) are installed. Chunk packets are mostly
     * built per player and are large to copy; when one is shared, the compressed cache
     * still saves its compression.
     */
    @Unique
    private boolean kryptonfnp$shouldUseBroadcastCache(ChannelHandlerContext ctx, Packet<?> packet) {
        if (!KryptonConfig.broadcastCacheEnabled || this.protocolInfo.flow() != PacketFlow.CLIENTBOUND) {
            return false;
        }
        if (packet instanceof ClientboundCustomPayloadPacket || packet instanceof ServerboundCustomPayloadPacket
                || packet instanceof ClientboundLevelChunkWithLightPacket) {
            return false;
        }
        return this.protocolInfo.id() == ConnectionProtocol.PLAY;
    }

    /**
     * Connection type and the peer's hello flags (all bits set for a peer without
     * Krypton Hybrid), or {@link #kryptonfnp$NO_SIGNATURE} while the listener is not
     * known yet.
     */
    @Unique
    private int kryptonfnp$signature(ChannelHandlerContext ctx) {
        int signature = this.kryptonfnp$signature;
        if (signature != kryptonfnp$NO_SIGNATURE) {
            return signature;
        }
        if (!(ctx.pipeline().get("packet_handler") instanceof Connection connection)
                || !(connection.getPacketListener() instanceof ICommonPacketListener listener)) {
            return kryptonfnp$NO_SIGNATURE;
        }
        PacketControlState state = PacketControlState.get(ctx.channel());
        int flags = state.isHelloNegotiated() ? state.getRemoteFeatureFlags() & 0xFFFFFF : 0xFFFFFF;
        signature = listener.getConnectionType().ordinal() << 24 | flags;
        this.kryptonfnp$signature = signature;
        return signature;
    }
}
