"""Regression tests for the reservation-lifecycle fixes:

- start_route cancels delivery tickets that don't make the route (over max_stops
  or outside their time window) instead of leaving them orphaned 'open' forever.
- create_ticket gives delivery reservations a TTL, and reservation_ttl_tick frees
  both self-pickup and delivery reservations once they lapse.
"""
import pytest
from datetime import datetime, timezone, timedelta

from backend.shop import db as shop_db
from backend.shop.db import init_db as shop_init
from backend.needy.db import (
    create_ticket, create_needy, create_or_update_profile, get_profile,
    set_profile_last_received, init_db as needy_init,
)
from backend.volunteer import db as vdb
from backend.volunteer.routes import start_route, finish_route
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


# ── Q5: a lot leaving the witrine must free its open reservations ──────────────

def test_expire_soon_lots_cancels_open_reservations():
    """Q5: when a lot is auto-expired (≤24h to its expiry date), its open
    reservations must be cancelled, not left stranded on a dead lot until the 48h
    TTL — that would block the recipient's one-active-ticket slot meanwhile."""
    shop_id = shop_db.create_shop("Shop", "+7123", 43.2, 76.9, "Almaty")
    tomorrow = (datetime.now(timezone.utc).date() + timedelta(days=1)).isoformat()
    lot_id = shop_db.create_lot(shop_id, "1 Apple", 1.0, tomorrow, None, "Address")
    nid = _delivery_ticket("R", "+7111")
    tid = create_ticket(nid, "Apples", "Addr", 43.2, 76.9, lot_id=lot_id, self_pickup=False)

    assert shop_db.expire_soon_lots() == 1
    assert shop_db.get_lot_by_id(lot_id)["status"] == "expired"
    with get_db_cursor() as cur:
        cur.execute("SELECT status FROM tickets WHERE id = %s", (tid,))
        assert cur.fetchone()["status"] == "cancelled"
        cur.execute(
            "SELECT COUNT(*) AS n FROM notifications WHERE needy_id = %s AND type = 'ticket_cancelled'",
            (nid,),
        )
        assert cur.fetchone()["n"] == 1


def test_delete_lot_cancels_open_reservations():
    """Q5: deleting an active lot (shop removed it) must free recipients holding a
    reservation on it, with the same 'choose another lot' notification."""
    shop_id = shop_db.create_shop("Shop", "+7123", 43.2, 76.9, "Almaty")
    lot_id = shop_db.create_lot(shop_id, "1 Apple", 1.0, "2026-12-31", None, "Address")
    nid = _delivery_ticket("R", "+7111")
    tid = create_ticket(nid, "Apples", "Addr", 43.2, 76.9, lot_id=lot_id, self_pickup=False)

    assert shop_db.delete_lot(lot_id) is True
    assert shop_db.get_lot_by_id(lot_id)["status"] == "removed"
    with get_db_cursor() as cur:
        cur.execute("SELECT status FROM tickets WHERE id = %s", (tid,))
        assert cur.fetchone()["status"] == "cancelled"
        cur.execute(
            "SELECT COUNT(*) AS n FROM notifications WHERE needy_id = %s AND type = 'ticket_cancelled'",
            (nid,),
        )
        assert cur.fetchone()["n"] == 1


# ── Q12: finish_route must revert the lot, not just reopen tickets ─────────────

def _route_id_for_lot(lot_id):
    with get_db_cursor() as cur:
        cur.execute("SELECT id FROM volunteer_routes WHERE lot_id = %s ORDER BY id DESC LIMIT 1", (lot_id,))
        return cur.fetchone()["id"]


def test_finish_route_reverts_lot_and_reopens_undelivered():
    """Q12: a volunteer ending a route early must put the lot back on the witrine
    AND reopen its undelivered tickets — previously the tickets were reopened but
    the lot stayed 'taken', so they stranded 'open' on an unservable lot."""
    shop_id = shop_db.create_shop("Shop", "+7123", 43.2, 76.9, "Almaty")
    lot_id = shop_db.create_lot(shop_id, "2 Apples", 2.0, "2026-12-31", None, "Address")
    vol_id = vdb.create_volunteer("Vol", "+700", 43.21, 76.91, "Almaty")
    vdb.set_volunteer_status(vol_id, "approved")

    for i in range(2):
        nid = _delivery_ticket(f"R{i}", f"+711{i}")
        create_ticket(nid, "Apples", "Addr", 43.2, 76.9, lot_id=lot_id, self_pickup=False)

    start_route(vol_id, vschemas.StartRouteRequest(lot_id=lot_id, max_stops=2), current_user=ADMIN)
    assert shop_db.get_lot_by_id(lot_id)["status"] == "taken"

    finish_route(_route_id_for_lot(lot_id), vschemas.FinishRouteRequest(volunteer_id=vol_id), current_user=ADMIN)

    lot = shop_db.get_lot_by_id(lot_id)
    assert lot["status"] == "active"          # lot is back on the witrine
    assert lot["quantity"] == 0.0             # 2 reopened reservations still held
    with get_db_cursor() as cur:
        cur.execute("SELECT status, COUNT(*) AS n FROM tickets WHERE lot_id = %s GROUP BY status", (lot_id,))
        counts = {r["status"]: r["n"] for r in cur.fetchall()}
    assert counts.get("open") == 2
    assert counts.get("assigned", 0) == 0


def test_finish_route_cancels_tickets_when_lot_confirmed():
    """Q12: if the shop already confirmed the hand-over, finishing the route can't
    put the lot back — the undelivered tickets are cancelled (slot freed), not
    reopened onto a confirmed lot."""
    shop_id = shop_db.create_shop("Shop", "+7123", 43.2, 76.9, "Almaty")
    lot_id = shop_db.create_lot(shop_id, "1 Apple", 1.0, "2026-12-31", None, "Address")
    vol_id = vdb.create_volunteer("Vol", "+700", 43.21, 76.91, "Almaty")
    vdb.set_volunteer_status(vol_id, "approved")
    nid = _delivery_ticket("R", "+7111")
    create_ticket(nid, "Apples", "Addr", 43.2, 76.9, lot_id=lot_id, self_pickup=False)

    start_route(vol_id, vschemas.StartRouteRequest(lot_id=lot_id, max_stops=1), current_user=ADMIN)
    rid = _route_id_for_lot(lot_id)
    assert shop_db.confirm_lot_transfer(lot_id) is True  # taken→confirmed

    finish_route(rid, vschemas.FinishRouteRequest(volunteer_id=vol_id), current_user=ADMIN)

    assert shop_db.get_lot_by_id(lot_id)["status"] == "confirmed"
    with get_db_cursor() as cur:
        cur.execute("SELECT status FROM tickets WHERE needy_id = %s", (nid,))
        assert cur.fetchone()["status"] == "cancelled"
        cur.execute(
            "SELECT COUNT(*) AS n FROM notifications WHERE needy_id = %s AND type = 'ticket_cancelled'",
            (nid,),
        )
        assert cur.fetchone()["n"] == 1


# ── §59 Q1-C / Q3: displacement counter + score rebalance ─────────────────────

def test_displaced_count_increments_on_claim_and_resets_on_fulfil():
    """Q1-C: a recipient bumped when a volunteer claims the lot gets
    displaced_count +1; getting helped resets it to 0."""
    shop_id = shop_db.create_shop("Shop", "+7123", 43.2, 76.9, "Almaty")
    lot_id = shop_db.create_lot(shop_id, "3 Apples", 3.0, "2026-12-31", None, "Address")
    vol_id = vdb.create_volunteer("Vol", "+700", 43.21, 76.91, "Almaty")
    vdb.set_volunteer_status(vol_id, "approved")

    nids = []
    for i in range(3):
        nid = _delivery_ticket(f"R{i}", f"+711{i}")
        create_ticket(nid, "Apples", "Addr", 43.2, 76.9, lot_id=lot_id, self_pickup=False)
        nids.append(nid)

    # Route fits ONE stop → one assigned, the other two are displaced.
    start_route(vol_id, vschemas.StartRouteRequest(lot_id=lot_id, max_stops=1), current_user=ADMIN)

    counts = {nid: (get_profile(nid).get("displaced_count") or 0) for nid in nids}
    assert sum(counts.values()) == 2
    assert sorted(counts.values()) == [0, 1, 1]

    # Fulfilment clears the counter.
    bumped = next(nid for nid, c in counts.items() if c == 1)
    set_profile_last_received(bumped, datetime.now(timezone.utc))
    assert (get_profile(bumped).get("displaced_count") or 0) == 0


def test_score_rebalance_objective_need_beats_self_declared():
    """Q3: with rebalanced weights, a long wait without help outranks a
    self-declared 'critical' + large family (old weights picked the latter)."""
    shop_id = shop_db.create_shop("Shop", "+7123", 43.2, 76.9, "Almaty")
    # category 'овощи' is not in _CAT_KEYWORDS → no preference-match noise.
    lot_id = shop_db.create_lot(shop_id, "2 Veg", 2.0, "2026-12-31", None, "Address", category="овощи")
    vol_id = vdb.create_volunteer("Vol", "+700", 43.21, 76.91, "Almaty")
    vdb.set_volunteer_status(vol_id, "approved")

    # Self-declared: critical urgency + family 5, helped only 8 days ago.
    nid_declared = create_needy("Declared", "+7001")
    create_or_update_profile(nid_declared, "Addr", 5, "", "critical", city="Almaty")
    set_profile_last_received(nid_declared, datetime.now(timezone.utc) - timedelta(days=8))
    create_ticket(nid_declared, "Veg", "Addr", 43.2, 76.9, lot_id=lot_id, self_pickup=False)

    # Objective: normal urgency + family 1, but 20 days without help.
    nid_obj = create_needy("Objective", "+7002")
    create_or_update_profile(nid_obj, "Addr", 1, "", "normal", city="Almaty")
    set_profile_last_received(nid_obj, datetime.now(timezone.utc) - timedelta(days=20))
    create_ticket(nid_obj, "Veg", "Addr", 43.2, 76.9, lot_id=lot_id, self_pickup=False)

    start_route(vol_id, vschemas.StartRouteRequest(lot_id=lot_id, max_stops=1), current_user=ADMIN)

    with get_db_cursor() as cur:
        cur.execute("SELECT needy_id, status FROM tickets WHERE lot_id = %s", (lot_id,))
        status = {r["needy_id"]: r["status"] for r in cur.fetchall()}
    assert status[nid_obj] == "assigned"       # objective need wins selection
    assert status[nid_declared] == "cancelled"
