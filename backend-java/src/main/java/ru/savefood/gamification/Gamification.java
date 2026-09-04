package ru.savefood.gamification;
import java.util.LinkedHashMap;
import java.util.Map;
public final class Gamification {
    private Gamification() {
    }
    private static final double POINTS_PER_DELIVERY = 10.0;
    private static final double POINTS_PER_KG = 1.0;
    /** (code, points threshold) ascending; i18n labels live on the frontend. */
    private static final String[] LEVEL_CODES = {"novice", "helper", "courier", "guardian", "city_hero"};
    private static final int[] LEVEL_THRESHOLDS = {0, 50, 200, 600, 1500};
    public static double computePoints(int deliveries, double kg) {
        return deliveries * POINTS_PER_DELIVERY + kg * POINTS_PER_KG;
    }
    /** Current level + progress towards the next one (for the progress bar). */
    public static Map<String, Object> computeLevel(int deliveries, double kg) {
        double points = computePoints(Math.max(0, deliveries), Math.max(0.0, kg));
        int currentIdx = 0;
        Integer nextIdx = null;
        for (int i = 0; i < LEVEL_CODES.length; i++) {
            if (points >= LEVEL_THRESHOLDS[i]) {
                currentIdx = i;
                nextIdx = i + 1 < LEVEL_CODES.length ? i + 1 : null;
            }
        }
        double progress;
        double toNext;
        if (nextIdx == null) {
            progress = 1.0;
            toNext = 0.0;
        } else {
            double span = LEVEL_THRESHOLDS[nextIdx] - LEVEL_THRESHOLDS[currentIdx];
            progress = span > 0 ? (points - LEVEL_THRESHOLDS[currentIdx]) / span : 1.0;
            toNext = Math.max(0.0, LEVEL_THRESHOLDS[nextIdx] - points);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", LEVEL_CODES[currentIdx]);
        out.put("points", round(points, 1));
        out.put("next_code", nextIdx == null ? null : LEVEL_CODES[nextIdx]);
        out.put("points_to_next", round(toNext, 1));
        out.put("progress", round(Math.min(1.0, Math.max(0.0, progress)), 3));
        return out;
    }
    private static double round(double v, int places) {
        double factor = Math.pow(10, places);
        return Math.round(v * factor) / factor;
    }
}
