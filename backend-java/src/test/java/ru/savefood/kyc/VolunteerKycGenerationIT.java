package ru.savefood.kyc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.savefood.audit.AuditService;
import ru.savefood.it.PostgresIT;
import ru.savefood.telegram.TelegramService;
import ru.savefood.volunteer.VolunteerRepository;

/** Database-level regression coverage for volunteer KYC document generations. */
class VolunteerKycGenerationIT extends PostgresIT {

    private VolunteerRepository volunteers;
    private KycService kyc;
    private ExecutorService executor;

    @BeforeEach
    void wire() {
        volunteers = new VolunteerRepository(jdbc);
        kyc = new KycService(jdbc, volunteers, mock(AuditService.class), mock(KycCrypto.class),
            mock(TelegramService.class), "", "model", 0.7, 0.3);
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @Test
    void uploadBResetsAAndDiscardsItsDelayedAutomaticApprovalWithoutNotification() {
        int volunteer = pendingVolunteer("/volunteer_kyc/a.enc", "generation-a");
        jdbc.update("UPDATE volunteers SET kyc_score = 0.4, kyc_verdict = 'review', "
            + "kyc_notes = 'A', kyc_checked_at = NOW() - INTERVAL '7 days' WHERE id = ?", volunteer);

        VolunteerRepository.KycDocumentReplacement replaced = volunteers.replaceVolunteerKycDocument(
            volunteer, "/volunteer_kyc/b.enc", "generation-b");
        boolean staleApplied = kyc.applyVolunteerKycResult(
            volunteer, "generation-a", 0.85, "likely_ok", "A approved");

        assertThat(replaced.previousDocument()).isEqualTo("/volunteer_kyc/a.enc");
        assertThat(staleApplied).isFalse();
        assertCurrentDocumentIsReset(volunteer, "/volunteer_kyc/b.enc", "generation-b");
        assertThat(notificationCount(volunteer, "moderation_approved")).isZero();
    }

    @Test
    void delayedFraudResultCannotRejectOrAnnotateDocumentB() {
        int volunteer = pendingVolunteer("/volunteer_kyc/a.enc", "generation-a");
        volunteers.replaceVolunteerKycDocument(volunteer, "/volunteer_kyc/b.enc", "generation-b");

        boolean staleApplied = kyc.applyVolunteerKycResult(
            volunteer, "generation-a", 0.1, "likely_fraud", "A rejected");

        assertThat(staleApplied).isFalse();
        assertCurrentDocumentIsReset(volunteer, "/volunteer_kyc/b.enc", "generation-b");
    }

    @Test
    void replacementOfAnApprovedDocumentReturnsVolunteerToPendingAndClearsOldResult() {
        int volunteer = pendingVolunteer("/volunteer_kyc/a.enc", "generation-a");
        jdbc.update("UPDATE volunteers SET status = 'approved', kyc_score = 0.9, "
            + "kyc_verdict = 'likely_ok', kyc_notes = 'A', kyc_checked_at = NOW() WHERE id = ?",
            volunteer);

        volunteers.replaceVolunteerKycDocument(
            volunteer, "/volunteer_kyc/b.enc", "generation-b");

        assertCurrentDocumentIsReset(volunteer, "/volunteer_kyc/b.enc", "generation-b");
    }

    @Test
    void concurrentResultsForOneGenerationEndInOneCoherentState() throws Exception {
        int volunteer = pendingVolunteer("/volunteer_kyc/a.enc", "generation-a");

        Future<Boolean> approval = executor.submit(() -> kyc.applyVolunteerKycResult(
            volunteer, "generation-a", 0.85, "likely_ok", "ok"));
        Future<Boolean> fraud = executor.submit(() -> kyc.applyVolunteerKycResult(
            volunteer, "generation-a", 0.1, "likely_fraud", "fraud"));
        approval.get();
        fraud.get();

        Map<String, Object> row = volunteers.getVolunteerById(volunteer);
        if ("approved".equals(row.get("status"))) {
            assertThat(row.get("kyc_verdict")).isEqualTo("likely_ok");
            assertThat(notificationCount(volunteer, "moderation_approved")).isEqualTo(1);
        } else {
            assertThat(row).containsEntry("status", "pending").containsEntry("kyc_verdict", "likely_fraud");
            assertThat(notificationCount(volunteer, "moderation_approved")).isZero();
        }
        assertThat(row.get("kyc_generation")).isEqualTo("generation-a");
    }

    @Test
    void currentGenerationStillCompletesNormalAutomaticApproval() {
        int volunteer = pendingVolunteer("/volunteer_kyc/a.enc", "generation-a");

        boolean applied = kyc.applyVolunteerKycResult(
            volunteer, "generation-a", 0.85, "likely_ok", "ok");

        assertThat(applied).isTrue();
        assertThat(volunteers.getVolunteerById(volunteer))
            .containsEntry("status", "approved")
            .containsEntry("kyc_verdict", "likely_ok")
            .containsEntry("kyc_generation", "generation-a");
        assertThat(notificationCount(volunteer, "moderation_approved")).isEqualTo(1);
    }

    @Test
    void deletingVolunteerDuringAnalysisMakesItsResultANoOp() {
        int volunteer = pendingVolunteer("/volunteer_kyc/a.enc", "generation-a");
        jdbc.update("DELETE FROM volunteers WHERE id = ?", volunteer);

        boolean applied = kyc.applyVolunteerKycResult(
            volunteer, "generation-a", 0.85, "likely_ok", "ok");

        assertThat(applied).isFalse();
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM volunteers WHERE id = ?", Integer.class, volunteer)).isZero();
        assertThat(notificationCount(volunteer, "moderation_approved")).isZero();
    }

    @Test
    void migrationAssignsGenerationToExistingVolunteerDocuments() {
        jdbc.execute("DROP SCHEMA public CASCADE");
        jdbc.execute("CREATE SCHEMA public");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
            .target("6").load().migrate();
        int volunteer = jdbc.queryForObject(
            "INSERT INTO volunteers (name, status, document, created_at) "
            + "VALUES ('Legacy', 'pending', '/volunteer_kyc/legacy.enc', NOW()) RETURNING id",
            Integer.class);

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        assertThat(jdbc.queryForObject(
            "SELECT kyc_generation FROM volunteers WHERE id = ?", String.class, volunteer))
            .startsWith("legacy-" + volunteer + "-");
    }

    private int pendingVolunteer(String document, String generation) {
        return jdbc.queryForObject(
            "INSERT INTO volunteers (name, status, document, kyc_generation, created_at) "
            + "VALUES ('Volunteer', 'pending', ?, ?, NOW()) RETURNING id",
            Integer.class, document, generation);
    }

    private void assertCurrentDocumentIsReset(int volunteer, String document, String generation) {
        Map<String, Object> row = volunteers.getVolunteerById(volunteer);
        assertThat(row).containsEntry("status", "pending")
            .containsEntry("document", document)
            .containsEntry("kyc_generation", generation);
        assertThat(row.get("kyc_score")).isNull();
        assertThat(row.get("kyc_verdict")).isNull();
        assertThat(row.get("kyc_notes")).isNull();
        assertThat(row.get("kyc_checked_at")).isNull();
    }

    private int notificationCount(int volunteer, String type) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM notifications WHERE volunteer_id = ? AND type = ?",
            Integer.class, volunteer, type);
    }
}
