package ru.savefood.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import ru.savefood.ai.AiService;
import ru.savefood.chat.ChatService;
import ru.savefood.push.PushDispatchService;
import ru.savefood.util.Html;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The inbound half of the Telegram channel (§16), restored after the Python
 * aiogram bot was removed with the rest of that stack.
 *
 * <p>Without this, the whole channel was one-way and therefore dead end-to-end:
 * {@code telegram_link_tokens} rows were minted by {@code /auth/telegram/init-link}
 * and never redeemed, so {@code users.telegram_chat_id} could never be set for a
 * new account — which in turn meant every outbound {@code TelegramService.notify*}
 * had nobody to deliver to, and {@code /auth/telegram/login/poll} could never
 * leave {@code pending}.
 *
 * <p>Handled updates:
 * <ul>
 *   <li>{@code /start link_<token>} — redeem an account-link token: write the
 *       sender's chat id onto the user and drop the token;</li>
 *   <li>{@code /start login_<token>} — bind an already-linked account to a
 *       pending login attempt, which is what the SPA is polling for;</li>
 *   <li>{@code /start}, {@code /help}, {@code /status}, {@code /chat},
 *       {@code /unlink};</li>
 *   <li>any other text — relayed to the counterpart of the sender's active
 *       delivery (§22.7), or answered by {@link AiService} with escalation to
 *       {@code SUPPORT_CHAT_ID}.</li>
 * </ul>
 *
 * <p>Every handler is best-effort and never throws: the webhook must always
 * answer 200, or Telegram redelivers the same update forever.
 */
@Service
public class TelegramBotService {

    private static final Logger log = Logger.getLogger(TelegramBotService.class.getName());

    /** Same TTL the link/login token issuers use (auth/OAuthController). */
    private static final int TOKEN_TTL_MINUTES = 10;

    private final JdbcTemplate jdbc;
    private final TelegramService telegram;
    private final ChatService chat;
    private final AiService ai;
    private final PushDispatchService push;
    private final String supportChatId;
    private final String siteUrl;

    public TelegramBotService(JdbcTemplate jdbc, TelegramService telegram, ChatService chat,
                              AiService ai, PushDispatchService push,
                              @Value("${savefood.support-chat-id:}") String supportChatId,
                              @Value("${savefood.oauth.public-url:}") String siteUrl) {
        this.jdbc = jdbc;
        this.telegram = telegram;
        this.chat = chat;
        this.ai = ai;
        this.push = push;
        this.supportChatId = supportChatId == null ? "" : supportChatId;
        this.siteUrl = siteUrl == null ? "" : siteUrl;
    }

    /** Entry point for one Telegram update. Never throws. */
    public void handleUpdate(JsonNode update) {
        try {
            JsonNode message = update.path("message");
            if (message.isMissingNode() || message.isNull()) {
                return;  // edited messages, callbacks, channel posts — not used
            }
            String chatId = message.path("chat").path("id").asText("");
            String text = message.path("text").asText("").strip();
            if (chatId.isEmpty() || text.isEmpty()) {
                return;
            }
            dispatch(chatId, text);
        } catch (RuntimeException e) {
            log.warning("[telegram] update handling failed: " + e.getMessage());
        }
    }

    private void dispatch(String chatId, String text) {
        if (text.startsWith("/start")) {
            String arg = text.length() > "/start".length() ? text.substring("/start".length()).strip() : "";
            if (arg.startsWith("link_")) {
                handleLink(chatId, arg.substring("link_".length()));
            } else if (arg.startsWith("login_")) {
                handleLogin(chatId, arg.substring("login_".length()));
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

    // ── /start link_<token> ────────────────────────────────────────────────────

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
        // One chat id per account: if this chat was linked to someone else, move it.
        jdbc.update("UPDATE users SET telegram_chat_id = NULL WHERE telegram_chat_id = ? AND id <> ?",
            chatId, userId);
        jdbc.update("UPDATE users SET telegram_chat_id = ? WHERE id = ?", chatId, userId);
        jdbc.update("DELETE FROM telegram_link_tokens WHERE user_id = ?", userId);
        telegram.sendMessage(chatId,
            "✓ Telegram привязан. Теперь уведомления о лотах, маршрутах и доставках "
            + "будут приходить сюда.\n\n" + helpText());
    }

    // ── /start login_<token> ───────────────────────────────────────────────────

    private void handleLogin(String chatId, String token) {
        Integer userId = jdbc.query(
            "SELECT id FROM users WHERE telegram_chat_id = ?",
            rs -> rs.next() ? rs.getInt("id") : null, chatId);
        if (userId == null) {
            telegram.sendMessage(chatId,
                "↗ Этот Telegram ещё не привязан ни к одному аккаунту, поэтому войти по нему нельзя.\n\n"
                + "Войдите на сайте логином и паролем, откройте профиль и нажмите "
                + "«Подключить Telegram» — после этого вход одной кнопкой заработает.");
            return;
        }
        int updated = jdbc.update(
            "UPDATE telegram_login_tokens SET user_id = ? "
            + "WHERE token = ? AND user_id IS NULL "
            + "AND created_at >= NOW() - (? * INTERVAL '1 minute')",
            userId, token, TOKEN_TTL_MINUTES);
        if (updated == 0) {
            telegram.sendMessage(chatId, "◷ Ссылка для входа устарела — начните вход на сайте заново.");
            return;
        }
        telegram.sendMessage(chatId, "✓ Вход подтверждён — возвращайтесь на сайт, страница уже открылась.");
    }

    // ── /status ────────────────────────────────────────────────────────────────

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
        String status = jdbc.query("SELECT status FROM needy WHERE id = ?",
            rs -> rs.next() ? rs.getString("status") : null, needyId);
        sb.append("Анкета: ").append(verificationLabel(status)).append('\n');
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

    // ── /unlink ────────────────────────────────────────────────────────────────

    private void handleUnlink(String chatId) {
        int rows = jdbc.update("UPDATE users SET telegram_chat_id = NULL WHERE telegram_chat_id = ?", chatId);
        telegram.sendMessage(chatId, rows > 0
            ? "○ Telegram отвязан. Уведомления сюда больше не придут — привязать заново можно в профиле."
            : "Этот Telegram и так не привязан ни к одному аккаунту.");
    }

    // ── free text: relay to the delivery counterpart, else support ─────────────

    private void handleFreeText(String chatId, String text) {
        Map<String, Object> user = linkedUser(chatId);
        if (user != null && !Boolean.TRUE.equals(user.get("is_blocked"))) {
            String role = (String) user.get("role");
            Integer relatedId = user.get("related_id") instanceof Number n ? n.intValue() : null;
            if (relatedId != null && relayToCounterpart(chatId, role, relatedId, text)) {
                return;
            }
        }
        askSupport(chatId, user, text);
    }

    /**
     * Forward the message to the other side of an in-flight delivery and record it
     * in the ticket thread, so Telegram and the in-app chat (§53) are one
     * conversation rather than two.
     *
     * @return true when the message was relayed
     */
    private boolean relayToCounterpart(String chatId, String role, int relatedId, String text) {
        Map<String, Object> row;
        boolean toNeedy;
        if ("volunteer".equals(role)) {
            // The next undelivered stop of the active route is the counterpart.
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
        int counterpartId = ((Number) row.get("counterpart_id")).intValue();

        chat.addMessage(ticketId, role, relatedId, text);
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
            // best-effort mirror; the message is already stored in the thread
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

    // ── helpers ────────────────────────────────────────────────────────────────

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
