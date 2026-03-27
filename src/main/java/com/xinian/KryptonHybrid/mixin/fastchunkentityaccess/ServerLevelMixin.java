package com.xinian.KryptonHybrid.mixin.fastchunkentityaccess;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import com.xinian.KryptonHybrid.shared.WorldEntityByChunkAccess;
import org.spongepowered.asm.mixin.Implements;
import org.spongepowered.asm.mixin.Interface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Collection;

/**
 * Makes {@link ServerLevel} implement {@link WorldEntityByChunkAccess} using the entity section storage
 * for fast per-chunk entity lookup.
 * 
 * <p>This mixin is Forge-specific (1.20.1) and must be in the forge module to generate
 * correct refmap entries with SRG names.</p>
 */
@SuppressWarnings("unchecked")
@Mixin(ServerLevel.class)
@Implements(@Interface(iface = WorldEntityByChunkAccess.class, prefix = "krypton$"))
public abstract class ServerLevelMixin {

    @Accessor("entityManager")
    public abstract PersistentEntitySectionManager<Entity> getEntityManager();

    public Collection<Entity> krypton$getEntitiesInChunk(int chunkX, int chunkZ) {
        EntitySectionStorage<Entity> storage =
                ((PersistentEntitySectionManagerAccessor<Entity>) this.getEntityManager()).getSectionStorage();
        return ((WorldEntityByChunkAccess) storage).getEntitiesInChunk(chunkX, chunkZ);
    }
}

