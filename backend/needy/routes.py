from fastapi import APIRouter, HTTPException
from typing import List
import os

from backend.needy import db, schemas

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
