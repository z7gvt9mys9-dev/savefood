package ru.savefood.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.savefood.push.PushDispatchService;
import ru.savefood.telegram.TelegramService;
import ru.savefood.volunteer.AvailabilityService;

class MatchingLoadTest {
    @Test
    void fiveHundredLotEventsHaveBoundedAdmission() throws Exception {
        var limits = new MatchingWorkProperties();
        var jdbc = mock(JdbcTemplate.class);
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        when(jdbc.queryForList(anyString(), anyInt())).thenAnswer(call -> {
            started.countDown();
            release.await();
            return List.of();
        });
        try (var matching = new BoundedWorkExecutor("match-load", new MatchingWorkProperties.ExecutorLimits(2, 3));
             var telegram = new BoundedWorkExecutor("telegram-load", limits.getTelegram())) {
            var service = new NeedsMatchService(jdbc, mock(AvailabilityService.class), mock(TelegramService.class),
                mock(PushDispatchService.class), matching, telegram, limits, mock(MatchingCandidateRepository.class));
            service.startNeedsMatch(1);
            service.startNeedsMatch(2);
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 500; i++) service.startNeedsMatch(i + 3);
            assertThat(matching.queueDepth()).isEqualTo(3);
            assertThat(matching.largestPoolSize()).isEqualTo(2);
            assertThat(matching.rejectedCount()).isEqualTo(497);
            release.countDown();
        }
    }
    @Test
    void slowTelegramDoesNotOccupyMatchingAndNormalPayloadsAndOptInArePreserved() throws Exception {
        var limits = new MatchingWorkProperties();
        limits.setTelegramSends(1);
        var jdbc = mock(JdbcTemplate.class);
        var candidates = mock(MatchingCandidateRepository.class);
        var provider = mock(TelegramService.class);
        var push = mock(PushDispatchService.class);
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(jdbc.queryForList(anyString(), anyInt())).thenReturn(List.of(Map.of(
            "category", "Выпечка", "city", "Алматы", "shop_name", "Shop", "description", "Bread")));
        when(candidates.recipients("Алматы", "Выпечка")).thenReturn(List.of(
            Map.of("needy_id", 1, "preferences", "хлеб", "geo_push_enabled", true),
            Map.of("needy_id", 2, "preferences", "без хлеба", "geo_push_enabled", true),
            Map.of("needy_id", 3, "preferences", "хлеб", "geo_push_enabled", false)));
        when(candidates.volunteers(any(), any(), any())).thenReturn(List.of(
            Map.of("id", 7, "availability", "available")));
        var availability = mock(AvailabilityService.class);
        when(availability.isAvailableNow("available")).thenReturn(true);
        when(provider.getChatIdByRelated("needy", 1)).thenReturn("chat");
        when(provider.sendMessage(eq("chat"), anyString())).thenAnswer(call -> {
            sending.countDown(); release.await(); return true;
        });
        try (var matching = new BoundedWorkExecutor("match-normal", new MatchingWorkProperties.ExecutorLimits(1, 2));
             var telegram = new BoundedWorkExecutor("telegram-slow", new MatchingWorkProperties.ExecutorLimits(1, 1))) {
            var service = new NeedsMatchService(jdbc, availability, provider, push, matching, telegram, limits, candidates);
            service.startNeedsMatch(1);
            assertThat(sending.await(5, TimeUnit.SECONDS)).isTrue();
            CountDownLatch matched = new CountDownLatch(1);
            matching.tryExecute(matched::countDown);
            assertThat(matched.await(5, TimeUnit.SECONDS)).isTrue();
            verify(push).notifyRole(eq("needy"), eq(1), startsWith("□ "), eq("/"), any());
            verify(push).notifyRole(eq("needy"), eq(1), startsWith("В магазине"), eq("/"), any());
            verify(push).notifyRole(eq("needy"), eq(3), startsWith("□ "), eq("/"), any());
            verify(push, never()).notifyRole(eq("needy"), eq(3), startsWith("В магазине"), eq("/"), any());
            verify(push, never()).notifyRole(eq("needy"), eq(2), anyString(), anyString(), any());
            verify(push).notifyRole(eq("volunteer"), eq(7), startsWith("□ "), eq("/"), any());
            for (int i = 0; i < 500; i++) service.startNeedsMatch(i + 2);
            assertThat(telegram.largestPoolSize()).isEqualTo(1);
            assertThat(telegram.queueDepth()).isLessThanOrEqualTo(1);
            assertThat(matching.largestPoolSize()).isEqualTo(1);
            assertThat(matching.queueDepth()).isLessThanOrEqualTo(2);
            release.countDown();
        }
    }
}
