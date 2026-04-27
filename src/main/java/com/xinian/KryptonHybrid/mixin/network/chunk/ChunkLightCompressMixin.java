package com.xinian.KryptonHybrid.mixin.network.chunk;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import com.xinian.KryptonHybrid.shared.KryptonConfig;
import com.xinian.KryptonHybrid.shared.network.control.KryptonWireFormat;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.BitSet;
import java.util.List;

/**
 * Optimizes {@link ClientboundLightUpdatePacketData} serialization for Forge 1.20.1.
 *
 * <h3>Problem</h3>
 * <p>Each non-empty light section (DataLayer) is always serialized as a raw 2048-byte
 * nibble array, regardless of content. Sky-light sections above the surface are nearly
 * always uniform (all nibble values == 15, stored as 0xFF bytes). In a standard
 * overworld chunk with a view distance of 12, up to 20 such "all-max" sections can be
 * present, costing {@code 20 脳 2048 = 40 960} bytes of redundant light payload per chunk
 * before connection-level compression.</p>
 *
 * <h3>Differences from 1.19.2</h3>
 * <p>The {@code trustEdges} boolean field was removed from
 * {@link ClientboundLightUpdatePacketData} in 1.20.1. This class therefore omits it
 * entirely. The wire format is otherwise identical to the 1.19.2 variant.</p>
 *
 * <h3>Solution 鈥?Uniform-RLE light encoding</h3>
 * <p>When {@link KryptonConfig#lightOptEnabled} is {@code true} and net savings are
 * positive, the {@code write()} method is replaced with a Krypton-aware format
 * identified by a leading {@code 0x4B} ('K') marker byte:</p>
 * <pre>
 *   [0x4B]           鈥?Krypton marker (1 byte)
 *   [skyYMask]       鈥?BitSet (FriendlyByteBuf.writeBitSet)
 *   [blockYMask]     鈥?BitSet
 *   [emptySkyYMask]    BitSet
 *   [emptyBlockYMask]  BitSet
 *   [skyCount]         VarInt
 *   For each sky DataLayer:
 *     [0x00] + 2048 bytes  raw encoding (fixed size; no VarInt prefix)
 *     [0x01] + 1 byte      uniform encoding (all nibble pairs == byte)
 *   [blockCount]     VarInt
 *   For each block DataLayer: same as above
 * </pre>
 *
 * <h3>Net-savings guard</h3>
 * <p>Before writing, the encoder counts uniform (U) and raw (R) DataLayers and
 * computes {@code savings = 2048*U + R - 1}. If {@code savings <= 0} (e.g. all
 * DataLayers are non-uniform, as in underground/Nether chunks), the vanilla
 * {@code write()} path is used unchanged so no bandwidth is wasted.</p>
 *
 * <p>If the incoming buffer does not start with {@code 0x4B} (vanilla server,
 * net-savings guard triggered, or mod disabled), the original constructor path
 * runs unmodified.</p>
 *
 * <h3>Compatibility</h3>
 * <p>This optimization requires Krypton Hybrid on <strong>both</strong> the server and the
 * client 鈥?consistent with the existing ZSTD compression requirement. Vanilla clients
 * cannot connect to a Krypton server anyway due to the compression algorithm mismatch.</p>
 */
@Mixin(ClientboundLightUpdatePacketData.class)
public abstract class ChunkLightCompressMixin {

    /** Magic marker byte that identifies the Krypton light-data encoding. */
    @Unique private static final int KRYPTON_MARKER = 0x4B; // 'K'
    /** Encoding flag: raw 2048-byte DataLayer follows. */
    @Unique private static final byte ENC_RAW     = 0x00;
    /** Encoding flag: uniform DataLayer; a single byte (the repeated value) follows. */
    @Unique private static final byte ENC_UNIFORM = 0x01;


    @Shadow @Final private BitSet skyYMask;
    @Shadow @Final private BitSet blockYMask;
    @Shadow @Final private BitSet emptySkyYMask;
    @Shadow @Final private BitSet emptyBlockYMask;
    @Shadow @Final private List<byte[]> skyUpdates;
    @Shadow @Final private List<byte[]> blockUpdates;

    /**
     * Replaces the vanilla {@code write()} with Krypton's compressed variant when
     * the encoding produces a net bandwidth saving.
     *
     * <p>Net savings formula: {@code 2048*U + R - 1} where U = uniform DataLayer
     * count and R = raw DataLayer count. The {@code -1} accounts for the marker byte
     * overhead. With fixed-size raw encoding (no VarInt prefix), each raw DataLayer
     * saves 1 byte vs vanilla; each uniform DataLayer saves 2048 bytes. The method
     * falls back to vanilla when savings &le; 0 (e.g. all-non-uniform underground
     * chunks, or empty light packets).</p>
     */
    @Inject(method = "write", at = @At("HEAD"), cancellable = true)
    private void write$krypton(FriendlyByteBuf buf, CallbackInfo ci) {
        if (!KryptonConfig.lightOptEnabled || !KryptonWireFormat.canWriteCurrentLightData()) return;

        int uniformCount = krypton$countUniform(this.skyUpdates) + krypton$countUniform(this.blockUpdates);
        int totalCount   = this.skyUpdates.size() + this.blockUpdates.size();
        int rawCount     = totalCount - uniformCount;

        if (2048L * uniformCount + rawCount <= 1L) return;

        buf.writeByte(KRYPTON_MARKER);
        buf.writeBitSet(this.skyYMask);
        buf.writeBitSet(this.blockYMask);
        buf.writeBitSet(this.emptySkyYMask);
        buf.writeBitSet(this.emptyBlockYMask);
        krypton$writeCompressedList(buf, this.skyUpdates);
        krypton$writeCompressedList(buf, this.blockUpdates);
        ci.cancel();
    }

    @Unique
    private static int krypton$countUniform(List<byte[]> arrays) {
        int count = 0;
        for (byte[] arr : arrays) if (krypton$isUniform(arr)) count++;
        return count;
    }

    /**
     * Writes a list of DataLayer arrays using Krypton's encoding.
     *
     * <p>Raw arrays are written as exactly 2048 bytes with no VarInt length prefix
     * (DataLayer size is always fixed), saving 2 bytes per raw array compared to
     * {@code FriendlyByteBuf.writeByteArray()} which prepends a VarInt length.</p>
     */
    @Unique
    private static void krypton$writeCompressedList(FriendlyByteBuf buf, List<byte[]> arrays) {
        buf.writeVarInt(arrays.size());
        for (byte[] arr : arrays) {
            if (krypton$isUniform(arr)) {
                buf.writeByte(ENC_UNIFORM);
                buf.writeByte(arr[0]);
            } else {
                buf.writeByte(ENC_RAW);
                buf.writeBytes(arr);
            }
        }
    }

    /**
     * Returns {@code true} if every byte in {@code arr} is equal to {@code arr[0]}.
     * Early-exits on the first mismatch.
     */
    @Unique
    private static boolean krypton$isUniform(byte[] arr) {
        byte first = arr[0];
        for (int i = 1; i < arr.length; i++) {
            if (arr[i] != first) return false;
        }
        return true;
    }
}

