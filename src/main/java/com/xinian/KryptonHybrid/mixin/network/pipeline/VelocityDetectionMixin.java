package com.xinian.KryptonHybrid.mixin.network.pipeline;

import com.mojang.authlib.GameProfile;
import com.xinian.KryptonHybrid.shared.KryptonConfig;
import com.xinian.KryptonHybrid.shared.KryptonSharedBootstrap;
import com.xinian.KryptonHybrid.shared.ProxyMode;
import com.xinian.KryptonHybrid.shared.network.KryptonCapabilityHolder;
import com.xinian.KryptonHybrid.shared.network.velocity.VelocityForwardingPayload;
import com.xinian.KryptonHybrid.shared.network.velocity.VelocityLoginQueryPayload;
import com.xinian.KryptonHybrid.shared.network.velocity.VelocityModernForwardingHandler;
import com.xinian.KryptonHybrid.shared.network.security.AnomalyDetector;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
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
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Implements Velocity Modern Forwarding and proxy detection on the backend server.
 *
 * <h3>Modern Forwarding</h3>
 * <p>When {@code proxyMode} is {@link ProxyMode#VELOCITY} or {@link ProxyMode#AUTO}
 * (with a non-empty {@code velocityForwardingSecret}), this mixin:
 * <ol>
 *   <li>Intercepts the offline-mode login path in {@code handleHello()} to send a
 *       {@code velocity:player_info} login plugin request instead of proceeding
 *       with the default offline-mode profile.</li>
 *   <li>Captures the response in {@code handleCustomQueryPacket()}, verifies the
 *       HMAC-SHA256 signature using the shared secret, and extracts the real player
 *       IP, UUID, name, and skin properties from the forwarding data.</li>
 *   <li>Updates the connection's remote address and continues login with the
 *       forwarded {@link GameProfile}.</li>
 * </ol>
 *
 * <h3>Proxy Detection</h3>
 * <p>When modern forwarding is not configured (no secret), falls back to a heuristic:
 * if the server is in offline mode ({@code online-mode=false}), mark the connection
 * as proxied.</p>
 *
 * <p>Marking a connection as proxied forces ZLIB compression on the backend leg
 * and disables custom wire formats until capability negotiation via
 * {@code krypton_hybrid:hello} proves the remote supports them.</p>
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class VelocityDetectionMixin {

    @Shadow @Final
    Connection connection;

    @Shadow @Final
    MinecraftServer server;

    @Shadow
    abstract void startClientVerification(GameProfile profile);

    @Shadow
    public abstract void disconnect(Component reason);

    // ==================== Modern Forwarding ====================

    /**
     * Determines whether Velocity Modern Forwarding should be used for this connection.
     *
     * <p>Requires:
     * <ul>
     *   <li>ProxyMode is AUTO or VELOCITY</li>
     *   <li>A non-empty forwarding secret is configured</li>
     *   <li>The connection is not a memory connection (not singleplayer)</li>
     * </ul>
     */
    @Unique
    private boolean krypton$shouldUseVelocityForwarding() {
        if (KryptonConfig.proxyMode == ProxyMode.NONE) return false;
        if (KryptonConfig.velocityForwardingSecret.isEmpty()) return false;
        return !this.connection.isMemoryConnection();
    }

    /**
     * Redirects the offline-mode {@code startClientVerification} call in
     * {@code handleHello()} to send a Velocity modern forwarding query instead.
     *
     * <p>This targets the second invocation of {@code startClientVerification}
     * (ordinal 1), which is the offline-mode path. The first invocation
     * (ordinal 0) is the singleplayer path and should not be affected.</p>
     */
    @Redirect(
            method = "handleHello",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/network/ServerLoginPacketListenerImpl;startClientVerification(Lcom/mojang/authlib/GameProfile;)V",
                    ordinal = 1
            )
    )
    private void krypton$redirectOfflineModeVerification(
            ServerLoginPacketListenerImpl self, GameProfile offlineProfile) {

        if (krypton$shouldUseVelocityForwarding()) {
            // Send the velocity:player_info login plugin request
            int txId = VelocityModernForwardingHandler.generateTransactionId();
            VelocityModernForwardingHandler.registerPendingQuery(txId);

            // Request the maximum forwarding version (MODERN_LAZY_SESSION for 1.19.3+)
            this.connection.send(new ClientboundCustomQueryPacket(
                    txId,
                    new VelocityLoginQueryPayload(VelocityLoginQueryPayload.MODERN_LAZY_SESSION)
            ));

            KryptonSharedBootstrap.LOGGER.debug(
                    "Sent velocity:player_info query (txId={}) to {}",
                    txId, this.connection.getRemoteAddress());

            // Don't call startClientVerification — state stays at HELLO,
            // tick() will timeout after 600 ticks if no response arrives
        } else {
            // No modern forwarding — proceed normally with the offline profile
            startClientVerification(offlineProfile);
        }
    }

    /**
     * Intercepts {@code handleCustomQueryPacket()} to process Velocity modern
     * forwarding responses before the vanilla handler disconnects the client.
     */
    @Inject(method = "handleCustomQueryPacket", at = @At("HEAD"), cancellable = true)
    private void krypton$handleVelocityForwardingResponse(
            ServerboundCustomQueryAnswerPacket packet, CallbackInfo ci) {

        int txId = packet.transactionId();
        if (!VelocityModernForwardingHandler.isPendingQuery(txId)) {
            return; // Not our query — let vanilla handle it (disconnect)
        }

        VelocityModernForwardingHandler.removePendingQuery(txId);
        ci.cancel(); // Prevent the vanilla disconnect

        if (!(packet.payload() instanceof VelocityForwardingPayload forwardingPayload)) {

            AnomalyDetector.get(this.connection.channel())
                    .recordStrike(AnomalyDetector.AnomalyType.HMAC_FAILURE,
                            "No forwarding payload in velocity response");
            disconnect(Component.literal(
                    "This server requires you to connect through the Velocity proxy."));
            return;
        }


        VelocityModernForwardingHandler.ForwardingResult result =
                VelocityModernForwardingHandler.processForwardingData(
                        forwardingPayload.data(),
                        KryptonConfig.velocityForwardingSecret);

        if (result == null) {
            AnomalyDetector.get(this.connection.channel())
                    .recordStrike(AnomalyDetector.AnomalyType.HMAC_FAILURE,
                            "HMAC verification or forwarding data parse failed");
            disconnect(Component.literal(
                    "Could not verify your connection. Check the forwarding secret."));
            return;
        }

        // Update the connection's remote address to the player's real IP
        if (this.connection instanceof KryptonCapabilityHolder holder) {
            holder.krypton$setAddress(result.remoteAddress());
            // Mark as behind proxy → forces ZLIB, gates custom wire formats
            holder.krypton$getCapabilities().setBehindProxy(true);

            KryptonSharedBootstrap.LOGGER.debug(
                    "Velocity forwarding complete for {} — address updated to {}, "
                    + "proxy mode active",
                    result.profile().getName(), result.remoteAddress());
        }

        // Continue login with the forwarded profile (UUID, name, properties)
        startClientVerification(result.profile());
    }

    // ==================== Heuristic Proxy Detection ====================

    /**
     * Fallback proxy detection at login acknowledgement time.
     * Used when modern forwarding is not configured (no forwarding secret).
     *
     * <p>When {@code proxyMode} is {@link ProxyMode#VELOCITY}, every connection is
     * marked as proxied. When {@code proxyMode} is {@link ProxyMode#AUTO} and no
     * forwarding secret is set, checks whether the server is in offline mode.</p>
     */
    @Inject(method = "handleLoginAcknowledgement", at = @At("HEAD"))
    private void krypton$detectProxyOnLogin(CallbackInfo ci) {
        // Skip heuristic if modern forwarding already handled this connection
        if (this.connection instanceof KryptonCapabilityHolder holder
                && holder.krypton$getCapabilities().isBehindProxy()) {
            return;
        }

        if (KryptonConfig.proxyMode == ProxyMode.VELOCITY) {
            krypton$markBehindProxy();
        } else if (KryptonConfig.proxyMode == ProxyMode.AUTO
                && KryptonConfig.velocityForwardingSecret.isEmpty()) {
            // No forwarding secret → fall back to offline-mode heuristic
            if (!this.server.usesAuthentication()) {
                krypton$markBehindProxy();
            }
        }
    }

    @Unique
    private void krypton$markBehindProxy() {
        if (this.connection instanceof KryptonCapabilityHolder holder) {
            holder.krypton$getCapabilities().setBehindProxy(true);
            KryptonSharedBootstrap.LOGGER.debug(
                    "Proxy mode active (heuristic) for {} — backend compression forced to ZLIB, "
                    + "custom wire formats gated behind krypton_hybrid:hello negotiation",
                    this.connection.getRemoteAddress());
        }
    }
}

