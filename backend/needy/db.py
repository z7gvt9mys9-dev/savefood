import psycopg2.errors
from datetime import datetime, timedelta, timezone
from typing import Optional, List, Dict, Any
from backend.database import get_db_cursor
from backend.utils import ensure_aware_utc, generate_qr_secret, build_qr_code

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
                quantity REAL NOT NULL DEFAULT 1.0,
                expires_at TIMESTAMP WITH TIME ZONE,
                status TEXT NOT NULL DEFAULT 'open',
                created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                assigned_volunteer TEXT,
                fulfilled_at TIMESTAMP WITH TIME ZONE,
                FOREIGN KEY(needy_id) REFERENCES needy(id)
            )
            """
        )
        cur.execute("ALTER TABLE tickets ADD COLUMN IF NOT EXISTS quantity REAL NOT NULL DEFAULT 1.0")
        cur.execute("ALTER TABLE tickets ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP WITH TIME ZONE")
        # Auto-KYC v1: AI pre-check verdict for the admin moderation queue
        # (likely_ok | review | likely_fraud | unchecked); final decision stays human.
        cur.execute("ALTER TABLE needy ADD COLUMN IF NOT EXISTS kyc_score REAL")
        cur.execute("ALTER TABLE needy ADD COLUMN IF NOT EXISTS kyc_verdict TEXT")
        cur.execute("ALTER TABLE needy ADD COLUMN IF NOT EXISTS kyc_notes TEXT")
        cur.execute("ALTER TABLE needy ADD COLUMN IF NOT EXISTS kyc_checked_at TIMESTAMP WITH TIME ZONE")

        cur.execute("ALTER TABLE tickets ADD COLUMN IF NOT EXISTS lot_id INTEGER")
        cur.execute("ALTER TABLE tickets ADD COLUMN IF NOT EXISTS apartment TEXT")
        cur.execute("ALTER TABLE tickets ADD COLUMN IF NOT EXISTS floor_num TEXT")
        cur.execute("ALTER TABLE tickets ADD COLUMN IF NOT EXISTS entrance TEXT")
        cur.execute("ALTER TABLE tickets ADD COLUMN IF NOT EXISTS self_pickup BOOLEAN DEFAULT FALSE")
        # Unguessable per-ticket secret for the delivery/self-pickup QR
        # (SF-{id}-{secret}). Backfill existing rows so in-flight tickets keep
        # working; the value is random enough to not be derivable from the id.
        cur.execute("ALTER TABLE tickets ADD COLUMN IF NOT EXISTS qr_secret TEXT")
        cur.execute("UPDATE tickets SET qr_secret = substr(md5(random()::text || id::text), 1, 12) WHERE qr_secret IS NULL")

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
        # Geo-push subscription (§48): opt-out toggle for «рядом появился
        # подходящий лот» Web Push pings sent by needs_match.py (default on).
        cur.execute("ALTER TABLE needy_profile ADD COLUMN IF NOT EXISTS geo_push_enabled BOOLEAN NOT NULL DEFAULT TRUE")
        # Fair-queue displacement counter (§59/Q1-C): how many times this
        # recipient's reservation was cancelled because a volunteer claimed the
        # lot (outside their time window or over max_stops). Feeds the routing
        # score so the repeatedly-bumped climb; reset to 0 on fulfilment.
        cur.execute("ALTER TABLE needy_profile ADD COLUMN IF NOT EXISTS displaced_count INTEGER NOT NULL DEFAULT 0")

    # One active (open/assigned) ticket per needy, enforced by the DB so two
    # parallel create_ticket calls can't both pass the SELECT-then-INSERT check.
    # Separate cursor: if legacy data already violates the constraint, we log and
    # keep the app booting instead of crashing on startup.
    try:
        with get_db_cursor() as cur:
            cur.execute(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS uq_tickets_one_active_per_needy
                ON tickets (needy_id)
                WHERE status IN ('open', 'assigned')
                """
            )
    except Exception as e:
        print(f"Warning: could not create uq_tickets_one_active_per_needy (duplicate active tickets in DB?): {e}")

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

        # A ticket must point at real, still-available food: an invented or
        # already claimed/expired lot_id produces a request nobody can serve
        # (and lets a recipient «занять» food that does not exist).
        if self_pickup and lot_id is None:
            # Self-pickup is confirmed by the shop against the ticket's lot —
            # without a lot the QR can never be matched to a shop.
            raise TicketCreateError("lot_required")

        expires_at = None
        if lot_id is not None:
            # Atomic reservation: check status, expiry, AND quantity.
            # Decrement quantity by 1 for any ticket linked to a lot.
            cur.execute(
                """
                UPDATE lots SET quantity = quantity - 1
                WHERE id = %s AND status = 'active' AND quantity >= 1
                  AND (expiry_date IS NULL OR expiry_date::date >= CURRENT_DATE)
                """,
                (lot_id,),
            )
            if cur.rowcount == 0:
                raise TicketCreateError("lot_unavailable")

            if self_pickup:
                # Self-pickup reservation TTL: 2 hours (recipient committed to
                # come now).
                expires_at = datetime.now(timezone.utc) + timedelta(hours=2)
            else:
                # Delivery reservation TTL: 48 hours. Without it, a ticket nobody
                # ever routes locks the reserved unit and the recipient's
                # one-active-ticket slot indefinitely. reservation_ttl_tick frees
                # both once it lapses; an 'assigned' ticket is no longer 'open',
                # so a routed delivery never expires from under the volunteer.
                expires_at = datetime.now(timezone.utc) + timedelta(hours=48)

        try:
            cur.execute(
                "INSERT INTO tickets (needy_id, items, address, lat, lon, available_time, lot_id, quantity, expires_at, apartment, floor_num, entrance, self_pickup, qr_secret, status, created_at) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, 'open', %s) RETURNING id",
                (needy_id, items, address, lat, lon, available_time, lot_id, 1.0, expires_at, apartment, floor_num, entrance, self_pickup, generate_qr_secret(), datetime.now(timezone.utc)),
            )
        except psycopg2.errors.UniqueViolation:
            # uq_tickets_one_active_per_needy: a parallel request won the race
            # Rollback lot reservation if lot_id was used
            if lot_id is not None:
                cur.execute("UPDATE lots SET quantity = quantity + 1 WHERE id = %s", (lot_id,))
            raise TicketCreateError("active_ticket_exists")
        tid = cur.fetchone()['id']
        return tid

def _with_qr_code(row: Dict[str, Any]) -> Dict[str, Any]:
    """Attach the full QR payload so the recipient's app can render it. Only
    owner/admin-gated endpoints return this — the volunteer never sees the
    secret, which is what makes the delivery confirmation meaningful."""
    row["qr_code"] = build_qr_code(row["id"], row.get("qr_secret"))
    return row


def get_tickets_by_needy_id(needy_id: int) -> List[Dict[str, Any]]:
    with get_db_cursor() as cur:
        cur.execute("SELECT * FROM tickets WHERE needy_id = %s ORDER BY created_at DESC", (needy_id,))
        rows = cur.fetchall()
        return [_with_qr_code(dict(r)) for r in rows]

def get_history(needy_id: int, limit: int = 20, offset: int = 0) -> List[Dict[str, Any]]:
    with get_db_cursor() as cur:
        # rating + comment joined in so a saved star rating and thank-you note
        # survive page reloads
        cur.execute(
            """
            SELECT t.*, dr.rating, dr.comment AS rating_comment
            FROM tickets t
            LEFT JOIN delivery_ratings dr ON dr.ticket_id = t.id
            WHERE t.needy_id = %s AND t.status IN ('assigned','fulfilled')
            ORDER BY t.created_at DESC LIMIT %s OFFSET %s
            """,
            (needy_id, limit, offset)
        )
        rows = cur.fetchall()
        return [_with_qr_code(dict(r)) for r in rows]

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


def set_geo_push_enabled(needy_id: int, enabled: bool) -> bool:
    """Toggle the geo-push subscription (§48). Creates a bare profile row if the
    recipient has none yet so the preference can be stored independently."""
    with get_db_cursor() as cur:
        cur.execute("UPDATE needy_profile SET geo_push_enabled = %s WHERE needy_id = %s", (bool(enabled), needy_id))
        if cur.rowcount == 0:
            cur.execute("SELECT 1 FROM needy WHERE id = %s", (needy_id,))
            if not cur.fetchone():
                return False
            cur.execute(
                "INSERT INTO needy_profile (needy_id, geo_push_enabled) VALUES (%s, %s)",
                (needy_id, bool(enabled)),
            )
        return True


def export_account(needy_id: int) -> Optional[Dict[str, Any]]:
    """GDPR-style data export (§49): everything the platform holds about one
    recipient, as a single JSON-serialisable dict. Owner/admin only."""
    with get_db_cursor() as cur:
        cur.execute("SELECT id, name, contact, status, created_at FROM needy WHERE id = %s", (needy_id,))
        n = cur.fetchone()
        if not n:
            return None
        cur.execute("SELECT * FROM needy_profile WHERE needy_id = %s", (needy_id,))
        profile = cur.fetchone()
        cur.execute("SELECT * FROM tickets WHERE needy_id = %s ORDER BY created_at", (needy_id,))
        tickets = [dict(r) for r in cur.fetchall()]
        cur.execute(
            "SELECT id, type, payload, created_at, read FROM notifications WHERE needy_id = %s ORDER BY created_at",
            (needy_id,),
        )
        notifications = [dict(r) for r in cur.fetchall()]
        cur.execute(
            "SELECT dr.* FROM delivery_ratings dr JOIN tickets t ON t.id = dr.ticket_id WHERE t.needy_id = %s",
            (needy_id,),
        )
        ratings = [dict(r) for r in cur.fetchall()]
        cur.execute(
            """
            SELECT tm.id, tm.ticket_id, tm.sender_role, tm.body, tm.created_at
            FROM ticket_messages tm JOIN tickets t ON t.id = tm.ticket_id
            WHERE t.needy_id = %s ORDER BY tm.id
            """,
            (needy_id,),
        )
        messages = [dict(r) for r in cur.fetchall()]
    return {
        "account": dict(n),
        "profile": dict(profile) if profile else None,
        "tickets": tickets,
        "ratings": ratings,
        "notifications": notifications,
        "messages": messages,
    }


def cancel_ticket(ticket_id: int) -> bool:
    """Explicit ticket cancellation: return reserved quantity to the lot if it's still active."""
    with get_db_cursor() as cur:
        cur.execute("SELECT lot_id, quantity, status FROM tickets WHERE id = %s", (ticket_id,))
        ticket = cur.fetchone()
        if not ticket or ticket['status'] not in ('open', 'assigned'):
            return False
        
        cur.execute("UPDATE tickets SET status = 'cancelled' WHERE id = %s", (ticket_id,))
        if ticket['lot_id']:
            # Guarded return: only if lot is still active.
            cur.execute(
                "UPDATE lots SET quantity = quantity + %s WHERE id = %s AND status = 'active'",
                (ticket['quantity'], ticket['lot_id'])
            )
        return True

def erase_account(needy_id: int) -> Optional[Dict[str, Any]]:
    """«Право на забвение» (§49). Returns the on-disk file paths the caller must
    delete (document + delivery photos), having already anonymised the row.

    Personal data is scrubbed but the ticket rows survive (status/timestamps
    only) so platform aggregates — deliveries completed, rescued kg via lots —
    stay correct. The login is removed, so the account can never be used again.
    """
    with get_db_cursor() as cur:
        cur.execute("SELECT id FROM needy WHERE id = %s", (needy_id,))
        if not cur.fetchone():
            return None

        # Collect file paths to remove on disk (caller handles the FS).
        cur.execute("SELECT document FROM needy_profile WHERE needy_id = %s", (needy_id,))
        prow = cur.fetchone()
        document = prow["document"] if prow else None
        cur.execute(
            "SELECT delivery_photo FROM tickets WHERE needy_id = %s AND delivery_photo IS NOT NULL",
            (needy_id,),
        )
        photos = [r["delivery_photo"] for r in cur.fetchall()]

        # Return reservations for live tickets
        cur.execute("SELECT id, lot_id, quantity FROM tickets WHERE needy_id = %s AND status IN ('open', 'assigned')", (needy_id,))
        for t in cur.fetchall():
            if t['lot_id']:
                cur.execute(
                    "UPDATE lots SET quantity = quantity + %s WHERE id = %s AND status = 'active'",
                    (t['quantity'], t['lot_id'])
                )

        # Close live tickets first: a scrubbed row must not hang in the queue as
        # an eternal 'open' request with NULL coordinates (nobody can serve it),
        # and an assigned volunteer must learn the stop is gone.
        cur.execute(
            "SELECT id, assigned_volunteer_id FROM tickets WHERE needy_id = %s AND status IN ('open', 'assigned')",
            (needy_id,),
        )
        live = cur.fetchall()
        if live:
            cur.execute(
                "UPDATE tickets SET status = 'cancelled', assigned_volunteer = NULL, assigned_volunteer_id = NULL "
                "WHERE needy_id = %s AND status IN ('open', 'assigned')",
                (needy_id,),
            )
            for crow in live:
                if crow.get("assigned_volunteer_id"):
                    cur.execute(
                        "INSERT INTO notifications (volunteer_id, type, payload, created_at, read) VALUES (%s, %s, %s, CURRENT_TIMESTAMP, 0)",
                        (crow["assigned_volunteer_id"], "ticket_cancelled",
                         f"Заявка #{crow['id']} отменена (аккаунт получателя удалён) — точка снята с маршрута."),
                    )

        # Scrub PII from tickets, keep status/timestamps for aggregates.
        cur.execute(
            """
            UPDATE tickets SET items = NULL, address = NULL, lat = NULL, lon = NULL,
                   apartment = NULL, floor_num = NULL, entrance = NULL, available_time = NULL,
                   assigned_volunteer = NULL,
                   delivery_photo = NULL, delivery_photo_status = NULL,
                   delivery_photo_ai_verdict = NULL, delivery_photo_ai_notes = NULL
             WHERE needy_id = %s
            """,
            (needy_id,),
        )
        # Chat threads and thank-you notes are the recipient's words (often with
        # addresses/phones inside) — erase them too; the numeric rating survives
        # so volunteer averages don't shift retroactively.
        cur.execute(
            "DELETE FROM ticket_messages WHERE ticket_id IN (SELECT id FROM tickets WHERE needy_id = %s)",
            (needy_id,),
        )
        cur.execute(
            "UPDATE delivery_ratings SET comment = NULL "
            "WHERE ticket_id IN (SELECT id FROM tickets WHERE needy_id = %s)",
            (needy_id,),
        )
        cur.execute("DELETE FROM notifications WHERE needy_id = %s", (needy_id,))
        cur.execute("DELETE FROM needy_profile WHERE needy_id = %s", (needy_id,))
        # Remove the login (cascades to push_subscriptions / link tokens).
        cur.execute("DELETE FROM users WHERE role = 'needy' AND related_id = %s", (needy_id,))
        # Anonymise the needy row itself; status 'deleted' hides it everywhere.
        cur.execute(
            "UPDATE needy SET name = 'Удалённый аккаунт', contact = NULL, status = 'deleted', "
            "document = NULL, kyc_notes = NULL WHERE id = %s",
            (needy_id,),
        )
    return {"document": document, "photos": photos}

def set_needy_status(needy_id: int, status: str, expected_status: Optional[str] = None) -> Optional[Dict[str, Any]]:
    """Set the moderation status. When `expected_status` is given the flip is
    conditional (UPDATE ... WHERE status = expected) and returns None if the row
    was no longer in that state — used by the auto-KYC thread so it cannot clobber
    a manual decision a moderator made during the AI call (TOCTOU guard)."""
    with get_db_cursor() as cur:
        cur.execute("SELECT * FROM needy WHERE id = %s", (needy_id,))
        n = cur.fetchone()
        if not n:
            return None

        # The eligibility document is NOT deleted on a decision any more — it is
        # retained encrypted-at-rest for accountability (§58). No human accesses
        # it; decryption is a deliberate break-glass action.
        if expected_status is None:
            cur.execute("UPDATE needy SET status = %s WHERE id = %s", (status, needy_id))
        else:
            cur.execute("UPDATE needy SET status = %s WHERE id = %s AND status = %s",
                        (status, needy_id, expected_status))
            if cur.rowcount == 0:
                return None

        cur.execute("SELECT * FROM needy WHERE id = %s", (needy_id,))
        updated = cur.fetchone()
        return dict(updated)

def set_profile_last_received(needy_id: int, ts):
    with get_db_cursor() as cur:
        # §59/Q1-C: getting helped clears the displacement counter — a fresh start.
        cur.execute("UPDATE needy_profile SET last_received_at = %s, displaced_count = 0 WHERE needy_id = %s", (ts, needy_id))
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
