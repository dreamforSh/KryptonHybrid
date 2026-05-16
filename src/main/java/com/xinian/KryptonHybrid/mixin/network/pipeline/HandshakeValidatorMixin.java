package com.xinian.KryptonHybrid.mixin.network.pipeline;

import com.xinian.KryptonHybrid.shared.KryptonConfig;
import com.xinian.KryptonHybrid.shared.network.control.PacketControlPhase;
import com.xinian.KryptonHybrid.shared.network.control.PacketControlState;
import com.xinian.KryptonHybrid.shared.network.security.AnomalyDetector;
import com.xinian.KryptonHybrid.shared.network.security.HandshakeTimeoutHandler;
import com.xinian.KryptonHybrid.shared.network.security.HandshakeValidator;
import com.xinian.KryptonHybrid.shared.network.security.StatusRequestGuard;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.server.network.ServerHandshakePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects into {@link ServerHandshakePacketListenerImpl} to:
 * <ol>
 *   <li>Validate the handshake packet (protocol version, server address) via
 *       {@link HandshakeValidator}.</li>
 *   <li>Advance the {@link HandshakeTimeoutHandler} from HANDSHAKE ??LOGIN stage.</li>
 * </ol>
 */
@Mixin(ServerHandshakePacketListenerImpl.class)
public abstract class HandshakeValidatorMixin {

    @Accessor("connection")
    abstract Connection krypton$getConnection();

    @Inject(method = "handleIntention", at = @At("HEAD"), cancellable = true)
    private void krypton$validateHandshake(ClientIntentionPacket packet, CallbackInfo ci) {
        if (!KryptonConfig.securityEnabled) return;
        Connection connection = this.krypton$getConnection();

        // ?? Validate handshake fields ?????????????????????????????????
        HandshakeValidator.ValidationResult result = HandshakeValidator.validate(
                packet.getProtocolVersion(),
                packet.getHostName(),
                packet.getPort());

        if (!result.valid()) {
            // Record anomaly
            AnomalyDetector detector = AnomalyDetector.get(connection.channel());
            detector.recordStrike(
                    AnomalyDetector.AnomalyType.PROTOCOL_VIOLATION,
                    "Invalid handshake: " + result.reason());

            connection.disconnect(Component.literal(
                    "Connection refused: " + result.reason()));
            ci.cancel();
            return;
        }

        if (packet.getIntention() == ConnectionProtocol.STATUS
                && !StatusRequestGuard.allowStatusPing(connection.channel(), packet.getHostName())) {
            if (KryptonConfig.securityStatusPingSilentDrop) {
                connection.channel().close();
            } else {
                connection.disconnect(Component.translatable("disconnect.ignoring_status_request"));
            }
            ci.cancel();
            return;
        }

        if (packet.getIntention() == ConnectionProtocol.STATUS) {
            return;
        }

        // ?? Advance timeout to LOGIN stage ????????????????????????????
        HandshakeTimeoutHandler.advanceStage(
                connection.channel(), HandshakeTimeoutHandler.Stage.LOGIN);
        PacketControlState.get(connection.channel()).setPhase(PacketControlPhase.LOGIN);
    }
}

