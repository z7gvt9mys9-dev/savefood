package ru.savefood.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import ru.savefood.billing.BillingService;
import ru.savefood.esg.EsgService;
import ru.savefood.forecast.ForecastService;
import ru.savefood.match.*;
import ru.savefood.needy.NeedyService;
import ru.savefood.partner.PartnerApiController;
import ru.savefood.partner.dto.ApiLotIn;
import ru.savefood.push.PushDispatchService;
import ru.savefood.receipt.ReceiptService;
import ru.savefood.security.CurrentUser;
import ru.savefood.security.PasswordService;
import ru.savefood.shop.*;
import ru.savefood.shop.dto.LotCreate;
import ru.savefood.telegram.TelegramService;
import ru.savefood.upload.UploadService;
import ru.savefood.volunteer.AvailabilityService;
import ru.savefood.web.ApiException;
import ru.savefood.web.RateLimiter;
import ru.savefood.webhook.WebhookProperties;
import ru.savefood.webhook.WebhookService;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MatchingBoundsIT extends PostgresIT {
    @Test
    void thousandsOfRowsStillReturnOnlyBoundedCityCandidatesAndNearestVolunteers() {
        jdbc.update("INSERT INTO needy(name, status, created_at) SELECT 'n' || i, 'active', NOW() FROM generate_series(1, 9000) i");
        jdbc.update("INSERT INTO needy_profile(needy_id, city, preferences) SELECT id, "
            + "CASE WHEN id <= 3000 THEN 'other' ELSE 'Алматы' END, "
            + "CASE WHEN id BETWEEN 3001 AND 6000 THEN 'яблоки' ELSE 'хлеб' END FROM needy");
        jdbc.update("INSERT INTO volunteers(name, city, availability, lat, lon, created_at) "
            + "SELECT 'v' || i, CASE WHEN i <= 3000 THEN 'other' ELSE 'Алматы' END, "
            + "CASE WHEN i BETWEEN 3001 AND 6000 THEN '[]' ELSE '[{}]' END, "
            + "43 + (9000-i)*0.001, 76, NOW() FROM generate_series(1,9000) i");
        var limits = new MatchingWorkProperties();
        limits.setRecipientCandidates(17);
        limits.setVolunteerCandidates(13);
        var repository = new MatchingCandidateRepository(jdbc, limits);
        var recipients = repository.recipients("Алматы", "Выпечка");
        assertThat(recipients).hasSize(17);
        assertThat(recipients.getFirst().get("needy_id")).isEqualTo(6001);
        var volunteers = repository.volunteers("Алматы", 43.0, 76.0);
        assertThat(volunteers).hasSize(13);
        assertThat(volunteers.getFirst().get("id")).isEqualTo(9000);
        assertThat(repository.volunteers("Алматы", null, null)).hasSize(13);
        assertThat(repository.recipients(null, "Выпечка")).isEmpty();
        assertThat(repository.volunteers("missing", 43.0, 76.0)).isEmpty();
    }
    @Test
    void normalMatchingBoundsNotificationsAndPreservesAvailabilityAndRestrictions() throws Exception {
        int shopId = insertShop("Shop", 43, 76);
        int lotId = insertLot(shopId, 1, "Выпечка");
        jdbc.update("UPDATE lots SET city = 'Алматы' WHERE id = ?", lotId);
        jdbc.update("INSERT INTO needy(name, status, created_at) SELECT 'n' || i, 'active', NOW() FROM generate_series(1, 100) i");
        jdbc.update("INSERT INTO needy_profile(needy_id, city, preferences) SELECT id, 'Алматы', "
            + "CASE WHEN id = 1 THEN 'без хлеба' ELSE 'хлеб' END FROM needy");
        jdbc.update("INSERT INTO volunteers(name, city, availability, lat, lon, created_at) "
            + "SELECT 'v' || i, 'Алматы', CASE WHEN i = 1 THEN 'unavailable' ELSE 'available' END, "
            + "43, 76, NOW() FROM generate_series(1,100) i");
        var limits = new MatchingWorkProperties();
        limits.setRecipientsNotified(3);
        limits.setVolunteersNotified(2);
        limits.setTelegramSends(2);
        var provider = mock(TelegramService.class);
        when(provider.getChatIdByRelated(anyString(), anyInt())).thenReturn("chat");
        var availability = mock(AvailabilityService.class);
        when(availability.isAvailableNow("available")).thenReturn(true);
        try (var matching = new BoundedWorkExecutor("normal-match", new MatchingWorkProperties.ExecutorLimits(1, 2));
             var telegram = new BoundedWorkExecutor("normal-telegram", limits.getTelegram())) {
            new NeedsMatchService(jdbc, availability, provider, mock(PushDispatchService.class),
                matching, telegram, limits, new MatchingCandidateRepository(jdbc, limits)).startNeedsMatch(lotId);
            CountDownLatch done = new CountDownLatch(1);
            matching.tryExecute(done::countDown);
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.queryForList("SELECT needy_id FROM notifications WHERE type = 'lot_match' ORDER BY needy_id", Integer.class))
                .containsExactly(2, 3, 4);
            assertThat(jdbc.queryForList("SELECT volunteer_id FROM notifications WHERE type = 'lot_nearby' ORDER BY volunteer_id", Integer.class))
                .containsExactly(2, 3);
            verify(provider, timeout(5000).times(2)).sendMessage(eq("chat"), anyString());
            verify(provider, times(2)).getChatIdByRelated(anyString(), anyInt());
        }
    }
    @Test
    void thousandsOfSubscriptionsAreLimitedInBothPushQueries() throws Exception {
        int userId = jdbc.queryForObject("INSERT INTO users(username, hashed_password, role, related_id) "
            + "VALUES ('push-test', 'unused', 'needy', 1) RETURNING id", Integer.class);
        jdbc.update("INSERT INTO fcm_tokens(user_id, role, related_id, token) "
            + "SELECT ?, 'needy', 1, 'device-' || i FROM generate_series(1, 3000) i", userId);
        jdbc.update("INSERT INTO push_subscriptions(user_id, endpoint, p256dh, auth) "
            + "SELECT ?, 'https://localhost/' || i, 'unused', 'unused' FROM generate_series(1,3000) i", userId);
        Map<String, Integer> returned = new java.util.concurrent.ConcurrentHashMap<>();
        var recordingJdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource) {
            @Override public List<Map<String, Object>> queryForList(String sql, Object... args) {
                var rows = super.queryForList(sql, args);
                returned.put(sql.contains("fcm_tokens") ? "fcm" : "web", rows.size());
                return rows;
            }
        };
        var limits = new MatchingWorkProperties();
        limits.setSubscriptionsPerChannel(3);
        var http = mock(java.net.http.HttpClient.class);
        var response = mock(java.net.http.HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(http.send(any(java.net.http.HttpRequest.class), any(java.net.http.HttpResponse.BodyHandler.class))).thenReturn(response);
        try (var executor = new BoundedWorkExecutor("subscription-test", new MatchingWorkProperties.ExecutorLimits(1, 2))) {
            var push = new PushDispatchService(recordingJdbc, executor, limits, "configured", "configured", "mailto:test@example.org",
                true, "project", "{}", "");
            ReflectionTestUtils.setField(push, "http", http);
            ReflectionTestUtils.setField(push, "fcmToken", "test-token");
            ReflectionTestUtils.setField(push, "fcmTokenExp", java.time.Instant.now().plusSeconds(3600));
            push.notifyRole("needy", 1, "hello", "/");
            CountDownLatch done = new CountDownLatch(1);
            executor.tryExecute(done::countDown);
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(returned).containsExactlyInAnyOrderEntriesOf(Map.of("web", 3, "fcm", 3));
            verify(http, times(3)).send(any(java.net.http.HttpRequest.class), any(java.net.http.HttpResponse.BodyHandler.class));
        }
    }
    @Test
    void saturatedMatchingStillPersistsShopAndPartnerLotsAndPartnerLimitUsesShopIdentity() throws Exception {
        int shopId = insertShop("Shop", 43.0, 76.0);
        var limits = new MatchingWorkProperties();
        limits.setPartnerCreatesPerMinute(2);
        var repo = new ShopRepository(jdbc);
        var billing = new BillingService(jdbc);
        var inventory = new ShopService(jdbc, repo, billing, mock(NeedyService.class),
            mock(PasswordService.class), new UploadService(), new LotUploadCleanup(jdbc, "/tmp"));
        CountDownLatch release = new CountDownLatch(1);
        try (var matching = new BoundedWorkExecutor("creation-match", new MatchingWorkProperties.ExecutorLimits(1, 1));
             var telegram = new BoundedWorkExecutor("creation-telegram", limits.getTelegram())) {
            matching.tryExecute(() -> {
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            matching.tryExecute(() -> {});
            var service = new NeedsMatchService(jdbc, mock(AvailabilityService.class), mock(TelegramService.class),
                mock(PushDispatchService.class), matching, telegram, limits, new MatchingCandidateRepository(jdbc, limits));
            var shop = new ShopController(repo, inventory, billing, mock(ReceiptService.class), mock(ForecastService.class),
                mock(EsgService.class), mock(WebhookService.class), service, new UploadService(), new RateLimiter(),
                mock(LotPhotoReferenceService.class), "/tmp", "/tmp");
            var shopResult = shop.createLot(shopId,
                new LotCreate("bread", 1.0, "кг", 1.0, null, null, null, null, "Выпечка", null, false),
                new CurrentUser(1, "shop", "shop", shopId));
            assertThat(shopResult.get("id")).isNotNull();
            var authJdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
            when(authJdbc.queryForList(contains("FROM api_keys"), anyString()))
                .thenReturn(List.of(Map.of("id", 1, "shop_id", shopId)));
            var partner = new PartnerApiController(authJdbc, mock(BillingService.class), mock(EsgService.class),
                service, inventory, repo, new WebhookProperties());
            ReflectionTestUtils.setField(partner, "lotCreateRateLimiter", new RateLimiter());
            ReflectionTestUtils.setField(partner, "matchingLimits", limits);
            var lot = new ApiLotIn("bread", 1.0, "Выпечка", null, null, null, null);
            partner.createLot(lot, "key-1");
            partner.createLot(lot, "key-2");
            assertThatThrownBy(() -> partner.createLot(lot, "key-3"))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(429));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lots WHERE shop_id = ?", Integer.class, shopId)).isEqualTo(3);
            assertThat(matching.rejectedCount()).isEqualTo(3);
            assertThat(matching.queueDepth()).isEqualTo(1);
            release.countDown();
        }
    }
}
