package ru.savefood.storage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.savefood.photo.DeliveryPhotoStorage;
import ru.savefood.storage.SensitiveFileCleanup.Storage;
class SensitiveFileCleanupTest {
    @TempDir
    Path tempDir;
    @Test
    void immediateDeletionSucceedsAndCompletesDurableWork() throws Exception {
        Fixture fixture = fixture(Files::deleteIfExists);
        Path file = Files.write(fixture.volunteerKyc.resolve("a.enc"), new byte[] {1});
        stubQueuedId(fixture.jdbc, Storage.VOLUNTEER_KYC, "/volunteer_kyc/a.enc", 11L);
        fixture.cleanup.trackAndDeleteAfterCommit(Storage.VOLUNTEER_KYC, "/volunteer_kyc/a.enc");
        assertThat(file).doesNotExist();
        verify(fixture.jdbc).update(contains("completed_at = CURRENT_TIMESTAMP"), eq(11L));
    }
    @Test
    void failedKycDeletionStaysDurableAndRetryDeletesOnlyOldExactPath() throws Exception {
        AtomicBoolean fail = new AtomicBoolean(true);
        Fixture fixture = fixture(path -> {
            if (fail.get()) {
                throw new java.io.IOException("injected delete failure");
            }
            Files.deleteIfExists(path);
        });
        Path oldA = Files.write(fixture.volunteerKyc.resolve("a.enc"), new byte[] {1});
        Path replacementB = Files.write(fixture.volunteerKyc.resolve("b.enc"), new byte[] {2});
        String ref = "/volunteer_kyc/a.enc";
        stubQueuedId(fixture.jdbc, Storage.VOLUNTEER_KYC, ref, 12L);
        fixture.cleanup.trackAndDeleteAfterCommit(Storage.VOLUNTEER_KYC, ref);
        assertThat(oldA).exists();
        verify(fixture.jdbc).update(contains("next_attempt_at"),
            eq("injected delete failure"), eq(12L));
        fail.set(false);
        when(fixture.jdbc.queryForList(contains("FROM sensitive_file_cleanup")))
            .thenReturn(List.of(row(12L, "volunteer_kyc", ref)));
        fixture.cleanup.retryPending();
        assertThat(oldA).doesNotExist();
        assertThat(replacementB).exists();
        verify(fixture.jdbc).update(contains("completed_at = CURRENT_TIMESTAMP"), eq(12L));
    }
    @Test
    void missingFileAndRepeatedExecutionResolveIdempotently() throws Exception {
        Fixture fixture = fixture(Files::deleteIfExists);
        String ref = "/volunteer_kyc/missing.enc";
        when(fixture.jdbc.queryForList(contains("FROM sensitive_file_cleanup")))
            .thenReturn(List.of(row(13L, "volunteer_kyc", ref)));
        fixture.cleanup.retryPending();
        fixture.cleanup.retryPending();
        verify(fixture.jdbc, times(2)).update(
            contains("WHERE id = ? AND completed_at IS NULL"), eq(13L));
    }
    @Test
    void deliveryPhotoFailureIsQueuedAndLaterRetried() throws Exception {
        AtomicBoolean fail = new AtomicBoolean(true);
        Fixture fixture = fixture(path -> {
            if (fail.get()) {
                throw new java.io.IOException("delivery delete failure");
            }
            Files.deleteIfExists(path);
        });
        Path proof = Files.write(fixture.delivery.resolve("proof.jpg"), new byte[] {1});
        String ref = "/delivery_photos/proof.jpg";
        DeliveryPhotoStorage storage = new DeliveryPhotoStorage(fixture.cleanup);
        storage.deleteOrQueue(ref);
        assertThat(proof).exists();
        verify(fixture.jdbc, atLeastOnce()).update(contains("INSERT INTO sensitive_file_cleanup"),
            eq("delivery_photo"), eq(ref), eq("delivery delete failure"));
        fail.set(false);
        when(fixture.jdbc.queryForList(contains("FROM sensitive_file_cleanup")))
            .thenReturn(List.of(row(14L, "delivery_photo", ref)));
        fixture.cleanup.retryPending();
        assertThat(proof).doesNotExist();
    }
    @Test
    void failedReceiptDeletionQueuesItsExactPathAndRetryDoesNotTouchReplacement() throws Exception {
        AtomicBoolean fail = new AtomicBoolean(true);
        Fixture fixture = fixture(path -> {
            if (fail.get()) {
                throw new java.io.IOException("receipt delete failure");
            }
            Files.deleteIfExists(path);
        });
        Path oldReceipt = Files.write(fixture.receipts.resolve("old.png"), new byte[] {1});
        Path replacement = Files.write(fixture.receipts.resolve("replacement.png"), new byte[] {2});
        String ref = "/receipts/old.png";
        fixture.cleanup.deleteUnlessPersisted(Storage.RECEIPT, ref).close();
        assertThat(oldReceipt).exists();
        verify(fixture.jdbc, atLeastOnce()).update(contains("INSERT INTO sensitive_file_cleanup"),
            eq("receipt"), eq(ref), eq("receipt delete failure"));
        fail.set(false);
        when(fixture.jdbc.queryForList(contains("FROM sensitive_file_cleanup")))
            .thenReturn(List.of(row(15L, "receipt", ref)));
        fixture.cleanup.retryPending();
        assertThat(oldReceipt).doesNotExist();
        assertThat(replacement).exists();
    }
    @Test
    void missingReceiptCleanupTargetIsSuccess() throws Exception {
        Fixture fixture = fixture(Files::deleteIfExists);
        String ref = "/receipts/missing.png";
        when(fixture.jdbc.queryForList(contains("FROM sensitive_file_cleanup")))
            .thenReturn(List.of(row(16L, "receipt", ref)));
        fixture.cleanup.retryPending();
        verify(fixture.jdbc).update(contains("completed_at = CURRENT_TIMESTAMP"), eq(16L));
    }
    private Fixture fixture(SensitiveFileCleanup.FileDeleter deleter) throws Exception {
        Path needy = Files.createDirectory(tempDir.resolve("needy"));
        Path volunteer = Files.createDirectory(tempDir.resolve("volunteer"));
        Path delivery = Files.createDirectory(tempDir.resolve("delivery"));
        Path legacy = Files.createDirectory(tempDir.resolve("legacy"));
        Path receipts = Files.createDirectory(tempDir.resolve("receipts"));
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        return new Fixture(jdbc, volunteer, delivery, receipts,
            new SensitiveFileCleanup(jdbc, needy.toString(), volunteer.toString(),
                delivery.toString(), legacy.toString(), receipts.toString(), deleter, null));
    }
    private static void stubQueuedId(JdbcTemplate jdbc, Storage storage, String ref, long id) {
        String storageValue = storage == Storage.VOLUNTEER_KYC ? "volunteer_kyc" : "delivery_photo";
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(storageValue), eq(ref))).thenReturn(id);
    }
    private static Map<String, Object> row(long id, String storage, String ref) {
        return Map.of("id", id, "storage_type", storage, "file_ref", ref);
    }
    private record Fixture(JdbcTemplate jdbc, Path volunteerKyc, Path delivery, Path receipts,
                           SensitiveFileCleanup cleanup) {
    }
}
