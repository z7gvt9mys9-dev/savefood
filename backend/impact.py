"""Public impact endpoints: city dashboard, leaderboards, anonymous feed.

Everything here is intentionally unauthenticated — this is the platform's
PR/transparency surface (media, city administrations, sponsors). No personal
data leaves the API: the feed strips names/addresses, leaderboards expose
only volunteer first names they registered with and aggregate numbers.
"""
from fastapi import APIRouter

from backend import cache, esg, gamification
from backend.database import get_db_cursor

router = APIRouter(prefix="/impact", tags=["impact"])

# Same "rescued" definition as the ESG methodology (§32.5).
_RESCUED = "l.status IN ('taken', 'confirmed')"
_RESCUED_AT = "COALESCE(l.taken_at, l.created_at)"


@router.get("/summary")
def impact_summary(months: int = 12):
    """Live city dashboard: ESG totals + platform counters + monthly series.
    Polled every 20s by every open /impact page — cached behind a short TTL."""
    months = max(1, min(36, months))
    return cache.cached_json(
        f"impact:summary:{months}", cache.TTL_STATS, lambda: _compute_summary(months)
    )


def _compute_summary(months: int):
    report = esg.global_report(months=months)
    with get_db_cursor() as cur:
        cur.execute("SELECT COUNT(*) AS n FROM tickets WHERE status = 'fulfilled'")
        deliveries = cur.fetchone()["n"]
        cur.execute(
            "SELECT COUNT(DISTINCT volunteer_id) AS n FROM volunteer_routes "
            "WHERE started_at >= CURRENT_TIMESTAMP - INTERVAL '30 days'"
        )
        active_volunteers = cur.fetchone()["n"]
        cur.execute("SELECT COUNT(*) AS n FROM shops")
        shops = cur.fetchone()["n"]
    return {
        "methodology": report["methodology"],
        "period_months": report["period_months"],
        "totals": report["totals"],
        "by_month": report["by_month"],
        "by_category": report["by_category"],
        "deliveries_completed": deliveries,
        "active_volunteers": active_volunteers,
        "partner_shops": shops,
    }


@router.get("/cities")
def city_leaderboard(months: int = 12):
    """«Рейтинг районов»: rescued kg per city — the inter-district competition."""
    months = max(1, min(36, months))
    with get_db_cursor() as cur:
        cur.execute(
            f"""
            SELECT COALESCE(NULLIF(TRIM(l.city), ''), 'Без города') AS city,
                   COALESCE(SUM(l.quantity), 0) AS kg,
                   COUNT(*) AS lots
            FROM lots l
            WHERE {_RESCUED} AND {_RESCUED_AT} >= CURRENT_TIMESTAMP - INTERVAL '%s months'
            GROUP BY 1 ORDER BY kg DESC LIMIT 10
            """,
            (months,),
        )
        return [dict(r) for r in cur.fetchall()]


@router.get("/volunteers")
def volunteer_leaderboard():
    """Top-10 volunteers by impact (first name + aggregates + level)."""
    with get_db_cursor() as cur:
        cur.execute(
            """
            SELECT v.id, v.name,
                   COUNT(t.id) AS deliveries,
                   COALESCE((
                       SELECT SUM(l.quantity)
                       FROM volunteer_routes vr JOIN lots l ON l.id = vr.lot_id
                       WHERE vr.volunteer_id = v.id AND vr.status = 'finished'
                   ), 0) AS kg
            FROM volunteers v
            JOIN tickets t ON t.assigned_volunteer_id = v.id AND t.status = 'fulfilled'
            GROUP BY v.id, v.name
            ORDER BY deliveries DESC, kg DESC
            LIMIT 10
            """
        )
        rows = [dict(r) for r in cur.fetchall()]
    for r in rows:
        # First word only — "Алексей Петров" must not leak a full name publicly.
        r["name"] = (r["name"] or "Волонтёр").split()[0]
        r["kg"] = float(r["kg"])
        r["level"] = gamification.compute_level(r["deliveries"], r["kg"])["code"]
    return rows


@router.get("/teams")
def team_leaderboard():
    """Corporate volunteering: top-10 teams by delivered tickets / rescued kg.
    Company names are public by design — that's the whole PR point for them."""
    with get_db_cursor() as cur:
        cur.execute(
            """
            SELECT tm.id, tm.name,
                   COUNT(DISTINCT v.id) AS members,
                   COUNT(t.id) AS deliveries,
                   COALESCE((
                       SELECT SUM(l.quantity)
                       FROM volunteer_routes vr
                       JOIN lots l ON l.id = vr.lot_id
                       JOIN volunteers v2 ON v2.id = vr.volunteer_id
                       WHERE v2.team_id = tm.id AND vr.status = 'finished'
                   ), 0) AS kg
            FROM teams tm
            JOIN volunteers v ON v.team_id = tm.id
            LEFT JOIN tickets t ON t.assigned_volunteer_id = v.id AND t.status = 'fulfilled'
            GROUP BY tm.id, tm.name
            ORDER BY deliveries DESC, kg DESC
            LIMIT 10
            """
        )
        rows = [dict(r) for r in cur.fetchall()]
    for r in rows:
        r["kg"] = float(r["kg"])
    return rows


@router.get("/feed")
def impact_feed(limit: int = 20):
    """Anonymous feed of completed deliveries with photos shared by recipients.

    Only deliveries the recipient photographed appear; no names, no addresses,
    no ids of people — just the photo, the food category, city and date.
    """
    limit = max(1, min(50, limit))
    with get_db_cursor() as cur:
        cur.execute(
            """
            SELECT t.delivery_photo, t.fulfilled_at, t.items,
                   l.category, l.city
            FROM tickets t
            LEFT JOIN lots l ON l.id = t.lot_id
            WHERE t.status = 'fulfilled' AND t.delivery_photo IS NOT NULL
            ORDER BY t.fulfilled_at DESC NULLS LAST
            LIMIT %s
            """,
            (limit,),
        )
        rows = cur.fetchall()
    return [
        {
            "photo": r["delivery_photo"],
            "date": r["fulfilled_at"],
            "items": (r["items"] or "")[:120],
            "category": r["category"],
            "city": r["city"],
        }
        for r in rows
    ]
