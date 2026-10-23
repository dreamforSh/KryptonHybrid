package com.xinian.KryptonHybrid.mixin.network.microopt;

import com.google.common.collect.ImmutableList;
import com.xinian.KryptonHybrid.shared.network.motion.MotionDeltaCache;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ServerEntity.class)
public class ServerEntityMixin {

    @Shadow @Final private Entity entity;

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Ljava/util/Collections;emptyList()Ljava/util/List;"))
    public List<Entity> construct$initialPassengersListIsGuavaImmutableList() {

        return ImmutableList.of();
    }

    /** Pairing sends the velocity directly; start the motion filter from scratch. */
    @Inject(method = "addPairing", at = @At("HEAD"))
    private void krypton$forgetMotionOnPair(ServerPlayer player, CallbackInfo ci) {
        MotionDeltaCache.forget(player.connection, this.entity.getId());
    }

    /** The player no longer sees this entity; drop its motion filter state. */
    @Inject(method = "removePairing", at = @At("HEAD"))
    private void krypton$forgetMotionOnUnpair(ServerPlayer player, CallbackInfo ci) {
        MotionDeltaCache.forget(player.connection, this.entity.getId());
    }
}
