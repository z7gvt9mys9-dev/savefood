"""ESG impact reporting: kg of food rescued → CO2-equivalent prevented.

The selling point of the «Профи»/«Enterprise» plans: a shop gets a report it
can publish (social media) or file (annual ESG reporting). The methodology is
versioned — coefficients must never change silently under an already
published report.

Methodology v1.1: category-average emission factors (kg CO2e per kg of food,
production+disposal, rounded from FAO «Food Wastage Footprint», 2013) and the
WFP convention of ~420 g per meal. Rescued = lots whose hand-over is actually
evidenced: the shop confirmed the transfer (status 'confirmed') OR at least
one ticket on the lot was fulfilled (QR-verified delivery / self-pickup).
A merely claimed lot ('taken', i.e. «волонтёр нажал кнопку») no longer counts —
the report feeds the tax CSV (§50), so it must not run ahead of reality.
Dated by the moment a volunteer claimed the lot (COALESCE(taken_at, created_at)).
"""
import csv
import io
from typing import Any, Dict, List, Optional

from backend.database import get_db_cursor

METHODOLOGY = (
    "SaveFood ESG v1.1 (FAO Food Wastage Footprint 2013, средние по категориям; "
    "спасённым считается лот с подтверждённой передачей)"
)

# kg CO2e prevented per kg of rescued food, by lot category.
CO2_PER_KG = {
    "Выпечка": 1.3,
    "Овощи/Фрукты": 0.9,
    "Готовая еда": 3.0,
    "Молочные продукты": 2.8,
}
CO2_DEFAULT = 2.5  # uncategorized lots
KG_PER_MEAL = 0.42

# Lots actually rescued: hand-over confirmed by the shop, or at least one
# fulfilled (QR-verified) ticket on the lot. 'taken' alone is just a button
# press — counting it would let the tax CSV report undelivered food.
# Shared with every other "rescued kg" surface (impact.py, /stats, admin stats)
# so the public numbers can never drift from the published methodology.
# Expects the lots table to be aliased as `l` in the enclosing query.
RESCUED_SQL = (
    "(l.status = 'confirmed' OR EXISTS "
    "(SELECT 1 FROM tickets _t WHERE _t.lot_id = l.id AND _t.status = 'fulfilled'))"
)
RESCUED_AT_SQL = "COALESCE(l.taken_at, l.created_at)"
RESCUED_KG_SQL = "(l.initial_quantity * l.unit_weight_kg)"


def _co2(kg: float, category: Optional[str]) -> float:
    return kg * CO2_PER_KG.get(category or "", CO2_DEFAULT)


def _build_report(rows_cat: List[Dict[str, Any]], rows_month: List[Dict[str, Any]],
                  months: int) -> Dict[str, Any]:
    by_category = []
    total_kg = 0.0
    total_co2 = 0.0
    total_lots = 0
    for r in rows_cat:
        kg = float(r["kg"] or 0)
        co2 = round(_co2(kg, r["category"]), 1)
        by_category.append({
            "category": r["category"] or "Без категории",
            "kg": kg,
            "co2_kg": co2,
            "lots": r["lots"],
        })
        total_kg += kg
        total_co2 += co2
        total_lots += r["lots"]

    by_month = [{
        "month": r["month"],
        "kg": float(r["kg"] or 0),
        # monthly CO2 uses the blended factor of that month's category mix
        "co2_kg": round(float(r["co2_kg"] or 0), 1),
    } for r in rows_month]

    return {
        "methodology": METHODOLOGY,
        "period_months": months,
        "totals": {
            "kg": round(total_kg, 1),
            "co2_kg": round(total_co2, 1),
            "meals": int(total_kg / KG_PER_MEAL),
            "lots": total_lots,
        },
        "by_category": by_category,
        "by_month": by_month,
    }


def _co2_sql_case() -> str:
    """SQL CASE mirroring CO2_PER_KG so monthly rows are weighted per category."""
    whens = " ".join(
        f"WHEN l.category = '{cat}' THEN {factor}" for cat, factor in CO2_PER_KG.items()
    )
    return f"(CASE {whens} ELSE {CO2_DEFAULT} END)"


def shop_report(shop_id: int, months: int = 12) -> Dict[str, Any]:
    months = max(1, min(36, months))
    with get_db_cursor() as cur:
        cur.execute(
            f"""
            SELECT l.category, COALESCE(SUM({RESCUED_KG_SQL}), 0) AS kg, COUNT(*) AS lots
            FROM lots l
            WHERE l.shop_id = %s AND {RESCUED_SQL}
              AND {RESCUED_AT_SQL} >= CURRENT_TIMESTAMP - INTERVAL '%s months'
            GROUP BY l.category
            ORDER BY kg DESC
            """,
            (shop_id, months),
        )
        rows_cat = [dict(r) for r in cur.fetchall()]
        cur.execute(
            f"""
            SELECT to_char(date_trunc('month', {RESCUED_AT_SQL}), 'YYYY-MM') AS month,
                   COALESCE(SUM({RESCUED_KG_SQL}), 0) AS kg,
                   COALESCE(SUM({RESCUED_KG_SQL} * {_co2_sql_case()}), 0) AS co2_kg
            FROM lots l
            WHERE l.shop_id = %s AND {RESCUED_SQL}
              AND {RESCUED_AT_SQL} >= CURRENT_TIMESTAMP - INTERVAL '%s months'
            GROUP BY 1 ORDER BY 1
            """,
            (shop_id, months),
        )
        rows_month = [dict(r) for r in cur.fetchall()]
    return _build_report(rows_cat, rows_month, months)


def report_to_csv(report: Dict[str, Any], shop_name: str = "") -> str:
    """Render a shop_report() dict as a tax-filing-friendly CSV: a totals block,
    a per-category breakdown and a per-month series. Semicolon-separated (the
    KZ/RU Excel locale default) so it opens without an import wizard."""
    buf = io.StringIO()
    w = csv.writer(buf, delimiter=";")
    totals = report.get("totals", {})
    w.writerow(["SaveFood — отчёт о переданной на благотворительность еде"])
    w.writerow(["Организация", shop_name])
    w.writerow(["Период, мес.", report.get("period_months", "")])
    w.writerow(["Методология", report.get("methodology", "")])
    w.writerow([])
    w.writerow(["Итого, кг", "Итого, порций", "Итого, кг CO2e", "Лотов"])
    w.writerow([totals.get("kg", 0), totals.get("meals", 0), totals.get("co2_kg", 0), totals.get("lots", 0)])
    w.writerow([])
    w.writerow(["Категория", "Кг", "Кг CO2e", "Лотов"])
    for c in report.get("by_category", []):
        w.writerow([c.get("category", ""), c.get("kg", 0), c.get("co2_kg", 0), c.get("lots", 0)])
    w.writerow([])
    w.writerow(["Месяц", "Кг", "Кг CO2e"])
    for m in report.get("by_month", []):
        w.writerow([m.get("month", ""), m.get("kg", 0), m.get("co2_kg", 0)])
    return buf.getvalue()


def global_report(months: int = 12) -> Dict[str, Any]:
    """Platform-wide report for the admin panel + top contributing shops."""
    months = max(1, min(36, months))
    with get_db_cursor() as cur:
        cur.execute(
            f"""
            SELECT l.category, COALESCE(SUM({RESCUED_KG_SQL}), 0) AS kg, COUNT(*) AS lots
            FROM lots l
            WHERE {RESCUED_SQL} AND {RESCUED_AT_SQL} >= CURRENT_TIMESTAMP - INTERVAL '%s months'
            GROUP BY l.category ORDER BY kg DESC
            """,
            (months,),
        )
        rows_cat = [dict(r) for r in cur.fetchall()]
        cur.execute(
            f"""
            SELECT to_char(date_trunc('month', {RESCUED_AT_SQL}), 'YYYY-MM') AS month,
                   COALESCE(SUM({RESCUED_KG_SQL}), 0) AS kg,
                   COALESCE(SUM({RESCUED_KG_SQL} * {_co2_sql_case()}), 0) AS co2_kg
            FROM lots l
            WHERE {RESCUED_SQL} AND {RESCUED_AT_SQL} >= CURRENT_TIMESTAMP - INTERVAL '%s months'
            GROUP BY 1 ORDER BY 1
            """,
            (months,),
        )
        rows_month = [dict(r) for r in cur.fetchall()]
        cur.execute(
            f"""
            SELECT s.id, s.name, COALESCE(SUM({RESCUED_KG_SQL}), 0) AS kg
            FROM lots l JOIN shops s ON s.id = l.shop_id
            WHERE {RESCUED_SQL} AND {RESCUED_AT_SQL} >= CURRENT_TIMESTAMP - INTERVAL '%s months'
            GROUP BY s.id, s.name ORDER BY kg DESC LIMIT 10
            """,
            (months,),
        )
        top_shops = [dict(r) for r in cur.fetchall()]
    report = _build_report(rows_cat, rows_month, months)
    report["top_shops"] = top_shops
    return report
