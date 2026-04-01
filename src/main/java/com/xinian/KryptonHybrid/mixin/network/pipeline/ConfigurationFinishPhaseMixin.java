package com.xinian.KryptonHybrid.mixin.network.pipeline;

import com.xinian.KryptonHybrid.shared.network.control.PacketControlPhase;
import com.xinian.KryptonHybrid.shared.network.control.PacketControlState;
import net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks the packet-control phase as PLAY when configuration fully completes.
 */
@Mixin(ServerConfigurationPacketListenerImpl.class)
public abstract class ConfigurationFinishPhaseMixin {
    @Inject(method = "handleConfigurationFinished", at = @At("TAIL"))
    private void krypton$advancePacketControlPhase(ServerboundFinishConfigurationPacket packet, CallbackInfo ci) {
        ServerConfigurationPacketListenerImpl self = (ServerConfigurationPacketListenerImpl) (Object) this;
        PacketControlState.get(self.getConnection().channel()).setPhase(PacketControlPhase.PLAY);
    }
}

