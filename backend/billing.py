"""Tariff plans and feature gating for shops (SaaS monetization).

Plans live in shops.plan ('basic' | 'pro' | 'enterprise'); payment itself is
handled out-of-band for now (admin flips the plan after an invoice is paid),
so this module is only about entitlements:

- basic:      manual lots only, 20 lots per calendar month
- pro:        OCR receipt parsing, ESG reports, unlimited lots
- enterprise: everything in pro (API connectors are sold/managed manually)
"""
from typing import Optional

from fastapi import HTTPException

from backend.database import get_db_cursor

DEFAULT_PLAN = "basic"

PLANS = {
    "basic": {
        "label": "Базовый",
        "monthly_lot_limit": 20,
        "ocr": False,
        "esg": False,
    },
    "pro": {
        "label": "Профи",
        "monthly_lot_limit": None,
        "ocr": True,
        "esg": True,
    },
    "enterprise": {
        "label": "Enterprise",
        "monthly_lot_limit": None,
        "ocr": True,
        "esg": True,
    },
}

# Human-readable feature names for upgrade error messages.
FEATURE_LABELS = {
    "ocr": "Распознавание чеков (OCR)",
    "esg": "ESG-отчёты",
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


def check_lot_quota(shop_id: int):
    """Raise 402 when a basic-plan shop hits its monthly lot limit."""
    plan = get_shop_plan(shop_id)
    limit: Optional[int] = PLANS[plan].get("monthly_lot_limit")
    if limit is None:
        return
    used = lots_created_this_month(shop_id)
    if used >= limit:
        raise HTTPException(
            status_code=402,
            detail=(
                f"Лимит тарифа «{PLANS[plan]['label']}» исчерпан: {used}/{limit} лотов в этом месяце. "
                "Перейдите на тариф «Профи» для безлимитной публикации."
            ),
        )


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
        "monthly_lot_limit": limit,
        "lots_used_this_month": lots_created_this_month(shop_id),
    }
