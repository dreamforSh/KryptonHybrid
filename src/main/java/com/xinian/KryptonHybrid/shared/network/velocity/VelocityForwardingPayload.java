package com.xinian.KryptonHybrid.shared.network.velocity;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.custom.CustomQueryAnswerPayload;

/**
 * A {@link CustomQueryAnswerPayload} that preserves the raw forwarding data
 * received from Velocity's modern forwarding response.
 *
 * <p>Vanilla's {@code ServerboundCustomQueryAnswerPacket} discards all payload
 * bytes in its codec. This class is injected via mixin to capture and retain
 * the raw byte array for HMAC verification and player info extraction.</p>
 */
public record VelocityForwardingPayload(byte[] data) implements CustomQueryAnswerPayload {

    @Override
    public void write(FriendlyByteBuf buffer) {
        buffer.writeBytes(data);
    }
}

