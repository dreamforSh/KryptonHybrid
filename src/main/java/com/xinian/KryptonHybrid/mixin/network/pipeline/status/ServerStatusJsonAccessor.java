package com.xinian.KryptonHybrid.mixin.network.pipeline.status;

import net.minecraft.network.protocol.status.ServerStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerStatus.class)
public interface ServerStatusJsonAccessor {
    @Accessor(value = "json", remap = false)
    void krypton$setJson(String json);
}
