package com.xinian.KryptonHybrid.shared.network.security;

import com.xinian.KryptonHybrid.shared.KryptonConfig;
import com.xinian.KryptonHybrid.shared.network.util.VarIntUtil;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;

/**
 * Hard limits on lengths and counts read from packets the server receives
 * ({@code security.packet_size.read_limits_enabled}).
 *
 * <p>The limits apply only while a server-side packet decoder reads a serverbound
 * packet ({@link #enter}). What a client reads from its server (registry sync, tags,
 * recipes, disconnect reasons) keeps vanilla's own limits. Each check looks at the
 * length or count the peer actually sent, before vanilla allocates anything for it,
 * and covers both the {@code FriendlyByteBuf} methods and the {@code StreamCodec}
 * paths ({@code Utf8String.read}, {@code ByteBufCodecs.readCount},
 * {@code FriendlyByteBuf.readByteArray(ByteBuf, int)}).</p>
 */
public final class InboundReadLimits {
    /** Whether the decode running on this thread reads a serverbound packet. */
    private static final ThreadLocal<boolean[]> SERVERBOUND = ThreadLocal.withInitial(() -> new boolean[1]);

    private InboundReadLimits() {}

    private static boolean enabled() {
        return KryptonConfig.securityEnabled && KryptonConfig.securityReadLimitsEnabled;
    }

    /**
     * Start of a packet decode. Every decode sets the flag again, so one left set by an
     * exception never outlives the next decode on the thread.
     */
    public static void enter(boolean serverbound) {
        if (enabled()) {
            SERVERBOUND.get()[0] = serverbound;
        }
    }

    public static void exit() {
        if (enabled()) {
            SERVERBOUND.get()[0] = false;
        }
    }

    /** Whether the read happening now on this thread is limited. */
    public static boolean active() {
        return enabled() && SERVERBOUND.get()[0];
    }

    /** String: at most {@code security.packet_size.string_max_chars} characters. */
    public static int maxStringChars(int requested) {
        return Math.min(requested, KryptonConfig.securityMaxStringChars);
    }

    /** Rejects a string whose encoded length (3 bytes per char at most) exceeds the limit. */
    public static void checkString(ByteBuf buf) {
        long maxBytes = 3L * KryptonConfig.securityMaxStringChars;
        int length = peekVarInt(buf);
        if (length > maxBytes) {
            reject("String of " + length + " bytes exceeds the security limit of "
                    + KryptonConfig.securityMaxStringChars + " characters");
        }
    }

    public static void checkByteArray(ByteBuf buf) {
        check(buf, KryptonConfig.securityMaxByteArrayBytes, "Byte array");
    }

    public static void checkCollection(ByteBuf buf) {
        check(buf, KryptonConfig.securityMaxCollectionElements, "Collection");
    }

    public static void checkMap(ByteBuf buf) {
        check(buf, KryptonConfig.securityMaxMapEntries, "Map");
    }

    public static void checkCounted(ByteBuf buf) {
        check(buf, KryptonConfig.securityMaxCountedElements, "Counted section");
    }

    private static void check(ByteBuf buf, int max, String what) {
        int count = peekVarInt(buf);
        if (count < 0 || count > max) {
            reject(what + " of size " + count + " exceeds the security limit of " + max);
        }
    }

    /** The next VarInt, without consuming it (malformed input throws like vanilla's read). */
    private static int peekVarInt(ByteBuf buf) {
        int start = buf.readerIndex();
        try {
            return VarIntUtil.readVarInt(buf);
        } finally {
            buf.readerIndex(start);
        }
    }

    private static void reject(String message) {
        SecurityMetrics.INSTANCE.recordReadLimitRejected();
        throw new DecoderException(message);
    }
}
