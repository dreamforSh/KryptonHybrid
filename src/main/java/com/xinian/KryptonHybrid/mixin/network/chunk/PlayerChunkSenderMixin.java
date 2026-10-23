package com.xinian.KryptonHybrid.mixin.network.chunk;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import com.xinian.KryptonHybrid.shared.network.util.AutoFlushUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Adds two chunk-streaming optimizations:
 * 1) Flush consolidation around sendNextChunks() batch sends
 * 2) Smarter collectChunksToSend() ordering with age/forward-motion bias
 */
@Mixin(PlayerChunkSender.class)
public abstract class PlayerChunkSenderMixin {

    @Shadow @Final private LongSet pendingChunks;
    @Shadow @Final private boolean memoryConnection;
    @Shadow private float batchQuota;

    @Unique
    private static final long KRYPTON_ABSENT = Long.MIN_VALUE;

    @Unique
    private final Long2LongOpenHashMap krypton$enqueueTimeMs = new Long2LongOpenHashMap();

    @Unique
    private boolean krypton$sendChunksAutoFlushActive;

    @Unique
    private boolean krypton$hasSendContext;

    @Unique
    private double krypton$ctxVelX;

    @Unique
    private double krypton$ctxVelZ;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void krypton$initState(boolean memoryConnection, CallbackInfo ci) {
        this.krypton$enqueueTimeMs.defaultReturnValue(KRYPTON_ABSENT);
    }

    @Inject(method = "markChunkPendingToSend", at = @At("TAIL"))
    private void krypton$trackPendingChunkAge(LevelChunk chunk, CallbackInfo ci) {
        long pos = chunk.getPos().toLong();
        if (this.krypton$enqueueTimeMs.get(pos) == KRYPTON_ABSENT) {
            this.krypton$enqueueTimeMs.put(pos, System.currentTimeMillis());
        }
    }

    @Inject(method = "dropChunk", at = @At("TAIL"))
    private void krypton$clearDroppedChunkAge(ServerPlayer player, ChunkPos chunkPos, CallbackInfo ci) {
        this.krypton$enqueueTimeMs.remove(chunkPos.toLong());
    }

    @Inject(method = "sendNextChunks", at = @At("HEAD"))
    private void krypton$sendNextChunksHead(ServerPlayer player, CallbackInfo ci) {
        // Hold the flush only if no window does already (the whole-tick window releases
        // this connection right after, in Connection.tick). A hold left behind by an
        // exception is released by the next outermost window (FlushWindows).
        this.krypton$sendChunksAutoFlushActive = AutoFlushUtil.isAutoFlushing(player);
        if (this.krypton$sendChunksAutoFlushActive) {
            AutoFlushUtil.setAutoFlush(player, false);
        }

        this.krypton$hasSendContext = true;
        this.krypton$ctxVelX = player.getDeltaMovement().x;
        this.krypton$ctxVelZ = player.getDeltaMovement().z;
    }

    @Inject(method = "sendNextChunks", at = @At("RETURN"))
    private void krypton$sendNextChunksReturn(ServerPlayer player, CallbackInfo ci) {
        this.krypton$hasSendContext = false;

        if (this.krypton$sendChunksAutoFlushActive) {
            this.krypton$sendChunksAutoFlushActive = false;
            AutoFlushUtil.setAutoFlush(player, true);
        }
    }

    @Inject(method = "collectChunksToSend", at = @At("HEAD"), cancellable = true)
    private void krypton$collectChunksToSend(
            ChunkMap chunkMap,
            ChunkPos playerChunk,
            CallbackInfoReturnable<List<LevelChunk>> cir
    ) {
        // Like vanilla, a memory connection (integrated server) gets every ready chunk at once.
        int limit = this.memoryConnection ? Integer.MAX_VALUE : (int) Math.floor(this.batchQuota);
        if (limit <= 0 || this.pendingChunks.isEmpty()) {
            cir.setReturnValue(List.of());
            return;
        }

        List<LevelChunk> candidates = new ArrayList<>(this.pendingChunks.size());
        for (long packed : this.pendingChunks) {
            LevelChunk chunk = chunkMap.getChunkToSend(packed);
            if (chunk != null) {
                candidates.add(chunk);
            }
        }

        if (candidates.isEmpty()) {
            cir.setReturnValue(List.of());
            return;
        }

        Comparator<LevelChunk> comparator;
        if (this.memoryConnection || !this.krypton$hasSendContext) {
            comparator = Comparator.comparingInt(c -> playerChunk.distanceSquared(c.getPos()));
        } else {
            long now = System.currentTimeMillis();
            comparator = Comparator.comparingDouble(c ->
                    this.krypton$priorityScore(c.getPos(), playerChunk, now)
            );
        }

        candidates.sort(comparator);

        int resultSize = Math.min(limit, candidates.size());
        List<LevelChunk> result = new ArrayList<>(resultSize);
        for (int i = 0; i < resultSize; i++) {
            LevelChunk chunk = candidates.get(i);
            result.add(chunk);
            long packed = chunk.getPos().toLong();
            this.pendingChunks.remove(packed);
            this.krypton$enqueueTimeMs.remove(packed);
        }

        cir.setReturnValue(result);
    }

    @Unique
    private double krypton$priorityScore(ChunkPos candidate, ChunkPos playerChunk, long nowMs) {
        int dx = candidate.x - playerChunk.x;
        int dz = candidate.z - playerChunk.z;

        double distanceScore = dx * dx + dz * dz;

        long enqueuedAt = this.krypton$enqueueTimeMs.get(candidate.toLong());
        double ageSeconds = enqueuedAt == KRYPTON_ABSENT ? 0.0 : Math.max(0.0, (nowMs - enqueuedAt) / 1000.0);
        double ageBias = Math.min(ageSeconds, 10.0) * 2.0;

        double velLenSq = this.krypton$ctxVelX * this.krypton$ctxVelX + this.krypton$ctxVelZ * this.krypton$ctxVelZ;
        double forwardBias = 0.0;
        if (velLenSq > 0.0001D) {
            double toChunkLenSq = (double) dx * (double) dx + (double) dz * (double) dz;
            if (toChunkLenSq > 0.0001D) {
                double invVelLen = 1.0D / Math.sqrt(velLenSq);
                double invToLen = 1.0D / Math.sqrt(toChunkLenSq);
                double dirDot = (dx * this.krypton$ctxVelX + dz * this.krypton$ctxVelZ) * invVelLen * invToLen;
                forwardBias = Math.max(0.0D, dirDot) * 4.0D;
            }
        }

        return distanceScore - ageBias - forwardBias;
    }
}

