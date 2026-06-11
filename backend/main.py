from fastapi import FastAPI, Request
from fastapi.staticfiles import StaticFiles
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from slowapi import _rate_limit_exceeded_handler
from slowapi.errors import RateLimitExceeded
from slowapi.middleware import SlowAPIMiddleware
from backend.limiter import limiter
import json
import os
import threading
import time
from datetime import datetime, timezone

from backend.shop import db, routes as shop_routes
from backend.needy import db as needy_db, routes as needy_routes
from backend.volunteer import db as vol_db, routes as vol_routes
from backend import auth_routes, oauth_routes, database, telegram_routes, proxy_service, telegram_service
from backend.admin import routes as admin_routes
from backend.database import get_db_cursor

app = FastAPI(title="SaveFood - Backend")
app.state.limiter = limiter
app.add_exception_handler(RateLimitExceeded, _rate_limit_exceeded_handler)
app.add_middleware(SlowAPIMiddleware)

allowed_origins = [o.strip() for o in os.getenv("CORS_ORIGIN", "").split(",") if o.strip()]
if not allowed_origins:
    raise RuntimeError(
        "CORS_ORIGIN must be set to a comma-separated list of allowed origins "
        "(e.g. https://savefood.example.com). Wildcards are not allowed with credentials."
    )
app.add_middleware(
    CORSMiddleware,
    allow_origins=allowed_origins,
    allow_credentials=True,
    allow_methods=["GET", "POST", "PATCH", "DELETE", "OPTIONS"],
    allow_headers=["Authorization", "Content-Type"],
)
# ensure upload directories exist before mounting static files
os.makedirs(shop_routes.UPLOAD_DIR, exist_ok=True)
os.makedirs(shop_routes.RECEIPT_DIR, exist_ok=True)
os.makedirs(needy_routes.UPLOAD_DIR, exist_ok=True)
os.makedirs(vol_routes.UPLOAD_DIR, exist_ok=True)

@app.on_event("startup")
async def startup():
    database.init_common_db()
    db.init_db()
    needy_db.init_db()
    vol_db.init_db()
    database.init_ticket_extensions()
    proxy_service.start_proxy()
    await telegram_routes.start_polling()

    def expire_loop():
        while True:
            try:
                updated = db.expire_soon_lots()
                if updated:
                    print(f"expire_soon_lots updated {updated} lots")
            except Exception as e:
                print(f"Expire loop error: {e}")
            time.sleep(60 * 30)

    t = threading.Thread(target=expire_loop, daemon=True)
    t.start()

    def reassign_loop():
        timeout_minutes = 60
        while True:
            try:
                with get_db_cursor() as cur:
                    # Inactivity timeout: measured from the last completed point
                    # (last_activity_at), not route start — long multi-stop routes
                    # with an active volunteer must not be reset mid-delivery.
                    cur.execute(
                        "SELECT * FROM volunteer_routes WHERE status = 'in_progress' AND COALESCE(last_activity_at, started_at) <= CURRENT_TIMESTAMP - INTERVAL '%s minutes'",
                        (timeout_minutes,)
                    )
                    rows = cur.fetchall()
                    for row in rows:
                        route_id = row['id']
                        try:
                            points = json.loads(row.get('points') or '[]')
                            for p in points:
                                if p.get('kind') == 'ticket' and p.get('ticket_id'):
                                    cur.execute(
                                        "UPDATE tickets SET status = 'open', assigned_volunteer = NULL, assigned_volunteer_id = NULL WHERE id = %s AND status = 'assigned'",
                                        (p['ticket_id'],)
                                    )
                        except Exception:
                            pass

                        try:
                            lot_id = row.get('lot_id')
                            if lot_id:
                                # Only revive lots still 'taken' — a lot the shop already
                                # confirmed as handed over must not reappear on the map.
                                cur.execute("UPDATE lots SET status = 'active', taken_at = NULL, taken_by = NULL WHERE id = %s AND status = 'taken'", (lot_id,))
                        except Exception:
                            pass

                        cur.execute("UPDATE volunteer_routes SET status = 'timed_out' WHERE id = %s", (route_id,))
                        support_chat = os.getenv("SUPPORT_CHAT_ID", "")
                        if support_chat:
                            try:
                                telegram_service.send_message(support_chat, f"⚠️ Маршрут #{route_id} волонтёра {row.get('volunteer_id')} переназначен по таймауту.")
                            except Exception:
                                pass
            except Exception as e:
                print(f"Reassign loop error: {e}")
            time.sleep(60 * 10)

    t2 = threading.Thread(target=reassign_loop, daemon=True)
    t2.start()

    def antifraud_loop():
        """§27: GPS-drift monitor. A volunteer claimed a lot but keeps moving
        AWAY from the shop → ping «Всё в порядке?»; still drifting after the
        grace period → release the route so the food returns to the map."""
        check_after_minutes = 15   # silence window after claiming the lot
        grace_minutes = 15         # time to react to the ping
        drift_threshold_m = 300    # ignore GPS noise below this
        from backend.utils import haversine as haversine_m

        while True:
            try:
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
                        (check_after_minutes,),
                    )
                    rows = cur.fetchall()

                now = datetime.now(timezone.utc)
                for row in rows:
                    try:
                        points = json.loads(row.get('points') or '[]')
                    except Exception:
                        continue
                    shop_point = next((p for p in points if p.get('kind') == 'shop'), None)
                    # Pickup confirmed → drift monitoring is over for this route.
                    if not shop_point or shop_point.get('done'):
                        continue
                    if row.get('v_lat') is None or row.get('v_lon') is None:
                        continue
                    dist_m = haversine_m(row['v_lat'], row['v_lon'], shop_point['lat'], shop_point['lon'])
                    moving_away = dist_m > float(row['start_dist_m']) + drift_threshold_m

                    with get_db_cursor() as cur:
                        if not moving_away:
                            if row.get('antifraud_ping_at'):
                                cur.execute("UPDATE volunteer_routes SET antifraud_ping_at = NULL WHERE id = %s", (row['id'],))
                            continue

                        ping_at = row.get('antifraud_ping_at')
                        if ping_at is None:
                            cur.execute("UPDATE volunteer_routes SET antifraud_ping_at = CURRENT_TIMESTAMP WHERE id = %s", (row['id'],))
                            msg = (f"⚠️ Всё в порядке? Вы взяли лот #{row.get('lot_id')}, но удаляетесь от магазина. "
                                   f"Если планы изменились — завершите маршрут, чтобы еда вернулась на витрину. "
                                   f"Иначе маршрут будет снят автоматически через {grace_minutes} минут.")
                            cur.execute(
                                "INSERT INTO notifications (volunteer_id, type, payload, created_at, read) VALUES (%s, %s, %s, %s, 0)",
                                (row['volunteer_id'], 'antifraud_ping', msg, now),
                            )
                            try:
                                telegram_service.notify_volunteer(row['volunteer_id'], msg)
                            except Exception:
                                pass
                            continue

                        if ping_at.tzinfo is None:
                            ping_at = ping_at.replace(tzinfo=timezone.utc)
                        if (now - ping_at).total_seconds() < grace_minutes * 60:
                            continue

                        # No reaction: release tickets, revive the lot, close the route.
                        for p in points:
                            if p.get('kind') == 'ticket' and p.get('ticket_id'):
                                cur.execute(
                                    "UPDATE tickets SET status = 'open', assigned_volunteer = NULL, assigned_volunteer_id = NULL WHERE id = %s AND status = 'assigned'",
                                    (p['ticket_id'],),
                                )
                        if row.get('lot_id'):
                            cur.execute(
                                "UPDATE lots SET status = 'active', taken_at = NULL, taken_by = NULL WHERE id = %s AND status = 'taken'",
                                (row['lot_id'],),
                            )
                        cur.execute("UPDATE volunteer_routes SET status = 'timed_out', finished_at = CURRENT_TIMESTAMP WHERE id = %s", (row['id'],))
                        cur.execute(
                            "INSERT INTO notifications (volunteer_id, type, payload, created_at, read) VALUES (%s, %s, %s, %s, 0)",
                            (row['volunteer_id'], 'route_reset',
                             f"Маршрут #{row['id']} снят: вы не приближались к магазину после предупреждения. Лот возвращён на витрину.", now),
                        )
                        support_chat = os.getenv("SUPPORT_CHAT_ID", "")
                        if support_chat:
                            try:
                                telegram_service.send_message(
                                    support_chat,
                                    f"🚨 Антифрод: маршрут #{row['id']} волонтёра {row['volunteer_id']} снят (удалялся от магазина, лот #{row.get('lot_id')} возвращён).",
                                )
                            except Exception:
                                pass
            except Exception as e:
                print(f"Antifraud loop error: {e}")
            time.sleep(60 * 3)

    t3 = threading.Thread(target=antifraud_loop, daemon=True)
    t3.start()


@app.on_event("shutdown")
async def shutdown():
    proxy_service.stop_proxy()
    await telegram_routes.stop_polling()

app.mount("/uploads", StaticFiles(directory=shop_routes.UPLOAD_DIR), name="uploads")
# /needy_uploads is intentionally NOT mounted publicly — it contains personal
# documents (passports/social-status proofs). Downloads go through the
# auth-checked /needy/{needy_id}/document endpoint instead.
app.mount("/volunteer_uploads", StaticFiles(directory=vol_routes.UPLOAD_DIR), name="volunteer_uploads")
app.include_router(auth_routes.router)
app.include_router(oauth_routes.router)
app.include_router(shop_routes.router)
app.include_router(needy_routes.router)
app.include_router(vol_routes.router)
app.include_router(admin_routes.router)
app.include_router(telegram_routes.router)


@app.get("/stats")
def stats():
    with get_db_cursor() as cur:
        cur.execute("SELECT COALESCE(SUM(quantity),0) as kg_saved FROM lots WHERE status IN ('taken','confirmed')")
        kg_saved = cur.fetchone()['kg_saved']

        cur.execute("SELECT COUNT(*) as count FROM tickets WHERE status = 'fulfilled'")
        deliveries_completed = cur.fetchone()['count']

        cur.execute("SELECT COUNT(DISTINCT volunteer_id) as count FROM volunteer_routes WHERE started_at >= CURRENT_TIMESTAMP - INTERVAL '30 days'")
        active_volunteers = cur.fetchone()['count']

        cur.execute("SELECT AVG(EXTRACT(EPOCH FROM (finished_at - started_at)) / 60) as avg_min FROM volunteer_routes WHERE status = 'finished' AND finished_at IS NOT NULL")
        avg_delivery_minutes = cur.fetchone()['avg_min'] or 0

        cur.execute("SELECT COUNT(*) as total FROM lots")
        total_lots = cur.fetchone()['total'] or 0
        cur.execute("SELECT COUNT(*) as expired FROM lots WHERE status = 'expired'")
        expired_lots = cur.fetchone()['expired'] or 0
        percent_expired = (expired_lots / total_lots * 100.0) if total_lots > 0 else 0.0

    return {
        'kg_food_saved': kg_saved,
        'deliveries_completed': deliveries_completed,
        'active_volunteers': active_volunteers,
        'avg_delivery_minutes': avg_delivery_minutes,
        'percent_expired_lots': percent_expired,
    }

