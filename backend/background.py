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

from backend import telegram_service, kyc_service
from backend.database import get_db_cursor
from backend.shop import db as shop_db
from backend.needy import db as needy_db
from backend.volunteer import db as volunteer_db
from backend.utils import haversine as haversine_m

# Tick intervals (seconds) — same cadence the in-process loops always had.
EXPIRE_INTERVAL = 60 * 30
REASSIGN_INTERVAL = 60 * 10
ANTIFRAUD_INTERVAL = 60 * 3
RESERVATION_TTL_INTERVAL = 60 * 5
KYC_DOC_RETENTION_INTERVAL = 60 * 60  # hourly is plenty for a slow-moving sweep
KYC_RETRY_INTERVAL = 60 * 15  # re-run AI for accounts stuck pending (AI was down)
# Cap how many stuck docs we re-send to the AI per tick, so a large backlog (e.g.
# after an AI outage) doesn't stampede the API / starve the worker on recovery.
# Oldest-attempt-first, so every doc is eventually retried across ticks.
KYC_RETRY_BATCH = int(os.getenv("KYC_RETRY_BATCH", "100"))

# Route-release timeouts (§8/§9). Inactivity is measured from the last completed
# point (last_activity_at), so an honest volunteer on a long leg — a distant
# drop, traffic, the §8 "ring and wait 10 min at the door" — is not reset
# mid-delivery. 60 min was too tight for one long leg, so it is 90. The absolute
# ceiling from started_at is the backstop: a fresh location ping only proves the
# device is on, not that delivery is progressing, so activity alone must never be
# able to hold a route open forever (audit Q9).
REASSIGN_TIMEOUT_MINUTES = 90
MAX_ROUTE_DURATION_MINUTES = 240

# Atomic lot-revert UPDATE shared by the three revert paths (reassign timeout,
# anti-fraud release, admin reset), all funnelled through revert_route_lot().
# When a lot genuinely flips taken→active it recomputes `quantity` from the
# source of truth (initial_quantity) minus the units no longer available: live
# reservations still held (tickets 'open'/'assigned') AND units already
# delivered/consumed (tickets 'fulfilled'). revert_route_lot runs this UPDATE
# BEFORE reopening the route's own tickets — safe because both 'open' and
# 'assigned' are counted here, so the reconciled quantity is identical either
# way. Omitting 'fulfilled' would resurrect already-delivered units as phantom
# availability (the lot reappears on the map with quantity > 0 → over-allocation).
# COALESCE(initial_quantity, quantity) degrades safely for legacy NULL lots;
# GREATEST(...,0) clamps in case of any data drift (math should never go negative).
_LOT_REVERT_SQL = (
    "UPDATE lots SET status = 'active', taken_at = NULL, taken_by = NULL, "
    "quantity = GREATEST(COALESCE(initial_quantity, quantity) - COALESCE("
    "(SELECT SUM(t.quantity) FROM tickets t WHERE t.lot_id = lots.id "
    "AND t.status IN ('open', 'assigned', 'fulfilled')), 0), 0) "
    "WHERE id = %s AND status = 'taken'"
)


def revert_route_lot(cur, lot_id, points, *, now=None) -> None:
    """Release a route's lot and resolve its tickets when the route is torn down
    (reassign timeout / anti-fraud / admin reset). Runs the lot revert FIRST and
    branches on whether the lot actually came back to 'active':

    - revert hit (rowcount == 1): the lot is back on the witrine, so the route's
      assigned tickets are REOPENED to 'open' for another volunteer to claim.
    - revert missed (rowcount == 0): the lot is no longer 'taken' — the shop
      already confirmed the hand-over (taken→confirmed), or it expired / was
      removed. No second route can ever claim it, so reopening its tickets would
      strand them 'open' on a dead lot until their 48h TTL, blocking the
      recipient's one-active-ticket slot (audit Q4/§57). CANCEL them and notify
      instead — same treatment start_route gives co-lot orphans at claim time.

    No advisory lock is taken: a lot is held by exactly one route at a time
    (status 'taken'), so revert-vs-revert on the same lot cannot occur (BUG-R3)."""
    if now is None:
        now = datetime.now(timezone.utc)
    ticket_ids = [p["ticket_id"] for p in points
                  if p.get("kind") == "ticket" and p.get("ticket_id")]
    lot_gone = False
    if lot_id:
        cur.execute(_LOT_REVERT_SQL, (lot_id,))
        lot_gone = cur.rowcount == 0
    if not lot_gone:
        for tid in ticket_ids:
            cur.execute(
                "UPDATE tickets SET status = 'open', assigned_volunteer = NULL, "
                "assigned_volunteer_id = NULL WHERE id = %s AND status = 'assigned'",
                (tid,),
            )
        return
    for tid in ticket_ids:
        cur.execute(
            "UPDATE tickets SET status = 'cancelled' WHERE id = %s AND status = 'assigned' "
            "RETURNING needy_id",
            (tid,),
        )
        row = cur.fetchone()
        if row:
            cur.execute(
                "INSERT INTO notifications (needy_id, type, payload, created_at, read) "
                "VALUES (%s, %s, %s, %s, 0)",
                (row["needy_id"], "ticket_cancelled",
                 f"Заявка #{tid} отменена: лот уже передан или снят магазином. "
                 f"Выберите другой лот — недельный лимит не потрачен.", now),
            )

# KYC document retention sweep (§5/§58). Documents are now retained ENCRYPTED at
# rest for accountability, so the purge is DISABLED by default (0). Set a
# positive number of hours only where a jurisdiction requires deleting
# unmoderated documents; keyed on kyc_checked_at (set seconds after upload),
# NOT created_at (the account date).
KYC_DOC_RETENTION_HOURS = int(os.getenv("KYC_DOC_RETENTION_HOURS", "0"))

# Upload dirs, derived the same way the route modules build them.
_VOL_KYC_DIR = os.path.join(os.path.dirname(volunteer_db.__file__), "kyc_uploads")
_NEEDY_UPLOAD_DIR = os.path.join(os.path.dirname(needy_db.__file__), "uploads")

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


def reassign_tick(timeout_minutes: int = REASSIGN_TIMEOUT_MINUTES,
                  max_route_minutes: int = MAX_ROUTE_DURATION_MINUTES) -> int:
    """Release routes idle past the timeout: tickets → open, lot → active.

    Inactivity is measured from the last completed point (last_activity_at),
    not route start — long multi-stop routes with an active volunteer must not
    be reset mid-delivery. A second, absolute ceiling on started_at catches a
    route that stays nominally active forever (e.g. a volunteer parked at home
    with the app pinging) but never actually finishes delivering (audit Q9)."""
    reset = 0
    with get_db_cursor() as cur:
        cur.execute(
            "SELECT * FROM volunteer_routes WHERE status = 'in_progress' AND ("
            "COALESCE(last_activity_at, started_at) <= CURRENT_TIMESTAMP - INTERVAL '%s minutes' "
            "OR started_at <= CURRENT_TIMESTAMP - INTERVAL '%s minutes')",
            (timeout_minutes, max_route_minutes),
        )
        rows = cur.fetchall()
        for row in rows:
            route_id = row["id"]
            # Reopening the route's tickets, reverting the lot's quantity and
            # closing the route must be all-or-nothing PER ROUTE. get_db_cursor
            # runs the whole batch in a single transaction, and in psycopg2 the
            # first failed statement aborts that transaction (every later execute
            # then raises until rollback). A per-route SAVEPOINT isolates the
            # failure: on any error we ROLLBACK TO the savepoint and leave the
            # route 'in_progress' so the NEXT tick retries it, instead of the old
            # behaviour where a swallowed revert error still closed the route and
            # stranded the lot in 'taken' forever (the tick only scans
            # 'in_progress'). The rest of the batch is unaffected.
            cur.execute("SAVEPOINT route_revert")
            try:
                points = json.loads(row.get("points") or "[]")
                # Revert the lot and resolve its tickets in one place: reopen them
                # if the lot came back to 'active', cancel them if it is already
                # gone (confirmed/expired/removed) so they don't strand (Q4).
                revert_route_lot(cur, row.get("lot_id"), points)
                cur.execute("UPDATE volunteer_routes SET status = 'timed_out', finished_at = CURRENT_TIMESTAMP WHERE id = %s", (route_id,))
                cur.execute("RELEASE SAVEPOINT route_revert")
            except Exception:
                cur.execute("ROLLBACK TO SAVEPOINT route_revert")
                cur.execute("RELEASE SAVEPOINT route_revert")
                logging.exception(
                    "[background] reassign_tick: revert failed for route %s; left in_progress for retry",
                    route_id,
                )
                continue

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
            # Unlike reassign_tick, this loop opens a FRESH get_db_cursor() per
            # route (one transaction each) and does NOT swallow revert errors, so
            # a failed revert propagates out of the `with` block, rolls back the
            # whole route — including the close below — and the route stays
            # 'in_progress' for the next tick. No SAVEPOINT needed here.
            # Revert the lot and resolve its tickets (reopen if it came back,
            # cancel if it is already confirmed/removed — Q4). Same helper as
            # reassign_tick; reuse this loop's `now` for the cancel notification.
            revert_route_lot(cur, row.get("lot_id"), points, now=now)
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


def reservation_ttl_tick() -> int:
    """Cancel expired reservations (self-pickup AND delivery) and return the
    reserved quantity to the lot. An 'open' ticket past its expires_at locks the
    reserved unit and the recipient's one-active-ticket slot; freeing it on lapse
    keeps the witrine honest. Only 'open' tickets are touched, so an 'assigned'
    delivery in a volunteer's route never expires from under them."""
    cancelled = 0
    with get_db_cursor() as cur:
        cur.execute(
            """
            SELECT id, needy_id, lot_id, quantity, self_pickup FROM tickets
            WHERE status = 'open'
              AND expires_at IS NOT NULL AND expires_at < CURRENT_TIMESTAMP
            """
        )
        expired = cur.fetchall()
        for t in expired:
            # 1. Cancel ticket
            cur.execute("UPDATE tickets SET status = 'cancelled' WHERE id = %s", (t['id'],))

            # 2. Return quantity (guarded: lot must be active)
            if t['lot_id']:
                cur.execute(
                    "UPDATE lots SET quantity = quantity + %s WHERE id = %s AND status = 'active'",
                    (t['quantity'], t['lot_id'])
                )

            # 3. Notify needy
            if t['self_pickup']:
                msg = f"⏳ Срок брони лота #{t['lot_id']} (самовывоз) истёк. Заявка отменена, еда вернулась на витрину."
                ntype = "self_pickup_expired"
            else:
                msg = f"⏳ Бронь лота #{t['lot_id']} истекла: волонтёр не взялся за доставку. Заявка отменена, еда вернулась на витрину — лимит не потрачен."
                ntype = "reservation_expired"
            cur.execute(
                "INSERT INTO notifications (needy_id, type, payload, created_at, read) VALUES (%s, %s, %s, CURRENT_TIMESTAMP, 0)",
                (t['needy_id'], ntype, msg)
            )
            try:
                telegram_service.notify_needy(t['needy_id'], f"⏳ {msg}")
            except Exception:
                pass
            cancelled += 1
    return cancelled


def _safe_doc_path(upload_dir: str, doc) -> str | None:
    """Resolve a stored document reference to an on-disk path INSIDE upload_dir,
    or None. Same realpath traversal guard the route handlers use, so a crafted
    basename can't escape the directory."""
    if not doc:
        return None
    real = os.path.realpath(os.path.join(upload_dir, os.path.basename(doc)))
    if real.startswith(os.path.realpath(upload_dir) + os.sep) and os.path.isfile(real):
        return real
    return None


def kyc_doc_retention_tick(retention_hours: int = KYC_DOC_RETENTION_HOURS) -> int:
    """Purge KYC documents that have sat unmoderated past the retention window
    (§5). A time-based sweep instead of relying on a moderator eventually acting:
    a still-'pending' account older than the window has its document file deleted
    and its document reference cleared, and is asked to re-upload. No moderation
    state is lost beyond the now-deleted file; the account stays 'pending' so the
    verification banner keeps prompting. Covers volunteers and needy."""
    if retention_hours <= 0:
        return 0  # disabled: documents are retained encrypted for accountability
    purged = 0
    with get_db_cursor() as cur:
        # ── Volunteers (document column on volunteers) ──
        cur.execute(
            """
            SELECT id, document FROM volunteers
            WHERE document IS NOT NULL AND status = 'pending'
              AND kyc_checked_at IS NOT NULL
              AND kyc_checked_at < CURRENT_TIMESTAMP - make_interval(hours => %s)
            """,
            (retention_hours,),
        )
        for row in cur.fetchall():
            real = _safe_doc_path(_VOL_KYC_DIR, row["document"])
            if real:
                try:
                    os.remove(real)
                except OSError:
                    pass
            cur.execute("UPDATE volunteers SET document = NULL WHERE id = %s", (row["id"],))
            msg = ("Срок хранения вашего удостоверения истёк, и оно удалено. "
                   "Загрузите документ заново, чтобы пройти верификацию.")
            cur.execute(
                "INSERT INTO notifications (volunteer_id, type, payload, created_at, read) VALUES (%s, %s, %s, CURRENT_TIMESTAMP, 0)",
                (row["id"], "kyc_doc_purged", msg),
            )
            try:
                telegram_service.notify_volunteer(row["id"], f"⏳ {msg}")
            except Exception:
                pass
            purged += 1

        # ── Needy (document lives on needy_profile, kyc_checked_at on needy) ──
        cur.execute(
            """
            SELECT n.id AS id, p.document AS document FROM needy n
            JOIN needy_profile p ON p.needy_id = n.id
            WHERE p.document IS NOT NULL AND n.status = 'pending'
              AND n.kyc_checked_at IS NOT NULL
              AND n.kyc_checked_at < CURRENT_TIMESTAMP - make_interval(hours => %s)
            """,
            (retention_hours,),
        )
        for row in cur.fetchall():
            real = _safe_doc_path(_NEEDY_UPLOAD_DIR, row["document"])
            if real:
                try:
                    os.remove(real)
                except OSError:
                    pass
            cur.execute("UPDATE needy_profile SET document = NULL WHERE needy_id = %s", (row["id"],))
            msg = ("Срок хранения вашего документа истёк, и он удалён. "
                   "Загрузите документ заново, чтобы подтвердить право на помощь.")
            cur.execute(
                "INSERT INTO notifications (needy_id, type, payload, created_at, read) VALUES (%s, %s, %s, CURRENT_TIMESTAMP, 0)",
                (row["id"], "kyc_doc_purged", msg),
            )
            try:
                telegram_service.notify_needy(row["id"], f"⏳ {msg}")
            except Exception:
                pass
            purged += 1
    return purged


def kyc_retry_tick() -> int:
    """Re-run fully-automated KYC for accounts stuck 'pending' because the AI was
    unavailable (verdict 'unchecked' or never recorded). There is no human
    fallback — the account is held pending and retried until a confident verdict
    (approve/reject). Only retries 'unchecked'/null verdicts; a genuine 'review'
    verdict is deterministic, so re-running the same document wouldn't change it."""
    retried = 0
    with get_db_cursor() as cur:
        cur.execute(
            """
            SELECT id, name, document FROM volunteers
            WHERE status = 'pending' AND document IS NOT NULL
              AND (kyc_verdict IS NULL OR kyc_verdict = 'unchecked')
            ORDER BY kyc_checked_at ASC NULLS FIRST
            LIMIT %s
            """,
            (KYC_RETRY_BATCH,),
        )
        vols = cur.fetchall()
        cur.execute(
            """
            SELECT n.id AS id, n.name AS name, p.document AS document
            FROM needy n JOIN needy_profile p ON p.needy_id = n.id
            WHERE n.status = 'pending' AND p.document IS NOT NULL
              AND (n.kyc_verdict IS NULL OR n.kyc_verdict = 'unchecked')
            ORDER BY n.kyc_checked_at ASC NULLS FIRST
            LIMIT %s
            """,
            (KYC_RETRY_BATCH,),
        )
        needies = cur.fetchall()

    for v in vols:
        path = _safe_doc_path(_VOL_KYC_DIR, v["document"])
        if path:
            kyc_service.run_volunteer_kyc_check(v["id"], path, v.get("name") or "")
            retried += 1
    for n in needies:
        path = _safe_doc_path(_NEEDY_UPLOAD_DIR, n["document"])
        if path:
            kyc_service.run_kyc_check(n["id"], path, n.get("name") or "")
            retried += 1
    return retried


# ── Scheduling ───────────────────────────────────────────────────────────────

TASKS = (
    ("expire", expire_tick, EXPIRE_INTERVAL),
    ("reassign", reassign_tick, REASSIGN_INTERVAL),
    ("antifraud", antifraud_tick, ANTIFRAUD_INTERVAL),
    ("reservation_ttl", reservation_ttl_tick, RESERVATION_TTL_INTERVAL),
    ("kyc_retry", kyc_retry_tick, KYC_RETRY_INTERVAL),
    ("kyc_doc_retention", kyc_doc_retention_tick, KYC_DOC_RETENTION_INTERVAL),
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
