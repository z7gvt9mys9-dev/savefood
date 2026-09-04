package ru.savefood.volunteer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
@Repository
public class VolunteerRepository {
    public record KycDocumentReplacement(String previousDocument) {
    }
    public record KycModerationTransition(String document, String generation) {
    }
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    public VolunteerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }
    /** Full volunteer row with {@code availability} decoded, or null if missing. */
    public Map<String, Object> getVolunteerById(int volId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM volunteers WHERE id = ?", volId);
        return rows.isEmpty() ? null : parseAvailability(rows.get(0));
    }
    /** Atomically assigns the volunteer only if they still have no team. */
    public boolean assignTeamIfUnassigned(int volId, int teamId) {
        return jdbc.update(
            "UPDATE volunteers SET team_id = ? WHERE id = ? AND team_id IS NULL",
            teamId, volId) == 1;
    }
    public Map<String, Object> updateVolunteer(int volId, String name, String contact, Double lat, Double lon,
                                               String city, Boolean hasThermalBag, Double capacityKg,
                                               String availabilityJson) {
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
        Object newCapacity = capacityKg != null ? capacityKg : v.get("capacity_kg");
        Object newAvail = availabilityJson != null ? availabilityJson : rawAvailability(volId);
        jdbc.update(
            "UPDATE volunteers SET name = ?, contact = ?, lat = ?, lon = ?, city = ?, "
            + "has_thermal_bag = ?, capacity_kg = ?, availability = ? WHERE id = ?",
            newName, newContact, newLat, newLon, newCity, newBag, newCapacity, newAvail, volId);
        return getVolunteerById(volId);
    }
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
    public KycDocumentReplacement replaceVolunteerKycDocument(int volId, String document,
                                                               String generation) {
        List<KycDocumentReplacement> rows = jdbc.query(
            "WITH previous AS (SELECT id, document FROM volunteers WHERE id = ? FOR UPDATE) "
            + "UPDATE volunteers v SET document = ?, kyc_generation = ?, status = 'pending', "
            + "kyc_score = NULL, kyc_verdict = NULL, kyc_notes = NULL, kyc_checked_at = NULL "
            + "FROM previous p WHERE v.id = p.id "
            + "RETURNING p.document AS previous_document",
            (rs, n) -> new KycDocumentReplacement(rs.getString("previous_document")),
            volId, document, generation);
        return rows.isEmpty() ? null : rows.get(0);
    }
    /** Persist one complete analysis result only while its document generation is current. */
    public boolean saveVolunteerKyc(int volId, String generation, Double score,
                                    String verdict, String notes, String expectedStatus) {
        String statusGuard = expectedStatus == null ? "" : " AND status = ?";
        List<Object> args = new java.util.ArrayList<>();
        args.add(score);
        args.add(verdict);
        args.add(notes);
        args.add(OffsetDateTime.now());
        args.add(volId);
        args.add(generation);
        if (expectedStatus != null) {
            args.add(expectedStatus);
        }
        return jdbc.update(
            "UPDATE volunteers SET kyc_score = ?, kyc_verdict = ?, kyc_notes = ?, kyc_checked_at = ? "
            + "WHERE id = ? AND kyc_generation = ? AND document IS NOT NULL" + statusGuard,
            args.toArray()) == 1;
    }
    public boolean autoApproveVolunteerKyc(int volId, String generation) {
        return jdbc.update(
            "UPDATE volunteers SET status = 'approved', "
            + "kyc_notes = LEFT('[авто-одобрено ИИ] ' || COALESCE(kyc_notes, ''), 1000) "
            + "WHERE id = ? AND kyc_generation = ? AND document IS NOT NULL "
            + "AND status = 'pending' AND kyc_verdict = 'likely_ok'",
            volId, generation) == 1;
    }
    /** Atomically decide the current pending generation and return its exact identity. */
    public KycModerationTransition moderateVolunteerKyc(int volId, String status) {
        List<KycModerationTransition> rows = jdbc.query(
            "UPDATE volunteers SET status = ? WHERE id = ? AND status = 'pending' "
            + "RETURNING document, kyc_generation",
            (rs, n) -> new KycModerationTransition(
                rs.getString("document"), rs.getString("kyc_generation")),
            status, volId);
        return rows.isEmpty() ? null : rows.get(0);
    }
    /** Clear only the exact document generation selected by the caller. */
    public boolean clearVolunteerKycDocument(int volId, String document, String generation) {
        return jdbc.update(
            "UPDATE volunteers SET document = NULL, kyc_generation = NULL "
            + "WHERE id = ? AND document = ? AND kyc_generation = ?",
            volId, document, generation) == 1;
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
    public boolean needyHasVolunteer(int needyId, int volId) {
        return !jdbc.queryForList(
            "SELECT 1 FROM tickets WHERE needy_id = ? AND assigned_volunteer_id = ? "
            + "AND status = 'assigned' LIMIT 1", needyId, volId).isEmpty();
    }
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
    /** Lock a route before a read-modify-write lifecycle mutation. */
    public Map<String, Object> getRouteByIdForUpdate(int routeId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT * FROM volunteer_routes WHERE id = ? FOR UPDATE", routeId);
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
