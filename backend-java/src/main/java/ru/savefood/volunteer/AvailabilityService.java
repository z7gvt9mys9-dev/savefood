package ru.savefood.volunteer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
@Service
public class AvailabilityService {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ZoneId zone;
    public AvailabilityService(@Value("${savefood.local-tz:Europe/Moscow}") String localTz) {
        ZoneId z;
        try {
            z = ZoneId.of(localTz);
        } catch (RuntimeException e) {
            z = ZoneId.of("Europe/Moscow");
        }
        this.zone = z;
    }
    public boolean isAvailableNow(String availabilityJson) {
        return isAvailableNow(availabilityJson, LocalDateTime.now(zone));
    }
    /** Approximate delay until the next configured availability window (at most one week). */
    public int minutesUntilAvailable(String availabilityJson) {
        return minutesUntilAvailable(availabilityJson, LocalDateTime.now(zone));
    }
    int minutesUntilAvailable(String availabilityJson, LocalDateTime now) {
        JsonNode windows = parseWindows(availabilityJson);
        if (windows == null || windows.isEmpty() || isAvailableNow(windows, now)) {
            return 0;
        }
        // Minute resolution is intentional: the UI rounds the result to a friendly 5-minute ETA.
        for (int minutes = 1; minutes <= 7 * 24 * 60; minutes++) {
            if (isAvailableNow(windows, now.plusMinutes(minutes))) {
                return minutes;
            }
        }
        return -1;
    }
    boolean isAvailableNow(String availabilityJson, LocalDateTime now) {
        JsonNode windows = parseWindows(availabilityJson);
        if (windows == null || windows.isEmpty()) {
            return true;
        }
        return isAvailableNow(windows, now);
    }
    private JsonNode parseWindows(String availabilityJson) {
        if (availabilityJson == null || availabilityJson.isBlank()) {
            return null;
        }
        try {
            JsonNode windows = mapper.readTree(availabilityJson);
            return windows != null && windows.isArray() ? windows : null;
        } catch (Exception e) {
            return null;
        }
    }
    private boolean isAvailableNow(JsonNode windows, LocalDateTime now) {
        int weekday = now.getDayOfWeek().getValue() - 1;
        int minutes = now.getHour() * 60 + now.getMinute();
        for (JsonNode w : windows) {
            try {
                if (w.get("day").asInt() != weekday) {
                    continue;
                }
                int[] start = parseHm(w.get("start").asText());
                int[] end = parseHm(w.get("end").asText());
                int startM = start[0] * 60 + start[1];
                int endM = end[0] * 60 + end[1];
                if (startM <= endM) {
                    if (startM <= minutes && minutes <= endM) {
                        return true;
                    }
                } else {
                    if (minutes >= startM || minutes <= endM) {
                        return true;
                    }
                }
            } catch (RuntimeException e) {
            }
        }
        return false;
    }
    private static int[] parseHm(String hm) {
        String[] parts = hm.split(":");
        return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
    }
}
