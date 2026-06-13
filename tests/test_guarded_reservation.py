import pytest
from datetime import datetime, timezone
from backend.shop import db as shop_db
from backend.needy.db import create_ticket, TicketCreateError, create_needy, create_or_update_profile, init_db as needy_init
from backend.shop.db import init_db as shop_init
from backend.database import get_db_cursor, init_common_db, get_conn

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
    with get_db_cursor() as cur:
        cur.execute("DELETE FROM tickets")
        cur.execute("DELETE FROM lots")
        cur.execute("DELETE FROM shops")
        cur.execute("DELETE FROM needy_profile")
        cur.execute("DELETE FROM needy")
        cur.execute("DELETE FROM users")

def test_guarded_return_success():
    # 1. Create a shop and a lot with 2 units
    shop_id = shop_db.create_shop("Shop", "+7123", 43.2, 76.9, "Almaty")
    lot_id = shop_db.create_lot(shop_id, "2 Apples", 2.0, "2026-12-31", None, "Address")

    # 2. Recipient creates a ticket
    needy_id = create_needy("Recipient", "+7111")
    create_or_update_profile(needy_id, "Addr", 1, "Apples", "Normal", city="Almaty")
    tid = create_ticket(needy_id, "Apples", "Addr", 43.2, 76.9, lot_id=lot_id, self_pickup=True)

    lot = shop_db.get_lot_by_id(lot_id)
    assert lot['quantity'] == 1.0

    # 3. Cancel ticket while lot is still active
    from backend.needy.db import cancel_ticket
    assert cancel_ticket(tid) is True

    # 4. Quantity should be returned
    lot = shop_db.get_lot_by_id(lot_id)
    assert lot['quantity'] == 2.0

def test_guarded_return_blocked_by_status():
    # 1. Create a shop and a lot with 2 units
    shop_id = shop_db.create_shop("Shop", "+7123", 43.2, 76.9, "Almaty")
    lot_id = shop_db.create_lot(shop_id, "2 Apples", 2.0, "2026-12-31", None, "Address")

    # 2. Recipient creates a ticket
    needy_id = create_needy("Recipient", "+7111")
    create_or_update_profile(needy_id, "Addr", 1, "Apples", "Normal", city="Almaty")
    tid = create_ticket(needy_id, "Apples", "Addr", 43.2, 76.9, lot_id=lot_id, self_pickup=True)

    lot = shop_db.get_lot_by_id(lot_id)
    assert lot['quantity'] == 1.0

    # 3. Volunteer takes the lot
    with get_db_cursor() as cur:
        cur.execute("UPDATE lots SET status = 'taken' WHERE id = %s", (lot_id,))

    # 4. Cancel ticket
    from backend.needy.db import cancel_ticket
    # In my implementation of cancel_ticket in db.py (which I read earlier),
    # it doesn't check lot status for returning quantity, it just executes the UPDATE.
    # The UPDATE lot ... WHERE status = 'active' will result in 0 rows updated.
    assert cancel_ticket(tid) is True

    # 5. Quantity should NOT be returned because lot is 'taken'
    lot = shop_db.get_lot_by_id(lot_id)
    assert lot['quantity'] == 1.0

def test_volunteer_map_filter():
    # 1. Create a shop and a lot with 1 unit
    shop_id = shop_db.create_shop("Filter Shop", "+7123", 43.2, 76.9, "Almaty")
    lot_id = shop_db.create_lot(shop_id, "1 Apple", 1.0, "2026-12-31", None, "Address")

    # 2. Check it appears on map (mocking context or just calling the logic)
    # Since I'm testing the SQL, I'll just check the query logic if I can.
    # But better to just run the query in the test.
    with get_db_cursor() as cur:
        cur.execute("""
            SELECT l.id FROM shops s
            JOIN lots l ON s.id = l.shop_id
            WHERE l.status = 'active' AND l.quantity > 0
            AND (l.expiry_date IS NULL OR l.expiry_date::date > CURRENT_DATE + INTERVAL '1 day')
            AND l.id = %s
        """, (lot_id,))
        assert cur.fetchone() is not None

    # 3. Reserve the only unit
    needy_id = create_needy("Recipient", "+7111")
    create_or_update_profile(needy_id, "Addr", 1, "Apples", "Normal", city="Almaty")
    create_ticket(needy_id, "Apples", "Addr", 43.2, 76.9, lot_id=lot_id, self_pickup=True)

    # 4. Check it disappeared from map
    with get_db_cursor() as cur:
        cur.execute("""
            SELECT l.id FROM shops s
            JOIN lots l ON s.id = l.shop_id
            WHERE l.status = 'active' AND l.quantity > 0
            AND (l.expiry_date IS NULL OR l.expiry_date::date > CURRENT_DATE + INTERVAL '1 day')
            AND l.id = %s
        """, (lot_id,))
        assert cur.fetchone() is None
