import os
import sqlite3
from datetime import datetime
from typing import Optional, List, Dict, Any

DB_PATH = os.path.join(os.getcwd(), "savefood.db")


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
            started_at TIMESTAMP NOT NULL,
            finished_at TIMESTAMP
        )
        """
    )

    conn.commit()
    conn.close()


def create_volunteer(name: str, contact: Optional[str], lat: Optional[float], lon: Optional[float]) -> int:
    conn = get_conn()
    cur = conn.cursor()
    cur.execute(
        "INSERT INTO volunteers (name, contact, lat, lon, created_at) VALUES (?, ?, ?, ?, ?)",
        (name, contact, lat, lon, datetime.utcnow()),
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


def create_route(volunteer_id: int, points_json: str) -> int:
    conn = get_conn()
    cur = conn.cursor()
    cur.execute(
        "INSERT INTO volunteer_routes (volunteer_id, points, status, started_at) VALUES (?, ?, 'in_progress', ?)",
        (volunteer_id, points_json, datetime.utcnow()),
    )
    rid = cur.lastrowid
    conn.commit()
    conn.close()
    return rid


def finish_route(route_id: int):
    conn = get_conn()
    cur = conn.cursor()
    cur.execute("UPDATE volunteer_routes SET status = 'finished', finished_at = ? WHERE id = ?", (datetime.utcnow(), route_id))
    conn.commit()
    conn.close()


def get_routes_by_volunteer(volunteer_id: int) -> List[Dict[str, Any]]:
    conn = get_conn()
    cur = conn.cursor()
    cur.execute("SELECT * FROM volunteer_routes WHERE volunteer_id = ? ORDER BY started_at DESC", (volunteer_id,))
    rows = cur.fetchall()
    conn.close()
    return [dict(r) for r in rows]
*** End Patch