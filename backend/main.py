from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles
from fastapi.middleware.cors import CORSMiddleware
import json
import os
import threading
import time

from backend.shop import db, routes as shop_routes
from backend.needy import db as needy_db, routes as needy_routes
from backend.volunteer import db as vol_db, routes as vol_routes
from backend import auth_routes, database
from backend.admin import routes as admin_routes
from backend.database import get_db_cursor

app = FastAPI(title="SaveFood - Backend")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # In production, specify the actual origin
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)
# ensure upload directories exist before mounting static files
os.makedirs(shop_routes.UPLOAD_DIR, exist_ok=True)
os.makedirs(needy_routes.UPLOAD_DIR, exist_ok=True)

@app.on_event("startup")
def startup():
    database.init_common_db()
    db.init_db()
    needy_db.init_db()
    vol_db.init_db()


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
                    cur.execute(
                        "SELECT * FROM volunteer_routes WHERE status = 'in_progress' AND started_at <= CURRENT_TIMESTAMP - INTERVAL '%s minutes'", 
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
                                        "UPDATE tickets SET status = 'open', assigned_volunteer = NULL WHERE id = %s AND status = 'assigned'", 
                                        (p['ticket_id'],)
                                    )
                        except Exception:
                            pass

                        try:
                            lot_id = row.get('lot_id')
                            if lot_id:
                                cur.execute("UPDATE lots SET status = 'active', taken_at = NULL, taken_by = NULL WHERE id = %s", (lot_id,))
                        except Exception:
                            pass

                        cur.execute("UPDATE volunteer_routes SET status = 'timed_out' WHERE id = %s", (route_id,))
            except Exception as e:
                print(f"Reassign loop error: {e}")
            time.sleep(60 * 10)

    t2 = threading.Thread(target=reassign_loop, daemon=True)
    t2.start()

app.mount("/uploads", StaticFiles(directory=shop_routes.UPLOAD_DIR), name="uploads")
app.mount("/needy_uploads", StaticFiles(directory=needy_routes.UPLOAD_DIR), name="needy_uploads")
app.include_router(auth_routes.router)
app.include_router(shop_routes.router)
app.include_router(needy_routes.router)
app.include_router(vol_routes.router)
app.include_router(admin_routes.router)


@app.get("/stats")
def stats():
    with get_db_cursor() as cur:
        cur.execute("SELECT COALESCE(SUM(quantity),0) as kg_saved FROM lots WHERE status = 'taken'")
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

