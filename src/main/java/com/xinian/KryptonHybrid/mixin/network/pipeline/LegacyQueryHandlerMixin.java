package com.xinian.KryptonHybrid.mixin.network.pipeline;

import com.xinian.KryptonHybrid.shared.network.security.MotdCache;
import com.xinian.KryptonHybrid.shared.network.security.StatusRequestGuard;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.server.ServerInfo;
import net.minecraft.server.network.LegacyQueryHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixes into {@link LegacyQueryHandler} to fix a security issue.
 */
@Mixin(LegacyQueryHandler.class)
public abstract class LegacyQueryHandlerMixin {
    @Inject(method = "channelRead", at = @At(value = "HEAD"), cancellable = true)
    public void channelRead(ChannelHandlerContext ctx, Object msg, CallbackInfo ci) throws Exception {
        if (!ctx.channel().isActive()) {
            ReferenceCountUtil.release(msg);
            ci.cancel();
            return;
        }

        if (msg instanceof ByteBuf byteBuf && !StatusRequestGuard.allowLegacyQuery(ctx.channel(), byteBuf.readableBytes())) {
            ReferenceCountUtil.release(msg);
            ctx.close();
            ci.cancel();
        }
    }

    @Inject(method = "createVersion0Response", at = @At("HEAD"), cancellable = true)
    private static void krypton$cachedLegacyV0(ServerInfo server, CallbackInfoReturnable<String> cir) {
        String cached = MotdCache.cachedLegacyVersion0(server);
        if (cached != null) {
            cir.setReturnValue(cached);
        }
    }

    @Inject(method = "createVersion1Response", at = @At("HEAD"), cancellable = true)
    private static void krypton$cachedLegacyV1(ServerInfo server, CallbackInfoReturnable<String> cir) {
        String cached = MotdCache.cachedLegacyVersion1(server);
        if (cached != null) {
            cir.setReturnValue(cached);
        }
    }
}

