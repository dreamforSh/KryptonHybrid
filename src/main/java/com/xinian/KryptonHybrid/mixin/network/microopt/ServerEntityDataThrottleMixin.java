package com.xinian.KryptonHybrid.mixin.network.microopt;

import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Throttles redundant {@link ClientboundSetEntityDataPacket} broadcasts by filtering
 * item stack values that did not change.
 *
 * <p>Vanilla already skips {@code set} calls with an equal value, except item stacks,
 * which it compares by reference: setting a copy of the same stack marks it dirty
 * again. Item stack values are therefore serialized into a reused scratch buffer and
 * compared with the bytes last broadcast. Every other dirty value is sent as vanilla
 * does, because a dirty value equal to the last one sent is intentional there: a forced
 * {@code set(key, value, true)} (display interpolation restarts) or a value set and
 * reset within a tick to correct the client ({@code Player.stopFallFlying}).</p>
 *
 * <p>A value whose encoding fails here is sent unfiltered, so a broken codec still
 * fails on the player's connection, as in vanilla, rather than on the server thread.</p>
 *
 * <p>{@code trackedDataValues} is still refreshed on every dirty tick, so players that
 * start tracking the entity later get its full current state from {@code addPairing}.</p>
 */
@Mixin(ServerEntity.class)
public abstract class ServerEntityDataThrottleMixin {

    /** Server-thread scratch buffer, reused for every comparison. */
    @Unique
    private static final ThreadLocal<FriendlyByteBuf> krypton$SCRATCH =
            ThreadLocal.withInitial(() -> new FriendlyByteBuf(Unpooled.buffer(256)));

    @Accessor("entity")
    abstract Entity krypton$getEntity();

    @Accessor("trackedDataValues")
    abstract void krypton$setTrackedDataValues(List<SynchedEntityData.DataValue<?>> values);

    @Invoker("broadcastAndSend")
    abstract void krypton$broadcastAndSend(Packet<?> packet);

    /** Last broadcast bytes per data value id; created on first use. */
    @Unique
    private Int2ObjectOpenHashMap<byte[]> krypton$lastBroadcastSnapshots;

    @Inject(method = "sendDirtyEntityData", at = @At("HEAD"), cancellable = true)
    private void krypton$throttledSendDirtyEntityData(CallbackInfo ci) {
        ci.cancel();

        Entity entity = this.krypton$getEntity();
        SynchedEntityData data = entity.getEntityData();
        List<SynchedEntityData.DataValue<?>> dirtyList = data.packDirty();

        if (dirtyList != null) {
            this.krypton$setTrackedDataValues(data.getNonDefaultValues());

            List<SynchedEntityData.DataValue<?>> filtered = krypton$filterUnchanged(dirtyList);
            if (!filtered.isEmpty()) {
                this.krypton$broadcastAndSend(new ClientboundSetEntityDataPacket(entity.getId(), filtered));
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
    private List<SynchedEntityData.DataValue<?>> krypton$filterUnchanged(
            List<SynchedEntityData.DataValue<?>> dirtyEntries) {
        boolean hasItemStack = false;
        for (SynchedEntityData.DataValue<?> entry : dirtyEntries) {
            if (entry.serializer() == EntityDataSerializers.ITEM_STACK) {
                hasItemStack = true;
                break;
            }
        }
        if (!hasItemStack) {
            return dirtyEntries;
        }
        Int2ObjectOpenHashMap<byte[]> snapshots = this.krypton$lastBroadcastSnapshots;
        if (snapshots == null) {
            snapshots = new Int2ObjectOpenHashMap<>();
            this.krypton$lastBroadcastSnapshots = snapshots;
        }
        FriendlyByteBuf scratch = krypton$SCRATCH.get();
        List<SynchedEntityData.DataValue<?>> result = new ArrayList<>(dirtyEntries.size());

        for (SynchedEntityData.DataValue<?> entry : dirtyEntries) {
            if (entry.serializer() != EntityDataSerializers.ITEM_STACK) {
                result.add(entry);
                continue;
            }
            scratch.clear();
            try {
                entry.write(scratch);
            } catch (RuntimeException e) {
                snapshots.remove(entry.id());
                result.add(entry);
                continue;
            }
            int length = scratch.readableBytes();

            byte[] previous = snapshots.get(entry.id());
            if (previous != null && krypton$sameBytes(previous, scratch, length)) {
                continue;
            }
            byte[] current = new byte[length];
            scratch.getBytes(0, current);
            snapshots.put(entry.id(), current);
            result.add(entry);
        }

        return result;
    }

    @Unique
    private static boolean krypton$sameBytes(byte[] previous, FriendlyByteBuf current, int length) {
        if (previous.length != length) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            if (previous[i] != current.getByte(i)) {
                return false;
            }
        }
        return true;
    }
}
