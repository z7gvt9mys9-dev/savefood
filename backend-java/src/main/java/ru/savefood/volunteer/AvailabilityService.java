package ru.savefood.volunteer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Port of backend/volunteer/db.py is_available_now (§54). True if {@code now}
 * (local) falls in any weekly window. An empty/missing calendar means «always
 * available» — the platform must not hide a volunteer who never filled it in.
 */
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

    boolean isAvailableNow(String availabilityJson, LocalDateTime now) {
        JsonNode windows;
        if (availabilityJson == null || availabilityJson.isBlank()) {
            return true;
        }
        try {
            windows = mapper.readTree(availabilityJson);
        } catch (Exception e) {
            return true; // unparseable → treat as "always available", like the source
        }
        if (windows == null || !windows.isArray() || windows.isEmpty()) {
            return true;
        }

        int weekday = now.getDayOfWeek().getValue() - 1; // Mon=0 … Sun=6
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
                } else { // overnight window
                    if (minutes >= startM || minutes <= endM) {
                        return true;
                    }
                }
            } catch (RuntimeException e) {
                // malformed window entry — skip it, mirroring the Python except
            }
        }
        return false;
    }

    private static int[] parseHm(String hm) {
        String[] parts = hm.split(":");
        return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
    }
}
