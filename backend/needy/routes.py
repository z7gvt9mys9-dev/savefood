from fastapi import APIRouter, HTTPException, UploadFile
from typing import List
import os
import shutil
import uuid

from backend.needy import db, schemas
from backend.shop import db as shop_db
from backend.shop import schemas as shop_schemas

router = APIRouter()

UPLOAD_DIR = os.path.join(os.path.dirname(__file__), "uploads")


@router.post("/needy/register")
def register_needy(payload: schemas.NeedyCreate):
    needy_id = db.create_needy(payload.name, payload.contact)
    return {"id": needy_id}


@router.post("/needy/{needy_id}/ticket")
def create_ticket(needy_id: int, payload: schemas.TicketCreate):
    needy = db.get_needy_by_id(needy_id)
    if not needy:
        raise HTTPException(status_code=404, detail="Needy not found")

    ticket_id = db.create_ticket(needy_id, payload.items, payload.address, payload.lat, payload.lon, payload.available_time)
    if ticket_id is None:
        # violation of once-per-week rule
        raise HTTPException(status_code=400, detail="Ticket creation blocked: assistance is limited to once per 7 days")
    return {"id": ticket_id}


@router.patch("/needy/notifications/{notification_id}/read")
def mark_notification_read(notification_id: int):
    db.mark_notification_read(notification_id)
    return {"ok": True}


@router.patch("/needy/{needy_id}")
def update_needy(needy_id: int, payload: schemas.NeedyCreate):
    updated = db.update_needy(needy_id, payload.name, payload.contact)
    if not updated:
        raise HTTPException(status_code=404, detail="Needy not found")
    return updated


@router.get("/needy/{needy_id}")
def get_needy(needy_id: int):
    needy = db.get_needy_by_id(needy_id)
    if not needy:
        raise HTTPException(status_code=404, detail="Needy not found")
    return needy


@router.post("/needy/{needy_id}/profile", response_model=schemas.NeedyProfileOut)
def create_profile(needy_id: int, payload: schemas.NeedyProfileCreate):
    prof = db.create_or_update_profile(needy_id, payload.address, payload.family_size, payload.preferences, payload.urgency)
    if not prof:
        raise HTTPException(status_code=404, detail="Needy not found")
    return prof


@router.patch("/needy/{needy_id}/profile", response_model=schemas.NeedyProfileOut)
def patch_profile(needy_id: int, payload: schemas.NeedyProfileUpdate):
    prof = db.create_or_update_profile(needy_id, payload.address, payload.family_size, payload.preferences, payload.urgency)
    if not prof:
        raise HTTPException(status_code=404, detail="Needy not found")
    return prof


@router.post("/needy/{needy_id}/profile/upload")
def upload_profile_document(needy_id: int, file: UploadFile = None):
    needy = db.get_needy_by_id(needy_id)
    if not needy:
        raise HTTPException(status_code=404, detail="Needy not found")
    if not file or not file.filename:
        raise HTTPException(status_code=400, detail="No file uploaded")
    os.makedirs(UPLOAD_DIR, exist_ok=True)
    original_name = os.path.basename(file.filename)
    ext = os.path.splitext(original_name)[1]
    filename = f"{uuid.uuid4().hex}{ext}"
    dest = os.path.join(UPLOAD_DIR, filename)
    with open(dest, "wb") as out:
        shutil.copyfileobj(file.file, out)
    # save to profile
    prof = db.create_or_update_profile(needy_id, None, None, None, None, document=f"/needy_uploads/{filename}")
    return prof


@router.get("/needy/{needy_id}/profile", response_model=schemas.NeedyProfileOut)
def get_profile(needy_id: int):
    p = db.get_profile(needy_id)
    if not p:
        raise HTTPException(status_code=404, detail="Profile not found")
    return p


@router.patch("/needy/{needy_id}/moderation")
def moderate_needy(needy_id: int, status: str):
    if status not in ('pending', 'approved', 'rejected'):
        raise HTTPException(status_code=400, detail="Invalid status")
    updated = db.set_needy_status(needy_id, status)
    if not updated:
        raise HTTPException(status_code=404, detail="Needy not found")
    return updated


@router.get("/lots", response_model=List[shop_schemas.LotOut])
def all_active_lots():
    rows = shop_db.get_all_active_lots()
    return rows


@router.get("/needy/{needy_id}/tickets", response_model=List[schemas.TicketOut])
def get_tickets(needy_id: int):
    tickets = db.get_tickets_by_needy_id(needy_id)
    return tickets


@router.get("/needy/{needy_id}/notifications", response_model=List[schemas.NotificationOut])
def get_notifications(needy_id: int):
    needy = db.get_needy_by_id(needy_id)
    if not needy:
        raise HTTPException(status_code=404, detail="Needy not found")
    notes = db.get_notifications(needy_id)
    return notes


@router.post("/tickets/{ticket_id}/assign", response_model=schemas.TicketOut)
def assign_ticket(ticket_id: int, volunteer_name: str):
    updated = db.assign_ticket(ticket_id, volunteer_name)
    if not updated:
        raise HTTPException(status_code=404, detail="Ticket not found or not assignable")
    return updated


@router.get("/needy/{needy_id}/history", response_model=List[schemas.TicketOut])
def history(needy_id: int):
    data = db.get_history(needy_id)
    return data
