package com.xinian.KryptonHybrid.shared.network.compression;

import com.xinian.KryptonHybrid.shared.KryptonConfig;
import com.xinian.KryptonHybrid.shared.KryptonSharedBootstrap;
import com.xinian.KryptonHybrid.shared.ProxyMode;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.ConnectionData;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.network.NetworkInstance;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.event.EventNetworkChannel;

import java.lang.reflect.Field;

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
 * <p>Both sides then apply the same rule to what was actually exchanged: use Zstd only
 * when the token this side advertised is a Zstd token and equals the peer's (same
 * dictionary id). A vanilla, Paper or proxy peer never advertises the channel, so it
 * always gets vanilla zlib.</p>
 *
 * <h3>Keeping the token current</h3>
 * <p>Forge reads a channel's version string once, when the channel is created during
 * mod construction, before any config is loaded. {@link #refreshToken()} therefore
 * writes the token of the current config into the channel (by reflection on Forge's
 * {@code NetworkInstance}) whenever the config or the dictionary (re)loads, and
 * decisions use that advertised token, never one recomputed later, so both sides always
 * judge the same pair of strings.</p>
 *
 * <p>With a dictionary the token also names the frame layout ({@link #DICT_FRAMES}):
 * dictionary frames are magicless and large packets use standard frames without the
 * dictionary. A peer built before that layout advertises the bare token, so the two
 * fall back to zlib instead of misreading each other's frames.</p>
 *
 * <p>Every Zstd token of this build also ends with {@link #BATCH_FRAMES}: this side can
 * decode {@link BatchFrames}. A peer built before them advertises the token without it,
 * so the two fall back to zlib like for any other mismatch; the token is compared as a
 * whole on both sides, so they always reach the same decision. Whether the server
 * actually sends batch frames is up to its {@code batch_frames.enabled}.</p>
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
    /** Batch frames can be decoded; see {@link BatchFrames}. */
    private static final String BATCH_FRAMES = ";batch";

    /** The token peers are being sent. */
    private static volatile String advertised = ZLIB_TOKEN;
    /** The channel's {@link NetworkInstance}, or {@code null} if its version cannot be updated. */
    private static volatile NetworkInstance channel;
    private static Field versionField;

    private CompressionNegotiation() {}

    public static void register() {
        EventNetworkChannel created = NetworkRegistry.newEventChannel(
                CHANNEL, CompressionNegotiation::computeAndAdvertise, peer -> true, peer -> true);
        try {
            Field instanceField = EventNetworkChannel.class.getDeclaredField("instance");
            instanceField.setAccessible(true);
            Field version = NetworkInstance.class.getDeclaredField("networkProtocolVersion");
            version.setAccessible(true);
            versionField = version;
            channel = (NetworkInstance) instanceField.get(created);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Peers keep seeing the token from mod construction (default config); both sides
            // still decide on the exchanged tokens, so they never disagree.
            KryptonSharedBootstrap.LOGGER.warn(
                    "Cannot update the compression negotiation token; compression config changes"
                            + " will not be negotiated: {}", e.toString());
        }
    }

    private static String computeAndAdvertise() {
        String token = localToken();
        advertised = token;
        return token;
    }

    /**
     * Advertises the token of the current configuration and dictionary to peers that log
     * in from now on. Called after every config or dictionary (re)load.
     */
    public static synchronized void refreshToken() {
        NetworkInstance instance = channel;
        if (instance == null) {
            return;
        }
        String token = localToken();
        try {
            versionField.set(instance, token);
            advertised = token;
        } catch (ReflectiveOperationException | RuntimeException e) {
            channel = null;
            KryptonSharedBootstrap.LOGGER.warn("Cannot update the compression negotiation token: {}", e.toString());
        }
    }

    /**
     * The token for the current configuration. Behind a proxy the backend link must stay
     * on zlib, because the proxy decodes it and may forward login handshakes from a
     * client that would otherwise agree to Zstd.
     */
    static String localToken() {
        if (KryptonConfig.proxyMode != ProxyMode.NONE) {
            return ZLIB_TOKEN;
        }
        long dictId = ZstdUtil.negotiableDictionaryId();
        if (dictId < 0L) {
            return ZLIB_TOKEN;
        }
        return (dictId == 0L ? ZSTD_TOKEN_PREFIX + "0" : ZSTD_TOKEN_PREFIX + dictId + DICT_FRAMES)
                + BATCH_FRAMES;
    }

    /**
     * Returns {@code true} when this side and the peer on {@code connection} advertised
     * the same Zstd token during the login handshake.
     */
    public static boolean shouldUseZstd(Connection connection) {
        return agreedZstdToken(connection) != null;
    }

    /**
     * Returns {@code true} when the Zstd token both sides agreed on says they can decode
     * {@link BatchFrames}.
     */
    public static boolean supportsBatchFrames(Connection connection) {
        String agreed = agreedZstdToken(connection);
        return agreed != null && agreed.endsWith(BATCH_FRAMES);
    }

    /** The Zstd token both sides advertised, or {@code null} when they did not agree on one. */
    private static String agreedZstdToken(Connection connection) {
        String local = advertised;
        if (!local.startsWith(ZSTD_TOKEN_PREFIX)) {
            return null;
        }
        ConnectionData data = NetworkHooks.getConnectionData(connection);
        if (data == null) {
            return null;
        }
        return local.equals(data.getChannels().get(CHANNEL)) ? local : null;
    }

    /** "local=..., peer=..." for logs. */
    public static String describe(Connection connection) {
        ConnectionData data = NetworkHooks.getConnectionData(connection);
        String peer = data == null ? "no connection data" : String.valueOf(data.getChannels().get(CHANNEL));
        return "local=" + advertised + ", peer=" + peer;
    }

    /** Dictionary id in the advertised Zstd token ({@code 0} for none). */
    public static long advertisedDictionaryId() {
        String token = advertised;
        if (!token.startsWith(ZSTD_TOKEN_PREFIX)) {
            return 0L;
        }
        String rest = token.substring(ZSTD_TOKEN_PREFIX.length());
        int end = rest.indexOf(';');
        return Long.parseLong(end < 0 ? rest : rest.substring(0, end));
    }
}
