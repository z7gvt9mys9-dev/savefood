package ru.savefood.esg;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.savefood.util.Clamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Port of backend/esg.py. Holds the rescued-food SQL fragments shared across the
 * API ({@link #RESCUED_SQL}, {@link #RESCUED_KG_SQL}) and the platform-wide
 * {@link #globalReport(int)} used by GET /admin/esg.
 *
 * <p>Methodology v1.1 (FAO Food Wastage Footprint 2013 category averages + WFP
 * ~420 g/meal). Coefficients must never change silently under a published
 * report, so they are constants here exactly as in the Python module.
 */
@Service
public class EsgService {

    public static final String METHODOLOGY =
        "SaveFood ESG v1.1 (FAO Food Wastage Footprint 2013, средние по категориям; "
        + "спасённым считается лот с подтверждённой передачей)";

    // kg CO2e prevented per kg of rescued food, by lot category. Insertion order
    // preserved so the generated SQL CASE is stable.
    private static final Map<String, Double> CO2_PER_KG = new LinkedHashMap<>();
    static {
        CO2_PER_KG.put("Выпечка", 1.3);
        CO2_PER_KG.put("Овощи/Фрукты", 0.9);
        CO2_PER_KG.put("Готовая еда", 3.0);
        CO2_PER_KG.put("Молочные продукты", 2.8);
    }
    private static final double CO2_DEFAULT = 2.5;   // uncategorized lots
    private static final double KG_PER_MEAL = 0.42;

    /** Lots actually rescued: shop-confirmed hand-over, or ≥1 fulfilled ticket. */
    public static final String RESCUED_SQL =
        "(l.status = 'confirmed' OR EXISTS "
        + "(SELECT 1 FROM tickets _t WHERE _t.lot_id = l.id AND _t.status = 'fulfilled'))";

    public static final String RESCUED_AT_SQL = "COALESCE(l.taken_at, l.created_at)";

    /** Rescued kg: whole lot if confirmed, else only fulfilled-ticket units. */
    public static final String RESCUED_KG_SQL =
        "(CASE WHEN l.status = 'confirmed' "
        + "THEN l.initial_quantity * l.unit_weight_kg "
        + "ELSE (SELECT COALESCE(SUM(_ft.quantity), 0) FROM tickets _ft "
        + "WHERE _ft.lot_id = l.id AND _ft.status = 'fulfilled') * l.unit_weight_kg "
        + "END)";

    private final JdbcTemplate jdbc;

    public EsgService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** SQL CASE mirroring CO2_PER_KG so monthly rows are weighted per category. */
    private static String co2SqlCase() {
        StringBuilder whens = new StringBuilder();
        for (Map.Entry<String, Double> e : CO2_PER_KG.entrySet()) {
            String cat = e.getKey().replace("'", "''");
            whens.append("WHEN l.category = '").append(cat).append("' THEN ")
                 .append(e.getValue()).append(' ');
        }
        return "(CASE " + whens + "ELSE " + CO2_DEFAULT + " END)";
    }

    private static double co2(double kg, String category) {
        return kg * CO2_PER_KG.getOrDefault(category == null ? "" : category, CO2_DEFAULT);
    }

    private static double toDouble(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static int toInt(Object v) {
        return v instanceof Number n ? n.intValue() : 0;
    }

    /** round(x, 1) with HALF_EVEN to match Python's round(). */
    private static double round1(double x) {
        return BigDecimal.valueOf(x).setScale(1, RoundingMode.HALF_EVEN).doubleValue();
    }

    /** Port of esg.py _build_report — assembles totals / by_category / by_month. */
    private Map<String, Object> buildReport(List<Map<String, Object>> rowsCat,
                                            List<Map<String, Object>> rowsMonth, int months) {
        List<Map<String, Object>> byCategory = new ArrayList<>();
        double totalKg = 0.0;
        double totalCo2 = 0.0;
        int totalLots = 0;
        for (Map<String, Object> r : rowsCat) {
            double kg = toDouble(r.get("kg"));
            String category = (String) r.get("category");
            double co2 = round1(co2(kg, category));
            int lots = toInt(r.get("lots"));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("category", category == null ? "Без категории" : category);
            entry.put("kg", kg);
            entry.put("co2_kg", co2);
            entry.put("lots", lots);
            byCategory.add(entry);
            totalKg += kg;
            totalCo2 += co2;
            totalLots += lots;
        }

        List<Map<String, Object>> byMonth = new ArrayList<>();
        for (Map<String, Object> r : rowsMonth) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("month", r.get("month"));
            entry.put("kg", toDouble(r.get("kg")));
            // monthly CO2 uses the blended factor of that month's category mix
            entry.put("co2_kg", round1(toDouble(r.get("co2_kg"))));
            byMonth.add(entry);
        }

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("kg", round1(totalKg));
        totals.put("co2_kg", round1(totalCo2));
        totals.put("meals", (int) (totalKg / KG_PER_MEAL));
        totals.put("lots", totalLots);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("methodology", METHODOLOGY);
        report.put("period_months", months);
        report.put("totals", totals);
        report.put("by_category", byCategory);
        report.put("by_month", byMonth);
        return report;
    }

    /** Single-shop ESG report (esg.py {@code shop_report}) — no top_shops block. */
    public Map<String, Object> shopReport(int shopId, int monthsArg) {
        int months = Clamp.clamp(monthsArg, 1, 36);

        List<Map<String, Object>> rowsCat = jdbc.queryForList(
            "SELECT l.category, COALESCE(SUM(" + RESCUED_KG_SQL + "), 0) AS kg, COUNT(*) AS lots "
            + "FROM lots l "
            + "WHERE l.shop_id = ? AND " + RESCUED_SQL + " AND " + RESCUED_AT_SQL
            + " >= CURRENT_TIMESTAMP - make_interval(months => ?) "
            + "GROUP BY l.category ORDER BY kg DESC",
            shopId, months);

        List<Map<String, Object>> rowsMonth = jdbc.queryForList(
            "SELECT to_char(date_trunc('month', " + RESCUED_AT_SQL + "), 'YYYY-MM') AS month, "
            + "COALESCE(SUM(" + RESCUED_KG_SQL + "), 0) AS kg, "
            + "COALESCE(SUM(" + RESCUED_KG_SQL + " * " + co2SqlCase() + "), 0) AS co2_kg "
            + "FROM lots l "
            + "WHERE l.shop_id = ? AND " + RESCUED_SQL + " AND " + RESCUED_AT_SQL
            + " >= CURRENT_TIMESTAMP - make_interval(months => ?) "
            + "GROUP BY 1 ORDER BY 1",
            shopId, months);

        return buildReport(rowsCat, rowsMonth, months);
    }

    /**
     * Render a {@link #shopReport} as a tax-filing-friendly CSV (esg.py
     * {@code report_to_csv}): a totals block, a per-category breakdown and a
     * per-month series, semicolon-separated for the KZ/RU Excel locale.
     */
    @SuppressWarnings("unchecked")
    public String reportToCsv(Map<String, Object> report, String shopName) {
        Map<String, Object> totals = (Map<String, Object>) report.getOrDefault("totals", Map.of());
        StringBuilder sb = new StringBuilder();
        csvRow(sb, "SaveFood — отчёт о переданной на благотворительность еде");
        csvRow(sb, "Организация", shopName);
        csvRow(sb, "Период, мес.", str(report.get("period_months")));
        csvRow(sb, "Методология", str(report.get("methodology")));
        csvRow(sb);
        csvRow(sb, "Итого, кг", "Итого, порций", "Итого, кг CO2e", "Лотов");
        csvRow(sb, str(totals.get("kg")), str(totals.get("meals")),
            str(totals.get("co2_kg")), str(totals.get("lots")));
        csvRow(sb);
        csvRow(sb, "Категория", "Кг", "Кг CO2e", "Лотов");
        for (Map<String, Object> c : (List<Map<String, Object>>) report.getOrDefault("by_category", List.of())) {
            csvRow(sb, str(c.get("category")), str(c.get("kg")), str(c.get("co2_kg")), str(c.get("lots")));
        }
        csvRow(sb);
        csvRow(sb, "Месяц", "Кг", "Кг CO2e");
        for (Map<String, Object> m : (List<Map<String, Object>>) report.getOrDefault("by_month", List.of())) {
            csvRow(sb, str(m.get("month")), str(m.get("kg")), str(m.get("co2_kg")));
        }
        return sb.toString();
    }

    private static void csvRow(StringBuilder sb, String... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                sb.append(';');
            }
            sb.append(csvCell(cells[i]));
        }
        sb.append("\r\n");
    }

    private static String csvCell(String v) {
        if (v == null) {
            return "";
        }
        if (v.contains(";") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }

    /** Platform-wide report for the admin panel + top contributing shops. */
    public Map<String, Object> globalReport(int monthsArg) {
        int months = Clamp.clamp(monthsArg, 1, 36);

        List<Map<String, Object>> rowsCat = jdbc.queryForList(
            "SELECT l.category, COALESCE(SUM(" + RESCUED_KG_SQL + "), 0) AS kg, COUNT(*) AS lots "
            + "FROM lots l "
            + "WHERE " + RESCUED_SQL + " AND " + RESCUED_AT_SQL
            + " >= CURRENT_TIMESTAMP - make_interval(months => ?) "
            + "GROUP BY l.category ORDER BY kg DESC",
            months);

        List<Map<String, Object>> rowsMonth = jdbc.queryForList(
            "SELECT to_char(date_trunc('month', " + RESCUED_AT_SQL + "), 'YYYY-MM') AS month, "
            + "COALESCE(SUM(" + RESCUED_KG_SQL + "), 0) AS kg, "
            + "COALESCE(SUM(" + RESCUED_KG_SQL + " * " + co2SqlCase() + "), 0) AS co2_kg "
            + "FROM lots l "
            + "WHERE " + RESCUED_SQL + " AND " + RESCUED_AT_SQL
            + " >= CURRENT_TIMESTAMP - make_interval(months => ?) "
            + "GROUP BY 1 ORDER BY 1",
            months);

        List<Map<String, Object>> topShops = jdbc.queryForList(
            "SELECT s.id, s.name, COALESCE(SUM(" + RESCUED_KG_SQL + "), 0) AS kg "
            + "FROM lots l JOIN shops s ON s.id = l.shop_id "
            + "WHERE " + RESCUED_SQL + " AND " + RESCUED_AT_SQL
            + " >= CURRENT_TIMESTAMP - make_interval(months => ?) "
            + "GROUP BY s.id, s.name ORDER BY kg DESC LIMIT 10",
            months);

        Map<String, Object> report = buildReport(rowsCat, rowsMonth, months);
        report.put("top_shops", topShops);
        return report;
    }
}
