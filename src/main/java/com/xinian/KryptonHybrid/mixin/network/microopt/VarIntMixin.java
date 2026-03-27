package com.xinian.KryptonHybrid.mixin.network.microopt;

import com.xinian.KryptonHybrid.shared.network.util.VarIntUtil;
import net.minecraft.network.VarInt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Replaces {@link VarInt#getByteSize(int)} with an optimized lookup-table variant.
 * In NeoForge 1.21.1 the VarInt size calculation was moved from {@code FriendlyByteBuf}
 * to the {@link VarInt} utility class. This mixin ports the same optimisation.
 */
@Mixin(VarInt.class)
public class VarIntMixin {

    @Inject(method = "getByteSize", at = @At("HEAD"), cancellable = true)
    private static void getByteSize$kryptonfnp(int v, CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(VarIntUtil.getVarIntLength(v));
    }
}

