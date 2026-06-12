from typing import Optional
from pydantic import BaseModel, Field
from datetime import datetime


class NeedyCreate(BaseModel):
    name: str = Field(..., example="John Doe")
    contact: Optional[str] = Field(None, example="+7 900 000 00 00")
    username: Optional[str] = None
    password: Optional[str] = Field(None, min_length=8, max_length=128)


class NeedyOut(BaseModel):
    id: int
    name: str
    contact: Optional[str]
    created_at: datetime
    status: Optional[str]
    document: Optional[str]


class TicketCreate(BaseModel):
    items: Optional[str] = None
    address: Optional[str] = None
    lat: Optional[float] = None
    lon: Optional[float] = None
    available_time: Optional[str] = None
    lot_id: Optional[int] = None
    apartment: Optional[str] = None
    floor_num: Optional[str] = None
    entrance: Optional[str] = None
    self_pickup: bool = False


class TicketOut(BaseModel):
    id: int
    needy_id: int
    items: Optional[str]
    address: Optional[str]
    lat: Optional[float]
    lon: Optional[float]
    available_time: Optional[str]
    lot_id: Optional[int]
    status: str
    created_at: datetime
    assigned_volunteer_id: Optional[int] = None
    fulfilled_at: Optional[datetime]
    apartment: Optional[str]
    floor_num: Optional[str]
    entrance: Optional[str]
    self_pickup: Optional[bool] = None
    delivery_photo: Optional[str] = None
    delivery_photo_status: Optional[str] = None  # pending/approved/rejected (moderation §36.1)
    rating: Optional[int] = None  # joined from delivery_ratings in history
    rating_comment: Optional[str] = None  # thank-you note joined from delivery_ratings


class NotificationOut(BaseModel):
    id: int
    needy_id: int
    type: Optional[str]
    payload: Optional[str]
    created_at: datetime
    read: int


class NeedyProfileCreate(BaseModel):
    address: Optional[str] = None
    family_size: Optional[int] = None
    preferences: Optional[str] = None
    urgency: Optional[str] = None
    available_time: Optional[str] = None
    apartment: Optional[str] = None
    floor_num: Optional[str] = None
    entrance: Optional[str] = None
    city: Optional[str] = None
    lat: Optional[float] = None
    lon: Optional[float] = None


class NeedyProfileOut(BaseModel):
    needy_id: int
    address: Optional[str]
    family_size: Optional[int]
    preferences: Optional[str]
    urgency: Optional[str]
    available_time: Optional[str]
    last_received_at: Optional[datetime]
    document: Optional[str]
    apartment: Optional[str]
    floor_num: Optional[str]
    entrance: Optional[str]
    city: Optional[str] = None
    lat: Optional[float] = None
    lon: Optional[float] = None


class NeedyProfileUpdate(BaseModel):
    address: Optional[str] = None
    family_size: Optional[int] = None
    preferences: Optional[str] = None
    urgency: Optional[str] = None
    available_time: Optional[str] = None
    apartment: Optional[str] = None
    floor_num: Optional[str] = None
    entrance: Optional[str] = None
    city: Optional[str] = None
    lat: Optional[float] = None
    lon: Optional[float] = None