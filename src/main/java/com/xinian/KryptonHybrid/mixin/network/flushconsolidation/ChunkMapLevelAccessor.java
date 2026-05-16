package com.xinian.KryptonHybrid.mixin.network.flushconsolidation;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkMap.class)
public interface ChunkMapLevelAccessor {
    @Accessor("level")
    ServerLevel krypton$getLevel();
}
