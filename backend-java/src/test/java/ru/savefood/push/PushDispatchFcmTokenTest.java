package ru.savefood.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import ru.savefood.match.BoundedWorkExecutor;
import ru.savefood.match.MatchingWorkProperties;

class PushDispatchFcmTokenTest {
    private final List<BoundedWorkExecutor> executors = new ArrayList<>();

    @AfterEach
    void closeExecutors() {
        executors.forEach(BoundedWorkExecutor::close);
    }

    @Test
    void endpointErrorIsNotCachedAndNextAttemptSucceeds() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> failed = response(500, "{\"error\":\"unavailable\"}");
        HttpResponse<String> recovered = response(200, validToken("recovered"));
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(failed, recovered);
        PushDispatchService service = service(http);

        assertThatThrownBy(() -> accessToken(service)).hasMessageContaining("HTTP 500");
        assertThat(accessToken(service)).isEqualTo("recovered");
        verify(http, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void missingAndBlankTokensAreNotCached() throws Exception {
        assertMalformedThenRecovered("{\"expires_in\":3600}");
        assertMalformedThenRecovered("{\"access_token\":\"   \",\"expires_in\":3600}");
    }

    @Test
    void invalidExpiryIsNotCached() throws Exception {
        assertMalformedThenRecovered("{\"access_token\":\"bad\"}");
        assertMalformedThenRecovered("{\"access_token\":\"bad\",\"expires_in\":0}");
        assertMalformedThenRecovered("{\"access_token\":\"bad\",\"expires_in\":-1}");
        assertMalformedThenRecovered("{\"access_token\":\"bad\",\"expires_in\":3600.5}");
    }

    @Test
    void validTokenIsCachedNormally() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> cached = response(200, validToken("cached"));
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(cached);
        PushDispatchService service = service(http);

        assertThat(accessToken(service)).isEqualTo("cached");
        assertThat(accessToken(service)).isEqualTo("cached");
        verify(http).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void concurrentCallersShareOneSuccessfulRefresh() throws Exception {
        HttpClient http = mock(HttpClient.class);
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
            requestStarted.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("token request timed out");
            return response(200, validToken("shared"));
        });
        PushDispatchService service = service(http);
        ExecutorService callers = Executors.newFixedThreadPool(6);
        try {
            Future<String>[] results = new Future[6];
            for (int i = 0; i < results.length; i++) {
                results[i] = callers.submit(() -> accessToken(service));
            }
            assertThat(requestStarted.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            for (Future<String> result : results) {
                assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo("shared");
            }
        } finally {
            callers.shutdownNow();
        }
        verify(http).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    private void assertMalformedThenRecovered(String malformed) throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> malformedResponse = response(200, malformed);
        HttpResponse<String> recovered = response(200, validToken("recovered"));
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(malformedResponse, recovered);
        PushDispatchService service = service(http);
        assertThatThrownBy(() -> accessToken(service));
        assertThat(ReflectionTestUtils.getField(service, "fcmToken")).isNull();
        assertThat(accessToken(service)).isEqualTo("recovered");
        verify(http, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    private PushDispatchService service(HttpClient http) throws Exception {
        KeyPairGenerator keys = KeyPairGenerator.getInstance("RSA");
        keys.initialize(2048);
        String privateKey = "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(
                keys.generateKeyPair().getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----";
        String credentials = new ObjectMapper().writeValueAsString(Map.of(
            "client_email", "push-test@example.test",
            "token_uri", "https://oauth2.example.test/token",
            "private_key", privateKey));
        BoundedWorkExecutor executor = new BoundedWorkExecutor(
            "fcm-token-test", new MatchingWorkProperties.ExecutorLimits(1, 1));
        executors.add(executor);
        PushDispatchService service = new PushDispatchService(mock(JdbcTemplate.class),
            executor, new MatchingWorkProperties(), "", "", "mailto:test@example.test",
            true, "project", credentials, "");
        ReflectionTestUtils.setField(service, "http", http);
        return service;
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }

    private static String validToken(String token) {
        return "{\"access_token\":\"" + token + "\",\"expires_in\":3600}";
    }

    private static String accessToken(PushDispatchService service) {
        return ReflectionTestUtils.invokeMethod(service, "fcmAccessToken");
    }
}
