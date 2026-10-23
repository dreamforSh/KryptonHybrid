package com.xinian.KryptonHybrid.mixin.network.pipeline.compression;

import com.xinian.KryptonHybrid.shared.network.compression.CompressionNegotiation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.network.protocol.login.custom.CustomQueryAnswerPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Reads the answer to the compression query instead of discarding it, as vanilla does
 * with every query answer. The answer is identified by its transaction id; it is either
 * absent (the client did not understand the query) or a {@link CompressionNegotiation.Answer}.
 */
@Mixin(ServerboundCustomQueryAnswerPacket.class)
public abstract class ServerboundCustomQueryAnswerPacketMixin {

    @Inject(method = "readPayload", at = @At("HEAD"), cancellable = true)
    private static void krypton$readCompressionAnswer(int transactionId, FriendlyByteBuf buffer,
                                                      CallbackInfoReturnable<CustomQueryAnswerPayload> cir) {
        if (transactionId == CompressionNegotiation.TRANSACTION_ID) {
            cir.setReturnValue(buffer.readBoolean() ? CompressionNegotiation.Answer.read(buffer) : null);
        }
    }
}
