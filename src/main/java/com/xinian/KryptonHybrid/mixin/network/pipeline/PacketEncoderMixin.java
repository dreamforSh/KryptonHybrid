package com.xinian.KryptonHybrid.mixin.network.pipeline;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import com.xinian.KryptonHybrid.shared.network.BroadcastSerializationCache;
import com.xinian.KryptonHybrid.shared.network.CapabilityContext;
import com.xinian.KryptonHybrid.shared.network.KryptonCapabilityHolder;
import com.xinian.KryptonHybrid.shared.network.NetworkTrafficStats;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects into {@link PacketEncoder} to:
 * <ol>
 *   <li>Set the per-connection {@link CapabilityContext} before encoding so that
 *       write-side mixins (chunk data, light data, block entity delta) can check
 *       negotiated capabilities and fall back to vanilla format when needed.</li>
 *   <li>Record per-packet-type and per-mod traffic stats for {@code /krypton stats}.</li>
 *   <li>Implement the <strong>Broadcast Serialization Cache</strong> (P0-⑧): when the
 *       same {@link Packet} object instance is encoded on the same Netty I/O thread
 *       (common in broadcast scenarios), the serialized bytes are cached on first encode
 *       and reused for subsequent encodes, skipping all VarInt/NBT/collection
 *       serialization work.</li>
 * </ol>
 */
@Mixin(PacketEncoder.class)
public class PacketEncoderMixin {

    /**
     * HEAD inject: set CapabilityContext and check broadcast cache.
     */
    @Inject(method = "encode", at = @At("HEAD"), cancellable = true)
    private void kryptonfnp$cacheHitEncode(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf out, CallbackInfo ci) {
        // Set per-connection capabilities for write-side mixins
        Connection connection = (Connection) ctx.pipeline().get("packet_handler");
        if (connection instanceof KryptonCapabilityHolder holder) {
            CapabilityContext.set(holder.krypton$getCapabilities());
        }

        byte[] cached = BroadcastSerializationCache.get(packet);
        if (cached != null) {
            out.writeBytes(cached);
            // Track stats for the cached write too
            NetworkTrafficStats.INSTANCE.recordPacketType(kryptonfnp$resolveKey(packet), cached.length);
            NetworkTrafficStats.INSTANCE.recordPacketMod(kryptonfnp$resolveModId(packet), cached.length);
            CapabilityContext.clear();
            ci.cancel();
        }
    }

    /**
     * TAIL inject: record traffic stats, populate broadcast cache, and clear CapabilityContext.
     */
    @Inject(method = "encode", at = @At("TAIL"))
    private void kryptonfnp$trackAndCachePacket(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf out, CallbackInfo ci) {
        int bytes = out.readableBytes();
        NetworkTrafficStats.INSTANCE.recordPacketType(kryptonfnp$resolveKey(packet), bytes);
        NetworkTrafficStats.INSTANCE.recordPacketMod(kryptonfnp$resolveModId(packet), bytes);

        // Cache the serialized bytes for broadcast reuse
        if (bytes > 0 && bytes < 65536) { // Only cache reasonably-sized packets
            byte[] serialized = new byte[bytes];
            out.getBytes(out.readerIndex(), serialized);
            BroadcastSerializationCache.put(packet, serialized);
        }

        // Clear capabilities context after encoding
        CapabilityContext.clear();
    }

    @Unique
    private static String kryptonfnp$resolveKey(Packet<?> packet) {
        if (packet instanceof ClientboundCustomPayloadPacket cp) {
            net.minecraft.resources.ResourceLocation id = cp.payload().type().id();
            return "custom:" + id.getNamespace() + "/" + id.getPath();
        }
        if (packet instanceof ServerboundCustomPayloadPacket sp) {
            net.minecraft.resources.ResourceLocation id = sp.payload().type().id();
            return "custom:" + id.getNamespace() + "/" + id.getPath();
        }
        return packet.getClass().getSimpleName();
    }

    @Unique
    private static String kryptonfnp$resolveModId(Packet<?> packet) {
        if (packet instanceof ClientboundCustomPayloadPacket cp) {
            return cp.payload().type().id().getNamespace();
        }
        if (packet instanceof ServerboundCustomPayloadPacket sp) {
            return sp.payload().type().id().getNamespace();
        }
        String pkg = packet.getClass().getPackageName();
        if (pkg.startsWith("net.minecraft.")) return "minecraft";
        String[] parts = pkg.split("\\.", 4);
        return parts.length >= 3 ? parts[2] : "unknown";
    }
}

