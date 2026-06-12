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

def test_atomic_reservation():
    # 1. Create a shop and a lot with 2 units
    shop_id = shop_db.create_shop("Test Shop", "+7123", 43.2, 76.9, "Almaty")
    lot_id = shop_db.create_lot(shop_id, "2 Apples", 2.0, "2026-12-31", None, "Address")
    
    # 2. Create a needy person
    needy_id1 = create_needy("Recipient 1", "+7111")
    create_or_update_profile(needy_id1, "Addr 1", 1, "Apples", "Normal", city="Almaty")
    
    needy_id2 = create_needy("Recipient 2", "+7222")
    create_or_update_profile(needy_id2, "Addr 2", 1, "Apples", "Normal", city="Almaty")

    needy_id3 = create_needy("Recipient 3", "+7333")
    create_or_update_profile(needy_id3, "Addr 3", 1, "Apples", "Normal", city="Almaty")

    # 3. First two reservations should succeed
    create_ticket(needy_id1, "Apples", "Addr 1", 43.2, 76.9, lot_id=lot_id, self_pickup=True)
    create_ticket(needy_id2, "Apples", "Addr 2", 43.2, 76.9, lot_id=lot_id, self_pickup=True)
    
    lot = shop_db.get_lot_by_id(lot_id)
    assert lot['quantity'] == 0
    
    # 4. Third reservation should fail (lot unavailable)
    with pytest.raises(TicketCreateError) as exc:
        create_ticket(needy_id3, "Apples", "Addr 3", 43.2, 76.9, lot_id=lot_id, self_pickup=True)
    assert exc.value.reason == "lot_unavailable"

def test_ttl_and_return():
    # 1. Create a lot with 1 unit
    shop_id = shop_db.create_shop("TTL Shop", "+7123", 43.2, 76.9, "Almaty")
    lot_id = shop_db.create_lot(shop_id, "1 Banana", 1.0, "2026-12-31", None, "Address")
    
    # 2. Create reservation
    needy_id = create_needy("TTL Recipient", "+7444")
    create_or_update_profile(needy_id, "Addr", 1, "Banana", "Normal", city="Almaty")
    tid = create_ticket(needy_id, "Banana", "Addr", 43.2, 76.9, lot_id=lot_id, self_pickup=True)
    
    lot = shop_db.get_lot_by_id(lot_id)
    assert lot['quantity'] == 0
    
    # 3. Manually expire the ticket in DB
    with get_db_cursor() as cur:
        cur.execute("UPDATE tickets SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id = %s", (tid,))
    
    # 4. Run background TTL task
    from backend.background import reservation_ttl_tick
    cancelled = reservation_ttl_tick()
    assert cancelled == 1
    
    # 5. Check lot quantity returned
    lot = shop_db.get_lot_by_id(lot_id)
    assert lot['quantity'] == 1
    
    # 6. Check ticket status
    with get_db_cursor() as cur:
        cur.execute("SELECT status FROM tickets WHERE id = %s", (tid,))
        assert cur.fetchone()['status'] == 'cancelled'

def test_volunteer_takes_all():
    # 1. Lot with 5 units
    shop_id = shop_db.create_shop("Vol Shop", "+7123", 43.2, 76.9, "Almaty")
    lot_id = shop_db.create_lot(shop_id, "5 Units", 5.0, "2026-12-31", None, "Address")
    
    # 2. 1 unit reserved by needy
    n1 = create_needy("N1", "+71")
    create_or_update_profile(n1, "A", 1, "Any", "N", city="Almaty")
    create_ticket(n1, "I", "A", 43.2, 76.9, lot_id=lot_id, self_pickup=True)
    
    lot = shop_db.get_lot_by_id(lot_id)
    assert lot['quantity'] == 4
    
    # 3. Volunteer takes the lot
    shop_db.take_lot(lot_id, "Vol 1")
    
    lot = shop_db.get_lot_by_id(lot_id)
    assert lot['status'] == 'taken'
    assert lot['quantity'] == 0
