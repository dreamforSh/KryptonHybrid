package com.xinian.KryptonHybrid.shared.network.velocity;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.xinian.KryptonHybrid.shared.KryptonSharedBootstrap;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages Velocity Modern Forwarding on the backend server side.
 *
 * <p>Tracks pending login plugin queries, verifies HMAC-SHA256 signatures,
 * and parses the forwarding data (player IP, UUID, GameProfile, properties).</p>
 *
 * <h3>Protocol Flow</h3>
 * <ol>
 *   <li>Backend sends {@code velocity:player_info} login plugin request to the
 *       connecting "client" (which is actually Velocity proxy).</li>
 *   <li>Velocity responds with HMAC-signed forwarding data containing the
 *       real player's IP, UUID, name, and profile properties.</li>
 *   <li>Backend verifies the HMAC using the shared forwarding secret.</li>
 *   <li>Backend replaces the connection's address and continues login with
 *       the forwarded {@link GameProfile}.</li>
 * </ol>
 */
public final class VelocityModernForwardingHandler {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int HMAC_LENGTH = 32; // SHA-256 produces 32 bytes

    /**
     * Transaction IDs that we are currently waiting for a response on.
     * Using a concurrent set because multiple connections may be logging in
     * simultaneously across different Netty I/O threads.
     */
    private static final Set<Integer> PENDING_QUERIES = ConcurrentHashMap.newKeySet();

    /**
     * Counter for generating unique transaction IDs.
     * Starts at a high value to avoid collisions with vanilla's sequential IDs.
     */
    private static final AtomicInteger TRANSACTION_ID_COUNTER = new AtomicInteger(0x4B525950); // "KRYP"

    private VelocityModernForwardingHandler() {}

    /**
     * Generates a unique transaction ID for a velocity:player_info query.
     */
    public static int generateTransactionId() {
        return TRANSACTION_ID_COUNTER.getAndIncrement();
    }

    /**
     * Registers a transaction ID as pending (we sent a query and await a response).
     */
    public static void registerPendingQuery(int transactionId) {
        PENDING_QUERIES.add(transactionId);
    }

    /**
     * Checks whether the given transaction ID is a pending velocity query.
     */
    public static boolean isPendingQuery(int transactionId) {
        return PENDING_QUERIES.contains(transactionId);
    }

    /**
     * Removes a pending query (called after receiving the response).
     */
    public static void removePendingQuery(int transactionId) {
        PENDING_QUERIES.remove(transactionId);
    }

    /**
     * Result of processing a Velocity Modern Forwarding response.
     */
    public record ForwardingResult(GameProfile profile, SocketAddress remoteAddress, int forwardingVersion) {}

    /**
     * Verifies the HMAC signature and parses the forwarding data.
     *
     * @param rawData the raw bytes from the login plugin response (HMAC + forwarding data)
     * @param secret  the shared forwarding secret (from config)
     * @return the parsed forwarding result, or {@code null} if verification failed
     */
    public static ForwardingResult processForwardingData(byte[] rawData, String secret) {
        if (rawData.length <= HMAC_LENGTH) {
            KryptonSharedBootstrap.LOGGER.warn(
                    "Velocity forwarding data too short: {} bytes", rawData.length);
            return null;
        }

        // Split: [32 bytes HMAC] [forwarding data]
        byte[] receivedHmac = new byte[HMAC_LENGTH];
        System.arraycopy(rawData, 0, receivedHmac, 0, HMAC_LENGTH);

        byte[] forwardingData = new byte[rawData.length - HMAC_LENGTH];
        System.arraycopy(rawData, HMAC_LENGTH, forwardingData, 0, forwardingData.length);

        // Verify HMAC-SHA256
        byte[] expectedHmac;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            expectedHmac = mac.doFinal(forwardingData);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            KryptonSharedBootstrap.LOGGER.error(
                    "Failed to compute HMAC for Velocity forwarding verification", e);
            return null;
        }

        if (!MessageDigest.isEqual(receivedHmac, expectedHmac)) {
            KryptonSharedBootstrap.LOGGER.warn(
                    "Velocity modern forwarding HMAC verification failed! "
                    + "Check that the forwarding secret matches between Velocity and this server.");
            return null;
        }

        // Parse the forwarding data
        try {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(forwardingData));

            int version = buf.readVarInt();
            String address = buf.readUtf();
            UUID uuid = buf.readUUID();
            String name = buf.readUtf();

            // Read properties
            int propCount = buf.readVarInt();
            GameProfile profile = new GameProfile(uuid, name);
            for (int i = 0; i < propCount; i++) {
                String propName = buf.readUtf();
                String propValue = buf.readUtf();
                boolean hasSig = buf.readBoolean();
                String propSig = hasSig ? buf.readUtf() : null;
                if (propSig != null) {
                    profile.getProperties().put(propName, new Property(propName, propValue, propSig));
                } else {
                    profile.getProperties().put(propName, new Property(propName, propValue));
                }
            }

            // We don't need to read player key data for 1.21.1 (version >= MODERN_LAZY_SESSION
            // skips the key entirely on 1.19.3+)

            // Build the remote address
            SocketAddress remoteAddress;
            try {
                remoteAddress = new InetSocketAddress(
                        InetAddress.getByName(address), 0);
            } catch (UnknownHostException e) {
                KryptonSharedBootstrap.LOGGER.warn(
                        "Could not parse forwarded address '{}', using raw string", address);
                remoteAddress = new InetSocketAddress(address, 0);
            }

            KryptonSharedBootstrap.LOGGER.info(
                    "Velocity modern forwarding verified: {} (UUID: {}, IP: {}, version: {})",
                    name, uuid, address, version);

            return new ForwardingResult(profile, remoteAddress, version);
        } catch (Exception e) {
            KryptonSharedBootstrap.LOGGER.error(
                    "Failed to parse Velocity forwarding data", e);
            return null;
        }
    }
}

