"""Background task scheduling config (the ticks themselves are DB-bound and
covered by the runtime smoke — here we pin the mode contract and task table)."""
import pytest

from backend import background


def test_default_mode_is_embedded(monkeypatch):
    monkeypatch.delenv("BACKGROUND_TASKS", raising=False)
    assert background.get_mode() == "embedded"


def test_modes_parse(monkeypatch):
    for value, expected in [
        ("external", "external"),
        ("OFF", "off"),
        ("  Embedded ", "embedded"),
        ("garbage", "embedded"),  # unknown value falls back to safe default
    ]:
        monkeypatch.setenv("BACKGROUND_TASKS", value)
        assert background.get_mode() == expected


def test_task_table_complete():
    names = [name for name, _, _ in background.TASKS]
    assert names == ["expire", "reassign", "antifraud", "reservation_ttl", "kyc_retry", "kyc_doc_retention"]
    for _, tick, interval in background.TASKS:
        assert callable(tick)
        assert interval > 0
    # anti-fraud must run noticeably more often than the route timeout check
    assert background.ANTIFRAUD_INTERVAL < background.REASSIGN_INTERVAL


# ── reassign_tick quantity reconciliation (BUG-1: quantity leak on revert) ─────

from backend.shop import db as shop_db
from backend.shop.db import init_db as shop_init
from backend.needy.db import (
    create_ticket, create_needy, create_or_update_profile, init_db as needy_init,
)
from backend.volunteer import db as vdb
from backend.volunteer.routes import start_route
from backend.volunteer import schemas as vschemas
from backend.database import (
    get_db_cursor, init_common_db, init_ticket_extensions, get_conn,
)


def _db_reachable():
    try:
        conn = get_conn()
        conn.close()
        return True
    except Exception:
        return False


@pytest.fixture
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


@pytest.mark.skipif(not _db_reachable(), reason="Database not reachable")
def test_reassign_tick_reconciles_quantity_on_revert(db_setup):
    """A claimed lot whose route times out must come back with the units of the
    co-lot tickets it cancelled at claim restored — not permanently leaked.

    Lot of 2 units, two delivery reservations (quantity → 0). With max_stops=1
    start_route assigns one ticket and cancels the other co-lot ticket WITHOUT
    returning its unit (lot is 'taken'). After the route times out and
    reassign_tick reverts the lot, quantity must be 1 (the one still-open route
    ticket holds 1 unit; the cancelled co-lot unit is reconciled back). Before
    the fix this was 0 and the lot would vanish from the map (quantity > 0 filter).
    """
    shop_id = shop_db.create_shop("Shop", "+7123", 43.2, 76.9, "Almaty")
    lot_id = shop_db.create_lot(shop_id, "2 Apples", 2.0, "2026-12-31", None, "Address")

    vol_id = vdb.create_volunteer("Vol", "+700", 43.21, 76.91, "Almaty")
    vdb.set_volunteer_status(vol_id, "approved")  # KYC gate (§58)

    # Two recipients each reserve one delivery unit → lot.quantity drops to 0.
    for i in range(2):
        nid = create_needy(f"R{i}", f"+711{i}")
        create_or_update_profile(nid, "Addr", 1, "Apples", "Normal", city="Almaty")
        create_ticket(nid, "Apples", "Addr", 43.2, 76.9, lot_id=lot_id, self_pickup=False)

    assert shop_db.get_lot_by_id(lot_id)["quantity"] == 0.0

    # Claim the lot; route fits only ONE stop, so one delivery ticket is assigned
    # and the other is cancelled with its unit NOT returned (lot is 'taken').
    start_route(vol_id, vschemas.StartRouteRequest(lot_id=lot_id, max_stops=1), current_user=ADMIN)

    lot = shop_db.get_lot_by_id(lot_id)
    assert lot["status"] == "taken"
    assert lot["quantity"] == 0.0  # leaked unit not yet reconciled

    # Force the route past the timeout and run the reassign tick.
    with get_db_cursor() as cur:
        cur.execute(
            "UPDATE volunteer_routes SET started_at = CURRENT_TIMESTAMP - INTERVAL '120 minutes', "
            "last_activity_at = NULL WHERE lot_id = %s",
            (lot_id,),
        )
    assert background.reassign_tick(timeout_minutes=60) == 1

    lot = shop_db.get_lot_by_id(lot_id)
    assert lot["status"] == "active"
    # One reopened route ticket still holds 1 unit; the cancelled co-lot unit
    # was reconciled back. Pre-fix this asserted-against value would be 0.
    assert lot["quantity"] == 1.0


@pytest.mark.skipif(not _db_reachable(), reason="Database not reachable")
def test_reassign_tick_subtracts_fulfilled_on_revert(db_setup):
    """BUG-R1: a fulfilled (delivered) unit must NOT reappear as phantom quantity.

    Lot of 3 units, three delivery reservations (quantity -> 0). Volunteer claims
    with max_stops=3 so all three tickets are assigned. One ticket is marked
    'fulfilled' (delivery happened). The route times out; reassign_tick reopens
    the two still-assigned tickets and reverts the lot. Truth: 1 delivered +
    2 reserved = 0 available. Pre-fix the revert SQL omitted 'fulfilled', giving
    quantity = 3 - 2 = 1 (a phantom unit -> the lot reappears on the map). With
    the fix quantity == 0 and the lot stays off the map.
    """
    shop_id = shop_db.create_shop("Shop", "+7123", 43.2, 76.9, "Almaty")
    lot_id = shop_db.create_lot(shop_id, "3 Apples", 3.0, "2026-12-31", None, "Address")

    vol_id = vdb.create_volunteer("Vol", "+700", 43.21, 76.91, "Almaty")
    vdb.set_volunteer_status(vol_id, "approved")  # KYC gate (§58)

    ticket_ids = []
    for i in range(3):
        nid = create_needy(f"R{i}", f"+712{i}")
        create_or_update_profile(nid, "Addr", 1, "Apples", "Normal", city="Almaty")
        ticket_ids.append(
            create_ticket(nid, "Apples", "Addr", 43.2, 76.9, lot_id=lot_id, self_pickup=False)
        )

    assert shop_db.get_lot_by_id(lot_id)["quantity"] == 0.0

    # Claim with room for ALL three stops -> all three tickets assigned.
    start_route(vol_id, vschemas.StartRouteRequest(lot_id=lot_id, max_stops=3), current_user=ADMIN)
    assert shop_db.get_lot_by_id(lot_id)["status"] == "taken"

    # Simulate one delivery: mark one assigned ticket 'fulfilled'.
    with get_db_cursor() as cur:
        cur.execute("UPDATE tickets SET status = 'fulfilled' WHERE id = %s", (ticket_ids[0],))

    # Force the route past the timeout and run the reassign tick.
    with get_db_cursor() as cur:
        cur.execute(
            "UPDATE volunteer_routes SET started_at = CURRENT_TIMESTAMP - INTERVAL '120 minutes', "
            "last_activity_at = NULL WHERE lot_id = %s",
            (lot_id,),
        )
    assert background.reassign_tick(timeout_minutes=60) == 1

    lot = shop_db.get_lot_by_id(lot_id)
    assert lot["status"] == "active"
    # 1 fulfilled + 2 reopened reservations = 3 units accounted -> 0 available.
    # Pre-fix (fulfilled omitted) this would be 1.0, a phantom unit on the map.
    assert lot["quantity"] == 0.0


@pytest.mark.skipif(not _db_reachable(), reason="Database not reachable")
def test_reassign_tick_skips_route_when_revert_fails(db_setup, monkeypatch):
    """BUG-R2: if the lot revert raises, the route must stay 'in_progress' (and
    the lot 'taken') so the next tick retries it — it must NOT be closed.

    Pre-fix the revert sat in `try/except: pass` and the route was closed to
    'timed_out' unconditionally, so a swallowed revert error stranded the lot in
    'taken' forever. The fix wraps the per-route work in a SAVEPOINT and rolls it
    back on failure, leaving the route untouched for the next tick.
    """
    shop_id = shop_db.create_shop("Shop", "+7123", 43.2, 76.9, "Almaty")
    lot_id = shop_db.create_lot(shop_id, "1 Apple", 1.0, "2026-12-31", None, "Address")

    vol_id = vdb.create_volunteer("Vol", "+700", 43.21, 76.91, "Almaty")
    vdb.set_volunteer_status(vol_id, "approved")  # KYC gate (§58)

    nid = create_needy("R", "+7110")
    create_or_update_profile(nid, "Addr", 1, "Apples", "Normal", city="Almaty")
    create_ticket(nid, "Apples", "Addr", 43.2, 76.9, lot_id=lot_id, self_pickup=False)

    start_route(vol_id, vschemas.StartRouteRequest(lot_id=lot_id, max_stops=1), current_user=ADMIN)
    assert shop_db.get_lot_by_id(lot_id)["status"] == "taken"

    with get_db_cursor() as cur:
        cur.execute(
            "UPDATE volunteer_routes SET started_at = CURRENT_TIMESTAMP - INTERVAL '120 minutes', "
            "last_activity_at = NULL WHERE lot_id = %s",
            (lot_id,),
        )

    # Inject a failing revert: invalid SQL raises inside the SAVEPOINT.
    monkeypatch.setattr(
        background, "_LOT_REVERT_SQL",
        "UPDATE lots SET nonexistent_column = 1 WHERE id = %s AND status = 'taken'",
    )
    # The failing route is skipped, not counted, and left untouched.
    assert background.reassign_tick(timeout_minutes=60) == 0
    with get_db_cursor() as cur:
        cur.execute("SELECT status FROM volunteer_routes WHERE lot_id = %s", (lot_id,))
        assert cur.fetchone()["status"] == "in_progress"
    assert shop_db.get_lot_by_id(lot_id)["status"] == "taken"

    # Restore the real SQL: the next tick now processes the still-open route.
    monkeypatch.undo()
    assert background.reassign_tick(timeout_minutes=60) == 1
    assert shop_db.get_lot_by_id(lot_id)["status"] == "active"
