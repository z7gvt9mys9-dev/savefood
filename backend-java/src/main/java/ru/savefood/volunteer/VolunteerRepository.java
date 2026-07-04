package ru.savefood.volunteer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Port of the single-statement (and read-modify-write) functions of
 * backend/volunteer/db.py, on {@link JdbcTemplate}. The multi-statement
 * transactional flows (start_route, complete_point, finish, teams) live in
 * {@link VolunteerService}; schema creation ({@code init_db}) stays with the
 * Python migrations since the Postgres schema is shared.
 *
 * <p>Rows are returned as column-keyed maps ({@code queryForList}), matching the
 * Python routes that return raw {@code dict(row)} values. The one shape the Python
 * side narrows — {@code VolunteerOut} on {@code GET /volunteers/{id}} — is
 * projected in the controller; here {@code availability} (a JSON TEXT column) is
 * decoded to a JSON array so it serialises like the pydantic field.
 */
@Repository
public class VolunteerRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public VolunteerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Volunteers ───────────────────────────────────────────────────────────────

    /** Full volunteer row with {@code availability} decoded, or null if missing. */
    public Map<String, Object> getVolunteerById(int volId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM volunteers WHERE id = ?", volId);
        return rows.isEmpty() ? null : parseAvailability(rows.get(0));
    }

    public Map<String, Object> updateVolunteer(int volId, String name, String contact, Double lat, Double lon,
                                               String city, Boolean hasThermalBag, String availabilityJson) {
        Map<String, Object> v = getVolunteerById(volId);
        if (v == null) {
            return null;
        }
        Object newName = name != null ? name : v.get("name");
        Object newContact = contact != null ? contact : v.get("contact");
        Object newLat = lat != null ? lat : v.get("lat");
        Object newLon = lon != null ? lon : v.get("lon");
        Object newCity = city != null ? city : v.get("city");
        Object newBag = hasThermalBag != null ? hasThermalBag : v.get("has_thermal_bag");
        // availability was decoded to a node on read; fall back to the raw stored text.
        Object newAvail = availabilityJson != null ? availabilityJson : rawAvailability(volId);
        jdbc.update(
            "UPDATE volunteers SET name = ?, contact = ?, lat = ?, lon = ?, city = ?, "
            + "has_thermal_bag = ?, availability = ? WHERE id = ?",
            newName, newContact, newLat, newLon, newCity, newBag, newAvail, volId);
        return getVolunteerById(volId);
    }

    /**
     * Auto-KYC decision (db.py {@code set_volunteer_status}). When
     * {@code expectedStatus} is given the flip is conditional and returns null if
     * the row was no longer in that state (TOCTOU guard for the auto-KYC thread).
     */
    public Map<String, Object> setVolunteerStatus(int volId, String status, String expectedStatus) {
        List<Integer> ids = jdbc.query("SELECT 1 FROM volunteers WHERE id = ?", (rs, n) -> 1, volId);
        if (ids.isEmpty()) {
            return null;
        }
        if (expectedStatus == null) {
            jdbc.update("UPDATE volunteers SET status = ? WHERE id = ?", status, volId);
        } else {
            int rows = jdbc.update("UPDATE volunteers SET status = ? WHERE id = ? AND status = ?",
                status, volId, expectedStatus);
            if (rows == 0) {
                return null;
            }
        }
        return getVolunteerById(volId);
    }

    public void setVolunteerDocument(int volId, String document) {
        jdbc.update("UPDATE volunteers SET document = ? WHERE id = ?", document, volId);
    }

    public void saveVolunteerKyc(int volId, Double score, String verdict, String notes) {
        jdbc.update(
            "UPDATE volunteers SET kyc_score = ?, kyc_verdict = ?, kyc_notes = ?, kyc_checked_at = ? WHERE id = ?",
            score, verdict, notes, OffsetDateTime.now(), volId);
    }

    public void updateVolunteerLocation(int volId, double lat, double lon) {
        jdbc.update("UPDATE volunteers SET lat = ?, lon = ?, updated_at = ? WHERE id = ?",
            lat, lon, OffsetDateTime.now(), volId);
    }

    public Map<String, Object> getVolunteerLocation(int volId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT lat, lon, updated_at FROM volunteers WHERE id = ?", volId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ── Notifications ──────────────────────────────────────────────────────────────

    public List<Map<String, Object>> getNotifications(int volId) {
        return jdbc.queryForList(
            "SELECT * FROM notifications WHERE volunteer_id = ? ORDER BY created_at DESC", volId);
    }

    public Map<String, Object> getNotificationById(int id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM notifications WHERE id = ?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void markNotificationRead(int id) {
        jdbc.update("UPDATE notifications SET read = 1 WHERE id = ?", id);
    }

    /**
     * Only an ACTIVE assignment grants a recipient access to the volunteer's live
     * location; once the ticket is fulfilled/released, tracking must stop (privacy).
     */
    public boolean needyHasVolunteer(int needyId, int volId) {
        return !jdbc.queryForList(
            "SELECT 1 FROM tickets WHERE needy_id = ? AND assigned_volunteer_id = ? "
            + "AND status = 'assigned' LIMIT 1", needyId, volId).isEmpty();
    }

    // ── Routes ─────────────────────────────────────────────────────────────────────

    public List<Map<String, Object>> getRoutesByVolunteer(int volId, int limit, int offset) {
        return jdbc.queryForList(
            "SELECT * FROM volunteer_routes WHERE volunteer_id = ? ORDER BY started_at DESC LIMIT ? OFFSET ?",
            volId, limit, offset);
    }

    public Map<String, Object> getRouteById(int routeId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT * FROM volunteer_routes WHERE id = ?", routeId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> getActiveRoute(int volId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT * FROM volunteer_routes WHERE volunteer_id = ? AND status = 'in_progress' "
            + "ORDER BY started_at DESC LIMIT 1", volId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void updateRoutePoints(int routeId, String pointsJson) {
        jdbc.update("UPDATE volunteer_routes SET points = ?, last_activity_at = ? WHERE id = ?",
            pointsJson, OffsetDateTime.now(), routeId);
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    private String rawAvailability(int volId) {
        List<String> raw = jdbc.query("SELECT availability FROM volunteers WHERE id = ?",
            (rs, n) -> rs.getString("availability"), volId);
        return raw.isEmpty() ? null : raw.get(0);
    }

    /** Decode the {@code availability} JSON TEXT column into a node (bad/empty → null). */
    private Map<String, Object> parseAvailability(Map<String, Object> row) {
        Object raw = row.get("availability");
        if (raw instanceof String s) {
            try {
                row.put("availability", s.isBlank() ? null : mapper.readTree(s));
            } catch (Exception e) {
                row.put("availability", null);
            }
        }
        return row;
    }
}
