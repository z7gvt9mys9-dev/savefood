"""Tariff plans and feature gating for shops (SaaS monetization).

Plans live in shops.plan ('basic' | 'pro' | 'enterprise'); payment itself is
handled out-of-band for now (admin flips the plan after an invoice is paid),
so this module is only about entitlements:

- basic:      manual lots only, 20 lots per calendar month
- pro:        OCR receipt parsing, ESG reports, unlimited lots
- enterprise: everything in pro (API connectors are sold/managed manually)
"""
from contextlib import contextmanager
from typing import Optional

from fastapi import HTTPException
from psycopg2.extras import RealDictCursor

from backend.database import get_conn, get_db_cursor

DEFAULT_PLAN = "basic"

# Advisory-lock namespace (first key of pg_advisory_xact_lock) for the monthly
# lot quota. Arbitrary constant, kept distinct from any other advisory lock.
_LOT_QUOTA_LOCK_NS = 0x10720001

PLANS = {
    "basic": {
        "label": "Базовый",
        "monthly_lot_limit": 20,
        "ocr": False,
        "esg": False,
        "api": False,
    },
    "pro": {
        "label": "Профи",
        "monthly_lot_limit": None,
        "ocr": True,
        "esg": True,
        "api": False,
    },
    "enterprise": {
        "label": "Enterprise",
        "monthly_lot_limit": None,
        "ocr": True,
        "esg": True,
        "api": True,
    },
}

# Human-readable feature names for upgrade error messages.
FEATURE_LABELS = {
    "ocr": "Распознавание чеков (OCR)",
    "esg": "ESG-отчёты",
    "api": "Партнёрский API и вебхуки",
}


def get_shop_plan(shop_id: int) -> str:
    with get_db_cursor() as cur:
        cur.execute("SELECT plan FROM shops WHERE id = %s", (shop_id,))
        row = cur.fetchone()
    plan = (row or {}).get("plan") or DEFAULT_PLAN
    return plan if plan in PLANS else DEFAULT_PLAN


def lots_created_this_month(shop_id: int) -> int:
    with get_db_cursor() as cur:
        cur.execute(
            "SELECT COUNT(*) AS n FROM lots WHERE shop_id = %s AND created_at >= date_trunc('month', CURRENT_TIMESTAMP)",
            (shop_id,),
        )
        return cur.fetchone()["n"]


def require_feature(shop_id: int, feature: str) -> str:
    """Raise 402 unless the shop's plan includes `feature`. Returns the plan."""
    plan = get_shop_plan(shop_id)
    if not PLANS[plan].get(feature):
        label = FEATURE_LABELS.get(feature, feature)
        raise HTTPException(
            status_code=402,
            detail=f"«{label}» доступно на тарифе «Профи» и выше. Текущий тариф: «{PLANS[plan]['label']}».",
        )
    return plan


def _quota_exceeded_error(plan: str, used: int, limit: int) -> HTTPException:
    return HTTPException(
        status_code=402,
        detail=(
            f"Лимит тарифа «{PLANS[plan]['label']}» исчерпан: {used}/{limit} лотов в этом месяце. "
            "Перейдите на тариф «Профи» для безлимитной публикации."
        ),
    )


def check_lot_quota(shop_id: int):
    """Raise 402 when a basic-plan shop hits its monthly lot limit.

    NOTE: this is a best-effort pre-check (e.g. for ``plan_summary``-style
    callers). It runs its own short-lived transaction and is NOT safe against
    concurrent inserts on its own. Endpoints that actually create lots must
    wrap the check + insert in :func:`lot_quota_guard` instead.
    """
    plan = get_shop_plan(shop_id)
    limit: Optional[int] = PLANS[plan].get("monthly_lot_limit")
    if limit is None:
        return
    used = lots_created_this_month(shop_id)
    if used >= limit:
        raise _quota_exceeded_error(plan, used, limit)


@contextmanager
def lot_quota_guard(shop_id: int):
    """Serialize quota-check + insert per shop so concurrent creates can't exceed the limit.

    ``get_db_cursor`` opens a fresh connection that commits and closes per
    ``with`` block, so a lock taken inside ``check_lot_quota`` would release
    before the route's ``db.create_lot`` insert (which runs in its own
    connection). This guard instead owns a dedicated connection and holds a
    transaction-scoped advisory lock keyed on the shop across BOTH the COUNT
    and the caller's insert: a concurrent same-shop request blocks on the lock
    until this transaction commits, by which point the first request's row is
    already committed (in its own connection) and visible to the COUNT.
    """
    plan = get_shop_plan(shop_id)
    limit: Optional[int] = PLANS[plan].get("monthly_lot_limit")
    if limit is None:
        # Unlimited plan: nothing to serialize, skip the lock/connection cost.
        yield
        return

    conn = get_conn()
    try:
        cur = conn.cursor(cursor_factory=RealDictCursor)
        # Transaction-scoped advisory lock: auto-released on commit/rollback.
        cur.execute("SELECT pg_advisory_xact_lock(%s, %s)", (_LOT_QUOTA_LOCK_NS, shop_id))
        cur.execute(
            "SELECT COUNT(*) AS n FROM lots WHERE shop_id = %s AND created_at >= date_trunc('month', CURRENT_TIMESTAMP)",
            (shop_id,),
        )
        used = cur.fetchone()["n"]
        if used >= limit:
            raise _quota_exceeded_error(plan, used, limit)
        # Caller's insert (db.create_lot) runs here, in its own connection, and
        # commits before we reach our commit below — so the lock is still held.
        yield
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


def plan_summary(shop_id: int) -> dict:
    """Plan + usage payload for the shop dashboard."""
    plan = get_shop_plan(shop_id)
    features = PLANS[plan]
    limit = features.get("monthly_lot_limit")
    return {
        "plan": plan,
        "label": features["label"],
        "ocr": features["ocr"],
        "esg": features["esg"],
        "api": features["api"],
        "monthly_lot_limit": limit,
        "lots_used_this_month": lots_created_this_month(shop_id),
    }
