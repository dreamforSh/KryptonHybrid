package com.xinian.KryptonHybrid.shared;

import net.minecraft.world.entity.Entity;

import java.util.Collection;

/**
 * Provides a fast way to look up entities in a given chunk.
 * Implemented by level mixins (ServerLevel, ClientLevel) and EntitySectionStorage mixin.
 */
public interface WorldEntityByChunkAccess {
    Collection<Entity> getEntitiesInChunk(int chunkX, int chunkZ);
}

