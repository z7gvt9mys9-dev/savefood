package ru.savefood.billing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.savefood.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Port of backend/billing.py — plan lookup, feature gating and the monthly
 * lot-quota guard. Entitlement data lives in {@link Plans}; this service applies
 * it against the live {@code shops}/{@code lots} tables.
 */
@Service
public class BillingService {

    /**
     * Advisory-lock namespace (first key of {@code pg_advisory_xact_lock}) for the
     * monthly lot quota — same arbitrary constant as billing.py {@code _LOT_QUOTA_LOCK_NS}.
     */
    private static final int LOT_QUOTA_LOCK_NS = 0x10720001;

    private final JdbcTemplate jdbc;

    public BillingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String getShopPlan(int shopId) {
        List<String> plans = jdbc.query("SELECT plan FROM shops WHERE id = ?",
            (rs, n) -> rs.getString("plan"), shopId);
        String plan = plans.isEmpty() ? null : plans.get(0);
        if (plan == null) {
            plan = Plans.DEFAULT_PLAN;
        }
        return Plans.PLANS.containsKey(plan) ? plan : Plans.DEFAULT_PLAN;
    }

    public int lotsCreatedThisMonth(int shopId) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM lots WHERE shop_id = ? "
            + "AND created_at >= date_trunc('month', CURRENT_TIMESTAMP)",
            Integer.class, shopId);
        return n == null ? 0 : n;
    }

    /** Raise 402 unless the shop's plan includes {@code feature}. Returns the plan. */
    public String requireFeature(int shopId, String feature) {
        String plan = getShopPlan(shopId);
        if (!Plans.hasFeature(plan, feature)) {
            String label = Plans.FEATURE_LABELS.getOrDefault(feature, feature);
            throw new ApiException(402,
                "«" + label + "» доступно на тарифе «Профи» и выше. Текущий тариф: «"
                + Plans.get(plan).label() + "».");
        }
        return plan;
    }

    /**
     * Quota half of billing.py {@code lot_quota_guard}: takes a transaction-scoped
     * advisory lock keyed on the shop, then rejects (402) if the monthly limit is
     * already reached. Must run inside the caller's {@code @Transactional} lot-create
     * method so the lock is held across the subsequent insert — a concurrent
     * same-shop create blocks here until this transaction commits and the new row
     * is visible to its COUNT. Unlimited plans skip the lock entirely.
     */
    public void acquireLotQuota(int shopId) {
        String plan = getShopPlan(shopId);
        Integer limit = Plans.get(plan).monthlyLotLimit();
        if (limit == null) {
            return; // unlimited plan: nothing to serialize
        }
        jdbc.queryForList("SELECT pg_advisory_xact_lock(?, ?)", LOT_QUOTA_LOCK_NS, shopId);
        int used = lotsCreatedThisMonth(shopId);
        if (used >= limit) {
            throw new ApiException(402,
                "Лимит тарифа «" + Plans.get(plan).label() + "» исчерпан: " + used + "/" + limit
                + " лотов в этом месяце. Перейдите на тариф «Профи» для безлимитной публикации.");
        }
    }

    /** Plan + usage payload for the shop dashboard (billing.py {@code plan_summary}). */
    public Map<String, Object> planSummary(int shopId) {
        String plan = getShopPlan(shopId);
        Plans.Plan p = Plans.get(plan);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("plan", plan);
        out.put("label", p.label());
        out.put("ocr", p.ocr());
        out.put("esg", p.esg());
        out.put("api", p.api());
        out.put("monthly_lot_limit", p.monthlyLotLimit());
        out.put("lots_used_this_month", lotsCreatedThisMonth(shopId));
        return out;
    }
}
