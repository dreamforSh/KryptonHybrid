package com.xinian.KryptonHybrid.shared.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.network.ConfigurationTask;
import net.neoforged.neoforge.network.configuration.ICustomConfigurationTask;

import java.util.function.Consumer;

/**
 * Configuration-phase task that actively probes the peer for Krypton Hybrid support.
 */
public final class KryptonHelloConfigurationTask implements ICustomConfigurationTask {
    public static final ConfigurationTask.Type TYPE = new ConfigurationTask.Type(
            ResourceLocation.fromNamespaceAndPath("krypton_hybrid", "hello"));
    public static final KryptonHelloConfigurationTask INSTANCE = new KryptonHelloConfigurationTask();

    private KryptonHelloConfigurationTask() {}

    @Override
    public ConfigurationTask.Type type() {
        return TYPE;
    }

    @Override
    public void run(Consumer<CustomPacketPayload> sender) {
        sender.accept(KryptonHelloPayload.current());
    }
}
