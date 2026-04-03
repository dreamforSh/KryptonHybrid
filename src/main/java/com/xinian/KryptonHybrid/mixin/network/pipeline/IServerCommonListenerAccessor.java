package com.xinian.KryptonHybrid.mixin.network.pipeline;

import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor mixin for {@link ServerCommonPacketListenerImpl} that exposes the
 * {@code connection} field as a getter.
 *
 * <p>This accessor targets {@link ServerCommonPacketListenerImpl} directly (where
 * {@code connection} is a <strong>direct</strong> field), so it works reliably even
 * when no Mixin refmap is loaded — Mixin locates the field by its mojmap/Parchment
 * name within the declaring class without needing a SRG mapping lookup.</p>
 *
 * <p>Used by {@link ConfigurationFinishPhaseMixin} to obtain the channel for
 * {@code ServerConfigurationPacketListenerImpl} instances (which inherit the field
 * from this parent class) without requiring a {@code @Shadow} on an inherited field.</p>
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public interface IServerCommonListenerAccessor {
    @Accessor("connection")
    Connection krypton$getConnection();
}

