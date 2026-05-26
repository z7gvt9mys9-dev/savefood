from fastapi import APIRouter, HTTPException, Depends
from backend import auth
from backend.needy import db as needy_db
from backend.database import get_db_cursor

router = APIRouter(prefix="/admin", tags=["admin"])

def require_admin(current_user: dict = Depends(auth.get_current_user)):
    if current_user.get("role") != "admin":
        raise HTTPException(status_code=403, detail="Admin access required")
    return current_user

@router.get("/needy")
def list_needy(status: str = None, _user: dict = Depends(require_admin)):
    return needy_db.get_all_needy(status)

@router.get("/stats")
def admin_stats(_user: dict = Depends(require_admin)):
    with get_db_cursor() as cur:
        cur.execute("SELECT COALESCE(SUM(quantity),0) as kg_saved FROM lots WHERE status IN ('taken','confirmed')")
        kg_saved = cur.fetchone()['kg_saved']
        cur.execute("SELECT COUNT(*) as count FROM tickets WHERE status = 'fulfilled'")
        deliveries_completed = cur.fetchone()['count']
        cur.execute("SELECT COUNT(DISTINCT volunteer_id) as count FROM volunteer_routes WHERE started_at >= CURRENT_TIMESTAMP - INTERVAL '30 days'")
        active_volunteers = cur.fetchone()['count']
        cur.execute("SELECT COUNT(*) as total FROM lots")
        total_lots = cur.fetchone()['total'] or 0
        cur.execute("SELECT COUNT(*) as expired FROM lots WHERE status = 'expired'")
        expired_lots = cur.fetchone()['expired'] or 0
        cur.execute("SELECT AVG(EXTRACT(EPOCH FROM (finished_at - started_at)) / 60) as avg_min FROM volunteer_routes WHERE status = 'finished' AND finished_at IS NOT NULL")
        avg_min = cur.fetchone()['avg_min'] or 0
    return {
        'kg_food_saved': float(kg_saved),
        'deliveries_completed': deliveries_completed,
        'active_volunteers': active_volunteers,
        'avg_delivery_minutes': round(float(avg_min), 1),
        'percent_expired_lots': round((expired_lots / total_lots * 100) if total_lots else 0.0, 1),
    }

@router.get("/routes")
def list_active_routes(_user: dict = Depends(require_admin)):
    with get_db_cursor() as cur:
        cur.execute("""
            SELECT vr.id, vr.volunteer_id, vr.lot_id, vr.status, vr.started_at, vr.finished_at,
                   v.name as volunteer_name
            FROM volunteer_routes vr
            LEFT JOIN volunteers v ON vr.volunteer_id = v.id
            WHERE vr.status = 'in_progress'
            ORDER BY vr.started_at DESC
        """)
        rows = cur.fetchall()
        return [dict(r) for r in rows]
