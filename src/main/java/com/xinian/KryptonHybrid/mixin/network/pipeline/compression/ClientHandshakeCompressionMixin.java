package com.xinian.KryptonHybrid.mixin.network.pipeline.compression;

import com.xinian.KryptonHybrid.shared.network.compression.CompressionNegotiation;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client side of {@link CompressionNegotiation}: answers the server's compression query
 * with this side's token and records the exchange before the compression packet arrives.
 * Every other query keeps vanilla's "not understood" answer.
 */
@Mixin(ClientHandshakePacketListenerImpl.class)
public abstract class ClientHandshakeCompressionMixin {
    @Shadow @Final private Connection connection;

    @Inject(method = "handleCustomQuery", at = @At("HEAD"), cancellable = true)
    private void krypton$answerCompressionQuery(ClientboundCustomQueryPacket packet, CallbackInfo ci) {
        if (packet.payload() instanceof CompressionNegotiation.Query query) {
            this.connection.send(new ServerboundCustomQueryAnswerPacket(packet.transactionId(),
                    CompressionNegotiation.answer(this.connection, query.token())));
            ci.cancel();
        }
    }
}
