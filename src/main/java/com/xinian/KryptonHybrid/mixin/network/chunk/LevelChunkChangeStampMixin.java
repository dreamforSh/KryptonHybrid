package com.xinian.KryptonHybrid.mixin.network.chunk;

import com.xinian.KryptonHybrid.shared.network.chunk.ChunkChangeStamp;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gives every {@link LevelChunk} a {@link ChunkChangeStamp}, so the delayed chunk cache
 * can tell whether a chunk a player still holds changed while it was out of view.
 * Block changes are stamped here, including in chunks that are not ticking and so
 * never broadcast them; broadcasts are stamped by {@code ChunkHolderBroadcastMixin}.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkChangeStampMixin implements ChunkChangeStamp {

    /** A new chunk counts as changed now, so a reload never matches an older stamp. */
    @Unique
    private long krypton$changeStamp = ChunkChangeStamp.next();

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void krypton$stampBlockChange(BlockPos pos, BlockState state, boolean isMoving,
                                          CallbackInfoReturnable<BlockState> cir) {
        // null means the state was already there.
        if (cir.getReturnValue() != null) {
            this.krypton$changeStamp = ChunkChangeStamp.next();
        }
    }

    @Override
    public long krypton$changeStamp() {
        return this.krypton$changeStamp;
    }

    @Override
    public void krypton$markChanged() {
        this.krypton$changeStamp = ChunkChangeStamp.next();
    }
}
