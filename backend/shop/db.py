import json
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
                time_slot TEXT,
                status TEXT NOT NULL DEFAULT 'active',
                created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                taken_at TIMESTAMP WITH TIME ZONE,
                taken_by TEXT,
                category TEXT,
                comment TEXT,
                FOREIGN KEY(shop_id) REFERENCES shops(id)
            )
            """
        )
        cur.execute("ALTER TABLE lots ADD COLUMN IF NOT EXISTS time_slot TEXT")
        cur.execute("ALTER TABLE lots ADD COLUMN IF NOT EXISTS category TEXT")
        cur.execute("ALTER TABLE lots ADD COLUMN IF NOT EXISTS comment TEXT")
        # Cold chain (§47): the lot must be kept refrigerated, so only a
        # volunteer with a thermal bag may claim it (see volunteer/routes.py).
        cur.execute("ALTER TABLE lots ADD COLUMN IF NOT EXISTS requires_cold BOOLEAN NOT NULL DEFAULT FALSE")
        cur.execute("ALTER TABLE shops ADD COLUMN IF NOT EXISTS city TEXT")
        cur.execute("ALTER TABLE lots ADD COLUMN IF NOT EXISTS city TEXT")
        # SaaS plan: 'basic' | 'pro' | 'enterprise' (see backend/billing.py).
        cur.execute("ALTER TABLE shops ADD COLUMN IF NOT EXISTS plan TEXT NOT NULL DEFAULT 'basic'")
        # C2C donors: 'business' (shop/cafe) | 'private' (a person donating
        # surplus). Private donors reuse the whole shop flow but their lots
        # require a photo and carry a «Частный донор» badge on the map.
        cur.execute("ALTER TABLE shops ADD COLUMN IF NOT EXISTS kind TEXT NOT NULL DEFAULT 'business'")

        # OCR-parsed write-off receipts (photo lives in a non-public dir; the
        # image is served only through the auth-checked receipt endpoints).
        cur.execute(
            """
            CREATE TABLE IF NOT EXISTS receipts (
                id SERIAL PRIMARY KEY,
                shop_id INTEGER NOT NULL REFERENCES shops(id),
                photo TEXT,
                sha256 TEXT,
                fingerprint TEXT,
                merchant TEXT,
                receipt_date DATE,
                total REAL,
                currency TEXT,
                items TEXT,
                fraud_score REAL,
                fraud_reasons TEXT,
                status TEXT NOT NULL DEFAULT 'parsed',
                lot_ids TEXT,
                created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                confirmed_at TIMESTAMP WITH TIME ZONE
            )
            """
        )
        cur.execute("CREATE INDEX IF NOT EXISTS idx_receipts_shop ON receipts (shop_id)")
        cur.execute("CREATE INDEX IF NOT EXISTS idx_receipts_sha ON receipts (sha256)")
        cur.execute("CREATE INDEX IF NOT EXISTS idx_receipts_fp ON receipts (fingerprint) WHERE fingerprint IS NOT NULL")

        # Enterprise partner API: keys are stored only as sha256 hashes — the
        # full secret is shown to the shop exactly once at creation time.
        cur.execute(
            """
            CREATE TABLE IF NOT EXISTS api_keys (
                id SERIAL PRIMARY KEY,
                shop_id INTEGER NOT NULL REFERENCES shops(id),
                key_hash TEXT UNIQUE NOT NULL,
                prefix TEXT NOT NULL,
                revoked BOOLEAN NOT NULL DEFAULT FALSE,
                created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
                last_used_at TIMESTAMP WITH TIME ZONE
            )
            """
        )
        # Outgoing webhooks to the shop's ERP. `events` is a CSV of event names
        # ('*' = everything); deliveries are HMAC-signed with `secret`.
        cur.execute(
            """
            CREATE TABLE IF NOT EXISTS webhooks (
                id SERIAL PRIMARY KEY,
                shop_id INTEGER NOT NULL REFERENCES shops(id),
                url TEXT NOT NULL,
                secret TEXT NOT NULL,
                events TEXT NOT NULL DEFAULT '*',
                active BOOLEAN NOT NULL DEFAULT TRUE,
                created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
                last_status INTEGER,
                last_delivery_at TIMESTAMP WITH TIME ZONE
            )
            """
        )
        cur.execute("CREATE INDEX IF NOT EXISTS idx_webhooks_shop ON webhooks (shop_id) WHERE active")

        cur.execute(
            """
            CREATE TABLE IF NOT EXISTS notifications (
                id SERIAL PRIMARY KEY,
                shop_id INTEGER,
                lot_id INTEGER,
                type TEXT,
                payload TEXT,
                created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                read INTEGER NOT NULL DEFAULT 0
            )
            """
        )
        # `notifications` is shared by shop/needy/volunteer modules (each filters by
        # its own *_id column). Relax the legacy shop_id NOT NULL so needy/volunteer
        # rows (which leave shop_id NULL) can be inserted.
        cur.execute("ALTER TABLE notifications ALTER COLUMN shop_id DROP NOT NULL")

        # Hot-path indexes — shop dashboard, lot listing, and the polling WS loop.
        cur.execute("CREATE INDEX IF NOT EXISTS idx_notifications_shop_id ON notifications (shop_id) WHERE shop_id IS NOT NULL")
        cur.execute("CREATE INDEX IF NOT EXISTS idx_lots_shop_status ON lots (shop_id, status)")
        cur.execute("CREATE INDEX IF NOT EXISTS idx_lots_status ON lots (status)")

def create_shop(name: str, contact: Optional[str], lat: Optional[float] = None, lon: Optional[float] = None, city: Optional[str] = None) -> int:
    with get_db_cursor() as cur:
        cur.execute(
            "INSERT INTO shops (name, contact, lat, lon, city, created_at) VALUES (%s, %s, %s, %s, %s, %s) RETURNING id",
            (name, contact, lat, lon, city, datetime.now(timezone.utc)),
        )
        shop_id = cur.fetchone()['id']
        return shop_id

def create_lot(shop_id: int, description: str, quantity: int, expiry_date: str, photo: Optional[str], address: Optional[str], time_slot: Optional[str] = None, category: Optional[str] = None, comment: Optional[str] = None, requires_cold: bool = False) -> int:
    with get_db_cursor() as cur:
        cur.execute("SELECT city FROM shops WHERE id = %s", (shop_id,))
        row = cur.fetchone()
        city = row['city'] if row else None
        cur.execute(
            "INSERT INTO lots (shop_id, description, quantity, expiry_date, photo, address, time_slot, category, comment, city, requires_cold, status, created_at) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, 'active', %s) RETURNING id",
            (shop_id, description, quantity, expiry_date, photo, address, time_slot, category, comment, city, bool(requires_cold), datetime.now(timezone.utc)),
        )
        lot_id = cur.fetchone()['id']
        return lot_id

def get_active_lots(shop_id: int) -> List[Dict[str, Any]]:
    with get_db_cursor() as cur:
        # 'taken' lots stay visible regardless of expiry so the shop can still
        # confirm transfer even after the 24h cutoff would have hidden them.
        cur.execute(
            """
            SELECT * FROM lots
            WHERE shop_id = %s
              AND (
                (status = 'active' AND (expiry_date IS NULL OR expiry_date > CURRENT_DATE + INTERVAL '1 day'))
                OR status = 'taken'
              )
            ORDER BY created_at DESC
            """,
            (shop_id,)
        )
        rows = cur.fetchall()
        return [dict(r) for r in rows]

def get_all_active_lots(limit: int = 20, offset: int = 0, category: str = None, search: str = None) -> List[Dict[str, Any]]:
    with get_db_cursor() as cur:
        filters = ["l.status = 'active'", "(l.expiry_date IS NULL OR l.expiry_date > CURRENT_DATE + INTERVAL '1 day')"]
        params = []
        if category:
            filters.append("l.category ILIKE %s")
            params.append(category)
        if search:
            filters.append("(l.description ILIKE %s OR l.address ILIKE %s)")
            params.extend([f"%{search}%", f"%{search}%"])
        where = " AND ".join(filters)
        params.extend([limit, offset])
        cur.execute(f"""
            SELECT l.*, s.name as shop_name, s.lat as shop_lat, s.lon as shop_lon, s.kind as shop_kind
            FROM lots l
            JOIN shops s ON s.id = l.shop_id
            WHERE {where}
            ORDER BY l.created_at DESC LIMIT %s OFFSET %s
        """, params)
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
                (lot['shop_id'], lot_id, 'lot_released', f'Лот #{lot_id} снова доступен волонтёрам', datetime.now(timezone.utc)),
            )
        except Exception:
            pass
        return True

def take_lot(lot_id: int, volunteer_name: str) -> Optional[Dict[str, Any]]:
    with get_db_cursor() as cur:
        taken_at = datetime.now(timezone.utc)
        # Atomic claim — only one concurrent caller can flip 'active' → 'taken'.
        cur.execute(
            "UPDATE lots SET status = 'taken', taken_at = %s, taken_by = %s WHERE id = %s AND status = 'active'",
            (taken_at, volunteer_name, lot_id),
        )
        if cur.rowcount == 0:
            return None

        cur.execute("SELECT * FROM lots WHERE id = %s", (lot_id,))
        updated = cur.fetchone()
        if not updated:
            return None

        cur.execute(
            "INSERT INTO notifications (shop_id, lot_id, type, payload, created_at, read) VALUES (%s, %s, %s, %s, %s, 0)",
            (updated["shop_id"], lot_id, 'lot_taken', f'Волонтёр {volunteer_name} забрал лот #{lot_id}', taken_at),
        )
        return dict(updated)

def get_history(shop_id: int, limit: int = 20, offset: int = 0) -> List[Dict[str, Any]]:
    with get_db_cursor() as cur:
        cur.execute(
            "SELECT * FROM lots WHERE shop_id = %s AND status IN ('taken', 'confirmed', 'expired', 'removed') ORDER BY COALESCE(taken_at, created_at) DESC LIMIT %s OFFSET %s",
            (shop_id, limit, offset)
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

def get_notification_by_id(notification_id: int) -> Optional[Dict[str, Any]]:
    with get_db_cursor() as cur:
        cur.execute("SELECT * FROM notifications WHERE id = %s", (notification_id,))
        row = cur.fetchone()
        return dict(row) if row else None

def get_lot_by_id(lot_id: int) -> Optional[Dict[str, Any]]:
    with get_db_cursor() as cur:
        cur.execute("SELECT * FROM lots WHERE id = %s", (lot_id,))
        row = cur.fetchone()
        return dict(row) if row else None

def get_shop_by_id(shop_id: int) -> Optional[Dict[str, Any]]:
    with get_db_cursor() as cur:
        cur.execute("SELECT * FROM shops WHERE id = %s", (shop_id,))
        row = cur.fetchone()
        return dict(row) if row else None

def update_shop(shop_id: int, name: Optional[str], contact: Optional[str], lat: Optional[float], lon: Optional[float], city: Optional[str] = None) -> Optional[Dict[str, Any]]:
    with get_db_cursor() as cur:
        cur.execute("SELECT * FROM shops WHERE id = %s", (shop_id,))
        shop = cur.fetchone()
        if not shop:
            return None

        new_name = name if name is not None else shop['name']
        new_contact = contact if contact is not None else shop['contact']
        new_lat = lat if lat is not None else shop['lat']
        new_lon = lon if lon is not None else shop['lon']
        new_city = city if city is not None else shop.get('city')

        cur.execute(
            "UPDATE shops SET name = %s, contact = %s, lat = %s, lon = %s, city = %s WHERE id = %s",
            (new_name, new_contact, new_lat, new_lon, new_city, shop_id),
        )
        cur.execute("SELECT * FROM shops WHERE id = %s", (shop_id,))
        updated = cur.fetchone()
        return dict(updated)

def update_lot(lot_id: int, description: Optional[str], quantity: Optional[int], expiry_date: Optional[str], address: Optional[str], category: Optional[str] = None, comment: Optional[str] = None, requires_cold: Optional[bool] = None) -> Optional[Dict[str, Any]]:
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
        new_category = category if category is not None else lot.get('category')
        new_comment = comment if comment is not None else lot.get('comment')
        new_cold = requires_cold if requires_cold is not None else lot.get('requires_cold')

        cur.execute(
            "UPDATE lots SET description = %s, quantity = %s, expiry_date = %s, address = %s, category = %s, comment = %s, requires_cold = %s WHERE id = %s",
            (new_description, new_quantity, new_expiry, new_address, new_category, new_comment, new_cold, lot_id),
        )
        cur.execute("SELECT * FROM lots WHERE id = %s", (lot_id,))
        updated = cur.fetchone()
        return dict(updated)


def confirm_lot_transfer(lot_id: int) -> bool:
    with get_db_cursor() as cur:
        cur.execute("SELECT * FROM lots WHERE id = %s", (lot_id,))
        lot = cur.fetchone()
        if not lot:
            return False
        if lot['status'] != 'taken':
            return False
        cur.execute("UPDATE lots SET status = 'confirmed' WHERE id = %s", (lot_id,))
        return True

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
                (lot['shop_id'], lot_id, 'lot_removed', f'Лот #{lot_id} удалён магазином', datetime.now(timezone.utc)),
            )
        except Exception:
            pass
        return True

def find_receipt_by_sha(sha256: str) -> Optional[Dict[str, Any]]:
    """Exact-duplicate check: the same image bytes uploaded before (any shop)."""
    with get_db_cursor() as cur:
        cur.execute("SELECT id, shop_id, status FROM receipts WHERE sha256 = %s LIMIT 1", (sha256,))
        row = cur.fetchone()
        return dict(row) if row else None


def fingerprint_exists(fp: str, exclude_id: Optional[int] = None) -> bool:
    """Near-duplicate check: same merchant+date+total already on a live receipt
    (rejected ones don't count — the shop may legitimately retry a photo)."""
    with get_db_cursor() as cur:
        cur.execute(
            "SELECT 1 FROM receipts WHERE fingerprint = %s AND status != 'rejected' AND (%s::int IS NULL OR id != %s) LIMIT 1",
            (fp, exclude_id, exclude_id),
        )
        return cur.fetchone() is not None


def create_receipt(shop_id: int, photo: Optional[str], sha256: str, fp: Optional[str],
                   parsed: Dict[str, Any], fraud: Dict[str, Any], status: str) -> int:
    with get_db_cursor() as cur:
        cur.execute(
            """
            INSERT INTO receipts (shop_id, photo, sha256, fingerprint, merchant, receipt_date,
                                  total, currency, items, fraud_score, fraud_reasons, status, created_at)
            VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s) RETURNING id
            """,
            (
                shop_id, photo, sha256, fp,
                parsed.get("merchant"),
                parsed.get("receipt_date"),
                parsed.get("total"),
                parsed.get("currency"),
                json.dumps(parsed.get("items") or [], ensure_ascii=False),
                fraud.get("score"),
                "; ".join(fraud.get("reasons") or []) or None,
                status,
                datetime.now(timezone.utc),
            ),
        )
        return cur.fetchone()["id"]


def get_receipt_by_id(receipt_id: int) -> Optional[Dict[str, Any]]:
    with get_db_cursor() as cur:
        cur.execute("SELECT * FROM receipts WHERE id = %s", (receipt_id,))
        row = cur.fetchone()
        return dict(row) if row else None


def confirm_receipt(receipt_id: int, lot_ids: List[int]):
    with get_db_cursor() as cur:
        cur.execute(
            "UPDATE receipts SET status = 'confirmed', lot_ids = %s, confirmed_at = %s WHERE id = %s",
            (json.dumps(lot_ids), datetime.now(timezone.utc), receipt_id),
        )


def get_receipts(shop_id: int, limit: int = 20, offset: int = 0) -> List[Dict[str, Any]]:
    with get_db_cursor() as cur:
        cur.execute(
            "SELECT * FROM receipts WHERE shop_id = %s ORDER BY created_at DESC LIMIT %s OFFSET %s",
            (shop_id, limit, offset),
        )
        return [dict(r) for r in cur.fetchall()]


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
                    (r['shop_id'], r['id'], 'lot_expired_soon', f'Лот #{r["id"]} снят: до истечения срока годности менее 24 часов', datetime.now(timezone.utc)),
                )
            except Exception:
                pass
        return len(ids)
