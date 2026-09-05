package ru.savefood.partner;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.savefood.billing.BillingService;
import ru.savefood.esg.EsgService;
import ru.savefood.match.NeedsMatchService;
import ru.savefood.match.MatchingWorkProperties;
import ru.savefood.web.RateLimiter;
import org.springframework.beans.factory.annotation.Autowired;
import ru.savefood.partner.dto.ApiLotIn;
import ru.savefood.partner.dto.WebhookIn;
import ru.savefood.receipt.ReceiptService;
import ru.savefood.security.Auth;
import ru.savefood.security.Authz;
import ru.savefood.security.CurrentUser;
import ru.savefood.shop.ShopRepository;
import ru.savefood.shop.ShopService;
import ru.savefood.util.Clamp;
import ru.savefood.web.ApiException;
import ru.savefood.webhook.WebhookService;
import ru.savefood.webhook.WebhookProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
@RestController
public class PartnerApiController {
    private static final String KEY_PREFIX = "sf_live_";
    private static final int PREFIX_LEN = KEY_PREFIX.length() + 6;
    private final JdbcTemplate jdbc;
    private final BillingService billing;
    private final EsgService esg;
    private final NeedsMatchService needsMatch;
    private final ShopService shopService;
    private final ShopRepository shopRepo;
    private final WebhookProperties webhookProperties;
    @Autowired
    private RateLimiter lotCreateRateLimiter;
    @Autowired
    private MatchingWorkProperties matchingLimits;
    private final SecureRandom random = new SecureRandom();
    public PartnerApiController(JdbcTemplate jdbc, BillingService billing, EsgService esg,
                               NeedsMatchService needsMatch, ShopService shopService, ShopRepository shopRepo,
                               WebhookProperties webhookProperties) {
        this.jdbc = jdbc;
        this.billing = billing;
        this.esg = esg;
        this.needsMatch = needsMatch;
        this.shopService = shopService;
        this.shopRepo = shopRepo;
        this.webhookProperties = webhookProperties;
    }
    @GetMapping("/api/v1/ping")
    public Map<String, Object> ping(@RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        int shopId = apiShop(apiKey);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("shop_id", shopId);
        out.put("plan", billing.getShopPlan(shopId));
        return out;
    }
    @GetMapping("/api/v1/lots")
    public List<Map<String, Object>> listLots(@RequestParam(required = false) String status,
                                              @RequestParam(defaultValue = "50") int limit,
                                              @RequestParam(defaultValue = "0") int offset,
                                              @RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        int shopId = apiShop(apiKey);
        int lim = Clamp.clamp(limit, 1, 200);
        if (status != null) {
            return jdbc.queryForList(
                "SELECT * FROM lots WHERE shop_id = ? AND status = ? ORDER BY created_at DESC LIMIT ? OFFSET ?",
                shopId, status, lim, offset);
        }
        return jdbc.queryForList(
            "SELECT * FROM lots WHERE shop_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?",
            shopId, lim, offset);
    }
    @PostMapping("/api/v1/lots")
    public Map<String, Object> createLot(@RequestBody ApiLotIn payload,
                                         @RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        int shopId = apiShop(apiKey);
        lotCreateRateLimiter.check("partner:lot_create", Integer.toString(shopId),
            matchingLimits.getPartnerCreatesPerMinute());
        if (payload.category() != null && !ReceiptService.LOT_CATEGORIES.contains(payload.category())) {
            throw new ApiException(400, "Неизвестная категория. Допустимые: "
                + String.join(", ", ReceiptService.LOT_CATEGORIES));
        }
        if (payload.quantity() == null || !Double.isFinite(payload.quantity())
            || payload.quantity() < 1 || payload.quantity() != Math.rint(payload.quantity())) {
            throw new ApiException(400, "quantity должен быть целым числом ≥ 1");
        }
        String comment = payload.comment() == null || payload.comment().isBlank()
            ? "Создано через партнёрский API" : payload.comment();
        int lotId = shopService.createLot(shopId, payload.description(), payload.quantity(),
            payload.expiryDate(), null, payload.address(), payload.timeSlot(), payload.category(),
            comment, false, "кг", 1.0);
        needsMatch.startNeedsMatch(lotId);
        return Map.of("id", lotId);
    }
    @DeleteMapping("/api/v1/lots/{lotId}")
    public Map<String, Object> deleteLot(@PathVariable int lotId,
                                         @RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        int shopId = apiShop(apiKey);
        Map<String, Object> lot = shopRepo.getLotById(lotId);
        if (lot == null || ((Number) lot.get("shop_id")).intValue() != shopId) {
            throw new ApiException(404, "Лот не найден");
        }
        if (!shopService.deleteLot(lotId)) {
            throw new ApiException(400, "Лот нельзя удалить в текущем статусе");
        }
        return Map.of("ok", true);
    }
    @GetMapping("/api/v1/esg")
    public Map<String, Object> esgReport(@RequestParam(defaultValue = "12") int months,
                                         @RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        int shopId = apiShop(apiKey);
        return esg.shopReport(shopId, months);
    }
    @PostMapping("/shops/{shopId}/api_keys")
    public Map<String, Object> createApiKey(@PathVariable int shopId, @Auth CurrentUser user) {
        requireApiPlan(shopId, user);
        String secret = KEY_PREFIX + tokenHex(24);
        String prefix = secret.substring(0, PREFIX_LEN);
        Integer keyId = jdbc.queryForObject(
            "INSERT INTO api_keys (shop_id, key_hash, prefix) VALUES (?, ?, ?) RETURNING id",
            Integer.class, shopId, hashKey(secret), prefix);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", keyId);
        out.put("key", secret);
        out.put("prefix", prefix);
        return out;
    }
    @GetMapping("/shops/{shopId}/api_keys")
    public List<Map<String, Object>> listApiKeys(@PathVariable int shopId, @Auth CurrentUser user) {
        requireApiPlan(shopId, user);
        return jdbc.queryForList(
            "SELECT id, prefix, revoked, created_at, last_used_at FROM api_keys "
            + "WHERE shop_id = ? ORDER BY created_at DESC", shopId);
    }
    @PostMapping("/shops/{shopId}/api_keys/{keyId}/revoke")
    public Map<String, Object> revokeApiKey(@PathVariable int shopId, @PathVariable int keyId,
                                            @Auth CurrentUser user) {
        requireApiPlan(shopId, user);
        List<Integer> ids = jdbc.query(
            "UPDATE api_keys SET revoked = TRUE WHERE id = ? AND shop_id = ? RETURNING id",
            (rs, n) -> rs.getInt("id"), keyId, shopId);
        if (ids.isEmpty()) {
            throw new ApiException(404, "Ключ не найден");
        }
        return Map.of("ok", true);
    }
    @PostMapping("/shops/{shopId}/webhooks")
    public Map<String, Object> createWebhook(@PathVariable int shopId, @RequestBody WebhookIn payload,
                                             @Auth CurrentUser user) {
        requireApiPlan(shopId, user);
        if (payload.url() == null || !(payload.url().startsWith("http://") || payload.url().startsWith("https://"))) {
            throw new ApiException(400, "URL должен начинаться с http(s)://");
        }
        List<String> events = payload.events() == null || payload.events().isEmpty()
            ? List.of("*") : payload.events();
        List<String> bad = new ArrayList<>();
        for (String e : events) {
            if (!"*".equals(e) && !WebhookService.EVENTS.contains(e)) {
                bad.add(e);
            }
        }
        if (!bad.isEmpty()) {
            throw new ApiException(400, "Неизвестные события: " + String.join(", ", bad)
                + ". Допустимые: " + String.join(", ", WebhookService.EVENTS) + " или *");
        }
        String secret = "whsec_" + tokenHex(24);
        List<Integer> hookIds = jdbc.query(
            "WITH shop_lock AS (SELECT pg_advisory_xact_lock(?)), inserted AS ("
                + "INSERT INTO webhooks (shop_id, url, secret, events) "
                + "SELECT ?, ?, ?, ? FROM shop_lock "
                + "WHERE (SELECT COUNT(*) FROM webhooks WHERE shop_id = ?) < ? RETURNING id) "
                + "SELECT id FROM inserted",
            (rs, n) -> rs.getInt("id"), shopId, shopId, payload.url(), secret, String.join(",", events),
            shopId, webhookProperties.getMaxPerShop());
        if (hookIds.isEmpty()) {
            throw new ApiException(409, "Достигнут лимит вебхуков для магазина");
        }
        Integer hookId = hookIds.getFirst();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", hookId);
        out.put("secret", secret);
        out.put("events", events);
        return out;
    }
    @GetMapping("/shops/{shopId}/webhooks")
    public List<Map<String, Object>> listWebhooks(@PathVariable int shopId, @Auth CurrentUser user) {
        requireApiPlan(shopId, user);
        return jdbc.queryForList(
            "SELECT id, url, events, active, created_at, last_status, last_delivery_at FROM webhooks "
            + "WHERE shop_id = ? ORDER BY created_at DESC", shopId);
    }
    @DeleteMapping("/shops/{shopId}/webhooks/{hookId}")
    public Map<String, Object> deleteWebhook(@PathVariable int shopId, @PathVariable int hookId,
                                             @Auth CurrentUser user) {
        requireApiPlan(shopId, user);
        List<Integer> ids = jdbc.query(
            "DELETE FROM webhooks WHERE id = ? AND shop_id = ? RETURNING id",
            (rs, n) -> rs.getInt("id"), hookId, shopId);
        if (ids.isEmpty()) {
            throw new ApiException(404, "Вебхук не найден");
        }
        return Map.of("ok", true);
    }
    /** X-API-Key → shop id (partner_api.py {@code get_api_shop}). */
    private int apiShop(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ApiException(401, "Заголовок X-API-Key обязателен");
        }
        Map<String, Object> row = jdbc.queryForList(
            "SELECT id, shop_id FROM api_keys WHERE key_hash = ? AND NOT revoked", hashKey(apiKey))
            .stream().findFirst().orElse(null);
        if (row == null) {
            throw new ApiException(401, "Неверный или отозванный API-ключ");
        }
        jdbc.update("UPDATE api_keys SET last_used_at = ? WHERE id = ?",
            OffsetDateTime.now(), ((Number) row.get("id")).intValue());
        int shopId = ((Number) row.get("shop_id")).intValue();
        billing.requireFeature(shopId, "api");
        return shopId;
    }
    private void requireApiPlan(int shopId, CurrentUser user) {
        Authz.ensureOwnerOrAdmin(user, "shop", shopId);
        if (shopRepo.getShopById(shopId) == null) {
            throw new ApiException(404, "Shop not found");
        }
        billing.requireFeature(shopId, "api");
    }
    private static String hashKey(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((key == null ? "" : key).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
    private String tokenHex(int bytes) {
        byte[] buf = new byte[bytes];
        random.nextBytes(buf);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte b : buf) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
