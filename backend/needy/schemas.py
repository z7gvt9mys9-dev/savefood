from typing import Optional, List
from pydantic import BaseModel, Field
from datetime import datetime


class NeedyCreate(BaseModel):
    name: str = Field(..., example="John Doe")
    contact: Optional[str] = Field(None, example="+7 900 000 00 00")


class NeedyOut(BaseModel):
    id: int
    name: str
    contact: Optional[str]
    created_at: datetime


class TicketCreate(BaseModel):
    items: Optional[str] = None
    address: Optional[str] = None
    lat: Optional[float] = None
    lon: Optional[float] = None


class TicketOut(BaseModel):
    id: int
    needy_id: int
    items: Optional[str]
    address: Optional[str]
    lat: Optional[float]
    lon: Optional[float]
    status: str
    created_at: datetime
    assigned_volunteer: Optional[str]
    fulfilled_at: Optional[datetime]


class NotificationOut(BaseModel):
    id: int
    needy_id: int
    type: Optional[str]
    payload: Optional[str]
    created_at: datetime
    read: int