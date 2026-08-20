package ru.savefood.kyc;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.savefood.audit.AuditService;
import ru.savefood.telegram.TelegramService;
import ru.savefood.volunteer.VolunteerRepository;

class KycServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void volunteerKycStillRecordsUncheckedWhenAiIsUnavailable() throws Exception {
        Path document = tempDir.resolve("volunteer.jpg");
        java.nio.file.Files.write(document, new byte[] {1, 2, 3});
        VolunteerRepository volunteers = mock(VolunteerRepository.class);
        KycCrypto crypto = mock(KycCrypto.class);
        when(crypto.readDecrypted(document.toString())).thenReturn(new byte[] {1, 2, 3});
        when(volunteers.saveVolunteerKyc(eq(17), eq("generation-a"), isNull(),
            eq("unchecked"), contains("повторена"), eq("pending"))).thenReturn(true);
        KycService service = new KycService(mock(JdbcTemplate.class), volunteers,
            mock(AuditService.class), crypto, mock(TelegramService.class), "", "model", 0.7, 0.3);

        service.runVolunteerKycCheck(17, document.toString(), "Volunteer", "generation-a");

        verify(volunteers).saveVolunteerKyc(
            eq(17), eq("generation-a"), isNull(), eq("unchecked"), contains("повторена"), eq("pending"));
    }

    @Test
    void recheckKeepsGenerationAndDoesNotRequirePendingStatus() throws Exception {
        Path document = tempDir.resolve("recheck.jpg");
        java.nio.file.Files.write(document, new byte[] {1, 2, 3});
        VolunteerRepository volunteers = mock(VolunteerRepository.class);
        KycCrypto crypto = mock(KycCrypto.class);
        when(crypto.readDecrypted(document.toString())).thenReturn(new byte[] {1, 2, 3});
        when(volunteers.saveVolunteerKyc(eq(17), eq("generation-a"), isNull(),
            eq("unchecked"), contains("повторена"), isNull())).thenReturn(true);
        KycService service = new KycService(mock(JdbcTemplate.class), volunteers,
            mock(AuditService.class), crypto, mock(TelegramService.class), "", "model", 0.7, 0.3);

        service.recheckVolunteer(17, document.toString(), "Volunteer", "generation-a");

        verify(volunteers).saveVolunteerKyc(eq(17), eq("generation-a"), isNull(),
            eq("unchecked"), contains("повторена"), isNull());
    }

    @Test
    void staleAutomaticApprovalCannotApproveOrNotifyANewerGeneration() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        VolunteerRepository volunteers = mock(VolunteerRepository.class);
        AuditService audit = mock(AuditService.class);
        TelegramService telegram = mock(TelegramService.class);
        when(volunteers.saveVolunteerKyc(
            17, "generation-a", 0.85, "likely_ok", "ok", "pending"))
            .thenReturn(false);
        KycService service = service(jdbc, volunteers, audit, telegram);

        boolean applied = service.applyVolunteerKycResult(
            17, "generation-a", 0.85, "likely_ok", "ok");

        org.assertj.core.api.Assertions.assertThat(applied).isFalse();
        verify(volunteers, never()).autoApproveVolunteerKyc(17, "generation-a");
        verifyNoInteractions(jdbc, audit, telegram);
    }

    @Test
    void staleFraudResultCannotRejectOrMutateANewerGeneration() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        VolunteerRepository volunteers = mock(VolunteerRepository.class);
        AuditService audit = mock(AuditService.class);
        TelegramService telegram = mock(TelegramService.class);
        when(volunteers.saveVolunteerKyc(
            17, "generation-a", 0.1, "likely_fraud", "fraud", "pending"))
            .thenReturn(false);
        KycService service = service(jdbc, volunteers, audit, telegram);

        boolean applied = service.applyVolunteerKycResult(
            17, "generation-a", 0.1, "likely_fraud", "fraud");

        org.assertj.core.api.Assertions.assertThat(applied).isFalse();
        verify(volunteers, never()).autoApproveVolunteerKyc(17, "generation-a");
        verifyNoInteractions(jdbc, audit, telegram);
    }

    @Test
    void currentGenerationCanCompleteNormalAutomaticApprovalOnce() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        VolunteerRepository volunteers = mock(VolunteerRepository.class);
        AuditService audit = mock(AuditService.class);
        TelegramService telegram = mock(TelegramService.class);
        when(volunteers.saveVolunteerKyc(
            17, "generation-a", 0.85, "likely_ok", "ok", "pending"))
            .thenReturn(true);
        when(volunteers.autoApproveVolunteerKyc(17, "generation-a")).thenReturn(true);
        KycService service = service(jdbc, volunteers, audit, telegram);

        boolean applied = service.applyVolunteerKycResult(
            17, "generation-a", 0.85, "likely_ok", "ok");

        org.assertj.core.api.Assertions.assertThat(applied).isTrue();
        verify(jdbc).update(contains("INSERT INTO notifications"),
            eq(17), eq("moderation_approved"), contains("автоматической"),
            org.mockito.ArgumentMatchers.any(java.time.OffsetDateTime.class));
        verify(telegram).notifyVolunteer(eq(17), contains("подтверждён"));
    }

    private KycService service(JdbcTemplate jdbc, VolunteerRepository volunteers,
                               AuditService audit, TelegramService telegram) {
        return new KycService(jdbc, volunteers, audit, mock(KycCrypto.class), telegram,
            "", "model", 0.7, 0.3);
    }
}
