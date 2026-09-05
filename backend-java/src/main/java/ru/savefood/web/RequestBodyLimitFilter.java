package ru.savefood.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.savefood.telegram.TelegramUpdateInbox;

/** Bounds JSON before MVC/Jackson and admits authenticated Telegram updates before controller dispatch. */
@Component
public class RequestBodyLimitFilter extends OncePerRequestFilter {
    static final int MAX_JSON_BODY_BYTES = 1024 * 1024;
    static final String TELEGRAM_PATH = "/telegram/webhook";
    private static final Logger log = Logger.getLogger(RequestBodyLimitFilter.class.getName());
    private static final String TELEGRAM_SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token";
    private final String telegramSecret;

    public RequestBodyLimitFilter(
            @Value("${savefood.telegram.webhook-secret:}") String telegramSecret) {
        this.telegramSecret = telegramSecret == null ? "" : telegramSecret.strip();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        boolean telegram = TELEGRAM_PATH.equals(request.getRequestURI());
        if (telegram && !telegramAuthenticated(request)) {
            log.warning(telegramSecret.isEmpty()
                ? "[telegram] webhook hit but TELEGRAM_WEBHOOK_SECRET is unset — update ignored"
                : "[telegram] webhook secret mismatch — update ignored");
            writeJson(response, HttpServletResponse.SC_OK, "{\"ok\":true}");
            return;
        }

        if (!telegram && !isJson(request.getContentType())) {
            chain.doFilter(request, response);
            return;
        }

        int limit = telegram ? TelegramUpdateInbox.MAX_PAYLOAD_BYTES : MAX_JSON_BODY_BYTES;
        if (request.getContentLengthLong() > limit) {
            writeJson(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                "{\"error\":\"Request body too large\"}");
            return;
        }
        byte[] body = request.getInputStream().readNBytes(limit + 1);
        if (body.length > limit) {
            writeJson(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                "{\"error\":\"Request body too large\"}");
            return;
        }
        chain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private boolean telegramAuthenticated(HttpServletRequest request) {
        if (telegramSecret.isEmpty()) return false;
        String provided = request.getHeader(TELEGRAM_SECRET_HEADER);
        if (provided == null) return false;
        return MessageDigest.isEqual(telegramSecret.getBytes(StandardCharsets.UTF_8),
            provided.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isJson(String contentType) {
        if (contentType == null) return false;
        String mediaType = contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        return "application/json".equals(mediaType) || mediaType.endsWith("+json");
    }

    private static void writeJson(HttpServletResponse response, int status, String body)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(body);
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("Async reads are not supported");
                }
                @Override public int read() { return input.read(); }
                @Override public int read(byte[] bytes, int off, int len) {
                    return input.read(bytes, off, len);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
