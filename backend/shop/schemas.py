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
    password: Optional[str] = Field(None, min_length=8, max_length=128)
    # 'business' (default) | 'private' — C2C donor, see §45
    kind: Optional[str] = "business"


class ShopOut(BaseModel):
    id: int
    name: str
    contact: Optional[str]
    created_at: datetime
    lat: Optional[float]
    lon: Optional[float]
    city: Optional[str]
    kind: Optional[str] = "business"


class LotCreate(BaseModel):
    description: str
    quantity: int
    expiry_date: Optional[date] = None
    photo: Optional[str] = None
    address: Optional[str] = None
    time_slot: Optional[str] = None
    category: Optional[str] = None
    comment: Optional[str] = None
    requires_cold: Optional[bool] = False


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
    requires_cold: Optional[bool] = False
    shop_name: Optional[str] = None
    shop_lat: Optional[float] = None
    shop_lon: Optional[float] = None
    shop_kind: Optional[str] = None


class LotUpdate(BaseModel):
    description: Optional[str] = None
    quantity: Optional[int] = None
    expiry_date: Optional[date] = None
    address: Optional[str] = None
    category: Optional[str] = None
    comment: Optional[str] = None
    requires_cold: Optional[bool] = None


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


class SelfPickupConfirm(BaseModel):
    code: str  # the recipient's QR payload, e.g. "SF-42"


class ReceiptItemOut(BaseModel):
    raw_name: str
    name: str
    category: str
    quantity: Optional[float] = None
    unit: Optional[str] = None
    weight_kg: Optional[float] = None


class ReceiptLotDraft(BaseModel):
    """One lot draft grouped from receipt items (editable on the frontend)."""
    description: str
    quantity: int = Field(..., ge=1)
    category: str


class ReceiptOut(BaseModel):
    id: int
    status: str  # parsed | rejected | confirmed
    merchant: Optional[str] = None
    receipt_date: Optional[date] = None
    total: Optional[float] = None
    currency: Optional[str] = None
    items: list[ReceiptItemOut] = []
    fraud_score: Optional[float] = None
    fraud_reasons: Optional[str] = None
    fraud_flagged: bool = False
    suggested_lots: list[ReceiptLotDraft] = []
    lot_ids: list[int] = []
    created_at: Optional[datetime] = None


class ReceiptConfirm(BaseModel):
    """Final lot drafts as edited by the shop, plus shared lot fields."""
    lots: list[ReceiptLotDraft] = Field(..., min_length=1)
    expiry_date: Optional[date] = None
    address: Optional[str] = None
    time_slot: Optional[str] = None
