import os
from datetime import datetime, timezone
from typing import Optional, List, Dict, Any
from backend.database import get_db_cursor

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
        cur.execute(
            "INSERT INTO volunteers (name, contact, lat, lon, city, created_at) VALUES (%s, %s, %s, %s, %s, %s) RETURNING id",
            (name, contact, lat, lon, city, datetime.now(timezone.utc)),
        )
        vid = cur.fetchone()['id']
        return vid

def get_volunteer_by_id(vol_id: int) -> Optional[Dict[str, Any]]:
    with get_db_cursor() as cur:
        cur.execute("SELECT * FROM volunteers WHERE id = %s", (vol_id,))
        row = cur.fetchone()
        return dict(row) if row else None

def update_volunteer(vol_id: int, name: Optional[str], contact: Optional[str], lat: Optional[float], lon: Optional[float], city: Optional[str] = None) -> Optional[Dict[str, Any]]:
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
        cur.execute("UPDATE volunteers SET name = %s, contact = %s, lat = %s, lon = %s, city = %s WHERE id = %s", (new_name, new_contact, new_lat, new_lon, new_city, vol_id))
        cur.execute("SELECT * FROM volunteers WHERE id = %s", (vol_id,))
        updated = cur.fetchone()
        return dict(updated)

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
