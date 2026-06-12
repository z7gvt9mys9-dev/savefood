import json as json_mod
import os
from fastapi import APIRouter, HTTPException, Depends
from pydantic import BaseModel
from backend import auth, billing, esg, kyc_service
from backend.needy import db as needy_db
from backend.database import get_db_cursor, log_action

NEEDY_UPLOAD_DIR = os.path.join(os.path.dirname(needy_db.__file__), "uploads")

router = APIRouter(prefix="/admin", tags=["admin"])

def require_admin(current_user: dict = Depends(auth.get_current_user)):
    if current_user.get("role") != "admin":
        raise HTTPException(status_code=403, detail="Admin access required")
    return current_user

# ── Moderation ──────────────────────────────────────────────────────────────

@router.get("/needy")
def list_needy(status: str = None, _user: dict = Depends(require_admin)):
    return needy_db.get_all_needy(status)

@router.post("/needy/{needy_id}/kyc_recheck")
def kyc_recheck(needy_id: int, _user: dict = Depends(require_admin)):
    """Second opinion on a disputed AI KYC verdict: re-run the Gemini check
    synchronously and return the fresh verdict. Only possible while the
    document still exists (i.e. moderation is not finished — §5 deletes it after)."""
    needy = needy_db.get_needy_by_id(needy_id)
    if not needy:
        raise HTTPException(status_code=404, detail="Needy not found")
    profile = needy_db.get_profile(needy_id)
    doc = (profile or {}).get("document")
    path = os.path.join(NEEDY_UPLOAD_DIR, os.path.basename(doc)) if doc else None
    if not path or not os.path.isfile(path):
        raise HTTPException(
            status_code=400,
            detail="Документ недоступен (уже удалён после модерации) — перепроверка невозможна",
        )
    kyc_service.run_kyc_check(needy_id, path, needy.get("name") or "")
    log_action(_user.get("sub"), "kyc_recheck", "needy", needy_id,
               f"Admin requested AI KYC second opinion for needy #{needy_id}")
    updated = needy_db.get_needy_by_id(needy_id) or {}
    return {
        "ok": True,
        "kyc_verdict": updated.get("kyc_verdict"),
        "kyc_score": updated.get("kyc_score"),
        "kyc_notes": updated.get("kyc_notes"),
    }

# ── Delivery photo moderation (public Impact feed, §36.1) ────────────────────

@router.get("/delivery_photos")
def list_delivery_photos(status: str = "pending", _user: dict = Depends(require_admin)):
    """Queue of recipient delivery photos awaiting a publish decision.

    Each row carries the AI pre-check verdict/score/notes so the moderator can
    decide in seconds; the photo URL is the same /volunteer_uploads/ path the
    feed uses. Only photos still attached to fulfilled tickets are listed."""
    if status not in ("pending", "approved", "rejected"):
        raise HTTPException(status_code=400, detail="Invalid status")
    with get_db_cursor() as cur:
        cur.execute(
            """
            SELECT t.id AS ticket_id, t.delivery_photo, t.delivery_photo_status,
                   t.delivery_photo_ai_verdict, t.delivery_photo_ai_score,
                   t.delivery_photo_ai_notes, t.fulfilled_at,
                   l.category, l.city
            FROM tickets t
            LEFT JOIN lots l ON l.id = t.lot_id
            WHERE t.delivery_photo IS NOT NULL AND t.delivery_photo_status = %s
            ORDER BY t.fulfilled_at DESC NULLS LAST
            LIMIT 100
            """,
            (status,),
        )
        return [dict(r) for r in cur.fetchall()]


@router.post("/delivery_photos/{ticket_id}/approve")
def approve_delivery_photo(ticket_id: int, _user: dict = Depends(require_admin)):
    """Publish the photo to the public Impact feed."""
    with get_db_cursor() as cur:
        # A rejected photo's file is already deleted from disk — approving it
        # would put a broken image into the public feed, so it's a hard no;
        # the recipient has to upload a new photo instead.
        cur.execute(
            "UPDATE tickets SET delivery_photo_status = 'approved', "
            "delivery_photo_reviewed_at = NOW() "
            "WHERE id = %s AND delivery_photo IS NOT NULL "
            "AND delivery_photo_status <> 'rejected' RETURNING id",
            (ticket_id,),
        )
        if not cur.fetchone():
            cur.execute(
                "SELECT delivery_photo_status AS s FROM tickets WHERE id = %s AND delivery_photo IS NOT NULL",
                (ticket_id,),
            )
            row = cur.fetchone()
            if row and row["s"] == "rejected":
                raise HTTPException(status_code=409, detail="Файл фото уже удалён при отклонении — нужна новая загрузка от получателя")
            raise HTTPException(status_code=404, detail="Photo not found")
    log_action(_user.get("sub"), "photo_approve", "ticket", ticket_id,
               f"Admin approved delivery photo for ticket #{ticket_id}")
    return {"ok": True, "status": "approved"}


@router.post("/delivery_photos/{ticket_id}/reject")
def reject_delivery_photo(ticket_id: int, _user: dict = Depends(require_admin)):
    """Reject the photo — it never appears in the feed and the file is deleted."""
    from backend.volunteer.routes import UPLOAD_DIR as VOL_UPLOAD_DIR
    with get_db_cursor() as cur:
        cur.execute("SELECT delivery_photo FROM tickets WHERE id = %s", (ticket_id,))
        row = cur.fetchone()
        if not row or not row.get("delivery_photo"):
            raise HTTPException(status_code=404, detail="Photo not found")
        cur.execute(
            "UPDATE tickets SET delivery_photo_status = 'rejected', "
            "delivery_photo_reviewed_at = NOW() WHERE id = %s",
            (ticket_id,),
        )
        photo = row["delivery_photo"]
    # Remove the file so a rejected (e.g. trolling) image can never leak.
    if photo and photo.startswith("/volunteer_uploads/"):
        path = os.path.join(VOL_UPLOAD_DIR, os.path.basename(photo))
        try:
            if os.path.isfile(path):
                os.remove(path)
        except OSError:
            pass
    log_action(_user.get("sub"), "photo_reject", "ticket", ticket_id,
               f"Admin rejected delivery photo for ticket #{ticket_id}")
    return {"ok": True, "status": "rejected"}


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

# ── Supply / demand heatmap (§51) ────────────────────────────────────────────

@router.get("/heatmap")
def supply_demand_heatmap(_user: dict = Depends(require_admin)):
    """Per-city supply vs demand so the admin sees where shops/volunteers are
    missing. Supply = active lots; demand = open delivery tickets + approved
    recipients. `gap` > 0 means demand outstrips active supply there.

    Built on the «карта потребностей» data (§35.1): a plain aggregation that the
    AdminPanel renders as colour-coded bars."""
    lot_city = "COALESCE(NULLIF(TRIM(city), ''), 'Без города')"
    np_city = "COALESCE(NULLIF(TRIM(np.city), ''), 'Без города')"
    with get_db_cursor() as cur:
        cur.execute(f"""
            SELECT {lot_city} AS city, COUNT(*) AS active_lots,
                   COALESCE(SUM(quantity), 0) AS active_kg
            FROM lots WHERE status = 'active' GROUP BY 1
        """)
        supply = {r["city"]: r for r in cur.fetchall()}
        # tickets has no city column — derive it from the recipient profile.
        cur.execute(f"""
            SELECT {np_city} AS city, COUNT(*) AS open_tickets
            FROM tickets t
            LEFT JOIN needy_profile np ON np.needy_id = t.needy_id
            WHERE t.status = 'open' GROUP BY 1
        """)
        demand_tickets = {r["city"]: r["open_tickets"] for r in cur.fetchall()}
        cur.execute(f"""
            SELECT {np_city} AS city, COUNT(*) AS approved_needy
            FROM needy_profile np
            JOIN needy n ON n.id = np.needy_id AND n.status = 'approved'
            GROUP BY 1
        """)
        approved = {r["city"]: r["approved_needy"] for r in cur.fetchall()}
        cur.execute(f"""
            SELECT {lot_city} AS city, COUNT(*) AS volunteers
            FROM volunteers GROUP BY 1
        """)
        volunteers = {r["city"]: r["volunteers"] for r in cur.fetchall()}
        # Availability calendar (§54): how many volunteers are free *right now*
        # per city — the real-time coverage signal, not just the headcount.
        cur.execute("SELECT city, availability FROM volunteers")
        avail_rows = cur.fetchall()

    from backend.volunteer.db import is_available_now
    available_now = {}
    for r in avail_rows:
        c = (r["city"] or "").strip() or "Без города"
        if is_available_now(r["availability"]):
            available_now[c] = available_now.get(c, 0) + 1

    cities = set(supply) | set(demand_tickets) | set(approved) | set(volunteers)
    rows = []
    for c in cities:
        active_lots = int(supply.get(c, {}).get("active_lots", 0) or 0)
        open_tickets = int(demand_tickets.get(c, 0) or 0)
        rows.append({
            "city": c,
            "active_lots": active_lots,
            "active_kg": float(supply.get(c, {}).get("active_kg", 0) or 0),
            "open_tickets": open_tickets,
            "approved_needy": int(approved.get(c, 0) or 0),
            "volunteers": int(volunteers.get(c, 0) or 0),
            "volunteers_available": int(available_now.get(c, 0)),
            # >0 = unmet demand (more open requests than lots on the shelf).
            "gap": open_tickets - active_lots,
        })
    rows.sort(key=lambda r: r["gap"], reverse=True)
    return rows


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
        # Only an in-progress route can be reset: resetting a finished one would
        # flip its (already delivered) lot back to 'active' on the map.
        if route.get('status') != 'in_progress':
            raise HTTPException(status_code=400, detail="Маршрут уже завершён или сброшен")
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
        # release lot — but only if it is still 'taken'; a lot the shop already
        # confirmed as handed over must not reappear on the map
        if route.get('lot_id'):
            cur.execute(
                "UPDATE lots SET status = 'active', taken_at = NULL, taken_by = NULL WHERE id = %s AND status = 'taken'",
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

# ── ESG & billing ────────────────────────────────────────────────────────────

@router.get("/esg")
def admin_esg(months: int = 12, _user: dict = Depends(require_admin)):
    """Platform-wide ESG report (all shops, top contributors)."""
    return esg.global_report(months=months)

@router.get("/shops")
def list_shops(_user: dict = Depends(require_admin)):
    with get_db_cursor() as cur:
        cur.execute("SELECT id, name, contact, city, plan, created_at FROM shops ORDER BY created_at DESC")
        return [dict(r) for r in cur.fetchall()]

class PlanUpdate(BaseModel):
    plan: str

@router.patch("/shops/{shop_id}/plan")
def set_shop_plan(shop_id: int, payload: PlanUpdate, _user: dict = Depends(require_admin)):
    """Manual billing for now: admin flips the plan once an invoice is paid."""
    if payload.plan not in billing.PLANS:
        raise HTTPException(status_code=400, detail=f"Unknown plan: {payload.plan}")
    with get_db_cursor() as cur:
        cur.execute("UPDATE shops SET plan = %s WHERE id = %s RETURNING id", (payload.plan, shop_id))
        if not cur.fetchone():
            raise HTTPException(status_code=404, detail="Shop not found")
    log_action(_user.get('sub'), 'plan_change', 'shop', shop_id, f"Admin set plan '{payload.plan}' for shop #{shop_id}")
    return {"ok": True, "plan": payload.plan}

@router.get("/audit")
def get_audit_log(limit: int = 50, offset: int = 0, _user: dict = Depends(require_admin)):
    with get_db_cursor() as cur:
        cur.execute(
            "SELECT * FROM audit_log ORDER BY created_at DESC LIMIT %s OFFSET %s",
            (limit, offset),
        )
        return [dict(r) for r in cur.fetchall()]
