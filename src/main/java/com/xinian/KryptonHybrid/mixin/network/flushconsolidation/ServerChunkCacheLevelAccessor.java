package com.xinian.KryptonHybrid.mixin.network.flushconsolidation;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerChunkCache.class)
public interface ServerChunkCacheLevelAccessor {
    @Accessor("level")
    ServerLevel krypton$getLevel();
}
