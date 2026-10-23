package com.xinian.KryptonHybrid.shared.network.broadcast;

import net.minecraft.network.protocol.BundlerInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class ClientBundlesTest {

    private static List<Packet<?>> packets(int count) {
        List<Packet<?>> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(new ClientboundSetTimePacket(i, i, false));
        }
        return list;
    }

    /** Flattens what was sent back into single packets, checking each bundle's size. */
    private static List<Packet<?>> sent(List<Packet<?>> packets, List<Integer> bundleSizes) {
        List<Packet<?>> out = new ArrayList<>();
        ClientBundles.send(packet -> {
            if (packet instanceof ClientboundBundlePacket bundle) {
                int size = 0;
                for (Packet<?> sub : bundle.subPackets()) {
                    out.add(sub);
                    size++;
                }
                bundleSizes.add(size);
            } else {
                out.add(packet);
                bundleSizes.add(1);
            }
        }, packets);
        return out;
    }

    private static void check(int count, List<Integer> expectedSizes) {
        List<Packet<?>> packets = packets(count);
        List<Integer> sizes = new ArrayList<>();
        List<Packet<?>> out = sent(packets, sizes);
        assertEquals(expectedSizes, sizes);
        assertEquals(count, out.size());
        for (int i = 0; i < count; i++) {
            assertSame(packets.get(i), out.get(i), "order at " + i);
        }
    }

    @Test
    void singlePacketIsNotBundled() {
        List<Packet<?>> packets = packets(1);
        List<Packet<?>> sent = new ArrayList<>();
        ClientBundles.send(sent::add, packets);
        assertEquals(1, sent.size());
        assertSame(packets.get(0), sent.get(0));
    }

    @Test
    void upToTheLimitIsOneBundle() {
        List<Packet<?>> sent = new ArrayList<>();
        ClientBundles.send(sent::add, packets(BundlerInfo.BUNDLE_SIZE_LIMIT));
        assertEquals(1, sent.size());
        assertInstanceOf(ClientboundBundlePacket.class, sent.get(0));
        check(BundlerInfo.BUNDLE_SIZE_LIMIT, List.of(BundlerInfo.BUNDLE_SIZE_LIMIT));
    }

    @Test
    void largerListsAreSplitInOrder() {
        int limit = BundlerInfo.BUNDLE_SIZE_LIMIT;
        check(limit + 1, List.of(limit, 1));
        check(2 * limit + 1, List.of(limit, limit, 1));
        check(2 * limit + 7, List.of(limit, limit, 7));
    }
}
