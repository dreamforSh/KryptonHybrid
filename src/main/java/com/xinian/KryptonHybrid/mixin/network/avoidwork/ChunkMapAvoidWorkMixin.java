package com.xinian.KryptonHybrid.mixin.network.avoidwork;

import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Reserved for future per-chunk entity tracking optimizations.
 *
 * <p>In NeoForge 1.21.1 the {@code playerLoadedChunk} method was removed from
 * {@link ChunkMap}. Entity tracking is now driven entirely through
 * {@link ChunkMap#tick()} → {@code TrackedEntity.updatePlayers()} which is already
 * covered by {@code ChunkMapFlushMixin}. This class is kept as a placeholder so that
 * the {@link ChunkMapTrackedEntityInvoker} accessor remains available.</p>
 */
@Mixin(ChunkMap.class)
public class ChunkMapAvoidWorkMixin {
    // No injections: playerLoadedChunk was removed in 1.21.1.
}
