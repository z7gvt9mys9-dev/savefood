package kz.savefood.match;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import kz.savefood.volunteer.AvailabilityService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * «Карта потребностей», ported from needs_match.py: when a shop publishes a lot,
 * notify the approved recipients in the same city whose stated preferences match
 * the lot's category, and the volunteers who marked the current time as
 * available. Runs fire-and-forget (a daemon executor here, a daemon thread in
 * Python) so lot creation never waits on the fan-out.
 *
 * <p>The in-app feed notifications (DB inserts) are ported in full. The external
 * Telegram and Web-Push fan-out is best-effort in the Python source (each call
 * wrapped in {@code try/except: pass}) and is not part of this backend module;
 * it stays with the Python notifier during the migration.
 */
@Service
public class NeedsMatchService {

    private static final Logger log = Logger.getLogger(NeedsMatchService.class.getName());

    private static final Map<String, List<String>> CATEGORY_KEYWORDS = Map.of(
        "Молочные продукты", List.of("молок", "молоч", "сыр", "творог", "кефир", "йогурт", "сметан"),
        "Выпечка", List.of("хлеб", "выпечк", "булк", "батон", "лаваш"),
        "Овощи/Фрукты", List.of("овощ", "фрукт", "яблок", "картофел", "капуст", "морков"),
        "Готовая еда", List.of("готовая еда", "готовой еды", "обед", "консерв", "крупа", "каш"));

    private static final List<String> RESTRICTION_WORDS =
        List.of("аллергия", "нельзя", "не ем", "без ", "непереносимость", "не могу", "запрет");

    private static final int MAX_NOTIFIED_PER_LOT = 20;
    private static final int MAX_VOLUNTEERS_PER_LOT = 10;

    private final JdbcTemplate jdbc;
    private final AvailabilityService availability;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "needs-match");
        t.setDaemon(true);
        return t;
    });

    public NeedsMatchService(JdbcTemplate jdbc, AvailabilityService availability) {
        this.jdbc = jdbc;
        this.availability = availability;
    }

    /** Fire-and-forget entry point, the analogue of {@code start_needs_match}. */
    public void startNeedsMatch(int lotId) {
        pool.submit(() -> {
            try {
                notifyMatchingNeedy(lotId);
            } catch (Exception e) {
                log.warning("[needs_match] lot " + lotId + " failed: " + e);
            }
            try {
                notifyAvailableVolunteers(lotId);
            } catch (Exception e) {
                log.warning("[needs_match] lot " + lotId + " volunteer push failed: " + e);
            }
        });
    }

    static boolean matchesPreferences(String category, String preferences) {
        List<String> kws = CATEGORY_KEYWORDS.get(category == null ? "" : category);
        if (kws == null || preferences == null) {
            return false;
        }
        boolean matched = false;
        for (String raw : preferences.split("[.,;\\n]+")) {
            String clause = raw.strip().toLowerCase();
            if (clause.isEmpty()) {
                continue;
            }
            boolean hasKw = kws.stream().anyMatch(clause::contains);
            if (!hasKw) {
                continue;
            }
            if (RESTRICTION_WORDS.stream().anyMatch(clause::contains)) {
                return false; // explicit restriction beats any positive mention
            }
            matched = true;
        }
        return matched;
    }

    void notifyMatchingNeedy(int lotId) {
        List<Map<String, Object>> lots = jdbc.queryForList(
            "SELECT l.id, l.description, l.category, l.city, s.name AS shop_name "
            + "FROM lots l JOIN shops s ON s.id = l.shop_id "
            + "WHERE l.id = ? AND l.status = 'active' AND l.quantity > 0", lotId);
        if (lots.isEmpty()) {
            return;
        }
        Map<String, Object> lot = lots.get(0);
        String category = (String) lot.get("category");
        if (category == null) {
            return;
        }
        List<Map<String, Object>> candidates = jdbc.queryForList(
            "SELECT n.id AS needy_id, np.preferences "
            + "FROM needy n JOIN needy_profile np ON np.needy_id = n.id "
            + "WHERE n.status = 'approved' "
            + "AND np.preferences IS NOT NULL AND TRIM(np.preferences) <> '' "
            + "AND np.city IS NOT NULL AND np.city = ?", lot.get("city"));

        List<Integer> matched = new ArrayList<>();
        for (Map<String, Object> c : candidates) {
            if (matchesPreferences(category, (String) c.get("preferences"))) {
                matched.add(((Number) c.get("needy_id")).intValue());
                if (matched.size() >= MAX_NOTIFIED_PER_LOT) {
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
        log.info("[needs_match] lot " + lotId + " matched " + matched.size() + " recipients");
    }

    void notifyAvailableVolunteers(int lotId) {
        List<Map<String, Object>> lots = jdbc.queryForList(
            "SELECT l.id, l.description, l.category, l.city, "
            + "s.name AS shop_name, s.lat AS s_lat, s.lon AS s_lon "
            + "FROM lots l JOIN shops s ON s.id = l.shop_id "
            + "WHERE l.id = ? AND l.status = 'active' AND l.quantity > 0", lotId);
        if (lots.isEmpty()) {
            return;
        }
        Map<String, Object> lot = lots.get(0);
        // Only volunteers who FILLED the calendar are pinged: an empty calendar
        // means «не беспокоить», not «доступен всегда» — push must be opt-in.
        List<Map<String, Object>> candidates = jdbc.queryForList(
            "SELECT id, lat, lon, availability FROM volunteers "
            + "WHERE availability IS NOT NULL AND TRIM(availability) NOT IN ('', '[]') "
            + "AND city IS NOT NULL AND city = ?", lot.get("city"));

        List<Map<String, Object>> available = new ArrayList<>();
        for (Map<String, Object> v : candidates) {
            Object av = v.get("availability");
            if (availability.isAvailableNow(av == null ? null : av.toString())) {
                available.add(v);
            }
        }
        Double sLat = toDouble(lot.get("s_lat"));
        Double sLon = toDouble(lot.get("s_lon"));
        if (sLat != null && sLon != null) {
            available.sort((a, b) -> Double.compare(
                distance(a, sLat, sLon), distance(b, sLat, sLon)));
        }
        List<Map<String, Object>> targets = available.size() > MAX_VOLUNTEERS_PER_LOT
            ? available.subList(0, MAX_VOLUNTEERS_PER_LOT) : available;
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
        log.info("[needs_match] lot " + lotId + " pinged " + targets.size() + " available volunteers");
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
        return kz.savefood.util.Geo.haversineMeters(lat1, lon1, lat2, lon2);
    }

    private static Double toDouble(Object v) {
        return v instanceof Number n ? n.doubleValue() : null;
    }
}
