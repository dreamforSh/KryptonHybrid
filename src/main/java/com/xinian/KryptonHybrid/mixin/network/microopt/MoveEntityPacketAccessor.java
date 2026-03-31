package com.xinian.KryptonHybrid.mixin.network.microopt;

import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor for the {@code protected} {@code entityId} field on
 * {@link ClientboundMoveEntityPacket}, needed by the packet coalescer to
 * identify which entity a move-delta packet belongs to without requiring
 * a {@link net.minecraft.world.level.Level} reference.
 */
@Mixin(ClientboundMoveEntityPacket.class)
public interface MoveEntityPacketAccessor {

    @Accessor("entityId")
    int krypton$getEntityId();
}

