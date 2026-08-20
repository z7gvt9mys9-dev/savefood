package ru.savefood.kyc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

class NeedyKycDocumentCleanupTest {

    @TempDir
    Path tempDir;

    @Test
    void deletesOnlyQueuedRecipientFileAndMarksTombstoneComplete() throws Exception {
        Path needyDir = Files.createDirectory(tempDir.resolve("needy"));
        Path volunteerDir = Files.createDirectory(tempDir.resolve("volunteer"));
        Path needyFile = Files.write(needyDir.resolve("recipient.enc"), new byte[] {1});
        Path volunteerFile = Files.write(volunteerDir.resolve("volunteer.enc"), new byte[] {2});
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("FROM needy_kyc_document_cleanup")))
            .thenReturn(List.of(Map.of("id", 1L, "document_ref", "/needy_uploads/recipient.enc")));

        new NeedyKycDocumentCleanup(jdbc, needyDir.toString()).cleanupPending();

        assertThat(needyFile).doesNotExist();
        assertThat(volunteerFile).exists();
        verify(jdbc).update(contains("INSERT INTO needy_kyc_document_cleanup"),
            eq("/needy_uploads/recipient.enc"));
        verify(jdbc).update(contains("completed_at = CURRENT_TIMESTAMP"), eq(1L));
    }

    @Test
    void failedDeletionRemainsTrackedForRetry() throws Exception {
        Path needyDir = Files.createDirectory(tempDir.resolve("needy-failure"));
        Path nonEmpty = Files.createDirectory(needyDir.resolve("recipient.enc"));
        Files.write(nonEmpty.resolve("child"), new byte[] {1});
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("FROM needy_kyc_document_cleanup")))
            .thenReturn(List.of(Map.of("id", 2L, "document_ref", "/needy_uploads/recipient.enc")));

        new NeedyKycDocumentCleanup(jdbc, needyDir.toString()).cleanupPending();

        assertThat(nonEmpty).exists();
        verify(jdbc).update(contains("next_attempt_at"), contains("recipient.enc"), eq(2L));
    }
}
