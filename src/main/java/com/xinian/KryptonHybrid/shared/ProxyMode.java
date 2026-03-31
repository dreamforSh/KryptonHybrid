package com.xinian.KryptonHybrid.shared;

/**
 * Selects how Krypton Hybrid interacts with reverse proxies.
 *
 * <ul>
 *   <li>{@link #NONE} — no proxy; all optimizations active (direct client↔server).</li>
 *   <li>{@link #AUTO} — auto-detect Velocity via the {@code velocity:player_info}
 *       plugin channel during login/configuration. Adjusts per-connection settings
 *       accordingly.</li>
 *   <li>{@link #VELOCITY} — assume every connection comes through Velocity. Forces
 *       ZLIB compression on the backend leg and gates custom wire formats behind
 *       feature negotiation.</li>
 * </ul>
 */
public enum ProxyMode {
    NONE,
    AUTO,
    VELOCITY
}

