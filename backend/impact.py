"""Public impact endpoints: city dashboard, leaderboards, anonymous feed.

Everything here is intentionally unauthenticated — this is the platform's
PR/transparency surface (media, city administrations, sponsors). No personal
data leaves the API: the feed strips names/addresses, leaderboards expose
only volunteer first names they registered with and aggregate numbers.
"""
import html as _html

from fastapi import APIRouter
from fastapi.responses import Response

from backend import cache, esg, gamification
from backend.database import get_db_cursor

router = APIRouter(prefix="/impact", tags=["impact"])

# Single source of truth for "rescued" lives in esg.py (methodology v1.1,
# §56): confirmed hand-over or a fulfilled ticket — never a bare 'taken'.


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
                   COALESCE(SUM({esg.RESCUED_KG_SQL}), 0) AS kg,
                   COUNT(*) AS lots
            FROM lots l
            WHERE {esg.RESCUED_SQL} AND {esg.RESCUED_AT_SQL} >= CURRENT_TIMESTAMP - INTERVAL '%s months'
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
                       SELECT SUM(l.initial_quantity * l.unit_weight_kg)
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
                       SELECT SUM(l.initial_quantity * l.unit_weight_kg)
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


def _fmt_kg(kg: float) -> str:
    kg = float(kg or 0)
    if kg >= 1000:
        return f"{kg / 1000:.1f} т".replace(".0 т", " т")
    return f"{int(round(kg))} кг"


def _badge_svg(title: str, kg: float, meals: int, co2: float) -> str:
    """A self-contained embeddable badge — no external fonts or images, so it
    renders identically wherever a partner drops it via <img> or <iframe>."""
    title = _html.escape(title[:40])
    kg_s = _html.escape(_fmt_kg(kg))
    meals_s = f"{int(meals):,}".replace(",", " ")
    co2_s = _html.escape(_fmt_kg(co2))
    return f"""<svg xmlns="http://www.w3.org/2000/svg" width="320" height="120" viewBox="0 0 320 120" role="img" aria-label="SaveFood impact">
  <defs>
    <linearGradient id="g" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#2e7d32"/><stop offset="1" stop-color="#66bb6a"/>
    </linearGradient>
  </defs>
  <rect width="320" height="120" rx="14" fill="url(#g)"/>
  <text x="20" y="34" font-family="Arial, sans-serif" font-size="15" font-weight="700" fill="#fff">🌱 {title}</text>
  <text x="20" y="74" font-family="Arial, sans-serif" font-size="30" font-weight="800" fill="#fff">{kg_s}</text>
  <text x="20" y="96" font-family="Arial, sans-serif" font-size="12" fill="#e8f5e9">спасено еды · {meals_s} порций · −{co2_s} CO₂e</text>
  <text x="300" y="113" text-anchor="end" font-family="Arial, sans-serif" font-size="10" fill="#c8e6c9">SaveFood</text>
</svg>"""


_SVG_HEADERS = {"Cache-Control": "public, max-age=3600"}


@router.get("/widget.svg")
def global_widget():
    """Embeddable «Мы спасли N кг еды» badge for the whole platform (§52).
    Public, cacheable — partners drop it via <img src=".../impact/widget.svg">."""
    def _build():
        r = esg.global_report(months=12)
        t = r["totals"]
        return _badge_svg("Вместе с SaveFood", t["kg"], t["meals"], t["co2_kg"])
    svg = cache.cached_json("impact:widget:global", cache.TTL_STATS, _build)
    return Response(content=svg, media_type="image/svg+xml", headers=_SVG_HEADERS)


@router.get("/widget/{shop_id}.svg")
def shop_widget(shop_id: int):
    """Per-shop embeddable impact badge (§52). Public by design — it only shows
    aggregate rescued kg, never any personal data."""
    def _build():
        with get_db_cursor() as cur:
            cur.execute("SELECT name FROM shops WHERE id = %s", (shop_id,))
            row = cur.fetchone()
        name = (row["name"] if row else None) or "Наш магазин"
        r = esg.shop_report(shop_id, months=12)
        t = r["totals"]
        return _badge_svg(name, t["kg"], t["meals"], t["co2_kg"])
    svg = cache.cached_json(f"impact:widget:shop:{shop_id}", cache.TTL_STATS, _build)
    return Response(content=svg, media_type="image/svg+xml", headers=_SVG_HEADERS)


@router.get("/feed")
def impact_feed(limit: int = 20):
    """Anonymous feed of completed deliveries with photos shared by recipients.

    Only deliveries the recipient photographed appear; no names, no addresses,
    no ids of people — just the photo, the food category, city and date.

    Photos must be moderation-approved (§36.1) before they reach this public
    PR surface — a 'pending'/'rejected' photo is never returned here.
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
              AND t.delivery_photo_status = 'approved'
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
