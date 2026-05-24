from fastapi import APIRouter, HTTPException, UploadFile, File, Form
from typing import List, Optional
import os
import uuid
import shutil

from backend.shop import db, schemas

router = APIRouter()

UPLOAD_DIR = os.path.join(os.path.dirname(__file__), "uploads")


@router.post("/shops/register")
def register_shop(payload: schemas.ShopCreate):
    shop_id = db.create_shop(payload.name, payload.contact, payload.lat, payload.lon)
    return {"id": shop_id}


@router.post("/shops/{shop_id}/lots")
def create_lot(shop_id: int, payload: schemas.LotCreate):
    shop = db.get_shop_by_id(shop_id)
    if not shop:
        raise HTTPException(status_code=404, detail="Shop not found")

    expiry = payload.expiry_date.isoformat() if payload.expiry_date else None
    lot_id = db.create_lot(shop_id, payload.description, payload.quantity, expiry, payload.photo, payload.address)
    return {"id": lot_id}


@router.post("/shops/{shop_id}/lots/upload")
def create_lot_upload(
    shop_id: int,
    description: str = Form(...),
    quantity: int = Form(...),
    expiry_date: Optional[str] = Form(None),
    address: Optional[str] = Form(None),
    file: Optional[UploadFile] = File(None),
):
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

    lot_id = db.create_lot(shop_id, description, int(quantity), expiry_date, photo_url, address)
    return {"id": lot_id}


@router.get("/shops/{shop_id}/lots", response_model=List[schemas.LotOut])
def list_active_lots(shop_id: int):
    shop = db.get_shop_by_id(shop_id)
    if not shop:
        raise HTTPException(status_code=404, detail="Shop not found")
    rows = db.get_active_lots(shop_id)
    return rows


@router.post("/lots/{lot_id}/take", response_model=schemas.LotOut)
def take_lot(lot_id: int, payload: schemas.TakeLotRequest):
    updated = db.take_lot(lot_id, payload.volunteer_name)
    if not updated:
        raise HTTPException(status_code=404, detail="Lot not found or already taken")
    return updated


@router.get("/shops/{shop_id}/history", response_model=List[schemas.LotOut])
def get_history(shop_id: int):
    shop = db.get_shop_by_id(shop_id)
    if not shop:
        raise HTTPException(status_code=404, detail="Shop not found")
    rows = db.get_history(shop_id)
    return rows


@router.get("/shops/{shop_id}/notifications", response_model=List[schemas.NotificationOut])
def notifications(shop_id: int):
    shop = db.get_shop_by_id(shop_id)
    if not shop:
        raise HTTPException(status_code=404, detail="Shop not found")
    notes = db.get_notifications(shop_id)
    return notes


@router.patch("/shops/notifications/{notification_id}/read")
def mark_notification_read(notification_id: int):
    db.mark_notification_read(notification_id)
    return {"ok": True}
