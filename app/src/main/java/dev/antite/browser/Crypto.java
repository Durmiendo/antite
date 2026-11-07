package dev.antite.browser;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class Crypto {
    private static final String ALIAS = "antite-vault";
    private static final String STORE = "AndroidKeyStore";
    private static final String MODE = "AES/GCM/NoPadding";
    private static SecretKey key;

    private Crypto() {
    }

    private static synchronized SecretKey key() throws Exception {
        if (key != null) return key;
        KeyStore ks = KeyStore.getInstance(STORE);
        ks.load(null);
        if (ks.containsAlias(ALIAS)) {
            key = ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
            return key;
        }
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE);
        g.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        key = g.generateKey();
        return key;
    }

    static String encrypt(String plain) {
        if (plain == null) return null;
        try {
            Cipher c = Cipher.getInstance(MODE);
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV();
            byte[] body = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            ByteBuffer out = ByteBuffer.allocate(1 + iv.length + body.length);
            out.put((byte) iv.length).put(iv).put(body);
            return Base64.encodeToString(out.array(), Base64.NO_WRAP);
        } catch (Exception e) {
            throw new IllegalStateException("encrypt failed", e);
        }
    }

    static String decrypt(String stored) {
        if (stored == null) return null;
        try {
            ByteBuffer in = ByteBuffer.wrap(Base64.decode(stored, Base64.NO_WRAP));
            byte[] iv = new byte[in.get()];
            in.get(iv);
            byte[] body = new byte[in.remaining()];
            in.get(body);
            Cipher c = Cipher.getInstance(MODE);
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(c.doFinal(body), StandardCharsets.UTF_8);
        } catch (Exception e) {
            android.util.Log.w("Antite", "decrypt failed", e);
            return null;
        }
    }
}
