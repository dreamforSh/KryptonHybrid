package com.xinian.KryptonHybrid.shared;

/**
 * Proxy compatibility mode for Krypton Hybrid.
 *
 * <p>Any mode other than {@link #NONE} keeps the backend link on zlib compression
 * and disables per-IP connection and status-ping limiting, since every player
 * arrives from the proxy's address. {@link #AUTO} does not detect a proxy yet; it
 * behaves like {@link #VELOCITY}.</p>
 */
public enum ProxyMode {
    NONE,
    AUTO,
    VELOCITY
}

