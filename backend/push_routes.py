"""Subscribe/unsubscribe endpoints for Web Push (VAPID) and FCM (native Android)."""
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel

from backend import push_service
from backend.auth import get_current_user
from backend.database import get_db_cursor

router = APIRouter(prefix="/push", tags=["push"])


class SubscriptionIn(BaseModel):
    endpoint: str
    keys: dict  # {p256dh, auth} — the browser's PushSubscription JSON shape


class UnsubscribeIn(BaseModel):
    endpoint: str


class FcmRegisterIn(BaseModel):
    token: str
    role: str
    related_id: Optional[int] = None


class FcmUnregisterIn(BaseModel):
    token: str


def _user_id(current_user: dict) -> int:
    with get_db_cursor() as cur:
        cur.execute("SELECT id FROM users WHERE username = %s", (current_user.get("sub"),))
        row = cur.fetchone()
    if not row:
        raise HTTPException(status_code=404, detail="User not found")
    return row["id"]


def _user_identity(current_user: dict) -> dict:
    with get_db_cursor() as cur:
        cur.execute(
            "SELECT id, role, related_id FROM users WHERE username = %s",
            (current_user.get("sub"),),
        )
        row = cur.fetchone()
    if not row:
        raise HTTPException(status_code=404, detail="User not found")
    return row


@router.get("/public_key")
def public_key():
    """Frontend probes this to decide whether to show the subscribe toggle."""
    if not push_service.is_configured():
        raise HTTPException(status_code=503, detail="Web Push не настроен (нет VAPID-ключей)")
    return {"key": push_service.VAPID_PUBLIC_KEY}


@router.post("/subscribe")
def subscribe(payload: SubscriptionIn, current_user: dict = Depends(get_current_user)):
    if not push_service.is_configured():
        raise HTTPException(status_code=503, detail="Web Push не настроен")
    p256dh = (payload.keys or {}).get("p256dh")
    auth = (payload.keys or {}).get("auth")
    if not payload.endpoint or not p256dh or not auth:
        raise HTTPException(status_code=400, detail="Неполная подписка")
    push_service.save_subscription(_user_id(current_user), payload.endpoint, p256dh, auth)
    return {"ok": True}


@router.post("/unsubscribe")
def unsubscribe(payload: UnsubscribeIn, current_user: dict = Depends(get_current_user)):
    push_service.delete_subscription(_user_id(current_user), payload.endpoint)
    return {"ok": True}


@router.post("/fcm/register")
def fcm_register(payload: FcmRegisterIn, current_user: dict = Depends(get_current_user)):
    """Register a native Android device's FCM token. The (role, related_id) the
    client sends must match the authenticated account — otherwise a logged-in
    user could route another account's notifications to their own device."""
    if not payload.token:
        raise HTTPException(status_code=400, detail="Пустой FCM-токен")
    me = _user_identity(current_user)
    if payload.role != me["role"] or payload.related_id != me["related_id"]:
        raise HTTPException(status_code=403, detail="role/related_id не совпадают с аккаунтом")
    push_service.save_fcm_token(me["id"], payload.token, me["role"], me["related_id"])
    return {"ok": True}


@router.post("/fcm/unregister")
def fcm_unregister(payload: FcmUnregisterIn, current_user: dict = Depends(get_current_user)):
    push_service.delete_fcm_token(_user_id(current_user), payload.token)
    return {"ok": True}
