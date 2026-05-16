package com.xinian.KryptonHybrid.mixin.network.pipeline;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Connection.class)
public interface ConnectionAccessor {
    @Accessor("channel")
    Channel krypton$getChannel();

    @Accessor("encrypted")
    boolean krypton$isEncrypted();

    @Accessor("encrypted")
    void krypton$setEncrypted(boolean value);
}
