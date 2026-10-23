package com.xinian.KryptonHybrid.mixin.network.microopt;

import com.xinian.KryptonHybrid.shared.network.security.InboundReadLimits;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.handler.codec.EncoderException;
import com.xinian.KryptonHybrid.shared.network.util.VarIntUtil;
import com.xinian.KryptonHybrid.shared.network.util.VarLongUtil;
import net.minecraft.network.FriendlyByteBuf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.charset.StandardCharsets;
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
 *   <li><strong>{@code writeUtf}</strong> ??single-pass write via {@link ByteBufUtil#utf8Bytes}</li>
 *   <li><strong>{@code writeVarInt}</strong> ??peeled 1/2/3/4/5-byte paths, direct source writes</li>
 *   <li><strong>{@code writeVarLong}</strong> ??peeled 1/2-byte paths</li>
 *   <li><strong>{@code readVarInt}</strong> ??branchless 4-byte getIntLE + bit-twiddling fast path
 *       (same algorithm as {@code Varint21FrameDecoderMixin}); eliminates per-byte conditional
 *       branches for 1?? byte VarInts (the vast majority of Minecraft traffic)</li>
 * </ul>
 */
@Mixin(value = FriendlyByteBuf.class, priority = 900)
public abstract class FriendlyByteBufMixin extends ByteBuf {

    @Accessor("source")
    abstract ByteBuf krypton$source();


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
        VarIntUtil.writeVarInt(this.krypton$source(), utf8Bytes);
        this.writeCharSequence(string, StandardCharsets.UTF_8);
        cir.setReturnValue((FriendlyByteBuf) (Object) this);
    }

    /**
     * Optimized VarInt reading: branchless 4-byte fast path using the same bit-twiddling
     * technique from the Varint21FrameDecoder (netty#14050).
     *
     * <p>Reads the underlying {@code source} buffer directly, bypassing the vanilla
     * {@code VarInt.read(this.krypton$source())} delegate call.  For 1?? byte VarInts (which
     * cover values 0??68,435,455 ??virtually all Minecraft VarInts), the decode is
     * performed with a single {@code getIntLE}, two bitwise merges, and zero conditional
     * branches.</p>
     *
     * <p>This is called on every inbound packet for at least the packet ID, and often
     * dozens of times per packet (collection sizes, string lengths, enum ordinals,
     * entity IDs, block positions, etc.).</p>
     */
    @Inject(method = "readVarInt", at = @At("HEAD"), cancellable = true)
    private void readVarInt$kryptonfnp(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(VarIntUtil.readVarInt(this.krypton$source()));
    }

    // ------------------------------------------------------------------
    //  Security read limits (server inbound only; see InboundReadLimits).
    //  Each checks the length or count the peer sent before vanilla reads it.
    // ------------------------------------------------------------------

    @Inject(method = "readUtf(I)Ljava/lang/String;", at = @At("HEAD"))
    private void readUtf$kryptonGuard(int maxLength, CallbackInfoReturnable<String> cir) {
        if (InboundReadLimits.active()) {
            InboundReadLimits.checkString(this.krypton$source());
        }
    }

    /** Lowers the character limit to the security limit for vanilla's own check. */
    @ModifyVariable(method = "readUtf(I)Ljava/lang/String;", at = @At("HEAD"), argsOnly = true)
    private int readUtf$kryptonLimitChars(int maxLength) {
        return InboundReadLimits.active() ? InboundReadLimits.maxStringChars(maxLength) : maxLength;
    }

    @Inject(method = "readByteArray(I)[B", at = @At("HEAD"))
    private void readByteArray$kryptonGuard(int maxLength, CallbackInfoReturnable<byte[]> cir) {
        if (InboundReadLimits.active()) {
            InboundReadLimits.checkByteArray(this.krypton$source());
        }
    }

    @Inject(method = "readCollection", at = @At("HEAD"))
    private void readCollection$kryptonGuard(IntFunction<?> collectionFactory,
                                             FriendlyByteBuf.Reader<?> elementReader,
                                             CallbackInfoReturnable<?> cir) {
        if (InboundReadLimits.active()) {
            InboundReadLimits.checkCollection(this.krypton$source());
        }
    }

    @Inject(method = "readMap(Ljava/util/function/IntFunction;Lnet/minecraft/network/FriendlyByteBuf$Reader;Lnet/minecraft/network/FriendlyByteBuf$Reader;)Ljava/util/Map;",
            at = @At("HEAD"))
    private void readMap$kryptonGuard(IntFunction<?> mapFactory,
                                      FriendlyByteBuf.Reader<?> keyReader,
                                      FriendlyByteBuf.Reader<?> valueReader,
                                      CallbackInfoReturnable<?> cir) {
        if (InboundReadLimits.active()) {
            InboundReadLimits.checkMap(this.krypton$source());
        }
    }

    @Inject(method = "readWithCount", at = @At("HEAD"))
    private void readWithCount$kryptonGuard(Consumer<FriendlyByteBuf> reader, CallbackInfo ci) {
        if (InboundReadLimits.active()) {
            InboundReadLimits.checkCounted(this.krypton$source());
        }
    }

    /**
     * Optimized VarInt writing: peel the 1- and 2-byte cases explicitly as they are the most
     * common sizes, improving branch prediction and inlining. Writes directly to the underlying
     * {@code source} buffer to avoid per-write delegate overhead.
     */
    @Inject(method = "writeVarInt", at = @At("HEAD"), cancellable = true)
    private void writeVarInt$kryptonfnp(int value, CallbackInfoReturnable<FriendlyByteBuf> cir) {
        VarIntUtil.writeVarInt(this.krypton$source(), value);
        cir.setReturnValue((FriendlyByteBuf) (Object) this);
    }

    /**
     * Optimized VarLong writing: peel the common 1- and 2-byte cases explicitly.
     */
    @Inject(method = "writeVarLong", at = @At("HEAD"), cancellable = true)
    private void writeVarLong$kryptonfnp(long value, CallbackInfoReturnable<FriendlyByteBuf> cir) {
        if ((value & VarLongUtil.MASK_7_BITS) == 0L) {
            this.krypton$source().writeByte((int) value);
        } else if ((value & VarLongUtil.MASK_14_BITS) == 0L) {
            this.krypton$source().writeShort((int) ((value & 0x7FL) | 0x80L) << 8 | (int) (value >>> 7));
        } else {
            VarLongUtil.writeVarLongFull(this.krypton$source(), value);
        }
        cir.setReturnValue((FriendlyByteBuf) (Object) this);
    }
}
