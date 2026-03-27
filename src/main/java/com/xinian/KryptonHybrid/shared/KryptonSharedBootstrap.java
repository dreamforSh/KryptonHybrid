package com.xinian.KryptonHybrid.shared;

import com.velocitypowered.natives.util.Natives;
import org.slf4j.Logger;

public class KryptonSharedBootstrap {
    public static final Logger LOGGER = org.slf4j.LoggerFactory.getLogger(KryptonSharedBootstrap.class);

    static {
        if (System.getProperty("io.netty.allocator.maxOrder") == null) {
            System.setProperty("io.netty.allocator.maxOrder", "9");
        }
    }

    public static void run(boolean client) {
        if (!client) {
            LOGGER.info("Krypton is now accelerating your Minecraft server's networking stack \uD83D\uDE80");
        } else {
            LOGGER.info("Krypton is now accelerating your Minecraft client's networking stack \uD83D\uDE80");
            LOGGER.info("Note that Krypton is most effective on servers, not the client.");
        }
        LOGGER.info("Compression will use {}, encryption will use {}",
                Natives.compress.getLoadedVariant(), Natives.cipher.getLoadedVariant());

    }
}

