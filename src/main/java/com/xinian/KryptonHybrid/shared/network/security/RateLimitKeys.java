package com.xinian.KryptonHybrid.shared.network.security;

import com.xinian.KryptonHybrid.shared.KryptonConfig;
import com.xinian.KryptonHybrid.shared.ProxyMode;
import io.netty.channel.Channel;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

/**
 * Resolves the per-IP bucket key used by {@link ConnectionRateLimiter} and
 * {@link StatusRequestGuard}. IPv6 sources are grouped by their /64 prefix: a single
 * host usually holds a whole /64 and could otherwise pick a fresh address (and a fresh
 * budget) for every connection.
 */
final class RateLimitKeys {
    /**
     * Most addresses tracked at once per limiter. Beyond it, after evicting idle ones,
     * new addresses are let through untracked rather than refused: only a source with
     * more addresses than this could get there, and per-IP limits cannot stop that.
     */
    static final int MAX_TRACKED_ADDRESSES = 65_536;

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
        if (address instanceof Inet6Address) {
            byte[] bytes = address.getAddress();
            for (int i = 8; i < bytes.length; i++) {
                bytes[i] = 0;
            }
            try {
                return InetAddress.getByAddress(bytes).getHostAddress() + "/64";
            } catch (UnknownHostException e) {
                return address.getHostAddress();
            }
        }
        return address.getHostAddress();
    }
}
