package ru.savefood.background;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.savefood.it.PostgresIT;
import ru.savefood.telegram.TelegramService;
import ru.savefood.volunteer.RouteRevertService;
import ru.savefood.volunteer.VolunteerRepository;

/** Focused retention races for volunteer KYC document generations. */
class VolunteerKycRetentionIT extends PostgresIT {

    @TempDir
    Path uploadDir;

    private VolunteerRepository volunteers;
    private TelegramService telegram;
    private MaintenanceTasks maintenance;

    @BeforeEach
    void wire() {
        volunteers = new VolunteerRepository(jdbc);
        telegram = mock(TelegramService.class);
        maintenance = new MaintenanceTasks(jdbc, txManager, new RouteRevertService(jdbc), null,
            telegram, "embedded", "", uploadDir.toString(), 1, 1);
    }

    @Test
    void retentionThatSelectedAHasNoEffectAfterBReplacesIt() throws Exception {
        Files.write(uploadDir.resolve("a.enc"), new byte[] {1});
        Files.write(uploadDir.resolve("b.enc"), new byte[] {2});
        int volunteer = pendingOldDocument("/volunteer_kyc/a.enc", "generation-a");
        Map<String, Object> selectedA = jdbc.queryForMap(
            "SELECT id, document, kyc_generation FROM volunteers WHERE id = ?", volunteer);

        volunteers.replaceVolunteerKycDocument(
            volunteer, "/volunteer_kyc/b.enc", "generation-b");
        boolean purged = maintenance.purgeVolunteerKycDocument(
            volunteer, (String) selectedA.get("document"), (String) selectedA.get("kyc_generation"));

        assertThat(purged).isFalse();
        assertThat(volunteers.getVolunteerById(volunteer))
            .containsEntry("document", "/volunteer_kyc/b.enc")
            .containsEntry("kyc_generation", "generation-b")
            .containsEntry("status", "pending");
        assertThat(volunteers.getVolunteerById(volunteer).get("kyc_checked_at")).isNull();
        assertThat(Files.exists(uploadDir.resolve("b.enc"))).isTrue();
        assertThat(notificationCount(volunteer)).isZero();
        verifyNoInteractions(telegram);
    }

    @Test
    void retentionWinnerClearsAndDeletesOnlyItsExactDocument() throws Exception {
        Files.write(uploadDir.resolve("a.enc"), new byte[] {1});
        Files.write(uploadDir.resolve("unrelated.enc"), new byte[] {2});
        int volunteer = pendingOldDocument("/volunteer_kyc/a.enc", "generation-a");

        boolean purged = maintenance.purgeVolunteerKycDocument(
            volunteer, "/volunteer_kyc/a.enc", "generation-a");

        assertThat(purged).isTrue();
        Map<String, Object> row = volunteers.getVolunteerById(volunteer);
        assertThat(row.get("document")).isNull();
        assertThat(row.get("kyc_generation")).isNull();
        assertThat(Files.exists(uploadDir.resolve("a.enc"))).isFalse();
        assertThat(Files.exists(uploadDir.resolve("unrelated.enc"))).isTrue();
        assertThat(notificationCount(volunteer)).isEqualTo(1);
    }

    private int pendingOldDocument(String document, String generation) {
        return jdbc.queryForObject(
            "INSERT INTO volunteers (name, status, document, kyc_generation, "
            + "kyc_verdict, kyc_checked_at, created_at) "
            + "VALUES ('Volunteer', 'pending', ?, ?, 'review', NOW() - INTERVAL '2 hours', NOW()) "
            + "RETURNING id",
            Integer.class, document, generation);
    }

    private int notificationCount(int volunteer) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM notifications WHERE volunteer_id = ? AND type = 'kyc_doc_purged'",
            Integer.class, volunteer);
    }
}
