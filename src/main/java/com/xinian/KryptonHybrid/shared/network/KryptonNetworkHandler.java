package com.xinian.KryptonHybrid.shared.network;

import com.xinian.KryptonHybrid.shared.network.control.PacketControlState;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Minimal handlers for Krypton Hybrid capability negotiation.
 */
public final class KryptonNetworkHandler {
    private KryptonNetworkHandler() {}

    public static void handleClientHello(KryptonHelloPayload payload, IPayloadContext context) {
        PacketControlState.get(context.connection().channel()).markHelloNegotiated(payload.featureFlags());
        context.reply(KryptonHelloPayload.current());
    }

    public static void handleServerHello(KryptonHelloPayload payload, IPayloadContext context) {
        PacketControlState.get(context.connection().channel()).markHelloNegotiated(payload.featureFlags());
        context.finishCurrentTask(KryptonHelloConfigurationTask.TYPE);
    }
}

