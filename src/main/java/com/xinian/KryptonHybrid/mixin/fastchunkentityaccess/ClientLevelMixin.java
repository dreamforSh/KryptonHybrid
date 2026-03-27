package com.xinian.KryptonHybrid.mixin.fastchunkentityaccess;

import com.xinian.KryptonHybrid.shared.WorldEntityByChunkAccess;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.TransientEntitySectionManager;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Collection;

/**
 * Makes {@link ClientLevel} implement {@link WorldEntityByChunkAccess} using the entity section storage
 * for fast per-chunk entity lookup on the client.
 */
@SuppressWarnings("unchecked")
@Mixin(ClientLevel.class)
@OnlyIn(Dist.CLIENT)
public abstract class ClientLevelMixin implements WorldEntityByChunkAccess {

    @Shadow @Final private TransientEntitySectionManager<Entity> entityStorage;

    @Override
    public Collection<Entity> getEntitiesInChunk(int chunkX, int chunkZ) {
        EntitySectionStorage<Entity> storage =
                ((TransientEntitySectionManagerAccessor<Entity>) this.entityStorage).getSectionStorage();
        return ((WorldEntityByChunkAccess) storage).getEntitiesInChunk(chunkX, chunkZ);
    }
}

