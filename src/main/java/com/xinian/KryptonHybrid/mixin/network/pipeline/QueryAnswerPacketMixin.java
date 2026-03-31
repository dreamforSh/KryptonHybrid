package com.xinian.KryptonHybrid.mixin.network.pipeline;

import com.xinian.KryptonHybrid.shared.network.velocity.VelocityForwardingPayload;
import com.xinian.KryptonHybrid.shared.network.velocity.VelocityModernForwardingHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.network.protocol.login.custom.CustomQueryAnswerPayload;
import net.minecraft.network.protocol.login.custom.DiscardedQueryAnswerPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Intercepts the deserialization of {@link ServerboundCustomQueryAnswerPacket} to
 * preserve the raw payload bytes for Velocity modern forwarding queries.
 *
 * <p>Vanilla's codec discards all payload bytes in {@code readUnknownPayload()}.
 * This mixin redirects the {@code readPayload()} call within the packet's
 * {@code read()} method to check whether the transaction ID belongs to a
 * pending Velocity modern forwarding query. If so, the raw bytes are captured
 * into a {@link VelocityForwardingPayload} instead of being discarded.</p>
 */
@Mixin(ServerboundCustomQueryAnswerPacket.class)
public class QueryAnswerPacketMixin {

    /**
     * Redirects the readPayload call to conditionally preserve velocity forwarding data.
     *
     * <p>The packet wire format after the transaction ID VarInt is:
     * {@code [Boolean: hasData] [Optional: data bytes]}.
     * Vanilla ignores this structure and skips all remaining bytes.</p>
     */
    @Redirect(
            method = "read",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/login/ServerboundCustomQueryAnswerPacket;readPayload(ILnet/minecraft/network/FriendlyByteBuf;)Lnet/minecraft/network/protocol/login/custom/CustomQueryAnswerPayload;"
            )
    )
    private static CustomQueryAnswerPayload krypton$captureVelocityPayload(
            int transactionId, FriendlyByteBuf buffer) {

        if (VelocityModernForwardingHandler.isPendingQuery(transactionId)) {
            // This is our velocity:player_info response — read it properly
            if (buffer.readableBytes() > 0 && buffer.readBoolean()) {
                // Velocity responded with forwarding data
                int readable = buffer.readableBytes();
                if (readable > 0 && readable <= 1048576) {
                    byte[] data = new byte[readable];
                    buffer.readBytes(data);
                    return new VelocityForwardingPayload(data);
                }
            }
            // Velocity declined or sent empty response
            buffer.skipBytes(buffer.readableBytes());
            return null;
        }

        // Default behavior: discard all remaining bytes (same as vanilla)
        int readable = buffer.readableBytes();
        if (readable >= 0 && readable <= 1048576) {
            buffer.skipBytes(readable);
            return DiscardedQueryAnswerPayload.INSTANCE;
        }
        throw new IllegalArgumentException("Payload may not be larger than 1048576 bytes");
    }
}

