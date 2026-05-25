import os
from datetime import datetime, timezone
from typing import Optional, List, Dict, Any
from backend.database import get_db_cursor, get_conn

def init_db():
    with get_db_cursor() as cur:
        cur.execute(
            """
            CREATE TABLE IF NOT EXISTS shops (
                id SERIAL PRIMARY KEY,
                name TEXT NOT NULL,
                contact TEXT,
                lat REAL,
                lon REAL,
                created_at TIMESTAMP WITH TIME ZONE NOT NULL
            )
            """
        )

        cur.execute(
            """
            CREATE TABLE IF NOT EXISTS lots (
                id SERIAL PRIMARY KEY,
                shop_id INTEGER NOT NULL,
                description TEXT,
                quantity INTEGER,
                expiry_date DATE,
                photo TEXT,
                address TEXT,
                status TEXT NOT NULL DEFAULT 'active',
                created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                taken_at TIMESTAMP WITH TIME ZONE,
                taken_by TEXT,
                FOREIGN KEY(shop_id) REFERENCES shops(id)
            )
            """
        )

        cur.execute(
            """
            CREATE TABLE IF NOT EXISTS notifications (
                id SERIAL PRIMARY KEY,
                shop_id INTEGER NOT NULL,
                lot_id INTEGER,
                type TEXT,
                payload TEXT,
                created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                read INTEGER NOT NULL DEFAULT 0
            )
            """
        )

def create_shop(name: str, contact: Optional[str], lat: Optional[float] = None, lon: Optional[float] = None) -> int:
    with get_db_cursor() as cur:
        cur.execute(
            "INSERT INTO shops (name, contact, lat, lon, created_at) VALUES (%s, %s, %s, %s, %s) RETURNING id",
            (name, contact, lat, lon, datetime.now(timezone.utc)),
        )
        shop_id = cur.fetchone()['id']
        return shop_id

def create_lot(shop_id: int, description: str, quantity: int, expiry_date: str, photo: Optional[str], address: Optional[str]) -> int:
    with get_db_cursor() as cur:
        cur.execute(
            "INSERT INTO lots (shop_id, description, quantity, expiry_date, photo, address, status, created_at) VALUES (%s, %s, %s, %s, %s, %s, 'active', %s) RETURNING id",
            (shop_id, description, quantity, expiry_date, photo, address, datetime.now(timezone.utc)),
        )
        lot_id = cur.fetchone()['id']
        return lot_id

def get_active_lots(shop_id: int) -> List[Dict[str, Any]]:
    with get_db_cursor() as cur:
        cur.execute(
            "SELECT * FROM lots WHERE shop_id = %s AND status = 'active' AND (expiry_date IS NULL OR expiry_date > CURRENT_DATE + INTERVAL '1 day') ORDER BY created_at DESC",
            (shop_id,)
        )
        rows = cur.fetchall()
        return [dict(r) for r in rows]

def get_all_active_lots() -> List[Dict[str, Any]]:
    with get_db_cursor() as cur:
        cur.execute(
            "SELECT * FROM lots WHERE status = 'active' AND (expiry_date IS NULL OR expiry_date > CURRENT_DATE + INTERVAL '1 day') ORDER BY created_at DESC"
        )
        rows = cur.fetchall()
        return [dict(r) for r in rows]

def release_lot(lot_id: int) -> bool:
    with get_db_cursor() as cur:
        cur.execute("SELECT * FROM lots WHERE id = %s", (lot_id,))
        lot = cur.fetchone()
        if not lot:
            return False
        if lot['status'] != 'taken':
            return False
        cur.execute("UPDATE lots SET status = 'active', taken_at = NULL, taken_by = NULL WHERE id = %s", (lot_id,))
        try:
            cur.execute(
                "INSERT INTO notifications (shop_id, lot_id, type, payload, created_at, read) VALUES (%s, %s, %s, %s, %s, 0)",
                (lot['shop_id'], lot_id, 'lot_released', f'Lot {lot_id} released back to active', datetime.now(timezone.utc)),
            )
        except Exception:
            pass
        return True

def take_lot(lot_id: int, volunteer_name: str) -> Optional[Dict[str, Any]]:
    with get_db_cursor() as cur:
        cur.execute("SELECT * FROM lots WHERE id = %s", (lot_id,))
        lot = cur.fetchone()
        if not lot:
            return None
        if lot["status"] != 'active':
            return None

        taken_at = datetime.now(timezone.utc)
        cur.execute(
            "UPDATE lots SET status = 'taken', taken_at = %s, taken_by = %s WHERE id = %s",
            (taken_at, volunteer_name, lot_id),
        )

        cur.execute(
            "INSERT INTO notifications (shop_id, lot_id, type, payload, created_at, read) VALUES (%s, %s, %s, %s, %s, 0)",
            (lot["shop_id"], lot_id, 'lot_taken', f'Volunteer {volunteer_name} took lot {lot_id}', taken_at),
        )

        cur.execute("SELECT * FROM lots WHERE id = %s", (lot_id,))
        updated = cur.fetchone()
        return dict(updated)

def get_history(shop_id: int) -> List[Dict[str, Any]]:
    with get_db_cursor() as cur:
        cur.execute(
            "SELECT * FROM lots WHERE shop_id = %s AND status IN ('taken', 'expired', 'removed') ORDER BY COALESCE(taken_at, created_at) DESC",
            (shop_id,)
        )
        rows = cur.fetchall()
        return [dict(r) for r in rows]

def get_notifications(shop_id: int) -> List[Dict[str, Any]]:
    with get_db_cursor() as cur:
        cur.execute("SELECT * FROM notifications WHERE shop_id = %s ORDER BY created_at DESC", (shop_id,))
        rows = cur.fetchall()
        return [dict(r) for r in rows]

def mark_notification_read(notification_id: int):
    with get_db_cursor() as cur:
        cur.execute("UPDATE notifications SET read = 1 WHERE id = %s", (notification_id,))

def get_shop_by_id(shop_id: int) -> Optional[Dict[str, Any]]:
    with get_db_cursor() as cur:
        cur.execute("SELECT * FROM shops WHERE id = %s", (shop_id,))
        row = cur.fetchone()
        return dict(row) if row else None

def update_shop(shop_id: int, name: Optional[str], contact: Optional[str], lat: Optional[float], lon: Optional[float]) -> Optional[Dict[str, Any]]:
    with get_db_cursor() as cur:
        cur.execute("SELECT * FROM shops WHERE id = %s", (shop_id,))
        shop = cur.fetchone()
        if not shop:
            return None

        new_name = name if name is not None else shop['name']
        new_contact = contact if contact is not None else shop['contact']
        new_lat = lat if lat is not None else shop['lat']
        new_lon = lon if lon is not None else shop['lon']

        cur.execute(
            "UPDATE shops SET name = %s, contact = %s, lat = %s, lon = %s WHERE id = %s",
            (new_name, new_contact, new_lat, new_lon, shop_id),
        )
        cur.execute("SELECT * FROM shops WHERE id = %s", (shop_id,))
        updated = cur.fetchone()
        return dict(updated)

def update_lot(lot_id: int, description: Optional[str], quantity: Optional[int], expiry_date: Optional[str], address: Optional[str]) -> Optional[Dict[str, Any]]:
    with get_db_cursor() as cur:
        cur.execute("SELECT * FROM lots WHERE id = %s", (lot_id,))
        lot = cur.fetchone()
        if not lot:
            return None
        if lot['status'] != 'active':
            return None

        new_description = description if description is not None else lot['description']
        new_quantity = quantity if quantity is not None else lot['quantity']
        new_expiry = expiry_date if expiry_date is not None else lot['expiry_date']
        new_address = address if address is not None else lot['address']

        cur.execute(
            "UPDATE lots SET description = %s, quantity = %s, expiry_date = %s, address = %s WHERE id = %s",
            (new_description, new_quantity, new_expiry, new_address, lot_id),
        )
        cur.execute("SELECT * FROM lots WHERE id = %s", (lot_id,))
        updated = cur.fetchone()
        return dict(updated)

def delete_lot(lot_id: int) -> bool:
    with get_db_cursor() as cur:
        cur.execute("SELECT * FROM lots WHERE id = %s", (lot_id,))
        lot = cur.fetchone()
        if not lot:
            return False
        if lot['status'] != 'active':
            return False

        cur.execute("UPDATE lots SET status = 'removed' WHERE id = %s", (lot_id,))
        try:
            cur.execute(
                "INSERT INTO notifications (shop_id, lot_id, type, payload, created_at, read) VALUES (%s, %s, %s, %s, %s, 0)",
                (lot['shop_id'], lot_id, 'lot_removed', f'Lot {lot_id} removed by shop', datetime.now(timezone.utc)),
            )
        except Exception:
            pass
        return True

def expire_soon_lots() -> int:
    with get_db_cursor() as cur:
        cur.execute(
            "SELECT id, shop_id FROM lots WHERE status = 'active' AND expiry_date IS NOT NULL AND expiry_date <= CURRENT_DATE + INTERVAL '1 day'"
        )
        rows = cur.fetchall()
        ids = [r['id'] for r in rows]
        for r in rows:
            try:
                cur.execute("UPDATE lots SET status = 'expired' WHERE id = %s", (r['id'],))
                cur.execute(
                    "INSERT INTO notifications (shop_id, lot_id, type, payload, created_at, read) VALUES (%s, %s, %s, %s, %s, 0)",
                    (r['shop_id'], r['id'], 'lot_expired_soon', f'Lot {r["id"]} marked expired (within 24h)', datetime.now(timezone.utc)),
                )
            except Exception:
                pass
        return len(ids)
