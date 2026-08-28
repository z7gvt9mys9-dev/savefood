package ru.savefood.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.savefood.it.PostgresIT;
import ru.savefood.needy.NeedyRepository;
import ru.savefood.needy.NeedyService;
import ru.savefood.photo.DeliveryPhotoStorage;
import ru.savefood.security.PasswordService;

class SensitiveFileAccountErasureIT extends PostgresIT {

    @TempDir
    Path tempDir;

    @Test
    void accountErasureCommitsOnlyWithObservableDeliveryPhotoRetryWork() throws Exception {
        Path needyDir = Files.createDirectory(tempDir.resolve("needy"));
        Path volunteerKycDir = Files.createDirectory(tempDir.resolve("volunteer-kyc"));
        Path deliveryDir = Files.createDirectory(tempDir.resolve("delivery"));
        Path legacyDir = Files.createDirectory(tempDir.resolve("legacy"));
        Path proof = Files.write(deliveryDir.resolve("proof.jpg"), new byte[] {1});
        AtomicBoolean fail = new AtomicBoolean(true);
        SensitiveFileCleanup cleanup = new SensitiveFileCleanup(jdbc,
            needyDir.toString(), volunteerKycDir.toString(), deliveryDir.toString(), legacyDir.toString(),
            path -> {
                if (fail.get()) {
                    throw new java.io.IOException("injected account-erasure failure");
                }
                Files.deleteIfExists(path);
            });
        NeedyService service = new NeedyService(jdbc, new NeedyRepository(jdbc),
            new PasswordService(), new DeliveryPhotoStorage(cleanup));
        int needyId = insertNeedy("Recipient");
        int ticketId = jdbc.queryForObject(
            "INSERT INTO tickets (needy_id, status, created_at, delivery_photo, delivery_photo_status) "
                + "VALUES (?, 'fulfilled', NOW(), '/delivery_photos/proof.jpg', 'approved') RETURNING id",
            Integer.class, needyId);

        tx.executeWithoutResult(ignored -> service.eraseAccount(needyId));

        assertThat(jdbc.queryForObject(
            "SELECT delivery_photo FROM tickets WHERE id = ?", String.class, ticketId)).isNull();
        assertThat(proof).exists();
        Map<String, Object> queued = jdbc.queryForMap(
            "SELECT storage_type, file_ref, completed_at, last_error "
                + "FROM sensitive_file_cleanup WHERE file_ref = '/delivery_photos/proof.jpg'");
        assertThat(queued).containsEntry("storage_type", "delivery_photo")
            .containsEntry("file_ref", "/delivery_photos/proof.jpg");
        assertThat(queued.get("completed_at")).isNull();
        assertThat(queued.get("last_error")).isEqualTo("injected account-erasure failure");

        fail.set(false);
        jdbc.update("UPDATE sensitive_file_cleanup SET next_attempt_at = CURRENT_TIMESTAMP "
            + "WHERE file_ref = '/delivery_photos/proof.jpg'");
        cleanup.retryPending();

        assertThat(proof).doesNotExist();
        assertThat(jdbc.queryForObject(
            "SELECT completed_at IS NOT NULL FROM sensitive_file_cleanup "
                + "WHERE file_ref = '/delivery_photos/proof.jpg'", Boolean.class)).isTrue();
        int attempts = jdbc.queryForObject(
            "SELECT attempts FROM sensitive_file_cleanup "
                + "WHERE file_ref = '/delivery_photos/proof.jpg'", Integer.class);

        cleanup.retryPending();

        assertThat(jdbc.queryForObject(
            "SELECT attempts FROM sensitive_file_cleanup "
                + "WHERE file_ref = '/delivery_photos/proof.jpg'", Integer.class)).isEqualTo(attempts);
    }
}
