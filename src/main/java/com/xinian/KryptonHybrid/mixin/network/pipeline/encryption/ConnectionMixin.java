package com.xinian.KryptonHybrid.mixin.network.pipeline.encryption;

import com.velocitypowered.natives.encryption.VelocityCipher;
import com.velocitypowered.natives.util.Natives;
import io.netty.channel.Channel;
import com.xinian.KryptonHybrid.shared.KryptonSharedBootstrap;
import com.xinian.KryptonHybrid.shared.misc.KryptonPipelineEvent;
import com.xinian.KryptonHybrid.shared.network.pipeline.ClientConnectionEncryptionExtension;
import com.xinian.KryptonHybrid.shared.network.pipeline.MinecraftCipherDecoder;
import com.xinian.KryptonHybrid.shared.network.pipeline.MinecraftCipherEncoder;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import java.security.GeneralSecurityException;

@Mixin(Connection.class)
public class ConnectionMixin implements ClientConnectionEncryptionExtension {
    @Shadow private boolean encrypted;
    @Shadow private Channel channel;

    /** Client: the secret captured from {@code handleHello}, until encryption starts. */
    @Unique
    private volatile SecretKey krypton$pendingKey;

    @Override
    public void setupEncryption(SecretKey key) throws GeneralSecurityException {
        if (!this.encrypted) {
            VelocityCipher decryption = Natives.cipher.get().forDecryption(key);
            VelocityCipher encryption = Natives.cipher.get().forEncryption(key);

            this.encrypted = true;
            this.channel.pipeline().addBefore("splitter", "decrypt", new MinecraftCipherDecoder(decryption));
            this.channel.pipeline().addBefore("prepender", "encrypt", new MinecraftCipherEncoder(encryption));

            this.channel.pipeline().fireUserEventTriggered(KryptonPipelineEvent.ENCRYPTION_ENABLED);
        }
    }

    @Override
    public void krypton$setPendingEncryptionKey(SecretKey key) {
        this.krypton$pendingKey = key;
    }

    /**
     * Client side (the server never calls this; see {@code ServerLoginPacketListenerImplMixin}):
     * installs the native in-place cipher instead of vanilla's Java cipher handlers, which
     * allocate and copy for every read. Falls back to vanilla if that fails.
     */
    @Inject(method = "setEncryptionKey", at = @At("HEAD"), cancellable = true)
    private void krypton$useNativeCipher(Cipher decrypting, Cipher encrypting, CallbackInfo ci) {
        SecretKey key = this.krypton$pendingKey;
        if (key == null) {
            return;
        }
        this.krypton$pendingKey = null;
        try {
            setupEncryption(key);
            ci.cancel();
        } catch (GeneralSecurityException | RuntimeException e) {
            KryptonSharedBootstrap.LOGGER.warn("[Krypton] Native cipher unavailable, using vanilla encryption", e);
        }
    }
}

