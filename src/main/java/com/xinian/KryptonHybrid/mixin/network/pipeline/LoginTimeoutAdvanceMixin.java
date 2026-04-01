package com.xinian.KryptonHybrid.mixin.network.pipeline;

import com.xinian.KryptonHybrid.shared.KryptonConfig;
import com.xinian.KryptonHybrid.shared.network.control.PacketControlPhase;
import com.xinian.KryptonHybrid.shared.network.control.PacketControlState;
import com.xinian.KryptonHybrid.shared.network.security.HandshakeTimeoutHandler;
import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Advances the {@link HandshakeTimeoutHandler} from LOGIN → PLAY stage when the
 * login phase completes and the connection transitions to configuration/play.
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public class LoginTimeoutAdvanceMixin {

    @Shadow @Final
    Connection connection;

    /**
     * When login acknowledgement is handled, the connection transitions out of
     * the login phase.  Advance the timeout to PLAY stage.
     */
    @Inject(method = "handleLoginAcknowledgement", at = @At("TAIL"))
    private void krypton$advanceToPlayTimeout(CallbackInfo ci) {
        if (!KryptonConfig.securityEnabled) return;
        HandshakeTimeoutHandler.advanceStage(
                this.connection.channel(), HandshakeTimeoutHandler.Stage.PLAY);
        PacketControlState.get(this.connection.channel()).setPhase(PacketControlPhase.CONFIGURATION);
    }
}

