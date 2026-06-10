from fastapi import APIRouter, HTTPException, UploadFile, File, Form, Depends, Request
from typing import List, Optional
from datetime import datetime, timezone
import os
import re
import psycopg2.errors

from backend.shop import db, schemas
from backend.database import create_user, get_db_cursor
from backend.needy import db as needy_db
from backend.auth import get_password_hash, get_current_user, ensure_owner_or_admin
from backend.limiter import limiter
from backend.utils import validate_and_save_upload, UploadValidationError

router = APIRouter()

UPLOAD_DIR = os.path.join(os.path.dirname(__file__), "uploads")


def _require_lot_owner(lot_id: int, current_user: dict):
    """Return the lot if the caller owns its shop (or is admin), else 404/403."""
    lot = db.get_lot_by_id(lot_id)
    if not lot:
        raise HTTPException(status_code=404, detail="Lot not found")
    ensure_owner_or_admin(current_user, "shop", lot["shop_id"])
    return lot


@router.post("/shops/register")
@limiter.limit("5/minute")
def register_shop(request: Request, payload: schemas.ShopCreate):
    # An account is mandatory: a shop row without credentials can never log in.
    if not payload.username or not payload.password:
        raise HTTPException(status_code=400, detail="Укажите логин и пароль")
    hashed = get_password_hash(payload.password)
    # Single transaction: if the username is taken, the shop row rolls back too.
    try:
        with get_db_cursor() as cur:
            cur.execute(
                "INSERT INTO shops (name, contact, lat, lon, city, created_at) VALUES (%s, %s, %s, %s, %s, %s) RETURNING id",
                (payload.name, payload.contact, payload.lat, payload.lon, payload.city, datetime.now(timezone.utc)),
            )
            shop_id = cur.fetchone()['id']
            cur.execute(
                "INSERT INTO users (username, hashed_password, role, related_id) VALUES (%s, %s, %s, %s)",
                (payload.username, hashed, "shop", shop_id),
            )
    except psycopg2.errors.UniqueViolation:
        raise HTTPException(status_code=409, detail="Username already taken")
    return {"id": shop_id}


@router.post("/shops/{shop_id}/lots")
def create_lot(shop_id: int, payload: schemas.LotCreate, current_user: dict = Depends(get_current_user)):
    ensure_owner_or_admin(current_user, "shop", shop_id)
    shop = db.get_shop_by_id(shop_id)
    if not shop:
        raise HTTPException(status_code=404, detail="Shop not found")

    expiry = payload.expiry_date.isoformat() if payload.expiry_date else None
    lot_id = db.create_lot(shop_id, payload.description, payload.quantity, expiry, payload.photo, payload.address, payload.time_slot, payload.category, payload.comment)
    return {"id": lot_id}


@router.post("/shops/{shop_id}/lots/upload")
def create_lot_upload(
    shop_id: int,
    description: str = Form(...),
    quantity: int = Form(...),
    expiry_date: Optional[str] = Form(None),
    address: Optional[str] = Form(None),
    time_slot: Optional[str] = Form(None),
    category: Optional[str] = Form(None),
    comment: Optional[str] = Form(None),
    file: Optional[UploadFile] = File(None),
    current_user: dict = Depends(get_current_user),
):
    ensure_owner_or_admin(current_user, "shop", shop_id)
    shop = db.get_shop_by_id(shop_id)
    if not shop:
        raise HTTPException(status_code=404, detail="Shop not found")

    photo_url = None
    if file and file.filename:
        try:
            filename = validate_and_save_upload(file, UPLOAD_DIR)
        except UploadValidationError as exc:
            raise HTTPException(status_code=exc.status_code, detail=exc.detail)
        photo_url = f"/uploads/{filename}"

    lot_id = db.create_lot(shop_id, description, int(quantity), expiry_date, photo_url, address, time_slot, category, comment)
    return {"id": lot_id}


@router.get("/shops/{shop_id}/lots", response_model=List[schemas.LotOut])
def list_active_lots(shop_id: int, current_user: dict = Depends(get_current_user)):
    ensure_owner_or_admin(current_user, "shop", shop_id)
    shop = db.get_shop_by_id(shop_id)
    if not shop:
        raise HTTPException(status_code=404, detail="Shop not found")
    rows = db.get_active_lots(shop_id)
    return rows


@router.delete("/lots/{lot_id}")
def delete_lot(lot_id: int, current_user: dict = Depends(get_current_user)):
    _require_lot_owner(lot_id, current_user)
    ok = db.delete_lot(lot_id)
    if not ok:
        raise HTTPException(status_code=404, detail="Lot not found or cannot be deleted")
    return {"ok": True}


@router.patch("/lots/{lot_id}", response_model=schemas.LotOut)
def patch_lot(lot_id: int, payload: schemas.LotUpdate, current_user: dict = Depends(get_current_user)):
    _require_lot_owner(lot_id, current_user)
    expiry = payload.expiry_date.isoformat() if payload.expiry_date else None
    updated = db.update_lot(lot_id, payload.description, payload.quantity, expiry, payload.address, payload.category, payload.comment)
    if not updated:
        raise HTTPException(status_code=404, detail="Lot not found or cannot be updated")
    return updated


@router.post("/lots/{lot_id}/confirm_transfer")
def confirm_transfer(lot_id: int, current_user: dict = Depends(get_current_user)):
    _require_lot_owner(lot_id, current_user)
    ok = db.confirm_lot_transfer(lot_id)
    if not ok:
        raise HTTPException(status_code=400, detail="Lot not found or not in taken status")
    return {"ok": True}


@router.get("/shops/{shop_id}/history", response_model=List[schemas.LotOut])
def get_history(shop_id: int, limit: int = 20, offset: int = 0, current_user: dict = Depends(get_current_user)):
    ensure_owner_or_admin(current_user, "shop", shop_id)
    shop = db.get_shop_by_id(shop_id)
    if not shop:
        raise HTTPException(status_code=404, detail="Shop not found")
    rows = db.get_history(shop_id, limit=limit, offset=offset)
    return rows


@router.get("/shops/{shop_id}", response_model=schemas.ShopOut)
def get_shop(shop_id: int, current_user: dict = Depends(get_current_user)):
    ensure_owner_or_admin(current_user, "shop", shop_id)
    shop = db.get_shop_by_id(shop_id)
    if not shop:
        raise HTTPException(status_code=404, detail="Shop not found")
    return shop


@router.patch("/shops/{shop_id}", response_model=schemas.ShopOut)
def patch_shop(shop_id: int, payload: schemas.ShopUpdate, current_user: dict = Depends(get_current_user)):
    ensure_owner_or_admin(current_user, "shop", shop_id)
    updated = db.update_shop(shop_id, payload.name, payload.contact, payload.lat, payload.lon, payload.city)
    if not updated:
        raise HTTPException(status_code=404, detail="Shop not found or cannot be updated")
    return updated


@router.get("/shops/{shop_id}/notifications", response_model=List[schemas.NotificationOut])
def notifications(shop_id: int, current_user: dict = Depends(get_current_user)):
    ensure_owner_or_admin(current_user, "shop", shop_id)
    shop = db.get_shop_by_id(shop_id)
    if not shop:
        raise HTTPException(status_code=404, detail="Shop not found")
    notes = db.get_notifications(shop_id)
    return notes


@router.patch("/shops/notifications/{notification_id}/read")
def mark_notification_read(notification_id: int, current_user: dict = Depends(get_current_user)):
    note = db.get_notification_by_id(notification_id)
    if not note:
        raise HTTPException(status_code=404, detail="Notification not found")
    ensure_owner_or_admin(current_user, "shop", note.get("shop_id"))
    db.mark_notification_read(notification_id)
    return {"ok": True}


@router.post("/shops/{shop_id}/self_pickup/confirm")
def confirm_self_pickup(shop_id: int, payload: schemas.SelfPickupConfirm, current_user: dict = Depends(get_current_user)):
    """The shop scans/types the recipient's QR code (SF-{ticket_id}) to close a
    self-pickup ticket. Without this, self-pickup tickets stay 'open' forever and
    block the recipient from creating any new ticket."""
    ensure_owner_or_admin(current_user, "shop", shop_id)
    match = re.fullmatch(r"SF-(\d+)", (payload.code or "").strip().upper())
    if not match:
        raise HTTPException(status_code=400, detail="Неверный формат кода (ожидается SF-<номер>)")
    ticket_id = int(match.group(1))

    with get_db_cursor() as cur:
        cur.execute(
            """
            SELECT t.* FROM tickets t
            JOIN lots l ON l.id = t.lot_id
            WHERE t.id = %s AND l.shop_id = %s
            """,
            (ticket_id, shop_id),
        )
        ticket = cur.fetchone()
        if not ticket:
            raise HTTPException(status_code=404, detail="Заявка не найдена или относится к другому магазину")
        if not ticket["self_pickup"]:
            raise HTTPException(status_code=400, detail="Эта заявка доставляется волонтёром, а не самовывозом")
        if ticket["status"] != "open":
            raise HTTPException(status_code=400, detail="Заявка уже закрыта или отменена")
        now = datetime.now(timezone.utc)
        cur.execute(
            "UPDATE tickets SET status = 'fulfilled', fulfilled_at = %s WHERE id = %s",
            (now, ticket_id),
        )
        cur.execute(
            "INSERT INTO notifications (needy_id, type, payload, created_at, read) VALUES (%s, %s, %s, %s, 0)",
            (ticket["needy_id"], 'self_pickup_confirmed',
             f'Самовывоз по заявке #{ticket_id} подтверждён магазином. Спасибо!', now),
        )
    try:
        needy_db.set_profile_last_received(ticket["needy_id"], now)
    except Exception:
        pass
    return {"ok": True, "ticket_id": ticket_id}
