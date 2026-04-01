package com.xinian.KryptonHybrid.shared.network.control;

/**
 * High-level inbound packet-control phase for a connection.
 */
public enum PacketControlPhase {
    CONNECTED,
    HANDSHAKE,
    LOGIN,
    CONFIGURATION,
    PLAY
}

