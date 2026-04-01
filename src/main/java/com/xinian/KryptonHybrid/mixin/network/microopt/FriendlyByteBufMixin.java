package com.xinian.KryptonHybrid.mixin.network.microopt;

import com.xinian.KryptonHybrid.shared.KryptonConfig;
import com.xinian.KryptonHybrid.shared.network.security.SecurityMetrics;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.network.codec.StreamDecoder;
import com.xinian.KryptonHybrid.shared.network.util.VarIntUtil;
import com.xinian.KryptonHybrid.shared.network.util.VarLongUtil;
import net.minecraft.network.FriendlyByteBuf;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/**
 * Micro-optimizations for {@link FriendlyByteBuf} serialization and deserialization.
 * <p>
 * Uses {@code @Inject(HEAD, cancellable=true)} instead of {@code @Overwrite} to preserve the
 * original method bytecode, allowing other mods (e.g. Mantle, PacketFixer) to apply their own
 * injections without conflict.
 *
 * <h3>Optimized methods</h3>
 * <ul>
 *   <li><strong>{@code writeUtf}</strong> — single-pass write via {@link ByteBufUtil#utf8Bytes}</li>
 *   <li><strong>{@code writeVarInt}</strong> — peeled 1/2/3/4/5-byte paths, direct source writes</li>
 *   <li><strong>{@code writeVarLong}</strong> — peeled 1/2-byte paths</li>
 *   <li><strong>{@code readVarInt}</strong> — branchless 4-byte getIntLE + bit-twiddling fast path
 *       (same algorithm as {@code Varint21FrameDecoderMixin}); eliminates per-byte conditional
 *       branches for 1–4 byte VarInts (the vast majority of Minecraft traffic)</li>
 * </ul>
 */
@Mixin(value = FriendlyByteBuf.class, priority = 900)
public abstract class FriendlyByteBufMixin extends ByteBuf {

    @Shadow
    @Final
    private ByteBuf source;

    @Shadow
    public abstract FriendlyByteBuf writeVarInt(int value);

    @Shadow
    public abstract int readVarInt();


    /**
     * Use {@link ByteBufUtil#utf8Bytes(CharSequence)} to compute byte length ahead of time
     * and {@link ByteBuf#writeCharSequence} for a single-pass write, avoiding intermediate
     * {@code byte[]} allocation.
     */
    @Inject(method = "writeUtf(Ljava/lang/String;I)Lnet/minecraft/network/FriendlyByteBuf;", at = @At("HEAD"), cancellable = true)
    private void writeUtf$kryptonfnp(String string, int maxLength, CallbackInfoReturnable<FriendlyByteBuf> cir) {
        if (string.length() > maxLength) {
            throw new EncoderException("String too big (was " + string.length() + " characters, max " + maxLength + ")");
        }
        int utf8Bytes = ByteBufUtil.utf8Bytes(string);
        if (utf8Bytes > maxLength * 3) {
            throw new EncoderException("String too big (was " + utf8Bytes + " bytes encoded, max " + (maxLength * 3) + ")");
        }
        this.writeVarInt(utf8Bytes);
        this.writeCharSequence(string, StandardCharsets.UTF_8);
        cir.setReturnValue((FriendlyByteBuf) (Object) this);
    }

    /**
     * Optimized VarInt reading: branchless 4-byte fast path using the same bit-twiddling
     * technique from the Varint21FrameDecoder (netty#14050).
     *
     * <p>Reads the underlying {@code source} buffer directly, bypassing the vanilla
     * {@code VarInt.read(this.source)} delegate call.  For 1–4 byte VarInts (which
     * cover values 0–268,435,455 — virtually all Minecraft VarInts), the decode is
     * performed with a single {@code getIntLE}, two bitwise merges, and zero conditional
     * branches.</p>
     *
     * <p>This is called on every inbound packet for at least the packet ID, and often
     * dozens of times per packet (collection sizes, string lengths, enum ordinals,
     * entity IDs, block positions, etc.).</p>
     */
    @Inject(method = "readVarInt", at = @At("HEAD"), cancellable = true)
    private void readVarInt$kryptonfnp(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(VarIntUtil.readVarInt(this.source));
    }

    @Inject(method = "readUtf(I)Ljava/lang/String;", at = @At("HEAD"))
    private void readUtf$kryptonGuard(int maxLength, CallbackInfoReturnable<String> cir) {
        if (!KryptonConfig.securityEnabled || !KryptonConfig.securityReadLimitsEnabled) {
            return;
        }
        int hardLimit = KryptonConfig.securityMaxStringChars;
        if (maxLength > hardLimit) {
            SecurityMetrics.INSTANCE.recordReadLimitRejected();
            throw new DecoderException("String maxLength " + maxLength + " exceeds security limit " + hardLimit);
        }
    }

    @Inject(method = "readByteArray(I)[B", at = @At("HEAD"), cancellable = true)
    private void readByteArray$kryptonGuard(int maxLength, CallbackInfoReturnable<byte[]> cir) {
        if (!KryptonConfig.securityEnabled || !KryptonConfig.securityReadLimitsEnabled) {
            return;
        }

        int effectiveMax = Math.min(maxLength, KryptonConfig.securityMaxByteArrayBytes);
        int size = VarIntUtil.readVarInt(this.source);
        if (size > effectiveMax) {
            SecurityMetrics.INSTANCE.recordReadLimitRejected();
            throw new DecoderException("ByteArray with size " + size + " exceeds security max " + effectiveMax);
        }

        byte[] out = new byte[size];
        this.source.readBytes(out);
        cir.setReturnValue(out);
    }

    @Inject(method = "readCollection", at = @At("HEAD"), cancellable = true)
    private <T, C extends Collection<T>> void readCollection$kryptonGuard(
            IntFunction<C> collectionFactory,
            StreamDecoder<? super FriendlyByteBuf, T> elementReader,
            CallbackInfoReturnable<C> cir) {
        if (!KryptonConfig.securityEnabled || !KryptonConfig.securityReadLimitsEnabled) {
            return;
        }

        int count = this.readVarInt();
        int max = KryptonConfig.securityMaxCollectionElements;
        if (count < 0 || count > max) {
            SecurityMetrics.INSTANCE.recordReadLimitRejected();
            throw new DecoderException("Collection with size " + count + " exceeds security max " + max);
        }

        C collection = collectionFactory.apply(count);
        FriendlyByteBuf self = (FriendlyByteBuf) (Object) this;
        for (int i = 0; i < count; i++) {
            collection.add(elementReader.decode(self));
        }
        cir.setReturnValue(collection);
    }

    @Inject(method = "readMap(Ljava/util/function/IntFunction;Lnet/minecraft/network/codec/StreamDecoder;Lnet/minecraft/network/codec/StreamDecoder;)Ljava/util/Map;", at = @At("HEAD"), cancellable = true)
    private <K, V, M extends Map<K, V>> void readMap$kryptonGuard(
            IntFunction<M> mapFactory,
            StreamDecoder<? super FriendlyByteBuf, K> keyReader,
            StreamDecoder<? super FriendlyByteBuf, V> valueReader,
            CallbackInfoReturnable<M> cir) {
        if (!KryptonConfig.securityEnabled || !KryptonConfig.securityReadLimitsEnabled) {
            return;
        }

        int count = this.readVarInt();
        int max = KryptonConfig.securityMaxMapEntries;
        if (count < 0 || count > max) {
            SecurityMetrics.INSTANCE.recordReadLimitRejected();
            throw new DecoderException("Map with size " + count + " exceeds security max " + max);
        }

        M map = mapFactory.apply(count);
        FriendlyByteBuf self = (FriendlyByteBuf) (Object) this;
        for (int i = 0; i < count; i++) {
            map.put(keyReader.decode(self), valueReader.decode(self));
        }
        cir.setReturnValue(map);
    }

    @Inject(method = "readMap(Lnet/minecraft/network/codec/StreamDecoder;Lnet/minecraft/network/codec/StreamDecoder;)Ljava/util/Map;", at = @At("HEAD"), cancellable = true)
    private <K, V> void readMapSimple$kryptonGuard(
            StreamDecoder<? super FriendlyByteBuf, K> keyReader,
            StreamDecoder<? super FriendlyByteBuf, V> valueReader,
            CallbackInfoReturnable<Map<K, V>> cir) {
        if (!KryptonConfig.securityEnabled || !KryptonConfig.securityReadLimitsEnabled) {
            return;
        }

        int count = this.readVarInt();
        int max = KryptonConfig.securityMaxMapEntries;
        if (count < 0 || count > max) {
            SecurityMetrics.INSTANCE.recordReadLimitRejected();
            throw new DecoderException("Map with size " + count + " exceeds security max " + max);
        }

        Map<K, V> map = new HashMap<>(Math.max(1, count));
        FriendlyByteBuf self = (FriendlyByteBuf) (Object) this;
        for (int i = 0; i < count; i++) {
            map.put(keyReader.decode(self), valueReader.decode(self));
        }
        cir.setReturnValue(map);
    }

    @Inject(method = "readWithCount", at = @At("HEAD"), cancellable = true)
    private void readWithCount$kryptonGuard(Consumer<FriendlyByteBuf> reader, CallbackInfo ci) {
        if (!KryptonConfig.securityEnabled || !KryptonConfig.securityReadLimitsEnabled) {
            return;
        }

        int count = this.readVarInt();
        int max = KryptonConfig.securityMaxCountedElements;
        if (count < 0 || count > max) {
            SecurityMetrics.INSTANCE.recordReadLimitRejected();
            throw new DecoderException("Counted section size " + count + " exceeds security max " + max);
        }

        FriendlyByteBuf self = (FriendlyByteBuf) (Object) this;
        for (int i = 0; i < count; i++) {
            reader.accept(self);
        }
        ci.cancel();
    }

    /**
     * Optimized VarInt writing: peel the 1- and 2-byte cases explicitly as they are the most
     * common sizes, improving branch prediction and inlining. Writes directly to the underlying
     * {@code source} buffer to avoid per-write delegate overhead.
     */
    @Inject(method = "writeVarInt", at = @At("HEAD"), cancellable = true)
    private void writeVarInt$kryptonfnp(int value, CallbackInfoReturnable<FriendlyByteBuf> cir) {
        if ((value & VarIntUtil.MASK_7_BITS) == 0) {
            this.source.writeByte(value);
        } else if ((value & VarIntUtil.MASK_14_BITS) == 0) {
            this.source.writeShort((value & 0x7F | 0x80) << 8 | (value >>> 7));
        } else if ((value & VarIntUtil.MASK_21_BITS) == 0) {
            this.source.writeMedium((value & 0x7F | 0x80) << 16
                    | ((value >>> 7) & 0x7F | 0x80) << 8
                    | (value >>> 14));
        } else if ((value & VarIntUtil.MASK_28_BITS) == 0) {
            this.source.writeInt((value & 0x7F | 0x80) << 24
                    | ((value >>> 7) & 0x7F | 0x80) << 16
                    | ((value >>> 14) & 0x7F | 0x80) << 8
                    | (value >>> 21));
        } else {
            this.source.writeInt((value & 0x7F | 0x80) << 24
                    | ((value >>> 7) & 0x7F | 0x80) << 16
                    | ((value >>> 14) & 0x7F | 0x80) << 8
                    | ((value >>> 21) & 0x7F | 0x80));
            this.source.writeByte(value >>> 28);
        }
        cir.setReturnValue((FriendlyByteBuf) (Object) this);
    }

    /**
     * Optimized VarLong writing: peel the common 1- and 2-byte cases explicitly.
     */
    @Inject(method = "writeVarLong", at = @At("HEAD"), cancellable = true)
    private void writeVarLong$kryptonfnp(long value, CallbackInfoReturnable<FriendlyByteBuf> cir) {
        if ((value & VarLongUtil.MASK_7_BITS) == 0L) {
            this.source.writeByte((int) value);
        } else if ((value & VarLongUtil.MASK_14_BITS) == 0L) {
            this.source.writeShort((int) ((value & 0x7FL) | 0x80L) << 8 | (int) (value >>> 7));
        } else {
            VarLongUtil.writeVarLongFull(this.source, value);
        }
        cir.setReturnValue((FriendlyByteBuf) (Object) this);
    }
}
