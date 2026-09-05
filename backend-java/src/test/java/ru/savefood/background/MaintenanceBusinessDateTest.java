package ru.savefood.background;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class MaintenanceBusinessDateTest {
    @Test
    void oneTickPassesOneStableConfiguredBusinessDateToSelectAndUpdate() {
        Instant instant = Instant.parse("2026-01-02T21:30:00Z");
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        MaintenanceTasks tasks = new MaintenanceTasks(jdbc, new NoOpTransactionManager(), null,
            null, null, "embedded", "", "/tmp/savefood-business-date-test", 1, 0, 100,
            Clock.fixed(instant, ZoneId.of("Europe/Moscow")));

        tasks.expireTick();

        assertThat(jdbc.businessDates).containsExactly(
            LocalDate.of(2026, 1, 3), LocalDate.of(2026, 1, 3));
        assertThat(jdbc.dateSql).allMatch(sql -> !sql.contains("CURRENT_DATE"));
    }

    @Test
    void changingConfiguredZoneChangesMaintenanceBusinessDate() {
        Instant instant = Instant.parse("2026-01-02T23:30:00Z");
        assertThat(capturedDate(instant, "UTC")).isEqualTo(LocalDate.of(2026, 1, 2));
        assertThat(capturedDate(instant, "Asia/Tokyo")).isEqualTo(LocalDate.of(2026, 1, 3));
    }

    @Test
    void dstObservingZoneSuppliesItsLocalCalendarDate() {
        Instant instant = Instant.parse("2026-11-01T03:30:00Z");
        assertThat(capturedDate(instant, "America/New_York")).isEqualTo(LocalDate.of(2026, 10, 31));
    }

    private static LocalDate capturedDate(Instant instant, String zone) {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate(false);
        MaintenanceTasks tasks = new MaintenanceTasks(jdbc, new NoOpTransactionManager(), null,
            null, null, "embedded", "", "/tmp/savefood-business-date-test", 1, 0, 100,
            Clock.fixed(instant, ZoneId.of(zone)));
        tasks.expireTick();
        return jdbc.businessDates.get(0);
    }

    private static final class CapturingJdbcTemplate extends JdbcTemplate {
        private final boolean returnCandidate;
        private final List<LocalDate> businessDates = new ArrayList<>();
        private final List<String> dateSql = new ArrayList<>();

        private CapturingJdbcTemplate() {
            this(true);
        }

        private CapturingJdbcTemplate(boolean returnCandidate) {
            this.returnCandidate = returnCandidate;
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            if (sql.startsWith("SELECT id FROM lots")) {
                capture(sql, args);
                return returnCandidate ? List.of(Map.of("id", 7)) : List.of();
            }
            return List.of();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            if (sql.startsWith("UPDATE lots SET status = 'expired'")) {
                capture(sql, args);
                return (List<T>) List.of(11);
            }
            return List.of();
        }

        @Override
        public int update(String sql, Object... args) {
            return 1;
        }

        private void capture(String sql, Object[] args) {
            dateSql.add(sql);
            for (Object arg : args) {
                if (arg instanceof LocalDate date) {
                    businessDates.add(date);
                }
            }
        }
    }

    private static final class NoOpTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
