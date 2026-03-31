package com.xinian.KryptonHybrid.mixin.network.pipeline;

import com.xinian.KryptonHybrid.shared.network.KryptonCapabilities;
import com.xinian.KryptonHybrid.shared.network.KryptonCapabilityHolder;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.net.SocketAddress;

/**
 * Attaches a per-connection {@link KryptonCapabilities} instance to
 * {@link Connection} via the {@link KryptonCapabilityHolder} duck interface.
 * Also provides the ability to update the connection's remote address for
 * Velocity Modern Forwarding.
 */
@Mixin(Connection.class)
public class ConnectionCapabilityMixin implements KryptonCapabilityHolder {

    @Shadow
    private SocketAddress address;

    @Unique
    private final KryptonCapabilities krypton$capabilities = new KryptonCapabilities();

    @Override
    public KryptonCapabilities krypton$getCapabilities() {
        return this.krypton$capabilities;
    }

    @Override
    public void krypton$setAddress(SocketAddress address) {
        this.address = address;
    }
}

