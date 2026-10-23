package com.xinian.KryptonHybrid.mixin.network.avoidwork;

import com.xinian.KryptonHybrid.mixin.network.flushconsolidation.ChunkMapLevelAccessor;
import com.xinian.KryptonHybrid.shared.WorldEntityByChunkAccess;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.apache.commons.lang3.mutable.MutableObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Collection;

/**
 * Avoids scanning every tracked entity each time a chunk is sent to a player.
 *
 * <p>Vanilla {@code ChunkMap.playerLoadedChunk} loops over the whole {@code entityMap}
 * and keeps the entities standing in the sent chunk, so a join or a walk across a
 * chunk border costs {@code chunks x tracked entities}. This looks the chunk's entities
 * up in the entity section storage instead and hands vanilla only their trackers; the
 * loop body (including its own chunk check, pairing, leash and passenger packets) is
 * unchanged.</p>
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapAvoidWorkMixin {

    @Redirect(
            method = "playerLoadedChunk",
            at = @At(
                    value = "INVOKE",
                    target = "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;values()Lit/unimi/dsi/fastutil/objects/ObjectCollection;",
                    remap = false))
    private ObjectCollection<?> krypton$trackersInChunk(
            Int2ObjectMap<?> entityMap,
            ServerPlayer player,
            MutableObject<ClientboundLevelChunkWithLightPacket> packetCache,
            LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        Collection<Entity> entities = ((WorldEntityByChunkAccess) ((ChunkMapLevelAccessor) this).krypton$getLevel())
                .getEntitiesInChunk(pos.x, pos.z);
        if (entities.isEmpty()) {
            return ObjectLists.emptyList();
        }
        ObjectArrayList<Object> trackers = new ObjectArrayList<>(entities.size());
        for (Entity entity : entities) {
            Object tracker = entityMap.get(entity.getId());
            if (tracker != null) {
                trackers.add(tracker);
            }
        }
        return trackers;
    }
}
