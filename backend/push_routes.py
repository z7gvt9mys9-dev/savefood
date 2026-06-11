"""Subscribe/unsubscribe endpoints for Web Push (VAPID)."""
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


def _user_id(current_user: dict) -> int:
    with get_db_cursor() as cur:
        cur.execute("SELECT id FROM users WHERE username = %s", (current_user.get("sub"),))
        row = cur.fetchone()
    if not row:
        raise HTTPException(status_code=404, detail="User not found")
    return row["id"]


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
