"""Background maintenance tasks: lot expiry, route timeouts, GPS anti-fraud.

Historically these ran as daemon threads inside the FastAPI process (startup
hook). That couples API liveness to maintenance work and prevents running
several API replicas (each would run its own loops). The loops now live here
as *tick* functions — one idempotent pass each — and can run in two modes,
selected by the BACKGROUND_TASKS env var:

- "embedded" (default) — daemon threads inside the API process, exactly the
  old behaviour; zero-config for dev and single-container deploys.
- "external" — the API process runs no loops; a dedicated worker container
  (`python -m backend.worker`, see docker-compose `worker` service) runs them.
- "off"      — nobody runs them (tests, ad-hoc tooling).

Ticks are plain functions over the DB, so a future Taskiq/Celery migration is
a matter of scheduling, not rewriting.
"""
import json
import logging
import os
import threading
import time
from datetime import datetime, timezone

from backend import telegram_service
from backend.database import get_db_cursor
from backend.shop import db as shop_db
from backend.utils import haversine as haversine_m

# Tick intervals (seconds) — same cadence the in-process loops always had.
EXPIRE_INTERVAL = 60 * 30
REASSIGN_INTERVAL = 60 * 10
ANTIFRAUD_INTERVAL = 60 * 3

REASSIGN_TIMEOUT_MINUTES = 60

# Anti-fraud tuning (§27)
ANTIFRAUD_CHECK_AFTER_MINUTES = 15  # silence window after claiming the lot
ANTIFRAUD_GRACE_MINUTES = 15        # time to react to the ping
ANTIFRAUD_DRIFT_THRESHOLD_M = 300   # ignore GPS noise below this

VALID_MODES = ("embedded", "external", "off")


def get_mode() -> str:
    mode = os.getenv("BACKGROUND_TASKS", "embedded").strip().lower()
    return mode if mode in VALID_MODES else "embedded"


# ── Ticks ────────────────────────────────────────────────────────────────────

def expire_tick() -> int:
    """Hide lots that are within 24h of their expiry date (§3.1)."""
    updated = shop_db.expire_soon_lots()
    if updated:
        logging.info("[background] expire_tick: %d lots expired", updated)
    return updated


def reassign_tick(timeout_minutes: int = REASSIGN_TIMEOUT_MINUTES) -> int:
    """Release routes idle past the timeout: tickets → open, lot → active.

    Inactivity is measured from the last completed point (last_activity_at),
    not route start — long multi-stop routes with an active volunteer must
    not be reset mid-delivery."""
    reset = 0
    with get_db_cursor() as cur:
        cur.execute(
            "SELECT * FROM volunteer_routes WHERE status = 'in_progress' AND COALESCE(last_activity_at, started_at) <= CURRENT_TIMESTAMP - INTERVAL '%s minutes'",
            (timeout_minutes,),
        )
        rows = cur.fetchall()
        for row in rows:
            route_id = row["id"]
            try:
                points = json.loads(row.get("points") or "[]")
                for p in points:
                    if p.get("kind") == "ticket" and p.get("ticket_id"):
                        cur.execute(
                            "UPDATE tickets SET status = 'open', assigned_volunteer = NULL, assigned_volunteer_id = NULL WHERE id = %s AND status = 'assigned'",
                            (p["ticket_id"],),
                        )
            except Exception:
                pass

            try:
                lot_id = row.get("lot_id")
                if lot_id:
                    # Only revive lots still 'taken' — a lot the shop already
                    # confirmed as handed over must not reappear on the map.
                    cur.execute(
                        "UPDATE lots SET status = 'active', taken_at = NULL, taken_by = NULL WHERE id = %s AND status = 'taken'",
                        (lot_id,),
                    )
            except Exception:
                pass

            cur.execute("UPDATE volunteer_routes SET status = 'timed_out' WHERE id = %s", (route_id,))
            reset += 1
            support_chat = os.getenv("SUPPORT_CHAT_ID", "")
            if support_chat:
                try:
                    telegram_service.send_message(
                        support_chat,
                        f"⚠️ Маршрут #{route_id} волонтёра {row.get('volunteer_id')} переназначен по таймауту.",
                    )
                except Exception:
                    pass
    return reset


def antifraud_tick() -> dict:
    """§27 GPS-drift monitor: ping volunteers moving away from the shop after
    claiming a lot; release the route if the ping is ignored."""
    pinged = 0
    released = 0
    with get_db_cursor() as cur:
        cur.execute(
            """
            SELECT vr.*, v.lat AS v_lat, v.lon AS v_lon
            FROM volunteer_routes vr
            JOIN volunteers v ON v.id = vr.volunteer_id
            WHERE vr.status = 'in_progress'
              AND vr.start_dist_m IS NOT NULL
              AND vr.started_at <= CURRENT_TIMESTAMP - INTERVAL '%s minutes'
            """,
            (ANTIFRAUD_CHECK_AFTER_MINUTES,),
        )
        rows = cur.fetchall()

    now = datetime.now(timezone.utc)
    for row in rows:
        try:
            points = json.loads(row.get("points") or "[]")
        except Exception:
            continue
        shop_point = next((p for p in points if p.get("kind") == "shop"), None)
        # Pickup confirmed → drift monitoring is over for this route.
        if not shop_point or shop_point.get("done"):
            continue
        if row.get("v_lat") is None or row.get("v_lon") is None:
            continue
        dist_m = haversine_m(row["v_lat"], row["v_lon"], shop_point["lat"], shop_point["lon"])
        moving_away = dist_m > float(row["start_dist_m"]) + ANTIFRAUD_DRIFT_THRESHOLD_M

        with get_db_cursor() as cur:
            if not moving_away:
                if row.get("antifraud_ping_at"):
                    cur.execute("UPDATE volunteer_routes SET antifraud_ping_at = NULL WHERE id = %s", (row["id"],))
                continue

            ping_at = row.get("antifraud_ping_at")
            if ping_at is None:
                cur.execute("UPDATE volunteer_routes SET antifraud_ping_at = CURRENT_TIMESTAMP WHERE id = %s", (row["id"],))
                msg = (f"⚠️ Всё в порядке? Вы взяли лот #{row.get('lot_id')}, но удаляетесь от магазина. "
                       f"Если планы изменились — завершите маршрут, чтобы еда вернулась на витрину. "
                       f"Иначе маршрут будет снят автоматически через {ANTIFRAUD_GRACE_MINUTES} минут.")
                cur.execute(
                    "INSERT INTO notifications (volunteer_id, type, payload, created_at, read) VALUES (%s, %s, %s, %s, 0)",
                    (row["volunteer_id"], "antifraud_ping", msg, now),
                )
                pinged += 1
                try:
                    telegram_service.notify_volunteer(row["volunteer_id"], msg)
                except Exception:
                    pass
                continue

            if ping_at.tzinfo is None:
                ping_at = ping_at.replace(tzinfo=timezone.utc)
            if (now - ping_at).total_seconds() < ANTIFRAUD_GRACE_MINUTES * 60:
                continue

            # No reaction: release tickets, revive the lot, close the route.
            for p in points:
                if p.get("kind") == "ticket" and p.get("ticket_id"):
                    cur.execute(
                        "UPDATE tickets SET status = 'open', assigned_volunteer = NULL, assigned_volunteer_id = NULL WHERE id = %s AND status = 'assigned'",
                        (p["ticket_id"],),
                    )
            if row.get("lot_id"):
                cur.execute(
                    "UPDATE lots SET status = 'active', taken_at = NULL, taken_by = NULL WHERE id = %s AND status = 'taken'",
                    (row["lot_id"],),
                )
            cur.execute("UPDATE volunteer_routes SET status = 'timed_out', finished_at = CURRENT_TIMESTAMP WHERE id = %s", (row["id"],))
            cur.execute(
                "INSERT INTO notifications (volunteer_id, type, payload, created_at, read) VALUES (%s, %s, %s, %s, 0)",
                (row["volunteer_id"], "route_reset",
                 f"Маршрут #{row['id']} снят: вы не приближались к магазину после предупреждения. Лот возвращён на витрину.", now),
            )
            released += 1
            support_chat = os.getenv("SUPPORT_CHAT_ID", "")
            if support_chat:
                try:
                    telegram_service.send_message(
                        support_chat,
                        f"🚨 Антифрод: маршрут #{row['id']} волонтёра {row['volunteer_id']} снят (удалялся от магазина, лот #{row.get('lot_id')} возвращён).",
                    )
                except Exception:
                    pass
    return {"pinged": pinged, "released": released}


# ── Scheduling ───────────────────────────────────────────────────────────────

TASKS = (
    ("expire", expire_tick, EXPIRE_INTERVAL),
    ("reassign", reassign_tick, REASSIGN_INTERVAL),
    ("antifraud", antifraud_tick, ANTIFRAUD_INTERVAL),
)


def _loop(name: str, tick, interval: int):
    while True:
        try:
            tick()
        except Exception as e:
            logging.warning("[background] %s tick failed: %s", name, e)
        time.sleep(interval)


def start_threads() -> list:
    """Spawn one daemon thread per task (embedded mode / worker process)."""
    threads = []
    for name, tick, interval in TASKS:
        t = threading.Thread(target=_loop, args=(name, tick, interval), daemon=True, name=f"bg-{name}")
        t.start()
        threads.append(t)
    logging.info("[background] started %d task threads (mode=%s)", len(threads), get_mode())
    return threads
