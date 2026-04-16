package com.xinian.KryptonHybrid.mixin.network.microopt;

import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor for the {@code private} {@code entityId} field on
 * {@link ClientboundRotateHeadPacket}, needed by the packet coalescer to
 * identify which entity a head-rotation packet belongs to without requiring
 * a {@link net.minecraft.world.level.Level} reference.
 */
@Mixin(ClientboundRotateHeadPacket.class)
public interface RotateHeadPacketAccessor {

    @Accessor("entityId")
    int krypton$getEntityId();
}

