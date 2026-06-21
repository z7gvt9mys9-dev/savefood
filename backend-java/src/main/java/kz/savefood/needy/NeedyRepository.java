package kz.savefood.needy;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kz.savefood.util.Qr;
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

    /**
     * Set the moderation status (db.py {@code set_needy_status}). When
     * {@code expectedStatus} is given the flip is conditional and returns null if
     * the row was no longer in that state — the TOCTOU guard the auto-KYC thread
     * relies on so it cannot clobber a status changed during the AI call.
     */
    public Map<String, Object> setNeedyStatus(int needyId, String status, String expectedStatus) {
        if (getNeedyById(needyId) == null) {
            return null;
        }
        if (expectedStatus == null) {
            jdbc.update("UPDATE needy SET status = ? WHERE id = ?", status, needyId);
        } else {
            int rows = jdbc.update("UPDATE needy SET status = ? WHERE id = ? AND status = ?",
                status, needyId, expectedStatus);
            if (rows == 0) {
                return null;
            }
        }
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
     * coalesced (a null argument keeps the stored value), so PATCH and the
     * single-field document/geo writes share one path. Returns the {@code
     * NeedyProfileOut} shape, or null if the needy row is missing.
     */
    public Map<String, Object> createOrUpdateProfile(int needyId, String address, Integer familySize,
            String preferences, String urgency, String document, String availableTime, String apartment,
            String floorNum, String entrance, String city, Double lat, Double lon) {
        if (getNeedyById(needyId) == null) {
            return null;
        }
        List<Map<String, Object>> existing =
            jdbc.queryForList("SELECT * FROM needy_profile WHERE needy_id = ?", needyId);
        if (!existing.isEmpty()) {
            Map<String, Object> p = existing.get(0);
            jdbc.update(
                "UPDATE needy_profile SET address = ?, family_size = ?, preferences = ?, urgency = ?, "
                + "document = ?, available_time = ?, apartment = ?, floor_num = ?, entrance = ?, "
                + "city = ?, lat = ?, lon = ? WHERE needy_id = ?",
                coalesce(address, p.get("address")),
                familySize != null ? familySize : p.get("family_size"),
                coalesce(preferences, p.get("preferences")),
                coalesce(urgency, p.get("urgency")),
                coalesce(document, p.get("document")),
                coalesce(availableTime, p.get("available_time")),
                coalesce(apartment, p.get("apartment")),
                coalesce(floorNum, p.get("floor_num")),
                coalesce(entrance, p.get("entrance")),
                coalesce(city, p.get("city")),
                lat != null ? lat : p.get("lat"),
                lon != null ? lon : p.get("lon"),
                needyId);
        } else {
            jdbc.update(
                "INSERT INTO needy_profile (needy_id, address, family_size, preferences, urgency, "
                + "available_time, last_received_at, document, apartment, floor_num, entrance, city, lat, lon) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                needyId, address, familySize, preferences, urgency, availableTime, null, document,
                apartment, floorNum, entrance, city, lat, lon);
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
        out.put("account", n.get(0));
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
        m.put("document", rs.getString("document"));
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
