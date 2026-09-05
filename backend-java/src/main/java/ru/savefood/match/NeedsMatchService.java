package ru.savefood.match;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import ru.savefood.push.PushSendBudget;
import java.util.logging.Logger;
import ru.savefood.push.PushDispatchService;
import ru.savefood.telegram.TelegramService;
import ru.savefood.util.FoodCategories;
import ru.savefood.util.Html;
import ru.savefood.volunteer.AvailabilityService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
@Service
public class NeedsMatchService {
    private static final Logger log = Logger.getLogger(NeedsMatchService.class.getName());
    private final JdbcTemplate jdbc;
    private final AvailabilityService availability;
    private final TelegramService telegram;
    private final PushDispatchService push;
    private final BoundedWorkExecutor pool;
    private final BoundedWorkExecutor telegramPool;
    private final MatchingWorkProperties limits;
    private final MatchingCandidateRepository candidates;
    public NeedsMatchService(JdbcTemplate jdbc, AvailabilityService availability,
                             TelegramService telegram, PushDispatchService push,
                             @Qualifier("matchingExecutor") BoundedWorkExecutor pool,
                             @Qualifier("matchingTelegramExecutor") BoundedWorkExecutor telegramPool,
                             MatchingWorkProperties limits, MatchingCandidateRepository candidates) {
        this.jdbc = jdbc;
        this.availability = availability;
        this.telegram = telegram;
        this.push = push;
        this.pool = pool;
        this.telegramPool = telegramPool;
        this.limits = limits;
        this.candidates = candidates;
    }
    private static final class LotBudget {
        private int telegramRemaining;
        private final PushSendBudget push;
        LotBudget(MatchingWorkProperties limits) {
            telegramRemaining = limits.getTelegramSends();
            push = new PushSendBudget(limits.getPushSends());
        }
    }
    /** Fire-and-forget entry point, the analogue of {@code start_needs_match}. */
    public void startNeedsMatch(int lotId) {
        pool.tryExecute(() -> {
            LotBudget budget = new LotBudget(limits);
            try {
                notifyMatchingNeedy(lotId, budget);
            } catch (Exception e) {
                log.warning("[needs_match] lot " + lotId + " failed: " + e);
            }
            if (Thread.currentThread().isInterrupted()) return;
            try {
                notifyAvailableVolunteers(lotId, budget);
            } catch (Exception e) {
                log.warning("[needs_match] lot " + lotId + " volunteer push failed: " + e);
            }
        });
    }
    static boolean matchesPreferences(String category, String preferences) {
        return FoodCategories.preferenceSignal(category, preferences) == FoodCategories.Signal.MATCH;
    }
    private void notifyMatchingNeedy(int lotId, LotBudget budget) {
        List<Map<String, Object>> lots = jdbc.queryForList(
            "SELECT l.id, l.description, l.category, l.city, s.name AS shop_name "
            + "FROM lots l JOIN shops s ON s.id = l.shop_id "
            + "WHERE l.id = ? AND l.status = 'active' AND l.quantity >= 1", lotId);
        if (lots.isEmpty()) {
            return;
        }
        Map<String, Object> lot = lots.get(0);
        String category = (String) lot.get("category");
        if (category == null) {
            return;
        }
        List<Map<String, Object>> candidates = this.candidates.recipients(lot.get("city"), category);
        List<Integer> matched = new ArrayList<>();
        List<Integer> pushTargets = new ArrayList<>();
        for (Map<String, Object> c : candidates) {
            if (matchesPreferences(category, (String) c.get("preferences"))) {
                int needyId = ((Number) c.get("needy_id")).intValue();
                matched.add(needyId);
                if (Boolean.TRUE.equals(c.get("geo_push_enabled"))) {
                    pushTargets.add(needyId);
                }
                if (matched.size() >= limits.getRecipientsNotified()) {
                    break;
                }
            }
        }
        if (matched.isEmpty()) {
            return;
        }
        String text = "В магазине «" + lot.get("shop_name") + "» появился лот из категории «"
            + category + "», которая указана в ваших предпочтениях: " + lot.get("description")
            + ". Откройте карту лотов, чтобы оформить заявку.";
        OffsetDateTime now = OffsetDateTime.now();
        for (Integer needyId : matched) {
            jdbc.update(
                "INSERT INTO notifications (needy_id, type, payload, created_at, read) "
                + "VALUES (?, ?, ?, ?, 0)",
                needyId, "lot_match", text, now);
        }
        String safe = Html.escape(text);
        for (Integer needyId : matched) {
            try {
                notifyExternal("needy", needyId, "□ " + safe, budget);
            } catch (RuntimeException ignore) {
            }
        }
        for (Integer needyId : pushTargets) {
            try {
                push.notifyRole("needy", needyId, text, "/", budget.push);
            } catch (RuntimeException ignore) {
            }
        }
        log.info("[needs_match] lot " + lotId + " matched " + matched.size()
            + " recipients (" + pushTargets.size() + " web-push)");
    }
    private void notifyAvailableVolunteers(int lotId, LotBudget budget) {
        List<Map<String, Object>> lots = jdbc.queryForList(
            "SELECT l.id, l.description, l.category, l.city, "
            + "s.name AS shop_name, s.lat AS s_lat, s.lon AS s_lon "
            + "FROM lots l JOIN shops s ON s.id = l.shop_id "
            + "WHERE l.id = ? AND l.status = 'active' AND l.quantity >= 1", lotId);
        if (lots.isEmpty()) {
            return;
        }
        Map<String, Object> lot = lots.get(0);
        Double sLat = toDouble(lot.get("s_lat"));
        Double sLon = toDouble(lot.get("s_lon"));
        List<Map<String, Object>> candidates = this.candidates.volunteers(lot.get("city"), sLat, sLon);
        List<Map<String, Object>> available = new ArrayList<>();
        for (Map<String, Object> v : candidates) {
            Object av = v.get("availability");
            if (availability.isAvailableNow(av == null ? null : av.toString())) {
                available.add(v);
            }
        }
        if (sLat != null && sLon != null) {
            available.sort((a, b) -> Double.compare(
                distance(a, sLat, sLon), distance(b, sLat, sLon)));
        }
        List<Map<String, Object>> targets = available.size() > limits.getVolunteersNotified()
            ? available.subList(0, limits.getVolunteersNotified()) : available;
        if (targets.isEmpty()) {
            return;
        }
        String text = "Новый лот рядом: «" + lot.get("description") + "» в магазине «"
            + lot.get("shop_name") + "». Вы отметили это время как доступное — "
            + "откройте карту, чтобы взять маршрут.";
        OffsetDateTime now = OffsetDateTime.now();
        for (Map<String, Object> v : targets) {
            jdbc.update(
                "INSERT INTO notifications (volunteer_id, type, payload, created_at, read) "
                + "VALUES (?, ?, ?, ?, 0)",
                ((Number) v.get("id")).intValue(), "lot_nearby", text, now);
        }
        String safe = Html.escape(text);
        for (Map<String, Object> v : targets) {
            try {
                notifyExternal("volunteer", ((Number) v.get("id")).intValue(), "□ " + safe, budget);
            } catch (RuntimeException ignore) {
            }
        }
        log.info("[needs_match] lot " + lotId + " pinged " + targets.size() + " available volunteers");
    }
    private void notifyExternal(String role, int id, String text, LotBudget budget) {
        if (budget.telegramRemaining > 0) {
            budget.telegramRemaining--;
            telegramPool.tryExecute(() -> {
                if (Thread.currentThread().isInterrupted()) return;
                try {
                    String chatId = telegram.getChatIdByRelated(role, id);
                    if (chatId != null && !chatId.isEmpty()) telegram.sendMessage(chatId, text);
                } catch (RuntimeException e) {
                    log.warning("[needs_match] Telegram delivery failed: " + e.getMessage());
                }
            });
        }
        // Preserve TelegramService's accompanying push and its HTML/plain-text payload.
        push.notifyRole(role, id, text, "/", budget.push);
    }
    private static double distance(Map<String, Object> v, double sLat, double sLon) {
        Double lat = toDouble(v.get("lat"));
        Double lon = toDouble(v.get("lon"));
        if (lat == null || lon == null) {
            return Double.POSITIVE_INFINITY;
        }
        return haversine(lat, lon, sLat, sLon);
    }
    /** Great-circle distance in metres (utils.py haversine). */
    static double haversine(double lat1, double lon1, double lat2, double lon2) {
        return ru.savefood.util.Geo.haversineMeters(lat1, lon1, lat2, lon2);
    }
    private static Double toDouble(Object v) {
        return v instanceof Number n ? n.doubleValue() : null;
    }
}
