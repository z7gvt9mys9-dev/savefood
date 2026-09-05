package ru.savefood.it;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import ru.savefood.shop.ShopRepository;
import ru.savefood.volunteer.RouteRevertService;
/** Regression coverage for the discrete lot-unit migration and discovery guard. */
class LotQuantityReconciliationIT extends PostgresIT {
    @Test
    void migrationRoundsLegacyActiveLotsDownAndKeepsOpenReservedUnitsServiceable() {
        migrateOnlyThroughV13();
        int shop = insertShop("Shop", 43.238, 76.889);
        int stillAvailable = insertLot(shop, 2.5, "Bakery");
        int remainderOnly = insertLot(shop, 0.5, "Bakery");
        int reservedRemainder = insertLot(shop, 0.5, "Bakery");
        jdbc.update("UPDATE lots SET initial_quantity = 1.5 WHERE id = ?", reservedRemainder);
        int needy = insertNeedy("Recipient");
        jdbc.update("INSERT INTO tickets (needy_id, lot_id, quantity, status, created_at) "
            + "VALUES (?, ?, 1, 'open', NOW())", needy, reservedRemainder);
        migrateThroughV14();
        assertThat(lotQuantity(stillAvailable)).isEqualTo(2.0);
        assertThat(jdbc.queryForObject("SELECT initial_quantity FROM lots WHERE id = ?", Double.class,
            stillAvailable)).isEqualTo(2.0);
        assertThat(status("lots", stillAvailable)).isEqualTo("active");
        assertThat(lotQuantity(remainderOnly)).isZero();
        assertThat(status("lots", remainderOnly)).isEqualTo("removed");
        assertThat(lotQuantity(reservedRemainder)).isZero();
        assertThat(jdbc.queryForObject("SELECT initial_quantity FROM lots WHERE id = ?", Double.class,
            reservedRemainder)).isEqualTo(1.0);
        assertThat(status("lots", reservedRemainder)).isEqualTo("active");
    }
    @Test
    void discoveryNeverReturnsAnActiveFractionalRemainder() {
        migrateOnlyThroughV13();
        int shop = insertShop("Shop", 43.238, 76.889);
        int fractional = insertLot(shop, 0.5, "Bakery");
        int whole = insertLot(shop, 1.0, "Bakery");
        ShopRepository repo = new ShopRepository(jdbc);
        List<Map<String, Object>> publicLots = repo.getAllActiveLots(20, 0, null, null);
        List<Map<String, Object>> shopLots = repo.getActiveLots(shop);
        assertThat(publicLots).extracting(row -> row.get("id"))
            .contains(whole).doesNotContain(fractional);
        assertThat(shopLots).extracting(row -> row.get("id"))
            .contains(whole).doesNotContain(fractional);
    }
    @Test
    void v21ReconcilesEveryRecoverableFractionWithoutChangingTerminalHistory() {
        migrateOnlyThroughV20();
        int shop = insertShop("Shop", 43.238, 76.889);
        int active = insertLot(shop, 2.5, "Bakery");
        int taken = insertLot(shop, 1.5, "Bakery");
        int routeRecoverable = insertLot(shop, 5.75, "Bakery");
        int inconsistent = insertLot(shop, 5.75, "Bakery");
        int empty = insertLot(shop, 0.75, "Bakery");
        int reserved = insertLot(shop, 0.5, "Bakery");
        int terminal = insertLot(shop, 2.5, "Bakery");
        jdbc.update("UPDATE lots SET status = 'taken' WHERE id IN (?, ?, ?, ?)",
            taken, routeRecoverable, inconsistent, empty);
        jdbc.update("UPDATE lots SET initial_quantity = 3.75 WHERE id = ?", inconsistent);
        jdbc.update("UPDATE lots SET initial_quantity = 1.5 WHERE id = ?", reserved);
        jdbc.update("UPDATE lots SET status = 'confirmed' WHERE id = ?", terminal);
        int needy = insertNeedy("Recipient");
        int openTicket = jdbc.queryForObject(
            "INSERT INTO tickets (needy_id, lot_id, quantity, status, created_at) "
            + "VALUES (?, ?, 1, 'open', NOW()) RETURNING id",
            Integer.class, needy, reserved);
        int secondNeedy = insertNeedy("Second recipient");
        jdbc.update("INSERT INTO tickets (needy_id, lot_id, quantity, status, created_at) "
            + "VALUES (?, ?, 1, 'open', NOW())", secondNeedy, inconsistent);

        migrateLatest();

        assertLot(active, 2.0, 2.0, "active");
        assertLot(taken, 1.0, 1.0, "taken");
        assertLot(routeRecoverable, 5.0, 5.0, "taken");
        assertLot(inconsistent, 2.0, 3.0, "taken");
        assertLot(empty, 0.0, 0.0, "removed");
        assertLot(reserved, 0.0, 1.0, "active");
        assertThat(status("tickets", openTicket)).isEqualTo("open");
        assertThat(lotQuantity(terminal)).isEqualTo(2.5);
        assertThat(status("lots", terminal)).isEqualTo("confirmed");
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM lots WHERE status IN ('active', 'taken') "
            + "AND (quantity < 0 OR initial_quantity < 0 OR quantity > initial_quantity "
            + "OR quantity <> FLOOR(quantity) OR initial_quantity <> FLOOR(initial_quantity))",
            Integer.class)).isZero();

        RouteRevertService revert = new RouteRevertService(jdbc);
        revert.revertRouteLot(taken, "[]");
        revert.revertRouteLot(routeRecoverable, "[]");
        assertLot(taken, 1.0, 1.0, "active");
        assertLot(routeRecoverable, 5.0, 5.0, "active");
    }
    @Test
    void v21AppliesAfterV20AndRejectsFutureFractionalSqlWrites() {
        migrateOnlyThroughV20();
        migrateLatest();
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '21' AND success",
            Integer.class)).isEqualTo(1);
        int lot = insertLot(insertShop("Shop", 43.238, 76.889), 3.0, "Bakery");
        assertThatThrownBy(() -> jdbc.update("UPDATE lots SET quantity = 1.5 WHERE id = ?", lot))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE lots SET initial_quantity = 2.5 WHERE id = ?", lot))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertLot(lot, 3.0, 3.0, "active");
    }
    private void migrateOnlyThroughV13() {
        jdbc.execute("DROP SCHEMA public CASCADE");
        jdbc.execute("CREATE SCHEMA public");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("13").load().migrate();
    }
    private void migrateThroughV14() {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("14").load().migrate();
    }
    private void migrateOnlyThroughV20() {
        jdbc.execute("DROP SCHEMA public CASCADE");
        jdbc.execute("CREATE SCHEMA public");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("20").load().migrate();
    }
    private void migrateLatest() {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    }
    private void assertLot(int lotId, double quantity, double initialQuantity, String status) {
        Map<String, Object> lot = jdbc.queryForMap(
            "SELECT quantity, initial_quantity, status FROM lots WHERE id = ?", lotId);
        assertThat(((Number) lot.get("quantity")).doubleValue()).isEqualTo(quantity);
        assertThat(((Number) lot.get("initial_quantity")).doubleValue()).isEqualTo(initialQuantity);
        assertThat(lot.get("status")).isEqualTo(status);
    }
}
