package com.xinian.KryptonHybrid.mixin.network.microopt;

import com.xinian.KryptonHybrid.shared.network.util.VarIntUtil;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.VarInt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Optimizes {@link VarInt} hot paths:
 *
 * <h4>1. {@code getByteSize(int)} ??lookup-table replacement</h4>
 * <p>Replaces the vanilla loop with
 * {@link VarIntUtil#getVarIntLength(int)}, a single array lookup indexed by
 * {@link Integer#numberOfLeadingZeros}.</p>
 *
 * <h4>2. {@code read(ByteBuf)} ??branchless bit-twiddling fast path</h4>
 * <p>Replaces the vanilla byte-at-a-time loop with
 * {@link VarIntUtil#readVarInt(ByteBuf)}, which reads 4 bytes in one
 * {@code getIntLE} and decodes 1?? byte VarInts with zero branches.
 * This is the same algorithm used in {@code Varint21FrameDecoderMixin}
 * (from <a href="https://github.com/netty/netty/pull/14050">netty#14050</a>),
 * now applied to the general {@code VarInt.read()} hot path used by packet
 * deserialization, codec dispatch, entity data sync, and compression headers.</p>
 *
 * <p>{@code VarInt.read()} is called at minimum once per inbound packet (packet ID)
 * and often dozens of times for complex packets (collections, maps, NBT lengths,
 * entity metadata).  Eliminating per-byte branches on the critical decode path
 * reduces instruction-cache pressure and branch mispredictions.</p>
 */
@Mixin(VarInt.class)
public class VarIntMixin {

    @Inject(method = "getByteSize", at = @At("HEAD"), cancellable = true)
    private static void getByteSize$kryptonfnp(int v, CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(VarIntUtil.getVarIntLength(v));
    }

    @Inject(method = "read", at = @At("HEAD"), cancellable = true)
    private static void read$kryptonfnp(ByteBuf buffer, CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(VarIntUtil.readVarInt(buffer));
    }
}

