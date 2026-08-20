package ru.savefood.impact;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import ru.savefood.cache.CacheService;
import ru.savefood.esg.EsgService;
import ru.savefood.gamification.Gamification;
import ru.savefood.util.Clamp;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Java port of backend/impact.py — the public impact/PR surface (city dashboard,
 * leaderboards, anonymous delivery feed, embeddable SVG badges). Everything here
 * is intentionally unauthenticated and exposes only aggregates / first names /
 * moderation-approved photos — no personal data. "Rescued" is defined once in
 * {@link EsgService} (confirmed hand-over or a fulfilled ticket, §56).
 */
@RestController
public class ImpactController {

    private final JdbcTemplate jdbc;
    private final EsgService esg;
    private final CacheService cache;
    private final String deliveryPhotoUploadDir;
    private final String legacyVolunteerUploadDir;

    public ImpactController(JdbcTemplate jdbc, EsgService esg, CacheService cache,
                            @Value("${savefood.delivery-photo-upload-dir}") String deliveryPhotoUploadDir,
                            @Value("${savefood.volunteer-upload-dir}") String legacyVolunteerUploadDir) {
        this.jdbc = jdbc;
        this.esg = esg;
        this.cache = cache;
        this.deliveryPhotoUploadDir = deliveryPhotoUploadDir;
        this.legacyVolunteerUploadDir = legacyVolunteerUploadDir;
    }

    @GetMapping("/impact/summary")
    public Map<String, Object> summary(@RequestParam(defaultValue = "12") int months) {
        int m = Clamp.clamp(months, 1, 36);
        return cache.cachedJson("impact:summary:" + m, CacheService.TTL_STATS, () -> computeSummary(m));
    }

    private Map<String, Object> computeSummary(int months) {
        Map<String, Object> report = esg.globalReport(months);
        long deliveries = count("SELECT COUNT(*) FROM tickets WHERE status = 'fulfilled'");
        long activeVolunteers = count(
            "SELECT COUNT(DISTINCT volunteer_id) FROM volunteer_routes "
            + "WHERE started_at >= CURRENT_TIMESTAMP - INTERVAL '30 days'");
        long shops = count("SELECT COUNT(*) FROM shops");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("methodology", report.get("methodology"));
        out.put("period_months", report.get("period_months"));
        out.put("totals", report.get("totals"));
        out.put("by_month", report.get("by_month"));
        out.put("by_category", report.get("by_category"));
        out.put("deliveries_completed", deliveries);
        out.put("active_volunteers", activeVolunteers);
        out.put("partner_shops", shops);
        return out;
    }

    @GetMapping("/impact/cities")
    public List<Map<String, Object>> cityLeaderboard(@RequestParam(defaultValue = "12") int months) {
        int m = Clamp.clamp(months, 1, 36);
        return jdbc.queryForList(
            "SELECT COALESCE(NULLIF(TRIM(l.city), ''), 'Без города') AS city, "
            + "COALESCE(SUM(" + EsgService.RESCUED_KG_SQL + "), 0) AS kg, COUNT(*) AS lots "
            + "FROM lots l WHERE " + EsgService.RESCUED_SQL + " AND " + EsgService.RESCUED_AT_SQL
            + " >= CURRENT_TIMESTAMP - (? * INTERVAL '1 month') "
            + "GROUP BY 1 ORDER BY kg DESC LIMIT 10", m);
    }

    @GetMapping("/impact/volunteers")
    public List<Map<String, Object>> volunteerLeaderboard() {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT v.id, v.name, COUNT(t.id) AS deliveries, COALESCE(("
            + "SELECT " + EsgService.FULFILLED_TICKET_KG_SQL + " FROM tickets t "
            + "JOIN lots l ON l.id = t.lot_id "
            + "WHERE t.assigned_volunteer_id = v.id AND t.status = 'fulfilled'"
            + "), 0) AS kg FROM volunteers v "
            + "JOIN tickets t ON t.assigned_volunteer_id = v.id AND t.status = 'fulfilled' "
            + "GROUP BY v.id, v.name ORDER BY deliveries DESC, kg DESC LIMIT 10");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            int deliveries = ((Number) r.get("deliveries")).intValue();
            double kg = toDouble(r.get("kg"));
            String name = r.get("name") == null ? "Волонтёр" : r.get("name").toString();
            String firstName = name.isBlank() ? "Волонтёр" : name.trim().split("\\s+")[0];
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", r.get("id"));
            // First word only — a full name must not leak publicly.
            e.put("name", firstName);
            e.put("deliveries", deliveries);
            e.put("kg", kg);
            e.put("level", Gamification.computeLevel(deliveries, kg).get("code"));
            out.add(e);
        }
        return out;
    }

    @GetMapping("/impact/teams")
    public List<Map<String, Object>> teamLeaderboard() {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT tm.id, tm.name, COUNT(DISTINCT v.id) AS members, COUNT(t.id) AS deliveries, COALESCE(("
            + "SELECT " + EsgService.FULFILLED_TICKET_KG_SQL + " FROM tickets t "
            + "JOIN lots l ON l.id = t.lot_id JOIN volunteers v2 ON v2.id = t.assigned_volunteer_id "
            + "WHERE v2.team_id = tm.id AND t.status = 'fulfilled'"
            + "), 0) AS kg FROM teams tm JOIN volunteers v ON v.team_id = tm.id "
            + "LEFT JOIN tickets t ON t.assigned_volunteer_id = v.id AND t.status = 'fulfilled' "
            + "GROUP BY tm.id, tm.name ORDER BY deliveries DESC, kg DESC LIMIT 10");
        for (Map<String, Object> r : rows) {
            r.put("kg", toDouble(r.get("kg")));
        }
        return rows;
    }

    @GetMapping(value = "/impact/widget.svg", produces = "image/svg+xml")
    public ResponseEntity<String> globalWidget() {
        String svg = cache.cachedJson("impact:widget:global", CacheService.TTL_STATS, () -> {
            Map<String, Object> t = totals(esg.globalReport(12));
            return badgeSvg("Вместе с SaveFood", toDouble(t.get("kg")),
                toLong(t.get("meals")), toDouble(t.get("co2_kg")));
        });
        return svgResponse(svg);
    }

    @GetMapping(value = "/impact/widget/{shopId}.svg", produces = "image/svg+xml")
    public ResponseEntity<String> shopWidget(@PathVariable int shopId) {
        String svg = cache.cachedJson("impact:widget:shop:" + shopId, CacheService.TTL_STATS, () -> {
            String name = jdbc.query("SELECT name FROM shops WHERE id = ?",
                (rs, n) -> rs.getString("name"), shopId).stream().findFirst().orElse(null);
            if (name == null || name.isBlank()) {
                name = "Наш магазин";
            }
            Map<String, Object> t = totals(esg.shopReport(shopId, 12));
            return badgeSvg(name, toDouble(t.get("kg")), toLong(t.get("meals")), toDouble(t.get("co2_kg")));
        });
        return svgResponse(svg);
    }

    @GetMapping("/impact/feed")
    public List<Map<String, Object>> feed(@RequestParam(defaultValue = "20") int limit) {
        int lim = Clamp.clamp(limit, 1, 50);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT t.id AS ticket_id, t.delivery_photo, t.fulfilled_at, l.category, l.city "
                + "FROM tickets t LEFT JOIN lots l ON l.id = t.lot_id "
                + "WHERE t.status = 'fulfilled' AND t.delivery_photo IS NOT NULL "
                + "AND t.delivery_photo_status = 'approved' "
                + "ORDER BY t.fulfilled_at DESC NULLS LAST LIMIT ?", lim)) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("photo", "/impact/delivery_photos/" + r.get("ticket_id") + "/image");
            e.put("date", r.get("fulfilled_at"));
            // A photo decision moderates the image only. Recipient-provided ticket
            // text is never a public-feed caption, including for legacy tickets.
            e.put("category", r.get("category"));
            e.put("city", r.get("city"));
            out.add(e);
        }
        return out;
    }

    /** Public only after moderation and fulfilment; pending proof files stay private. */
    @GetMapping("/impact/delivery_photos/{ticketId}/image")
    public ResponseEntity<Resource> deliveryPhoto(@PathVariable int ticketId) {
        List<String> refs = jdbc.query(
            "SELECT delivery_photo FROM tickets WHERE id = ? AND status = 'fulfilled' "
            + "AND delivery_photo_status = 'approved' AND delivery_photo IS NOT NULL",
            (rs, n) -> rs.getString("delivery_photo"), ticketId);
        if (refs.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Path path = deliveryPhotoPath(refs.get(0));
        if (path == null || !Files.isRegularFile(path)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
            .contentType(mediaTypeFor(path.getFileName().toString()))
            .header("Cache-Control", "public, max-age=3600")
            .body(new FileSystemResource(path));
    }

    // ── badge rendering (impact.py {@code _badge_svg}) ────────────────────────────

    private static String badgeSvg(String title, double kg, long meals, double co2) {
        String titleS = htmlEscape(title.length() > 40 ? title.substring(0, 40) : title);
        String kgS = htmlEscape(fmtKg(kg));
        String mealsS = String.format(Locale.US, "%,d", meals).replace(',', ' ');
        String co2S = htmlEscape(fmtKg(co2));
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"320\" height=\"120\" viewBox=\"0 0 320 120\" "
            + "role=\"img\" aria-label=\"SaveFood impact\">\n"
            + "  <defs>\n"
            + "    <linearGradient id=\"g\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\"1\">\n"
            + "      <stop offset=\"0\" stop-color=\"#2e7d32\"/><stop offset=\"1\" stop-color=\"#66bb6a\"/>\n"
            + "    </linearGradient>\n"
            + "  </defs>\n"
            + "  <rect width=\"320\" height=\"120\" rx=\"14\" fill=\"url(#g)\"/>\n"
            + "  <path d=\"M20 35c1-10 7-16 19-17-1 11-7 17-19 17Zm0 4c3-8 8-13 15-17\" "
            + "fill=\"none\" stroke=\"#fff\" stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>\n"
            + "  <text x=\"48\" y=\"34\" font-family=\"Arial, sans-serif\" font-size=\"15\" font-weight=\"700\" "
            + "fill=\"#fff\">" + titleS + "</text>\n"
            + "  <text x=\"20\" y=\"74\" font-family=\"Arial, sans-serif\" font-size=\"30\" font-weight=\"800\" "
            + "fill=\"#fff\">" + kgS + "</text>\n"
            + "  <text x=\"20\" y=\"96\" font-family=\"Arial, sans-serif\" font-size=\"12\" fill=\"#e8f5e9\">"
            + "спасено еды · " + mealsS + " порций · −" + co2S + " CO₂e</text>\n"
            + "  <text x=\"300\" y=\"113\" text-anchor=\"end\" font-family=\"Arial, sans-serif\" font-size=\"10\" "
            + "fill=\"#c8e6c9\">SaveFood</text>\n"
            + "</svg>";
    }

    private static String fmtKg(double kg) {
        double v = Math.max(0, kg);
        if (v >= 1000) {
            String s = String.format(Locale.US, "%.1f т", v / 1000);
            return s.replace(".0 т", " т");
        }
        return (long) Math.round(v) + " кг";
    }

    private ResponseEntity<String> svgResponse(String svg) {
        return ResponseEntity.ok()
            .contentType(MediaType.valueOf("image/svg+xml"))
            .header("Cache-Control", "public, max-age=3600")
            .body(svg);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> totals(Map<String, Object> report) {
        Object t = report.get("totals");
        return t instanceof Map ? (Map<String, Object>) t : Map.of();
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    private static double toDouble(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static long toLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }

    private static String htmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#x27;");
    }

    private Path deliveryPhotoPath(String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        String dir = ref.startsWith("/delivery_photos/") ? deliveryPhotoUploadDir
            : ref.startsWith("/volunteer_uploads/") ? legacyVolunteerUploadDir : null;
        if (dir == null) {
            return null;
        }
        try {
            Path base = Paths.get(dir).toAbsolutePath().normalize();
            Path candidate = base.resolve(Paths.get(ref).getFileName()).normalize();
            return candidate.startsWith(base) ? candidate : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static MediaType mediaTypeFor(String filename) {
        String f = filename.toLowerCase(Locale.ROOT);
        if (f.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (f.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        }
        return MediaType.IMAGE_JPEG;
    }
}
