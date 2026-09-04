package ru.savefood.telegram;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.logging.Logger;
import ru.savefood.proxy.ProxyService;
import ru.savefood.push.PushDispatchService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
@Service
public class TelegramService {
    private static final Logger log = Logger.getLogger(TelegramService.class.getName());
    private final ObjectMapper mapper = new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final ProxyService proxyService;
    private final PushDispatchService push;
    private final String botToken;
    public TelegramService(JdbcTemplate jdbc, ProxyService proxyService, PushDispatchService push,
                           @Value("${savefood.oauth.telegram-bot-token:}") String botToken) {
        this.jdbc = jdbc;
        this.proxyService = proxyService;
        this.push = push;
        this.botToken = botToken == null ? "" : botToken;
    }
    /** Low-level send. Returns true on HTTP 200, false (never throws) otherwise. */
    public boolean sendMessage(String chatId, String text) {
        if (botToken.isEmpty() || chatId == null || chatId.isEmpty()) {
            return false;
        }
        try {
            byte[] body = mapper.writeValueAsBytes(Map.of(
                "chat_id", chatId, "text", text, "parse_mode", "HTML"));
            URL url = URI.create(
                "https://api.telegram.org/bot" + botToken + "/sendMessage").toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection(proxy());
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
            int status = conn.getResponseCode();
            conn.disconnect();
            return status == 200;
        } catch (Exception e) {
            log.warning("Telegram send_message failed: " + e.getMessage());
            return false;
        }
    }
    public String getChatIdByRelated(String role, int relatedId) {
        try {
            return jdbc.query(
                "SELECT telegram_chat_id FROM users WHERE role = ? AND related_id = ?",
                rs -> rs.next() ? rs.getString("telegram_chat_id") : null, role, relatedId);
        } catch (RuntimeException e) {
            return null;
        }
    }
    public void notifyNeedy(int needyId, String text) {
        notify("needy", needyId, text);
    }
    public void notifyShop(int shopId, String text) {
        notify("shop", shopId, text);
    }
    public void notifyVolunteer(int volunteerId, String text) {
        notify("volunteer", volunteerId, text);
    }
    private void notify(String role, int relatedId, String text) {
        String chatId = getChatIdByRelated(role, relatedId);
        if (chatId != null && !chatId.isEmpty()) {
            sendMessage(chatId, text);
        }
        try {
            push.notifyRole(role, relatedId, text, "/");
        } catch (Exception ignore) {
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
