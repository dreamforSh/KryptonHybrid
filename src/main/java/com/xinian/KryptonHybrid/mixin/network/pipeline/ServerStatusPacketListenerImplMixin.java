package com.xinian.KryptonHybrid.mixin.network.pipeline;

import com.xinian.KryptonHybrid.mixin.network.pipeline.status.ServerStatusJsonAccessor;
import com.xinian.KryptonHybrid.shared.network.security.MotdCache;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.network.protocol.status.ServerboundStatusRequestPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerStatusPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Serves Server List Ping responses through Krypton's short-lived JSON cache.
 */
@Mixin(ServerStatusPacketListenerImpl.class)
public abstract class ServerStatusPacketListenerImplMixin {

    @Accessor("server")
    abstract MinecraftServer krypton$getServer();

    @Accessor("connection")
    abstract Connection krypton$getConnection();

    @Accessor("hasRequestedStatus")
    abstract boolean krypton$getHasRequestedStatus();

    @Accessor("hasRequestedStatus")
    abstract void krypton$setHasRequestedStatus(boolean value);

    @Inject(method = "handleStatusRequest", at = @At("HEAD"), cancellable = true)
    private void krypton$serveCachedStatus(ServerboundStatusRequestPacket packet, CallbackInfo ci) {
        Connection connection = this.krypton$getConnection();
        if (this.krypton$getHasRequestedStatus()) {
            connection.disconnect(Component.translatable("multiplayer.status.request_handled"));
            ci.cancel();
            return;
        }

        ServerStatus status = this.krypton$getServer().getStatus();
        String cachedJson = MotdCache.cachedStatusJson(status);
        if (cachedJson == null) {
            return;
        }

        ((ServerStatusJsonAccessor) status).krypton$setJson(cachedJson);
        this.krypton$setHasRequestedStatus(true);
        connection.send(new ClientboundStatusResponsePacket(status));
        ci.cancel();
    }
}
