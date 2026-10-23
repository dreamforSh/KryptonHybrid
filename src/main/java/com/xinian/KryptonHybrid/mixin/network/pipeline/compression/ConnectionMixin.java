package com.xinian.KryptonHybrid.mixin.network.pipeline.compression;

import com.velocitypowered.natives.compression.VelocityCompressor;
import com.velocitypowered.natives.util.Natives;
import io.netty.channel.Channel;
import com.xinian.KryptonHybrid.shared.misc.KryptonPipelineEvent;
import com.xinian.KryptonHybrid.shared.KryptonConfig;
import com.xinian.KryptonHybrid.shared.KryptonSharedBootstrap;
import com.xinian.KryptonHybrid.shared.network.compression.CompressionNegotiation;
import com.xinian.KryptonHybrid.shared.network.compression.MinecraftCompressDecoder;
import com.xinian.KryptonHybrid.shared.network.compression.MinecraftCompressEncoder;
import com.xinian.KryptonHybrid.shared.network.compression.ZstdCompressDecoder;
import com.xinian.KryptonHybrid.shared.network.compression.ZstdCompressEncoder;
import com.xinian.KryptonHybrid.shared.network.compression.ZstdUtil;
import net.minecraft.network.CompressionDecoder;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public class ConnectionMixin {
    @Shadow
    private Channel channel;

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
            if (kryptonfnp$isKryptonOrVanillaDecompressor(this.channel.pipeline().get("decompress"))) {
                this.channel.pipeline().remove("decompress");
            }
            if (kryptonfnp$isKryptonOrVanillaCompressor(this.channel.pipeline().get("compress"))) {
                this.channel.pipeline().remove("compress");
            }

            this.channel.pipeline().fireUserEventTriggered(KryptonPipelineEvent.COMPRESSION_DISABLED);
        } else {
            Object existingDecoder = channel.pipeline().get("decompress");
            Object existingEncoder = channel.pipeline().get("compress");

            if (existingDecoder instanceof ZstdCompressDecoder
                    && existingEncoder instanceof ZstdCompressEncoder) {
                ((ZstdCompressDecoder) existingDecoder).setThreshold(compressionThreshold);
                ((ZstdCompressEncoder) existingEncoder).setThreshold(compressionThreshold);
                this.channel.pipeline().fireUserEventTriggered(KryptonPipelineEvent.COMPRESSION_THRESHOLD_UPDATED);

            } else if (existingDecoder instanceof MinecraftCompressDecoder
                    && existingEncoder instanceof MinecraftCompressEncoder) {
                ((MinecraftCompressDecoder) existingDecoder).setThreshold(compressionThreshold);
                ((MinecraftCompressEncoder) existingEncoder).setThreshold(compressionThreshold);
                this.channel.pipeline().fireUserEventTriggered(KryptonPipelineEvent.COMPRESSION_THRESHOLD_UPDATED);

            } else {
                Connection connection = (Connection) (Object) this;
                boolean zstd = CompressionNegotiation.shouldUseZstd(connection);
                KryptonSharedBootstrap.LOGGER.debug("Compression for {}: {} ({})",
                        this.channel.remoteAddress(), zstd ? "zstd" : "zlib",
                        CompressionNegotiation.describe(connection));
                if (zstd) {
                    // One dictionary for both directions: the one named in the token both sides
                    // agreed to (none for id 0). If the config changed during this login so it
                    // is no longer loaded, end the connection rather than send unreadable frames.
                    long agreedDictionary = CompressionNegotiation.agreedDictionaryId(connection);
                    byte[] dictionary = null;
                    if (agreedDictionary != 0L) {
                        try {
                            dictionary = ZstdUtil.compressionDictionary();
                        } catch (IllegalStateException unavailable) {
                            dictionary = null;
                        }
                    }
                    if (agreedDictionary != 0L
                            && (dictionary == null || ZstdUtil.getCurrentDictionaryId() != agreedDictionary)) {
                        connection.disconnect(Component.literal(
                                "Zstd dictionary changed while logging in; please reconnect."));
                        ci.cancel();
                        return;
                    }
                    // Batch frames only go from the server (validate = true) to the client.
                    boolean batchFrames = CompressionNegotiation.supportsBatchFrames(connection);
                    ZstdCompressEncoder zstdEncoder =
                            new ZstdCompressEncoder(compressionThreshold, ZstdUtil.createCompressor(), dictionary,
                                    batchFrames && validate);
                    ZstdCompressDecoder zstdDecoder =
                            new ZstdCompressDecoder(compressionThreshold, validate, ZstdUtil.createDecompressor(),
                                    dictionary == null ? null : ZstdUtil.createDictionaryDecompressor(dictionary),
                                    batchFrames && !validate);

                    channel.pipeline().addBefore("decoder", "decompress", zstdDecoder);
                    channel.pipeline().addBefore("encoder", "compress", zstdEncoder);
                } else {
                    VelocityCompressor compressor = Natives.compress.get().create(4);

                    MinecraftCompressEncoder encoder =
                            new MinecraftCompressEncoder(compressionThreshold, compressor);
                    MinecraftCompressDecoder decoder =
                            new MinecraftCompressDecoder(compressionThreshold, validate, compressor);

                    channel.pipeline().addBefore("decoder", "decompress", decoder);
                    channel.pipeline().addBefore("encoder", "compress", encoder);
                }

                this.channel.pipeline().fireUserEventTriggered(KryptonPipelineEvent.COMPRESSION_ENABLED);
            }
        }

        ci.cancel();
    }
}

