package ru.savefood.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import ru.savefood.match.BoundedWorkExecutor;
import ru.savefood.match.MatchingWorkProperties;

class PushDispatchLoadTest {
    @Test
    void slowProviderAndHundredsOfPushesKeepWorkersAndQueueBounded() throws Exception {
        var limits = new MatchingWorkProperties();
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("FROM fcm_tokens"), eq("needy"), anyInt(), eq(5)))
            .thenReturn(List.of(Map.of("token", "device")));
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        CountDownLatch sending = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
            sending.countDown(); release.await(); return response;
        });
        try (var executor = new BoundedWorkExecutor("push-load", new MatchingWorkProperties.ExecutorLimits(2, 3))) {
            var service = configured(jdbc, executor, limits, http);
            service.notifyRole("needy", 1, "<b>hello</b>", "/");
            service.notifyRole("needy", 2, "hello", "/");
            assertThat(sending.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 500; i++) service.notifyRole("needy", i + 3, "hello", "/");
            assertThat(executor.largestPoolSize()).isEqualTo(2);
            assertThat(executor.queueDepth()).isEqualTo(3);
            assertThat(executor.rejectedCount()).isEqualTo(497);
            // Rejected jobs never even query the database.
            verify(jdbc, times(2)).queryForList(anyString(), eq("needy"), anyInt(), eq(5));
            release.countDown();
        }
    }
    @Test
    void oneLotBudgetCapsActualSendsAcrossMultipleRoleJobs() throws Exception {
        var limits = new MatchingWorkProperties();
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("LIMIT ?"), anyString(), anyInt(), anyInt())).thenReturn(
            List.of(Map.of("token", "a"), Map.of("token", "b"), Map.of("token", "c")));
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        try (var executor = new BoundedWorkExecutor("push-budget", new MatchingWorkProperties.ExecutorLimits(1, 10))) {
            var service = configured(jdbc, executor, limits, http);
            var budget = new PushSendBudget(4);
            service.notifyRole("needy", 1, "hello", "/", budget);
            service.notifyRole("volunteer", 2, "hello", "/", budget);
            service.notifyRole("needy", 3, "hello", "/", budget);
            CountDownLatch drained = new CountDownLatch(1);
            executor.tryExecute(drained::countDown);
            assertThat(drained.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(budget.remaining()).isZero();
            verify(http, times(4)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
            verify(jdbc, times(2)).queryForList(contains("LIMIT ?"), anyString(), anyInt(), anyInt());
        }
    }
    public static PushDispatchService configured(JdbcTemplate jdbc, BoundedWorkExecutor executor,
                                                  MatchingWorkProperties limits, HttpClient http) {
        var service = new PushDispatchService(jdbc, executor, limits, "", "", "mailto:test@example.org",
            true, "project", "{}", "");
        ReflectionTestUtils.setField(service, "http", http);
        ReflectionTestUtils.setField(service, "fcmToken", "test-access-token");
        ReflectionTestUtils.setField(service, "fcmTokenExp", Instant.now().plusSeconds(3600));
        return service;
    }
}
