package com.xinian.KryptonHybrid.mixin.network.readlimit;

import com.xinian.KryptonHybrid.shared.network.security.InboundReadLimits;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.Utf8String;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * String read limit for every path ({@code FriendlyByteBuf.readUtf} and
 * {@code ByteBufCodecs.STRING_UTF8} both end here): the encoded length is checked
 * before vanilla allocates, and the character limit is lowered to the security limit
 * for vanilla's own check.
 */
@Mixin(Utf8String.class)
public abstract class Utf8StringReadLimitMixin {

    @Inject(method = "read", at = @At("HEAD"))
    private static void krypton$checkLength(ByteBuf buffer, int maxLength, CallbackInfoReturnable<String> cir) {
        if (InboundReadLimits.active()) {
            InboundReadLimits.checkString(buffer);
        }
    }

    @ModifyVariable(method = "read", at = @At("HEAD"), argsOnly = true)
    private static int krypton$limitChars(int maxLength) {
        return InboundReadLimits.active() ? InboundReadLimits.maxStringChars(maxLength) : maxLength;
    }
}
