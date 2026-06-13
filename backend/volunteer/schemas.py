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


class AvailabilityWindow(BaseModel):
    day: int = Field(..., ge=0, le=6)   # 0=Mon … 6=Sun
    start: str = Field(..., pattern=r"^([01]\d|2[0-3]):[0-5]\d$")
    end: str = Field(..., pattern=r"^([01]\d|2[0-3]):[0-5]\d$")


class VolunteerUpdate(BaseModel):
    name: Optional[str] = None
    contact: Optional[str] = None
    lat: Optional[float] = None
    lon: Optional[float] = None
    city: Optional[str] = None
    has_thermal_bag: Optional[bool] = None
    availability: Optional[List[AvailabilityWindow]] = None


class VolunteerOut(BaseModel):
    id: int
    name: str
    contact: Optional[str]
    lat: Optional[float]
    lon: Optional[float]
    city: Optional[str] = None
    has_thermal_bag: Optional[bool] = False
    availability: Optional[List[AvailabilityWindow]] = None
    # KYC (§58): verification state shown in the dashboard so a pending volunteer
    # knows why routes are blocked; kyc_* are surfaced in the admin queue.
    status: Optional[str] = "approved"
    kyc_score: Optional[float] = None
    kyc_verdict: Optional[str] = None
    kyc_notes: Optional[str] = None
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


class TeamCreate(BaseModel):
    name: str


class TeamJoin(BaseModel):
    code: str
