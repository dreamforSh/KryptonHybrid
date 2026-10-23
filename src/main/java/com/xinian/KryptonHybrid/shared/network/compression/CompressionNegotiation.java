package com.xinian.KryptonHybrid.shared.network.compression;

import com.xinian.KryptonHybrid.shared.KryptonConfig;
import com.xinian.KryptonHybrid.shared.ProxyMode;
import io.netty.util.AttributeKey;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.custom.CustomQueryAnswerPayload;
import net.minecraft.network.protocol.login.custom.CustomQueryPayload;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

/**
 * Negotiates the packet compression algorithm with the remote peer during login.
 *
 * <p>Compression is switched on during LOGIN, before NeoForge negotiates its channels in
 * the configuration phase, so the negotiation uses vanilla's login custom query: right
 * before the server would send {@code ClientboundLoginCompressionPacket} it sends a
 * {@link Query} carrying its compression token and waits for the answer
 * ({@code ServerLoginCompressionMixin}). A Krypton Hybrid client answers with its own
 * token ({@code ClientHandshakeCompressionMixin}); a vanilla client answers that it does
 * not know the query. Each side records the pair of tokens on the connection.</p>
 *
 * <p>Both sides then apply the same rule to what was actually exchanged: use Zstd only
 * when both tokens are the same Zstd token (same dictionary id). A vanilla peer, a peer
 * from an older build, or a server without Zstd ends up with vanilla zlib. Behind a proxy
 * the server does not ask at all.</p>
 *
 * <p>With a dictionary the token also names the frame layout ({@link #DICT_FRAMES}):
 * dictionary frames are magicless and large packets use standard frames without the
 * dictionary. Every Zstd token also ends with {@link #BATCH_FRAMES}: this side can decode
 * {@link BatchFrames}. Whether the server actually sends batch frames is up to its
 * {@code batch_frames.enabled}.</p>
 */
public final class CompressionNegotiation {
    public static final ResourceLocation QUERY_ID = ResourceLocation.fromNamespaceAndPath("krypton_hybrid", "compression");
    /** Transaction id of the query; fixed, since the server sends at most one per login. */
    public static final int TRANSACTION_ID = 0x4B525950;

    private static final int MAX_TOKEN_LENGTH = 256;
    private static final String ZLIB_TOKEN = "zlib";
    private static final String ZSTD_TOKEN_PREFIX = "zstd;dict=";
    /** Frame layout of dictionary connections; see {@code ZstdUtil#FRAME_MAGIC}. */
    private static final String DICT_FRAMES = ";frames=split";
    /** Batch frames can be decoded; see {@link BatchFrames}. */
    private static final String BATCH_FRAMES = ";batch";

    /** The Zstd token both sides agreed on; absent when the connection uses zlib. */
    private static final AttributeKey<String> AGREED_TOKEN =
            AttributeKey.valueOf("krypton_hybrid:compression_token");

    private CompressionNegotiation() {}

    /** Server to client: the server's compression token. */
    public record Query(String token) implements CustomQueryPayload {
        public static Query read(FriendlyByteBuf buf) {
            return new Query(buf.readUtf(MAX_TOKEN_LENGTH));
        }

        @Override
        public ResourceLocation id() {
            return QUERY_ID;
        }

        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeUtf(this.token, MAX_TOKEN_LENGTH);
        }
    }

    /** Client to server: the client's compression token. */
    public record Answer(String token) implements CustomQueryAnswerPayload {
        public static Answer read(FriendlyByteBuf buf) {
            return new Answer(buf.readUtf(MAX_TOKEN_LENGTH));
        }

        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeUtf(this.token, MAX_TOKEN_LENGTH);
        }
    }

    /**
     * The token for the current configuration and dictionary. Behind a proxy the backend
     * link must stay on zlib, because the proxy decodes it.
     */
    public static String localToken() {
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

    /** Whether {@code token} offers Zstd at all (otherwise there is nothing to negotiate). */
    public static boolean isZstdToken(String token) {
        return token.startsWith(ZSTD_TOKEN_PREFIX);
    }

    /** Client side: records the exchange for {@code serverToken} and returns the answer to send. */
    public static Answer answer(Connection connection, String serverToken) {
        String local = localToken();
        recordAgreement(connection, local, serverToken);
        return new Answer(local);
    }

    /**
     * Records the outcome of the exchange on {@code connection}: Zstd when both tokens are the
     * same Zstd token, zlib otherwise ({@code peerToken} is {@code null} for a peer that did
     * not understand the query).
     */
    public static void recordAgreement(Connection connection, String localToken, @Nullable String peerToken) {
        if (isZstdToken(localToken) && localToken.equals(peerToken)) {
            connection.channel().attr(AGREED_TOKEN).set(localToken);
        } else {
            connection.channel().attr(AGREED_TOKEN).set(null);
        }
    }

    /** Returns {@code true} when both sides agreed on a Zstd token during login. */
    public static boolean shouldUseZstd(Connection connection) {
        return agreedToken(connection) != null;
    }

    /** Returns {@code true} when the agreed token says both sides can decode {@link BatchFrames}. */
    public static boolean supportsBatchFrames(Connection connection) {
        String agreed = agreedToken(connection);
        return agreed != null && agreed.endsWith(BATCH_FRAMES);
    }

    /** Dictionary id named in the agreed token ({@code 0} for none or no agreement). */
    public static long agreedDictionaryId(Connection connection) {
        String agreed = agreedToken(connection);
        if (agreed == null) {
            return 0L;
        }
        String rest = agreed.substring(ZSTD_TOKEN_PREFIX.length());
        int end = rest.indexOf(';');
        return Long.parseLong(end < 0 ? rest : rest.substring(0, end));
    }

    /** "agreed=..." for logs. */
    public static String describe(Connection connection) {
        String agreed = agreedToken(connection);
        return agreed == null ? "no Zstd agreement" : "agreed=" + agreed;
    }

    @Nullable
    private static String agreedToken(Connection connection) {
        return connection.channel() == null ? null : connection.channel().attr(AGREED_TOKEN).get();
    }
}
