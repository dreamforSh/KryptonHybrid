package com.xinian.KryptonHybrid.mixin.network.microopt;

import com.google.common.collect.ImmutableList;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

@Mixin(ServerEntity.class)
public class ServerEntityMixin {

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Ljava/util/Collections;emptyList()Ljava/util/List;"))
    public List<Entity> construct$initialPassengersListIsGuavaImmutableList() {

        return ImmutableList.of();
    }
}

