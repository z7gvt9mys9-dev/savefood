package ru.savefood.forecast;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.savefood.config.BusinessTimeConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
@Service
public class ForecastService {
    private static final int BASIS_WEEKS = 8;
    private static final double MIN_AVG_KG = 1.0;
    private static final Map<Integer, String> ISO_DOW_NAMES_RU = Map.of(
        1, "понедельник", 2, "вторник", 3, "среда", 4, "четверг",
        5, "пятница", 6, "суббота", 7, "воскресенье");
    private final JdbcTemplate jdbc;
    private final Clock clock;
    @Autowired
    public ForecastService(JdbcTemplate jdbc, Clock businessClock) {
        this.jdbc = jdbc;
        this.clock = businessClock;
    }
    public ForecastService(JdbcTemplate jdbc, String localTz) {
        this(jdbc, BusinessTimeConfiguration.systemClock(localTz));
    }
    public Map<String, Object> shopForecast(int shopId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT EXTRACT(ISODOW FROM l.created_at AT TIME ZONE ?)::int AS isodow, l.category, "
            + "COALESCE(SUM(l.initial_quantity * l.unit_weight_kg), 0) AS kg "
            + "FROM lots l "
            + "WHERE l.shop_id = ? "
            + "AND l.created_at >= CURRENT_TIMESTAMP - make_interval(weeks => ?) "
            + "GROUP BY 1, 2",
            clock.getZone().getId(), shopId, BASIS_WEEKS);
        int todayIsoDow = isoDowAt(Instant.now(clock), clock.getZone());
        return buildForecast(rows, todayIsoDow, BASIS_WEEKS);
    }
    static int isoDowAt(Instant instant, ZoneId zone) {
        return instant.atZone(zone).getDayOfWeek().getValue();
    }
    static Map<String, Object> buildForecast(List<Map<String, Object>> rows, int todayIsoDow,
                                             int basisWeeks) {
        int tomorrow = todayIsoDow % 7 + 1;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("basis_weeks", basisWeeks);
        out.put("today", dayBlock(rows, todayIsoDow, basisWeeks));
        out.put("tomorrow", dayBlock(rows, tomorrow, basisWeeks));
        return out;
    }
    private static Map<String, Object> dayBlock(List<Map<String, Object>> rows, int isodow,
                                                int basisWeeks) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (toInt(r.get("isodow")) != isodow) {
                continue;
            }
            double avg = toDouble(r.get("kg")) / basisWeeks;
            if (avg >= MIN_AVG_KG) {
                Map<String, Object> item = new LinkedHashMap<>();
                Object cat = r.get("category");
                item.put("category", cat == null ? "Без категории" : cat);
                item.put("avg_kg", round1(avg));
                items.add(item);
            }
        }
        items.sort((a, b) -> Double.compare((double) b.get("avg_kg"), (double) a.get("avg_kg")));
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("isodow", isodow);
        block.put("day_name", ISO_DOW_NAMES_RU.get(isodow));
        block.put("items", items);
        return block;
    }
    private static int toInt(Object v) {
        return v instanceof Number n ? n.intValue() : 0;
    }
    private static double toDouble(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }
    private static double round1(double x) {
        return BigDecimal.valueOf(x).setScale(1, RoundingMode.HALF_EVEN).doubleValue();
    }
}
