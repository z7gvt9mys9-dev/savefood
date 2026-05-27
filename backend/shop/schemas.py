from typing import Optional
from pydantic import BaseModel, Field
from datetime import date, datetime


class ShopCreate(BaseModel):
    name: str = Field(..., example="My Shop")
    contact: Optional[str] = Field(None, example="+7 900 000 00 00")
    lat: Optional[float] = None
    lon: Optional[float] = None
    city: Optional[str] = None
    username: Optional[str] = None
    password: Optional[str] = None


class ShopOut(BaseModel):
    id: int
    name: str
    contact: Optional[str]
    created_at: datetime
    lat: Optional[float]
    lon: Optional[float]
    city: Optional[str]


class LotCreate(BaseModel):
    description: str
    quantity: int
    expiry_date: Optional[date] = None
    photo: Optional[str] = None
    address: Optional[str] = None
    time_slot: Optional[str] = None
    category: Optional[str] = None
    comment: Optional[str] = None


class LotOut(BaseModel):
    id: int
    shop_id: int
    description: Optional[str]
    quantity: Optional[int]
    expiry_date: Optional[date]
    photo: Optional[str]
    address: Optional[str]
    time_slot: Optional[str]
    status: str
    created_at: datetime
    taken_at: Optional[datetime]
    taken_by: Optional[str]
    category: Optional[str]
    comment: Optional[str]
    shop_name: Optional[str] = None
    shop_lat: Optional[float] = None
    shop_lon: Optional[float] = None


class LotUpdate(BaseModel):
    description: Optional[str] = None
    quantity: Optional[int] = None
    expiry_date: Optional[date] = None
    address: Optional[str] = None
    category: Optional[str] = None
    comment: Optional[str] = None


class TakeLotRequest(BaseModel):
    volunteer_name: str


class NotificationOut(BaseModel):
    id: int
    shop_id: int
    lot_id: Optional[int]
    type: Optional[str]
    payload: Optional[str]
    created_at: datetime
    read: int


class ShopUpdate(BaseModel):
    name: Optional[str] = None
    contact: Optional[str] = None
    lat: Optional[float] = None
    lon: Optional[float] = None
    city: Optional[str] = None
