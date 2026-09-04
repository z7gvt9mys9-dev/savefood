package ru.savefood.volunteer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
/** Privacy boundary for recipient data denormalized into route point JSON. */
public final class RoutePointPrivacy {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final Set<String> SENSITIVE_TICKET_FIELDS = Set.of(
        "lat", "lon", "latitude", "longitude",
        "address", "recipient_address", "full_address",
        "apartment", "unit", "unit_number", "floor", "floor_num", "entrance",
        "addr_detail", "door", "door_code", "access_code", "access_instructions",
        "door_instructions", "instructions",
        "contact", "contact_info", "recipient_contact", "phone", "phone_number", "email",
        "description", "items", "requested_items", "recipient_notes", "notes", "free_text",
        "available_time", "recipient_name", "needy_id", "recipient_id"
    );
    /** Explicit non-sensitive historical schema; unknown ticket fields fail closed. */
    public static final Set<String> HISTORICAL_TICKET_FIELDS = Set.of(
        "kind", "ticket_id", "lot_id", "shop_id", "sequence", "position",
        "done", "cancelled", "released", "failed", "status", "outcome", "attempt_count",
        "quantity", "weight_kg", "completed_at", "fulfilled_at", "cancelled_at", "failed_at",
        "terminal_at"
    );
    private RoutePointPrivacy() {
    }
    /** Remove recipient-only data from one ticket point, retaining outcome/statistical fields. */
    public static boolean redactTicketPoint(Map<String, Object> point) {
        if (!"ticket".equals(point.get("kind"))) {
            return false;
        }
        boolean changed = false;
        for (String field : List.copyOf(point.keySet())) {
            if (!HISTORICAL_TICKET_FIELDS.contains(field)) {
                point.remove(field);
                changed = true;
            }
        }
        return changed;
    }
    /** Remove recipient-only data from every ticket point in a route. */
    public static boolean redactAllTicketPoints(List<Map<String, Object>> points) {
        boolean changed = false;
        for (Map<String, Object> point : points) {
            changed |= redactTicketPoint(point);
        }
        return changed;
    }
    /** Terminal point flags used by current and legacy route JSON. */
    public static boolean isTerminalTicketPoint(Map<String, Object> point) {
        return "ticket".equals(point.get("kind"))
            && (Boolean.TRUE.equals(point.get("done"))
                || Boolean.TRUE.equals(point.get("cancelled"))
                || Boolean.TRUE.equals(point.get("released"))
                || Boolean.TRUE.equals(point.get("failed")));
    }
    /** Safe terminal-route serializer: malformed legacy JSON is discarded, not exposed. */
    public static String redactAllTicketPointsJson(Object pointsRaw) {
        if (pointsRaw == null || pointsRaw.toString().isBlank()) {
            return "[]";
        }
        try {
            List<Map<String, Object>> points = MAPPER.readValue(
                pointsRaw.toString(), new TypeReference<>() { });
            redactAllTicketPoints(points);
            return MAPPER.writeValueAsString(points);
        } catch (Exception ignored) {
            return "[]";
        }
    }
}
