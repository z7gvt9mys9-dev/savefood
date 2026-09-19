package ru.savefood.needy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.savefood.volunteer.AvailabilityService;
import ru.savefood.web.ApiException;

/** Recipient-facing snapshot of volunteer capacity for an open delivery request. */
@Service
public class DeliveryAvailabilityService {
    private static final int DEFAULT_WAIT_MINUTES = 60;

    private final JdbcTemplate jdbc;
    private final AvailabilityService availability;
    private final boolean kycRequired;

    public DeliveryAvailabilityService(JdbcTemplate jdbc, AvailabilityService availability,
            @Value("${savefood.volunteer-kyc-required:true}") boolean kycRequired) {
        this.jdbc = jdbc;
        this.availability = availability;
        this.kycRequired = kycRequired;
    }

    public Map<String, Object> forTicket(int needyId, int ticketId) {
        List<Map<String, Object>> tickets = jdbc.queryForList(
            "SELECT t.self_pickup, COALESCE(NULLIF(TRIM(l.city), ''), "
                + "NULLIF(TRIM(s.city), ''), NULLIF(TRIM(np.city), '')) AS city "
                + "FROM tickets t LEFT JOIN lots l ON l.id = t.lot_id "
                + "LEFT JOIN shops s ON s.id = l.shop_id "
                + "LEFT JOIN needy_profile np ON np.needy_id = t.needy_id "
                + "WHERE t.id = ? AND t.needy_id = ?",
            ticketId, needyId);
        if (tickets.isEmpty()) {
            throw new ApiException(404, "Ticket not found");
        }
        Map<String, Object> ticket = tickets.get(0);
        if (Boolean.TRUE.equals(ticket.get("self_pickup"))) {
            throw new ApiException(400, "Для самовывоза волонтёр не требуется");
        }
        String city = ticket.get("city") instanceof String value ? value.trim() : "";
        if (city.isEmpty()) {
            return summarize(List.of(), DEFAULT_WAIT_MINUTES);
        }

        String eligible = kycRequired ? " AND v.status = 'approved'" : "";
        List<Map<String, Object>> volunteers = jdbc.queryForList(
            "SELECT v.id, v.availability, "
                + "(v.last_seen_at >= CURRENT_TIMESTAMP - INTERVAL '2 minutes') AS connected, "
                + "EXISTS (SELECT 1 FROM volunteer_routes vr "
                + "WHERE vr.volunteer_id = v.id AND vr.status = 'in_progress') AS busy, "
                + "(SELECT EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - vr.started_at)) / 60.0 "
                + "FROM volunteer_routes vr WHERE vr.volunteer_id = v.id "
                + "AND vr.status = 'in_progress' ORDER BY vr.id LIMIT 1) AS active_minutes "
                + "FROM volunteers v WHERE LOWER(TRIM(v.city)) = LOWER(TRIM(?))" + eligible,
            city);
        Double average = jdbc.queryForObject(
            "SELECT AVG(EXTRACT(EPOCH FROM (finished_at - started_at)) / 60.0) "
                + "FROM volunteer_routes WHERE status = 'finished' AND finished_at IS NOT NULL "
                + "AND finished_at >= CURRENT_TIMESTAMP - INTERVAL '30 days'",
            Double.class);
        return summarize(volunteers, average == null ? DEFAULT_WAIT_MINUTES : average);
    }

    Map<String, Object> summarize(List<Map<String, Object>> volunteers, double averageRouteMinutes) {
        int online = 0;
        int free = 0;
        int nextOnlineMinutes = Integer.MAX_VALUE;
        double earliestBusyWait = Double.POSITIVE_INFINITY;
        for (Map<String, Object> volunteer : volunteers) {
            Object raw = volunteer.get("availability");
            String calendar = raw == null ? null : raw.toString();
            boolean connected = Boolean.TRUE.equals(volunteer.get("connected"));
            if (connected && availability.isAvailableNow(calendar)) {
                online++;
                if (!Boolean.TRUE.equals(volunteer.get("busy"))) {
                    free++;
                } else {
                    double elapsed = volunteer.get("active_minutes") instanceof Number number
                        ? Math.max(0, number.doubleValue()) : 0;
                    earliestBusyWait = Math.min(earliestBusyWait,
                        Math.max(5, averageRouteMinutes - elapsed));
                }
            } else if (connected) {
                int next = availability.minutesUntilAvailable(calendar);
                if (next >= 0) {
                    nextOnlineMinutes = Math.min(nextOnlineMinutes, next);
                }
            }
        }

        String status;
        int waitMinutes;
        if (free > 0) {
            status = "available";
            waitMinutes = 0;
        } else if (online > 0) {
            status = "busy";
            waitMinutes = roundToFive(Double.isFinite(earliestBusyWait)
                ? earliestBusyWait : averageRouteMinutes);
        } else {
            status = "no_online";
            waitMinutes = nextOnlineMinutes == Integer.MAX_VALUE
                ? DEFAULT_WAIT_MINUTES : roundToFive(nextOnlineMinutes);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status);
        out.put("online_volunteers", online);
        out.put("free_volunteers", free);
        out.put("estimated_wait_minutes", waitMinutes);
        return out;
    }

    private static int roundToFive(double minutes) {
        if (!Double.isFinite(minutes) || minutes <= 0) {
            return 5;
        }
        return Math.max(5, (int) (Math.ceil(minutes / 5.0) * 5));
    }
}
