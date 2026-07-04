package ru.savefood.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import ru.savefood.ai.AiService;
import ru.savefood.proxy.ProxyService;
import ru.savefood.util.Html;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Java port of backend/telegram_routes.py — the inbound side of the bot that the
 * migration had left behind (the Python webhook/aiogram poller used to fill the
 * link/login tokens; without it «Подключить Telegram» never completed and the bot
 * was silent). Long-polls {@code getUpdates} on a daemon thread and handles:
 *
 * <ul>
 *   <li>{@code /start link_<token>} — attach this chat to the SaveFood account
 *       that created the token via {@code /auth/telegram/init-link};</li>
 *   <li>{@code /start login_<token>} — confirm a pending browser login
 *       ({@code /auth/telegram/login/start} + {@code /login/poll});</li>
 *   <li>{@code /help}, {@code /status}, {@code /chat}, {@code /unlink};</li>
 *   <li>free text — relayed to the counterpart of the active delivery
 *       (volunteer ↔ recipient), otherwise answered by the AI assistant with
 *       escalation to SUPPORT_CHAT_ID.</li>
 * </ul>
 *
 * <p>Enabled when {@code TELEGRAM_POLLING=true} (default) and a bot token is set.
 * Telegram API calls go through the optional VLESS SOCKS5 proxy, mirroring
 * {@link TelegramService}.
 */
@Service
public class TelegramBotService {

    private static final Logger log = Logger.getLogger(TelegramBotService.class.getName());

    private static final int POLL_TIMEOUT_SECONDS = 25;

    private static final String HELP_TEXT = """
        📋 <b>Команды SaveFood-бота</b>

        /start — добро пожаловать / привязать аккаунт
        /help — это сообщение
        /status — проверить, привязан ли аккаунт и текущий статус
        /chat — как переписываться с волонтёром или получателем
        /unlink — отвязать Telegram от аккаунта SaveFood

        💬 Просто отправьте текст — он будет переслан вашему волонтёру/получателю \
        при наличии активного маршрута.
        🤖 Если активной доставки нет — на вопрос ответит ИИ-помощник, \
        а сложные вопросы он передаст администратору.""";

    private final JdbcTemplate jdbc;
    private final TelegramService telegramService;
    private final ProxyService proxyService;
    private final AiService aiService;
    private final ObjectMapper mapper = new ObjectMapper();

    private final String botToken;
    private final boolean pollingEnabled;
    private final String siteUrl;
    private final String supportChatId;

    private volatile boolean running;
    private Thread pollThread;

    public TelegramBotService(JdbcTemplate jdbc, TelegramService telegramService,
                              ProxyService proxyService, AiService aiService,
                              @Value("${savefood.oauth.telegram-bot-token:}") String botToken,
                              @Value("${savefood.telegram-polling:true}") boolean pollingEnabled,
                              @Value("${savefood.oauth.public-url:http://localhost}") String siteUrl,
                              @Value("${savefood.support-chat-id:}") String supportChatId) {
        this.jdbc = jdbc;
        this.telegramService = telegramService;
        this.proxyService = proxyService;
        this.aiService = aiService;
        this.botToken = botToken == null ? "" : botToken;
        this.pollingEnabled = pollingEnabled;
        this.siteUrl = siteUrl.replaceAll("/+$", "");
        this.supportChatId = supportChatId == null ? "" : supportChatId;
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────────

    @PostConstruct
    void start() {
        if (!pollingEnabled || botToken.isEmpty()) {
            log.info("[telegram] Bot polling disabled (TELEGRAM_POLLING=false or no token)");
            return;
        }
        running = true;
        pollThread = new Thread(this::pollLoop, "telegram-bot-poll");
        pollThread.setDaemon(true);
        pollThread.start();
        log.info("[telegram] Bot long-polling started");
    }

    @PreDestroy
    void stop() {
        running = false;
        if (pollThread != null) {
            pollThread.interrupt();
        }
    }

    private void pollLoop() {
        registerCommands();
        // Drop the webhook in case one was configured earlier — getUpdates and a
        // webhook are mutually exclusive on Telegram's side.
        api("deleteWebhook", Map.of("drop_pending_updates", false));
        long offset = 0;
        while (running) {
            try {
                Map<String, Object> req = new LinkedHashMap<>();
                req.put("timeout", POLL_TIMEOUT_SECONDS);
                req.put("allowed_updates", List.of("message"));
                if (offset > 0) {
                    req.put("offset", offset);
                }
                JsonNode resp = api("getUpdates", req);
                if (resp == null || !resp.path("ok").asBoolean(false)) {
                    Thread.sleep(3000);
                    continue;
                }
                for (JsonNode update : resp.path("result")) {
                    offset = Math.max(offset, update.path("update_id").asLong() + 1);
                    try {
                        handleUpdate(update);
                    } catch (Exception e) {
                        log.log(Level.WARNING, "[telegram] update handling failed", e);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warning("[telegram] poll error: " + e.getMessage());
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Push the command menu to Telegram so it shows up in the client UI. */
    private void registerCommands() {
        api("setMyCommands", Map.of("commands", List.of(
            Map.of("command", "start", "description", "Добро пожаловать / привязать аккаунт"),
            Map.of("command", "help", "description", "Список команд"),
            Map.of("command", "status", "description", "Статус аккаунта и активных задач"),
            Map.of("command", "chat", "description", "Как переписываться через бота"),
            Map.of("command", "unlink", "description", "Отвязать Telegram от SaveFood"))));
    }

    // ── Update dispatch ──────────────────────────────────────────────────────────

    private void handleUpdate(JsonNode update) {
        JsonNode message = update.path("message");
        String text = message.path("text").asText("");
        String chatId = message.path("chat").path("id").asText("");
        if (chatId.isEmpty() || text.isEmpty()) {
            return;
        }
        String trimmed = text.strip();
        if (trimmed.startsWith("/start")) {
            handleStart(chatId, trimmed.length() > 6 ? trimmed.substring(6).strip() : "");
        } else if (trimmed.startsWith("/help")) {
            reply(chatId, HELP_TEXT);
        } else if (trimmed.startsWith("/status")) {
            handleStatus(chatId);
        } else if (trimmed.startsWith("/unlink")) {
            handleUnlink(chatId);
        } else if (trimmed.startsWith("/chat")) {
            reply(chatId, "💬 Просто напишите сообщение в этот чат — оно будет переслано "
                + "волонтёру/получателю, если у вас есть активный маршрут.");
        } else if (trimmed.startsWith("/")) {
            reply(chatId, "❓ Неизвестная команда. Введите /help чтобы посмотреть список команд.");
        } else {
            handleRelay(chatId, trimmed);
        }
    }

    // ── /start (welcome, link_<token>, login_<token>) ────────────────────────────

    private void handleStart(String chatId, String args) {
        if (args.startsWith("link_")) {
            handleLink(chatId, args.substring(5));
            return;
        }
        if (args.startsWith("login_")) {
            handleLogin(chatId, args.substring(6));
            return;
        }
        reply(chatId, "👋 Добро пожаловать в <b>SaveFood</b>!\n\n"
            + "Мы соединяем магазины, волонтёров и нуждающихся "
            + "в единую систему распределения еды.\n\n"
            + "🔗 <a href=\"" + siteUrl + "\">Открыть платформу</a>\n\n"
            + "Войдите в аккаунт и подключите Telegram в настройках профиля "
            + "для получения уведомлений.");
    }

    private void handleLink(String chatId, String token) {
        List<Integer> userIds = jdbc.query(
            "SELECT user_id FROM telegram_link_tokens "
                + "WHERE token = ? AND created_at >= NOW() - INTERVAL '10 minutes'",
            (rs, n) -> rs.getInt("user_id"), token);
        if (userIds.isEmpty()) {
            reply(chatId, "❌ Ссылка устарела или недействительна. "
                + "Создайте новую в настройках профиля.");
            return;
        }
        jdbc.update("UPDATE users SET telegram_chat_id = ? WHERE id = ?", chatId, userIds.get(0));
        jdbc.update("DELETE FROM telegram_link_tokens WHERE token = ?", token);
        reply(chatId, "✅ <b>Telegram успешно подключён</b> к вашему аккаунту SaveFood!\n\n"
            + "Теперь вы будете получать уведомления о доставках прямо сюда.");
    }

    private void handleLogin(String chatId, String token) {
        Map<String, Object> user = findUserByChat(chatId);
        if (user == null) {
            reply(chatId, "❌ Этот Telegram не привязан ни к одному аккаунту SaveFood.\n"
                + "Войдите на платформу по паролю и нажмите «Подключить Telegram» в профиле — "
                + "после этого вход через Telegram заработает.");
            return;
        }
        int confirmed = jdbc.update(
            "UPDATE telegram_login_tokens SET user_id = ? "
                + "WHERE token = ? AND user_id IS NULL "
                + "AND created_at >= NOW() - INTERVAL '10 minutes'",
            user.get("id"), token);
        if (confirmed > 0) {
            reply(chatId, "✅ Вход подтверждён для аккаунта <b>"
                + Html.escape((String) user.get("username")) + "</b>.\n"
                + "Вернитесь на вкладку с сайтом — вы уже вошли.");
        } else {
            reply(chatId, "❌ Ссылка для входа устарела или уже использована. Начните вход заново.");
        }
    }

    // ── /status ──────────────────────────────────────────────────────────────────

    private void handleStatus(String chatId) {
        Map<String, Object> user = findUserByChat(chatId);
        if (user == null) {
            reply(chatId, "❌ Аккаунт <b>не привязан</b>.\n\n"
                + "Войдите на <a href=\"" + siteUrl + "\">платформу</a> "
                + "и подключите Telegram в настройках профиля.");
            return;
        }
        Map<String, String> roleLabels = Map.of(
            "shop", "Магазин", "volunteer", "Волонтёр", "needy", "Получатель", "admin", "Администратор");
        String role = (String) user.get("role");
        Integer relatedId = (Integer) user.get("related_id");
        List<String> lines = new ArrayList<>();
        lines.add("✅ Аккаунт привязан");
        lines.add("👤 " + Html.escape((String) user.get("username"))
            + " · " + roleLabels.getOrDefault(role, role));

        if ("volunteer".equals(role) && relatedId != null) {
            List<Integer> routes = jdbc.query(
                "SELECT id FROM volunteer_routes WHERE volunteer_id = ? AND status = 'in_progress' "
                    + "ORDER BY started_at DESC LIMIT 1",
                (rs, n) -> rs.getInt("id"), relatedId);
            lines.add(routes.isEmpty() ? "🟢 Нет активного маршрута"
                : "🚗 Активный маршрут #" + routes.get(0));
        } else if ("needy".equals(role) && relatedId != null) {
            List<Map<String, Object>> tickets = jdbc.query(
                "SELECT id, status FROM tickets WHERE needy_id = ? AND status IN ('open','assigned') LIMIT 1",
                (rs, n) -> Map.of("id", (Object) rs.getInt("id"), "status", (Object) rs.getString("status")),
                relatedId);
            if (tickets.isEmpty()) {
                lines.add("🟢 Нет активных заявок");
            } else {
                String statusLabel = "assigned".equals(tickets.get(0).get("status"))
                    ? "назначен волонтёр" : "ожидает";
                lines.add("📦 Активная заявка #" + tickets.get(0).get("id") + " — " + statusLabel);
            }
        } else if ("shop".equals(role) && relatedId != null) {
            Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM lots WHERE shop_id = ? AND status = 'active'",
                Integer.class, relatedId);
            lines.add("🏪 Активных лотов: " + (cnt == null ? 0 : cnt));
        }
        reply(chatId, String.join("\n", lines));
    }

    // ── /unlink ──────────────────────────────────────────────────────────────────

    private void handleUnlink(String chatId) {
        List<String> usernames = jdbc.query(
            "UPDATE users SET telegram_chat_id = NULL WHERE telegram_chat_id = ? RETURNING username",
            (rs, n) -> rs.getString("username"), chatId);
        if (usernames.isEmpty()) {
            reply(chatId, "❓ Этот чат не был привязан ни к одному аккаунту.");
        } else {
            reply(chatId, "✅ Telegram отвязан от аккаунта <b>" + Html.escape(usernames.get(0))
                + "</b>.\nУведомления больше не будут приходить сюда.");
        }
    }

    // ── Free text: relay to the delivery counterpart or the AI assistant ─────────

    private void handleRelay(String chatId, String text) {
        Map<String, Object> sender = findUserByChat(chatId);
        if (sender == null) {
            aiAnswerOrEscalate(chatId, text, null);
            return;
        }
        String role = (String) sender.get("role");
        Integer relatedId = (Integer) sender.get("related_id");
        String senderName = (String) sender.get("username");

        if ("volunteer".equals(role) && relatedId != null) {
            List<String> pointsJson = jdbc.query(
                "SELECT points FROM volunteer_routes WHERE volunteer_id = ? AND status = 'in_progress' "
                    + "ORDER BY started_at DESC LIMIT 1",
                (rs, n) -> rs.getString("points"), relatedId);
            if (pointsJson.isEmpty()) {
                aiAnswerOrEscalate(chatId, text, sender);
                return;
            }
            List<Integer> needyIds = pendingNeedyIds(pointsJson.get(0));
            if (needyIds.isEmpty()) {
                reply(chatId, "Нет активных получателей для пересылки.");
                return;
            }
            String msg = "💬 Волонтёр " + Html.escape(senderName) + ": " + Html.escape(text);
            needyIds.stream().distinct().forEach(nid -> telegramService.notifyNeedy(nid, msg));
            reply(chatId, "✅ Сообщение отправлено");
            return;
        }

        if ("needy".equals(role) && relatedId != null) {
            List<Integer> volunteerIds = jdbc.query(
                "SELECT assigned_volunteer_id FROM tickets "
                    + "WHERE needy_id = ? AND status = 'assigned' AND assigned_volunteer_id IS NOT NULL LIMIT 1",
                (rs, n) -> rs.getInt("assigned_volunteer_id"), relatedId);
            if (volunteerIds.isEmpty()) {
                aiAnswerOrEscalate(chatId, text, sender);
                return;
            }
            telegramService.notifyVolunteer(volunteerIds.get(0),
                "💬 Получатель " + Html.escape(senderName) + ": " + Html.escape(text));
            reply(chatId, "✅ Сообщение отправлено");
            return;
        }

        // Shops/admins have no relay counterpart — route to the AI assistant.
        aiAnswerOrEscalate(chatId, text, sender);
    }

    /** Ticket points of the active route that are not delivered yet → needy ids. */
    private List<Integer> pendingNeedyIds(String pointsJson) {
        List<Integer> needyIds = new ArrayList<>();
        try {
            JsonNode points = mapper.readTree(pointsJson == null || pointsJson.isBlank() ? "[]" : pointsJson);
            for (JsonNode p : points) {
                if (!"ticket".equals(p.path("kind").asText()) || p.path("done").asBoolean(false)) {
                    continue;
                }
                int ticketId = p.path("ticket_id").asInt(0);
                if (ticketId <= 0) {
                    continue;
                }
                List<Integer> ids = jdbc.query("SELECT needy_id FROM tickets WHERE id = ?",
                    (rs, n) -> rs.getInt("needy_id"), ticketId);
                needyIds.addAll(ids);
            }
        } catch (Exception e) {
            log.warning("[telegram] points parse failed: " + e.getMessage());
        }
        return needyIds;
    }

    /** Free-form question → AI assistant; on ESCALATE / failure — human admin. */
    private void aiAnswerOrEscalate(String chatId, String text, Map<String, Object> sender) {
        String role = sender == null ? "guest" : (String) sender.get("role");
        String username = sender == null ? null : (String) sender.get("username");
        String answer = aiService.askSupportAi(text, role, username);
        if (answer != null && !AiService.ESCALATE.equals(answer.strip())) {
            reply(chatId, Html.escape(answer));
            return;
        }
        if (!supportChatId.isEmpty()) {
            String who = username != null ? role + " " + username : "гость (chat " + chatId + ")";
            telegramService.sendMessage(supportChatId,
                "🆘 Вопрос пользователя требует администратора\n"
                    + "От: " + Html.escape(who) + "\n"
                    + "Сообщение: " + Html.escape(text));
            reply(chatId, "🤝 Я не уверен в ответе, поэтому передал ваш вопрос администратору — "
                + "он ответит вам здесь или на платформе.");
        } else {
            reply(chatId, "❓ Не могу ответить на этот вопрос. Напишите в поддержку на платформе "
                + "или используйте /help для списка команд.");
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private Map<String, Object> findUserByChat(String chatId) {
        List<Map<String, Object>> rows = jdbc.query(
            "SELECT id, username, role, related_id FROM users WHERE telegram_chat_id = ?",
            (rs, n) -> {
                Map<String, Object> u = new LinkedHashMap<>();
                u.put("id", rs.getInt("id"));
                u.put("username", rs.getString("username"));
                u.put("role", rs.getString("role"));
                u.put("related_id", rs.getObject("related_id") == null ? null : rs.getInt("related_id"));
                return u;
            }, chatId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Reply into the chat: HTML parse mode, link previews off (welcome message). */
    private void reply(String chatId, String html) {
        api("sendMessage", Map.of(
            "chat_id", chatId,
            "text", html,
            "parse_mode", "HTML",
            "disable_web_page_preview", true));
    }

    /**
     * Bare Telegram Bot API call over {@code HttpURLConnection} (honours the SOCKS
     * proxy, unlike the JDK HttpClient). Returns the parsed JSON or null; never throws.
     */
    private JsonNode api(String method, Map<String, Object> payload) {
        if (botToken.isEmpty()) {
            return null;
        }
        try {
            byte[] body = mapper.writeValueAsBytes(payload);
            URL url = URI.create("https://api.telegram.org/bot" + botToken + "/" + method).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection(proxy());
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10_000);
            // Read timeout must sit above the long-poll window or getUpdates
            // would time out client-side before Telegram responds.
            conn.setReadTimeout((POLL_TIMEOUT_SECONDS + 10) * 1000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
            int status = conn.getResponseCode();
            try (InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream()) {
                JsonNode json = is == null ? null : mapper.readTree(is);
                if (status != 200) {
                    log.warning("[telegram] " + method + " → " + status
                        + (json != null ? " " + json.path("description").asText("") : ""));
                }
                return json;
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            log.warning("[telegram] " + method + " failed: " + e.getMessage());
            return null;
        }
    }

    private Proxy proxy() {
        String url = proxyService.getProxyUrl();
        if (url == null || !url.startsWith("socks5://")) {
            return Proxy.NO_PROXY;
        }
        String hostPort = url.substring("socks5://".length());
        int colon = hostPort.lastIndexOf(':');
        if (colon <= 0) {
            return Proxy.NO_PROXY;
        }
        String host = hostPort.substring(0, colon);
        int port = Integer.parseInt(hostPort.substring(colon + 1));
        return new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(host, port));
    }
}
