package com.xinian.KryptonHybrid.mixin.network.microopt;

import com.xinian.KryptonHybrid.shared.KryptonConfig;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import io.netty.buffer.Unpooled;

import java.util.ArrayList;
import java.util.List;

/**
 * Throttles redundant {@link ClientboundSetEntityDataPacket} broadcasts on
 * Forge 1.19.2 by filtering unchanged {@link SynchedEntityData.DataItem} values.
 *
 * <p>Vanilla already skips {@code set} calls with an equal value; this also drops
 * values that changed and changed back within a tick, and item stacks (which vanilla
 * compares by reference). Each dirty item is serialized into a reused scratch buffer
 * and compared with the bytes last broadcast; a new snapshot is only stored when the
 * value really changed.</p>
 *
 * <p>Only the entity data packet vanilla builds in {@code sendDirtyEntityData} is
 * filtered (or dropped when nothing is left); the vanilla method still runs to the
 * end. Attribute syncing and other mods' injections into {@code sendDirtyEntityData}
 * (Epic Fight sends its own synched data from a TAIL injection) are unaffected.</p>
 */
@Mixin(ServerEntity.class)
public abstract class ServerEntityDataThrottleMixin {

    /** Server-thread scratch buffer, reused for every comparison. */
    @Unique
    private static final ThreadLocal<FriendlyByteBuf> krypton$SCRATCH =
            ThreadLocal.withInitial(() -> new FriendlyByteBuf(Unpooled.buffer(256)));

    @Invoker("broadcastAndSend")
    abstract void krypton$broadcastAndSend(Packet<?> packet);

    /** Last broadcast bytes per data item id; created on first use. */
    @Unique
    private Int2ObjectOpenHashMap<byte[]> krypton$lastBroadcastSnapshots;

    /**
     * Wraps the first {@code broadcastAndSend} call, which sends the entity data packet.
     * {@code require = 0}: if another mod already redirects this call, the throttle
     * steps aside instead of failing the game launch.
     */
    @Redirect(
            method = "sendDirtyEntityData",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerEntity;broadcastAndSend(Lnet/minecraft/network/protocol/Packet;)V",
                    ordinal = 0
            ),
            require = 0
    )
    private void krypton$throttleEntityData(ServerEntity self, Packet<?> packet) {
        if (!KryptonConfig.entityDataThrottleEnabled) {
            // Values broadcast while disabled are not recorded; drop the stale snapshots.
            this.krypton$lastBroadcastSnapshots = null;
        } else if (packet instanceof ClientboundSetEntityDataPacket dataPacket) {
            List<SynchedEntityData.DataItem<?>> items = dataPacket.getUnpackedData();
            if (items != null) {
                List<SynchedEntityData.DataItem<?>> filtered = krypton$filterUnchanged(items);
                if (filtered.isEmpty()) {
                    return;
                }
                if (filtered.size() != items.size()) {
                    // The packet was just built from packDirty() copies and is not shared yet.
                    ((ClientboundSetEntityDataPacketAccessor) dataPacket).krypton$setPackedItems(filtered);
                }
            }
        }
        this.krypton$broadcastAndSend(packet);
    }

    @Unique
    private List<SynchedEntityData.DataItem<?>> krypton$filterUnchanged(
            List<SynchedEntityData.DataItem<?>> dirtyEntries) {
        Int2ObjectOpenHashMap<byte[]> snapshots = this.krypton$lastBroadcastSnapshots;
        if (snapshots == null) {
            snapshots = new Int2ObjectOpenHashMap<>();
            this.krypton$lastBroadcastSnapshots = snapshots;
        }
        FriendlyByteBuf scratch = krypton$SCRATCH.get();
        List<SynchedEntityData.DataItem<?>> result = new ArrayList<>(dirtyEntries.size());

        for (SynchedEntityData.DataItem<?> entry : dirtyEntries) {
            int id = entry.getAccessor().getId();
            scratch.clear();
            SynchedEntityData.pack(List.of(entry), scratch);
            int length = scratch.readableBytes();

            byte[] previous = snapshots.get(id);
            if (previous != null && krypton$sameBytes(previous, scratch, length)) {
                continue;
            }
            byte[] current = new byte[length];
            scratch.getBytes(0, current);
            snapshots.put(id, current);
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
