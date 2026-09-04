package ru.savefood.needy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import ru.savefood.security.CurrentUser;
import ru.savefood.security.JwtService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
@Component
public class NeedyWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = Logger.getLogger(NeedyWebSocketHandler.class.getName());
    private static final int MAX_WS_PER_USER = 3;
    private static final long AUTH_TIMEOUT_SECONDS = 5;
    private static final long POLL_SECONDS = 3;
    private final JwtService jwtService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<Integer, AtomicInteger> connections = new ConcurrentHashMap<>();
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "needy-ws");
        t.setDaemon(true);
        return t;
    });
    public NeedyWebSocketHandler(JwtService jwtService, JdbcTemplate jdbc) {
        this.jwtService = jwtService;
        this.jdbc = jdbc;
    }
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        Integer needyId = parseNeedyId(session.getUri());
        if (needyId == null) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        AtomicInteger counter = connections.computeIfAbsent(needyId, k -> new AtomicInteger());
        if (counter.get() >= MAX_WS_PER_USER) {
            session.close(new CloseStatus(CloseStatus.POLICY_VIOLATION.getCode(), "Too many connections"));
            return;
        }
        counter.incrementAndGet();
        SessionState state = new SessionState(needyId);
        sessions.put(session.getId(), state);
        state.authTimeout = scheduler.schedule(() -> {
            if (!state.authenticated) {
                closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            }
        }, AUTH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        SessionState state = sessions.get(session.getId());
        if (state == null) {
            return;
        }
        if (state.authenticated) {
            return;
        }
        JsonNode first;
        try {
            first = mapper.readTree(message.getPayload());
        } catch (Exception e) {
            closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        if (!first.isObject() || !"auth".equals(first.path("type").asText())) {
            closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        String token = first.path("token").asText(null);
        CurrentUser user = token == null ? null : jwtService.decode(token);
        user = currentUser(user);
        String role = user == null ? null : user.role();
        boolean ok = user != null && ("admin".equals(role)
            || ("needy".equals(role) && Integer.valueOf(state.needyId).equals(user.relatedId())));
        if (!ok) {
            closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        long lastId;
        JsonNode since = first.get("since_id");
        if (since != null && since.isInt() && since.asInt() >= 0) {
            lastId = since.asInt();
        } else {
            lastId = currentMaxId(state.needyId);
        }
        state.lastId = lastId;
        state.authenticated = true;
        if (state.authTimeout != null) {
            state.authTimeout.cancel(false);
        }
        if (!send(session, Map.of("type", "ready", "last_id", lastId))) {
            return;
        }
        state.poll = scheduler.scheduleWithFixedDelay(
            () -> pollOnce(session, state), POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
    }
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        SessionState state = sessions.remove(session.getId());
        if (state == null) {
            return;
        }
        if (state.authTimeout != null) {
            state.authTimeout.cancel(false);
        }
        if (state.poll != null) {
            state.poll.cancel(false);
        }
        AtomicInteger counter = connections.get(state.needyId);
        if (counter != null) {
            counter.updateAndGet(v -> Math.max(0, v - 1));
        }
    }
    private void pollOnce(WebSocketSession session, SessionState state) {
        if (!session.isOpen()) {
            return;
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, type, payload FROM notifications WHERE needy_id = ? AND id > ? ORDER BY id ASC",
                state.needyId, state.lastId);
            for (Map<String, Object> row : rows) {
                long id = ((Number) row.get("id")).longValue();
                state.lastId = id;
                if (!send(session, Map.of(
                        "id", id,
                        "type", row.get("type") == null ? "" : row.get("type"),
                        "payload", row.get("payload") == null ? "" : row.get("payload")))) {
                    return;
                }
            }
        } catch (RuntimeException e) {
            log.warning("[needy-ws] poll for needy " + state.needyId + " failed: " + e.getMessage());
        }
    }
    /** @return true if the frame was sent; false (and closes the socket) on failure. */
    private boolean send(WebSocketSession session, Map<String, Object> payload) {
        try {
            String json = mapper.writeValueAsString(payload);
            synchronized (session) {
                if (!session.isOpen()) {
                    return false;
                }
                session.sendMessage(new TextMessage(json));
            }
            return true;
        } catch (IOException e) {
            closeQuietly(session, CloseStatus.SERVER_ERROR);
            return false;
        }
    }
    private CurrentUser currentUser(CurrentUser tokenUser) {
        if (tokenUser == null) {
            return null;
        }
        List<CurrentUser> rows = jdbc.query(
            "SELECT username, role, related_id FROM users WHERE id = ? AND NOT is_blocked",
            (rs, n) -> new CurrentUser(tokenUser.userId(), rs.getString("username"),
                rs.getString("role"), rs.getObject("related_id") instanceof Number id ? id.intValue() : null),
            tokenUser.userId());
        return rows.isEmpty() ? null : rows.get(0);
    }
    private long currentMaxId(int needyId) {
        Long max = jdbc.queryForObject(
            "SELECT COALESCE(MAX(id), 0) FROM notifications WHERE needy_id = ?", Long.class, needyId);
        return max == null ? 0L : max;
    }
    private static Integer parseNeedyId(URI uri) {
        if (uri == null) {
            return null;
        }
        String path = uri.getPath();
        int slash = path.lastIndexOf('/');
        if (slash < 0 || slash == path.length() - 1) {
            return null;
        }
        try {
            return Integer.parseInt(path.substring(slash + 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }
    private static void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException ignored) {
        }
    }
    private static final class SessionState {
        final int needyId;
        volatile boolean authenticated;
        volatile long lastId;
        volatile ScheduledFuture<?> authTimeout;
        volatile ScheduledFuture<?> poll;
        SessionState(int needyId) {
            this.needyId = needyId;
        }
    }
}
