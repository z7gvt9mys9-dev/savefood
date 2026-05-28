from typing import Optional, List
from pydantic import BaseModel, Field
from datetime import datetime


class VolunteerCreate(BaseModel):
    name: str
    contact: Optional[str] = None
    lat: Optional[float] = None
    lon: Optional[float] = None
    city: Optional[str] = None
    username: Optional[str] = None
    password: Optional[str] = Field(None, min_length=8, max_length=128)


class VolunteerUpdate(BaseModel):
    name: Optional[str] = None
    contact: Optional[str] = None
    lat: Optional[float] = None
    lon: Optional[float] = None
    city: Optional[str] = None


class VolunteerOut(BaseModel):
    id: int
    name: str
    contact: Optional[str]
    lat: Optional[float]
    lon: Optional[float]
    city: Optional[str] = None
    created_at: datetime


class StartRouteRequest(BaseModel):
    lot_id: int
    max_stops: Optional[int] = 10


class FinishRouteRequest(BaseModel):
    volunteer_id: int


class RoutePoint(BaseModel):
    ticket_id: Optional[int]
    lat: Optional[float]
    lon: Optional[float]
    kind: str  # 'shop' or 'ticket'
    description: Optional[str]
    done: Optional[bool] = False


class RouteOut(BaseModel):
    id: int
    volunteer_id: int
    lot_id: Optional[int]
    points: List[RoutePoint]
    status: str
    started_at: datetime
    finished_at: Optional[datetime]


class CompletePointRequest(BaseModel):
    volunteer_id: int
    ticket_id: Optional[int] = None
    lat: Optional[float] = None
    lon: Optional[float] = None
    qr_code: Optional[str] = None


class LocationUpdate(BaseModel):
    lat: float
    lon: float
