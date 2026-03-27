package com.xinian.KryptonHybrid.mixin.network.microopt;

import com.xinian.KryptonHybrid.shared.network.util.VarLongUtil;
import net.minecraft.network.VarLong;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Replaces {@link VarLong#getByteSize(long)} with an optimized lookup-table variant.
 * In NeoForge 1.21.1 the VarLong size calculation was moved from {@code FriendlyByteBuf}
 * to the {@link VarLong} utility class. This mixin ports the same optimisation.
 */
@Mixin(VarLong.class)
public class VarLongMixin {

    @Inject(method = "getByteSize", at = @At("HEAD"), cancellable = true)
    private static void getByteSize$kryptonfnp(long v, CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(VarLongUtil.getVarLongLength(v));
    }
}

