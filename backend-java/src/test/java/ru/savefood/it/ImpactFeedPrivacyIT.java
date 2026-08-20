package ru.savefood.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.savefood.cache.CacheService;
import ru.savefood.esg.EsgService;
import ru.savefood.impact.ImpactController;

class ImpactFeedPrivacyIT extends PostgresIT {

    private final ObjectMapper mapper = new ObjectMapper();
    private ImpactController impact;

    @BeforeEach
    void wire() {
        impact = new ImpactController(jdbc, new EsgService(jdbc), new CacheService(), "/tmp", "/tmp");
    }

    @Test
    void unauthenticatedApprovedPhotoFeedNeverSerializesRecipientFreeText() throws Exception {
        int shopId = insertShop("Shop", 43.238, 76.889);
        int lotId = insertLot(shopId, 2.0, "Выпечка");
        jdbc.update("UPDATE lots SET city = ? WHERE id = ?", "Алматы", lotId);
        int needyId = insertNeedy("Мария Иванова");
        int ticketId = insertFulfilledPhotoTicket(needyId, lotId,
            "Позвоните +7 701 123-45-67. Мария Иванова живёт по адресу: "
                + "ул. Абая, дом 42, кв. 7. Нужна еда из-за диагноза.",
            "/delivery_photos/approved.jpg", "approved");

        // This endpoint deliberately has no authentication parameter; assert the
        // exact response that an anonymous caller receives.
        List<Map<String, Object>> feed = impact.feed(20);

        assertThat(feed).singleElement().satisfies(post -> {
            assertThat(post).containsOnlyKeys("photo", "date", "category", "city");
            assertThat(post).containsEntry("photo", "/impact/delivery_photos/" + ticketId + "/image")
                .containsEntry("category", "Выпечка")
                .containsEntry("city", "Алматы");
            assertThat(post.get("date")).isNotNull();
        });
        String publicJson = mapper.writeValueAsString(feed);
        assertThat(publicJson)
            .doesNotContain("+7 701 123-45-67", "Мария Иванова", "ул. Абая", "дом 42", "диагноза");
    }

    @Test
    void onlyApprovedPhotoAppearsAndItsControlledMetadataIsPreserved() {
        int shopId = insertShop("Shop", 43.238, 76.889);
        int lotId = insertLot(shopId, 2.0, "Овощи/Фрукты");
        int needyId = insertNeedy("Recipient");
        int approvedTicket = insertFulfilledPhotoTicket(needyId, lotId, "arbitrary recipient text",
            "/delivery_photos/approved.jpg", "approved");
        int pendingTicket = insertFulfilledPhotoTicket(needyId, lotId, "another private request",
            "/delivery_photos/pending.jpg", "pending");

        List<Map<String, Object>> feed = impact.feed(20);

        assertThat(feed).singleElement().satisfies(post -> {
            assertThat(post).containsEntry("photo", "/impact/delivery_photos/" + approvedTicket + "/image")
                .containsEntry("category", "Овощи/Фрукты");
            assertThat(post).doesNotContainKey("items");
        });
        assertThat(feed).extracting(post -> post.get("photo"))
            .doesNotContain("/impact/delivery_photos/" + pendingTicket + "/image");
    }

    private int insertFulfilledPhotoTicket(int needyId, int lotId, String items, String photo, String photoStatus) {
        return jdbc.queryForObject(
            "INSERT INTO tickets (needy_id, items, address, lat, lon, lot_id, quantity, status, created_at, "
                + "fulfilled_at, delivery_photo, delivery_photo_status) "
                + "VALUES (?, ?, 'Private address 1', 43.24, 76.90, ?, 1, 'fulfilled', NOW(), NOW(), ?, ?) "
                + "RETURNING id",
            Integer.class, needyId, items, lotId, photo, photoStatus);
    }
}
