import os
import sqlite3
from datetime import datetime, timedelta, timezone
from typing import Optional, List, Dict, Any

from backend.utils import ensure_aware_utc

DB_PATH = os.path.join(os.path.dirname(os.path.dirname(__file__)), "savefood.db")
PROFILE_COLUMNS = "needy_id, address, family_size, preferences, urgency, CAST(last_received_at AS TEXT) AS last_received_at, document"


def get_conn():
    conn = sqlite3.connect(DB_PATH, detect_types=sqlite3.PARSE_DECLTYPES)
    conn.row_factory = sqlite3.Row
    return conn


def init_db():
    conn = get_conn()
    cur = conn.cursor()
    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS needy (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            contact TEXT,
            status TEXT NOT NULL DEFAULT 'pending',
            document TEXT,
            created_at TIMESTAMP NOT NULL
        )
        """
    )

    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS tickets (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            needy_id INTEGER NOT NULL,
            items TEXT,
            available_time TEXT,
            address TEXT,
            lat REAL,
            lon REAL,
            status TEXT NOT NULL DEFAULT 'open',
            created_at TIMESTAMP NOT NULL,
            assigned_volunteer TEXT,
            fulfilled_at TIMESTAMP,
            FOREIGN KEY(needy_id) REFERENCES needy(id)
        )
        """
    )

    # ensure optional available_time column exists for tickets (backward compatible)
    cur.execute("PRAGMA table_info(tickets)")
    cols = [r[1] for r in cur.fetchall()]
    if 'available_time' not in cols:
        try:
            cur.execute("ALTER TABLE tickets ADD COLUMN available_time TEXT")
        except Exception:
            pass

    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS notifications (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            needy_id INTEGER,
            type TEXT,
            payload TEXT,
            created_at TIMESTAMP NOT NULL,
            read INTEGER NOT NULL DEFAULT 0
        )
        """
    )

    # Profile table for needy
    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS needy_profile (
            needy_id INTEGER PRIMARY KEY,
            address TEXT,
            family_size INTEGER,
            preferences TEXT,
            urgency TEXT,
            last_received_at TIMESTAMP,
            document TEXT,
            FOREIGN KEY(needy_id) REFERENCES needy(id)
        )
        """
    )

    conn.commit()
    conn.close()


def create_needy(name: str, contact: Optional[str]) -> int:
    conn = get_conn()
    cur = conn.cursor()
    cur.execute(
        "INSERT INTO needy (name, contact, created_at) VALUES (?, ?, ?)",
        (name, contact, datetime.now(timezone.utc)),
    )
    nid = cur.lastrowid
    conn.commit()
    conn.close()
    return nid


def get_needy_by_id(needy_id: int) -> Optional[Dict[str, Any]]:
    conn = get_conn()
    cur = conn.cursor()
    cur.execute("SELECT * FROM needy WHERE id = ?", (needy_id,))
    row = cur.fetchone()
    conn.close()
    return dict(row) if row else None


def create_ticket(needy_id: int, items: Optional[str], address: Optional[str], lat: Optional[float], lon: Optional[float], available_time: Optional[str] = None) -> Optional[int]:
    conn = get_conn()
    cur = conn.cursor()
    # enforce once-per-week: check profile.last_received_at
    cur.execute("SELECT CAST(last_received_at AS TEXT) FROM needy_profile WHERE needy_id = ?", (needy_id,))
    pr = cur.fetchone()
    if pr and pr[0]:
        try:
            last_dt = ensure_aware_utc(pr[0])
            if datetime.now(timezone.utc) - last_dt < timedelta(days=7):
                conn.close()
                return None
        except Exception:
            pass
    cur.execute(
        "INSERT INTO tickets (needy_id, items, address, lat, lon, available_time, status, created_at) VALUES (?, ?, ?, ?, ?, ?, 'open', ?)",
        (needy_id, items, address, lat, lon, available_time, datetime.now(timezone.utc)),
    )
    tid = cur.lastrowid
    conn.commit()
    conn.close()
    return tid


def get_tickets_by_needy_id(needy_id: int) -> List[Dict[str, Any]]:
    conn = get_conn()
    cur = conn.cursor()
    cur.execute("SELECT * FROM tickets WHERE needy_id = ? ORDER BY created_at DESC", (needy_id,))
    rows = cur.fetchall()
    conn.close()
    return [dict(r) for r in rows]


def assign_ticket(ticket_id: int, volunteer_name: str) -> Optional[Dict[str, Any]]:
    conn = get_conn()
    cur = conn.cursor()
    cur.execute("SELECT * FROM tickets WHERE id = ?", (ticket_id,))
    ticket = cur.fetchone()
    if not ticket:
        conn.close()
        return None
    if ticket["status"] != 'open':
        conn.close()
        return None

    cur.execute(
        "UPDATE tickets SET status = 'assigned', assigned_volunteer = ? WHERE id = ?",
        (volunteer_name, ticket_id),
    )
    # create notification for needy
    cur.execute(
        "INSERT INTO notifications (needy_id, type, payload, created_at, read) VALUES (?, ?, ?, ?, 0)",
        (ticket["needy_id"], 'volunteer_assigned', f'Volunteer {volunteer_name} assigned to ticket {ticket_id}', datetime.now(timezone.utc)),
    )

    conn.commit()
    cur.execute("SELECT * FROM tickets WHERE id = ?", (ticket_id,))
    updated = cur.fetchone()
    conn.close()
    return dict(updated)


def get_history(needy_id: int) -> List[Dict[str, Any]]:
    conn = get_conn()
    cur = conn.cursor()
    cur.execute("SELECT * FROM tickets WHERE needy_id = ? AND status IN ('assigned','fulfilled') ORDER BY created_at DESC", (needy_id,))
    rows = cur.fetchall()
    conn.close()
    return [dict(r) for r in rows]


def get_notifications(needy_id: int) -> List[Dict[str, Any]]:
    conn = get_conn()
    cur = conn.cursor()
    cur.execute("SELECT * FROM notifications WHERE needy_id = ? ORDER BY created_at DESC", (needy_id,))
    rows = cur.fetchall()
    conn.close()
    return [dict(r) for r in rows]


def mark_notification_read(notification_id: int):
    conn = get_conn()
    cur = conn.cursor()
    cur.execute("UPDATE notifications SET read = 1 WHERE id = ?", (notification_id,))
    conn.commit()
    conn.close()


def update_needy(needy_id: int, name: str, contact: Optional[str]) -> Optional[Dict[str, Any]]:
    conn = get_conn()
    cur = conn.cursor()
    cur.execute("SELECT * FROM needy WHERE id = ?", (needy_id,))
    row = cur.fetchone()
    if not row:
        conn.close()
        return None
    cur.execute("UPDATE needy SET name = ?, contact = ? WHERE id = ?", (name, contact, needy_id))
    conn.commit()
    cur.execute("SELECT * FROM needy WHERE id = ?", (needy_id,))
    updated = cur.fetchone()
    conn.close()
    return dict(updated)


def create_or_update_profile(needy_id: int, address: Optional[str], family_size: Optional[int], preferences: Optional[str], urgency: Optional[str], document: Optional[str] = None) -> Optional[Dict[str, Any]]:
    conn = get_conn()
    cur = conn.cursor()
    cur.execute("SELECT * FROM needy WHERE id = ?", (needy_id,))
    n = cur.fetchone()
    if not n:
        conn.close()
        return None

    cur.execute(f"SELECT {PROFILE_COLUMNS} FROM needy_profile WHERE needy_id = ?", (needy_id,))
    p = cur.fetchone()
    if p:
        new_address = address if address is not None else p['address']
        new_family_size = family_size if family_size is not None else p['family_size']
        new_preferences = preferences if preferences is not None else p['preferences']
        new_urgency = urgency if urgency is not None else p['urgency']
        new_document = document if document is not None else p['document']
        cur.execute(
            "UPDATE needy_profile SET address = ?, family_size = ?, preferences = ?, urgency = ?, document = ? WHERE needy_id = ?",
            (new_address, new_family_size, new_preferences, new_urgency, new_document, needy_id),
        )
    else:
        cur.execute(
            "INSERT INTO needy_profile (needy_id, address, family_size, preferences, urgency, last_received_at, document) VALUES (?, ?, ?, ?, ?, ?, ?)",
            (needy_id, address, family_size, preferences, urgency, None, document),
        )
    conn.commit()
    cur.execute(f"SELECT {PROFILE_COLUMNS} FROM needy_profile WHERE needy_id = ?", (needy_id,))
    updated = cur.fetchone()
    conn.close()
    return dict(updated)


def get_profile(needy_id: int) -> Optional[Dict[str, Any]]:
    conn = get_conn()
    cur = conn.cursor()
    cur.execute(f"SELECT {PROFILE_COLUMNS} FROM needy_profile WHERE needy_id = ?", (needy_id,))
    row = cur.fetchone()
    conn.close()
    return dict(row) if row else None


def set_needy_status(needy_id: int, status: str) -> Optional[Dict[str, Any]]:
    conn = get_conn()
    cur = conn.cursor()
    cur.execute("SELECT * FROM needy WHERE id = ?", (needy_id,))
    n = cur.fetchone()
    if not n:
        conn.close()
        return None
    cur.execute("UPDATE needy SET status = ? WHERE id = ?", (status, needy_id))
    conn.commit()
    cur.execute("SELECT * FROM needy WHERE id = ?", (needy_id,))
    updated = cur.fetchone()
    conn.close()
    return dict(updated)


def set_profile_last_received(needy_id: int, ts):
    conn = get_conn()
    cur = conn.cursor()
    cur.execute("UPDATE needy_profile SET last_received_at = ? WHERE needy_id = ?", (ts, needy_id))
    if cur.rowcount == 0:
        cur.execute(
            "INSERT INTO needy_profile (needy_id, last_received_at) VALUES (?, ?)",
            (needy_id, ts),
        )
    conn.commit()
    conn.close()
