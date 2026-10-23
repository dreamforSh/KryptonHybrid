package com.xinian.KryptonHybrid.mixin.network.pipeline.encryption;

import com.xinian.KryptonHybrid.shared.network.pipeline.ClientConnectionEncryptionExtension;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.util.Crypt;
import net.minecraft.util.CryptException;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import java.security.Key;

/**
 * Client side of native encryption: vanilla still builds its ciphers (so its flow and
 * fallback stay intact), but the shared secret is handed to the connection, whose
 * {@code setEncryptionKey} then installs the native cipher instead (see
 * {@code encryption.ConnectionMixin}).
 */
@Mixin(ClientHandshakePacketListenerImpl.class)
public abstract class ClientHandshakePacketListenerImplMixin {

    @Shadow @Final private Connection connection;

    @Redirect(
            method = "handleHello",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Crypt;getCipher(ILjava/security/Key;)Ljavax/crypto/Cipher;"))
    private Cipher krypton$captureSecret(int mode, Key key) throws CryptException {
        if (key instanceof SecretKey secret) {
            ((ClientConnectionEncryptionExtension) this.connection).krypton$setPendingEncryptionKey(secret);
        }
        return Crypt.getCipher(mode, key);
    }
}
