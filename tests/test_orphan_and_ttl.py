"""Regression tests for the reservation-lifecycle fixes:

- start_route cancels delivery tickets that don't make the route (over max_stops
  or outside their time window) instead of leaving them orphaned 'open' forever.
- create_ticket gives delivery reservations a TTL, and reservation_ttl_tick frees
  both self-pickup and delivery reservations once they lapse.
"""
import pytest
from datetime import datetime, timezone

from backend.shop import db as shop_db
from backend.shop.db import init_db as shop_init
from backend.needy.db import (
    create_ticket, create_needy, create_or_update_profile, init_db as needy_init,
)
from backend.volunteer import db as vdb
from backend.volunteer.routes import start_route
from backend.volunteer import schemas as vschemas
from backend.database import get_db_cursor, init_common_db, init_ticket_extensions, get_conn


def is_db_reachable():
    try:
        conn = get_conn()
        conn.close()
        return True
    except Exception:
        return False


pytestmark = pytest.mark.skipif(not is_db_reachable(), reason="Database not reachable")


@pytest.fixture(autouse=True)
def db_setup():
    init_common_db()
    shop_init()
    needy_init()
    vdb.init_db()
    init_ticket_extensions()
    with get_db_cursor() as cur:
        cur.execute("DELETE FROM notifications")
        cur.execute("DELETE FROM volunteer_routes")
        cur.execute("DELETE FROM tickets")
        cur.execute("DELETE FROM lots")
        cur.execute("DELETE FROM shops")
        cur.execute("DELETE FROM needy_profile")
        cur.execute("DELETE FROM needy")
        cur.execute("DELETE FROM volunteers")
        cur.execute("DELETE FROM users")


ADMIN = {"role": "admin"}


def _delivery_ticket(name, contact):
    nid = create_needy(name, contact)
    create_or_update_profile(nid, "Addr", 1, "Apples", "Normal", city="Almaty")
    return nid


def test_start_route_cancels_orphaned_delivery_tickets():
    shop_id = shop_db.create_shop("Shop", "+7123", 43.2, 76.9, "Almaty")
    lot_id = shop_db.create_lot(shop_id, "5 Apples", 5.0, "2026-12-31", None, "Address")

    vol_id = vdb.create_volunteer("Vol", "+700", 43.21, 76.91, "Almaty")
    vdb.set_volunteer_status(vol_id, "approved")  # KYC gate (§58): only approved volunteers claim routes

    # Three recipients each reserve one delivery unit on the same lot.
    ids = []
    for i in range(3):
        nid = _delivery_ticket(f"R{i}", f"+711{i}")
        create_ticket(nid, "Apples", "Addr", 43.2, 76.9, lot_id=lot_id, self_pickup=False)
        ids.append(nid)

    lot = shop_db.get_lot_by_id(lot_id)
    assert lot["quantity"] == 2.0  # 5 - 3 reserved

    # Volunteer claims the lot but the route only fits ONE stop.
    start_route(vol_id, vschemas.StartRouteRequest(lot_id=lot_id, max_stops=1), current_user=ADMIN)

    with get_db_cursor() as cur:
        cur.execute("SELECT status, COUNT(*) AS n FROM tickets WHERE lot_id = %s GROUP BY status ORDER BY status", (lot_id,))
        counts = {r["status"]: r["n"] for r in cur.fetchall()}

    # Exactly one assigned; the other two are cancelled, not left dangling 'open'.
    assert counts.get("assigned") == 1
    assert counts.get("cancelled") == 2
    assert counts.get("open", 0) == 0

    # Each cancelled recipient was notified that their slot is free.
    with get_db_cursor() as cur:
        cur.execute(
            "SELECT COUNT(*) AS n FROM notifications WHERE type = 'ticket_cancelled' AND needy_id = ANY(%s)",
            (ids,),
        )
        assert cur.fetchone()["n"] == 2


def test_delivery_reservation_gets_ttl():
    shop_id = shop_db.create_shop("Shop", "+7123", 43.2, 76.9, "Almaty")
    lot_id = shop_db.create_lot(shop_id, "1 Apple", 1.0, "2026-12-31", None, "Address")
    nid = _delivery_ticket("R", "+7111")
    tid = create_ticket(nid, "Apples", "Addr", 43.2, 76.9, lot_id=lot_id, self_pickup=False)

    with get_db_cursor() as cur:
        cur.execute("SELECT expires_at FROM tickets WHERE id = %s", (tid,))
        assert cur.fetchone()["expires_at"] is not None  # delivery reservations now expire too


def test_reservation_ttl_tick_frees_expired_delivery_reservation():
    shop_id = shop_db.create_shop("Shop", "+7123", 43.2, 76.9, "Almaty")
    lot_id = shop_db.create_lot(shop_id, "1 Apple", 1.0, "2026-12-31", None, "Address")
    nid = _delivery_ticket("R", "+7111")
    tid = create_ticket(nid, "Apples", "Addr", 43.2, 76.9, lot_id=lot_id, self_pickup=False)

    assert shop_db.get_lot_by_id(lot_id)["quantity"] == 0.0

    with get_db_cursor() as cur:
        cur.execute("UPDATE tickets SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id = %s", (tid,))

    from backend.background import reservation_ttl_tick
    assert reservation_ttl_tick() == 1

    assert shop_db.get_lot_by_id(lot_id)["quantity"] == 1.0  # unit returned
    with get_db_cursor() as cur:
        cur.execute("SELECT status FROM tickets WHERE id = %s", (tid,))
        assert cur.fetchone()["status"] == "cancelled"
        cur.execute("SELECT COUNT(*) AS n FROM notifications WHERE needy_id = %s AND type = 'reservation_expired'", (nid,))
        assert cur.fetchone()["n"] == 1
