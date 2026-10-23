package com.xinian.KryptonHybrid.mixin.network.blockentity;

import com.xinian.KryptonHybrid.shared.network.blockentity.BlockEntityDeltaCache;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Client side of block-entity delta encoding.
 *
 * <h3>Delta base</h3>
 * <p>The server computes each delta against the last tag it sent for that position
 * ({@link BlockEntityDeltaCache}), so the client keeps the same base: every received
 * tag (full, or delta merged into full) is recorded per position. Delta tags are
 * identified by the {@code __krypton_delta = true} key.</p>
 *
 * <h3>Applying a delta</h3>
 * <p>The delta is merged into the recorded base and written back into the packet's
 * tag in place, so the vanilla handler then runs normally: on the client thread and
 * through NeoForge's {@code BlockEntity.onDataPacket}, which mods override to read their
 * update tag. If the base is missing (the server re-seeds it with a full tag after
 * every chunk send, so this should not happen), the block entity's current saved
 * data is used as a best-effort base.</p>
 *
 * <h3>Fallback</h3>
 * <p>Tags without the delta marker (vanilla server, or delta disabled) are applied
 * by the vanilla handler unchanged.</p>
 */
@Mixin(ClientPacketListener.class)
public class ClientBlockEntityDeltaMixin {

    /**
     * Must stay well above the server's per-player cache size (512) so every position
     * the server can still send a delta for keeps a base here.
     */
    @Unique
    private static final int KRYPTON_MAX_DELTA_BASES = 2048;

    @Unique
    private final Long2ObjectLinkedOpenHashMap<CompoundTag> krypton$deltaBases = new Long2ObjectLinkedOpenHashMap<>();

    @Inject(method = "handleBlockEntityData", at = @At("HEAD"))
    private void krypton$mergeDelta(ClientboundBlockEntityDataPacket packet, CallbackInfo ci) {
        CompoundTag tag = packet.getTag();
        if (tag == null) {
            return;
        }

        // Same hand-off vanilla does first: re-queues the packet on the client thread
        // (by throwing RunningOnDifferentThreadException here), so the base map and
        // the block entity are only touched there.
        PacketUtils.ensureRunningOnSameThread(packet, (ClientPacketListener) (Object) this, Minecraft.getInstance());

        if (BlockEntityDeltaCache.isDelta(tag)) {
            CompoundTag base = this.krypton$deltaBases.get(packet.getPos().asLong());
            CompoundTag merged = base != null ? base.copy() : krypton$fallbackBase(packet);
            BlockEntityDeltaCache.applyDelta(merged, tag);

            for (String key : List.copyOf(tag.getAllKeys())) {
                tag.remove(key);
            }
            for (String key : merged.getAllKeys()) {
                tag.put(key, merged.get(key));
            }
        }

        this.krypton$deltaBases.putAndMoveToLast(packet.getPos().asLong(), tag.copy());
        if (this.krypton$deltaBases.size() > KRYPTON_MAX_DELTA_BASES) {
            this.krypton$deltaBases.removeFirst();
        }
    }

    @Unique
    private static CompoundTag krypton$fallbackBase(ClientboundBlockEntityDataPacket packet) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return new CompoundTag();
        }
        return level.getBlockEntity(packet.getPos(), packet.getType())
                .map(be -> be.saveWithoutMetadata(level.registryAccess()))
                .orElseGet(CompoundTag::new);
    }
}
