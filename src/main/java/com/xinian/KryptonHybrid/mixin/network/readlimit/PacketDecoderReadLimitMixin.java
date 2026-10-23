package com.xinian.KryptonHybrid.mixin.network.readlimit;

import com.xinian.KryptonHybrid.shared.network.security.InboundReadLimits;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Scopes the security read limits to serverbound packet decodes; see {@link InboundReadLimits}. */
@Mixin(PacketDecoder.class)
public abstract class PacketDecoderReadLimitMixin {
    /** The direction this decoder receives. */
    @Shadow @Final private PacketFlow flow;

    @Inject(method = "decode", at = @At("HEAD"))
    private void krypton$enterReadLimits(ChannelHandlerContext ctx, ByteBuf in, List<Object> out, CallbackInfo ci) {
        InboundReadLimits.enter(this.flow == PacketFlow.SERVERBOUND);
    }

    @Inject(method = "decode", at = @At("RETURN"))
    private void krypton$exitReadLimits(ChannelHandlerContext ctx, ByteBuf in, List<Object> out, CallbackInfo ci) {
        InboundReadLimits.exit();
    }
}
