"""Enterprise partner REST API (роадмап v2.2): lots automation for ERP.

Two routers:
- `router` (/api/v1/…)  — authenticated by X-API-Key; the key maps to a shop.
- `manage_router`       — JWT-authenticated key/webhook management for the
                          shop dashboard (create / list / revoke).

Key format: `sf_live_<48 hex>`. Only the sha256 hash is stored; the full
secret is returned exactly once at creation. Webhook secrets are stored as-is
(they sign outgoing payloads — we need the plaintext to compute HMACs).
"""
import hashlib
import secrets
from datetime import date, datetime, timezone
from typing import List, Optional, Tuple

from fastapi import APIRouter, Depends, Header, HTTPException
from pydantic import BaseModel

from backend import billing, esg, needs_match, webhook_service
from backend.auth import ensure_owner_or_admin, get_current_user
from backend.database import get_db_cursor
from backend.receipt_service import LOT_CATEGORIES
from backend.shop import db as shop_db

KEY_PREFIX = "sf_live_"
PREFIX_LEN = len(KEY_PREFIX) + 6  # shown in lists: "sf_live_a1b2c3"


def generate_api_key() -> Tuple[str, str, str]:
    """→ (full secret, display prefix, sha256 hash). Secret is shown once."""
    secret = KEY_PREFIX + secrets.token_hex(24)
    return secret, secret[:PREFIX_LEN], hash_key(secret)


def hash_key(key: str) -> str:
    return hashlib.sha256((key or "").encode()).hexdigest()


# ── API-key authentication ───────────────────────────────────────────────────

def get_api_shop(x_api_key: Optional[str] = Header(None, alias="X-API-Key")) -> int:
    if not x_api_key:
        raise HTTPException(status_code=401, detail="Заголовок X-API-Key обязателен")
    with get_db_cursor() as cur:
        cur.execute(
            "SELECT id, shop_id FROM api_keys WHERE key_hash = %s AND NOT revoked",
            (hash_key(x_api_key),),
        )
        row = cur.fetchone()
        if not row:
            raise HTTPException(status_code=401, detail="Неверный или отозванный API-ключ")
        cur.execute(
            "UPDATE api_keys SET last_used_at = %s WHERE id = %s",
            (datetime.now(timezone.utc), row["id"]),
        )
    # Plan downgrade revokes access without touching the keys themselves.
    billing.require_feature(row["shop_id"], "api")
    return row["shop_id"]


router = APIRouter(prefix="/api/v1", tags=["partner-api"])


class ApiLotIn(BaseModel):
    description: str
    quantity: int
    category: Optional[str] = None
    expiry_date: Optional[date] = None
    address: Optional[str] = None
    time_slot: Optional[str] = None
    comment: Optional[str] = None


@router.get("/ping")
def ping(shop_id: int = Depends(get_api_shop)):
    return {"ok": True, "shop_id": shop_id, "plan": billing.get_shop_plan(shop_id)}


@router.get("/lots")
def api_list_lots(
    status: Optional[str] = None,
    limit: int = 50,
    offset: int = 0,
    shop_id: int = Depends(get_api_shop),
):
    limit = max(1, min(200, limit))
    with get_db_cursor() as cur:
        if status:
            cur.execute(
                "SELECT * FROM lots WHERE shop_id = %s AND status = %s ORDER BY created_at DESC LIMIT %s OFFSET %s",
                (shop_id, status, limit, offset),
            )
        else:
            cur.execute(
                "SELECT * FROM lots WHERE shop_id = %s ORDER BY created_at DESC LIMIT %s OFFSET %s",
                (shop_id, limit, offset),
            )
        return [dict(r) for r in cur.fetchall()]


@router.post("/lots")
def api_create_lot(payload: ApiLotIn, shop_id: int = Depends(get_api_shop)):
    """Автолоты из ERP: the «Списание» button on the POS can call this directly."""
    if payload.category is not None and payload.category not in LOT_CATEGORIES:
        raise HTTPException(
            status_code=400,
            detail=f"Неизвестная категория. Допустимые: {', '.join(LOT_CATEGORIES)}",
        )
    if payload.quantity < 1:
        raise HTTPException(status_code=400, detail="quantity должен быть ≥ 1")
    billing.check_lot_quota(shop_id)
    expiry = payload.expiry_date.isoformat() if payload.expiry_date else None
    lot_id = shop_db.create_lot(
        shop_id, payload.description, payload.quantity, expiry, None,
        payload.address, payload.time_slot, payload.category,
        payload.comment or "Создано через партнёрский API",
    )
    needs_match.start_needs_match(lot_id)
    return {"id": lot_id}


@router.delete("/lots/{lot_id}")
def api_delete_lot(lot_id: int, shop_id: int = Depends(get_api_shop)):
    lot = shop_db.get_lot_by_id(lot_id)
    if not lot or lot["shop_id"] != shop_id:
        raise HTTPException(status_code=404, detail="Лот не найден")
    ok = shop_db.delete_lot(lot_id)
    if not ok:
        raise HTTPException(status_code=400, detail="Лот нельзя удалить в текущем статусе")
    return {"ok": True}


@router.get("/esg")
def api_esg(months: int = 12, shop_id: int = Depends(get_api_shop)):
    return esg.shop_report(shop_id, months=months)


# ── JWT management endpoints (shop dashboard) ────────────────────────────────

manage_router = APIRouter(tags=["partner-api-manage"])


def _require_api_plan(shop_id: int, current_user: dict):
    ensure_owner_or_admin(current_user, "shop", shop_id)
    if not shop_db.get_shop_by_id(shop_id):
        raise HTTPException(status_code=404, detail="Shop not found")
    billing.require_feature(shop_id, "api")


@manage_router.post("/shops/{shop_id}/api_keys")
def create_api_key(shop_id: int, current_user: dict = Depends(get_current_user)):
    _require_api_plan(shop_id, current_user)
    secret, prefix, key_hash = generate_api_key()
    with get_db_cursor() as cur:
        cur.execute(
            "INSERT INTO api_keys (shop_id, key_hash, prefix) VALUES (%s, %s, %s) RETURNING id",
            (shop_id, key_hash, prefix),
        )
        key_id = cur.fetchone()["id"]
    # The only moment the full secret ever leaves the server.
    return {"id": key_id, "key": secret, "prefix": prefix}


@manage_router.get("/shops/{shop_id}/api_keys")
def list_api_keys(shop_id: int, current_user: dict = Depends(get_current_user)):
    _require_api_plan(shop_id, current_user)
    with get_db_cursor() as cur:
        cur.execute(
            "SELECT id, prefix, revoked, created_at, last_used_at FROM api_keys WHERE shop_id = %s ORDER BY created_at DESC",
            (shop_id,),
        )
        return [dict(r) for r in cur.fetchall()]


@manage_router.post("/shops/{shop_id}/api_keys/{key_id}/revoke")
def revoke_api_key(shop_id: int, key_id: int, current_user: dict = Depends(get_current_user)):
    _require_api_plan(shop_id, current_user)
    with get_db_cursor() as cur:
        cur.execute(
            "UPDATE api_keys SET revoked = TRUE WHERE id = %s AND shop_id = %s RETURNING id",
            (key_id, shop_id),
        )
        if not cur.fetchone():
            raise HTTPException(status_code=404, detail="Ключ не найден")
    return {"ok": True}


class WebhookIn(BaseModel):
    url: str
    events: List[str] = ["*"]


@manage_router.post("/shops/{shop_id}/webhooks")
def create_webhook(shop_id: int, payload: WebhookIn, current_user: dict = Depends(get_current_user)):
    _require_api_plan(shop_id, current_user)
    if not payload.url.startswith(("http://", "https://")):
        raise HTTPException(status_code=400, detail="URL должен начинаться с http(s)://")
    bad = [e for e in payload.events if e != "*" and e not in webhook_service.EVENTS]
    if bad:
        raise HTTPException(
            status_code=400,
            detail=f"Неизвестные события: {', '.join(bad)}. Допустимые: {', '.join(webhook_service.EVENTS)} или *",
        )
    secret = "whsec_" + secrets.token_hex(24)
    events_csv = ",".join(payload.events or ["*"])
    with get_db_cursor() as cur:
        cur.execute(
            "INSERT INTO webhooks (shop_id, url, secret, events) VALUES (%s, %s, %s, %s) RETURNING id",
            (shop_id, payload.url, secret, events_csv),
        )
        hook_id = cur.fetchone()["id"]
    # Secret is shown once — the partner stores it to verify signatures.
    return {"id": hook_id, "secret": secret, "events": payload.events}


@manage_router.get("/shops/{shop_id}/webhooks")
def list_webhooks(shop_id: int, current_user: dict = Depends(get_current_user)):
    _require_api_plan(shop_id, current_user)
    with get_db_cursor() as cur:
        cur.execute(
            "SELECT id, url, events, active, created_at, last_status, last_delivery_at FROM webhooks WHERE shop_id = %s ORDER BY created_at DESC",
            (shop_id,),
        )
        return [dict(r) for r in cur.fetchall()]


@manage_router.delete("/shops/{shop_id}/webhooks/{hook_id}")
def delete_webhook(shop_id: int, hook_id: int, current_user: dict = Depends(get_current_user)):
    _require_api_plan(shop_id, current_user)
    with get_db_cursor() as cur:
        cur.execute(
            "DELETE FROM webhooks WHERE id = %s AND shop_id = %s RETURNING id",
            (hook_id, shop_id),
        )
        if not cur.fetchone():
            raise HTTPException(status_code=404, detail="Вебхук не найден")
    return {"ok": True}
