package com.xinian.KryptonHybrid.mixin.network.pipeline;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import com.xinian.KryptonHybrid.shared.network.NetworkTrafficStats;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects into {@link PacketEncoder} to record per-packet-type and per-mod traffic stats
 * for the {@code /krypton stats} command.
 */
@Mixin(PacketEncoder.class)
public class PacketEncoderMixin {

    @Inject(method = "encode", at = @At("TAIL"))
    private void kryptonfnp$trackPacketSent(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf out, CallbackInfo ci) {
        int bytes = out.readableBytes();
        NetworkTrafficStats.INSTANCE.recordPacketType(kryptonfnp$resolveKey(packet), bytes);
        NetworkTrafficStats.INSTANCE.recordPacketMod(kryptonfnp$resolveModId(packet), bytes);
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

