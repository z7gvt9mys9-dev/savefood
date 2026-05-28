import json as json_mod
from fastapi import APIRouter, HTTPException, Depends
from backend import auth
from backend.needy import db as needy_db
from backend.database import get_db_cursor, log_action

router = APIRouter(prefix="/admin", tags=["admin"])

def require_admin(current_user: dict = Depends(auth.get_current_user)):
    if current_user.get("role") != "admin":
        raise HTTPException(status_code=403, detail="Admin access required")
    return current_user

# ── Moderation ──────────────────────────────────────────────────────────────

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

# ── Routes dispatcher ────────────────────────────────────────────────────────

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
        return [dict(r) for r in cur.fetchall()]

@router.post("/routes/{route_id}/reset")
def reset_route(route_id: int, _user: dict = Depends(require_admin)):
    with get_db_cursor() as cur:
        cur.execute("SELECT * FROM volunteer_routes WHERE id = %s", (route_id,))
        route = cur.fetchone()
        if not route:
            raise HTTPException(status_code=404, detail="Route not found")
        # free assigned tickets back to open
        try:
            points = json_mod.loads(route.get('points') or '[]')
            for p in points:
                if p.get('kind') == 'ticket' and p.get('ticket_id'):
                    cur.execute(
                        "UPDATE tickets SET status = 'open', assigned_volunteer = NULL, assigned_volunteer_id = NULL WHERE id = %s AND status = 'assigned'",
                        (p['ticket_id'],)
                    )
        except Exception:
            pass
        # release lot
        if route.get('lot_id'):
            cur.execute(
                "UPDATE lots SET status = 'active', taken_at = NULL, taken_by = NULL WHERE id = %s",
                (route['lot_id'],)
            )
        cur.execute("UPDATE volunteer_routes SET status = 'timed_out', finished_at = NOW() WHERE id = %s", (route_id,))
    log_action(_user.get('sub'), 'route_reset', 'route', route_id, f"Admin reset route #{route_id}")
    return {"ok": True}

# ── Lot management ───────────────────────────────────────────────────────────

@router.post("/lots/{lot_id}/reset")
def reset_lot(lot_id: int, _user: dict = Depends(require_admin)):
    with get_db_cursor() as cur:
        cur.execute("SELECT id FROM lots WHERE id = %s", (lot_id,))
        if not cur.fetchone():
            raise HTTPException(status_code=404, detail="Lot not found")
        cur.execute(
            "UPDATE lots SET status = 'active', taken_at = NULL, taken_by = NULL WHERE id = %s",
            (lot_id,)
        )
    log_action(_user.get('sub'), 'lot_reset', 'lot', lot_id, f"Admin reset lot #{lot_id}")
    return {"ok": True}

# ── User management ──────────────────────────────────────────────────────────

@router.get("/users")
def list_users(_user: dict = Depends(require_admin)):
    with get_db_cursor() as cur:
        cur.execute(
            "SELECT id, username, role, related_id, is_blocked, created_at FROM users ORDER BY created_at DESC"
        )
        return [dict(r) for r in cur.fetchall()]

@router.post("/users/{user_id}/block")
def block_user(user_id: int, _user: dict = Depends(require_admin)):
    with get_db_cursor() as cur:
        cur.execute("UPDATE users SET is_blocked = TRUE WHERE id = %s RETURNING id", (user_id,))
        if not cur.fetchone():
            raise HTTPException(status_code=404, detail="User not found")
    log_action(_user.get('sub'), 'user_block', 'user', user_id, f"Admin blocked user #{user_id}")
    return {"ok": True}

@router.post("/users/{user_id}/unblock")
def unblock_user(user_id: int, _user: dict = Depends(require_admin)):
    with get_db_cursor() as cur:
        cur.execute("UPDATE users SET is_blocked = FALSE WHERE id = %s RETURNING id", (user_id,))
        if not cur.fetchone():
            raise HTTPException(status_code=404, detail="User not found")
    log_action(_user.get('sub'), 'user_unblock', 'user', user_id, f"Admin unblocked user #{user_id}")
    return {"ok": True}

@router.get("/audit")
def get_audit_log(limit: int = 50, offset: int = 0, _user: dict = Depends(require_admin)):
    with get_db_cursor() as cur:
        cur.execute(
            "SELECT * FROM audit_log ORDER BY created_at DESC LIMIT %s OFFSET %s",
            (limit, offset),
        )
        return [dict(r) for r in cur.fetchall()]
