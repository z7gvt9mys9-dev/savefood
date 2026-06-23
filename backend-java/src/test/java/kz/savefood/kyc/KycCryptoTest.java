package kz.savefood.kyc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import static org.assertj.core.api.Assertions.assertThat;

class KycCryptoTest {

    @TempDir
    Path tempDir;

    private static String generateFernetKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getUrlEncoder().encodeToString(key);
    }

    @Test
    void roundtripEncryptsOnDisk() throws IOException {
        KycCrypto crypto = new KycCrypto(generateFernetKey());
        Path doc = tempDir.resolve("doc.bin");
        byte[] plaintext = "secret identity document".getBytes();
        Files.write(doc, plaintext);

        crypto.encryptFile(doc.toString());
        assertThat(Files.readAllBytes(doc)).isNotEqualTo(plaintext);

        byte[] recovered = crypto.readDecrypted(doc.toString());
        assertThat(recovered).isEqualTo(plaintext);
    }

    @Test
    void passthroughWithoutKey() throws IOException {
        KycCrypto crypto = new KycCrypto("");  // no key → passthrough
        assertThat(crypto.enabled()).isFalse();

        Path doc = tempDir.resolve("plain.bin");
        byte[] data = "plain".getBytes();
        Files.write(doc, data);

        crypto.encryptFile(doc.toString());  // no-op
        assertThat(Files.readAllBytes(doc)).isEqualTo(data);
        assertThat(crypto.readDecrypted(doc.toString())).isEqualTo(data);
    }

    @Test
    void readsLegacyPlaintextWithKey() throws IOException {
        // A file written before the key existed must still be readable (no throw).
        KycCrypto crypto = new KycCrypto(generateFernetKey());
        Path doc = tempDir.resolve("legacy.bin");
        byte[] data = "legacy plaintext".getBytes();
        Files.write(doc, data);

        byte[] recovered = crypto.readDecrypted(doc.toString());
        assertThat(recovered).isEqualTo(data);
    }
}
