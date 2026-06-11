"""Write-off forecast: «what will this shop probably write off today/tomorrow».

v1 is deliberately simple and explainable: the average kg per category that
the shop listed on the same weekday over the last BASIS_WEEKS weeks. Lots are
the signal (every published lot IS a write-off the shop declared), so the
forecast works even before any OCR receipts exist. The divisor is the fixed
number of weeks in the window — weeks with zero write-offs count as zeros,
otherwise one lucky Friday would inflate the average forever.
"""
import os
from datetime import datetime
from typing import Any, Dict, List
from zoneinfo import ZoneInfo

from backend.database import get_db_cursor

LOCAL_TZ_NAME = os.getenv("LOCAL_TZ", "Asia/Almaty")

BASIS_WEEKS = 8
# Only suggest a category when the average is at least this many kg —
# a 0.2 kg «forecast» is noise, not a planning aid.
MIN_AVG_KG = 1.0

ISO_DOW_NAMES_RU = {
    1: "понедельник", 2: "вторник", 3: "среда", 4: "четверг",
    5: "пятница", 6: "суббота", 7: "воскресенье",
}


def build_forecast(rows: List[Dict[str, Any]], today_isodow: int,
                   basis_weeks: int = BASIS_WEEKS) -> Dict[str, Any]:
    """Pure aggregation: rows = [{isodow, category, kg}] summed over the window."""
    def day_items(isodow: int) -> List[Dict[str, Any]]:
        items = []
        for r in rows:
            if int(r["isodow"]) != isodow:
                continue
            avg = float(r["kg"] or 0) / basis_weeks
            if avg >= MIN_AVG_KG:
                items.append({
                    "category": r["category"] or "Без категории",
                    "avg_kg": round(avg, 1),
                })
        items.sort(key=lambda x: -x["avg_kg"])
        return items

    tomorrow = today_isodow % 7 + 1
    return {
        "basis_weeks": basis_weeks,
        "today": {
            "isodow": today_isodow,
            "day_name": ISO_DOW_NAMES_RU[today_isodow],
            "items": day_items(today_isodow),
        },
        "tomorrow": {
            "isodow": tomorrow,
            "day_name": ISO_DOW_NAMES_RU[tomorrow],
            "items": day_items(tomorrow),
        },
    }


def shop_forecast(shop_id: int) -> Dict[str, Any]:
    with get_db_cursor() as cur:
        cur.execute(
            """
            SELECT EXTRACT(ISODOW FROM l.created_at)::int AS isodow,
                   l.category,
                   COALESCE(SUM(l.quantity), 0) AS kg
            FROM lots l
            WHERE l.shop_id = %s
              AND l.created_at >= CURRENT_TIMESTAMP - INTERVAL '%s weeks'
            GROUP BY 1, 2
            """,
            (shop_id, BASIS_WEEKS),
        )
        rows = [dict(r) for r in cur.fetchall()]
    # «Friday» must mean the shop's local Friday, not the server's UTC one.
    try:
        today_isodow = datetime.now(ZoneInfo(LOCAL_TZ_NAME)).isoweekday()
    except Exception:
        today_isodow = datetime.now().isoweekday()
    return build_forecast(rows, today_isodow)
