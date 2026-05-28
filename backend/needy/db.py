import os
from datetime import datetime, timedelta, timezone
from typing import Optional, List, Dict, Any
from backend.database import get_db_cursor
from backend.utils import ensure_aware_utc

def init_db():
    with get_db_cursor() as cur:
        cur.execute(
            """
            CREATE TABLE IF NOT EXISTS needy (
                id SERIAL PRIMARY KEY,
                name TEXT NOT NULL,
                contact TEXT,
                status TEXT NOT NULL DEFAULT 'pending',
                document TEXT,
                created_at TIMESTAMP WITH TIME ZONE NOT NULL
            )
            """
        )

        cur.execute(
            """
            CREATE TABLE IF NOT EXISTS tickets (
                id SERIAL PRIMARY KEY,
                needy_id INTEGER NOT NULL,
                items TEXT,
                available_time TEXT,
                address TEXT,
                lat REAL,
                lon REAL,
                lot_id INTEGER,
                status TEXT NOT NULL DEFAULT 'open',
                created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                assigned_volunteer TEXT,
                fulfilled_at TIMESTAMP WITH TIME ZONE,
                FOREIGN KEY(needy_id) REFERENCES needy(id)
            )
            """
        )
        cur.execute("ALTER TABLE tickets ADD COLUMN IF NOT EXISTS lot_id INTEGER")
        cur.execute("ALTER TABLE tickets ADD COLUMN IF NOT EXISTS apartment TEXT")
        cur.execute("ALTER TABLE tickets ADD COLUMN IF NOT EXISTS floor_num TEXT")
        cur.execute("ALTER TABLE tickets ADD COLUMN IF NOT EXISTS entrance TEXT")
        cur.execute("ALTER TABLE tickets ADD COLUMN IF NOT EXISTS self_pickup BOOLEAN DEFAULT FALSE")

        cur.execute(
            """
            CREATE TABLE IF NOT EXISTS notifications (
                id SERIAL PRIMARY KEY,
                needy_id INTEGER,
                type TEXT,
                payload TEXT,
                created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                read INTEGER NOT NULL DEFAULT 0
            )
            """
        )
        # Shared `notifications` table may have been created first by the shop module
        # (which omits needy_id). Ensure the column exists.
        cur.execute("ALTER TABLE notifications ADD COLUMN IF NOT EXISTS needy_id INTEGER")

        # Hot-path indexes — WS poll (notifications.needy_id), needy history view,
        # and the open-ticket lookup used by the routing algorithm.
        cur.execute("CREATE INDEX IF NOT EXISTS idx_notifications_needy_id ON notifications (needy_id) WHERE needy_id IS NOT NULL")
        cur.execute("CREATE INDEX IF NOT EXISTS idx_tickets_needy_status ON tickets (needy_id, status)")
        cur.execute("CREATE INDEX IF NOT EXISTS idx_tickets_status ON tickets (status)")

        cur.execute(
            """
            CREATE TABLE IF NOT EXISTS needy_profile (
                needy_id INTEGER PRIMARY KEY,
                address TEXT,
                family_size INTEGER,
                preferences TEXT,
                urgency TEXT,
                available_time TEXT,
                last_received_at TIMESTAMP WITH TIME ZONE,
                document TEXT,
                FOREIGN KEY(needy_id) REFERENCES needy(id)
            )
            """
        )
        cur.execute("ALTER TABLE needy_profile ADD COLUMN IF NOT EXISTS available_time TEXT")
        cur.execute("ALTER TABLE needy_profile ADD COLUMN IF NOT EXISTS apartment TEXT")
        cur.execute("ALTER TABLE needy_profile ADD COLUMN IF NOT EXISTS floor_num TEXT")
        cur.execute("ALTER TABLE needy_profile ADD COLUMN IF NOT EXISTS entrance TEXT")
        cur.execute("ALTER TABLE needy_profile ADD COLUMN IF NOT EXISTS city TEXT")
        cur.execute("ALTER TABLE needy_profile ADD COLUMN IF NOT EXISTS lat REAL")
        cur.execute("ALTER TABLE needy_profile ADD COLUMN IF NOT EXISTS lon REAL")

def create_needy(name: str, contact: Optional[str]) -> int:
    with get_db_cursor() as cur:
        cur.execute(
            "INSERT INTO needy (name, contact, created_at) VALUES (%s, %s, %s) RETURNING id",
            (name, contact, datetime.now(timezone.utc)),
        )
        nid = cur.fetchone()['id']
        return nid

def get_needy_by_id(needy_id: int) -> Optional[Dict[str, Any]]:
    with get_db_cursor() as cur:
        cur.execute("SELECT * FROM needy WHERE id = %s", (needy_id,))
        row = cur.fetchone()
        return dict(row) if row else None

class TicketCreateError(Exception):
    def __init__(self, reason: str):
        super().__init__(reason)
        self.reason = reason


def create_ticket(needy_id: int, items: Optional[str], address: Optional[str], lat: Optional[float], lon: Optional[float], available_time: Optional[str] = None, lot_id: Optional[int] = None, apartment: Optional[str] = None, floor_num: Optional[str] = None, entrance: Optional[str] = None, self_pickup: bool = False) -> Optional[int]:
    with get_db_cursor() as cur:
        # §3.2: one assistance per 7 days (counted from the previous fulfilment).
        cur.execute("SELECT last_received_at FROM needy_profile WHERE needy_id = %s", (needy_id,))
        pr = cur.fetchone()
        if pr and pr['last_received_at']:
            last_dt = pr['last_received_at']
            if datetime.now(timezone.utc) - last_dt < timedelta(days=7):
                raise TicketCreateError("weekly_limit")

        # Block parallel tickets: only one open or in-progress ticket at a time
        # per needy, otherwise the weekly limit can be bypassed by spamming.
        cur.execute(
            "SELECT 1 FROM tickets WHERE needy_id = %s AND status IN ('open','assigned') LIMIT 1",
            (needy_id,),
        )
        if cur.fetchone():
            raise TicketCreateError("active_ticket_exists")

        cur.execute(
            "INSERT INTO tickets (needy_id, items, address, lat, lon, available_time, lot_id, apartment, floor_num, entrance, self_pickup, status, created_at) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, 'open', %s) RETURNING id",
            (needy_id, items, address, lat, lon, available_time, lot_id, apartment, floor_num, entrance, self_pickup, datetime.now(timezone.utc)),
        )
        tid = cur.fetchone()['id']
        return tid

def get_tickets_by_needy_id(needy_id: int) -> List[Dict[str, Any]]:
    with get_db_cursor() as cur:
        cur.execute("SELECT * FROM tickets WHERE needy_id = %s ORDER BY created_at DESC", (needy_id,))
        rows = cur.fetchall()
        return [dict(r) for r in rows]

def get_history(needy_id: int, limit: int = 20, offset: int = 0) -> List[Dict[str, Any]]:
    with get_db_cursor() as cur:
        cur.execute(
            "SELECT * FROM tickets WHERE needy_id = %s AND status IN ('assigned','fulfilled') ORDER BY created_at DESC LIMIT %s OFFSET %s",
            (needy_id, limit, offset)
        )
        rows = cur.fetchall()
        return [dict(r) for r in rows]

def get_notifications(needy_id: int) -> List[Dict[str, Any]]:
    with get_db_cursor() as cur:
        cur.execute("SELECT * FROM notifications WHERE needy_id = %s ORDER BY created_at DESC", (needy_id,))
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

def update_needy(needy_id: int, name: str, contact: Optional[str]) -> Optional[Dict[str, Any]]:
    with get_db_cursor() as cur:
        cur.execute("SELECT * FROM needy WHERE id = %s", (needy_id,))
        row = cur.fetchone()
        if not row:
            return None
        cur.execute("UPDATE needy SET name = %s, contact = %s WHERE id = %s", (name, contact, needy_id))
        cur.execute("SELECT * FROM needy WHERE id = %s", (needy_id,))
        updated = cur.fetchone()
        return dict(updated)

def create_or_update_profile(needy_id: int, address: Optional[str], family_size: Optional[int], preferences: Optional[str], urgency: Optional[str], document: Optional[str] = None, available_time: Optional[str] = None, apartment: Optional[str] = None, floor_num: Optional[str] = None, entrance: Optional[str] = None, city: Optional[str] = None, lat: Optional[float] = None, lon: Optional[float] = None) -> Optional[Dict[str, Any]]:
    with get_db_cursor() as cur:
        cur.execute("SELECT * FROM needy WHERE id = %s", (needy_id,))
        if not cur.fetchone():
            return None

        cur.execute("SELECT * FROM needy_profile WHERE needy_id = %s", (needy_id,))
        p = cur.fetchone()
        if p:
            new_address = address if address is not None else p['address']
            new_family_size = family_size if family_size is not None else p['family_size']
            new_preferences = preferences if preferences is not None else p['preferences']
            new_urgency = urgency if urgency is not None else p['urgency']
            new_document = document if document is not None else p['document']
            new_available_time = available_time if available_time is not None else p.get('available_time')
            new_apartment = apartment if apartment is not None else p.get('apartment')
            new_floor_num = floor_num if floor_num is not None else p.get('floor_num')
            new_entrance = entrance if entrance is not None else p.get('entrance')
            new_city = city if city is not None else p.get('city')
            new_lat = lat if lat is not None else p.get('lat')
            new_lon = lon if lon is not None else p.get('lon')
            cur.execute(
                "UPDATE needy_profile SET address = %s, family_size = %s, preferences = %s, urgency = %s, document = %s, available_time = %s, apartment = %s, floor_num = %s, entrance = %s, city = %s, lat = %s, lon = %s WHERE needy_id = %s",
                (new_address, new_family_size, new_preferences, new_urgency, new_document, new_available_time, new_apartment, new_floor_num, new_entrance, new_city, new_lat, new_lon, needy_id),
            )
        else:
            cur.execute(
                "INSERT INTO needy_profile (needy_id, address, family_size, preferences, urgency, available_time, last_received_at, document, apartment, floor_num, entrance, city, lat, lon) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)",
                (needy_id, address, family_size, preferences, urgency, available_time, None, document, apartment, floor_num, entrance, city, lat, lon),
            )
        cur.execute("SELECT * FROM needy_profile WHERE needy_id = %s", (needy_id,))
        updated = cur.fetchone()
        return dict(updated)

def get_profile(needy_id: int) -> Optional[Dict[str, Any]]:
    with get_db_cursor() as cur:
        cur.execute("SELECT * FROM needy_profile WHERE needy_id = %s", (needy_id,))
        row = cur.fetchone()
        return dict(row) if row else None

def set_needy_status(needy_id: int, status: str) -> Optional[Dict[str, Any]]:
    with get_db_cursor() as cur:
        cur.execute("SELECT * FROM needy WHERE id = %s", (needy_id,))
        n = cur.fetchone()
        if not n:
            return None
        
        if status == 'approved':
            cur.execute("SELECT document FROM needy_profile WHERE needy_id = %s", (needy_id,))
            prof = cur.fetchone()
            if prof and prof['document']:
                doc_path = prof['document']
                filename = os.path.basename(doc_path)
                local_path = os.path.join(os.path.dirname(__file__), "uploads", filename)
                if os.path.exists(local_path):
                    try:
                        os.remove(local_path)
                    except Exception as e:
                        print(f"Error deleting sensitive document: {e}")
                cur.execute("UPDATE needy_profile SET document = NULL WHERE needy_id = %s", (needy_id,))

        cur.execute("UPDATE needy SET status = %s WHERE id = %s", (status, needy_id))
        cur.execute("SELECT * FROM needy WHERE id = %s", (needy_id,))
        updated = cur.fetchone()
        return dict(updated)

def set_profile_last_received(needy_id: int, ts):
    with get_db_cursor() as cur:
        cur.execute("UPDATE needy_profile SET last_received_at = %s WHERE needy_id = %s", (ts, needy_id))
        if cur.rowcount == 0:
            cur.execute(
                "INSERT INTO needy_profile (needy_id, last_received_at) VALUES (%s, %s)",
                (needy_id, ts),
            )


def get_all_needy(status: Optional[str] = None) -> List[Dict[str, Any]]:
    with get_db_cursor() as cur:
        if status:
            cur.execute(
                "SELECT n.*, np.document FROM needy n LEFT JOIN needy_profile np ON n.id = np.needy_id WHERE n.status = %s ORDER BY n.created_at DESC",
                (status,)
            )
        else:
            cur.execute(
                "SELECT n.*, np.document FROM needy n LEFT JOIN needy_profile np ON n.id = np.needy_id ORDER BY n.created_at DESC"
            )
        rows = cur.fetchall()
        return [dict(r) for r in rows]
