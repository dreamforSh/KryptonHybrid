package com.xinian.KryptonHybrid.shared.network.compression;

import com.xinian.KryptonHybrid.shared.KryptonConfig;
import com.xinian.KryptonHybrid.shared.ProxyMode;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.ConnectionData;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.network.NetworkRegistry;

/**
 * Negotiates the packet compression algorithm with the remote peer.
 *
 * <p>Compression is switched on during LOGIN, before any PLAY-phase payload can be
 * exchanged, so the negotiation piggybacks on Forge's login handshake instead: this
 * class registers an event channel whose protocol version string is the local
 * compression capability token. Forge sends every channel's version in
 * {@code S2CModList} / {@code C2SModListReply} and stores the peer's list in the
 * connection's {@link ConnectionData}, which is in place on both sides before the
 * server sends {@code ClientboundLoginCompressionPacket}.</p>
 *
 * <p>Both sides then apply the same rule: use Zstd only when the local token is a Zstd
 * token and equals the peer's token (same dictionary id). A vanilla, Paper or proxy
 * peer never advertises the channel, so it always gets vanilla zlib.</p>
 *
 * <p>With a dictionary the token also names the frame layout ({@link #DICT_FRAMES}):
 * dictionary frames are magicless and large packets use standard frames without the
 * dictionary. A peer built before that layout advertises the bare token, so the two
 * fall back to zlib instead of misreading each other's frames.</p>
 *
 * <p>The channel accepts any peer version (including absent), so it never blocks a
 * connection by itself.</p>
 */
public final class CompressionNegotiation {
    public static final ResourceLocation CHANNEL = new ResourceLocation("krypton_hybrid", "compression");

    private static final String ZLIB_TOKEN = "zlib";
    private static final String ZSTD_TOKEN_PREFIX = "zstd;dict=";
    /** Frame layout of dictionary connections; see {@code ZstdUtil#FRAME_MAGIC}. */
    private static final String DICT_FRAMES = ";frames=split";

    private CompressionNegotiation() {}

    public static void register() {
        NetworkRegistry.newEventChannel(CHANNEL, CompressionNegotiation::localToken, peer -> true, peer -> true);
    }

    /**
     * The token advertised to the peer. Behind a proxy the backend link must stay on
     * zlib, because the proxy decodes it and may forward login handshakes from a client
     * that would otherwise agree to Zstd.
     */
    static String localToken() {
        if (KryptonConfig.proxyMode != ProxyMode.NONE) {
            return ZLIB_TOKEN;
        }
        long dictId = ZstdUtil.negotiableDictionaryId();
        if (dictId < 0L) {
            return ZLIB_TOKEN;
        }
        return dictId == 0L ? ZSTD_TOKEN_PREFIX + "0" : ZSTD_TOKEN_PREFIX + dictId + DICT_FRAMES;
    }

    /**
     * Returns {@code true} when both this side and the peer on {@code connection}
     * advertised the same Zstd token during the login handshake.
     */
    public static boolean shouldUseZstd(Connection connection) {
        String local = localToken();
        if (!local.startsWith(ZSTD_TOKEN_PREFIX)) {
            return false;
        }
        ConnectionData data = NetworkHooks.getConnectionData(connection);
        if (data == null) {
            return false;
        }
        return local.equals(data.getChannels().get(CHANNEL));
    }
}
