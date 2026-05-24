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
        CREATE TABLE IF NOT EXISTS shops (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            contact TEXT,
            created_at TIMESTAMP NOT NULL
        )
        """
    )

    # ensure optional lat/lon columns exist for routing
    cur.execute("PRAGMA table_info(shops)")
    cols = [r[1] for r in cur.fetchall()]
    if 'lat' not in cols:
        try:
            cur.execute("ALTER TABLE shops ADD COLUMN lat REAL")
        except Exception:
            pass
    if 'lon' not in cols:
        try:
            cur.execute("ALTER TABLE shops ADD COLUMN lon REAL")
        except Exception:
            pass

    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS lots (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            shop_id INTEGER NOT NULL,
            description TEXT,
            quantity INTEGER,
            expiry_date DATE,
            photo TEXT,
            address TEXT,
            status TEXT NOT NULL DEFAULT 'active',
            created_at TIMESTAMP NOT NULL,
            taken_at TIMESTAMP,
            taken_by TEXT,
            FOREIGN KEY(shop_id) REFERENCES shops(id)
        )
        """
    )

    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS notifications (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            shop_id INTEGER NOT NULL,
            lot_id INTEGER,
            type TEXT,
            payload TEXT,
            created_at TIMESTAMP NOT NULL,
            read INTEGER NOT NULL DEFAULT 0
        )
        """
    )

    conn.commit()
    conn.close()


def create_shop(name: str, contact: Optional[str], lat: Optional[float] = None, lon: Optional[float] = None) -> int:
    conn = get_conn()
    cur = conn.cursor()
    cur.execute(
        "INSERT INTO shops (name, contact, lat, lon, created_at) VALUES (?, ?, ?, ?, ?)",
        (name, contact, lat, lon, datetime.now(timezone.utc)),
    )
    shop_id = cur.lastrowid
    conn.commit()
    conn.close()
    return shop_id


def create_lot(shop_id: int, description: str, quantity: int, expiry_date: str, photo: Optional[str], address: Optional[str]) -> int:
    conn = get_conn()
    cur = conn.cursor()
    cur.execute(
        "INSERT INTO lots (shop_id, description, quantity, expiry_date, photo, address, status, created_at) VALUES (?, ?, ?, ?, ?, ?, 'active', ?)",
        (shop_id, description, quantity, expiry_date, photo, address, datetime.now(timezone.utc)),
    )
    lot_id = cur.lastrowid
    conn.commit()
    conn.close()
    return lot_id


def get_active_lots(shop_id: int) -> List[Dict[str, Any]]:
    conn = get_conn()
    cur = conn.cursor()
    cur.execute(
        "SELECT * FROM lots WHERE shop_id = ? AND status = 'active' AND (expiry_date IS NULL OR date(expiry_date) > date('now', '+1 day')) ORDER BY created_at DESC",
        (shop_id,)
    )
    rows = cur.fetchall()
    conn.close()
    return [dict(r) for r in rows]


def take_lot(lot_id: int, volunteer_name: str) -> Optional[Dict[str, Any]]:
    conn = get_conn()
    cur = conn.cursor()
    cur.execute("SELECT * FROM lots WHERE id = ?", (lot_id,))
    lot = cur.fetchone()
    if not lot:
        conn.close()
        return None
    if lot["status"] != 'active':
        conn.close()
        return None

    taken_at = datetime.now(timezone.utc)
    cur.execute(
        "UPDATE lots SET status = 'taken', taken_at = ?, taken_by = ? WHERE id = ?",
        (taken_at, volunteer_name, lot_id),
    )

    # create notification for shop
    cur.execute(
        "INSERT INTO notifications (shop_id, lot_id, type, payload, created_at, read) VALUES (?, ?, ?, ?, ?, 0)",
        (lot["shop_id"], lot_id, 'lot_taken', f'Volunteer {volunteer_name} took lot {lot_id}', taken_at),
    )

    conn.commit()
    cur.execute("SELECT * FROM lots WHERE id = ?", (lot_id,))
    updated = cur.fetchone()
    conn.close()
    return dict(updated)


def get_history(shop_id: int) -> List[Dict[str, Any]]:
    conn = get_conn()
    cur = conn.cursor()
    # include lots that were taken, or active lots that have already expired
    cur.execute(
        "SELECT * FROM lots WHERE shop_id = ? AND (status = 'taken' OR (status = 'active' AND expiry_date IS NOT NULL AND date(expiry_date) < date('now'))) ORDER BY taken_at DESC",
        (shop_id,)
    )
    rows = cur.fetchall()
    conn.close()
    return [dict(r) for r in rows]


def get_notifications(shop_id: int) -> List[Dict[str, Any]]:
    conn = get_conn()
    cur = conn.cursor()
    cur.execute("SELECT * FROM notifications WHERE shop_id = ? ORDER BY created_at DESC", (shop_id,))
    rows = cur.fetchall()
    conn.close()
    return [dict(r) for r in rows]


def mark_notification_read(notification_id: int):
    conn = get_conn()
    cur = conn.cursor()
    cur.execute("UPDATE notifications SET read = 1 WHERE id = ?", (notification_id,))
    conn.commit()
    conn.close()


def get_shop_by_id(shop_id: int) -> Optional[Dict[str, Any]]:
    conn = get_conn()
    cur = conn.cursor()
    cur.execute("SELECT * FROM shops WHERE id = ?", (shop_id,))
    row = cur.fetchone()
    conn.close()
    return dict(row) if row else None
