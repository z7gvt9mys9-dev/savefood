package kz.savefood.kyc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.logging.Logger;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Encryption-at-rest for KYC eligibility/identity documents (§58), ported from
 * kyc_crypto.py. Wire-compatible with Python's {@code cryptography.fernet}: the
 * same {@code KYC_ENCRYPTION_KEY} (a base64url-encoded 32-byte Fernet key, first
 * 16 bytes HMAC-SHA256 signing, last 16 bytes AES-128-CBC) decrypts documents
 * written by either backend.
 *
 * <p>When the key is unset (local dev / CI) every method is a transparent
 * passthrough — one warning is logged so this can never be mistaken for
 * production behaviour. A malformed key fails loudly at startup rather than
 * silently storing plaintext.
 */
@Service
public class KycCrypto {

    private static final Logger log = Logger.getLogger(KycCrypto.class.getName());
    private static final byte VERSION = (byte) 0x80;
    private static final SecureRandom RNG = new SecureRandom();

    private final byte[] signingKey;
    private final byte[] encryptionKey;

    public KycCrypto(@Value("${savefood.kyc-encryption-key:}") String key) {
        key = key == null ? "" : key.strip();
        if (key.isEmpty()) {
            this.signingKey = null;
            this.encryptionKey = null;
            log.warning("[kyc_crypto] KYC_ENCRYPTION_KEY is unset — KYC documents are stored "
                + "UNENCRYPTED. Set it in production.");
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

    /** Encrypt a freshly-saved upload in place. No-op (passthrough) without a key. */
    public void encryptFile(String path) {
        if (!enabled()) {
            return;
        }
        try {
            byte[] data = Files.readAllBytes(Paths.get(path));
            byte[] token = encrypt(data);
            // Temp file + atomic replace, so a crash mid-write can't leave a
            // half-encrypted (unrecoverable) document.
            Path tmp = Paths.get(path + ".enc.tmp");
            Files.write(tmp, token);
            Files.move(tmp, Paths.get(path), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new IllegalStateException("KYC document encryption failed: " + e.getMessage(), e);
        }
    }

    /**
     * Read a stored document and return its plaintext bytes (in memory only).
     * Tolerates a plaintext file when no key is configured, and a key-but-legacy
     * (pre-encryption) file whose bytes aren't a valid Fernet token.
     */
    public byte[] readDecrypted(String path) throws IOException {
        byte[] data = Files.readAllBytes(Paths.get(path));
        if (!enabled()) {
            return data;
        }
        try {
            return decrypt(data);
        } catch (Exception e) {
            // Not a valid token → legacy plaintext written before the key existed.
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
