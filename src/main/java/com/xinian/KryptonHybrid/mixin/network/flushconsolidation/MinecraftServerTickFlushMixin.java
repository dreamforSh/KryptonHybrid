package com.xinian.KryptonHybrid.mixin.network.flushconsolidation;

import com.xinian.KryptonHybrid.shared.network.broadcast.BroadcastSerializationCache;
import com.xinian.KryptonHybrid.shared.network.flow.FlushWindows;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * Opens the whole-tick flush window when a server tick starts (before Forge's
 * pre-tick event) and closes it when the tick returns. Each connection is released
 * earlier, at vanilla's per-connection flush in the connection phase; see
 * {@link FlushWindows}. The tick start also retires the previous tick's broadcast
 * cache entries.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerTickFlushMixin {

    @Inject(method = "tickServer", at = @At("HEAD"))
    private void krypton$openTickWindow(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        BroadcastSerializationCache.onServerTick();
        FlushWindows.openTick((MinecraftServer) (Object) this);
    }

    @Inject(method = "tickServer", at = @At("RETURN"))
    private void krypton$closeTickWindow(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        FlushWindows.closeTick((MinecraftServer) (Object) this);
    }
}
