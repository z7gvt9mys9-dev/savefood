package ru.savefood.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import ru.savefood.shop.ShopRepository;

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

    private void migrateOnlyThroughV13() {
        jdbc.execute("DROP SCHEMA public CASCADE");
        jdbc.execute("CREATE SCHEMA public");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("13").load().migrate();
    }

    private void migrateThroughV14() {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("14").load().migrate();
    }
}
