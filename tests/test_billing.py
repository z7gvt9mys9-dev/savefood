import pytest
from fastapi import HTTPException

from backend import billing


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
