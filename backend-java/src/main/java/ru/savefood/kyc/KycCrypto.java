package ru.savefood.kyc;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;
@Service
public class KycCrypto {
    private static final byte VERSION = (byte) 0x80;
    private static final SecureRandom RNG = new SecureRandom();
    private final byte[] signingKey;
    private final byte[] encryptionKey;
    public KycCrypto(@Value("${savefood.kyc-encryption-key:}") String key,
                     @Value("${savefood.kyc-plaintext-enabled:false}") boolean plaintextEnabled,
                     Environment environment) {
        key = key == null ? "" : key.strip();
        if (key.isEmpty()) {
            if (!plaintextEnabled || !environment.acceptsProfiles(Profiles.of("dev", "test"))) {
                throw new IllegalStateException(
                    "KYC_ENCRYPTION_KEY must be set to a valid Fernet key; plaintext KYC is permitted "
                    + "only with savefood.kyc-plaintext-enabled=true in the dev or test profile.");
            }
            this.signingKey = null;
            this.encryptionKey = null;
            return;
        }
        byte[] raw;
        try {
            raw = Base64.getUrlDecoder().decode(key);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                "KYC_ENCRYPTION_KEY is set but is not a valid Fernet key — generate one with "
                + "Fernet.generate_key().");
        }
        if (raw.length != 32) {
            throw new IllegalStateException(
                "KYC_ENCRYPTION_KEY is set but is not a valid Fernet key — generate one with "
                + "Fernet.generate_key().");
        }
        this.signingKey = Arrays.copyOfRange(raw, 0, 16);
        this.encryptionKey = Arrays.copyOfRange(raw, 16, 32);
    }
    public boolean enabled() {
        return encryptionKey != null;
    }
    /** Encrypt a freshly-saved upload in place. Plaintext passthrough is dev/test-only. */
    public void encryptFile(String path) {
        if (!enabled()) {
            return;
        }
        try {
            byte[] data = Files.readAllBytes(Paths.get(path));
            byte[] token = encrypt(data);
            Path tmp = Paths.get(path + ".enc.tmp");
            Files.write(tmp, token);
            Files.move(tmp, Paths.get(path), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new IllegalStateException("KYC document encryption failed: " + e.getMessage(), e);
        }
    }
    public byte[] readDecrypted(String path) throws IOException {
        byte[] data = Files.readAllBytes(Paths.get(path));
        if (!enabled()) {
            return data;
        }
        try {
            return decrypt(data);
        } catch (Exception e) {
            return data;
        }
    }
    private byte[] encrypt(byte[] plaintext) throws Exception {
        byte[] iv = new byte[16];
        RNG.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), new IvParameterSpec(iv));
        byte[] ciphertext = cipher.doFinal(plaintext);
        long timestamp = System.currentTimeMillis() / 1000L;
        ByteBuffer header = ByteBuffer.allocate(1 + 8 + 16 + ciphertext.length);
        header.put(VERSION).putLong(timestamp).put(iv).put(ciphertext);
        byte[] signed = header.array();
        byte[] hmac = hmac(signed);
        byte[] token = new byte[signed.length + 32];
        System.arraycopy(signed, 0, token, 0, signed.length);
        System.arraycopy(hmac, 0, token, signed.length, 32);
        return Base64.getUrlEncoder().encode(token);
    }
    private byte[] decrypt(byte[] token) throws Exception {
        byte[] raw = Base64.getUrlDecoder().decode(token);
        if (raw.length < 1 + 8 + 16 + 32 || raw[0] != VERSION) {
            throw new IllegalArgumentException("not a fernet token");
        }
        int bodyLen = raw.length - 32;
        byte[] body = Arrays.copyOfRange(raw, 0, bodyLen);
        byte[] hmac = Arrays.copyOfRange(raw, bodyLen, raw.length);
        if (!constantTimeEquals(hmac, hmac(body))) {
            throw new IllegalArgumentException("invalid HMAC");
        }
        byte[] iv = Arrays.copyOfRange(raw, 1 + 8, 1 + 8 + 16);
        byte[] ciphertext = Arrays.copyOfRange(raw, 1 + 8 + 16, bodyLen);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(ciphertext);
    }
    private byte[] hmac(byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
        return mac.doFinal(data);
    }
    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }
}
