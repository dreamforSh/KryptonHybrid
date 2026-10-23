package com.xinian.KryptonHybrid.mixin.network.flushconsolidation;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import com.xinian.KryptonHybrid.mixin.network.pipeline.ConnectionAccessor;
import com.xinian.KryptonHybrid.shared.network.flow.ConfigurableAutoFlush;
import com.xinian.KryptonHybrid.shared.network.flow.FlushWindows;
import com.xinian.KryptonHybrid.shared.network.stats.NetworkTrafficStats;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Optimizes {@link Connection} by adding the ability to disable auto-flushing.
 * When auto-flush is disabled, packet writes are buffered and only flushed when
 * re-enabled, allowing many packets to be sent in one syscall instead of one per packet.
 *
 * <h3>Two flags</h3>
 * <p>A packet sent from the server thread is written later, by a task on the channel's
 * event loop. The state the windows ask for ({@link #kryptonfnp$requested}) therefore
 * reaches the event loop as tasks queued behind those writes ({@link #kryptonfnp$ioAutoFlush}):
 * every packet sent inside a window is written without a flush however late its task
 * runs, and the release flush runs after all of them.</p>
 */
@Mixin(Connection.class)
public abstract class ConnectionMixin implements ConfigurableAutoFlush {

    /** Auto-flush as requested by the flush windows (server thread). */
    @Unique
    private AtomicBoolean kryptonfnp$requested;

    /** Auto-flush as seen by writes (event loop); follows the requested state in task order. */
    @Unique
    private volatile boolean kryptonfnp$ioAutoFlush = true;

    /** Packets written but not flushed since the last consolidated flush (event loop only). */
    @Unique
    private AtomicInteger kryptonfnp$deferred;

    @Unique
    private Channel kryptonfnp$channel() {
        return ((ConnectionAccessor) this).krypton$getChannel();
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void kryptonfnp$initAutoFlush(PacketFlow pReceiving, CallbackInfo ci) {
        this.kryptonfnp$requested = new AtomicBoolean(true);
        this.kryptonfnp$deferred = new AtomicInteger();
    }

    /**
     * Vanilla's per-tick {@code Channel.flush()}, run in the connection phase of the
     * server tick. It ends a whole-tick flush window for this connection (see
     * {@link FlushWindows}), and is otherwise suppressed only while a window holds
     * the flush.
     * <p>
     * When auto-flush is on (the default: during login, Forge handshake, hybrid-server
     * negotiation, etc.) the tick flush proceeds normally. Regular sends already use
     * {@code writeAndFlush} in that state; however, some code paths on Forge / hybrid
     * server stacks (mod-channel registration, Bungeecord forwarding, etc.) write to the
     * channel directly via {@code ctx.write()} / {@code channel.write()} and rely on the
     * tick flush as the safety net to actually deliver those bytes.
     */
    @Redirect(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lio/netty/channel/Channel;flush()Lio/netty/channel/Channel;",
                    remap = false))
    private Channel kryptonfnp$suppressTickFlush(Channel channel) {
        AtomicBoolean requested = this.kryptonfnp$requested;
        if (requested != null && !requested.get()) {
            if (FlushWindows.isTickWindowOpen()) {
                // Releasing flushes in order with the writes queued so far.
                setShouldAutoFlush(true);
            }
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
                    ordinal = 0,
                    remap = false))
    private ChannelFuture kryptonfnp$writeOrFlush(Channel channel, Object msg) {
        if (!this.kryptonfnp$ioAutoFlush) {
            this.kryptonfnp$deferred.incrementAndGet();
            return channel.write(msg);
        }
        return channel.writeAndFlush(msg);
    }

    @Override
    public void setShouldAutoFlush(boolean shouldAutoFlush) {
        AtomicBoolean requested = this.kryptonfnp$requested;
        if (requested == null || requested.getAndSet(shouldAutoFlush) == shouldAutoFlush) {
            return;
        }
        Channel channel = this.kryptonfnp$channel();
        if (channel == null) {
            this.kryptonfnp$ioAutoFlush = shouldAutoFlush;
            return;
        }
        // Sends from other threads reach the channel as event-loop tasks; applying the
        // change as a task too orders it after the writes queued before it.
        if (channel.eventLoop().inEventLoop()) {
            kryptonfnp$applyAutoFlush(channel, shouldAutoFlush);
        } else {
            channel.eventLoop().execute(() -> kryptonfnp$applyAutoFlush(channel, shouldAutoFlush));
        }
    }

    @Override
    public boolean isAutoFlushing() {
        AtomicBoolean requested = this.kryptonfnp$requested;
        return requested == null || requested.get();
    }

    @Unique
    private void kryptonfnp$applyAutoFlush(Channel channel, boolean autoFlush) {
        this.kryptonfnp$ioAutoFlush = autoFlush;
        if (!autoFlush) {
            return;
        }
        int packets = this.kryptonfnp$deferred.getAndSet(0);
        if (packets > 0) {
            NetworkTrafficStats.INSTANCE.recordConsolidatedFlush(packets);
        }
        channel.flush();
    }
}
