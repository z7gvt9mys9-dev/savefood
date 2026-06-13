import json
import os
from datetime import datetime, timezone
from typing import Optional, List, Dict, Any
from backend.database import get_db_cursor

try:
    from zoneinfo import ZoneInfo
except ImportError:  # py < 3.9 fallback
    ZoneInfo = None

LOCAL_TZ_NAME = os.getenv("LOCAL_TZ", "Asia/Almaty")


def _local_now() -> datetime:
    if ZoneInfo is not None:
        try:
            return datetime.now(ZoneInfo(LOCAL_TZ_NAME))
        except Exception:
            pass
    return datetime.now()


def is_available_now(availability, now: Optional[datetime] = None) -> bool:
    """Availability calendar (§54): True if `now` (local) falls in any weekly
    window. `availability` is the parsed list [{day,start,end}]. An empty/missing
    calendar means «always available» — the platform must not silently hide a
    volunteer who simply never filled it in."""
    if isinstance(availability, str):
        try:
            availability = json.loads(availability) if availability else None
        except (ValueError, TypeError):
            availability = None
    if not availability:
        return True
    now = now or _local_now()
    weekday = now.weekday()  # 0=Mon … 6=Sun
    minutes = now.hour * 60 + now.minute
    for w in availability:
        try:
            if int(w["day"]) != weekday:
                continue
            sh, sm = [int(x) for x in str(w["start"]).split(":")]
            eh, em = [int(x) for x in str(w["end"]).split(":")]
            start_m, end_m = sh * 60 + sm, eh * 60 + em
            if start_m <= end_m:
                if start_m <= minutes <= end_m:
                    return True
            else:  # overnight window
                if minutes >= start_m or minutes <= end_m:
                    return True
        except (KeyError, ValueError, TypeError):
            continue
    return False


def _parse_availability(row: Optional[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    """Decode the volunteers.availability JSON text column into a list so it
    matches the VolunteerOut schema. Bad/empty values become None."""
    if not row:
        return row
    raw = row.get("availability")
    if isinstance(raw, str):
        try:
            row["availability"] = json.loads(raw) if raw else None
        except (ValueError, TypeError):
            row["availability"] = None
    return row

def init_db():
    with get_db_cursor() as cur:
        cur.execute(
            """
            CREATE TABLE IF NOT EXISTS volunteers (
                id SERIAL PRIMARY KEY,
                name TEXT NOT NULL,
                contact TEXT,
                lat REAL,
                lon REAL,
                created_at TIMESTAMP WITH TIME ZONE NOT NULL
            )
            """
        )
        cur.execute("ALTER TABLE volunteers ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITH TIME ZONE")
        cur.execute("ALTER TABLE volunteers ADD COLUMN IF NOT EXISTS city TEXT")
        # Cold chain (§47): only a volunteer with a thermal bag may claim a
        # refrigerated (requires_cold) lot.
        cur.execute("ALTER TABLE volunteers ADD COLUMN IF NOT EXISTS has_thermal_bag BOOLEAN NOT NULL DEFAULT FALSE")
        # Availability calendar (§54): weekly time windows as JSON, e.g.
        # [{"day":1,"start":"18:00","end":"21:00"}] (day 0=Mon … 6=Sun).
        cur.execute("ALTER TABLE volunteers ADD COLUMN IF NOT EXISTS availability TEXT")

        # Volunteer KYC (§58): identity verification mirrors the needy flow.
        # `status` is 'pending' | 'approved' | 'rejected'; only 'approved'
        # volunteers may claim routes (gated in start_route). The column is added
        # WITHOUT a default so the one-time grandfather below can tell pre-existing
        # rows (NULL) from new registrations (which set 'pending' explicitly) —
        # locking out volunteers who were active before KYC existed would be worse
        # than the fraud it guards against.
        cur.execute("ALTER TABLE volunteers ADD COLUMN IF NOT EXISTS status TEXT")
        cur.execute("UPDATE volunteers SET status = 'approved' WHERE status IS NULL")
        cur.execute("ALTER TABLE volunteers ADD COLUMN IF NOT EXISTS document TEXT")
        # AI pre-check verdict for the admin moderation queue (same columns as needy).
        cur.execute("ALTER TABLE volunteers ADD COLUMN IF NOT EXISTS kyc_score REAL")
        cur.execute("ALTER TABLE volunteers ADD COLUMN IF NOT EXISTS kyc_verdict TEXT")
        cur.execute("ALTER TABLE volunteers ADD COLUMN IF NOT EXISTS kyc_notes TEXT")
        cur.execute("ALTER TABLE volunteers ADD COLUMN IF NOT EXISTS kyc_checked_at TIMESTAMP WITH TIME ZONE")
        # The admin moderation queue and the start_route gate both filter on
        # status; index it so the lookup stays cheap as the table grows.
        cur.execute("CREATE INDEX IF NOT EXISTS idx_volunteers_status ON volunteers (status)")

        # Corporate volunteering: a team is just a named group with a join
        # code; impact aggregates roll up via volunteers.team_id.
        cur.execute(
            """
            CREATE TABLE IF NOT EXISTS teams (
                id SERIAL PRIMARY KEY,
                name TEXT NOT NULL,
                join_code TEXT UNIQUE NOT NULL,
                created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
            )
            """
        )
        cur.execute("ALTER TABLE volunteers ADD COLUMN IF NOT EXISTS team_id INTEGER REFERENCES teams(id)")

        cur.execute(
            """
            CREATE TABLE IF NOT EXISTS volunteer_routes (
                id SERIAL PRIMARY KEY,
                volunteer_id INTEGER NOT NULL,
                points TEXT,
                status TEXT NOT NULL,
                lot_id INTEGER,
                started_at TIMESTAMP WITH TIME ZONE NOT NULL,
                finished_at TIMESTAMP WITH TIME ZONE
            )
            """
        )
        # Reassign-timeout is measured from the last completed point, not route
        # start — a long multi-stop route must not be killed mid-delivery.
        cur.execute("ALTER TABLE volunteer_routes ADD COLUMN IF NOT EXISTS last_activity_at TIMESTAMP WITH TIME ZONE")
        # Anti-fraud monitor (§27): volunteer→shop distance at claim time and
        # the timestamp of the "Всё в порядке?" ping (NULL = not pinged).
        cur.execute("ALTER TABLE volunteer_routes ADD COLUMN IF NOT EXISTS start_dist_m REAL")
        cur.execute("ALTER TABLE volunteer_routes ADD COLUMN IF NOT EXISTS antifraud_ping_at TIMESTAMP WITH TIME ZONE")

        cur.execute(
            """
            CREATE TABLE IF NOT EXISTS notifications (
                id SERIAL PRIMARY KEY,
                volunteer_id INTEGER,
                type TEXT,
                payload TEXT,
                created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                read INTEGER NOT NULL DEFAULT 0
            )
            """
        )
        # Shared `notifications` table may have been created first by the shop module
        # (which omits volunteer_id). Ensure the column exists.
        cur.execute("ALTER TABLE notifications ADD COLUMN IF NOT EXISTS volunteer_id INTEGER")

        # Hot-path indexes — active-route lookup, history, and notification fanout.
        cur.execute("CREATE INDEX IF NOT EXISTS idx_notifications_volunteer_id ON notifications (volunteer_id) WHERE volunteer_id IS NOT NULL")
        cur.execute("CREATE INDEX IF NOT EXISTS idx_routes_volunteer_status ON volunteer_routes (volunteer_id, status)")

    # One in-progress route per volunteer, enforced by the DB so two parallel
    # start_route calls (double-click on two lots) can't both pass the
    # SELECT-then-INSERT check. Separate cursor: if legacy data already violates
    # the constraint, log and keep booting (same pattern as tickets).
    try:
        with get_db_cursor() as cur:
            cur.execute(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS uq_routes_one_active_per_volunteer
                ON volunteer_routes (volunteer_id)
                WHERE status = 'in_progress'
                """
            )
    except Exception as e:
        print(f"Warning: could not create uq_routes_one_active_per_volunteer (duplicate active routes in DB?): {e}")

def create_notification(volunteer_id: int, notification_type: str, payload: str, created_at: Optional[datetime] = None):
    with get_db_cursor() as cur:
        if created_at is None:
            created_at = datetime.now(timezone.utc)
        cur.execute(
            "INSERT INTO notifications (volunteer_id, type, payload, created_at, read) VALUES (%s, %s, %s, %s, 0) RETURNING id",
            (volunteer_id, notification_type, payload, created_at),
        )
        nid = cur.fetchone()['id']
        return nid

def get_notifications(volunteer_id: int) -> List[Dict[str, Any]]:
    with get_db_cursor() as cur:
        cur.execute("SELECT * FROM notifications WHERE volunteer_id = %s ORDER BY created_at DESC", (volunteer_id,))
        rows = cur.fetchall()
        return [dict(r) for r in rows]

def mark_notification_read(notification_id: int):
    with get_db_cursor() as cur:
        cur.execute("UPDATE notifications SET read = 1 WHERE id = %s", (notification_id,))

def get_notification_by_id(notification_id: int) -> Optional[Dict[str, Any]]:
    with get_db_cursor() as cur:
        cur.execute("SELECT * FROM notifications WHERE id = %s", (notification_id,))
        row = cur.fetchone()
        return dict(row) if row else None

def needy_has_volunteer(needy_id: int, volunteer_id: int) -> bool:
    # Only an ACTIVE assignment grants access to the volunteer's live location;
    # once the ticket is fulfilled/released, tracking must stop (privacy).
    with get_db_cursor() as cur:
        cur.execute(
            "SELECT 1 FROM tickets WHERE needy_id = %s AND assigned_volunteer_id = %s AND status = 'assigned' LIMIT 1",
            (needy_id, volunteer_id),
        )
        return cur.fetchone() is not None

def create_volunteer(name: str, contact: Optional[str], lat: Optional[float], lon: Optional[float], city: Optional[str] = None) -> int:
    with get_db_cursor() as cur:
        # New volunteers start 'pending' (§58): they must upload an identity
        # document and clear KYC before they can claim routes. 'pending' is set
        # explicitly so the grandfather UPDATE (status IS NULL → 'approved' in
        # init_db) never re-approves a fresh account.
        cur.execute(
            "INSERT INTO volunteers (name, contact, lat, lon, city, status, created_at) VALUES (%s, %s, %s, %s, %s, 'pending', %s) RETURNING id",
            (name, contact, lat, lon, city, datetime.now(timezone.utc)),
        )
        vid = cur.fetchone()['id']
        return vid


def set_volunteer_status(vol_id: int, status: str) -> Optional[Dict[str, Any]]:
    """Admin/auto-KYC decision (§58). Returns the on-disk document path the caller
    must delete after a final decision (approve/reject) — the identity document is
    only needed during moderation, same lifecycle as the needy document (§5)."""
    with get_db_cursor() as cur:
        cur.execute("SELECT * FROM volunteers WHERE id = %s", (vol_id,))
        v = cur.fetchone()
        if not v:
            return None
        doc_path = v.get("document")
        # Drop the document reference after a final decision (the file itself is
        # removed by the route, which owns the upload dir) — done in the same
        # UPDATE as the status flip to avoid a second round-trip on the row.
        if status in ("approved", "rejected") and doc_path:
            cur.execute("UPDATE volunteers SET status = %s, document = NULL WHERE id = %s", (status, vol_id))
        else:
            cur.execute("UPDATE volunteers SET status = %s WHERE id = %s", (status, vol_id))
        cur.execute("SELECT * FROM volunteers WHERE id = %s", (vol_id,))
        return _parse_availability(dict(cur.fetchone()))


def set_volunteer_document(vol_id: int, document: Optional[str]):
    with get_db_cursor() as cur:
        cur.execute("UPDATE volunteers SET document = %s WHERE id = %s", (document, vol_id))


def save_volunteer_kyc(vol_id: int, score, verdict: str, notes: str):
    with get_db_cursor() as cur:
        cur.execute(
            "UPDATE volunteers SET kyc_score = %s, kyc_verdict = %s, kyc_notes = %s, kyc_checked_at = %s WHERE id = %s",
            (score, verdict, notes, datetime.now(timezone.utc), vol_id),
        )


def get_all_volunteers(status: Optional[str] = None) -> List[Dict[str, Any]]:
    with get_db_cursor() as cur:
        if status:
            cur.execute("SELECT * FROM volunteers WHERE status = %s ORDER BY created_at DESC", (status,))
        else:
            cur.execute("SELECT * FROM volunteers ORDER BY created_at DESC")
        return [_parse_availability(dict(r)) for r in cur.fetchall()]

def get_volunteer_by_id(vol_id: int) -> Optional[Dict[str, Any]]:
    with get_db_cursor() as cur:
        cur.execute("SELECT * FROM volunteers WHERE id = %s", (vol_id,))
        row = cur.fetchone()
        return _parse_availability(dict(row)) if row else None

def update_volunteer(vol_id: int, name: Optional[str], contact: Optional[str], lat: Optional[float], lon: Optional[float], city: Optional[str] = None, has_thermal_bag: Optional[bool] = None, availability_json: Optional[str] = None) -> Optional[Dict[str, Any]]:
    with get_db_cursor() as cur:
        cur.execute("SELECT * FROM volunteers WHERE id = %s", (vol_id,))
        v = cur.fetchone()
        if not v:
            return None
        new_name = name if name is not None else v['name']
        new_contact = contact if contact is not None else v['contact']
        new_lat = lat if lat is not None else v['lat']
        new_lon = lon if lon is not None else v['lon']
        new_city = city if city is not None else v.get('city')
        new_bag = has_thermal_bag if has_thermal_bag is not None else v.get('has_thermal_bag')
        new_avail = availability_json if availability_json is not None else v.get('availability')
        cur.execute("UPDATE volunteers SET name = %s, contact = %s, lat = %s, lon = %s, city = %s, has_thermal_bag = %s, availability = %s WHERE id = %s", (new_name, new_contact, new_lat, new_lon, new_city, new_bag, new_avail, vol_id))
        cur.execute("SELECT * FROM volunteers WHERE id = %s", (vol_id,))
        updated = cur.fetchone()
        return _parse_availability(dict(updated))

def create_route(volunteer_id: int, points_json: str, lot_id: Optional[int] = None) -> int:
    with get_db_cursor() as cur:
        cur.execute(
            "INSERT INTO volunteer_routes (volunteer_id, points, status, started_at, lot_id) VALUES (%s, %s, 'in_progress', %s, %s) RETURNING id",
            (volunteer_id, points_json, datetime.now(timezone.utc), lot_id),
        )
        rid = cur.fetchone()['id']
        return rid

def finish_route(route_id: int):
    with get_db_cursor() as cur:
        cur.execute("UPDATE volunteer_routes SET status = 'finished', finished_at = %s WHERE id = %s", (datetime.now(timezone.utc), route_id))

def get_routes_by_volunteer(volunteer_id: int, limit: int = 20, offset: int = 0) -> List[Dict[str, Any]]:
    with get_db_cursor() as cur:
        cur.execute(
            "SELECT * FROM volunteer_routes WHERE volunteer_id = %s ORDER BY started_at DESC LIMIT %s OFFSET %s",
            (volunteer_id, limit, offset)
        )
        rows = cur.fetchall()
        return [dict(r) for r in rows]

def get_route_by_id(route_id: int) -> Optional[Dict[str, Any]]:
    with get_db_cursor() as cur:
        cur.execute("SELECT * FROM volunteer_routes WHERE id = %s", (route_id,))
        row = cur.fetchone()
        return dict(row) if row else None

def get_active_route(volunteer_id: int) -> Optional[Dict[str, Any]]:
    with get_db_cursor() as cur:
        cur.execute("SELECT * FROM volunteer_routes WHERE volunteer_id = %s AND status = 'in_progress' ORDER BY started_at DESC LIMIT 1", (volunteer_id,))
        row = cur.fetchone()
        return dict(row) if row else None

def update_route_points(route_id: int, points_json: str):
    with get_db_cursor() as cur:
        cur.execute(
            "UPDATE volunteer_routes SET points = %s, last_activity_at = %s WHERE id = %s",
            (points_json, datetime.now(timezone.utc), route_id),
        )

def update_volunteer_location(vol_id: int, lat: float, lon: float):
    with get_db_cursor() as cur:
        cur.execute(
            "UPDATE volunteers SET lat = %s, lon = %s, updated_at = %s WHERE id = %s",
            (lat, lon, datetime.now(timezone.utc), vol_id),
        )

def get_volunteer_location(vol_id: int) -> Optional[Dict[str, Any]]:
    with get_db_cursor() as cur:
        cur.execute("SELECT lat, lon, updated_at FROM volunteers WHERE id = %s", (vol_id,))
        row = cur.fetchone()
        return dict(row) if row else None
