package com.xinian.KryptonHybrid.shared.network.security;

import com.xinian.KryptonHybrid.shared.KryptonConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates Minecraft handshake packets for protocol version and server address sanity.
 *
 * <p>This guard runs early in the connection lifecycle to reject scanning bots,
 * malformed clients, and handshake-based exploits before they consume server
 * resources on login or encryption.</p>
 *
 * <h3>Checks</h3>
 * <ul>
 *   <li>Protocol version must be within the configured range of known versions</li>
 *   <li>Server address must not exceed the configured max length (vanilla: 255)</li>
 *   <li>Server address must not be empty</li>
 *   <li>Server address must not contain control characters</li>
 * </ul>
 */
public final class HandshakeValidator {

    private static final Logger LOGGER = LoggerFactory.getLogger("KryptonSecurity");


    private HandshakeValidator() {}

    /**
     * Result of handshake validation.
     */
    public record ValidationResult(boolean valid, String reason) {
        public static final ValidationResult OK = new ValidationResult(true, null);

        public static ValidationResult fail(String reason) {
            return new ValidationResult(false, reason);
        }
    }

    /**
     * Validates a handshake packet's protocol version and server address.
     *
     * @param protocolVersion the protocol version from the handshake
     * @param serverAddress   the server address string from the handshake
     * @param serverPort      the port from the handshake
     * @return validation result
     */
    public static ValidationResult validate(int protocolVersion, String serverAddress, int serverPort) {
        if (!KryptonConfig.securityEnabled) return ValidationResult.OK;


        int minProto = KryptonConfig.securityMinProtocolVersion;
        int maxProto = KryptonConfig.securityMaxProtocolVersion;

        if (protocolVersion < minProto || protocolVersion > maxProto) {
            SecurityMetrics.INSTANCE.recordHandshakeRejected();
            String reason = String.format(
                    "Protocol version %d outside allowed range [%d, %d]",
                    protocolVersion, minProto, maxProto);
            LOGGER.warn("[Krypton Security] Handshake rejected: {}", reason);
            return ValidationResult.fail(reason);
        }


        if (serverAddress == null || serverAddress.isEmpty()) {
            SecurityMetrics.INSTANCE.recordHandshakeRejected();
            LOGGER.warn("[Krypton Security] Handshake rejected: empty server address");
            return ValidationResult.fail("Empty server address");
        }

        int maxLen = KryptonConfig.securityMaxHandshakeAddressLength;
        if (serverAddress.length() > maxLen) {
            SecurityMetrics.INSTANCE.recordHandshakeRejected();
            String reason = String.format(
                    "Server address too long: %d > %d", serverAddress.length(), maxLen);
            LOGGER.warn("[Krypton Security] Handshake rejected: {}", reason);
            return ValidationResult.fail(reason);
        }


        for (int i = 0; i < serverAddress.length(); i++) {
            char c = serverAddress.charAt(i);
            if (c < 0x20 && c != '\t') {
                SecurityMetrics.INSTANCE.recordHandshakeRejected();
                String reason = String.format(
                        "Server address contains control character at index %d (0x%02X)",
                        i, (int) c);
                LOGGER.warn("[Krypton Security] Handshake rejected: {}", reason);
                return ValidationResult.fail(reason);
            }
        }


        if (serverPort < 0 || serverPort > 65535) {
            SecurityMetrics.INSTANCE.recordHandshakeRejected();
            String reason = String.format("Invalid port: %d", serverPort);
            LOGGER.warn("[Krypton Security] Handshake rejected: {}", reason);
            return ValidationResult.fail(reason);
        }

        return ValidationResult.OK;
    }
}

