package com.xinian.KryptonHybrid.mixin.network.pipeline;

import com.xinian.KryptonHybrid.shared.KryptonConfig;
import com.xinian.KryptonHybrid.shared.network.security.AnomalyDetector;
import com.xinian.KryptonHybrid.shared.network.security.HandshakeTimeoutHandler;
import com.xinian.KryptonHybrid.shared.network.security.HandshakeValidator;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.server.network.ServerHandshakePacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects into {@link ServerHandshakePacketListenerImpl} to:
 * <ol>
 *   <li>Validate the handshake packet (protocol version, server address) via
 *       {@link HandshakeValidator}.</li>
 *   <li>Advance the {@link HandshakeTimeoutHandler} from HANDSHAKE → LOGIN stage.</li>
 * </ol>
 */
@Mixin(ServerHandshakePacketListenerImpl.class)
public class HandshakeValidatorMixin {

    @Shadow @Final
    private Connection connection;

    @Inject(method = "handleIntention", at = @At("HEAD"), cancellable = true)
    private void krypton$validateHandshake(ClientIntentionPacket packet, CallbackInfo ci) {
        if (!KryptonConfig.securityEnabled) return;

        // ── Validate handshake fields ─────────────────────────────────
        HandshakeValidator.ValidationResult result = HandshakeValidator.validate(
                packet.protocolVersion(),
                packet.hostName(),
                packet.port());

        if (!result.valid()) {
            // Record anomaly
            AnomalyDetector detector = AnomalyDetector.get(this.connection.channel());
            detector.recordStrike(
                    AnomalyDetector.AnomalyType.PROTOCOL_VIOLATION,
                    "Invalid handshake: " + result.reason());

            this.connection.disconnect(Component.literal(
                    "Connection refused: " + result.reason()));
            ci.cancel();
            return;
        }

        // ── Advance timeout to LOGIN stage ────────────────────────────
        HandshakeTimeoutHandler.advanceStage(
                this.connection.channel(), HandshakeTimeoutHandler.Stage.LOGIN);
    }
}

