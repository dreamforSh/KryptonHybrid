package com.xinian.KryptonHybrid.shared.network.pipeline;

import javax.crypto.SecretKey;
import java.security.GeneralSecurityException;

public interface ClientConnectionEncryptionExtension {
    void setupEncryption(SecretKey key) throws GeneralSecurityException;

    /**
     * Client side: remembers the shared secret while vanilla builds its ciphers, so the
     * later {@code setEncryptionKey} call can install the native cipher instead.
     */
    void krypton$setPendingEncryptionKey(SecretKey key);
}
