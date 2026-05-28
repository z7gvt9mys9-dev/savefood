from fastapi import APIRouter, HTTPException, UploadFile, File, Form, Depends
from typing import List, Optional
import os
import uuid
import shutil

from backend.shop import db, schemas
from backend.database import create_user
from backend.auth import get_password_hash, get_current_user, ensure_owner_or_admin

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
def register_shop(payload: schemas.ShopCreate):
    shop_id = db.create_shop(payload.name, payload.contact, payload.lat, payload.lon, payload.city)
    if payload.username and payload.password:
        hashed = get_password_hash(payload.password)
        try:
            create_user(payload.username, hashed, "shop", shop_id)
        except Exception:
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
        ext = os.path.splitext(file.filename)[1]
        filename = f"{uuid.uuid4().hex}{ext}"
        dest_path = os.path.join(UPLOAD_DIR, filename)
        with open(dest_path, "wb") as out:
            shutil.copyfileobj(file.file, out)
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


@router.post("/lots/{lot_id}/take", response_model=schemas.LotOut)
def take_lot(lot_id: int, payload: schemas.TakeLotRequest, current_user: dict = Depends(get_current_user)):
    if current_user.get("role") not in ("volunteer", "admin"):
        raise HTTPException(status_code=403, detail="Forbidden")
    updated = db.take_lot(lot_id, payload.volunteer_name)
    if not updated:
        raise HTTPException(status_code=404, detail="Lot not found or already taken")
    return updated


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
    updated = db.update_shop(shop_id, payload.name, payload.contact, payload.lat, payload.lon)
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
