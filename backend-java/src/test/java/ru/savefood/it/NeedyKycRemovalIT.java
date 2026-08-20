package ru.savefood.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import ru.savefood.needy.NeedyRepository;
import ru.savefood.needy.NeedyService;
import ru.savefood.security.PasswordService;

class NeedyKycRemovalIT extends PostgresIT {

    @Test
    void newlyRegisteredRecipientIsActiveAndCanCreateARequestWithoutDocuments() {
        NeedyService service = new NeedyService(jdbc, new NeedyRepository(jdbc), new PasswordService());
        int needy = service.registerNeedy("Recipient", "+70000000000", "recipient-new", "password123");
        int shop = insertShop("Shop", 43.2, 76.9);
        int lot = insertLot(shop, 2, "Bakery");

        int ticket = service.createTicket(needy, null, null, null, null, null, lot,
            null, null, null, true);

        assertThat(status("needy", needy)).isEqualTo("active");
        assertThat(status("tickets", ticket)).isEqualTo("open");
    }

    @Test
    void migrationActivatesLegacyRecipientsQueuesFilesAndLeavesVolunteerKycUntouched() {
        jdbc.execute("DROP SCHEMA public CASCADE");
        jdbc.execute("CREATE SCHEMA public");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
            .target("4").load().migrate();

        int pending = insertLegacyNeedy("pending", "/needy_uploads/pending.enc", null);
        int approved = insertLegacyNeedy("approved", null, "/needy_uploads/approved.enc");
        int rejected = insertLegacyNeedy("rejected", null, null);
        int volunteer = jdbc.queryForObject(
            "INSERT INTO volunteers (name, status, document, kyc_verdict, created_at) "
            + "VALUES ('Volunteer', 'pending', '/volunteer_kyc/vol.enc', 'review', NOW()) RETURNING id",
            Integer.class);

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        assertThat(List.of(status("needy", pending), status("needy", approved), status("needy", rejected)))
            .containsOnly("active");
        assertThat(jdbc.queryForList(
            "SELECT document_ref FROM needy_kyc_document_cleanup ORDER BY document_ref", String.class))
            .containsExactly("/needy_uploads/approved.enc", "/needy_uploads/pending.enc");
        assertThat(columnExists("needy", "kyc_verdict")).isFalse();
        assertThat(columnExists("needy_profile", "document")).isFalse();
        assertThat(status("volunteers", volunteer)).isEqualTo("pending");
        assertThat(jdbc.queryForObject("SELECT document FROM volunteers WHERE id = ?", String.class, volunteer))
            .isEqualTo("/volunteer_kyc/vol.enc");
        assertThat(jdbc.queryForObject("SELECT kyc_verdict FROM volunteers WHERE id = ?", String.class, volunteer))
            .isEqualTo("review");
    }

    private int insertLegacyNeedy(String status, String accountDocument, String profileDocument) {
        int id = jdbc.queryForObject(
            "INSERT INTO needy (name, status, document, kyc_score, kyc_verdict, kyc_notes, created_at) "
            + "VALUES ('Recipient', ?, ?, 0.4, 'review', 'legacy', NOW()) RETURNING id",
            Integer.class, status, accountDocument);
        jdbc.update("INSERT INTO needy_profile (needy_id, document) VALUES (?, ?)", id, profileDocument);
        return id;
    }

    private boolean columnExists(String table, String column) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns "
            + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
            Integer.class, table, column);
        return count != null && count > 0;
    }
}
