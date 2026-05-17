package com.xinian.KryptonHybrid.mixin.network.microopt;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import io.netty.buffer.Unpooled;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Throttles redundant {@link ClientboundSetEntityDataPacket} broadcasts on
 * Forge 1.19.2 by filtering unchanged {@link SynchedEntityData.DataItem} values.
 */
@Mixin(ServerEntity.class)
public abstract class ServerEntityDataThrottleMixin {

    @Accessor("entity")
    abstract Entity krypton$getEntity();

    @Invoker("broadcastAndSend")
    abstract void krypton$broadcastAndSend(Packet<?> packet);

    @Unique
    private final Int2ObjectOpenHashMap<byte[]> krypton$lastBroadcastSnapshots = new Int2ObjectOpenHashMap<>();

    @Inject(method = "sendDirtyEntityData", at = @At("HEAD"), cancellable = true)
    private void krypton$throttledSendDirtyEntityData(CallbackInfo ci) {
        ci.cancel();

        Entity entity = this.krypton$getEntity();
        SynchedEntityData data = entity.getEntityData();

        if (data.isDirty()) {
            List<SynchedEntityData.DataItem<?>> dirtyList = data.packDirty();
            if (dirtyList != null) {
                List<SynchedEntityData.DataItem<?>> filtered = krypton$filterUnchanged(dirtyList);
                if (!filtered.isEmpty()) {
                    this.krypton$broadcastAndSend(krypton$createSetEntityDataPacket(entity.getId(), filtered));
                }
            }
        }

        if (entity instanceof LivingEntity living) {
            Set<AttributeInstance> set = living.getAttributes().getDirtyAttributes();
            if (!set.isEmpty()) {
                this.krypton$broadcastAndSend(new ClientboundUpdateAttributesPacket(entity.getId(), set));
            }
            set.clear();
        }
    }

    @Unique
    private List<SynchedEntityData.DataItem<?>> krypton$filterUnchanged(
            List<SynchedEntityData.DataItem<?>> dirtyEntries) {
        List<SynchedEntityData.DataItem<?>> result = new ArrayList<>(dirtyEntries.size());

        for (SynchedEntityData.DataItem<?> entry : dirtyEntries) {
            int id = entry.getAccessor().getId();
            byte[] currentSnapshot = krypton$serializedSnapshot(entry);
            boolean hasPreviousValue = krypton$lastBroadcastSnapshots.containsKey(id);
            byte[] previousSnapshot = krypton$lastBroadcastSnapshots.get(id);

            if (!hasPreviousValue || !Arrays.equals(previousSnapshot, currentSnapshot)) {
                result.add(entry);
                krypton$lastBroadcastSnapshots.put(id, currentSnapshot);
            }
        }

        return result;
    }

    @Unique
    private static byte[] krypton$serializedSnapshot(SynchedEntityData.DataItem<?> item) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        SynchedEntityData.pack(List.of(item), buf);
        byte[] out = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), out);
        return out;
    }

    @Unique
    private static ClientboundSetEntityDataPacket krypton$createSetEntityDataPacket(
            int entityId,
            List<SynchedEntityData.DataItem<?>> items) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarInt(entityId);
        SynchedEntityData.pack(items, buf);
        return new ClientboundSetEntityDataPacket(buf);
    }
}
