package com.xinian.KryptonHybrid.mixin.network.readlimit;

import com.xinian.KryptonHybrid.shared.network.security.InboundReadLimits;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Collection and map size limit for {@code StreamCodec}s ({@code ByteBufCodecs.collection}, {@code map}, ...). */
@Mixin(ByteBufCodecs.class)
public interface ByteBufCodecsReadLimitMixin {

    @Inject(method = "readCount", at = @At("HEAD"))
    private static void krypton$checkCount(ByteBuf buffer, int maxSize, CallbackInfoReturnable<Integer> cir) {
        if (InboundReadLimits.active()) {
            InboundReadLimits.checkCollection(buffer);
        }
    }
}
