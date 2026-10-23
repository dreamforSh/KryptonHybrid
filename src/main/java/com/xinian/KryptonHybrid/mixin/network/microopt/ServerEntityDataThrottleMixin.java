package com.xinian.KryptonHybrid.mixin.network.microopt;

import com.xinian.KryptonHybrid.shared.KryptonConfig;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

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
 * <p>Only the list returned by {@code packDirty()} is filtered; the vanilla method
 * still runs to the end. Attribute syncing and other mods' injections into
 * {@code sendDirtyEntityData} (Epic Fight sends its own synched data from a TAIL
 * injection) are unaffected. When every dirty value is filtered out the list becomes
 * {@code null}, so vanilla sends nothing and keeps {@code trackedDataValues}, whose
 * item stacks encode to the same bytes as the current ones.</p>
 *
 * <p>A value whose encoding fails here is sent unfiltered, so a broken codec still
 * fails on the player's connection, as in vanilla, rather than on the server thread.</p>
 */
@Mixin(ServerEntity.class)
public abstract class ServerEntityDataThrottleMixin {

    /** Server-thread scratch buffer, reused for every comparison. */
    @Unique
    private static final ThreadLocal<FriendlyByteBuf> krypton$SCRATCH =
            ThreadLocal.withInitial(() -> new FriendlyByteBuf(Unpooled.buffer(256)));

    /** Last broadcast bytes per data value id; created on first use. */
    @Unique
    private Int2ObjectOpenHashMap<byte[]> krypton$lastBroadcastSnapshots;

    @ModifyVariable(method = "sendDirtyEntityData", at = @At("STORE"))
    @Nullable
    private List<SynchedEntityData.DataValue<?>> krypton$throttleDirtyValues(
            @Nullable List<SynchedEntityData.DataValue<?>> dirtyList) {
        if (!KryptonConfig.entityDataThrottleEnabled) {
            // Values broadcast while disabled are not recorded; drop the stale snapshots.
            this.krypton$lastBroadcastSnapshots = null;
            return dirtyList;
        }
        if (dirtyList == null) {
            return null;
        }
        List<SynchedEntityData.DataValue<?>> filtered = krypton$filterUnchanged(dirtyList);
        return filtered.isEmpty() ? null : filtered;
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
