package com.xinian.KryptonHybrid.shared.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Custom payload sent during the CONFIGURATION phase to negotiate Krypton
 * capabilities between server and client.
 *
 * <h3>Wire format</h3>
 * <pre>
 *   VarInt  protocol_version   (currently 1)
 *   VarInt  capabilities       (bitfield, see {@link KryptonCapabilities})
 * </pre>
 *
 * <p>Both sides send this payload. The receiver intersects its own capabilities
 * with the remote's to determine the final negotiated set.</p>
 */
public record KryptonHelloPayload(int protocolVersion, int capabilities) implements CustomPacketPayload {

    public static final int CURRENT_PROTOCOL = 1;

    public static final CustomPacketPayload.Type<KryptonHelloPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("krypton_hybrid", "hello"));

    public static final StreamCodec<FriendlyByteBuf, KryptonHelloPayload> STREAM_CODEC =
            StreamCodec.of(KryptonHelloPayload::write, KryptonHelloPayload::read);

    private static KryptonHelloPayload read(FriendlyByteBuf buf) {
        int version = buf.readVarInt();
        int caps = buf.readVarInt();
        return new KryptonHelloPayload(version, caps);
    }

    private static void write(FriendlyByteBuf buf, KryptonHelloPayload payload) {
        buf.writeVarInt(payload.protocolVersion);
        buf.writeVarInt(payload.capabilities);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

