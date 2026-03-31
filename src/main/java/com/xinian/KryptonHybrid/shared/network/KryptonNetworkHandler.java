package com.xinian.KryptonHybrid.shared.network;

import com.xinian.KryptonHybrid.shared.KryptonConfig;
import com.xinian.KryptonHybrid.shared.KryptonSharedBootstrap;
import com.xinian.KryptonHybrid.shared.network.compression.ZstdUtil;
import net.minecraft.network.Connection;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Handles the {@code krypton_hybrid:hello} payload on both server and client.
 *
 * <p>When a hello is received, the handler intersects the remote capabilities with
 * the local capabilities to determine which Krypton features are active on the
 * connection.</p>
 */
public final class KryptonNetworkHandler {

    private KryptonNetworkHandler() {}

    /**
     * Builds the local capability bitfield based on current config.
     */
    public static int buildLocalCapabilities() {
        int bits = 0;
        if (ZstdUtil.isEnabled())                   bits |= KryptonCapabilities.BIT_ZSTD;
        if (KryptonConfig.chunkOptEnabled)          bits |= KryptonCapabilities.BIT_CHUNK_OPT;
        if (KryptonConfig.lightOptEnabled)          bits |= KryptonCapabilities.BIT_LIGHT_OPT;
        if (KryptonConfig.blockEntityDeltaEnabled)  bits |= KryptonCapabilities.BIT_BLOCK_ENTITY_DELTA;
        return bits;
    }

    /**
     * Server-side handler for incoming hello from client.
     */
    public static void handleServerHello(KryptonHelloPayload payload, IPayloadContext context) {
        Connection connection = context.connection();

        KryptonSharedBootstrap.LOGGER.info(
                "Received Krypton hello from client (protocol={}, caps=0x{}) on {}",
                payload.protocolVersion(),
                Integer.toHexString(payload.capabilities()),
                connection.getRemoteAddress());

        if (connection instanceof KryptonCapabilityHolder holder) {
            KryptonCapabilities caps = holder.krypton$getCapabilities();

            // Set local capabilities first
            caps.fromBitfield(buildLocalCapabilities());

            // If behind proxy, force disable Zstd on backend leg
            if (caps.isBehindProxy()) {
                caps.setZstdSupported(false);
            }

            // Intersect with remote
            caps.applyNegotiated(payload.capabilities());

            KryptonSharedBootstrap.LOGGER.info("Negotiated capabilities: {}", caps);

            // Reply with our capabilities so the client knows what we support
            context.reply(new KryptonHelloPayload(
                    KryptonHelloPayload.CURRENT_PROTOCOL,
                    buildLocalCapabilities()));
        }
    }

    /**
     * Client-side handler for incoming hello from server.
     */
    public static void handleClientHello(KryptonHelloPayload payload, IPayloadContext context) {
        Connection connection = context.connection();

        KryptonSharedBootstrap.LOGGER.info(
                "Received Krypton hello from server (protocol={}, caps=0x{})",
                payload.protocolVersion(),
                Integer.toHexString(payload.capabilities()));

        if (connection instanceof KryptonCapabilityHolder holder) {
            KryptonCapabilities caps = holder.krypton$getCapabilities();

            // Set local capabilities
            caps.fromBitfield(buildLocalCapabilities());

            // Intersect with remote
            caps.applyNegotiated(payload.capabilities());

            KryptonSharedBootstrap.LOGGER.info("Negotiated capabilities with server: {}", caps);
        }
    }
}

