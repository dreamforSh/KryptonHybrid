package com.xinian.KryptonHybrid.mixin.network.flushconsolidation;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import com.xinian.KryptonHybrid.shared.network.ConfigurableAutoFlush;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Optimizes {@link Connection} by adding the ability to disable auto-flushing.
 * When auto-flush is disabled, packet writes are buffered and only flushed when
 * re-enabled, allowing many packets to be sent in one syscall instead of one per packet.
 */
@Mixin(Connection.class)
public abstract class ConnectionMixin implements ConfigurableAutoFlush {

    @Shadow private Channel channel;

    @Unique
    private AtomicBoolean kryptonfnp$autoFlush;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void kryptonfnp$initAutoFlush(PacketFlow pReceiving, CallbackInfo ci) {
        this.kryptonfnp$autoFlush = new AtomicBoolean(true);
    }

    /**
     * Suppress the per-tick {@code Channel.flush()} call only when flush consolidation is
     * actively batching writes (i.e. {@code autoFlush == false}).
     * <p>
     * When {@code autoFlush} is {@code true} (the default: during login, Forge handshake,
     * hybrid-server negotiation, etc.) we let the tick flush proceed normally.  This is safe
     * because all regular sends go through {@link #kryptonfnp$writeOrFlush} which already
     * uses {@code writeAndFlush} in that state; however, some code paths on Forge / hybrid
     * server stacks (mod-channel registration, Bungeecord forwarding, etc.) write to the
     * channel directly via {@code ctx.write()} / {@code channel.write()} and rely on the
     * tick flush as the safety net to actually deliver those bytes.
     */
    @Redirect(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lio/netty/channel/Channel;flush()Lio/netty/channel/Channel;"))
    private Channel kryptonfnp$suppressTickFlush(Channel channel) {
        AtomicBoolean af = this.kryptonfnp$autoFlush;
        if (af != null && !af.get()) {

            return channel;
        }

        return channel.flush();
    }

    /**
     * When auto-flush is disabled, use {@code write()} instead of {@code writeAndFlush()} for the
     * primary packet write in {@code doSendPacket}. This buffers packets until flush is called.
     */
    @Redirect(
            method = "doSendPacket",
            at = @At(
                    value = "INVOKE",
                    target = "Lio/netty/channel/Channel;writeAndFlush(Ljava/lang/Object;)Lio/netty/channel/ChannelFuture;",
                    ordinal = 0))
    private ChannelFuture kryptonfnp$writeOrFlush(Channel channel, Object msg) {
        AtomicBoolean af = this.kryptonfnp$autoFlush;
        if (af != null && !af.get()) {
            return channel.write(msg);
        }
        return channel.writeAndFlush(msg);
    }

    @Override
    public void setShouldAutoFlush(boolean shouldAutoFlush) {
        AtomicBoolean af = this.kryptonfnp$autoFlush;
        if (af == null) return;
        boolean prev = af.getAndSet(shouldAutoFlush);
        if (!prev && shouldAutoFlush) {

            this.channel.flush();
        }
    }
}

