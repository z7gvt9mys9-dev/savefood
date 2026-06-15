import pytest

from backend import push_service
from backend.push_service import strip_html


def test_strip_html_removes_telegram_markup():
    assert strip_html("Волонтёр <b>Алексей</b> взял ваш лот") == "Волонтёр Алексей взял ваш лот"
    assert strip_html("🛒 <i>тест</i>") == "🛒 тест"


def test_strip_html_plain_text_untouched():
    assert strip_html("обычный текст") == "обычный текст"
    assert strip_html("") == ""
    assert strip_html(None) == ""


# ── FCM config gate (pure, no DB / no network) ───────────────────────────────

def test_fcm_disabled_by_default():
    # The channel is opt-in: without FCM_ENABLED nothing should dispatch.
    assert push_service.fcm_is_configured() is False


def test_fcm_is_configured_requires_enabled_project_and_creds(monkeypatch):
    monkeypatch.setattr(push_service, "FCM_ENABLED", True)
    monkeypatch.setattr(push_service, "FCM_PROJECT_ID", "proj-123")
    monkeypatch.setattr(push_service, "FCM_CREDENTIALS_JSON", '{"x":1}')
    monkeypatch.setattr(push_service, "FCM_CREDENTIALS_FILE", "")
    assert push_service.fcm_is_configured() is True

    # Missing any one piece → off.
    monkeypatch.setattr(push_service, "FCM_PROJECT_ID", "")
    assert push_service.fcm_is_configured() is False
    monkeypatch.setattr(push_service, "FCM_PROJECT_ID", "proj-123")
    monkeypatch.setattr(push_service, "FCM_CREDENTIALS_JSON", "")
    assert push_service.fcm_is_configured() is False
    monkeypatch.setattr(push_service, "FCM_ENABLED", False)
    monkeypatch.setattr(push_service, "FCM_CREDENTIALS_JSON", '{"x":1}')
    assert push_service.fcm_is_configured() is False


class _InlineThread:
    """Runs the target synchronously so notify_role's dispatch is observable
    without real threads, a DB, or the network."""

    def __init__(self, target, args=(), daemon=None):
        self._target, self._args = target, args

    def start(self):
        self._target(*self._args)


def test_notify_role_skips_fcm_when_disabled(monkeypatch):
    sent = []
    monkeypatch.setattr(push_service, "is_configured", lambda: False)
    monkeypatch.setattr(push_service, "fcm_is_configured", lambda: False)
    monkeypatch.setattr(push_service.threading, "Thread", _InlineThread)
    monkeypatch.setattr(push_service, "_send_fcm_to_role_blocking", lambda *a: sent.append(a))

    push_service.notify_role("volunteer", 7, "<b>привет</b>", url="/v")
    assert sent == []  # flag off ⇒ no FCM send


def test_notify_role_dispatches_fcm_when_enabled(monkeypatch):
    sent = []
    # Web Push off so the only observable dispatch is FCM (no DB lookup needed).
    monkeypatch.setattr(push_service, "is_configured", lambda: False)
    monkeypatch.setattr(push_service, "fcm_is_configured", lambda: True)
    monkeypatch.setattr(push_service.threading, "Thread", _InlineThread)
    monkeypatch.setattr(push_service, "_send_fcm_to_role_blocking", lambda *a: sent.append(a))

    push_service.notify_role("volunteer", 7, "<b>привет</b>", url="/v")
    # HTML stripped, fixed title, role/related_id/url forwarded verbatim.
    assert sent == [("volunteer", 7, "SaveFood", "привет", "/v")]


def test_notify_role_no_related_id_is_noop(monkeypatch):
    sent = []
    monkeypatch.setattr(push_service, "fcm_is_configured", lambda: True)
    monkeypatch.setattr(push_service.threading, "Thread", _InlineThread)
    monkeypatch.setattr(push_service, "_send_fcm_to_role_blocking", lambda *a: sent.append(a))
    push_service.notify_role("volunteer", None, "hi")
    assert sent == []


# ── register / unregister endpoints (need a database) ────────────────────────

from backend.database import get_conn, get_db_cursor, init_common_db, create_user  # noqa: E402
from backend.push_routes import (  # noqa: E402
    fcm_register, fcm_unregister, FcmRegisterIn, FcmUnregisterIn,
)
from fastapi import HTTPException  # noqa: E402


def _db_reachable():
    try:
        get_conn().close()
        return True
    except Exception:
        return False


pytestmark_db = pytest.mark.skipif(not _db_reachable(), reason="Database not reachable")

_USER = "fcm_test_user"
_ROLE = "volunteer"
_RID = 999777


@pytest.fixture()
def fcm_user():
    init_common_db()
    with get_db_cursor() as cur:
        cur.execute("DELETE FROM fcm_tokens WHERE role = %s AND related_id = %s", (_ROLE, _RID))
        cur.execute("DELETE FROM users WHERE username = %s", (_USER,))
    uid = create_user(_USER, "hashed", _ROLE, _RID)
    yield uid
    with get_db_cursor() as cur:
        cur.execute("DELETE FROM fcm_tokens WHERE user_id = %s", (uid,))
        cur.execute("DELETE FROM users WHERE id = %s", (uid,))


@pytestmark_db
def test_fcm_register_then_unregister(fcm_user):
    current = {"sub": _USER}
    assert fcm_register(FcmRegisterIn(token="tok-A", role=_ROLE, related_id=_RID), current) == {"ok": True}
    with get_db_cursor() as cur:
        cur.execute("SELECT user_id, role, related_id FROM fcm_tokens WHERE token = %s", ("tok-A",))
        row = cur.fetchone()
    assert row["user_id"] == fcm_user and row["role"] == _ROLE and row["related_id"] == _RID

    assert fcm_unregister(FcmUnregisterIn(token="tok-A"), current) == {"ok": True}
    with get_db_cursor() as cur:
        cur.execute("SELECT 1 FROM fcm_tokens WHERE token = %s", ("tok-A",))
        assert cur.fetchone() is None


@pytestmark_db
def test_fcm_register_is_idempotent_on_token(fcm_user):
    current = {"sub": _USER}
    fcm_register(FcmRegisterIn(token="tok-B", role=_ROLE, related_id=_RID), current)
    fcm_register(FcmRegisterIn(token="tok-B", role=_ROLE, related_id=_RID), current)  # re-register
    with get_db_cursor() as cur:
        cur.execute("SELECT COUNT(*) AS n FROM fcm_tokens WHERE token = %s", ("tok-B",))
        assert cur.fetchone()["n"] == 1  # UNIQUE(token) upsert, not a duplicate


@pytestmark_db
def test_fcm_register_rejects_mismatched_identity(fcm_user):
    current = {"sub": _USER}
    with pytest.raises(HTTPException) as exc:
        fcm_register(FcmRegisterIn(token="tok-C", role="shop", related_id=_RID), current)
    assert exc.value.status_code == 403
    with pytest.raises(HTTPException) as exc:
        fcm_register(FcmRegisterIn(token="tok-C", role=_ROLE, related_id=123456), current)
    assert exc.value.status_code == 403
