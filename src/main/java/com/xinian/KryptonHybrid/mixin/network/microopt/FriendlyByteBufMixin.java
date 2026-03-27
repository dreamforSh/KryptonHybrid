package com.xinian.KryptonHybrid.mixin.network.microopt;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.handler.codec.EncoderException;
import com.xinian.KryptonHybrid.shared.network.util.VarIntUtil;
import com.xinian.KryptonHybrid.shared.network.util.VarLongUtil;
import net.minecraft.network.FriendlyByteBuf;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.charset.StandardCharsets;

/**
 * Micro-optimizations for {@link FriendlyByteBuf} serialization.
 * <p>
 * Uses {@code @Inject(HEAD, cancellable=true)} instead of {@code @Overwrite} to preserve the
 * original method bytecode, allowing other mods (e.g. Mantle, PacketFixer) to apply their own
 * injections without conflict.
 */
@Mixin(value = FriendlyByteBuf.class, priority = 900)
public abstract class FriendlyByteBufMixin extends ByteBuf {

    @Shadow
    @Final
    private ByteBuf source;

    @Shadow
    public abstract FriendlyByteBuf writeVarInt(int value);


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
