package kz.savefood.billing;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Tariff plans and their entitlements, ported from backend/billing.py PLANS.
 *
 * <ul>
 *   <li>basic — manual lots only, 20 lots per calendar month</li>
 *   <li>pro — OCR receipt parsing, ESG reports, unlimited lots</li>
 *   <li>enterprise — everything in pro (API connectors sold/managed manually)</li>
 * </ul>
 */
public final class Plans {
    private Plans() {
    }

    /** One plan's entitlements. {@code monthlyLotLimit == null} means unlimited. */
    public record Plan(String label, Integer monthlyLotLimit, boolean ocr, boolean esg, boolean api) {
    }

    public static final String DEFAULT_PLAN = "basic";

    // Insertion order preserved to match the Python dict.
    public static final Map<String, Plan> PLANS = new LinkedHashMap<>();
    static {
        PLANS.put("basic", new Plan("Базовый", 20, false, false, false));
        PLANS.put("pro", new Plan("Профи", null, true, true, false));
        PLANS.put("enterprise", new Plan("Enterprise", null, true, true, true));
    }

    /** Human-readable feature names for upgrade error messages. */
    public static final Map<String, String> FEATURE_LABELS = Map.of(
        "ocr", "Распознавание чеков (OCR)",
        "esg", "ESG-отчёты",
        "api", "Партнёрский API и вебхуки");

    public static final Set<String> KEYS = PLANS.keySet();

    public static boolean isValid(String plan) {
        return plan != null && PLANS.containsKey(plan);
    }

    /** Returns the plan's entitlements, falling back to {@code basic} for unknowns. */
    public static Plan get(String plan) {
        return PLANS.getOrDefault(plan, PLANS.get(DEFAULT_PLAN));
    }

    public static boolean hasFeature(String plan, String feature) {
        Plan p = get(plan);
        return switch (feature) {
            case "ocr" -> p.ocr();
            case "esg" -> p.esg();
            case "api" -> p.api();
            default -> false;
        };
    }
}
