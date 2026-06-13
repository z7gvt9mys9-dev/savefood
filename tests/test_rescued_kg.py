"""«Спасённые кг» — семантика RESCUED_SQL / RESCUED_KG_SQL без живой БД.

Константы из esg.py — это сырой SQL, общий для всех «rescued»-поверхностей
(esg, impact, /stats, admin). Прогоняем их в in-memory SQLite на минимальных
таблицах lots/tickets: CASE + коррелированный подзапрос портируемы, поэтому
тест ловит регрессию (например, возврат к `initial_quantity` для self-pickup)
в обычном CI, где Postgres недоступен.
"""
import sqlite3

import pytest

from backend.esg import RESCUED_SQL, RESCUED_KG_SQL


@pytest.fixture()
def db():
    conn = sqlite3.connect(":memory:")
    conn.executescript(
        """
        CREATE TABLE lots (
            id INTEGER PRIMARY KEY,
            status TEXT,
            quantity REAL,
            initial_quantity REAL,
            unit_weight_kg REAL
        );
        CREATE TABLE tickets (
            id INTEGER PRIMARY KEY,
            lot_id INTEGER,
            status TEXT,
            quantity REAL
        );
        """
    )
    yield conn
    conn.close()


def _rescued_kg(conn) -> float:
    cur = conn.execute(
        f"SELECT COALESCE(SUM({RESCUED_KG_SQL}), 0) AS kg FROM lots l WHERE {RESCUED_SQL}"
    )
    return cur.fetchone()[0]


def test_confirmed_lot_counts_whole_initial_quantity(db):
    # Delivery flow: shop confirmed transfer of the whole lot → all of it donated.
    db.execute("INSERT INTO lots VALUES (1, 'confirmed', 0, 10, 0.4)")
    assert _rescued_kg(db) == pytest.approx(10 * 0.4)


def test_self_pickup_counts_only_fulfilled_units_not_initial(db):
    # The bug this guards against: a 50-unit active lot with ONE picked-up unit
    # must report 1 unit, not 50.
    db.execute("INSERT INTO lots VALUES (1, 'active', 49, 50, 0.4)")
    db.execute("INSERT INTO tickets VALUES (1, 1, 'fulfilled', 1)")
    db.execute("INSERT INTO tickets VALUES (2, 1, 'open', 1)")  # reserved, not picked up
    assert _rescued_kg(db) == pytest.approx(1 * 0.4)


def test_taken_lot_without_fulfilled_tickets_is_not_rescued(db):
    # 'taken' alone (volunteer pressed the button) must not count — the whole
    # point of methodology v1.1.
    db.execute("INSERT INTO lots VALUES (1, 'taken', 0, 5, 1.0)")
    db.execute("INSERT INTO tickets VALUES (1, 1, 'assigned', 1)")
    assert _rescued_kg(db) == 0


def test_mixed_units_stay_honest(db):
    # 3 fulfilled units of a 0.4 kg item + a confirmed 2 kg lot = 1.2 + 2.0.
    db.execute("INSERT INTO lots VALUES (1, 'active', 7, 10, 0.4)")
    db.executemany(
        "INSERT INTO tickets (id, lot_id, status, quantity) VALUES (?, ?, 'fulfilled', 1)",
        [(1, 1), (2, 1), (3, 1)],
    )
    db.execute("INSERT INTO lots VALUES (2, 'confirmed', 0, 2, 1.0)")
    assert _rescued_kg(db) == pytest.approx(3 * 0.4 + 2 * 1.0)


def test_confirmed_and_fulfilled_do_not_double_count(db):
    # A confirmed delivery lot that also has fulfilled tickets takes the
    # 'confirmed' branch only (whole lot), never confirmed + per-ticket.
    db.execute("INSERT INTO lots VALUES (1, 'confirmed', 0, 5, 1.0)")
    db.executemany(
        "INSERT INTO tickets (id, lot_id, status, quantity) VALUES (?, ?, 'fulfilled', 1)",
        [(1, 1), (2, 1), (3, 1)],
    )
    assert _rescued_kg(db) == pytest.approx(5 * 1.0)
