package ru.savefood.volunteer;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RoutePointPrivacyTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void redactsRecipientDeliveryFieldsButKeepsOutcomeAndStatisticsKeys() throws Exception {
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("kind", "ticket");
        point.put("ticket_id", 41);
        point.put("done", true);
        point.put("attempt_count", 2);
        point.put("unknown_legacy_private_field", "private");
        for (String field : RoutePointPrivacy.SENSITIVE_TICKET_FIELDS) {
            point.put(field, "private");
        }

        assertThat(RoutePointPrivacy.redactTicketPoint(point)).isTrue();

        assertThat(point).containsEntry("ticket_id", 41)
            .containsEntry("done", true)
            .containsEntry("attempt_count", 2);
        assertThat(point.keySet()).doesNotContainAnyElementsOf(RoutePointPrivacy.SENSITIVE_TICKET_FIELDS);
        assertThat(point).doesNotContainKey("unknown_legacy_private_field");
    }

    @Test
    void terminalRouteSerializerPreservesShopNavigationFactsAndDiscardsMalformedJson() throws Exception {
        String redacted = RoutePointPrivacy.redactAllTicketPointsJson("""
            [{"kind":"shop","lat":43.2,"lon":76.9,"description":"Store"},
             {"kind":"ticket","ticket_id":7,"lat":43.3,"lon":76.8,"address":"Home","done":true}]
            """);
        List<Map<String, Object>> points = mapper.readValue(redacted, new TypeReference<>() { });

        assertThat(points.get(0)).containsEntry("lat", 43.2).containsEntry("description", "Store");
        assertThat(points.get(1)).containsEntry("ticket_id", 7).containsEntry("done", true);
        assertThat(points.get(1).keySet()).doesNotContainAnyElementsOf(RoutePointPrivacy.SENSITIVE_TICKET_FIELDS);
        assertThat(RoutePointPrivacy.redactAllTicketPointsJson("{private malformed json"))
            .isEqualTo("[]");
    }
}
