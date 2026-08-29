package ru.savefood.needy;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.savefood.util.Qr;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Port of the single-statement (and read-modify-write) functions of
 * backend/needy/db.py, on {@link JdbcTemplate} (raw SQL, mirroring the psycopg2
 * style). {@code TicketOut} / {@code NotificationOut} / {@code NeedyProfileOut}
 * shapes are built explicitly so the JSON matches the pydantic
 * {@code response_model} field set — notably the per-ticket {@code qr_secret} is
 * never emitted, only the derived {@code qr_code}.
 *
 * <p>Multi-statement transactional flows (register, ticket creation/cancel,
 * account erase) live in {@link NeedyService}; schema creation ({@code init_db})
 * stays with the Python migrations, since the Postgres schema is shared.
 */
@Repository
public class NeedyRepository {

    private final JdbcTemplate jdbc;

    public NeedyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Needy ────────────────────────────────────────────────────────────────────

    public Map<String, Object> getNeedyById(int needyId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM needy WHERE id = ?", needyId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Update name/contact and return the refreshed row, or null if missing. */
    public Map<String, Object> updateNeedy(int needyId, String name, String contact) {
        if (getNeedyById(needyId) == null) {
            return null;
        }
        jdbc.update("UPDATE needy SET name = ?, contact = ? WHERE id = ?", name, contact, needyId);
        return getNeedyById(needyId);
    }

    // ── Tickets ──────────────────────────────────────────────────────────────────

    public Map<String, Object> getTicketById(int ticketId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM tickets WHERE id = ?", ticketId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> getTicketsByNeedyId(int needyId) {
        return jdbc.query("SELECT * FROM tickets WHERE needy_id = ? ORDER BY created_at DESC",
            ticketOut(false), needyId);
    }

    /**
     * History view (db.py {@code get_history}): assigned/fulfilled tickets with the
     * saved star rating and thank-you note joined in so they survive page reloads.
     */
    public List<Map<String, Object>> getHistory(int needyId, int limit, int offset) {
        return jdbc.query(
            "SELECT t.*, dr.rating, dr.comment AS rating_comment "
            + "FROM tickets t LEFT JOIN delivery_ratings dr ON dr.ticket_id = t.id "
            + "WHERE t.needy_id = ? AND t.status IN ('assigned','fulfilled') "
            + "ORDER BY t.created_at DESC LIMIT ? OFFSET ?",
            ticketOut(true), needyId, limit, offset);
    }

    // ── Notifications ────────────────────────────────────────────────────────────

    public List<Map<String, Object>> getNotifications(int needyId) {
        return jdbc.query("SELECT * FROM notifications WHERE needy_id = ? ORDER BY created_at DESC",
            NOTIFICATION_OUT, needyId);
    }

    public Map<String, Object> getNotificationById(int id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM notifications WHERE id = ?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void markNotificationRead(int id) {
        jdbc.update("UPDATE notifications SET read = 1 WHERE id = ?", id);
    }

    // ── Profile ──────────────────────────────────────────────────────────────────

    public Map<String, Object> getProfile(int needyId) {
        return jdbc.query("SELECT * FROM needy_profile WHERE needy_id = ?", PROFILE_OUT, needyId)
            .stream().findFirst().orElse(null);
    }

    /**
     * Upsert the profile (db.py {@code create_or_update_profile}): every column is
     * coalesced (a null argument keeps the stored value), so PATCH and geo writes
     * share one path. Returns the {@code
     * NeedyProfileOut} shape, or null if the needy row is missing.
     */
    public Map<String, Object> createOrUpdateProfile(int needyId, String address, Integer familySize,
            String preferences, String urgency, String availableTime, String apartment,
            String floorNum, String entrance, String city, Double lat, Double lon,
            boolean clearCoordinates) {
        if (getNeedyById(needyId) == null) {
            return null;
        }
        List<Map<String, Object>> existing =
            jdbc.queryForList("SELECT * FROM needy_profile WHERE needy_id = ?", needyId);
        if (!existing.isEmpty()) {
            Map<String, Object> p = existing.get(0);
            jdbc.update(
                "UPDATE needy_profile SET address = ?, family_size = ?, preferences = ?, urgency = ?, "
                + "available_time = ?, apartment = ?, floor_num = ?, entrance = ?, "
                + "city = ?, lat = ?, lon = ? WHERE needy_id = ?",
                coalesce(address, p.get("address")),
                familySize != null ? familySize : p.get("family_size"),
                coalesce(preferences, p.get("preferences")),
                coalesce(urgency, p.get("urgency")),
                coalesce(availableTime, p.get("available_time")),
                coalesce(apartment, p.get("apartment")),
                coalesce(floorNum, p.get("floor_num")),
                coalesce(entrance, p.get("entrance")),
                coalesce(city, p.get("city")),
                clearCoordinates ? null : (lat != null ? lat : p.get("lat")),
                clearCoordinates ? null : (lon != null ? lon : p.get("lon")),
                needyId);
        } else {
            jdbc.update(
                "INSERT INTO needy_profile (needy_id, address, family_size, preferences, urgency, "
                + "available_time, last_received_at, apartment, floor_num, entrance, city, lat, lon) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                needyId, address, familySize, preferences, urgency, availableTime, null,
                apartment, floorNum, entrance, city, clearCoordinates ? null : lat,
                clearCoordinates ? null : lon);
        }
        return getProfile(needyId);
    }

    /**
     * Toggle the geo-push subscription (db.py {@code set_geo_push_enabled}, §48):
     * creates a bare profile row if none exists. Returns false only when the needy
     * row itself is missing.
     */
    public boolean setGeoPushEnabled(int needyId, boolean enabled) {
        int rows = jdbc.update("UPDATE needy_profile SET geo_push_enabled = ? WHERE needy_id = ?",
            enabled, needyId);
        if (rows == 0) {
            if (getNeedyById(needyId) == null) {
                return false;
            }
            jdbc.update("INSERT INTO needy_profile (needy_id, geo_push_enabled) VALUES (?, ?)",
                needyId, enabled);
        }
        return true;
    }

    // ── Data export (§49 «право на доступ») ──────────────────────────────────────

    /** Everything the platform holds about one recipient, as one map (db.py {@code export_account}). */
    public Map<String, Object> exportAccount(int needyId) {
        List<Map<String, Object>> n = jdbc.queryForList(
            "SELECT id, name, contact, status, created_at FROM needy WHERE id = ?", needyId);
        if (n.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> profile =
            jdbc.queryForList("SELECT * FROM needy_profile WHERE needy_id = ?", needyId);
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> account = new LinkedHashMap<>(n.get(0));
        List<Map<String, Object>> users = jdbc.queryForList(
            "SELECT id, username, role, related_id, created_at, is_blocked, "
                + "telegram_chat_id, google_id, yandex_id "
                + "FROM users WHERE role = 'needy' AND related_id = ? ORDER BY id LIMIT 1",
            needyId);
        Map<String, Object> user = users.isEmpty() ? null : users.get(0);
        account.put("user_id", user == null ? null : user.get("id"));
        account.put("username", user == null ? null : user.get("username"));
        account.put("account_created_at", user == null ? null : user.get("created_at"));
        account.put("is_blocked", user == null ? null : user.get("is_blocked"));
        out.put("account", account);
        Map<String, Object> links = new LinkedHashMap<>();
        links.put("telegram_chat_id", user == null ? null : user.get("telegram_chat_id"));
        links.put("google_id", user == null ? null : user.get("google_id"));
        links.put("yandex_id", user == null ? null : user.get("yandex_id"));
        out.put("account_links", links);
        int userId = user == null ? -1 : ((Number) user.get("id")).intValue();
        out.put("push_subscriptions", sanitizedPushSubscriptions(jdbc.queryForList(
            "SELECT id, endpoint, created_at FROM push_subscriptions WHERE user_id = ? ORDER BY id", userId)));
        out.put("fcm_registrations", sanitizedFcmRegistrations(jdbc.queryForList(
            "SELECT id, token, created_at FROM fcm_tokens WHERE user_id = ? "
                + "AND role = 'needy' AND related_id = ? ORDER BY id", userId, needyId)));
        out.put("refresh_sessions", jdbc.queryForList(
            "SELECT session_id, created_at, expires_at, consumed_at, revoked_at, "
                + "CASE WHEN revoked_at IS NOT NULL THEN 'revoked' "
                + "WHEN consumed_at IS NOT NULL THEN 'consumed' "
                + "WHEN expires_at <= CURRENT_TIMESTAMP THEN 'expired' ELSE 'active' END AS status "
                + "FROM refresh_sessions WHERE user_id = ? ORDER BY created_at", userId));
        out.put("profile", profile.isEmpty() ? null : profile.get(0));
        out.put("tickets",
            jdbc.queryForList("SELECT * FROM tickets WHERE needy_id = ? ORDER BY created_at", needyId));
        out.put("ratings", jdbc.queryForList(
            "SELECT dr.* FROM delivery_ratings dr JOIN tickets t ON t.id = dr.ticket_id "
            + "WHERE t.needy_id = ?", needyId));
        out.put("notifications", jdbc.queryForList(
            "SELECT id, type, payload, created_at, read FROM notifications WHERE needy_id = ? "
            + "ORDER BY created_at", needyId));
        out.put("messages", jdbc.queryForList(
            "SELECT tm.id, tm.ticket_id, tm.sender_role, tm.body, tm.created_at "
            + "FROM ticket_messages tm JOIN tickets t ON t.id = tm.ticket_id "
            + "WHERE t.needy_id = ? ORDER BY tm.id", needyId));
        return out;
    }

    /** Keep device registrations visible to their owner without disclosing delivery credentials. */
    private static List<Map<String, Object>> sanitizedPushSubscriptions(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> subscription = new LinkedHashMap<>();
            subscription.put("id", row.get("id"));
            subscription.put("type", "web_push");
            subscription.put("endpoint_redacted", redactIdentifier((String) row.get("endpoint")));
            subscription.put("created_at", row.get("created_at"));
            out.add(subscription);
        }
        return out;
    }

    /** FCM tokens identify a recipient device, but must not be usable as raw registration credentials. */
    private static List<Map<String, Object>> sanitizedFcmRegistrations(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> registration = new LinkedHashMap<>();
            registration.put("id", row.get("id"));
            registration.put("type", "fcm");
            registration.put("token_redacted", redactIdentifier((String) row.get("token")));
            registration.put("created_at", row.get("created_at"));
            out.add(registration);
        }
        return out;
    }

    private static String redactIdentifier(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        int suffixLength = Math.min(6, value.length());
        return "…" + value.substring(value.length() - suffixLength);
    }

    // ── Row mappers (exact response_model shapes) ────────────────────────────────

    /** Builds the {@code TicketOut} map; {@code withRating} adds the joined history columns. */
    private static RowMapper<Map<String, Object>> ticketOut(boolean withRating) {
        return (rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            int id = rs.getInt("id");
            m.put("id", id);
            m.put("needy_id", rs.getInt("needy_id"));
            m.put("items", rs.getString("items"));
            m.put("address", rs.getString("address"));
            m.put("lat", getDouble(rs, "lat"));
            m.put("lon", getDouble(rs, "lon"));
            m.put("available_time", rs.getString("available_time"));
            m.put("lot_id", getInteger(rs, "lot_id"));
            m.put("status", rs.getString("status"));
            m.put("created_at", rs.getObject("created_at", OffsetDateTime.class));
            m.put("assigned_volunteer_id", getInteger(rs, "assigned_volunteer_id"));
            m.put("fulfilled_at", rs.getObject("fulfilled_at", OffsetDateTime.class));
            m.put("apartment", rs.getString("apartment"));
            m.put("floor_num", rs.getString("floor_num"));
            m.put("entrance", rs.getString("entrance"));
            m.put("self_pickup", getBoolean(rs, "self_pickup"));
            // Owner/admin-gated rows expose the full QR payload; the raw secret never leaves the server.
            m.put("qr_code", Qr.buildCode(id, rs.getString("qr_secret")));
            m.put("delivery_photo", rs.getString("delivery_photo"));
            m.put("delivery_photo_status", rs.getString("delivery_photo_status"));
            m.put("rating", withRating ? getInteger(rs, "rating") : null);
            m.put("rating_comment", withRating ? rs.getString("rating_comment") : null);
            return m;
        };
    }

    private static final RowMapper<Map<String, Object>> NOTIFICATION_OUT = (rs, n) -> {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getInt("id"));
        m.put("needy_id", getInteger(rs, "needy_id"));
        m.put("type", rs.getString("type"));
        m.put("payload", rs.getString("payload"));
        m.put("created_at", rs.getObject("created_at", OffsetDateTime.class));
        m.put("read", rs.getInt("read"));
        return m;
    };

    private static final RowMapper<Map<String, Object>> PROFILE_OUT = (rs, n) -> {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("needy_id", rs.getInt("needy_id"));
        m.put("address", rs.getString("address"));
        m.put("family_size", getInteger(rs, "family_size"));
        m.put("preferences", rs.getString("preferences"));
        m.put("urgency", rs.getString("urgency"));
        m.put("available_time", rs.getString("available_time"));
        m.put("last_received_at", rs.getObject("last_received_at", OffsetDateTime.class));
        m.put("apartment", rs.getString("apartment"));
        m.put("floor_num", rs.getString("floor_num"));
        m.put("entrance", rs.getString("entrance"));
        m.put("city", rs.getString("city"));
        m.put("lat", getDouble(rs, "lat"));
        m.put("lon", getDouble(rs, "lon"));
        Object geo = rs.getObject("geo_push_enabled");
        m.put("geo_push_enabled", geo == null ? Boolean.TRUE : rs.getBoolean("geo_push_enabled"));
        return m;
    };

    private static Object coalesce(Object value, Object fallback) {
        return value != null ? value : fallback;
    }

    private static Double getDouble(ResultSet rs, String col) throws SQLException {
        Object v = rs.getObject(col);
        return v instanceof Number num ? num.doubleValue() : null;
    }

    private static Integer getInteger(ResultSet rs, String col) throws SQLException {
        Object v = rs.getObject(col);
        return v instanceof Number num ? num.intValue() : null;
    }

    private static Boolean getBoolean(ResultSet rs, String col) throws SQLException {
        Object v = rs.getObject(col);
        return v == null ? null : rs.getBoolean(col);
    }
}
