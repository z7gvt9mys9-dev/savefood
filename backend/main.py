from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles
import os
import threading
import time

from backend.shop import db, routes as shop_routes
from backend.needy import db as needy_db, routes as needy_routes
from backend.volunteer import db as vol_db, routes as vol_routes

app = FastAPI(title="SaveFood - Backend")
# ensure upload directories exist before mounting static files
os.makedirs(shop_routes.UPLOAD_DIR, exist_ok=True)
os.makedirs(needy_routes.UPLOAD_DIR, exist_ok=True)

@app.on_event("startup")
def startup():
    db.init_db()
    needy_db.init_db()
    vol_db.init_db()


    def expire_loop():
        # run periodically to mark lots that are within 24h of expiry
        while True:
            try:
                updated = db.expire_soon_lots()
                if updated:
                    print(f"expire_soon_lots updated {updated} lots")
            except Exception:
                pass
            time.sleep(60 * 30)  # check every 30 minutes

    t = threading.Thread(target=expire_loop, daemon=True)
    t.start()

    def reassign_loop():
        # if a route is in_progress for longer than timeout_minutes, mark timed_out and release tickets and lot
        timeout_minutes = 60  # consider volunteer unresponsive after 60 minutes
        while True:
            try:
                conn = vol_db.get_conn()
                cur = conn.cursor()
                cur.execute("SELECT * FROM volunteer_routes WHERE status = 'in_progress' AND started_at <= datetime('now', ?)", (f'-{timeout_minutes} minutes',))
                rows = cur.fetchall()
                for r in rows:
                    route_id = r['id']
                    volunteer_id = r['volunteer_id']
                    # parse points and release assigned tickets
                    try:
                        points = []
                        import json
                        points = json.loads(r.get('points') or '[]')
                        nconn = needy_db.get_conn()
                        ncur = nconn.cursor()
                        for p in points:
                            if p.get('kind') == 'ticket' and p.get('ticket_id'):
                                # set ticket back to open if still assigned
                                ncur.execute("UPDATE tickets SET status = 'open', assigned_volunteer = NULL WHERE id = ? AND status = 'assigned'", (p['ticket_id'],))
                        nconn.commit()
                        nconn.close()
                    except Exception:
                        pass

                    # release lot if any
                    try:
                        lot_id = r.get('lot_id')
                        if lot_id:
                            try:
                                db.release_lot(lot_id)
                            except Exception:
                                pass
                    except Exception:
                        pass

                    # mark route as timed_out
                    try:
                        cur.execute("UPDATE volunteer_routes SET status = 'timed_out' WHERE id = ?", (route_id,))
                    except Exception:
                        pass

                conn.commit()
                conn.close()
            except Exception:
                pass
            time.sleep(60 * 10)  # check every 10 minutes

    t2 = threading.Thread(target=reassign_loop, daemon=True)
    t2.start()

app.mount("/uploads", StaticFiles(directory=shop_routes.UPLOAD_DIR), name="uploads")
app.mount("/needy_uploads", StaticFiles(directory=needy_routes.UPLOAD_DIR), name="needy_uploads")
app.include_router(shop_routes.router)
app.include_router(needy_routes.router)
app.include_router(vol_routes.router)


@app.get("/stats")
def stats():
    # kilograms of food saved = sum(quantity) of lots that were taken
    conn = db.get_conn()
    cur = conn.cursor()
    cur.execute("SELECT IFNULL(SUM(quantity),0) as kg_saved FROM lots WHERE status = 'taken'")
    kg_saved = cur.fetchone()[0] or 0

    # deliveries completed = count of fulfilled tickets
    cur.execute("SELECT COUNT(*) FROM tickets WHERE status = 'fulfilled'")
    deliveries_completed = cur.fetchone()[0]

    # active volunteers = volunteers with routes in last 30 days
    cur.execute("SELECT COUNT(DISTINCT volunteer_id) FROM volunteer_routes WHERE started_at >= datetime('now', '-30 days')")
    active_volunteers = cur.fetchone()[0]

    # average delivery time (minutes) for finished routes
    cur.execute("SELECT AVG(julianday(finished_at) - julianday(started_at)) * 24 * 60 FROM volunteer_routes WHERE status = 'finished' AND finished_at IS NOT NULL")
    avg_delivery_minutes = cur.fetchone()[0] or 0

    # percent expired lots
    cur.execute("SELECT COUNT(*) FROM lots")
    total_lots = cur.fetchone()[0] or 0
    cur.execute("SELECT COUNT(*) FROM lots WHERE status = 'expired'")
    expired_lots = cur.fetchone()[0] or 0
    percent_expired = (expired_lots / total_lots * 100.0) if total_lots > 0 else 0.0

    conn.close()
    return {
        'kg_food_saved': kg_saved,
        'deliveries_completed': deliveries_completed,
        'active_volunteers': active_volunteers,
        'avg_delivery_minutes': avg_delivery_minutes,
        'percent_expired_lots': percent_expired,
    }

