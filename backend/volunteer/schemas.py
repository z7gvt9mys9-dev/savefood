from typing import Optional, List
from pydantic import BaseModel
from datetime import datetime


class VolunteerCreate(BaseModel):
    name: str
    contact: Optional[str] = None
    lat: Optional[float] = None
    lon: Optional[float] = None


class VolunteerOut(BaseModel):
    id: int
    name: str
    contact: Optional[str]
    lat: Optional[float]
    lon: Optional[float]
    created_at: datetime


class StartRouteRequest(BaseModel):
    lot_id: int
    max_stops: Optional[int] = 10


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
    points: List[RoutePoint]
    status: str
    started_at: datetime
    finished_at: Optional[datetime]


class CompletePointRequest(BaseModel):
    volunteer_id: int
    ticket_id: Optional[int] = None