package com.xinian.KryptonHybrid.mixin.network.pipeline;

import com.xinian.KryptonHybrid.shared.network.control.PacketControlPhase;
import com.xinian.KryptonHybrid.shared.network.control.PacketControlState;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks the packet-control phase as PLAY when configuration fully completes.
 *
 * <p>Uses {@link IServerCommonListenerAccessor} (a companion accessor mixin on the
 * parent class) instead of {@code @Shadow} so that the connection field can be
 * retrieved without a Mixin refmap, even though the field is declared in the parent
 * class {@code ServerCommonPacketListenerImpl}.</p>
 */
@Mixin(ServerConfigurationPacketListenerImpl.class)
public abstract class ConfigurationFinishPhaseMixin {

    @Inject(method = "handleConfigurationFinished", at = @At("TAIL"))
    private void krypton$advancePacketControlPhase(ServerboundFinishConfigurationPacket packet, CallbackInfo ci) {
        Connection conn = ((IServerCommonListenerAccessor) (Object) this).krypton$getConnection();
        if (conn != null) {
            PacketControlState.get(conn.channel()).setPhase(PacketControlPhase.PLAY);
        }
    }
}
