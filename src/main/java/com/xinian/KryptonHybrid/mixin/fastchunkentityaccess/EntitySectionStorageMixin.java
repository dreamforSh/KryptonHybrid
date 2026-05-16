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
import org.spongepowered.asm.mixin.Implements;
import org.spongepowered.asm.mixin.Interface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

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

    @Accessor("sections") abstract Long2ObjectMap<EntitySection<T>> kh$sections();

    @Invoker("getChunkSections")
    abstract LongSortedSet kh$getChunkSections(int pX, int pZ);

    public Collection<Entity> krypton$getEntitiesInChunk(final int chunkX, final int chunkZ) {
        final LongSortedSet sectionKeys = this.kh$getChunkSections(chunkX, chunkZ);
        if (sectionKeys.isEmpty()) {
            return List.of();
        }

        final Long2ObjectMap<EntitySection<T>> sections = this.kh$sections();
        final List<Entity> entities = new ArrayList<>();
        final LongIterator sectionsIterator = sectionKeys.iterator();
        while (sectionsIterator.hasNext()) {
            final long key = sectionsIterator.nextLong();
            final EntitySection<T> section = sections.get(key);
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

