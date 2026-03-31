package com.xinian.KryptonHybrid.shared.network;

/**
 * Duck interface implemented on {@link net.minecraft.server.network.ServerGamePacketListenerImpl}
 * via mixin, providing access to the per-player {@link BlockEntityDeltaCache}.
 */
public interface BlockEntityDeltaHolder {

    /** Returns the per-player block entity delta cache. */
    BlockEntityDeltaCache krypton$getBlockEntityDeltaCache();
}

