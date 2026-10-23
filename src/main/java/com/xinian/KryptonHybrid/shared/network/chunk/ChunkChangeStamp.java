package com.xinian.KryptonHybrid.shared.network.chunk;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Implemented on {@code LevelChunk}: a stamp from one global counter, refreshed when the
 * chunk is created (loaded or generated), when a block in it changes and whenever its
 * {@code ChunkHolder} broadcasts an update (blocks, light, block entities). A chunk
 * whose stamp is not newer than a stamp taken earlier has not changed since then.
 */
public interface ChunkChangeStamp {
    AtomicLong COUNTER = new AtomicLong();

    static long next() {
        return COUNTER.incrementAndGet();
    }

    long krypton$changeStamp();

    void krypton$markChanged();
}
