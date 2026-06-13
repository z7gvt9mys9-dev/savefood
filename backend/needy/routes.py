from fastapi import APIRouter, HTTPException, UploadFile, File, Depends, WebSocket, WebSocketDisconnect, Request
from fastapi.responses import FileResponse
from typing import List, Optional
import os
import json as json_mod
import asyncio
import psycopg2.errors
from datetime import datetime, timezone
from collections import defaultdict

# Per-needy WebSocket connection counter. A logged-in user can open at most
# MAX_WS_PER_USER sockets; further attempts are rejected so one client cannot
# DoS the polling loop or exhaust the connection pool.
ws_connections: dict[int, int] = defaultdict(int)
MAX_WS_PER_USER = 3

from backend.needy import db, schemas
from backend.shop import db as shop_db
from backend.shop import schemas as shop_schemas
from backend import auth, kyc_service, telegram_service
from backend.database import create_user, get_db_cursor
from backend.limiter import limiter
from backend.utils import validate_and_save_upload, UploadValidationError

router = APIRouter()

UPLOAD_DIR = os.path.join(os.path.dirname(__file__), "uploads")


@router.post("/needy/register")
@limiter.limit("5/minute")
def register_needy(request: Request, payload: schemas.NeedyCreate):
    # An account is mandatory: a needy row without credentials can never log in
    # or create tickets, so silently creating one just strands the person.
    if not payload.username or not payload.password:
        raise HTTPException(status_code=400, detail="Укажите логин и пароль")
    hashed = auth.get_password_hash(payload.password)
    # Single transaction: if the username is taken, the needy row rolls back too.
    try:
        with get_db_cursor() as cur:
            cur.execute(
                "INSERT INTO needy (name, contact, created_at) VALUES (%s, %s, %s) RETURNING id",
                (payload.name, payload.contact, datetime.now(timezone.utc)),
            )
            needy_id = cur.fetchone()['id']
            cur.execute(
                "INSERT INTO users (username, hashed_password, role, related_id) VALUES (%s, %s, %s, %s)",
                (payload.username, hashed, "needy", needy_id),
            )
    except psycopg2.errors.UniqueViolation:
        raise HTTPException(status_code=409, detail="Username already taken")
    return {"id": needy_id}


@router.post("/needy/{needy_id}/ticket")
def create_ticket(needy_id: int, payload: schemas.TicketCreate, current_user: dict = Depends(auth.get_current_user)):
    if current_user["role"] not in ("needy", "admin"):
        raise HTTPException(status_code=403, detail="Forbidden")
    if current_user["role"] == "needy" and current_user["related_id"] != needy_id:
        raise HTTPException(status_code=403, detail="Forbidden")

    needy = db.get_needy_by_id(needy_id)
    if not needy:
        raise HTTPException(status_code=404, detail="Needy not found")
    if needy.get('status') != 'approved':
        raise HTTPException(status_code=403, detail="Account not approved yet")

    lat, lon = payload.lat, payload.lon
    # Delivery tickets must carry the recipient's home coordinates, otherwise the
    # volunteer map/route queries (which require lat/lon NOT NULL) never surface them.
    if not payload.self_pickup and (lat is None or lon is None):
        profile = db.get_profile(needy_id) or {}
        lat = lat if lat is not None else profile.get('lat')
        lon = lon if lon is not None else profile.get('lon')

    try:
        ticket_id = db.create_ticket(needy_id, payload.items, payload.address, lat, lon, payload.available_time, payload.lot_id, payload.apartment, payload.floor_num, payload.entrance, payload.self_pickup)
    except db.TicketCreateError as exc:
        if exc.reason == "weekly_limit":
            raise HTTPException(status_code=400, detail="Помощь можно получать не чаще раза в неделю")
        if exc.reason == "active_ticket_exists":
            raise HTTPException(status_code=400, detail="У вас уже есть активная заявка — дождитесь её завершения")
        if exc.reason == "lot_required":
            raise HTTPException(status_code=400, detail="Для самовывоза нужно выбрать конкретный лот")
        if exc.reason == "lot_unavailable":
            raise HTTPException(status_code=400, detail="Этот лот уже разобран или просрочен — выберите другой на карте")
        raise HTTPException(status_code=400, detail=str(exc))
    return {"id": ticket_id}


@router.patch("/needy/notifications/{notification_id}/read")
def mark_notification_read(notification_id: int, current_user: dict = Depends(auth.get_current_user)):
    note = db.get_notification_by_id(notification_id)
    if not note:
        raise HTTPException(status_code=404, detail="Notification not found")
    auth.ensure_owner_or_admin(current_user, "needy", note.get("needy_id"))
    db.mark_notification_read(notification_id)
    return {"ok": True}


@router.patch("/needy/{needy_id}")
def update_needy(needy_id: int, payload: schemas.NeedyCreate, current_user: dict = Depends(auth.get_current_user)):
    auth.ensure_owner_or_admin(current_user, "needy", needy_id)
    updated = db.update_needy(needy_id, payload.name, payload.contact)
    if not updated:
        raise HTTPException(status_code=404, detail="Needy not found")
    return updated


@router.get("/needy/{needy_id}")
def get_needy(needy_id: int, current_user: dict = Depends(auth.get_current_user)):
    auth.ensure_owner_or_admin(current_user, "needy", needy_id)
    needy = db.get_needy_by_id(needy_id)
    if not needy:
        raise HTTPException(status_code=404, detail="Needy not found")
    return needy


@router.post("/needy/{needy_id}/profile", response_model=schemas.NeedyProfileOut)
def create_profile(needy_id: int, payload: schemas.NeedyProfileCreate, current_user: dict = Depends(auth.get_current_user)):
    auth.ensure_owner_or_admin(current_user, "needy", needy_id)
    prof = db.create_or_update_profile(needy_id, payload.address, payload.family_size, payload.preferences, payload.urgency, available_time=payload.available_time, apartment=payload.apartment, floor_num=payload.floor_num, entrance=payload.entrance, city=payload.city, lat=payload.lat, lon=payload.lon)
    if not prof:
        raise HTTPException(status_code=404, detail="Needy not found")
    return prof


@router.patch("/needy/{needy_id}/profile", response_model=schemas.NeedyProfileOut)
def patch_profile(needy_id: int, payload: schemas.NeedyProfileUpdate, current_user: dict = Depends(auth.get_current_user)):
    auth.ensure_owner_or_admin(current_user, "needy", needy_id)
    prof = db.create_or_update_profile(needy_id, payload.address, payload.family_size, payload.preferences, payload.urgency, available_time=payload.available_time, apartment=payload.apartment, floor_num=payload.floor_num, entrance=payload.entrance, city=payload.city, lat=payload.lat, lon=payload.lon)
    if not prof:
        raise HTTPException(status_code=404, detail="Needy not found")
    return prof


@router.post("/needy/{needy_id}/profile/upload")
def upload_profile_document(needy_id: int, file: UploadFile = None, current_user: dict = Depends(auth.get_current_user)):
    auth.ensure_owner_or_admin(current_user, "needy", needy_id)
    needy = db.get_needy_by_id(needy_id)
    if not needy:
        raise HTTPException(status_code=404, detail="Needy not found")
    try:
        filename = validate_and_save_upload(file, UPLOAD_DIR, allow_pdf=True)
    except UploadValidationError as exc:
        raise HTTPException(status_code=exc.status_code, detail=exc.detail)
    # save to profile
    prof = db.create_or_update_profile(needy_id, None, None, None, None, document=f"/needy_uploads/{filename}")
    # Auto-KYC v1: fire-and-forget AI pre-check; the verdict appears in the
    # admin moderation queue (the human still makes the final call).
    kyc_service.start_kyc_check(needy_id, os.path.join(UPLOAD_DIR, filename), needy.get("name") or "")
    return prof


@router.get("/needy/{needy_id}/profile", response_model=schemas.NeedyProfileOut)
def get_profile(needy_id: int, current_user: dict = Depends(auth.get_current_user)):
    auth.ensure_owner_or_admin(current_user, "needy", needy_id)
    p = db.get_profile(needy_id)
    if not p:
        raise HTTPException(status_code=404, detail="Profile not found")
    return p


@router.patch("/needy/{needy_id}/geo_push")
def set_geo_push(needy_id: int, payload: schemas.GeoPushUpdate, current_user: dict = Depends(auth.get_current_user)):
    """Geo-push subscription toggle (§48): opt in/out of «рядом появился
    подходящий лот» Web Push pings driven by needs_match.py."""
    auth.ensure_owner_or_admin(current_user, "needy", needy_id)
    if not db.set_geo_push_enabled(needy_id, payload.enabled):
        raise HTTPException(status_code=404, detail="Needy not found")
    return {"geo_push_enabled": payload.enabled}


@router.get("/needy/{needy_id}/export")
def export_account(needy_id: int, current_user: dict = Depends(auth.get_current_user)):
    """Data export (§49 «право на доступ»): a JSON dump of everything stored
    about this recipient. Owner or admin only."""
    auth.ensure_owner_or_admin(current_user, "needy", needy_id)
    data = db.export_account(needy_id)
    if data is None:
        raise HTTPException(status_code=404, detail="Needy not found")
    from fastapi.responses import JSONResponse
    return JSONResponse(
        content=json_mod.loads(json_mod.dumps(data, default=str, ensure_ascii=False)),
        headers={"Content-Disposition": f'attachment; filename="savefood_data_{needy_id}.json"'},
    )


@router.delete("/needy/{needy_id}/account")
def delete_account(needy_id: int, current_user: dict = Depends(auth.get_current_user)):
    """«Право на забвение» (§49): erase personal data, anonymise tickets (keeping
    aggregates), delete uploaded files and the login. Owner or admin only."""
    auth.ensure_owner_or_admin(current_user, "needy", needy_id)
    result = db.erase_account(needy_id)
    if result is None:
        raise HTTPException(status_code=404, detail="Needy not found")

    # Delete the ID document from disk (lives in this module's UPLOAD_DIR).
    doc = result.get("document")
    if doc:
        doc_path = os.path.join(UPLOAD_DIR, os.path.basename(doc))
        try:
            if os.path.isfile(doc_path):
                os.remove(doc_path)
        except OSError:
            pass
    # Delete any delivery photos (live in the volunteer uploads dir).
    from backend.volunteer.routes import UPLOAD_DIR as VOL_UPLOAD_DIR
    for photo in result.get("photos", []):
        if photo and photo.startswith("/volunteer_uploads/"):
            p = os.path.join(VOL_UPLOAD_DIR, os.path.basename(photo))
            try:
                if os.path.isfile(p):
                    os.remove(p)
            except OSError:
                pass
    return {"ok": True, "deleted": True}


@router.get("/needy/{needy_id}/document")
def download_needy_document(needy_id: int, current_user: dict = Depends(auth.get_current_user)):
    """Owner or admin-only download for a needy's uploaded ID document.

    Replaces the public StaticFiles mount on /needy_uploads so personal
    documents are not addressable by URL alone.
    """
    auth.ensure_owner_or_admin(current_user, "needy", needy_id)
    profile = db.get_profile(needy_id)
    doc_path = profile.get("document") if profile else None
    if not doc_path:
        raise HTTPException(status_code=404, detail="Document not found")
    # Stored as "/needy_uploads/<uuid>.<ext>" — collapse to a basename so a
    # malicious profile value can't traverse out of UPLOAD_DIR.
    filename = os.path.basename(doc_path)
    abs_path = os.path.join(UPLOAD_DIR, filename)
    real_path = os.path.realpath(abs_path)
    if not real_path.startswith(os.path.realpath(UPLOAD_DIR) + os.sep):
        raise HTTPException(status_code=404, detail="Document not found")
    if not os.path.isfile(real_path):
        raise HTTPException(status_code=404, detail="Document not found")
    return FileResponse(real_path)


@router.patch("/needy/{needy_id}/moderation")
def moderate_needy(needy_id: int, status: str, current_user: dict = Depends(auth.get_current_user)):
    if current_user.get("role") != "admin":
        raise HTTPException(status_code=403, detail="Admin access required")
    if status not in ('pending', 'approved', 'rejected'):
        raise HTTPException(status_code=400, detail="Invalid status")
    updated = db.set_needy_status(needy_id, status)
    if not updated:
        raise HTTPException(status_code=404, detail="Needy not found")
    # §5: delete document from disk after approval/rejection to protect personal data
    if status in ('approved', 'rejected'):
        profile = db.get_profile(needy_id)
        doc_path = profile.get('document') if profile else None
        if doc_path:
            # doc_path stored as "/needy_uploads/<filename>", resolve to absolute path
            filename = os.path.basename(doc_path)
            abs_path = os.path.join(UPLOAD_DIR, filename)
            try:
                if os.path.isfile(abs_path):
                    os.remove(abs_path)
                db.create_or_update_profile(needy_id, None, None, None, None, document=None)
            except Exception:
                pass
    return updated


@router.get("/lots", response_model=List[shop_schemas.LotOut])
def all_active_lots(limit: int = 20, offset: int = 0, category: Optional[str] = None, search: Optional[str] = None):
    # The hottest read on the platform (every recipient's map). Short-TTL
    # read-through cache: a lot may appear on the map up to TTL_LOTS seconds
    # late, which is harmless; no write-side invalidation needed.
    from backend import cache
    key = f"lots:active:{limit}:{offset}:{category or ''}:{search or ''}"
    return cache.cached_json(
        key, cache.TTL_LOTS,
        lambda: shop_db.get_all_active_lots(limit=limit, offset=offset, category=category, search=search),
    )


@router.get("/needy/{needy_id}/tickets", response_model=List[schemas.TicketOut])
def get_tickets(needy_id: int, current_user: dict = Depends(auth.get_current_user)):
    auth.ensure_owner_or_admin(current_user, "needy", needy_id)
    tickets = db.get_tickets_by_needy_id(needy_id)
    return tickets


@router.get("/needy/{needy_id}/notifications", response_model=List[schemas.NotificationOut])
def get_notifications(needy_id: int, current_user: dict = Depends(auth.get_current_user)):
    auth.ensure_owner_or_admin(current_user, "needy", needy_id)
    needy = db.get_needy_by_id(needy_id)
    if not needy:
        raise HTTPException(status_code=404, detail="Needy not found")
    notes = db.get_notifications(needy_id)
    return notes


@router.get("/needy/{needy_id}/history", response_model=List[schemas.TicketOut])
def history(needy_id: int, limit: int = 20, offset: int = 0, current_user: dict = Depends(auth.get_current_user)):
    auth.ensure_owner_or_admin(current_user, "needy", needy_id)
    data = db.get_history(needy_id, limit=limit, offset=offset)
    return data


@router.delete("/needy/{needy_id}/ticket/{ticket_id}")
def cancel_ticket(needy_id: int, ticket_id: int, current_user: dict = Depends(auth.get_current_user)):
    if current_user["role"] not in ("needy", "admin"):
        raise HTTPException(status_code=403, detail="Forbidden")
    if current_user["role"] == "needy" and current_user.get("related_id") != needy_id:
        raise HTTPException(status_code=403, detail="Forbidden")
    notify_vol_id = None
    with get_db_cursor() as cur:
        cur.execute("SELECT * FROM tickets WHERE id = %s AND needy_id = %s", (ticket_id, needy_id))
        ticket = cur.fetchone()
        if not ticket:
            raise HTTPException(status_code=404, detail="Ticket not found")
        if ticket["status"] in ("open", "assigned"):
            vol_id = ticket.get("assigned_volunteer_id")
            cur.execute(
                "UPDATE tickets SET status = 'cancelled', assigned_volunteer = NULL, assigned_volunteer_id = NULL "
                "WHERE id = %s AND status IN ('open', 'assigned')",
                (ticket_id,),
            )
            if cur.rowcount == 0:
                raise HTTPException(status_code=409, detail="Заявка уже изменила статус — обновите страницу")
            
            # Guarded return: return reserved quantity to the lot if it's still active.
            if ticket['lot_id']:
                cur.execute(
                    "UPDATE lots SET quantity = quantity + %s WHERE id = %s AND status = 'active'",
                    (ticket.get('quantity') or 1.0, ticket['lot_id'])
                )

            if vol_id:
                # Drop the stop from the volunteer's active route so they don't
                # drive to a recipient who is no longer waiting.
                cur.execute(
                    "SELECT id, points FROM volunteer_routes WHERE volunteer_id = %s AND status = 'in_progress'",
                    (vol_id,),
                )
                route = cur.fetchone()
                if route:
                    try:
                        points = json_mod.loads(route["points"] or "[]")
                    except Exception:
                        points = []
                    changed = False
                    for p in points:
                        if p.get("kind") == "ticket" and p.get("ticket_id") == ticket_id and not p.get("done"):
                            p["done"] = True
                            p["cancelled"] = True
                            changed = True
                    if changed:
                        cur.execute(
                            "UPDATE volunteer_routes SET points = %s WHERE id = %s",
                            (json_mod.dumps(points, ensure_ascii=False), route["id"]),
                        )
                cur.execute(
                    "INSERT INTO notifications (volunteer_id, type, payload, created_at, read) VALUES (%s, %s, %s, %s, 0)",
                    (vol_id, "ticket_cancelled",
                     f"Получатель отменил заявку #{ticket_id} — точка снята с вашего маршрута.",
                     datetime.now(timezone.utc)),
                )
                notify_vol_id = vol_id
        else:
            raise HTTPException(status_code=400, detail="Можно отменить только открытую или назначенную заявку")
    if notify_vol_id:
        try:
            telegram_service.notify_volunteer(
                notify_vol_id,
                f"❌ Получатель отменил заявку #{ticket_id} — точка снята с вашего маршрута.",
            )
        except Exception:
            pass
    return {"ok": True}


@router.websocket("/ws/needy/{needy_id}")
async def ws_needy(websocket: WebSocket, needy_id: int):
    # Auth happens via a handshake message instead of a query-string token,
    # so the JWT never appears in nginx access logs or browser history. The
    # client must accept the connection and then immediately send
    #     {"type": "auth", "token": "...", "since_id": <optional int>}
    # within AUTH_TIMEOUT seconds, otherwise we drop the socket.
    AUTH_TIMEOUT = 5.0

    # Reject before accept() so we don't even spin up the loop when the
    # per-user connection cap is already hit.
    if ws_connections[needy_id] >= MAX_WS_PER_USER:
        await websocket.close(code=1008, reason="Too many connections")
        return

    await websocket.accept()
    ws_connections[needy_id] += 1
    try:
        try:
            first = await asyncio.wait_for(websocket.receive_json(), timeout=AUTH_TIMEOUT)
        except (asyncio.TimeoutError, ValueError):
            await websocket.close(code=1008)
            return

        if not isinstance(first, dict) or first.get("type") != "auth":
            await websocket.close(code=1008)
            return

        token = first.get("token")
        payload = auth.decode_access_token(token) if token else None
        role = payload.get("role") if payload else None
        if not payload or (role != "admin" and not (role == "needy" and payload.get("related_id") == needy_id)):
            await websocket.close(code=1008)
            return

        username = payload.get("sub")
        if username:
            def _check_blocked():
                with get_db_cursor() as cur:
                    cur.execute("SELECT is_blocked FROM users WHERE username = %s", (username,))
                    row = cur.fetchone()
                return bool(row and row["is_blocked"])

            if await asyncio.to_thread(_check_blocked):
                await websocket.close(code=1008)
                return

        # Optional resume cursor; otherwise start from MAX(id).
        since_id = first.get("since_id")
        if not isinstance(since_id, int) or since_id < 0:
            since_id = None

        def get_last():
            with get_db_cursor() as cur:
                cur.execute(
                    "SELECT COALESCE(MAX(id), 0) as mid FROM notifications WHERE needy_id = %s",
                    (needy_id,),
                )
                return cur.fetchone()["mid"]

        last_id = since_id if since_id is not None else await asyncio.to_thread(get_last)
        await websocket.send_json({"type": "ready", "last_id": last_id})

        while True:
            def fetch_new(lid):
                with get_db_cursor() as cur:
                    cur.execute(
                        "SELECT id, type, payload FROM notifications WHERE needy_id = %s AND id > %s ORDER BY id ASC",
                        (needy_id, lid),
                    )
                    return [dict(r) for r in cur.fetchall()]

            rows = await asyncio.to_thread(fetch_new, last_id)
            for row in rows:
                last_id = row["id"]
                await websocket.send_json({"id": row["id"], "type": row["type"], "payload": row["payload"]})

            # Wait ~3s between polls *on the socket itself* instead of sleeping:
            # receive() raises WebSocketDisconnect the moment the client goes
            # away. A plain sleep never notices the disconnect (we only write),
            # so dead loops would pile up and exhaust MAX_WS_PER_USER forever.
            try:
                await asyncio.wait_for(websocket.receive_text(), timeout=3)
            except asyncio.TimeoutError:
                pass
    except WebSocketDisconnect:
        pass
    except Exception:
        pass
    finally:
        ws_connections[needy_id] = max(0, ws_connections[needy_id] - 1)


@router.post("/needy/{needy_id}/ticket/{ticket_id}/rate")
def rate_delivery(needy_id: int, ticket_id: int, rating: int, comment: Optional[str] = None, current_user: dict = Depends(auth.get_current_user)):
    if current_user["role"] not in ("needy", "admin"):
        raise HTTPException(status_code=403, detail="Forbidden")
    if current_user["role"] == "needy" and current_user.get("related_id") != needy_id:
        raise HTTPException(status_code=403, detail="Forbidden")
    if not (1 <= rating <= 5):
        raise HTTPException(status_code=400, detail="Rating must be between 1 and 5")
    # The note lands in the volunteer's thanks feed — cap it server-side (the
    # frontend's maxLength=300 is advisory only for direct API callers).
    if comment is not None:
        comment = comment[:500]
    with get_db_cursor() as cur:
        cur.execute("SELECT * FROM tickets WHERE id = %s AND needy_id = %s", (ticket_id, needy_id))
        ticket = cur.fetchone()
        if not ticket:
            raise HTTPException(status_code=404, detail="Ticket not found")
        if ticket["status"] != "fulfilled":
            raise HTTPException(status_code=400, detail="Can only rate fulfilled deliveries")
        # Self-pickup has no courier: a rating row with volunteer_id NULL is
        # invisible to every rating query (all filter by volunteer_id) — reject
        # instead of silently swallowing the recipient's feedback.
        if ticket.get("assigned_volunteer_id") is None:
            raise HTTPException(status_code=400, detail="Оценка доступна только для доставок волонтёром")
        # comment is optional: when omitted (e.g. user only re-taps a star) keep
        # any thank-you note already on file instead of wiping it to ''.
        cur.execute(
            """
            INSERT INTO delivery_ratings (ticket_id, volunteer_id, rating, comment)
            VALUES (%s, %s, %s, %s)
            ON CONFLICT (ticket_id) DO UPDATE SET rating = EXCLUDED.rating,
                comment = COALESCE(EXCLUDED.comment, delivery_ratings.comment)
            """,
            (ticket_id, ticket.get("assigned_volunteer_id"), rating, comment),
        )
    return {"ok": True}


@router.post("/needy/{needy_id}/ticket/{ticket_id}/photo")
@limiter.limit("3/hour")
def upload_impact_photo(
    request: Request,
    needy_id: int,
    ticket_id: int,
    file: UploadFile = File(...),
    current_user: dict = Depends(auth.get_current_user),
):
    """Recipients upload photos of their received food to share results/impact."""
    auth.ensure_owner_or_admin(current_user, "needy", needy_id)
    with get_db_cursor() as cur:
        cur.execute("SELECT * FROM tickets WHERE id = %s AND needy_id = %s", (ticket_id, needy_id))
        ticket = cur.fetchone()
        if not ticket:
            raise HTTPException(status_code=404, detail="Ticket not found")
        if ticket["status"] != "fulfilled":
            raise HTTPException(status_code=400, detail="Can only upload photos for fulfilled deliveries")

    # Reuse the volunteer uploads directory for the public impact feed
    from backend.volunteer.routes import UPLOAD_DIR as VOL_UPLOAD_DIR
    try:
        filename = validate_and_save_upload(file, VOL_UPLOAD_DIR)
    except UploadValidationError as exc:
        raise HTTPException(status_code=exc.status_code, detail=exc.detail)

    photo_url = f"/volunteer_uploads/{filename}"
    # The photo is NOT public yet: it lands as 'pending' and only the
    # moderation queue / AI pre-check can promote it to 'approved' (§36.1).
    with get_db_cursor() as cur:
        cur.execute(
            """
            UPDATE tickets
               SET delivery_photo = %s,
                   delivery_photo_status = 'pending',
                   delivery_photo_ai_verdict = NULL,
                   delivery_photo_ai_score = NULL,
                   delivery_photo_ai_notes = NULL,
                   delivery_photo_reviewed_at = NULL
             WHERE id = %s
            """,
            (photo_url, ticket_id),
        )

    # The DB now points at the new file — the replaced photo (if any) is an orphan.
    old_photo = ticket.get("delivery_photo")
    if old_photo and old_photo.startswith("/volunteer_uploads/"):
        old_path = os.path.join(VOL_UPLOAD_DIR, os.path.basename(old_photo))
        try:
            if os.path.isfile(old_path):
                os.remove(old_path)
        except OSError:
            pass

    # Fire-and-forget AI "is this actually food?" pre-check (§36.1).
    from backend import photo_moderation
    photo_moderation.start_photo_check(ticket_id, os.path.join(VOL_UPLOAD_DIR, filename))

    return {"photo_url": photo_url, "status": "pending"}
