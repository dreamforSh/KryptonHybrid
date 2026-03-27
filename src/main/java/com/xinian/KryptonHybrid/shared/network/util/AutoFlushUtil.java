package com.xinian.KryptonHybrid.shared.network.util;

import com.xinian.KryptonHybrid.shared.network.ConfigurableAutoFlush;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;

/**
 * Utility class for controlling auto-flush behavior on player connections.
 * Used by flush consolidation mixins to batch packet writes and flush all at once.
 */
public final class AutoFlushUtil {

    /**
     * Sets whether the given player's connection should auto-flush after every write.
     * Disabling auto-flush allows batching many packets and flushing them together for efficiency.
     *
     * @param player the server player whose connection to configure
     * @param val    {@code true} to re-enable auto-flush (and immediately flush any pending data),
     *               {@code false} to buffer writes until re-enabled
     */
    public static void setAutoFlush(ServerPlayer player, boolean val) {
        // Only apply to vanilla ServerPlayer instances, not subclasses (e.g. fake players)
        if (player.getClass() == ServerPlayer.class) {
            Connection connection = player.connection.getConnection();
            ((ConfigurableAutoFlush) connection).setShouldAutoFlush(val);
        }
    }

    /**
     * Sets whether the given {@link Connection} should auto-flush.
     *
     * @param connection the connection to configure
     * @param val        {@code true} to re-enable flushing, {@code false} to buffer
     */
    public static void setAutoFlush(Connection connection, boolean val) {
        if (connection.getClass() == Connection.class) {
            ((ConfigurableAutoFlush) connection).setShouldAutoFlush(val);
        }
    }

    private AutoFlushUtil() {}
}

