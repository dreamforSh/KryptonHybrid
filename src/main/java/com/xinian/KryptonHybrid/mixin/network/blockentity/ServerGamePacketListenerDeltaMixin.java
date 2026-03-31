package com.xinian.KryptonHybrid.mixin.network.blockentity;

import com.xinian.KryptonHybrid.shared.network.BlockEntityDeltaCache;
import com.xinian.KryptonHybrid.shared.network.BlockEntityDeltaHolder;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Attaches a per-player {@link BlockEntityDeltaCache} to
 * {@link ServerGamePacketListenerImpl} via the {@link BlockEntityDeltaHolder}
 * duck interface.  The cache lives for the duration of the player's connection
 * and is garbage-collected with it.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class ServerGamePacketListenerDeltaMixin implements BlockEntityDeltaHolder {

    @Unique
    private final BlockEntityDeltaCache krypton$blockEntityDeltaCache = new BlockEntityDeltaCache();

    @Override
    public BlockEntityDeltaCache krypton$getBlockEntityDeltaCache() {
        return this.krypton$blockEntityDeltaCache;
    }
}

