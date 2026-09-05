package ru.savefood.match;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.savefood.util.FoodCategories;

@Repository
public class MatchingCandidateRepository {
    private final JdbcTemplate jdbc;
    private final MatchingWorkProperties limits;
    public MatchingCandidateRepository(JdbcTemplate jdbc, MatchingWorkProperties limits) {
        this.jdbc = jdbc;
        this.limits = limits;
    }
    public List<Map<String, Object>> recipients(Object city, String category) {
        List<String> keywords = FoodCategories.KEYWORDS.get(category == null ? "" : category.strip());
        if (keywords == null) return List.of();
        List<Object> args = new ArrayList<>();
        args.add(city);
        String mentions = String.join(" OR ", keywords.stream().map(k -> "LOWER(np.preferences) LIKE ?").toList());
        keywords.forEach(keyword -> args.add("%" + keyword + "%"));
        args.add(limits.getRecipientCandidates());
        // SQL narrows by existing category keywords; Java retains the exact restriction rules.
        return query("SELECT n.id AS needy_id, np.preferences, "
            + "COALESCE(np.geo_push_enabled, TRUE) AS geo_push_enabled "
            + "FROM needy_profile np JOIN needy n ON n.id = np.needy_id "
            + "WHERE n.status IN ('active', 'pending', 'approved', 'rejected') "
            + "AND np.preferences IS NOT NULL AND TRIM(np.preferences) <> '' "
            + "AND np.city IS NOT NULL AND np.city = ? AND (" + mentions + ") "
            + "ORDER BY np.needy_id LIMIT ?", args);
    }
    public List<Map<String, Object>> volunteers(Object city, Double lat, Double lon) {
        List<Object> args = new ArrayList<>();
        args.add(city);
        String order = "id";
        if (lat != null && lon != null) {
            // Great-circle proximity, monotonic with haversine; unknown coordinates sort last.
            order = "(SIN(RADIANS(lat)) * SIN(RADIANS(CAST(? AS double precision))) "
                + "+ COS(RADIANS(lat)) * COS(RADIANS(CAST(? AS double precision))) "
                + "* COS(RADIANS(lon - CAST(? AS double precision)))) DESC NULLS LAST, id";
            args.add(lat);
            args.add(lat);
            args.add(lon);
        }
        args.add(limits.getVolunteerCandidates());
        return query("SELECT id, lat, lon, availability FROM volunteers "
            + "WHERE availability IS NOT NULL AND TRIM(availability) NOT IN ('', '[]') "
            + "AND city IS NOT NULL AND city = ? ORDER BY " + order + " LIMIT ?", args);
    }
    private List<Map<String, Object>> query(String sql, List<Object> args) {
        return jdbc.query(connection -> {
            var statement = connection.prepareStatement(sql);
            statement.setQueryTimeout(limits.getCandidateQueryTimeoutSeconds());
            for (int i = 0; i < args.size(); i++) statement.setObject(i + 1, args.get(i));
            return statement;
        }, new ColumnMapRowMapper());
    }
}
