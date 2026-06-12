"""«Карта потребностей»: push matching lots to the recipients who need them.

When a shop publishes a lot, recipients whose profile preferences mention the
lot's food category get a notification (feed + Telegram) — the shift from
«бери что дают» to «помогаем тем, что нужно». Matching is keyword-based over
the free-text preferences field; a clause that mentions the category together
with a restriction word («аллергия на молоко») is a conflict, not a match —
the same convention as the route scoring in volunteer/routes.py.

Runs in a fire-and-forget daemon thread (same pattern as kyc_service) so lot
creation never waits on N Telegram calls.
"""
import html
import logging
import re
import threading
from datetime import datetime, timezone
from typing import Optional

from backend.database import get_db_cursor
from backend.utils import haversine
from backend import telegram_service, push_service

# lot category → preference keywords that signal the recipient wants it
CATEGORY_KEYWORDS = {
    "Молочные продукты": ["молок", "молоч", "сыр", "творог", "кефир", "йогурт", "сметан"],
    "Выпечка": ["хлеб", "выпечк", "булк", "батон", "лаваш"],
    "Овощи/Фрукты": ["овощ", "фрукт", "яблок", "картофел", "капуст", "морков"],
    "Готовая еда": ["готовая еда", "готовой еды", "обед", "консерв", "крупа", "каш"],
}

RESTRICTION_WORDS = ["аллергия", "нельзя", "не ем", "без ", "непереносимость", "не могу", "запрет"]

MAX_NOTIFIED_PER_LOT = 20
MAX_VOLUNTEERS_PER_LOT = 10


def matches_preferences(category: Optional[str], preferences: Optional[str]) -> bool:
    """True when the preferences text positively mentions the lot category."""
    kws = CATEGORY_KEYWORDS.get(category or "")
    if not kws or not preferences:
        return False
    clauses = [c.strip().lower() for c in re.split(r"[.,;\n]+", preferences) if c.strip()]
    matched = False
    for clause in clauses:
        if not any(kw in clause for kw in kws):
            continue
        if any(rw in clause for rw in RESTRICTION_WORDS):
            return False  # explicit restriction beats any positive mention
        matched = True
    return matched


def notify_matching_needy(lot_id: int):
    """Blocking part — call via start_needs_match from request handlers."""
    with get_db_cursor() as cur:
        cur.execute(
            """
            SELECT l.id, l.description, l.category, l.city, s.name AS shop_name
            FROM lots l JOIN shops s ON s.id = l.shop_id
            WHERE l.id = %s AND l.status = 'active'
            """,
            (lot_id,),
        )
        lot = cur.fetchone()
        if not lot or not lot["category"]:
            return
        # Approved recipients with stated preferences in the same city
        # (recipients without a profile city are skipped: a cross-city ping
        # about food they cannot reach is noise, not help).
        cur.execute(
            """
            SELECT n.id AS needy_id, np.preferences, COALESCE(np.geo_push_enabled, TRUE) AS geo_push_enabled
            FROM needy n JOIN needy_profile np ON np.needy_id = n.id
            WHERE n.status = 'approved'
              AND np.preferences IS NOT NULL AND TRIM(np.preferences) <> ''
              AND np.city IS NOT NULL AND np.city = %s
            """,
            (lot["city"],),
        )
        candidates = cur.fetchall()

    matched_rows = [
        c for c in candidates
        if matches_preferences(lot["category"], c["preferences"])
    ][:MAX_NOTIFIED_PER_LOT]
    matched = [c["needy_id"] for c in matched_rows]
    # Geo-push subscription (§48): in-app feed + Telegram reach every match, but
    # the browser/PWA Web Push only goes to recipients who kept the toggle on.
    push_targets = [c["needy_id"] for c in matched_rows if c["geo_push_enabled"]]
    if not matched:
        return

    now = datetime.now(timezone.utc)
    text = (
        f"В магазине «{lot['shop_name']}» появился лот из категории "
        f"«{lot['category']}», которая указана в ваших предпочтениях: {lot['description']}. "
        f"Откройте карту лотов, чтобы оформить заявку."
    )
    with get_db_cursor() as cur:
        for needy_id in matched:
            cur.execute(
                "INSERT INTO notifications (needy_id, type, payload, created_at, read) VALUES (%s, %s, %s, %s, 0)",
                (needy_id, "lot_match", text, now),
            )
    safe = html.escape(text)
    for needy_id in matched:
        try:
            telegram_service.notify_needy(needy_id, f"🛒 {safe}")
        except Exception:
            pass
    for needy_id in push_targets:
        try:
            push_service.notify_role("needy", needy_id, text, url="/")
        except Exception:
            pass
    logging.info(
        "[needs_match] lot %s matched %d recipients (%d web-push)",
        lot_id, len(matched), len(push_targets),
    )


def notify_available_volunteers(lot_id: int):
    """Push-matching for volunteers (§54): the availability calendar finally
    decides WHO gets pinged about a new lot, instead of waiting for them to
    open the map. Same city, window covers now, nearest first."""
    from backend.volunteer.db import is_available_now

    with get_db_cursor() as cur:
        cur.execute(
            """
            SELECT l.id, l.description, l.category, l.city,
                   s.name AS shop_name, s.lat AS s_lat, s.lon AS s_lon
            FROM lots l JOIN shops s ON s.id = l.shop_id
            WHERE l.id = %s AND l.status = 'active'
            """,
            (lot_id,),
        )
        lot = cur.fetchone()
        if not lot:
            return
        # Only volunteers who FILLED the calendar are pinged: an empty calendar
        # means «не беспокоить», not «доступен всегда» — push must be opt-in.
        cur.execute(
            """
            SELECT id, lat, lon, availability FROM volunteers
            WHERE availability IS NOT NULL AND TRIM(availability) NOT IN ('', '[]')
              AND city IS NOT NULL AND city = %s
            """,
            (lot["city"],),
        )
        candidates = [dict(r) for r in cur.fetchall()]

    available = [v for v in candidates if is_available_now(v["availability"])]
    if lot["s_lat"] is not None and lot["s_lon"] is not None:
        available.sort(
            key=lambda v: haversine(v["lat"], v["lon"], lot["s_lat"], lot["s_lon"])
            if v.get("lat") is not None and v.get("lon") is not None else float("inf")
        )
    targets = available[:MAX_VOLUNTEERS_PER_LOT]
    if not targets:
        return

    now = datetime.now(timezone.utc)
    text = (
        f"Новый лот рядом: «{lot['description']}» в магазине «{lot['shop_name']}». "
        f"Вы отметили это время как доступное — откройте карту, чтобы взять маршрут."
    )
    with get_db_cursor() as cur:
        for v in targets:
            cur.execute(
                "INSERT INTO notifications (volunteer_id, type, payload, created_at, read) VALUES (%s, %s, %s, %s, 0)",
                (v["id"], "lot_nearby", text, now),
            )
    safe = html.escape(text)
    for v in targets:
        try:
            telegram_service.notify_volunteer(v["id"], f"📦 {safe}")
        except Exception:
            pass
    logging.info("[needs_match] lot %s pinged %d available volunteers", lot_id, len(targets))


def start_needs_match(lot_id: int):
    def _run():
        try:
            notify_matching_needy(lot_id)
        except Exception as e:
            logging.warning("[needs_match] lot %s failed: %s", lot_id, e)
        try:
            notify_available_volunteers(lot_id)
        except Exception as e:
            logging.warning("[needs_match] lot %s volunteer push failed: %s", lot_id, e)

    threading.Thread(target=_run, daemon=True).start()
