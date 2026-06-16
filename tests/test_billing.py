import threading
import time

import pytest
from fastapi import HTTPException

from backend import billing
from backend.database import get_conn


def test_basic_plan_has_no_ocr_or_esg():
    assert not billing.PLANS["basic"]["ocr"]
    assert not billing.PLANS["basic"]["esg"]
    assert billing.PLANS["pro"]["ocr"] and billing.PLANS["enterprise"]["esg"]


def test_require_feature_blocks_basic(monkeypatch):
    monkeypatch.setattr(billing, "get_shop_plan", lambda shop_id: "basic")
    with pytest.raises(HTTPException) as exc:
        billing.require_feature(1, "ocr")
    assert exc.value.status_code == 402  # payment required, not 403


def test_require_feature_allows_pro(monkeypatch):
    monkeypatch.setattr(billing, "get_shop_plan", lambda shop_id: "pro")
    assert billing.require_feature(1, "ocr") == "pro"


def test_lot_quota_exhausted(monkeypatch):
    monkeypatch.setattr(billing, "get_shop_plan", lambda shop_id: "basic")
    monkeypatch.setattr(billing, "lots_created_this_month", lambda shop_id: 20)
    with pytest.raises(HTTPException) as exc:
        billing.check_lot_quota(1)
    assert exc.value.status_code == 402


def test_lot_quota_below_limit(monkeypatch):
    monkeypatch.setattr(billing, "get_shop_plan", lambda shop_id: "basic")
    monkeypatch.setattr(billing, "lots_created_this_month", lambda shop_id: 19)
    billing.check_lot_quota(1)  # no exception


def test_unlimited_plan_never_counts(monkeypatch):
    monkeypatch.setattr(billing, "get_shop_plan", lambda shop_id: "pro")

    def _fail(shop_id):
        raise AssertionError("must not hit the DB for an unlimited plan")

    monkeypatch.setattr(billing, "lots_created_this_month", _fail)
    billing.check_lot_quota(1)


# ── lot_quota_guard (TOCTOU serialization, BUG-3) ────────────────────────────


class _FakeCursor:
    """Minimal RealDictCursor stand-in: serves a fixed COUNT, no real DB."""

    def __init__(self, count):
        self._count = count
        self._row = None

    def execute(self, sql, params=None):
        # The advisory-lock call returns nothing useful; only the COUNT matters.
        if "COUNT(*)" in sql:
            self._row = {"n": self._count}
        else:
            self._row = None

    def fetchone(self):
        return self._row


class _FakeConn:
    def __init__(self, count):
        self._count = count
        self.committed = False
        self.rolled_back = False
        self.closed = False

    def cursor(self, cursor_factory=None):
        return _FakeCursor(self._count)

    def commit(self):
        self.committed = True

    def rollback(self):
        self.rolled_back = True

    def close(self):
        self.closed = True


def test_lot_quota_guard_raises_when_over_limit(monkeypatch):
    """Over the monthly limit, the guard must 402 before yielding to the insert."""
    monkeypatch.setattr(billing, "get_shop_plan", lambda shop_id: "basic")
    fake = _FakeConn(count=20)
    monkeypatch.setattr(billing, "get_conn", lambda: fake)

    entered = False
    with pytest.raises(HTTPException) as exc:
        with billing.lot_quota_guard(1):
            entered = True  # must never run — quota is exhausted
    assert exc.value.status_code == 402
    assert not entered
    assert fake.rolled_back and not fake.committed
    assert fake.closed


def test_lot_quota_guard_allows_and_commits_under_limit(monkeypatch):
    """Under the limit, the guard yields to the insert then commits (releasing the lock)."""
    monkeypatch.setattr(billing, "get_shop_plan", lambda shop_id: "basic")
    fake = _FakeConn(count=19)
    monkeypatch.setattr(billing, "get_conn", lambda: fake)

    entered = False
    with billing.lot_quota_guard(1):
        entered = True
    assert entered
    assert fake.committed and not fake.rolled_back
    assert fake.closed


def test_lot_quota_guard_unlimited_plan_skips_connection(monkeypatch):
    """Unlimited plans take no lock/connection at all."""
    monkeypatch.setattr(billing, "get_shop_plan", lambda shop_id: "pro")

    def _boom():
        raise AssertionError("unlimited plan must not open a connection")

    monkeypatch.setattr(billing, "get_conn", _boom)
    with billing.lot_quota_guard(1):
        pass  # no exception, no connection


def _db_reachable():
    try:
        get_conn().close()
        return True
    except Exception:
        return False


@pytest.mark.skipif(not _db_reachable(), reason="Database not reachable")
def test_lot_quota_guard_serializes_concurrent_creates():
    """Two concurrent creators at the limit boundary: the advisory lock must
    serialize them so only the allowed number of lots gets created.

    Requires a live Postgres (skipped otherwise, like the other DB tests)."""
    from backend.database import init_common_db, get_db_cursor
    from backend.shop import db as shop_db
    from backend.shop.db import init_db as shop_init

    init_common_db()
    shop_init()
    with get_db_cursor() as cur:
        cur.execute("DELETE FROM lots")
        cur.execute("DELETE FROM shops")

    shop_id = shop_db.create_shop("Quota Shop", "+7123", 43.2, 76.9, "Almaty")
    limit = billing.PLANS["basic"]["monthly_lot_limit"]
    # Pre-fill to one below the limit so exactly one more lot is allowed.
    for _ in range(limit - 1):
        shop_db.create_lot(shop_id, "x", 1.0, "2026-12-31", None, "addr")

    results = []
    barrier = threading.Barrier(2)

    def worker():
        barrier.wait()
        try:
            with billing.lot_quota_guard(shop_id):
                shop_db.create_lot(shop_id, "race", 1.0, "2026-12-31", None, "addr")
            results.append("ok")
        except HTTPException as exc:
            results.append(exc.status_code)

    threads = [threading.Thread(target=worker) for _ in range(2)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    assert results.count("ok") == 1
    assert results.count(402) == 1
    with get_db_cursor() as cur:
        cur.execute("SELECT COUNT(*) AS n FROM lots WHERE shop_id = %s", (shop_id,))
        assert cur.fetchone()["n"] == limit
