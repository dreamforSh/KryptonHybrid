package com.xinian.KryptonHybrid.mixin.network.pipeline.compression;

import com.mojang.authlib.GameProfile;
import com.xinian.KryptonHybrid.shared.network.compression.CompressionNegotiation;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Server side of {@link CompressionNegotiation}: before the compression packet goes out,
 * asks the client for its compression token with a login custom query and waits for the
 * answer. {@code tick()} keeps calling {@code verifyLoginAndFinishConnectionSetup} while
 * the login is {@code VERIFYING}; it proceeds as usual once the answer is in. A client
 * that never answers runs into vanilla's slow-login timeout.
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginCompressionMixin {
    @Shadow @Final Connection connection;
    @Shadow @Final MinecraftServer server;

    /** The token sent with the query, or {@code null} before it was sent. */
    @Unique
    private volatile String krypton$sentToken;
    /** Set once there is nothing (more) to wait for. */
    @Unique
    private volatile boolean krypton$negotiated;

    @Inject(method = "verifyLoginAndFinishConnectionSetup", at = @At("HEAD"), cancellable = true)
    private void krypton$queryCompressionFirst(GameProfile profile, CallbackInfo ci) {
        if (this.krypton$negotiated
                || this.server.getCompressionThreshold() < 0
                || this.connection.isMemoryConnection()) {
            return;
        }
        if (this.krypton$sentToken == null) {
            String token = CompressionNegotiation.localToken();
            if (!CompressionNegotiation.isZstdToken(token)) {
                // Nothing to negotiate: this side only offers zlib.
                this.krypton$negotiated = true;
                return;
            }
            this.krypton$sentToken = token;
            this.connection.send(new ClientboundCustomQueryPacket(
                    CompressionNegotiation.TRANSACTION_ID, new CompressionNegotiation.Query(token)));
        }
        ci.cancel();
    }

    @Inject(method = "handleCustomQueryPacket", at = @At("HEAD"), cancellable = true)
    private void krypton$receiveCompressionAnswer(ServerboundCustomQueryAnswerPacket packet, CallbackInfo ci) {
        String sent = this.krypton$sentToken;
        if (packet.transactionId() != CompressionNegotiation.TRANSACTION_ID || sent == null || this.krypton$negotiated) {
            return;
        }
        CompressionNegotiation.recordAgreement(this.connection, sent,
                packet.payload() instanceof CompressionNegotiation.Answer answer ? answer.token() : null);
        this.krypton$negotiated = true;
        ci.cancel();
    }
}
