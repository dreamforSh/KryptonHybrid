package com.xinian.KryptonHybrid.shared.network.stats;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Stats keys (packet type and owning mod) for a packet, computed once per packet class
 * or custom-payload channel instead of on every encode.
 */
public final class PacketStatKeys {

    public record Keys(String type, String mod) {}

    /** Channels are a fixed set per mod list; the cap only guards against misuse. */
    private static final int MAX_CHANNELS = 4096;

    private static final ClassValue<Keys> BY_CLASS = new ClassValue<>() {
        @Override
        protected Keys computeValue(Class<?> type) {
            return new Keys(type.getSimpleName(), modOf(type.getPackageName()));
        }
    };

    private static final ConcurrentHashMap<ResourceLocation, Keys> BY_CHANNEL = new ConcurrentHashMap<>();

    private PacketStatKeys() {}

    public static Keys of(Packet<?> packet) {
        ResourceLocation channel;
        if (packet instanceof ClientboundCustomPayloadPacket cp) {
            channel = cp.getIdentifier();
        } else if (packet instanceof ServerboundCustomPayloadPacket sp) {
            channel = sp.getIdentifier();
        } else {
            return BY_CLASS.get(packet.getClass());
        }
        Keys keys = BY_CHANNEL.get(channel);
        if (keys == null) {
            keys = new Keys("custom:" + channel.getNamespace() + "/" + channel.getPath(), channel.getNamespace());
            if (BY_CHANNEL.size() < MAX_CHANNELS) {
                BY_CHANNEL.putIfAbsent(channel, keys);
            }
        }
        return keys;
    }

    private static String modOf(String packageName) {
        if (packageName.startsWith("net.minecraft.")) return "minecraft";
        String[] parts = packageName.split("\\.", 4);
        return parts.length >= 3 ? parts[2] : "unknown";
    }
}
