package com.xinian.KryptonHybrid.shared.network.flow;

/**
 * Implemented on {@code Connection} by the flush-consolidation mixin. While auto-flush
 * is off, sent packets are only written; re-enabling it flushes them in one go.
 */
public interface ConfigurableAutoFlush {
    void setShouldAutoFlush(boolean shouldAutoFlush);

    boolean isAutoFlushing();
}
