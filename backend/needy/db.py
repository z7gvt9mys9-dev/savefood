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
        CREATE TABLE IF NOT EXISTS needy (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            contact TEXT,
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

    conn.commit()
    conn.close()


def create_needy(name: str, contact: Optional[str]) -> int:
    conn = get_conn()
    cur = conn.cursor()
    cur.execute(
        "INSERT INTO needy (name, contact, created_at) VALUES (?, ?, ?)",
        (name, contact, datetime.utcnow()),
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


def create_ticket(needy_id: int, items: Optional[str], address: Optional[str], lat: Optional[float], lon: Optional[float]) -> int:
    conn = get_conn()
    cur = conn.cursor()
    cur.execute(
        "INSERT INTO tickets (needy_id, items, address, lat, lon, status, created_at) VALUES (?, ?, ?, ?, ?, 'open', ?)",
        (needy_id, items, address, lat, lon, datetime.utcnow()),
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
        (ticket["needy_id"], 'volunteer_assigned', f'Volunteer {volunteer_name} assigned to ticket {ticket_id}', datetime.utcnow()),
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