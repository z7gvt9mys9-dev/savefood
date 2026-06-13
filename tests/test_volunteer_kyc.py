"""Volunteer identity KYC (§58): new volunteers start 'pending' and cannot claim
routes until approved; the AI scorer and moderation flow mirror the needy KYC.
"""
import pytest

from backend.shop import db as shop_db
from backend.shop.db import init_db as shop_init
from backend.needy.db import (
    create_ticket, create_needy, create_or_update_profile, init_db as needy_init,
)
from backend.volunteer import db as vdb
from backend.volunteer.routes import start_route
from backend.volunteer import schemas as vschemas
from backend import kyc_service
from backend.database import get_db_cursor, init_common_db, init_ticket_extensions, get_conn
from fastapi import HTTPException


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


# ── Scoring (pure, no DB / no network) ───────────────────────────────────────

def test_score_volunteer_rejects_non_id_document():
    r = kyc_service._score_volunteer({"is_document": True, "is_id_document": False, "summary": "чек"})
    assert r["verdict"] == "likely_fraud"
    assert r["score"] == 0.0


def test_score_volunteer_clean_id_is_ok():
    r = kyc_service._score_volunteer({
        "is_document": True, "is_id_document": True, "name_matches": True,
        "legible": True, "tampering_signs": False, "document_type": "Удостоверение личности РК",
        "summary": "Документ читаем, имя совпадает.",
    })
    assert r["verdict"] == "likely_ok"
    assert r["score"] >= kyc_service.KYC_OK_THRESHOLD


def test_score_volunteer_tampering_is_fraud():
    r = kyc_service._score_volunteer({
        "is_document": True, "is_id_document": True, "name_matches": False,
        "legible": True, "tampering_signs": True, "tampering_reason": "скриншот",
    })
    assert r["verdict"] == "likely_fraud"


def test_auto_approve_volunteer_gated_by_env_and_confidence():
    assert kyc_service.should_auto_approve_volunteer("likely_ok", 0.9, enabled=True, threshold=0.85) is True
    assert kyc_service.should_auto_approve_volunteer("likely_ok", 0.8, enabled=True, threshold=0.85) is False
    assert kyc_service.should_auto_approve_volunteer("review", 0.99, enabled=True, threshold=0.85) is False
    assert kyc_service.should_auto_approve_volunteer("likely_ok", 0.99, enabled=False, threshold=0.85) is False


# ── Gate + lifecycle (DB) ────────────────────────────────────────────────────

def test_new_volunteer_starts_pending():
    vid = vdb.create_volunteer("V", "+7", 43.2, 76.9, "Almaty")
    assert vdb.get_volunteer_by_id(vid)["status"] == "pending"


def test_register_endpoint_sets_pending_and_survives_grandfather():
    """Regression (§58): the public register endpoint must set status='pending'.
    If it left status NULL, the init_db grandfather UPDATE (NULL → 'approved')
    would silently re-approve fresh accounts on the next startup, bypassing KYC.
    """
    from backend.volunteer.routes import register

    vol = vschemas.VolunteerCreate(
        name="V", contact="+7", lat=43.2, lon=76.9, city="Almaty",
        username="reg_kyc_user", password="supersecret123",
    )
    # register is rate-limited; call the undecorated function (request unused).
    res = register.__wrapped__(None, vol)
    vid = res["id"]
    assert vdb.get_volunteer_by_id(vid)["status"] == "pending"

    # Re-run the grandfather UPDATE: it must NOT re-approve a pending account.
    vdb.init_db()
    assert vdb.get_volunteer_by_id(vid)["status"] == "pending"


def _lot_with_delivery_ticket():
    shop_id = shop_db.create_shop("Shop", "+7123", 43.2, 76.9, "Almaty")
    lot_id = shop_db.create_lot(shop_id, "Apples", 5.0, "2026-12-31", None, "Address")
    nid = create_needy("R", "+7111")
    create_or_update_profile(nid, "Addr", 1, "Apples", "Normal", city="Almaty")
    create_ticket(nid, "Apples", "Addr", 43.2, 76.9, lot_id=lot_id, self_pickup=False)
    return lot_id


def test_pending_volunteer_cannot_start_route():
    lot_id = _lot_with_delivery_ticket()
    vid = vdb.create_volunteer("V", "+7", 43.21, 76.91, "Almaty")  # pending
    # Owner token (not admin) must be blocked by the KYC gate.
    owner = {"role": "volunteer", "related_id": vid}
    with pytest.raises(HTTPException) as exc:
        start_route(vid, vschemas.StartRouteRequest(lot_id=lot_id, max_stops=5), current_user=owner)
    assert exc.value.status_code == 403


def test_approved_volunteer_can_start_route():
    lot_id = _lot_with_delivery_ticket()
    vid = vdb.create_volunteer("V", "+7", 43.21, 76.91, "Almaty")
    vdb.set_volunteer_status(vid, "approved")
    owner = {"role": "volunteer", "related_id": vid}
    res = start_route(vid, vschemas.StartRouteRequest(lot_id=lot_id, max_stops=5), current_user=owner)
    assert res["route_id"]


def test_set_status_conditional_guard_blocks_stale_flip():
    """TOCTOU guard (§58): the auto-KYC thread approves only via expected_status.
    If a moderator rejected during the AI call, the conditional flip must be a
    no-op (returns None) instead of clobbering the rejection.
    """
    vid = vdb.create_volunteer("V", "+7", 43.2, 76.9, "Almaty")  # pending
    vdb.set_volunteer_status(vid, "rejected")  # moderator decides first
    # Auto-KYC tries to approve, but only if still 'pending' — must not win.
    result = vdb.set_volunteer_status(vid, "approved", expected_status="pending")
    assert result is None
    assert vdb.get_volunteer_by_id(vid)["status"] == "rejected"


def test_set_status_clears_document_reference():
    vid = vdb.create_volunteer("V", "+7", 43.2, 76.9, "Almaty")
    vdb.set_volunteer_document(vid, "/volunteer_kyc/abc.jpg")
    assert vdb.get_volunteer_by_id(vid)["document"] == "/volunteer_kyc/abc.jpg"
    vdb.set_volunteer_status(vid, "approved")
    assert vdb.get_volunteer_by_id(vid)["document"] is None
