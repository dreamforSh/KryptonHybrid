package com.xinian.KryptonHybrid.mixin.network.pipeline;

import com.xinian.KryptonHybrid.shared.network.security.MotdCache;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.network.protocol.status.ServerboundStatusRequestPacket;
import net.minecraft.server.network.ServerStatusPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Serves Server List Ping responses from a short-lived serialized JSON cache.
 */
@Mixin(ServerStatusPacketListenerImpl.class)
public abstract class ServerStatusPacketListenerImplMixin {

    @Shadow @Final
    private ServerStatus status;

    @Shadow @Final
    private Connection connection;

    @Shadow
    private boolean hasRequestedStatus;

    @Inject(method = "handleStatusRequest", at = @At("HEAD"), cancellable = true)
    private void krypton$serveCachedStatus(ServerboundStatusRequestPacket packet, CallbackInfo ci) {
        String cachedJson = MotdCache.cachedStatusJson(this.status);
        if (cachedJson == null) {
            return;
        }

        if (this.hasRequestedStatus) {
            this.connection.disconnect(Component.translatable("multiplayer.status.request_handled"));
        } else {
            this.hasRequestedStatus = true;
            this.connection.send(new ClientboundStatusResponsePacket(this.status, cachedJson));
        }
        ci.cancel();
    }
}
