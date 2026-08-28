package ru.savefood.kyc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

class KycCryptoTest {

    private static final ApplicationContextRunner KYC_CONTEXT = new ApplicationContextRunner()
        .withBean(KycCrypto.class);

    @TempDir
    Path tempDir;

    private static String generateFernetKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getUrlEncoder().encodeToString(key);
    }

    @Test
    void roundtripEncryptsOnDisk() throws IOException {
        KycCrypto crypto = crypto(generateFernetKey(), false);
        Path doc = tempDir.resolve("doc.bin");
        byte[] plaintext = "secret identity document".getBytes();
        Files.write(doc, plaintext);

        crypto.encryptFile(doc.toString());
        assertThat(Files.readAllBytes(doc)).isNotEqualTo(plaintext);

        byte[] recovered = crypto.readDecrypted(doc.toString());
        assertThat(recovered).isEqualTo(plaintext);
    }

    @Test
    void explicitPlaintextOptInWorksOnlyInDevOrTestProfiles() throws IOException {
        KycCrypto crypto = crypto("", true, "test");
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
        KycCrypto crypto = crypto(generateFernetKey(), false);
        Path doc = tempDir.resolve("legacy.bin");
        byte[] data = "legacy plaintext".getBytes();
        Files.write(doc, data);

        byte[] recovered = crypto.readDecrypted(doc.toString());
        assertThat(recovered).isEqualTo(data);
    }

    @Test
    void missingOrEmptyKeyFailsClosedByDefault() {
        assertThatThrownBy(() -> crypto(null, false))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("KYC_ENCRYPTION_KEY must be set");
        assertThatThrownBy(() -> crypto("   ", false))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("KYC_ENCRYPTION_KEY must be set");
    }

    @Test
    void malformedOrWrongLengthKeyFailsAtStartup() {
        assertThatThrownBy(() -> crypto("not a Fernet key", false))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not a valid Fernet key");
        assertThatThrownBy(() -> crypto(Base64.getUrlEncoder().encodeToString(new byte[31]), false))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not a valid Fernet key");
    }

    @Test
    void plaintextOptInCannotEnableFallbackOutsideDevOrTest() {
        assertThatThrownBy(() -> crypto("", true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("dev or test profile");
        assertThatThrownBy(() -> crypto("", true, "production"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("dev or test profile");
        assertThat(crypto("", true, "dev").enabled()).isFalse();
    }

    @Test
    void defaultApplicationContextFailsStartupWithoutKey() {
        KYC_CONTEXT.run(context -> {
            assertThat(context).hasFailed();
            assertRootCauseContains(context.getStartupFailure(), "KYC_ENCRYPTION_KEY must be set");
        });
    }

    @Test
    void applicationContextFailsStartupForEmptyOrMalformedKey() {
        KYC_CONTEXT.withPropertyValues("savefood.kyc-encryption-key=   ").run(context -> {
            assertThat(context).hasFailed();
            assertRootCauseContains(context.getStartupFailure(), "KYC_ENCRYPTION_KEY must be set");
        });
        KYC_CONTEXT.withPropertyValues("savefood.kyc-encryption-key=not a Fernet key").run(context -> {
            assertThat(context).hasFailed();
            assertRootCauseContains(context.getStartupFailure(), "not a valid Fernet key");
        });
    }

    @Test
    void applicationContextStartsWithValidKeyOrExplicitTestPlaintextMode() {
        KYC_CONTEXT.withPropertyValues("savefood.kyc-encryption-key=" + generateFernetKey()).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(KycCrypto.class).enabled()).isTrue();
        });
        KYC_CONTEXT.withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
            .withPropertyValues("savefood.kyc-plaintext-enabled=true")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(KycCrypto.class).enabled()).isFalse();
            });
    }

    private static KycCrypto crypto(String key, boolean plaintextEnabled, String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return new KycCrypto(key, plaintextEnabled, environment);
    }

    private static void assertRootCauseContains(Throwable failure, String expectedMessage) {
        Throwable rootCause = failure;
        while (rootCause.getCause() != null) {
            rootCause = rootCause.getCause();
        }
        assertThat(rootCause)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(expectedMessage);
    }
}
