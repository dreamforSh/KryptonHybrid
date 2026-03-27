package com.xinian.KryptonHybrid.mixin.network.avoidwork;

import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ChunkMap.class)
public class ChunkMapAvoidWorkMixin {
    // No injections: playerLoadedChunk was removed in 1.21.1.
}
