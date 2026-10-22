package com.xinian.KryptonHybrid.shared.network.security;

import com.xinian.KryptonHybrid.shared.KryptonConfig;
import com.xinian.KryptonHybrid.shared.ProxyMode;
import io.netty.channel.Channel;

import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * Resolves the per-IP bucket key used by {@link ConnectionRateLimiter} and
 * {@link StatusRequestGuard}.
 */
final class RateLimitKeys {
    private RateLimitKeys() {}

    /**
     * Returns the source IP to rate-limit, or {@code null} when the connection must not
     * be IP-limited: memory/local channels, loopback sources, and every connection when
     * a proxy is configured. Behind a proxy all players share the proxy's address, so
     * limiting it would lock everyone out; per-client limits belong on the proxy.
     */
    static String of(Channel channel) {
        if (!(channel.remoteAddress() instanceof InetSocketAddress inet)) {
            return null;
        }
        InetAddress address = inet.getAddress();
        if (address == null || address.isLoopbackAddress()) {
            return null;
        }
        if (KryptonConfig.proxyMode != ProxyMode.NONE) {
            return null;
        }
        return address.getHostAddress();
    }
}
