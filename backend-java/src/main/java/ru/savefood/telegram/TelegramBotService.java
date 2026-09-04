package ru.savefood.telegram;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import ru.savefood.ai.AiService;
import ru.savefood.auth.TelegramLoginService;
import ru.savefood.chat.ChatService;
import ru.savefood.push.PushDispatchService;
import ru.savefood.security.CurrentUser;
import ru.savefood.util.Html;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
@Service
public class TelegramBotService {
    private static final Logger log = Logger.getLogger(TelegramBotService.class.getName());
    /** Same TTL the link/login token issuers use (auth/OAuthController). */
    private static final int TOKEN_TTL_MINUTES = 10;
    private final JdbcTemplate jdbc;
    private final TelegramService telegram;
    private final TelegramLoginService telegramLogin;
    private final ChatService chat;
    private final AiService ai;
    private final PushDispatchService push;
    private final String supportChatId;
    private final String siteUrl;
    public TelegramBotService(JdbcTemplate jdbc, TelegramService telegram,
                              TelegramLoginService telegramLogin, ChatService chat,
                              AiService ai, PushDispatchService push,
                              @Value("${savefood.support-chat-id:}") String supportChatId,
                              @Value("${savefood.oauth.public-url:}") String siteUrl,
                              @Value("${SITE_URL:}") String fallbackSiteUrl) {
        this.jdbc = jdbc;
        this.telegram = telegram;
        this.telegramLogin = telegramLogin;
        this.chat = chat;
        this.ai = ai;
        this.push = push;
        this.supportChatId = supportChatId == null ? "" : supportChatId;
        this.siteUrl = normalizeSiteUrl(siteUrl, fallbackSiteUrl);
    }
    /** Entry point for one Telegram update. Never throws. */
    public void handleUpdate(JsonNode update) {
        try {
            JsonNode message = update.path("message");
            if (message.isMissingNode() || message.isNull()) {
                return;
            }
            String chatId = message.path("chat").path("id").asText("");
            String chatType = message.path("chat").path("type").asText("");
            String senderId = message.path("from").path("id").asText("");
            String text = message.path("text").asText("").strip();
            if (chatId.isEmpty() || text.isEmpty()) {
                return;
            }
            boolean authenticatedPrivateChat = "private".equals(chatType) && chatId.equals(senderId);
            dispatch(chatId, text, authenticatedPrivateChat);
        } catch (RuntimeException e) {
            log.warning("[telegram] update handling failed: " + e.getMessage());
        }
    }
    private void dispatch(String chatId, String text, boolean authenticatedPrivateChat) {
        if (text.startsWith("/start")) {
            String arg = text.length() > "/start".length() ? text.substring("/start".length()).strip() : "";
            if (arg.startsWith("link_")) {
                handleLink(chatId, arg.substring("link_".length()));
            } else if (arg.startsWith("login_")) {
                if (authenticatedPrivateChat) {
                    handleLogin(chatId, arg.substring("login_".length()));
                } else {
                    telegram.sendMessage(chatId,
                        "Для безопасного входа откройте эту ссылку в личном чате с ботом.");
                }
            } else {
                telegram.sendMessage(chatId, greeting());
            }
            return;
        }
        if (text.startsWith("/help")) {
            telegram.sendMessage(chatId, helpText());
            return;
        }
        if (text.startsWith("/status")) {
            handleStatus(chatId);
            return;
        }
        if (text.startsWith("/chat")) {
            telegram.sendMessage(chatId,
                "◇ Просто напишите сообщение сюда — оно уйдёт второй стороне активной доставки "
                + "(волонтёру или получателю) и появится в чате заявки на сайте.\n\n"
                + "Если активной доставки нет, вопрос уйдёт в поддержку.");
            return;
        }
        if (text.startsWith("/unlink")) {
            handleUnlink(chatId);
            return;
        }
        handleFreeText(chatId, text);
    }
    private void handleLink(String chatId, String token) {
        List<Integer> userIds = jdbc.query(
            "SELECT user_id FROM telegram_link_tokens "
            + "WHERE token = ? AND created_at >= NOW() - (? * INTERVAL '1 minute')",
            (rs, n) -> rs.getInt("user_id"), token, TOKEN_TTL_MINUTES);
        if (userIds.isEmpty()) {
            telegram.sendMessage(chatId,
                "◷ Ссылка привязки устарела или уже использована. "
                + "Откройте профиль на сайте и нажмите «Подключить Telegram» ещё раз.");
            return;
        }
        int userId = userIds.get(0);
        jdbc.update("UPDATE users SET telegram_chat_id = NULL WHERE telegram_chat_id = ? AND id <> ?",
            chatId, userId);
        jdbc.update("UPDATE users SET telegram_chat_id = ? WHERE id = ?", chatId, userId);
        jdbc.update("DELETE FROM telegram_link_tokens WHERE user_id = ?", userId);
        telegram.sendMessage(chatId,
            "✓ Telegram привязан. Теперь уведомления о лотах, маршрутах и доставках "
            + "будут приходить сюда.\n\n" + helpText());
    }
    private void handleLogin(String chatId, String token) {
        String completionToken = telegramLogin.confirm(token, chatId);
        if (completionToken == null) {
            telegram.sendMessage(chatId,
                "◷ Вход недоступен: ссылка устарела, уже использована или аккаунт не привязан. "
                + "Начните вход на сайте заново.");
            return;
        }
        String completionUrl = completionUrl(completionToken);
        if (completionUrl == null) {
            telegramLogin.revokeConfirmation(token, completionToken);
            telegram.sendMessage(chatId,
                "Вход через Telegram временно недоступен из-за настройки адреса сайта.");
            return;
        }
        boolean delivered = telegram.sendMessage(chatId,
            "✓ Telegram подтвердил аккаунт.\n\n"
                + "<a href=\"" + Html.escape(completionUrl) + "\">Завершить вход в SaveFood</a>\n\n"
                + "Ссылка одноразовая и действует "
                + TelegramLoginService.COMPLETION_TTL_MINUTES + " минут.");
        if (!delivered || !telegramLogin.markDelivered(token, completionToken)) {
            telegramLogin.revokeConfirmation(token, completionToken);
        }
    }
    private void handleStatus(String chatId) {
        Map<String, Object> user = linkedUser(chatId);
        if (user == null) {
            telegram.sendMessage(chatId,
                "↗ Этот Telegram не привязан к аккаунту SaveFood.\n"
                + "Откройте профиль на сайте и нажмите «Подключить Telegram».");
            return;
        }
        String role = (String) user.get("role");
        Integer relatedId = user.get("related_id") instanceof Number n ? n.intValue() : null;
        StringBuilder sb = new StringBuilder();
        sb.append("○ Аккаунт: <b>").append(Html.escape(String.valueOf(user.get("username"))))
          .append("</b>\nРоль: ").append(roleLabel(role)).append('\n');
        if (Boolean.TRUE.equals(user.get("is_blocked"))) {
            sb.append("\n× Аккаунт заблокирован администратором.");
            telegram.sendMessage(chatId, sb.toString());
            return;
        }
        if (relatedId != null) {
            switch (role == null ? "" : role) {
                case "volunteer" -> appendVolunteerStatus(sb, relatedId);
                case "needy" -> appendNeedyStatus(sb, relatedId);
                case "shop" -> appendShopStatus(sb, relatedId);
                default -> { }
            }
        }
        telegram.sendMessage(chatId, sb.toString());
    }
    private void appendVolunteerStatus(StringBuilder sb, int volunteerId) {
        String status = jdbc.query("SELECT status FROM volunteers WHERE id = ?",
            rs -> rs.next() ? rs.getString("status") : null, volunteerId);
        sb.append("Верификация: ").append(verificationLabel(status)).append('\n');
        Integer routeId = jdbc.query(
            "SELECT id FROM volunteer_routes WHERE volunteer_id = ? AND status = 'in_progress'",
            rs -> rs.next() ? rs.getInt("id") : null, volunteerId);
        sb.append(routeId == null ? "Активного маршрута нет." : "→ Активный маршрут #" + routeId);
    }
    private void appendNeedyStatus(StringBuilder sb, int needyId) {
        Map<String, Object> ticket = firstRow(
            "SELECT id, status FROM tickets WHERE needy_id = ? AND status IN ('open','assigned') "
            + "ORDER BY id DESC LIMIT 1", needyId);
        if (ticket == null) {
            sb.append("Активной заявки нет.");
        } else {
            sb.append("□ Заявка #").append(ticket.get("id"))
              .append("assigned".equals(ticket.get("status")) ? " — волонтёр в пути" : " — ждёт волонтёра");
        }
    }
    private void appendShopStatus(StringBuilder sb, int shopId) {
        Integer active = jdbc.queryForObject(
            "SELECT COUNT(*) FROM lots WHERE shop_id = ? AND status = 'active'", Integer.class, shopId);
        Integer taken = jdbc.queryForObject(
            "SELECT COUNT(*) FROM lots WHERE shop_id = ? AND status = 'taken'", Integer.class, shopId);
        sb.append("▣ Лотов на витрине: ").append(active == null ? 0 : active)
          .append("\nЗабрано волонтёрами: ").append(taken == null ? 0 : taken);
    }
    private void handleUnlink(String chatId) {
        int rows = jdbc.update("UPDATE users SET telegram_chat_id = NULL WHERE telegram_chat_id = ?", chatId);
        telegram.sendMessage(chatId, rows > 0
            ? "○ Telegram отвязан. Уведомления сюда больше не придут — привязать заново можно в профиле."
            : "Этот Telegram и так не привязан ни к одному аккаунту.");
    }
    private void handleFreeText(String chatId, String text) {
        Map<String, Object> user = linkedUser(chatId);
        if (user != null && !Boolean.TRUE.equals(user.get("is_blocked"))) {
            String role = (String) user.get("role");
            Integer relatedId = user.get("related_id") instanceof Number n ? n.intValue() : null;
            Integer userId = user.get("id") instanceof Number n ? n.intValue() : null;
            if (relatedId != null && userId != null && relayToCounterpart(chatId,
                    new CurrentUser(userId, (String) user.get("username"), role, relatedId), text)) {
                return;
            }
        }
        askSupport(chatId, user, text);
    }
    private boolean relayToCounterpart(String chatId, CurrentUser user, String text) {
        String role = user.role();
        int relatedId = user.relatedId();
        Map<String, Object> row;
        boolean toNeedy;
        if ("volunteer".equals(role)) {
            row = firstRow(
                "SELECT t.id AS ticket_id, t.needy_id AS counterpart_id FROM tickets t "
                + "JOIN volunteer_routes vr ON vr.volunteer_id = t.assigned_volunteer_id "
                + "WHERE t.assigned_volunteer_id = ? AND t.status = 'assigned' "
                + "AND vr.status = 'in_progress' ORDER BY t.id ASC LIMIT 1", relatedId);
            toNeedy = true;
        } else if ("needy".equals(role)) {
            row = firstRow(
                "SELECT id AS ticket_id, assigned_volunteer_id AS counterpart_id FROM tickets "
                + "WHERE needy_id = ? AND status = 'assigned' AND assigned_volunteer_id IS NOT NULL "
                + "ORDER BY id DESC LIMIT 1", relatedId);
            toNeedy = false;
        } else {
            return false;
        }
        if (row == null) {
            return false;
        }
        int ticketId = ((Number) row.get("ticket_id")).intValue();
        ChatService.AddedMessage added = chat.addMessage(ticketId, user, text);
        int counterpartId = added.counterpartId();
        String safe = Html.escape(text);
        try {
            if (toNeedy) {
                telegram.notifyNeedy(counterpartId, "◇ Волонтёр: " + safe);
                push.notifyRole("needy", counterpartId, "Сообщение от волонтёра: " + text, "/needy");
            } else {
                telegram.notifyVolunteer(counterpartId, "◇ Получатель: " + safe);
                push.notifyRole("volunteer", counterpartId, "Сообщение от получателя: " + text, "/volunteer");
            }
        } catch (RuntimeException ignore) {
        }
        telegram.sendMessage(chatId, "✓ Отправлено (заявка #" + ticketId + ").");
        return true;
    }
    /** Gemini answers, or the question is escalated to the support chat. */
    private void askSupport(String chatId, Map<String, Object> user, String text) {
        String role = user == null ? null : (String) user.get("role");
        String username = user == null ? null : (String) user.get("username");
        String answer = ai.askSupportAi(text, role, username);
        if (answer != null && !AiService.ESCALATE.equals(answer.strip())) {
            telegram.sendMessage(chatId, Html.escape(answer));
            return;
        }
        if (supportChatId.isEmpty()) {
            telegram.sendMessage(chatId,
                "Не могу ответить на этот вопрос автоматически. Посмотрите /help — "
                + "возможно, ответ там.");
            return;
        }
        telegram.sendMessage(supportChatId,
            "? Вопрос в поддержку от " + (username == null ? "непривязанного пользователя" : Html.escape(username))
            + " (" + roleLabel(role) + ", chat_id " + chatId + "):\n\n" + Html.escape(text));
        telegram.sendMessage(chatId, "→ Вопрос передан администратору — с вами свяжутся здесь же.");
    }
    private Map<String, Object> linkedUser(String chatId) {
        return firstRow(
            "SELECT id, username, role, related_id, is_blocked FROM users WHERE telegram_chat_id = ?",
            chatId);
    }
    private Map<String, Object> firstRow(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }
    private String greeting() {
        String site = siteUrl.isBlank() ? "https://savefood.kz" : siteUrl;
        return "◇ Это бот платформы <b>SaveFood</b> — спасаем еду от списания и передаём тем, кому она нужна.\n\n"
            + "Чтобы получать сюда уведомления, откройте профиль на сайте и нажмите «Подключить Telegram»:\n"
            + site + "\n\n" + helpText();
    }
    private String completionUrl(String completionToken) {
        return siteUrl.isBlank() ? null : siteUrl + "/auth#telegram_completion=" + completionToken;
    }
    private static String normalizeSiteUrl(String primary, String fallback) {
        for (String candidate : new String[]{primary, fallback}) {
            if (candidate == null || candidate.isBlank()) continue;
            String normalized = candidate.strip().replaceAll("/+$", "");
            try {
                URI uri = URI.create(normalized);
                if (("https".equalsIgnoreCase(uri.getScheme())
                    || "http".equalsIgnoreCase(uri.getScheme())) && uri.getHost() != null) {
                    return normalized;
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        return "";
    }
    private String helpText() {
        return "Команды:\n"
            + "/status — привязан ли аккаунт, активная заявка или маршрут\n"
            + "/chat — как переписаться с волонтёром или получателем\n"
            + "/unlink — отвязать Telegram от аккаунта\n"
            + "/help — это сообщение\n\n"
            + "Любое другое сообщение уйдёт второй стороне активной доставки, "
            + "а если её нет — в поддержку.";
    }
    private static String roleLabel(String role) {
        if (role == null) {
            return "гость";
        }
        return switch (role) {
            case "shop" -> "магазин / донор";
            case "needy" -> "получатель";
            case "volunteer" -> "волонтёр";
            case "admin" -> "администратор";
            default -> role;
        };
    }
    private static String verificationLabel(String status) {
        if (status == null) {
            return "не заполнена";
        }
        return switch (status) {
            case "approved" -> "✓ подтверждена";
            case "rejected" -> "! отклонена — загрузите документ заново";
            case "pending" -> "◷ на проверке";
            default -> status;
        };
    }
}
