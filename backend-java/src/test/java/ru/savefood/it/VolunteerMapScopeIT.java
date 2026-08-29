package ru.savefood.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.savefood.needy.NeedyRepository;
import ru.savefood.needy.NeedyService;
import ru.savefood.security.PasswordService;
import ru.savefood.volunteer.RouteRevertService;
import ru.savefood.volunteer.VolunteerRepository;
import ru.savefood.volunteer.VolunteerService;

class VolunteerMapScopeIT extends PostgresIT {

    private NeedyService needyService;
    private VolunteerService volunteerService;

    @BeforeEach
    void wire() {
        needyService = new NeedyService(jdbc, new NeedyRepository(jdbc), new PasswordService());
        volunteerService = mapService(jdbc);
    }

    @Test
    void mapContainsOnlyTheRequestedCityAndKeepsDeliveryAvailabilityCounts() {
        int almatyLot = deliveryLot("Алматы", "Алматы магазин");
        int remoteLot = deliveryLot("Астана", "Астана магазин");
        createDeliveryTicket(almatyLot, "первая заявка");
        createDeliveryTicket(almatyLot, "вторая заявка");
        createDeliveryTicket(remoteLot, "чужая заявка");

        Map<String, Object> map = volunteerService.mapPoints("Алматы", 100);

        assertThat(lots(map)).extracting(lot -> lot.get("lot_id")).containsExactly(almatyLot);
        assertThat(lots(map).get(0).get("open_delivery_tickets")).isEqualTo(2);
        assertThat(tickets(map)).extracting(ticket -> ticket.get("lot_id")).containsOnly(almatyLot);
        assertThat(tickets(map)).hasSize(2);
    }

    @Test
    void mapCapsEachResponseCollectionAndIgnoresThousandsOfUnrelatedRowsWithTwoQueries() {
        for (int i = 0; i < 4; i++) {
            int lot = deliveryLot("Алматы", "Локальный магазин " + i);
            createDeliveryTicket(lot, "локальная заявка " + i);
        }
        CountingJdbcTemplate countingJdbc = new CountingJdbcTemplate(dataSource);
        VolunteerService countedService = mapService(countingJdbc);

        Map<String, Object> before = countedService.mapPoints("Алматы", 2);
        assertThat(lots(before)).hasSize(2);
        assertThat(tickets(before)).hasSize(2);
        assertThat(countingJdbc.queryForListCalls).isEqualTo(2);

        insertThousandsOfRemoteTickets();
        countingJdbc.queryForListCalls = 0;
        Map<String, Object> after = countedService.mapPoints("Алматы", 2);

        assertThat(lots(after)).hasSize(2);
        assertThat(tickets(after)).hasSize(2);
        assertThat(countingJdbc.queryForListCalls).isEqualTo(2);
    }

    @Test
    void mapIndexMigrationIsApplied() {
        List<String> indexes = jdbc.queryForList(
            "SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'tickets'",
            String.class);

        assertThat(indexes).contains("idx_tickets_lot_status");
    }

    private VolunteerService mapService(JdbcTemplate template) {
        return new VolunteerService(template, new VolunteerRepository(template), new RouteRevertService(template),
            new PasswordService(), needyService, null, "Europe/Moscow");
    }

    private int deliveryLot(String city, String shopName) {
        int shop = jdbc.queryForObject(
            "INSERT INTO shops (name, lat, lon, city, created_at) VALUES (?, 43.238, 76.889, ?, NOW()) RETURNING id",
            Integer.class, shopName, city);
        return insertLot(shop, 2_000.0, "Выпечка");
    }

    private void createDeliveryTicket(int lotId, String items) {
        int needy = insertNeedy(items);
        needyService.createTicket(needy, items, "адрес", 43.24, 76.90, null, lotId,
            null, null, null, false);
    }

    private void insertThousandsOfRemoteTickets() {
        int remoteLot = deliveryLot("Астана", "Удалённый магазин");
        jdbc.update("INSERT INTO needy (name, status, created_at) "
            + "SELECT 'remote-map-' || g, 'active', NOW() FROM generate_series(1, 1000) g");
        jdbc.update("INSERT INTO tickets (needy_id, items, address, lat, lon, lot_id, quantity, status, created_at, self_pickup) "
            + "SELECT id, 'remote', 'remote', 51.1694, 71.4491, ?, 1, 'open', NOW(), FALSE "
            + "FROM needy WHERE name LIKE 'remote-map-%'", remoteLot);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> lots(Map<String, Object> map) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> shop : (List<Map<String, Object>>) map.get("shops")) {
            out.addAll((List<Map<String, Object>>) shop.get("lots"));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> tickets(Map<String, Object> map) {
        return (List<Map<String, Object>>) map.get("tickets");
    }

    private static final class CountingJdbcTemplate extends JdbcTemplate {
        private int queryForListCalls;

        private CountingJdbcTemplate(javax.sql.DataSource dataSource) {
            super(dataSource);
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            queryForListCalls++;
            return super.queryForList(sql, args);
        }
    }
}
