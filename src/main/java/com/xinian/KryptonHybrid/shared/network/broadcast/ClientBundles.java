package com.xinian.KryptonHybrid.shared.network.broadcast;

import net.minecraft.network.protocol.BundlerInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;

import java.util.List;
import java.util.function.Consumer;

/** Sends packets collected for one player as client bundles. */
final class ClientBundles {
    private ClientBundles() {}

    /**
     * Sends {@code packets} in order: a single packet as is, more as bundles. The client
     * rejects a bundle with more than {@link BundlerInfo#BUNDLE_SIZE_LIMIT} packets
     * ("Too many packets in a bundle") and disconnects, so larger lists are split.
     * The list must not change afterwards: bundles keep views of it until encoded.
     */
    @SuppressWarnings("unchecked")
    static void send(Consumer<Packet<?>> sender, List<Packet<?>> packets) {
        int size = packets.size();
        for (int from = 0; from < size; from += BundlerInfo.BUNDLE_SIZE_LIMIT) {
            List<Packet<?>> part = packets.subList(from, Math.min(size, from + BundlerInfo.BUNDLE_SIZE_LIMIT));
            if (part.size() == 1) {
                sender.accept(part.get(0));
            } else {
                sender.accept(new ClientboundBundlePacket(
                        (Iterable<Packet<? super ClientGamePacketListener>>) (Iterable<?>) part));
            }
        }
    }
}
