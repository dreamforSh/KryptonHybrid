package com.xinian.KryptonHybrid.mixin.fastchunkentityaccess;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSortedSet;
import com.xinian.KryptonHybrid.shared.WorldEntityByChunkAccess;
import net.minecraft.util.ClassInstanceMultiMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Implements;
import org.spongepowered.asm.mixin.Interface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Provides a fast way to search the entity section storage for entities in a specific chunk,
 * avoiding the need to iterate over all entities.
 */
@SuppressWarnings("unchecked")
@Mixin(EntitySectionStorage.class)
@Implements(@Interface(iface = WorldEntityByChunkAccess.class, prefix = "krypton$"))
public abstract class EntitySectionStorageMixin<T extends EntityAccess> {

    @Shadow @Final private Long2ObjectMap<EntitySection<T>> sections;

    @Shadow protected abstract LongSortedSet getChunkSections(int pX, int pZ);

    public Collection<Entity> krypton$getEntitiesInChunk(final int chunkX, final int chunkZ) {
        final LongSortedSet sectionKeys = this.getChunkSections(chunkX, chunkZ);
        if (sectionKeys.isEmpty()) {
            return List.of();
        }

        final List<Entity> entities = new ArrayList<>();
        final LongIterator sectionsIterator = sectionKeys.iterator();
        while (sectionsIterator.hasNext()) {
            final long key = sectionsIterator.nextLong();
            final EntitySection<T> section = this.sections.get(key);
            if (section != null && section.getStatus().isAccessible()) {
                ClassInstanceMultiMap<T> storage = ((EntitySectionAccessor<T>) section).getStorage();
                for (T entity : storage) {
                    entities.add((Entity) entity);
                }
            }
        }
        return entities;
    }
}

