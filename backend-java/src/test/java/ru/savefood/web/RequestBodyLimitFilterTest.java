package ru.savefood.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import ru.savefood.telegram.TelegramUpdateInbox;

class RequestBodyLimitFilterTest {
    private static final String SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token";
    private final RequestBodyLimitFilter filter = new RequestBodyLimitFilter("telegram-secret");

    @Test
    void normalJsonPassesWithItsBodyIntact() throws Exception {
        var request = jsonRequest("/shops/register", "{\"name\":\"shop\"}");
        var response = new MockHttpServletResponse();
        var bodySeen = new AtomicReference<String>();

        filter.doFilter(request, response, (filtered, ignored) -> bodySeen.set(
            new String(filtered.getInputStream().readAllBytes(), StandardCharsets.UTF_8)));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(bodySeen).hasValue("{\"name\":\"shop\"}");
    }

    @Test
    void oversizedOrdinaryJsonIsRejectedBeforeDispatch() throws Exception {
        var request = jsonRequest("/shops/register",
            "x".repeat(RequestBodyLimitFilter.MAX_JSON_BODY_BYTES + 1));
        var response = new MockHttpServletResponse();
        var dispatched = new AtomicBoolean();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> dispatched.set(true));

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(dispatched).isFalse();
    }

    @Test
    void validTelegramUpdatePassesAdmissionBeforeControllerDispatch() throws Exception {
        var request = jsonRequest(RequestBodyLimitFilter.TELEGRAM_PATH, "{\"update_id\":1}");
        request.addHeader(SECRET_HEADER, "telegram-secret");
        var response = new MockHttpServletResponse();
        var bodySeen = new AtomicReference<String>();

        filter.doFilter(request, response, (filtered, ignored) -> bodySeen.set(
            new String(filtered.getInputStream().readAllBytes(), StandardCharsets.UTF_8)));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(bodySeen).hasValue("{\"update_id\":1}");
    }

    @Test
    void invalidTelegramSecretReturnsWithoutReadingBodyOrDispatching() throws Exception {
        MockHttpServletRequest request = spy(jsonRequest(
            RequestBodyLimitFilter.TELEGRAM_PATH, "x".repeat(TelegramUpdateInbox.MAX_PAYLOAD_BYTES + 1)));
        request.addHeader(SECRET_HEADER, "wrong");
        var response = new MockHttpServletResponse();
        var dispatched = new AtomicBoolean();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> dispatched.set(true));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(dispatched).isFalse();
        verify(request, never()).getInputStream();
    }

    @Test
    void oversizedTelegramContentLengthIsRejectedBeforeDispatch() throws Exception {
        var request = jsonRequest(RequestBodyLimitFilter.TELEGRAM_PATH,
            "x".repeat(TelegramUpdateInbox.MAX_PAYLOAD_BYTES + 1));
        request.addHeader(SECRET_HEADER, "telegram-secret");
        var response = new MockHttpServletResponse();
        var dispatched = new AtomicBoolean();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> dispatched.set(true));

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(dispatched).isFalse();
    }

    @Test
    void chunkedOversizedTelegramBodyIsReadOnlyToTheHardLimit() throws Exception {
        byte[] body = "x".repeat(TelegramUpdateInbox.MAX_PAYLOAD_BYTES + 1)
            .getBytes(StandardCharsets.UTF_8);
        var request = new MockHttpServletRequest("POST", RequestBodyLimitFilter.TELEGRAM_PATH) {
            @Override public long getContentLengthLong() { return -1; }
        };
        request.setContentType("application/json");
        request.setContent(body);
        request.addHeader(SECRET_HEADER, "telegram-secret");
        var response = new MockHttpServletResponse();
        var dispatched = new AtomicBoolean();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> dispatched.set(true));

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(dispatched).isFalse();
    }

    @Test
    void unsetTelegramSecretFailsClosedBeforeDispatch() throws ServletException, IOException {
        var request = jsonRequest(RequestBodyLimitFilter.TELEGRAM_PATH, "{\"update_id\":1}");
        request.addHeader(SECRET_HEADER, "anything");
        var response = new MockHttpServletResponse();
        var dispatched = new AtomicBoolean();

        new RequestBodyLimitFilter("").doFilter(request, response,
            (ignoredRequest, ignoredResponse) -> dispatched.set(true));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(dispatched).isFalse();
    }

    private static MockHttpServletRequest jsonRequest(String path, String body) {
        var request = new MockHttpServletRequest("POST", path);
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
