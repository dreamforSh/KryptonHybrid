package com.xinian.KryptonHybrid.mixin.network.blockentity;

import com.xinian.KryptonHybrid.shared.network.BlockEntityDeltaCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client-side mixin that detects Krypton delta-encoded
 * {@link ClientboundBlockEntityDataPacket}s and merges the delta tag into
 * the block entity's existing data instead of replacing it wholesale.
 *
 * <h3>Detection</h3>
 * <p>Delta tags are identified by the presence of the
 * {@code __krypton_delta = true} boolean key.  When detected:</p>
 * <ol>
 *   <li>The existing block entity's current data is retrieved via
 *       {@link BlockEntity#saveWithoutMetadata(HolderLookup.Provider)}.</li>
 *   <li>Removed keys (listed in {@code __krypton_removed}) are deleted.</li>
 *   <li>Changed/added keys from the delta tag are merged.</li>
 *   <li>The merged tag is applied via
 *       {@link BlockEntity#loadWithComponents(CompoundTag, HolderLookup.Provider)}.</li>
 * </ol>
 *
 * <h3>Fallback</h3>
 * <p>If the tag does not contain the delta marker (vanilla server, or delta
 * disabled), the vanilla handler runs unchanged.</p>
 */
@Mixin(ClientPacketListener.class)
public class ClientBlockEntityDeltaMixin {

    /**
     * Intercepts {@code handleBlockEntityData} at HEAD to detect and merge delta tags.
     * If the tag is a delta, we handle it entirely and cancel the vanilla handler.
     */
    @Inject(method = "handleBlockEntityData", at = @At("HEAD"), cancellable = true)
    private void krypton$mergeDelta(ClientboundBlockEntityDataPacket packet, CallbackInfo ci) {
        CompoundTag tag = packet.getTag();
        if (tag == null || !tag.contains(BlockEntityDeltaCache.DELTA_MARKER_KEY)) {
            return;
        }

        if (!tag.getBoolean(BlockEntityDeltaCache.DELTA_MARKER_KEY)) {
            return;
        }


        ci.cancel();


        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }

        BlockPos pos = packet.getPos();
        var optionalBe = level.getBlockEntity(pos, packet.getType());
        if (optionalBe.isEmpty()) {
            return;
        }

        BlockEntity be = optionalBe.get();
        HolderLookup.Provider registries = level.registryAccess();


        CompoundTag currentData = be.saveWithoutMetadata(registries);


        if (tag.contains(BlockEntityDeltaCache.REMOVED_KEYS_KEY)) {
            String removedCsv = tag.getString(BlockEntityDeltaCache.REMOVED_KEYS_KEY);
            for (String key : removedCsv.split(",")) {
                if (!key.isEmpty()) {
                    currentData.remove(key);
                }
            }
        }


        for (String key : tag.getAllKeys()) {
            if (key.equals(BlockEntityDeltaCache.DELTA_MARKER_KEY)
                    || key.equals(BlockEntityDeltaCache.REMOVED_KEYS_KEY)) {
                continue;
            }
            net.minecraft.nbt.Tag value = tag.get(key);
            if (value != null) {
                currentData.put(key, value.copy());
            }
        }


        be.loadWithComponents(currentData, registries);
    }
}
