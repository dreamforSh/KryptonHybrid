package com.xinian.KryptonHybrid.mixin.network.pipeline.compression;

import com.velocitypowered.natives.compression.VelocityCompressor;
import com.velocitypowered.natives.util.Natives;
import io.netty.channel.Channel;
import com.xinian.KryptonHybrid.mixin.network.pipeline.ConnectionAccessor;
import com.xinian.KryptonHybrid.shared.misc.KryptonPipelineEvent;
import com.xinian.KryptonHybrid.shared.KryptonConfig;
import com.xinian.KryptonHybrid.shared.network.compression.MinecraftCompressDecoder;
import com.xinian.KryptonHybrid.shared.network.compression.MinecraftCompressEncoder;
import com.xinian.KryptonHybrid.shared.network.compression.ZstdCompressDecoder;
import com.xinian.KryptonHybrid.shared.network.compression.ZstdCompressEncoder;
import com.xinian.KryptonHybrid.shared.network.compression.ZstdUtil;
import net.minecraft.network.CompressionDecoder;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public class ConnectionMixin {
    @Unique
    private Channel kryptonfnp$channel() {
        return ((ConnectionAccessor) this).krypton$getChannel();
    }

    @Unique
    private static boolean kryptonfnp$isKryptonOrVanillaDecompressor(Object o) {
        return o instanceof CompressionEncoder
                || o instanceof MinecraftCompressDecoder
                || o instanceof ZstdCompressDecoder;
    }

    @Unique
    private static boolean kryptonfnp$isKryptonOrVanillaCompressor(Object o) {
        return o instanceof CompressionDecoder
                || o instanceof MinecraftCompressEncoder
                || o instanceof ZstdCompressEncoder;
    }

    @Inject(method = "setupCompression", at = @At("HEAD"), cancellable = true)
    public void setCompressionThreshold(int compressionThreshold, boolean validate, CallbackInfo ci) {
        if (compressionThreshold < 0) {
            if (kryptonfnp$isKryptonOrVanillaDecompressor(this.kryptonfnp$channel().pipeline().get("decompress"))) {
                this.kryptonfnp$channel().pipeline().remove("decompress");
            }
            if (kryptonfnp$isKryptonOrVanillaCompressor(this.kryptonfnp$channel().pipeline().get("compress"))) {
                this.kryptonfnp$channel().pipeline().remove("compress");
            }

            this.kryptonfnp$channel().pipeline().fireUserEventTriggered(KryptonPipelineEvent.COMPRESSION_DISABLED);
        } else {
            Object existingDecoder = this.kryptonfnp$channel().pipeline().get("decompress");
            Object existingEncoder = this.kryptonfnp$channel().pipeline().get("compress");

            if (existingDecoder instanceof ZstdCompressDecoder
                    && existingEncoder instanceof ZstdCompressEncoder) {
                ((ZstdCompressDecoder) existingDecoder).setThreshold(compressionThreshold);
                ((ZstdCompressEncoder) existingEncoder).setThreshold(compressionThreshold);
                this.kryptonfnp$channel().pipeline().fireUserEventTriggered(KryptonPipelineEvent.COMPRESSION_THRESHOLD_UPDATED);

            } else if (existingDecoder instanceof MinecraftCompressDecoder
                    && existingEncoder instanceof MinecraftCompressEncoder) {
                ((MinecraftCompressDecoder) existingDecoder).setThreshold(compressionThreshold);
                ((MinecraftCompressEncoder) existingEncoder).setThreshold(compressionThreshold);
                this.kryptonfnp$channel().pipeline().fireUserEventTriggered(KryptonPipelineEvent.COMPRESSION_THRESHOLD_UPDATED);

            } else {
                if (ZstdUtil.isEnabled()) {
                    ZstdCompressEncoder zstdEncoder =
                            new ZstdCompressEncoder(compressionThreshold, ZstdUtil.createCompressor());
                    ZstdCompressDecoder zstdDecoder =
                            new ZstdCompressDecoder(compressionThreshold, validate, ZstdUtil.createDecompressor());

                    this.kryptonfnp$channel().pipeline().addBefore("decoder", "decompress", zstdDecoder);
                    this.kryptonfnp$channel().pipeline().addBefore("encoder", "compress", zstdEncoder);
                } else {
                    VelocityCompressor compressor = Natives.compress.get().create(4);

                    MinecraftCompressEncoder encoder =
                            new MinecraftCompressEncoder(compressionThreshold, compressor);
                    MinecraftCompressDecoder decoder =
                            new MinecraftCompressDecoder(compressionThreshold, validate, compressor);

                    this.kryptonfnp$channel().pipeline().addBefore("decoder", "decompress", decoder);
                    this.kryptonfnp$channel().pipeline().addBefore("encoder", "compress", encoder);
                }

                this.kryptonfnp$channel().pipeline().fireUserEventTriggered(KryptonPipelineEvent.COMPRESSION_ENABLED);
            }
        }

        ci.cancel();
    }
}

