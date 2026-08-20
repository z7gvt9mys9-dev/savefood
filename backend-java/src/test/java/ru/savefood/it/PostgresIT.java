package ru.savefood.it;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for integration tests: one Postgres container for the whole run, with the
 * real schema applied by Flyway from {@code db/migration}.
 *
 * <p>These exist because the unit suite is explicitly "pure logic only" — no
 * database, no Spring context — which leaves the transactional chains uncovered.
 * Those chains are exactly where the audits kept finding holes, and they are
 * enforced by things only Postgres has: partial unique indexes
 * ({@code uq_tickets_one_active_per_needy}), {@code UPDATE … RETURNING},
 * guarded updates and rollback semantics.
 *
 * <p>No Spring context is started. Services are constructed directly with a real
 * {@link JdbcTemplate}, which keeps these tests as fast and as explicit as the
 * unit ones while still exercising the real SQL.
 */
public abstract class PostgresIT {

    /**
     * Where the test database comes from.
     *
     * <p>By default a throwaway container, started once for the whole run —
     * per-class containers would dominate the runtime. Setting
     * {@code SAVEFOOD_IT_JDBC_URL} instead points the suite at an existing
     * Postgres (for example the one already running in docker compose). That is
     * faster locally, and it is the escape hatch when Testcontainers cannot talk
     * to the Docker daemon: Docker Desktop rejects the API version docker-java
     * negotiates by default, and the failure surfaces as the useless
     * "Could not find a valid Docker environment".
     *
     * <p><b>The target database is wiped:</b> {@code resetSchema} drops and
     * recreates the {@code public} schema before every test. Never point this at
     * anything you care about.
     */
    private static final String EXTERNAL_URL = System.getenv("SAVEFOOD_IT_JDBC_URL");

    private static final PostgreSQLContainer<?> POSTGRES;

    static {
        if (EXTERNAL_URL == null || EXTERNAL_URL.isBlank()) {
            @SuppressWarnings("resource")
            PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:15");
            container.start();
            POSTGRES = container;
        } else {
            POSTGRES = null;
        }
    }

    private static String url() {
        return POSTGRES != null ? POSTGRES.getJdbcUrl() : EXTERNAL_URL;
    }

    private static String user() {
        return POSTGRES != null ? POSTGRES.getUsername() : envOr("SAVEFOOD_IT_DB_USER", "postgres");
    }

    private static String password() {
        return POSTGRES != null ? POSTGRES.getPassword() : envOr("SAVEFOOD_IT_DB_PASS", "postgres");
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    protected DataSource dataSource;
    protected JdbcTemplate jdbc;
    protected PlatformTransactionManager txManager;
    protected TransactionTemplate tx;

    @BeforeEach
    void resetSchema() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(url());
        ds.setUsername(user());
        ds.setPassword(password());
        this.dataSource = ds;
        this.jdbc = new JdbcTemplate(ds);
        this.txManager = new DataSourceTransactionManager(ds);
        this.tx = new TransactionTemplate(txManager);

        // A clean schema per test: cheaper and far less surprising than unpicking
        // whatever the previous test left behind.
        jdbc.execute("DROP SCHEMA public CASCADE");
        jdbc.execute("CREATE SCHEMA public");
        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .load()
            .migrate();
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    // NB: created_at is NOT NULL *without* a default in the real schema (the
    // alembic definition had one; the live database does not). Production code
    // always passes it explicitly, and so must these fixtures.
    protected int insertShop(String name, double lat, double lon) {
        return jdbc.queryForObject(
            "INSERT INTO shops (name, lat, lon, city, created_at) "
            + "VALUES (?, ?, ?, 'Алматы', NOW()) RETURNING id",
            Integer.class, name, lat, lon);
    }

    protected int insertLot(int shopId, double quantity, String category) {
        return jdbc.queryForObject(
            "INSERT INTO lots (shop_id, description, quantity, initial_quantity, unit, "
            + "unit_weight_kg, category, status, expiry_date, created_at) "
            + "VALUES (?, 'лот', ?, ?, 'кг', 1.0, ?, 'active', CURRENT_DATE + 10, NOW()) RETURNING id",
            Integer.class, shopId, quantity, quantity, category);
    }

    protected int insertNeedy(String name) {
        Integer id = jdbc.queryForObject(
            "INSERT INTO needy (name, status, created_at) VALUES (?, 'active', NOW()) RETURNING id",
            Integer.class, name);
        jdbc.update("INSERT INTO needy_profile (needy_id, family_size, lat, lon) VALUES (?, 2, 43.24, 76.90)", id);
        return id;
    }

    protected int insertVolunteer(String name) {
        return jdbc.queryForObject(
            "INSERT INTO volunteers (name, lat, lon, status, created_at) "
            + "VALUES (?, 43.238, 76.889, 'approved', NOW()) RETURNING id",
            Integer.class, name);
    }

    protected String status(String table, int id) {
        return jdbc.queryForObject("SELECT status FROM " + table + " WHERE id = ?", String.class, id);
    }

    protected double lotQuantity(int lotId) {
        return jdbc.queryForObject("SELECT quantity FROM lots WHERE id = ?", Double.class, lotId);
    }
}
