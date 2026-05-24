import os
import sqlite3
from datetime import datetime, timezone
from typing import Optional, List, Dict, Any

DB_PATH = os.path.join(os.path.dirname(os.path.dirname(__file__)), "savefood.db")


def get_conn():
    conn = sqlite3.connect(DB_PATH, detect_types=sqlite3.PARSE_DECLTYPES)
    conn.row_factory = sqlite3.Row
    return conn


def init_db():
    conn = get_conn()
    cur = conn.cursor()
    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS volunteers (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            contact TEXT,
            lat REAL,
            lon REAL,
            created_at TIMESTAMP NOT NULL
        )
        """
    )

    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS volunteer_routes (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            volunteer_id INTEGER NOT NULL,
            points TEXT,
            status TEXT NOT NULL,
            lot_id INTEGER,
            started_at TIMESTAMP NOT NULL,
            finished_at TIMESTAMP
        )
        """
    )

    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS notifications (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            volunteer_id INTEGER NOT NULL,
            type TEXT,
            payload TEXT,
            created_at TIMESTAMP NOT NULL,
            read INTEGER NOT NULL DEFAULT 0
        )
        """
    )

    # ensure optional lot_id column exists for volunteer_routes (backward compatible)
    cur.execute("PRAGMA table_info(volunteer_routes)")
    cols = [r[1] for r in cur.fetchall()]
    if 'lot_id' not in cols:
        try:
            cur.execute("ALTER TABLE volunteer_routes ADD COLUMN lot_id INTEGER")
        except Exception:
            pass

    conn.commit()
    conn.close()


def create_notification(volunteer_id: int, type: str, payload: str, created_at: Optional[datetime] = None):
    conn = get_conn()
    cur = conn.cursor()
    if created_at is None:
        created_at = datetime.now(timezone.utc)
    cur.execute(
        "INSERT INTO notifications (volunteer_id, type, payload, created_at, read) VALUES (?, ?, ?, ?, 0)",
        (volunteer_id, type, payload, created_at),
    )
    nid = cur.lastrowid
    conn.commit()
    conn.close()
    return nid


def get_notifications(volunteer_id: int) -> List[Dict[str, Any]]:
    conn = get_conn()
    cur = conn.cursor()
    cur.execute("SELECT * FROM notifications WHERE volunteer_id = ? ORDER BY created_at DESC", (volunteer_id,))
    rows = cur.fetchall()
    conn.close()
    return [dict(r) for r in rows]


def mark_notification_read(notification_id: int):
    conn = get_conn()
    cur = conn.cursor()
    cur.execute("UPDATE notifications SET read = 1 WHERE id = ?", (notification_id,))
    conn.commit()
    conn.close()


def create_volunteer(name: str, contact: Optional[str], lat: Optional[float], lon: Optional[float]) -> int:
    conn = get_conn()
    cur = conn.cursor()
    cur.execute(
        "INSERT INTO volunteers (name, contact, lat, lon, created_at) VALUES (?, ?, ?, ?, ?)",
        (name, contact, lat, lon, datetime.now(timezone.utc)),
    )
    vid = cur.lastrowid
    conn.commit()
    conn.close()
    return vid


def get_volunteer_by_id(vol_id: int) -> Optional[Dict[str, Any]]:
    conn = get_conn()
    cur = conn.cursor()
    cur.execute("SELECT * FROM volunteers WHERE id = ?", (vol_id,))
    row = cur.fetchone()
    conn.close()
    return dict(row) if row else None


def create_route(volunteer_id: int, points_json: str, lot_id: Optional[int] = None) -> int:
    conn = get_conn()
    cur = conn.cursor()
    cur.execute(
        "INSERT INTO volunteer_routes (volunteer_id, points, status, started_at, lot_id) VALUES (?, ?, 'in_progress', ?, ?)",
        (volunteer_id, points_json, datetime.now(timezone.utc), lot_id),
    )
    rid = cur.lastrowid
    conn.commit()
    conn.close()
    return rid


def finish_route(route_id: int):
    conn = get_conn()
    cur = conn.cursor()
    cur.execute("UPDATE volunteer_routes SET status = 'finished', finished_at = ? WHERE id = ?", (datetime.now(timezone.utc), route_id))
    conn.commit()
    conn.close()


def get_routes_by_volunteer(volunteer_id: int) -> List[Dict[str, Any]]:
    conn = get_conn()
    cur = conn.cursor()
    cur.execute("SELECT * FROM volunteer_routes WHERE volunteer_id = ? ORDER BY started_at DESC", (volunteer_id,))
    rows = cur.fetchall()
    conn.close()
    return [dict(r) for r in rows]


def get_route_by_id(route_id: int) -> Optional[Dict[str, Any]]:
    conn = get_conn()
    cur = conn.cursor()
    cur.execute("SELECT * FROM volunteer_routes WHERE id = ?", (route_id,))
    row = cur.fetchone()
    conn.close()
    return dict(row) if row else None


def update_route_points(route_id: int, points_json: str):
    conn = get_conn()
    cur = conn.cursor()
    cur.execute("UPDATE volunteer_routes SET points = ? WHERE id = ?", (points_json, route_id))
    conn.commit()
    conn.close()