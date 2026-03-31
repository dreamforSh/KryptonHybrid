package com.xinian.KryptonHybrid.shared.network.velocity;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.custom.CustomQueryPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Outbound login plugin request payload for the {@code velocity:player_info} channel.
 *
 * <p>The backend server sends this to the connecting proxy (Velocity) during login
 * to request modern forwarding data. The single byte indicates the maximum forwarding
 * protocol version supported.</p>
 */
public record VelocityLoginQueryPayload(int requestedVersion) implements CustomQueryPayload {

    /** The channel identifier used by Velocity modern forwarding. */
    public static final ResourceLocation CHANNEL =
            ResourceLocation.parse("velocity:player_info");

    /** Velocity forwarding version: default (just IP, UUID, name, properties). */
    public static final int MODERN_DEFAULT = 1;
    /** Velocity forwarding version: includes player public key. */
    public static final int MODERN_WITH_KEY = 2;
    /** Velocity forwarding version: includes player public key v2 + signer UUID. */
    public static final int MODERN_WITH_KEY_V2 = 3;
    /** Velocity forwarding version: lazy session (1.19.3+). */
    public static final int MODERN_LAZY_SESSION = 4;

    @Override
    public ResourceLocation id() {
        return CHANNEL;
    }

    @Override
    public void write(FriendlyByteBuf buffer) {
        buffer.writeByte(requestedVersion);
    }
}

