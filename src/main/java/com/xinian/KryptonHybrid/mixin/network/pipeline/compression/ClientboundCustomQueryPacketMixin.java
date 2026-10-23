package com.xinian.KryptonHybrid.mixin.network.pipeline.compression;

import com.xinian.KryptonHybrid.shared.network.compression.CompressionNegotiation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.custom.CustomQueryPayload;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Reads the server's compression query instead of discarding it, as vanilla does with every query. */
@Mixin(ClientboundCustomQueryPacket.class)
public abstract class ClientboundCustomQueryPacketMixin {

    @Inject(method = "readPayload", at = @At("HEAD"), cancellable = true)
    private static void krypton$readCompressionQuery(ResourceLocation id, FriendlyByteBuf buffer,
                                                     CallbackInfoReturnable<CustomQueryPayload> cir) {
        if (CompressionNegotiation.QUERY_ID.equals(id)) {
            cir.setReturnValue(CompressionNegotiation.Query.read(buffer));
        }
    }
}
