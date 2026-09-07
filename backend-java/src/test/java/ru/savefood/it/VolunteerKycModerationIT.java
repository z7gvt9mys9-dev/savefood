package ru.savefood.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.savefood.admin.AdminController;
import ru.savefood.admin.ModerationDecision;
import ru.savefood.audit.AuditService;
import ru.savefood.esg.EsgService;
import ru.savefood.security.CurrentUser;
import ru.savefood.telegram.TelegramService;
import ru.savefood.volunteer.AvailabilityService;
import ru.savefood.volunteer.RouteRevertService;
import ru.savefood.volunteer.VolunteerRepository;
import ru.savefood.web.ApiException;

class VolunteerKycModerationIT extends PostgresIT {
    private VolunteerRepository volunteers;
    private TelegramService telegram;
    private AdminController admin;
    private ExecutorService executor;
    private CurrentUser adminUser;

    @BeforeEach
    void wire() {
        volunteers = new VolunteerRepository(jdbc);
        telegram = mock(TelegramService.class);
        admin = new AdminController(jdbc, volunteers, mock(EsgService.class), new AuditService(jdbc),
            mock(RouteRevertService.class), mock(AvailabilityService.class), telegram, "/tmp", "/tmp");
        int userId = jdbc.queryForObject(
            "INSERT INTO users (username, hashed_password, role) VALUES ('moderator', 'hash', 'admin') RETURNING id",
            Integer.class);
        adminUser = new CurrentUser(userId, "moderator", "admin", null);
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @Test
    void replacementMakesAnOpenModerationDecisionStaleWithoutSideEffects() {
        int volunteer = pendingVolunteer("generation-a", "/volunteer_kyc/a.jpg");
        String reviewedGeneration = (String) admin.listVolunteers("pending", adminUser).get(0)
            .get("kyc_generation");
        volunteers.replaceVolunteerKycDocument(volunteer, "/volunteer_kyc/b.jpg", "generation-b");

        assertThatThrownBy(() -> admin.moderateVolunteer(volunteer,
            new ModerationDecision("approved", null, reviewedGeneration), adminUser))
            .isInstanceOf(ApiException.class)
            .extracting(e -> ((ApiException) e).getStatus())
            .isEqualTo(409);

        assertThat(volunteers.getVolunteerById(volunteer))
            .containsEntry("status", "pending")
            .containsEntry("kyc_generation", "generation-b");
        assertThat(sideEffectCount(volunteer)).isZero();
        verifyNoInteractions(telegram);
    }

    @Test
    void currentGenerationCanBeApproved() {
        int volunteer = pendingVolunteer("generation-a", "/volunteer_kyc/a.jpg");
        admin.moderateVolunteer(volunteer,
            new ModerationDecision("approved", "looks valid", "generation-a"), adminUser);
        assertThat(status("volunteers", volunteer)).isEqualTo("approved");
        assertThat(sideEffectCount(volunteer)).isEqualTo(2);
        verify(telegram).notifyVolunteer(org.mockito.ArgumentMatchers.eq(volunteer),
            org.mockito.ArgumentMatchers.contains("подтверждён"));
    }

    @Test
    void currentGenerationCanBeRejected() {
        int volunteer = pendingVolunteer("generation-a", "/volunteer_kyc/a.jpg");
        admin.moderateVolunteer(volunteer,
            new ModerationDecision("rejected", null, "generation-a"), adminUser);
        assertThat(status("volunteers", volunteer)).isEqualTo("rejected");
        assertThat(sideEffectCount(volunteer)).isEqualTo(2);
    }

    @Test
    void committedReplacementWinsAgainstConcurrentStaleModeration() throws Exception {
        int volunteer = pendingVolunteer("generation-a", "/volunteer_kyc/a.jpg");
        CountDownLatch replacementWritten = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        Future<?> replacement = executor.submit(() -> tx.executeWithoutResult(ignored -> {
            volunteers.replaceVolunteerKycDocument(volunteer, "/volunteer_kyc/b.jpg", "generation-b");
            replacementWritten.countDown();
            await(allowCommit);
        }));
        assertThat(replacementWritten.await(5, TimeUnit.SECONDS)).isTrue();
        Future<VolunteerRepository.KycModerationTransition> moderation = executor.submit(
            () -> volunteers.moderateVolunteerKyc(volunteer, "approved", "generation-a"));
        assertThatThrownBy(() -> moderation.get(500, TimeUnit.MILLISECONDS))
            .isInstanceOf(TimeoutException.class);
        allowCommit.countDown();
        replacement.get(5, TimeUnit.SECONDS);
        assertThat(moderation.get(5, TimeUnit.SECONDS)).isNull();
        assertThat(volunteers.getVolunteerById(volunteer))
            .containsEntry("status", "pending")
            .containsEntry("kyc_generation", "generation-b");
    }

    private int pendingVolunteer(String generation, String document) {
        int volunteer = insertVolunteer("Applicant");
        jdbc.update("UPDATE volunteers SET status = 'pending', document = ?, kyc_generation = ? WHERE id = ?",
            document, generation, volunteer);
        return volunteer;
    }

    private int sideEffectCount(int volunteer) {
        int notifications = jdbc.queryForObject(
            "SELECT COUNT(*) FROM notifications WHERE volunteer_id = ? AND type LIKE 'moderation_%'",
            Integer.class, volunteer);
        int audits = jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE target_type = 'volunteer' AND target_id = ? "
                + "AND action LIKE 'kyc_manual_%'",
            Integer.class, volunteer);
        return notifications + audits;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out coordinating KYC race");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted coordinating KYC race", e);
        }
    }
}
