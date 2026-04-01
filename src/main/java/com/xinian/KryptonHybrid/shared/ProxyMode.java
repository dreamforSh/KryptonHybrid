package com.xinian.KryptonHybrid.shared;

/**
 * Proxy compatibility mode for Krypton Hybrid.
 *
 * <p>The current security implementation keeps the setting for configuration
 * compatibility, but proxy-specific mixin behaviour is intentionally not
 * activated in this workspace.</p>
 */
public enum ProxyMode {
    NONE,
    AUTO,
    VELOCITY
}

