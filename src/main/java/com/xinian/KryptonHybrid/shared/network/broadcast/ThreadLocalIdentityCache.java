package com.xinian.KryptonHybrid.shared.network.broadcast;

import java.util.IdentityHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;

/**
 * Per-thread, identity-keyed cache used by {@link BroadcastSerializationCache} and
 * {@link BroadcastCompressedCache} to short-circuit redundant per-recipient work
 * when the same {@link net.minecraft.network.protocol.Packet} instance is encoded
 * multiple times on the same Netty I/O thread.
 *
 * <p>Entries live for one server tick: the first access on a thread after
 * {@link #nextTick()} clears that thread's map, so packets are not kept past the tick
 * that broadcast them and a busy tick is not cut short by an overflow clear. The entry
 * cap is only a safety net.</p>
 *
 * <p>Reads/writes are gated by an external {@link BooleanSupplier} (typically a
 * {@code KryptonConfig} flag) so the cache turns into a no-op when disabled,
 * without any state leaking into the {@link ThreadLocal}.
 *
 * <p>Not exported outside this package. Both caches keep their public static
 * surface; this is purely an internal implementation detail.
 */
final class ThreadLocalIdentityCache<V> {

    /** Bumped once per server tick (server thread). */
    private static volatile long tick;

    private static final class Holder<V> {
        final IdentityHashMap<Object, V> map = new IdentityHashMap<>();
        long tick;
    }

    private final int maxEntries;
    private final BooleanSupplier enabled;
    private final ThreadLocal<Holder<V>> tl = ThreadLocal.withInitial(Holder::new);
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();

    ThreadLocalIdentityCache(int maxEntries, BooleanSupplier enabled) {
        this.maxEntries = maxEntries;
        this.enabled = enabled;
    }

    /** Starts a new server tick: every thread drops the previous tick's entries on next use. */
    @SuppressWarnings("NonAtomicOperationOnVolatileField") // single writer: the server thread
    static void nextTick() {
        tick++;
    }

    private IdentityHashMap<Object, V> map() {
        Holder<V> holder = tl.get();
        long current = tick;
        if (holder.tick != current) {
            holder.map.clear();
            holder.tick = current;
        }
        return holder.map;
    }

    V get(Object key) {
        if (!enabled.getAsBoolean()) return null;
        V value = map().get(key);
        if (value != null) {
            hits.increment();
        } else {
            misses.increment();
        }
        return value;
    }

    void put(Object key, V value) {
        if (!enabled.getAsBoolean()) return;
        IdentityHashMap<Object, V> map = map();
        if (map.size() >= maxEntries) {
            map.clear();
        }
        map.put(key, value);
    }

    void clear() {
        tl.get().map.clear();
    }

    long hits() {
        return hits.sum();
    }

    long misses() {
        return misses.sum();
    }

    void resetCounters() {
        hits.reset();
        misses.reset();
    }
}
