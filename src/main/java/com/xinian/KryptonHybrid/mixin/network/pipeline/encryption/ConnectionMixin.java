package com.xinian.KryptonHybrid.mixin.network.pipeline.encryption;

import com.velocitypowered.natives.encryption.VelocityCipher;
import com.velocitypowered.natives.util.Natives;
import com.xinian.KryptonHybrid.mixin.network.pipeline.ConnectionAccessor;
import com.xinian.KryptonHybrid.shared.misc.KryptonPipelineEvent;
import com.xinian.KryptonHybrid.shared.network.pipeline.ClientConnectionEncryptionExtension;
import com.xinian.KryptonHybrid.shared.network.pipeline.MinecraftCipherDecoder;
import com.xinian.KryptonHybrid.shared.network.pipeline.MinecraftCipherEncoder;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;

import javax.crypto.SecretKey;
import java.security.GeneralSecurityException;

@Mixin(Connection.class)
public class ConnectionMixin implements ClientConnectionEncryptionExtension {

    @Override
    public void setupEncryption(SecretKey key) throws GeneralSecurityException {
        ConnectionAccessor accessor = (ConnectionAccessor) this;
        if (!accessor.krypton$isEncrypted()) {
            VelocityCipher decryption = Natives.cipher.get().forDecryption(key);
            VelocityCipher encryption = Natives.cipher.get().forEncryption(key);

            accessor.krypton$setEncrypted(true);
            accessor.krypton$getChannel().pipeline().addBefore("splitter", "decrypt", new MinecraftCipherDecoder(decryption));
            accessor.krypton$getChannel().pipeline().addBefore("prepender", "encrypt", new MinecraftCipherEncoder(encryption));

            accessor.krypton$getChannel().pipeline().fireUserEventTriggered(KryptonPipelineEvent.ENCRYPTION_ENABLED);
        }
    }
}
